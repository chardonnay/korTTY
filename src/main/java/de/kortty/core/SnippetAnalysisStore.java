package de.kortty.core;

import com.google.gson.ExclusionStrategy;
import com.google.gson.FieldAttributes;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import de.kortty.core.SnippetAnalysisHistory.LoadIssue;
import javafx.application.Platform;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;

/**
 * The one store for persisted Full-code analyses: Gson JSON, one file per snippet at
 * {@code <config>/snippet-analyses/<id>.json}, never touching {@code snippets.xml}.
 *
 * <p><b>Threading.</b> Every mutation ({@link #update}, {@link #discardRecord}, {@link #discardAll},
 * {@link #rekey}, {@link #copy}) runs on the FX thread in the app; worker threads marshal first.
 * The {@code persistable} predicate (which asks the non-thread-safe {@code SnippetManager}) is
 * therefore only evaluated there. Subscribers of a mutation are notified synchronously on the
 * calling thread; results of background work (a load, a failed write) are delivered on the FX
 * thread when the toolkit runs, else directly. Loads and writes run on one daemon executor,
 * {@code snippet-analysis-store}, so they are ordered; writes are coalesced per snippet (latest
 * state wins) and written atomically via {@link AtomicFileWriter}.
 *
 * <p><b>Nothing is lost unless the user discards it.</b>
 * <ul>
 *   <li>A file that cannot be loaded (unparseable, too large, wrong snippet id) is moved aside via
 *       {@link CorruptFileQuarantine} before anything new is written; when that fails the snippet
 *       is read-only for the session. A file from a newer schema is shown read-only and never
 *       written.</li>
 *   <li>A failed write is kept as {@link SnippetAnalysisHistory#lastWriteError()} and retried with
 *       the next change.</li>
 *   <li>Histories whose id is not persistable yet (a draft, a policy-managed snippet, an external
 *       file) live in memory; they are written as soon as the id becomes a saved snippet
 *       ({@link #attachTo(SnippetManager)}, {@link #persistIfPossible}).</li>
 *   <li>Files are never deleted automatically: only {@link #discardAll} (the user deleting all
 *       analyses or the snippet) and {@link #rekey} (a move) remove one.</li>
 * </ul>
 */
public final class SnippetAnalysisStore implements AutoCloseable {

    public static final String DIRECTORY_NAME = "snippet-analyses";
    /** Overrides the directory of {@link #shared()} outside the app (tests, scratch runners). */
    public static final String DIRECTORY_PROPERTY = "kortty.snippetAnalyses.dir";
    /** Larger files are not loaded (they are moved aside instead). */
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;
    static final String FILE_SUFFIX = ".json";

