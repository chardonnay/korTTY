package de.kortty.core;

import de.kortty.model.SavedAiChatMessage;
import de.kortty.ui.I18n;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Exports AI chats into different file formats.
 */
public class AiChatExportService {

    public enum Format {
        PDF(".pdf", "ai.result.export.file.pdf"),
        MARKDOWN(".md", "ai.result.export.file.markdown"),
        TEXT(".txt", "ai.result.export.file.text");

        private final String extension;
        private final String filterKey;

        Format(String extension, String filterKey) {
            this.extension = extension;
            this.filterKey = filterKey;
        }

        public String getExtension() {
            return extension;
        }

        public String getFilterKey() {
            return filterKey;
        }
    }

    private static final Pattern CODE_BLOCK_PATTERN = Pattern.compile("(?s)```([\\w#+.-]*)\\n(.*?)```");
    private static final DateTimeFormatter PDF_HEADER_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    public void exportChat(Path targetFile, Format format, List<SavedAiChatMessage> messages, int fontSize) throws IOException {
        switch (format) {
            case PDF -> exportPdf(targetFile, messages, fontSize);
            case MARKDOWN -> Files.writeString(targetFile, buildMarkdownExport(messages), StandardCharsets.UTF_8);
            case TEXT -> Files.writeString(targetFile, buildPlainTextExport(messages), StandardCharsets.UTF_8);
        }
    }

