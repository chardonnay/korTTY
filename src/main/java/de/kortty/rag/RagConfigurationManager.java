package de.kortty.rag;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.FileOutputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Atomically persisted registry for RAG stores and their configured local sources. */
public final class RagConfigurationManager {
    public static final int FORMAT_VERSION = 1;
    public static final Path DEFAULT_FILE = Path.of(System.getProperty("user.home"), ".kortty", "rag", "stores.json");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<Path, Object> JVM_FILE_LOCKS = new ConcurrentHashMap<>();

    private final Path file;
    private final Path lockFile;
    private final Object jvmFileLock;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public RagConfigurationManager() throws IOException {
        this(DEFAULT_FILE);
    }

    public RagConfigurationManager(Path file) throws IOException {
        this.file = file.toAbsolutePath().normalize();
        this.lockFile = this.file.resolveSibling(this.file.getFileName() + ".lock");
        this.jvmFileLock = JVM_FILE_LOCKS.computeIfAbsent(this.file, ignored -> new Object());
        load();
    }

    public synchronized List<RagStore> listStores() {
        return entries.values().stream().map(Entry::store).toList();
    }

    public synchronized Optional<RagStore> findStore(String storeId) {
        Entry entry = entries.get(storeId);
        return entry != null ? Optional.of(entry.store()) : Optional.empty();
    }

    public synchronized RagStore create(RagStore store) throws IOException {
        return withExclusiveFileLock(() -> {
            reload();
            if (entries.containsKey(store.id())) {
                throw new IllegalArgumentException("RAG store already exists: " + store.id());
            }
            entries.put(store.id(), new Entry(store, List.of()));
            saveOrRollback(() -> entries.remove(store.id()));
            return store;
        });
    }

    public synchronized RagStore update(RagStore store) throws IOException {
        return withExclusiveFileLock(() -> {
            reload();
            Entry previous = entries.get(store.id());
            if (previous == null) {
                throw new IllegalArgumentException("Unknown RAG store: " + store.id());
            }
            entries.put(store.id(), new Entry(store, previous.sources()));
            saveOrRollback(() -> entries.put(store.id(), previous));
            return store;
        });
    }

    public synchronized boolean delete(String storeId) throws IOException {
        return withExclusiveFileLock(() -> {
            reload();
            Entry previous = entries.remove(storeId);
            if (previous == null) {
                return false;
            }
            saveOrRollback(() -> entries.put(storeId, previous));
            return true;
        });
    }

    public synchronized List<RagSource> getSources(String storeId) {
        Entry entry = requiredEntry(storeId);
        return entry.sources();
    }

    public synchronized void setSources(String storeId, List<RagSource> sources) throws IOException {
        withExclusiveFileLock(() -> {
            reload();
            Entry previous = requiredEntry(storeId);
            List<RagSource> safeSources = mergeLatestIndexState(
                sources == null ? List.of() : List.copyOf(sources), previous.sources());
            validateSources(safeSources);
            entries.put(storeId, new Entry(previous.store(), safeSources));
            saveOrRollback(() -> entries.put(storeId, previous));
            return null;
        });
    }

    /** Persists one completed synchronization without replacing the user's source settings. */
    public synchronized void updateSourceState(String storeId, RagSyncResult result) throws IOException {
        updateSourceStateIfScanConfigurationMatches(storeId, null, result);
    }

    /**
     * Persists synchronization state only while the source still has the scan configuration that
     * produced it. This prevents an in-flight watcher from approving settings edited by the user.
     */
    public synchronized boolean updateSourceStateIfScanConfigurationMatches(
        String storeId,
        RagSource expected,
        RagSyncResult result
    ) throws IOException {
        return withExclusiveFileLock(() -> {
            reload();
            Entry previous = requiredEntry(storeId);
            RagSource current = previous.sources().stream()
                .filter(source -> source.id().equals(result.sourceId()))
                .findFirst().orElse(null);
            if (current == null || (expected != null
                && !RagSourceSynchronizer.sameScanConfiguration(current, expected))) {
                return false;
            }
            List<RagSource> updated = previous.sources().stream()
                .map(source -> source.id().equals(result.sourceId()) ? source.withIndexState(result) : source)
                .toList();
            entries.put(storeId, new Entry(previous.store(), updated));
            saveOrRollback(() -> entries.put(storeId, previous));
            return true;
        });
    }

    public Path file() {
        return file;
    }

