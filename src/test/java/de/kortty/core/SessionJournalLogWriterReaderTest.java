package de.kortty.core;

import de.kortty.model.SessionJournalLogFormat;
import de.kortty.model.SessionJournalMeta;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalLogWriterReaderTest {

    private Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-session-journal-log-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException e) {
                    throw new IllegalStateException("Failed to delete temp path " + path, e);
                }
            });
        }
    }

    @DataProvider(name = "formats")
    Object[][] formats() {
        return new Object[][] {
            {SessionJournalLogFormat.XML},
            {SessionJournalLogFormat.JSON},
            {SessionJournalLogFormat.YAML},
        };
    }

    private static SessionJournalMeta sampleMeta() {
        SessionJournalMeta meta = new SessionJournalMeta();
        meta.setHost("192.168.1.50");
        meta.setPort(22);
        meta.setUsername("daniel");
        meta.setConnectionName("web \"quoted\" & <server>");
        meta.setAppVersion("2.8.0");
        meta.setStartedAt(OffsetDateTime.of(2026, 8, 3, 14, 15, 2, 0, ZoneOffset.ofHours(2)));
        return meta;
    }

    private static List<SessionJournalLogEntry> sampleEntries() {
        OffsetDateTime base = OffsetDateTime.of(2026, 8, 3, 14, 15, 3, 0, ZoneOffset.ofHours(2));
        return List.of(
            new SessionJournalLogEntry(1, base, SessionJournalLogEntry.Kind.OUT,
                "Last login: Sun Aug  3 09:12:44 & <today> \"quoted\" öäü 東京", false, false, null),
            new SessionJournalLogEntry(2, base.plusSeconds(1), SessionJournalLogEntry.Kind.IN,
                "sudo systemctl status nginx", false, false, null),
            new SessionJournalLogEntry(3, base.plusSeconds(2), SessionJournalLogEntry.Kind.OUT,
                "[sudo] password for daniel:", false, true, null),
            new SessionJournalLogEntry(4, base.plusSeconds(3), SessionJournalLogEntry.Kind.IN,
                "", true, false, null),
            new SessionJournalLogEntry(5, base.plusSeconds(4), SessionJournalLogEntry.Kind.SEED,
                "make[2]: Entering directory '/usr/src/app'", false, false, null),
            new SessionJournalLogEntry(6, base.plusSeconds(5), SessionJournalLogEntry.Kind.SCREENSHOT,
                "", false, false, "screenshots/shot-000006.png"),
            new SessionJournalLogEntry(7, base.plusSeconds(6), SessionJournalLogEntry.Kind.NOTE,
                "session reconnected", false, false, null));
    }

    private Path writePart(SessionJournalLogFormat format, int part, List<SessionJournalLogEntry> entries,
                           boolean withFooter) throws IOException {
        SessionJournalLogSerializer serializer = SessionJournalLogSerializer.forFormat(format);
        StringBuilder sb = new StringBuilder();
        sb.append(serializer.header("journal-1", part, sampleMeta(), "tab-session-1"));
        for (SessionJournalLogEntry entry : entries) {
            sb.append(serializer.entryLine(entry));
        }
        if (withFooter) {
            sb.append(serializer.footer());
        }
        Path file = tempDir.resolve(SessionJournalLogReader.partFileName(part, format));
        Files.writeString(file, sb.toString(), StandardCharsets.UTF_8);
        return file;
    }

    @Test(dataProvider = "formats")
    void roundTripsAllEntryKinds(SessionJournalLogFormat format) throws IOException {
        List<SessionJournalLogEntry> entries = sampleEntries();
        Path file = writePart(format, 1, entries, true);
        List<SessionJournalLogEntry> read = SessionJournalLogReader.readPart(file);
        assertThat(read).isEqualTo(entries);
    }

    @Test(dataProvider = "formats")
    void readsLiveFileWithoutFooter(SessionJournalLogFormat format) throws IOException {
        List<SessionJournalLogEntry> entries = sampleEntries();
        Path file = writePart(format, 1, entries, false);
        List<SessionJournalLogEntry> read = SessionJournalLogReader.readPart(file);
        assertThat(read).isEqualTo(entries);
    }

    @Test(dataProvider = "formats")
    void recoversFromTornTrailingLine(SessionJournalLogFormat format) throws IOException {
        List<SessionJournalLogEntry> entries = sampleEntries();
        Path file = writePart(format, 1, entries, false);
        // Simulate a crash mid-write: append an incomplete entry line without newline.
        SessionJournalLogSerializer serializer = SessionJournalLogSerializer.forFormat(format);
        SessionJournalLogEntry torn = new SessionJournalLogEntry(
            8, OffsetDateTime.now(), SessionJournalLogEntry.Kind.OUT, "half written", false, false, null);
        String tornLine = serializer.entryLine(torn);
        Files.writeString(file, tornLine.substring(0, tornLine.length() / 2), StandardCharsets.UTF_8,
            java.nio.file.StandardOpenOption.APPEND);
        List<SessionJournalLogEntry> read = SessionJournalLogReader.readPart(file);
        assertThat(read).isEqualTo(entries);
    }

    @Test(dataProvider = "formats")
    void readsCompressedParts(SessionJournalLogFormat format) throws IOException {
        List<SessionJournalLogEntry> entries = sampleEntries();
        Path file = writePart(format, 1, entries, true);
        Path compressed = SessionJournalLogCompressor.compress(file);
        assertThat(compressed.getFileName().toString()).endsWith(".gz");
        assertThat(Files.exists(file)).isFalse();
        List<SessionJournalLogEntry> read = SessionJournalLogReader.readPart(compressed);
        assertThat(read).isEqualTo(entries);
        assertThat(SessionJournalLogReader.findPartFile(tempDir, 1)).isEqualTo(compressed);
    }

    @Test(dataProvider = "formats")
    void sequenceContinuesAcrossRotatedParts(SessionJournalLogFormat format) throws IOException {
        OffsetDateTime base = OffsetDateTime.of(2026, 8, 3, 15, 0, 0, 0, ZoneOffset.ofHours(2));
        List<SessionJournalLogEntry> part1 = List.of(
            new SessionJournalLogEntry(1, base, SessionJournalLogEntry.Kind.OUT, "one", false, false, null),
            new SessionJournalLogEntry(2, base, SessionJournalLogEntry.Kind.IN, "cmd-a", false, false, null));
        List<SessionJournalLogEntry> part2 = List.of(
            new SessionJournalLogEntry(3, base, SessionJournalLogEntry.Kind.OUT, "three", false, false, null),
            new SessionJournalLogEntry(4, base, SessionJournalLogEntry.Kind.IN, "cmd-b", false, false, null));
        Path part1File = writePart(format, 1, part1, true);
        writePart(format, 2, part2, false);
        SessionJournalLogCompressor.compress(part1File);

        assertThat(SessionJournalLogReader.countParts(tempDir)).isEqualTo(2);
        assertThat(SessionJournalLogReader.readAfter(tempDir, 0)).hasSize(4);
        assertThat(SessionJournalLogReader.readAfter(tempDir, 2)).isEqualTo(part2);
        assertThat(SessionJournalLogReader.readRange(tempDir, 2, 3).stream()
            .map(SessionJournalLogEntry::seq).toList()).containsExactly(2L, 3L).inOrder();
    }

    @Test
    void tailWindowsOutputAndInputSeparately() throws IOException {
        OffsetDateTime base = OffsetDateTime.of(2026, 8, 3, 15, 0, 0, 0, ZoneOffset.ofHours(2));
        List<SessionJournalLogEntry> entries = new java.util.ArrayList<>();
        long seq = 0;
        for (int i = 0; i < 10; i++) {
            entries.add(new SessionJournalLogEntry(++seq, base, SessionJournalLogEntry.Kind.OUT, "out-" + i, false, false, null));
        }
        for (int i = 0; i < 5; i++) {
            entries.add(new SessionJournalLogEntry(++seq, base, SessionJournalLogEntry.Kind.IN, "in-" + i, false, false, null));
        }
        writePart(SessionJournalLogFormat.XML, 1, entries, false);

        SessionJournalLogTail tail = SessionJournalLogReader.readTail(tempDir, 3, 2);
        assertThat(tail.output().stream().map(SessionJournalLogEntry::text).toList())
            .containsExactly("out-7", "out-8", "out-9").inOrder();
        assertThat(tail.input().stream().map(SessionJournalLogEntry::text).toList())
            .containsExactly("in-3", "in-4").inOrder();
        assertThat(tail.lastSeq()).isEqualTo(15);
    }

    @Test
    void tailSkipsStalePartialLinesButKeepsFinalPrompt() throws IOException {
        OffsetDateTime base = OffsetDateTime.of(2026, 8, 3, 15, 0, 0, 0, ZoneOffset.ofHours(2));
        List<SessionJournalLogEntry> entries = List.of(
            new SessionJournalLogEntry(1, base, SessionJournalLogEntry.Kind.OUT, "progress 45%", false, true, null),
            new SessionJournalLogEntry(2, base, SessionJournalLogEntry.Kind.OUT, "progress 100%", false, false, null),
            new SessionJournalLogEntry(3, base, SessionJournalLogEntry.Kind.OUT, "daniel@web01 ~ $", false, true, null));
        writePart(SessionJournalLogFormat.XML, 1, entries, false);

        SessionJournalLogTail tail = SessionJournalLogReader.readTail(tempDir, 10, 10);
        assertThat(tail.output().stream().map(SessionJournalLogEntry::text).toList())
            .containsExactly("progress 100%", "daniel@web01 ~ $").inOrder();
    }

    @Test
    void xmlEntriesAreOnePhysicalLineEach() {
        SessionJournalLogSerializer serializer = SessionJournalLogSerializer.forFormat(SessionJournalLogFormat.XML);
        for (SessionJournalLogEntry entry : sampleEntries()) {
            String line = serializer.entryLine(entry);
            assertThat(line).endsWith("\n");
            assertThat(line.substring(0, line.length() - 1)).doesNotContain("\n");
        }
    }
}
