package de.kortty.rag;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** Transactional vector store contract shared by local HNSW and Qdrant. */
public interface RagVectorStore extends AutoCloseable {
    int dimensions();

    String embeddingModelId();

    List<RagEmbeddedChunk> chunksForSource(String sourceId) throws Exception;

    void replaceSource(
        String sourceId,
        Collection<RagEmbeddedChunk> chunks,
        CancellationToken cancellation
    ) throws Exception;

    void removeSource(String sourceId, CancellationToken cancellation) throws Exception;

    List<RagSearchResult> search(
        float[] queryVector,
        int limit,
        Set<String> sourceIds,
        CancellationToken cancellation
    ) throws Exception;

    @Override
    default void close() throws Exception { }
}
