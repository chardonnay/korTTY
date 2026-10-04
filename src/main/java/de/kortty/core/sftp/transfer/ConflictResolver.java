package de.kortty.core.sftp.transfer;

import java.util.Objects;

/**
 * Answers a conflict the batch's {@link ConflictPolicy} could not answer by itself, usually by
 * asking the user. Called on a transfer worker thread, never on the FX thread; it may block until
 * the answer is there.
 *
 * <p>An implementation that lets several workers wait must re-check
 * {@link ConflictPolicy#answeredMeanwhile(ConflictInfo)} before it asks, and must
 * {@link ConflictPolicy#record record} each answer before the next waiter re-checks, so one
 * "apply to all" answer settles every waiting conflict of the same kind.
 */
@FunctionalInterface
public interface ConflictResolver {

    /** One answer, and whether it applies to every later conflict of the same kind in the batch. */
    record Resolution(ConflictAction action, boolean applyToAll) {

        /** Stops the batch. */
        public static final Resolution CANCEL_ALL = new Resolution(ConflictAction.CANCEL_ALL, false);

        public Resolution {
            Objects.requireNonNull(action, "action");
        }
    }

    /**
     * Answers {@code info} for the batch governed by {@code policy}.
     *
     * @throws InterruptedException when the waiting worker is interrupted
     */
    Resolution resolve(ConflictInfo info, ConflictPolicy policy) throws InterruptedException;

    /**
     * Stops asking for the batch governed by {@code policy}: the policy is cancelled, and a resolver
     * with an open prompt for that batch answers it with {@link ConflictAction#CANCEL_ALL}.
     */
    default void cancelBatch(ConflictPolicy policy) {
        policy.cancel();
    }

    /** A resolver that always gives {@code action} without asking (headless callers, tests). */
    static ConflictResolver always(ConflictAction action) {
        Resolution fixed = new Resolution(action, false);
        return (info, policy) -> fixed;
    }
}
