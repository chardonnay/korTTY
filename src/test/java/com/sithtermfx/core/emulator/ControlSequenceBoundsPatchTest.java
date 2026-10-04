package com.sithtermfx.core.emulator;

import com.sithtermfx.core.ArrayTerminalDataStream;
import com.sithtermfx.core.CursorShape;
import com.sithtermfx.core.TerminalDisplay;
import com.sithtermfx.core.emulator.mouse.MouseFormat;
import com.sithtermfx.core.emulator.mouse.MouseMode;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalSelection;
import com.sithtermfx.core.model.TerminalTextBuffer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testng.annotations.Test;

import java.io.IOException;

import static com.google.common.truth.Truth.assertThat;

/**
 * Regression coverage for korTTY's pinned SithTermFX 1.2.2 control-sequence bounds patch
 * ({@code patches/sithtermfx/1.2.2-control-sequence-bounds.patch}).
 *
 * <p>The chars SithTermFX cannot place in a CSI — an intermediate byte, a control character, a
 * misplaced {@code ?} — are pushed back to be read again before the sequence itself. That push-back
 * went through a fixed 1024-char array without a bounds check, so output with an overlong sequence
 * (from a malicious server, a {@code cat}-ed file, a log line) threw
 * {@link ArrayIndexOutOfBoundsException} on the emulator thread and stopped the terminal. The patch
 * drops what does not fit and always pushes back a terminated sequence; a sequence that fits is
 * pushed back exactly as before.
 *
 * <p>CI builds SithTermFX with {@code -DskipTests}, so these cases are what guards the patch.
 */
class ControlSequenceBoundsPatchTest {

    private static final String ESC = "\u001B";
    private static final int PUSH_BACK_LENGTH = 1024;

    @Test
    void aSequenceThatFitsIsPushedBackUnchanged() throws IOException {
        // vttest's control characters inside a sequence: the backspace is read first, then the CSI.
        assertThat(pushedBack("2\bC")).isEqualTo("\b" + ESC + "[2C");
        assertThat(pushedBack("?25$p")).isEqualTo("$" + ESC + "[?25p");
        assertThat(pushedBack("1;22;333 q")).isEqualTo(" " + ESC + "[1;22;333q");
    }

    @Test
    void aSequenceWithoutStrayCharsIsNotPushedBack() throws IOException {
        ArrayTerminalDataStream stream = new ArrayTerminalDataStream("1;2H".toCharArray());
        ControlSequence sequence = new ControlSequence(stream);

        assertThat(sequence.pushBackReordered(stream)).isFalse();
        assertThat(stream.isEmpty()).isTrue();
    }

    @Test
    void aSequenceThatExactlyFillsThePushBackKeepsEveryChar() throws IOException {
        String stray = "\0".repeat(PUSH_BACK_LENGTH - "\u001B[5A".length());

        assertThat(pushedBack(stray + "5A")).isEqualTo(stray + ESC + "[5A");
    }

    @Test
    void strayCharsBeyondThePushBackAreDroppedAndTheSequenceStaysTerminated() throws IOException {
        String replay = pushedBack("\0".repeat(5_000) + "A");

        assertThat(replay).isEqualTo("\0".repeat(PUSH_BACK_LENGTH - 3) + ESC + "[A");
    }

    @Test
    void theMarkerKeepsItsPlaceWhenStrayCharsAreDropped() throws IOException {
        String replay = pushedBack("?" + "\0".repeat(5_000) + "h");

        assertThat(replay).isEqualTo("\0".repeat(PUSH_BACK_LENGTH - 4) + ESC + "[?h");
    }

    @Test
    void parametersBeyondThePushBackAreDroppedWhole() throws IOException {
        String replay = pushedBack("123;".repeat(600) + "123\bm");

        assertThat(replay.length()).isAtMost(PUSH_BACK_LENGTH);
        assertThat(replay).matches("\b\u001B\\[123(;123)*m");
        // as many whole parameters as fit beside the stray char, ESC [ and the final char
        assertThat(replay.length()).isGreaterThan(PUSH_BACK_LENGTH - ";123".length());
    }

    @Test
    void anOverlongSequenceNoLongerStopsTheEmulator() throws IOException {
        Session session = new Session(10, 2);

        session.process(ESC + "[" + "\0".repeat(5_000) + "1m" + "after");

        assertThat(session.screen()).isEqualTo("after     \n          \n");
    }

    @Test
    void outputAfterOverlongParametersStillRenders() throws IOException {
        Session session = new Session(10, 2);

        session.process(ESC + "[" + "1;".repeat(3_000) + "1\0m" + "after");

        assertThat(session.screen()).isEqualTo("after     \n          \n");
    }

    /** The chars a CSI body (everything after {@code ESC [}) is pushed back as. */
    private static String pushedBack(String body) throws IOException {
        ArrayTerminalDataStream stream = new ArrayTerminalDataStream(body.toCharArray());
        ControlSequence sequence = new ControlSequence(stream);
        assertThat(stream.isEmpty()).isTrue();

        assertThat(sequence.pushBackReordered(stream)).isTrue();

        StringBuilder replay = new StringBuilder();
        while (!stream.isEmpty()) {
            replay.append(stream.getChar());
        }
        return replay.toString();
    }

    private static final class Session {

        private final TerminalTextBuffer buffer;

        private final SithTerminal terminal;

        Session(int columns, int rows) {
            StyleState styleState = new StyleState();
            buffer = new TerminalTextBuffer(columns, rows, styleState);
            terminal = new SithTerminal(new NoopDisplay(), buffer, styleState);
        }

        void process(String data) throws IOException {
            Emulator emulator = new SithEmulator(new ArrayTerminalDataStream(data.toCharArray()), terminal);
            while (emulator.hasNext()) {
                emulator.next();
            }
        }

        String screen() {
            return buffer.getScreenLines();
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
