package de.kortty.telemetry;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-disk overflow buffer for events that could not be sent (Aptabase offline).
 * Events are persisted so they survive an app restart and are re-sent once a
 * connection is available. The file holds the same anonymized events as the
 * in-memory queue — no additional data. Written atomically.
 */
final class TelemetrySpool {

    private static final Logger logger = LoggerFactory.getLogger(TelemetrySpool.class);
    private static final int SPOOL_VERSION = 1;

    private final Path file;

    TelemetrySpool(Path configDir) {
        this.file = configDir.resolve(TelemetryService.SPOOL_FILE);
    }

    /** Atomically replaces the spool with the given events (best-effort; never throws). */
    synchronized void write(List<TelemetryEvent> events) {
        try {
            JsonObject root = new JsonObject();
            root.addProperty("v", SPOOL_VERSION);
            JsonArray array = new JsonArray();
            for (TelemetryEvent event : events) {
                JsonObject json = new JsonObject();
                json.addProperty("t", event.timestamp);
                json.addProperty("s", event.sessionId);
                json.addProperty("e", event.eventName);
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
                json.add("p", props);
                json.addProperty("a", event.sendAttempts);
                array.add(json);
            }
            root.add("events", array);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, root.toString(), StandardCharsets.UTF_8);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException atomicUnsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Telemetry spool write failed: {}", e.toString());
        }
    }

    /** Reads and removes the spool, returning its events (empty on missing/corrupt file). */
    synchronized List<TelemetryEvent> readAndDelete() {
        List<TelemetryEvent> events = new ArrayList<>();
        try {
            if (!Files.exists(file)) {
                return events;
            }
            String content = Files.readString(file, StandardCharsets.UTF_8);
            Files.deleteIfExists(file);
            JsonElement parsed = JsonParser.parseString(content);
            if (!parsed.isJsonObject()) {
                return events;
            }
            JsonObject root = parsed.getAsJsonObject();
            if (!root.has("events") || !root.get("events").isJsonArray()) {
                return events;
            }
            for (JsonElement element : root.getAsJsonArray("events")) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject json = element.getAsJsonObject();
                String timestamp = optString(json, "t");
                String sessionId = optString(json, "s");
                String eventName = optString(json, "e");
                if (timestamp == null || sessionId == null || eventName == null) {
                    continue;
                }
                Map<String, Object> props = new LinkedHashMap<>();
                if (json.has("p") && json.get("p").isJsonObject()) {
                    for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("p").entrySet()) {
                        JsonElement value = entry.getValue();
                        if (!value.isJsonPrimitive()) {
                            continue;
                        }
                        JsonPrimitive primitive = value.getAsJsonPrimitive();
                        if (primitive.isBoolean()) {
                            props.put(entry.getKey(), primitive.getAsBoolean());
                        } else if (primitive.isNumber()) {
                            props.put(entry.getKey(), primitive.getAsNumber());
                        } else {
                            props.put(entry.getKey(), primitive.getAsString());
                        }
                    }
                }
                TelemetryEvent event = new TelemetryEvent(timestamp, sessionId, eventName, Map.copyOf(props));
                event.sendAttempts = json.has("a") && json.get("a").isJsonPrimitive()
                    ? safeInt(json.get("a").getAsString()) : 0;
                events.add(event);
            }
        } catch (IOException | RuntimeException e) {
            logger.debug("Telemetry spool read failed: {}", e.toString());
            delete();
        }
        return events;
    }

    synchronized void delete() {
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            logger.debug("Telemetry spool delete failed: {}", e.toString());
        }
    }

    private static String optString(JsonObject object, String key) {
        return object.has(key) && object.get(key).isJsonPrimitive() ? object.get(key).getAsString() : null;
    }

    private static int safeInt(String value) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
