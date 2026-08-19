package de.kortty.core;

import org.testng.annotations.Test;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalOutputCoalescerTest {

    private static final OffsetDateTime BASE = OffsetDateTime.of(2026, 8, 19, 10, 0, 0, 0, ZoneOffset.ofHours(2));

    private static OffsetDateTime at(int second) {
        return BASE.plusSeconds(second);
    }

    private static long millis(int second) {
        return 1_000_000L + second * 1000L;
    }

    @Test
    void aRunBreakEmitsTheCountedTailWithLastOccurrenceTime() {
        SessionJournalOutputCoalescer coalescer = new SessionJournalOutputCoalescer(1000, p -> true);

        assertThat(coalescer.onOutputLine("ping timeout", at(0), millis(0)).suppressed()).isFalse();
        assertThat(coalescer.onOutputLine("ping timeout", at(1), millis(1)).suppressed()).isTrue();
        assertThat(coalescer.onOutputLine("ping timeout", at(2), millis(2)).suppressed()).isTrue();

        SessionJournalOutputCoalescer.OnLine breaking = coalescer.onOutputLine("connected", at(3), millis(3));
        assertThat(breaking.suppressed()).isFalse();
        assertThat(breaking.flush()).isNotNull();
        assertThat(breaking.flush().text()).isEqualTo("ping timeout");
        assertThat(breaking.flush().count()).isEqualTo(2);
        assertThat(breaking.flush().lastOccurrence()).isEqualTo(at(2));
    }

    @Test
    void aSingleOccurrenceFlushesNothingOnBreak() {
        SessionJournalOutputCoalescer coalescer = new SessionJournalOutputCoalescer(1000, p -> true);
        coalescer.onOutputLine("one", at(0), millis(0));

        SessionJournalOutputCoalescer.OnLine next = coalescer.onOutputLine("two", at(1), millis(1));
        assertThat(next.suppressed()).isFalse();
        assertThat(next.flush()).isNull();
        assertThat(coalescer.takePending()).isNull();
    }

    @Test
    void theRepeatCapFlushesMidRunAndTheRunKeepsCounting() {
        SessionJournalOutputCoalescer coalescer = new SessionJournalOutputCoalescer(3, p -> true);
        coalescer.onOutputLine("y", at(0), millis(0));

        assertThat(coalescer.onOutputLine("y", at(1), millis(1)).flush()).isNull();
        assertThat(coalescer.onOutputLine("y", at(2), millis(2)).flush()).isNull();
        SessionJournalOutputCoalescer.OnLine capped = coalescer.onOutputLine("y", at(3), millis(3));
        assertThat(capped.suppressed()).isTrue();
        assertThat(capped.flush().count()).isEqualTo(3);

        // The run is still open: the next duplicate counts from zero again.
        assertThat(coalescer.onOutputLine("y", at(4), millis(4)).suppressed()).isTrue();
        SessionJournalOutputCoalescer.PendingRepeat tail = coalescer.takePending();
        assertThat(tail.count()).isEqualTo(1);
        assertThat(tail.lastOccurrence()).isEqualTo(at(4));
    }

    @Test
    void takePendingClosesTheRunSoTheNextIdenticalLineIsANewHead() {
        SessionJournalOutputCoalescer coalescer = new SessionJournalOutputCoalescer(1000, p -> true);
        coalescer.onOutputLine("repeat me", at(0), millis(0));
        coalescer.onOutputLine("repeat me", at(1), millis(1));

        assertThat(coalescer.takePending().count()).isEqualTo(1);
        // After an input line broke the run, the same text starts fresh — a new head.
        assertThat(coalescer.onOutputLine("repeat me", at(2), millis(2)).suppressed()).isFalse();
    }

    @Test
    void idleFlushEmitsTheCounterButKeepsTheRunOpen() {
        List<SessionJournalOutputCoalescer.PendingRepeat> emitted = new ArrayList<>();
        SessionJournalOutputCoalescer coalescer = new SessionJournalOutputCoalescer(1000, emitted::add);
        coalescer.onOutputLine("tick", at(0), millis(0));
        coalescer.onOutputLine("tick", at(1), millis(1));

        coalescer.flushIfIdle(1500, millis(1) + 1000);
        assertThat(emitted).isEmpty(); // not idle yet

        coalescer.flushIfIdle(1500, millis(1) + 2000);
        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).count()).isEqualTo(1);

        // Run stayed open: another identical line is still suppressed.
        assertThat(coalescer.onOutputLine("tick", at(5), millis(5)).suppressed()).isTrue();
    }

    @Test
    void aRefusedIdleFlushKeepsTheCounterForALaterRetry() {
        List<SessionJournalOutputCoalescer.PendingRepeat> emitted = new ArrayList<>();
        boolean[] accept = {false};
        SessionJournalOutputCoalescer coalescer = new SessionJournalOutputCoalescer(1000, p -> {
            if (!accept[0]) {
                return false;
            }
            emitted.add(p);
            return true;
        });
        coalescer.onOutputLine("busy", at(0), millis(0));
        coalescer.onOutputLine("busy", at(1), millis(1));

        coalescer.flushIfIdle(1500, millis(1) + 2000); // queue "full": refused
        assertThat(emitted).isEmpty();

        coalescer.onOutputLine("busy", at(4), millis(4)); // counting continued meanwhile
        accept[0] = true;
        coalescer.flushIfIdle(1500, millis(4) + 2000);
        assertThat(emitted).hasSize(1);
        assertThat(emitted.get(0).count()).isEqualTo(2); // nothing was lost across the refusal
    }

    @Test
    void aRefusedTryFlushKeepsRunAndCounterUntouched() {
        SessionJournalOutputCoalescer coalescer = new SessionJournalOutputCoalescer(1000, p -> false);
        coalescer.onOutputLine("note incoming", at(0), millis(0));
        coalescer.onOutputLine("note incoming", at(1), millis(1));

        coalescer.tryFlushPending(); // refused: run and counter survive
        SessionJournalOutputCoalescer.PendingRepeat tail = coalescer.takePending();
        assertThat(tail.count()).isEqualTo(1);
    }

    @Test
    void anAcceptedTryFlushClosesTheRun() {
        List<SessionJournalOutputCoalescer.PendingRepeat> emitted = new ArrayList<>();
        SessionJournalOutputCoalescer coalescer = new SessionJournalOutputCoalescer(1000, emitted::add);
        coalescer.onOutputLine("note incoming", at(0), millis(0));
        coalescer.onOutputLine("note incoming", at(1), millis(1));

        coalescer.tryFlushPending();
        assertThat(emitted).hasSize(1);
        // Run closed: the same text is a fresh head afterwards.
        assertThat(coalescer.onOutputLine("note incoming", at(2), millis(2)).suppressed()).isFalse();
    }

    @Test
    void distinctLinesNeverCoalesce() {
        SessionJournalOutputCoalescer coalescer = new SessionJournalOutputCoalescer(1000, p -> true);
        for (int i = 0; i < 5; i++) {
            SessionJournalOutputCoalescer.OnLine decision =
                coalescer.onOutputLine("line " + i, at(i), millis(i));
            assertThat(decision.suppressed()).isFalse();
            assertThat(decision.flush()).isNull();
        }
    }
}
