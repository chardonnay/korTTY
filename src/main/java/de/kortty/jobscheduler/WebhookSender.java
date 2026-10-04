package de.kortty.jobscheduler;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Delivers webhook payloads on its own small, bounded background executor, never on the FX thread
 * or a job's worker.
 *
 * <p>Each request is a JSON {@code POST} with a {@link #TIMEOUT} and redirects switched off: a
 * redirect is a failure, because following it would hand the payload (and, for Slack and Teams, the
 * credential in the path) to a host the user never configured. A delivery makes up to
 * {@link #MAX_ATTEMPTS} attempts with exponential backoff after an I/O error or timeout, a 5xx
 * answer or a 429, whose {@code Retry-After} is honoured up to {@link #MAX_RETRY_AFTER}; any other
 * 4xx or a 3xx is final. On shutdown the queue drains for at most {@link #SHUTDOWN_GRACE} and the
 * rest is dropped.
 *
 * <p>The log names only the receiver's host; the URL's path and query, which carry the credential
 * of a Slack or Teams webhook, never appear in it, nor does an exception message that might quote
 * the URL.
 */
public final class WebhookSender implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(WebhookSender.class);

    /** Connect and request timeout of one attempt. */
    public static final Duration TIMEOUT = Duration.ofSeconds(10);
    /** Attempts per delivery, the first one included. */
    public static final int MAX_ATTEMPTS = 3;
    /** The wait before the second attempt; it doubles for each further one. */
    public static final Duration BASE_BACKOFF = Duration.ofSeconds(1);
    /** The longest {@code Retry-After} of a 429 that is honoured. */
    public static final Duration MAX_RETRY_AFTER = Duration.ofSeconds(60);
    /** How long shutdown waits for queued deliveries before dropping them. */
    public static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(5);
    /** Deliveries waiting beyond the running ones; more are dropped. */
    static final int QUEUE_CAPACITY = 64;
    private static final int THREADS = 2;

    /** How a delivery ended. */
    public enum Outcome {
        /** The receiver answered 2xx. */
        DELIVERED,
        /** Every attempt failed, or the answer was final (3xx, 4xx other than 429). */
        FAILED,
        /** Never sent, or abandoned: the queue was full or the sender shut down. */
        DROPPED
    }

    /**
     * The result of one delivery.
     *
     * @param outcome    how it ended
     * @param attempts   the requests made
     * @param statusCode the last HTTP status, or {@code 0} when no answer arrived
     * @param problem    a short reason for a failure without URL or response body; {@code null}
     *                   when delivered
     */
    public record Result(Outcome outcome, int attempts, int statusCode, String problem) {
        public boolean delivered() {
            return outcome == Outcome.DELIVERED;
        }
    }

    /** Waits between attempts; replaced in tests. */
    @FunctionalInterface
    interface Sleeper {
        void sleep(Duration duration) throws InterruptedException;
    }

    private final HttpClient client;
    private final ThreadPoolExecutor executor;
    private final Duration timeout;
    private final Duration baseBackoff;
    private final Sleeper sleeper;
    private final Clock clock;

    /** A sender with the production timeout, backoff and executor. */
    public WebhookSender() {
        this(TIMEOUT, BASE_BACKOFF, duration -> Thread.sleep(duration), Clock.systemUTC());
    }

    WebhookSender(Duration timeout, Duration baseBackoff, Sleeper sleeper, Clock clock) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.baseBackoff = Objects.requireNonNull(baseBackoff, "baseBackoff");
        this.sleeper = Objects.requireNonNull(sleeper, "sleeper");
        this.clock = Objects.requireNonNull(clock, "clock");
        AtomicInteger threadIndex = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(THREADS, THREADS, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(QUEUE_CAPACITY), runnable -> {
                Thread thread = new Thread(runnable, "kortty-webhook-" + threadIndex.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }, new ThreadPoolExecutor.AbortPolicy());
        this.executor.allowCoreThreadTimeOut(true);
        this.client = HttpClient.newBuilder()
            .connectTimeout(timeout)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
    }

    /**
     * Queues a delivery of {@code jsonBody} to {@code uri}. The future completes with
     * {@link Outcome#DROPPED} at once when the queue is full or the sender is shut down; it never
     * completes exceptionally.
     */
    public CompletableFuture<Result> send(URI uri, String jsonBody) {
        Objects.requireNonNull(uri, "uri");
        Objects.requireNonNull(jsonBody, "jsonBody");
        CompletableFuture<Result> result = new CompletableFuture<>();
        if (!execute(() -> result.complete(deliver(uri, jsonBody)))) {
            logger.warn("Webhook delivery to {} dropped: the send queue is full or closed", host(uri));
            result.complete(new Result(Outcome.DROPPED, 0, 0, "queue full or closed"));
        }
        return result;
    }

    /**
     * Runs {@code task} on the sender's executor, for work that has to happen before a send
     * (decrypting the URL, checking the policy) and must not block the caller.
     *
     * @return false when the queue is full or the sender is shut down; the task then never runs
     */
    public boolean execute(Runnable task) {
        Objects.requireNonNull(task, "task");
        try {
            executor.execute(() -> {
                try {
                    task.run();
                } catch (RuntimeException e) {
                    logger.warn("Webhook task failed: {}", e.getClass().getSimpleName());
                }
            });
            return true;
        } catch (RejectedExecutionException e) {
            return false;
        }
    }

    /**
     * Delivers synchronously with the retry rules. Call it only from a task on this sender's
     * executor ({@link #execute}); it blocks for up to several timeouts and backoffs.
     */
    public Result deliver(URI uri, String jsonBody) {
        String host = host(uri);
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(uri)
                .timeout(timeout)
                .header("Content-Type", "application/json; charset=utf-8")
                .header("User-Agent", "korTTY")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();
        } catch (IllegalArgumentException e) {
            logger.warn("Webhook delivery to {} failed: the URL cannot be requested", host);
            return new Result(Outcome.FAILED, 0, 0, "invalid URL");
        }
        int status = 0;
        String problem = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            Duration wait;
            try {
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                status = response.statusCode();
                if (status >= 200 && status < 300) {
                    logger.info("Webhook delivered to {} (HTTP {}, attempt {})", host, status, attempt);
                    return new Result(Outcome.DELIVERED, attempt, status, null);
                }
                problem = "HTTP " + status;
                if (status == 429) {
                    wait = retryAfter(response).orElse(backoff(attempt));
                } else if (status >= 500) {
                    wait = backoff(attempt);
                } else {
                    logger.warn("Webhook delivery to {} failed: HTTP {} (not retried)", host, status);
                    return new Result(Outcome.FAILED, attempt, status, problem);
                }
            } catch (HttpTimeoutException e) {
                status = 0;
                problem = "timeout";
                wait = backoff(attempt);
            } catch (IOException e) {
                status = 0;
                problem = e.getClass().getSimpleName();
                wait = backoff(attempt);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Webhook delivery to {} abandoned at shutdown", host);
                return new Result(Outcome.DROPPED, attempt, status, "interrupted");
            }
            if (attempt == MAX_ATTEMPTS) {
                break;
            }
            logger.info("Webhook delivery to {} failed ({}), retrying in {} ms", host, problem, wait.toMillis());
            try {
                sleeper.sleep(wait);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Webhook delivery to {} abandoned at shutdown", host);
                return new Result(Outcome.DROPPED, attempt, status, "interrupted");
            }
        }
        logger.warn("Webhook delivery to {} failed after {} attempts: {}", host, MAX_ATTEMPTS, problem);
        return new Result(Outcome.FAILED, MAX_ATTEMPTS, status, problem);
    }

    /** {@link #BASE_BACKOFF} doubled for each attempt already made. */
    private Duration backoff(int attemptsMade) {
        return baseBackoff.multipliedBy(1L << Math.max(0, attemptsMade - 1));
    }

    /**
     * The {@code Retry-After} of a 429 in seconds or as an HTTP date, capped at
     * {@link #MAX_RETRY_AFTER}; empty when missing or unreadable.
     */
    private java.util.Optional<Duration> retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").map(String::strip).flatMap(value -> {
            Duration wait;
            try {
                wait = Duration.ofSeconds(Math.max(0, Long.parseLong(value)));
            } catch (NumberFormatException notSeconds) {
                try {
                    ZonedDateTime at = ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME);
                    wait = Duration.between(clock.instant(), at.toInstant());
                    if (wait.isNegative()) {
                        wait = Duration.ZERO;
                    }
                } catch (RuntimeException notDate) {
                    return java.util.Optional.empty();
                }
            }
            return java.util.Optional.of(wait.compareTo(MAX_RETRY_AFTER) > 0 ? MAX_RETRY_AFTER : wait);
        });
    }

    /** The lower-cased host of {@code uri} for the log; never its path or query. */
    static String host(URI uri) {
        String host = uri != null ? uri.getHost() : null;
        return host != null ? host.toLowerCase(Locale.ROOT) : "<no host>";
    }

    /**
     * Stops accepting deliveries, lets the queued ones run for at most {@link #SHUTDOWN_GRACE} and
     * then interrupts and drops the rest.
     */
    public void shutdown() {
        shutdown(SHUTDOWN_GRACE);
    }

    void shutdown(Duration grace) {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(grace.toMillis(), TimeUnit.MILLISECONDS)) {
                int dropped = executor.shutdownNow().size();
                logger.info("Webhook sender stopped; {} queued deliveries dropped", dropped);
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        client.shutdownNow();
    }

    public boolean isShutdown() {
        return executor.isShutdown();
    }

    @Override
    public void close() {
        shutdown();
    }
}
