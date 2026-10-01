package de.kortty.model;

/** How long a kept automation run journal is retained before it is deleted automatically. */
public enum AutomationJournalRetentionMode {
    /** A number of days after the run ended. */
    DAYS,
    /** Until the end of a fixed calendar date. */
    FIXED_DATE,
    /** Never deleted automatically (an administrator cap still applies). */
    NONE
}
