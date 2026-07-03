package de.kortty.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Reads the live current working directory of a locally-spawned process (the shell behind a
 * {@link LocalShellTtyConnector}) directly from the operating system, so features that resolve a
 * terminal selection against the shell's filesystem (e.g. "Load as text file") use the directory
 * the shell is ACTUALLY in — not a stale spawn directory, and not a prompt that only shows the
 * folder's basename (the macOS zsh default after a {@code cd}).
 *
 * <ul>
 *   <li>Linux (and any OS exposing {@code /proc}): reads the {@code /proc/<pid>/cwd} symlink.</li>
 *   <li>macOS: shells out to {@code lsof} (there is no {@code /proc}) and parses its field output.</li>
 *   <li>Windows / unknown: unsupported — returns {@code null} so callers fall back to their
 *       prompt-derived directory.</li>
 * </ul>
 *
 * <p>Every failure mode degrades to {@code null}: this is a best-effort enrichment, never a hard
 * dependency. Callers must treat {@code null} as "unknown" and fall back accordingly.</p>
 */
public final class LocalProcessDirectory {

    private static final Logger logger = LoggerFactory.getLogger(LocalProcessDirectory.class);

    /** lsof for a single fd of a single process is tiny and fast; bound it so a wedged lsof can't hang us. */
    private static final long LSOF_TIMEOUT_MS = 1500;

    /** Fixed macOS path so a hijacked {@code PATH} cannot substitute a different "lsof". */
    private static final String MACOS_LSOF = "/usr/sbin/lsof";

    private LocalProcessDirectory() {
    }

    /**
     * The live working directory of the process with the given OS pid, or {@code null} when it
     * cannot be determined (unsupported OS, dead process, tooling missing, permission denied, …).
     */
    public static String read(long pid) {
        if (pid <= 0) {
            return null;
        }
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        try {
            if (os.contains("mac") || os.contains("darwin")) {
                return readViaLsof(pid);
            }
            Path cwdLink = Path.of("/proc", Long.toString(pid), "cwd");
            if (Files.exists(cwdLink, LinkOption.NOFOLLOW_LINKS)) {
                return readViaProc(cwdLink);
            }
        } catch (Exception e) {
            logger.debug("Could not read working directory of pid {}: {}", pid, e.getMessage());
        }
        return null;
    }

    private static String readViaProc(Path cwdLink) throws IOException {
        Path target = Files.readSymbolicLink(cwdLink);
        String path = target.toString();
        return path.isBlank() ? null : path;
    }

    private static String readViaLsof(long pid) throws IOException, InterruptedException {
        ProcessBuilder builder = new ProcessBuilder(
            MACOS_LSOF, "-a", "-d", "cwd", "-p", Long.toString(pid), "-Fn", "-w");
        Process process = builder.start();
        process.getOutputStream().close();

        // Guard against a wedged lsof: a watchdog force-kills it after the timeout, which makes the
        // blocking readAllBytes() below return EOF instead of hanging the caller indefinitely.
        Thread watchdog = new Thread(() -> {
            try {
                if (!process.waitFor(LSOF_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    process.destroyForcibly();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }, "lsof-cwd-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();

        byte[] out;
        try (InputStream in = process.getInputStream()) {
            out = in.readAllBytes();
        }
        boolean exited = process.waitFor(LSOF_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        watchdog.interrupt();
        if (!exited) {
            process.destroyForcibly();
            return null;
        }
        return parseLsofCwdOutput(new String(out, StandardCharsets.UTF_8));
    }

    /**
     * Parses {@code lsof -Fn} field output, returning the first {@code n} (name) field — the cwd
     * path — or {@code null} when absent. In field mode each field is emitted on its own line
     * prefixed by its identifying character ({@code n} for name). Package-visible for testing.
     *
     * <p>lsof escapes non-printable bytes in the name field as C-style backslash sequences, so a
     * real newline/tab inside a directory name arrives as the two literal characters {@code \n} /
     * {@code \t} (never a real line break — which is why splitting on {@code \n} stays correct).
     * The escapes are decoded back to the true bytes so the returned path matches the filesystem.</p>
     */
    static String parseLsofCwdOutput(String output) {
        if (output == null || output.isBlank()) {
            return null;
        }
        for (String line : output.split("\n", -1)) {
            if (line.startsWith("n") && line.length() > 1) {
                String path = unescapeLsofName(line.substring(1).stripTrailing());
                if (!path.isBlank()) {
                    return path;
                }
            }
        }
        return null;
    }

    /**
     * Decodes lsof's C-style escapes in a name field: {@code \n \t \r \f \b}, {@code \\}, and
     * {@code \xHH} hex byte escapes. An unrecognized escape is kept verbatim (both characters), so
     * ordinary paths — which contain no backslash in lsof output unless it was itself escaped — pass
     * through unchanged. Because lsof escapes real control bytes, decoding cannot reintroduce a line
     * break into the field.
     */
    private static String unescapeLsofName(String name) {
        if (name.indexOf('\\') < 0) {
            return name;
        }
        StringBuilder out = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            if (c != '\\' || i + 1 >= name.length()) {
                out.append(c);
                continue;
            }
            char next = name.charAt(i + 1);
            switch (next) {
                case 'n' -> { out.append('\n'); i++; }
                case 't' -> { out.append('\t'); i++; }
                case 'r' -> { out.append('\r'); i++; }
                case 'f' -> { out.append('\f'); i++; }
                case 'b' -> { out.append('\b'); i++; }
                case '\\' -> { out.append('\\'); i++; }
                case 'x' -> {
                    if (i + 3 < name.length()
                        && isHex(name.charAt(i + 2)) && isHex(name.charAt(i + 3))) {
                        out.append((char) Integer.parseInt(name.substring(i + 2, i + 4), 16));
                        i += 3;
                    } else {
                        out.append(c);
                    }
                }
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static boolean isHex(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
