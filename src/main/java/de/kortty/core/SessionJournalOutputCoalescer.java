package de.kortty.core;

import java.time.OffsetDateTime;
import java.util.function.Predicate;

/**
 * Syslog-style duplicate coalescing for full (non-partial) OUT lines: the first occurrence of a
 * line is written immediately (live view, crash safety); consecutive identical follow-ups are
 * only counted here, and when the run breaks — a different line, an input/note/screenshot entry,
 * the idle tick, the repeat cap, or session close — one repeat entry carrying the count is
 * written. The sum of {@code repeat} over all entries equals the original line count.
 *
 * <p>Callers coalesce the already-redacted text, so two different secrets redacting to the same
 * placeholder line coalesce correctly, and suppressed duplicates never consume sequence numbers.
 *
 * <p>Threading: all methods are synchronized on this object; it is consulted by the connector
 * reader thread, the shared idle-flush thread, and FX-origin entry paths. Blocking enqueues must
 * happen OUTSIDE this monitor (via the taken {@link PendingRepeat}), or a backpressure-blocked
 * capture thread would stall the shared idle flusher of every session. The non-blocking
 * {@code tryEmitter} runs inside the monitor on purpose: taking the counter and offering the
 * entry atomically is what lets a refused offer keep the counter without any restore race.</p>
 */
final class SessionJournalOutputCoalescer {

    /** A closed run tail: {@code count} suppressed occurrences of {@code text}, last one at {@code lastOccurrence}. */
    record PendingRepeat(String text, int count, OffsetDateTime lastOccurrence) {
    }

    /**
     * Decision for one full OUT line: {@code suppressed} = the line is a counted duplicate and
     * must not be enqueued; {@code flush} = a repeat entry that must be enqueued first (run break
     * or repeat cap), or null.
     */
    record OnLine(boolean suppressed, PendingRepeat flush) {
    }

    private final int repeatCap;
    private final Predicate<PendingRepeat> tryEmitter;

    private String pendingText;
    private int suppressedCount;
    private OffsetDateTime lastOccurrence;
    private long lastOccurrenceMillis;

    /**
     * @param repeatCap  flush a repeat entry after this many suppressed duplicates, so a flood
     *                   bounds crash loss and the live panel's counter keeps moving
     * @param tryEmitter non-blocking emit used by the idle/FX flush paths; returns whether the
     *                   entry was accepted (a refusal keeps the counter for a later retry)
     */
    SessionJournalOutputCoalescer(int repeatCap, Predicate<PendingRepeat> tryEmitter) {
        this.repeatCap = Math.max(1, repeatCap);
        this.tryEmitter = tryEmitter;
    }

    /** Decides suppression for one full (non-partial) OUT line; see {@link OnLine}. */
    synchronized OnLine onOutputLine(String text, OffsetDateTime now, long nowMillis) {
        if (pendingText != null && pendingText.equals(text)) {
            suppressedCount++;
            lastOccurrence = now;
            lastOccurrenceMillis = nowMillis;
            if (suppressedCount >= repeatCap) {
                // Cap flush: the run stays open, only the counter is written out.
                return new OnLine(true, takeCounter());
            }
            return new OnLine(true, null);
        }
        PendingRepeat flush = takeCounter();
        pendingText = text;
        return new OnLine(false, flush);
    }

    /**
     * Closes the run entirely and returns its counted tail (null when there is none). Used by
     * run breaks that must preserve ordering losslessly (input lines, seeds, session close); the
     * caller enqueues the result blocking, outside this monitor.
     */
    synchronized PendingRepeat takePending() {
        PendingRepeat pending = takeCounter();
        pendingText = null;
        return pending;
    }

    /**
     * Attempts a non-blocking flush-and-close of the run via the emitter. On refusal the run and
     * counter stay untouched for a later retry. Used by FX-origin entry paths, where a bounded
     * hiccup is acceptable but counted duplicates must never be lost.
     */
    synchronized void tryFlushPending() {
        if (suppressedCount == 0) {
            pendingText = null;
            return;
        }
        if (tryEmitter.test(new PendingRepeat(pendingText, suppressedCount, lastOccurrence))) {
            suppressedCount = 0;
            pendingText = null;
        }
    }

    /**
     * Emits the counter once the run has been idle for {@code idleMillis} (the run itself stays
     * open — a later identical line keeps counting). Bounds both the live panel's staleness and
     * the crash window of a counted-but-unwritten tail.
     */
    synchronized void flushIfIdle(long idleMillis, long nowMillis) {
        if (suppressedCount == 0 || nowMillis - lastOccurrenceMillis < idleMillis) {
            return;
        }
        if (tryEmitter.test(new PendingRepeat(pendingText, suppressedCount, lastOccurrence))) {
            suppressedCount = 0;
        }
    }

    private PendingRepeat takeCounter() {
        if (suppressedCount == 0) {
            return null;
        }
        PendingRepeat pending = new PendingRepeat(pendingText, suppressedCount, lastOccurrence);
        suppressedCount = 0;
        return pending;
    }
}
