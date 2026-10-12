package de.kortty.core;

import org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Random;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

class TerminalLogCodecsTest {

    private static final int ONE_MIB = 1024 * 1024;

    private Path tempDir;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-terminal-log-codecs-test");
    }

    @AfterMethod
    void tearDown() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete " + path, e);
                }
            });
        }
    }

    /**
     * About a mebibyte of log-like text: repetitive enough to compress, varied enough that a
     * compressor cannot cheat, and carrying multi-byte UTF-8 so a codec that mangles bytes shows.
     * Seeded, so every run writes the same bytes.
     */
    private static byte[] sampleText() {
        Random random = new Random(20260804L);
        String[] words = {"connected", "ssh", "exit", "status 0", "grüße", "日本語", "ok", "retry 3", "pty"};
        StringBuilder text = new StringBuilder(ONE_MIB + 512);
        int line = 0;
        while (text.length() < ONE_MIB) {
            text.append(line++).append(' ')
                .append(words[random.nextInt(words.length)]).append(' ')
                .append(random.nextInt(1_000_000)).append(' ')
                .append(Long.toHexString(random.nextLong())).append('\n');
        }
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    /** Writes the sample in {@code compression} under {@code name}, reads it back, and returns the file size. */
    private long roundTrip(String name, TerminalLogNaming.Compression compression) throws IOException {
        byte[] original = sampleText();
        Path target = tempDir.resolve(name);
        try (OutputStream out = TerminalLogCodecs.openOutput(target, compression)) {
            out.write(original);
        }
        try (InputStream in = TerminalLogCodecs.openInput(target)) {
            assertWithMessage("%s read back", name).that(in.readAllBytes()).isEqualTo(original);
        }
        return Files.size(target);
    }

    @Test
    void aPlainLogRoundTripsUnchanged() throws IOException {
        long size = roundTrip("session.log", TerminalLogNaming.Compression.NONE);

        assertThat(size).isEqualTo((long) sampleText().length);
    }

    @Test
    void aGzipArchiveRoundTripsAndIsSmaller() throws IOException {
        long size = roundTrip("session.log.gz", TerminalLogNaming.Compression.GZIP);

        assertThat(size).isLessThan((long) sampleText().length);
    }

    @Test
    void aZstdArchiveRoundTripsAndIsSmaller() throws IOException {
        long size = roundTrip("session.log.zst", TerminalLogNaming.Compression.ZSTD);

        assertThat(size).isLessThan((long) sampleText().length);
    }

    @Test
    void aBzip2ArchiveRoundTripsAndIsSmaller() throws IOException {
        long size = roundTrip("session.log.bz2", TerminalLogNaming.Compression.BZIP2);

        assertThat(size).isLessThan((long) sampleText().length);
    }

    @Test
    void theArchivesAreInTheFormatTheirSuffixNames() throws IOException {
        // A round trip through our own reader would pass for a file no other tool can open, so
        // check the magic bytes that standard tools look for.
        roundTrip("magic.log.gz", TerminalLogNaming.Compression.GZIP);
        roundTrip("magic.log.zst", TerminalLogNaming.Compression.ZSTD);
        roundTrip("magic.log.bz2", TerminalLogNaming.Compression.BZIP2);

        assertThat(firstBytes(tempDir.resolve("magic.log.gz"), 2)).isEqualTo(new byte[] {0x1f, (byte) 0x8b});
        assertThat(firstBytes(tempDir.resolve("magic.log.zst"), 4))
            .isEqualTo(new byte[] {0x28, (byte) 0xb5, 0x2f, (byte) 0xfd});
        assertThat(firstBytes(tempDir.resolve("magic.log.bz2"), 3))
            .isEqualTo(new byte[] {'B', 'Z', 'h'});
    }

    @Test
    void aBzip2FileMadeOfTwoStreamsIsReadInFull() throws IOException {
        // pbzip2 and similar tools write several bzip2 streams back to back; a reader that stops
        // after the first would silently lose the tail of the log.
        byte[] first = "first stream, with some words in it\n".repeat(2000).getBytes(StandardCharsets.UTF_8);
        byte[] second = "second stream, a different tail\n".repeat(2000).getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream file = new ByteArrayOutputStream();
        for (byte[] part : List.of(first, second)) {
            try (BZip2CompressorOutputStream out = new BZip2CompressorOutputStream(file)) {
                out.write(part);
            }
        }
        Path target = tempDir.resolve("two-streams.log.bz2");
        Files.write(target, file.toByteArray());

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(first);
        expected.write(second);
        try (InputStream in = TerminalLogCodecs.openInput(target)) {
            assertThat(in.readAllBytes()).isEqualTo(expected.toByteArray());
        }
    }

    private static byte[] firstBytes(Path file, int count) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            return in.readNBytes(count);
        }
    }
}
