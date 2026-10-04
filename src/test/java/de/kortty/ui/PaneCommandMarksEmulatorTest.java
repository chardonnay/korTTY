package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.CommandBlockStore;
import de.kortty.shellintegration.CommandBlockStore.CommandBlock;
import de.kortty.shellintegration.CommandBlockStore.Mark;
import de.kortty.shellintegration.OwnedOsc;
import de.kortty.shellintegration.ShellIntegrationEvent;
import de.kortty.ui.OscEmulatorHarness.Screen;
import de.kortty.ui.OscEmulatorHarness.ScriptedConnector;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * A pane's marks from real output: SithTermFX's emulator reads it through
 * {@link ShellIntegrationTtyConnector}, which hands the OSC 133 events to {@link PaneCommandMarks}
 * on the emulator thread, while the buffer's model listener counts the scrollback trims as the pane
 * does. Each prompt mark must keep naming the line its prompt is printed on, however much scrollback
 * was dropped in between.
 */
class PaneCommandMarksEmulatorTest {

    private static final String ESC = "\u001B";
    private static final String A = ESC + "]133;A\u0007";
    private static final String B = ESC + "]133;B\u0007";
    private static final String C = ESC + "]133;C\u0007";

    private static String finished(int status) {
        return ESC + "]133;D;" + status + "\u0007";
    }

    @DataProvider
    Object[][] chunkSizes() {
        return new Object[][] {{1}, {3}, {7}, {4096}};
    }

    @Test(dataProvider = "chunkSizes")
    void eachMarkIsRecordedWhereTheShellPutIt(int chunkSize) throws IOException {
        Screen screen = new Screen(20, 6, 100);
        PaneCommandMarks marks = run(screen, chunkSize,
            A + "$ " + B + "ls\r\n" + C + "file1\r\nfile2\r\n" + finished(2) + A + "$ " + B);

        List<CommandBlock> blocks = marks.store().blocks();
        assertThat(blocks).hasSize(2);
        CommandBlock ls = blocks.get(0);
        assertThat(ls.prompt()).isEqualTo(new Mark(0, 0));
        assertThat(ls.command()).isEqualTo(new Mark(0, 2));
        assertThat(ls.output()).isEqualTo(new Mark(1, 0));
        assertThat(ls.end()).isEqualTo(new Mark(3, 0));
        assertThat(ls.exitStatus()).isEqualTo(2);
        assertThat(ls.outputStartNanos()).isAtMost(ls.endNanos());
        assertThat(blocks.get(1).prompt()).isEqualTo(new Mark(3, 0));
        assertThat(blocks.get(1).command()).isEqualTo(new Mark(3, 2));
        assertThat(marks.store().currentPrompt().getAsLong()).isEqualTo(3);
    }

    @Test(dataProvider = "chunkSizes")
    void promptMarksFollowTheirLinesWhileTheScrollbackDropsLines(int chunkSize) throws IOException {
        Screen screen = new Screen(20, 4, 6);
        StringBuilder output = new StringBuilder();
        int commands = 30;
        for (int command = 0; command < commands; command++) {
            output.append(A).append("$ ").append(B).append("c").append(command).append("\r\n").append(C);
            for (int line = 0; line < command % 4; line++) {
                output.append("out ").append(command).append('.').append(line).append("\r\n");
            }
            output.append(finished(command % 3));
        }
        output.append(A).append("$ ").append(B);
        PaneCommandMarks marks = run(screen, chunkSize, output.toString());

        CommandBlockStore store = marks.store();
        List<String> promptLines = new ArrayList<>();
        screen.buffer.lock();
        try {
            marks.syncTrims();
            for (CommandBlock block : store.blocks()) {
                long absolute = store.absoluteLine(block.prompt().line());
                if (absolute < 0) {
                    continue;
                }
                int row = (int) absolute - screen.buffer.getHistoryLinesCount();
                promptLines.add(screen.buffer.getLine(row).getText().stripTrailing());
            }
        } finally {
            screen.buffer.unlock();
        }
        assertWithMessage("the scrollback dropped lines").that(store.trimmed()).isGreaterThan(0L);
        assertThat(promptLines).isNotEmpty();
        assertWithMessage("the newest prompt is the one being typed at")
            .that(promptLines.getLast()).isEqualTo("$");
        // Every kept prompt line still shows its own command, in order, up to the last one.
        List<String> commandsShown = promptLines.subList(0, promptLines.size() - 1);
        for (int index = 0; index < commandsShown.size(); index++) {
            int command = commands - commandsShown.size() + index;
            assertThat(commandsShown.get(index)).isEqualTo("$ c" + command);
        }
    }

