package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.model.CharBuffer;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalLine;
import com.sithtermfx.core.model.TerminalTextBuffer;
import de.kortty.ui.ScrollbackTrimTracker.Trim;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

/**
 * How many lines the scrollback dropped from its top, as the command-timestamp marks need it.
 *
 * <p>The marks are keyed by absolute line, so a full scrollback that trims {@code n} lines must
 * move every key by exactly {@code n}; anything else makes the marks drift away from their command
 * lines and makes every new prompt on the bottom row collide with the previous one.
 */
class ScrollbackTrimTrackerTest {

    @Test
    void anUnchangedHistoryReportsNoShift() {
        FakeLines lines = new FakeLines(5);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        assertThat(tracker.poll()).isEqualTo(Trim.none());
        assertThat(tracker.poll()).isEqualTo(Trim.none());
    }

    @Test
    void aGrowingHistoryIsNotATrim() {
        FakeLines lines = new FakeLines(5);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        lines.append(4);

        assertWithMessage("lines appended below the anchor keep every absolute line where it was")
                .that(tracker.poll()).isEqualTo(Trim.none());
    }

    @Test
    void droppingThreeTopLinesReportsAShiftOfThree() {
        FakeLines lines = new FakeLines(10);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        lines.append(3);
        lines.trimTop(3);

        assertThat(tracker.poll()).isEqualTo(Trim.shift(3));
        assertWithMessage("a poll reports what happened since the previous one, not since the start")
                .that(tracker.poll()).isEqualTo(Trim.none());
    }

    @Test
    void observedTrimsAddUpUntilThePoll() {
        FakeLines lines = new FakeLines(10);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        for (int i = 0; i < 4; i++) {
            lines.append(1);
            lines.trimTop(1);
            tracker.observe();
        }
        lines.append(2);
        lines.trimTop(2);

        assertThat(tracker.poll()).isEqualTo(Trim.shift(6));
    }

    @Test
    void drainHandsOverWhatWasObservedWithoutReadingTheBuffer() {
        FakeLines lines = new FakeLines(10);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);
        lines.append(2);
        lines.trimTop(2);
        tracker.observe();
        lines.reads = 0;

