package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.SelectionUtil;
import de.kortty.shellintegration.CommandBlockStore.CommandBlock;
import de.kortty.shellintegration.CommandBlockStore.Mark;
import de.kortty.shellintegration.LastOutputRange;
import de.kortty.ui.OscEmulatorHarness.Screen;
import de.kortty.ui.OscEmulatorHarness.ScriptedConnector;
import de.kortty.ui.PaneCommandMarks.LastOutput;
import de.kortty.ui.PaneCommandMarks.LastOutputStatus;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import kotlin.Pair;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

/**
 * Select and Copy Last Output: the range of what the newest finished command printed, from its
 * {@code OSC 133;C} mark to its {@code D} mark with an exclusive end, and the text SithTermFX reads
 * from it, through real output in a real emulator.
 */
class LastOutputRangeTest {

    private static final String ESC = "\u001B";
    private static final String A = ESC + "]133;A\u0007";
    private static final String B = ESC + "]133;B\u0007";
    private static final String C = ESC + "]133;C\u0007";

    private static String finished(int status) {
        return ESC + "]133;D;" + status + "\u0007";
    }

    /** One command as a bash with korTTY's snippet prints it: prompt, command line, output, D, next prompt. */
    private static String command(String commandLine, String output) {
        return A + "$ " + B + commandLine + "\r\n" + C + output + finished(0);
    }

    private static final String NEXT_PROMPT = A + "$ " + B;

    // ---- the range alone ---------------------------------------------------------------------

    /** Lines of the given text lengths, absolute line 0 first. */
    private static LastOutputRange.Lines lines(int... lengths) {
        return new LastOutputRange.Lines() {
            @Override
            public int count() {
                return lengths.length;
            }

            @Override
            public int length(int absoluteLine) {
                return lengths[absoluteLine];
            }
        };
    }

    private static CommandBlock block(Mark output, Mark end) {
        return new CommandBlock(new Mark(0, 0), new Mark(0, 2), output, end, 0, 1L, 2L, true);
    }

    @Test
    void anEndInsideALineIsExclusive() {
        // printf 'one\ntwo': D stands right after "two", where the next prompt follows.
        Optional<LastOutputRange> range = LastOutputRange.of(block(new Mark(1, 0), new Mark(2, 3)), 0,
            lines(9, 3, 8));
        assertThat(range).hasValue(new LastOutputRange(1, 0, 2, 3, false));
        assertWithMessage("SithTermFX's selections end on the last character")
            .that(range.orElseThrow().lastColumn()).isEqualTo(2);
    }

    @Test
    void anEndAtTheStartOfALineEndsAfterTheLastCharacterAbove() {
        // ls: the output ends with a line break, so D stands at the start of the next line.
        assertThat(LastOutputRange.of(block(new Mark(1, 0), new Mark(3, 0)), 0, lines(9, 5, 5, 2)))
            .hasValue(new LastOutputRange(1, 0, 2, 5, false));
    }

    @Test
    void emptyLinesAtTheEndAreLeftOut() {
        assertThat(LastOutputRange.of(block(new Mark(1, 0), new Mark(5, 0)), 0, lines(9, 5, 0, 4, 0, 0, 2)))
            .hasValue(new LastOutputRange(1, 0, 3, 4, false));
    }

    @Test
    void aCommandWithoutTextHasNoRange() {
        assertWithMessage("true: D right where C was")
            .that(LastOutputRange.of(block(new Mark(1, 0), new Mark(1, 0)), 0, lines(9, 2))).isEmpty();
        assertWithMessage("echo: one empty line")
            .that(LastOutputRange.of(block(new Mark(1, 0), new Mark(2, 0)), 0, lines(9, 0, 2))).isEmpty();
    }

    @Test
    void aStartThatLeftTheScrollbackStartsAtTheOldestLineAndSaysSo() {
        // Ten lines were dropped: C (line id 4) is gone, D (line id 12) is absolute line 2.
        assertThat(LastOutputRange.of(block(new Mark(4, 0), new Mark(12, 0)), 10, lines(3, 3, 2)))
            .hasValue(new LastOutputRange(0, 0, 1, 3, true));
    }

    @Test
    void anOutputThatLeftTheScrollbackEntirelyHasNoRange() {
        assertThat(LastOutputRange.of(block(new Mark(4, 0), new Mark(9, 0)), 10, lines(3, 3, 2))).isEmpty();
    }

    @Test
    void aStartAfterTheTextOfItsLineMovesToTheNextLine() {
        // A shell that marks C at the end of the command line, before the line break is echoed.
        assertThat(LastOutputRange.of(block(new Mark(0, 6), new Mark(2, 0)), 0, lines(6, 4, 2)))
            .hasValue(new LastOutputRange(1, 0, 1, 4, false));
    }

    @Test
    void anEndBeyondTheTextOrThePaneIsClamped() {
        assertWithMessage("D after the cursor moved right without printing")
            .that(LastOutputRange.of(block(new Mark(1, 0), new Mark(2, 7)), 0, lines(9, 4, 3)))
            .hasValue(new LastOutputRange(1, 0, 2, 3, false));
        assertWithMessage("the pane got shorter since D")
            .that(LastOutputRange.of(block(new Mark(1, 0), new Mark(8, 0)), 0, lines(9, 4, 3)))
            .hasValue(new LastOutputRange(1, 0, 2, 3, false));
    }

    @Test
    void aCommandThatDidNotFinishHasNoRange() {
        CommandBlock running = new CommandBlock(new Mark(0, 0), null, new Mark(1, 0), null, null, 1L, 0L, false);
        assertThat(LastOutputRange.of(running, 0, lines(9, 4, 3))).isEmpty();
    }

