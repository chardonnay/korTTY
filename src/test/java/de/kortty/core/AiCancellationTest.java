package de.kortty.core;

import static com.google.common.truth.Truth.assertThat;

import com.sun.net.httpserver.HttpServer;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiReasoningEffort;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.testng.SkipException;
import org.testng.annotations.Test;

/**
 * Stop must take effect within about a second on every backend, a stopped run must send no further
 * request, and whatever a provider still returns after the stop must never reach the caller.
 */
public class AiCancellationTest {

    private static final long STOP_BUDGET_MILLIS = 1_500L;

    @Test
    public void cancelRunsHooksInterruptsTheBoundThreadAndStaysCancelled() throws Exception {
        AiCancellation.Handle handle = AiCancellation.newHandle();
        AtomicInteger hookRuns = new AtomicInteger();
        CountDownLatch bound = new CountDownLatch(1);
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> AiCancellation.runBound(handle, () -> {
            AiCancellation.onCancel(hookRuns::incrementAndGet);
            bound.countDown();
            try {
                Thread.sleep(30_000L);
            } catch (InterruptedException e) {
                outcome.set(e);
                // The flag is gone now, but the handle still says "stopped".
                try {
                    AiCancellation.throwIfCancelled();
                } catch (AiCancelledException stopped) {
                    outcome.set(stopped);
                }
            }
        }));
        worker.start();
        assertThat(bound.await(5, TimeUnit.SECONDS)).isTrue();

        handle.cancel();
        handle.cancel();
        worker.join(STOP_BUDGET_MILLIS);

