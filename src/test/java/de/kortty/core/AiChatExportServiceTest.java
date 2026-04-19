package de.kortty.core;

import de.kortty.model.SavedAiChatMessage;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiChatExportServiceTest {

    @Test
    void plainTextExportContainsLocalizedRoleLabels() {
        AiChatExportService service = new AiChatExportService();

        String exported = service.buildPlainTextExport(List.of(
            message(SavedAiChatMessage.ROLE_USER, "Zeig mir die letzte Fehlermeldung", null),
            message(SavedAiChatMessage.ROLE_ASSISTANT, "Proxy ist nicht erreichbar.", "GPT Ops")));

        assertTrue(exported.contains("You:"));
        assertTrue(exported.contains("AI (GPT Ops):"));
        assertTrue(exported.contains("Proxy ist nicht erreichbar."));
    }

    @Test
    void markdownExportKeepsCodeBlocksAndTextSections() {
        AiChatExportService service = new AiChatExportService();

        String exported = service.buildMarkdownExport(List.of(
            message(SavedAiChatMessage.ROLE_ASSISTANT, "Analyse\n```bash\ncurl -I https://example.test\n```", "GPT Ops")));

        assertTrue(exported.contains("## AI (GPT Ops):"));
        assertTrue(exported.contains("### Text"));
        assertTrue(exported.contains("### Code (bash)"));
        assertTrue(exported.contains("curl -I https://example.test"));
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

                assertTrue(document.getNumberOfPages() > 1);
                assertEquals("Server Analyse Export", info.getTitle());
                assertEquals("KorTTY by Daniel Mengel", info.getProducer());
                assertEquals("AI chat export", info.getSubject());
                assertNotNull(outline);
                assertNotNull(root);
                assertEquals("Server Analyse Export", root.getTitle());
                assertTrue(countChildren(root) >= messages.size());
                assertTrue(extractedText.contains("Created with KorTTY by Daniel Mengel"));
                assertTrue(extractedText.contains("AI-Chat export from korTTY by Daniel Mengel"));
                assertTrue(extractedText.contains("Übertragung"));
                assertTrue(extractedText.contains("Änderungen übernommen"));
                assertTrue(extractedText.contains("curl -I https://example.test"));
                assertTrue(extractedText.contains("Service"));
                assertTrue(extractedText.contains("worker"));
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

                assertNull(document.getDocumentCatalog().getDocumentOutline());
                assertNotEquals("Should Not Appear", info.getTitle());
                assertNotEquals("Hidden Producer", info.getProducer());
                assertNotEquals("Hidden Subject", info.getSubject());
                assertTrue(extractedText.contains("Created with KorTTY by Daniel Mengel"));
                assertTrue(extractedText.contains("AI-Chat export from korTTY by Daniel Mengel"));
                assertFalse(extractedText.contains("Should Not Appear"));
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
