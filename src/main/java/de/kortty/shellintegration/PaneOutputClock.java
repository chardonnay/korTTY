package de.kortty.shellintegration;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * When one terminal pane last received output, for {@link PaneActivityMonitor}.
 *
 * <p>The pane's emulator thread calls {@link #outputArrived()} for every chunk the pane reads from
 * its session, so it has to stay cheap there: one clock read, one volatile write and, for the first
 * chunk after a {@link #take()}, one compare-and-set. It never blocks and never throws. Only output
 * counts: what the user types into the pane does not, but the echo the shell prints for it does.
 *
 * <p>{@link #take()} hands the monitor what arrived since its previous call: the first and the
 * last moment output arrived. The first one tells how long the pane was quiet before; the last one
 * how long it has been quiet since. One thread takes, the UI thread in the application; any thread
 * may report output.
 */
public final class PaneOutputClock {

    /** The moment of an output that never arrived. */
    public static final long NEVER = Long.MIN_VALUE;

    /**
     * What a pane received between two {@link #take()} calls.
     *
     * @param firstNanos the clock reading when the first output since the previous take arrived, or
     *                   {@link #NEVER} when none did
     * @param lastNanos  the clock reading when the latest output arrived, also before the previous
     *                   take, or {@link #NEVER} when the pane never received any
     */
    public record Sample(long firstNanos, long lastNanos) {

        /** Whether output arrived since the previous take. */
        public boolean newOutput() {
            return firstNanos != NEVER;
        }
    }

    private final LongSupplier clockNanos;

    private volatile long lastOutputNanos = NEVER;

    private final AtomicLong firstUntakenNanos = new AtomicLong(NEVER);

    /** The latest output the previous take reported; read and written by the taking thread only. */
    private long lastTakenNanos = NEVER;

    /** A clock on {@link System#nanoTime()}. */
    public PaneOutputClock() {
        this(System::nanoTime);
    }

    /** @param clockNanos a clock in nanoseconds that never goes back; the monitor's clock */
    public PaneOutputClock(LongSupplier clockNanos) {
        this.clockNanos = Objects.requireNonNull(clockNanos, "clockNanos");
    }

    /** The pane received output just now. Any thread; never blocks and never throws. */
    public void outputArrived() {
        long now = clockNanos.getAsLong();
        if (firstUntakenNanos.get() == NEVER) {
            firstUntakenNanos.compareAndSet(NEVER, now);
        }
        lastOutputNanos = now;
    }

    /** The clock reading when the latest output arrived, or {@link #NEVER}. */
    public long lastOutputNanos() {
        return lastOutputNanos;
    }

    /**
     * What arrived since the previous call; the next call starts from here. Always from the same
     * thread. Output reported while this runs is either in this sample or in the next one, never
     * lost: a latest moment later than the previous sample's counts as new output even when its
     * first moment slipped past.
     */
    public Sample take() {
        long first = firstUntakenNanos.getAndSet(NEVER);
        long last = lastOutputNanos;
        if (first == NEVER && last != NEVER && last != lastTakenNanos) {
            first = last;
        }
        if (first != NEVER && (last == NEVER || last - first < 0)) {
            last = first;
        }
        lastTakenNanos = last;
        return new Sample(first, last);
    }
}