    @Test
    void aBurstLongerThanTheWholeScrollbackIsCountedLineByLine() throws IOException {
        Screen screen = new Screen(20, 3, 5);
        StringBuilder output = new StringBuilder(A + "$ " + B + "first\r\n" + C + finished(0));
        output.append(A).append("$ ").append(B).append("seq 50\r\n").append(C);
        for (int line = 1; line <= 50; line++) {
            output.append(line).append("\r\n");
        }
        output.append(finished(0)).append(A).append("$ ").append(B);
        PaneCommandMarks marks = run(screen, 4096, output.toString());

        CommandBlockStore store = marks.store();
        screen.buffer.lock();
        try {
            marks.syncTrims();
            long current = store.currentPrompt().getAsLong();
            int row = (int) store.absoluteLine(current) - screen.buffer.getHistoryLinesCount();
            assertThat(screen.buffer.getLine(row).getText().stripTrailing()).isEqualTo("$");
            assertWithMessage("both earlier prompts left the scrollback")
                .that(store.promptAbove(current).isPresent()).isFalse();
        } finally {
            screen.buffer.unlock();
        }
    }

    @Test
    void marksOnTheAlternateScreenAreDropped() throws IOException {
        Screen screen = new Screen(20, 5, 50);
        PaneCommandMarks marks = run(screen, 4096,
            "before\r\n" + ESC + "[?1049h" + A + "full screen" + C + ESC + "[?1049l" + "after");
        assertThat(marks.store().isEmpty()).isTrue();
    }

    @Test
    void aClearedScrollbackDropsTheMarks() throws IOException {
        Screen screen = new Screen(20, 3, 50);
        StringBuilder output = new StringBuilder();
        for (int command = 0; command < 5; command++) {
            output.append(A).append("$ ").append(B).append("c").append(command).append("\r\n").append(C)
                .append("out\r\n").append(finished(0));
        }
        // What `clear` prints: the scrollback, then the screen, is emptied and the cursor goes home.
        output.append(ESC).append("[3J").append(ESC).append("[H").append(ESC).append("[2J");
        output.append(A).append("$ ").append(B);
        PaneCommandMarks marks = run(screen, 4096, output.toString());

        List<CommandBlock> blocks = marks.store().blocks();
        assertThat(blocks).hasSize(1);
        assertThat(marks.store().absoluteLine(blocks.getFirst().prompt().line()))
            .isEqualTo(screen.buffer.getHistoryLinesCount());
    }

    @Test
    void onlyTheMarksAreRecorded() {
        assertThat(PaneCommandMarks.isMark(new ShellIntegrationEvent.PromptStart())).isTrue();
        assertThat(PaneCommandMarks.isMark(new ShellIntegrationEvent.CommandFinished(null))).isTrue();
        assertThat(PaneCommandMarks.isMark(new ShellIntegrationEvent.RemoteNotification(OwnedOsc.NOTIFICATION, null, "x")))
            .isFalse();
        assertThat(PaneCommandMarks.isMark(new ShellIntegrationEvent.Oversize(OwnedOsc.SHELL_INTEGRATION))).isFalse();

        Screen screen = new Screen(20, 5, 50);
        PaneCommandMarks marks = new PaneCommandMarks(screen.buffer);
        marks.record(new ShellIntegrationEvent.RemoteNotification(OwnedOsc.NOTIFICATION, null, "x"), screen.terminal, 1L);
        assertThat(marks.store().isEmpty()).isTrue();
    }

    /** Runs {@code output} through the wrapper into a pane's marks, the way a pane's emulator thread does. */
    private static PaneCommandMarks run(Screen screen, int chunkSize, String output) throws IOException {
        PaneCommandMarks marks = new PaneCommandMarks(screen.buffer);
        screen.buffer.addModelListener(marks::observe);
        long[] clock = {0};
        screen.run(new ShellIntegrationTtyConnector(ScriptedConnector.inChunksOf(output, chunkSize),
            event -> marks.record(event, screen.terminal, ++clock[0])));
        return marks;
    }
}
