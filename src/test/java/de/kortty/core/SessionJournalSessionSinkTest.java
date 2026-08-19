package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import static com.google.common.truth.Truth.assertThat;

/** The live-entry sink must mirror exactly what the writer thread persisted — nothing more, nothing less. */
class SessionJournalSessionSinkTest {

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-session-journal-sink-test");
        settings = new GlobalSettings();
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

    private ServerConnection sampleConnection() {
        ServerConnection connection = new ServerConnection("Sink Test", "192.168.1.9", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        return connection;
    }

    private SessionJournalSession sessionWithTinyParts(long maxLogSizeBytes) throws IOException {
        Path directory = tempDir.resolve("tiny-journal");
        Files.createDirectories(directory);
        SessionJournalMeta meta = new SessionJournalMeta();
        meta.setConnectionName("Sink Test");
        meta.setHost("192.168.1.9");
        meta.setPort(22);
        meta.setUsername("daniel");
        meta.setStartedAt(OffsetDateTime.now());
        return new SessionJournalSession(
            service, directory, "journal-sink-test", SessionJournalLogFormat.JSON, meta,
            "tab-1234567890ab", true, false, 0, maxLogSizeBytes, 20, new SessionJournalRedactor());
    }

    @Test
    void deliversPersistedEntriesInSequenceToRegisteredSinks() throws IOException {
        SessionJournalSession session = service.createSession(
            sampleConnection(), "tab-1234567890ab", settings, List.of(), false);
        List<SessionJournalLogEntry> received = new CopyOnWriteArrayList<>();
        session.addLiveEntrySink(received::add);
        session.start();

        session.appendOutputChunk("Welcome to web01\r\n");
        session.appendInputLine("uptime");
        session.noteReconnect();
        waitUntil(() -> received.size() >= 3);
        session.close();

        assertThat(received.stream().map(SessionJournalLogEntry::kind).toList()).containsExactly(
            SessionJournalLogEntry.Kind.OUT,
            SessionJournalLogEntry.Kind.IN,
            SessionJournalLogEntry.Kind.NOTE).inOrder();
        assertThat(received.get(0).text()).isEqualTo("Welcome to web01");
        assertThat(received.get(1).text()).isEqualTo("uptime");
        assertThat(received.get(2).text()).isEqualTo("session reconnected");
        assertThat(received.stream().map(SessionJournalLogEntry::seq).toList()).isInOrder();
    }

    @Test
    void failingSinkNeverStopsWritingOrStarvesOtherSinks() throws IOException {
        SessionJournalSession session = service.createSession(
            sampleConnection(), "tab-1234567890ab", settings, List.of(), false);
        List<SessionJournalLogEntry> received = new CopyOnWriteArrayList<>();
        session.addLiveEntrySink(entry -> {
            throw new IllegalStateException("boom");
        });
        session.addLiveEntrySink(received::add);
        session.start();

        session.appendInputLine("ls -la");
        session.appendInputLine("pwd");
        waitUntil(() -> received.size() >= 2);
        session.close();

        assertThat(received.stream().map(SessionJournalLogEntry::text).toList())
            .containsExactly("ls -la", "pwd").inOrder();
        List<SessionJournalLogEntry> persisted =
            SessionJournalLogReader.readAfter(session.getDirectory(), 0);
        assertThat(persisted.stream().map(SessionJournalLogEntry::text).toList())
            .containsAtLeast("ls -la", "pwd").inOrder();
    }

    @Test
    void userNotesReachTheSinkAndTheLog() throws IOException {
        SessionJournalSession session = service.createSession(
            sampleConnection(), "tab-1234567890ab", settings, List.of("vault-secret-pw"), false);
        List<SessionJournalLogEntry> received = new CopyOnWriteArrayList<>();
        session.addLiveEntrySink(received::add);
        session.start();

        session.appendUserNote("checkpoint before restart of vault-secret-pw");
        waitUntil(() -> received.size() >= 1);
        session.close();

        assertThat(received.get(0).kind()).isEqualTo(SessionJournalLogEntry.Kind.NOTE);
        assertThat(received.get(0).text()).doesNotContain("vault-secret-pw");
        assertThat(received.get(0).text()).contains("checkpoint before restart");
        List<SessionJournalLogEntry> persisted =
            SessionJournalLogReader.readAfter(session.getDirectory(), 0);
        assertThat(persisted.stream().anyMatch(e ->
            e.kind() == SessionJournalLogEntry.Kind.NOTE
                && e.text().startsWith("checkpoint before restart"))).isTrue();
    }

    @Test
    void removedSinkReceivesNothingFurther() throws IOException {
        SessionJournalSession session = service.createSession(
            sampleConnection(), "tab-1234567890ab", settings, List.of(), false);
        List<SessionJournalLogEntry> received = new CopyOnWriteArrayList<>();
        Consumer<SessionJournalLogEntry> sink = received::add;
        session.addLiveEntrySink(sink);
        session.start();

        session.appendInputLine("first");
        waitUntil(() -> received.size() >= 1);
        session.removeLiveEntrySink(sink);
        session.appendInputLine("second");
        session.close();

        assertThat(received.stream().map(SessionJournalLogEntry::text).toList())
            .containsExactly("first");
    }

    @Test
    void rotationNotesReachTheSinkAndOutputStopsAfterCaptureStop() throws IOException {
        SessionJournalSession session = sessionWithTinyParts(700);
        List<SessionJournalLogEntry> received = new CopyOnWriteArrayList<>();
        session.addLiveEntrySink(received::add);
        session.start();

        // 20 tiny parts fill fast; keep appending until the safety valve trips.
        for (int i = 0; i < 400 && !outputCaptureStoppedNoteSeen(received); i++) {
            session.appendOutputChunk("line-" + i + " padding-padding-padding-padding\r\n");
            if (i % 50 == 49) {
                waitUntil(() -> received.size() > 0);
            }
        }
        waitUntil(() -> outputCaptureStoppedNoteSeen(received));
        assertThat(outputCaptureStoppedNoteSeen(received)).isTrue();
        assertThat(received.stream().anyMatch(e ->
            e.kind() == SessionJournalLogEntry.Kind.NOTE
                && e.text().startsWith("continued in part "))).isTrue();

        int sizeAtStop = received.size();
        session.appendOutputChunk("after-stop output\r\n");
        session.appendInputLine("input still captured");
        waitUntil(() -> received.size() > sizeAtStop);
        session.close();

        List<SessionJournalLogEntry> afterStop = received.subList(sizeAtStop, received.size());
        assertThat(afterStop.stream().noneMatch(e ->
            e.kind() == SessionJournalLogEntry.Kind.OUT
                || e.kind() == SessionJournalLogEntry.Kind.SEED)).isTrue();
        assertThat(afterStop.stream().anyMatch(e ->
            e.kind() == SessionJournalLogEntry.Kind.IN
                && e.text().equals("input still captured"))).isTrue();
    }

    private static boolean outputCaptureStoppedNoteSeen(List<SessionJournalLogEntry> entries) {
        return entries.stream().anyMatch(e ->
            e.kind() == SessionJournalLogEntry.Kind.NOTE
                && e.text().startsWith("output capture stopped"));
    }

    private static void waitUntil(java.util.function.BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + 5000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }
}
