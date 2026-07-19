package de.kortty.ai.catalog;

import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.PublicKey;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Process-wide catalog view: verified cache immediately, one background stable-channel refresh. */
public final class AiCatalogService implements AutoCloseable {

    private static final Object DEFAULT_LOCK = new Object();
    private static volatile AiCatalogService defaultInstance;

    private final AiCatalogRepository repository;
    private final ExecutorService executor;
    private final AtomicReference<AiCatalogRepository.LoadResult> current;
    private final AtomicBoolean refreshStarted = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();

    public AiCatalogService(AiCatalogRepository repository) {
        this(repository, newExecutor());
    }

    AiCatalogService(AiCatalogRepository repository, ExecutorService executor) {
        this.repository = repository;
        this.executor = executor;
        AiCatalogRepository.LoadResult initial = repository != null
            ? repository.loadCachedOrBootstrap()
            : new AiCatalogRepository.LoadResult(
                AiCatalogBootstrap.catalog(), AiCatalogRepository.Source.BOOTSTRAP, List.of());
        current = new AtomicReference<>(initial);
    }

    public static AiCatalogService getDefault() {
        AiCatalogService value = defaultInstance;
        if (value != null && !value.closed.get()) {
            return value;
        }
        synchronized (DEFAULT_LOCK) {
            value = defaultInstance;
            if (value == null || value.closed.get()) {
                value = createDefault();
                defaultInstance = value;
            }
            return value;
        }
    }

    /** Returns immediately from verified cache/bootstrap and schedules at most one remote refresh. */
    public AiModelPromptCatalog catalog() {
        refreshOnce();
        return current.get().catalog();
    }

    public AiCatalogRepository.LoadResult status() {
        return current.get();
    }

    public CompletableFuture<AiCatalogRepository.LoadResult> refreshAsync() {
        if (closed.get() || repository == null) {
            return CompletableFuture.completedFuture(current.get());
        }
        return CompletableFuture.supplyAsync(() -> {
            AiCatalogRepository.LoadResult refreshed = repository.refresh();
            current.set(refreshed);
            return refreshed;
        }, executor);
    }

    private void refreshOnce() {
        if (repository != null && !closed.get() && refreshStarted.compareAndSet(false, true)) {
            refreshAsync();
        }
    }

    private static AiCatalogService createDefault() {
        try {
            AiCatalogReleaseConfiguration configuration = AiCatalogReleaseConfiguration.loadDefault();
            Optional<PublicKey> trustRoot = configuration.trustedPublicKey();
            // Missing/invalid trust configuration must never construct a network source or trust a cache.
            if (trustRoot.isEmpty()) {
                return new AiCatalogService(null, null);
            }
            AiCatalogSignatureVerifier verifier = new AiCatalogSignatureVerifier(trustRoot.get());
            Path cacheDirectory = Path.of(System.getProperty("user.home"), ".kortty", "llm", "catalog");
            AiCatalogRepository repository = new AiCatalogRepository(
                new AiCatalogHttpSource(configuration.catalogUri(), configuration.signatureUri()),
                new AiCatalogCache(cacheDirectory),
                verifier,
                AiCatalogBootstrap.catalog());
            return new AiCatalogService(repository);
        } catch (GeneralSecurityException | IllegalArgumentException | IllegalStateException e) {
            return new AiCatalogService(null, null);
        }
    }

    private static ExecutorService newExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "kortty-ai-catalog-refresh");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        if (executor != null) {
            executor.shutdownNow();
        }
    }
}
