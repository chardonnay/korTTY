package de.kortty.rag;

import java.util.Collection;
import java.util.List;
import java.util.Set;

/** RagVectorStore facade backed by a Qdrant REST adapter. */
public final class QdrantVectorStore implements RagVectorStore {
    private final String collectionName;
    private final int dimensions;
    private final String embeddingModelId;
    private final QdrantRestAdapter adapter;

    public QdrantVectorStore(
        String collectionName,
        int dimensions,
        String embeddingModelId,
        QdrantRestAdapter adapter
    ) {
        if (collectionName == null || collectionName.isBlank() || dimensions <= 0 || adapter == null) {
            throw new IllegalArgumentException("collectionName, dimensions and adapter are required");
        }
        this.collectionName = collectionName;
        this.dimensions = dimensions;
        this.embeddingModelId = embeddingModelId == null ? "" : embeddingModelId;
        this.adapter = adapter;
    }

    public void initialize() throws Exception {
        adapter.ensureCollection(collectionName, dimensions);
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public String embeddingModelId() {
        return embeddingModelId;
    }

    @Override
    public List<RagEmbeddedChunk> chunksForSource(String sourceId) throws Exception {
        return adapter.readSource(collectionName, sourceId);
    }

    @Override
    public void replaceSource(
        String sourceId,
        Collection<RagEmbeddedChunk> chunks,
        CancellationToken cancellation
    ) throws Exception {
        for (RagEmbeddedChunk chunk : chunks) {
            if (chunk.vector().length != dimensions) {
                throw new IllegalArgumentException("Embedding dimension mismatch");
            }
        }
        adapter.replaceSource(collectionName, sourceId, chunks, cancellation);
    }

    @Override
    public void removeSource(String sourceId, CancellationToken cancellation) throws Exception {
        adapter.removeSource(collectionName, sourceId, cancellation);
    }

    @Override
    public List<RagSearchResult> search(
        float[] queryVector,
        int limit,
        Set<String> sourceIds,
        CancellationToken cancellation
    ) throws Exception {
        if (queryVector == null || queryVector.length != dimensions) {
            throw new IllegalArgumentException("Query vector dimension mismatch");
        }
        return adapter.search(collectionName, queryVector, limit, sourceIds, cancellation);
    }
}
