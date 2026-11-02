package de.kortty.memory;

import java.util.regex.Pattern;

/**
 * The memory document a read or a write addresses: the global one, or the one of a single
 * connection. Connection ids are sanitised for file names; an id that had to change gets a short
 * hash suffix, so two ids never share a file.
 *
 * @param connectionId the connection's stable id, or {@code null} for the global scope
 */
public record MemoryScope(String connectionId) {

    static final String GLOBAL_FILE_NAME = "MEMORY.md";
    static final String CONNECTIONS_DIRECTORY_NAME = "connections";
    private static final Pattern UNSAFE_CHARACTERS = Pattern.compile("[^A-Za-z0-9-]");
    private static final int MAX_NAME_LENGTH = 80;

    public static MemoryScope global() {
        return new MemoryScope(null);
    }

    public static MemoryScope connection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            throw new IllegalArgumentException("A connection memory needs a connection id.");
        }
        return new MemoryScope(connectionId.strip());
    }

    public boolean isGlobal() {
        return connectionId == null;
    }

    /** The file's path relative to the memory directory, with {@code /} as separator. */
    public String relativePath() {
        return isGlobal()
            ? GLOBAL_FILE_NAME
            : CONNECTIONS_DIRECTORY_NAME + "/" + safeName(connectionId) + ".md";
    }

    /** A stable key for history names and state entries, such as {@code global}. */
    public String key() {
        return isGlobal() ? "global" : "connection-" + safeName(connectionId);
    }

    private static String safeName(String id) {
        String safe = UNSAFE_CHARACTERS.matcher(id).replaceAll("_");
        if (safe.equals(id) && safe.length() <= MAX_NAME_LENGTH) {
            return safe;
        }
        String prefix = safe.substring(0, Math.min(safe.length(), MAX_NAME_LENGTH - 9));
        return prefix + "-" + MemoryFormat.sha256(id).substring(0, 8);
    }
}
