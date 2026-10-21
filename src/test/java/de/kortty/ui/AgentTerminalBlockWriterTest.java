package de.kortty.ui;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The in-terminal blocks of KI-Agent design 03 go into a real emulator's buffer above the shell's prompt
 * line: the prompt line and the cursor on it stay as they were, the block grows and shrinks around them,
 * a full screen scrolls into the history, and output the shell writes afterwards lands at the prompt.
 */
class AgentTerminalBlockWriterTest {

    private static final AgentTerminalBlockWriter.Palette PALETTE =
        AgentTerminalBlockWriter.Palette.of("#4F9CF0", "#8696AB", "#E36A4D", true);

    @Test
    void theBlockGoesAboveThePromptAndTheCursorStaysOnIt() {
        EmulatorTextBufferFixture fixture = fixture(10);
        fixture.write("$ df -h\r\n/dev/sda3 93%\r\nuser@host:~$ agent why");
        int cursorColumn = fixture.terminal.getCursorX();
        AgentTerminalBlockWriter writer = writer(fixture);

        AgentTerminalBlockWriter.Region region = writer.open(lines("╭─ block", "│ step", "╰─"), PALETTE);

        assertThat(region).isNotNull();
        assertThat(screen(fixture)).containsExactly(
            "$ df -h", "/dev/sda3 93%", "╭─ block", "│ step", "╰─", "user@host:~$ agent why").inOrder();
        assertThat(fixture.terminal.getCursorY()).isEqualTo(6);
        assertThat(fixture.terminal.getCursorX()).isEqualTo(cursorColumn);

        fixture.write("X");
        assertWithMessage("shell echo lands on the prompt line")
            .that(fixture.buffer.getLine(5).getText().stripTrailing()).isEqualTo("user@host:~$ agent whyX");
    }

    @Test
    void theBlockGrowsAndShrinksWithoutTouchingThePrompt() {
        EmulatorTextBufferFixture fixture = fixture(10);
        fixture.write("output\r\nprompt$ ");
        AgentTerminalBlockWriter writer = writer(fixture);
        List<AgentInlineBlockLayout.Line> first = lines("head", "foot");
        AgentTerminalBlockWriter.Region region = writer.open(first, PALETTE);

        List<AgentInlineBlockLayout.Line> grown = lines("head", "row 1", "row 2", "foot");
        assertThat(writer.rewrite(region, grown, first, PALETTE)).isEqualTo(AgentTerminalBlockWriter.Result.WRITTEN);
        assertThat(screen(fixture)).containsExactly("output", "head", "row 1", "row 2", "foot", "prompt$").inOrder();
        assertThat(fixture.terminal.getCursorY()).isEqualTo(6);

        List<AgentInlineBlockLayout.Line> collapsed = lines("summary", "answer");
        assertThat(writer.rewrite(region, collapsed, grown, PALETTE)).isEqualTo(AgentTerminalBlockWriter.Result.WRITTEN);
        assertThat(screen(fixture)).containsExactly("output", "summary", "answer", "prompt$").inOrder();
        assertThat(fixture.terminal.getCursorY()).isEqualTo(4);
        assertThat(fixture.terminal.getCursorX()).isEqualTo(9);
    }

    @Test
    void aFullScreenScrollsIntoTheHistoryToMakeRoom() {
        EmulatorTextBufferFixture fixture = fixture(5);
        fixture.write("one\r\ntwo\r\nthree\r\nfour\r\nprompt$ ");
        AgentTerminalBlockWriter writer = writer(fixture);

        AgentTerminalBlockWriter.Region region = writer.open(lines("head", "foot"), PALETTE);

        assertThat(region).isNotNull();
        assertThat(screen(fixture)).containsExactly("three", "four", "head", "foot", "prompt$").inOrder();
        assertThat(fixture.buffer.getHistoryLinesCount()).isEqualTo(2);
        assertThat(fixture.terminal.getCursorY()).isEqualTo(5);
    }

    @Test
    void aBlockThatScrolledAwayIsReportedLost() {
        EmulatorTextBufferFixture fixture = fixture(4);
        fixture.write("prompt$ ");
        AgentTerminalBlockWriter writer = writer(fixture);
        List<AgentInlineBlockLayout.Line> block = lines("head", "foot");
        AgentTerminalBlockWriter.Region region = writer.open(block, PALETTE);

        fixture.write("\r\nout 1\r\nout 2\r\nout 3\r\nout 4\r\nprompt$ ");

        assertThat(writer.rewrite(region, lines("head", "row", "foot"), block, PALETTE))
            .isEqualTo(AgentTerminalBlockWriter.Result.LOST);
    }

