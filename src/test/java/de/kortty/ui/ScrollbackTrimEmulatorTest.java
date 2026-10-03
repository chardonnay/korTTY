package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.ArrayTerminalDataStream;
import com.sithtermfx.core.CursorShape;
import com.sithtermfx.core.TerminalDisplay;
import com.sithtermfx.core.emulator.SithEmulator;
import com.sithtermfx.core.emulator.mouse.MouseFormat;
import com.sithtermfx.core.emulator.mouse.MouseMode;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalSelection;
import com.sithtermfx.core.model.TerminalTextBuffer;
import de.kortty.ui.ScrollbackTrimTracker.Trim;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.TreeMap;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testng.annotations.Test;

/**
 * The scrollback trims the command-timestamp marks see when real terminal output runs through the
 * emulator, and what those trims may do to the marks.
 *
 * <p>{@link ScrollbackTrimTrackerTest} drives the vendor buffer through its methods; these cases
 * feed escape sequences into a {@link SithTerminal}, the way a shell's output reaches a tab, observe
 * on every model change like {@code TerminalView}'s model listener and apply each drained trim the
 * way {@code TerminalView.applyScrollbackTrim} does.
 */
class ScrollbackTrimEmulatorTest {

    private static final String ESC = "\u001B";
    /** What ncurses' {@code clear} prints on Linux for xterm-256color: clear_screen, then E3. */
    private static final String LINUX_CLEAR = ESC + "[H" + ESC + "[2J" + ESC + "[3J";
    /** What {@code /usr/bin/clear} prints on macOS for xterm-256color: E3 first. */
    private static final String MACOS_CLEAR = ESC + "[3J" + ESC + "[H" + ESC + "[2J";

    private static final int MAX_HISTORY = 10;
    private static final int ROWS = 5;
    private static final LocalDateTime T0 = LocalDateTime.of(2026, 10, 2, 9, 0);

    @Test
    void outputLongerThanTheScrollbackShiftsTheMarksByEveryLineThatScrolledOut() throws IOException {
        Session session = new Session();
        session.process(lines(0, MAX_HISTORY + ROWS));
        session.drain();
        session.marks.put(MAX_HISTORY + 1, T0);
        session.marks.put(MAX_HISTORY - 3, T0.plusSeconds(5));

        session.process(lines(100, 4));

        assertThat(session.buffer.getHistoryLinesCount()).isEqualTo(MAX_HISTORY);
        assertWithMessage("four lines scrolled out of a full scrollback: the marks follow their lines")
                .that(session.drain()).isEqualTo(Trim.shift(4));
        assertThat(session.marks.keySet()).containsExactly(MAX_HISTORY - 7, MAX_HISTORY - 3).inOrder();

        session.process(lines(200, 3 * MAX_HISTORY));
        assertThat(session.drain()).isEqualTo(Trim.shift(3 * MAX_HISTORY));
        assertWithMessage("a burst longer than the scrollback drops every mark it pushed out")
                .that(session.marks).isEmpty();
    }

    @Test
    void bothClearOrdersWipeTheMarks() throws IOException {
        for (String clear : List.of(LINUX_CLEAR, MACOS_CLEAR)) {
            Session session = new Session();
            session.process(lines(0, MAX_HISTORY + 2));
            session.drain();
            session.marks.put(3, T0);
            session.marks.put(MAX_HISTORY + 1, T0.plusSeconds(1));

            session.process(clear);

            assertWithMessage("clear empties the screen and the scrollback, so every mark goes")
                    .that(session.drain()).isEqualTo(Trim.cleared());
            assertThat(session.marks).isEmpty();
        }
    }

    @Test
    void aFullScreenProgramLeavesThePrimaryMarksAlone() throws IOException {
        Session session = new Session();
        session.process(lines(0, MAX_HISTORY + ROWS));
        session.drain();
        session.marks.put(4, T0);

        session.process(ESC + "[?1049h" + lines(500, 3 * MAX_HISTORY) + ESC + "[?1049l");
        session.drain();

        assertWithMessage("the program scrolls the alternate screen's history, not the primary one")
                .that(session.marks.keySet()).containsExactly(4);
        session.process(lines(900, 2));
        assertThat(session.drain()).isEqualTo(Trim.shift(2));
        assertThat(session.marks.keySet()).containsExactly(2);
    }

    @Test
    void onlyAClearOrARealShiftMovesMarks() {
        assertThat(TerminalView.scrollbackTrimMovesMarks(Trim.cleared())).isTrue();
        assertThat(TerminalView.scrollbackTrimMovesMarks(Trim.shift(4))).isTrue();
        assertThat(TerminalView.scrollbackTrimMovesMarks(Trim.none())).isFalse();
        assertWithMessage("a width reflow keeps the marks where they are")
                .that(TerminalView.scrollbackTrimMovesMarks(Trim.unknown())).isFalse();
        assertThat(TerminalView.scrollbackTrimMovesMarks(Trim.suspended())).isFalse();
        assertThat(TerminalView.scrollbackTrimMovesMarks(null)).isFalse();
    }

    private static String lines(int first, int count) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < count; i++) {
            text.append("line ").append(first + i).append("\r\n");
        }
        return text.toString();
    }

    private static final class Session {
        private final TerminalTextBuffer buffer;
        private final SithTerminal terminal;
        private final ScrollbackTrimTracker tracker;
        private TreeMap<Integer, LocalDateTime> marks = new TreeMap<>();

        Session() {
            StyleState styleState = new StyleState();
            buffer = new TerminalTextBuffer(20, ROWS, styleState, MAX_HISTORY, null);
            terminal = new SithTerminal(new NoopDisplay(), buffer, styleState);
            tracker = ScrollbackTrimTracker.forBuffer(buffer);
            buffer.addModelListener(tracker::observe);
        }

        void process(String data) throws IOException {
            SithEmulator emulator = new SithEmulator(new ArrayTerminalDataStream(data.toCharArray()), terminal);
            while (emulator.hasNext()) {
                emulator.next();
            }
        }

        /** One FX batch of the model listener: drain, then apply as TerminalView does. */
        Trim drain() {
            Trim trim = tracker.drain();
            if (TerminalView.scrollbackTrimMovesMarks(trim)) {
                marks = trim.kind() == Trim.Kind.CLEARED
                        ? new TreeMap<>()
                        : TimestampHistory.shift(marks, trim.lines());
            }
            return trim;
        }
    }

    /** Minimal display: SithTermFX's own test doubles are not published in the artifact. */
    private static final class NoopDisplay implements TerminalDisplay {

        @Override
        public void setCursor(int x, int y) {
        }

        @Override
        public void setCursorShape(@Nullable CursorShape cursorShape) {
        }

        @Override
        public void beep() {
        }

        @Override
        public void scrollArea(int scrollRegionTop, int scrollRegionSize, int dy) {
        }

        @Override
        public void setCursorVisible(boolean isCursorVisible) {
        }

        @Override
        public void useAlternateScreenBuffer(boolean useAlternateScreenBuffer) {
        }

        @Override
        public String getWindowTitle() {
            return "";
        }

        @Override
        public void setWindowTitle(@NotNull String windowTitle) {
        }

        @Override
        public @Nullable TerminalSelection getSelection() {
            return null;
        }

        @Override
        public void terminalMouseModeSet(@NotNull MouseMode mouseMode) {
        }

        @Override
        public void setMouseFormat(@NotNull MouseFormat mouseFormat) {
        }

        @Override
        public boolean ambiguousCharsAreDoubleWidth() {
            return false;
        }
    }
}
