package de.kortty.core;

import de.kortty.core.TextLineDiff.Hunk;
import de.kortty.core.TextLineDiff.Kind;
import de.kortty.core.TextLineDiff.Line;
import de.kortty.core.TextLineDiff.Result;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The golden strings below were produced with GNU diffutils 3.12 ({@code diff -u --label before
 * --label after}); the numbering rules they pin — a 0-count side names the preceding line, a count
 * of 1 is left out — are what {@code patch} and every diff viewer expect.
 */
class TextLineDiffTest {

    @Test
    void identicalTextsAreUnchanged() {
        Result result = TextLineDiff.diff("a\nb\nc\n", "a\nb\nc\n");

        assertThat(result.unchanged()).isTrue();
        assertThat(result.hunks()).isEmpty();
        assertThat(result.added()).isEqualTo(0);
        assertThat(result.removed()).isEqualTo(0);
        assertThat(result.oldLineCount()).isEqualTo(3);
        assertThat(result.newLineCount()).isEqualTo(3);
        assertThat(result.tooLarge()).isFalse();
        assertThat(result.unified("before", "after")).isEmpty();

        assertThat(TextLineDiff.diff("", "").unchanged()).isTrue();
        assertThat(TextLineDiff.diff(null, null).unchanged()).isTrue();
    }

    @Test
    void insertAtStartMiddleAndEnd() {
        Result start = TextLineDiff.diff("a\nb\n", "new\na\nb\n");
        assertThat(start.added()).isEqualTo(1);
        assertThat(start.removed()).isEqualTo(0);
        assertThat(start.hunks()).hasSize(1);
        assertThat(start.hunks().getFirst().header()).isEqualTo("@@ -1,2 +1,3 @@");
        assertThat(start.hunks().getFirst().lines().getFirst())
            .isEqualTo(new Line(Kind.ADDED, 0, 1, "new"));

        Result middle = TextLineDiff.diff("a\nb\nc\nd\n", "a\nb\nnew\nc\nd\n");
        assertThat(middle.added()).isEqualTo(1);
        assertThat(middle.hunks().getFirst().header()).isEqualTo("@@ -1,4 +1,5 @@");
        assertThat(middle.hunks().getFirst().lines().get(2)).isEqualTo(new Line(Kind.ADDED, 0, 3, "new"));

        Result end = TextLineDiff.diff("a\nb\n", "a\nb\nc\nd\n");
        assertThat(end.added()).isEqualTo(2);
        assertThat(end.unified("before", "after")).isEqualTo("""
            --- before
            +++ after
            @@ -1,2 +1,4 @@
             a
             b
            +c
            +d
            """);
    }

    @Test
    void deleteAtStartMiddleAndEnd() {
        Result start = TextLineDiff.diff("x\na\nb\nc\nd\n", "a\nb\nc\nd\n");
        assertThat(start.removed()).isEqualTo(1);
        assertThat(start.added()).isEqualTo(0);
        assertThat(start.unified("before", "after")).isEqualTo("""
            --- before
            +++ after
            @@ -1,4 +1,3 @@
            -x
             a
             b
             c
            """);

        Result middle = TextLineDiff.diff("a\nb\nx\nc\nd\n", "a\nb\nc\nd\n");
        assertThat(middle.removed()).isEqualTo(1);
        assertThat(middle.hunks().getFirst().lines().get(2)).isEqualTo(new Line(Kind.REMOVED, 3, 0, "x"));
        assertThat(middle.hunks().getFirst().header()).isEqualTo("@@ -1,5 +1,4 @@");

        Result end = TextLineDiff.diff("a\nb\nc\nx\n", "a\nb\nc\n");
        assertThat(end.removed()).isEqualTo(1);
        assertThat(end.hunks().getFirst().header()).isEqualTo("@@ -1,4 +1,3 @@");
        assertThat(end.hunks().getFirst().lines().getLast()).isEqualTo(new Line(Kind.REMOVED, 4, 0, "x"));
    }

