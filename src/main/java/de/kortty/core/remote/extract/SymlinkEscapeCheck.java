package de.kortty.core.remote.extract;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Decides on the client whether a symlink below the staging folder resolves outside it, from the
 * links and targets the server listed. Resolution follows other listed links on the way, the way
 * the kernel would, so {@code a -> x/up/../..} with {@code x/up -> ..} is caught although its text
 * alone looks harmless. An absolute target always counts as an escape (after the rename it could
 * only point at the old staging path or somewhere else entirely).
 */
public final class SymlinkEscapeCheck {

    /** The kernel's limit on links followed while resolving one path. */
    private static final int MAX_HOPS = 40;

    private SymlinkEscapeCheck() {
    }

    /**
     * @param root the staging folder (absolute, without trailing slash)
     * @param links absolute link path to its target text, as {@code readlink} printed it
     * @return the first link (relative to {@code root}) that escapes, or empty when all stay inside
     */
    public static Optional<String> firstEscape(String root, Map<String, String> links) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(links, "links");
        String prefix = root.endsWith("/") ? root : root + "/";
        Map<String, String> relative = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, String> link : links.entrySet()) {
            String path = link.getKey();
            if (!path.startsWith(prefix) || path.length() == prefix.length()) {
                return Optional.of(path);
            }
            relative.put(String.join("/", segments(path.substring(prefix.length()))), link.getValue());
        }
        for (Map.Entry<String, String> link : relative.entrySet()) {
            if (escapes(link.getKey(), relative)) {
                return Optional.of(link.getKey());
            }
        }
        return Optional.empty();
    }

    private static boolean escapes(String link, Map<String, String> links) {
        List<String> current = segments(link);
        current.remove(current.size() - 1);
        String target = links.get(link);
        if (target == null || target.isEmpty() || target.startsWith("/")) {
            return true;
        }
        Deque<String> todo = new ArrayDeque<>(segments(target));
        int hops = 0;
        while (!todo.isEmpty()) {
            String segment = todo.pollFirst();
            if ("..".equals(segment)) {
                if (current.isEmpty()) {
                    return true;
                }
                current.remove(current.size() - 1);
                continue;
            }
            current.add(segment);
            String next = links.get(String.join("/", current));
            if (next != null) {
                if (++hops > MAX_HOPS) {
                    // a loop: the kernel refuses it with ELOOP, so it reaches nothing
                    return false;
                }
                if (next.isEmpty() || next.startsWith("/")) {
                    return true;
                }
                current.remove(current.size() - 1);
                List<String> replacement = segments(next);
                for (int i = replacement.size() - 1; i >= 0; i--) {
                    todo.addFirst(replacement.get(i));
                }
            }
        }
        return false;
    }

    private static List<String> segments(String path) {
        List<String> result = new ArrayList<>();
        for (String segment : path.split("/")) {
            if (!segment.isEmpty() && !".".equals(segment)) {
                result.add(segment);
            }
        }
        return result;
    }
}
