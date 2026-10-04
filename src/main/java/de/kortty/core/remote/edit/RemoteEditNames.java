package de.kortty.core.remote.edit;

import java.util.Locale;
import java.util.Set;

/**
 * The local name a remote file gets in its edit folder.
 *
 * <p>Remote names are chosen by whoever can write on the server, and the local copy is handed to
 * an editor through the operating system. So the name keeps only {@code [A-Za-z0-9._ -]} (anything
 * else, {@code %} included, becomes {@code _}, which keeps {@code cmd}-style {@code %VAR%}
 * expansion and shell metacharacters out of it), never ends in a dot or a space, never is a Windows
 * device name such as {@code CON} or {@code COM1} (also with an extension), never is {@code .} or
 * {@code ..}, and is at most {@value #MAX_LENGTH} characters long with its extension kept. Pure.
 */
public final class RemoteEditNames {

    /** The longest local name; the extension is kept when a name is shortened. */
    static final int MAX_LENGTH = 120;
    private static final String FALLBACK = "file";
    private static final Set<String> WINDOWS_DEVICES = Set.of(
        "CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    private RemoteEditNames() {
    }

    /** The safe local file name for the remote path or name {@code remote}. */
    public static String safeLocalName(String remote) {
        String name = remote == null ? "" : remote;
        int slash = name.lastIndexOf('/');
        if (slash >= 0) {
            name = name.substring(slash + 1);
        }
        StringBuilder mapped = new StringBuilder(name.length());
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            mapped.append(allowed(c) ? c : '_');
        }
        String safe = stripTrailingDotsAndSpaces(mapped.toString()).strip();
        if (safe.isEmpty() || safe.chars().allMatch(c -> c == '.')) {
            safe = FALLBACK;
        }
        if (isWindowsDevice(safe)) {
            safe = "_" + safe;
        }
        if (safe.length() > MAX_LENGTH) {
            safe = shorten(safe);
        }
        return safe;
    }

    private static boolean allowed(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')
            || c == '.' || c == '_' || c == ' ' || c == '-';
    }

    private static String stripTrailingDotsAndSpaces(String name) {
        int end = name.length();
        while (end > 0 && (name.charAt(end - 1) == '.' || name.charAt(end - 1) == ' ')) {
            end--;
        }
        return name.substring(0, end);
    }

    /** {@code CON}, {@code con.txt}, {@code Com1.tar.gz}: the part before the first dot is a device. */
    static boolean isWindowsDevice(String name) {
        int dot = name.indexOf('.');
        String stem = (dot >= 0 ? name.substring(0, dot) : name).strip().toUpperCase(Locale.ROOT);
        return WINDOWS_DEVICES.contains(stem);
    }

    private static String shorten(String name) {
        int dot = name.lastIndexOf('.');
        String extension = dot > 0 && name.length() - dot <= 16 ? name.substring(dot) : "";
        String stem = name.substring(0, MAX_LENGTH - extension.length());
        return stripTrailingDotsAndSpaces(stem) + extension;
    }
}
