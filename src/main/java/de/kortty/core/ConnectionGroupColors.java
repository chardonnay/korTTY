package de.kortty.core;

import de.kortty.model.GroupPath;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

/**
 * Tab colors of connection groups (the folders of the Connection Manager), stored in the global
 * settings by group path ({@code Work/Production}). A connection without a tab color of its own
 * takes the color of its group; a group without one takes the color of the nearest group above it
 * that has one, so coloring {@code Production} also marks {@code Production/DB}. There are no
 * default colors. Group paths are compared after {@link GroupPath} has trimmed their segments, and
 * case matters, as it does in the Connection Manager's tree. Pure: no JavaFX.
 */
public final class ConnectionGroupColors {

    /** The color a connection takes from its group, and the group that sets it ({@code Work/Production}). */
    public record Inherited(String hex, String groupPath) {
    }

    private ConnectionGroupColors() {
    }

    /**
     * {@code group} as the key its color is stored under: the segments trimmed and joined with
     * {@code /}. {@code null} for no group (blank, only separators), which never has a color.
     */
    public static String key(String group) {
        if (group == null) {
            return null;
        }
        String path = new GroupPath(group).getPath();
        return path.isEmpty() ? null : path;
    }

    /**
     * The color a connection in {@code group} takes from its groups: the group's own color, else that
     * of the nearest group above it with one; {@code null} when none of them has a color or the
     * connection is in no group. A stored value that is not a hex color counts as no color, so the
     * search goes on to the next group up.
     *
     * @param colorOfGroup the stored color of the group with the given key, or {@code null}
     */
    public static Inherited inherited(String group, Function<String, String> colorOfGroup) {
        String key = key(group);
        if (key == null || colorOfGroup == null) {
            return null;
        }
        GroupPath path = new GroupPath(key);
        while (path != null && !path.isRoot()) {
            String color = ConnectionColorSupport.normalizeHex(colorOfGroup.apply(path.getPath()));
            if (color != null) {
                return new Inherited(color, path.getPath());
            }
            path = path.getParent();
        }
        return null;
    }

    /**
     * The color {@code group} shows without a color of its own: the color of the nearest group
     * above it with one, or {@code null}. What the group color dialog names when the group has none.
     */
    public static Inherited inheritedFromAbove(String group, Function<String, String> colorOfGroup) {
        String key = key(group);
        if (key == null) {
            return null;
        }
        GroupPath parent = new GroupPath(key).getParent();
        return parent == null || parent.isRoot() ? null : inherited(parent.getPath(), colorOfGroup);
    }

    /**
     * The colors after the group {@code oldPath} was renamed to {@code newPath}: the colors of the
     * group and of every group below it move along ({@code Prod/DB} becomes {@code Live/DB} when
     * {@code Prod} is renamed to {@code Live}). Where a group of that name already has a color of its
     * own, as when the rename merges two groups, that color stays and the moved one is dropped, so the
     * connections already in that group keep their color. Other groups are untouched.
     */
    public static Map<String, String> renamed(Map<String, String> colors, String oldPath, String newPath) {
        Map<String, String> result = copyOf(colors);
        String from = key(oldPath);
        String to = key(newPath);
        if (from == null || to == null || from.equals(to)) {
            return result;
        }
        Map<String, String> moved = new LinkedHashMap<>();
        result.entrySet().removeIf(entry -> {
            String suffix = suffixBelow(entry.getKey(), from);
            if (suffix == null) {
                return false;
            }
            moved.put(to + suffix, entry.getValue());
            return true;
        });
        moved.forEach(result::putIfAbsent);
        return result;
    }

    /**
     * The colors after the group {@code path} was deleted together with its connections: the colors
     * of the group and of every group below it are dropped, so a new group of the same name starts
     * without one. Other groups are untouched.
     */
    public static Map<String, String> deleted(Map<String, String> colors, String path) {
        Map<String, String> result = copyOf(colors);
        String removed = key(path);
        if (removed != null) {
            result.keySet().removeIf(key -> suffixBelow(key, removed) != null);
        }
        return result;
    }

    /**
     * The colors as stored: keys made {@link #key}s and colors {@code #RRGGBB}; entries without a
     * group or with a value that is not a hex color are left out, and of two entries for the same
     * group the first counts.
     */
    public static Map<String, String> copyOf(Map<String, String> colors) {
        Map<String, String> result = new LinkedHashMap<>();
        if (colors == null) {
            return result;
        }
        colors.forEach((group, color) -> {
            String key = key(group);
            String hex = ConnectionColorSupport.normalizeHex(color);
            if (key != null && hex != null) {
                result.putIfAbsent(key, hex);
            }
        });
        return result;
    }

    /**
     * What follows {@code group} in {@code key} when {@code key} is the group itself (an empty
     * string) or a group below it ({@code /DB}); {@code null} otherwise. {@code Production2} is not
     * below {@code Production}.
     */
    private static String suffixBelow(String key, String group) {
        if (key.equals(group)) {
            return "";
        }
        return key.startsWith(group + GroupPath.SEPARATOR) ? key.substring(group.length()) : null;
    }
}
