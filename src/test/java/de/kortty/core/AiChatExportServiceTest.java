package de.kortty.core;

import de.kortty.model.SavedAiChatMessage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import static com.google.common.truth.Truth.assertThat;


class AiChatExportServiceTest {

    @Test
    void plainTextExportContainsLocalizedRoleLabels() {
        AiChatExportService service = new AiChatExportService();

        String exported = service.buildPlainTextExport(List.of(
            message(SavedAiChatMessage.ROLE_USER, "Zeig mir die letzte Fehlermeldung", null),
            message(SavedAiChatMessage.ROLE_ASSISTANT, "Proxy ist nicht erreichbar.", "GPT Ops")));

        assertThat(exported.contains("You:")).isTrue();
        assertThat(exported.contains("AI (GPT Ops):")).isTrue();
        assertThat(exported.contains("Proxy ist nicht erreichbar.")).isTrue();
    }

    @Test
    void markdownExportKeepsCodeBlocksAndTextSections() {
        AiChatExportService service = new AiChatExportService();

        String exported = service.buildMarkdownExport(List.of(
            message(SavedAiChatMessage.ROLE_ASSISTANT, "Analyse\n```bash\ncurl -I https://example.test\n```", "GPT Ops")));

        assertThat(exported.contains("## AI (GPT Ops):")).isTrue();
        assertThat(exported.contains("### Text")).isTrue();
        assertThat(exported.contains("### Code (bash)")).isTrue();
        assertThat(exported.contains("curl -I https://example.test")).isTrue();
    }

    @Test
    void pdfExportIncludesMetadataBookmarksUnicodeAndMultiplePages() throws Exception {
        AiChatExportService service = new AiChatExportService();
        List<SavedAiChatMessage> messages = List.of(
            message(SavedAiChatMessage.ROLE_USER, "Bitte prüfe die Logs für Übertragung, Größe und Ähnlichkeit.", null),
            message(SavedAiChatMessage.ROLE_ASSISTANT, buildLongAssistantContent(), "GPT Ops"));

        AiChatExportContext context = new AiChatExportContext(
            "Server Analyse Export",
            LocalDateTime.of(2026, 4, 16, 10, 30),
            "GPT Ops",
            messages.size());
        AiPdfExportOptions options = new AiPdfExportOptions(
            AiPdfExportOptions.LayoutMode.REPORT,
            true,
            "Server Analyse Export",
            "KorTTY by Daniel Mengel",
            "AI chat export",
            true);

        Path exportFile = Files.createTempFile("ai-chat-export-", ".pdf");
        try {
            service.exportChat(exportFile, AiChatExportService.Format.PDF, messages, 13, context, options);

            try (PDDocument document = Loader.loadPDF(exportFile.toFile())) {
                PDDocumentInformation info = document.getDocumentInformation();
                PDDocumentOutline outline = document.getDocumentCatalog().getDocumentOutline();
                PDOutlineItem root = outline != null ? (PDOutlineItem) outline.getFirstChild() : null;
                String extractedText = new PDFTextStripper().getText(document);

                assertThat(document.getNumberOfPages() > 1).isTrue();
                assertThat(info.getTitle()).isEqualTo("Server Analyse Export");
                assertThat(info.getProducer()).isEqualTo("KorTTY by Daniel Mengel");
                assertThat(info.getSubject()).isEqualTo("AI chat export");
                assertThat(outline).isNotNull();
                assertThat(root).isNotNull();
                assertThat(root.getTitle()).isEqualTo("Server Analyse Export");
                assertThat(countChildren(root) >= messages.size()).isTrue();
                assertThat(extractedText.contains("Created with KorTTY by Daniel Mengel")).isTrue();
                // The footer now uses the shared, user-configurable export brand line (default text here).
                assertThat(extractedText.contains(de.kortty.core.ExportBranding.defaultFooterText())).isTrue();
                assertThat(extractedText.contains("Übertragung")).isTrue();
                assertThat(extractedText.contains("Änderungen übernommen")).isTrue();
                assertThat(extractedText.contains("curl -I https://example.test")).isTrue();
                assertThat(extractedText.contains("Service")).isTrue();
                assertThat(extractedText.contains("worker")).isTrue();
            }
        } finally {
            Files.deleteIfExists(exportFile);
        }
    }

    @Test
    void pdfExportCanSkipDocumentMetadataAndBookmarksInCompactMode() throws Exception {
        AiChatExportService service = new AiChatExportService();
        List<SavedAiChatMessage> messages = List.of(
            message(SavedAiChatMessage.ROLE_USER, "Kurzprüfung", null),
            message(SavedAiChatMessage.ROLE_ASSISTANT, "Status ist ok.\n\n```bash\necho ok\n```", "GPT Ops"));

        AiChatExportContext context = new AiChatExportContext(
            "Compact Export",
            LocalDateTime.of(2026, 4, 16, 11, 15),
            "GPT Ops",
            messages.size());
        AiPdfExportOptions options = new AiPdfExportOptions(
            AiPdfExportOptions.LayoutMode.COMPACT,
            false,
            "Should Not Appear",
            "Hidden Producer",
            "Hidden Subject",
            false);

        Path exportFile = Files.createTempFile("ai-chat-export-compact-", ".pdf");
        try {
            service.exportChat(exportFile, AiChatExportService.Format.PDF, messages, 13, context, options);

            try (PDDocument document = Loader.loadPDF(exportFile.toFile())) {
                PDDocumentInformation info = document.getDocumentInformation();
                String extractedText = new PDFTextStripper().getText(document);

                assertThat(document.getDocumentCatalog().getDocumentOutline()).isNull();
                assertThat(info.getTitle()).isNotEqualTo("Should Not Appear");
                assertThat(info.getProducer()).isNotEqualTo("Hidden Producer");
                assertThat(info.getSubject()).isNotEqualTo("Hidden Subject");
                assertThat(extractedText.contains("Created with KorTTY by Daniel Mengel")).isTrue();
                // The footer now uses the shared, user-configurable export brand line (default text here).
                assertThat(extractedText.contains(de.kortty.core.ExportBranding.defaultFooterText())).isTrue();
                assertThat(extractedText.contains("Should Not Appear")).isFalse();
            }
        } finally {
            Files.deleteIfExists(exportFile);
        }
    }

    private int countChildren(PDOutlineItem root) {
        int count = 0;
        for (var child = root.getFirstChild(); child != null; child = child.getNextSibling()) {
            count++;
        }
        return count;
    }

    private String buildLongAssistantContent() {
        String repeatedParagraph = "Änderungen übernommen und Übertragung geprüft. "
            + "Die Auswertung bleibt gut lesbar und enthält zusätzliche Details für den Export. ";
        StringBuilder builder = new StringBuilder();
        for (int index = 0; index < 45; index++) {
            builder.append(repeatedParagraph);
        }
        builder.append("\n\n");
        builder.append("| Service | Status |\n");
        builder.append("| --- | --- |\n");
        builder.append("| api | ok |\n");
        builder.append("| worker | warn |\n\n");
        builder.append("```bash\n");
        builder.append("curl -I https://example.test\n");
        builder.append("echo \"Übertragung abgeschlossen\"\n");
        builder.append("```\n");
        return builder.toString();
    }

    private SavedAiChatMessage message(String role, String content, String profileName) {
        SavedAiChatMessage message = new SavedAiChatMessage();
        message.setRole(role);
        message.setContent(content);
        message.setAiProfileName(profileName);
        return message;
    }
}
