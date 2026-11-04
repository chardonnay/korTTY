package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

/**
 * "Re-evaluate with AI…" in the background: the run belongs to the job, not to a window; it reports
 * which journal it works on, cancels without leaving a journal empty, and changes nothing when the
 * profile fails its test.
 */
class SessionJournalReevaluationJobTest {

    /** Answers every call with a summary titled {@code title}; can hold each call until released. */
    private static final class ScriptedInvoker implements SessionJournalAiSupport.AiInvoker {
        private final String title;
        private final boolean failsTest;
        private final CountDownLatch release;
        private final CountDownLatch firstCall = new CountDownLatch(1);
        private final AtomicInteger calls = new AtomicInteger();

        ScriptedInvoker(String title, boolean failsTest, CountDownLatch release) {
            this.title = title;
            this.failsTest = failsTest;
            this.release = release;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public void testConnection() throws Exception {
            if (failsTest) {
                throw new IOException("connection refused");
            }
        }

        @Override
        public AiExecutionResult execute(String systemPrompt, String userPrompt) throws Exception {
            calls.incrementAndGet();
            firstCall.countDown();
            if (release != null) {
                release.await(20, TimeUnit.SECONDS);
            }
            return new AiExecutionResult("{\"title\":\"" + title + "\",\"summary\":\"" + title
                + " summary.\",\"category\":\"info\"}", new AiTokenUsage(30, 10, 40));
        }
    }

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;
    private SessionJournalSummarizer summarizer;
    private SessionJournalReevaluationJob job;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-journal-reevaluation-job");
        settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
        service = new SessionJournalService();
        summarizer = new SessionJournalSummarizer(service, () -> settings, new ScriptedInvoker("First", false, null));
        job = new SessionJournalReevaluationJob(service, summarizer, null, null);
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        job.stop();
        summarizer.stop();
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private Path closedJournal(String tab) throws Exception {
        ServerConnection connection = new ServerConnection("Test Server", "192.168.1.9", 22, "daniel");
        connection.getSessionJournalConfig().setEnabled(true);
        SessionJournalSession session = service.createSession(connection, tab, settings, List.of(), false);
        session.start();
        session.appendInputLine("df -h");
        session.appendOutputChunk("/dev/disk1  100G  42G  58G  42% /\n");
        Path dir = session.getDirectory();
        session.close();
        summarizer.summarizeClosedJournal(dir, new ScriptedInvoker("First", false, null), true).get(30, TimeUnit.SECONDS);
        return dir;
    }

    private SessionJournalReevaluationJob.Request request(ScriptedInvoker invoker, Path... dirs) {
        List<SessionJournalReevaluationJob.Target> targets = new ArrayList<>();
        for (Path dir : dirs) {
            targets.add(new SessionJournalReevaluationJob.Target(dir, dir.getFileName().toString()));
        }
        return new SessionJournalReevaluationJob.Request(targets, null, "Second", invoker, false);
    }