    // ---- through the emulator ----------------------------------------------------------------

    @DataProvider
    Object[][] chunkSizes() {
        return new Object[][] {{1}, {5}, {4096}};
    }

    @Test(dataProvider = "chunkSizes")
    void theCopyIsTheOutputWithoutItsLastLineBreak(int chunkSize) throws IOException {
        Screen screen = new Screen(30, 8, 100);
        PaneCommandMarks marks = run(screen, chunkSize,
            command("ls", "file1\r\nfile two\r\n") + NEXT_PROMPT);

        LastOutput output = marks.lastOutput(true);
        assertThat(output.status()).isEqualTo(LastOutputStatus.FOUND);
        assertThat(output.text()).isEqualTo("file1\nfile two");
        assertThat(output.range()).isEqualTo(new LastOutputRange(1, 0, 2, 8, false));
        assertThat(selectedByCopy(screen, output)).isEqualTo(output.text());
    }

    @Test(dataProvider = "chunkSizes")
    void outputWithoutALineBreakEndsBeforeTheNextPrompt(int chunkSize) throws IOException {
        Screen screen = new Screen(30, 8, 100);
        PaneCommandMarks marks = run(screen, chunkSize,
            command("printf 'a\\nbc'", "a\r\nbc") + NEXT_PROMPT + "ls");

        LastOutput output = marks.lastOutput(true);
        assertWithMessage("the prompt after the output on the same line is not part of it")
            .that(output.text()).isEqualTo("a\nbc");
        assertThat(selectedByCopy(screen, output)).isEqualTo("a\nbc");
    }

    @Test
    void aWrappedLineIsCopiedAsOneLine() throws IOException {
        Screen screen = new Screen(20, 8, 100);
        String longLine = "0123456789".repeat(3);
        PaneCommandMarks marks = run(screen, 4096, command("cat long", longLine + "\r\nend\r\n") + NEXT_PROMPT);

        LastOutput output = marks.lastOutput(true);
        assertThat(output.text()).isEqualTo(longLine + "\nend");
        assertThat(selectedByCopy(screen, output)).isEqualTo(output.text());
    }

    @Test
    void anOutputLongerThanTheScrollbackKeepsWhatIsLeftAndSaysItIsTruncated() throws IOException {
        Screen screen = new Screen(20, 3, 5);
        String numbers = IntStream.rangeClosed(1, 50).mapToObj(n -> n + "\r\n").collect(Collectors.joining());
        PaneCommandMarks marks = run(screen, 4096, command("seq 50", numbers) + NEXT_PROMPT);

        LastOutput output = marks.lastOutput(true);
        assertThat(output.status()).isEqualTo(LastOutputStatus.FOUND);
        assertThat(output.range().truncated()).isTrue();
        assertThat(output.range().startLine()).isEqualTo(0);
        List<String> copied = output.text().lines().toList();
        int first = Integer.parseInt(copied.getFirst());
        assertWithMessage("the first lines are gone").that(first).isGreaterThan(1);
        assertWithMessage("every line that is left, in order, up to the last")
            .that(copied).isEqualTo(IntStream.rangeClosed(first, 50).mapToObj(Integer::toString).toList());
        assertWithMessage("the scrollback and the screen hold the rest and the prompt")
            .that(copied.size()).isEqualTo(screen.buffer.getHistoryLinesCount() + 3 - 1);
    }

    @Test
    void aRunningCommandLeavesTheOutputOfTheOneBefore() throws IOException {
        Screen screen = new Screen(30, 8, 100);
        PaneCommandMarks marks = run(screen, 4096,
            command("echo one", "one\r\n") + A + "$ " + B + "tail -f log\r\n" + C + "line 1\r\n");

        assertThat(marks.lastOutput(true).text()).isEqualTo("one");
    }

    @Test
    void theStatusSaysWhyThereIsNothingToTake() throws IOException {
        assertThat(run(new Screen(30, 8, 100), 4096, "plain output\r\n").lastOutput(true).status())
            .isEqualTo(LastOutputStatus.NO_MARKS);
        assertThat(run(new Screen(30, 8, 100), 4096, NEXT_PROMPT + "ls").lastOutput(true).status())
            .isEqualTo(LastOutputStatus.NO_COMMAND);
        assertThat(run(new Screen(30, 8, 100), 4096, command("true", "") + NEXT_PROMPT).lastOutput(true).status())
            .isEqualTo(LastOutputStatus.NO_OUTPUT);
        assertThat(run(new Screen(30, 8, 100), 4096, command("ls", "a\r\n") + NEXT_PROMPT + "vim\r\n"
            + ESC + "[?1049h").lastOutput(true).status())
            .isEqualTo(LastOutputStatus.FULL_SCREEN);
    }

    @Test
    void selectingReadsNoText() throws IOException {
        Screen screen = new Screen(30, 8, 100);
        LastOutput output = run(screen, 4096, command("ls", "a\r\n") + NEXT_PROMPT).lastOutput(false);
        assertThat(output.status()).isEqualTo(LastOutputStatus.FOUND);
        assertThat(output.text()).isNull();
        assertThat(output.selection()).isNotNull();
    }

    /** What SithTermFX's Copy reads for the returned selection, the way it reads one made with the mouse. */
    private static String selectedByCopy(Screen screen, LastOutput output) {
        screen.buffer.lock();
        try {
            Pair<Point, Point> points = output.selection().pointsForRun(screen.buffer.getWidth());
            return SelectionUtil.getSelectedText(points.getFirst(), points.getSecond(), screen.buffer);
        } finally {
            screen.buffer.unlock();
        }
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
