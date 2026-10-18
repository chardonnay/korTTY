package de.kortty.core;

import java.util.ArrayList;
import java.util.List;

/**
 * A line diff of two texts in the unified format ({@code diff -u}), for an AI prompt and for the
 * "+12 −4" summary of a file comparison. The side-by-side view itself is Monaco's; this only has
 * to be correct and readable, not minimal at any price.
 *
 * <p>Myers' algorithm runs on what is left after the common start and end are cut off. When the
 * two differ in more than {@link #MAX_EDIT_DISTANCE} lines, the middle is reported as replaced as a
 * whole ({@link Result#simplified()}), which keeps time and memory bounded for unrelated files.
 */
public final class TextDiff {

    /** Lines beyond this many edits are not aligned any more (see the class comment). */
    public static final int MAX_EDIT_DISTANCE = 2_000;

    /** Lines added on the right and removed from the left. */
    public record Stats(int added, int removed) {
        public boolean identical() {
            return added == 0 && removed == 0;
        }
    }

    /** The unified diff (empty for identical texts), its counts, and whether the middle was not aligned. */
    public record Result(String unified, Stats stats, boolean simplified) {
    }

    private enum Kind { EQUAL, DELETE, INSERT }

    private record Op(Kind kind, int left, int right) {
    }

    private TextDiff() {
    }

    /**
     * The unified diff from {@code left} to {@code right} with {@code context} unchanged lines around
     * each change, headed {@code --- leftName} / {@code +++ rightName}.
     */
    public static Result unified(String leftName, String rightName, String left, String right, int context) {
        List<String> a = lines(left);
        List<String> b = lines(right);
        boolean[] simplified = {false};
        List<Op> ops = diff(a, b, simplified);
        int added = 0;
        int removed = 0;
        for (Op op : ops) {
            if (op.kind() == Kind.INSERT) {
                added++;
            } else if (op.kind() == Kind.DELETE) {
                removed++;
            }
        }
        Stats stats = new Stats(added, removed);
        if (stats.identical()) {
            return new Result("", stats, false);
        }
        StringBuilder out = new StringBuilder();
        out.append("--- ").append(leftName).append('\n');
        out.append("+++ ").append(rightName).append('\n');
        appendHunks(out, ops, a, b, Math.max(0, context));
        return new Result(out.toString(), stats, simplified[0]);
    }

    /** How many lines {@code text} has; a final line break does not start another one. */
    public static int lineCount(String text) {
        return lines(text).size();
    }

