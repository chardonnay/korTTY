package de.kortty.rag;

import java.util.Objects;

/** Chunk plus normalized embedding vector. */
public record RagEmbeddedChunk(RagChunk chunk, float[] vector) {
    public RagEmbeddedChunk {
        chunk = Objects.requireNonNull(chunk, "chunk");
        vector = Objects.requireNonNull(vector, "vector").clone();
        if (vector.length == 0) {
            throw new IllegalArgumentException("Embedding vector must not be empty");
        }
        double norm = 0;
        for (float value : vector) {
            if (!Float.isFinite(value)) {
                throw new IllegalArgumentException("Embedding contains non-finite value");
            }
            norm += (double) value * value;
        }
        if (norm == 0) {
            throw new IllegalArgumentException("Embedding vector must not be zero");
        }
        double scale = 1.0 / Math.sqrt(norm);
        for (int i = 0; i < vector.length; i++) {
            vector[i] = (float) (vector[i] * scale);
        }
    }

    @Override
    public float[] vector() {
        return vector.clone();
    }

    float[] rawVector() {
        return vector;
    }
}
