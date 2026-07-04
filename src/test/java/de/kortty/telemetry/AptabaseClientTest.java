package de.kortty.telemetry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpServer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

class AptabaseClientTest {

    private static final SystemProps SYSTEM_PROPS =
        new SystemProps(true, "de-DE", "macOS", "15.5", "2.3.3", "kortty-aptabase@1.0.0");

    @Test
    void serializesEventsAccordingToAptabaseSpec() {
        TelemetryEvent event = new TelemetryEvent(
            "2026-07-04T10:00:00Z",
            "170000000012345678",
            "tool_opened",
            Map.of("tool", "snippet_manager", "open_tabs", 3, "enabled", true));

        String json = AptabaseClient.serialize(List.of(event), SYSTEM_PROPS);

        JsonArray batch = JsonParser.parseString(json).getAsJsonArray();
        assertThat(batch.size()).isEqualTo(1);
        JsonObject first = batch.get(0).getAsJsonObject();
        assertThat(first.get("timestamp").getAsString()).isEqualTo("2026-07-04T10:00:00Z");
        assertThat(first.get("sessionId").getAsString()).isEqualTo("170000000012345678");
        assertThat(first.get("eventName").getAsString()).isEqualTo("tool_opened");

        JsonObject systemProps = first.getAsJsonObject("systemProps");
        assertThat(systemProps.get("isDebug").getAsBoolean()).isTrue();
        assertThat(systemProps.get("locale").getAsString()).isEqualTo("de-DE");
        assertThat(systemProps.get("osName").getAsString()).isEqualTo("macOS");
        assertThat(systemProps.get("osVersion").getAsString()).isEqualTo("15.5");
        assertThat(systemProps.get("appVersion").getAsString()).isEqualTo("2.3.3");
        assertThat(systemProps.get("sdkVersion").getAsString()).isEqualTo("kortty-aptabase@1.0.0");

        JsonObject props = first.getAsJsonObject("props");
        assertThat(props.get("tool").getAsString()).isEqualTo("snippet_manager");
        assertThat(props.get("open_tabs").getAsJsonPrimitive().isNumber()).isTrue();
        assertThat(props.get("open_tabs").getAsInt()).isEqualTo(3);
        assertThat(props.get("enabled").getAsJsonPrimitive().isBoolean()).isTrue();
    }

    @Test
    void mapsHttpStatusToSendResult() throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger nextStatus = new AtomicInteger(200);
        List<String> appKeys = new CopyOnWriteArrayList<>();
        server.createContext("/api/v0/events", exchange -> {
            appKeys.add(exchange.getRequestHeaders().getFirst("App-Key"));
            exchange.sendResponseHeaders(nextStatus.get(), -1);
            exchange.close();
        });
        server.start();
        try {
            URI endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/api/v0/events");
            AptabaseClient client = new AptabaseClient(HttpClient.newHttpClient(), endpoint);
            List<TelemetryEvent> events =
                List.of(new TelemetryEvent("2026-07-04T10:00:00Z", "1", "app_started", Map.of()));

            assertThat(client.sendBatch(events, SYSTEM_PROPS)).isEqualTo(AptabaseClient.SendResult.SENT);
            nextStatus.set(500);
            assertThat(client.sendBatch(events, SYSTEM_PROPS)).isEqualTo(AptabaseClient.SendResult.RETRYABLE_FAILURE);
            nextStatus.set(429);
            assertThat(client.sendBatch(events, SYSTEM_PROPS)).isEqualTo(AptabaseClient.SendResult.RETRYABLE_FAILURE);
            nextStatus.set(400);
            assertThat(client.sendBatch(events, SYSTEM_PROPS)).isEqualTo(AptabaseClient.SendResult.PERMANENT_FAILURE);

            assertThat(appKeys).containsExactly(
                AptabaseClient.APP_KEY, AptabaseClient.APP_KEY, AptabaseClient.APP_KEY, AptabaseClient.APP_KEY);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void reportsRetryableFailureWhenServerUnreachable() {
        AptabaseClient client = new AptabaseClient(
            HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1/api/v0/events"));
        List<TelemetryEvent> events =
            List.of(new TelemetryEvent("2026-07-04T10:00:00Z", "1", "app_started", Map.of()));

        assertThat(client.sendBatch(events, SYSTEM_PROPS)).isEqualTo(AptabaseClient.SendResult.RETRYABLE_FAILURE);
    }

    @Test
    void sendsEmptyBatchAsNoOp() {
        AptabaseClient client = new AptabaseClient(
            HttpClient.newHttpClient(), URI.create("http://127.0.0.1:1/api/v0/events"));

        assertThat(client.sendBatch(List.of(), SYSTEM_PROPS)).isEqualTo(AptabaseClient.SendResult.SENT);
    }
}
