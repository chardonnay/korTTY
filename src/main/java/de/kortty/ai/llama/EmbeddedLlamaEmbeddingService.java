package de.kortty.ai.llama;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.rag.CancellationToken;
import de.kortty.rag.EmbeddingService;

import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** RAG embedding adapter backed by a leased, authenticated local llama-server. */
public final class EmbeddedLlamaEmbeddingService implements EmbeddingService {

    private static final Gson GSON = new Gson();
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(10);

    private final String modelId;
    private final int dimensions;
    private final Supplier<LlamaRuntimeManager> runtimeManagerSupplier;
    private final HttpClient httpClient;

    public EmbeddedLlamaEmbeddingService(String modelId, int dimensions) {
        this(modelId, dimensions, LlamaRuntimeManager::getDefault, HttpClient.newHttpClient());
    }

    public EmbeddedLlamaEmbeddingService(String modelId, int dimensions, LlamaRuntimeManager runtimeManager) {
        this(modelId, dimensions, () -> runtimeManager, HttpClient.newHttpClient());
    }

    EmbeddedLlamaEmbeddingService(
        String modelId,
        int dimensions,
        Supplier<LlamaRuntimeManager> runtimeManagerSupplier,
        HttpClient httpClient) {

        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Embedding model id must be configured.");
        }
        if (dimensions <= 0) {
            throw new IllegalArgumentException("Embedding dimensions must be positive.");
        }
        if (runtimeManagerSupplier == null || httpClient == null) {
            throw new IllegalArgumentException("Embedding runtime dependencies must be configured.");
        }
        this.modelId = modelId.trim();
        this.dimensions = dimensions;
        this.runtimeManagerSupplier = runtimeManagerSupplier;
        this.httpClient = httpClient;
    }

    @Override
    public String modelId() {
        return modelId;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts, CancellationToken cancellation) throws Exception {
        if (texts == null) {
            throw new IllegalArgumentException("Embedding texts must not be null.");
        }
        if (texts.isEmpty()) {
            return List.of();
        }
        for (String text : texts) {
            if (text == null) {
                throw new IllegalArgumentException("Embedding texts must not contain null entries.");
            }
        }
        CancellationToken token = cancellation != null ? cancellation : CancellationToken.NONE;
        token.throwIfCancelled();
        LlamaRuntimeManager manager = runtimeManagerSupplier.get();
        if (manager == null) {
            throw new LlamaRuntimeException("llama.cpp runtime manager is not available for embeddings.");
        }
        try (LlamaRuntimeManager.RuntimeLease lease = manager.acquire(modelId)) {
            if (lease.purpose() != LlamaModelPurpose.EMBEDDING) {
                throw new LlamaRuntimeException(
                    "Local model " + modelId + " is configured for chat generation, not embeddings.");
            }
            HttpRequest request = HttpRequest.newBuilder(lease.embeddingsEndpoint())
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + lease.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(
                    requestBody(lease.modelAlias(), texts),
                    StandardCharsets.UTF_8))
                .build();
            HttpResponse<String> response = awaitResponse(httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)), token);
            token.throwIfCancelled();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new IOException(
                    "llama.cpp embedding API returned HTTP " + response.statusCode() + ": "
                        + safeError(response.body()));
            }
            return parseEmbeddings(response.body(), texts.size(), token);
        }
    }

    private static String requestBody(String modelAlias, List<String> texts) {
        JsonObject root = new JsonObject();
        root.addProperty("model", modelAlias);
        root.addProperty("encoding_format", "float");
        JsonArray input = new JsonArray();
        texts.forEach(input::add);
        root.add("input", input);
        return GSON.toJson(root);
    }

    private static HttpResponse<String> awaitResponse(
        CompletableFuture<HttpResponse<String>> future,
        CancellationToken cancellation) throws Exception {

        try {
            while (true) {
                cancellation.throwIfCancelled();
                try {
                    return future.get(100L, TimeUnit.MILLISECONDS);
                } catch (TimeoutException ignored) {
                    // Poll cancellation without forcing a dedicated executor per indexing job.
                } catch (ExecutionException e) {
                    Throwable cause = e.getCause();
                    if (cause instanceof Exception exception) {
                        throw exception;
                    }
                    throw new IOException("llama.cpp embedding request failed.", cause);
                }
            }
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw e;
        } catch (Exception e) {
            future.cancel(true);
            throw e;
        }
    }

    private List<float[]> parseEmbeddings(String responseBody, int expectedCount, CancellationToken token) throws IOException {
        try {
            JsonObject root = JsonParser.parseString(responseBody).getAsJsonObject();
            JsonArray data = root.getAsJsonArray("data");
            if (data == null || data.size() != expectedCount) {
                throw new IOException("Embedding API returned an unexpected vector count.");
            }
            List<IndexedVector> indexed = new ArrayList<>(data.size());
            for (int fallbackIndex = 0; fallbackIndex < data.size(); fallbackIndex++) {
                token.throwIfCancelled();
                JsonObject entry = data.get(fallbackIndex).getAsJsonObject();
                int index = entry.has("index") ? entry.get("index").getAsInt() : fallbackIndex;
                JsonArray values = entry.getAsJsonArray("embedding");
                if (values == null || values.size() != dimensions) {
                    throw new IOException(
                        "Embedding vector dimension mismatch: expected " + dimensions + ", got "
                            + (values != null ? values.size() : 0) + ".");
                }
                float[] vector = new float[dimensions];
                for (int i = 0; i < dimensions; i++) {
                    JsonElement value = values.get(i);
                    float number = value.getAsFloat();
                    if (!Float.isFinite(number)) {
                        throw new IOException("Embedding API returned a non-finite vector value.");
                    }
                    vector[i] = number;
                }
                indexed.add(new IndexedVector(index, vector));
            }
            indexed.sort(Comparator.comparingInt(IndexedVector::index));
            for (int i = 0; i < indexed.size(); i++) {
                if (indexed.get(i).index() != i) {
                    throw new IOException("Embedding API returned duplicate or missing vector indices.");
                }
            }
            return indexed.stream().map(IndexedVector::vector).toList();
        } catch (CancellationException e) {
            throw e;
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Could not parse llama.cpp embedding response.", e);
        }
    }

    private static String safeError(String body) {
        if (body == null || body.isBlank()) {
            return "empty response";
        }
        String normalized = body.replaceAll("\\s+", " ").trim();
        return normalized.length() <= 500 ? normalized : normalized.substring(0, 500) + "…";
    }

    private record IndexedVector(int index, float[] vector) {
    }
}
