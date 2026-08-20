package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.SessionJournalMeta;
import de.kortty.rag.CancellationToken;
import de.kortty.rag.EmbeddingService;
import de.kortty.rag.LocalHnswStore;
import de.kortty.rag.RagChunk;
import de.kortty.rag.RagConfigurationManager;
import de.kortty.rag.RagEmbeddedChunk;
import de.kortty.rag.RagSearchResult;
import de.kortty.rag.RagStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;

/**
 * Opt-in semantic ranking for the cross-journal search: journal search cards embedded into a
 * dedicated {@link LocalHnswStore} under {@code <journals>/.semantic-index/}, kept incremental
 * by the card's document mtime (stored as chunk metadata). Reuses the RAG stack's components —
 * store, embedding service, chunk model — but none of its source/store configuration machinery:
 * the journals themselves are the corpus. The embedding model is borrowed from the first
 * knowledge store that has one configured, so no second model-management UI exists.
 *
 * <p>Every failure returns an empty score map — BM25 is always the fallback ranking.</p>
 */
public final class SessionJournalSemanticIndex {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalSemanticIndex.class);
    static final String INDEX_DIR_NAME = ".semantic-index";
    private static final String MTIME_METADATA_KEY = "mtime";
    private static final int SEARCH_LIMIT = 48;

    private final SessionJournalSearchCardIndex cardIndex;
    private final Path indexDirectory;
    private final EmbeddingService embeddings;
    private LocalHnswStore store;

    SessionJournalSemanticIndex(SessionJournalSearchCardIndex cardIndex, Path indexDirectory,
                                EmbeddingService embeddings) {
        this.cardIndex = cardIndex;
        this.indexDirectory = indexDirectory;
        this.embeddings = embeddings;
    }

    /**
     * Production instance, or null when the feature is off or no embedding model is configured —
     * the caller then simply stays on BM25.
     */
    public static SessionJournalSemanticIndex applicationOrNull(
            GlobalSettings settings, SessionJournalSearchCardIndex cardIndex) {
        try {
            if (settings == null || !settings.isSessionJournalSemanticSearchEnabled()) {
                return null;
            }
            RagStore donor = new RagConfigurationManager(RagConfigurationManager.DEFAULT_FILE)
                .listStores().stream()
                .filter(store -> !store.embeddingModelId().isBlank() && store.embeddingDimensions() > 0)
                .findFirst()
                .orElse(null);
            if (donor == null) {
                return null;
            }
            EmbeddingService embeddings = new de.kortty.ai.llama.EmbeddedLlamaEmbeddingService(
                donor.embeddingModelId(), donor.embeddingDimensions());
            Path indexDir = SessionJournalService.resolveJournalsDirectory(settings)
                .resolve(INDEX_DIR_NAME);
            return new SessionJournalSemanticIndex(cardIndex, indexDir, embeddings);
        } catch (Exception e) {
            logger.debug("Semantic journal search unavailable: {}", e.getMessage());
            return null;
        }
    }

    /** True when an embedding model is configured — drives the settings checkbox hint. */
    public static boolean embeddingModelConfigured() {
        try {
            return new RagConfigurationManager(RagConfigurationManager.DEFAULT_FILE)
                .listStores().stream()
                .anyMatch(store -> !store.embeddingModelId().isBlank()
                    && store.embeddingDimensions() > 0);
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Best semantic similarity per journal directory for {@code query}, over the given scope.
     * Synchronizes stale journals into the index first (incremental by document mtime). Empty on
     * any failure or cancellation — never throws.
     */
    public Map<Path, Double> score(String query, List<SessionJournalMeta> scope,
                                   BooleanSupplier cancelled) {
        try {
            CancellationToken token = cancelled != null ? cancelled::getAsBoolean : CancellationToken.NONE;
            LocalHnswStore hnsw = openStore();
            Map<String, Path> sourceToDirectory = new HashMap<>();
            for (SessionJournalMeta meta : scope) {
                if (meta.getDirectory() == null) {
                    continue;
                }
                token.throwIfCancelled();
                Path dir = meta.getDirectory().toAbsolutePath().normalize();
                String sourceId = dir.getFileName().toString();
                sourceToDirectory.put(sourceId, dir);
                syncJournal(hnsw, sourceId, meta, token);
            }
            token.throwIfCancelled();
            float[] queryVector = embeddings.embedQuery(query, token);
            Set<String> sources = new HashSet<>(sourceToDirectory.keySet());
            Map<Path, Double> best = new HashMap<>();
            for (RagSearchResult result : hnsw.search(queryVector, SEARCH_LIMIT, sources, token)) {
                Path dir = sourceToDirectory.get(result.chunk().sourceId());
                if (dir != null) {
                    best.merge(dir, result.score(), Math::max);
                }
            }
            return best;
        } catch (Exception e) {
            logger.debug("Semantic journal scoring skipped: {}", e.getMessage());
            return Map.of();
        }
    }

    private synchronized LocalHnswStore openStore() throws Exception {
        if (store == null) {
            try {
                store = new LocalHnswStore(indexDirectory, embeddings.dimensions(), embeddings.modelId());
            } catch (LocalHnswStore.IncompatibleIndexException e) {
                // The embedding model changed — the old vectors are meaningless. Rebuild from zero.
                deleteIndexFiles();
                store = new LocalHnswStore(indexDirectory, embeddings.dimensions(), embeddings.modelId());
            }
        }
        return store;
    }

    private void deleteIndexFiles() {
        try (var paths = java.nio.file.Files.list(indexDirectory)) {
            paths.forEach(path -> {
                try {
                    java.nio.file.Files.deleteIfExists(path);
                } catch (Exception ignored) {
                    // best effort — the store constructor will fail loudly if it matters
                }
            });
        } catch (Exception ignored) {
            // directory may not exist yet
        }
    }

    /** Re-embeds one journal's card when the stored chunks are missing or stale. */
    private void syncJournal(LocalHnswStore hnsw, String sourceId, SessionJournalMeta meta,
                             CancellationToken token) {
        try {
            SessionJournalSearchCard card = cardIndex.card(meta);
            String expectedMtime = String.valueOf(card.documentMtimeMillis());
            boolean fresh = hnsw.chunksForSource(sourceId).stream()
                .anyMatch(chunk -> expectedMtime.equals(chunk.chunk().metadata().get(MTIME_METADATA_KEY)));
            if (fresh) {
                return;
            }
            List<String> texts = new ArrayList<>();
            List<RagChunk> chunks = new ArrayList<>();
            addChunk(chunks, texts, sourceId, card, 0, null, card.metaText());
            int index = 1;
            for (SessionJournalSearchCard.Section section : card.sections()) {
                String text = section.searchText();
                if (!text.isBlank()) {
                    addChunk(chunks, texts, sourceId, card, index++, section.entryId(), text);
                }
            }
            List<float[]> vectors = embeddings.embed(texts, token);
            List<RagEmbeddedChunk> embedded = new ArrayList<>(chunks.size());
            for (int i = 0; i < chunks.size() && i < vectors.size(); i++) {
                embedded.add(new RagEmbeddedChunk(chunks.get(i), vectors.get(i)));
            }
            hnsw.replaceSource(sourceId, embedded, token);
        } catch (Exception e) {
            logger.debug("Semantic sync skipped for {}: {}", sourceId, e.getMessage());
        }
    }

    private static void addChunk(List<RagChunk> chunks, List<String> texts, String sourceId,
                                 SessionJournalSearchCard card, int index, String entryId,
                                 String text) {
        Map<String, String> metadata = new HashMap<>();
        metadata.put(MTIME_METADATA_KEY, String.valueOf(card.documentMtimeMillis()));
        if (entryId != null) {
            metadata.put("entryId", entryId);
        }
        chunks.add(new RagChunk(
            sourceId + "#" + index,
            sourceId,
            sourceId,
            String.valueOf(card.documentMtimeMillis()),
            index,
            0,
            text.length(),
            text,
            metadata));
        texts.add(text);
    }
}
