package de.kortty.rag;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** Application-wide per-store serialization, startup reconciliation and file watching. */
public final class RagCoordinator implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(RagCoordinator.class);
    private static final Object DEFAULT_LOCK = new Object();
    private static RagCoordinator defaultInstance;

    private final RagConfigurationManager configuration;
    private final RagRuntimeService runtimeService;
    private final Map<String, StoreRuntime> runtimes = new HashMap<>();
    private final CopyOnWriteArrayList<Consumer<RagStatus>> listeners = new CopyOnWriteArrayList<>();
    private boolean started;
    private boolean closed;

    public RagCoordinator(RagConfigurationManager configuration) {
        this.configuration = configuration;
        this.runtimeService = new RagRuntimeService(configuration.file());
    }

    public static RagCoordinator getDefault() {
        synchronized (DEFAULT_LOCK) {
            if (defaultInstance == null || defaultInstance.closed) {
                try {
                    defaultInstance = new RagCoordinator(new RagConfigurationManager());
                } catch (IOException error) {
                    throw new IllegalStateException("Could not initialize knowledge-store coordinator", error);
                }
            }
            return defaultInstance;
        }
    }

    public static void startDefault() {
        try {
            getDefault().start();
        } catch (RuntimeException error) {
            logger.warn("Local knowledge-store coordination is unavailable", error);
        }
    }

    public static void shutdownDefault() {
        synchronized (DEFAULT_LOCK) {
            if (defaultInstance != null) {
                defaultInstance.close();
                defaultInstance = null;
            }
        }
    }

    public synchronized void start() {
        if (started || closed) return;
        started = true;
        refreshConfiguration(true);
    }

    public synchronized void refreshConfiguration() {
        refreshConfiguration(false);
    }

    private void refreshConfiguration(boolean reconcileAutomatic) {
        if (closed) return;
        try {
            configuration.reload();
        } catch (IOException error) {
            logger.warn("Could not reload knowledge-store configuration", error);
            return;
        }
        List<RagStore> configured = configuration.listStores();
        Set<String> activeIds = configured.stream().map(RagStore::id).collect(java.util.stream.Collectors.toSet());
        List<String> removed = runtimes.keySet().stream().filter(id -> !activeIds.contains(id)).toList();
        removed.forEach(id -> {
            StoreRuntime runtime = runtimes.remove(id);
            if (runtime != null) runtime.close();
        });
        for (RagStore store : configured) {
            StoreRuntime runtime = runtimes.computeIfAbsent(store.id(), ignored -> new StoreRuntime(store));
            runtime.reconfigure(store, configuration.getSources(store.id()));
            if (reconcileAutomatic) {
                List<RagSource> automatic = configuration.getSources(store.id()).stream()
                    .filter(RagSource::enabled)
                    .filter(source -> source.syncMode() == RagSyncMode.AUTOMATIC)
                    // A persisted source is not an approved automatic scope until its first
                    // user-confirmed snapshot completed successfully. This also closes the crash
                    // window between saving a new source and committing its initial index.
                    .filter(source -> source.lastSuccessfulIndex() != null)
                    .toList();
                if (!automatic.isEmpty()) runtime.synchronize(automatic, CancellationToken.NONE);
            }
        }
    }

    public AutoCloseable addStatusListener(Consumer<RagStatus> listener) {
        if (listener == null) return () -> { };
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    public CompletableFuture<List<RagSyncResult>> synchronize(
        RagStore store,
        List<RagSource> sources,
        CancellationToken cancellation) {

        if (sources != null && sources.stream().anyMatch(source ->
            source.enabled() && source.lastSuccessfulIndex() == null)) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                "The source must complete a confirmed preview before automatic synchronization"));
        }
        StoreRuntime runtime;
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("RAG coordinator is closed"));
            try {
                configuration.reload();
                RagStore configuredStore = configuration.findStore(store.id()).orElse(store);
                runtime = runtimes.computeIfAbsent(store.id(), ignored -> new StoreRuntime(configuredStore));
                // A manual refresh may target only one source. Watch registration must always be
                // derived from the complete persisted store configuration, never that selection.
                runtime.reconfigure(configuredStore, configuration.getSources(store.id()));
            } catch (IOException | RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }
        }
        return runtime.synchronize(sources, cancellation != null ? cancellation : CancellationToken.NONE);
    }

    /**
     * Synchronizes only snapshots that were explicitly shown and confirmed by the user.
     * Persisted scan settings are rechecked here before the request enters the store executor.
     */
    public CompletableFuture<List<RagSyncResult>> synchronizeConfirmed(
        RagStore store,
        List<RagScanPreview> confirmedPreviews,
        CancellationToken cancellation) {

        if (confirmedPreviews == null || confirmedPreviews.isEmpty()) {
            return CompletableFuture.completedFuture(List.of());
        }
        StoreRuntime runtime;
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("RAG coordinator is closed"));
            try {
                configuration.reload();
                RagStore configuredStore = configuration.findStore(store.id()).orElse(store);
                List<RagSource> configuredSources = configuration.getSources(store.id());
                Map<String, RagSource> configuredById = configuredSources.stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(RagSource::id, source -> source));
                Set<String> seen = new java.util.HashSet<>();
                for (RagScanPreview preview : confirmedPreviews) {
                    if (preview == null || !seen.add(preview.source().id())) {
                        throw new IllegalArgumentException("Each confirmed source preview must be present exactly once");
                    }
                    RagSource configured = configuredById.get(preview.source().id());
                    if (configured == null
                        || !RagSourceSynchronizer.sameScanConfiguration(configured, preview.source())) {
                        throw new IllegalStateException(
                            "Source settings changed after the preview; review and confirm them again");
                    }
                }
                runtime = runtimes.computeIfAbsent(store.id(), ignored -> new StoreRuntime(configuredStore));
                runtime.reconfigure(configuredStore, configuredSources);
            } catch (IOException | RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }
        }
        return runtime.synchronizeConfirmed(confirmedPreviews,
            cancellation != null ? cancellation : CancellationToken.NONE);
    }

    public CompletableFuture<Void> removeSources(RagStore store, List<RagSource> sources) {
        StoreRuntime runtime;
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new IllegalStateException("RAG coordinator is closed"));
            try {
                configuration.reload();
                RagStore configuredStore = configuration.findStore(store.id()).orElse(store);
                runtime = runtimes.computeIfAbsent(store.id(), ignored -> new StoreRuntime(configuredStore));
                runtime.reconfigure(configuredStore, configuration.getSources(store.id()));
            } catch (IOException | RuntimeException error) {
                return CompletableFuture.failedFuture(error);
            }
        }
        return runtime.remove(sources);
    }

    synchronized Set<String> watchedSourceIds(String storeId) {
        StoreRuntime runtime = runtimes.get(storeId);
        return runtime != null ? runtime.watchedSourceIds() : Set.of();
    }

    private void publish(RagStatus status) {
        for (Consumer<RagStatus> listener : listeners) {
            try { listener.accept(status); } catch (RuntimeException ignored) { }
        }
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        runtimes.values().forEach(StoreRuntime::close);
        runtimes.clear();
        listeners.clear();
    }

    private final class StoreRuntime implements AutoCloseable {
        private final ExecutorService executor;
        private RagStore store;
        private RagSourceSynchronizer synchronizer;
        private RagSourceWatchService watcher;
        private Set<String> watchedIds = Set.of();

        private StoreRuntime(RagStore store) {
            this.store = store;
            this.executor = Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kortty-rag-store-" + store.id());
                thread.setDaemon(true);
                return thread;
            });
            try {
                watcher = new RagSourceWatchService(
                    source -> synchronize(List.of(source), CancellationToken.NONE),
                    (source, message) -> publish(new RagStatus(
                        source.id(), RagSourceStatus.WARNING, message, 0,
                        source.indexedFiles(), source.indexedChunks(), source.lastProblemCount(),
                        java.time.Instant.now())));
            } catch (IOException error) {
                logger.warn("Automatic watching is unavailable for knowledge store {}", store.displayName(), error);
            }
        }

        private synchronized void reconfigure(RagStore updated, List<RagSource> sources) {
            if (!updated.equals(store)) {
                store = updated;
                synchronizer = null;
            }
            if (watcher == null) {
                sources.stream()
                    .filter(RagSource::enabled)
                    .filter(source -> source.syncMode() == RagSyncMode.AUTOMATIC)
                    .filter(source -> source.lastSuccessfulIndex() != null)
                    .forEach(source -> publish(new RagStatus(source.id(), RagSourceStatus.WARNING,
                        "Automatic file watching is limited; manual refresh remains available",
                        0, source.indexedFiles(), source.indexedChunks(), source.lastProblemCount(),
                        java.time.Instant.now())));
                return;
            }
            Set<String> configuredIds = sources.stream().map(RagSource::id)
                .collect(java.util.stream.Collectors.toSet());
            watchedIds.stream().filter(id -> !configuredIds.contains(id)).forEach(watcher::unwatch);
            for (RagSource source : sources) {
                boolean approvedAutomatic = source.enabled()
                    && source.syncMode() == RagSyncMode.AUTOMATIC
                    && source.lastSuccessfulIndex() != null;
                if (!approvedAutomatic) {
                    watcher.unwatch(source.id());
                    continue;
                }
                try { watcher.watch(source); } catch (IOException error) {
                    publish(new RagStatus(source.id(), RagSourceStatus.WARNING,
                        "Automatic file watching is limited; manual refresh remains available",
                        0, 0, 0, 1, java.time.Instant.now()));
                }
            }
            watchedIds = watcher.watchedSourceIds();
        }

        private CompletableFuture<List<RagSyncResult>> synchronize(
            List<RagSource> sources,
            CancellationToken cancellation) {

            return CompletableFuture.supplyAsync(() -> {
                try {
                    RagSourceSynchronizer service = synchronizer();
                    List<RagSyncResult> results = new java.util.ArrayList<>();
                    for (RagSource source : sources) {
                        cancellation.throwIfCancelled();
                        RagSyncResult result = service.synchronize(source, cancellation);
                        results.add(result);
                        try {
                            boolean persisted = configuration.updateSourceStateIfScanConfigurationMatches(
                                store.id(), source, result);
                            if (!persisted) {
                                publish(new RagStatus(source.id(), RagSourceStatus.PENDING,
                                    "Source settings changed while synchronization was running; review them before updating",
                                    0, result.documents(), result.chunks(), result.problems(),
                                    java.time.Instant.now()));
                            }
                        } catch (IOException error) {
                            logger.warn("Could not persist synchronization state for source {}", source.id(), error);
                        }
                    }
                    refreshPersistedConfiguration();
                    return List.copyOf(results);
                } catch (Exception error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            }, executor);
        }

        private CompletableFuture<List<RagSyncResult>> synchronizeConfirmed(
            List<RagScanPreview> confirmedPreviews,
            CancellationToken cancellation) {

            return CompletableFuture.supplyAsync(() -> {
                try {
                    RagSourceSynchronizer service = synchronizer();
                    List<RagSyncResult> results = new java.util.ArrayList<>();
                    for (RagScanPreview preview : confirmedPreviews) {
                        cancellation.throwIfCancelled();
                        RagSyncResult result = service.synchronizeConfirmed(
                            preview, cancellation, () -> requireConfirmedConfiguration(preview));
                        results.add(result);
                        try {
                            boolean persisted = configuration.updateSourceStateIfScanConfigurationMatches(
                                store.id(), preview.source(), result);
                            if (!persisted) {
                                throw confirmedConfigurationChanged(preview.source().id());
                            }
                        } catch (IOException error) {
                            logger.warn("Could not persist synchronization state for source {}",
                                preview.source().id(), error);
                        }
                    }
                    refreshPersistedConfiguration();
                    return List.copyOf(results);
                } catch (Exception error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            }, executor);
        }

        private void requireConfirmedConfiguration(RagScanPreview preview) throws Exception {
            configuration.reload();
            RagSource current = configuration.getSources(store.id()).stream()
                .filter(source -> source.id().equals(preview.source().id()))
                .findFirst().orElse(null);
            if (current == null
                || !RagSourceSynchronizer.sameScanConfiguration(current, preview.source())) {
                throw confirmedConfigurationChanged(preview.source().id());
            }
        }

        private RagSourceSynchronizer.ConfirmedPreviewStaleException confirmedConfigurationChanged(
            String sourceId
        ) {
            String message = "Source settings changed after the preview; review and confirm them again";
            publish(new RagStatus(sourceId, RagSourceStatus.ERROR, message, 0,
                0, 0, 1, java.time.Instant.now()));
            return new RagSourceSynchronizer.ConfirmedPreviewStaleException(message);
        }

        private void refreshPersistedConfiguration() {
            try {
                configuration.reload();
                RagStore configuredStore = configuration.findStore(store.id()).orElse(store);
                reconfigure(configuredStore, configuration.getSources(store.id()));
            } catch (IOException | RuntimeException error) {
                logger.warn("Could not refresh automatic watchers for knowledge store {}", store.id(), error);
            }
        }

        private synchronized Set<String> watchedSourceIds() {
            return watcher != null ? watcher.watchedSourceIds() : Set.of();
        }

        private CompletableFuture<Void> remove(List<RagSource> sources) {
            return CompletableFuture.runAsync(() -> {
                try {
                    RagSourceSynchronizer service = synchronizer();
                    for (RagSource source : sources) service.remove(source, CancellationToken.NONE);
                } catch (Exception error) {
                    throw new java.util.concurrent.CompletionException(error);
                }
            }, executor);
        }

        private synchronized RagSourceSynchronizer synchronizer() throws Exception {
            if (synchronizer == null) {
                synchronizer = runtimeService.synchronizer(store);
                synchronizer.setStatusListener(RagCoordinator.this::publish);
            }
            return synchronizer;
        }

        @Override
        public synchronized void close() {
            if (watcher != null) {
                try { watcher.close(); } catch (IOException ignored) { }
                watcher = null;
            }
            executor.shutdownNow();
        }
    }
}
