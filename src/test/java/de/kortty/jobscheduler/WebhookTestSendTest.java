package de.kortty.jobscheduler;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.PolicyFile;
import de.kortty.policy.PolicyIdentity;
import de.kortty.policy.PolicyRule;
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
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

/** The "Send test" button's pipeline against a loopback stub; no real webhook is ever called. */
class WebhookTestSendTest {

    private static final EncryptionService ENC = new EncryptionService();
    private static final char[] MASTER = "test-send-master".toCharArray();
    private static final String SECRET_PATH = "/hooks/T0SECRET/xoxTestPath42";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-04T09:00:00Z"), ZoneOffset.UTC);

    private HttpServer server;
    private final LinkedBlockingQueue<String> received = new LinkedBlockingQueue<>();
    private final AtomicInteger status = new AtomicInteger(200);
    private WebhookSender sender;

    @BeforeMethod
    void start() throws IOException {
        received.clear();
        status.set(200);
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

    private static WebhookTarget target(boolean includeSummary) {
        WebhookTarget target = new WebhookTarget();
        target.setName("Ops");
        target.setFormat(WebhookFormat.GENERIC_JSON);
        target.setIncludeSummary(includeSummary);
        return target;
    }

    private WebhookTestSend.Outcome sendNow(WebhookTarget target, String typedUrl, char[] master, EffectivePolicy policy) {
        return WebhookTestSend.sendNow(target, typedUrl, new WebhookTargetSecrets(ENC), master, policy,
            new WebhookPayloadFormatter(JobNotificationDispatcherTest.I18N), sender, CLOCK, "Nightly backup",
            "This is a test.");
    }

    @Test
    void aTypedUrlIsSentTheSampleRunInTheBackground() throws Exception {
        WebhookTestSend.Outcome outcome = WebhookTestSend.send(target(false), url(), new WebhookTargetSecrets(ENC), null,
                EffectivePolicy.unrestricted(), new WebhookPayloadFormatter(JobNotificationDispatcherTest.I18N), sender,
                CLOCK, "Nightly backup", "This is a test.")
            .get(10, TimeUnit.SECONDS);

        assertThat(outcome.kind()).isEqualTo(WebhookTestSend.Kind.DELIVERED);
        assertThat(outcome.host()).isEqualTo("127.0.0.1");
        assertThat(outcome.attempts()).isEqualTo(1);
        assertThat(outcome.messageKey()).isEqualTo("jobscheduler.dialog.webhook.test.ok");
        var json = JsonParser.parseString(received.poll(5, TimeUnit.SECONDS)).getAsJsonObject();
        assertThat(json.get("status").getAsString()).isEqualTo("success");
        assertThat(json.get("trigger").getAsString()).isEqualTo("manual");
        assertThat(json.getAsJsonObject("job").get("name").getAsString()).isEqualTo("Nightly backup");
        assertThat(json.has("summary")).isFalse();
    }

    @Test
    void theStoredUrlIsDecryptedWithTheMasterPasswordAndTheSummaryIsOptIn() throws Exception {
        WebhookTarget target = target(true);
        new WebhookTargetSecrets(ENC).storeUrl(target, url(), MASTER);

        WebhookTestSend.Outcome outcome = sendNow(target, "", MASTER, EffectivePolicy.unrestricted());

        assertThat(outcome.delivered()).isTrue();
        assertThat(received.poll(5, TimeUnit.SECONDS)).contains("This is a test.");
    }

    @Test
    void aLockedMasterPasswordSendsNothing() throws Exception {
        WebhookTarget target = target(false);
        new WebhookTargetSecrets(ENC).storeUrl(target, url(), MASTER);

        WebhookTestSend.Outcome outcome = sendNow(target, null, null, EffectivePolicy.unrestricted());

        assertThat(outcome.kind()).isEqualTo(WebhookTestSend.Kind.LOCKED);
        assertThat(outcome.messageKey()).isEqualTo("jobscheduler.dialog.webhook.test.locked");
        assertThat(sendNow(target(false), "", MASTER, EffectivePolicy.unrestricted()).kind())
            .isEqualTo(WebhookTestSend.Kind.NO_URL);
        assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void anInvalidTypedUrlReportsTheValidatorsProblem() throws Exception {
        WebhookTestSend.Outcome outcome = sendNow(target(false), "http://hooks.example.org/x", null,
            EffectivePolicy.unrestricted());

        assertThat(outcome.kind()).isEqualTo(WebhookTestSend.Kind.INVALID_URL);
        assertThat(outcome.messageKey()).isEqualTo(WebhookUrlValidator.Problem.INSECURE_HTTP.i18nKey());
    }

    @Test
    void thePolicyIsCheckedBeforeAnythingIsSent() throws Exception {
        assertThat(sendNow(target(false), url(), null, EffectivePolicy.lockdown()).kind())
            .isEqualTo(WebhookTestSend.Kind.FEATURE_DENIED);

        EffectivePolicy allowlist = policy(PolicyRule.builder().webhookHostAllowlist(List.of("hooks.slack.com")).build());
        WebhookTestSend.Outcome blocked = sendNow(target(false), url(), null, allowlist);

        assertThat(blocked.kind()).isEqualTo(WebhookTestSend.Kind.HOST_NOT_ALLOWED);
        assertThat(blocked.host()).isEqualTo("127.0.0.1");
        assertThat(received.poll(300, TimeUnit.MILLISECONDS)).isNull();
    }

    @Test
    void aRejectedDeliveryNamesOnlyTheHostAndTheStatus() throws Exception {
        status.set(404);

        WebhookTestSend.Outcome outcome = sendNow(target(false), url(), null, EffectivePolicy.unrestricted());

        assertThat(outcome.kind()).isEqualTo(WebhookTestSend.Kind.FAILED);
        assertThat(outcome.problem()).isEqualTo("HTTP 404");
        assertThat(Arrays.toString(outcome.messageArgs())).doesNotContain("T0SECRET");
        assertThat(outcome.messageArgs()).asList().containsExactly("127.0.0.1", "1", "HTTP 404").inOrder();
    }

    @Test
    void aClosedSenderDropsTheTestAndLeavesTheCallersMasterPasswordAlone() throws Exception {
        sender.shutdown(Duration.ofMillis(200));
        char[] master = MASTER.clone();

        WebhookTestSend.Outcome outcome = WebhookTestSend.send(target(false), url(), new WebhookTargetSecrets(ENC),
            master, EffectivePolicy.unrestricted(), new WebhookPayloadFormatter(JobNotificationDispatcherTest.I18N),
            sender, CLOCK, "job", null).get(5, TimeUnit.SECONDS);

        assertThat(outcome.kind()).isEqualTo(WebhookTestSend.Kind.DROPPED);
        assertThat(new String(master)).isEqualTo(new String(MASTER));
    }

    private static EffectivePolicy policy(PolicyRule rule) {
        return EffectivePolicy.resolve(new PolicyFile(1, "ACME", Map.of(), List.of(rule),
            List.of(), List.of(), List.of(), List.of()), new PolicyIdentity() {
                @Override
                public String userName() {
                    return "u";
                }

                @Override
                public Set<String> osGroups() {
                    return Set.of();
                }
            });
    }
}