    @Test
    void theBlockIsFoundAgainAfterTheTerminalGotNarrower() {
        EmulatorTextBufferFixture fixture = fixture(10);
        fixture.write("output\r\nprompt$ ");
        AgentTerminalBlockWriter writer = writer(fixture);
        List<AgentInlineBlockLayout.Line> block = lines("head", "row", "foot");
        AgentTerminalBlockWriter.Region region = writer.open(block, PALETTE);

        fixture.terminal.resize(new com.sithtermfx.core.util.TermSize(36, 10),
            com.sithtermfx.core.RequestOrigin.User);

        assertThat(writer.rewrite(region, lines("head", "row", "row 2", "foot"), block, PALETTE))
            .isEqualTo(AgentTerminalBlockWriter.Result.WRITTEN);
        assertThat(screen(fixture)).containsExactly("output", "head", "row", "row 2", "foot", "prompt$").inOrder();
    }

    @Test
    void onlyChangedRowsAreRewritten() {
        EmulatorTextBufferFixture fixture = fixture(8);
        fixture.write("prompt$ ");
        AgentTerminalBlockWriter writer = writer(fixture);
        List<AgentInlineBlockLayout.Line> before = lines("head ⠋", "row", "foot");
        AgentTerminalBlockWriter.Region region = writer.open(before, PALETTE);
        // A stray mark the writer would wipe if it rewrote the unchanged row.
        fixture.buffer.getLine(1).writeString(10, new com.sithtermfx.core.model.CharBuffer("!"), com.sithtermfx.core.TextStyle.EMPTY);

        writer.rewrite(region, lines("head ⠙", "row", "foot"), before, PALETTE);

        assertThat(fixture.buffer.getLine(0).getText().stripTrailing()).isEqualTo("head ⠙");
        assertThat(fixture.buffer.getLine(1).getText()).contains("!");
    }

    @Test
    void nothingIsWrittenWhileAFullScreenProgramRuns() {
        EmulatorTextBufferFixture fixture = fixture(8);
        fixture.write("prompt$ ");
        AgentTerminalBlockWriter writer = writer(fixture);
        List<AgentInlineBlockLayout.Line> block = lines("head", "foot");
        AgentTerminalBlockWriter.Region region = writer.open(block, PALETTE);

        fixture.terminal.useAlternateBuffer(true);
        assertThat(writer.rewrite(region, lines("head", "row", "foot"), block, PALETTE))
            .isEqualTo(AgentTerminalBlockWriter.Result.DEFERRED);
        assertThat(writer.open(block, PALETTE)).isNull();
        fixture.terminal.useAlternateBuffer(false);

        assertThat(writer.rewrite(region, lines("head", "row", "foot"), block, PALETTE))
            .isEqualTo(AgentTerminalBlockWriter.Result.WRITTEN);
    }

    @Test
    void theBlockUsesThePaletteColours() {
        EmulatorTextBufferFixture fixture = fixture(6);
        fixture.write("prompt$ ");
        writer(fixture).open(List.of(new AgentInlineBlockLayout.Line(List.of(
            new AgentInlineBlockLayout.Segment("✓", AgentInlineBlockLayout.Role.OK),
            new AgentInlineBlockLayout.Segment(" done", AgentInlineBlockLayout.Role.TEXT)))), PALETTE);

        com.sithtermfx.core.TextStyle okStyle = fixture.buffer.getStyleAt(0, 0);
        assertThat(okStyle.getForeground()).isEqualTo(PALETTE.ok());
    }

    private static EmulatorTextBufferFixture fixture(int height) {
        return new EmulatorTextBufferFixture(40, height, 100, new KorttyOsc8LinkInfoProvider());
    }

    private static AgentTerminalBlockWriter writer(EmulatorTextBufferFixture fixture) {
        return new AgentTerminalBlockWriter(fixture.terminal, fixture.buffer, fixture.terminal.getStyleState());
    }

    private static List<AgentInlineBlockLayout.Line> lines(String... texts) {
        List<AgentInlineBlockLayout.Line> lines = new ArrayList<>();
        for (String text : texts) {
            lines.add(new AgentInlineBlockLayout.Line(List.of(
                new AgentInlineBlockLayout.Segment(text, AgentInlineBlockLayout.Role.TEXT))));
        }
        return lines;
    }

    private static List<String> screen(EmulatorTextBufferFixture fixture) {
        List<String> rows = new ArrayList<>();
        for (int row = 0; row < fixture.buffer.getHeight(); row++) {
            String text = fixture.buffer.getLine(row).getText().stripTrailing();
            if (!text.isEmpty()) {
                rows.add(text);
            }
        }
        return rows;
    }
}
