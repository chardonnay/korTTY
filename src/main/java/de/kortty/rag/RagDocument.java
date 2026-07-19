package de.kortty.rag;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Objects;

/** Validated, extracted document ready for chunking. */
public record RagDocument(
    String sourceId,
    Path absolutePath,
    String relativePath,
    String format,
    long rawSize,
    Instant lastModified,
    String sha256,
    String text
) {
    public RagDocument {
        Objects.requireNonNull(sourceId, "sourceId");
        absolutePath = Objects.requireNonNull(absolutePath, "absolutePath").toAbsolutePath().normalize();
        relativePath = relativePath == null || relativePath.isBlank()
            ? absolutePath.getFileName().toString() : relativePath.replace('\\', '/');
        format = format == null ? "text" : format;
        lastModified = lastModified != null ? lastModified : Instant.EPOCH;
        sha256 = Objects.requireNonNull(sha256, "sha256");
        text = Objects.requireNonNull(text, "text");
    }
}
