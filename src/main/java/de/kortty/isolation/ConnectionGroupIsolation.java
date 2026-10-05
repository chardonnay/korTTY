package de.kortty.isolation;

import de.kortty.core.ConnectionGroupColors;
import de.kortty.model.GroupPath;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Isolation levels of connection groups (the folders of the Connection Manager), stored in the global
 * settings by group path ({@code Customers/Extern}). A connection without a level of its own takes the
 * level of its group, or that of the nearest group above it with one, so isolating {@code Customers}
 * also isolates {@code Customers/ACME}. The level belongs to the folder, not to its name: it moves when
 * the folder is renamed and is dropped when the folder is deleted, so a later folder that reuses the
 * name does not inherit it. Pure: no JavaFX.
 */
public final class ConnectionGroupIsolation {

    /** The level a connection takes from its folders, and the folder that sets it. */
    public record Inherited(IsolationLevel level, String groupPath) {
    }

    private ConnectionGroupIsolation() {
    }

    /** {@code group} as the key its level is stored under; null for no group. */
    public static String key(String group) {
        return ConnectionGroupColors.key(group);
    }

    /**
     * The level a connection in {@code group} takes from its folders: the folder's own, else that of the
     * nearest folder above it with one; null when none sets a level or the connection is in no folder.
     *
     * @param levelOfGroup the stored level of the folder with the given key, or null
     */
    public static Inherited inherited(String group, Function<String, IsolationLevel> levelOfGroup) {
        String key = key(group);
        if (key == null || levelOfGroup == null) {
            return null;
        }
        GroupPath path = new GroupPath(key);
        while (path != null && !path.isRoot()) {
            IsolationLevel level = levelOfGroup.apply(path.getPath());
            if (level != null) {
                return new Inherited(level, path.getPath());
            }
            path = path.getParent();
        }
        return null;
    }

    /** The level {@code group} shows without one of its own: that of the nearest folder above it, or null. */
    public static Inherited inheritedFromAbove(String group, Function<String, IsolationLevel> levelOfGroup) {
        String key = key(group);
        if (key == null) {
            return null;
        }
        GroupPath parent = new GroupPath(key).getParent();
        return parent == null || parent.isRoot() ? null : inherited(parent.getPath(), levelOfGroup);
    }

    /**
     * The levels after folder {@code oldPath} was renamed to {@code newPath}: the folder's level and those
     * of the folders below it move along. Where a folder of the new name already has a level, the stricter
     * of the two stays, so a merge never relaxes a connection that was isolated before.
     */
    public static Map<String, IsolationLevel> renamed(Map<String, IsolationLevel> levels, String oldPath,
                                                      String newPath) {
        Map<String, IsolationLevel> result = copyOf(levels);
        String from = key(oldPath);
        String to = key(newPath);
        if (from == null || from.equals(to)) {
            return result;
        }
        if (to == null) {
            return deleted(result, from);
        }
        Map<String, IsolationLevel> moved = new LinkedHashMap<>();
        result.entrySet().removeIf(entry -> {
            String suffix = suffixBelow(entry.getKey(), from);
            if (suffix == null) {
                return false;
            }
            moved.put(to + suffix, entry.getValue());
            return true;
        });
        moved.forEach((path, level) -> result.merge(path, level, IsolationLevel::mostRestrictive));
        return result;
    }

    /** The levels after folder {@code path} was deleted with its connections: its level and those below it go. */
    public static Map<String, IsolationLevel> deleted(Map<String, IsolationLevel> levels, String path) {
        Map<String, IsolationLevel> result = copyOf(levels);
        String removed = key(path);
        if (removed != null) {
            result.keySet().removeIf(key -> suffixBelow(key, removed) != null);
        }
        return result;
    }

    /** The levels as stored: keys made {@link #key}s, entries without a folder or level left out, first one wins. */
    public static Map<String, IsolationLevel> copyOf(Map<String, IsolationLevel> levels) {
        Map<String, IsolationLevel> result = new LinkedHashMap<>();
        if (levels == null) {
            return result;
        }
        levels.forEach((group, level) -> {
            String key = key(group);
            if (key != null && level != null) {
                result.putIfAbsent(key, level);
            }
        });
        return result;
    }

    private static String suffixBelow(String key, String group) {
        if (key.equals(group)) {
            return "";
        }
        return key.startsWith(group + GroupPath.SEPARATOR) ? key.substring(group.length()) : null;
    }
}
