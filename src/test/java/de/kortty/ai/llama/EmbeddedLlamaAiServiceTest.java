package de.kortty.ai.llama;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import de.kortty.core.AiAction;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiRequest;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.core.OpenAiCompatibleAiService;
import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class EmbeddedLlamaAiServiceTest {

    @Test
    void delegatesThroughAuthenticatedLeaseAndReleasesItAfterRequest() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonObject> requestBody = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(JsonParser.parseString(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] response = """
                {"choices":[{"message":{"content":"local response"}}],
                 "usage":{"prompt_tokens":7,"completion_tokens":2,"total_tokens":9}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try (LlamaRuntimeManager manager = newManagerFor(server)) {
            EmbeddedLlamaAiService service = new EmbeddedLlamaAiService(
                "test-model",
                AiReasoningEffort.DISABLED,
                null,
                AiSkillPromptSupport.disabled(),
                manager);

            AiExecutionResult result = service.execute(
                new AiRequest(AiAction.SUMMARIZE, "sample", "terminal", "en"));

            assertThat(result.content()).isEqualTo("local response");
            assertThat(result.usage().totalTokens()).isEqualTo(9);
            assertThat(authorization.get()).startsWith("Bearer ");
            assertThat(requestBody.get().get("model").getAsString()).isEqualTo("test-model");
            assertThat(manager.status("test-model").orElseThrow().activeLeases()).isEqualTo(0);
            assertThat(manager.status("test-model").orElseThrow().state()).isEqualTo(LlamaRuntimeState.READY);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesOnceWhenLocalServerFailsWithServerError() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            int attempt = requests.incrementAndGet();
            byte[] response = (attempt == 1
                ? """
                    {"error":{"code":500,"type":"server_error",
                     "message":"The model produced output that does not match the expected peg-native format"}}
                    """
                : """
                    {"choices":[{"message":{"content":"retried response"}}]}
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(attempt == 1 ? 500 : 200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try (LlamaRuntimeManager manager = newManagerFor(server)) {
            EmbeddedLlamaAiService service = new EmbeddedLlamaAiService(
                "test-model",
                AiReasoningEffort.DISABLED,
                null,
                AiSkillPromptSupport.disabled(),
                manager);

            AiExecutionResult result = service.execute(
                new AiRequest(AiAction.SUMMARIZE, "sample", "terminal", "en"));

            assertThat(result.content()).isEqualTo("retried response");
            assertThat(requests.get()).isEqualTo(2);
            assertThat(manager.status("test-model").orElseThrow().activeLeases()).isEqualTo(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void separatesInlineReasoningFromLocalReplies() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = """
                {"choices":[{"message":{"content":"<think>weighing the options</think>final answer"}}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try (LlamaRuntimeManager manager = newManagerFor(server)) {
            EmbeddedLlamaAiService service = new EmbeddedLlamaAiService(
                "test-model",
                AiReasoningEffort.DISABLED,
                null,
                AiSkillPromptSupport.disabled(),
                manager);

            AiExecutionResult result = service.execute(
                new AiRequest(AiAction.SUMMARIZE, "sample", "terminal", "en"));

            assertThat(result.content()).isEqualTo("final answer");
            assertThat(result.reasoning()).isEqualTo("weighing the options");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void retriesReasoningOnlyRepliesOnceBeforeFailing() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            byte[] response = """
                {"choices":[{"message":{"content":"<think>reasoning cut off by the token limit"}}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try (LlamaRuntimeManager manager = newManagerFor(server)) {
            EmbeddedLlamaAiService service = new EmbeddedLlamaAiService(
                "test-model",
                AiReasoningEffort.DISABLED,
                null,
                AiSkillPromptSupport.disabled(),
                manager);

            IOException failure = expectThrows(
                IOException.class,
                () -> service.execute(new AiRequest(AiAction.SUMMARIZE, "sample", "terminal", "en")));

            assertThat(failure).hasMessageThat().contains("reasoning");
            assertThat(requests.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void recoversWhenRetryAnswersAfterReasoningOnlyReply() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            byte[] response = (requests.incrementAndGet() == 1
                ? """
                    {"choices":[{"message":{"content":"<think>reasoning cut off by the token limit"}}]}
                    """
                : """
                    {"choices":[{"message":{"content":"<think>short plan</think>final answer"}}]}
                    """).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try (LlamaRuntimeManager manager = newManagerFor(server)) {
            EmbeddedLlamaAiService service = new EmbeddedLlamaAiService(
                "test-model",
                AiReasoningEffort.DISABLED,
                null,
                AiSkillPromptSupport.disabled(),
                manager);

            AiExecutionResult result = service.execute(
                new AiRequest(AiAction.SUMMARIZE, "sample", "terminal", "en"));

            assertThat(result.content()).isEqualTo("final answer");
            assertThat(result.reasoning()).isEqualTo("short plan");
            assertThat(requests.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void doesNotRetryClientErrors() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.incrementAndGet();
            byte[] response = """
                {"error":{"code":400,"type":"invalid_request_error","message":"invalid request"}}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try (LlamaRuntimeManager manager = newManagerFor(server)) {
            EmbeddedLlamaAiService service = new EmbeddedLlamaAiService(
                "test-model",
                AiReasoningEffort.DISABLED,
                null,
                AiSkillPromptSupport.disabled(),
                manager);

            OpenAiCompatibleAiService.AiApiException failure = expectThrows(
                OpenAiCompatibleAiService.AiApiException.class,
                () -> service.execute(new AiRequest(AiAction.SUMMARIZE, "sample", "terminal", "en")));

            assertThat(failure.statusCode()).isEqualTo(400);
            assertThat(requests.get()).isEqualTo(1);
        } finally {
            server.stop(0);
        }
    }

    private static LlamaRuntimeManager newManagerFor(HttpServer server) throws Exception {
        Path directory = Files.createTempDirectory("kortty-embedded-service-");
        Path gguf = Files.writeString(directory.resolve("model.gguf"), "GGUF");
        Path executable = Files.writeString(directory.resolve("llama-server"), "test executable");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(directory);
        registry.register(new LlamaModel(
            "test-model",
            "Test Model",
            gguf,
            executable,
            LlamaBackend.CPU,
            4096,
            0,
            0,
            0));
        return new LlamaRuntimeManager(
            registry,
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (args, workingDirectory, logFile) -> new LlamaRuntimeManagerTest.FakeProcess(),
            (process, healthEndpoint, apiKey, timeout) -> { },
            () -> server.getAddress().getPort(),
            Executors.newSingleThreadScheduledExecutor());
    }
}
