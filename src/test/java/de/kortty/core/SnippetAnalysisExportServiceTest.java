package de.kortty.core;

import de.kortty.core.SnippetAiResponseSupport.ScriptAnalysis;
import de.kortty.core.SnippetAiResponseSupport.ScriptDependency;
import de.kortty.core.SnippetAiResponseSupport.ScriptImprovement;
import org.testng.annotations.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * Exercises the AI-code-analysis report exporter end-to-end (without a diagram): HTML and
 * Markdown must carry the report content, and the PDF must be a real, non-empty PDF document.
 */
class SnippetAnalysisExportServiceTest {

    private static ScriptAnalysis sampleAnalysis() {
        return new ScriptAnalysis(
            "Downloads a release asset with curl and installs it.",
            List.of(new ScriptDependency("D1", "curl", "program", "download the asset", "use wget instead")),
            List.of(
                new ScriptImprovement("SEC-1", "security", "high", "Unquoted path expansion",
                    "$path is used unquoted.", "Quote it: \"$path\".", 12),
                new ScriptImprovement("OPT-1", "optimization", "low", "Avoid re-downloading",
                    "The asset is fetched twice.", "Cache the download.", null)));
    }

    private static SnippetAnalysisExportService.Context sampleContext() {
        return new SnippetAnalysisExportService.Context(
            "installer.sh", "LM Studio", LocalDateTime.of(2026, 7, 9, 17, 51), List.of("Bash hardening"));
    }

    @Test
    void htmlExportContainsReportContent() throws Exception {
        Path target = Files.createTempFile("analysis-export", ".html");
        try {
            new SnippetAnalysisExportService().export(
                target, SnippetAnalysisExportService.Format.HTML, sampleAnalysis(), sampleContext(), null);
            String html = Files.readString(target, StandardCharsets.UTF_8);
            assertThat(html).contains("<!doctype html>");
            assertThat(html).contains("Downloads a release asset");
            assertThat(html).contains("Unquoted path expansion");
            assertThat(html).contains("curl");
            assertThat(html).contains("installer.sh");
            assertThat(html).contains("LM Studio");
            assertThat(html).contains("Bash hardening");
            // Section icons are inline SVG (not emoji, which the WebView cannot render).
            assertThat(html).contains("<svg class=\"sec-ic\"");
            assertThat(html).doesNotContain("🛡"); // 🛡 (U+1F6E1)
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void markdownExportContainsReportContent() throws Exception {
        Path target = Files.createTempFile("analysis-export", ".md");
        try {
            new SnippetAnalysisExportService().export(
                target, SnippetAnalysisExportService.Format.MARKDOWN, sampleAnalysis(), sampleContext(), null);
            String markdown = Files.readString(target, StandardCharsets.UTF_8);
            assertThat(markdown).contains("# ");
            assertThat(markdown).contains("Downloads a release asset");
            assertThat(markdown).contains("Unquoted path expansion");
            assertThat(markdown).contains("Avoid re-downloading");
            assertThat(markdown).contains("curl");
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void pdfExportProducesRealPdf() throws Exception {
        Path target = Files.createTempFile("analysis-export", ".pdf");
        try {
            new SnippetAnalysisExportService().export(
                target, SnippetAnalysisExportService.Format.PDF, sampleAnalysis(), sampleContext(), null);
            byte[] bytes = Files.readAllBytes(target);
            assertThat(bytes.length).isGreaterThan(0);
            String header = new String(bytes, 0, Math.min(5, bytes.length), StandardCharsets.US_ASCII);
            assertThat(header).startsWith("%PDF");
        } finally {
            Files.deleteIfExists(target);
        }
    }

    @Test
    void emptyAnalysisExportsWithoutError() throws Exception {
        Path target = Files.createTempFile("analysis-empty", ".html");
        try {
            new SnippetAnalysisExportService().export(
                target, SnippetAnalysisExportService.Format.HTML,
                new ScriptAnalysis("", List.of(), List.of()),
                new SnippetAnalysisExportService.Context(null, null, LocalDateTime.of(2026, 1, 1, 0, 0), List.of()),
                null);
            assertThat(Files.readString(target)).contains("<!doctype html>");
        } finally {
            Files.deleteIfExists(target);
        }
    }
}
