package de.kortty.core;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import java.util.function.BooleanSupplier;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class TerminalLogSearchTest {

    private static final int ONE_MIB = 1024 * 1024;

    private final List<Path> files = new ArrayList<>();
    private final List<TerminalLogReader> readers = new ArrayList<>();

    @AfterMethod
    void tearDown() throws IOException {
        for (TerminalLogReader reader : readers) {
            reader.close();
        }
        readers.clear();
        for (Path file : files) {
            Files.deleteIfExists(file);
        }
        files.clear();
    }

    private TerminalLogReader open(String content) throws IOException {
        Path file = Files.createTempFile("kortty-line-search-test", ".log");
        files.add(file);
        Files.writeString(file, content);
        TerminalLogLineIndex index = TerminalLogLineIndex.build(file, Long.MAX_VALUE, null, null);
        TerminalLogReader reader = TerminalLogReader.open(file, index, false);
        readers.add(reader);
        return reader;
    }

    private static final BooleanSupplier NEVER = () -> false;

    @Test
    void aFileShorterThanItsIndexReportsRotationNotNoMatch() throws IOException {
        TerminalLogReader reader = open("first line\nsecond line\nthird line\n");
        Path file = reader.index().file();
        Files.write(file, "first\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        try {
            TerminalLogSearch.findForward(reader, "third", 0, NEVER);
            throw new AssertionError("a truncated file must not read as 'no match'");
        } catch (TerminalLogReader.RotatedException expected) {
            assertThat(expected.getMessage()).contains("shorter than its index");
        }
    }

    @Test
    void aHitStraddlingTheChunkBoundaryIsFoundOnceAndAtItsLine() throws IOException {
        // The search reads 64 Ki characters at a time. Each start in this range puts the needle
        // across that boundary in some run, and the match must be counted once in every case.
        for (int start = 65_530; start <= 65_540; start++) {
            TerminalLogReader reader = open("x".repeat(start) + "NeEdLe" + "\nafter\n");

            assertThat(TerminalLogSearch.countMatches(reader, "needle", 100, NEVER, null)).isEqualTo(1);
            assertThat(TerminalLogSearch.findForward(reader, "needle", 0, NEVER)).isEqualTo(OptionalInt.of(0));
            assertThat(TerminalLogSearch.findBackward(reader, "needle", 1, NEVER)).isEqualTo(OptionalInt.of(0));
            assertThat(TerminalLogSearch.findForward(reader, "needle", 1, NEVER)).isEqualTo(OptionalInt.empty());
        }
    }

    @Test
    void countStopsAtTheCap() throws IOException {
        TerminalLogReader reader = open("ab ab ab ab ab\n");

        assertThat(TerminalLogSearch.countMatches(reader, "ab", 3, NEVER, null)).isEqualTo(3);
        assertThat(TerminalLogSearch.countMatches(reader, "ab", 10, NEVER, null)).isEqualTo(5);
        assertThat(TerminalLogSearch.countMatches(reader, "ab", 0, NEVER, null)).isEqualTo(0);
        assertThat(TerminalLogSearch.DEFAULT_CAP).isEqualTo(10_000);
    }

    @Test
    void countsEveryStartingPositionSoOverlapsCount() throws IOException {
        TerminalLogReader reader = open("aaa\n");

        assertThat(TerminalLogSearch.countMatches(reader, "aa", 10, NEVER, null)).isEqualTo(2);
    }

    @Test
    void matchingIgnoresCaseWithoutChangingPositions() throws IOException {
        TerminalLogReader reader = open("Ärger\nMädchen ÄPFEL\nnothing\n");

        assertThat(TerminalLogSearch.countMatches(reader, "ä", 10, NEVER, null)).isEqualTo(3);
        assertThat(TerminalLogSearch.findForward(reader, "ä", 0, NEVER)).isEqualTo(OptionalInt.of(0));
        assertThat(TerminalLogSearch.findForward(reader, "ä", 1, NEVER)).isEqualTo(OptionalInt.of(1));
        assertThat(TerminalLogSearch.findBackward(reader, "ä", 2, NEVER)).isEqualTo(OptionalInt.of(1));
        assertThat(TerminalLogSearch.findBackward(reader, "ä", 0, NEVER)).isEqualTo(OptionalInt.of(0));
        assertThat(TerminalLogSearch.findForward(reader, "äpfel", 0, NEVER)).isEqualTo(OptionalInt.of(1));
        assertThat(TerminalLogSearch.columnOf("Mädchen ÄPFEL", "äpfel")).isEqualTo(8);
    }

    @Test
    void aNeedleSpanningLinesOrEmptyIsRejected() throws IOException {
        TerminalLogReader reader = open("a\n");

        expectThrows(IllegalArgumentException.class,
            () -> TerminalLogSearch.countMatches(reader, "a\nb", 10, NEVER, null));
        expectThrows(IllegalArgumentException.class,
            () -> TerminalLogSearch.findForward(reader, "", 0, NEVER));
        expectThrows(IllegalArgumentException.class,
            () -> TerminalLogSearch.findBackward(reader, null, 0, NEVER));
    }

    @Test
    void theFirstAndLastLinesAreFoundAndTheRangesRespected() throws IOException {
        TerminalLogReader reader = open("first hit\nmiddle\nlast hit\n");

        assertThat(TerminalLogSearch.findForward(reader, "hit", 0, NEVER)).isEqualTo(OptionalInt.of(0));
        assertThat(TerminalLogSearch.findForward(reader, "hit", 1, NEVER)).isEqualTo(OptionalInt.of(2));
        assertThat(TerminalLogSearch.findForward(reader, "hit", 3, NEVER)).isEqualTo(OptionalInt.empty());
        assertThat(TerminalLogSearch.findBackward(reader, "hit", 2, NEVER)).isEqualTo(OptionalInt.of(2));
        assertThat(TerminalLogSearch.findBackward(reader, "hit", 1, NEVER)).isEqualTo(OptionalInt.of(0));
        assertThat(TerminalLogSearch.findBackward(reader, "hit", 99, NEVER)).isEqualTo(OptionalInt.of(2));
        assertThat(TerminalLogSearch.findBackward(reader, "hit", -1, NEVER)).isEqualTo(OptionalInt.empty());
        assertThat(TerminalLogSearch.countMatches(reader, "hit", 10, NEVER, null)).isEqualTo(2);
    }

    @Test
    void searchesFromTheMiddleOfAFileWithManyBlocks() throws IOException {
        StringBuilder text = new StringBuilder();
        for (int line = 0; line < 10_000; line++) {
            text.append(line % 100 == 42 ? "target line " : "filler " + line + " ").append('\n');
        }
        TerminalLogReader reader = open(text.toString());

        assertThat(reader.index().checkpointCount()).isGreaterThan(20);
        assertThat(TerminalLogSearch.findForward(reader, "target", 550, NEVER)).isEqualTo(OptionalInt.of(642));
        assertThat(TerminalLogSearch.findBackward(reader, "target", 550, NEVER)).isEqualTo(OptionalInt.of(542));
        assertThat(TerminalLogSearch.findBackward(reader, "target", 42, NEVER)).isEqualTo(OptionalInt.of(42));
        assertThat(TerminalLogSearch.findForward(reader, "target", 9943, NEVER)).isEqualTo(OptionalInt.empty());
        assertThat(TerminalLogSearch.findForward(reader, "target", 9942, NEVER)).isEqualTo(OptionalInt.of(9942));
        assertThat(TerminalLogSearch.countMatches(reader, "target", 1000, NEVER, null)).isEqualTo(100);
    }

    @Test
    void crlfLinesCountTheirLineNumbersAndColumnsCountCharacters() throws IOException {
        TerminalLogReader reader = open("a\r\nfoo\r\nbar NEEDLE\r\n");

        assertThat(TerminalLogSearch.findForward(reader, "needle", 0, NEVER)).isEqualTo(OptionalInt.of(2));
        assertThat(TerminalLogSearch.findBackward(reader, "needle", 2, NEVER)).isEqualTo(OptionalInt.of(2));
        assertThat(TerminalLogSearch.columnOf("bar NEEDLE", "needle")).isEqualTo(4);
        assertThat(TerminalLogSearch.columnOf("abc", "")).isEqualTo(-1);
        assertThat(TerminalLogSearch.columnOf(null, "x")).isEqualTo(-1);
        assertThat(TerminalLogSearch.columnOf("abc", "d")).isEqualTo(-1);
    }

    @Test
    void aCancelledSearchReturnsNothingRatherThanAPartialAnswer() throws IOException {
        TerminalLogReader reader = open("hit\nhit\nhit\n");

        assertThat(TerminalLogSearch.findForward(reader, "hit", 0, () -> true)).isEqualTo(OptionalInt.empty());
        assertThat(TerminalLogSearch.findBackward(reader, "hit", 2, () -> true)).isEqualTo(OptionalInt.empty());
        assertThat(TerminalLogSearch.countMatches(reader, "hit", 10, () -> true, null)).isEqualTo(0);
    }

    @Test
    void progressIsReportedUpToAHundredAndNeverDecreases() throws IOException {
        StringBuilder text = new StringBuilder();
        while (text.length() < 3 * ONE_MIB) {
            text.append("line with some text, and a hit here or not\n");
        }
        TerminalLogReader reader = open(text.toString());
        List<Integer> progress = new ArrayList<>();

        TerminalLogSearch.countMatches(reader, "hit", 1_000_000, NEVER, progress::add);

        assertThat(progress).isNotEmpty();
        assertThat(progress).isInOrder();
        assertThat(progress.get(progress.size() - 1)).isEqualTo(100);
    }

    @Test
    void aTokenNearTheEndOfEightMebibytesIsFoundOnItsLine() throws IOException {
        StringBuilder text = new StringBuilder();
        int line = 0;
        while (text.length() < 8 * ONE_MIB) {
            text.append("filler line number ").append(line).append(" with some padding text\n");
            line++;
        }
        int tokenLine = line;
        text.append("the UNIQUE-TOKEN marks the spot\n");
        for (int tail = 0; tail < 50; tail++) {
            text.append("tail ").append(tail).append('\n');
        }
        TerminalLogReader reader = open(text.toString());

        assertThat(TerminalLogSearch.findForward(reader, "unique-token", 0, NEVER))
            .isEqualTo(OptionalInt.of(tokenLine));
        assertThat(TerminalLogSearch.findBackward(reader, "unique-token", reader.index().lineCount() - 1, NEVER))
            .isEqualTo(OptionalInt.of(tokenLine));
        assertThat(TerminalLogSearch.countMatches(reader, "unique-token", 10, NEVER, null)).isEqualTo(1);
        assertThat(TerminalLogSearch.findForward(reader, "unique-token", tokenLine + 1, NEVER))
            .isEqualTo(OptionalInt.empty());
    }

    @Test
    void emptyFilesHaveNoMatches() throws IOException {
        TerminalLogReader reader = open("");

        assertThat(TerminalLogSearch.findForward(reader, "x", 0, NEVER)).isEqualTo(OptionalInt.empty());
        assertThat(TerminalLogSearch.findBackward(reader, "x", 0, NEVER)).isEqualTo(OptionalInt.empty());
        assertThat(TerminalLogSearch.countMatches(reader, "x", 10, NEVER, null)).isEqualTo(0);
    }
}
