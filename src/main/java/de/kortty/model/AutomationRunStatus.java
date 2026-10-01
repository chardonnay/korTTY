package de.kortty.model;

/** Outcome of one automation run (or of one target of it), as recorded on its journal. */
public enum AutomationRunStatus {
    SUCCESS,
    FAILED,
    BLOCKED,
    CANCELLED;

    /** Everything but a success counts as a failure for keep and AI modes. */
    public boolean isFailure() {
        return this != SUCCESS;
    }
}
