package de.kortty.telemetry;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Minimal client for the Aptabase events API
 * (https://github.com/aptabase/aptabase/wiki/How-to-build-your-own-SDK).
 * {@link #sendBatch} is synchronous — callers must stay off the JavaFX thread.
 */
final class AptabaseClient {

    static final String APP_KEY = "A-EU-6767357327";
    static final URI DEFAULT_ENDPOINT = URI.create("https://eu.aptabase.com/api/v0/events");
    static final String ENDPOINT_OVERRIDE_PROPERTY = "kortty.telemetry.endpoint";
    static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    static final int MAX_BATCH_SIZE = 25;

    enum SendResult { SENT, RETRYABLE_FAILURE, PERMANENT_FAILURE }

    private final HttpClient httpClient;
    private final URI endpoint;

    AptabaseClient() {
        this(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build(), resolveEndpoint());
    }

    AptabaseClient(HttpClient httpClient, URI endpoint) {
        this.httpClient = httpClient;
        this.endpoint = endpoint;
    }

    static URI resolveEndpoint() {
        String override = System.getProperty(ENDPOINT_OVERRIDE_PROPERTY);
        if (override != null && !override.isBlank()) {
            try {
                return URI.create(override.trim());
            } catch (IllegalArgumentException e) {
                // invalid override — fall back to the default endpoint
            }
        }
        return DEFAULT_ENDPOINT;
    }

    SendResult sendBatch(List<TelemetryEvent> events, SystemProps systemProps) {
        if (events.isEmpty()) {
            return SendResult.SENT;
        }
        HttpRequest request = HttpRequest.newBuilder()
            .uri(endpoint)
            .timeout(REQUEST_TIMEOUT)
            .header("App-Key", APP_KEY)
            .header("Content-Type", "application/json")
            .header("User-Agent", "KorTTY/" + systemProps.appVersion())
            .POST(HttpRequest.BodyPublishers.ofString(serialize(events, systemProps), StandardCharsets.UTF_8))
            .build();
        try {
            HttpResponse<Void> response = httpClient.send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                return SendResult.SENT;
            }
            if (status == 429 || status >= 500) {
                return SendResult.RETRYABLE_FAILURE;
            }
            return SendResult.PERMANENT_FAILURE;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.RETRYABLE_FAILURE;
        } catch (IOException | RuntimeException e) {
            return SendResult.RETRYABLE_FAILURE;
        }
    }

    static String serialize(List<TelemetryEvent> events, SystemProps systemProps) {
        JsonArray batch = new JsonArray();
        for (TelemetryEvent event : events) {
            JsonObject json = new JsonObject();
            json.addProperty("timestamp", event.timestamp);
            json.addProperty("sessionId", event.sessionId);
            json.addProperty("eventName", event.eventName);
            JsonObject system = new JsonObject();
            system.addProperty("isDebug", systemProps.isDebug());
            system.addProperty("locale", systemProps.locale());
            system.addProperty("osName", systemProps.osName());
            system.addProperty("osVersion", systemProps.osVersion());
            system.addProperty("appVersion", systemProps.appVersion());
            system.addProperty("sdkVersion", systemProps.sdkVersion());
            json.add("systemProps", system);
            JsonObject props = new JsonObject();
            for (Map.Entry<String, Object> entry : event.props.entrySet()) {
                Object value = entry.getValue();
                if (value instanceof Boolean booleanValue) {
                    props.addProperty(entry.getKey(), booleanValue);
                } else if (value instanceof Number numberValue) {
                    props.addProperty(entry.getKey(), numberValue);
                } else if (value != null) {
                    props.addProperty(entry.getKey(), value.toString());
                }
            }
            json.add("props", props);
            batch.add(json);
        }
        return batch.toString();
    }
}
