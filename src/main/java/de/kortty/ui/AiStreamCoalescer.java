package de.kortty.ui;

import de.kortty.core.AiStreamListener;

import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * Hands the snapshots of a streamed AI answer to the UI thread without flooding it.
 *
 * <p>The listener callbacks arrive on the thread that reads the HTTP response. This class keeps
 * only the <em>latest</em> snapshot and has at most one drain scheduled at a time; a drain is
 * delayed so that two drains are at least {@code intervalMillis} apart. The drain runs on the UI
 * thread (the {@link Scheduler} decides how) and hands the latest snapshot to the {@link Sink}.
 * Snapshots that arrive while a drain is pending simply replace each other.</p>
 *
 * <ul>
 *   <li>{@link #onRestart()} drops the pending snapshot and makes the next drain
 *       {@linkplain Sink#clear() clear} what is shown.</li>
 *   <li>{@link #onComplete()} asks for a drain right away, so the last snapshot is not held back
 *       by the interval.</li>
 *   <li>{@link #close()} (UI thread, when the request ended) turns every later drain into a no-op,
 *       so a drain that was still queued can never bring the preview back after the final answer
 *       was rendered.</li>
 * </ul>
 *
 * <p>Toolkit-free: the JavaFX wiring lives in {@link AiChatStreamingView}.</p>
 */
final class AiStreamCoalescer implements AiStreamListener {

    /** Default gap between two drains: about 20 UI updates per second. */
    static final long DEFAULT_INTERVAL_MILLIS = 50L;

    /** Runs a drain on the UI thread, after the given delay (0 = as soon as possible). */
    @FunctionalInterface
    interface Scheduler {
        void schedule(Runnable drain, long delayMillis);
    }

    /** Receives the coalesced updates; always called from a drain, i.e. on the UI thread. */
    interface Sink {
        /** Shows the answer and reasoning received so far (both never {@code null}). */
        void show(String content, String reasoning);

        /** Removes what was shown: the request starts over. */
        void clear();
    }

    private record Snapshot(String content, String reasoning) {
    }

    private final Sink sink;
    private final Scheduler scheduler;
    private final LongSupplier clockMillis;
    private final long intervalMillis;

    private final Object lock = new Object();
    private Snapshot latest;
    private boolean clearRequested;
    private boolean drainPending;
    private boolean closed;
    private boolean drainedOnce;
    private long lastDrainMillis;

    AiStreamCoalescer(Sink sink, Scheduler scheduler, LongSupplier clockMillis, long intervalMillis) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.intervalMillis = Math.max(0L, intervalMillis);
    }

    @Override
    public void onProgress(String contentSoFar, String reasoningSoFar) {
        synchronized (lock) {
            if (closed) {
                return;
            }
            latest = new Snapshot(
                contentSoFar != null ? contentSoFar : "",
                reasoningSoFar != null ? reasoningSoFar : "");
            requestDrainLocked(false);
        }
    }

    @Override
    public void onRestart() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            latest = null;
            clearRequested = true;
            requestDrainLocked(false);
        }
    }

    @Override
    public void onComplete() {
        synchronized (lock) {
            if (closed) {
                return;
            }
            requestDrainLocked(true);
        }
    }

    /** Stops all further updates; a drain still queued does nothing. Idempotent. */
    void close() {
        synchronized (lock) {
            closed = true;
            latest = null;
            clearRequested = false;
        }
    }

    boolean isClosed() {
        synchronized (lock) {
            return closed;
        }
    }

    private void requestDrainLocked(boolean immediately) {
        if (drainPending) {
            return;
        }
        drainPending = true;
        long delay = 0L;
        if (!immediately && drainedOnce) {
            long sinceLast = clockMillis.getAsLong() - lastDrainMillis;
            delay = Math.max(0L, intervalMillis - sinceLast);
        }
        scheduler.schedule(this::drain, delay);
    }

    /** Runs on the UI thread: applies the pending clear and the latest snapshot. */
    void drain() {
        Snapshot snapshot;
        boolean clear;
        synchronized (lock) {
            drainPending = false;
            if (closed) {
                return;
            }
            drainedOnce = true;
            lastDrainMillis = clockMillis.getAsLong();
            snapshot = latest;
            latest = null;
            clear = clearRequested;
            clearRequested = false;
        }
        if (clear) {
            sink.clear();
        }
        if (snapshot != null) {
            sink.show(snapshot.content(), snapshot.reasoning());
        }
    }
}
