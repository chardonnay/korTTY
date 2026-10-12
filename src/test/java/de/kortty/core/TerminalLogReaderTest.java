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
import static org.testng.Assert.expectThrows;

class TerminalLogReaderTest {

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

    private Path write(byte[] content) throws IOException {
        Path file = Files.createTempFile("kortty-line-reader-test", ".log");
        files.add(file);
        Files.write(file, content);
        return file;
    }

    private Path write(String content) throws IOException {
        return write(content.getBytes(StandardCharsets.UTF_8));
    }

    private TerminalLogReader open(Path file, boolean keepChannelOpen) throws IOException {
        TerminalLogLineIndex index = TerminalLogLineIndex.build(file, Long.MAX_VALUE, null, null);
        TerminalLogReader reader = TerminalLogReader.open(file, index, keepChannelOpen);
        readers.add(reader);
        return reader;
    }

    @Test
    void pagesAcrossBlockBoundariesMatchTheReferenceLines() throws IOException {
        Random random = new Random(7L);
        String[] fragments = {"plain ascii ", "grüße ", "日本語 ", "tab\tsep ", "x"};
        List<String> reference = new ArrayList<>();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        while (out.size() < 3 * ONE_MIB) {
            StringBuilder line = new StringBuilder();
            int pieces = random.nextInt(60);
            for (int piece = 0; piece < pieces; piece++) {
                line.append(fragments[random.nextInt(fragments.length)]);
            }
            reference.add(line.toString());
            byte[] bytes = (line + "\n").getBytes(StandardCharsets.UTF_8);
            out.write(bytes, 0, bytes.length);
        }
        Path file = write(out.toByteArray());

        for (boolean keepChannelOpen : new boolean[] {true, false}) {
            TerminalLogReader reader = open(file, keepChannelOpen);
            List<String> read = new ArrayList<>();
            for (int from = 0; from < reference.size(); from += 1000) {
                List<TerminalLogReader.Line> page = reader.readPage(from, 1000);
                for (int i = 0; i < page.size(); i++) {
                    TerminalLogReader.Line line = page.get(i);
                    assertThat(line.number()).isEqualTo(from + i);
                    assertThat(line.truncated()).isFalse();
                    read.add(line.text());
                }
            }
            assertThat(read).isEqualTo(reference);
        }
    }

    @Test
    void aMultibyteCharacterAcrossTheBlockBoundaryIsDecodedWhole() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] head = new byte[ONE_MIB - 2];
        Arrays.fill(head, (byte) 'a');
        out.write(head);
        out.write('\n');
        out.write("äbc\n".getBytes(StandardCharsets.UTF_8));
        TerminalLogReader reader = open(write(out.toByteArray()), false);

        List<TerminalLogReader.Line> page = reader.readPage(1, 1);

        assertThat(page).hasSize(1);
        assertThat(page.get(0).text()).isEqualTo("äbc");
        assertThat(page.get(0).number()).isEqualTo(1);
    }

    @Test
    void aLongLineIsCutForDisplayAndFlaggedAndTheLinesAfterItStayNumbered() throws IOException {
        byte[] longLine = new byte[100 * 1024];
        Arrays.fill(longLine, (byte) 'a');
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(longLine);
        out.write("\nshort\r\n".getBytes(StandardCharsets.UTF_8));
        TerminalLogReader reader = open(write(out.toByteArray()), false);

        List<TerminalLogReader.Line> page = reader.readPage(0, 2);

        assertThat(page).hasSize(2);
        assertThat(page.get(0).text()).hasLength(TerminalLogReader.DISPLAY_CHARS);
        assertThat(page.get(0).truncated()).isTrue();
        assertThat(page.get(1).number()).isEqualTo(1);
        assertThat(page.get(1).text()).isEqualTo("short");
        assertThat(page.get(1).truncated()).isFalse();
    }

    @Test
    void aLineBetweenTheDisplayAndByteCapsIsCutToTheDisplayCharacters() throws IOException {
        TerminalLogReader reader = open(write("b".repeat(20_000) + "\n"), false);

        List<TerminalLogReader.Line> page = reader.readPage(0, 1);

        assertThat(page.get(0).text()).hasLength(TerminalLogReader.DISPLAY_CHARS);
        assertThat(page.get(0).truncated()).isTrue();
    }

    @Test
    void aTrailingCarriageReturnIsStrippedButAnInnerOneIsKept() throws IOException {
        TerminalLogReader reader = open(write("a\rb\r\n"), false);

        assertThat(reader.readPage(0, 1).get(0).text()).isEqualTo("a\rb");
    }

    @Test
    void aPageReachingPastTheEndReturnsWhatExists() throws IOException {
        TerminalLogReader reader = open(write("one\ntwo\nthree\nfour\nfive\n"), false);

        List<TerminalLogReader.Line> tail = reader.readPage(3, 10);

        assertThat(tail).hasSize(2);
        assertThat(tail.get(0).number()).isEqualTo(3);
        assertThat(tail.get(1).text()).isEqualTo("five");
        assertThat(reader.readPage(5, 10)).isEmpty();
        assertThat(reader.readPage(0, 0)).isEmpty();
    }

    @Test
    void aFinalLineWithoutLineFeedIsReturned() throws IOException {
        TerminalLogReader reader = open(write("a\nb"), false);

        List<TerminalLogReader.Line> page = reader.readPage(0, 10);

        assertThat(page).hasSize(2);
        assertThat(page.get(1).text()).isEqualTo("b");
    }

    @Test
    void aRotatedFileIsReportedWhenTheReaderOpensItPerPage() throws IOException {
        Path file = write("one\ntwo\nthree\n");
        TerminalLogReader reader = open(file, false);
        assertThat(reader.readPage(0, 1).get(0).text()).isEqualTo("one");

        Files.delete(file);

        expectThrows(TerminalLogReader.RotatedException.class, () -> reader.readPage(0, 1));
        expectThrows(TerminalLogReader.RotatedException.class, () -> reader.readPage(1, 1));
    }

    @Test
    void aKeptChannelKeepsReadingAfterTheFileWasDeletedOnPosixSystems() throws IOException {
        Path file = write("one\ntwo\nthree\n");
        TerminalLogReader reader = open(file, true);
        boolean posix = file.getFileSystem().supportedFileAttributeViews().contains("posix");
        if (!posix) {
            return;
        }

        Files.delete(file);

        assertThat(reader.readPage(1, 2).get(0).text()).isEqualTo("two");
    }

    @Test
    void aClosedReaderRefusesToRead() throws IOException {
        TerminalLogReader reader = open(write("a\n"), true);
        reader.close();
        reader.close();

        expectThrows(IOException.class, () -> reader.readPage(0, 1));
    }

    @Test
    void negativeStartLineIsRejected() throws IOException {
        TerminalLogReader reader = open(write("a\n"), false);

        expectThrows(IllegalArgumentException.class, () -> reader.readPage(-1, 1));
    }
}
