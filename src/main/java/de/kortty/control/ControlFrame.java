package de.kortty.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import java.util.Map;
import java.util.Objects;

/**
 * One outbound frame: a response when {@link #eventMethod()} is null, otherwise an event
 * notification.
 *
 * <p>Pure, any thread. {@link #toLine()} serialises the whole frame in memory before anything is
 * written, so a partially written frame is impossible; the per-connection writer thread is then the
 * only code that touches the socket, which is why an event can never interleave inside a response.
 *
 * @param id the correlation id of a response; null for an event, and JSON null for a framing error
 *     that could not be correlated
 * @param result the successful result, or null
 * @param error the failure, or null
 * @param eventMethod the notification method name, or null for a response
 * @param eventParams the notification params; only meaningful with {@code eventMethod}
 */
public record ControlFrame(JsonElement id, JsonElement result, ControlApiError error,
                           String eventMethod, JsonObject eventParams) {

    private static final String JSONRPC_KEY = "jsonrpc";

    private static final String JSONRPC_VERSION = "2.0";

    /** A successful response. */
    public static ControlFrame result(JsonElement id, JsonElement result) {
        return new ControlFrame(id == null ? JsonNull.INSTANCE : id,
            result == null ? JsonNull.INSTANCE : result, null, null, null);
    }

    /** A failed response; {@code id} may be JSON null when the request could not be correlated. */
    public static ControlFrame error(JsonElement id, ControlApiError error) {
        return new ControlFrame(id == null ? JsonNull.INSTANCE : id, null,
            Objects.requireNonNull(error, "error"), null, null);
    }

    /** An event notification, which carries no id. */
    public static ControlFrame event(String method, JsonObject params) {
        return new ControlFrame(null, null, null, Objects.requireNonNull(method, "method"),
            params == null ? new JsonObject() : params);
    }

    private boolean isEvent() {
        return eventMethod != null;
    }

    /**
     * The compact JSON-RPC 2.0 representation plus a single trailing {@code '\n'}. Gson escapes any
     * newline inside a string value, so the returned text contains exactly one line break, at the end.
     */
    public String toLine() {
        JsonObject frame = new JsonObject();
        frame.addProperty(JSONRPC_KEY, JSONRPC_VERSION);
        if (isEvent()) {
            frame.addProperty("method", eventMethod);
            frame.add("params", eventParams == null ? new JsonObject() : eventParams);
        } else {
            frame.add("id", id == null ? JsonNull.INSTANCE : id);
            if (error != null) {
                frame.add("error", errorObject(error));
            } else {
                frame.add("result", result == null ? JsonNull.INSTANCE : result);
            }
        }
        return ControlJson.gson().toJson(frame) + "\n";
    }

    private static JsonObject errorObject(ControlApiError error) {
        JsonObject object = new JsonObject();
        object.addProperty("code", error.code());
        object.addProperty("message", error.message());
        JsonObject data = new JsonObject();
        for (Map.Entry<String, Object> entry : error.data().entrySet()) {
            data.add(entry.getKey(), ControlJson.toTree(entry.getValue()));
        }
        object.add("data", data);
        return object;
    }
}