    /** Waits until the job has reported an outcome. */
    private SessionJournalReevaluationJob.Outcome awaitOutcome() throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            SessionJournalReevaluationJob.Snapshot snapshot = job.snapshot();
            if (!snapshot.running() && snapshot.lastOutcome() != null) {
                return snapshot.lastOutcome();
            }
            Thread.sleep(20);
        }
        throw new AssertionError("re-evaluation did not finish");
    }

    private List<SessionJournalEntry> summaries(Path dir) throws IOException {
        return service.loadDocument(dir).getEntries().stream()
            .filter(e -> e.getKind() == SessionJournalEntryKind.AI_SUMMARY)
            .toList();
    }

    @Test(timeOut = 60_000)
    void aRunReplacesTheSummariesInTheBackgroundAndReportsTheResult() throws Exception {
        Path dir = closedJournal("tab-reevaljob01");
        AtomicInteger events = new AtomicInteger();
        job.addListener(events::incrementAndGet);

        assertThat(job.submit(request(new ScriptedInvoker("Second", false, null), dir))).isTrue();
        SessionJournalReevaluationJob.Outcome outcome = awaitOutcome();

        assertThat(outcome.processed()).isEqualTo(1);
        assertThat(outcome.tokens()).isGreaterThan(0L);
        assertThat(outcome.cancelled()).isFalse();
        assertThat(outcome.hasProblems()).isFalse();
        assertThat(summaries(dir)).isNotEmpty();
        assertThat(summaries(dir).stream().allMatch(e -> e.getTitle().contains("Second"))).isTrue();
        assertThat(events.get()).isGreaterThan(2);
        assertThat(job.stateOf(dir)).isEqualTo(SessionJournalReevaluationJob.JournalState.NONE);
    }

    @Test(timeOut = 60_000)
    void whileACallRunsTheJobNamesTheJournalAndHasADeterminateBar() throws Exception {
        Path first = closedJournal("tab-reevaljob02");
        Path second = closedJournal("tab-reevaljob03");
        CountDownLatch release = new CountDownLatch(1);
        ScriptedInvoker invoker = new ScriptedInvoker("Second", false, release);

        job.submit(request(invoker, first, second));
        assertThat(invoker.firstCall.await(20, TimeUnit.SECONDS)).isTrue();
        SessionJournalReevaluationJob.Snapshot snapshot = job.snapshot();

        assertThat(snapshot.phase()).isEqualTo(SessionJournalReevaluationJob.Phase.RUNNING);
        assertThat(snapshot.journalNumber()).isEqualTo(1);
        assertThat(snapshot.journalCount()).isEqualTo(2);
        assertThat(snapshot.estimate()).isNotNull();
        assertThat(snapshot.estimate().stepKind()).isEqualTo(SessionJournalReevaluationProgress.StepKind.WINDOW);
        assertThat(job.stateOf(first)).isEqualTo(SessionJournalReevaluationJob.JournalState.RUNNING);
        assertThat(job.stateOf(second)).isEqualTo(SessionJournalReevaluationJob.JournalState.WAITING);
        // A journal already in a run is not queued twice.
        assertThat(job.submit(request(invoker, second))).isFalse();

        release.countDown();
        assertThat(awaitOutcome().processed()).isEqualTo(2);
    }

    @Test(timeOut = 60_000)
    void cancellingStopsBeforeTheNextCallAndKeepsTheActivityVisible() throws Exception {
        Path first = closedJournal("tab-reevaljob04");
        Path second = closedJournal("tab-reevaljob05");
        long secondBefore = summaries(second).size();
        CountDownLatch release = new CountDownLatch(1);
        ScriptedInvoker invoker = new ScriptedInvoker("Second", false, release);

        job.submit(request(invoker, first, second));
        assertThat(invoker.firstCall.await(20, TimeUnit.SECONDS)).isTrue();
        job.cancel();
        assertThat(job.snapshot().phase()).isEqualTo(SessionJournalReevaluationJob.Phase.CANCELLING);
        release.countDown();
        SessionJournalReevaluationJob.Outcome outcome = awaitOutcome();

        assertThat(outcome.cancelled()).isTrue();
        // The window call in flight finished; the wrap-up after it was never asked for.
        assertThat(invoker.calls.get()).isEqualTo(1);
        assertThat(summaries(first)).isNotEmpty();
        assertThat(service.loadDocument(first).getEntries().stream()
            .noneMatch(e -> e.getKind() == SessionJournalEntryKind.SESSION_SUMMARY)).isTrue();
        // The second journal was never touched.
        assertThat(summaries(second)).hasSize((int) secondBefore);
        assertThat(summaries(second).stream().allMatch(e -> e.getTitle().contains("First"))).isTrue();
    }

    @Test(timeOut = 60_000)
    void aProfileThatFailsItsTestChangesNothing() throws Exception {
        Path dir = closedJournal("tab-reevaljob06");
        List<String> before = summaries(dir).stream().map(SessionJournalEntry::getTitle).toList();

        job.submit(request(new ScriptedInvoker("Broken", true, null), dir));
        SessionJournalReevaluationJob.Outcome outcome = awaitOutcome();

        assertThat(outcome.testFailed()).isTrue();
        assertThat(outcome.testFailure()).contains("connection refused");
        assertThat(summaries(dir).stream().map(SessionJournalEntry::getTitle).toList()).isEqualTo(before);
    }

    @Test(timeOut = 60_000)
    void aSecondRequestWaitsForTheFirstAndCancelDropsIt() throws Exception {
        Path first = closedJournal("tab-reevaljob07");
        Path second = closedJournal("tab-reevaljob08");
        CountDownLatch release = new CountDownLatch(1);
        ScriptedInvoker invoker = new ScriptedInvoker("Second", false, release);

        job.submit(request(invoker, first));
        assertThat(invoker.firstCall.await(20, TimeUnit.SECONDS)).isTrue();
        job.submit(request(new ScriptedInvoker("Third", false, null), second));
        assertThat(job.snapshot().queuedRuns()).isEqualTo(1);
        assertThat(job.stateOf(second)).isEqualTo(SessionJournalReevaluationJob.JournalState.WAITING);

        job.cancel();
        release.countDown();
        awaitOutcome();
        Thread.sleep(200);

        assertThat(job.isRunning()).isFalse();
        assertThat(summaries(second).stream().noneMatch(e -> e.getTitle().contains("Third"))).isTrue();
    }

    @Test(timeOut = 60_000)
    void theOutcomeStaysUntilDismissed() throws Exception {
        Path dir = closedJournal("tab-reevaljob09");
        job.submit(request(new ScriptedInvoker("Second", false, null), dir));
        awaitOutcome();

        assertThat(job.snapshot().lastOutcome()).isNotNull();
        job.dismissOutcome();
        assertThat(job.snapshot().lastOutcome()).isNull();
        assertThat(job.snapshot().running()).isFalse();
    }
}
