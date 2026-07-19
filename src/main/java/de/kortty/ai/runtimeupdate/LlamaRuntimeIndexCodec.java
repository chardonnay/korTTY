package de.kortty.ai.runtimeupdate;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.ai.llama.LlamaBackend;
import java.io.IOException;
import java.net.URI;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** Strict JSON codec kept separate from signature verification for easy deterministic testing. */
public final class LlamaRuntimeIndexCodec {

    private static final Gson PRETTY_GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public LlamaRuntimeIndex parse(byte[] jsonBytes) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(jsonBytes, java.nio.charset.StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("llama.cpp runtime index contains malformed JSON.", e);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("llama.cpp runtime index must be a JSON object.");
        }
        JsonObject root = parsed.getAsJsonObject();
        int schema = requiredInt(root, "schemaVersion");
        Instant generatedAt;
        try {
            generatedAt = Instant.parse(requiredString(root, "generatedAt"));
        } catch (DateTimeParseException e) {
            throw new IOException("Runtime index generatedAt is invalid.", e);
        }
        JsonArray sourcePackages = requiredArray(root, "packages");
        List<LlamaRuntimePackageDescriptor> packages = new ArrayList<>();
        Set<String> uniqueInstallations = new LinkedHashSet<>();
        for (JsonElement item : sourcePackages) {
            if (!item.isJsonObject()) {
                throw new IOException("Runtime package descriptor must be an object.");
            }
            LlamaRuntimePackageDescriptor descriptor = parseDescriptor(item.getAsJsonObject());
            if (!uniqueInstallations.add(descriptor.installationId())) {
                throw new IOException("Duplicate runtime package descriptor: " + descriptor.installationId());
            }
            packages.add(descriptor);
        }
        Set<String> revoked = stringSet(root.get("revokedRuntimeIds"));
        try {
            return new LlamaRuntimeIndex(schema, generatedAt, packages, revoked);
        } catch (IllegalArgumentException e) {
            throw new IOException("Runtime index is incompatible with this korTTY version.", e);
        }
    }

    public LlamaRuntimePackageDescriptor parseDescriptor(JsonObject object) throws IOException {
        try {
            return new LlamaRuntimePackageDescriptor(
                requiredString(object, "runtimeId"),
                requiredString(object, "llamaTag"),
                requiredString(object, "commit"),
                requiredInt(object, "apiContractVersion"),
                requiredString(object, "minimumKorttyVersion"),
                LlamaRuntimePlatform.valueOf(requiredString(object, "platform").toUpperCase(Locale.ROOT)),
                requiredString(object, "architecture"),
                LlamaBackend.valueOf(requiredString(object, "backend").toUpperCase(Locale.ROOT)),
                requiredLong(object, "size"),
                requiredString(object, "sha256"),
                URI.create(requiredString(object, "downloadUrl")),
                requiredString(object, "entrypoint"),
                optionalBoolean(object, "revoked"));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid llama.cpp runtime package descriptor.", e);
        }
    }

    public String descriptorJson(LlamaRuntimePackageDescriptor descriptor) {
        JsonObject object = new JsonObject();
        object.addProperty("runtimeId", descriptor.runtimeId());
        object.addProperty("llamaTag", descriptor.llamaTag());
        object.addProperty("commit", descriptor.commit());
        object.addProperty("apiContractVersion", descriptor.apiContractVersion());
        object.addProperty("minimumKorttyVersion", descriptor.minimumKorttyVersion());
        object.addProperty("platform", descriptor.platform().manifestValue());
        object.addProperty("architecture", descriptor.architecture());
        object.addProperty("backend", descriptor.backend().name().toLowerCase(Locale.ROOT));
        object.addProperty("size", descriptor.size());
        object.addProperty("sha256", descriptor.sha256());
        object.addProperty("downloadUrl", descriptor.downloadUri().toString());
        object.addProperty("entrypoint", descriptor.entrypoint());
        object.addProperty("revoked", descriptor.revoked());
        return PRETTY_GSON.toJson(object) + System.lineSeparator();
    }

    private static JsonArray requiredArray(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IOException("Runtime index is missing array " + name + ".");
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().isBlank()) {
            throw new IOException("Runtime index is missing string " + name + ".");
        }
        return value.getAsString().trim();
    }

    private static int requiredInt(JsonObject object, String name) throws IOException {
        long value = requiredLong(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("Runtime index integer is out of range: " + name + ".");
        }
        return (int) value;
    }

    private static long requiredLong(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Runtime index is missing number " + name + ".");
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException e) {
            throw new IOException("Runtime index number is invalid: " + name + ".", e);
        }
    }

    private static boolean optionalBoolean(JsonObject object, String name) {
        JsonElement value = object.get(name);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
            && value.getAsBoolean();
    }

    private static Set<String> stringSet(JsonElement element) throws IOException {
        if (element == null || element.isJsonNull()) {
            return Set.of();
        }
        if (!element.isJsonArray()) {
            throw new IOException("revokedRuntimeIds must be an array.");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString() || item.getAsString().isBlank()) {
                throw new IOException("revokedRuntimeIds contains an invalid value.");
            }
            values.add(item.getAsString().trim());
        }
        return Set.copyOf(values);
    }
}