    private static final Logger logger = LoggerFactory.getLogger(SnippetAnalysisStore.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Set<String> RESERVED_WINDOWS_NAMES = Set.of(
        "CON", "PRN", "AUX", "NUL",
        "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
        "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9");

    static final Gson GSON = new GsonBuilder()
        .setPrettyPrinting()
        .disableHtmlEscaping()
        .addSerializationExclusionStrategy(TransientHistoryFields.INSTANCE)
        .addDeserializationExclusionStrategy(TransientHistoryFields.INSTANCE)
        .create();

    private static volatile SnippetAnalysisStore applicationStore;
    private static volatile SnippetAnalysisStore memoryStore;
    private static final Map<Path, SnippetAnalysisStore> PROPERTY_STORES = new ConcurrentHashMap<>();

    /** A subscription handle; closing it removes the listener. */
    public interface Subscription extends AutoCloseable {
        @Override
        void close();
    }

    private static final class Entry {
        SnippetAnalysisHistory history;
        boolean loaded;
        LoadIssue loadIssue;
        String lastWriteError;
        /** Changed while the id was not persistable; written once it is. */
        boolean awaitingPersist;
        CompletableFuture<SnippetAnalysisHistory> loading;
        Object runOwner;
        final List<Consumer<SnippetAnalysisHistory>> subscribers = new CopyOnWriteArrayList<>();

        Entry(String snippetId) {
            history = SnippetAnalysisHistory.empty(snippetId);
        }
    }

    private record LoadResult(SnippetAnalysisHistory history, LoadIssue issue) {
    }

    private final Path directory;
    private final Predicate<String> persistable;
    private final IntSupplier historyLimit;
    private final ExecutorService executor;
    private final Object lock = new Object();
    private final Map<String, Entry> entries = new HashMap<>();
    private final Map<String, SnippetAnalysisHistory> pendingWrites = new LinkedHashMap<>();
    private final Set<String> scheduledWrites = new HashSet<>();
    private final Consumer<SnippetManager.Change> snippetChangeListener = this::onSnippetsChanged;
    private SnippetManager attachedManager;
    private long generation;
    private volatile boolean warnOffFxThread;
    private volatile boolean closed;

    /**
     * @param directory    where the files live, or {@code null} for a memory-only store
     * @param persistable  whether an id may be written (a saved, non-policy snippet); evaluated
     *                     on the mutating thread
     * @param historyLimit the retention limit applied when a new analysis arrives
     */
    public SnippetAnalysisStore(Path directory, Predicate<String> persistable, IntSupplier historyLimit) {
        this.directory = directory != null ? directory.toAbsolutePath().normalize() : null;
        this.persistable = persistable != null ? persistable : id -> true;
        this.historyLimit = historyLimit != null ? historyLimit : () -> 5;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "snippet-analysis-store");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * The application's store; outside the app a store in {@value #DIRECTORY_PROPERTY} (which
     * persists every id), else a JVM-wide memory-only store.
     */
    public static SnippetAnalysisStore shared() {
        SnippetAnalysisStore installed = applicationStore;
        if (installed != null) {
            return installed;
        }
        String configured = System.getProperty(DIRECTORY_PROPERTY);
        if (configured != null && !configured.isBlank()) {
            Path dir = Path.of(configured.strip()).toAbsolutePath().normalize();
            return PROPERTY_STORES.computeIfAbsent(dir, path -> new SnippetAnalysisStore(path, id -> true, () -> 5));
        }
        SnippetAnalysisStore store = memoryStore;
        if (store == null) {
            synchronized (SnippetAnalysisStore.class) {
                store = memoryStore;
                if (store == null) {
                    store = new SnippetAnalysisStore(null, id -> false, () -> 5);
                    memoryStore = store;
                }
            }
        }
        return store;
    }

    /**
     * Makes {@code store} what {@link #shared()} returns (called once by the application; core
     * code never references the application class, whose static initializer reconfigures logging).
     */
    public static void installApplicationStore(SnippetAnalysisStore store) {
        applicationStore = store;
    }

    /** In the app: log (with a stack trace) every mutation that does not run on the FX thread. */
    public void warnOnMutationsOffFxThread() {
        warnOffFxThread = true;
    }

    /**
     * Listens synchronously to {@link SnippetManager} saves: a pending in-memory history is
     * written as soon as its id became a saved snippet (covers saves through result handlers).
     */
    public void attachTo(SnippetManager manager) {
        synchronized (lock) {
            if (attachedManager != null) {
                attachedManager.removeChangeListener(snippetChangeListener);
            }
            attachedManager = manager;
        }
        if (manager != null) {
            manager.addChangeListener(snippetChangeListener);
        }
    }

    public Path directory() {
        return directory;
    }

    // ---- Reading ----

