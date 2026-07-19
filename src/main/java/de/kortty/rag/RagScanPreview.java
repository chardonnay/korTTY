package de.kortty.rag;

import java.nio.file.Path;
import java.util.List;

/** Complete scan result: accepted documents plus every skipped/error path and reason. */
public record RagScanPreview(
    RagSource source,
    List<RagDocument> documents,
    List<Problem> problems,
    long acceptedBytes,
    int visitedFiles
) {
    public RagScanPreview {
        documents = documents == null ? List.of() : List.copyOf(documents);
        problems = problems == null ? List.of() : List.copyOf(problems);
    }

    public boolean hasUsableDocuments() {
        return !documents.isEmpty();
    }

    public int skippedFiles() {
        return problems.size();
    }

    public enum Severity { INFO, WARNING, ERROR }

    public enum ProblemCode {
        SOURCE_MISSING,
        TYPE_MISMATCH,
        UNSUPPORTED_FORMAT,
        EXCLUDED,
        SYMBOLIC_LINK,
        NOT_READABLE,
        TOO_LARGE,
        BINARY_OR_NON_UTF8,
        EMPTY_CONTENT,
        PROTECTED_PDF,
        PDF_WITHOUT_TEXT,
        EXTRACTION_FAILED,
        LIMIT_EXCEEDED,
        DUPLICATE_OR_OVERLAP
    }

    public record Problem(Path path, ProblemCode code, Severity severity, String message) {
        public Problem {
            message = message == null ? "" : message;
        }
    }
}