    private <T> T withExclusiveFileLock(LockedOperation<T> operation) throws IOException {
        synchronized (jvmFileLock) {
            Path parent = lockFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            try (FileChannel channel = FileChannel.open(
                    lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                return operation.run();
            }
        }
    }

    private static List<RagSource> mergeLatestIndexState(
        List<RagSource> requested,
        List<RagSource> persisted
    ) {
        Map<String, RagSource> currentById = persisted.stream()
            .collect(java.util.stream.Collectors.toMap(
                RagSource::id, source -> source, (first, ignored) -> first, LinkedHashMap::new));
        return requested.stream().map(source -> {
            RagSource current = currentById.get(source.id());
            if (current == null) {
                return source;
            }
            boolean scopeChanged = !RagSourceSynchronizer.sameContentScope(source, current);
            RagSourceStatus status = !source.enabled()
                ? RagSourceStatus.DISABLED
                : scopeChanged || !current.enabled() ? RagSourceStatus.PENDING : current.lastStatus();
            return new RagSource(
                source.id(), source.displayName(), source.path(), source.type(), source.syncMode(),
                source.enabled(), source.includePatterns(), source.excludePatterns(), source.recursive(),
                source.respectGitIgnore(), source.maxFileBytes(), status, current.documentHashes(),
                current.indexedFiles(), current.indexedChunks(), current.lastProblemCount(),
                scopeChanged ? null : current.lastSuccessfulIndex());
        }).toList();
    }

    @FunctionalInterface
    private interface LockedOperation<T> {
        T run() throws IOException;
    }

    /** Reloads the registry from disk so application-wide services see edits made by the UI. */
    public synchronized void reload() throws IOException {
        entries.clear();
        load();
    }

    private Entry requiredEntry(String storeId) {
        Entry entry = entries.get(storeId);
        if (entry == null) {
            throw new IllegalArgumentException("Unknown RAG store: " + storeId);
        }
        return entry;
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        JsonElement parsed;
        try {
            parsed = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
        } catch (RuntimeException error) {
            throw new IOException("Invalid RAG store registry", error);
        }
        JsonObject root = requireObject(parsed, "registry root");
        int version = requireInt(root, "version");
        if (version != FORMAT_VERSION) {
            throw new IOException("Unsupported RAG registry version: " + version);
        }
        JsonArray stores = requireArray(root, "stores");
        Map<String, Entry> loaded = new LinkedHashMap<>();
        for (JsonElement value : stores) {
            JsonObject object = requireObject(value, "store entry");
            RagStore store = parseStore(requireObject(object.get("store"), "store"));
            List<RagSource> sources = new ArrayList<>();
            for (JsonElement source : requireArray(object, "sources")) {
                sources.add(parseSource(requireObject(source, "source")));
            }
            try {
                validateSources(sources);
            } catch (IllegalArgumentException error) {
                throw new IOException("Invalid overlapping RAG sources for store " + store.id(), error);
            }
            if (loaded.put(store.id(), new Entry(store, List.copyOf(sources))) != null) {
                throw new IOException("Duplicate RAG store id: " + store.id());
            }
        }
        entries.clear();
        entries.putAll(loaded);
    }

    private void saveOrRollback(Runnable rollback) throws IOException {
        try {
            persist();
        } catch (IOException | RuntimeException error) {
            rollback.run();
            throw error;
        }
    }

    private void persist() throws IOException {
        JsonObject root = new JsonObject();
        root.addProperty("version", FORMAT_VERSION);
        JsonArray stores = new JsonArray();
        for (Entry entry : entries.values()) {
            JsonObject object = new JsonObject();
            object.add("store", serializeStore(entry.store()));
            JsonArray sources = new JsonArray();
            entry.sources().forEach(source -> sources.add(serializeSource(source)));
            object.add("sources", sources);
            stores.add(object);
        }
        root.add("stores", stores);
        byte[] bytes = (GSON.toJson(root) + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);

        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Path temporary = (parent != null ? parent : Path.of("."))
            .resolve(file.getFileName() + ".tmp-" + UUID.randomUUID());
        try {
            try (FileOutputStream output = new FileOutputStream(temporary.toFile())) {
                output.write(bytes);
                output.flush();
                output.getChannel().force(true);
            }
            setOwnerOnlyPermissions(temporary);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException error) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
            setOwnerOnlyPermissions(file);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static JsonObject serializeStore(RagStore store) {
        JsonObject object = new JsonObject();
        object.addProperty("id", store.id());
        object.addProperty("displayName", store.displayName());
        object.addProperty("type", store.type().name());
        addNullable(object, "localDirectory", store.localDirectory() != null ? store.localDirectory().toString() : null);
        addNullable(object, "endpoint", store.endpoint() != null ? store.endpoint().toString() : null);
        object.addProperty("collectionName", store.collectionName());
        object.addProperty("apiKey", store.apiKey());
        object.addProperty("embeddingModelId", store.embeddingModelId());
        object.addProperty("embeddingDimensions", store.embeddingDimensions());
        object.addProperty("textEnabled", store.textEnabled());
        object.addProperty("codingEnabled", store.codingEnabled());
        object.addProperty("autonomousEnabled", store.autonomousEnabled());
        return object;
    }

    private static RagStore parseStore(JsonObject object) throws IOException {
        try {
            String local = nullableString(object, "localDirectory");
            String endpoint = nullableString(object, "endpoint");
            String embeddingModelId = optionalString(object, "embeddingModelId", "");
            int embeddingDimensions = optionalInt(object, "embeddingDimensions", 0);
            return new RagStore(
                requireString(object, "id"), requireString(object, "displayName"),
                RagStoreType.valueOf(requireString(object, "type")),
                local != null ? Path.of(local) : null,
                endpoint != null ? URI.create(endpoint) : null,
                requireString(object, "collectionName"), nullableString(object, "apiKey"),
                embeddingModelId, embeddingDimensions,
                optionalBoolean(object, "textEnabled", true),
                optionalBoolean(object, "codingEnabled", true),
                optionalBoolean(object, "autonomousEnabled", false));
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid RAG store entry", error);
        }
    }

    private static JsonObject serializeSource(RagSource source) {
        JsonObject object = new JsonObject();
        object.addProperty("id", source.id());
        object.addProperty("displayName", source.displayName());
        object.addProperty("path", source.path().toString());
        object.addProperty("type", source.type().name());
        object.addProperty("syncMode", source.syncMode().name());
        object.addProperty("enabled", source.enabled());
        object.add("includePatterns", strings(source.includePatterns()));
        object.add("excludePatterns", strings(source.excludePatterns()));
        object.addProperty("recursive", source.recursive());
        object.addProperty("respectGitIgnore", source.respectGitIgnore());
        object.addProperty("maxFileBytes", source.maxFileBytes());
        object.addProperty("lastStatus", source.lastStatus().name());
        JsonObject hashes = new JsonObject();
        source.documentHashes().forEach(hashes::addProperty);
        object.add("documentHashes", hashes);
        object.addProperty("indexedFiles", source.indexedFiles());
        object.addProperty("indexedChunks", source.indexedChunks());
        object.addProperty("lastProblemCount", source.lastProblemCount());
        addNullable(object, "lastSuccessfulIndex",
            source.lastSuccessfulIndex() != null ? source.lastSuccessfulIndex().toString() : null);
        return object;
    }

    private static RagSource parseSource(JsonObject object) throws IOException {
        try {
            return new RagSource(
                requireString(object, "id"), requireString(object, "displayName"),
                Path.of(requireString(object, "path")),
                RagSourceType.valueOf(requireString(object, "type")),
                RagSyncMode.valueOf(requireString(object, "syncMode")),
                requireBoolean(object, "enabled"),
                parseStrings(requireArray(object, "includePatterns")),
                parseStrings(requireArray(object, "excludePatterns")),
                optionalBoolean(object, "recursive", true),
                optionalBoolean(object, "respectGitIgnore", true),
                optionalLong(object, "maxFileBytes", RagSourceFormatRegistry.DEFAULT_MAX_FILE_BYTES),
                RagSourceStatus.valueOf(optionalString(object, "lastStatus",
                    requireBoolean(object, "enabled") ? RagSourceStatus.PENDING.name() : RagSourceStatus.DISABLED.name())),
                optionalStringMap(object, "documentHashes"),
                optionalInt(object, "indexedFiles", 0),
                optionalInt(object, "indexedChunks", 0),
                optionalInt(object, "lastProblemCount", 0),
                parseOptionalInstant(nullableString(object, "lastSuccessfulIndex")));
        } catch (IllegalArgumentException error) {
            throw new IOException("Invalid RAG source entry", error);
        }
    }

    private static void validateSources(List<RagSource> sources) {
        Set<String> ids = new java.util.HashSet<>();
        RagSourceScanner scanner = new RagSourceScanner();
        List<RagSource> accepted = new ArrayList<>();
        for (RagSource source : sources) {
            if (!ids.add(source.id())) {
                throw new IllegalArgumentException("Duplicate RAG source id: " + source.id());
            }
            Optional<RagSource> overlap = scanner.findOverlap(source, accepted);
            if (overlap.isPresent()) {
                throw new IllegalArgumentException("RAG source overlaps with " + overlap.get().displayName()
                    + ": " + source.path());
            }
            accepted.add(source);
        }
    }

    private static JsonArray strings(List<String> values) {
        JsonArray array = new JsonArray();
        values.forEach(array::add);
        return array;
    }

    private static List<String> parseStrings(JsonArray array) throws IOException {
        List<String> result = new ArrayList<>();
        for (JsonElement element : array) {
            if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString()) {
                throw new IOException("Expected string array in RAG registry");
            }
            result.add(element.getAsString());
        }
        return List.copyOf(result);
    }

    private static Map<String, String> optionalStringMap(JsonObject object, String name) throws IOException {
        if (!object.has(name)) {
            return Map.of();
        }
        JsonElement value = object.get(name);
        if (!value.isJsonObject()) {
            throw new IOException("Expected JSON object: " + name);
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
            if (!entry.getValue().isJsonPrimitive() || !entry.getValue().getAsJsonPrimitive().isString()) {
                throw new IOException("Expected string value in " + name);
            }
            result.put(entry.getKey(), entry.getValue().getAsString());
        }
        return Map.copyOf(result);
    }

    private static java.time.Instant parseOptionalInstant(String value) throws IOException {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return java.time.Instant.parse(value);
        } catch (java.time.format.DateTimeParseException error) {
            throw new IOException("Invalid timestamp in RAG source", error);
        }
    }

