package de.kortty.rag;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

/** Transport-level tests for the generation manifest used on top of Qdrant points. */
public class HttpQdrantRestAdapterTest {

    @Test
    void rejectsResponseBodiesAboveConfiguredLimit() {
        IOException error = org.testng.Assert.expectThrows(IOException.class, () ->
            HttpQdrantRestAdapter.readResponseBody(
                new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)), 4));

        assertThat(error).hasMessageThat().contains("safe size limit");
    }

    @Test
    void failedStagingKeepsOldManifestAndDeletesOnlyThePartialGeneration() throws Exception {
        try (QdrantStub qdrant = new QdrantStub()) {
            QdrantVectorStore store = store(qdrant);
            store.initialize();
            store.replaceSource("source", List.of(embedded("old")), CancellationToken.NONE);
            String oldGeneration = qdrant.activeGeneration("source");

            // 129 points force two batches. The first new batch is persisted, the second fails.
            qdrant.failChunkUpsertRequest(qdrant.chunkUpsertRequests() + 2);
            List<RagEmbeddedChunk> replacement = new ArrayList<>();
            for (int index = 0; index < 129; index++) {
                replacement.add(embedded("new-" + index));
            }

            try {
                store.replaceSource("source", replacement, CancellationToken.NONE);
                throw new AssertionError("Expected the injected Qdrant staging failure");
            } catch (IOException expected) {
                assertThat(expected).hasMessageThat().contains("HTTP 503");
            }

            assertThat(qdrant.activeGeneration("source")).isEqualTo(oldGeneration);
            assertThat(store.chunksForSource("source").stream()
                .map(value -> value.chunk().id()).toList()).containsExactly("old");
            assertThat(qdrant.chunkPointCount("source")).isEqualTo(1);
            assertThat(qdrant.manifestUpsertRequests()).isEqualTo(1);
            assertThat(qdrant.filteredDeleteGenerations()).hasSize(1);
            assertThat(qdrant.filteredDeleteGenerations().getFirst()).isNotEqualTo(oldGeneration);
            qdrant.assertConsistencyParameters();
        }
    }

    @Test
    void failedPhysicalCleanupCannotExposeStaleChunksAndRemovalStillDeactivatesFirst() throws Exception {
        try (QdrantStub qdrant = new QdrantStub()) {
            QdrantVectorStore store = store(qdrant);
            store.initialize();
            store.replaceSource("source", List.of(embedded("old")), CancellationToken.NONE);

            qdrant.failNextFilteredDelete();
            store.replaceSource("source", List.of(embedded("new")), CancellationToken.NONE);

            // The old physical point remains after the injected cleanup failure, but both read
            // and retrieval filters are derived exclusively from the active manifest generation.
            assertThat(qdrant.chunkPointCount("source")).isEqualTo(2);
            assertThat(store.chunksForSource("source").stream()
                .map(value -> value.chunk().id()).toList()).containsExactly("new");
            assertThat(store.search(new float[] {1, 0}, 6, Set.of("source"), CancellationToken.NONE)
                .stream().map(result -> result.chunk().id()).toList()).containsExactly("new");

            qdrant.clearMutationEvents();
            qdrant.failNextFilteredDelete();
            store.removeSource("source", CancellationToken.NONE);

            assertThat(qdrant.mutationEvents()).containsExactly("manifest-delete", "filter-delete").inOrder();
            assertThat(qdrant.activeGeneration("source")).isNull();
            assertThat(store.chunksForSource("source")).isEmpty();
            assertThat(qdrant.chunkPointCount("source")).isEqualTo(2);

            // Retrying removal after the transient cleanup fault also sweeps stale generations
            // left by older failed replacements, while the source stays logically inactive.
            store.removeSource("source", CancellationToken.NONE);
            assertThat(qdrant.chunkPointCount("source")).isEqualTo(0);
            qdrant.assertConsistencyParameters();
        }
    }

    @Test
    void lostCommitResponseUsesTheManifestAsAuthoritativeCommitRecord() throws Exception {
        try (QdrantStub qdrant = new QdrantStub()) {
            QdrantVectorStore store = store(qdrant);
            store.initialize();
            store.replaceSource("source", List.of(embedded("old")), CancellationToken.NONE);

            qdrant.failNextManifestResponseAfterApplying();
            store.replaceSource("source", List.of(embedded("new")), CancellationToken.NONE);

            assertThat(store.chunksForSource("source").stream()
                .map(value -> value.chunk().id()).toList()).containsExactly("new");
            assertThat(qdrant.chunkPointCount("source")).isEqualTo(1);
            assertThat(qdrant.manifestUpsertRequests()).isEqualTo(2);
        }
    }

    @Test
    void rejectedCommitNeverDeletesThePossiblyReferencedStagedGeneration() throws Exception {
        try (QdrantStub qdrant = new QdrantStub()) {
            QdrantVectorStore store = store(qdrant);
            store.initialize();
            store.replaceSource("source", List.of(embedded("old")), CancellationToken.NONE);
            String oldGeneration = qdrant.activeGeneration("source");

            qdrant.failNextManifestBeforeApplying();
            try {
                store.replaceSource("source", List.of(embedded("new")), CancellationToken.NONE);
                throw new AssertionError("Expected the injected Qdrant commit failure");
            } catch (IOException expected) {
                assertThat(expected).hasMessageThat().contains("HTTP 503");
            }

            assertThat(qdrant.activeGeneration("source")).isEqualTo(oldGeneration);
            assertThat(store.chunksForSource("source").stream()
                .map(value -> value.chunk().id()).toList()).containsExactly("old");
            assertThat(qdrant.chunkPointCount("source")).isEqualTo(2);
        }
    }

    private static QdrantVectorStore store(QdrantStub qdrant) {
        return new QdrantVectorStore("knowledge", 2, "embedding-model",
            new HttpQdrantRestAdapter(qdrant.endpoint(), "test-api-key",
                HttpClient.newHttpClient(), Duration.ofSeconds(5)));
    }

    private static RagEmbeddedChunk embedded(String id) {
        return RagTestSupport.embedded(id, "source", id + ".md", 1, 0);
    }

    /**
     * Small stateful HTTP implementation of the Qdrant point contract used by the adapter. It
     * intentionally validates the generated REST filter tree rather than mocking adapter methods.
     */
    private static final class QdrantStub implements AutoCloseable {
        private final HttpServer server;
        private final Map<String, JsonObject> points = new LinkedHashMap<>();
        private final List<String> mutationQueries = new ArrayList<>();
        private final List<String> readQueries = new ArrayList<>();
        private final List<String> filteredDeleteGenerations = new ArrayList<>();
        private final List<String> mutationEvents = new ArrayList<>();
        private boolean collectionExists;
        private int dimensions;
        private int chunkUpsertRequests;
        private int manifestUpsertRequests;
        private int failedChunkUpsertRequest = -1;
        private boolean failNextFilteredDelete;
        private boolean failNextManifestBeforeApplying;
        private boolean failNextManifestResponseAfterApplying;

        private QdrantStub() throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }

        private URI endpoint() {
            return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        }

        private synchronized void handle(HttpExchange exchange) throws IOException {
            try {
                assertThat(exchange.getRequestHeaders().getFirst("api-key")).isEqualTo("test-api-key");
                String method = exchange.getRequestMethod();
                String path = exchange.getRequestURI().getPath();
                JsonObject body = readBody(exchange);
                if ("/collections/knowledge".equals(path)) {
                    handleCollection(exchange, method, body);
                } else if ("/collections/knowledge/points".equals(path) && "PUT".equals(method)) {
                    handleUpsert(exchange, body);
                } else if ("/collections/knowledge/points/scroll".equals(path) && "POST".equals(method)) {
                    recordReadQuery(exchange);
                    handleScroll(exchange, body);
                } else if ("/collections/knowledge/points/query".equals(path) && "POST".equals(method)) {
                    recordReadQuery(exchange);
                    handleQuery(exchange, body);
                } else if ("/collections/knowledge/points/delete".equals(path) && "POST".equals(method)) {
                    handleDelete(exchange, body);
                } else {
                    respond(exchange, 404, error("unexpected request " + method + " " + path));
                }
            } catch (Throwable error) {
                respond(exchange, 500, error(error.toString()));
            }
        }

        private void handleCollection(HttpExchange exchange, String method, JsonObject body) throws IOException {
            if ("GET".equals(method)) {
                if (!collectionExists) {
                    respond(exchange, 404, error("missing collection"));
                    return;
                }
                respond(exchange, 200, JsonParser.parseString("""
                    {"result":{"config":{"params":{"vectors":{"size":%d,"distance":"Cosine"}}}}}
                    """.formatted(dimensions)).getAsJsonObject());
                return;
            }
            if (!"PUT".equals(method)) {
                respond(exchange, 405, error("unsupported collection method"));
                return;
            }
            dimensions = body.getAsJsonObject("vectors").get("size").getAsInt();
            collectionExists = true;
            respond(exchange, 200, ok());
        }

        private void handleUpsert(HttpExchange exchange, JsonObject body) throws IOException {
            recordMutationQuery(exchange);
            JsonArray values = body.getAsJsonArray("points");
            boolean manifest = values.size() == 1 && "source_manifest".equals(
                values.get(0).getAsJsonObject().getAsJsonObject("payload").get("_kortty_kind").getAsString());
            if (manifest) {
                manifestUpsertRequests++;
                if (failNextManifestBeforeApplying) {
                    failNextManifestBeforeApplying = false;
                    respond(exchange, 503, error("injected rejected manifest commit"));
                    return;
                }
            } else {
                chunkUpsertRequests++;
                if (chunkUpsertRequests == failedChunkUpsertRequest) {
                    respond(exchange, 503, error("injected chunk upsert failure"));
                    return;
                }
            }
            for (JsonElement value : values) {
                JsonObject point = value.getAsJsonObject().deepCopy();
                points.put(point.get("id").getAsString(), point);
            }
            if (manifest && failNextManifestResponseAfterApplying) {
                failNextManifestResponseAfterApplying = false;
                respond(exchange, 503, error("injected lost manifest response"));
                return;
            }
            respond(exchange, 200, ok());
        }

        private void handleScroll(HttpExchange exchange, JsonObject body) throws IOException {
            JsonArray matches = new JsonArray();
            for (JsonObject point : points.values()) {
                if (matches(point.getAsJsonObject("payload"), body.getAsJsonObject("filter"))) {
                    matches.add(point.deepCopy());
                }
            }
            JsonObject result = new JsonObject();
            result.add("points", matches);
            result.add("next_page_offset", com.google.gson.JsonNull.INSTANCE);
            JsonObject response = ok();
            response.add("result", result);
            respond(exchange, 200, response);
        }

        private void handleQuery(HttpExchange exchange, JsonObject body) throws IOException {
            JsonArray matches = new JsonArray();
            int limit = body.get("limit").getAsInt();
            for (JsonObject point : points.values()) {
                if (matches.size() >= limit) {
                    break;
                }
                if (matches(point.getAsJsonObject("payload"), body.getAsJsonObject("filter"))) {
                    JsonObject result = point.deepCopy();
                    result.addProperty("score", 1.0);
                    matches.add(result);
                }
            }
            JsonObject queryResult = new JsonObject();
            queryResult.add("points", matches);
            JsonObject response = ok();
            response.add("result", queryResult);
            respond(exchange, 200, response);
        }

        private void handleDelete(HttpExchange exchange, JsonObject body) throws IOException {
            recordMutationQuery(exchange);
            if (body.has("points")) {
                mutationEvents.add("manifest-delete");
                for (JsonElement id : body.getAsJsonArray("points")) {
                    points.remove(id.getAsString());
                }
                respond(exchange, 200, ok());
                return;
            }
            mutationEvents.add("filter-delete");
            JsonObject filter = body.getAsJsonObject("filter");
            String generation = matchValue(filter, "_kortty_generation");
            if (generation != null) {
                filteredDeleteGenerations.add(generation);
            }
            if (failNextFilteredDelete) {
                failNextFilteredDelete = false;
                respond(exchange, 503, error("injected cleanup failure"));
                return;
            }
            points.entrySet().removeIf(entry -> matches(
                entry.getValue().getAsJsonObject("payload"), filter));
            respond(exchange, 200, ok());
        }

        private static boolean matches(JsonObject payload, JsonObject filter) {
            JsonArray must = filter.has("must") ? filter.getAsJsonArray("must") : new JsonArray();
            for (JsonElement condition : must) {
                if (!matchesCondition(payload, condition.getAsJsonObject())) {
                    return false;
                }
            }
            JsonArray mustNot = filter.has("must_not") ? filter.getAsJsonArray("must_not") : new JsonArray();
            for (JsonElement condition : mustNot) {
                if (matchesCondition(payload, condition.getAsJsonObject())) {
                    return false;
                }
            }
            JsonArray should = filter.has("should") ? filter.getAsJsonArray("should") : new JsonArray();
            if (!should.isEmpty()) {
                for (JsonElement condition : should) {
                    if (matchesCondition(payload, condition.getAsJsonObject())) {
                        return true;
                    }
                }
                return false;
            }
            return true;
        }

        private static boolean matchesCondition(JsonObject payload, JsonObject condition) {
            if (!condition.has("key")) {
                return matches(payload, condition);
            }
            String key = condition.get("key").getAsString();
            JsonElement payloadValue = payload.get(key);
            if (payloadValue == null || !payloadValue.isJsonPrimitive()) {
                return false;
            }
            JsonObject match = condition.getAsJsonObject("match");
            if (match.has("value")) {
                return payloadValue.equals(match.get("value"));
            }
            if (match.has("any")) {
                for (JsonElement candidate : match.getAsJsonArray("any")) {
                    if (payloadValue.equals(candidate)) {
                        return true;
                    }
                }
                return false;
            }
            throw new IllegalArgumentException("Unsupported Qdrant match condition: " + match);
        }

        private static String matchValue(JsonObject filter, String key) {
            for (JsonElement condition : filter.getAsJsonArray("must")) {
                JsonObject object = condition.getAsJsonObject();
                if (key.equals(object.get("key").getAsString())) {
                    return object.getAsJsonObject("match").get("value").getAsString();
                }
            }
            return null;
        }

        private static JsonObject readBody(HttpExchange exchange) throws IOException {
            byte[] bytes = exchange.getRequestBody().readAllBytes();
            return bytes.length == 0 ? new JsonObject()
                : JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }

        private void recordMutationQuery(HttpExchange exchange) {
            mutationQueries.add(exchange.getRequestURI().getRawQuery());
        }

        private void recordReadQuery(HttpExchange exchange) {
            readQueries.add(exchange.getRequestURI().getRawQuery());
        }

        private static JsonObject ok() {
            JsonObject response = new JsonObject();
            response.addProperty("status", "ok");
            return response;
        }

        private static JsonObject error(String message) {
            JsonObject response = new JsonObject();
            response.addProperty("status", "error");
            response.addProperty("message", message);
            return response;
        }

        private static void respond(HttpExchange exchange, int status, JsonObject body) throws IOException {
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        private synchronized String activeGeneration(String sourceId) {
            return points.values().stream()
                .map(point -> point.getAsJsonObject("payload"))
                .filter(payload -> "source_manifest".equals(payload.get("_kortty_kind").getAsString()))
                .filter(payload -> sourceId.equals(payload.get("source_id").getAsString()))
                .map(payload -> payload.get("_kortty_generation").getAsString())
                .findFirst().orElse(null);
        }

        private synchronized long chunkPointCount(String sourceId) {
            return points.values().stream()
                .map(point -> point.getAsJsonObject("payload"))
                .filter(payload -> "chunk".equals(payload.get("_kortty_kind").getAsString()))
                .filter(payload -> sourceId.equals(payload.get("source_id").getAsString()))
                .count();
        }

        private synchronized void failChunkUpsertRequest(int request) {
            failedChunkUpsertRequest = request;
        }

        private synchronized int chunkUpsertRequests() {
            return chunkUpsertRequests;
        }

        private synchronized int manifestUpsertRequests() {
            return manifestUpsertRequests;
        }

        private synchronized void failNextFilteredDelete() {
            failNextFilteredDelete = true;
        }

        private synchronized void failNextManifestResponseAfterApplying() {
            failNextManifestResponseAfterApplying = true;
        }

        private synchronized void failNextManifestBeforeApplying() {
            failNextManifestBeforeApplying = true;
        }

        private synchronized List<String> filteredDeleteGenerations() {
            return List.copyOf(filteredDeleteGenerations);
        }

        private synchronized void clearMutationEvents() {
            mutationEvents.clear();
        }

        private synchronized List<String> mutationEvents() {
            return List.copyOf(mutationEvents);
        }

        private synchronized void assertConsistencyParameters() {
            assertThat(mutationQueries).isNotEmpty();
            assertThat(mutationQueries.stream().allMatch(query -> query != null)).isTrue();
            assertThat(mutationQueries.stream().allMatch("wait=true&ordering=strong"::equals)).isTrue();
            assertThat(readQueries).isNotEmpty();
            assertThat(readQueries.stream().allMatch("consistency=all"::equals)).isTrue();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
