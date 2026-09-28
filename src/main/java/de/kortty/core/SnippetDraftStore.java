package de.kortty.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Crash protection for the snippet editor: the unsaved form (name, language, category, tags,
 * description, content) of an editor is written to {@code <config>/snippet-drafts/<id>.json}
 * while it differs from the saved snippet, and removed again when the editor saves or the user
 * discards the changes. A draft that survives a crash or a kill is offered for restoring the next
 * time the snippet is opened; it is never applied by itself.
 *
 * <p>Files are written atomically via {@link AtomicFileWriter} (owner-only permissions on POSIX),
 * the file name follows the same path guard as {@link SnippetAnalysisStore#fileNameFor}, and a
 * file that cannot be parsed is moved aside with {@link CorruptFileQuarantine} instead of being
 * deleted. The directory is deliberately not part of the backup: a draft is a short-lived safety
 * net, not user data. All I/O runs on one daemon thread, so writes and deletes stay ordered; a
 * burst of saves for the same id is coalesced (the newest wins).</p>
 */
public final class SnippetDraftStore implements AutoCloseable {

    public static final String DIRECTORY_NAME = "snippet-drafts";
    /** Overrides the directory of {@link #shared()} outside the app (tests, scratch runners). */
    public static final String DIRECTORY_PROPERTY = "kortty.snippetDrafts.dir";
    /** Larger files are not loaded (they are moved aside). */
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    public static final int SCHEMA_VERSION = 1;

    private static final Logger logger = LoggerFactory.getLogger(SnippetDraftStore.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static volatile SnippetDraftStore applicationStore;
    private static volatile SnippetDraftStore memoryStore;
    private static final Map<Path, SnippetDraftStore> PROPERTY_STORES = new ConcurrentHashMap<>();

    /**
     * One unsaved editor state.
     *
     * @param baseContentSha256 {@link SnippetDiagramSupport#contentHash} of the snippet's saved
     *                          content when the draft was taken; empty for a never-saved snippet
     * @param newSnippet        the snippet was never saved (the id is the editor's draft id)
     */
    public record SnippetDraft(int schemaVersion, String snippetId, long savedAt, boolean newSnippet,
                               String name, String language, String category, String tags,
                               String description, String content, String baseContentSha256) {

        public SnippetDraft {
            snippetId = snippetId != null ? snippetId : "";
            name = name != null ? name : "";
            language = language != null ? language : "";
            category = category != null ? category : "";
            tags = tags != null ? tags : "";
            description = description != null ? description : "";
            content = content != null ? content : "";
            baseContentSha256 = baseContentSha256 != null ? baseContentSha256 : "";
        }

        public static SnippetDraft of(String snippetId, long savedAt, boolean newSnippet, String name,
                                      String language, String category, String tags, String description,
                                      String content, String baseContentSha256) {
            return new SnippetDraft(SCHEMA_VERSION, snippetId, savedAt, newSnippet, name, language, category,
                tags, description, content, baseContentSha256);
        }

        /** Whether the form fields are the same as {@code other}'s (timestamps and ids ignored). */
        public boolean sameFormAs(SnippetDraft other) {
            return other != null
                && name.strip().equals(other.name.strip())
                && language.strip().equals(other.language.strip())
                && category.strip().equals(other.category.strip())
                && tags.strip().equals(other.tags.strip())
                && description.strip().equals(other.description.strip())
                && content.equals(other.content);
        }
    }

    private final Path directory;
    private final ExecutorService executor;
    /** Memory-only stores keep their drafts here (tests, render smokes). */
    private final Map<String, SnippetDraft> memory = new ConcurrentHashMap<>();
    /** The newest draft per id waiting for the I/O thread (coalesced). */
    private final Map<String, SnippetDraft> pendingWrites = new LinkedHashMap<>();
    private final Object lock = new Object();
    private volatile boolean closed;

    /** @param directory where the files live, or {@code null} for a memory-only store */
    public SnippetDraftStore(Path directory) {
        this.directory = directory != null ? directory.toAbsolutePath().normalize() : null;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "snippet-draft-store");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The application's store; outside the app a store in {@value #DIRECTORY_PROPERTY}, else a
     * JVM-wide memory-only store.
     */
    public static SnippetDraftStore shared() {
        SnippetDraftStore installed = applicationStore;
        if (installed != null) {
            return installed;
        }
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            Path dir = Path.of(configured.strip()).toAbsolutePath().normalize();
            return PROPERTY_STORES.computeIfAbsent(dir, SnippetDraftStore::new);
        }
        SnippetDraftStore store = memoryStore;
        if (store == null) {
            synchronized (SnippetDraftStore.class) {
                store = memoryStore;
                if (store == null) {
                    store = new SnippetDraftStore(null);
                    memoryStore = store;
                }
            }
        }
        return store;
    }

    /** Makes {@code store} what {@link #shared()} returns (called once by the application). */
    public static void installApplicationStore(SnippetDraftStore store) {
        applicationStore = store;
    }

    public Path directory() {
        return directory;
    }

    // ---- writing ----

    /** Queues {@code draft} for writing (replacing a queued older one of the same id). */
    public void save(SnippetDraft draft) {
        Objects.requireNonNull(draft, "draft");
        String id = requireId(draft.snippetId());
        if (directory == null) {
            memory.put(id, draft);
            return;
        }
        boolean schedule;
        synchronized (lock) {
            schedule = !pendingWrites.containsKey(id);
            pendingWrites.put(id, draft);
        }
        if (schedule) {
            submit(() -> writePending(id));
        }
    }

    /** Removes the draft of {@code snippetId} (a queued write of it is dropped first). */
    public CompletableFuture<Void> delete(String snippetId) {
        if (snippetId == null || snippetId.isBlank()) {
            return CompletableFuture.completedFuture(null);
        }
        if (directory == null) {
            memory.remove(snippetId);
            return CompletableFuture.completedFuture(null);
        }
        synchronized (lock) {
            pendingWrites.remove(snippetId);
        }
        return submit(() -> {
            try {
                Files.deleteIfExists(fileFor(snippetId));
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not delete the snippet draft of {}", snippetId, e);
            }
        });
    }

    // ---- reading ----

    /**
     * Reads the draft of {@code snippetId} on the I/O thread (after every queued write). A file
     * that cannot be parsed, is too large or belongs to another id is moved aside and reported as
     * empty; a file of a newer schema is left alone and reported as empty.
     */
    public CompletableFuture<Optional<SnippetDraft>> load(String snippetId) {
        if (snippetId == null || snippetId.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        if (directory == null) {
            return CompletableFuture.completedFuture(Optional.ofNullable(memory.get(snippetId)));
        }
        CompletableFuture<Optional<SnippetDraft>> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                try {
                    result.complete(readFile(snippetId, fileFor(snippetId)));
                } catch (RuntimeException e) {
                    result.complete(Optional.empty());
                }
            });
        } catch (RejectedExecutionException e) {
            result.complete(Optional.empty());
        }
        return result;
    }

    /** Every readable draft, newest first (corrupt files are moved aside on the way). */
    public CompletableFuture<List<SnippetDraft>> loadAll() {
        if (directory == null) {
            List<SnippetDraft> drafts = new ArrayList<>(memory.values());
            drafts.sort(Comparator.comparingLong(SnippetDraft::savedAt).reversed());
            return CompletableFuture.completedFuture(List.copyOf(drafts));
        }
        CompletableFuture<List<SnippetDraft>> result = new CompletableFuture<>();
        try {
            executor.execute(() -> {
                List<SnippetDraft> drafts = new ArrayList<>();
                try {
                    if (Files.isDirectory(directory)) {
                        try (Stream<Path> files = Files.list(directory)) {
                            for (Path file : files.filter(this::isDraftFile).sorted().toList()) {
                                readFile(null, file).ifPresent(drafts::add);
                            }
                        }
                    }
                } catch (IOException | RuntimeException e) {
                    logger.warn("Could not list the snippet drafts in {}", directory, e);
                }
                drafts.sort(Comparator.comparingLong(SnippetDraft::savedAt).reversed());
                result.complete(List.copyOf(drafts));
            });
        } catch (RejectedExecutionException e) {
            result.complete(List.of());
        }
        return result;
    }

    /** Waits until every queued write and delete ran (tests, shutdown). */
    public void flush(long timeoutMillis) {
        if (directory == null || closed) {
            return;
        }
        try {
            submit(() -> { }).get(timeoutMillis, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            logger.debug("Snippet draft flush did not finish", e);
        }
    }

    /** Writes what is still queued and stops the I/O thread. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        flush(5_000);
        closed = true;
        executor.shutdown();
    }

    // ---- files ----

    /** The file of {@code snippetId}, always inside {@link #directory()} (same rule as the analyses). */
    Path fileFor(String snippetId) {
        if (directory == null) {
            throw new IllegalStateException("memory-only store");
        }
        Path file = directory.resolve(SnippetAnalysisStore.fileNameFor(snippetId)).normalize();
        if (!file.startsWith(directory) || file.equals(directory)) {
            throw new IllegalArgumentException("Snippet draft path escapes its directory: " + snippetId);
        }
        return file;
    }

    private boolean isDraftFile(Path file) {
        String name = file.getFileName().toString();
        return Files.isRegularFile(file) && name.endsWith(SnippetAnalysisStore.FILE_SUFFIX) && !name.startsWith(".");
    }

    private void writePending(String id) {
        SnippetDraft draft;
        synchronized (lock) {
            draft = pendingWrites.remove(id);
        }
        if (draft == null) {
            return;
        }
        try {
            ensureDirectory();
            AtomicFileWriter.writeStringAtomically(fileFor(id), GSON.toJson(draft));
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not write the snippet draft of {}", id, e);
        }
    }

    private void ensureDirectory() throws IOException {
        if (Files.isDirectory(directory)) {
            return;
        }
        Files.createDirectories(directory);
        PosixFileAttributeView view = Files.getFileAttributeView(directory, PosixFileAttributeView.class);
        if (view != null) {
            view.setPermissions(PosixFilePermissions.fromString("rwx------"));
        }
    }

    /**
     * @param expectedId the id the file must carry, or {@code null} when listing (any id, but it
     *                   must map back to this file name)
     */
    private Optional<SnippetDraft> readFile(String expectedId, Path file) {
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        String json;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                quarantine(file, "larger than " + MAX_FILE_BYTES + " bytes", null);
                return Optional.empty();
            }
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            logger.warn("Could not read the snippet draft {}", file, e);
            return Optional.empty();
        }
        SnippetDraft draft;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                quarantine(file, "not a JSON object", null);
                return Optional.empty();
            }
            JsonElement version = root.getAsJsonObject().get("schemaVersion");
            int schema = version != null && version.isJsonPrimitive() ? version.getAsInt() : 0;
            if (schema > SCHEMA_VERSION) {
                logger.info("Snippet draft {} uses schema {} (this version knows {}); ignored", file, schema,
                    SCHEMA_VERSION);
                return Optional.empty();
            }
            draft = GSON.fromJson(root, SnippetDraft.class);
        } catch (RuntimeException e) {
            quarantine(file, "unparseable", e);
            return Optional.empty();
        }
        if (draft == null || draft.snippetId().isBlank()) {
            quarantine(file, "empty", null);
            return Optional.empty();
        }
        boolean matches = expectedId != null
            ? expectedId.equals(draft.snippetId())
            : SnippetAnalysisStore.fileNameFor(draft.snippetId()).equals(file.getFileName().toString());
        if (!matches) {
            quarantine(file, "belongs to snippet '" + draft.snippetId() + "'", null);
            return Optional.empty();
        }
        return Optional.of(draft);
    }

    private static void quarantine(Path file, String reason, Exception cause) {
        try {
            Path backup = CorruptFileQuarantine.moveAside(file);
            logger.warn("Snippet draft {} could not be loaded ({}); moved to {}", file, reason, backup, cause);
        } catch (IOException moveFailure) {
            logger.warn("Snippet draft {} could not be loaded ({}) nor moved aside", file, reason, moveFailure);
        }
    }

    private CompletableFuture<Void> submit(Runnable task) {
        if (closed) {
            return CompletableFuture.completedFuture(null);
        }
        try {
            return CompletableFuture.runAsync(task, executor);
        } catch (RejectedExecutionException e) {
            return CompletableFuture.completedFuture(null);
        }
    }

    private static String requireId(String snippetId) {
        if (snippetId == null || snippetId.isBlank()) {
            throw new IllegalArgumentException("snippetId is required");
        }
        return snippetId;
    }
}
