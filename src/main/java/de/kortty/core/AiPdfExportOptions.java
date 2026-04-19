package de.kortty.core;

/**
 * Per-export PDF rendering options for AI chat exports.
 */
public record AiPdfExportOptions(
    LayoutMode layoutMode,
    boolean includeDocumentMetadata,
    String documentTitle,
    String documentProducer,
    String documentSubject,
    boolean includeBookmarks) {

    public enum LayoutMode {
        REPORT,
        COMPACT
    }

    public AiPdfExportOptions {
        layoutMode = layoutMode != null ? layoutMode : LayoutMode.REPORT;
        documentTitle = normalize(documentTitle);
        documentProducer = normalize(documentProducer);
        documentSubject = normalize(documentSubject);
    }

    public static AiPdfExportOptions defaults(String title) {
        return new AiPdfExportOptions(
            LayoutMode.REPORT,
            true,
            title,
            "KorTTY by Daniel Mengel",
            "AI chat export",
            true);
    }

    private static String normalize(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }
}
