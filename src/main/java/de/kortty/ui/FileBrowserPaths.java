package de.kortty.ui;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Pure path helpers shared by the local file browser sidebar and the SFTP manager:
 * home-relative abbreviation, tilde expansion, conflict-free destination names,
 * filter matching and POSIX shell quoting.
 */
final class FileBrowserPaths {

    private FileBrowserPaths() {
    }

    /** Renders {@code path} with the user's home directory abbreviated to {@code ~}. */
    static String abbreviateHome(Path path, Path home) {
        if (path == null) {
            return "";
        }
        Path normalized = path.toAbsolutePath().normalize();
        if (home != null) {
            Path normalizedHome = home.toAbsolutePath().normalize();
            if (normalized.equals(normalizedHome)) {
                return "~";
            }
            if (normalized.startsWith(normalizedHome)) {
                return "~/" + normalizedHome.relativize(normalized);
            }
        }
        return normalized.toString();
    }

    /** Abbreviates a remote absolute path string against a remote home directory string. */
    static String abbreviateRemote(String path, String remoteHome) {
        if (path == null || path.isBlank()) {
            return "";
        }
        if (remoteHome == null || remoteHome.isBlank() || "/".equals(remoteHome)) {
            return path;
        }
        String home = remoteHome.endsWith("/") ? remoteHome.substring(0, remoteHome.length() - 1) : remoteHome;
        if (path.equals(home)) {
            return "~";
        }
        if (path.startsWith(home + "/")) {
            return "~" + path.substring(home.length());
        }
        return path;
    }

    /** Expands a leading {@code ~} against {@code home}; other input is parsed as-is. */
    static Path expandHome(String input, Path home) {
        String trimmed = input == null ? "" : input.trim();
        if (trimmed.isEmpty()) {
            return home;
        }
        if ("~".equals(trimmed)) {
            return home;
        }
        if (trimmed.startsWith("~/")) {
            return home.resolve(trimmed.substring(2));
        }
        return Paths.get(trimmed);
    }

    /** Expands a leading {@code ~} against a remote home directory string. */
    static String expandRemoteHome(String input, String remoteHome) {
        String trimmed = input == null ? "" : input.trim();
        String home = remoteHome == null || remoteHome.isBlank() ? "/" : remoteHome;
        if (trimmed.isEmpty() || "~".equals(trimmed)) {
            return home;
        }
        if (trimmed.startsWith("~/")) {
            return (home.endsWith("/") ? home : home + "/") + trimmed.substring(2);
        }
        return trimmed;
    }

    /**
     * Returns a destination inside {@code targetDir} that does not collide with an
     * existing entry, appending {@code " (2)"}, {@code " (3)"}, ... before the extension.
     */
    static Path uniqueDestination(Path targetDir, String fileName) {
        Path direct = targetDir.resolve(fileName);
        if (!Files.exists(direct, LinkOption.NOFOLLOW_LINKS)) {
            return direct;
        }
        String base = fileName;
        String extension = "";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            base = fileName.substring(0, dot);
            extension = fileName.substring(dot);
        }
        for (int counter = 2; ; counter++) {
            Path candidate = targetDir.resolve(base + " (" + counter + ")" + extension);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return candidate;
            }
        }
    }

    /** Case-insensitive substring match; a blank filter matches everything. */
    static boolean matchesFilter(String fileName, String filter) {
        if (filter == null || filter.isBlank()) {
            return true;
        }
        if (fileName == null) {
            return false;
        }
        return fileName.toLowerCase(java.util.Locale.ROOT)
            .contains(filter.trim().toLowerCase(java.util.Locale.ROOT));
    }

    /** Quotes a value for a POSIX shell using single quotes. */
    static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }
}
