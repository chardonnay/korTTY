package de.kortty.core.remote.search;

import java.util.Objects;

/**
 * One match of a recursive remote search.
 *
 * @param path the absolute remote path
 * @param relativePath the path below the search root, shown in the results list
 * @param name the last path segment
 * @param kind what the entry is; {@link Kind#UNKNOWN} when {@code find} found it (it prints only names)
 */
public record RemoteSearchHit(String path, String relativePath, String name, Kind kind) {

    /** The type of a hit, as far as the strategy knows it. */
    public enum Kind { FILE, DIRECTORY, SYMLINK, OTHER, UNKNOWN }

    public RemoteSearchHit {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(relativePath, "relativePath");
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(kind, "kind");
    }

    /** A hit below {@code root} (already normalized, see {@link FindCommandBuilder#normalizeRoot}). */
    static RemoteSearchHit of(String root, String path, Kind kind) {
        String relative;
        if ("/".equals(root)) {
            relative = path.startsWith("/") ? path.substring(1) : path;
        } else if (path.startsWith(root + "/")) {
            relative = path.substring(root.length() + 1);
        } else {
            relative = path;
        }
        int slash = path.lastIndexOf('/');
        String name = slash >= 0 ? path.substring(slash + 1) : path;
        return new RemoteSearchHit(path, relative, name, kind);
    }

    /** The folder that holds this entry. */
    public String parentPath() {
        int slash = path.lastIndexOf('/');
        return slash <= 0 ? "/" : path.substring(0, slash);
    }
}
