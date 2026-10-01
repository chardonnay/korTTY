package de.kortty.model;

/** When an automation journal (JobScheduler / AI Swarm run) gets AI summaries. */
public enum AutomationJournalAiMode {
    /** Capture only; the journal never calls the AI (no tokens). */
    OFF,
    /** Summarize every kept journal. */
    ALWAYS,
    /** Summarize only journals of runs that did not succeed. */
    ON_FAILURE;

    /** True when a run that ended with {@code status} should be summarized. */
    public boolean summarizes(AutomationRunStatus status) {
        return switch (this) {
            case OFF -> false;
            case ALWAYS -> true;
            case ON_FAILURE -> status != null && status.isFailure();
        };
    }
}