    @Test
    void replaceListsRemovalsBeforeAdditionsAndNumbersBothSides() {
        Result result = TextLineDiff.diff("a\nb\nc\nd\ne\nf\ng\n", "a\nb\nc\nD1\nD2\ne\nf\ng\n");

        assertThat(result.added()).isEqualTo(2);
        assertThat(result.removed()).isEqualTo(1);
        Hunk hunk = result.hunks().getFirst();
        assertThat(hunk.header()).isEqualTo("@@ -1,7 +1,8 @@");
        assertThat(hunk.lines().subList(3, 6)).containsExactly(
            new Line(Kind.REMOVED, 4, 0, "d"),
            new Line(Kind.ADDED, 0, 4, "D1"),
            new Line(Kind.ADDED, 0, 5, "D2")).inOrder();
        assertThat(hunk.lines().get(6)).isEqualTo(new Line(Kind.CONTEXT, 5, 6, "e"));
    }

    @Test
    void crlfAndCrEqualLf() {
        assertThat(TextLineDiff.diff("a\r\nb\r\nc", "a\nb\nc").unchanged()).isTrue();
        assertThat(TextLineDiff.diff("a\rb\rc\r", "a\nb\nc\n").unchanged()).isTrue();

        Result mixed = TextLineDiff.diff("a\r\nb\r\n", "a\r\nB\r\n");
        assertThat(mixed.hunks().getFirst().lines()).containsExactly(
            new Line(Kind.CONTEXT, 1, 1, "a"),
            new Line(Kind.REMOVED, 2, 0, "b"),
            new Line(Kind.ADDED, 0, 2, "B")).inOrder();
    }

    @Test
    void trailingNewlineIsNotALine() {
        assertThat(TextLineDiff.diff("a\nb", "a\nb\n").unchanged()).isTrue();
        assertThat(TextLineDiff.diff("a\nb\n", "a\nb").oldLineCount()).isEqualTo(2);
        // A lone newline is one empty line, the way diff and wc -l see it.
        assertThat(TextLineDiff.diff("", "\n").added()).isEqualTo(1);
        assertThat(TextLineDiff.diff("a\n\n", "a\n").removed()).isEqualTo(1);
        assertThat(TextLineDiff.diff("a\n\n", "a\n").hunks().getFirst().lines().getLast())
            .isEqualTo(new Line(Kind.REMOVED, 2, 0, ""));
    }

    @Test
    void unifiedOutputMatchesGnuDiffForTwoDistantChanges() {
        String before = joinLines("l1", "l2", "l3", "l4", "l5", "l6", "l7", "l8", "l9", "l10",
            "l11", "l12", "l13", "l14", "l15", "l16", "l17", "l18", "l19", "l20");
        String after = joinLines("l1", "l2", "l3", "l4", "X5", "l6", "l7", "l8", "l9", "l10",
            "l11", "l12", "l13", "l14", "l15", "l16", "l17", "l18", "l19", "l20", "l21");

        Result result = TextLineDiff.diff(before, after);

        assertThat(result.hunks()).hasSize(2);
        assertThat(result.unified("before", "after")).isEqualTo("""
            --- before
            +++ after
            @@ -2,7 +2,7 @@
             l2
             l3
             l4
            -l5
            +X5
             l6
             l7
             l8
            @@ -18,3 +18,4 @@
             l18
             l19
             l20
            +l21
            """);
    }

    @Test
    void hunksAreMergedWhenTheirGapIsAtMostTwiceTheContext() {
        String before = joinLines("a", "b", "c", "d", "e", "f", "g", "h", "i", "j", "k", "l", "m");

        // Six unchanged lines between the changes: 2 * context, one hunk.
        Result merged = TextLineDiff.diff(before,
            joinLines("a", "b", "C", "d", "e", "f", "g", "h", "i", "J", "k", "l", "m"));
        assertThat(merged.hunks()).hasSize(1);
        assertThat(merged.unified("before", "after")).isEqualTo("""
            --- before
            +++ after
            @@ -1,13 +1,13 @@
             a
             b
            -c
            +C
             d
             e
             f
             g
             h
             i
            -j
            +J
             k
             l
             m
            """);

        // Seven unchanged lines: one more than 2 * context, two hunks.
        Result split = TextLineDiff.diff(before,
            joinLines("a", "b", "C", "d", "e", "f", "g", "h", "i", "j", "K", "l", "m"));
        assertThat(split.hunks()).hasSize(2);
        assertThat(split.unified("before", "after")).isEqualTo("""
            --- before
            +++ after
            @@ -1,6 +1,6 @@
             a
             b
            -c
            +C
             d
             e
             f
            @@ -8,6 +8,6 @@
             h
             i
             j
            -k
            +K
             l
             m
            """);
    }

