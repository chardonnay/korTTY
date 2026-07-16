package de.kortty.rag;

/** User-facing lifecycle state of a RAG source. */
public enum RagSourceStatus {
    PENDING,
    SCANNING,
    INDEXING,
    READY,
    CHANGED,
    WARNING,
    ERROR,
    MISSING,
    REBUILD_REQUIRED,
    DISABLED,
    CANCELLED
}
