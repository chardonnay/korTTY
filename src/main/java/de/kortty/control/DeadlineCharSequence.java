package de.kortty.control;

import java.util.Objects;

/**
 * A {@link CharSequence} whose {@link #charAt(int)} throws once a deadline has passed.
 *
 * <p>This bounds catastrophic backtracking: a pattern such as {@code (a+)+$} over a long line can run
 * for hours inside {@code java.util.regex}, and neither a thread interrupt nor a future's timeout
 * stops it. Matching over this sequence does stop, because the matcher itself has to read a character
 * to keep going. Together with the 512-character pattern cap and the 256 KiB text cap it means a
 * wait can never outlive its own timeout or pin the timer thread.
 *
 * <p>The clock is sampled every {@value #CHECK_MASK}+1 reads rather than on every read, which keeps
 * the overhead invisible while bounding the overshoot to microseconds.
 *
 * <p>Pure, any thread; one instance belongs to one matching run.
 */
public final class DeadlineCharSequence implements CharSequence {

    /** How often the clock is sampled: every read whose counter has these low bits clear. */
    private static final int CHECK_MASK = 0x3FF;

    private final CharSequence delegate;

    private final long deadlineNanos;

    private int reads;

    /**
     * @param delegate the text to match over
     * @param deadlineNanos the {@link System#nanoTime()} value at which reads start failing
     */
    public DeadlineCharSequence(CharSequence delegate, long deadlineNanos) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.deadlineNanos = deadlineNanos;
    }

    @Override
    public int length() {
        return delegate.length();
    }

    @Override
    public char charAt(int index) {
        if ((++reads & CHECK_MASK) == 0 && System.nanoTime() - deadlineNanos >= 0L) {
            throw new DeadlineExceeded();
        }
        return delegate.charAt(index);
    }

    @Override
    public CharSequence subSequence(int start, int end) {
        return new DeadlineCharSequence(delegate.subSequence(start, end), deadlineNanos);
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    /**
     * Thrown by {@link #charAt(int)} once the deadline has passed. It carries no stack trace: it is a
     * control-flow signal the waiter catches, not a diagnosable failure.
     */
    public static final class DeadlineExceeded extends RuntimeException {

        private static final long serialVersionUID = 1L;

        DeadlineExceeded() {
            super("The matching deadline passed", null, false, false);
        }
    }
}
