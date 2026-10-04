package de.kortty.jobscheduler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.LoggerFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

/** Loopback {@link HttpServer} stubs only; no request ever leaves the machine. */
class WebhookSenderTest {

    private static final String SECRET_PATH = "/services/T0SECRET/B0SECRET/xoxSecretPath42";

    /** One canned answer: status plus optional headers. */
    private record Answer(int status, String headerName, String headerValue, long delayMillis) {
        static Answer of(int status) {
            return new Answer(status, null, null, 0);
        }
    }

    private HttpServer server;
    private ExecutorService serverExecutor;
    private final ConcurrentLinkedQueue<Answer> answers = new ConcurrentLinkedQueue<>();
    private final List<String> bodies = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger hits = new AtomicInteger();
    private final AtomicInteger redirectTargetHits = new AtomicInteger();
    private final List<Duration> sleeps = Collections.synchronizedList(new ArrayList<>());
    private WebhookSender sender;

    @BeforeMethod
    void start() throws IOException {
        answers.clear();
        bodies.clear();
        sleeps.clear();
        hits.set(0);
        redirectTargetHits.set(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverExecutor = Executors.newCachedThreadPool();
        server.setExecutor(serverExecutor);
        server.createContext(SECRET_PATH, this::handle);
        server.createContext("/elsewhere", exchange -> {
            redirectTargetHits.incrementAndGet();
            respond(exchange, 200);
        });
        server.start();
        sender = sender(Duration.ofSeconds(5));
    }

    @AfterMethod(alwaysRun = true)
    void stop() {
        sender.shutdown(Duration.ofMillis(200));
        server.stop(0);
        serverExecutor.shutdownNow();
    }

    private WebhookSender sender(Duration timeout) {
        return new WebhookSender(timeout, Duration.ofSeconds(1), sleeps::add, Clock.systemUTC());
    }

    private void handle(HttpExchange exchange) throws IOException {
        hits.incrementAndGet();
        bodies.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        Answer answer = answers.poll();
        if (answer == null) {
            answer = Answer.of(200);
        }
        if (answer.delayMillis() > 0) {
            try {
                Thread.sleep(answer.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        if (answer.headerName() != null) {
            exchange.getResponseHeaders().add(answer.headerName(), answer.headerValue());
        }
        respond(exchange, answer.status());
    }

    private static void respond(HttpExchange exchange, int status) throws IOException {
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
    }

    private URI uri() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort() + SECRET_PATH);
    }

    @Test
    void retriesA503AndThenDelivers() {
        answers.add(Answer.of(503));
        answers.add(Answer.of(200));

        WebhookSender.Result result = sender.deliver(uri(), "{\"a\":1}");

        assertThat(result.outcome()).isEqualTo(WebhookSender.Outcome.DELIVERED);
        assertThat(result.attempts()).isEqualTo(2);
        assertThat(result.statusCode()).isEqualTo(200);
        assertThat(hits.get()).isEqualTo(2);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
        assertThat(bodies).containsExactly("{\"a\":1}", "{\"a\":1}");
    }

    @Test
    void persistent5xxGivesUpAfterThreeAttemptsWithExponentialBackoff() {
        answers.add(Answer.of(500));
        answers.add(Answer.of(502));
        answers.add(Answer.of(503));
        answers.add(Answer.of(200));

        WebhookSender.Result result = sender.deliver(uri(), "{}");

        assertThat(result.outcome()).isEqualTo(WebhookSender.Outcome.FAILED);
        assertThat(result.attempts()).isEqualTo(WebhookSender.MAX_ATTEMPTS);
        assertThat(result.statusCode()).isEqualTo(503);
        assertThat(hits.get()).isEqualTo(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1), Duration.ofSeconds(2)).inOrder();
    }

    @Test
    void a404IsNotRetried() {
        answers.add(Answer.of(404));

        WebhookSender.Result result = sender.deliver(uri(), "{}");

        assertThat(result.outcome()).isEqualTo(WebhookSender.Outcome.FAILED);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(result.statusCode()).isEqualTo(404);
        assertThat(hits.get()).isEqualTo(1);
        assertThat(sleeps).isEmpty();
    }

    @Test
    void retryAfterIsHonouredAndCapped() {
        answers.add(new Answer(429, "Retry-After", "7", 0));
        answers.add(new Answer(429, "Retry-After", "3600", 0));
        answers.add(Answer.of(204));

        WebhookSender.Result result = sender.deliver(uri(), "{}");

        assertThat(result.outcome()).isEqualTo(WebhookSender.Outcome.DELIVERED);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(sleeps).containsExactly(Duration.ofSeconds(7), WebhookSender.MAX_RETRY_AFTER).inOrder();
    }

    @Test
    void a429WithoutRetryAfterUsesTheBackoff() {
        answers.add(Answer.of(429));
        answers.add(Answer.of(200));

        assertThat(sender.deliver(uri(), "{}").delivered()).isTrue();
        assertThat(sleeps).containsExactly(Duration.ofSeconds(1));
    }

    @Test
    void aTimeoutCountsAsAFailedAttempt() {
        WebhookSender slow = sender(Duration.ofMillis(300));
        try {
            for (int i = 0; i < 3; i++) {
                answers.add(new Answer(200, null, null, 1_500));
            }

            WebhookSender.Result result = slow.deliver(uri(), "{}");

            assertThat(result.outcome()).isEqualTo(WebhookSender.Outcome.FAILED);
            assertThat(result.attempts()).isEqualTo(3);
            assertThat(result.statusCode()).isEqualTo(0);
            assertThat(result.problem()).isEqualTo("timeout");
        } finally {
            slow.shutdown(Duration.ofMillis(200));
        }
    }

    @Test
    void aRedirectIsNotFollowed() {
        answers.add(new Answer(302, "Location", "/elsewhere", 0));

        WebhookSender.Result result = sender.deliver(uri(), "{}");

        assertThat(result.outcome()).isEqualTo(WebhookSender.Outcome.FAILED);
        assertThat(result.statusCode()).isEqualTo(302);
        assertThat(result.attempts()).isEqualTo(1);
        assertThat(redirectTargetHits.get()).isEqualTo(0);
    }

    @Test
    void aConnectionErrorIsRetriedAndFails() throws IOException {
        int closedPort;
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            closedPort = socket.getLocalPort();
        }

        WebhookSender.Result result = sender.deliver(URI.create("http://127.0.0.1:" + closedPort + SECRET_PATH), "{}");

        assertThat(result.outcome()).isEqualTo(WebhookSender.Outcome.FAILED);
        assertThat(result.attempts()).isEqualTo(3);
        assertThat(sleeps).hasSize(2);
    }

