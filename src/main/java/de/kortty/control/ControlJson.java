package de.kortty.control;

import com.google.gson.FieldNamingPolicy;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The single Gson instance of the control API and every typed parameter accessor.
 *
 * <p>Untrusted input is <strong>never</strong> reflectively bound: {@link #parseObjectStrict(String)}
 * reads one element in {@link Strictness#STRICT} mode and every verb then reads its parameters field
 * by field through the accessors below, each of which reports a precise
 * {@link ControlErrorCode#INVALID_PARAMS}. Reflection is used only in the outbound direction, for the
 * package's own value records.
 *
 * <p>Pure, any thread.
 */
public final class ControlJson {

    private static final Gson GSON = new GsonBuilder()
        .setFieldNamingPolicy(FieldNamingPolicy.LOWER_CASE_WITH_UNDERSCORES)
        .serializeNulls()
        .disableHtmlEscaping()
        .create();

    private ControlJson() {
    }

    /** The one Gson instance: snake_case field names, explicit nulls, no HTML escaping. */
    public static Gson gson() {
        return GSON;
    }

    /**
     * Parses exactly one JSON object from one line.
     *
     * @throws ControlApiException {@link ControlErrorCode#PARSE_ERROR} when the line is not a single
     *     well-formed JSON document in strict mode (trailing content, comments, {@code NaN}, unquoted
     *     names), or {@link ControlErrorCode#INVALID_REQUEST} when it is valid JSON but not an object
     *     — a batch array is the shape this rejects most often
     */
    public static JsonObject parseObjectStrict(String line) throws ControlApiException {
        if (line == null || line.isBlank()) {
            throw new ControlApiException(ControlErrorCode.PARSE_ERROR, "Empty request line");
        }
        JsonElement element;
        try (JsonReader reader = new JsonReader(new StringReader(line))) {
            reader.setStrictness(Strictness.STRICT);
            element = JsonParser.parseReader(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) {
                throw new ControlApiException(ControlErrorCode.PARSE_ERROR,
                    "Trailing content after the JSON document");
            }
        } catch (JsonParseException | NumberFormatException | IOException e) {
            throw new ControlApiException(ControlErrorCode.PARSE_ERROR, "Malformed JSON: " + e.getMessage(), e);
        }
        if (element == null || element.isJsonNull()) {
            throw new ControlApiException(ControlErrorCode.PARSE_ERROR, "The JSON document was null");
        }
        if (!element.isJsonObject()) {
            throw new ControlApiException(ControlErrorCode.INVALID_REQUEST,
                "Expected one JSON object per line; batch arrays are not supported");
        }
        return element.getAsJsonObject();
    }

    /** A required string parameter. */
    public static String requireString(JsonObject p, String name) throws ControlApiException {
        JsonElement value = member(p, name);
        if (value == null) {
            throw invalidParams(name, "is required");
        }
        return asString(value, name);
    }

    /** An optional string parameter; an absent or JSON-null member yields {@code fallback}. */
    public static String optString(JsonObject p, String name, String fallback) throws ControlApiException {
        JsonElement value = member(p, name);
        return value == null ? fallback : asString(value, name);
    }

    /**
     * An optional int parameter. An absent member yields {@code fallback} clamped into
     * {@code [min, max]}, so a caller's default can never violate the declared range; a member that
     * is present but outside the range is rejected with {@link ControlErrorCode#INVALID_PARAMS}.
     */
    public static int optInt(JsonObject p, String name, int fallback, int min, int max)
            throws ControlApiException {
        return (int) optLong(p, name, fallback, min, max);
    }

    /** The {@code long} form of {@link #optInt(JsonObject, String, int, int, int)}. */
    public static long optLong(JsonObject p, String name, long fallback, long min, long max)
            throws ControlApiException {
        JsonElement value = member(p, name);
        if (value == null) {
            return Math.max(min, Math.min(max, fallback));
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw invalidParams(name, "must be a number");
        }
        long number;
        try {
            number = value.getAsJsonPrimitive().getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw invalidParams(name, "must be a whole number between " + min + " and " + max);
        }
        if (number < min || number > max) {
            throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "Parameter '" + name + "' must be between " + min + " and " + max,
                Map.of("param", name, "min", min, "max", max, "value", number));
        }
        return number;
    }

    /** An optional boolean parameter. */
    public static boolean optBool(JsonObject p, String name, boolean fallback) throws ControlApiException {
        JsonElement value = member(p, name);
        if (value == null) {
            return fallback;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean()) {
            throw invalidParams(name, "must be a boolean");
        }
        return value.getAsBoolean();
    }

    /**
     * An optional list-of-strings parameter. A JSON array of strings becomes that list; a bare string
     * becomes a single-element list (verbs that accept a space-separated form, such as
     * {@code pane.send_keys}, then run it through {@link ControlKeyTable#split(String)}); an absent or
     * JSON-null member becomes an empty list.
     */
    public static List<String> optStringList(JsonObject p, String name) throws ControlApiException {
        JsonElement value = member(p, name);
        if (value == null) {
            return List.of();
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            return List.of(value.getAsString());
        }
        if (!value.isJsonArray()) {
            throw invalidParams(name, "must be an array of strings or a string");
        }
        JsonArray array = value.getAsJsonArray();
        List<String> result = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) {
                throw invalidParams(name, "must contain strings only");
            }
            result.add(item.getAsString());
        }
        return List.copyOf(result);
    }

    /** Serialises one of this package's value records into a JSON tree. */
    public static JsonElement toTree(Object value) {
        return GSON.toJsonTree(value);
    }

    private static JsonElement member(JsonObject p, String name) {
        if (p == null) {
            return null;
        }
        JsonElement value = p.get(name);
        return value == null || value.isJsonNull() ? null : value;
    }

    private static String asString(JsonElement value, String name) throws ControlApiException {
        if (!value.isJsonPrimitive()) {
            throw invalidParams(name, "must be a string");
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        if (!primitive.isString()) {
            throw invalidParams(name, "must be a string");
        }
        return primitive.getAsString();
    }

    private static ControlApiException invalidParams(String name, String detail) {
        return new ControlApiException(ControlErrorCode.INVALID_PARAMS,
            "Parameter '" + name + "' " + detail, Map.of("param", name));
    }
}