    /** Loads (once, on the store thread) and caches the history of {@code snippetId}. */
    public CompletableFuture<SnippetAnalysisHistory> load(String snippetId) {
        String id = requireId(snippetId);
        synchronized (lock) {
            Entry entry = entry(id);
            if (entry.loaded) {
                return CompletableFuture.completedFuture(view(entry));
            }
            if (entry.loading != null) {
                return entry.loading;
            }
            if (directory == null || closed) {
                entry.loaded = true;
                return CompletableFuture.completedFuture(view(entry));
            }
            long loadGeneration = generation;
            CompletableFuture<SnippetAnalysisHistory> future = CompletableFuture
                .supplyAsync(() -> readFile(id), executor)
                .thenApply(result -> {
                    SnippetAnalysisHistory delivered;
                    synchronized (lock) {
                        Entry current = entries.get(id);
                        if (current == null || loadGeneration != generation) {
                            return result.history().withTransientState(null, result.issue());
                        }
                        current.loading = null;
                        if (!current.loaded) {
                            current.history = result.history();
                            current.loadIssue = result.issue();
                            current.loaded = true;
                        }
                        delivered = view(current);
                    }
                    notifyLater(id, delivered);
                    return delivered;
                });
            entry.loading = future;
            return future;
        }
    }

    /** The cached history, or {@code null} when it was not loaded yet. */
    public SnippetAnalysisHistory cached(String snippetId) {
        synchronized (lock) {
            Entry entry = entries.get(snippetId);
            return entry != null && entry.loaded ? view(entry) : null;
        }
    }

    /** Whether changes to {@code snippetId} are written to disk right now (evaluate on FX). */
    public boolean isPersistable(String snippetId) {
        if (directory == null || snippetId == null || snippetId.isBlank()) {
            return false;
        }
        synchronized (lock) {
            Entry entry = entries.get(snippetId);
            if (entry != null && entry.loadIssue != null && entry.loadIssue.readOnly()) {
                return false;
            }
        }
        return testPersistable(snippetId);
    }

    // ---- Mutations (FX thread) ----

    /**
     * Applies {@code change} to the history of {@code snippetId} (loading it synchronously first
     * if needed), bumps the revision, notifies subscribers on this thread and queues a coalesced
     * write. Returns the new history; an unchanged result (same instance) is a no-op.
     */
    public SnippetAnalysisHistory update(String snippetId, UnaryOperator<SnippetAnalysisHistory> change) {
        String id = requireId(snippetId);
        Objects.requireNonNull(change, "change");
        checkThread("update");
        SnippetAnalysisHistory result;
        synchronized (lock) {
            Entry entry = ensureLoaded(id);
            SnippetAnalysisHistory before = view(entry);
            SnippetAnalysisHistory after = change.apply(before);
            if (after == null || after == before) {
                return before;
            }
            entry.history = after.withTransientState(null, null)
                .withSnippetId(id)
                .compact()
                .withRevision(entry.history.revision() + 1, System.currentTimeMillis());
            result = view(entry);
        }
        persistOrDefer(id);
        notifyNow(id);
        return cachedOr(id, result);
    }

    /** The limit the retention of {@link SnippetAnalysisHistory#withNewCurrent} should use. */
    public int historyLimit() {
        try {
            return Math.max(1, Math.min(20, historyLimit.getAsInt()));
        } catch (RuntimeException e) {
            return 5;
        }
    }

    /** Convenience: prepends a new analysis and applies retention with {@link #historyLimit()}. */
    public SnippetAnalysisHistory addAnalysis(String snippetId, SnippetAnalysisRecord record) {
        int limit = historyLimit();
        return update(snippetId, history -> history.withNewCurrent(record, limit));
    }

    /** Discard: removes one record (user action). */
    public SnippetAnalysisHistory discardRecord(String snippetId, String recordId) {
        return update(snippetId, history -> history.remove(recordId));
    }

    /**
     * Removes every analysis of {@code snippetId} and its file (user action: "delete all analyses"
     * or deleting the snippet).
     */
    public void discardAll(String snippetId) {
        String id = requireId(snippetId);
        checkThread("discardAll");
        synchronized (lock) {
            Entry entry = entry(id);
            long revision = entry.history.revision();
            entry.history = SnippetAnalysisHistory.empty(id).withRevision(revision + 1, System.currentTimeMillis());
            entry.loaded = true;
            entry.loading = null;
            entry.loadIssue = null;
            entry.lastWriteError = null;
            entry.awaitingPersist = false;
            pendingWrites.remove(id);
        }
        scheduleDelete(id);
        notifyNow(id);
    }

