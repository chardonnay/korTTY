package de.kortty.core.sftp.transfer;

import java.util.EnumMap;
import java.util.Map;

/**
 * The conflict answers of one transfer batch. Thread-safe: every worker of the batch shares one
 * instance.
 *
 * <ul>
 *   <li>A folder onto a folder merges without asking.</li>
 *   <li>An "apply to all" answer is remembered for that {@link ConflictKind} only.</li>
 *   <li>A symbolic link target is never replaced automatically: neither a default nor any
 *       remembered answer can overwrite it, and {@link ConflictAction#OVERWRITE} is not even
 *       offered for it. The same goes for a file/folder type mismatch.</li>
 *   <li>{@link ConflictAction#CANCEL_ALL} cancels the batch: every later conflict, including
 *       ones already waiting, gets {@code CANCEL_ALL} without a prompt.</li>
 * </ul>
 */
public final class ConflictPolicy {

    private final ConflictAction defaultAction;
    private final Map<ConflictKind, ConflictAction> sticky = new EnumMap<>(ConflictKind.class);
    private volatile boolean cancelled;

    /** A policy that asks for every conflict. */
    public ConflictPolicy() {
        this(null);
    }

    /**
     * A policy with a preset answer (the "when a file already exists" setting). {@code null} means
     * ask. The preset applies only to kinds that allow it; others are still asked.
     */
    public ConflictPolicy(ConflictAction defaultAction) {
        if (defaultAction == ConflictAction.CANCEL_ALL) {
            throw new IllegalArgumentException("CANCEL_ALL is not a default");
        }
        this.defaultAction = defaultAction;
    }

    /**
     * Decides {@code info}: from the batch's answers if possible, otherwise through
     * {@code resolver}. The result is always allowed for the conflict's kind.
     */
    public ConflictAction resolve(ConflictInfo info, ConflictResolver resolver) throws InterruptedException {
        ConflictResolver.Resolution known = answeredMeanwhile(info);
        if (known != null) {
            return known.action();
        }
        return record(info, resolver.resolve(info, this));
    }

    /**
     * The answer the batch already has for {@code info}, or {@code null} when it must be asked.
     * Waiting resolvers call this again after every answer.
     */
    public ConflictResolver.Resolution answeredMeanwhile(ConflictInfo info) {
        if (cancelled) {
            return ConflictResolver.Resolution.CANCEL_ALL;
        }
        ConflictKind kind = info.kind();
        if (kind == ConflictKind.FOLDER) {
            return new ConflictResolver.Resolution(ConflictAction.OVERWRITE, false);
        }
        ConflictAction remembered;
        synchronized (sticky) {
            remembered = sticky.get(kind);
        }
        if (kind.allows(remembered)) {
            return new ConflictResolver.Resolution(remembered, false);
        }
        if (kind.allows(defaultAction)) {
            return new ConflictResolver.Resolution(defaultAction, false);
        }
        return null;
    }

    /**
     * Stores an answer for {@code info} and returns the action to carry out. A missing answer
     * counts as {@link ConflictAction#CANCEL_ALL}; an answer not allowed for the kind (such as
     * overwriting a symbolic link) becomes {@link ConflictAction#SKIP}. Recording the same answer
     * twice is harmless.
     */
    public ConflictAction record(ConflictInfo info, ConflictResolver.Resolution answer) {
        if (cancelled || answer == null || answer.action() == ConflictAction.CANCEL_ALL) {
            cancelled = true;
            return ConflictAction.CANCEL_ALL;
        }
        ConflictKind kind = info.kind();
        ConflictAction action = kind.allows(answer.action()) ? answer.action() : ConflictAction.SKIP;
        if (answer.applyToAll() && action == answer.action()) {
            synchronized (sticky) {
                sticky.put(kind, action);
            }
        }
        return action;
    }

    /** Cancels the batch: every open and later conflict is answered with CANCEL_ALL. */
    public void cancel() {
        cancelled = true;
    }

    /** Whether the batch was cancelled, by an answer or by {@link #cancel()}. */
    public boolean isCancelled() {
        return cancelled;
    }
}
