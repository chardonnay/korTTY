package de.kortty.ai.mlx;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import de.kortty.core.AiAction;
import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiRequest;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.model.AiReasoningEffort;
import org.testng.annotations.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;

class EmbeddedMlxAiServiceTest {

    @Test
    void delegatesThroughAuthenticatedLeaseWithoutNamingAModel() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonObject> requestBody = new AtomicReference<>();
        server.createContext("/v1/chat/completions", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(JsonParser.parseString(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] response = """
                {"choices":[{"message":{"content":"<think>plan</think>mlx response"}}]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();

        try (MlxRuntimeManager manager = newManagerFor(server)) {
            EmbeddedMlxAiService service = new EmbeddedMlxAiService(
                "test-model",
                AiReasoningEffort.DISABLED,
                null,
                AiSkillPromptSupport.disabled(),
                manager);

            AiExecutionResult result = service.execute(
                new AiRequest(AiAction.SUMMARIZE, "sample", "terminal", "en"));

            assertThat(result.content()).isEqualTo("mlx response");
            assertThat(result.reasoning()).isEqualTo("plan");
            assertThat(authorization.get()).startsWith("Bearer ");
            // mlx-lm loads the request's "model" value as a path or Hugging Face id; the sidecar
            // must keep serving the one directory it was started with.
            assertThat(requestBody.get().has("model")).isFalse();
            // mlx_lm.server caps an absent max_tokens at 512 completion tokens, which reasoning
            // models exhaust inside their chain-of-thought; the explicit budget prevents that.
            assertThat(requestBody.get().get("max_tokens").getAsInt())
                .isEqualTo(EmbeddedMlxAiService.MAX_COMPLETION_TOKENS);
            assertThat(manager.status("test-model").orElseThrow().activeLeases()).isEqualTo(0);
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
                    {"error":{"code":500,"type":"server_error","message":"model crashed"}}
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

        try (MlxRuntimeManager manager = newManagerFor(server)) {
            EmbeddedMlxAiService service = new EmbeddedMlxAiService(
                "test-model",
                AiReasoningEffort.DISABLED,
                null,
                AiSkillPromptSupport.disabled(),
                manager);

            AiExecutionResult result = service.execute(
                new AiRequest(AiAction.SUMMARIZE, "sample", "terminal", "en"));

            assertThat(result.content()).isEqualTo("retried response");
            assertThat(requests.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    private static MlxRuntimeManager newManagerFor(HttpServer server) throws Exception {
        Path directory = Files.createTempDirectory("kortty-embedded-mlx-service-");
        Path modelDirectory = Files.createDirectories(directory.resolve("model"));
        Files.writeString(modelDirectory.resolve("config.json"), "{}");
        Files.writeString(modelDirectory.resolve("model.safetensors"), "safetensors");
        Path packageDirectory = Files.createDirectories(
            directory.resolve("runtime").resolve("packages").resolve("mlx-test"));
        Path python = Files.createDirectories(packageDirectory.resolve("python").resolve("bin"))
            .resolve("python3");
        Files.writeString(python, "interpreter");
        assertThat(python.toFile().setExecutable(true)).isTrue();
        Files.writeString(packageDirectory.resolve("kortty_mlx_server.py"), "# launcher");
        Files.writeString(directory.resolve("runtime").resolve("active"), "mlx-test");
        MlxModelRegistry registry = MlxModelRegistry.inDirectory(directory);
        registry.register(new MlxModel(
            "test-model", "Test Model", modelDirectory,
            MlxModel.MODEL_DEFAULT_CONTEXT_SIZE, 0, "4bit"));
        return new MlxRuntimeManager(
            registry,
            new MlxRuntimeLocator(directory.resolve("runtime")),
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (args, environment, workingDirectory, logFile) -> new IdleFakeProcess(),
            (process, healthEndpoint, timeout) -> { },
            () -> server.getAddress().getPort());
    }

    /** Stays alive so leases keep their sidecar; the tests talk to the local HTTP stub instead. */
    private static final class IdleFakeProcess extends Process {

        private final CompletableFuture<Process> exit = new CompletableFuture<>();

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public int exitValue() {
            throw new IllegalThreadStateException("still running");
        }

        @Override
        public void destroy() {
            exit.complete(this);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return !exit.isDone();
        }

        @Override
        public CompletableFuture<Process> onExit() {
            return exit;
        }
    }
}
