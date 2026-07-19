package de.kortty.ai.catalog;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/** Atomic single-file cache for the last signature-verified catalog payload. */
public final class AiCatalogCache {

    private static final int CACHE_FORMAT_VERSION = 1;
    private static final long MAX_CACHE_BYTES = 4L * 1024 * 1024;
    private static final Set<String> FIELDS = Set.of("formatVersion", "catalogBase64", "detachedSignature");
    private final Path cacheFile;

    public AiCatalogCache(Path directory) {
        if (directory == null) {
            throw new IllegalArgumentException("AI catalog cache directory is required.");
        }
        cacheFile = directory.toAbsolutePath().normalize().resolve("last-valid-catalog-v1.json");
    }

    public Optional<AiCatalogSource.SignedPayload> read() throws IOException {
        if (!Files.isRegularFile(cacheFile, LinkOption.NOFOLLOW_LINKS)) {
            return Optional.empty();
        }
        if (Files.size(cacheFile) <= 0 || Files.size(cacheFile) > MAX_CACHE_BYTES) {
            throw new IOException("Cached AI catalog has an invalid size.");
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(cacheFile, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new IOException("Cached AI catalog envelope is invalid JSON.", e);
        }
        if (!parsed.isJsonObject()) {
            throw new IOException("Cached AI catalog envelope must be an object.");
        }
        JsonObject object = parsed.getAsJsonObject();
        Set<String> unknown = new HashSet<>(object.keySet());
        unknown.removeAll(FIELDS);
        if (!unknown.isEmpty() || integer(object, "formatVersion") != CACHE_FORMAT_VERSION) {
            throw new IOException("Cached AI catalog envelope has an unsupported format.");
        }
        String encoded = string(object, "catalogBase64");
        String signature = string(object, "detachedSignature");
        try {
            byte[] payload = Base64.getDecoder().decode(encoded);
            if (payload.length == 0 || payload.length > AiCatalogSignatureVerifier.MAX_CATALOG_BYTES) {
                throw new IOException("Cached AI catalog payload has an invalid size.");
            }
            return Optional.of(new AiCatalogSource.SignedPayload(payload, signature));
        } catch (IllegalArgumentException e) {
            throw new IOException("Cached AI catalog payload is not valid Base64.", e);
        }
    }

    public void write(AiCatalogSource.SignedPayload payload) throws IOException {
        if (payload == null || payload.catalogBytes().length > AiCatalogSignatureVerifier.MAX_CATALOG_BYTES) {
            throw new IOException("AI catalog payload cannot be cached.");
        }
        JsonObject object = new JsonObject();
        object.addProperty("formatVersion", CACHE_FORMAT_VERSION);
        object.addProperty("catalogBase64", Base64.getEncoder().encodeToString(payload.catalogBytes()));
        object.addProperty("detachedSignature", payload.detachedSignature());
        byte[] bytes = object.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_CACHE_BYTES) {
            throw new IOException("AI catalog cache envelope exceeds the maximum size.");
        }
        Files.createDirectories(cacheFile.getParent());
        Path partial = cacheFile.resolveSibling(cacheFile.getFileName() + ".part-" + UUID.randomUUID());
        try {
            Files.write(partial, bytes, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
            try {
                Files.move(partial, cacheFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(partial, cacheFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(partial);
        }
    }

    public Path file() {
        return cacheFile;
    }

    private static int integer(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new IOException("Cached AI catalog field " + name + " must be an integer.");
        }
        try {
            BigDecimal value = element.getAsBigDecimal().stripTrailingZeros();
            if (value.scale() > 0) {
                throw new IOException("Cached AI catalog field " + name + " must be an integer.");
            }
            return value.intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IOException("Cached AI catalog field " + name + " is invalid.", e);
        }
    }

    private static String string(JsonObject object, String name) throws IOException {
        JsonElement element = object.get(name);
        if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new IOException("Cached AI catalog field " + name + " must be a string.");
        }
        return element.getAsString();
    }
}
