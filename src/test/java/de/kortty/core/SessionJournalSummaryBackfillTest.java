package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalLogFormat;
import de.kortty.model.SessionJournalMeta;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

class SessionJournalSummaryBackfillTest {

    private static final OffsetDateTime BASE =
        OffsetDateTime.of(2026, 8, 3, 14, 15, 3, 0, ZoneOffset.ofHours(2));

    private static final class CountingInvoker implements SessionJournalAiSupport.AiInvoker {
        final AtomicInteger calls = new AtomicInteger();
        volatile boolean fail;

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public AiExecutionResult execute(String systemPrompt, String userPrompt) throws Exception {
            calls.incrementAndGet();
            if (fail) {
                throw new IOException("simulated AI outage");
            }
            return new AiExecutionResult(
                "{\"title\":\"Backfilled\",\"summary\":\"Something happened.\",\"category\":\"none\"}",
                null, null);
        }
    }

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;
    private CountingInvoker invoker;
    private SessionJournalSummarizer summarizer;
    private SessionJournalSummaryBackfill backfill;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-backfill-test");
        settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.toString());
        service = new SessionJournalService();
        invoker = new CountingInvoker();
        summarizer = new SessionJournalSummarizer(service, () -> settings, invoker);
        backfill = new SessionJournalSummaryBackfill(service, summarizer);
    }

    @AfterMethod
    void tearDown() throws IOException {
        summarizer.stop();
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

    private SessionJournalMeta journal(String name, long lastSummarizedSeq, int logLines)
            throws IOException {
        Path dir = Files.createDirectories(tempDir.resolve(name));
        SessionJournalMeta meta = new SessionJournalMeta();
        meta.setTitle(name);
        meta.setHost("192.168.1.9");
        meta.setUsername("daniel");
        meta.setConnectionName(name);
        meta.setStartedAt(BASE);
        meta.setEndedAt(BASE.plusMinutes(5));
        meta.setLastSummarizedSeq(lastSummarizedSeq);
        meta.setLogEntryCount(logLines);
        meta.setDirectory(dir);

        SessionJournalDocument document = new SessionJournalDocument();
        document.setMeta(new SessionJournalMeta(meta));
        service.saveDocument(dir, document);

        if (logLines > 0) {
            SessionJournalLogSerializer serializer =
                SessionJournalLogSerializer.forFormat(SessionJournalLogFormat.JSON);
            StringBuilder sb = new StringBuilder();
            sb.append(serializer.header(name, 1, meta, "tab-1"));
            for (long seq = 1; seq <= logLines; seq++) {
                sb.append(serializer.entryLine(new SessionJournalLogEntry(
                    seq, BASE.plusSeconds(seq),
                    seq % 3 == 0 ? SessionJournalLogEntry.Kind.IN : SessionJournalLogEntry.Kind.OUT,
                    "line " + seq, false, false, null)));
            }
            sb.append(serializer.footer());
            Files.writeString(dir.resolve(
                    SessionJournalLogReader.partFileName(1, SessionJournalLogFormat.JSON)),
                sb.toString(), StandardCharsets.UTF_8);
        }
        return meta;
    }

    @Test
    void findsOnlyNeverSummarizedClosedJournalsWithOutput() throws IOException {
        journal("never-summarized", 0, 8);
        journal("already-summarized", 42, 8);
        journal("empty-journal", 0, 0);

        List<SessionJournalMeta> candidates = backfill.findCandidates(settings);

        assertThat(candidates).hasSize(1);
        assertThat(candidates.get(0).getTitle()).isEqualTo("never-summarized");
    }

    @Test
    void runSummarizesAndReportsProgress() throws IOException {
        journal("first", 0, 8);
        journal("second", 0, 8);
        List<SessionJournalMeta> candidates = backfill.findCandidates(settings);
        List<SessionJournalSummaryBackfill.Progress> progress = new ArrayList<>();

        SessionJournalSummaryBackfill.Outcome outcome =
            backfill.run(candidates, progress::add, () -> false);

        assertThat(outcome.processed()).isEqualTo(2);
        assertThat(outcome.cancelled()).isFalse();
        assertThat(outcome.failedTitles()).isEmpty();
        assertThat(invoker.calls.get()).isGreaterThan(0);
        assertThat(progress.get(0).total()).isEqualTo(2);
        // Both journals now carry summarization progress — a re-run finds no candidates.
        assertThat(backfill.findCandidates(settings)).isEmpty();
    }

    @Test
    void collectsFailuresInsteadOfAborting() throws IOException {
        journal("doomed", 0, 8);
        invoker.fail = true;

        SessionJournalSummaryBackfill.Outcome outcome =
            backfill.run(backfill.findCandidates(settings), null, () -> false);

        assertThat(outcome.processed()).isEqualTo(1);
        assertThat(outcome.failedTitles()).containsExactly("doomed");
    }

    @Test
    void cancellationStopsBeforeTheNextJournal() throws IOException {
        journal("first", 0, 8);
        journal("second", 0, 8);
        List<SessionJournalMeta> candidates = backfill.findCandidates(settings);
        AtomicInteger seen = new AtomicInteger();

        SessionJournalSummaryBackfill.Outcome outcome = backfill.run(
            candidates,
            progress -> seen.incrementAndGet(),
            () -> seen.get() >= 1); // cancel after the first journal started

        assertThat(outcome.cancelled()).isTrue();
        assertThat(outcome.processed()).isLessThan(2);
    }
}
