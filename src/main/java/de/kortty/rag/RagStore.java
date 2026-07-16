package de.kortty.rag;

import java.net.URI;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;

/** Persistable configuration of a local HNSW or Qdrant store. */
public record RagStore(
    String id,
    String displayName,
    RagStoreType type,
    Path localDirectory,
    URI endpoint,
    String collectionName,
    String apiKey,
    String embeddingModelId,
    int embeddingDimensions,
    boolean textEnabled,
    boolean codingEnabled,
    boolean autonomousEnabled
) {
    public RagStore(
        String id,
        String displayName,
        RagStoreType type,
        Path localDirectory,
        URI endpoint,
        String collectionName,
        String apiKey) {

        this(id, displayName, type, localDirectory, endpoint, collectionName, apiKey,
            "", 0, true, true, false);
    }

    public RagStore {
        id = id == null || id.isBlank() ? UUID.randomUUID().toString() : id.trim();
        displayName = displayName == null || displayName.isBlank() ? "RAG" : displayName.trim();
        type = Objects.requireNonNull(type, "type");
        localDirectory = localDirectory != null ? localDirectory.toAbsolutePath().normalize() : null;
        collectionName = collectionName == null || collectionName.isBlank() ? "kortty_rag" : collectionName.trim();
        apiKey = apiKey == null ? "" : apiKey;
        embeddingModelId = embeddingModelId == null ? "" : embeddingModelId.trim();
        if (embeddingDimensions < 0) {
            throw new IllegalArgumentException("Embedding dimensions must not be negative");
        }
        if (!embeddingModelId.isBlank() && embeddingDimensions == 0) {
            throw new IllegalArgumentException("An embedding model requires positive vector dimensions");
        }
        if (type == RagStoreType.LOCAL_HNSW && localDirectory == null) {
            throw new IllegalArgumentException("LOCAL_HNSW requires localDirectory");
        }
        if (type == RagStoreType.QDRANT && endpoint == null) {
            throw new IllegalArgumentException("QDRANT requires endpoint");
        }
    }

    public static RagStore local(Path directory) {
        return new RagStore(null, "Local RAG", RagStoreType.LOCAL_HNSW, directory, null,
            "kortty_rag", "", "", 0, true, true, false);
    }

    public static RagStore qdrant(URI endpoint, String collectionName, String apiKey) {
        return new RagStore(null, "Qdrant", RagStoreType.QDRANT, null, endpoint,
            collectionName, apiKey, "", 0, true, true, false);
    }

    public RagStore withEmbedding(String modelId, int dimensions) {
        return new RagStore(id, displayName, type, localDirectory, endpoint, collectionName,
            apiKey, modelId, dimensions, textEnabled, codingEnabled, autonomousEnabled);
    }

    public RagStore withAssignments(boolean text, boolean coding, boolean autonomous) {
        return new RagStore(id, displayName, type, localDirectory, endpoint, collectionName,
            apiKey, embeddingModelId, embeddingDimensions, text, coding, autonomous);
    }
}
