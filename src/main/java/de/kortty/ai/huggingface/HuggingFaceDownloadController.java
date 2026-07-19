package de.kortty.ai.huggingface;

import java.io.IOException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/** Cooperative pause/resume/cancel control. Partial files remain resumable after cancellation. */
public final class HuggingFaceDownloadController {

    private final ReentrantLock lock = new ReentrantLock();
    private final Condition changed = lock.newCondition();
    private boolean paused;
    private boolean cancelled;

    public void pause() {
        lock.lock();
        try {
            if (!cancelled) {
                paused = true;
            }
        } finally {
            lock.unlock();
        }
    }

    public void resume() {
        lock.lock();
        try {
            paused = false;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public void cancel() {
        lock.lock();
        try {
            cancelled = true;
            paused = false;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    public boolean isPaused() {
        lock.lock();
        try {
            return paused;
        } finally {
            lock.unlock();
        }
    }

    public boolean isCancelled() {
        lock.lock();
        try {
            return cancelled;
        } finally {
            lock.unlock();
        }
    }

    boolean awaitPermission() throws InterruptedException {
        lock.lockInterruptibly();
        try {
            while (paused && !cancelled) {
                changed.await();
            }
            return !cancelled;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Runs the irreversible activation step only if cancellation has not won the race.
     * Cancellation and activation are linearized under the same lock: once activation begins,
     * completion wins and a concurrent cancellation applies only to subsequent work.
     */
    boolean activateIfPermitted(Activation activation) throws IOException, InterruptedException {
        lock.lockInterruptibly();
        try {
            while (paused && !cancelled) {
                changed.await();
            }
            if (cancelled) {
                return false;
            }
            activation.run();
            return true;
        } finally {
            lock.unlock();
        }
    }

    @FunctionalInterface
    interface Activation {
        void run() throws IOException;
    }
}
