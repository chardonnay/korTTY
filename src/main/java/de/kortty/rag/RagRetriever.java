package de.kortty.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Query embedding plus bounded retrieval: six hits total and at most two per source by default. */
public final class RagRetriever {
    public static final int DEFAULT_TOP_K = 6;
    public static final int DEFAULT_MAX_PER_SOURCE = 2;

    private final EmbeddingService embeddingService;
    private final RagVectorStore store;
    private final int topK;
    private final int maxPerSource;

    public RagRetriever(EmbeddingService embeddingService, RagVectorStore store) {
        this(embeddingService, store, DEFAULT_TOP_K, DEFAULT_MAX_PER_SOURCE);
    }

    public RagRetriever(EmbeddingService embeddingService, RagVectorStore store, int topK, int maxPerSource) {
        if (topK <= 0 || maxPerSource <= 0) {
            throw new IllegalArgumentException("Retrieval limits must be positive");
        }
        this.embeddingService = embeddingService;
        this.store = store;
        this.topK = topK;
        this.maxPerSource = maxPerSource;
    }

    public List<RagSearchResult> retrieve(
        String query,
        Set<String> activeSourceIds,
        CancellationToken cancellation
    ) throws Exception {
        if (query == null || query.isBlank() || activeSourceIds != null && activeSourceIds.isEmpty()) {
            return List.of();
        }
        CancellationToken token = cancellation != null ? cancellation : CancellationToken.NONE;
        float[] vector = embeddingService.embedQuery(query, token);
        List<RagSearchResult> candidates = store.search(vector, Math.max(topK * 12, 64),
            activeSourceIds == null ? Set.of() : Set.copyOf(activeSourceIds), token);
        Map<String, Integer> perSource = new HashMap<>();
        Set<String> seenChunks = new HashSet<>();
        List<RagSearchResult> result = new ArrayList<>();
        for (RagSearchResult candidate : candidates) {
            token.throwIfCancelled();
            String sourceId = candidate.chunk().sourceId();
            if (!seenChunks.add(candidate.chunk().id())
                || perSource.getOrDefault(sourceId, 0) >= maxPerSource) {
                continue;
            }
            result.add(candidate);
            perSource.merge(sourceId, 1, Integer::sum);
            if (result.size() == topK) {
                break;
            }
        }
        return List.copyOf(result);
    }
}
