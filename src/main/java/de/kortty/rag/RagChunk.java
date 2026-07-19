package de.kortty.rag;

import java.util.Map;
import java.util.Objects;

/** Deterministic chunk with citation metadata. */
public record RagChunk(
    String id,
    String sourceId,
    String documentPath,
    String documentHash,
    int chunkIndex,
    int startOffset,
    int endOffset,
    String text,
    Map<String, String> metadata
) {
    public RagChunk {
        id = Objects.requireNonNull(id, "id");
        sourceId = Objects.requireNonNull(sourceId, "sourceId");
        documentPath = Objects.requireNonNull(documentPath, "documentPath");
        documentHash = Objects.requireNonNull(documentHash, "documentHash");
        text = Objects.requireNonNull(text, "text");
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }

    public String citation() {
        String page = metadata.get("page");
        return page == null || page.isBlank() ? documentPath : documentPath + "#page=" + page;
    }
}
