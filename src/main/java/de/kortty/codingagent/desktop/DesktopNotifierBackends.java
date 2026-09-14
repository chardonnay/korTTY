package de.kortty.codingagent.desktop;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Predicate;

/**
 * Chooses the {@link DesktopNotifierBackend} for the running platform before any backend
 * constructor runs; AWT ({@code SystemTray}) is only ever touched by the Windows backend.
 */
public final class DesktopNotifierBackends {

    /** Bounded wait for one {@code osascript} / {@code notify-send} run. */
    static final long COMMAND_TIMEOUT_MILLIS = 3_000L;

    private static final Path OSASCRIPT = Path.of("/usr/bin/osascript");
    private static final String NOTIFY_SEND = "notify-send";
    private static final String DEFAULT_PATH = "/usr/local/bin:/usr/bin:/bin";

    private DesktopNotifierBackends() {
    }

    /** Selects the backend from the live platform facts and the tools installed on this machine. */
    public static DesktopNotifierBackend createDefault(PlatformProbe probe) {
        Objects.requireNonNull(probe, "probe");
        boolean osascriptPresent = probe.isMac() && Files.isExecutable(OSASCRIPT);
        boolean notifySendPresent = probe.isLinux() && !probe.flatpak()
            && isOnPath(NOTIFY_SEND, System.getenv(), Files::isExecutable);
        return select(probe, osascriptPresent, notifySendPresent);
    }

    /**
     * Pure selection: macOS with {@code osascript} → Notification Center (packaged or not);
     * Windows unless headless → tray balloon; Linux inside Flatpak or with {@code notify-send} on
     * the PATH → {@code notify-send}; everything else → unsupported.
     */
    static DesktopNotifierBackend select(PlatformProbe probe, boolean osascriptPresent, boolean notifySendPresent) {
        Objects.requireNonNull(probe, "probe");
        if (probe.isMac()) {
            if (osascriptPresent) {
                return new MacOsascriptNotifierBackend(
                    argv -> ExternalCommandRunner.runBlocking(argv, COMMAND_TIMEOUT_MILLIS));
            }
            return new UnsupportedNotifierBackend();
        }
        if (probe.isWindows()) {
            if (!probe.awtHeadless()) {
                return new WindowsTrayNotifierBackend();
            }
            return new UnsupportedNotifierBackend();
        }
        if (probe.isLinux() && (probe.flatpak() || notifySendPresent)) {
            return new LinuxNotifySendNotifierBackend(
                NotificationCommands.linuxIcon(probe, Files::isRegularFile),
                probe.flatpak(),
                argv -> ExternalCommandRunner.runBlocking(argv, COMMAND_TIMEOUT_MILLIS));
        }
        return new UnsupportedNotifierBackend();
    }

    /**
     * Pure PATH lookup: whether {@code executable} exists (per {@code executable} predicate) in one
     * of the {@code PATH} entries of {@code env} (default {@code /usr/local/bin:/usr/bin:/bin}).
     */
    public static boolean isOnPath(String executable, Map<String, String> env, Predicate<Path> executableCheck) {
        return resolveOnPath(executable, env, executableCheck).isPresent();
    }

    /**
     * The same lookup, but handing back <em>which</em> file it found.
     *
     * <p>The caller needs the resolved path, not just the answer: handing the bare name to
     * {@code ProcessBuilder} would search {@code PATH} again at exec time, so the file that was
     * checked here and the file that actually runs need not be the same one.
     */
    public static Optional<Path> resolveOnPath(String executable, Map<String, String> env,
                                               Predicate<Path> executableCheck) {
        Objects.requireNonNull(executable, "executable");
        Objects.requireNonNull(executableCheck, "executable predicate");
        String path = env == null ? null : env.get("PATH");
        if (path == null || path.isBlank()) {
            path = DEFAULT_PATH;
        }
        for (String dir : path.split(File.pathSeparator)) {
            if (dir.isBlank()) {
                continue;
            }
            try {
                Path candidate = Path.of(dir).resolve(executable);
                if (executableCheck.test(candidate)) {
                    return Optional.of(candidate);
                }
            } catch (InvalidPathException e) {
                // Skip malformed PATH entries.
            }
        }
        return Optional.empty();
    }
}
