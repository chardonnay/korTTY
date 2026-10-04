package de.kortty.ui;

import org.jetbrains.annotations.Nullable;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static com.google.common.truth.Truth.assertThat;

class TerminalRecentOutputSourceTest {

    /** A pane with optional marks output and some lines; records how many lines were asked for. */
    private static final class FakePane implements TerminalRecentOutputSource.Reader {
        private final @Nullable String commandOutput;
        private final @Nullable List<String> lines;
        int requestedLines = -1;

        FakePane(@Nullable String commandOutput, @Nullable List<String> lines) {
            this.commandOutput = commandOutput;
            this.lines = lines;
        }

        @Override
        public @Nullable String lastCommandOutput() {
            return commandOutput;
        }

        @Override
        public @Nullable List<String> tail(int maxLines) {
            requestedLines = maxLines;
            return lines;
        }
    }

    private static List<String> numberedLines(int count) {
        return IntStream.rangeClosed(1, count).mapToObj(i -> "line " + i).toList();
    }

    @Test
    void usesTheLastCommandOutputWhenTheMarksHaveOne() {
        FakePane pane = new FakePane("total 0\r\ndrwxr-xr-x  2 demo demo .\r\n", List.of("older", "lines"));

        TerminalRecentOutputSource.RecentOutput output = TerminalRecentOutputSource.read(pane);

        assertThat(output).isNotNull();
        assertThat(output.origin()).isEqualTo(TerminalRecentOutputSource.Origin.LAST_COMMAND);
        assertThat(output.text()).isEqualTo("total 0\ndrwxr-xr-x  2 demo demo .");
        assertThat(output.truncated()).isFalse();
        assertThat(pane.requestedLines).isEqualTo(-1);
    }

    @Test
    void fallsBackToTheLastBoundedLinesWithoutMarks() {
        FakePane pane = new FakePane(null, numberedLines(450));

        TerminalRecentOutputSource.RecentOutput output = TerminalRecentOutputSource.read(pane);

        assertThat(pane.requestedLines).isEqualTo(TerminalRecentOutputSource.MAX_LINES);
        assertThat(output).isNotNull();
        assertThat(output.origin()).isEqualTo(TerminalRecentOutputSource.Origin.RECENT_LINES);
        List<String> sent = List.of(output.text().split("\n"));
        assertThat(sent).hasSize(TerminalRecentOutputSource.MAX_LINES);
        assertThat(sent.get(0)).isEqualTo("line 251");
        assertThat(sent.get(sent.size() - 1)).isEqualTo("line 450");
    }

    @Test
    void fallsBackWhenTheLastCommandPrintedNothing() {
        FakePane pane = new FakePane("  \n\u001B[0m\n", List.of("$ make", "error: missing ;"));

        TerminalRecentOutputSource.RecentOutput output = TerminalRecentOutputSource.read(pane);

        assertThat(output).isNotNull();
        assertThat(output.origin()).isEqualTo(TerminalRecentOutputSource.Origin.RECENT_LINES);
        assertThat(output.text()).isEqualTo("$ make\nerror: missing ;");
    }

    @Test
    void returnsNothingForAnEmptyPaneOrAFullScreenProgram() {
        assertThat(TerminalRecentOutputSource.read(new FakePane(null, null))).isNull();
        assertThat(TerminalRecentOutputSource.read(new FakePane(null, List.of()))).isNull();
        assertThat(TerminalRecentOutputSource.read(new FakePane("", List.of("", "   ")))).isNull();
    }

    @Test
    void capsTheCharactersAndKeepsTheNewestOutputFromALineStart() {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 150; i++) {
            lines.add(i + " " + "x".repeat(200));
        }
        String longOutput = String.join("\n", lines);
        assertThat(longOutput.length()).isGreaterThan(TerminalRecentOutputSource.MAX_CHARS);

        TerminalRecentOutputSource.RecentOutput output = TerminalRecentOutputSource.read(new FakePane(longOutput, null));

        assertThat(output).isNotNull();
        assertThat(output.truncated()).isTrue();
        assertThat(output.text().length()).isAtMost(TerminalRecentOutputSource.MAX_CHARS);
        assertThat(output.text()).endsWith("149 " + "x".repeat(200));
        // Starts at a whole line, not in the middle of one.
        assertThat(output.text()).matches("(?s)\\d+ x+\n.*");
    }

    @Test
    void capsTheFallbackLinesToo() {
        List<String> lines = IntStream.range(0, 200).mapToObj(i -> "y".repeat(500)).toList();

        TerminalRecentOutputSource.RecentOutput output = TerminalRecentOutputSource.read(new FakePane(null, lines));

        assertThat(output).isNotNull();
        assertThat(output.truncated()).isTrue();
        assertThat(output.text().length()).isAtMost(TerminalRecentOutputSource.MAX_CHARS);
    }

    @Test
    void stripsAnsiSequencesAndControlCharacters() {
        String raw = "\u001B[1;31merror\u001B[0m: build failed\u0007\n"
            + "\u001B]0;window title\u0007done\u001B]8;;file:///x\u001B\\link\u001B]8;;\u001B\\\n"
            + "tab\there‮reversed\u0000\u001B(B";

        assertThat(TerminalRecentOutputSource.clean(raw))
            .isEqualTo("error: build failed\ndonelink\ntab\therereversed");
    }

    @Test
    void cleanTrimsBlankLinesAtBothEnds() {
        assertThat(TerminalRecentOutputSource.clean("\n\n  a  \n\nb\n  \n")).isEqualTo("  a\n\nb");
        assertThat(TerminalRecentOutputSource.clean(null)).isEmpty();
    }
}
