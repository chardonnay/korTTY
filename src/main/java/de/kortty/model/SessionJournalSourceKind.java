package de.kortty.model;

/** What created a session journal. Journals without a source are interactive terminal journals. */
public enum SessionJournalSourceKind {
    /** A terminal tab (manual or per-connection journal). */
    INTERACTIVE,
    /** A JobScheduler job run, including scheduled AI Swarm jobs. */
    JOB,
    /** An interactive AI Swarm run. */
    SWARM
}
