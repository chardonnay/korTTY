package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import org.testng.annotations.Test;

class BracketedPasteTrackerTest {

    private static final String ESC = "\u001b";

    @Test
    void constantsAreTheDecsetAndDecrstSequences() {
        assertThat(BracketedPasteTracker.ENABLE).isEqualTo(ESC + "[?2004h");
        assertThat(BracketedPasteTracker.DISABLE).isEqualTo(ESC + "[?2004l");
    }

    @Test
    void enableAndDisable() {
        BracketedPasteTracker tracker = new BracketedPasteTracker();
        assertThat(tracker.isEnabled()).isFalse();

        tracker.onData("prompt> " + BracketedPasteTracker.ENABLE);
        assertThat(tracker.isEnabled()).isTrue();

        tracker.onData("output" + BracketedPasteTracker.DISABLE + "more");
        assertThat(tracker.isEnabled()).isFalse();
    }

    @Test
    void sequenceSplitAcrossChunks() {
        BracketedPasteTracker tracker = new BracketedPasteTracker();
        tracker.onData(ESC + "[?20");
        assertThat(tracker.isEnabled()).isFalse();
        tracker.onData("04h");
        assertThat(tracker.isEnabled()).isTrue();

        tracker.onData(ESC);
        tracker.onData("[");
        tracker.onData("?");
        tracker.onData("2004");
        assertThat(tracker.isEnabled()).isTrue();
        tracker.onData("l");
        assertThat(tracker.isEnabled()).isFalse();
    }

    @Test
    void combinedParametersAreRecognised() {
        BracketedPasteTracker tracker = new BracketedPasteTracker();
        tracker.onData(ESC + "[?1049;2004h");
        assertThat(tracker.isEnabled()).isTrue();
        tracker.onData(ESC + "[?2004;1l");
        assertThat(tracker.isEnabled()).isFalse();
        tracker.onData(ESC + "[?1;2004;25h");
        assertThat(tracker.isEnabled()).isTrue();
    }

    @Test
    void unrelatedPrivateModesLeaveTheState() {
        BracketedPasteTracker tracker = new BracketedPasteTracker();
        tracker.onData(BracketedPasteTracker.ENABLE);
        tracker.onData(ESC + "[?1049h" + ESC + "[?25l" + ESC + "[?12004h" + ESC + "[?20041l");
        assertThat(tracker.isEnabled()).isTrue();
        tracker.onData(ESC + "[?1049l");
        assertThat(tracker.isEnabled()).isTrue();
        tracker.onData(ESC + "[2004l" + ESC + "[?2004");
        assertThat(tracker.isEnabled()).isTrue();
    }

    @Test
    void lastOccurrenceInAChunkWins() {
        BracketedPasteTracker tracker = new BracketedPasteTracker();
        tracker.onData(BracketedPasteTracker.ENABLE + "x" + BracketedPasteTracker.DISABLE);
        assertThat(tracker.isEnabled()).isFalse();
        tracker.onData(BracketedPasteTracker.DISABLE + BracketedPasteTracker.ENABLE + BracketedPasteTracker.DISABLE
            + BracketedPasteTracker.ENABLE);
        assertThat(tracker.isEnabled()).isTrue();
    }

    @Test
    void resetForgetsModeAndCarry() {
        BracketedPasteTracker tracker = new BracketedPasteTracker();
        tracker.onData(BracketedPasteTracker.ENABLE + ESC + "[?20");
        assertThat(tracker.isEnabled()).isTrue();
        assertThat(tracker.carry()).isNotEmpty();

        tracker.reset();

        assertThat(tracker.isEnabled()).isFalse();
        assertThat(tracker.carry()).isEmpty();
        tracker.onData("04l");
        assertThat(tracker.isEnabled()).isFalse();
    }

    @Test
    void scanIsPureAndEquivalent() {
        assertThat(BracketedPasteTracker.scan(BracketedPasteTracker.ENABLE, false)).isTrue();
        assertThat(BracketedPasteTracker.scan(BracketedPasteTracker.DISABLE, true)).isFalse();
        assertThat(BracketedPasteTracker.scan("plain output", true)).isTrue();
        assertThat(BracketedPasteTracker.scan("plain output", false)).isFalse();
        assertThat(BracketedPasteTracker.scan("", true)).isTrue();
        assertThat(BracketedPasteTracker.scan(null, false)).isFalse();
        assertThat(BracketedPasteTracker.scan(ESC + "[?2004", false)).isFalse();
        assertThat(BracketedPasteTracker.scan(ESC + "[?2004h" + ESC + "[?2004l" + ESC + "[?2004h", false)).isTrue();
        assertThat(BracketedPasteTracker.scan(ESC + "[?" + ESC + "[?2004h", false)).isTrue();
    }

    @Test
    void carryIsBoundedToFifteenChars() {
        BracketedPasteTracker tracker = new BracketedPasteTracker();
        tracker.onData("x".repeat(500));
        assertThat(tracker.carry()).hasLength(BracketedPasteTracker.MAX_TAIL);
        assertThat(BracketedPasteTracker.MAX_TAIL).isEqualTo(15);

        // A split sequence that fits the tail completes with the next chunk.
        tracker.onData(ESC + "[?1049;2004");
        assertThat(tracker.carry()).endsWith(ESC + "[?1049;2004");
        assertThat(tracker.carry()).hasLength(15);
        tracker.onData("h");
        assertThat(tracker.isEnabled()).isTrue();
        tracker.onData("abc");
        assertThat(tracker.carry()).isEqualTo("[?1049;2004habc");
        assertThat(tracker.carry()).hasLength(15);

        // A split sequence longer than the bound loses its ESC and is (by design) not recognised.
        tracker.onData(BracketedPasteTracker.DISABLE);
        tracker.onData(ESC + "[?1;2;3;4;5;6;7;8;9;2004");
        assertThat(tracker.carry().length()).isAtMost(15);
        tracker.onData("h");
        assertThat(tracker.isEnabled()).isFalse();
    }

    @Test
    void carryReplayDoesNotResurrectAnOlderMode() {
        BracketedPasteTracker tracker = new BracketedPasteTracker();
        tracker.onData(BracketedPasteTracker.ENABLE);
        tracker.onData(BracketedPasteTracker.DISABLE);
        tracker.onData("");
        tracker.onData("text");
        tracker.onData("more text that pushes the tail");
        assertThat(tracker.isEnabled()).isFalse();
    }
}
