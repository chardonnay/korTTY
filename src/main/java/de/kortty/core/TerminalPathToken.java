package de.kortty.core;

import java.util.Objects;

/**
 * A file path detected in terminal output ({@link TerminalLinkDetector.Kind#PATH}), split into the
 * path and the position that compilers, linters and {@code grep -n} append to it: {@code :N},
 * {@code :N:M} or {@code (N,M)}. A Windows drive letter is never taken for a line number, so
 * {@code C:\x} and {@code C:/x} stay whole paths.
 *
 * @param path the path without the position suffix
 * @param line the 1-based line, or {@link #NONE}
 * @param column the 1-based column, or {@link #NONE}
 */
public record TerminalPathToken(String path, int line, int column) {

    /** No line or column given. A {@code :0} suffix is removed from the path but also yields this. */
    public static final int NONE = 0;

    /** Longest number taken as a line or column; it always fits an {@code int}. */
    private static final int MAX_DIGITS = 9;

    public TerminalPathToken {
        Objects.requireNonNull(path, "path");
    }

    /** Splits {@code text} into path, line and column. Text without a position suffix is the path. */
    public static TerminalPathToken parse(String text) {
        Objects.requireNonNull(text, "text");
        if (text.endsWith(")")) {
            int open = text.lastIndexOf('(');
            int comma = text.indexOf(',', open + 1);
            if (open >= 0 && comma > open && isNumber(text, open + 1, comma)
                && isNumber(text, comma + 1, text.length() - 1) && isPath(text.substring(0, open))) {
                return new TerminalPathToken(text.substring(0, open), number(text, open + 1, comma),
                    number(text, comma + 1, text.length() - 1));
            }
        }
        int last = text.lastIndexOf(':');
        if (last >= 0 && isNumber(text, last + 1, text.length())) {
            int lastNumber = number(text, last + 1, text.length());
            int previous = text.lastIndexOf(':', last - 1);
            if (previous >= 0 && isNumber(text, previous + 1, last) && isPath(text.substring(0, previous))) {
                return new TerminalPathToken(text.substring(0, previous), number(text, previous + 1, last), lastNumber);
            }
            if (isPath(text.substring(0, last))) {
                return new TerminalPathToken(text.substring(0, last), lastNumber, NONE);
            }
        }
        return new TerminalPathToken(text, NONE, NONE);
    }

    public boolean hasLine() {
        return line > 0;
    }

    public boolean hasColumn() {
        return column > 0;
    }

    /** What is left before a suffix must be a path: not empty and not a bare drive letter ({@code C:12}). */
    private static boolean isPath(String candidate) {
        if (candidate.isEmpty()) {
            return false;
        }
        char first = candidate.charAt(0);
        boolean driveLetter = candidate.length() == 1 && ((first >= 'A' && first <= 'Z') || (first >= 'a' && first <= 'z'));
        return !driveLetter;
    }

    private static boolean isNumber(String text, int start, int end) {
        if (end <= start || end - start > MAX_DIGITS) {
            return false;
        }
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    private static int number(String text, int start, int end) {
        return Integer.parseInt(text, start, end, 10);
    }
}
