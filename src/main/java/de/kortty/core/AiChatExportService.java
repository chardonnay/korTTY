package de.kortty.core;

import de.kortty.model.SavedAiChatMessage;
import de.kortty.ui.I18n;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.destination.PDPageXYZDestination;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.util.Matrix;

import java.awt.Color;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

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

    private static final String SANS_FONT_RESOURCE = "/fonts/noto/NotoSans-Regular.ttf";
    private static final String SANS_BOLD_FONT_RESOURCE = "/fonts/noto/NotoSans-Bold.ttf";
    private static final String MONO_FONT_RESOURCE = "/fonts/noto/NotoSansMono-Regular.ttf";
    private static final String WATERMARK_TEXT = "AI-Chat export from korTTY by Daniel Mengel";
    private static final float PAGE_MARGIN = 48f;
    private static final float CONTENT_BOTTOM_Y = 62f;
    private static final float CONTENT_TOP_Y = PDRectangle.A4.getHeight() - 72f;
    private static final float BLOCK_SPACING = 10f;
    private static final float SECTION_SPACING = 16f;
    private static final float PARAGRAPH_FONT_SIZE = 11.5f;
    private static final float CODE_FONT_SIZE = 10.2f;
    private static final float TABLE_FONT_SIZE = 10.2f;
    private static final DateTimeFormatter PDF_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    public void exportChat(Path targetFile, Format format, List<SavedAiChatMessage> messages, int fontSize) throws IOException {
        AiChatExportContext exportContext = new AiChatExportContext(
            I18n.get("ai.result.export.title"),
            LocalDateTime.now(),
            null,
            safeMessages(messages).size());
        exportChat(targetFile, format, messages, fontSize, exportContext, AiPdfExportOptions.defaults(exportContext.title()));
    }

    public void exportChat(
        Path targetFile,
        Format format,
        List<SavedAiChatMessage> messages,
        int fontSize,
        AiChatExportContext exportContext,
        AiPdfExportOptions pdfOptions) throws IOException {

        switch (format) {
            case PDF -> exportPdf(targetFile, messages, exportContext, pdfOptions);
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

            List<AiChatContentSupport.ContentSection> orderedSections = AiChatContentSupport.splitContent(message.getContent()).stream()
                .filter(section -> !section.content().isBlank())
                .toList();

            for (int i = 0; i < orderedSections.size(); i++) {
                AiChatContentSupport.ContentSection section = orderedSections.get(i);
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

    private void exportPdf(
        Path targetFile,
        List<SavedAiChatMessage> messages,
        AiChatExportContext exportContext,
        AiPdfExportOptions pdfOptions) throws IOException {

        AiChatExportContext effectiveContext = exportContext != null
            ? exportContext
            : new AiChatExportContext(I18n.get("ai.result.export.title"), LocalDateTime.now(), null, safeMessages(messages).size());
        AiPdfExportOptions effectiveOptions = pdfOptions != null
            ? pdfOptions
            : AiPdfExportOptions.defaults(effectiveContext.title());

        try (PDDocument document = new PDDocument()) {
            PdfFonts fonts = loadFonts(document);
            if (effectiveOptions.includeDocumentMetadata()) {
                applyDocumentMetadata(document, effectiveContext, effectiveOptions);
            }

            PdfTheme theme = PdfTheme.forLayout(effectiveOptions.layoutMode());
            RenderState state = new RenderState(document, fonts, theme, effectiveContext, effectiveOptions);
            LayoutCursor cursor = openFirstPage(state);
            cursor = renderOpeningSection(state, cursor);

            int messageIndex = 0;
            for (SavedAiChatMessage message : safeMessages(messages)) {
                cursor = renderMessage(state, cursor, message, messageIndex++);
            }
            closeCursor(cursor);

            applyPageChrome(state);
            if (effectiveOptions.includeBookmarks()) {
                applyBookmarks(document, effectiveContext.title(), state.bookmarks);
            }

            document.save(targetFile.toFile());
        }
    }

    private List<SavedAiChatMessage> safeMessages(List<SavedAiChatMessage> messages) {
        return messages != null ? messages : List.of();
    }

    private PdfFonts loadFonts(PDDocument document) throws IOException {
        return new PdfFonts(
            loadFont(document, SANS_FONT_RESOURCE),
            loadFont(document, SANS_BOLD_FONT_RESOURCE),
            loadFont(document, MONO_FONT_RESOURCE));
    }

    private PDType0Font loadFont(PDDocument document, String resourcePath) throws IOException {
        try (InputStream inputStream = AiChatExportService.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Missing PDF font resource " + resourcePath);
            }
            return PDType0Font.load(document, inputStream, true);
        }
    }

    private void applyDocumentMetadata(
        PDDocument document,
        AiChatExportContext context,
        AiPdfExportOptions options) {

        PDDocumentInformation information = document.getDocumentInformation();
        information.setTitle(options.documentTitle() != null ? options.documentTitle() : context.title());
        information.setProducer(options.documentProducer());
        information.setSubject(options.documentSubject());
    }

    private LayoutCursor openFirstPage(RenderState state) throws IOException {
        return openNewPage(state, null);
    }

    private LayoutCursor openNewPage(RenderState state, LayoutCursor currentCursor) throws IOException {
        if (currentCursor != null) {
            currentCursor.stream().close();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        state.document.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(state.document, page);
        return new LayoutCursor(page, stream, CONTENT_TOP_Y);
    }

    private void closeCursor(LayoutCursor cursor) throws IOException {
        if (cursor != null) {
            cursor.stream().close();
        }
    }

    private LayoutCursor renderOpeningSection(RenderState state, LayoutCursor cursor) throws IOException {
        if (state.options.layoutMode() == AiPdfExportOptions.LayoutMode.COMPACT) {
            return renderCompactOpeningSection(state, cursor);
        }
        return renderReportOpeningSection(state, cursor);
    }

    private LayoutCursor renderReportOpeningSection(RenderState state, LayoutCursor cursor) throws IOException {
        float width = getContentWidth(cursor.page());
        float heroHeight = 112f;
        float heroBottom = cursor.y - heroHeight;
        drawFilledRect(cursor.stream(), PAGE_MARGIN, heroBottom, width, heroHeight, state.theme.heroFill);

        drawText(
            cursor.stream(),
            state.fonts.sansBold(),
            10.5f,
            state.theme.heroMetaText,
            PAGE_MARGIN + 18f,
            cursor.y - 24f,
            I18n.get("ai.result.export.pdf.reportTitle"));
        drawText(
            cursor.stream(),
            state.fonts.sansBold(),
            24f,
            state.theme.heroText,
            PAGE_MARGIN + 18f,
            cursor.y - 56f,
            fitTextToWidth(state.context.title(), state.fonts.sansBold(), 24f, width - 36f));

        cursor = cursor.withY(heroBottom - 22f);
        drawText(
            cursor.stream(),
            state.fonts.sans(),
            10f,
            state.theme.mutedText,
            PAGE_MARGIN,
            cursor.y,
            buildMetaLine(state.context));
        drawText(
            cursor.stream(),
            state.fonts.sansBold(),
            10.2f,
            state.theme.text,
            PAGE_MARGIN,
            cursor.y - 18f,
            I18n.get("ai.result.export.pdf.header"));
        drawLine(cursor.stream(), PAGE_MARGIN, cursor.y - 28f, PAGE_MARGIN + width, cursor.y - 28f, state.theme.ruleColor, 0.8f);
        return cursor.withY(cursor.y - 42f);
    }

    private LayoutCursor renderCompactOpeningSection(RenderState state, LayoutCursor cursor) throws IOException {
        float width = getContentWidth(cursor.page());
        drawText(
            cursor.stream(),
            state.fonts.sansBold(),
            21f,
            state.theme.text,
            PAGE_MARGIN,
            cursor.y,
            fitTextToWidth(state.context.title(), state.fonts.sansBold(), 21f, width));
        drawText(
            cursor.stream(),
            state.fonts.sans(),
            10.2f,
            state.theme.mutedText,
            PAGE_MARGIN,
            cursor.y - 22f,
            buildMetaLine(state.context));
        drawText(
            cursor.stream(),
            state.fonts.sans(),
            9.4f,
            state.theme.mutedText,
            PAGE_MARGIN,
            cursor.y - 38f,
            I18n.get("ai.result.export.pdf.header"));
        drawLine(cursor.stream(), PAGE_MARGIN, cursor.y - 48f, PAGE_MARGIN + width, cursor.y - 48f, state.theme.ruleColor, 0.75f);
        return cursor.withY(cursor.y - 62f);
    }

    private String buildMetaLine(AiChatExportContext context) {
        List<String> parts = new ArrayList<>();
        parts.add(I18n.get("ai.result.export.pdf.meta.exportedAt") + ": " + context.exportTimestamp().format(PDF_TIMESTAMP_FORMAT));
        if (context.activeProfileName() != null && !context.activeProfileName().isBlank()) {
            parts.add(I18n.get("ai.result.export.pdf.meta.profile") + ": " + context.activeProfileName());
        }
        parts.add(I18n.get("ai.result.export.pdf.meta.messages") + ": " + context.messageCount());
        return String.join(" | ", parts);
    }

    private LayoutCursor renderMessage(RenderState state, LayoutCursor cursor, SavedAiChatMessage message, int messageIndex) throws IOException {
        cursor = ensureSpace(state, cursor, 28f);
        state.bookmarks.add(new BookmarkTarget(buildBookmarkLabel(message, messageIndex), cursor.page(), cursor.y + 14f));

        cursor = renderMessageHeader(state, cursor, message);
        if (SavedAiChatMessage.ROLE_ASSISTANT.equals(message.getRole())) {
            for (AiChatContentSupport.ContentSection section : AiChatContentSupport.splitContent(message.getContent())) {
                if (section.content().isBlank()) {
                    continue;
                }
                if (section.code()) {
                    cursor = renderCodeBlock(state, cursor, section.language(), section.content());
                } else {
                    cursor = renderStructuredText(state, cursor, section.content(), message);
                }
            }
        } else {
            cursor = renderStructuredText(state, cursor, message.getContent(), message);
        }
        return cursor.withY(cursor.y - SECTION_SPACING);
    }

    private LayoutCursor renderMessageHeader(RenderState state, LayoutCursor cursor, SavedAiChatMessage message) throws IOException {
        String roleLabel = resolveRoleLabel(message);
        boolean userMessage = SavedAiChatMessage.ROLE_USER.equals(message.getRole());
        Color accentColor = userMessage ? state.theme.userAccent : state.theme.assistantAccent;

        if (state.options.layoutMode() == AiPdfExportOptions.LayoutMode.COMPACT) {
            drawText(cursor.stream(), state.fonts.sansBold(), 12.2f, accentColor, PAGE_MARGIN, cursor.y, roleLabel);
            float labelWidth = textWidth(state.fonts.sansBold(), 12.2f, roleLabel);
            drawLine(
                cursor.stream(),
                PAGE_MARGIN + labelWidth + 10f,
                cursor.y + 3f,
                PAGE_MARGIN + getContentWidth(cursor.page()),
                cursor.y + 3f,
                state.theme.ruleColor,
                0.7f);
            return cursor.withY(cursor.y - 22f);
        }

        float labelWidth = textWidth(state.fonts.sansBold(), 11.5f, roleLabel) + 18f;
        float labelHeight = 18f;
        drawFilledRect(cursor.stream(), PAGE_MARGIN, cursor.y - labelHeight, labelWidth, labelHeight, accentColor);
        drawText(cursor.stream(), state.fonts.sansBold(), 11.5f, Color.WHITE, PAGE_MARGIN + 9f, cursor.y - 13f, roleLabel);
        return cursor.withY(cursor.y - 24f);
    }

    private LayoutCursor renderStructuredText(
        RenderState state,
        LayoutCursor cursor,
        String content,
        SavedAiChatMessage message) throws IOException {

        for (AiChatContentSupport.StructuredTextBlock block : AiChatContentSupport.splitStructuredText(content)) {
            if (block.type() == AiChatContentSupport.StructuredTextBlock.Type.TABLE) {
                cursor = renderTableBlock(state, cursor, block.tableRows());
            } else {
                cursor = renderParagraphBlock(state, cursor, block.text(), message);
            }
        }
        return cursor;
    }

    private LayoutCursor renderParagraphBlock(
        RenderState state,
        LayoutCursor cursor,
        String text,
        SavedAiChatMessage message) throws IOException {

        boolean userMessage = SavedAiChatMessage.ROLE_USER.equals(message.getRole());
        PanelStyle panelStyle = userMessage ? state.theme.userPanel : state.theme.assistantPanel;
        float innerWidth = getContentWidth(cursor.page()) - (panelStyle.paddingX * 2f);
        List<String> remainingLines = new ArrayList<>(wrapParagraphText(text, state.fonts.sans(), PARAGRAPH_FONT_SIZE, innerWidth));
        float leading = PARAGRAPH_FONT_SIZE + 4.4f;

        while (!remainingLines.isEmpty()) {
            cursor = ensureSpace(state, cursor, panelStyle.minimumHeight());
            int maxLines = Math.max(1, (int) Math.floor((cursor.availableHeight() - (panelStyle.paddingTop + panelStyle.paddingBottom)) / leading));
            int lineCount = Math.min(maxLines, remainingLines.size());
            List<String> chunk = new ArrayList<>(remainingLines.subList(0, lineCount));
            float blockHeight = panelStyle.paddingTop + panelStyle.paddingBottom + (chunk.size() * leading);

            drawPanel(cursor.stream(), PAGE_MARGIN, cursor.y, getContentWidth(cursor.page()), blockHeight, panelStyle, state.theme.ruleColor);
            float textY = cursor.y - panelStyle.paddingTop - PARAGRAPH_FONT_SIZE;
            for (String line : chunk) {
                drawText(cursor.stream(), state.fonts.sans(), PARAGRAPH_FONT_SIZE, state.theme.text, PAGE_MARGIN + panelStyle.paddingX, textY, line);
                textY -= leading;
            }

            remainingLines.subList(0, lineCount).clear();
            cursor = cursor.withY(cursor.y - blockHeight - BLOCK_SPACING);
        }
        return cursor;
    }

    private LayoutCursor renderCodeBlock(
        RenderState state,
        LayoutCursor cursor,
        String language,
        String code) throws IOException {

        String languageLabel = language != null && !language.isBlank() ? language : I18n.get("ai.result.code");
        PanelStyle panelStyle = state.theme.codePanel;
        float width = getContentWidth(cursor.page());
        float innerWidth = width - (panelStyle.paddingX * 2f);
        List<String> remainingLines = new ArrayList<>(wrapCodeText(code, state.fonts.mono(), CODE_FONT_SIZE, innerWidth));
        float leading = CODE_FONT_SIZE + 3.8f;
        float headerHeight = 18f;

        while (!remainingLines.isEmpty()) {
            cursor = ensureSpace(state, cursor, panelStyle.minimumHeight() + headerHeight);
            int maxLines = Math.max(1,
                (int) Math.floor((cursor.availableHeight() - headerHeight - panelStyle.paddingTop - panelStyle.paddingBottom) / leading));
            int lineCount = Math.min(maxLines, remainingLines.size());
            List<String> chunk = new ArrayList<>(remainingLines.subList(0, lineCount));
            float blockHeight = headerHeight + panelStyle.paddingTop + panelStyle.paddingBottom + (chunk.size() * leading);

            drawPanel(cursor.stream(), PAGE_MARGIN, cursor.y, width, blockHeight, panelStyle, state.theme.ruleColor);
            if (panelStyle.headerFill != null) {
                drawFilledRect(cursor.stream(), PAGE_MARGIN, cursor.y - headerHeight, width, headerHeight, panelStyle.headerFill);
            }
            drawText(
                cursor.stream(),
                state.fonts.sansBold(),
                9.4f,
                panelStyle.headerText,
                PAGE_MARGIN + panelStyle.paddingX,
                cursor.y - 12.5f,
                languageLabel);

            float codeY = cursor.y - headerHeight - panelStyle.paddingTop - CODE_FONT_SIZE;
            for (String line : chunk) {
                drawText(
                    cursor.stream(),
                    state.fonts.mono(),
                    CODE_FONT_SIZE,
                    panelStyle.textColor,
                    PAGE_MARGIN + panelStyle.paddingX,
                    codeY,
                    line);
                codeY -= leading;
            }

            remainingLines.subList(0, lineCount).clear();
            cursor = cursor.withY(cursor.y - blockHeight - BLOCK_SPACING);
        }
        return cursor;
    }

    private LayoutCursor renderTableBlock(
        RenderState state,
        LayoutCursor cursor,
        List<List<String>> rawRows) throws IOException {

        AiMarkdownTableSupport.RenderedMarkdownTable table = AiMarkdownTableSupport.buildRenderedTable(rawRows);
        int columnCount = Math.max(1, table.header().size());
        float width = getContentWidth(cursor.page());
        float[] columnWidths = new float[columnCount];
        float totalColumnWidth = 0f;
        for (int index = 0; index < columnCount; index++) {
            columnWidths[index] = width / columnCount;
            totalColumnWidth += columnWidths[index];
        }
        columnWidths[columnCount - 1] += width - totalColumnWidth;

        float paddingX = 6f;
        float paddingY = 5f;
        float leading = TABLE_FONT_SIZE + 3.4f;
        List<RowLayout> rows = new ArrayList<>();
        rows.add(buildRowLayout(table.header(), columnWidths, paddingX, paddingY, leading, state.fonts.sansBold(), true));
        for (List<String> row : table.rows()) {
            rows.add(buildRowLayout(row, columnWidths, paddingX, paddingY, leading, state.fonts.sans(), false));
        }

        int nextRowIndex = 1;
        while (nextRowIndex <= table.rows().size()) {
            RowLayout headerRow = rows.getFirst();
            cursor = ensureSpace(state, cursor, headerRow.height() + paddingY + leading);
            if (cursor.availableHeight() < headerRow.height() + paddingY + leading) {
                cursor = openNewPage(state, cursor);
            }

            cursor = renderTableRow(state, cursor, headerRow, columnWidths, true, false);
            boolean renderedAnyDataRow = false;
            while (nextRowIndex < rows.size()) {
                RowLayout row = rows.get(nextRowIndex);
                if (cursor.availableHeight() < row.height()) {
                    break;
                }
                cursor = renderTableRow(state, cursor, row, columnWidths, false, renderedAnyDataRow);
                nextRowIndex++;
                renderedAnyDataRow = true;
            }
            if (nextRowIndex < rows.size()) {
                cursor = openNewPage(state, cursor);
            }
        }
        return cursor.withY(cursor.y - BLOCK_SPACING);
    }

    private RowLayout buildRowLayout(
        List<String> values,
        float[] columnWidths,
        float paddingX,
        float paddingY,
        float leading,
        PDFont font,
        boolean header) throws IOException {

        List<List<String>> cellLines = new ArrayList<>(columnWidths.length);
        int maxLineCount = 1;
        for (int column = 0; column < columnWidths.length; column++) {
            String value = column < values.size() ? values.get(column) : "";
            List<String> wrappedLines = wrapParagraphText(value, font, TABLE_FONT_SIZE, columnWidths[column] - (paddingX * 2f));
            cellLines.add(wrappedLines);
            maxLineCount = Math.max(maxLineCount, wrappedLines.size());
        }
        float height = (paddingY * 2f) + (maxLineCount * leading);
        return new RowLayout(cellLines, height, header);
    }

    private LayoutCursor renderTableRow(
        RenderState state,
        LayoutCursor cursor,
        RowLayout row,
        float[] columnWidths,
        boolean header,
        boolean zebraToggle) throws IOException {

        float x = PAGE_MARGIN;
        float textLeading = TABLE_FONT_SIZE + 3.4f;
        for (int column = 0; column < columnWidths.length; column++) {
            float columnWidth = columnWidths[column];
            Color fillColor = header
                ? state.theme.tableHeaderFill
                : (zebraToggle ? state.theme.tableRowFillAlt : state.theme.tableRowFill);
            drawFilledRect(cursor.stream(), x, cursor.y - row.height(), columnWidth, row.height(), fillColor);
            drawStrokedRect(cursor.stream(), x, cursor.y - row.height(), columnWidth, row.height(), state.theme.tableBorder, 0.65f);

            List<String> lines = row.lines().get(column);
            float textY = cursor.y - 6f - TABLE_FONT_SIZE;
            for (String line : lines) {
                float lineX = x + 6f;
                if (!header && AiMarkdownTableSupport.isNumericLike(line)) {
                    float availableWidth = columnWidth - 12f;
                    float lineWidth = textWidth(state.fonts.sans(), TABLE_FONT_SIZE, line);
                    lineX = x + 6f + Math.max(0f, availableWidth - lineWidth);
                }
                drawText(
                    cursor.stream(),
                    header ? state.fonts.sansBold() : state.fonts.sans(),
                    TABLE_FONT_SIZE,
                    header ? state.theme.tableHeaderText : state.theme.text,
                    lineX,
                    textY,
                    line);
                textY -= textLeading;
            }
            x += columnWidth;
        }
        return cursor.withY(cursor.y - row.height());
    }

    private LayoutCursor ensureSpace(RenderState state, LayoutCursor cursor, float minimumHeight) throws IOException {
        if (cursor.availableHeight() >= minimumHeight) {
            return cursor;
        }
        return openNewPage(state, cursor);
    }

    private void applyPageChrome(RenderState state) throws IOException {
        int totalPages = state.document.getNumberOfPages();
        for (int pageIndex = 0; pageIndex < totalPages; pageIndex++) {
            PDPage page = state.document.getPage(pageIndex);
            try (PDPageContentStream stream = new PDPageContentStream(state.document, page, AppendMode.APPEND, true, true)) {
                if (pageIndex > 0) {
                    drawContinuingHeader(state, page, stream);
                }
                drawFooter(state, page, stream, pageIndex + 1, totalPages);
            }
            if (pageIndex == 0 && state.options.layoutMode() == AiPdfExportOptions.LayoutMode.REPORT) {
                applyFirstPageWatermark(state, page);
            }
        }
    }

    private void drawContinuingHeader(RenderState state, PDPage page, PDPageContentStream stream) throws IOException {
        float pageHeight = page.getMediaBox().getHeight();
        float pageWidth = page.getMediaBox().getWidth();
        drawText(
            stream,
            state.fonts.sans(),
            8.8f,
            state.theme.mutedText,
            PAGE_MARGIN,
            pageHeight - 28f,
            fitTextToWidth(state.context.title(), state.fonts.sans(), 8.8f, pageWidth - (PAGE_MARGIN * 2f) - 120f));
        String exportedAt = state.context.exportTimestamp().format(PDF_TIMESTAMP_FORMAT);
        float exportedWidth = textWidth(state.fonts.sans(), 8.8f, exportedAt);
        drawText(
            stream,
            state.fonts.sans(),
            8.8f,
            state.theme.mutedText,
            pageWidth - PAGE_MARGIN - exportedWidth,
            pageHeight - 28f,
            exportedAt);
        drawLine(stream, PAGE_MARGIN, pageHeight - 34f, pageWidth - PAGE_MARGIN, pageHeight - 34f, state.theme.ruleColor, 0.7f);
    }

    private void drawFooter(RenderState state, PDPage page, PDPageContentStream stream, int pageNumber, int totalPages) throws IOException {
        float pageWidth = page.getMediaBox().getWidth();
        drawLine(stream, PAGE_MARGIN, 42f, pageWidth - PAGE_MARGIN, 42f, state.theme.ruleColor, 0.7f);
        drawText(
            stream,
            state.fonts.sans(),
            8.6f,
            state.theme.mutedText,
            PAGE_MARGIN,
            28f,
            WATERMARK_TEXT);
        String pageLabel = I18n.get("ai.result.export.pdf.pageNumber", pageNumber, totalPages);
        float pageLabelWidth = textWidth(state.fonts.sans(), 8.6f, pageLabel);
        drawText(
            stream,
            state.fonts.sans(),
            8.6f,
            state.theme.mutedText,
            pageWidth - PAGE_MARGIN - pageLabelWidth,
            28f,
            pageLabel);
    }

    private void applyFirstPageWatermark(RenderState state, PDPage page) throws IOException {
        try (PDPageContentStream stream = new PDPageContentStream(state.document, page, AppendMode.APPEND, true, true)) {
            PDExtendedGraphicsState graphicsState = new PDExtendedGraphicsState();
            graphicsState.setNonStrokingAlphaConstant(0.12f);
            stream.saveGraphicsState();
            stream.setGraphicsStateParameters(graphicsState);
            float centerX = page.getMediaBox().getWidth() / 2f;
            float centerY = page.getMediaBox().getHeight() / 2f;
            float fontSize = 30f;
            float watermarkWidth = textWidth(state.fonts.sansBold(), fontSize, WATERMARK_TEXT);
            while (fontSize > 16f && watermarkWidth > page.getMediaBox().getWidth() - 120f) {
                fontSize -= 1f;
                watermarkWidth = textWidth(state.fonts.sansBold(), fontSize, WATERMARK_TEXT);
            }
            stream.transform(Matrix.getRotateInstance(Math.toRadians(38), centerX, centerY));
            drawText(
                stream,
                state.fonts.sansBold(),
                fontSize,
                state.theme.watermarkColor,
                centerX - (watermarkWidth / 2f),
                centerY,
                WATERMARK_TEXT);
            stream.restoreGraphicsState();
        }
    }

    private void applyBookmarks(PDDocument document, String title, List<BookmarkTarget> targets) {
        PDDocumentOutline outline = new PDDocumentOutline();
        document.getDocumentCatalog().setDocumentOutline(outline);
        PDOutlineItem root = new PDOutlineItem();
        root.setTitle(title != null && !title.isBlank() ? title : I18n.get("ai.result.export.title"));
        if (!document.getPages().iterator().hasNext()) {
            return;
        }
        PDPageXYZDestination rootDestination = new PDPageXYZDestination();
        rootDestination.setPage(document.getPage(0));
        rootDestination.setTop((int) document.getPage(0).getMediaBox().getHeight());
        root.setDestination(rootDestination);
        outline.addLast(root);

        for (BookmarkTarget target : targets) {
            PDOutlineItem item = new PDOutlineItem();
            item.setTitle(target.label());
            PDPageXYZDestination destination = new PDPageXYZDestination();
            destination.setPage(target.page());
            destination.setTop((int) target.topY());
            item.setDestination(destination);
            root.addLast(item);
        }

        root.openNode();
        outline.openNode();
    }

    private String buildBookmarkLabel(SavedAiChatMessage message, int messageIndex) {
        String roleLabel = resolveRoleLabel(message).replace(":", "").trim();
        String content = message != null && message.getContent() != null ? message.getContent() : "";
        String excerpt = content
            .replaceAll("(?s)```.*?```", " ")
            .replace("\n", " ")
            .replace("\r", " ")
            .replaceAll("\\s+", " ")
            .trim();
        if (excerpt.length() > 56) {
            excerpt = excerpt.substring(0, 53).trim() + "...";
        }
        if (excerpt.isBlank()) {
            return roleLabel + " " + (messageIndex + 1);
        }
        return roleLabel + " - " + excerpt;
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

    private List<String> wrapParagraphText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> wrappedLines = new ArrayList<>();
        String normalized = normalizeForPdf(text);
        for (String rawLine : normalized.split("\\R", -1)) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                wrappedLines.add("");
                continue;
            }
            wrapWordsIntoLines(wrappedLines, line, font, fontSize, maxWidth);
        }
        if (wrappedLines.isEmpty()) {
            wrappedLines.add("");
        }
        return wrappedLines;
    }

    private void wrapWordsIntoLines(
        List<String> wrappedLines,
        String line,
        PDFont font,
        float fontSize,
        float maxWidth) throws IOException {

        String[] words = line.split("\\s+");
        String currentLine = "";
        for (String rawWord : words) {
            String word = prepareText(font, rawWord);
            String candidate = currentLine.isEmpty() ? word : currentLine + " " + word;
            if (currentLine.isEmpty() || textWidth(font, fontSize, candidate) <= maxWidth) {
                currentLine = candidate;
                continue;
            }

            wrappedLines.add(currentLine);
            if (textWidth(font, fontSize, word) <= maxWidth) {
                currentLine = word;
                continue;
            }

            List<String> hardWrappedWord = breakLongToken(word, font, fontSize, maxWidth);
            wrappedLines.addAll(hardWrappedWord.subList(0, hardWrappedWord.size() - 1));
            currentLine = hardWrappedWord.getLast();
        }
        if (!currentLine.isEmpty()) {
            wrappedLines.add(currentLine);
        }
    }

    private List<String> wrapCodeText(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> wrappedLines = new ArrayList<>();
        String normalized = normalizeForPdf(text).replace("\t", "    ");
        for (String rawLine : normalized.split("\\R", -1)) {
            String line = prepareText(font, rawLine);
            if (line.isEmpty()) {
                wrappedLines.add("");
                continue;
            }
            if (textWidth(font, fontSize, line) <= maxWidth) {
                wrappedLines.add(line);
                continue;
            }
            wrappedLines.addAll(breakLongToken(line, font, fontSize, maxWidth));
        }
        if (wrappedLines.isEmpty()) {
            wrappedLines.add("");
        }
        return wrappedLines;
    }

    private List<String> breakLongToken(String token, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> parts = new ArrayList<>();
        String remaining = token;
        while (!remaining.isEmpty()) {
            int fittingLength = findLongestFittingLength(remaining, font, fontSize, maxWidth);
            parts.add(remaining.substring(0, fittingLength));
            remaining = remaining.substring(fittingLength);
        }
        return parts;
    }

    private int findLongestFittingLength(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        int low = 1;
        int high = text.length();
        int best = 1;
        while (low <= high) {
            int mid = (low + high) / 2;
            String candidate = text.substring(0, mid);
            if (textWidth(font, fontSize, candidate) <= maxWidth) {
                best = mid;
                low = mid + 1;
            } else {
                high = mid - 1;
            }
        }
        return best;
    }

    private String normalizeForPdf(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '\r') {
                continue;
            }
            if (character == '\n' || character == '\t' || character >= 32) {
                builder.append(character);
            }
        }
        return builder.toString();
    }

    private String prepareText(PDFont font, String text) throws IOException {
        StringBuilder builder = new StringBuilder();
        String safeText = text != null ? text : "";
        for (int index = 0; index < safeText.length(); ) {
            int codePoint = safeText.codePointAt(index);
            String glyph = new String(Character.toChars(codePoint));
            if (Character.isWhitespace(codePoint)) {
                builder.append(glyph);
            } else if (fontWillRender(font, glyph)) {
                builder.append(glyph);
            } else {
                builder.append('?');
            }
            index += Character.charCount(codePoint);
        }
        return builder.toString();
    }

    private boolean fontWillRender(PDFont font, String glyph) throws IOException {
        try {
            font.encode(glyph);
            return true;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    private float textWidth(PDFont font, float fontSize, String text) throws IOException {
        String safeText = prepareText(font, text);
        return font.getStringWidth(safeText) / 1000f * fontSize;
    }

    private String fitTextToWidth(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        String safeText = prepareText(font, text);
        if (textWidth(font, fontSize, safeText) <= maxWidth) {
            return safeText;
        }
        String ellipsis = "...";
        int endIndex = safeText.length();
        while (endIndex > 1) {
            String candidate = safeText.substring(0, endIndex).trim() + ellipsis;
            if (textWidth(font, fontSize, candidate) <= maxWidth) {
                return candidate;
            }
            endIndex--;
        }
        return ellipsis;
    }

    private float getContentWidth(PDPage page) {
        return page.getMediaBox().getWidth() - (PAGE_MARGIN * 2f);
    }

    private void drawText(PDPageContentStream stream, PDFont font, float fontSize, Color color, float x, float y, String text) throws IOException {
        String safeText = prepareText(font, text);
        stream.beginText();
        stream.setFont(font, fontSize);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(safeText);
        stream.endText();
    }

    private void drawFilledRect(PDPageContentStream stream, float x, float y, float width, float height, Color fillColor) throws IOException {
        stream.setNonStrokingColor(fillColor);
        stream.addRect(x, y, width, height);
        stream.fill();
    }

    private void drawStrokedRect(PDPageContentStream stream, float x, float y, float width, float height, Color strokeColor, float lineWidth)
        throws IOException {

        stream.setStrokingColor(strokeColor);
        stream.setLineWidth(lineWidth);
        stream.addRect(x, y, width, height);
        stream.stroke();
    }

    private void drawPanel(
        PDPageContentStream stream,
        float x,
        float topY,
        float width,
        float height,
        PanelStyle panelStyle,
        Color fallbackBorderColor) throws IOException {

        if (panelStyle.fillColor != null) {
            drawFilledRect(stream, x, topY - height, width, height, panelStyle.fillColor);
        }
        if (panelStyle.borderColor != null) {
            drawStrokedRect(stream, x, topY - height, width, height, panelStyle.borderColor, panelStyle.borderWidth);
        } else if (fallbackBorderColor != null && panelStyle.borderWidth > 0f) {
            drawStrokedRect(stream, x, topY - height, width, height, fallbackBorderColor, panelStyle.borderWidth);
        }
    }

    private void drawLine(PDPageContentStream stream, float x1, float y1, float x2, float y2, Color strokeColor, float lineWidth)
        throws IOException {

        stream.setStrokingColor(strokeColor);
        stream.setLineWidth(lineWidth);
        stream.moveTo(x1, y1);
        stream.lineTo(x2, y2);
        stream.stroke();
    }

    private record LayoutCursor(PDPage page, PDPageContentStream stream, float y) {
        private float availableHeight() {
            return y - CONTENT_BOTTOM_Y;
        }

        private LayoutCursor withY(float y) {
            return new LayoutCursor(page, stream, y);
        }
    }

    private record PdfFonts(PDFont sans, PDFont sansBold, PDFont mono) {
    }

    private record BookmarkTarget(String label, PDPage page, float topY) {
    }

    private record RowLayout(List<List<String>> lines, float height, boolean header) {
    }

    private static final class RenderState {
        private final PDDocument document;
        private final PdfFonts fonts;
        private final PdfTheme theme;
        private final AiChatExportContext context;
        private final AiPdfExportOptions options;
        private final List<BookmarkTarget> bookmarks = new ArrayList<>();

        private RenderState(
            PDDocument document,
            PdfFonts fonts,
            PdfTheme theme,
            AiChatExportContext context,
            AiPdfExportOptions options) {
            this.document = document;
            this.fonts = fonts;
            this.theme = theme;
            this.context = context;
            this.options = options;
        }
    }

    private record PanelStyle(
        Color fillColor,
        Color borderColor,
        float borderWidth,
        float paddingX,
        float paddingTop,
        float paddingBottom,
        Color textColor,
        Color headerFill,
        Color headerText) {

        private float minimumHeight() {
            return paddingTop + paddingBottom + 18f;
        }
    }

    private record PdfTheme(
        Color heroFill,
        Color heroText,
        Color heroMetaText,
        Color text,
        Color mutedText,
        Color ruleColor,
        Color userAccent,
        Color assistantAccent,
        PanelStyle userPanel,
        PanelStyle assistantPanel,
        PanelStyle codePanel,
        Color tableHeaderFill,
        Color tableHeaderText,
        Color tableRowFill,
        Color tableRowFillAlt,
        Color tableBorder,
        Color watermarkColor) {

        private static PdfTheme forLayout(AiPdfExportOptions.LayoutMode layoutMode) {
            if (layoutMode == AiPdfExportOptions.LayoutMode.COMPACT) {
                return new PdfTheme(
                    Color.WHITE,
                    new Color(0x1F, 0x29, 0x37),
                    new Color(0xDB, 0xE7, 0xFF),
                    new Color(0x1F, 0x29, 0x37),
                    new Color(0x60, 0x72, 0x89),
                    new Color(0xD0, 0xD7, 0xDE),
                    new Color(0x00, 0x66, 0xCC),
                    new Color(0x4B, 0x55, 0x63),
                    new PanelStyle(null, new Color(0xD0, 0xD7, 0xDE), 0.65f, 10f, 8f, 8f, new Color(0x1F, 0x29, 0x37), null, null),
                    new PanelStyle(null, new Color(0xD0, 0xD7, 0xDE), 0.65f, 10f, 8f, 8f, new Color(0x1F, 0x29, 0x37), null, null),
                    new PanelStyle(null, new Color(0x9F, 0xA9, 0xB7), 0.75f, 10f, 8f, 8f, new Color(0x1F, 0x29, 0x37), null, new Color(0x1F, 0x29, 0x37)),
                    new Color(0xF3, 0xF4, 0xF6),
                    new Color(0x1F, 0x29, 0x37),
                    Color.WHITE,
                    new Color(0xFA, 0xFA, 0xFA),
                    new Color(0xC7, 0xD0, 0xD9),
                    new Color(0xB9, 0xC9, 0xDE));
            }
            return new PdfTheme(
                new Color(0x00, 0x66, 0xCC),
                Color.WHITE,
                new Color(0xD9, 0xEA, 0xFF),
                new Color(0x1F, 0x29, 0x37),
                new Color(0x5E, 0x6E, 0x82),
                new Color(0xD4, 0xDE, 0xEA),
                new Color(0x00, 0x66, 0xCC),
                new Color(0x4B, 0x55, 0x63),
                new PanelStyle(new Color(0xEA, 0xF3, 0xFF), new Color(0xC8, 0xDE, 0xF7), 0.75f, 12f, 9f, 9f, new Color(0x1F, 0x29, 0x37), null, null),
                new PanelStyle(new Color(0xF4, 0xF6, 0xF8), new Color(0xDD, 0xE2, 0xE8), 0.75f, 12f, 9f, 9f, new Color(0x1F, 0x29, 0x37), null, null),
                new PanelStyle(new Color(0x12, 0x17, 0x20), new Color(0x27, 0x36, 0x4A), 0.75f, 12f, 8f, 9f, new Color(0xE8, 0xEE, 0xF7), new Color(0x20, 0x2A, 0x38), new Color(0xC6, 0xDB, 0xFF)),
                new Color(0xDB, 0xEA, 0xFE),
                new Color(0x0F, 0x17, 0x2A),
                Color.WHITE,
                new Color(0xF8, 0xFA, 0xFC),
                new Color(0xC9, 0xD4, 0xE0),
                new Color(0x9D, 0xC0, 0xEA));
        }
    }
}