    @Test
    void theLogNamesTheHostButNeverThePath() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(WebhookSender.class);
        ListAppender<ILoggingEvent> events = new ListAppender<>();
        events.start();
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        logger.addAppender(events);
        try {
            answers.add(Answer.of(500));
            answers.add(Answer.of(200));
            sender.deliver(uri(), "{}");
            answers.add(Answer.of(404));
            sender.deliver(uri(), "{}");
            sender.deliver(URI.create("http://127.0.0.1:1" + SECRET_PATH + "?token=querySecret"), "{}");
        } finally {
            logger.detachAppender(events);
            logger.setLevel(previous);
        }

        assertThat(events.list).isNotEmpty();
        boolean namedHost = false;
        for (ILoggingEvent event : events.list) {
            String message = event.getFormattedMessage();
            assertThat(message).doesNotContain("T0SECRET");
            assertThat(message).doesNotContain("xoxSecretPath42");
            assertThat(message).doesNotContain("querySecret");
            assertThat(message).doesNotContain("/services");
            assertThat(event.getThrowableProxy()).isNull();
            namedHost |= message.contains("127.0.0.1");
        }
        assertThat(namedHost).isTrue();
    }

    @Test
    void sendRunsOnTheSenderExecutorAndShutdownDropsLaterSends() throws Exception {
        WebhookSender.Result result = sender.send(uri(), "{}").get(10, TimeUnit.SECONDS);
        assertThat(result.delivered()).isTrue();

        CountDownLatch ran = new CountDownLatch(1);
        String[] thread = new String[1];
        assertThat(sender.execute(() -> {
            thread[0] = Thread.currentThread().getName();
            ran.countDown();
        })).isTrue();
        assertThat(ran.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(thread[0]).startsWith("kortty-webhook-");

        sender.shutdown(Duration.ofSeconds(1));
        assertThat(sender.isShutdown()).isTrue();
        WebhookSender.Result dropped = sender.send(uri(), "{}").get(1, TimeUnit.SECONDS);
        assertThat(dropped.outcome()).isEqualTo(WebhookSender.Outcome.DROPPED);
        assertThat(sender.execute(() -> { })).isFalse();
    }

    @Test
    void shutdownInterruptsABackoffAndDropsTheDelivery() throws Exception {
        WebhookSender sleeping = new WebhookSender(Duration.ofSeconds(5), Duration.ofSeconds(30),
            duration -> Thread.sleep(duration), Clock.systemUTC());
        answers.add(Answer.of(503));
        java.util.concurrent.CompletableFuture<WebhookSender.Result> pending = sleeping.send(uri(), "{}");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (hits.get() == 0 && System.nanoTime() < deadline) {
            Thread.onSpinWait();
        }

        long start = System.nanoTime();
        sleeping.shutdown(Duration.ofMillis(200));
        WebhookSender.Result result = pending.get(5, TimeUnit.SECONDS);

        assertThat(result.outcome()).isEqualTo(WebhookSender.Outcome.DROPPED);
        assertThat(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start)).isLessThan(5);
    }
}