    @Test
    void headerFollowsGnuNumberingForSingleLineAndEmptySides() {
        assertThat(TextLineDiff.diff("only\n", "other\n").unified("before", "after")).isEqualTo("""
            --- before
            +++ after
            @@ -1 +1 @@
            -only
            +other
            """);

        assertThat(TextLineDiff.diff("", "new1\nnew2\n").unified("before", "after")).isEqualTo("""
            --- before
            +++ after
            @@ -0,0 +1,2 @@
            +new1
            +new2
            """);

        assertThat(TextLineDiff.diff("gone\n", "").hunks().getFirst().header()).isEqualTo("@@ -1 +0,0 @@");
        // With no context a change in the middle names the preceding line on its empty side.
        Result inserted = TextLineDiff.diff("a\nb\nc\n", "a\nb\nnew\nc\n", 0, TextLineDiff.DEFAULT_MAX_EDITS);
        assertThat(inserted.hunks().getFirst().header()).isEqualTo("@@ -2,0 +3 @@");
    }

    @Test
    void tooLargeWhenTheEditCapIsExceeded() {
        String before = joinLines("a", "b", "c", "d", "e", "f");
        String after = joinLines("A", "B", "C", "D", "E", "F");

        Result capped = TextLineDiff.diff(before, after, TextLineDiff.DEFAULT_CONTEXT, 4);
        assertThat(capped.tooLarge()).isTrue();
        assertThat(capped.unchanged()).isFalse();
        assertThat(capped.hunks()).isEmpty();
        assertThat(capped.oldLineCount()).isEqualTo(6);
        assertThat(capped.newLineCount()).isEqualTo(6);
        assertThat(capped.unified("before", "after")).isEmpty();

        // Exactly at the cap the diff is still computed.
        Result exact = TextLineDiff.diff(before, after, TextLineDiff.DEFAULT_CONTEXT, 12);
        assertThat(exact.tooLarge()).isFalse();
        assertThat(exact.added()).isEqualTo(6);
        assertThat(exact.removed()).isEqualTo(6);

        // A pure insertion past the cap is too large as well; the cap is about edits, not effort.
        Result insertion = TextLineDiff.diff("", joinLines("1", "2", "3", "4", "5"), 3, 4);
        assertThat(insertion.tooLarge()).isTrue();
        assertThat(insertion.newLineCount()).isEqualTo(5);

        // The cap never triggers for unchanged input.
        assertThat(TextLineDiff.diff(before, before, 3, 0).unchanged()).isTrue();
    }

    @Test
    void randomizedHunksReproduceTheNewTextAndAreMinimal() {
        Random random = new Random(20260928L);
        String[] alphabet = {"a", "b", "c", "d", "", "e"};
        for (int caseIndex = 0; caseIndex < 200; caseIndex++) {
            List<String> before = randomLines(random, alphabet, random.nextInt(31));
            List<String> after = mutate(random, alphabet, before);
            int context = random.nextInt(4);

            Result result = TextLineDiff.diff(joinLines(before), joinLines(after), context, TextLineDiff.DEFAULT_MAX_EDITS);
            String label = "case " + caseIndex + " context " + context + " before=" + before + " after=" + after;

            assertWithMessage(label).that(result.tooLarge()).isFalse();
            assertWithMessage(label).that(result.oldLineCount()).isEqualTo(before.size());
            assertWithMessage(label).that(result.newLineCount()).isEqualTo(after.size());
            assertWithMessage(label).that(apply(before, result)).isEqualTo(after);
            assertWithMessage(label).that(result.unchanged()).isEqualTo(before.equals(after));
            assertWithMessage(label).that(result.added() - result.removed())
                .isEqualTo(after.size() - before.size());

            // Myers finds a shortest edit script: added + removed == n + m - 2 * LCS.
            int lcs = longestCommonSubsequence(before, after);
            assertWithMessage(label).that(result.added() + result.removed())
                .isEqualTo(before.size() + after.size() - 2 * lcs);

            int addedLines = 0;
            int removedLines = 0;
            for (Hunk hunk : result.hunks()) {
                int oldLines = 0;
                int newLines = 0;
                for (Line line : hunk.lines()) {
                    switch (line.kind()) {
                        case CONTEXT -> {
                            oldLines++;
                            newLines++;
                            assertWithMessage(label).that(before.get(line.oldNumber() - 1)).isEqualTo(line.text());
                            assertWithMessage(label).that(after.get(line.newNumber() - 1)).isEqualTo(line.text());
                        }
                        case ADDED -> {
                            newLines++;
                            addedLines++;
                            assertWithMessage(label).that(line.oldNumber()).isEqualTo(0);
                            assertWithMessage(label).that(after.get(line.newNumber() - 1)).isEqualTo(line.text());
                        }
                        case REMOVED -> {
                            oldLines++;
                            removedLines++;
                            assertWithMessage(label).that(line.newNumber()).isEqualTo(0);
                            assertWithMessage(label).that(before.get(line.oldNumber() - 1)).isEqualTo(line.text());
                        }
                    }
                }
                assertWithMessage(label).that(hunk.oldCount()).isEqualTo(oldLines);
                assertWithMessage(label).that(hunk.newCount()).isEqualTo(newLines);
                assertWithMessage(label).that(hunk.lines().getFirst().kind() != Kind.CONTEXT || context > 0).isTrue();
            }
            assertWithMessage(label).that(addedLines).isEqualTo(result.added());
            assertWithMessage(label).that(removedLines).isEqualTo(result.removed());
        }
    }