    private static JsonObject requireObject(JsonElement value, String label) throws IOException {
        if (value == null || !value.isJsonObject()) {
            throw new IOException("Expected JSON object for " + label);
        }
        return value.getAsJsonObject();
    }

    private static JsonArray requireArray(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || !value.isJsonArray()) {
            throw new IOException("Missing JSON array: " + name);
        }
        return value.getAsJsonArray();
    }

    private static String requireString(JsonObject object, String name) throws IOException {
        String value = nullableString(object, name);
        if (value == null) {
            throw new IOException("Missing JSON string: " + name);
        }
        return value;
    }

    private static String nullableString(JsonObject object, String name) throws IOException {
        JsonElement value = object.get(name);
        if (value == null || value.isJsonNull()) {
            return null;
        }
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new IOException("Expected JSON string: " + name);
        }
        return value.getAsString();
    }

    private static String optionalString(JsonObject object, String name, String fallback) throws IOException {
        String value = nullableString(object, name);
        return value != null ? value : fallback;
    }

    private static int optionalInt(JsonObject object, String name, int fallback) throws IOException {
        if (!object.has(name)) {
            return fallback;
        }
        return requireInt(object, name);
    }

    private static boolean optionalBoolean(JsonObject object, String name, boolean fallback) throws IOException {
        if (!object.has(name)) {
            return fallback;
        }
        return requireBoolean(object, name);
    }

    private static long optionalLong(JsonObject object, String name, long fallback) throws IOException {
        if (!object.has(name)) {
            return fallback;
        }
        try {
            return object.get(name).getAsLong();
        } catch (RuntimeException error) {
            throw new IOException("Invalid JSON long: " + name, error);
        }
    }

    private static int requireInt(JsonObject object, String name) throws IOException {
        try {
            return object.get(name).getAsInt();
        } catch (RuntimeException error) {
            throw new IOException("Missing/invalid JSON integer: " + name, error);
        }
    }

    private static boolean requireBoolean(JsonObject object, String name) throws IOException {
        try {
            return object.get(name).getAsBoolean();
        } catch (RuntimeException error) {
            throw new IOException("Missing/invalid JSON boolean: " + name, error);
        }
    }

    private static void addNullable(JsonObject object, String name, String value) {
        if (value == null) {
            object.add(name, com.google.gson.JsonNull.INSTANCE);
        } else {
            object.addProperty(name, value);
        }
    }

    private static void setOwnerOnlyPermissions(Path path) {
        try {
            Files.setPosixFilePermissions(path, EnumSet.of(
                PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        } catch (IOException | UnsupportedOperationException | SecurityException ignored) {
            // Windows/non-POSIX filesystems use their platform ACL defaults.
        }
    }

    private record Entry(RagStore store, List<RagSource> sources) {
        private Entry {
            sources = List.copyOf(sources);
        }
    }
}