    /**
     * Moves the history of {@code from} to {@code to} (external file saved as a snippet under a new
     * id): the records are merged into {@code to}, {@code from} ends up empty and its file is
     * removed after {@code to} was queued. Subscribers and a run claim move along.
     */
    public SnippetAnalysisHistory rekey(String from, String to) {
        String source = requireId(from);
        String target = requireId(to);
        if (source.equals(target)) {
            return load(source).join();
        }
        checkThread("rekey");
        synchronized (lock) {
            Entry sourceEntry = ensureLoaded(source);
            Entry targetEntry = ensureLoaded(target);
            targetEntry.history = merge(targetEntry.history, sourceEntry.history, target);
            targetEntry.subscribers.addAll(sourceEntry.subscribers);
            sourceEntry.subscribers.clear();
            if (targetEntry.runOwner == null) {
                targetEntry.runOwner = sourceEntry.runOwner;
            }
            sourceEntry.runOwner = null;
            long revision = sourceEntry.history.revision();
            sourceEntry.history = SnippetAnalysisHistory.empty(source)
                .withRevision(revision + 1, System.currentTimeMillis());
            sourceEntry.awaitingPersist = false;
            pendingWrites.remove(source);
        }
        persistOrDefer(target);
        boolean targetQueued;
        synchronized (lock) {
            Entry targetEntry = entries.get(target);
            targetQueued = targetEntry != null && !targetEntry.awaitingPersist
                && (targetEntry.loadIssue == null || !targetEntry.loadIssue.readOnly());
        }
        // The old file only goes once the records are queued under the new id; a target that
        // cannot be written yet keeps the old file as the durable copy.
        if (targetQueued) {
            scheduleDelete(source);
        }
        notifyNow(target);
        return cached(target);
    }

    /**
     * Copies the history of {@code from} into {@code to} ("save as new snippet" keeps the
     * analyses with both). {@code from} is unchanged.
     */
    public SnippetAnalysisHistory copy(String from, String to) {
        String source = requireId(from);
        String target = requireId(to);
        if (source.equals(target)) {
            return load(source).join();
        }
        checkThread("copy");
        synchronized (lock) {
            Entry sourceEntry = ensureLoaded(source);
            Entry targetEntry = ensureLoaded(target);
            targetEntry.history = merge(targetEntry.history, sourceEntry.history, target);
        }
        persistOrDefer(target);
        notifyNow(target);
        return cached(target);
    }

    /** Writes a history that waited for its id to become a saved snippet (after the first save). */
    public void persistIfPossible(String snippetId) {
        if (snippetId == null || snippetId.isBlank()) {
            return;
        }
        boolean waiting;
        synchronized (lock) {
            Entry entry = entries.get(snippetId);
            waiting = entry != null && entry.awaitingPersist;
        }
        if (waiting) {
            persistOrDefer(snippetId);
        }
    }

    /**
     * Drops every cached history that came from disk (after a backup restore replaced the files)
     * and reloads the ones with subscribers. In-memory histories still waiting for their snippet
     * to be saved are kept, so an open draft loses nothing.
     */
    public void invalidateAll() {
        List<String> reload = new ArrayList<>();
        synchronized (lock) {
            generation++;
            for (var iterator = entries.entrySet().iterator(); iterator.hasNext(); ) {
                Map.Entry<String, Entry> item = iterator.next();
                Entry entry = item.getValue();
                if (entry.awaitingPersist) {
                    continue;
                }
                pendingWrites.remove(item.getKey());
                if (entry.subscribers.isEmpty() && entry.runOwner == null) {
                    iterator.remove();
                    continue;
                }
                entry.history = SnippetAnalysisHistory.empty(item.getKey());
                entry.loaded = false;
                entry.loading = null;
                entry.loadIssue = null;
                entry.lastWriteError = null;
                reload.add(item.getKey());
            }
        }
        reload.forEach(this::load);
    }

