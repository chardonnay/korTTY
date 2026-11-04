package de.kortty.model;

import jakarta.xml.bind.annotation.XmlEnum;

/**
 * Whether the command an AI_SUMMARY entry describes had finished: absent (null) when it had. A
 * journal written before this existed has no such element; an older korTTY ignores it.
 */
@XmlEnum
public enum SessionJournalCommandState {
    /** Still running when the entry was written; replaced by the entry for the finished command. */
    RUNNING,
    /** The session ended before the shell showed its prompt again. */
    ENDED_WITH_SESSION
}
