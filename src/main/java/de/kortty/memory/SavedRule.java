package de.kortty.memory;

import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A rule that was saved, with the undo for it. {@link #undo()} works for {@link RuleProposalService#UNDO_WINDOW} after
 * the save and only once; it removes exactly what the save added, and nothing the user wrote.
 */
public final class SavedRule {

    /** What an undo did, or why it did not. */
    public enum UndoOutcome {
        /** The rule was removed again: the memory entry, the learned rule or the new skill. */
        UNDONE,
        /** The new skill was changed since it was created, so only its rule was removed. */
        RULE_REMOVED_SKILL_KEPT,
        /** The undo window has passed. The rule stays saved. */
        EXPIRED,
        /** The rule was already undone. */
        ALREADY_UNDONE,
        /** The rule is no longer there, so there is nothing to remove. */
        NOT_FOUND,
        /** The removal was refused or failed, for example because the memory became read-only. */
        FAILED
    }

    /** The removal the save set up; it runs only inside the undo window. */
    interface Undoer {
        CompletableFuture<UndoOutcome> undo();
    }

    private final RuleTarget target;
    private final String text;
    private final Instant savedAt;
    private final Clock clock;
    private final Undoer undoer;
    private final AtomicBoolean undone = new AtomicBoolean();

    SavedRule(RuleTarget target, String text, Instant savedAt, Clock clock, Undoer undoer) {
        this.target = target;
        this.text = text;
        this.savedAt = savedAt;
        this.clock = clock;
        this.undoer = undoer;
    }

    public RuleTarget target() {
        return target;
    }

    /** The text as it was saved, on one line. */
    public String text() {
        return text;
    }

    public Instant savedAt() {
        return savedAt;
    }

    /** The first instant at which {@link #undo()} reports {@link UndoOutcome#EXPIRED}. */
    public Instant undoableUntil() {
        return savedAt.plus(RuleProposalService.UNDO_WINDOW);
    }

    /** Whether {@link #undo()} can still remove the rule. */
    public boolean undoable() {
        return clock.instant().isBefore(undoableUntil()) && !undone.get();
    }

    /**
     * Removes the rule again. The future completes on a background thread, or on the owner thread of the skills, and
     * never fails: a removal that could not run completes with {@link UndoOutcome#FAILED}.
     */
    public CompletableFuture<UndoOutcome> undo() {
        if (!clock.instant().isBefore(undoableUntil())) {
            return CompletableFuture.completedFuture(UndoOutcome.EXPIRED);
        }
        if (!undone.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(UndoOutcome.ALREADY_UNDONE);
        }
        try {
            return undoer.undo().handle((outcome, failure) -> failure == null ? outcome : UndoOutcome.FAILED);
        } catch (RuntimeException e) {
            return CompletableFuture.completedFuture(UndoOutcome.FAILED);
        }
    }
}
