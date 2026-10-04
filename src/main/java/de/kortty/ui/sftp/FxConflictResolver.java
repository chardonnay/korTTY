package de.kortty.ui.sftp;

import de.kortty.core.sftp.transfer.ConflictAction;
import de.kortty.core.sftp.transfer.ConflictInfo;
import de.kortty.core.sftp.transfer.ConflictPolicy;
import de.kortty.core.sftp.transfer.ConflictResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Asks the user about conflicts, one prompt at a time per transfer queue.
 *
 * <p>Single-flight: while a prompt is open, every other worker that hits a conflict waits on the
 * same lock. After each answer the waiters re-check their batch's {@link ConflictPolicy}, so an
 * "apply to all" answer settles them without another prompt. {@link #cancelBatch} and
 * {@link #close()} complete an open prompt with {@link ConflictAction#CANCEL_ALL} and release the
 * waiters, so no worker stays blocked after the batch or the tab is gone.
 *
 * <p>The class holds no JavaFX state; the {@link Presenter} shows the dialog (see
 * {@link SftpConflictDialog#presenter}).
 */
public final class FxConflictResolver implements ConflictResolver, AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(FxConflictResolver.class);

    /** Shows a prompt and eventually calls {@link Prompt#answer}. Called on a worker thread. */
    @FunctionalInterface
    public interface Presenter {
        void present(Prompt prompt);
    }

    /** One open question. The answer may come from any thread; the first answer wins. */
    public static final class Prompt {
        private final ConflictInfo info;
        private final CompletableFuture<Resolution> answer = new CompletableFuture<>();

        Prompt(ConflictInfo info) {
            this.info = info;
        }

        public ConflictInfo info() {
            return info;
        }

        public List<ConflictAction> allowedActions() {
            return info.allowedActions();
        }

        /** Gives the answer; a {@code null} answer counts as cancel. Later answers are ignored. */
        public void answer(Resolution resolution) {
            answer.complete(resolution == null ? Resolution.CANCEL_ALL : resolution);
        }

        /** Whether the prompt has its answer, from the user or from a cancel. */
        public boolean isDone() {
            return answer.isDone();
        }

        /** Runs {@code action} once the prompt has its answer (for closing the dialog). */
        public void whenDone(Runnable action) {
            answer.whenComplete((resolution, error) -> action.run());
        }
    }

    private final Presenter presenter;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private boolean busy;
    private boolean closed;
    private Prompt pending;
    private ConflictPolicy pendingPolicy;

    public FxConflictResolver(Presenter presenter) {
        this.presenter = Objects.requireNonNull(presenter, "presenter");
    }

    @Override
    public Resolution resolve(ConflictInfo info, ConflictPolicy policy) throws InterruptedException {
        Prompt prompt;
        lock.lockInterruptibly();
        try {
            while (true) {
                if (closed) {
                    policy.cancel();
                    return Resolution.CANCEL_ALL;
                }
                Resolution known = policy.answeredMeanwhile(info);
                if (known != null) {
                    return known;
                }
                if (!busy) {
                    break;
                }
                changed.await();
            }
            busy = true;
            prompt = new Prompt(info);
            pending = prompt;
            pendingPolicy = policy;
        } finally {
            lock.unlock();
        }

        Resolution resolution = Resolution.CANCEL_ALL;
        boolean interrupted = false;
        try {
            try {
                presenter.present(prompt);
            } catch (RuntimeException e) {
                logger.warn("Could not show the SFTP conflict prompt", e);
                prompt.answer(Resolution.CANCEL_ALL);
            }
            resolution = prompt.answer.get();
        } catch (InterruptedException e) {
            interrupted = true;
            prompt.answer(Resolution.CANCEL_ALL);
        } catch (ExecutionException e) {
            prompt.answer(Resolution.CANCEL_ALL);
        } finally {
            lock.lock();
            try {
                if (!interrupted) {
                    // Record before anyone re-checks, so an "apply to all" settles the waiters.
                    policy.record(info, resolution);
                }
                busy = false;
                pending = null;
                pendingPolicy = null;
                changed.signalAll();
            } finally {
                lock.unlock();
            }
        }
        if (interrupted) {
            throw new InterruptedException("Interrupted while waiting for a conflict answer");
        }
        return resolution;
    }

    /**
     * Cancels one batch: its open prompt is answered with CANCEL_ALL and its waiting workers
     * return CANCEL_ALL. Prompts of other batches are not touched.
     */
    public void cancelBatch(ConflictPolicy policy) {
        policy.cancel();
        lock.lock();
        try {
            if (pending != null && pendingPolicy == policy) {
                pending.answer(Resolution.CANCEL_ALL);
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    /** Whether a prompt is open right now. */
    public boolean isPrompting() {
        lock.lock();
        try {
            return busy;
        } finally {
            lock.unlock();
        }
    }

    /** For closing the tab: answers the open prompt with CANCEL_ALL and every later call too. */
    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            if (pending != null) {
                pendingPolicy.cancel();
                pending.answer(Resolution.CANCEL_ALL);
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }
}
