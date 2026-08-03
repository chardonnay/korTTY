package de.kortty.core;

import java.util.List;

/**
 * The newest slice of a session journal capture log, windowed separately for output and input —
 * what the AI summarizer consumes.
 *
 * @param output   newest output/seed lines, ascending by seq, capped by the caller's limit
 * @param input    newest input lines, ascending by seq, capped by the caller's limit
 * @param firstSeq lowest sequence covered by either list (0 when both are empty)
 * @param lastSeq  highest sequence covered by either list (0 when both are empty)
 */
public record SessionJournalLogTail(
    List<SessionJournalLogEntry> output,
    List<SessionJournalLogEntry> input,
    long firstSeq,
    long lastSeq) {

    public boolean isEmpty() {
        return output.isEmpty() && input.isEmpty();
    }
}
