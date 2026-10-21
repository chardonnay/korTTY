package de.kortty.core.agent;

import de.kortty.core.agent.AgentCommandRunner.ShellKind;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    private static final Pattern NESTED_POWERSHELL = Pattern.compile(
        "(?is)^(?:&\\s*)?['\"]?(?:powershell|pwsh)(?:\\.exe)?['\"]?"
            + "(?:\\s+-(?:NoProfile|NoLogo|NonInteractive|nop|noni|ExecutionPolicy\\s+\\S+|ep\\s+\\S+))*"
            + "\\s+-(?:Command|c)\\s+(.+)$");

    /**
     * Strips a redundant {@code powershell -Command "<script>"} (or {@code pwsh -c ...}) wrapper
     * from a command that is about to run in PowerShell anyway. Without this the outer PowerShell
     * expands {@code $variables} inside the double-quoted script to empty strings before the inner
     * process sees it, so {@code powershell -Command "$c = Get-Content 'f'"} fails with exit 1.
     * Commands that are not such a wrapper are returned unchanged.
     */
    public static String unwrapNestedPowerShell(String command) {
        if (command == null) {
            return null;
        }
        Matcher matcher = NESTED_POWERSHELL.matcher(command.strip());
        if (!matcher.matches()) {
            return command;
        }
        String script = matcher.group(1).strip();
        if (script.length() >= 2 && script.startsWith("\"") && script.endsWith("\"")) {
            String inner = script.substring(1, script.length() - 1);
            return inner.replace("\\\"", "\"").replace("`\"", "\"").replace("`$", "$");
        }
        if (script.length() >= 2 && script.startsWith("'") && script.endsWith("'")) {
            return script.substring(1, script.length() - 1).replace("''", "'");
        }
        if (script.length() >= 2 && script.startsWith("{") && script.endsWith("}")) {
            return script.substring(1, script.length() - 1).strip();
        }
        if (script.startsWith("\"") || script.startsWith("'")) {
            return command; // unbalanced quoting: leave it to PowerShell rather than guess
        }
        return script;
    }

    /** Base64 of the script's UTF-16LE bytes, the encoding {@code -EncodedCommand} expects. */
    public static String encodePowerShell(String script) {
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }
}
