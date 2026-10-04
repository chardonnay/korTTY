package de.kortty.core;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static com.google.common.truth.Truth.assertThat;

/**
 * Streams answers from a loopback SSE stub through {@link OpenAiCompatibleAiService} and checks
 * what an {@link AiStreamListener} sees. Never talks to a real AI service.
 */
public class OpenAiCompatibleStreamingListenerTest {

    private HttpServer server;
    private final ConcurrentLinkedQueue<Handler> handlers = new ConcurrentLinkedQueue<>();
    private final List<String> requestBodies = Collections.synchronizedList(new ArrayList<>());

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange, String requestBody) throws Exception;
    }

    @BeforeMethod
    public void startServer() throws IOException {
        handlers.clear();
        requestBodies.clear();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requestBodies.add(body);
            Handler handler = handlers.poll();
            try {
                if (handler == null) {
                    exchange.sendResponseHeaders(500, -1);
                } else {
                    handler.handle(exchange, body);
                }
            } catch (Exception e) {
                // A deliberately cut stream ends here.
            } finally {
                try {
                    exchange.close();
                } catch (RuntimeException ignored) {
                    // Closing a short fixed-length body fails on purpose for the cut-stream case.
                }
            }
        });
        server.start();
    }

    @AfterMethod(alwaysRun = true)
    public void stopServer() {
        server.stop(0);
    }

    private OpenAiCompatibleAiService service() {
        return new OpenAiCompatibleAiService(
            "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/chat/completions",
            "stub-model",
            "test-key");
    }

    private static AiRequest chatRequest(AiStreamListener listener) {
        return new AiRequest(AiAction.ASK, "uptime", "box", "en", "How long is it up?")
            .withStreamListener(listener);
    }

    private static String delta(String content) {
        return "data: {\"choices\":[{\"delta\":{\"content\":" + json(content) + "}}]}\n\n";
    }

    private static String json(String text) {
        return new com.google.gson.Gson().toJson(text);
    }

    private static final String DONE_WITH_USAGE =
        "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}],"
            + "\"usage\":{\"prompt_tokens\":11,\"completion_tokens\":7,\"total_tokens\":18}}\n\n"
            + "data: [DONE]\n\n";

    /** Sends {@code chunks} as an SSE stream, pausing so the throttle lets several snapshots out. */
    private static Handler sse(String... chunks) {
        return (exchange, body) -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            OutputStream out = exchange.getResponseBody();
            for (String chunk : chunks) {
                out.write(chunk.getBytes(StandardCharsets.UTF_8));
                out.flush();
                TimeUnit.MILLISECONDS.sleep(70);
            }
        };
    }

    /** Announces a longer body than it sends, so the client reads an early EOF. */
    private static Handler cutSse(String... chunks) {
        return (exchange, body) -> {
            StringBuilder all = new StringBuilder();
            for (String chunk : chunks) {
                all.append(chunk);
            }
            byte[] bytes = all.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, bytes.length + 4096L);
            OutputStream out = exchange.getResponseBody();
            out.write(bytes);
            out.flush();
            throw new IOException("cut on purpose");
        };
    }

    private static final class Recorder implements AiStreamListener {
        final List<String> events = Collections.synchronizedList(new ArrayList<>());
        final List<String> contents = Collections.synchronizedList(new ArrayList<>());

        @Override
        public void onProgress(String contentSoFar, String reasoningSoFar) {
            events.add("progress:" + contentSoFar);
            contents.add(contentSoFar);
        }

        @Override
        public void onRestart() {
            events.add("restart");
            contents.clear();
        }

        @Override
        public void onComplete() {
            events.add("complete");
        }
    }

    @Test
    public void snapshotsGrowMonotonicallyAndUsageIsStillRecorded() throws Exception {
        handlers.add(sse(delta("The host "), delta("has been up "), delta("for 12 days."), DONE_WITH_USAGE));
        Recorder recorder = new Recorder();

        AiExecutionResult result = service().execute(chatRequest(recorder));

        assertThat(result.content()).isEqualTo("The host has been up for 12 days.");
        assertThat(result.usage()).isNotNull();
        assertThat(result.usage().totalTokens()).isEqualTo(18);
        assertThat(recorder.contents.size()).isAtLeast(2);
        for (int i = 1; i < recorder.contents.size(); i++) {
            assertThat(recorder.contents.get(i)).startsWith(recorder.contents.get(i - 1));
        }
        assertThat(recorder.contents.get(recorder.contents.size() - 1))
            .isEqualTo("The host has been up for 12 days.");
        assertThat(recorder.events).doesNotContain("restart");
        assertThat(recorder.events.get(recorder.events.size() - 1)).isEqualTo("complete");
        assertThat(requestBodies.get(0)).contains("\"stream\":true");
    }

    @Test
    public void miniMaxFinalFullMessageChunkDoesNotDoubleTheText() throws Exception {
        handlers.add(sse(
            delta("{\"a\":"),
            delta("1}"),
            "data: {\"choices\":[{\"message\":{\"content\":\"{\\\"a\\\":1}\"},\"finish_reason\":\"stop\"}]}\n\n",
            "data: [DONE]\n\n"));
        Recorder recorder = new Recorder();

        AiExecutionResult result = service().execute(chatRequest(recorder));

        assertThat(result.content()).isEqualTo("{\"a\":1}");
        for (String snapshot : recorder.contents) {
            assertThat(snapshot).doesNotContain("}{");
        }
        assertThat(recorder.contents.get(recorder.contents.size() - 1)).isEqualTo("{\"a\":1}");
    }

    @Test
    public void cutStreamRestartsAndReplaysTheRetry() throws Exception {
        handlers.add(cutSse(delta("partial ans")));
        handlers.add(sse(delta("complete "), delta("answer"), DONE_WITH_USAGE));
        Recorder recorder = new Recorder();

        AiExecutionResult result = service().execute(chatRequest(recorder));

        assertThat(result.content()).isEqualTo("complete answer");
        assertThat(requestBodies).hasSize(2);
        int restart = recorder.events.indexOf("restart");
        assertThat(restart).isAtLeast(1);
        assertThat(recorder.events.subList(0, restart)).contains("progress:partial ans");
        // After the restart only the replayed attempt is shown.
        for (String event : recorder.events.subList(restart + 1, recorder.events.size())) {
            assertThat(event).doesNotContain("partial");
        }
        assertThat(recorder.contents.get(recorder.contents.size() - 1)).isEqualTo("complete answer");
        assertThat(recorder.events.get(recorder.events.size() - 1)).isEqualTo("complete");
    }

    @Test
    public void rejectedStreamFallsBackToBufferedWithRestartAndOneSnapshot() throws Exception {
        Handler handler = (exchange, body) -> {
            if (body.contains("\"stream\":true")) {
                byte[] error = "{\"error\":{\"message\":\"stream is not supported here\"}}"
                    .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(400, error.length);
                exchange.getResponseBody().write(error);
                return;
            }
            byte[] ok = ("{\"choices\":[{\"message\":{\"content\":\"buffered answer\"},\"finish_reason\":\"stop\"}],"
                + "\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":2,\"total_tokens\":5}}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, ok.length);
            exchange.getResponseBody().write(ok);
        };
        handlers.add(handler);
        handlers.add(handler);
        Recorder recorder = new Recorder();

        AiExecutionResult result = service().execute(chatRequest(recorder));

        assertThat(result.content()).isEqualTo("buffered answer");
        assertThat(result.usage().totalTokens()).isEqualTo(5);
        assertThat(recorder.events).containsExactly("restart", "progress:buffered answer", "complete").inOrder();
    }

    @Test
    public void throwingListenerDoesNotFailTheRequest() throws Exception {
        handlers.add(sse(delta("still "), delta("works"), DONE_WITH_USAGE));
        AiStreamListener throwing = new AiStreamListener() {
            @Override
            public void onProgress(String contentSoFar, String reasoningSoFar) {
                throw new IllegalStateException("listener bug");
            }

            @Override
            public void onComplete() {
                throw new IllegalStateException("listener bug");
            }
        };

        AiExecutionResult result = service().execute(chatRequest(throwing));

        assertThat(result.content()).isEqualTo("still works");
    }

    @Test
    public void promptPathsAndRequestsWithoutListenerStillStream() throws Exception {
        handlers.add(sse(delta("plain"), DONE_WITH_USAGE));

        AiExecutionResult result = service().execute(chatRequest(null));

        assertThat(result.content()).isEqualTo("plain");
    }

    @Test
    public void reasoningIsPartOfTheSnapshot() throws Exception {
        handlers.add(sse(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking...\"}}]}\n\n",
            delta("answer"),
            DONE_WITH_USAGE));
        List<String> reasoning = Collections.synchronizedList(new ArrayList<>());
        AiStreamListener listener = (content, thoughts) -> reasoning.add(thoughts);

        service().execute(chatRequest(listener));

        assertThat(reasoning).isNotEmpty();
        assertThat(reasoning.get(reasoning.size() - 1)).isEqualTo("thinking...");
    }

    // ---- throttle, with a fake clock ----

    @Test
    public void progressIsThrottledToAboutTwentyPerSecondPlusAFinalSnapshot() {
        AtomicLong now = new AtomicLong(1_000_000_000L);
        List<String> seen = new ArrayList<>();
        AiStreamProgress progress = AiStreamProgress.of((content, thoughts) -> seen.add(content), now::get);

        progress.acceptLine(delta("a").trim());
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(10));
        progress.acceptLine(delta("b").trim());
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(10));
        progress.acceptLine(delta("c").trim());
        now.addAndGet(TimeUnit.MILLISECONDS.toNanos(60));
        progress.acceptLine(delta("d").trim());
        progress.acceptLine(delta("e").trim());
        progress.flush();
        progress.flush();

        assertThat(seen).containsExactly("a", "abcd", "abcde").inOrder();
    }

    @Test
    public void beginAttemptRestartsOnlyAfterSomethingWasShown() {
        List<String> events = new ArrayList<>();
        AiStreamProgress progress = AiStreamProgress.of(new AiStreamListener() {
            @Override
            public void onProgress(String contentSoFar, String reasoningSoFar) {
                events.add("progress:" + contentSoFar);
            }

            @Override
            public void onRestart() {
                events.add("restart");
            }
        });

        progress.beginAttempt();
        progress.acceptLine(delta("x").trim());
        progress.beginAttempt();
        progress.acceptLine(delta("y").trim());

        assertThat(events).containsExactly("progress:x", "restart", "progress:y").inOrder();
        assertThat(AiStreamProgress.of(null)).isNull();
    }
}
