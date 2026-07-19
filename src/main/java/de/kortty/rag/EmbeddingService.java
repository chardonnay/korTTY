package de.kortty.rag;

import java.util.List;

/** Adapter implemented by the local llama.cpp embedding runtime (or another embedding provider). */
public interface EmbeddingService {
    String modelId();

    int dimensions();

    List<float[]> embed(List<String> texts, CancellationToken cancellation) throws Exception;

    default float[] embedQuery(String query, CancellationToken cancellation) throws Exception {
        List<float[]> result = embed(List.of(query), cancellation);
        if (result.size() != 1) {
            throw new IllegalStateException("Embedding service returned " + result.size() + " vectors for one query");
        }
        return result.get(0);
    }
}
