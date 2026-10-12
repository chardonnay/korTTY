package de.kortty.core;

import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

class TerminalLogLineIndexTest {

    private static final int ONE_MIB = 1024 * 1024;

    private final List<Path> files = new ArrayList<>();

    @AfterMethod
    void tearDown() throws IOException {
        for (Path file : files) {
            Files.deleteIfExists(file);
        }
        files.clear();
    }

    private Path write(byte[] content) throws IOException {
        Path file = Files.createTempFile("kortty-line-index-test", ".log");
        files.add(file);
        Files.write(file, content);
        return file;
    }

    private Path write(String content) throws IOException {
        return write(content.getBytes(StandardCharsets.UTF_8));
    }

    private static TerminalLogLineIndex build(Path file) throws IOException {
        return TerminalLogLineIndex.build(file, Long.MAX_VALUE, null, null);
    }

    @Test
    void indexesLinesTerminatedByLineFeeds() throws IOException {
        TerminalLogLineIndex index = build(write("a\nbb\nccc\n"));

        assertThat(index.lineCount()).isEqualTo(3);
        assertThat(index.indexedBytes()).isEqualTo(9);
        assertThat(index.offsetOf(0)).isEqualTo(0);
        assertThat(index.offsetOf(1)).isEqualTo(2);
        assertThat(index.offsetOf(2)).isEqualTo(5);
    }

    @Test
    void crlfLinesStartAfterTheLineFeed() throws IOException {
        TerminalLogLineIndex index = build(write("a\r\nb\r\n"));

        assertThat(index.lineCount()).isEqualTo(2);
        assertThat(index.offsetOf(1)).isEqualTo(3);
    }

    @Test
    void aFinalLineWithoutLineFeedCounts() throws IOException {
        TerminalLogLineIndex index = build(write("a\nb"));

        assertThat(index.lineCount()).isEqualTo(2);
        assertThat(index.offsetOf(1)).isEqualTo(2);
    }

    @Test
    void anEmptyFileHasNoLines() throws IOException {
        TerminalLogLineIndex index = build(write(""));

        assertThat(index.lineCount()).isEqualTo(0);
        assertThat(index.indexedBytes()).isEqualTo(0);
        assertThat(index.checkpointCount()).isAtLeast(1);
        expectThrows(IllegalArgumentException.class, () -> index.offsetOf(0));
    }

    @Test
    void countsTheLinesTheReaderWillShow() throws IOException {
        assertThat(build(write("a\n")).lineCount()).isEqualTo(1);
        assertThat(build(write("\n")).lineCount()).isEqualTo(1);
        assertThat(build(write("a\n\n")).lineCount()).isEqualTo(2);
        assertThat(build(write("a")).lineCount()).isEqualTo(1);
        assertThat(build(write("\n\n")).lineCount()).isEqualTo(2);
        assertThat(build(write("a\nb\n")).lineCount()).isEqualTo(2);
    }

    @Test
    void lineFeedOnlyFileHasOneEmptyLineWhoseStartIsZero() throws IOException {
        TerminalLogLineIndex index = build(write("\n"));

        assertThat(index.lineCount()).isEqualTo(1);
        assertThat(index.offsetOf(0)).isEqualTo(0);
    }

    @Test
    void anEmptyLineBetweenLinesHasItsOwnStart() throws IOException {
        TerminalLogLineIndex index = build(write("a\n\nb\n"));

        assertThat(index.lineCount()).isEqualTo(3);
        assertThat(index.offsetOf(1)).isEqualTo(2);
        assertThat(index.offsetOf(2)).isEqualTo(3);
    }

    @Test
    void theLengthLimitIndexesOnlyThatPrefix() throws IOException {
        Path file = write("a\nb\nc\n");

        TerminalLogLineIndex index = TerminalLogLineIndex.build(file, 3, null, null);

        assertThat(index.indexedBytes()).isEqualTo(3);
        assertThat(index.lineCount()).isEqualTo(2);
        assertThat(index.offsetOf(1)).isEqualTo(2);
    }