        assertThat(worker.isAlive()).isFalse();
        assertThat(hookRuns.get()).isEqualTo(1);
        assertThat(outcome.get()).isInstanceOf(AiCancelledException.class);
        assertThat(handle.isCancelled()).isTrue();
        AtomicInteger late = new AtomicInteger();
        handle.onCancel(late::incrementAndGet);
        assertThat(late.get()).isEqualTo(1);
    }

    @Test
    public void withoutABoundHandleNothingChanges() {
        assertThat(AiCancellation.current()).isNull();
        assertThat(AiCancellation.isCancelled()).isFalse();
        AiCancellation.throwIfCancelled();
        AiCancellation.onCancel(() -> {
            throw new AssertionError("no handle, no hook");
        }).close();
    }

    @Test
    public void classifiesStopsButNotTimeouts() {
        assertThat(AiCancellation.isCancellation(new InterruptedException())).isTrue();
        assertThat(AiCancellation.isCancellation(new IOException(new InterruptedIOException()))).isTrue();
        assertThat(AiCancellation.isCancellation(new AiCancelledException("x", null))).isTrue();
        assertThat(AiCancellation.isCancellation(new SocketTimeoutException())).isFalse();
        assertThat(AiCancellation.isCancellation(new IllegalStateException("boom"))).isFalse();
        assertThat(AiCancellation.isCancellation(null)).isFalse();
    }

    @Test
    public void stoppingAStreamingHttpRequestEndsItWithinASecond() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch streaming = new CountDownLatch(1);
        server.createContext("/v1/chat/completions", exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                // A slow model: one token every 200 ms for a minute.
                for (int i = 0; i < 300; i++) {
                    out.write(("data: {\"choices\":[{\"delta\":{\"content\":\"tok" + i + " \"}}]}\n\n")
                        .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    streaming.countDown();
                    Thread.sleep(200L);
                }
            } catch (IOException | InterruptedException ignored) {
                // The client went away — exactly what a stop should cause.
            }
        });
        server.start();
        try {
            OpenAiCompatibleAiService provider = new OpenAiCompatibleAiService(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "test-model", "");
            AiService service = LoggingAiService.wrap(provider, profile(AiConnectionMode.HTTP_API), "test-model",
                AiReasoningEffort.DISABLED);
            StopOutcome outcome = runAndStop(
                () -> ((AiPromptService) service).executePrompt("system", "user"), streaming);

            assertThat(outcome.stopMillis()).isLessThan(STOP_BUDGET_MILLIS);
            assertThat(outcome.result()).isNull();
            assertThat(outcome.failure()).isInstanceOf(AiCancelledException.class);
        } finally {
            server.stop(0);
        }
    }

    @Test
    public void stoppingARequestThatNeverAnswersEndsItWithinASecond() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        CountDownLatch received = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        server.createContext("/v1/chat/completions", exchange -> {
            received.countDown();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
        try {
            OpenAiCompatibleAiService provider = new OpenAiCompatibleAiService(
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
                "test-model", "");
            AiService service = LoggingAiService.wrap(provider, profile(AiConnectionMode.HTTP_API), "test-model",
                AiReasoningEffort.DISABLED);
            StopOutcome outcome = runAndStop(
                () -> ((AiPromptService) service).executePrompt("system", "user"), received);

            assertThat(outcome.stopMillis()).isLessThan(STOP_BUDGET_MILLIS);
            assertThat(outcome.failure()).isInstanceOf(AiCancelledException.class);
        } finally {
            release.countDown();
            server.stop(0);
        }
    }

    @Test
    public void aLateResultIsDiscardedAndNoFurtherRequestIsSent() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        // A provider that ignores interrupts entirely and answers anyway.
        AiPromptService stubborn = new FakePromptService(() -> {
            calls.incrementAndGet();
            started.countDown();
            long until = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(600);
            while (System.nanoTime() < until) {
                Thread.onSpinWait();
            }
            return new AiExecutionResult("LATE ANSWER", null);
        });
        AiService service = LoggingAiService.wrap(stubborn, profile(AiConnectionMode.HTTP_API), "m",
            AiReasoningEffort.DISABLED);
        AiCancellation.Handle handle = AiCancellation.newHandle();
        AtomicReference<Object> first = new AtomicReference<>();
        AtomicReference<Object> second = new AtomicReference<>();
        Thread worker = new Thread(() -> AiCancellation.runBound(handle, () -> {
            first.set(call(() -> ((AiPromptService) service).executePrompt("s", "u")));
            // A workflow's retry/repair after the stop.
            second.set(call(() -> ((AiPromptService) service).executePrompt("s", "u")));
        }));
        worker.start();
        assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        handle.cancel();
        worker.join(5_000L);

        assertThat(first.get()).isInstanceOf(AiCancelledException.class);
        assertThat(second.get()).isInstanceOf(AiCancelledException.class);
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    public void stoppingACliRequestKillsTheProcessTreeWithinASecond() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new SkipException("Shell-script CLI stub requires a POSIX shell");
        }
        Path pidFile = Files.createTempFile("kortty-cli-child", ".pid");
        pidFile.toFile().deleteOnExit();
        // The CLI spawns a worker (as Node-based CLIs do) and then waits for it.
        Path script = Files.createTempFile("kortty-cli-slow", ".sh");
        Files.writeString(script, "#!/bin/sh\nsleep 60 &\necho $! > '" + pidFile + "'\nwait\n");
        script.toFile().setExecutable(true);
        script.toFile().deleteOnExit();
        LocalCliAiService provider = new LocalCliAiService(
            "test", script.toString(), "{promptFile}", "model", AiReasoningEffort.DISABLED,
            AiSkillPromptSupport.disabled(), null);
        AiService service = LoggingAiService.wrap(provider, profile(AiConnectionMode.LOCAL_CLI), "model",
            AiReasoningEffort.DISABLED);
        CountDownLatch childStarted = new CountDownLatch(1);
        Thread watcher = new Thread(() -> {
            long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (System.nanoTime() < until) {
                try {
                    if (Files.size(pidFile) > 0) {
                        childStarted.countDown();
                        return;
                    }
                    Thread.sleep(20L);
                } catch (IOException | InterruptedException e) {
                    return;
                }
            }
        });
        watcher.start();

        StopOutcome outcome = runAndStop(
            () -> ((AiPromptService) service).executePrompt("system", "user"), childStarted);

        assertThat(outcome.stopMillis()).isLessThan(STOP_BUDGET_MILLIS);
        assertThat(outcome.failure()).isInstanceOf(AiCancelledException.class);
        long childPid = Long.parseLong(Files.readString(pidFile).strip());
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)
                && System.nanoTime() < deadline) {
            Thread.sleep(20L);
        }
        assertThat(ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false)).isFalse();
    }

    // ---------------------------------------------------------------------------------------

    private record StopOutcome(long stopMillis, Object result, Throwable failure) {
    }

    @FunctionalInterface
    private interface Call {
        AiExecutionResult run() throws Exception;
    }

    private static Object call(Call call) {
        try {
            return call.run();
        } catch (Exception e) {
            return e;
        }
    }

    /** Runs {@code call} bound to a handle, stops it once {@code inFlight} opened, times the stop. */
    private static StopOutcome runAndStop(Call call, CountDownLatch inFlight) throws Exception {
        AiCancellation.Handle handle = AiCancellation.newHandle();
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread worker = new Thread(() -> AiCancellation.runBound(handle, () -> outcome.set(call(call))));
        worker.setDaemon(true);
        worker.start();
        assertThat(inFlight.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(150L);
        long stopAt = System.nanoTime();
        handle.cancel();
        worker.join(10_000L);
        long stopMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - stopAt);
        assertThat(worker.isAlive()).isFalse();
        Object value = outcome.get();
        return value instanceof Throwable failure
            ? new StopOutcome(stopMillis, null, failure)
            : new StopOutcome(stopMillis, value, null);
    }

    private static AiProfile profile(AiConnectionMode mode) {
        AiProfile profile = new AiProfile();
        profile.setName("Stop test");
        profile.setConnectionMode(mode);
        return profile;
    }

    /** Only {@code executePrompt} is used; everything else is out of scope here. */
    private static final class FakePromptService implements AiPromptService {
        private final Call answer;

        FakePromptService(Call answer) {
            this.answer = answer;
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception {
            return answer.run();
        }

        @Override
        public AiExecutionResult executePrompt(String systemPrompt, String userPrompt,
                                               AiPromptExecutionScope scope) throws Exception {
            return answer.run();
        }

        @Override
        public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws Exception {
            return answer.run();
        }

        @Override
        public AiExecutionResult execute(AiRequest request) throws Exception {
            return answer.run();
        }

        @Override
        public boolean testConnection() {
            return true;
        }
    }
}
