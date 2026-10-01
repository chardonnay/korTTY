package de.kortty.model;

/** Which automation run journals are kept once the run has finished. */
public enum AutomationJournalKeepMode {
    /** Keep the journal of every run. */
    ALWAYS,
    /** Discard the journal of a successful run right away; keep failed, blocked and cancelled runs. */
    ONLY_ON_FAILURE;

    /** True when the journal of a run that ended with {@code status} is kept. */
    public boolean keeps(AutomationRunStatus status) {
        return this == ALWAYS || (status != null && status.isFailure());
    }
}
