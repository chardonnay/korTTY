package de.kortty.core.agent;

import de.kortty.core.agent.AgentCommandRunner.ShellKind;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Builds the argv that runs one command string non-interactively in a local shell. Shared by the
 * terminal agent's local runner and the credential manager's external password command, so both
 * quote commands the same way on every platform.
 *
 * <ul>
 *   <li>{@link ShellKind#POSIX}: {@code /bin/sh -c <command>} (macOS, Linux).</li>
 *   <li>{@link ShellKind#WINDOWS_POWERSHELL}: {@code powershell.exe -NoProfile -NonInteractive
 *       -EncodedCommand <base64>}. The script is Base64 UTF-16LE, so quotes and newlines survive
 *       Windows' command-line argument quoting, and it is prefixed with
 *       {@link #POWERSHELL_UTF8_PREFIX} so captured stdout decodes as UTF-8 whatever the console
 *       code page is.</li>
 *   <li>{@link ShellKind#WINDOWS_CMD}: {@code cmd.exe /c <command>}.</li>
 * </ul>
 */
public final class LocalShellArgv {

    /** Forces UTF-8 stdout in PowerShell, regardless of the console code page. */
    public static final String POWERSHELL_UTF8_PREFIX = "[Console]::OutputEncoding=[System.Text.Encoding]::UTF8;";

    private LocalShellArgv() {
    }

    /**
     * The shell for a command with no configured shell: PowerShell on Windows (its
     * {@code -EncodedCommand} survives argv quoting), {@code /bin/sh} everywhere else.
     */
    public static ShellKind platformDefault() {
        return platformDefault(System.getProperty("os.name", ""));
    }

    static ShellKind platformDefault(String osName) {
        return isWindows(osName) ? ShellKind.WINDOWS_POWERSHELL : ShellKind.POSIX;
    }

    /** True when {@code os.name} names Windows. */
    public static boolean isWindows(String osName) {
        return osName != null && osName.toLowerCase(Locale.ROOT).contains("win");
    }

    /**
     * Returns the argv that runs {@code command} in the given shell. A {@code null} kind means
     * POSIX. The returned list is unmodifiable.
     */
    public static List<String> argv(ShellKind kind, String command) {
        Objects.requireNonNull(command, "command");
        if (kind == null) {
            kind = ShellKind.POSIX;
        }
        return switch (kind) {
            case WINDOWS_POWERSHELL -> List.of(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-EncodedCommand",
                encodePowerShell(POWERSHELL_UTF8_PREFIX + command));
            case WINDOWS_CMD -> List.of("cmd.exe", "/c", command);
            case POSIX -> List.of("/bin/sh", "-c", command);
        };
    }

    /** Base64 of the script's UTF-16LE bytes, the encoding {@code -EncodedCommand} expects. */
    public static String encodePowerShell(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }
}
