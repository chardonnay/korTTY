package de.kortty.jobscheduler;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import de.kortty.policy.EffectivePolicy;
import de.kortty.security.EncryptionService;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

/** Loopback {@link HttpServer} stub as the receiver; no real webhook is ever called. */
class JobWebhookNotifierTest {

    private static final EncryptionService ENC = new EncryptionService();
    private static final char[] MASTER = "notifier-master-pass".toCharArray();
    private static final String SECRET_PATH = "/hooks/T0SECRET/xoxReceiverPath42";
    private static final String TOKEN = "ghp_abcdefghijklmnopqrstuvwxyz0123456789";

    private HttpServer server;
    private final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private final Map<String, WebhookTarget> targets = new HashMap<>();
    private final List<JobJournalEntry> journal = Collections.synchronizedList(new ArrayList<>());
    private CountDownLatch journaled;
    private char[] master = MASTER;
    private WebhookSender sender;

    @BeforeMethod
    void start() throws IOException {
        received.clear();
        status.set(200);
        targets.clear();
        journal.clear();
        journaled = new CountDownLatch(1);
        master = MASTER;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext(SECRET_PATH, exchange -> {
            received.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        sender = new WebhookSender(Duration.ofSeconds(5), Duration.ofMillis(1), duration -> { }, Clock.systemUTC());
    }

    @AfterMethod(alwaysRun = true)
    void stop() {
        sender.shutdown(Duration.ofMillis(200));
        server.stop(0);
    }

    private String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + SECRET_PATH;
    }

    private WebhookTarget target(String id, WebhookFormat format, boolean includeSummary) throws Exception {
        WebhookTarget target = new WebhookTarget();
        target.setId(id);
        target.setName("Ops " + id);
        target.setFormat(format);
        target.setIncludeSummary(includeSummary);
        new WebhookTargetSecrets(ENC).storeUrl(target, url(), MASTER);
        targets.put(id, target);
        return target;
    }

    private JobWebhookNotifier notifier() {
        return new JobWebhookNotifier(id -> Optional.ofNullable(targets.get(id)), new WebhookTargetSecrets(ENC),
            () -> master, new WebhookPayloadFormatter(JobNotificationDispatcherTest.I18N), sender, entry -> {
                journal.add(entry);
                journaled.countDown();
            });
    }

    private static JobRunEvent failedRun() {
        return new JobRunEvent("job-1", "Nightly backup", JobRunStatus.FAILED, null, 2, "scheduled",
            "failed with token=" + TOKEN, Instant.parse("2026-10-04T08:00:00Z"));
    }

    @Test
    void deliversTheTargetsFormatWithoutTheSummaryByDefault() throws Exception {
        target("t1", WebhookFormat.GENERIC_JSON, false);

        Optional<WebhookSender.Result> result = notifier().deliverTo(failedRun(), "t1");

        assertThat(result.orElseThrow().delivered()).isTrue();
        String body = received.poll(5, TimeUnit.SECONDS);
        assertThat(JsonParser.parseString(body).getAsJsonObject().get("schema").getAsString())
            .isEqualTo(WebhookPayloadFormatter.GENERIC_SCHEMA);
        assertThat(body).doesNotContain("summary");
        assertThat(body).doesNotContain(TOKEN);
        assertThat(journal).isEmpty();
    }

    @Test
    void optedInSummaryArrivesMasked() throws Exception {
        target("t1", WebhookFormat.SLACK, true);

        notifier().deliverTo(failedRun(), "t1");

        String body = received.poll(5, TimeUnit.SECONDS);
        assertThat(body).contains("failed with token=ghp_***");
        assertThat(body).doesNotContain(TOKEN);
    }

    @Test
    void lockedMasterPasswordSkipsAndJournals() throws Exception {
        target("t1", WebhookFormat.TEAMS, false);
        master = null;

        assertThat(notifier().deliverTo(failedRun(), "t1")).isEmpty();

        assertThat(received).isEmpty();
        assertThat(journal).hasSize(1);
        JobJournalEntry entry = journal.get(0);
        assertThat(entry.getSummary()).isEqualTo(WebhookTargetSecrets.SKIPPED_LOCKED);
        assertThat(entry.getStatus()).isEqualTo(JobRunStatus.BLOCKED);
        assertThat(entry.getJobId()).isEqualTo("__system__");
        assertThat(entry.getDetailText()).contains("Nightly backup");
        assertThat(entry.getDetailText()).contains("Ops t1");
    }

    @Test
    void disabledOrDeletedTargetsAreLeftOutSilently() throws Exception {
        target("t1", WebhookFormat.GENERIC_JSON, false).setEnabled(false);

        assertThat(notifier().deliverTo(failedRun(), "t1")).isEmpty();
        assertThat(notifier().deliverTo(failedRun(), "gone")).isEmpty();

        assertThat(received).isEmpty();
        assertThat(journal).isEmpty();
    }

    @Test
    void finalFailureIsJournaledWithTheHostOnly() throws Exception {
        target("t1", WebhookFormat.GENERIC_JSON, false);
        status.set(404);

        WebhookSender.Result result = notifier().deliverTo(failedRun(), "t1").orElseThrow();

        assertThat(result.outcome()).isEqualTo(WebhookSender.Outcome.FAILED);
        assertThat(journal).hasSize(1);
        JobJournalEntry entry = journal.get(0);
        assertThat(entry.getSummary()).isEqualTo(JobWebhookNotifier.FAILED_DELIVERY);
        assertThat(entry.getStatus()).isEqualTo(JobRunStatus.FAILED);
        assertThat(entry.getDetailText()).contains("Host: 127.0.0.1");
        assertThat(entry.getDetailText()).contains("HTTP 404");
        assertThat(entry.getDetailText()).doesNotContain("T0SECRET");
        assertThat(entry.getDetailText()).doesNotContain("xoxReceiverPath42");
    }

    @Test
    void dispatcherHandsTheRunToTheJobsTargetsOffTheCallingThread() throws Exception {
        target("t1", WebhookFormat.GENERIC_JSON, false);
        JobNotificationConfig config = JobNotificationConfig.defaults();
        config.setDesktop(false);
        config.setWebhookTargetIds(List.of("t1"));
        JobNotificationDispatcher dispatcher = new JobNotificationDispatcher(() -> null, EffectivePolicy::unrestricted,
            Clock.systemUTC(), id -> config, notifier(), JobNotificationDispatcherTest.I18N);

        dispatcher.onJobRunFinished(failedRun());

        assertThat(received.poll(10, TimeUnit.SECONDS)).contains("\"status\":\"failed\"");
    }

    @Test
    void dispatcherSendsNothingForANonMatchingRunOrWithoutTargets() throws Exception {
        target("t1", WebhookFormat.GENERIC_JSON, false);
        JobNotificationConfig withTarget = JobNotificationConfig.defaults();
        withTarget.setWebhookTargetIds(List.of("t1"));
        JobNotificationConfig without = JobNotificationConfig.defaults();
        Map<String, JobNotificationConfig> configs = Map.of("job-1", withTarget, "job-2", without);
        JobNotificationDispatcher dispatcher = new JobNotificationDispatcher(() -> null, EffectivePolicy::unrestricted,
            Clock.systemUTC(), configs::get, notifier(), JobNotificationDispatcherTest.I18N);

        // SUCCESS is opt-in, so job-1's success is not sent; job-2 has no webhook target.
        dispatcher.onJobRunFinished(new JobRunEvent("job-1", "A", JobRunStatus.SUCCESS, null, 0, "manual", null, null));
        dispatcher.onJobRunFinished(new JobRunEvent("job-2", "B", JobRunStatus.FAILED, null, 1, "manual", null, null));

        assertThat(received.poll(500, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void aClosedSenderJournalsTheDroppedNotification() throws Exception {
        target("t1", WebhookFormat.GENERIC_JSON, false);
        sender.shutdown(Duration.ofMillis(200));

        notifier().notify(failedRun(), List.of("t1"));

        assertThat(journaled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(journal.get(0).getSummary()).isEqualTo(JobWebhookNotifier.DROPPED_DELIVERY);
        assertThat(received).isEmpty();
    }
}
