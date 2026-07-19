package de.kortty.ai.catalog;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import de.kortty.ai.catalog.AiModelPromptCatalog.PromptFamily;
import de.kortty.ai.catalog.AiModelPromptCatalog.Recommendation;
import de.kortty.ai.catalog.AiModelPromptCatalog.Role;
import de.kortty.model.AiPromptPreset;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Strict schema-v1 parser for an already signature-verified model/prompt catalog. */
public final class AiCatalogCodec {

    private static final Set<String> ROOT_FIELDS = Set.of(
        "schemaVersion", "sequence", "catalogVersion", "recommendations", "promptFamilies");
    private static final Set<String> RECOMMENDATION_FIELDS = Set.of(
        "id", "modelId", "revision", "quantization", "roles", "minimumSystemMemoryBytes", "preference");
    private static final Set<String> PROMPT_FAMILY_FIELDS = Set.of(
        "id", "preset", "modelNameContains", "priority");

    public AiModelPromptCatalog parse(byte[] jsonBytes) throws IOException {
        if (jsonBytes == null || jsonBytes.length == 0) {
            throw new IOException("AI catalog is empty.");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(decodeUtf8(jsonBytes));
        } catch (JsonParseException | IllegalStateException e) {
            throw new IOException("AI catalog is not valid JSON.", e);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("AI catalog root must be an object.");
        }
        try {
            return parseObject(parsed.getAsJsonObject());
        } catch (IOException e) {
            throw e;
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new IOException("AI catalog failed schema validation: " + e.getMessage(), e);
        }
    }

    private static AiModelPromptCatalog parseObject(JsonObject root) throws IOException {
        requireOnlyFields(root, ROOT_FIELDS, "catalog root");
        int schemaVersion = requiredInt(root, "schemaVersion");
        if (schemaVersion != AiModelPromptCatalog.SCHEMA_VERSION) {
            throw new IOException("Unsupported AI catalog schema version: " + schemaVersion);
        }
        long sequence = requiredLong(root, "sequence");
        String catalogVersion = requiredString(root, "catalogVersion");
        JsonArray recommendationArray = requiredArray(root, "recommendations");
        JsonArray promptFamilyArray = requiredArray(root, "promptFamilies");

        List<Recommendation> recommendations = new ArrayList<>();
        for (JsonElement element : recommendationArray) {
            JsonObject object = requiredObject(element, "recommendation");
            requireOnlyFields(object, RECOMMENDATION_FIELDS, "recommendation");
            EnumSet<Role> roles = EnumSet.noneOf(Role.class);
            for (String role : stringArray(object, "roles")) {
                try {
                    roles.add(Role.valueOf(role));
                } catch (IllegalArgumentException e) {
                    throw new IOException("Unknown AI catalog recommendation role: " + role, e);
                }
            }
            recommendations.add(new Recommendation(
                requiredString(object, "id"),
                requiredString(object, "modelId"),
                optionalString(object, "revision"),
                requiredString(object, "quantization"),
                roles,
                requiredLong(object, "minimumSystemMemoryBytes"),
                requiredInt(object, "preference")));
        }

        List<PromptFamily> promptFamilies = new ArrayList<>();
        for (JsonElement element : promptFamilyArray) {
            JsonObject object = requiredObject(element, "prompt family");
            requireOnlyFields(object, PROMPT_FAMILY_FIELDS, "prompt family");
            AiPromptPreset preset;
            String presetName = requiredString(object, "preset");
            try {
                preset = AiPromptPreset.valueOf(presetName);
            } catch (IllegalArgumentException e) {
                throw new IOException("Unknown AI catalog prompt preset: " + presetName, e);
            }
            promptFamilies.add(new PromptFamily(
                requiredString(object, "id"),
                preset,
                stringArray(object, "modelNameContains"),
                requiredInt(object, "priority")));
        }

        return new AiModelPromptCatalog(schemaVersion, sequence, catalogVersion, recommendations, promptFamilies);
    }

    private static String decodeUtf8(byte[] bytes) throws IOException {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes))
                .toString();
        } catch (CharacterCodingException e) {
            throw new IOException("AI catalog is not valid UTF-8.", e);
        }
    }

    private static JsonObject requiredObject(JsonElement element, String name) throws IOException {
        if (element == null || !element.isJsonObject()) {
            throw new IOException("AI catalog " + name + " must be an object.");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray requiredArray(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonArray()) {
            throw new IOException("AI catalog field " + name + " must be an array.");
        }
        return element.getAsJsonArray();
    }

    private static List<String> stringArray(JsonObject object, String name) throws IOException {
        List<String> values = new ArrayList<>();
        for (JsonElement element : requiredArray(object, name)) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IOException("AI catalog field " + name + " must contain strings only.");
            }
            values.add(element.getAsString());
        }
        return List.copyOf(values);
    }

    private static String requiredString(JsonObject object, String name) throws IOException {
        String value = optionalString(object, name);
        if (value == null) {
            throw new IOException("AI catalog field " + name + " is required.");
        }
        return value;
    }

    private static String optionalString(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("AI catalog field " + name + " must be a string.");
        }
        return element.getAsString();
    }

    private static int requiredInt(JsonObject object, String name) throws IOException {
        long value = requiredIntegralNumber(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("AI catalog field " + name + " is outside the integer range.");
        }
        return (int) value;
    }

    private static long requiredLong(JsonObject object, String name) throws IOException {
        return requiredIntegralNumber(object, name);
    }

    private static long requiredIntegralNumber(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("AI catalog field " + name + " must be an integer.");
        }
        try {
            BigDecimal value = element.getAsBigDecimal().stripTrailingZeros();
            if (value.scale() > 0) {
                throw new IOException("AI catalog field " + name + " must be an integer.");
            }
            return value.longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IOException("AI catalog field " + name + " is not a valid integer.", e);
        }
    }

    private static void requireOnlyFields(JsonObject object, Set<String> allowed, String context) throws IOException {
        Set<String> unknown = new HashSet<>(object.keySet());
        unknown.removeAll(allowed);
        if (!unknown.isEmpty()) {
            throw new IOException("AI catalog " + context + " contains unknown fields: " + unknown);
        }
    }
}
