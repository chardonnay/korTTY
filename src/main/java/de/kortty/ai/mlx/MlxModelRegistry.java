package de.kortty.ai.mlx;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, atomically persisted registry of local MLX model directories.
 *
 * <p>Persistence uses defensive hand-rolled JSON in {@code mlx-models.json}: every field is
 * type-checked and re-validated through {@link MlxModel} before it enters the live catalog, so a
 * tampered or truncated registry file fails closed instead of feeding unchecked values into
 * sidecar launches.
 */
public final class MlxModelRegistry {

    public static final String REGISTRY_FILE_NAME = "mlx-models.json";
    private static final int SCHEMA_VERSION = 1;
    private static final long MAX_REGISTRY_BYTES = 1024L * 1024;
    private static final Map<Path, Object> JVM_FILE_LOCKS = new ConcurrentHashMap<>();

    private final Path registryFile;
    private final Path lockFile;
    private final Map<String, MlxModel> models = new LinkedHashMap<>();

    public MlxModelRegistry(Path registryFile) {
        if (registryFile == null) {
            throw new IllegalArgumentException("Registry file must be configured.");
        }
        this.registryFile = registryFile.toAbsolutePath().normalize();
        this.lockFile = this.registryFile.resolveSibling(this.registryFile.getFileName() + ".lock");
        reload();
    }

    public static MlxModelRegistry inDirectory(Path llmDirectory) {
        if (llmDirectory == null) {
            throw new IllegalArgumentException("MLX data directory must be configured.");
        }
        return new MlxModelRegistry(llmDirectory.resolve(REGISTRY_FILE_NAME));
    }

    public synchronized Path getRegistryFile() {
        return registryFile;
    }

    public synchronized List<MlxModel> list() {
        return models.values().stream().map(MlxModel::new).toList();
    }

    public synchronized Optional<MlxModel> find(String id) {
        String normalizedId = normalizeId(id);
        MlxModel model = normalizedId != null ? models.get(normalizedId) : null;
        return model != null ? Optional.of(new MlxModel(model)) : Optional.empty();
    }

    /** Adds or replaces one registry entry and commits the complete registry atomically. */
    public synchronized void register(MlxModel model) {
        MlxModel validated = validatedCopy(model);
        mutateCatalog(() -> {
            models.put(validated.getId(), validated);
            return null;
        });
    }

    /** Removes an entry without deleting its model directory or the installed runtime package. */
    public synchronized Optional<MlxModel> remove(String id) {
        String normalizedId = normalizeId(id);
        return mutateCatalog(() -> {
            MlxModel removed = normalizedId != null ? models.remove(normalizedId) : null;
            return removed != null ? Optional.of(new MlxModel(removed)) : Optional.empty();
        });
    }

    public synchronized void reload() {
        withCatalogLock(() -> {
            reloadUnlocked();
            return null;
        });
    }

    private void reloadUnlocked() {
        if (!Files.exists(registryFile)) {
            models.clear();
            return;
        }
        try {
            if (!Files.isRegularFile(registryFile, LinkOption.NOFOLLOW_LINKS)) {
                throw new MlxRegistryException("Local MLX model registry is not a regular file: " + registryFile);
            }
            if (Files.size(registryFile) > MAX_REGISTRY_BYTES) {
                throw new MlxRegistryException("Local MLX model registry is unexpectedly large: " + registryFile);
            }
            JsonObject root = asObject(
                JsonParser.parseString(Files.readString(registryFile, StandardCharsets.UTF_8)),
                "registry document");
            int schemaVersion = intMember(root, "schemaVersion", SCHEMA_VERSION);
            if (schemaVersion > SCHEMA_VERSION) {
                throw new MlxRegistryException(
                    "Local MLX model registry schema " + schemaVersion
                        + " is newer than this korTTY version supports.");
            }
            Map<String, MlxModel> loadedModels = new LinkedHashMap<>();
            for (JsonElement element : arrayMember(root, "models")) {
                MlxModel validated = validatedCopy(parseModel(asObject(element, "model entry")));
                if (loadedModels.putIfAbsent(validated.getId(), validated) != null) {
                    throw new MlxRegistryException("Duplicate local MLX model id in registry: " + validated.getId());
                }
            }
            models.clear();
            models.putAll(loadedModels);
        } catch (MlxRegistryException e) {
            throw e;
        } catch (Exception e) {
            throw new MlxRegistryException("Could not read local MLX model registry " + registryFile + ".", e);
        }
    }

