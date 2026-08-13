package de.kortty.ui;

import de.kortty.core.SessionJournalLogEntry;
import de.kortty.core.SessionJournalService;
import de.kortty.core.SessionJournalSession;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.google.common.truth.Truth.assertThat;

/** The feed must deliver every log entry exactly once: backfill for the past, batches for the present. */
class SessionJournalLiveFeedTest {

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;
    private SessionJournalSession session;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-journal-live-feed-test");
        settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
        service = new SessionJournalService();
        ServerConnection connection = new ServerConnection("Feed Test", "192.168.1.9", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        session = service.createSession(connection, "tab-1234567890ab", settings, List.of(), false);
        session.start();
    }

    @AfterMethod
    void tearDown() throws IOException {
        if (session != null) {
            session.close();
        }
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

    @Test
    void backfillsThePastAndStreamsTheFutureWithoutDuplicates() {
        session.appendInputLine("before-1");
        session.appendInputLine("before-2");
        waitUntil(() -> {
            try {
                return de.kortty.core.SessionJournalLogReader
                    .readAfter(session.getDirectory(), 0).size() >= 2;
            } catch (IOException e) {
                return false;
            }
        });

        List<List<SessionJournalLogEntry>> backfills = new CopyOnWriteArrayList<>();
        List<SessionJournalLogEntry> live = new CopyOnWriteArrayList<>();
        SessionJournalLiveFeed feed = new SessionJournalLiveFeed(
            session, 5000, 0, Runnable::run, backfills::add, live::addAll);
        feed.start();
        waitUntil(() -> !backfills.isEmpty());

        session.appendInputLine("after-1");
        session.appendInputLine("after-2");
        waitUntil(() -> live.size() >= 2);
        feed.stop();

        assertThat(backfills).hasSize(1);
        List<String> backfilled = backfills.get(0).stream().map(SessionJournalLogEntry::text).toList();
        assertThat(backfilled).containsAtLeast("before-1", "before-2").inOrder();
        List<String> streamed = live.stream().map(SessionJournalLogEntry::text).toList();
        assertThat(streamed).containsExactly("after-1", "after-2").inOrder();

        // No entry may appear in both phases.
        List<Long> allSeqs = new java.util.ArrayList<>();
        backfills.get(0).forEach(e -> allSeqs.add(e.seq()));
        live.forEach(e -> allSeqs.add(e.seq()));
        assertThat(allSeqs).containsNoDuplicates();
    }

    @Test
    void coalescesBurstsIntoFewBatches() {
        List<List<SessionJournalLogEntry>> backfills = new CopyOnWriteArrayList<>();
        List<List<SessionJournalLogEntry>> batches = new CopyOnWriteArrayList<>();
        SessionJournalLiveFeed feed = new SessionJournalLiveFeed(
            session, 5000, 100, Runnable::run, backfills::add, batches::add);
        feed.start();
        waitUntil(() -> !backfills.isEmpty());

        for (int i = 0; i < 50; i++) {
            session.appendInputLine("burst-" + i);
        }
        waitUntil(() -> batches.stream().mapToInt(List::size).sum() >= 50);
        feed.stop();

        assertThat(batches.stream().mapToInt(List::size).sum()).isEqualTo(50);
        assertThat(batches.size()).isLessThan(50);
    }

    @Test
    void stopDetachesAndDropsEverythingAfter() {
        List<List<SessionJournalLogEntry>> backfills = new CopyOnWriteArrayList<>();
        List<SessionJournalLogEntry> live = new CopyOnWriteArrayList<>();
        SessionJournalLiveFeed feed = new SessionJournalLiveFeed(
            session, 5000, 0, Runnable::run, backfills::add, live::addAll);
        feed.start();
        waitUntil(() -> !backfills.isEmpty());
        session.appendInputLine("delivered");
        waitUntil(() -> live.size() >= 1);

        feed.stop();
        session.appendInputLine("dropped");
        session.noteReconnect();
        sleep(150);

        assertThat(live.stream().map(SessionJournalLogEntry::text).toList())
            .containsExactly("delivered");
    }

    @Test
    void startAndStopAreIdempotent() {
        SessionJournalLiveFeed feed = new SessionJournalLiveFeed(
            session, 5000, 0, Runnable::run, entries -> { }, entries -> { });
        feed.start();
        feed.start();
        feed.stop();
        feed.stop();
        assertThat(feed.isStopped()).isTrue();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
