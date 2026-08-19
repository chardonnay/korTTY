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
 * @param repeat    number of occurrences this entry represents (coalesced duplicate OUT lines).
 *                  A run of K identical lines is stored as the head entry (repeat 1, written
 *                  immediately) plus one repeat entry with {@code repeat = K - 1} when the run
 *                  breaks, so the sum of {@code repeat} over all entries equals the original
 *                  line count. The head carries the first occurrence time, the repeat entry the
 *                  last, so the pair brackets the run's duration.
 */
public record SessionJournalLogEntry(
    long seq,
    OffsetDateTime timestamp,
    Kind kind,
    String text,
    boolean redacted,
    boolean partial,
    String file,
    int repeat) {

    public SessionJournalLogEntry {
        if (repeat < 1) {
            repeat = 1;
        }
    }

    /** Single-occurrence entry ({@code repeat = 1}) — the shape every non-coalesced line has. */
    public SessionJournalLogEntry(
            long seq,
            OffsetDateTime timestamp,
            Kind kind,
            String text,
            boolean redacted,
            boolean partial,
            String file) {
        this(seq, timestamp, kind, text, redacted, partial, file, 1);
    }

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
