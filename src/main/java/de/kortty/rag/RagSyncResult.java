package de.kortty.rag;

import java.time.Instant;
import java.util.Map;

/** Outcome of one transactional source synchronization. */
public record RagSyncResult(
    String sourceId,
    RagSourceStatus status,
    int documents,
    int chunks,
    int reusedDocuments,
    int embeddedDocuments,
    int removedDocuments,
    int problems,
    Instant completedAt,
    Map<String, String> documentHashes
) {
    public RagSyncResult(
        String sourceId,
        RagSourceStatus status,
        int documents,
        int chunks,
        int reusedDocuments,
        int embeddedDocuments,
        int removedDocuments,
        int problems,
        Instant completedAt) {

        this(sourceId, status, documents, chunks, reusedDocuments, embeddedDocuments,
            removedDocuments, problems, completedAt, Map.of());
    }

    public RagSyncResult {
        completedAt = completedAt != null ? completedAt : Instant.now();
        documentHashes = documentHashes == null ? Map.of() : Map.copyOf(documentHashes);
    }
}