    /** The lines of {@code text}: split at line breaks ({@code \n}, {@code \r\n}, {@code \r}), no trailing empty line. */
    static List<String> lines(String text) {
        List<String> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r') {
                lines.add(text.substring(start, i));
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                    i++;
                }
                start = i + 1;
            }
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private static List<Op> diff(List<String> a, List<String> b, boolean[] simplified) {
        int prefix = 0;
        while (prefix < a.size() && prefix < b.size() && a.get(prefix).equals(b.get(prefix))) {
            prefix++;
        }
        int suffix = 0;
        while (suffix < a.size() - prefix && suffix < b.size() - prefix
                && a.get(a.size() - 1 - suffix).equals(b.get(b.size() - 1 - suffix))) {
            suffix++;
        }
        List<Op> ops = new ArrayList<>();
        for (int i = 0; i < prefix; i++) {
            ops.add(new Op(Kind.EQUAL, i, i));
        }
        List<String> midA = a.subList(prefix, a.size() - suffix);
        List<String> midB = b.subList(prefix, b.size() - suffix);
        List<Op> middle = myers(midA, midB);
        if (middle == null) {
            simplified[0] = true;
            middle = new ArrayList<>();
            for (int i = 0; i < midA.size(); i++) {
                middle.add(new Op(Kind.DELETE, i, -1));
            }
            for (int j = 0; j < midB.size(); j++) {
                middle.add(new Op(Kind.INSERT, -1, j));
            }
        }
        for (Op op : middle) {
            ops.add(new Op(op.kind(), op.left() < 0 ? -1 : op.left() + prefix, op.right() < 0 ? -1 : op.right() + prefix));
        }
        for (int i = 0; i < suffix; i++) {
            ops.add(new Op(Kind.EQUAL, a.size() - suffix + i, b.size() - suffix + i));
        }
        return ops;
    }

    /** Myers' shortest edit script, or null when it needs more than {@link #MAX_EDIT_DISTANCE} edits. */
    private static List<Op> myers(List<String> a, List<String> b) {
        int n = a.size();
        int m = b.size();
        if (n == 0 || m == 0) {
            List<Op> ops = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                ops.add(new Op(Kind.DELETE, i, -1));
            }
            for (int j = 0; j < m; j++) {
                ops.add(new Op(Kind.INSERT, -1, j));
            }
            return ops;
        }
        int limit = Math.min(n + m, MAX_EDIT_DISTANCE);
        int offset = limit + 1;
        int[] v = new int[2 * offset + 1];
        // trace.get(d) holds v[k] for k in [-(d-1), d-1] as it was when round d started.
        List<int[]> trace = new ArrayList<>();
        int finalD = -1;
        for (int d = 0; d <= limit && finalD < 0; d++) {
            int[] snapshot = new int[Math.max(0, 2 * d - 1)];
            for (int k = -(d - 1); k <= d - 1; k++) {
                snapshot[k + d - 1] = v[offset + k];
            }
            trace.add(snapshot);
            for (int k = -d; k <= d; k += 2) {
                int x;
                if (k == -d || (k != d && v[offset + k - 1] < v[offset + k + 1])) {
                    x = v[offset + k + 1];
                } else {
                    x = v[offset + k - 1] + 1;
                }
                int y = x - k;
                while (x < n && y < m && a.get(x).equals(b.get(y))) {
                    x++;
                    y++;
                }
                v[offset + k] = x;
                if (x >= n && y >= m) {
                    finalD = d;
                    break;
                }
            }
        }
        if (finalD < 0) {
            return null;
        }
        List<Op> reversed = new ArrayList<>();
        int x = n;
        int y = m;
        for (int d = finalD; d > 0; d--) {
            int[] snapshot = trace.get(d);
            int k = x - y;
            int previousK;
            if (k == -d || (k != d && at(snapshot, d, k - 1) < at(snapshot, d, k + 1))) {
                previousK = k + 1;
            } else {
                previousK = k - 1;
            }
            int previousX = at(snapshot, d, previousK);
            int previousY = previousX - previousK;
            while (x > previousX && y > previousY) {
                reversed.add(new Op(Kind.EQUAL, x - 1, y - 1));
                x--;
                y--;
            }
            if (x == previousX) {
                reversed.add(new Op(Kind.INSERT, -1, y - 1));
            } else {
                reversed.add(new Op(Kind.DELETE, x - 1, -1));
            }
            x = previousX;
            y = previousY;
        }
        while (x > 0 && y > 0) {
            reversed.add(new Op(Kind.EQUAL, x - 1, y - 1));
            x--;
            y--;
        }
        List<Op> ops = new ArrayList<>(reversed.size());
        for (int i = reversed.size() - 1; i >= 0; i--) {
            ops.add(reversed.get(i));
        }
        return ops;
    }

    private static int at(int[] snapshot, int d, int k) {
        return snapshot[k + d - 1];
    }

    private static void appendHunks(StringBuilder out, List<Op> ops, List<String> a, List<String> b, int context) {
        int index = 0;
        while (index < ops.size()) {
            while (index < ops.size() && ops.get(index).kind() == Kind.EQUAL) {
                index++;
            }
            if (index >= ops.size()) {
                return;
            }
            int start = Math.max(0, index - context);
            int end = index;
            // Extend over changes and over gaps of equal lines short enough to join two changes.
            while (end < ops.size()) {
                if (ops.get(end).kind() != Kind.EQUAL) {
                    end++;
                    continue;
                }
                int gap = end;
                while (gap < ops.size() && ops.get(gap).kind() == Kind.EQUAL) {
                    gap++;
                }
                if (gap < ops.size() && gap - end <= 2 * context) {
                    end = gap;
                } else {
                    end = Math.min(ops.size(), end + context);
                    break;
                }
            }
            appendHunk(out, ops, start, end, a, b);
            index = end;
        }
    }

    private static void appendHunk(StringBuilder out, List<Op> ops, int start, int end, List<String> a, List<String> b) {
        int leftBefore = 0;
        int rightBefore = 0;
        for (int i = 0; i < start; i++) {
            Kind kind = ops.get(i).kind();
            leftBefore += kind == Kind.INSERT ? 0 : 1;
            rightBefore += kind == Kind.DELETE ? 0 : 1;
        }
        int leftCount = 0;
        int rightCount = 0;
        for (int i = start; i < end; i++) {
            Kind kind = ops.get(i).kind();
            leftCount += kind == Kind.INSERT ? 0 : 1;
            rightCount += kind == Kind.DELETE ? 0 : 1;
        }
        out.append("@@ -").append(range(leftBefore, leftCount))
            .append(" +").append(range(rightBefore, rightCount))
            .append(" @@\n");
        for (int i = start; i < end; i++) {
            Op op = ops.get(i);
            switch (op.kind()) {
                case EQUAL -> out.append(' ').append(a.get(op.left())).append('\n');
                case DELETE -> out.append('-').append(a.get(op.left())).append('\n');
                case INSERT -> out.append('+').append(b.get(op.right())).append('\n');
            }
        }
    }

    /** {@code start,count} as diff -u writes it: 1-based, the count left out when 1, the line before an empty range. */
    private static String range(int linesBefore, int count) {
        if (count == 0) {
            return linesBefore + ",0";
        }
        return (linesBefore + 1) + (count == 1 ? "" : "," + count);
    }
}
