package de.kortty.codingagent.desktop;

import de.kortty.platform.FlatpakSupport;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Pure argv and text helpers for the notification backends — no process is started here, so every
 * command shape is unit-tested.
 */
public final class NotificationCommands {

    /** Application name shown by the OS notification services. */
    static final String APP_NAME = "korTTY";
    /** Generic icon name used when no korTTY icon is installed on Linux. */
    static final String FALLBACK_LINUX_ICON = "utilities-terminal";
    /** Notification lifetime handed to {@code notify-send}. */
    static final int NOTIFY_SEND_EXPIRE_MILLIS = 8_000;

    private static final String OSASCRIPT = "/usr/bin/osascript";
    private static final String ELLIPSIS = "…";

    private NotificationCommands() {
    }

    /**
     * The {@code osascript} argv: {@code display notification "<body>" with title "<appName>"
     * subtitle "<title>"} (the app name is the bold first line in Notification Center).
     */
    public static List<String> osascript(String appName, String title, String body) {
        String script = "display notification " + appleScriptLiteral(body)
            + " with title " + appleScriptLiteral(appName)
            + " subtitle " + appleScriptLiteral(title);
        return List.of(OSASCRIPT, "-e", script);
    }

    /**
     * Quotes {@code text} as an AppleScript string literal: backslash and double quote are escaped,
     * line breaks and tabs become spaces and every other control character is dropped.
     */
    public static String appleScriptLiteral(String text) {
        String source = text == null ? "" : text;
        StringBuilder out = new StringBuilder(source.length() + 2).append('"');
        for (int i = 0; i < source.length(); i++) {
            char c = source.charAt(i);
            if (c == '\\') {
                out.append("\\\\");
            } else if (c == '"') {
                out.append("\\\"");
            } else if (c == '\n' || c == '\r' || c == '\t') {
                out.append(' ');
            } else if (c >= 0x20 && c != 0x7f) {
                out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /**
     * The {@code notify-send} argv, wrapped with {@code flatpak-spawn --host} when {@code env}
     * carries {@code FLATPAK_ID} (the sandbox lacks the notification talk-name).
     */
    public static List<String> notifySend(String appName, String icon, String title, String body,
                                          Map<String, String> env) {
        List<String> argv = List.of(
            "notify-send",
            "--app-name=" + appName,
            "--icon=" + icon,
            "--urgency=normal",
            "--expire-time=" + NOTIFY_SEND_EXPIRE_MILLIS,
            title == null ? "" : title,
            body == null ? "" : body);
        return ExternalCommandRunner.hostAware(argv, env);
    }

    /**
     * The icon argument for {@code notify-send}: the Flatpak app id inside the sandbox, the
     * installed {@code lib/korTTY.png} next to the jpackage launcher when present, otherwise the
     * generic {@code utilities-terminal} theme icon.
     */
    public static String linuxIcon(PlatformProbe probe, Predicate<Path> exists) {
        Objects.requireNonNull(probe, "probe");
        Objects.requireNonNull(exists, "exists");
        if (probe.flatpak()) {
            return FlatpakSupport.APP_ID;
        }
        if (probe.isPackaged()) {
            try {
                Path launcher = Path.of(probe.jpackageAppPath()).toAbsolutePath();
                Path binDir = launcher.getParent();
                if (binDir != null) {
                    Path icon = binDir.resolve("..").resolve("lib").resolve("korTTY.png").normalize();
                    if (exists.test(icon)) {
                        return icon.toString();
                    }
                }
            } catch (RuntimeException e) {
                // An unusable jpackage path falls back to the theme icon.
            }
        }
        return FALLBACK_LINUX_ICON;
    }

    /** Cuts {@code body} to {@code maxChars} characters, ending a shortened text with an ellipsis. */
    public static String truncate(String body, int maxChars) {
        String text = body == null ? "" : body;
        if (maxChars <= 0) {
            return "";
        }
        if (text.length() <= maxChars) {
            return text;
        }
        if (maxChars == 1) {
            return ELLIPSIS;
        }
        int cut = maxChars - 1;
        if (Character.isHighSurrogate(text.charAt(cut - 1))) {
            cut--;
        }
        return text.substring(0, cut) + ELLIPSIS;
    }
}
