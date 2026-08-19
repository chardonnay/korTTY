package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalLogFormat;
import de.kortty.model.SessionJournalMeta;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * End-to-end coalescing and backpressure semantics of a live capture session: duplicate floods
 * collapse to head + repeat entries in causal order, close() flushes the counted tail, and a
 * distinct-line flood far beyond the queue capacity loses nothing.
 */
class SessionJournalSessionCoalescingTest {

    private Path tempDir;
    private SessionJournalService service;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-session-journal-coalescing-test");
        GlobalSettings settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
        service = new SessionJournalService();
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
                    throw new IllegalStateException("Failed to delete temp path " + path, e);
                }
            });
        }
    }

    private SessionJournalSession newSession(long maxLogSizeBytes, int maxLogParts) throws IOException {
        Path directory = tempDir.resolve("journal");
        Files.createDirectories(directory);
        SessionJournalMeta meta = new SessionJournalMeta();
        meta.setConnectionName("Coalescing Test");
        meta.setHost("192.168.1.9");
        meta.setPort(22);
        meta.setUsername("daniel");
        meta.setStartedAt(OffsetDateTime.now());
        return new SessionJournalSession(
            service, directory, "journal-coalescing-test", SessionJournalLogFormat.JSON, meta,
            "tab-1234567890ab", true, false, 0, maxLogSizeBytes, maxLogParts,
            new SessionJournalRedactor());
    }

    @Test
    void aDuplicateFloodCollapsesToHeadPlusRepeatInCausalOrder() throws IOException {
        SessionJournalSession session = newSession(10L * 1024 * 1024, 20);
        session.start();
        for (int i = 0; i < 50; i++) {
            session.appendOutputChunk("retrying connection\n");
        }
        // The input line breaks the run; its repeat entry must precede the IN entry.
        session.appendInputLine("systemctl restart app");
        session.appendOutputChunk("done\n");
        session.close();

        List<SessionJournalLogEntry> entries =
            SessionJournalLogReader.readAfter(session.getDirectory(), 0);
        List<SessionJournalLogEntry> content = entries.stream()
            .filter(e -> e.kind() != SessionJournalLogEntry.Kind.NOTE)
            .toList();

        assertThat(content.stream().map(SessionJournalLogEntry::text).toList())
            .containsExactly("retrying connection", "retrying connection", "systemctl restart app", "done")
            .inOrder();
        SessionJournalLogEntry head = content.get(0);
        SessionJournalLogEntry repeat = content.get(1);
        assertThat(head.repeat()).isEqualTo(1);
        assertThat(repeat.repeat()).isEqualTo(49);
        // Sum of repeat over all entries equals the original line count.
        assertThat(content.stream().filter(e -> e.text().equals("retrying connection"))
            .mapToInt(SessionJournalLogEntry::repeat).sum()).isEqualTo(50);
        // The repeat entry brackets the run: its timestamp is not before the head's.
        assertThat(repeat.timestamp().isBefore(head.timestamp())).isFalse();
        // Suppressed duplicates never burned sequence numbers.
        assertThat(repeat.seq()).isEqualTo(head.seq() + 1);
    }

    @Test
    void closeFlushesTheCountedTailOfAnOpenRun() throws IOException {
        SessionJournalSession session = newSession(10L * 1024 * 1024, 20);
        session.start();
        for (int i = 0; i < 7; i++) {
            session.appendOutputChunk("tail line\n");
        }
        session.close(); // no break before close — the tail counter only exists in memory

        List<SessionJournalLogEntry> outs =
            SessionJournalLogReader.readAfter(session.getDirectory(), 0).stream()
                .filter(e -> e.kind() == SessionJournalLogEntry.Kind.OUT)
                .toList();
        assertThat(outs).hasSize(2);
        assertThat(outs.get(0).repeat()).isEqualTo(1);
        assertThat(outs.get(1).repeat()).isEqualTo(6);
    }

    @Test
    void aDistinctLineFloodBeyondQueueCapacityLosesNothing() throws IOException {
        SessionJournalSession session = newSession(50L * 1024 * 1024, 20);
        session.start();
        int lines = 25_000; // 2.5x the queue capacity
        for (int i = 0; i < lines; i++) {
            session.appendOutputChunk("distinct line number " + i + "\n");
        }
        session.close();

        List<SessionJournalLogEntry> outs =
            SessionJournalLogReader.readAfter(session.getDirectory(), 0).stream()
                .filter(e -> e.kind() == SessionJournalLogEntry.Kind.OUT)
                .toList();
        assertThat(outs).hasSize(lines);
        long previousSeq = 0;
        for (int i = 0; i < lines; i++) {
            assertThat(outs.get(i).text()).isEqualTo("distinct line number " + i);
            assertThat(outs.get(i).seq()).isGreaterThan(previousSeq);
            previousSeq = outs.get(i).seq();
        }
    }
}
