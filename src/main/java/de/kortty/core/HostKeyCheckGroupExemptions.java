package de.kortty.core;

import de.kortty.model.GroupPath;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The connection groups (the folders of the Connection Manager) whose SSH host-key verification is
 * relaxed to accept-new, as stored in the global settings by group path ({@code Work/Lab}), kept in
 * step when a folder is renamed or deleted. An exemption belongs to a folder, not to a name: left
 * behind under an old name, it would silently turn verification off for every connection of a
 * later folder that reuses the name. Group paths are compared after {@link GroupPath} has trimmed
 * their segments, and case matters, as it does in the Connection Manager's tree; entries that are
 * not touched are kept exactly as stored. Pure: no JavaFX.
 */
public final class HostKeyCheckGroupExemptions {

    private HostKeyCheckGroupExemptions() {
    }

    /**
     * The exemptions after the group {@code oldPath} was renamed to {@code newPath}. The group and
     * every group below it move along ({@code Lab/DB} becomes {@code Test/DB} when {@code Lab} is
     * renamed to {@code Test}), and each takes its own state with it: an exempt group stays exempt
     * under its new path, and a group with verification on keeps it on, even where an exemption was
     * left for the new path by a group that no longer exists. Where the rename merges a group into
     * one that still has connections of its own, verification stays off only if it was off in both
     * and is on otherwise, so a merge never relaxes a connection that was verified before. Exemptions
     * of groups below {@code oldPath} that have no connections are dropped; all others are kept.
     *
     * @param exempt the stored exemptions
     * @param groupsBefore the group of every connection, placeholders included, before the rename; a
     *     group exists while one of them is that group or a group below it
     */
    public static List<String> renamed(
            Collection<String> exempt, String oldPath, String newPath, Collection<String> groupsBefore) {
        List<String> stored = exempt == null ? List.of() : new ArrayList<>(exempt);
        String from = key(oldPath);
        String to = key(newPath);
        if (from == null || from.equals(to)) {
            return new ArrayList<>(stored);
        }
        if (to == null) {
            // A name of only separators leaves the connections in no group, which is never exempt.
            return deleted(stored, from);
        }
        Collection<String> groups = groupsBefore == null ? List.of() : groupsBefore;
        Set<String> exemptKeys = new HashSet<>();
        for (String entry : stored) {
            String key = key(entry);
            if (key != null) {
                exemptKeys.add(key);
            }
        }

        // The renamed group always moves, and so does every group below it that has connections.
        Set<String> movedSuffixes = new LinkedHashSet<>();
        movedSuffixes.add("");
        for (String group : groups) {
            String suffix = suffixBelow(key(group), from);
            if (suffix == null) {
                continue;
            }
            for (int slash = suffix.indexOf(GroupPath.SEPARATOR, 1); slash > 0;
                    slash = suffix.indexOf(GroupPath.SEPARATOR, slash + 1)) {
                movedSuffixes.add(suffix.substring(0, slash));
            }
            movedSuffixes.add(suffix);
        }

        Set<String> decided = new HashSet<>();
        List<String> exemptAfter = new ArrayList<>();
        for (String suffix : movedSuffixes) {
            String target = to + suffix;
            boolean merged = hasConnectionsStaying(target, from, groups);
            boolean exemptNow = exemptKeys.contains(from + suffix) && (!merged || exemptKeys.contains(target));
            decided.add(target);
            if (exemptNow) {
                exemptAfter.add(target);
            }
        }

        List<String> result = new ArrayList<>();
        for (String entry : stored) {
            String key = key(entry);
            if (key != null && (suffixBelow(key, from) != null || decided.contains(key))) {
                continue;
            }
            result.add(entry);
        }
        result.addAll(exemptAfter);
        return result;
    }

    /**
     * The exemptions after the group {@code path} was deleted together with its connections: the
     * exemptions of the group and of every group below it are dropped, so a new group of the same
     * name starts with verification on. Other groups are untouched.
     */
    public static List<String> deleted(Collection<String> exempt, String path) {
        List<String> result = new ArrayList<>();
        if (exempt == null) {
            return result;
        }
        String removed = key(path);
        for (String entry : exempt) {
            if (removed == null || suffixBelow(key(entry), removed) == null) {
                result.add(entry);
            }
        }
        return result;
    }

    /**
     * {@code group} as a {@link GroupPath}: the segments trimmed and joined with {@code /}. {@code
     * null} for no group (blank, only separators).
     */
    static String key(String group) {
        if (group == null) {
            return null;
        }
        String path = new GroupPath(group).getPath();
        return path.isEmpty() ? null : path;
    }

    /**
     * Whether {@code group} keeps connections when {@code moving} and every group below it move away:
     * a connection in {@code group} or below it that is not in {@code moving} or below it.
     */
    private static boolean hasConnectionsStaying(String group, String moving, Collection<String> groups) {
        for (String connectionGroup : groups) {
            String key = key(connectionGroup);
            if (suffixBelow(key, group) != null && suffixBelow(key, moving) == null) {
                return true;
            }
        }
        return false;
    }

    /**
     * What follows {@code group} in {@code key} when {@code key} is the group itself (an empty
     * string) or a group below it ({@code /DB}); {@code null} otherwise, and for a {@code null} key.
     * {@code Lab2} is not below {@code Lab}.
     */
    private static String suffixBelow(String key, String group) {
        if (key == null) {
            return null;
        }
        if (key.equals(group)) {
            return "";
        }
        return key.startsWith(group + GroupPath.SEPARATOR) ? key.substring(group.length()) : null;
    }
}
