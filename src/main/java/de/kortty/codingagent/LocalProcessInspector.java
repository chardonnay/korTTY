package de.kortty.codingagent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * Finds the coding-agent process running below a local shell without any native code: it walks
 * {@link ProcessHandle#descendants()} of the shell PID and picks the newest live descendant whose
 * executable base name — or, for a script host such as {@code node}, the path of the script it
 * runs — maps to a {@link CodingAgentKind}.
 *
 * <p>Everything here is best-effort enrichment. Every {@link ProcessHandle} call is guarded, so a
 * platform without process-tree support, a security manager, or a process that vanished mid-walk
 * degrades to {@link Optional#empty()} instead of an exception. Flatpak sandboxes and remote-client
 * shells (where the pty PID is not the real shell) are handled by {@link #sourceFor}, which never
 * touches the OS when the caller reports a foreign session.
 *
 * <p>The pure classification helpers ({@link #executableBasename}, {@link #classify}) are
 * package-private statics so they can be tested with literal strings; the live helpers are
 * exercised against real child processes.
 */
public final class LocalProcessInspector {

    private static final Logger logger = LoggerFactory.getLogger(LocalProcessInspector.class);

    /** Interpreters whose first non-flag argument (the script path) identifies the agent. */
    public static final Set<String> SCRIPT_HOSTS = Set.of("node", "bun", "deno");

    /** Distro-packaged alias of node (Debian/Ubuntu ship {@code nodejs}); treated like a script host. */
    private static final Set<String> SCRIPT_HOST_ALIASES = Set.of("nodejs");

    /** Suffixes dropped from an executable or script base name before matching, longest first. */
    private static final List<String> STRIPPED_SUFFIXES = List.of(".exe", ".cmd", ".bat", ".com", ".mjs", ".cjs", ".js");

    /** Marker returned by {@link #toAgentProcess} when the OS does not report the command path. */
    private static final String UNKNOWN_COMMAND = "?";

    public LocalProcessInspector() {
    }

    /**
     * Newest live coding-agent process among the descendants of {@code shellPid}.
     *
     * @param shellPid PID of the pane's shell; non-positive or unknown PIDs yield empty
     * @return the agent process, or empty when none is running, the PID is gone, or the process tree
     *     cannot be read; never throws
     */
    public Optional<AgentProcess> findAgentProcess(long shellPid) {
        if (shellPid <= 0) {
            return Optional.empty();
        }
        Optional<ProcessHandle> newest = newestLiveDescendant(shellPid, handle -> classifyHandle(handle).isPresent());
        if (newest.isEmpty()) {
            return Optional.empty();
        }
        ProcessHandle handle = newest.get();
        return classifyHandle(handle).flatMap(kind -> toAgentProcess(handle, kind));
    }

    /**
     * Builds the process source a monitor polls: it re-reads the shell PID on every call (the shell
     * may reconnect) and short-circuits to empty — without touching the OS — while the connector
     * reports a foreign session (Flatpak sandbox, remote-client shell) whose pty PID is not the real
     * shell.
     *
     * @param shellPid supplies the current shell PID, empty when not connected or unsupported
     * @param foreignSession true when the PID does not belong to the real (host) shell
     * @return a supplier that never throws
     */
    public Supplier<Optional<AgentProcess>> sourceFor(Supplier<OptionalLong> shellPid, BooleanSupplier foreignSession) {
        return () -> {
            try {
                if (foreignSession.getAsBoolean()) {
                    return Optional.empty();
                }
                OptionalLong pid = shellPid.get();
                if (pid == null || pid.isEmpty()) {
                    return Optional.empty();
                }
                return findAgentProcess(pid.getAsLong());
            } catch (RuntimeException e) {
                logger.debug("Coding-agent process source failed: {}", e.toString());
                return Optional.empty();
            }
        };
    }

    // ---- Pure helpers ---------------------------------------------------------------------------

    /**
     * Lower-case file name of {@code path} without directories (both separators) and without a
     * trailing {@code .exe/.cmd/.bat/.com} or {@code .js/.mjs/.cjs} extension; {@code ""} for
     * null/blank input.
     */
    static String executableBasename(String path) {
        if (path == null || path.isBlank()) {
            return "";
        }
        String name = path.trim();
        int cut = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (cut >= 0) {
            name = name.substring(cut + 1);
        }
        name = name.toLowerCase(Locale.ROOT);
        for (String suffix : STRIPPED_SUFFIXES) {
            if (name.length() > suffix.length() && name.endsWith(suffix)) {
                name = name.substring(0, name.length() - suffix.length());
                break;
            }
        }
        return name;
    }

    /**
     * Classifies a process by its executable and, for script hosts, by the script it runs.
     *
     * <p>Rules, in order: (1) the executable's base name is a known agent name; (2) the executable is
     * a script host ({@link #SCRIPT_HOSTS} or {@code nodejs}) and the first non-flag argument —
     * taken from {@code arguments}, or from the whitespace tokens of {@code commandLine} when the
     * platform does not report arguments — has a base name (extension stripped) or any path segment
     * equal to a known executable name or kind id (npm installs run {@code cli.js} from a directory
     * named {@code claude-code}).
     *
     * @param command executable path as reported by {@link ProcessHandle.Info#command()}; when
     *     null the first token of {@code commandLine} is used
     * @param arguments process arguments, may be null or empty
     * @param commandLine full command line, may be null
     * @return the kind, or empty for shells, unrelated scripts and null input; never throws
     */
    static Optional<CodingAgentKind> classify(String command, List<String> arguments, String commandLine) {
        List<String> commandLineTokens = tokens(commandLine);
        String executable = command;
        if ((executable == null || executable.isBlank()) && !commandLineTokens.isEmpty()) {
            executable = commandLineTokens.get(0);
        }
        String basename = executableBasename(executable);
        if (basename.isEmpty()) {
            return Optional.empty();
        }
        Optional<CodingAgentKind> direct = CodingAgentKind.forExecutable(basename);
        if (direct.isPresent()) {
            return direct;
        }
        if (!SCRIPT_HOSTS.contains(basename) && !SCRIPT_HOST_ALIASES.contains(basename)) {
            return Optional.empty();
        }
        Optional<String> script = firstNonFlag(arguments == null ? List.of() : arguments);
        if (script.isEmpty() && !commandLineTokens.isEmpty()) {
            script = firstNonFlag(commandLineTokens.subList(1, commandLineTokens.size()));
        }
        return script.flatMap(LocalProcessInspector::classifyScriptPath);
    }

    /** Newest live descendant of {@code pid} accepted by {@code accept}; newest = latest start instant (missing sorts last), ties broken by the higher PID. */
    static Optional<ProcessHandle> newestLiveDescendant(long pid, Predicate<ProcessHandle> accept) {
        try {
            Optional<ProcessHandle> root = ProcessHandle.of(pid);
            if (root.isEmpty()) {
                return Optional.empty();
            }
            try (Stream<ProcessHandle> descendants = root.get().descendants()) {
                return descendants
                    .filter(handle -> isLiveAndAccepted(handle, accept))
                    .max(LocalProcessInspector::compareByStartThenPid);
            }
        } catch (RuntimeException e) {
            // UnsupportedOperationException, SecurityException, or a process that vanished mid-walk.
            logger.debug("Cannot walk descendants of pid {}: {}", pid, e.toString());
            return Optional.empty();
        }
    }

    /** Snapshot of {@code handle} as an {@link AgentProcess}; command falls back to {@code "?"}, startedAt to null. */
    static Optional<AgentProcess> toAgentProcess(ProcessHandle handle, CodingAgentKind kind) {
        if (handle == null || kind == null) {
            return Optional.empty();
        }
        try {
            ProcessHandle.Info info = handle.info();
            String command = info.command().orElse(UNKNOWN_COMMAND);
            Instant startedAt = info.startInstant().orElse(null);
            return Optional.of(new AgentProcess(handle.pid(), kind, command, startedAt));
        } catch (RuntimeException e) {
            logger.debug("Cannot read process info: {}", e.toString());
            return Optional.empty();
        }
    }

    // ---- Private ------------------------------------------------------------------------------

    private static Optional<CodingAgentKind> classifyHandle(ProcessHandle handle) {
        try {
            ProcessHandle.Info info = handle.info();
            List<String> arguments = info.arguments().map(List::of).orElse(List.of());
            return classify(info.command().orElse(null), arguments, info.commandLine().orElse(null));
        } catch (RuntimeException e) {
            logger.debug("Cannot classify process: {}", e.toString());
            return Optional.empty();
        }
    }

    private static boolean isLiveAndAccepted(ProcessHandle handle, Predicate<ProcessHandle> accept) {
        try {
            return handle.isAlive() && accept.test(handle);
        } catch (RuntimeException e) {
            logger.debug("Skipping process {}: {}", handle.pid(), e.toString());
            return false;
        }
    }

    private static int compareByStartThenPid(ProcessHandle a, ProcessHandle b) {
        Optional<Instant> startA = startInstant(a);
        Optional<Instant> startB = startInstant(b);
        if (startA.isPresent() && startB.isPresent()) {
            int byStart = startA.get().compareTo(startB.get());
            if (byStart != 0) {
                return byStart;
            }
        } else if (startA.isPresent()) {
            return 1;
        } else if (startB.isPresent()) {
            return -1;
        }
        return Long.compare(a.pid(), b.pid());
    }

    private static Optional<Instant> startInstant(ProcessHandle handle) {
        try {
            return handle.info().startInstant();
        } catch (RuntimeException e) {
            return Optional.empty();
        }
    }

    private static Optional<CodingAgentKind> classifyScriptPath(String script) {
        Optional<CodingAgentKind> byName = CodingAgentKind.forExecutable(executableBasename(script));
        if (byName.isPresent()) {
            return byName;
        }
        for (String segment : script.split("[/\\\\]+")) {
            String name = segment.toLowerCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            Optional<CodingAgentKind> kind = CodingAgentKind.forExecutable(name).or(() -> CodingAgentKind.forId(name));
            if (kind.isPresent()) {
                return kind;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> firstNonFlag(List<String> arguments) {
        for (String argument : arguments) {
            if (argument != null && !argument.isBlank() && !argument.startsWith("-")) {
                return Optional.of(argument);
            }
        }
        return Optional.empty();
    }

    private static List<String> tokens(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return List.of();
        }
        List<String> result = new ArrayList<>();
        for (String token : commandLine.trim().split("\\s+")) {
            if (!token.isEmpty()) {
                result.add(token);
            }
        }
        return result;
    }
}
