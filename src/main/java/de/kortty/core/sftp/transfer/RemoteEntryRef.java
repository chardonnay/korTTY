package de.kortty.core.sftp.transfer;

import java.util.Objects;

/**
 * A remote entry picked for download, as the listing showed it. The transfer engine re-checks the
 * entry on the server before it acts; the type here only decides whether the entry is walked as a
 * folder.
 *
 * @param path        the entry's absolute remote path
 * @param name        its name (the last path segment)
 * @param type        what the listing showed
 * @param size        its size in bytes, or {@code -1} when unknown
 * @param mtimeMillis its modification time, or {@code null} when unknown
 */
public record RemoteEntryRef(String path, String name, Type type, long size, Long mtimeMillis) {

    /** What the listing showed. A symbolic link is resolved when the item runs. */
    public enum Type {
        FILE,
        DIRECTORY,
        SYMLINK,
        OTHER
    }

    public RemoteEntryRef {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(type, "type");
        if (name == null || name.isEmpty()) {
            name = lastSegment(path);
        }
        size = size < 0 ? -1 : size;
    }

    /** A regular file of unknown modification time. */
    public static RemoteEntryRef file(String path, long size) {
        return new RemoteEntryRef(path, null, Type.FILE, size, null);
    }

    /** A folder. */
    public static RemoteEntryRef directory(String path) {
        return new RemoteEntryRef(path, null, Type.DIRECTORY, -1, null);
    }

    private static String lastSegment(String path) {
        String trimmed = path;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = trimmed.lastIndexOf('/');
        return slash < 0 ? trimmed : trimmed.substring(slash + 1);
    }
}