    private void saveUnlocked() {
        Path parent = registryFile.getParent();
        Path tempFile = null;
        try {
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String prefix = registryFile.getFileName().toString();
            tempFile = Files.createTempFile(parent, prefix, ".tmp");
            JsonObject root = new JsonObject();
            root.addProperty("schemaVersion", SCHEMA_VERSION);
            JsonArray array = new JsonArray();
            for (MlxModel model : models.values()) {
                array.add(toJson(model));
            }
            root.add("models", array);
            String json = new GsonBuilder().setPrettyPrinting().create().toJson(root);
            Files.writeString(
                tempFile,
                json + System.lineSeparator(),
                StandardCharsets.UTF_8,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE);
            try {
                Files.move(
                    tempFile,
                    registryFile,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(tempFile, registryFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            throw new MlxRegistryException("Could not write local MLX model registry " + registryFile + ".", e);
        } finally {
            if (tempFile != null) {
                try {
                    Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                    // Best-effort cleanup; the committed registry is unaffected.
                }
            }
        }
    }

    /** Caller holds this instance monitor; the shared lock closes the cross-instance race. */
    private <T> T mutateCatalog(CatalogMutation<T> mutation) {
        return withCatalogLock(() -> {
            reloadUnlocked();
            Map<String, MlxModel> previous = copyModels(models);
            try {
                T result = mutation.apply();
                saveUnlocked();
                return result;
            } catch (RuntimeException e) {
                models.clear();
                models.putAll(previous);
                throw e;
            }
        });
    }

    private <T> T withCatalogLock(LockedOperation<T> operation) {
        Object jvmLock = JVM_FILE_LOCKS.computeIfAbsent(lockFile, ignored -> new Object());
        synchronized (jvmLock) {
            try {
                Path parent = lockFile.getParent();
                if (parent != null) {
                    Files.createDirectories(parent);
                }
                try (FileChannel channel = FileChannel.open(
                        lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                     FileLock ignored = channel.lock()) {
                    return operation.run();
                }
            } catch (MlxRegistryException e) {
                throw e;
            } catch (Exception e) {
                throw new MlxRegistryException("Could not lock local MLX model registry " + registryFile + ".", e);
            }
        }
    }

    private static MlxModel parseModel(JsonObject json) {
        String directory = stringMember(json, "modelDirectory");
        return new MlxModel(
            stringMember(json, "id"),
            stringMember(json, "displayName"),
            directory != null ? Path.of(directory) : null,
            intMember(json, "contextSize", MlxModel.MODEL_DEFAULT_CONTEXT_SIZE),
            intMember(json, "idleTimeoutMinutes", MlxModel.DEFAULT_IDLE_TIMEOUT_MINUTES),
            stringMember(json, "quantizationLabel"));
    }

    private static JsonObject toJson(MlxModel model) {
        JsonObject json = new JsonObject();
        json.addProperty("id", model.getId());
        json.addProperty("displayName", model.getDisplayName());
        json.addProperty("modelDirectory", model.getModelDirectory().toString());
        json.addProperty("contextSize", model.getContextSize());
        json.addProperty("idleTimeoutMinutes", model.getIdleTimeoutMinutes());
        if (model.getQuantizationLabel() != null) {
            json.addProperty("quantizationLabel", model.getQuantizationLabel());
        }
        return json;
    }

    private static JsonObject asObject(JsonElement element, String description) {
        if (element == null || !element.isJsonObject()) {
            throw new MlxRegistryException("Local MLX model registry contains an invalid " + description + ".");
        }
        return element.getAsJsonObject();
    }

    private static JsonArray arrayMember(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null) {
            return new JsonArray();
        }
        if (!element.isJsonArray()) {
            throw new MlxRegistryException("Local MLX model registry member " + name + " must be an array.");
        }
        return element.getAsJsonArray();
    }

    private static String stringMember(JsonObject json, String name) {
        JsonElement element = json.get(name);
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
            throw new MlxRegistryException("Local MLX model registry member " + name + " must be a string.");
        }
        return element.getAsString();
    }

    private static int intMember(JsonObject json, String name, int defaultValue) {
        JsonElement element = json.get(name);
        if (element == null || element.isJsonNull()) {
            return defaultValue;
        }
        if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber()) {
            throw new MlxRegistryException("Local MLX model registry member " + name + " must be a number.");
        }
        try {
            return element.getAsInt();
        } catch (NumberFormatException e) {
            throw new MlxRegistryException("Local MLX model registry member " + name + " is not a valid integer.", e);
        }
    }

    private static Map<String, MlxModel> copyModels(Map<String, MlxModel> source) {
        Map<String, MlxModel> copy = new LinkedHashMap<>();
        source.forEach((id, model) -> copy.put(id, new MlxModel(model)));
        return copy;
    }

    private static MlxModel validatedCopy(MlxModel model) {
        if (model == null) {
            throw new IllegalArgumentException("Local MLX model must not be null.");
        }
        MlxModel copy = new MlxModel(model);
        copy.validate();
        return copy;
    }

    private static String normalizeId(String id) {
        return id != null && !id.isBlank() ? id.trim() : null;
    }

    @FunctionalInterface
    private interface CatalogMutation<T> {
        T apply();
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws Exception;
    }
}
