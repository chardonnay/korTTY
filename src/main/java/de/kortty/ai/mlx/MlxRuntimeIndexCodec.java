package de.kortty.ai.mlx;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Strict, defensive JSON codec for the signed MLX runtime index; mirrors the llama index codec. */
public final class MlxRuntimeIndexCodec {

    private static final int MAX_PACKAGES = 1_000;
    private static final int MAX_REVOKED_IDS = 10_000;

    public MlxRuntimeIndex parse(byte[] jsonBytes) throws IOException {
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(new String(jsonBytes, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("MLX runtime index contains malformed JSON.", e);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("MLX runtime index must be a JSON object.");
        }
        JsonObject root = parsed.getAsJsonObject();
        int schema = requiredInt(root, "schemaVersion");
        Instant generatedAt;
        try {
            generatedAt = Instant.parse(requiredString(root, "generatedAt"));
        } catch (DateTimeParseException e) {
            throw new IOException("MLX runtime index generatedAt is invalid.", e);
        }
        JsonArray sourcePackages = requiredArray(root, "packages");
        if (sourcePackages.size() > MAX_PACKAGES) {
            throw new IOException("MLX runtime index contains too many packages.");
        }
        List<MlxRuntimePackageDescriptor> packages = new ArrayList<>();
        Set<String> uniqueInstallations = new LinkedHashSet<>();
        for (JsonElement item : sourcePackages) {
            if (!item.isJsonObject()) {
                throw new IOException("MLX runtime package descriptor must be an object.");
            }
            MlxRuntimePackageDescriptor descriptor = parseDescriptor(item.getAsJsonObject());
            if (!uniqueInstallations.add(descriptor.installationId())) {
                throw new IOException("Duplicate MLX runtime package descriptor: " + descriptor.installationId());
            }
            packages.add(descriptor);
        }
        Set<String> revoked = stringSet(root.get("revokedRuntimeIds"));
        try {
            return new MlxRuntimeIndex(schema, generatedAt, packages, revoked);
        } catch (IllegalArgumentException e) {
            throw new IOException("MLX runtime index is incompatible with this korTTY version.", e);
        }
    }

    public MlxRuntimePackageDescriptor parseDescriptor(JsonObject object) throws IOException {
        try {
            return new MlxRuntimePackageDescriptor(
                requiredInt(object, "schemaVersion"),
                requiredString(object, "runtimeId"),
                requiredString(object, "installationId"),
                requiredString(object, "platform"),
                requiredString(object, "architecture"),
                requiredString(object, "backend"),
                requiredString(object, "minimumOsVersion"),
                requiredString(object, "mlxLmVersion"),
                requiredString(object, "pythonVersion"),
                requiredString(object, "sourceCommit"),
                requiredString(object, "executablePath"),
                requiredString(object, "launcherPath"),
                requiredLong(object, "sizeBytes"),
                requiredString(object, "sha256"),
                requiredString(object, "requirementsLockSha256"),
                uri(requiredString(object, "downloadUrl")));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid MLX runtime package descriptor.", e);
        }
    }

    private static URI uri(String value) throws IOException {
        try {
            return URI.create(value);
        } catch (IllegalArgumentException e) {
            throw new IOException("MLX runtime package URL is invalid.", e);
        }
    }

    private static JsonArray requiredArray(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IOException("MLX runtime index is missing array " + name + ".");
        }
        return value.getAsJsonArray();
    }

    private static String requiredString(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()
            || value.getAsString().isBlank() || value.getAsString().length() > 4096) {
            throw new IOException("MLX runtime index is missing string " + name + ".");
        }
        return value.getAsString().trim();
    }

    private static int requiredInt(JsonObject object, String name) throws IOException {
        long value = requiredLong(object, name);
        if (value < Integer.MIN_VALUE || value > Integer.MAX_VALUE) {
            throw new IOException("MLX runtime index integer is out of range: " + name + ".");
        }
        return (int) value;
    }

    private static long requiredLong(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new IOException("MLX runtime index is missing number " + name + ".");
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException e) {
            throw new IOException("MLX runtime index number is invalid: " + name + ".", e);
        }
    }

    private static Set<String> stringSet(JsonElement element) throws IOException {
        if (element == null || element.isJsonNull()) {
            return Set.of();
        }
        if (!element.isJsonArray()) {
            throw new IOException("revokedRuntimeIds must be an array.");
        }
        if (element.getAsJsonArray().size() > MAX_REVOKED_IDS) {
            throw new IOException("revokedRuntimeIds is unexpectedly large.");
        }
        LinkedHashSet<String> values = new LinkedHashSet<>();
        for (JsonElement item : element.getAsJsonArray()) {
            if (!item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()
                || item.getAsString().isBlank() || item.getAsString().length() > 256) {
                throw new IOException("revokedRuntimeIds contains an invalid value.");
            }
            values.add(item.getAsString().trim());
        }
        return Set.copyOf(values);
    }
}
