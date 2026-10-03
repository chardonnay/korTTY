package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.shellintegration.CommandStatus;
import de.kortty.shellintegration.ShellIntegrationEvent;
import de.kortty.ui.OscEmulatorHarness.Screen;
import de.kortty.ui.OscEmulatorHarness.ScriptedConnector;
import java.io.IOException;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.function.Predicate;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * What a pane's OSC 133 marks hand its command-timestamp gutter, from real output read by SithTermFX:
 * the exit status of each finished command on the line where the next prompt is printed, the first
 * output line of a running command, and the completions whose times become timestamps.
 *
 * <p>{@link Gutter} does what {@code TerminalView.updateCommandStatuses} does with the gutter's own
 * trim tracker, so the test also proves that statuses, completions and shifted timestamps keep
 * naming the same lines while the scrollback drops lines between two updates.
 */
class PaneCommandMarksGutterTest {

    private static final String ESC = "\u001B";
    private static final String A = ESC + "]133;A\u0007";
    private static final String B = ESC + "]133;B\u0007";
    private static final String C = ESC + "]133;C\u0007";
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 4, 12, 0, 0);

    private static String finished(int status) {
        return ESC + "]133;D;" + status + "\u0007";
    }

    @Test
    void eachFinishedCommandReachesTheGutterOnceOnTheLineOfTheNextPrompt() throws IOException {
        Screen screen = new Screen(20, 6, 100);
        PaneCommandMarks marks = run(screen, 7, event -> false, null,
            A + "$ " + B + "ls\r\n" + C + "file1\r\nfile2\r\n" + finished(0)
                + A + "$ " + B + "false\r\n" + C + finished(1) + A + "$ " + B);

        PaneCommandMarks.GutterUpdate update = marks.gutterUpdate();
        assertThat(update.statuses().keySet()).containsExactly(3, 4).inOrder();
        assertThat(update.statuses().get(3).kind()).isEqualTo(CommandStatus.Kind.SUCCEEDED);
        assertThat(update.statuses().get(4).kind()).isEqualTo(CommandStatus.Kind.FAILED);
        assertThat(update.statuses().get(4).exitStatus()).isEqualTo(1);
        assertThat(update.completions().stream().map(PaneCommandMarks.Completion::absoluteLine).toList())
            .containsExactly(3, 4).inOrder();
        assertThat(lineText(screen, 3)).isEqualTo("$ false");
        assertThat(lineText(screen, 4)).isEqualTo("$");

        PaneCommandMarks.GutterUpdate again = marks.gutterUpdate();
        assertWithMessage("a completion is handed over once").that(again.completions()).isEmpty();
        assertThat(again.statuses()).isEqualTo(update.statuses());
    }

    @Test
    void aRunningCommandShowsOnItsFirstOutputLine() throws IOException {
        Screen screen = new Screen(20, 6, 100);
        PaneCommandMarks marks = run(screen, 4096, event -> false, null,
            A + "$ " + B + "make\r\n" + C + "cc a.c\r\n");

        PaneCommandMarks.GutterUpdate update = marks.gutterUpdate();
        assertThat(update.statuses().keySet()).containsExactly(1);
        assertThat(update.statuses().get(1).running()).isTrue();
        assertThat(lineText(screen, 1)).isEqualTo("cc a.c");
        assertThat(update.completions()).isEmpty();
    }

    @DataProvider
    Object[][] updateRhythms() {
        // After every finish, after every third, and once at the very end.
        return new Object[][] {{1}, {3}, {1_000}};
    }

    @Test(dataProvider = "updateRhythms")
    void statusesCompletionsAndTimestampsStayOnTheirPromptLinesWhileTheScrollbackDropsLines(int everyNthFinish)
            throws IOException {
        Screen screen = new Screen(20, 4, 8);
        Gutter gutter = new Gutter(screen);
        int[] finishes = {0};
        StringBuilder output = new StringBuilder();
        int commands = 40;
        for (int command = 0; command < commands; command++) {
            output.append(A).append("$ ").append(B).append("c").append(command).append("\r\n").append(C);
            for (int line = 0; line < command % 4; line++) {
                output.append("out ").append(command).append('.').append(line).append("\r\n");
            }
            output.append(finished(command % 3));
        }
        output.append(A).append("$ ").append(B);
        PaneCommandMarks marks = run(screen, 5, event -> event instanceof ShellIntegrationEvent.CommandFinished
            && ++finishes[0] % everyNthFinish == 0, gutter, output.toString());
        gutter.update(marks);

        screen.buffer.lock();
        try {
            int history = screen.buffer.getHistoryLinesCount();
            assertWithMessage("the scrollback dropped lines").that(history).isEqualTo(8);
            int checked = 0;
            for (int line = 0; line < history + 4; line++) {
                String text = lineText(screen, line);
                CommandStatus status = gutter.statusAt(line);
                if (status != null) {
                    assertWithMessage("a status on line " + line + ": " + text).that(text).startsWith("$");
                    // The prompt of command n is where command n - 1 finished, with status (n - 1) % 3.
                    int previous = text.equals("$") ? commands - 1 : Integer.parseInt(text.substring(3)) - 1;
                    assertWithMessage("the status of c" + previous).that(status.exitStatus()).isEqualTo(previous % 3);
                    checked++;
                }
                if (gutter.timestamps.containsKey(line)) {
                    assertWithMessage("a completion timestamp on line " + line + ": " + text).that(text).startsWith("$");
                }
            }
            assertWithMessage("statuses checked").that(checked).isAtLeast(3);
            assertWithMessage("every prompt still in the scrollback got its completion timestamp")
                .that(gutter.timestamps.size()).isEqualTo(checked);
        } finally {
            screen.buffer.unlock();
        }
    }

    @Test
    void aClearedScrollbackDropsTheCompletionsNotYetHandedOver() throws IOException {
        Screen screen = new Screen(20, 3, 50);
        StringBuilder output = new StringBuilder();
        for (int command = 0; command < 4; command++) {
            output.append(A).append("$ ").append(B).append("c").append(command).append("\r\n").append(C)
                .append("out\r\n").append(finished(0));
        }
        // What `clear` prints: the scrollback, then the screen, is emptied and the cursor goes home.
        output.append(ESC).append("[3J").append(ESC).append("[H").append(ESC).append("[2J");
        output.append(A).append("$ ").append(B);
        PaneCommandMarks marks = run(screen, 4096, event -> false, null, output.toString());

        PaneCommandMarks.GutterUpdate update = marks.gutterUpdate();
        assertThat(update.completions()).isEmpty();
        assertThat(update.statuses()).isEmpty();
    }

    @Test
    void aFloodOfFinishesKeepsOnlyTheNewestCompletions() throws IOException {
        Screen screen = new Screen(20, 5, 2_000);
        StringBuilder output = new StringBuilder();
        int commands = PaneCommandMarks.MAX_PENDING_COMPLETIONS + 44;
        for (int command = 0; command < commands; command++) {
            output.append(A).append("$ ").append(B).append("x\r\n").append(C).append(finished(0));
        }
        output.append(A).append("$ ").append(B);
        PaneCommandMarks marks = run(screen, 4096, event -> false, null, output.toString());

        List<PaneCommandMarks.Completion> completions = marks.gutterUpdate().completions();
        assertThat(completions).hasSize(PaneCommandMarks.MAX_PENDING_COMPLETIONS);
        assertWithMessage("the newest finish is kept").that(completions.getLast().absoluteLine()).isEqualTo(commands);
    }

    @Test
    void oneUpdateIsAskedForUntilItIsTaken() {
        Screen screen = new Screen(20, 5, 50);
        PaneCommandMarks marks = new PaneCommandMarks(screen.buffer);
        assertThat(marks.requestGutterUpdate()).isTrue();
        assertWithMessage("already scheduled").that(marks.requestGutterUpdate()).isFalse();
        marks.gutterUpdate();
        assertThat(marks.requestGutterUpdate()).isTrue();
        marks.cancelGutterUpdateRequest();
        assertWithMessage("a request that could not be scheduled is withdrawn").that(marks.requestGutterUpdate()).isTrue();
    }

    @Test
    void aCompletionTakesTheWallClockTimeOfItsMark() {
        PaneCommandMarks.Completion completion = new PaneCommandMarks.Completion(3, 1_000L);
        assertThat(completion.time(NOW, 2_000_001_000L)).isEqualTo(NOW.minusSeconds(2));
        assertWithMessage("a clock read before the mark counts as now")
            .that(completion.time(NOW, 500L)).isEqualTo(NOW);
    }

    /**
     * Runs {@code output} through the wrapper into a pane's marks, the way a pane's emulator thread
     * does; after each event that {@code updateAfter} accepts, {@code gutter} takes an update right
     * there, as the FX thread may at any point of the stream.
     */
    private static PaneCommandMarks run(Screen screen, int chunkSize, Predicate<ShellIntegrationEvent> updateAfter,
            Gutter gutter, String output) throws IOException {
        PaneCommandMarks marks = new PaneCommandMarks(screen.buffer);
        screen.buffer.addModelListener(marks::observe);
        long[] clock = {0};
        screen.run(new ShellIntegrationTtyConnector(ScriptedConnector.inChunksOf(output, chunkSize), event -> {
            marks.record(event, screen.terminal, ++clock[0]);
            if (gutter != null && updateAfter.test(event)) {
                gutter.update(marks);
            }
        }));
        return marks;
    }

    private static String lineText(Screen screen, int absoluteLine) {
        screen.buffer.lock();
        try {
            return screen.buffer.getLine(absoluteLine - screen.buffer.getHistoryLinesCount()).getText().stripTrailing();
        } finally {
            screen.buffer.unlock();
        }
    }

    /** The timestamp gutter's side of a pane, as {@code TerminalView} keeps it. */
    private static final class Gutter {
        private final Screen screen;
        private final ScrollbackTrimTracker tracker;
        private TreeMap<Integer, LocalDateTime> timestamps = new TreeMap<>();
        private NavigableMap<Integer, CommandStatus> statuses = Collections.emptyNavigableMap();
        private long statusShift;

        Gutter(Screen screen) {
            this.screen = screen;
            this.tracker = ScrollbackTrimTracker.forBuffer(screen.buffer);
            screen.buffer.addModelListener(tracker::observe);
        }

        /** {@code TerminalView.updateCommandStatuses}: poll the gutter's tracker and read the marks under one lock. */
        void update(PaneCommandMarks marks) {
            ScrollbackTrimTracker.Trim trim;
            PaneCommandMarks.GutterUpdate update;
            screen.buffer.lock();
            try {
                trim = tracker.poll();
                update = marks.gutterUpdate();
            } finally {
                screen.buffer.unlock();
            }
            if (TerminalView.scrollbackTrimMovesMarks(trim)) {
                if (trim.kind() == ScrollbackTrimTracker.Trim.Kind.CLEARED) {
                    timestamps = new TreeMap<>();
                    statuses = Collections.emptyNavigableMap();
                } else {
                    timestamps = TimestampHistory.shift(timestamps, trim.lines());
                    statusShift += trim.lines();
                }
            }
            for (PaneCommandMarks.Completion completion : update.completions()) {
                timestamps.putIfAbsent(completion.absoluteLine(), completion.time(NOW, completion.nanos()));
            }
            statuses = update.statuses();
            statusShift = 0;
        }

        CommandStatus statusAt(int absoluteLine) {
            return statuses.get((int) (absoluteLine + statusShift));
        }
    }
}
