package de.kortty.ai.catalog;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Loads network -> verified cache -> bootstrap without ever accepting unverified catalog data. */
public final class AiCatalogRepository {

    private final AiCatalogSource source;
    private final AiCatalogCache cache;
    private final AiCatalogSignatureVerifier verifier;
    private final AiModelPromptCatalog bootstrap;

    public AiCatalogRepository(
        AiCatalogSource source,
        AiCatalogCache cache,
        AiCatalogSignatureVerifier verifier,
        AiModelPromptCatalog bootstrap
    ) {
        this.source = source;
        this.cache = Objects.requireNonNull(cache, "cache");
        this.verifier = Objects.requireNonNull(verifier, "verifier");
        this.bootstrap = Objects.requireNonNull(bootstrap, "bootstrap");
    }

    /** Returns only a reverified cache entry or the built-in bootstrap; never performs network I/O. */
    public LoadResult loadCachedOrBootstrap() {
        return cachedOrBootstrap(new ArrayList<>());
    }

    /** Tries the signed remote channel first, preserving the last valid cache on every failure. */
    public LoadResult refresh() {
        List<String> failures = new ArrayList<>();
        if (source != null) {
            try {
                AiCatalogSource.SignedPayload payload = source.fetch();
                AiModelPromptCatalog catalog = verifier.verifyAndParse(payload);
                Optional<AiCatalogSource.SignedPayload> previousPayload = cache.read();
                if (previousPayload.isPresent()) {
                    AiModelPromptCatalog previous = verifier.verifyAndParse(previousPayload.get());
                    rejectReplay(catalog, previous);
                } else {
                    rejectReplay(catalog, bootstrap);
                }
                // Do not use a newer network catalog unless its monotonic high-water state can
                // be persisted. Otherwise a later validly signed replay could silently undo it.
                cache.write(payload);
                return new LoadResult(catalog, Source.NETWORK, failures);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                failures.add("network: interrupted");
            } catch (IOException | RuntimeException e) {
                failures.add(message("network", e));
            }
        }
        return cachedOrBootstrap(failures);
    }

    private LoadResult cachedOrBootstrap(List<String> failures) {
        try {
            Optional<AiCatalogSource.SignedPayload> cached = cache.read();
            if (cached.isPresent()) {
                AiModelPromptCatalog catalog = verifier.verifyAndParse(cached.get());
                // An application update may ship a bootstrap that is newer than the last signed
                // catalog this installation cached. The monotonic sequence decides, exactly as in
                // rejectReplay: otherwise the stale cache would hide new bootstrap entries until
                // the remote channel publishes a newer signed catalog.
                if (catalog.sequence() >= bootstrap.sequence()) {
                    return new LoadResult(catalog, Source.CACHE, failures);
                }
                failures.add("cache: superseded by newer built-in catalog (signed sequence "
                    + catalog.sequence() + " < bootstrap sequence " + bootstrap.sequence() + ")");
            }
        } catch (IOException | RuntimeException e) {
            failures.add(message("cache", e));
        }
        return new LoadResult(bootstrap, Source.BOOTSTRAP, failures);
    }

    private static String message(String phase, Exception error) {
        String detail = error.getMessage();
        return phase + ": " + (detail != null && !detail.isBlank() ? detail : error.getClass().getSimpleName());
    }

    private static void rejectReplay(AiModelPromptCatalog candidate, AiModelPromptCatalog accepted)
        throws IOException {
        if (candidate.sequence() < accepted.sequence()) {
            throw new IOException("AI catalog replay rejected: signed sequence " + candidate.sequence()
                + " is older than accepted sequence " + accepted.sequence() + ".");
        }
        if (candidate.sequence() == accepted.sequence()
            && !candidate.catalogVersion().equals(accepted.catalogVersion())) {
            throw new IOException("AI catalog sequence collision rejected.");
        }
    }

    public enum Source {
        NETWORK,
        CACHE,
        BOOTSTRAP
    }

    public record LoadResult(AiModelPromptCatalog catalog, Source source, List<String> failures) {
        public LoadResult {
            catalog = Objects.requireNonNull(catalog, "catalog");
            source = Objects.requireNonNull(source, "source");
            failures = List.copyOf(failures != null ? failures : List.of());
        }
    }
}
