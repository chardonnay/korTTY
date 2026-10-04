package de.kortty.ui;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.function.Predicate;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

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
                String relative = normalizedHome.relativize(normalized).toString();
                String separator = normalized.getFileSystem().getSeparator();
                return "~/" + (separator.equals("/") ? relative : relative.replace(separator, "/"));
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

    /**
     * The name filter of a search field. A blank filter matches every name. A filter with
     * {@code *}, {@code ?}, {@code [...]} or <code>{a,b}</code> is a glob over the whole name,
     * ignoring case: {@code *} is any run of characters, {@code ?} one character, {@code [abc]}
     * and {@code [a-z]} one of the listed characters ({@code [!abc]} or {@code [^abc]} one that is
     * not listed) and <code>{py,sh}</code> one of the alternatives. Any other filter, and a glob
     * that does not parse (such as an unclosed {@code [}), matches like {@link #matchesFilter}:
     * a case-insensitive substring.
     *
     * <p>The glob is compiled here once, not per name, and never goes through
     * {@code FileSystem.getPathMatcher}: remote names may contain {@code :} or {@code \}, which
     * a Windows path cannot hold.
     */
    static Predicate<String> compileNameFilter(String filter) {
        if (filter == null || filter.isBlank()) {
            return name -> true;
        }
        String trimmed = filter.trim();
        if (hasGlobSyntax(trimmed)) {
            Pattern glob = globPattern(trimmed);
            if (glob != null) {
                return name -> name != null && glob.matcher(name).matches();
            }
        }
        return name -> matchesFilter(name, trimmed);
    }

    /**
     * Whether {@link #compileNameFilter} treats {@code filter} as a glob (it has glob syntax and
     * parses); otherwise it is a substring filter.
     */
    static boolean isGlobFilter(String filter) {
        if (filter == null || filter.isBlank()) {
            return false;
        }
        String trimmed = filter.trim();
        return hasGlobSyntax(trimmed) && globPattern(trimmed) != null;
    }

    private static boolean hasGlobSyntax(String filter) {
        for (int i = 0; i < filter.length(); i++) {
            char c = filter.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{') {
                return true;
            }
        }
        return false;
    }

    /** The glob as a case-insensitive full-match pattern, or {@code null} when it does not parse. */
    private static Pattern globPattern(String glob) {
        StringBuilder regex = new StringBuilder(glob.length() * 2);
        StringBuilder literal = new StringBuilder();
        boolean inBraces = false;
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == '{' || (inBraces && (c == ',' || c == '}'))) {
                flushLiteral(regex, literal);
            }
            switch (c) {
                case '*' -> regex.append(".*");
                case '?' -> regex.append('.');
                case '[' -> {
                    int end = appendCharacterClass(glob, i, regex);
                    if (end < 0) {
                        return null;
                    }
                    i = end;
                }
                case '{' -> {
                    if (inBraces) {
                        // Nested alternatives are not supported.
                        return null;
                    }
                    inBraces = true;
                    regex.append("(?:");
                }
                case ',' -> {
                    if (inBraces) {
                        regex.append('|');
                    } else {
                        literal.append(c);
                    }
                }
                case '}' -> {
                    if (inBraces) {
                        inBraces = false;
                        regex.append(')');
                    } else {
                        literal.append(c);
                    }
                }
                default -> literal.append(c);
            }
            i++;
        }
        if (inBraces) {
            return null;
        }
        flushLiteral(regex, literal);
        try {
            return Pattern.compile(regex.toString(),
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE | Pattern.DOTALL);
        } catch (PatternSyntaxException e) {
            // For example a reversed range such as [z-a].
            return null;
        }
    }

    private static void flushLiteral(StringBuilder regex, StringBuilder literal) {
        if (!literal.isEmpty()) {
            regex.append(Pattern.quote(literal.toString()));
            literal.setLength(0);
        }
    }

    /**
     * Appends the character class that opens at {@code start} (the {@code [}) and returns the
     * index of its closing {@code ]}, or {@code -1} when it is not closed. A {@code ]} right after
     * the opening {@code [} or {@code [!} is a literal, as in a shell.
     */
    private static int appendCharacterClass(String glob, int start, StringBuilder regex) {
        int i = start + 1;
        boolean negated = i < glob.length() && (glob.charAt(i) == '!' || glob.charAt(i) == '^');
        if (negated) {
            i++;
        }
        int contentStart = i;
        StringBuilder content = new StringBuilder();
        while (i < glob.length()) {
            char c = glob.charAt(i);
            if (c == ']' && i > contentStart) {
                regex.append(negated ? "[^" : "[").append(content).append(']');
                return i;
            }
            boolean edge = i == contentStart || (i + 1 < glob.length() && glob.charAt(i + 1) == ']');
            if (c == '\\' || c == '[' || c == ']' || c == '^' || c == '&' || (c == '-' && edge)) {
                // Literal inside the class; a '-' between two characters stays a range.
                content.append('\\');
            }
            content.append(c);
            i++;
        }
        return -1;
    }

    /** Quotes a value for a POSIX shell using single quotes. */
    static String shellQuote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\\''")) + "'";
    }
}