    public String buildPlainTextExport(List<SavedAiChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (SavedAiChatMessage message : safeMessages(messages)) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append(resolveRoleLabel(message)).append("\n");
            builder.append(message.getContent() != null ? message.getContent().trim() : "");
        }
        return builder.toString();
    }

    public String buildMarkdownExport(List<SavedAiChatMessage> messages) {
        StringBuilder builder = new StringBuilder();
        for (SavedAiChatMessage message : safeMessages(messages)) {
            if (builder.length() > 0) {
                builder.append("\n\n");
            }
            builder.append("## ").append(resolveRoleLabel(message)).append("\n\n");
            if (!SavedAiChatMessage.ROLE_ASSISTANT.equals(message.getRole())) {
                builder.append(message.getContent() != null ? message.getContent().trim() : "");
                continue;
            }

            List<ContentSection> orderedSections = splitContent(message.getContent()).stream()
                .filter(section -> !section.content().isBlank())
                .toList();

            for (int i = 0; i < orderedSections.size(); i++) {
                ContentSection section = orderedSections.get(i);
                if (i > 0) {
                    builder.append("\n\n");
                }
                if (section.code()) {
                    builder.append("### ")
                        .append(I18n.get("ai.result.export.codeSection",
                            section.language() != null && !section.language().isBlank()
                                ? section.language()
                                : I18n.get("ai.result.code")))
                        .append("\n\n```")
                        .append(section.language() != null ? section.language() : "")
                        .append("\n")
                        .append(section.content())
                        .append("\n```");
                } else {
                    builder.append("### ").append(I18n.get("ai.result.export.textSection")).append("\n\n");
                    builder.append(section.content());
                }
            }
        }
        return builder.toString();
    }

    public void exportPdf(Path targetFile, List<SavedAiChatMessage> messages, int fontSize) throws IOException {
        LocalDateTime exportTimestamp = LocalDateTime.now();
        try (PDDocument document = new PDDocument()) {
            PDType1Font bodyFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            PDType1Font boldFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            PDType1Font codeFont = new PDType1Font(Standard14Fonts.FontName.COURIER);
            PDType1Font headerFont = new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE);
            float margin = 48f;
            PdfCursor cursor = startPdfPage(document, margin, exportTimestamp, headerFont);

            for (SavedAiChatMessage message : safeMessages(messages)) {
                cursor = writePdfParagraph(cursor, document, resolveRoleLabel(message), boldFont, Math.max(13f, fontSize + 1f), margin, exportTimestamp);
                if (SavedAiChatMessage.ROLE_ASSISTANT.equals(message.getRole())) {
                    List<ContentSection> sections = splitContent(message.getContent()).stream()
                        .filter(section -> !section.content().isBlank())
                        .toList();
                    for (ContentSection section : sections) {
                        String sectionTitle = section.code()
                            ? I18n.get("ai.result.export.codeSection",
                                section.language() != null && !section.language().isBlank()
                                    ? section.language()
                                    : I18n.get("ai.result.code"))
                            : I18n.get("ai.result.export.textSection");
                        cursor = writePdfParagraph(cursor, document, sectionTitle, boldFont, 11f, margin + 12f, exportTimestamp);
                        cursor = writePdfParagraph(
                            cursor,
                            document,
                            section.content(),
                            section.code() ? codeFont : bodyFont,
                            section.code() ? Math.max(10f, Math.min(13f, fontSize)) : Math.max(11f, Math.min(14f, fontSize)),
                            margin + 24f,
                            exportTimestamp);
                    }
                } else {
                    cursor = writePdfParagraph(cursor, document, message.getContent(), bodyFont, Math.max(11f, Math.min(14f, fontSize)), margin + 12f, exportTimestamp);
                }
                cursor = writePdfBlankLine(cursor, document, exportTimestamp);
            }

            finishPdfCursor(cursor);
            document.save(targetFile.toFile());
        }
    }

    private List<SavedAiChatMessage> safeMessages(List<SavedAiChatMessage> messages) {
        return messages != null ? messages : List.of();
    }

    private String resolveRoleLabel(SavedAiChatMessage message) {
        if (message == null) {
            return I18n.get("ai.result.assistant");
        }
        if (SavedAiChatMessage.ROLE_USER.equals(message.getRole())) {
            return I18n.get("ai.result.user");
        }
        String profileName = message.getAiProfileName();
        if (profileName == null || profileName.isBlank()) {
            return I18n.get("ai.result.assistant");
        }
        return I18n.get("ai.result.assistantWithProfile", profileName.trim());
    }

    private List<String> wrapForPdf(String content, int maxCharsPerLine) {
        List<String> lines = new ArrayList<>();
        for (String rawLine : (content != null ? content : "").replace("\t", "    ").split("\\R", -1)) {
            if (rawLine.isEmpty()) {
                lines.add("");
                continue;
            }
            String remaining = rawLine;
            while (remaining.length() > maxCharsPerLine) {
                int breakIndex = remaining.lastIndexOf(' ', maxCharsPerLine);
                if (breakIndex <= 0) {
                    breakIndex = maxCharsPerLine;
                }
                lines.add(remaining.substring(0, breakIndex));
                remaining = remaining.substring(Math.min(breakIndex + 1, remaining.length()));
            }
            lines.add(remaining);
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private String sanitizePdfLine(String line) {
        StringBuilder builder = new StringBuilder(line != null ? line.length() : 0);
        for (char c : (line != null ? line : "").toCharArray()) {
            if (c >= 32 && c <= 255) {
                builder.append(c);
            } else {
                builder.append('?');
            }
        }
        return builder.toString();
    }

    private List<ContentSection> splitContent(String content) {
        List<ContentSection> sections = new ArrayList<>();
        Matcher matcher = CODE_BLOCK_PATTERN.matcher(content != null ? content : "");
        int lastEnd = 0;
        while (matcher.find()) {
            if (matcher.start() > lastEnd) {
                sections.add(new ContentSection(false, null, content.substring(lastEnd, matcher.start()).trim()));
            }
            sections.add(new ContentSection(true, matcher.group(1), matcher.group(2)));
            lastEnd = matcher.end();
        }
        if (content != null && lastEnd < content.length()) {
            sections.add(new ContentSection(false, null, content.substring(lastEnd).trim()));
        }
        if (sections.isEmpty()) {
            sections.add(new ContentSection(false, null, content != null ? content : ""));
        }
        return sections;
    }

    private PdfCursor startPdfPage(PDDocument document, float margin, LocalDateTime exportTimestamp, PDType1Font headerFont) throws IOException {
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        PDPageContentStream contentStream = new PDPageContentStream(document, page);
        float pageTop = page.getMediaBox().getHeight() - margin;

        writePdfHeaderLine(contentStream, page, margin, exportTimestamp, headerFont);

        contentStream.beginText();
        contentStream.newLineAtOffset(margin, pageTop - 20f);
        return new PdfCursor(page, contentStream, pageTop - 20f, margin, margin);
    }

    private void writePdfHeaderLine(PDPageContentStream contentStream, PDPage page, float margin, LocalDateTime exportTimestamp, PDType1Font headerFont) throws IOException {
        float fontSize = 8.5f;
        String leftHeader = I18n.get("ai.result.export.pdf.header");
        String rightHeader = exportTimestamp.format(PDF_HEADER_TIMESTAMP_FORMAT);
        contentStream.beginText();
        contentStream.setFont(headerFont, fontSize);
        contentStream.newLineAtOffset(margin, page.getMediaBox().getHeight() - margin + 10f);
        contentStream.showText(sanitizePdfLine(leftHeader));
        contentStream.endText();

        float rightWidth = headerFont.getStringWidth(sanitizePdfLine(rightHeader)) / 1000f * fontSize;
        contentStream.beginText();
        contentStream.setFont(headerFont, fontSize);
        contentStream.newLineAtOffset(page.getMediaBox().getWidth() - margin - rightWidth, page.getMediaBox().getHeight() - margin + 10f);
        contentStream.showText(sanitizePdfLine(rightHeader));
        contentStream.endText();
    }

    private PdfCursor writePdfParagraph(PdfCursor cursor, PDDocument document, String text, PDType1Font font, float fontSize, float leftOffset, LocalDateTime exportTimestamp) throws IOException {
        float usableWidth = cursor.page().getMediaBox().getWidth() - leftOffset - cursor.margin();
        int maxCharsPerLine = Math.max(30, (int) (usableWidth / (fontSize * 0.56f)));
        float leading = fontSize + 4f;
        for (String line : wrapForPdf(text, maxCharsPerLine)) {
            cursor = ensurePdfLineCapacity(cursor, document, leading, exportTimestamp);
            String safeLine = sanitizePdfLine(line);
            cursor.stream().setFont(font, fontSize);
            if (Float.compare(leftOffset, cursor.currentX()) != 0) {
                cursor.stream().newLineAtOffset(leftOffset - cursor.currentX(), 0);
                cursor = cursor.withCurrentX(leftOffset);
            }
            if (!safeLine.isEmpty()) {
                cursor.stream().showText(safeLine);
            }
            cursor.stream().newLineAtOffset(-(cursor.currentX() - cursor.margin()), -leading);
            cursor = new PdfCursor(cursor.page(), cursor.stream(), cursor.currentY() - leading, cursor.margin(), cursor.margin());
        }
        return cursor;
    }

    private PdfCursor writePdfBlankLine(PdfCursor cursor, PDDocument document, LocalDateTime exportTimestamp) throws IOException {
        PdfCursor ensured = ensurePdfLineCapacity(cursor, document, 8f, exportTimestamp);
        ensured.stream().newLineAtOffset(-(ensured.currentX() - ensured.margin()), -8f);
        return new PdfCursor(ensured.page(), ensured.stream(), ensured.currentY() - 8f, ensured.margin(), ensured.margin());
    }

    private PdfCursor ensurePdfLineCapacity(PdfCursor cursor, PDDocument document, float leading, LocalDateTime exportTimestamp) throws IOException {
        if (cursor.currentY() - leading >= cursor.margin()) {
            return cursor;
        }
        finishPdfCursor(cursor);
        return startPdfPage(document, cursor.margin(), exportTimestamp, new PDType1Font(Standard14Fonts.FontName.HELVETICA_OBLIQUE));
    }

    private void finishPdfCursor(PdfCursor cursor) throws IOException {
        cursor.stream().endText();
        cursor.stream().close();
    }

    private record ContentSection(boolean code, String language, String content) {
    }

    private record PdfCursor(PDPage page, PDPageContentStream stream, float currentY, float margin, float currentX) {
        private PdfCursor withCurrentX(float currentX) {
            return new PdfCursor(page, stream, currentY, margin, currentX);
        }
    }
}
