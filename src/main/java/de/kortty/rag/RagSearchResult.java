package de.kortty.rag;

/** Ranked retrieval hit with an immediately usable local citation. */
public record RagSearchResult(RagChunk chunk, double score, String citation) {
    public RagSearchResult {
        if (chunk == null) {
            throw new IllegalArgumentException("chunk is required");
        }
        citation = citation == null || citation.isBlank() ? chunk.citation() : citation;
    }
}
