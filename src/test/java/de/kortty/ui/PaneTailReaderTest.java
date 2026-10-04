package de.kortty.ui;

import com.sithtermfx.core.model.CharBuffer;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalTextBuffer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The session snapshot reads the newest rows of every pane that received output, every half minute.
 * The read holds the pane's buffer lock, which the emulator thread needs for every byte it shows, so
 * it must touch only the rows it returns and never build the whole history first.
 */
class PaneTailReaderTest {

    /** A buffer with {@code history} lines scrolled into the history and {@code screen} on the screen. */
    private static TerminalTextBuffer buffer(int columns, int rows, int history, int screen) {
        TerminalTextBuffer buffer = new TerminalTextBuffer(columns, rows, new StyleState(), 10_000, null);
        int line = 1;
        buffer.lock();
        try {
            for (int i = 0; i < history; i++) {
                buffer.writeString(0, 1, new CharBuffer("line " + line++));
                // Scrolls the whole screen up by one: its top row goes into the history.
                buffer.scrollArea(1, -1, rows);
            }
            for (int y = 1; y <= screen; y++) {
                buffer.writeString(0, y, new CharBuffer("line " + line++));
            }
        } finally {
            buffer.unlock();
        }
        return buffer;
    }

    @Test
    void returnsTheNewestLinesOldestFirst() {
        TerminalTextBuffer buffer = buffer(40, 5, 20, 3);

        List<String> tail = PaneTailReader.readTail(buffer, 4);

        assertThat(tail).containsExactly("line 20", "line 21", "line 22", "line 23").inOrder();
    }

    @Test
    void readsAcrossTheHistoryAndTheScreenWithoutTheBlankRowsBelowTheOutput() {
        TerminalTextBuffer buffer = buffer(40, 5, 3, 2);

        List<String> tail = PaneTailReader.readTail(buffer, 100);

        assertWithMessage("the scrolled-off rows, then the screen, without the blank rows below the prompt")
            .that(tail.subList(tail.size() - 2, tail.size())).containsExactly("line 4", "line 5").inOrder();
        assertThat(tail).contains("line 1");
        assertThat(tail.get(tail.size() - 1)).isEqualTo("line 5");
    }

    @Test
    void aLongHistoryIsNotReadWhole() {
        TerminalTextBuffer buffer = buffer(40, 5, 5_000, 1);

        List<String> tail = PaneTailReader.readTail(buffer, 10);

        assertThat(tail).hasSize(10);
        assertThat(tail.get(9)).isEqualTo("line 5001");
        assertThat(tail.get(0)).isEqualTo("line 4992");
    }

    @Test
    void nothingIsReadForANonPositiveLimitOrNoBuffer() {
        assertThat(PaneTailReader.readTail(buffer(40, 5, 3, 1), 0)).isEmpty();
        assertThat(PaneTailReader.readTail(null, 10)).isEmpty();
    }

    @Test
    void theAlternateScreenIsNotSaved() {
        TerminalTextBuffer buffer = buffer(40, 5, 3, 1);
        buffer.useAlternateBuffer(true);

        assertWithMessage("a full-screen program shows: the file keeps the output before it")
            .that(PaneTailReader.readTail(buffer, 10)).isNull();
    }

    @Test
    void theReaderTakesTheLockAndWalksRowsInsteadOfCopyingTheHistory() throws IOException {
        String source = Files.readString(Path.of("src/main/java/de/kortty/ui/PaneTailReader.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(source).contains("buffer.lock();");
        assertThat(source).contains("buffer.unlock();");
        for (String wholeCopy : List.of("getLines()", "getLineTexts()", "getScreenLines()",
                "processHistoryAndScreenLines(")) {
            assertWithMessage("the tail read must not build the whole history: " + wholeCopy)
                .that(source).doesNotContain(wholeCopy);
        }
    }

    @Test
    void theSessionScrollbackIsReadWithTheBoundedReader() throws IOException {
        String view = Files.readString(Path.of("src/main/java/de/kortty/ui/TerminalView.java"),
            StandardCharsets.UTF_8).replace("\r\n", "\n");

        assertThat(view).contains("PaneTailReader.readTail(pane.getTerminalTextBuffer(), maxLines)");
    }
}
