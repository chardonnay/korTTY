package de.kortty.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** JDK HttpClient implementation of the Qdrant REST points/scroll/query APIs. */
public final class HttpQdrantRestAdapter implements QdrantRestAdapter {
    private static final Logger logger = LoggerFactory.getLogger(HttpQdrantRestAdapter.class);
    private static final int UPSERT_BATCH = 128;
    private static final int SCROLL_PAGE_SIZE = 256;
    private static final int SNAPSHOT_READ_ATTEMPTS = 3;
    private static final int MAX_RESPONSE_BYTES = 32 * 1024 * 1024;
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final String CONSISTENT_READ = "?consistency=all";
    private static final String ORDERED_WRITE = "?wait=true&ordering=strong";

    private final String baseUrl;
    private final String apiKey;
    private final HttpClient client;
    private final Duration timeout;
    private final Map<String, Integer> collectionDimensions = new ConcurrentHashMap<>();
    private final Object[] sourceWriteLocks = createSourceWriteLocks();

    public HttpQdrantRestAdapter(URI endpoint, String apiKey) {
        this(endpoint, apiKey, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build(), DEFAULT_TIMEOUT);
    }

    public HttpQdrantRestAdapter(URI endpoint, String apiKey, HttpClient client, Duration timeout) {
        if (endpoint == null || client == null) {
            throw new IllegalArgumentException("endpoint and client are required");
        }
        String scheme = endpoint.getScheme();
        if (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme)) {
            throw new IllegalArgumentException("Qdrant endpoint must use HTTP or HTTPS");
        }
        if ("http".equalsIgnoreCase(scheme) && !isLoopback(endpoint.getHost())) {
            throw new IllegalArgumentException("Remote Qdrant endpoints must use HTTPS");
        }
        this.baseUrl = endpoint.toString().replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey;
        this.client = client;
        this.timeout = timeout != null ? timeout : DEFAULT_TIMEOUT;
    }

    private static boolean isLoopback(String host) {
        return host != null && ("localhost".equalsIgnoreCase(host)
            || "127.0.0.1".equals(host) || "::1".equals(host));
    }

    @Override
    public void ensureCollection(String collectionName, int dimensions) throws Exception {
        String path = collectionPath(collectionName);
        Response current = send("GET", path, null);
        if (current.status == 404) {
            JsonObject vectors = new JsonObject();
            vectors.addProperty("size", dimensions);
            vectors.addProperty("distance", "Cosine");
            JsonObject body = new JsonObject();
            body.add("vectors", vectors);
            requireSuccess(send("PUT", path, body), "create Qdrant collection");
            collectionDimensions.put(collectionName, dimensions);
            return;
        }
        JsonObject response = requireSuccess(current, "read Qdrant collection");
        JsonElement vectors = path(response, "result", "config", "params", "vectors");
        if (vectors != null && vectors.isJsonObject()) {
            JsonObject config = vectors.getAsJsonObject();
            if (config.has("size") && config.get("size").getAsInt() != dimensions) {
                throw new IOException("Qdrant collection vector dimension mismatch");
            }
            if (config.has("distance") && !"cosine".equalsIgnoreCase(config.get("distance").getAsString())) {
                throw new IOException("Qdrant collection must use cosine distance");
            }
        }
        collectionDimensions.put(collectionName, dimensions);
    }

    @Override
    public List<RagEmbeddedChunk> readSource(String collectionName, String sourceId) throws Exception {
        for (int attempt = 1; attempt <= SNAPSHOT_READ_ATTEMPTS; attempt++) {
            Optional<String> activeGeneration = activeGeneration(collectionName, sourceId);
            if (activeGeneration.isEmpty()) {
                return List.of();
            }
            List<RagEmbeddedChunk> chunks = readSourceGeneration(
                collectionName, sourceId, activeGeneration.get());
            if (activeGeneration(collectionName, sourceId).equals(activeGeneration)) {
                return chunks;
            }
            logger.debug("Qdrant source {} changed generation during read; retrying ({}/{})",
                sourceId, attempt, SNAPSHOT_READ_ATTEMPTS);
        }
        throw new IOException("Qdrant source changed repeatedly while reading its active generation");
    }

    private List<RagEmbeddedChunk> readSourceGeneration(
        String collectionName,
        String sourceId,
        String generation
    ) throws Exception {
        List<RagEmbeddedChunk> chunks = new ArrayList<>();
        JsonElement offset = null;
        do {
            JsonObject body = new JsonObject();
            body.add("filter", sourceGenerationFilter(sourceId, generation));
            body.addProperty("limit", SCROLL_PAGE_SIZE);
            body.addProperty("with_payload", true);
            body.addProperty("with_vector", true);
            if (offset != null && !offset.isJsonNull()) {
                body.add("offset", offset.deepCopy());
            }
            JsonObject response = requireSuccess(send("POST",
                collectionPath(collectionName) + "/points/scroll" + CONSISTENT_READ, body),
                "scroll Qdrant source");
            JsonObject result = requireObject(response.get("result"), "Qdrant scroll result");
            JsonArray points = requireArray(result.get("points"), "Qdrant scroll points");
            for (JsonElement point : points) {
                JsonObject object = requireObject(point, "Qdrant point");
                RagChunk chunk = parseChunk(requireObject(object.get("payload"), "Qdrant payload"));
                float[] vector = parseVector(object.get("vector"));
                chunks.add(new RagEmbeddedChunk(chunk, vector));
            }
            offset = result.get("next_page_offset");
        } while (offset != null && !offset.isJsonNull());
        return List.copyOf(chunks);
    }

    @Override
    public void replaceSource(
        String collectionName,
        String sourceId,
        Collection<RagEmbeddedChunk> chunks,
        CancellationToken cancellation
    ) throws Exception {
        synchronized (sourceWriteLock(collectionName, sourceId)) {
            replaceSourceLocked(collectionName, sourceId, chunks, cancellation);
        }
    }

    private void replaceSourceLocked(
        String collectionName,
        String sourceId,
        Collection<RagEmbeddedChunk> chunks,
        CancellationToken cancellation
    ) throws Exception {
        CancellationToken token = cancellation != null ? cancellation : CancellationToken.NONE;
        token.throwIfCancelled();
        List<RagEmbeddedChunk> values = List.copyOf(chunks);
        String generation = UUID.randomUUID().toString();
        int dimensions = requiredDimensions(collectionName);
        for (RagEmbeddedChunk value : values) {
            if (!value.chunk().sourceId().equals(sourceId)) {
                throw new IllegalArgumentException("Replacement contains a different source id");
            }
            if (value.vector().length != dimensions) {
                throw new IllegalArgumentException("Replacement vector dimension mismatch");
            }
            validateVector(value.vector());
        }
        Optional<String> previousGeneration = activeGeneration(collectionName, sourceId);

        // Stage a complete new generation under generation-specific point IDs. Until the single
        // manifest point below is replaced, every reader continues to filter on the old generation.
        try {
            for (int offset = 0; offset < values.size(); offset += UPSERT_BATCH) {
                token.throwIfCancelled();
                int end = Math.min(values.size(), offset + UPSERT_BATCH);
                JsonArray points = new JsonArray();
                for (RagEmbeddedChunk value : values.subList(offset, end)) {
                    points.add(serializePoint(value, generation));
                }
                JsonObject body = new JsonObject();
                body.add("points", points);
                requireSuccess(send("PUT", collectionPath(collectionName) + "/points" + ORDERED_WRITE, body),
                    "upsert Qdrant points");
            }
            token.throwIfCancelled();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        } catch (Exception error) {
            cleanupGenerationBestEffort(collectionName, sourceId, generation,
                "partially staged Qdrant source generation");
            throw error;
        }

        // One wait=true point upsert is the logical commit. A crash or failed request before this
        // line leaves the previous manifest and therefore the previous source generation active.
        JsonObject commit = new JsonObject();
        JsonArray manifest = new JsonArray();
        manifest.add(serializeManifest(sourceId, generation, dimensions));
        commit.add("points", manifest);
        try {
            requireSuccess(send("PUT", collectionPath(collectionName) + "/points" + ORDERED_WRITE, commit),
                "activate Qdrant source generation");
        } catch (InterruptedException error) {
            // The server may have committed before the transport observed the interruption. Keep
            // the staged generation intact because deleting it could invalidate the active manifest.
            Thread.currentThread().interrupt();
            throw error;
        } catch (Exception commitError) {
            try {
                if (activeGeneration(collectionName, sourceId).filter(generation::equals).isPresent()) {
                    // A response can be lost after Qdrant has applied the wait=true update. Treat
                    // the manifest as authoritative instead of reporting a false failed snapshot.
                    logger.warn("Qdrant activated source generation {} despite a failed commit response",
                        generation);
                } else {
                    // Absence is not proof that a distributed write did not commit: a replica may
                    // lag even with a consistency-qualified read. Never delete this generation
                    // after a commit was attempted, because a manifest on another replica could
                    // already reference it. A later replacement/removal can sweep the orphan.
                    throw commitError;
                }
            } catch (Exception verificationError) {
                if (verificationError != commitError) {
                    commitError.addSuppressed(verificationError);
                }
                throw commitError;
            }
        }

        // Delete only the generation that was active when this replacement began. A broad
        // "everything except current" filter could erase a generation concurrently staged by a
        // different client. Invisible abandoned generations are safer than an active manifest
        // whose points another writer removed.
        previousGeneration.filter(previous -> !previous.equals(generation)).ifPresent(previous ->
            cleanupGenerationBestEffort(collectionName, sourceId, previous,
                "previous Qdrant source generation"));
    }

    @Override
    public void removeSource(String collectionName, String sourceId, CancellationToken cancellation) throws Exception {
        synchronized (sourceWriteLock(collectionName, sourceId)) {
            removeSourceLocked(collectionName, sourceId, cancellation);
        }
    }

    private void removeSourceLocked(
        String collectionName,
        String sourceId,
        CancellationToken cancellation
    ) throws Exception {
        CancellationToken token = cancellation != null ? cancellation : CancellationToken.NONE;
        token.throwIfCancelled();
        JsonObject deactivate = new JsonObject();
        JsonArray manifestId = new JsonArray();
        manifestId.add(manifestPointId(sourceId));
        deactivate.add("points", manifestId);
        try {
            requireSuccess(send("POST", collectionPath(collectionName) + "/points/delete" + ORDERED_WRITE,
                deactivate), "deactivate Qdrant source");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw error;
        }

        // The manifest deletion is the required operation. Physical deletion is deliberately
        // best effort and runs only after deactivation. Unlike replacement cleanup, removal is a
        // terminal operation and sweeps every chunk generation, including leftovers from an
        // earlier failed cleanup.
        cleanupSourceBestEffort(collectionName, sourceId);
    }

    @Override
    public List<RagSearchResult> search(
        String collectionName,
        float[] queryVector,
        int limit,
        Set<String> sourceIds,
        CancellationToken cancellation
    ) throws Exception {
        if (limit <= 0) {
            return List.of();
        }
        CancellationToken token = cancellation != null ? cancellation : CancellationToken.NONE;
        token.throwIfCancelled();
        Set<String> requestedSources = sourceIds != null ? Set.copyOf(sourceIds) : Set.of();
        for (int attempt = 1; attempt <= SNAPSHOT_READ_ATTEMPTS; attempt++) {
            token.throwIfCancelled();
            Map<String, String> activeGenerations = activeGenerations(collectionName, requestedSources);
            if (activeGenerations.isEmpty()) {
                return List.of();
            }
            List<RagSearchResult> results = searchGenerations(
                collectionName, queryVector, limit, activeGenerations, token);
            if (activeGenerations(collectionName, requestedSources).equals(activeGenerations)) {
                return results;
            }
            logger.debug("Qdrant source generations changed during search; retrying ({}/{})",
                attempt, SNAPSHOT_READ_ATTEMPTS);
        }
        throw new IOException("Qdrant source generations changed repeatedly during search");
    }

    private List<RagSearchResult> searchGenerations(
        String collectionName,
        float[] queryVector,
        int limit,
        Map<String, String> activeGenerations,
        CancellationToken token
    ) throws Exception {
        JsonObject body = new JsonObject();
        body.add("query", floats(queryVector));
        body.addProperty("limit", limit);
        body.addProperty("with_payload", true);
        body.addProperty("with_vector", false);
        body.add("filter", activeSourceFilter(activeGenerations));
        JsonObject response = requireSuccess(send("POST",
            collectionPath(collectionName) + "/points/query" + CONSISTENT_READ, body),
            "query Qdrant points");
        JsonElement rawResult = response.get("result");
        JsonArray points = rawResult != null && rawResult.isJsonObject()
            ? requireArray(rawResult.getAsJsonObject().get("points"), "Qdrant query points")
            : requireArray(rawResult, "Qdrant query result");
        List<RagSearchResult> results = new ArrayList<>();
        for (JsonElement value : points) {
            token.throwIfCancelled();
            JsonObject point = requireObject(value, "Qdrant query point");
            RagChunk chunk = parseChunk(requireObject(point.get("payload"), "Qdrant payload"));
            results.add(new RagSearchResult(chunk, point.get("score").getAsDouble(), chunk.citation()));
        }
        return List.copyOf(results);
    }

    private JsonObject serializePoint(RagEmbeddedChunk value, String generation) {
        JsonObject point = new JsonObject();
        point.addProperty("id", pointId(value.chunk().id(), generation));
        point.add("vector", floats(value.vector()));
        RagChunk chunk = value.chunk();
        JsonObject payload = new JsonObject();
        payload.addProperty("_kortty_kind", "chunk");
        payload.addProperty("_kortty_generation", generation);
        payload.addProperty("chunk_id", chunk.id());
        payload.addProperty("source_id", chunk.sourceId());
        payload.addProperty("document_path", chunk.documentPath());
        payload.addProperty("document_hash", chunk.documentHash());
        payload.addProperty("chunk_index", chunk.chunkIndex());
        payload.addProperty("start_offset", chunk.startOffset());
        payload.addProperty("end_offset", chunk.endOffset());
        payload.addProperty("text", chunk.text());
        JsonObject metadata = new JsonObject();
        chunk.metadata().forEach(metadata::addProperty);
        payload.add("metadata", metadata);
        point.add("payload", payload);
        return point;
    }

    private static JsonObject serializeManifest(String sourceId, String generation, int dimensions) {
        JsonObject point = new JsonObject();
        point.addProperty("id", manifestPointId(sourceId));
        float[] markerVector = new float[dimensions];
        markerVector[0] = 1.0f;
        point.add("vector", floats(markerVector));
        JsonObject payload = new JsonObject();
        payload.addProperty("_kortty_kind", "source_manifest");
        payload.addProperty("_kortty_generation", generation);
        payload.addProperty("source_id", sourceId);
        point.add("payload", payload);
        return point;
    }

    private static RagChunk parseChunk(JsonObject payload) throws IOException {
        JsonObject rawMetadata = payload.has("metadata") && payload.get("metadata").isJsonObject()
            ? payload.getAsJsonObject("metadata") : new JsonObject();
        java.util.Map<String, String> metadata = new java.util.LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : rawMetadata.entrySet()) {
            metadata.put(entry.getKey(), entry.getValue().getAsString());
        }
        try {
            return new RagChunk(
                payload.get("chunk_id").getAsString(), payload.get("source_id").getAsString(),
                payload.get("document_path").getAsString(), payload.get("document_hash").getAsString(),
                payload.get("chunk_index").getAsInt(), payload.get("start_offset").getAsInt(),
                payload.get("end_offset").getAsInt(), payload.get("text").getAsString(), metadata);
        } catch (RuntimeException error) {
            throw new IOException("Invalid korTTY payload in Qdrant", error);
        }
    }

    private Optional<String> activeGeneration(String collectionName, String sourceId) throws Exception {
        return Optional.ofNullable(activeGenerations(collectionName, Set.of(sourceId)).get(sourceId));
    }

    private Map<String, String> activeGenerations(String collectionName, Set<String> sourceIds) throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        JsonElement offset = null;
        do {
            JsonObject body = new JsonObject();
            body.add("filter", manifestFilter(sourceIds));
            body.addProperty("limit", SCROLL_PAGE_SIZE);
            body.addProperty("with_payload", true);
            body.addProperty("with_vector", false);
            if (offset != null && !offset.isJsonNull()) {
                body.add("offset", offset.deepCopy());
            }
            JsonObject response = requireSuccess(send("POST",
                collectionPath(collectionName) + "/points/scroll" + CONSISTENT_READ, body),
                "scroll Qdrant source manifests");
            JsonObject scroll = requireObject(response.get("result"), "Qdrant manifest scroll result");
            for (JsonElement value : requireArray(scroll.get("points"), "Qdrant manifest points")) {
                JsonObject payload = requireObject(
                    requireObject(value, "Qdrant manifest point").get("payload"), "Qdrant manifest payload");
                String sourceId = requiredPayloadString(payload, "source_id");
                String generation = requiredPayloadString(payload, "_kortty_generation");
                result.put(sourceId, generation);
            }
            offset = scroll.get("next_page_offset");
        } while (offset != null && !offset.isJsonNull());
        return Map.copyOf(result);
    }

    private int requiredDimensions(String collectionName) {
        Integer dimensions = collectionDimensions.get(collectionName);
        if (dimensions == null || dimensions <= 0) {
            throw new IllegalStateException("Qdrant collection must be initialized before replacing a source");
        }
        return dimensions;
    }

    private static String requiredPayloadString(JsonObject payload, String name) throws IOException {
        JsonElement value = payload.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().isBlank()) {
            throw new IOException("Invalid Qdrant manifest payload field: " + name);
        }
        return value.getAsString();
    }

    private static JsonObject manifestFilter(Set<String> sourceIds) {
        JsonArray must = new JsonArray();
        must.add(matchCondition("_kortty_kind", "source_manifest"));
        if (sourceIds != null && !sourceIds.isEmpty()) {
            must.add(matchCondition("source_id", sourceIds));
        }
        JsonObject filter = new JsonObject();
        filter.add("must", must);
        return filter;
    }

    private static JsonObject sourceGenerationFilter(String sourceId, String generation) {
        JsonArray must = new JsonArray();
        must.add(matchCondition("_kortty_kind", "chunk"));
        must.add(matchCondition("source_id", sourceId));
        must.add(matchCondition("_kortty_generation", generation));
        JsonObject filter = new JsonObject();
        filter.add("must", must);
        return filter;
    }

    private static JsonObject activeSourceFilter(Map<String, String> generations) {
        if (generations.size() == 1) {
            Map.Entry<String, String> only = generations.entrySet().iterator().next();
            return sourceGenerationFilter(only.getKey(), only.getValue());
        }
        JsonArray should = new JsonArray();
        generations.forEach((sourceId, generation) ->
            should.add(sourceGenerationFilter(sourceId, generation)));
        JsonObject filter = new JsonObject();
        filter.add("should", should);
        return filter;
    }

    private static JsonObject matchCondition(String key, String value) {
        JsonObject match = new JsonObject();
        match.addProperty("value", value);
        return fieldCondition(key, match);
    }

    private static JsonObject matchCondition(String key, Set<String> values) {
        JsonArray any = new JsonArray();
        values.forEach(any::add);
        JsonObject match = new JsonObject();
        match.add("any", any);
        return fieldCondition(key, match);
    }

    private static JsonObject fieldCondition(String key, JsonObject match) {
        JsonObject condition = new JsonObject();
        condition.addProperty("key", key);
        condition.add("match", match);
        return condition;
    }

    private void cleanupGenerationBestEffort(
        String collectionName,
        String sourceId,
        String generation,
        String description
    ) {
        try {
            JsonObject cleanup = new JsonObject();
            cleanup.add("filter", sourceGenerationFilter(sourceId, generation));
            requireSuccess(send("POST", collectionPath(collectionName) + "/points/delete" + ORDERED_WRITE,
                cleanup), "delete " + description);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while deleting {} for source {}", description, sourceId);
        } catch (Exception error) {
            logger.warn("Could not delete {} for source {}; the generation is not retrievable",
                description, sourceId, error);
        }
    }

    private void cleanupSourceBestEffort(String collectionName, String sourceId) {
        try {
            JsonObject cleanup = new JsonObject();
            cleanup.add("filter", sourceChunksFilter(sourceId));
            requireSuccess(send("POST", collectionPath(collectionName) + "/points/delete" + ORDERED_WRITE,
                cleanup), "delete deactivated Qdrant source chunks");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            logger.warn("Interrupted while deleting chunks for deactivated Qdrant source {}", sourceId);
        } catch (Exception error) {
            logger.warn("Could not delete chunks for deactivated Qdrant source {}; it remains inactive",
                sourceId, error);
        }
    }

    private static JsonObject sourceChunksFilter(String sourceId) {
        JsonArray must = new JsonArray();
        must.add(matchCondition("_kortty_kind", "chunk"));
        must.add(matchCondition("source_id", sourceId));
        JsonObject filter = new JsonObject();
        filter.add("must", must);
        return filter;
    }

    private Object sourceWriteLock(String collectionName, String sourceId) {
        int hash = 31 * collectionName.hashCode() + sourceId.hashCode();
        return sourceWriteLocks[Math.floorMod(hash, sourceWriteLocks.length)];
    }

    private static Object[] createSourceWriteLocks() {
        Object[] locks = new Object[64];
        for (int index = 0; index < locks.length; index++) {
            locks[index] = new Object();
        }
        return locks;
    }

    private Response send(String method, String path, JsonObject body) throws IOException, InterruptedException {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(baseUrl + path)).timeout(timeout)
            .header("Accept", "application/json");
        if (!apiKey.isBlank()) {
            request.header("api-key", apiKey);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        String payload = body != null ? body.toString() : "";
        request.method(method, body != null
            ? HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8)
            : HttpRequest.BodyPublishers.noBody());
        HttpResponse<InputStream> response = client.send(request.build(),
            HttpResponse.BodyHandlers.ofInputStream());
        return new Response(response.statusCode(), readResponseBody(response.body(), MAX_RESPONSE_BYTES));
    }

    static String readResponseBody(InputStream input, int maximumBytes) throws IOException {
        if (input == null || maximumBytes < 1) {
            throw new IOException("Qdrant response stream or size limit is invalid");
        }
        byte[] bytes;
        try (InputStream body = input) {
            bytes = body.readNBytes(maximumBytes + 1);
        }
        if (bytes.length > maximumBytes) {
            throw new IOException("Qdrant response exceeded the safe size limit");
        }
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private static JsonObject requireSuccess(Response response, String action) throws IOException {
        if (response.status < 200 || response.status >= 300) {
            String body = response.body == null ? "" : response.body;
            if (body.length() > 2_000) {
                body = body.substring(0, 2_000) + "…";
            }
            throw new IOException("Failed to " + action + " (HTTP " + response.status + "): " + body);
        }
        try {
            JsonElement parsed = JsonParser.parseString(response.body == null || response.body.isBlank()
                ? "{}" : response.body);
            return requireObject(parsed, "Qdrant response");
        } catch (RuntimeException error) {
            throw new IOException("Invalid JSON while attempting to " + action, error);
        }
    }

    private static String collectionPath(String name) {
        return "/collections/" + URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String pointId(String chunkId, String generation) {
        return UUID.nameUUIDFromBytes((generation + '\0' + chunkId).getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String manifestPointId(String sourceId) {
        return UUID.nameUUIDFromBytes(("kortty-source-manifest\0" + sourceId)
            .getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static JsonArray floats(float[] values) {
        validateVector(values);
        JsonArray array = new JsonArray();
        for (float value : values) {
            array.add(value);
        }
        return array;
    }

    private static void validateVector(float[] values) {
        if (values == null) {
            throw new IllegalArgumentException("Vector is required");
        }
        for (float value : values) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Vector contains non-finite value");
            }
        }
    }

    private static float[] parseVector(JsonElement value) throws IOException {
        JsonArray array = requireArray(value, "Qdrant vector");
        float[] result = new float[array.size()];
        for (int i = 0; i < result.length; i++) {
            result[i] = array.get(i).getAsFloat();
        }
        return result;
    }

    private static JsonElement path(JsonObject object, String... names) {
        JsonElement current = object;
        for (String name : names) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(name);
        }
        return current;
    }

    private static JsonObject requireObject(JsonElement value, String label) throws IOException {
        if (value == null || !value.isJsonObject()) {
            throw new IOException("Expected JSON object: " + label);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonElement value, String label) throws IOException {
        if (value == null || !value.isJsonArray()) {
            throw new IOException("Expected JSON array: " + label);
        }
        return value.getAsJsonArray();
    }

    private record Response(int status, String body) { }
}
