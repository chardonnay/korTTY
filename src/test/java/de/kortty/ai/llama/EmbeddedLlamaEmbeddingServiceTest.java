package de.kortty.ai.llama;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import de.kortty.rag.CancellationToken;
import org.testng.annotations.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;

class EmbeddedLlamaEmbeddingServiceTest {

    @Test
    void embedsAuthenticatedBatchAndRestoresInputOrder() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicReference<String> authorization = new AtomicReference<>();
        AtomicReference<JsonObject> requestBody = new AtomicReference<>();
        server.createContext("/v1/embeddings", exchange -> {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(JsonParser.parseString(
                new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject());
            byte[] response = """
                {"data":[
                  {"index":1,"embedding":[0.4,0.5,0.6]},
                  {"index":0,"embedding":[0.1,0.2,0.3]}
                ]}
                """.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        });
        server.start();
        TestManager test = createManager(server.getAddress().getPort());
        try (test.manager) {
            EmbeddedLlamaEmbeddingService service = new EmbeddedLlamaEmbeddingService(
                "embedding-model", 3, test.manager);

            List<float[]> result = service.embed(List.of("first", "second"), CancellationToken.NONE);

            assertThat(result).hasSize(2);
            assertThat(result.get(0)).usingExactEquality().containsExactly(0.1f, 0.2f, 0.3f).inOrder();
            assertThat(result.get(1)).usingExactEquality().containsExactly(0.4f, 0.5f, 0.6f).inOrder();
            assertThat(authorization.get()).startsWith("Bearer ");
            assertThat(requestBody.get().get("model").getAsString()).isEqualTo("embedding-model");
            assertThat(requestBody.get().getAsJsonArray("input")).hasSize(2);
            assertThat(test.manager.status("embedding-model").orElseThrow().activeLeases()).isEqualTo(0);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void honorsCancellationBeforeAcquiringRuntime() throws Exception {
        TestManager test = createManager(26000);
        try (test.manager) {
            EmbeddedLlamaEmbeddingService service = new EmbeddedLlamaEmbeddingService(
                "embedding-model", 3, test.manager);
            CancellationToken.Source cancellation = CancellationToken.source();
            cancellation.cancel();

            org.testng.Assert.expectThrows(
                java.util.concurrent.CancellationException.class,
                () -> service.embed(List.of("cancelled"), cancellation.token()));

            assertThat(test.manager.status("embedding-model")).isEmpty();
        }
    }

    private static TestManager createManager(int port) throws Exception {
        Path directory = Files.createTempDirectory("kortty-embedding-service-");
        Path gguf = Files.writeString(directory.resolve("embedding.gguf"), "GGUF");
        Path executable = Files.writeString(directory.resolve("llama-server"), "test executable");
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(directory);
        registry.register(new LlamaModel(
            "embedding-model", "Embedding Model", gguf, executable,
            LlamaBackend.CPU, LlamaModelPurpose.EMBEDDING, 4096, 0, 0, 0));
        LlamaRuntimeManagerTest.FakeProcess process = new LlamaRuntimeManagerTest.FakeProcess();
        LlamaRuntimeManager manager = new LlamaRuntimeManager(
            registry,
            directory.resolve("run"),
            Duration.ofSeconds(5),
            (args, workingDirectory, logFile) -> process,
            (startedProcess, healthEndpoint, apiKey, timeout) -> { },
            () -> port,
            Executors.newSingleThreadScheduledExecutor());
        return new TestManager(manager);
    }

    private record TestManager(LlamaRuntimeManager manager) {
    }
}
