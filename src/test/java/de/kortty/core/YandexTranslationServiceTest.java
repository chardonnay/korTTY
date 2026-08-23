package de.kortty.core;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.google.common.truth.Truth.assertThat;

class YandexTranslationServiceTest {

    @Test
    void sendsCloudTranslateV2RequestWithApiKeyAuth() throws IOException {
        try (StubTranslate stub = StubTranslate.replying(
            200, "{\"translations\":[{\"text\":\"Hallo\"},{\"text\":\"Speichern\"}]}")) {

            List<String> result = new YandexTranslationService("AQVNabc", stub.baseUrl())
                .translateBatch(List.of("Hello", "Save"), "en", "de");

            assertThat(result).containsExactly("Hallo", "Speichern").inOrder();
            assertThat(stub.authorizations).containsExactly("Api-Key AQVNabc");

            JsonObject body = JsonParser.parseString(stub.bodies.get(0)).getAsJsonObject();
            assertThat(body.get("targetLanguageCode").getAsString()).isEqualTo("de");
            assertThat(body.get("sourceLanguageCode").getAsString()).isEqualTo("en");
            assertThat(body.get("format").getAsString()).isEqualTo("PLAIN_TEXT");
            assertThat(body.getAsJsonArray("texts").size()).isEqualTo(2);
            // An API key already implies its service account's folder; sending one would be rejected.
            assertThat(body.has("folderId")).isFalse();
        }
    }

    @Test
    void usesBearerAuthForIamTokenAndSendsSuppliedFolderId() throws IOException {
        try (StubTranslate stub = StubTranslate.replying(
            200, "{\"translations\":[{\"text\":\"Hallo\"}]}")) {

            String translated = new YandexTranslationService("t1.9euelZq", stub.baseUrl(), "b1gfolder")
                .translate("Hello", "en", "de");

            assertThat(translated).isEqualTo("Hallo");
            assertThat(stub.authorizations).containsExactly("Bearer t1.9euelZq");
            assertThat(JsonParser.parseString(stub.bodies.get(0)).getAsJsonObject()
                .get("folderId").getAsString()).isEqualTo("b1gfolder");
        }
    }

    @Test
    void reducesLocaleCodesToTheIso639CodesTheApiAccepts() throws IOException {
        try (StubTranslate stub = StubTranslate.replying(
            200, "{\"translations\":[{\"text\":\"Ola\"}]}")) {

            new YandexTranslationService("AQVNabc", stub.baseUrl())
                .translate("Hello", "en_US", "pt-BR");

            JsonObject body = JsonParser.parseString(stub.bodies.get(0)).getAsJsonObject();
            assertThat(body.get("targetLanguageCode").getAsString()).isEqualTo("pt");
            assertThat(body.get("sourceLanguageCode").getAsString()).isEqualTo("en");
        }
    }

    @Test
    void splitsBatchesOnTheCharacterBudgetNotJustTheItemCount() throws IOException {
        try (StubTranslate stub = StubTranslate.replying(200, null)) {
            // Three 4,000-character strings cannot share one 10,000-character request.
            List<String> texts = List.of("a".repeat(4000), "b".repeat(4000), "c".repeat(4000));

            List<String> result = new YandexTranslationService("AQVNabc", stub.baseUrl())
                .translateBatch(texts, "en", "de");

            assertThat(result).hasSize(3);
            assertThat(stub.bodies).hasSize(2);
            assertThat(JsonParser.parseString(stub.bodies.get(0)).getAsJsonObject()
                .getAsJsonArray("texts").size()).isEqualTo(2);
            assertThat(JsonParser.parseString(stub.bodies.get(1)).getAsJsonObject()
                .getAsJsonArray("texts").size()).isEqualTo(1);
        }
    }

    @Test
    void returnsNullOnApiErrorRatherThanPartialResults() throws IOException {
        try (StubTranslate stub = StubTranslate.replying(
            401, "{\"code\":16,\"message\":\"Unauthenticated\"}")) {

            assertThat(new YandexTranslationService("AQVNabc", stub.baseUrl())
                .translateBatch(List.of("Hello"), "en", "de")).isNull();
        }
    }

    @Test
    void returnsNullWhenTheResponseDropsTranslations() throws IOException {
        try (StubTranslate stub = StubTranslate.replying(
            200, "{\"translations\":[{\"text\":\"Hallo\"}]}")) {

            assertThat(new YandexTranslationService("AQVNabc", stub.baseUrl())
                .translateBatch(List.of("Hello", "Save"), "en", "de")).isNull();
        }
    }

    @Test
    void rejectsAnEmptyCredentialWithoutCallingTheApi() throws IOException {
        try (StubTranslate stub = StubTranslate.replying(200, "{\"translations\":[]}")) {
            assertThat(new YandexTranslationService("  ", stub.baseUrl())
                .translateBatch(List.of("Hello"), "en", "de")).isNull();
            assertThat(stub.bodies).isEmpty();
        }
    }

    /**
     * A URL stored back when the v1.5 API was current must not survive the migration, or every
     * request would go to a host that no longer serves translations.
     */
    @Test
    void ignoresAStoredV15BaseUrl() {
        assertThat(YandexTranslationService.resolveBaseUrl("https://translate.yandex.net/api/v1.5/tr.json"))
            .isEqualTo(YandexTranslationService.DEFAULT_BASE_URL);
        assertThat(YandexTranslationService.resolveBaseUrl(null))
            .isEqualTo(YandexTranslationService.DEFAULT_BASE_URL);
        assertThat(YandexTranslationService.resolveBaseUrl("https://proxy.example/translate/v2/"))
            .isEqualTo("https://proxy.example/translate/v2");
    }

    /** Local stub of POST {base}/translate that records what the service sent. */
    private static final class StubTranslate implements AutoCloseable {
        private final HttpServer server;
        private final List<String> bodies = new CopyOnWriteArrayList<>();
        private final List<String> authorizations = new CopyOnWriteArrayList<>();

        private static StubTranslate replying(int status, String body) throws IOException {
            return new StubTranslate(status, body);
        }

        private StubTranslate(int status, String cannedBody) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/translate/v2/translate", exchange -> {
                authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
                String requestBody = new String(
                    exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                bodies.add(requestBody);

                String reply = cannedBody != null ? cannedBody : echo(requestBody);
                byte[] bytes = reply.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
                exchange.close();
            });
            server.start();
        }

        /** Echoes one translation per requested text so batch splitting stays observable. */
        private static String echo(String requestBody) {
            int count = JsonParser.parseString(requestBody).getAsJsonObject()
                .getAsJsonArray("texts").size();
            List<String> entries = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                entries.add("{\"text\":\"t" + i + "\"}");
            }
            return "{\"translations\":[" + String.join(",", entries) + "]}";
        }

        private String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/translate/v2";
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
