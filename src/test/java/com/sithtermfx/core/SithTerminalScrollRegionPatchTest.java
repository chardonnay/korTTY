package com.sithtermfx.core;

import com.sithtermfx.core.emulator.Emulator;
import com.sithtermfx.core.emulator.SithEmulator;
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
 * Regression coverage for the scroll-region fix that korTTY carried as a pinned patch until it
 * shipped upstream in SithTermFX 1.2.2 (see {@code vendor-patches/0002-*.patch}).
 *
 * <p>SithTermFX has its own tests for it, but CI builds SithTermFX with {@code -DskipTests}, so
 * they never run for a korTTY change — and a later SithTermFX release could regress it unnoticed.
 * These cases re-assert the behaviour from korTTY's own suite, mirroring
 * {@code TerminalPanelBoundaryPatchTest} for the boundary patch.
 *
 * <p>Only origin mode (DECOM) confines the cursor to the scrolling region. With DECOM reset — the
 * default, and what tmux relies on — the margins bound scrolling, not line addressing: text written
 * above the top margin or below the bottom one stays on the line the application addressed.
 */
class SithTerminalScrollRegionPatchTest {

    private static final String ESC = "\u001B";

    /**
     * tmux draws its first pane row while the margins are still set to the scrolled sub-region.
     * Clamping the cursor to the top margin pushed that row one line down and stranded the cursor
     * on the blank line above the shell prompt.
     */
    @Test
    void keepsWriteAboveTopMarginOnTheAddressedLine() throws IOException {
        Session session = new Session(5, 4);

        session.process(ESC + "[2;3r" + ESC + "[1;1Hab");

        assertThat(session.screen()).isEqualTo("ab   \n     \n     \n     \n");
        assertThat(session.terminal.getCursorY()).isEqualTo(1);
    }

    /** tmux paints its status line below the bottom margin; that write must not scroll the pane. */
    @Test
    void keepsWriteBelowBottomMarginOnTheAddressedLine() throws IOException {
        Session session = new Session(5, 4);

        session.process(ESC + "[1;3r" + ESC + "[1;1Ha" + ESC + "[4;1Hzz");

        assertThat(session.screen()).isEqualTo("a    \n     \n     \nzz   \n");
        assertThat(session.terminal.getCursorY()).isEqualTo(4);
    }

    /** A line feed below the bottom margin advances to the last screen line without scrolling. */
    @Test
    void lineFeedBelowBottomMarginDoesNotScroll() throws IOException {
        Session session = new Session(5, 4);

        session.process(ESC + "[1;3r" + ESC + "[1;1Ha" + ESC + "[2;1Hb" + ESC + "[3;1Hc"
                + ESC + "[4;1Hz" + "\n");

        assertThat(session.screen()).isEqualTo("a    \nb    \nc    \nz    \n");
        assertThat(session.buffer.getHistoryLinesCount()).isEqualTo(0);
        assertThat(session.terminal.getCursorY()).isEqualTo(4);
    }

    /** The behaviour the patch must preserve: on the bottom margin a line feed still scrolls. */
    @Test
    void lineFeedOnBottomMarginStillScrollsTheRegionOnly() throws IOException {
        Session session = new Session(5, 4);

        session.process(ESC + "[1;3r" + ESC + "[4;1Hz" + ESC + "[1;1Ha" + ESC + "[2;1Hb"
                + ESC + "[3;1Hc" + "\n");

        assertThat(session.screen()).isEqualTo("b    \nc    \n     \nz    \n");
        assertThat(session.terminal.getCursorY()).isEqualTo(3);
    }

    /** With origin mode set the region does confine the cursor — line addressing is relative. */
    @Test
    void originModeStillConfinesTheCursorToTheRegion() throws IOException {
        Session session = new Session(5, 4);

        session.process(ESC + "[?6h" + ESC + "[2;3r" + ESC + "[1;1Hab");

        assertThat(session.screen()).isEqualTo("     \nab   \n     \n     \n");
        assertThat(session.terminal.getCursorY()).isEqualTo(2);
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
