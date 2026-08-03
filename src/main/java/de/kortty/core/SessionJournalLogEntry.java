package de.kortty.core;

import java.time.OffsetDateTime;

/**
 * One line of the session journal capture log, used both when writing and when reading back.
 *
 * @param seq       monotonic sequence id, unique across all entry kinds and rotation parts
 * @param timestamp capture time (taken at enqueue, not at write)
 * @param kind      what the line is
 * @param text      redacted line text; empty for redacted input placeholders and screenshots
 * @param redacted  true for the placeholder written instead of suppressed (password) input
 * @param partial   true when an output line was flushed by the idle timer without a newline
 * @param file      journal-directory-relative screenshot path; only for SCREENSHOT entries
 */
public record SessionJournalLogEntry(
    long seq,
    OffsetDateTime timestamp,
    Kind kind,
    String text,
    boolean redacted,
    boolean partial,
    String file) {

    public enum Kind {
        /** Server output line. */
        OUT("out"),
        /** Assembled user input line. */
        IN("in"),
        /** Scrollback line imported by a retroactive enable. */
        SEED("seed"),
        /** Screenshot reference. */
        SCREENSHOT("screenshot"),
        /** System marker written by the journal pipeline itself. */
        NOTE("note");

        private final String key;

        Kind(String key) {
            this.key = key;
        }

        public String key() {
            return key;
        }

        public static Kind fromKey(String key) {
            for (Kind kind : values()) {
                if (kind.key.equals(key)) {
                    return kind;
                }
            }
            return null;
        }
    }
}
