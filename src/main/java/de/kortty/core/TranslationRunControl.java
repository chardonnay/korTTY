package de.kortty.core;

import java.util.function.BooleanSupplier;

/**
 * Pause and cancel for a long translation run. The generators poll a {@link BooleanSupplier}
 * between batches; {@link #asCancelPoll()} turns this control into one that blocks while paused
 * and answers {@code true} once cancelled, so pause needs no second code path in the generators.
 */
public interface TranslationRunControl {

    /** Whether the run was cancelled; a cancelled run stops at the next batch boundary. */
    boolean isCancelled();

    /**
     * Blocks while the run is paused. Returns {@code true} when the run was cancelled, before or
     * during the pause, and {@code false} when it may go on.
     */
    boolean awaitResumeOrCancel();

    /** Whether the run is paused right now. */
    boolean isPaused();

    /** A poll the generators understand: waits out a pause, then reports whether to stop. */
    default BooleanSupplier asCancelPoll() {
        return this::awaitResumeOrCancel;
    }

    /** A control that never pauses and is cancelled when {@code cancelled} says so. */
    static TranslationRunControl of(BooleanSupplier cancelled) {
        return new TranslationRunControl() {
            @Override
            public boolean isCancelled() {
                return cancelled != null && cancelled.getAsBoolean();
            }

            @Override
            public boolean awaitResumeOrCancel() {
                return isCancelled();
            }

            @Override
            public boolean isPaused() {
                return false;
            }
        };
    }

    /** A pausable, cancellable control; the UI calls {@link #pause()}, {@link #resume()} and {@link #cancel()}. */
    final class Switch implements TranslationRunControl {
        private boolean paused;
        private boolean cancelled;
        /** Monotonic nanos spent paused, so an ETA can leave the pause out. */
        private long pausedNanos;
        private long pauseStartedNanos;

        public synchronized void pause() {
            if (!paused && !cancelled) {
                paused = true;
                pauseStartedNanos = System.nanoTime();
            }
        }

        public synchronized void resume() {
            if (paused) {
                paused = false;
                pausedNanos += System.nanoTime() - pauseStartedNanos;
                notifyAll();
            }
        }

        public synchronized void cancel() {
            cancelled = true;
            if (paused) {
                paused = false;
                pausedNanos += System.nanoTime() - pauseStartedNanos;
            }
            notifyAll();
        }

        public synchronized void reset() {
            paused = false;
            cancelled = false;
            pausedNanos = 0;
        }

        @Override
        public synchronized boolean isCancelled() {
            return cancelled;
        }

        @Override
        public synchronized boolean isPaused() {
            return paused;
        }

        /** Nanoseconds spent paused so far, the current pause included. */
        public synchronized long pausedNanos() {
            return pausedNanos + (paused ? System.nanoTime() - pauseStartedNanos : 0);
        }

        @Override
        public synchronized boolean awaitResumeOrCancel() {
            while (paused && !cancelled) {
                try {
                    wait();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return true;
                }
            }
            return cancelled;
        }
    }
}