    // ---- helpers -------------------------------------------------------------------------------

    /** Applies the hunks the way {@code patch} would: copy up to each hunk, then replay its lines. */
    private static List<String> apply(List<String> before, Result result) {
        List<String> out = new ArrayList<>();
        int oldIndex = 0;
        for (Hunk hunk : result.hunks()) {
            int hunkStart = hunk.oldCount() > 0 ? hunk.oldStart() - 1 : hunk.oldStart();
            assertThat(hunkStart).isAtLeast(oldIndex);
            while (oldIndex < hunkStart) {
                out.add(before.get(oldIndex++));
            }
            for (Line line : hunk.lines()) {
                switch (line.kind()) {
                    case CONTEXT -> {
                        assertThat(before.get(oldIndex)).isEqualTo(line.text());
                        out.add(before.get(oldIndex++));
                    }
                    case REMOVED -> {
                        assertThat(before.get(oldIndex)).isEqualTo(line.text());
                        oldIndex++;
                    }
                    case ADDED -> out.add(line.text());
                }
            }
        }
        while (oldIndex < before.size()) {
            out.add(before.get(oldIndex++));
        }
        return out;
    }

    private static int longestCommonSubsequence(List<String> a, List<String> b) {
        int[][] table = new int[a.size() + 1][b.size() + 1];
        for (int i = a.size() - 1; i >= 0; i--) {
            for (int j = b.size() - 1; j >= 0; j--) {
                table[i][j] = a.get(i).equals(b.get(j))
                    ? table[i + 1][j + 1] + 1
                    : Math.max(table[i + 1][j], table[i][j + 1]);
            }
        }
        return table[0][0];
    }

    private static List<String> randomLines(Random random, String[] alphabet, int count) {
        List<String> lines = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            lines.add(alphabet[random.nextInt(alphabet.length)]);
        }
        return lines;
    }

    /** A handful of random insertions, deletions and replacements; sometimes an unrelated text. */
    private static List<String> mutate(Random random, String[] alphabet, List<String> before) {
        if (random.nextInt(10) == 0) {
            return randomLines(random, alphabet, random.nextInt(31));
        }
        List<String> after = new ArrayList<>(before);
        int edits = random.nextInt(6);
        for (int edit = 0; edit < edits; edit++) {
            int position = after.isEmpty() ? 0 : random.nextInt(after.size() + 1);
            switch (random.nextInt(3)) {
                case 0 -> after.add(position, alphabet[random.nextInt(alphabet.length)]);
                case 1 -> {
                    if (position < after.size()) {
                        after.remove(position);
                    }
                }
                default -> {
                    if (position < after.size()) {
                        after.set(position, alphabet[random.nextInt(alphabet.length)]);
                    }
                }
            }
        }
        return after;
    }

    private static String joinLines(String... lines) {
        return joinLines(List.of(lines));
    }

    private static String joinLines(List<String> lines) {
        StringBuilder builder = new StringBuilder();
        for (String line : lines) {
            builder.append(line).append('\n');
        }
        return builder.toString();
    }
}
