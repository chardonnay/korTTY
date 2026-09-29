package de.kortty.core;

import org.testng.annotations.Test;

import java.time.Duration;

import static com.google.common.truth.Truth.assertThat;

/** A 5 MB script (about 110k lines) must diff without freezing: precisely when close, "too large" when not. */
public class TextLineDiffLargeScriptTest {

    private static final long MB = 1024L * 1024;

    private static String script(long bytes, String salt, int changeEvery) {
        StringBuilder text = new StringBuilder((int) bytes + 100);
        int line = 0;
        while (text.length() < bytes) {
            boolean changed = changeEvery > 0 && line % changeEvery == 0;
            text.append("echo \"line ").append(line).append(' ').append(changed ? salt : "same")
                .append(" of a generated script\"\n");
            line++;
        }
        return text.toString();
    }

    @Test
    public void aFewEditsInAFiveMegabyteScriptDiffPrecisely() {
        String before = script(5 * MB, "old", 5000);
        String after = script(5 * MB, "new", 5000);
        long start = System.nanoTime();
        TextLineDiff.Result diff = TextLineDiff.diff(before, after);
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        System.out.printf("[measure] 5 MB diff, few edits: %d ms, %d hunks%n", millis, diff.hunks().size());
        assertThat(diff.tooLarge()).isFalse();
        assertThat(diff.added()).isGreaterThan(0);
        assertThat(diff.removed()).isEqualTo(diff.added());
        assertThat(millis).isLessThan(10_000L);
    }

    @Test
    public void aCompletelyRewrittenFiveMegabyteScriptFallsBackToTooLarge() {
        String before = script(5 * MB, "old", 1);
        String after = script(5 * MB, "new", 1);
        long start = System.nanoTime();
        TextLineDiff.Result diff = TextLineDiff.diff(before, after);
        long millis = Duration.ofNanos(System.nanoTime() - start).toMillis();
        System.out.printf("[measure] 5 MB diff, everything changed: %d ms, tooLarge=%s%n", millis, diff.tooLarge());
        assertThat(diff.tooLarge()).isTrue();
        assertThat(diff.oldLineCount()).isGreaterThan(50_000);
        assertThat(millis).isLessThan(10_000L);
    }
}
