package de.kortty.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Line-based diff with {@code diff -u} style hunks, used by the code-analysis reports to show what
 * an AI apply run changed. No external dependency: Maven artifacts have no hash-verification
 * infrastructure here, and unified line hunks are all the reports need.
 *
 * <p>The algorithm is greedy forward Myers (O(ND)) run on the middle that remains after the common
 * prefix and suffix are trimmed — AI rewrites keep most lines, so the middle is usually short.
 * Lines are interned to {@code int} ids first, so the inner loop compares integers. The trace keeps
 * one {@code V} row per edit step, which is O(D²) memory (about 4 MB at D=1000); the edit cap
 * bounds both work and memory, and a diff that needs more edits than the cap comes back as
 * {@link Result#tooLarge()} with the line counts only.</p>
 *
 * <p>Line endings are normalised ({@code CRLF}/{@code CR} become {@code LF}) and a trailing newline
 * does not start an extra empty line, so {@code "a\nb\n"} and {@code "a\r\nb"} are the same two
 * lines.</p>
 */
public final class TextLineDiff {

    public static final int DEFAULT_CONTEXT = 3;
    public static final int DEFAULT_MAX_EDITS = 4000;

    private TextLineDiff() {
    }

    public enum Kind {
        CONTEXT,
        ADDED,
        REMOVED
    }

    /** One diff line; {@code oldNumber}/{@code newNumber} are 1-based and 0 when the side has no such line. */
    public record Line(Kind kind, int oldNumber, int newNumber, String text) {

        public Line {
            Objects.requireNonNull(kind, "kind");
            text = text != null ? text : "";
        }
    }

    /**
     * A group of changes with surrounding context. Numbering follows {@code diff -u}: a side whose
     * count is 0 reports the line that precedes the hunk as its start, and a count of 1 is left
     * out of the header.
     */
    public record Hunk(int oldStart, int oldCount, int newStart, int newCount, List<Line> lines) {

        public Hunk {
            lines = lines != null ? List.copyOf(lines) : List.of();
        }

        /** {@code @@ -a,b +c,d @@} exactly as {@code diff -u} prints it. */
        public String header() {
            return "@@ -" + range(oldStart, oldCount) + " +" + range(newStart, newCount) + " @@";
        }

        private static String range(int start, int count) {
            return count == 1 ? Integer.toString(start) : start + "," + count;
        }
    }

    /**
     * The whole diff. {@code added}/{@code removed} count changed lines; {@code tooLarge} means the
     * edit cap was hit and only the line counts are meaningful.
     */
    public record Result(
        List<Hunk> hunks,
        int added,
        int removed,
        int oldLineCount,
        int newLineCount,
        boolean tooLarge) {

        public Result {
            hunks = hunks != null ? List.copyOf(hunks) : List.of();
        }

        /** True when both texts have the same lines (never true for a {@link #tooLarge()} result). */
        public boolean unchanged() {
            return !tooLarge && hunks.isEmpty();
        }

        /**
         * The diff in unified format with {@code ---}/{@code +++} label lines, byte-compatible with
         * {@code diff -u --label}. Empty for an unchanged or too-large result, which has no hunks.
         */
        public String unified(String oldLabel, String newLabel) {
            if (hunks.isEmpty()) {
                return "";
            }
            StringBuilder out = new StringBuilder();
            out.append("--- ").append(oldLabel != null ? oldLabel : "").append('\n');
            out.append("+++ ").append(newLabel != null ? newLabel : "").append('\n');
            for (Hunk hunk : hunks) {
                out.append(hunk.header()).append('\n');
                for (Line line : hunk.lines()) {
                    out.append(switch (line.kind()) {
                        case CONTEXT -> ' ';
                        case ADDED -> '+';
                        case REMOVED -> '-';
                    }).append(line.text()).append('\n');
                }
            }
            return out.toString();
        }
    }

    public static Result diff(String before, String after) {
        return diff(before, after, DEFAULT_CONTEXT, DEFAULT_MAX_EDITS);
    }

    /**
     * @param context  context lines around each change; hunks whose changes are at most
     *                 {@code 2 * context} lines apart are merged, as {@code diff -u} does
     * @param maxEdits the most edits (added + removed lines) worth computing; beyond that the
     *                 result is {@link Result#tooLarge()}
     */
    public static Result diff(String before, String after, int context, int maxEdits) {
        List<String> oldLines = splitLines(before);
        List<String> newLines = splitLines(after);
        int safeContext = Math.max(0, context);
        int safeMaxEdits = Math.max(0, maxEdits);

        Map<String, Integer> ids = new HashMap<>();
        int[] a = intern(oldLines, ids);
        int[] b = intern(newLines, ids);

        int prefix = 0;
        int maxPrefix = Math.min(a.length, b.length);
        while (prefix < maxPrefix && a[prefix] == b[prefix]) {
            prefix++;
        }
        int suffix = 0;
        int maxSuffix = maxPrefix - prefix;
        while (suffix < maxSuffix && a[a.length - 1 - suffix] == b[b.length - 1 - suffix]) {
            suffix++;
        }

        int n = a.length - prefix - suffix;
        int m = b.length - prefix - suffix;
        if (n == 0 && m == 0) {
            return new Result(List.of(), 0, 0, oldLines.size(), newLines.size(), false);
        }
        if (n + m > safeMaxEdits && Math.min(n, m) == 0) {
            // A pure insertion or deletion needs exactly n + m edits; no point in running Myers.
            return new Result(List.of(), 0, 0, oldLines.size(), newLines.size(), true);
        }

        Kind[] middle = myers(a, prefix, n, b, prefix, m, safeMaxEdits);
        if (middle == null) {
            return new Result(List.of(), 0, 0, oldLines.size(), newLines.size(), true);
        }

        List<Line> script = new ArrayList<>(a.length + b.length - prefix - suffix);
        for (int index = 0; index < prefix; index++) {
            script.add(new Line(Kind.CONTEXT, index + 1, index + 1, oldLines.get(index)));
        }
        int oldIndex = prefix;
        int newIndex = prefix;
        int added = 0;
        int removed = 0;
        for (Kind kind : middle) {
            switch (kind) {
                case CONTEXT -> {
                    script.add(new Line(Kind.CONTEXT, oldIndex + 1, newIndex + 1, oldLines.get(oldIndex)));
                    oldIndex++;
                    newIndex++;
                }
                case REMOVED -> {
                    script.add(new Line(Kind.REMOVED, oldIndex + 1, 0, oldLines.get(oldIndex)));
                    oldIndex++;
                    removed++;
                }
                case ADDED -> {
                    script.add(new Line(Kind.ADDED, 0, newIndex + 1, newLines.get(newIndex)));
                    newIndex++;
                    added++;
                }
            }
        }
        for (int index = 0; index < suffix; index++) {
            script.add(new Line(Kind.CONTEXT, oldIndex + 1, newIndex + 1, oldLines.get(oldIndex)));
            oldIndex++;
            newIndex++;
        }

        return new Result(buildHunks(script, safeContext), added, removed, oldLines.size(), newLines.size(), false);
    }

    // ---- Myers ---------------------------------------------------------------------------------

    /**
     * Greedy forward Myers over {@code a[aFrom, aFrom + n)} and {@code b[bFrom, bFrom + m)}. Returns
     * the edit script for that middle, or null when it needs more than {@code maxEdits} edits.
     * Within a run of changes the removals come first, the order {@code diff -u} prints.
     */
    private static Kind[] myers(int[] a, int aFrom, int n, int[] b, int bFrom, int m, int maxEdits) {
        int maxD = Math.min(n + m, maxEdits);
        int offset = maxD + 1;
        int[] v = new int[2 * offset + 1];
        List<int[]> trace = new ArrayList<>();
        int finalD = -1;
        for (int d = 0; d <= maxD && finalD < 0; d++) {
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (d == 0) {
                    x = 0;
                } else if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    x = v[offset + k + 1];
                } else {
                    x = v[offset + k - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a[aFrom + x] == b[bFrom + y]) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    finalD = d;
                    break;
                }
            }
            int[] row = new int[2 * d + 1];
            System.arraycopy(v, offset - d, row, 0, row.length);
            trace.add(row);
        }
        if (finalD < 0) {
            return null;
        }

        Kind[] reversed = new Kind[n + m];
        int length = 0;
        int x = n;
        int y = m;
        for (int d = finalD; d > 0; d--) {
            int[] previous = trace.get(d - 1);
            int k = x - y;
            int previousK;
            if (k == -d || (k != d && value(previous, d - 1, k - 1) < value(previous, d - 1, k + 1))) {
                previousK = k + 1;
            } else {
                previousK = k - 1;
            }
            int previousX = value(previous, d - 1, previousK);
            int previousY = previousX - previousK;
            while (x > previousX && y > previousY) {
                reversed[length++] = Kind.CONTEXT;
                x--;
                y--;
            }
            reversed[length++] = x == previousX ? Kind.ADDED : Kind.REMOVED;
            x = previousX;
            y = previousY;
        }
        while (x > 0 && y > 0) {
            reversed[length++] = Kind.CONTEXT;
            x--;
            y--;
        }

        Kind[] script = new Kind[length];
        for (int index = 0; index < length; index++) {
            script[index] = reversed[length - 1 - index];
        }
        orderRemovalsFirst(script);
        return script;
    }

    private static int value(int[] row, int d, int k) {
        return row[k + d];
    }

    /**
     * Myers may interleave insertions and deletions inside one change block; {@code diff -u}
     * prints every removal of a block before its additions. Reordering inside a block is safe
     * because its removals are consecutive old lines and its additions consecutive new lines.
     */
    private static void orderRemovalsFirst(Kind[] script) {
        int index = 0;
        while (index < script.length) {
            if (script[index] == Kind.CONTEXT) {
                index++;
                continue;
            }
            int end = index;
            int removals = 0;
            while (end < script.length && script[end] != Kind.CONTEXT) {
                if (script[end] == Kind.REMOVED) {
                    removals++;
                }
                end++;
            }
            for (int position = index; position < end; position++) {
                script[position] = position - index < removals ? Kind.REMOVED : Kind.ADDED;
            }
            index = end;
        }
    }

    // ---- hunks ---------------------------------------------------------------------------------

    private static List<Hunk> buildHunks(List<Line> script, int context) {
        List<Hunk> hunks = new ArrayList<>();
        int size = script.size();
        int index = 0;
        // Old and new lines consumed before `counted`, kept in step so hunk starts are O(1).
        int counted = 0;
        int oldBefore = 0;
        int newBefore = 0;
        while (index < size) {
            if (script.get(index).kind() == Kind.CONTEXT) {
                index++;
                continue;
            }
            int start = Math.max(0, index - context);
            int lastChange = index;
            int scan = index;
            while (scan < size) {
                if (script.get(scan).kind() != Kind.CONTEXT) {
                    lastChange = scan;
                    scan++;
                    continue;
                }
                // A run of context lines: keep going while the next change is close enough to merge.
                int gapEnd = scan;
                while (gapEnd < size && script.get(gapEnd).kind() == Kind.CONTEXT) {
                    gapEnd++;
                }
                if (gapEnd >= size || gapEnd - scan > 2 * context) {
                    break;
                }
                scan = gapEnd;
            }
            int end = Math.min(size, lastChange + context + 1);
            for (; counted < start; counted++) {
                Kind kind = script.get(counted).kind();
                if (kind != Kind.ADDED) {
                    oldBefore++;
                }
                if (kind != Kind.REMOVED) {
                    newBefore++;
                }
            }
            hunks.add(hunkFor(script, start, end, oldBefore, newBefore));
            index = end;
        }
        return hunks;
    }

    private static Hunk hunkFor(List<Line> script, int start, int end, int oldBefore, int newBefore) {
        List<Line> lines = script.subList(start, end);
        int oldCount = 0;
        int newCount = 0;
        for (Line line : lines) {
            if (line.kind() != Kind.ADDED) {
                oldCount++;
            }
            if (line.kind() != Kind.REMOVED) {
                newCount++;
            }
        }
        return new Hunk(
            oldCount > 0 ? oldBefore + 1 : oldBefore,
            oldCount,
            newCount > 0 ? newBefore + 1 : newBefore,
            newCount,
            lines);
    }

    // ---- lines ---------------------------------------------------------------------------------

    static List<String> splitLines(String text) {
        if (text == null || text.isEmpty()) {
            return List.of();
        }
        String normalized = text.replace("\r\n", "\n").replace('\r', '\n');
        String[] parts = normalized.split("\n", -1);
        int count = normalized.endsWith("\n") ? parts.length - 1 : parts.length;
        List<String> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            lines.add(parts[index]);
        }
        return lines;
    }

    private static int[] intern(List<String> lines, Map<String, Integer> ids) {
        int[] result = new int[lines.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = ids.computeIfAbsent(lines.get(index), ignored -> ids.size());
        }
        return result;
    }
}
