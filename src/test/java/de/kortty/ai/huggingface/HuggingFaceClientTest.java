package de.kortty.ai.huggingface;

import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class HuggingFaceClientTest {

    private static final String REVISION = "0123456789abcdef0123456789abcdef01234567";

    @Test
    void parsesPinnedGgufMetadataAndAllShards() throws Exception {
        String json = """
            {
              "id": "Qwen/Test Model-GGUF",
              "sha": "%s",
              "author": "Qwen",
              "downloads": 42,
              "likes": 7,
              "gated": "auto",
              "private": false,
              "lastModified": "2026-07-15T10:00:00Z",
              "tags": ["gguf", "license:apache-2.0"],
              "config": {
                "architectures": ["Qwen3ForCausalLM"],
                "max_position_embeddings": 32768
              },
              "siblings": [
                {
                  "rfilename": "Qwen Test-Q4_K_M-00001-of-00002.gguf",
                  "lfs": {"size": 123, "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}
                },
                {
                  "rfilename": "Qwen Test-Q4_K_M-00002-of-00002.gguf",
                  "lfs": {"size": 456, "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}
                },
                {"rfilename": "README.md", "size": 10}
              ]
            }
            """.formatted(REVISION);

        HuggingFaceModel model = HuggingFaceClient.parseModel(
            JsonParser.parseString(json).getAsJsonObject(), URI.create("https://huggingface.co/"));

        assertThat(model.id()).isEqualTo("Qwen/Test Model-GGUF");
        assertThat(model.revision()).isEqualTo(REVISION);
        assertThat(model.license()).isEqualTo("apache-2.0");
        assertThat(model.architecture()).isEqualTo("Qwen3ForCausalLM");
        assertThat(model.contextLength()).isEqualTo(32768);
        assertThat(model.ggufBytes()).isEqualTo(579);
        assertThat(model.quantizations()).containsExactly("Q4_K_M");
        assertThat(model.gated()).isTrue();
        assertThat(model.files()).hasSize(2);
        assertThat(model.files().get(0).shardIndex()).isEqualTo(1);
        assertThat(model.files().get(0).shardCount()).isEqualTo(2);
        assertThat(model.files().get(0).downloadUri().toString()).contains(REVISION);
        assertThat(model.files().get(0).downloadUri().toString()).contains("Qwen%20Test-Q4_K_M");
    }

    @Test
    void parsesMlxRepositoryAsSingleQuantizationDirectory() throws Exception {
        String json = """
            {
              "id": "mlx-community/Qwen3-4B-4bit",
              "sha": "%s",
              "author": "mlx-community",
              "gated": false,
              "private": false,
              "tags": ["mlx", "4-bit", "license:apache-2.0"],
              "config": {"model_type": "qwen3"},
              "siblings": [
                {"rfilename": "config.json", "size": 937},
                {"rfilename": "model-00001-of-00002.safetensors",
                 "lfs": {"size": 1200, "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}},
                {"rfilename": "model-00002-of-00002.safetensors",
                 "lfs": {"size": 800, "sha256": "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"}},
                {"rfilename": "model.safetensors.index.json", "size": 40},
                {"rfilename": "tokenizer.json",
                 "lfs": {"size": 500, "sha256": "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc"}},
                {"rfilename": "tokenizer_config.json", "size": 60},
                {"rfilename": "README.md", "size": 10},
                {"rfilename": ".gitattributes", "size": 5}
              ]
            }
            """.formatted(REVISION);

        HuggingFaceModel model = HuggingFaceClient.parseModel(
            JsonParser.parseString(json).getAsJsonObject(), URI.create("https://huggingface.co/"));

        assertThat(model.format()).isEqualTo(HuggingFaceModelFormat.MLX);
        assertThat(model.architecture()).isEqualTo("qwen3");
        assertThat(model.quantizations()).containsExactly("4BIT");
        assertThat(model.files()).hasSize(6);
        assertThat(model.filesForQuantization("4BIT")).hasSize(6);
        assertThat(model.bytesForQuantization("4BIT")).isEqualTo(937 + 1200 + 800 + 40 + 500 + 60);
        assertThat(model.smallestQuantizationBytes()).isEqualTo(937 + 1200 + 800 + 40 + 500 + 60);
        assertThat(model.files().stream()
            .filter(file -> file.path().equals("model-00002-of-00002.safetensors"))
            .findFirst().orElseThrow().shardIndex()).isEqualTo(2);
    }

    @Test
    void mlxQuantizationLabelPrefersRepoNameSuffixOverTags() {
        assertThat(HuggingFaceClient.mlxQuantizationLabel(
            "mlx-community/Qwen3-4B-4bit", java.util.Set.of("mlx"))).isEqualTo("4BIT");
        assertThat(HuggingFaceClient.mlxQuantizationLabel(
            "mlx-community/Llama-3.3-70B-8bit-DWQ", java.util.Set.of("mlx"))).isEqualTo("8BIT-DWQ");
        assertThat(HuggingFaceClient.mlxQuantizationLabel(
            "mlx-community/SomeModel-bf16", java.util.Set.of("mlx"))).isEqualTo("BF16");
        assertThat(HuggingFaceClient.mlxQuantizationLabel(
            "mlx-community/NoSuffixModel", java.util.Set.of("mlx", "4-bit"))).isEqualTo("4BIT");
        assertThat(HuggingFaceClient.mlxQuantizationLabel(
            "mlx-community/NoSuffixModel", java.util.Set.of("mlx"))).isEqualTo("MLX");
    }

    @Test
    void ggufRepositoriesStayGgufEvenWithMlxTag() throws Exception {
        String json = """
            {
              "id": "owner/DualModel",
              "sha": "%s",
              "gated": false,
              "private": false,
              "tags": ["gguf", "mlx"],
              "siblings": [
                {"rfilename": "model-Q4_K_M.gguf",
                 "lfs": {"size": 100, "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}
              ]
            }
            """.formatted(REVISION);

        HuggingFaceModel model = HuggingFaceClient.parseModel(
            JsonParser.parseString(json).getAsJsonObject(), URI.create("https://huggingface.co/"));

        assertThat(model.format()).isEqualTo(HuggingFaceModelFormat.GGUF);
        assertThat(model.quantizations()).containsExactly("Q4_K_M");
    }

    @Test
    void quantizationSelectionKeepsShardOrder() {
        HuggingFaceModel model = new HuggingFaceModel(
            "owner/model", "owner", REVISION, "mit", "test", 4096, 3,
            null,
            List.of(
                file("model-Q4_K_M-00002-of-00002.gguf", 2, 2),
                file("model-Q4_K_M-00001-of-00002.gguf", 1, 2)),
            null, false, false, 0, 0, null, null);

        assertThat(model.filesForQuantization("q4_k_m").stream()
            .map(HuggingFaceModelFile::shardIndex).toList()).containsExactly(1, 2).inOrder();
    }

    @Test
    void completeFileWinsWhenRepositoryAlsoPublishesShardedAlternative() {
        HuggingFaceModelFile complete = file("model-Q4_K_M.gguf", 1, 1);
        HuggingFaceModel model = new HuggingFaceModel(
            "owner/model", "owner", REVISION, "mit", "test", 4096, 4,
            null,
            List.of(
                file("model-Q4_K_M-00001-of-00002.gguf", 1, 2),
                file("model-Q4_K_M-00002-of-00002.gguf", 2, 2),
                complete),
            null, false, false, 0, 0, null, null);

        assertThat(model.filesForQuantization("Q4_K_M")).containsExactly(complete);
        assertThat(model.bytesForQuantization("Q4_K_M")).isEqualTo(1);
    }

    @Test
    void quantizationSizeDoesNotSumAlternativeRepositoryWeights() {
        HuggingFaceModel model = new HuggingFaceModel(
            "owner/model", "owner", REVISION, "mit", "test", 4096, 30,
            null,
            List.of(
                sizedFile("model-Q4_K_M.gguf", 10),
                sizedFile("model-Q8_0.gguf", 20)),
            null, false, false, 0, 0, null, null);

        assertThat(model.bytesForQuantization("Q4_K_M")).isEqualTo(10);
        assertThat(model.bytesForQuantization("Q8_0")).isEqualTo(20);
    }

    @Test
    void parsesSearchEndpointGgufExpansionWithoutBlobMetadata() throws Exception {
        String json = """
            {
              "id": "owner/search-result",
              "sha": "%s",
              "tags": ["gguf", "license:mit"],
              "siblings": [
                {"rfilename": "model-Q8_0.gguf"}
              ],
              "gguf": {
                "total": 1000,
                "totalFileSize": 1200,
                "architecture": "qwen3",
                "context_length": 40960
              }
            }
            """.formatted(REVISION);

        HuggingFaceModel model = HuggingFaceClient.parseModel(
            JsonParser.parseString(json).getAsJsonObject(), URI.create("https://huggingface.co/"));

        assertThat(model.architecture()).isEqualTo("qwen3");
        assertThat(model.contextLength()).isEqualTo(40960);
        assertThat(model.ggufBytes()).isEqualTo(1200);
        assertThat(model.quantizations()).containsExactly("Q8_0");
        assertThat(model.files().getFirst().size()).isEqualTo(-1);
        assertThat(model.hasExactFileSizes("Q8_0")).isFalse();
    }

    @Test
    void ggufParameterCountIsNeverReportedAsDownloadBytes() throws Exception {
        String json = """
            {
              "id": "owner/search-result",
              "sha": "%s",
              "siblings": [{"rfilename": "model-Q4_K_M.gguf"}],
              "gguf": {"total": 25233142046}
            }
            """.formatted(REVISION);

        HuggingFaceModel model = HuggingFaceClient.parseModel(
            JsonParser.parseString(json).getAsJsonObject(), URI.create("https://huggingface.co/"));

        assertThat(model.ggufBytes()).isEqualTo(-1);
        assertThat(model.bytesForQuantization("Q4_K_M")).isEqualTo(-1);
    }

    @Test
    void auxiliaryGgufsNeverReplacePrimaryModelWeights() throws Exception {
        String json = """
            {
              "id": "owner/model",
              "sha": "%s",
              "siblings": [
                {"rfilename": "MTP/mtp-model-Q8_0.gguf", "lfs": {"size": 10, "sha256": "%s"}},
                {"rfilename": "mmproj-BF16.gguf", "lfs": {"size": 20, "sha256": "%s"}},
                {"rfilename": "imatrix-model-Q8_0.gguf", "lfs": {"size": 30, "sha256": "%s"}},
                {"rfilename": "model-Q8_0.gguf", "lfs": {"size": 900, "sha256": "%s"}},
                {"rfilename": "model-MXFP4_MOE.gguf", "lfs": {"size": 500, "sha256": "%s"}}
              ]
            }
            """.formatted(REVISION,
                "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
                "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
                "dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd",
                "eeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeeee");

        HuggingFaceModel model = HuggingFaceClient.parseModel(
            JsonParser.parseString(json).getAsJsonObject(), URI.create("https://huggingface.co/"));

        assertThat(model.files().stream().map(HuggingFaceModelFile::path).toList())
            .containsExactly("model-Q8_0.gguf", "model-MXFP4_MOE.gguf").inOrder();
        assertThat(model.quantizations()).containsExactly("Q8_0", "MXFP4_MOE");
        assertThat(model.bytesForQuantization("Q8_0")).isEqualTo(900);
    }

    @Test
    void loadsSignedRecommendationAtItsExactRevision() throws Exception {
        AtomicReference<URI> requested = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/models/owner/model/revision/" + REVISION, exchange -> {
            requested.set(exchange.getRequestURI());
            byte[] body = ("{\"id\":\"owner/model\",\"sha\":\"" + REVISION
                + "\",\"siblings\":[{\"rfilename\":\"model-Q4_K_S.gguf\","
                + "\"lfs\":{\"size\":16487608096,\"sha256\":"
                + "\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\"}}]}")
                .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            HuggingFaceClient client = new HuggingFaceClient(
                HttpClient.newHttpClient(), base, HuggingFaceTokenProvider.anonymous());

            HuggingFaceModel model = client.getModel("owner/model", REVISION);

            assertThat(model.revision()).isEqualTo(REVISION);
            assertThat(model.hasExactFileSizes("Q4_K_S")).isTrue();
            assertThat(model.bytesForQuantization("Q4_K_S")).isEqualTo(16_487_608_096L);
            assertThat(requested.get().getPath())
                .isEqualTo("/api/models/owner/model/revision/" + REVISION);
            assertThat(requested.get().getQuery()).isEqualTo("blobs=true");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void followsOnlySameOriginCursorPaginationAndKeepsBothPages() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/models", exchange -> {
            int request = requests.incrementAndGet();
            boolean continuation = exchange.getRequestURI().getRawQuery().contains("cursor=next-token");
            String id = continuation ? "owner/second" : "owner/first";
            byte[] body = ("[{\"id\":\"" + id + "\",\"sha\":\"" + REVISION
                + "\",\"siblings\":[]}]").getBytes(StandardCharsets.UTF_8);
            if (!continuation) {
                exchange.getResponseHeaders().set("Link", "<http://127.0.0.1:"
                    + server.getAddress().getPort()
                    + "/api/models?filter=gguf&limit=1&cursor=next-token>; rel=\"next\"");
            }
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) {
                output.write(body);
            }
        });
        server.start();
        try {
            URI base = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/");
            HuggingFaceClient client = new HuggingFaceClient(
                HttpClient.newHttpClient(), base, HuggingFaceTokenProvider.anonymous());

            HuggingFaceClient.SearchPage first = client.searchGgufModelsPage("qwen", 1);
            HuggingFaceClient.SearchPage second = client.continueGgufModelSearch(
                first.nextPage().orElseThrow());

            assertThat(first.models().getFirst().id()).isEqualTo("owner/first");
            assertThat(second.models().getFirst().id()).isEqualTo("owner/second");
            assertThat(second.nextPage()).isEmpty();
            assertThat(requests.get()).isEqualTo(2);
        } finally {
            server.stop(0);
        }
    }

    private static HuggingFaceModelFile file(String path, int index, int count) {
        return new HuggingFaceModelFile(
            path, 1,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            URI.create("https://example.test/" + path),
            "Q4_K_M", index, count);
    }

    private static HuggingFaceModelFile sizedFile(String path, long size) {
        return new HuggingFaceModelFile(
            path, size,
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            URI.create("https://example.test/" + path),
            null, 1, 1);
    }
}