    // ---- Subscriptions and run claims ----

    /**
     * Notifies {@code listener} after every change of {@code snippetId}'s history. Closing the last
     * subscription of a history that only lives in memory evicts it one FX pulse later, unless its
     * snippet was saved meanwhile (then it is written).
     */
    public Subscription subscribe(String snippetId, Consumer<SnippetAnalysisHistory> listener) {
        String id = requireId(snippetId);
        Objects.requireNonNull(listener, "listener");
        synchronized (lock) {
            entry(id).subscribers.add(listener);
        }
        return () -> unsubscribe(listener);
    }

    private void unsubscribe(Consumer<SnippetAnalysisHistory> listener) {
        String emptied = null;
        synchronized (lock) {
            for (Map.Entry<String, Entry> item : entries.entrySet()) {
                if (item.getValue().subscribers.remove(listener)) {
                    if (item.getValue().subscribers.isEmpty() && item.getValue().awaitingPersist) {
                        emptied = item.getKey();
                    }
                    break;
                }
            }
        }
        if (emptied != null) {
            String id = emptied;
            runOnFxLater(() -> evictIfAbandoned(id));
        }
    }

    private void evictIfAbandoned(String id) {
        if (testPersistable(id) && directory != null) {
            persistIfPossible(id);
            return;
        }
        synchronized (lock) {
            Entry entry = entries.get(id);
            if (entry != null && entry.subscribers.isEmpty() && entry.awaitingPersist && entry.runOwner == null) {
                entries.remove(id);
            }
        }
    }

    /**
     * Claims the single apply run of {@code snippetId} for {@code owner} (one editor). Another
     * window showing the same snippet disables Apply while the claim is held.
     */
    public boolean tryClaimRun(String snippetId, Object owner) {
        Objects.requireNonNull(owner, "owner");
        synchronized (lock) {
            Entry entry = entry(requireId(snippetId));
            if (entry.runOwner == null || entry.runOwner == owner) {
                entry.runOwner = owner;
                return true;
            }
            return false;
        }
    }

    public void releaseRun(String snippetId, Object owner) {
        synchronized (lock) {
            Entry entry = entries.get(snippetId);
            if (entry != null && entry.runOwner == owner) {
                entry.runOwner = null;
            }
        }
    }

    /** Whether another owner than {@code owner} holds the run claim. */
    public boolean isRunClaimedByOther(String snippetId, Object owner) {
        synchronized (lock) {
            Entry entry = entries.get(snippetId);
            return entry != null && entry.runOwner != null && entry.runOwner != owner;
        }
    }

    // ---- Lifecycle ----

