package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.testng.annotations.Test;

class DeadlineCharSequenceTest {

    /** The budget a catastrophic match is allowed; the assertion allows twice this. */
    private static final long DEADLINE_MILLIS = 1_000L;

    private static DeadlineCharSequence after(CharSequence text, long millis) {
        return new DeadlineCharSequence(text, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
    }

    @Test(timeOut = 30_000)
    void aCatastrophicPatternStopsAtTheDeadlineInsteadOfRunningForever() {
        Pattern catastrophic = Pattern.compile("(a+)+$");
        String input = "a".repeat(10_000) + "!";
        Matcher matcher = catastrophic.matcher(after(input, DEADLINE_MILLIS));

        long startNanos = System.nanoTime();
        expectThrows(DeadlineCharSequence.DeadlineExceeded.class, matcher::find);
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertWithMessage("the match ran %s ms for a %s ms deadline", elapsedMillis, DEADLINE_MILLIS)
            .that(elapsedMillis)
            .isAtMost(2 * DEADLINE_MILLIS);
    }

    @Test(timeOut = 30_000)
    void aWellBehavedPatternStillMatchesBeforeTheDeadline() {
        Matcher matcher = Pattern.compile("user@host:~\\$ ")
            .matcher(after("nothing\nuser@host:~$ ls\nmore", 30_000L));
        assertThat(matcher.find()).isTrue();
        assertThat(matcher.group()).isEqualTo("user@host:~$ ");
    }

    @Test(timeOut = 30_000)
    void readsBeforeTheDeadlineAreTransparent() {
        DeadlineCharSequence sequence = after("hello", 30_000L);
        assertThat(sequence.length()).isEqualTo(5);
        assertThat(sequence.charAt(0)).isEqualTo('h');
        assertThat(sequence.charAt(4)).isEqualTo('o');
        assertThat(sequence.toString()).isEqualTo("hello");
        assertThat(sequence.subSequence(1, 3).toString()).isEqualTo("el");
    }

    @Test(timeOut = 30_000)
    void aSubSequenceInheritsTheSameDeadline() {
        CharSequence sub = after("a".repeat(10_000) + "!", DEADLINE_MILLIS).subSequence(0, 10_000);
        assertThat(sub).isInstanceOf(DeadlineCharSequence.class);

        Matcher matcher = Pattern.compile("(a+)+b").matcher(sub);
        long startNanos = System.nanoTime();
        expectThrows(DeadlineCharSequence.DeadlineExceeded.class, matcher::find);
        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos))
            .isAtMost(2 * DEADLINE_MILLIS);
    }

    @Test(timeOut = 30_000)
    void anAlreadyExpiredDeadlineFailsOnTheFirstBlockOfReads() {
        DeadlineCharSequence sequence =
            new DeadlineCharSequence("a".repeat(10_000), System.nanoTime() - 1L);
        expectThrows(DeadlineCharSequence.DeadlineExceeded.class, () -> {
            for (int i = 0; i < sequence.length(); i++) {
                sequence.charAt(i);
            }
        });
    }

    @Test(timeOut = 30_000)
    void theSignalCarriesNoStackTraceBecauseItIsControlFlow() {
        DeadlineCharSequence sequence =
            new DeadlineCharSequence("a".repeat(10_000), System.nanoTime() - 1L);
        DeadlineCharSequence.DeadlineExceeded signal =
            expectThrows(DeadlineCharSequence.DeadlineExceeded.class, () -> {
                for (int i = 0; i < sequence.length(); i++) {
                    sequence.charAt(i);
                }
            });
        assertThat(signal.getStackTrace()).isEmpty();
    }

    @Test(timeOut = 30_000)
    void aNullDelegateIsRejected() {
        expectThrows(NullPointerException.class, () -> new DeadlineCharSequence(null, 0L));
    }
}