        assertThat(tracker.drain()).isEqualTo(Trim.shift(2));
        assertWithMessage("the UI half of the model listener must not contend for the buffer lock")
                .that(lines.reads).isEqualTo(0);
        assertThat(lines.lockDepth).isEqualTo(0);
        assertThat(tracker.drain()).isEqualTo(Trim.none());
    }

    @Test
    void drainDoesNotSeeAChangeThatWasNeverObserved() {
        FakeLines lines = new FakeLines(10);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);
        lines.append(1);
        lines.trimTop(1);

        assertThat(tracker.drain()).isEqualTo(Trim.none());
        assertWithMessage("the next poll measures and still reports the trim")
                .that(tracker.poll()).isEqualTo(Trim.shift(1));
    }

    @Test
    void aClearedHistoryReportsCleared() {
        FakeLines lines = new FakeLines(8);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        lines.clear();

        assertThat(tracker.poll()).isEqualTo(Trim.cleared());
        assertWithMessage("an empty history that stays empty is not cleared again")
                .that(tracker.poll()).isEqualTo(Trim.none());
    }

    @Test
    void aClearWinsOverShiftsObservedAfterIt() {
        FakeLines lines = new FakeLines(8);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        lines.append(1);
        lines.trimTop(1);
        tracker.observe();
        lines.clear();
        tracker.observe();
        lines.append(5);
        tracker.observe();
        lines.append(1);
        lines.trimTop(1);

        assertWithMessage("every mark recorded before the clear is gone, whatever moved afterwards")
                .that(tracker.poll()).isEqualTo(Trim.cleared());
    }

    @Test
    void anEmptiedScreenClearsATerminalThatHasNotScrolledYet() {
        FakeLines lines = new FakeLines(0);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        lines.setScreen(0);

        assertWithMessage("with no history every mark sits on a screen row, and Clear Buffer empties the screen")
                .that(tracker.poll()).isEqualTo(Trim.cleared());
        lines.setScreen(1);
        assertThat(tracker.poll()).isEqualTo(Trim.none());
    }

    @Test
    void anEmptiedScreenIsNotAClearWhileTheHistoryStillHoldsLines() {
        FakeLines lines = new FakeLines(5);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        lines.setScreen(0);

        assertThat(tracker.poll()).isEqualTo(Trim.none());
    }

    @Test
    void aFreshTerminalThatStartsWritingIsNotCleared() {
        FakeLines lines = new FakeLines(0);
        lines.setScreen(0);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        assertThat(tracker.poll()).isEqualTo(Trim.none());
        lines.setScreen(3);
        assertThat(tracker.poll()).isEqualTo(Trim.none());
    }

    @Test
    void anEmptyHistoryThatNeverHadLinesIsNotCleared() {
        FakeLines lines = new FakeLines(0);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        assertThat(tracker.poll()).isEqualTo(Trim.none());
        lines.append(3);
        assertThat(tracker.poll()).isEqualTo(Trim.none());
        lines.append(1);
        lines.trimTop(2);
        assertThat(tracker.poll()).isEqualTo(Trim.shift(2));
    }

    @Test
    void theAlternateScreenIsANoOpAndTheAnchorSurvivesTheRoundTrip() {
        FakeLines lines = new FakeLines(6);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        lines.enterAlternateScreen();
        assertThat(tracker.poll()).isEqualTo(Trim.suspended());
        assertThat(tracker.poll()).isEqualTo(Trim.suspended());
        lines.leaveAlternateScreen();

        assertWithMessage("vim and less must not move or drop the marks of the primary history")
                .that(tracker.poll()).isEqualTo(Trim.none());
        lines.append(2);
        lines.trimTop(2);
        assertWithMessage("the anchor taken before the alternate screen still measures afterwards")
                .that(tracker.poll()).isEqualTo(Trim.shift(2));
    }

    @Test
    void aShiftObservedBeforeTheAlternateScreenIsStillReported() {
        FakeLines lines = new FakeLines(6);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        lines.append(1);
        lines.trimTop(1);
        tracker.observe();
        lines.enterAlternateScreen();

        assertThat(tracker.poll()).isEqualTo(Trim.shift(1));
    }

    @Test
    void replacedLineObjectsReportUnknownAndReanchor() {
        FakeLines lines = new FakeLines(6);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        lines.replaceAll(9);

        assertWithMessage("a width reflow rebuilds every line: no shift can be claimed")
                .that(tracker.poll()).isEqualTo(Trim.unknown());
        lines.append(1);
        lines.trimTop(1);
        assertWithMessage("after re-anchoring the tracker measures exactly again")
                .that(tracker.poll()).isEqualTo(Trim.shift(1));
    }

    @Test
    void resetForgetsWhatWasAccumulated() {
        FakeLines lines = new FakeLines(6);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);

        lines.append(2);
        lines.trimTop(2);
        tracker.observe();
        tracker.reset();

        assertThat(tracker.poll()).isEqualTo(Trim.none());
    }

    @Test
    void theTrackerTakesTheSourceLockForEveryRead() {
        FakeLines lines = new FakeLines(4);
        ScrollbackTrimTracker tracker = new ScrollbackTrimTracker(lines);
        lines.readsOutsideLock = 0;

        tracker.observe();
        lines.append(1);
        lines.trimTop(1);
        tracker.poll();
        tracker.reset();

        assertThat(lines.readsOutsideLock).isEqualTo(0);
        assertThat(lines.lockDepth).isEqualTo(0);
    }

    @Test
    void aFullVendorBufferReportsTheExactTrimmedCount() {
        TerminalTextBuffer buffer = newBuffer();
        ScrollbackTrimTracker tracker = ScrollbackTrimTracker.forBuffer(buffer);
        Pusher pusher = new Pusher(buffer);

        pusher.push(MAX_HISTORY);
        assertThat(buffer.getHistoryLinesCount()).isEqualTo(MAX_HISTORY);
        assertWithMessage("filling the history up to its limit trims nothing")
                .that(tracker.poll()).isEqualTo(Trim.none());

        pusher.push(3);
        assertWithMessage("the history count stays at its limit, so only the anchor can tell")
                .that(buffer.getHistoryLinesCount()).isEqualTo(MAX_HISTORY);
        assertThat(tracker.poll()).isEqualTo(Trim.shift(3));

        pusher.push(MAX_HISTORY - 1);
        assertThat(tracker.poll()).isEqualTo(Trim.shift(MAX_HISTORY - 1));
    }

    @Test
    void observingFromTheModelListenerCountsABurstLongerThanTheWholeScrollback() {
        TerminalTextBuffer buffer = newBuffer();
        ScrollbackTrimTracker tracker = ScrollbackTrimTracker.forBuffer(buffer);
        Pusher pusher = new Pusher(buffer);
        pusher.push(MAX_HISTORY);
        tracker.poll();
        // What TerminalView does: observe on every model change, drain later on the FX thread.
        buffer.addModelListener(tracker::observe);

        pusher.push(3 * MAX_HISTORY + 5);

        assertWithMessage("seq 300 against a scrollback of 100 must still move every mark by 300")
                .that(tracker.drain()).isEqualTo(Trim.shift(3 * MAX_HISTORY + 5));
    }

    @Test
    void withoutObservingABurstLongerThanTheScrollbackLosesTheAnchor() {
        TerminalTextBuffer buffer = newBuffer();
        ScrollbackTrimTracker tracker = ScrollbackTrimTracker.forBuffer(buffer);
        Pusher pusher = new Pusher(buffer);
        pusher.push(MAX_HISTORY);
        tracker.poll();

        pusher.push(MAX_HISTORY + 2);

        assertWithMessage("this is why TerminalView observes on the emulator thread")
                .that(tracker.poll()).isEqualTo(Trim.unknown());
    }

    @Test
    void clearingTheVendorHistoryReportsCleared() {
        TerminalTextBuffer buffer = newBuffer();
        ScrollbackTrimTracker tracker = ScrollbackTrimTracker.forBuffer(buffer);
        Pusher pusher = new Pusher(buffer);
        pusher.push(4);
        tracker.poll();

        buffer.clearHistory();

        assertThat(tracker.poll()).isEqualTo(Trim.cleared());
    }

    @Test
    void clearBufferOnAVendorBufferThatNeverScrolledReportsCleared() {
        TerminalTextBuffer buffer = newBuffer();
        ScrollbackTrimTracker tracker = ScrollbackTrimTracker.forBuffer(buffer);
        buffer.addModelListener(tracker::observe);
        new Pusher(buffer);
        assertThat(buffer.getHistoryLinesCount()).isEqualTo(0);
        assertWithMessage("writing a fresh screen is not a clear").that(tracker.drain()).isEqualTo(Trim.none());

        // The steps of the vendor's TerminalPanel.clearBuffer(true), cursor on the third row.
        TerminalLine cursorLine = buffer.getLine(2);
        buffer.clearHistory();
        buffer.clearScreenBuffer();
        buffer.addLine(cursorLine);

        assertThat(tracker.drain()).isEqualTo(Trim.cleared());
    }

    @Test
    void writingAndScrollingAFreshVendorBufferNeverReportsCleared() {
        TerminalTextBuffer buffer = newBuffer();
        ScrollbackTrimTracker tracker = ScrollbackTrimTracker.forBuffer(buffer);
        buffer.addModelListener(tracker::observe);

        Pusher pusher = new Pusher(buffer);
        pusher.push(2 * MAX_HISTORY);

        assertThat(tracker.drain()).isEqualTo(Trim.shift(MAX_HISTORY));
    }

    @Test
    void theVendorAlternateScreenLeavesThePrimaryAnchorAlone() {
        TerminalTextBuffer buffer = newBuffer();
        ScrollbackTrimTracker tracker = ScrollbackTrimTracker.forBuffer(buffer);
        buffer.addModelListener(tracker::observe);
        Pusher pusher = new Pusher(buffer);
        pusher.push(MAX_HISTORY);
        tracker.poll();

        buffer.useAlternateBuffer(true);
        new Pusher(buffer).push(MAX_HISTORY + 4);
        assertThat(tracker.poll()).isEqualTo(Trim.suspended());
        buffer.useAlternateBuffer(false);

        assertThat(tracker.poll()).isEqualTo(Trim.none());
        pusher.push(2);
        assertThat(tracker.poll()).isEqualTo(Trim.shift(2));
    }

    private static final int MAX_HISTORY = 10;
    private static final int ROWS = 5;

    private static TerminalTextBuffer newBuffer() {
        // Built like de.kortty.core.headless.HeadlessTerminal builds its buffer, minus the emulator.
        return new TerminalTextBuffer(20, ROWS, new StyleState(), MAX_HISTORY, null);
    }

    /** Scrolls the full screen up one line at a time, as a newline on the bottom row does. */
    private static final class Pusher {
        private final TerminalTextBuffer buffer;
        private int next;

        Pusher(TerminalTextBuffer buffer) {
            this.buffer = buffer;
            for (int row = 1; row <= ROWS; row++) {
                buffer.writeString(0, row, new CharBuffer("screen " + row));
            }
        }

        void push(int count) {
            for (int i = 0; i < count; i++) {
                buffer.lock();
                try {
                    buffer.scrollArea(1, -1, ROWS);
                    buffer.writeString(0, ROWS, new CharBuffer("line " + next++));
                } finally {
                    buffer.unlock();
                }
            }
        }
    }

    /** A history made of plain objects, compared by identity like the vendor's lines. */
    private static final class FakeLines implements ScrollbackTrimTracker.LineSource {
        private final List<Object> history = new ArrayList<>();
        private int screen = 24;
        private boolean alternate;
        int lockDepth;
        int reads;
        int readsOutsideLock;

        FakeLines(int initial) {
            append(initial);
        }

        void append(int count) {
            for (int i = 0; i < count; i++) {
                history.add(new Object());
            }
        }

        void trimTop(int count) {
            history.subList(0, count).clear();
        }

        void clear() {
            history.clear();
        }

        void replaceAll(int count) {
            history.clear();
            append(count);
        }

        void setScreen(int lines) {
            screen = lines;
        }

        void enterAlternateScreen() {
            alternate = true;
        }

        void leaveAlternateScreen() {
            alternate = false;
        }

        @Override
        public int historyCount() {
            noteRead();
            // The vendor buffer swaps in an empty history while the alternate screen is active.
            return alternate ? 0 : history.size();
        }

        @Override
        public Object historyLine(int index) {
            noteRead();
            if (alternate) {
                throw new AssertionError("the swapped-out history must not be read");
            }
            return history.get(index);
        }

        @Override
        public int screenCount() {
            noteRead();
            return screen;
        }

        @Override
        public boolean alternateScreen() {
            noteRead();
            return alternate;
        }

        @Override
        public void lock() {
            lockDepth++;
        }

        @Override
        public void unlock() {
            lockDepth--;
        }

        private void noteRead() {
            reads++;
            if (lockDepth <= 0) {
                readsOutsideLock++;
            }
        }
    }
}