    /** Waits up to {@code timeout} for every queued write to finish. */
    public void flush(Duration timeout) {
        if (executor.isShutdown()) {
            return;
        }
        try {
            Future<?> barrier = executor.submit(() -> { });
            barrier.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            logger.warn("Snippet analyses were not fully written within {} ms", timeout.toMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Flushing snippet analyses failed: {}", e.toString());
        }
    }

    @Override
    public void close() {
        attachTo(null);
        flush(Duration.ofSeconds(2));
        closed = true;
        executor.shutdown();
    }

    // ---- Files ----

    /**
     * The file of {@code snippetId}: the id itself when it is a plain name, else its SHA-256 hex
     * (also for Windows device names and dot names). Always inside {@link #directory()}.
     */
    Path fileFor(String snippetId) {
        if (directory == null) {
            throw new IllegalStateException("memory-only store");
        }
        Path file = directory.resolve(fileNameFor(snippetId)).normalize();
        if (!file.startsWith(directory) || file.equals(directory)) {
            throw new IllegalArgumentException("Snippet analysis path escapes its directory: " + snippetId);
        }
        return file;
    }

    static String fileNameFor(String snippetId) {
        String id = snippetId != null ? snippetId : "";
        boolean plain = SAFE_ID.matcher(id).matches()
            && !id.startsWith(".")
            && !id.endsWith(".")
            && !RESERVED_WINDOWS_NAMES.contains(baseName(id).toUpperCase(Locale.ROOT));
        return (plain ? id : SnippetDiagramSupport.contentHash(id)) + FILE_SUFFIX;
    }

    private static String baseName(String id) {
        int dot = id.indexOf('.');
        return dot >= 0 ? id.substring(0, dot) : id;
    }

    /**
     * The stored revision of an analysis file without loading its content, or empty when the file
     * is missing or unreadable (used by the backup merge: the newer revision wins).
     */
    public static OptionalLong readRevision(Path file) {
        try {
            if (file == null || !Files.isRegularFile(file) || Files.size(file) > MAX_FILE_BYTES) {
                return OptionalLong.empty();
            }
            JsonElement root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8));
            if (!root.isJsonObject()) {
                return OptionalLong.empty();
            }
            JsonObject object = root.getAsJsonObject();
            return object.has("revision") && object.get("revision").isJsonPrimitive()
                ? OptionalLong.of(object.get("revision").getAsLong())
                : OptionalLong.of(0L);
        } catch (IOException | RuntimeException e) {
            return OptionalLong.empty();
        }
    }

    /** The JSON text written for {@code history} (file form, blobs deduplicated). */
    static String toJson(SnippetAnalysisHistory history) {
        return GSON.toJson(history.compact().externalizeContent());
    }

    /** Parses the file form back into the memory form (no normalisation). */
    static SnippetAnalysisHistory fromJson(String json) {
        SnippetAnalysisHistory parsed = GSON.fromJson(json, SnippetAnalysisHistory.class);
        return parsed == null ? null : parsed.internalizeContent();
    }

    private LoadResult readFile(String id) {
        Path file;
        try {
            file = fileFor(id);
        } catch (RuntimeException e) {
            return new LoadResult(SnippetAnalysisHistory.empty(id), null);
        }
        if (!Files.exists(file)) {
            return new LoadResult(SnippetAnalysisHistory.empty(id), null);
        }
        String json;
        try {
            if (Files.size(file) > MAX_FILE_BYTES) {
                return quarantine(id, file, "larger than " + MAX_FILE_BYTES + " bytes", null);
            }
            json = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException | RuntimeException e) {
            logger.error("Could not read snippet analyses {}; nothing will be written over it", file, e);
            return new LoadResult(SnippetAnalysisHistory.empty(id),
                new LoadIssue(LoadIssue.Kind.UNREADABLE_READ_ONLY, e.toString()));
        }
        int schema;
        try {
            JsonElement root = JsonParser.parseString(json);
            if (!root.isJsonObject()) {
                return quarantine(id, file, "not a JSON object", null);
            }
            JsonElement version = root.getAsJsonObject().get("schemaVersion");
            schema = version != null && version.isJsonPrimitive() ? version.getAsInt() : 0;
        } catch (RuntimeException e) {
            return quarantine(id, file, "unparseable", e);
        }
        if (schema > SnippetAnalysisHistory.SCHEMA_VERSION) {
            SnippetAnalysisHistory newer;
            try {
                newer = fromJson(json);
            } catch (RuntimeException e) {
                newer = null;
            }
            logger.warn("Snippet analyses {} use schema {} (this version knows {}); shown read-only",
                file, schema, SnippetAnalysisHistory.SCHEMA_VERSION);
            SnippetAnalysisHistory shown = newer != null && id.equals(newer.snippetId())
                ? newer.normalizeAfterLoad()
                : SnippetAnalysisHistory.empty(id);
            return new LoadResult(shown, new LoadIssue(LoadIssue.Kind.NEWER_SCHEMA_READ_ONLY, String.valueOf(schema)));
        }
        SnippetAnalysisHistory history;
        try {
            history = fromJson(json);
        } catch (RuntimeException e) {
            return quarantine(id, file, "unparseable", e);
        }
        if (history == null) {
            return quarantine(id, file, "empty", null);
        }
        if (!id.equals(history.snippetId())) {
            return quarantine(id, file, "belongs to snippet '" + history.snippetId() + "'", null);
        }
        return new LoadResult(history.withSchemaVersion(SnippetAnalysisHistory.SCHEMA_VERSION).normalizeAfterLoad(),
            null);
    }

    private LoadResult quarantine(String id, Path file, String reason, Exception cause) {
        try {
            Path backup = CorruptFileQuarantine.moveAside(file);
            logger.error("Snippet analyses {} could not be loaded ({}); moved to {}", file, reason, backup, cause);
            return new LoadResult(SnippetAnalysisHistory.empty(id),
                new LoadIssue(LoadIssue.Kind.QUARANTINED, backup.toString()));
        } catch (IOException moveFailure) {
            logger.error("Snippet analyses {} could not be loaded ({}) nor moved aside; they stay read-only",
                file, reason, moveFailure);
            return new LoadResult(SnippetAnalysisHistory.empty(id),
                new LoadIssue(LoadIssue.Kind.UNREADABLE_READ_ONLY, reason));
        }
    }

    // ---- Internals ----

    private Entry entry(String id) {
        return entries.computeIfAbsent(id, Entry::new);
    }

    /** Called with {@link #lock} held: a synchronous load for mutations that arrive first. */
    private Entry ensureLoaded(String id) {
        Entry entry = entry(id);
        if (!entry.loaded) {
            LoadResult result = directory == null || closed
                ? new LoadResult(SnippetAnalysisHistory.empty(id), null)
                : readFile(id);
            entry.history = result.history();
            entry.loadIssue = result.issue();
            entry.loaded = true;
        }
        return entry;
    }

    private SnippetAnalysisHistory view(Entry entry) {
        return entry.history.withTransientState(entry.lastWriteError, entry.loadIssue);
    }

    private SnippetAnalysisHistory cachedOr(String id, SnippetAnalysisHistory fallback) {
        SnippetAnalysisHistory current = cached(id);
        return current != null ? current : fallback;
    }

    private boolean testPersistable(String id) {
        try {
            return persistable.test(id);
        } catch (RuntimeException e) {
            logger.warn("Snippet analysis persistability check failed for {}: {}", id, e.toString());
            return false;
        }
    }

    private void persistOrDefer(String id) {
        boolean canWrite = directory != null && !closed && testPersistable(id);
        synchronized (lock) {
            Entry entry = entries.get(id);
            if (entry == null) {
                return;
            }
            if (entry.loadIssue != null && entry.loadIssue.readOnly()) {
                return;
            }
            if (!canWrite) {
                entry.awaitingPersist = true;
                return;
            }
            entry.awaitingPersist = false;
            pendingWrites.put(id, entry.history);
            if (!scheduledWrites.add(id)) {
                return;
            }
        }
        executor.execute(() -> writePending(id));
    }

    private void writePending(String id) {
        SnippetAnalysisHistory snapshot;
        synchronized (lock) {
            scheduledWrites.remove(id);
            snapshot = pendingWrites.remove(id);
        }
        if (snapshot == null) {
            return;
        }
        String error = null;
        try {
            Path file = fileFor(id);
            String json = toJson(snapshot);
            if (json.length() > MAX_FILE_BYTES) {
                logger.warn("Snippet analyses of {} grew to {} chars; the file may be moved aside on the next load",
                    id, json.length());
            }
            Files.createDirectories(file.getParent());
            AtomicFileWriter.writeStringAtomically(file, json);
        } catch (IOException | RuntimeException e) {
            error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            logger.error("Failed to write snippet analyses of {}; retrying with the next change", id, e);
        }
        recordWriteOutcome(id, error);
    }

    private void scheduleDelete(String id) {
        if (directory == null || closed) {
            return;
        }
        executor.execute(() -> {
            String error = null;
            try {
                Files.deleteIfExists(fileFor(id));
            } catch (IOException | RuntimeException e) {
                error = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                logger.error("Failed to delete the snippet analyses of {}", id, e);
            }
            recordWriteOutcome(id, error);
        });
    }

    private void recordWriteOutcome(String id, String error) {
        SnippetAnalysisHistory changed = null;
        synchronized (lock) {
            Entry entry = entries.get(id);
            if (entry != null && !Objects.equals(entry.lastWriteError, error)) {
                entry.lastWriteError = error;
                changed = view(entry);
            }
        }
        if (changed != null) {
            notifyLater(id, changed);
        }
    }

    private static SnippetAnalysisHistory merge(SnippetAnalysisHistory target, SnippetAnalysisHistory incoming,
                                                String targetId) {
        Map<String, SnippetAnalysisRecord> byId = new LinkedHashMap<>();
        for (SnippetAnalysisRecord record : target.records()) {
            byId.putIfAbsent(record.id(), record);
        }
        for (SnippetAnalysisRecord record : incoming.records()) {
            byId.putIfAbsent(record.id(), record);
        }
        List<SnippetAnalysisRecord> records = new ArrayList<>(byId.values());
        records.sort(Comparator.comparingLong(SnippetAnalysisRecord::analyzedAt).reversed());
        long revision = Math.max(target.revision(), incoming.revision()) + 1;
        return target.withRecords(records).withSnippetId(targetId)
            .withTransientState(null, null)
            .withRevision(revision, System.currentTimeMillis());
    }

    private void onSnippetsChanged(SnippetManager.Change change) {
        List<String> waiting = new ArrayList<>();
        synchronized (lock) {
            entries.forEach((id, entry) -> {
                if (entry.awaitingPersist) {
                    waiting.add(id);
                }
            });
        }
        waiting.forEach(this::persistIfPossible);
    }

    private void notifyNow(String id) {
        SnippetAnalysisHistory current;
        List<Consumer<SnippetAnalysisHistory>> listeners;
        synchronized (lock) {
            Entry entry = entries.get(id);
            if (entry == null || entry.subscribers.isEmpty()) {
                return;
            }
            current = view(entry);
            listeners = List.copyOf(entry.subscribers);
        }
        deliver(listeners, current);
    }

    private void notifyLater(String id, SnippetAnalysisHistory history) {
        List<Consumer<SnippetAnalysisHistory>> listeners;
        synchronized (lock) {
            Entry entry = entries.get(id);
            if (entry == null || entry.subscribers.isEmpty()) {
                return;
            }
            listeners = List.copyOf(entry.subscribers);
        }
        runOnFxLater(() -> deliver(listeners, history));
    }

    private static void deliver(List<Consumer<SnippetAnalysisHistory>> listeners, SnippetAnalysisHistory history) {
        for (Consumer<SnippetAnalysisHistory> listener : listeners) {
            try {
                listener.accept(history);
            } catch (RuntimeException e) {
                logger.error("Snippet analysis subscriber failed", e);
            }
        }
    }

    /** FX when the toolkit runs (one pulse later), else directly (unit tests, headless tools). */
    private static void runOnFxLater(Runnable action) {
        try {
            Platform.runLater(action);
        } catch (IllegalStateException toolkitNotRunning) {
            action.run();
        }
    }

    private void checkThread(String operation) {
        if (warnOffFxThread && !Platform.isFxApplicationThread()) {
            logger.warn("SnippetAnalysisStore.{} called off the FX thread", operation,
                new IllegalStateException("off FX thread"));
        }
    }

    private static String requireId(String snippetId) {
        if (snippetId == null || snippetId.isBlank()) {
            throw new IllegalArgumentException("snippetId is required");
        }
        return snippetId;
    }

    private enum TransientHistoryFields implements ExclusionStrategy {
        INSTANCE;

        @Override
        public boolean shouldSkipField(FieldAttributes field) {
            return field.getDeclaringClass() == SnippetAnalysisHistory.class
                && SnippetAnalysisHistory.TRANSIENT_FIELDS.contains(field.getName());
        }

        @Override
        public boolean shouldSkipClass(Class<?> type) {
            return false;
        }
    }
}