    @Test
    void aMultibyteCharacterStraddlingTheBlockBoundaryDoesNotShiftLines() throws IOException {
        // The first byte of "ä" is the last byte of the first 1 MiB block, the second opens the next.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(bytesOf('a', ONE_MIB - 2));
        out.write('\n');
        out.write("äbc\n".getBytes(StandardCharsets.UTF_8));
        Path file = write(out.toByteArray());

        TerminalLogLineIndex index = build(file);

        assertThat(index.lineCount()).isEqualTo(2);
        assertThat(index.offsetOf(1)).isEqualTo(ONE_MIB - 1);
    }

    @Test
    void randomLookupsAgreeWithAReferenceAcrossEightMebibytes() throws IOException {
        Random random = new Random(20260804L);
        ByteArrayOutputStream out = new ByteArrayOutputStream(8 * ONE_MIB + 4096);
        List<Long> starts = new ArrayList<>();
        while (out.size() < 8 * ONE_MIB) {
            starts.add((long) out.size());
            int length = random.nextInt(200);
            for (int i = 0; i < length; i++) {
                out.write('a' + random.nextInt(26));
            }
            out.write('\n');
        }
        long[] reference = starts.stream().mapToLong(Long::longValue).toArray();
        Path file = write(out.toByteArray());

        TerminalLogLineIndex index = build(file);

        assertThat(index.lineCount()).isEqualTo(reference.length);
        assertThat(index.indexedBytes()).isEqualTo(out.size());
        int checkpointBound = reference.length / 256 + (int) (out.size() / ONE_MIB) + 2;
        assertThat(index.checkpointCount()).isAtMost(checkpointBound);
        for (int i = 0; i < 2000; i++) {
            int line = random.nextInt(index.lineCount());
            assertWithMessage("offset of line %s", line).that(index.offsetOf(line)).isEqualTo(reference[line]);
        }
        int last = index.lineCount() - 1;
        assertThat(index.offsetOf(last)).isEqualTo(reference[last]);
    }

    @Test
    void aSingleFiveMebibyteLineGetsItsOwnCheckpointAndTheNextLineStillResolves() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(bytesOf('x', 5 * ONE_MIB));
        out.write('\n');
        out.write("tail".getBytes(StandardCharsets.UTF_8));
        Path file = write(out.toByteArray());

        TerminalLogLineIndex index = build(file);

        assertThat(index.lineCount()).isEqualTo(2);
        assertThat(index.offsetOf(0)).isEqualTo(0);
        assertThat(index.offsetOf(1)).isEqualTo(5L * ONE_MIB + 1);
        assertThat(index.checkpointCount()).isAtMost(3);
    }

    @Test
    void reportsProgressUpToTheIndexedBytes() throws IOException {
        Path file = write(bytesOf('a', 3 * ONE_MIB));
        List<Long> progress = new ArrayList<>();

        TerminalLogLineIndex index = TerminalLogLineIndex.build(file, Long.MAX_VALUE, progress::add, null);

        assertThat(progress).isNotEmpty();
        assertThat(progress).isInOrder();
        assertThat(progress.get(progress.size() - 1)).isEqualTo(index.indexedBytes());
    }

    @Test
    void cancellingTheScanThrowsAndBuildsNothing() throws IOException {
        Path file = write(bytesOf('a', 3 * ONE_MIB));

        expectThrows(TerminalLogScratch.CancelledException.class,
            () -> TerminalLogLineIndex.build(file, Long.MAX_VALUE, null, () -> true));
    }

    @Test
    void linesOutsideTheRangeAreRejected() throws IOException {
        TerminalLogLineIndex index = build(write("a\nb\n"));

        expectThrows(IllegalArgumentException.class, () -> index.offsetOf(-1));
        expectThrows(IllegalArgumentException.class, () -> index.offsetOf(index.lineCount()));
    }

    @Test
    void reportsItsFileAsAnAbsolutePath() throws IOException {
        Path file = write("a\n");

        assertThat(build(file).file()).isEqualTo(file.toAbsolutePath().normalize());
    }

    private static byte[] bytesOf(char value, int count) {
        byte[] bytes = new byte[count];
        Arrays.fill(bytes, (byte) value);
        return bytes;
    }
}
