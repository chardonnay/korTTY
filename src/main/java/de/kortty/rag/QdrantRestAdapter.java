package de.kortty.rag;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Domain-level REST adapter so Qdrant transport can be replaced in tests or deployments. */
public interface QdrantRestAdapter {
    void ensureCollection(String collectionName, int dimensions) throws Exception;

    List<RagEmbeddedChunk> readSource(String collectionName, String sourceId) throws Exception;

    void replaceSource(
        String collectionName,
        String sourceId,
        Collection<RagEmbeddedChunk> chunks,
        CancellationToken cancellation
    ) throws Exception;

    void removeSource(String collectionName, String sourceId, CancellationToken cancellation) throws Exception;

    List<RagSearchResult> search(
        String collectionName,
        float[] queryVector,
        int limit,
        Set<String> sourceIds,
        CancellationToken cancellation
    ) throws Exception;
}
