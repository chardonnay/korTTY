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

    /**
     * How many {@code a}s the backtracking patterns chew on.
     *
     * <p>It has to be large enough that the search cannot possibly finish inside the deadline,
     * because a search that finishes proves nothing about the deadline. {@code (a+)+} is quadratic in
     * Java, not exponential, so the cost is a measurable function of this number rather than infinite:
     * on the machine this was calibrated on the whole search completed in 985 ms at 10 000, 4 236 ms
     * at 20 000 and 16 339 ms at 40 000. At 10 000 against a one-second deadline that is a coin flip,
     * and it is a coin flip a faster CPU wins — which is exactly how this test used to fail on Apple
     * Silicon while passing elsewhere. At 100 000 the search needs some hundred times the deadline, so
     * the margin survives any CPU and a hot JIT. A working deadline stops the run after a second
     * whatever this number is, so a generous one costs nothing; it only pays when the control is
     * broken, and then the method's own 30-second timeout ends it.
     *
     * <p>It also stays inside {@code ControlApiProtocol.MAX_SEARCH_CHARS} (262 144), so the test
     * exercises a text the control API would really accept.
     */
    private static final int CATASTROPHIC_LENGTH = 100_000;

    private static DeadlineCharSequence after(CharSequence text, long millis) {
        return new DeadlineCharSequence(text, System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(millis));
    }

    /**
     * The control this whole class exists for. {@code pane.wait_output} lets a caller supply the
     * pattern, so a deadline that does not fire is not a test nicety but an unbounded CPU burn a
     * local process can trigger at will.
     *
     * <p>The outcome is inspected by hand rather than through {@code expectThrows}, because when this
     * breaks the useful question is <em>what happened instead</em> — the match returned, or some other
     * throwable came first — and a bare "expected X" answers none of it.
     */
    @Test(timeOut = 30_000)
    void aCatastrophicPatternStopsAtTheDeadlineInsteadOfRunningForever() {
        Pattern catastrophic = Pattern.compile("(a+)+$");
        String input = "a".repeat(CATASTROPHIC_LENGTH) + "!";
        Matcher matcher = catastrophic.matcher(after(input, DEADLINE_MILLIS));

        long startNanos = System.nanoTime();
        Throwable thrown = null;
        Boolean found = null;
        try {
            found = matcher.find();
        } catch (Throwable t) {
            thrown = t;
        }
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);

        assertWithMessage("a catastrophic pattern must be stopped by the deadline, but find()"
                + " returned %s after %s ms; without this the control API's wait verbs can be made"
                + " to burn a core for hours on a caller-supplied regex", found, elapsedMillis)
            .that(thrown).isNotNull();
        assertWithMessage("the deadline must be what stopped the match, but it ended after %s ms"
                + " with %s: %s", elapsedMillis, thrown.getClass().getName(), thrown.getMessage())
            .that(thrown).isInstanceOf(DeadlineCharSequence.DeadlineExceeded.class);
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
        CharSequence sub = after("a".repeat(CATASTROPHIC_LENGTH) + "!", DEADLINE_MILLIS)
            .subSequence(0, CATASTROPHIC_LENGTH);
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
