package de.kortty.core;

import de.kortty.model.AutomationJournalAiMode;
import de.kortty.model.AutomationJournalConfig;
import de.kortty.model.AutomationJournalKeepMode;
import de.kortty.model.AutomationRunStatus;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionJournalMeta;
import de.kortty.model.SessionJournalSourceKind;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

class AutomationJournalRunTest {

    /** Answers every summary prompt and counts the calls. */
    private static final class CountingInvoker implements SessionJournalAiSupport.AiInvoker {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public AiExecutionResult execute(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            return new AiExecutionResult(
                "{\"title\":\"Checked uptime\",\"summary\":\"The job checked the uptime.\",\"category\":\"info\"}",
                new AiTokenUsage(40, 10, 50));
        }
    }

    private Path tempDir;
    private GlobalSettings settings;
    private SessionJournalService service;
    private CountingInvoker invoker;
    private SessionJournalSummarizer summarizer;
    private final AtomicInteger limitCalls = new AtomicInteger();

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-automation-journal-run-test");
        settings = new GlobalSettings();
        settings.setSessionJournalStoragePath(tempDir.resolve("journals").toString());
        service = new SessionJournalService();
        invoker = new CountingInvoker();
        summarizer = new SessionJournalSummarizer(service, () -> settings, invoker);
        limitCalls.set(0);
    }

    @AfterMethod
    void tearDown() throws IOException {
        summarizer.stop();
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    private static ServerConnection connection(String id, String host) {
        ServerConnection connection = new ServerConnection("Server " + host, host, 22, "root");
        connection.setId(id);
        return connection;
    }

    private static AutomationJournalConfig config() {
        AutomationJournalConfig config = new AutomationJournalConfig();
        config.setEnabled(true);
        return config;
    }

    private AutomationJournalRun run(AutomationJournalConfig config, AutomationJournalPolicy policy) {
        return new AutomationJournalRun(
            new AutomationJournalRun.Source(SessionJournalSourceKind.JOB, "job-1", "Nightly check", "COMMAND"),
            UUID.randomUUID().toString(),
            config,
            service,
            summarizer,
            () -> settings,
            id -> invoker,
            policy,
            (source, cfg) -> limitCalls.incrementAndGet(),
            Clock.systemDefaultZone());
    }

    private static void record(AutomationJournalRun run, ServerConnection connection, String output) {
        AutomationJournalRecorder recorder = run.recorderFor(connection);
        recorder.appendCommand("uptime");
        recorder.appendOutput(output, null);
    }

    private static void await(AutomationJournalRun.FinishResult result) throws Exception {
        result.summariesDone().get(30, TimeUnit.SECONDS);
    }

    private SessionJournalMeta meta(Path dir) throws IOException {
        return service.loadDocument(dir).getMeta();
    }

    @Test
    void keptRunIsDescribedExpiresAndGetsSummarized() throws Exception {
        AutomationJournalRun run = run(config(), AutomationJournalPolicy.UNRESTRICTED);
        record(run, connection("c1", "web01"), " 10:00 up 3 days");

        AutomationJournalRun.FinishResult result = run.finish(AutomationRunStatus.SUCCESS);
        await(result);

        assertThat(result.kept()).hasSize(1);
        SessionJournalMeta meta = meta(result.kept().get(0));
        assertThat(meta.getSourceKind()).isEqualTo(SessionJournalSourceKind.JOB);
        assertThat(meta.getSourceId()).isEqualTo("job-1");
        assertThat(meta.getRunId()).isEqualTo(run.runId());
        assertThat(meta.getRunStatus()).isEqualTo(AutomationRunStatus.SUCCESS);
        assertThat(meta.getTitle()).contains("Nightly check");
        assertThat(meta.getExpiresAt()).isGreaterThan(OffsetDateTime.now().plusDays(13));
        assertThat(meta.getContentHash()).isNotEmpty();
        assertThat(invoker.calls.get()).isGreaterThan(0);
        assertThat(meta.getAiTotalTokens()).isGreaterThan(0L);
        assertThat(limitCalls.get()).isEqualTo(1);
    }

    @Test
    void onlyOnFailureDiscardsASuccessfulRunWithoutCallingTheAi() throws Exception {
        AutomationJournalConfig config = config();
        config.setKeepMode(AutomationJournalKeepMode.ONLY_ON_FAILURE);
        AutomationJournalRun run = run(config, AutomationJournalPolicy.UNRESTRICTED);
        record(run, connection("c1", "web01"), "ok");

        AutomationJournalRun.FinishResult result = run.finish(AutomationRunStatus.SUCCESS);
        await(result);

        assertThat(result.kept()).isEmpty();
        assertThat(result.discarded()).isEqualTo(1);
        assertThat(invoker.calls.get()).isEqualTo(0);
        assertThat(service.listJournals(settings)).isEmpty();
    }

    @Test
    void onlyOnFailureKeepsAFailedTarget() throws Exception {
        AutomationJournalConfig config = config();
        config.setKeepMode(AutomationJournalKeepMode.ONLY_ON_FAILURE);
        AutomationJournalRun run = run(config, AutomationJournalPolicy.UNRESTRICTED);
        record(run, connection("c1", "web01"), "ok");
        record(run, connection("c2", "web02"), "disk full");
        run.recorderFor(connection("c1", "web01")).setTargetStatus(AutomationRunStatus.SUCCESS);
        run.recorderFor(connection("c2", "web02")).setTargetStatus(AutomationRunStatus.FAILED);

        AutomationJournalRun.FinishResult result = run.finish(AutomationRunStatus.FAILED);
        await(result);

        assertThat(result.kept()).hasSize(1);
        assertThat(meta(result.kept().get(0)).getHost()).isEqualTo("web02");
        assertThat(result.discarded()).isEqualTo(1);
    }

    @Test
    void aiOnFailureSummarizesOnlyFailedRuns() throws Exception {
        AutomationJournalConfig config = config();
        config.setAiMode(AutomationJournalAiMode.ON_FAILURE);
        config.setDedupEnabled(false);

        AutomationJournalRun success = run(config, AutomationJournalPolicy.UNRESTRICTED);
        record(success, connection("c1", "web01"), "ok");
        AutomationJournalRun.FinishResult ok = success.finish(AutomationRunStatus.SUCCESS);
        await(ok);
        assertThat(invoker.calls.get()).isEqualTo(0);
        assertThat(meta(ok.kept().get(0)).getAiCallCount()).isEqualTo(0);

        AutomationJournalRun failure = run(config, AutomationJournalPolicy.UNRESTRICTED);
        record(failure, connection("c1", "web01"), "error");
        await(failure.finish(AutomationRunStatus.FAILED));
        assertThat(invoker.calls.get()).isGreaterThan(0);
    }

    @Test
    void aiModeOffNeverCallsTheAi() throws Exception {
        AutomationJournalConfig config = config();
        config.setAiMode(AutomationJournalAiMode.OFF);
        AutomationJournalRun run = run(config, AutomationJournalPolicy.UNRESTRICTED);
        record(run, connection("c1", "web01"), "error");

        await(run.finish(AutomationRunStatus.FAILED));

        assertThat(invoker.calls.get()).isEqualTo(0);
    }

    @Test
    void aCancelledRunOverridesTheTargetStatus() throws Exception {
        AutomationJournalRun run = run(config(), AutomationJournalPolicy.UNRESTRICTED);
        record(run, connection("c1", "web01"), "ok");
        run.recorderFor(connection("c1", "web01")).setTargetStatus(AutomationRunStatus.SUCCESS);

        AutomationJournalRun.FinishResult result = run.finish(AutomationRunStatus.CANCELLED);
        await(result);

        assertThat(meta(result.kept().get(0)).getRunStatus()).isEqualTo(AutomationRunStatus.CANCELLED);
    }

    @Test
    void anIdenticalRunIsDiscardedInFavourOfTheEarlierJournal() throws Exception {
        AutomationJournalRun first = run(config(), AutomationJournalPolicy.UNRESTRICTED);
        record(first, connection("c1", "web01"), "same output");
        AutomationJournalRun.FinishResult firstResult = first.finish(AutomationRunStatus.SUCCESS);
        await(firstResult);
        Path reference = firstResult.kept().get(0);
        int callsAfterFirst = invoker.calls.get();

        AutomationJournalRun second = run(config(), AutomationJournalPolicy.UNRESTRICTED);
        record(second, connection("c1", "web01"), "same output");
        AutomationJournalRun.FinishResult secondResult = second.finish(AutomationRunStatus.SUCCESS);
        await(secondResult);

        assertThat(secondResult.kept()).isEmpty();
        assertThat(secondResult.duplicateOf()).containsExactly(reference);
        assertThat(meta(reference).getDuplicateRunCount()).isEqualTo(1);
        assertThat(meta(reference).getLastDuplicateAt()).isNotNull();
        assertThat(service.listJournals(settings)).hasSize(1);
        assertThat(invoker.calls.get()).isEqualTo(callsAfterFirst);
    }

    @Test
    void differentOutputOrDisabledDedupKeepsBothRuns() throws Exception {
        AutomationJournalRun first = run(config(), AutomationJournalPolicy.UNRESTRICTED);
        record(first, connection("c1", "web01"), "load 0.1");
        await(first.finish(AutomationRunStatus.SUCCESS));

        AutomationJournalRun changed = run(config(), AutomationJournalPolicy.UNRESTRICTED);
        record(changed, connection("c1", "web01"), "load 0.9");
        assertThat(changed.finish(AutomationRunStatus.SUCCESS).kept()).hasSize(1);

        AutomationJournalConfig noDedup = config();
        noDedup.setDedupEnabled(false);
        AutomationJournalRun same = run(noDedup, AutomationJournalPolicy.UNRESTRICTED);
        record(same, connection("c1", "web01"), "load 0.9");
        AutomationJournalRun.FinishResult result = same.finish(AutomationRunStatus.SUCCESS);
        await(result);
        assertThat(result.kept()).hasSize(1);
    }

    @Test
    void withoutDeletePermissionEveryJournalIsKept() throws Exception {
        AutomationJournalConfig config = config();
        config.setKeepMode(AutomationJournalKeepMode.ONLY_ON_FAILURE);
        AutomationJournalPolicy noDelete = new AutomationJournalPolicy(true, true, false, null, null, null);
        AutomationJournalRun run = run(config, noDelete);
        record(run, connection("c1", "web01"), "ok");

        AutomationJournalRun.FinishResult result = run.finish(AutomationRunStatus.SUCCESS);
        await(result);

        assertThat(result.kept()).hasSize(1);
        assertThat(result.discarded()).isEqualTo(0);
    }

    @Test
    void policyWithoutAiRecordsRawEntriesOnly() throws Exception {
        AutomationJournalPolicy noAi = new AutomationJournalPolicy(true, false, true, null, null, null);
        AutomationJournalRun run = run(config(), noAi);
        record(run, connection("c1", "web01"), "ok");

        await(run.finish(AutomationRunStatus.FAILED));

        assertThat(invoker.calls.get()).isEqualTo(0);
    }

    @Test
    void theDisabledRunRecordsNothing() {
        AutomationJournalRecorder recorder = AutomationJournalRun.NONE.recorderFor(connection("c1", "web01"));

        recorder.appendCommand("uptime");

        assertThat(recorder.isRecording()).isFalse();
        assertThat(AutomationJournalRun.NONE.finish(AutomationRunStatus.SUCCESS).kept()).isEmpty();
        assertThat(AutomationJournalRun.begin(
            new AutomationJournalRun.Source(SessionJournalSourceKind.JOB, "j", "J", null),
            "run", new AutomationJournalConfig())).isSameInstanceAs(AutomationJournalRun.NONE);
    }

    @Test
    void secretsAddedLaterAreRedactedFromCapture() throws Exception {
        AutomationJournalRun run = run(config(), AutomationJournalPolicy.UNRESTRICTED);
        AutomationJournalRecorder recorder = run.recorderFor(connection("c1", "web01"));
        recorder.addSecret("s3cr3t-pass");
        recorder.appendCommand("echo s3cr3t-pass");
        recorder.appendOutput("s3cr3t-pass", null);

        AutomationJournalRun.FinishResult result = run.finish(AutomationRunStatus.FAILED);
        await(result);

        List<SessionJournalLogEntry> log = service.readLogAfter(result.kept().get(0), 0);
        assertThat(log).isNotEmpty();
        assertThat(log.stream().map(SessionJournalLogEntry::text).toList().toString()).doesNotContain("s3cr3t-pass");
    }
}
