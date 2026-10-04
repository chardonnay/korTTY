package de.kortty.core.remote.search;

import de.kortty.core.remote.RemoteShell;

import java.util.Objects;

/**
 * Builds the {@code find} command of a recursive remote search.
 *
 * <p>The command is {@code find -P <root> -maxdepth N [-xdev] \( -iname <pattern> \) -print0
 * 2>/dev/null}: symlinks are never followed ({@code -P}), names come back NUL-separated so any
 * file name survives, and there is no {@code | head} (GNU-only {@code head -z}); the client stops
 * reading at its result limit and closes the channel instead.
 *
 * <p>{@code find} has no {@code --} before its start path, so a root starting with {@code -} would
 * be read as an option. The root therefore has to be absolute (start with {@code /}); anything
 * else is refused rather than rewritten.
 *
 * <p>The {@code -iname} pattern is a superset of the search-field filter: braces become {@code *}
 * and a plain text becomes {@code *text*}. The exact filter (the same one the folder listing
 * uses) is applied again on the client to every name {@code find} prints.
 */
public final class FindCommandBuilder {

    private FindCommandBuilder() {
    }

    /**
     * The full command line.
     *
     * @param root the absolute folder to search in
     * @param namePattern the {@code -iname} pattern, see {@link #namePattern(String, boolean)}
     * @param maxDepth how many folder levels below the root are searched (at least 1)
     * @param sameFilesystem adds {@code -xdev}, so mounted file systems are not crossed
     * @throws IllegalArgumentException when the root is not absolute, the depth is below 1, or a
     *                                  value contains NUL
     */
    public static String build(String root, String namePattern, int maxDepth, boolean sameFilesystem) {
        String start = normalizeRoot(root);
        Objects.requireNonNull(namePattern, "namePattern");
        if (namePattern.isEmpty()) {
            throw new IllegalArgumentException("The name pattern cannot be empty.");
        }
        if (maxDepth < 1) {
            throw new IllegalArgumentException("The search depth must be at least 1.");
        }
        StringBuilder command = new StringBuilder("find -P ")
            .append(RemoteShell.quote(start))
            .append(" -maxdepth ").append(maxDepth);
        if (sameFilesystem) {
            command.append(" -xdev");
        }
        command.append(" \\( -iname ").append(RemoteShell.quote(namePattern)).append(" \\) -print0 2>/dev/null");
        return command.toString();
    }

    /**
     * The search root without trailing slashes ({@code /} stays {@code /}).
     *
     * @throws IllegalArgumentException when the path is not absolute or contains NUL
     */
    public static String normalizeRoot(String root) {
        Objects.requireNonNull(root, "root");
        if (!root.startsWith("/")) {
            throw new IllegalArgumentException("The search folder must be an absolute path.");
        }
        if (root.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("The search folder cannot contain a NUL character.");
        }
        String trimmed = root;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    /**
     * The {@code -iname} pattern for a search-field text.
     *
     * @param text the search text, not blank
     * @param glob whether the text is a glob that parses (the same decision the folder filter
     *             makes); otherwise it is matched as a case-insensitive substring
     */
    public static String namePattern(String text, boolean glob) {
        Objects.requireNonNull(text, "text");
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new IllegalArgumentException("The search text cannot be empty.");
        }
        if (!glob) {
            return "*" + escapeLiteral(trimmed) + "*";
        }
        StringBuilder pattern = new StringBuilder(trimmed.length() + 4);
        boolean inBraces = false;
        boolean inClass = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            if (inBraces) {
                if (c == '}') {
                    inBraces = false;
                }
                continue;
            }
            if (inClass) {
                if (c == ']' && pattern.charAt(pattern.length() - 1) != '[' && !classJustNegated(pattern)) {
                    inClass = false;
                }
                pattern.append(c == '\\' ? "\\\\" : String.valueOf(c));
                continue;
            }
            switch (c) {
                case '{' -> {
                    // Alternatives become "anything"; the client filter picks the exact ones.
                    inBraces = true;
                    appendStar(pattern);
                }
                case '[' -> {
                    inClass = true;
                    pattern.append('[');
                    if (i + 1 < trimmed.length() && trimmed.charAt(i + 1) == '^') {
                        // fnmatch spells a negated class [!...]; [^...] is not portable.
                        pattern.append('!');
                        i++;
                    }
                }
                case '*' -> appendStar(pattern);
                case '\\' -> pattern.append("\\\\");
                default -> pattern.append(c);
            }
        }
        return pattern.toString();
    }

    private static boolean classJustNegated(StringBuilder pattern) {
        int n = pattern.length();
        return n >= 2 && pattern.charAt(n - 1) == '!' && pattern.charAt(n - 2) == '[';
    }

    private static void appendStar(StringBuilder pattern) {
        if (pattern.isEmpty() || pattern.charAt(pattern.length() - 1) != '*') {
            pattern.append('*');
        }
    }

    /** Escapes the fnmatch metacharacters of a literal text. */
    static String escapeLiteral(String text) {
        StringBuilder escaped = new StringBuilder(text.length() + 4);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '*' || c == '?' || c == '[' || c == ']' || c == '\\') {
                escaped.append('\\');
            }
            escaped.append(c);
        }
        return escaped.toString();
    }
}
