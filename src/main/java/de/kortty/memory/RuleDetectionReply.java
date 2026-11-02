package de.kortty.memory;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.util.Optional;

/**
 * Reads the reply of the rule detection call: one JSON object with {@code isRule}, {@code rule}, {@code topic} and
 * {@code scopeHint}. A Markdown fence or text around the object is tolerated. Anything else gives nothing, so the
 * detection stays silent.
 */
public final class RuleDetectionReply {

    /**
     * What the model said about one message: whether it asks for a lasting rule, the rule as proposed, its topic and the
     * scope hint. Missing fields read as empty strings and {@code false}.
     */
    public record Candidate(boolean isRule, String rule, String topic, String scopeHint) {

        public Candidate {
            rule = rule == null ? "" : rule;
            topic = topic == null ? "" : topic;
            scopeHint = scopeHint == null ? "" : scopeHint;
        }
    }

    private RuleDetectionReply() {
    }

    /** The candidate the reply describes, or empty when the reply is not a JSON object. */
    public static Optional<Candidate> parse(String content) {
        String object = objectText(content);
        if (object == null) {
            return Optional.empty();
        }
        try {
            JsonElement element = JsonParser.parseString(object);
            if (!element.isJsonObject()) {
                return Optional.empty();
            }
            JsonObject json = element.getAsJsonObject();
            return Optional.of(new Candidate(
                booleanField(json, "isRule"),
                textField(json, "rule"),
                textField(json, "topic"),
                textField(json, "scopeHint")));
        } catch (RuntimeException malformed) {
            return Optional.empty();
        }
    }

    /** The text from the first opening brace to the last closing one, or {@code null} when there is none. */
    private static String objectText(String content) {
        if (content == null) {
            return null;
        }
        String text = content.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start < 0 || end <= start ? null : text.substring(start, end + 1);
    }

    private static boolean booleanField(JsonObject json, String name) {
        JsonElement value = json.get(name);
        if (value == null || !value.isJsonPrimitive()) {
            return false;
        }
        JsonPrimitive primitive = value.getAsJsonPrimitive();
        return primitive.isBoolean()
            ? primitive.getAsBoolean()
            : "true".equalsIgnoreCase(primitive.getAsString().strip());
    }

    private static String textField(JsonObject json, String name) {
        JsonElement value = json.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()
            ? value.getAsString()
            : "";
    }
}
