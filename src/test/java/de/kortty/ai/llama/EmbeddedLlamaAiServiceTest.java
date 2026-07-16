package de.kortty.ai.llama;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import de.kortty.core.AiAction;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiRequest;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;

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
        LlamaRuntimeManagerTest.FakeProcess fakeProcess = new LlamaRuntimeManagerTest.FakeProcess();
        LlamaRuntimeManager manager = new LlamaRuntimeManager(
            registry,
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (args, workingDirectory, logFile) -> fakeProcess,
            (process, healthEndpoint, apiKey, timeout) -> { },
            () -> server.getAddress().getPort(),
            Executors.newSingleThreadScheduledExecutor());
        try (manager) {
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
}
