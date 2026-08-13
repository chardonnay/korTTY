package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;

/** Kind of a curated session journal entry (journal.xml), not of a raw capture-log line. */
@XmlEnum
public enum SessionJournalEntryKind {
    /** Periodic AI summary of a capture-log range. */
    AI_SUMMARY,
    /** The closing AI wrap-up written once when the session ends. */
    SESSION_SUMMARY,
    /** A screenshot the user attached to the journal. */
    SCREENSHOT,
    /** A free-text note the user added during or after the session. */
    USER_NOTE,
    /** A terminal AI-agent run: the user's prompt and the agent's final answer. */
    AGENT,
    /** Entries written by the journal pipeline itself (e.g. seeding or rotation notices). */
    SYSTEM
}
