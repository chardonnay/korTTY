package de.kortty.rag;

import java.time.Instant;

/** Snapshot suitable for status rows and progress reporting. */
public record RagStatus(
    String sourceId,
    RagSourceStatus status,
    String message,
    double progress,
    int indexedDocuments,
    int indexedChunks,
    int problemCount,
    Instant updatedAt
) {
    public RagStatus {
        message = message == null ? "" : message;
        progress = Math.max(0.0, Math.min(1.0, progress));
        updatedAt = updatedAt != null ? updatedAt : Instant.now();
    }
}
