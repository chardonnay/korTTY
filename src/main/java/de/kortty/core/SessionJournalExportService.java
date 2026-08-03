package de.kortty.core;

import de.kortty.model.SessionJournalDocument;
import de.kortty.model.SessionJournalEntry;
import de.kortty.model.SessionJournalEntryKind;
import de.kortty.model.SessionJournalMarker;
import de.kortty.model.SessionJournalMeta;
import de.kortty.ui.I18n;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * Exports a session journal: a "simple journal" (entries, excerpts, embedded downscaled
 * screenshots) as PDF or Markdown, or the complete journal as an HTML bundle — a zip archive of
 * journal.html, the decompressed capture logs and the screenshots, laid out exactly like the
 * on-disk journal directory so the page works immediately when unzipped.
 *
 * <p>The PDF is laid out directly from the entry model with PDFBox (there is no HTML-to-PDF
 * engine in korTTY), following the conventions of {@link SnippetAnalysisExportService} /
 * {@link AiChatExportService}: bundled Noto fonts, hero header, timestamp margin column, marker
 * badges, tinted mono excerpt panels, page footers.</p>
 */
public final class SessionJournalExportService {

    private static final Logger logger = LoggerFactory.getLogger(SessionJournalExportService.class);

    public enum Format {
        PDF(".pdf", "journal.export.file.pdf"),
        MARKDOWN(".md", "journal.export.file.markdown"),
        HTML_BUNDLE(".zip", "journal.export.file.bundle");

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

    /** Export options; screenshots apply to PDF (embedding) and Markdown (sibling copies). */
    public record Options(boolean includeScreenshots) {
        public static Options defaults() {
            return new Options(true);
        }
    }

    private static final String SANS_FONT_RESOURCE = "/fonts/noto/NotoSans-Regular.ttf";
    private static final String SANS_BOLD_FONT_RESOURCE = "/fonts/noto/NotoSans-Bold.ttf";
    private static final String MONO_FONT_RESOURCE = "/fonts/noto/NotoSansMono-Regular.ttf";

    private static final float PAGE_MARGIN = 48f;
    private static final float CONTENT_BOTTOM_Y = 60f;
    private static final float CONTENT_TOP_Y = PDRectangle.A4.getHeight() - 60f;
    private static final float TIME_COLUMN_WIDTH = 52f;
    private static final int SCREENSHOT_MAX_PIXEL_WIDTH = 1200;
    private static final float SCREENSHOT_MAX_HEIGHT_PT = 220f;

    private static final DateTimeFormatter TIME_HM = DateTimeFormatter.ofPattern("HH:mm");
    private static final DateTimeFormatter DATE_FULL = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final SessionJournalService service;
    private final SessionJournalHtmlRenderer renderer;

    public SessionJournalExportService(SessionJournalService service, SessionJournalHtmlRenderer renderer) {
        this.service = service;
        this.renderer = renderer;
    }

    /** Exports the journal in {@code journalDir} to {@code target}. */
    public void export(Format format, Path journalDir, Path target, Options options) throws IOException {
        SessionJournalDocument document = service.loadDocument(journalDir);
        Options effective = options != null ? options : Options.defaults();
        switch (format) {
            case PDF -> writePdf(target, document, journalDir, effective);
            case MARKDOWN -> writeMarkdown(target, document, journalDir, effective);
            case HTML_BUNDLE -> writeBundle(target, journalDir);
        }
    }

    private static List<SessionJournalEntry> sortedEntries(SessionJournalDocument document) {
        List<SessionJournalEntry> entries = new ArrayList<>(document.getEntries());
        entries.sort(Comparator.comparing(SessionJournalEntry::getCreatedAt,
            Comparator.nullsLast(Comparator.naturalOrder())));
        return entries;
    }

    // ==== Markdown ==============================================================================

    private void writeMarkdown(Path target, SessionJournalDocument document, Path journalDir, Options options)
            throws IOException {
        SessionJournalMeta meta = document.getMeta();
        String assetDirName = stripExtension(target.getFileName().toString()) + "-files";
        Path assetDir = target.resolveSibling(assetDirName);
        ZoneId zone = ZoneId.systemDefault();
        StringBuilder md = new StringBuilder(16 * 1024);

        md.append("# ").append(nullSafe(meta.getTitle())).append("\n\n");
        String connection = SessionJournalHeaderSupport.connectionSubtitle(meta);
        if (!connection.isEmpty()) {
            md.append("- **").append(i18n("journal.md.connection", "Connection")).append(":** ")
                .append(connection).append('\n');
        }
        md.append("- **").append(i18n("journal.md.started", "Started")).append(":** ")
            .append(meta.getStartedAt() != null ? meta.getStartedAt().format(DATE_TIME) : "?")
            .append(" · **").append(i18n("journal.md.duration", "Duration")).append(":** ")
            .append(durationText(meta)).append('\n');
        md.append("- **").append(i18n("journal.md.commands", "Commands")).append(":** ")
            .append(meta.getCommandCount())
            .append(" · **").append(i18n("journal.md.errors", "Errors")).append(":** ")
            .append(meta.getErrorCount())
            .append(" · **").append(i18n("journal.md.screenshots", "Screenshots")).append(":** ")
            .append(meta.getScreenshotCount()).append('\n');
        if (meta.getDescription() != null && !meta.getDescription().isBlank()) {
            md.append("- **").append(i18n("journal.md.description", "Description")).append(":** ")
                .append(meta.getDescription().replace('\n', ' ')).append('\n');
        }
        md.append('\n');

        LocalDate currentDay = null;
        for (SessionJournalEntry entry : sortedEntries(document)) {
            OffsetDateTime createdAt = entry.getCreatedAt();
            LocalDate day = createdAt != null ? createdAt.atZoneSameInstant(zone).toLocalDate() : null;
            if (day != null && !day.equals(currentDay)) {
                currentDay = day;
                md.append("## ").append(day.format(DATE_FULL)).append("\n\n");
            }
            String time = createdAt != null ? createdAt.atZoneSameInstant(zone).format(TIME_HM) : "";
            md.append("### ").append(time);
            if (entry.getTitle() != null && !entry.getTitle().isBlank()) {
                md.append(" — ").append(entry.getTitle());
            }
            if (entry.getMarker() != SessionJournalMarker.NONE) {
                md.append("  `[").append(entry.getMarker().name()).append("]`");
            }
            md.append("\n\n");
            if (entry.getKind() == SessionJournalEntryKind.SCREENSHOT && entry.getScreenshotFile() != null) {
                appendMarkdownScreenshot(md, entry, journalDir, assetDir, assetDirName, options);
            }
            if (entry.getText() != null && !entry.getText().isBlank()) {
                md.append(entry.getText()).append("\n\n");
            }
            if (!entry.getInputExcerpt().isEmpty()) {
                md.append("**").append(i18n("journal.md.input", "Input")).append(":**\n");
                appendFencedBlock(md, "console", prefixLines(entry.getInputExcerpt(), "$ "));
            }
            if (!entry.getOutputExcerpt().isEmpty()) {
                md.append("**").append(i18n("journal.md.output", "Output")).append(":**\n");
                appendFencedBlock(md, "text", String.join("\n", entry.getOutputExcerpt()));
            }
            if (entry.getUserNote() != null && !entry.getUserNote().isBlank()) {
                md.append("> ").append(i18n("journal.md.note", "Note")).append(": ")
                    .append(entry.getUserNote().replace('\n', ' ')).append("\n\n");
            }
        }
        Files.writeString(target, md.toString(), StandardCharsets.UTF_8);
    }

    private void appendMarkdownScreenshot(
            StringBuilder md, SessionJournalEntry entry, Path journalDir,
            Path assetDir, String assetDirName, Options options) {
        if (!options.includeScreenshots()) {
            return;
        }
        try {
            Path source = journalDir.resolve(entry.getScreenshotFile());
            if (!Files.isRegularFile(source)) {
                return;
            }
            Files.createDirectories(assetDir);
            Path copy = assetDir.resolve(source.getFileName().toString());
            Files.copy(source, copy, StandardCopyOption.REPLACE_EXISTING);
            md.append("![").append(i18n("journal.md.screenshot", "Screenshot")).append("](")
                .append(assetDirName).append('/').append(source.getFileName()).append(")\n\n");
        } catch (IOException e) {
            logger.warn("Could not copy screenshot for Markdown export: {}", e.getMessage());
        }
    }

    /** Fences with more backticks than any run inside the content, so terminal text cannot break out. */
    private static void appendFencedBlock(StringBuilder md, String language, String content) {
        String fence = "```";
        while (content.contains(fence)) {
            fence += "`";
        }
        md.append(fence).append(language).append('\n').append(content).append('\n').append(fence).append("\n\n");
    }

    private static String prefixLines(List<String> lines, String prefix) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) {
                sb.append('\n');
            }
            sb.append(prefix).append(lines.get(i));
        }
        return sb.toString();
    }

    // ==== HTML bundle ===========================================================================

    /**
     * Zip archive matching the on-disk journal layout. Capture logs are stored decompressed —
     * the zip compresses anyway and the unzipped bundle stays immediately readable.
     */
    private void writeBundle(Path target, Path journalDir) throws IOException {
        Path htmlFile = journalDir.resolve(SessionJournalHtmlRenderer.HTML_FILE_NAME);
        if (renderer != null) {
            htmlFile = renderer.renderToFile(journalDir);
        }
        Files.deleteIfExists(target);
        Path tempDir = Files.createTempDirectory("kortty-journal-bundle");
        try (ZipFile zip = new ZipFile(target.toFile())) {
            if (Files.isRegularFile(htmlFile)) {
                zip.addFile(htmlFile.toFile());
            }
            Path documentFile = journalDir.resolve(SessionJournalService.DOCUMENT_FILE_NAME);
            if (Files.isRegularFile(documentFile)) {
                zip.addFile(documentFile.toFile());
            }
            int parts = SessionJournalLogReader.countParts(journalDir);
            for (int part = 1; part <= parts; part++) {
                Path partFile = SessionJournalLogReader.findPartFile(journalDir, part);
                if (partFile == null) {
                    continue;
                }
                String name = partFile.getFileName().toString();
                if (name.endsWith(SessionJournalLogCompressor.GZIP_SUFFIX)) {
                    String plainName = name.substring(0, name.length() - SessionJournalLogCompressor.GZIP_SUFFIX.length());
                    Path decompressed = tempDir.resolve(plainName);
                    try (InputStream in = new GZIPInputStream(Files.newInputStream(partFile))) {
                        Files.copy(in, decompressed, StandardCopyOption.REPLACE_EXISTING);
                    }
                    ZipParameters parameters = new ZipParameters();
                    parameters.setFileNameInZip(plainName);
                    zip.addFile(decompressed.toFile(), parameters);
                } else {
                    zip.addFile(partFile.toFile());
                }
            }
            Path screenshotsDir = journalDir.resolve(SessionJournalService.SCREENSHOTS_DIR_NAME);
            if (Files.isDirectory(screenshotsDir)) {
                zip.addFolder(screenshotsDir.toFile());
            }
        } finally {
            try (var walk = Files.walk(tempDir)) {
                for (Path path : walk.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }

    // ==== PDF ===================================================================================

    private void writePdf(Path target, SessionJournalDocument document, Path journalDir, Options options)
            throws IOException {
        SessionJournalMeta meta = document.getMeta();
        ZoneId zone = ZoneId.systemDefault();
        try (PDDocument pdf = new PDDocument()) {
            PdfFonts fonts = new PdfFonts(
                loadFont(pdf, SANS_FONT_RESOURCE),
                loadFont(pdf, SANS_BOLD_FONT_RESOURCE),
                loadFont(pdf, MONO_FONT_RESOURCE));

            Cursor cursor = newPage(pdf, null);
            cursor = drawHeader(pdf, cursor, fonts, meta);

            LocalDate currentDay = null;
            for (SessionJournalEntry entry : sortedEntries(document)) {
                OffsetDateTime createdAt = entry.getCreatedAt();
                LocalDate day = createdAt != null ? createdAt.atZoneSameInstant(zone).toLocalDate() : null;
                if (day != null && !day.equals(currentDay)) {
                    currentDay = day;
                    cursor = drawDayDivider(pdf, cursor, fonts, day.format(DATE_FULL));
                }
                cursor = drawEntry(pdf, cursor, fonts, entry, journalDir, options, zone);
            }
            cursor.stream().close();
            drawFooters(pdf, fonts);
            pdf.save(target.toFile());
        }
    }

    private Cursor drawHeader(PDDocument pdf, Cursor cursor, PdfFonts fonts, SessionJournalMeta meta)
            throws IOException {
        float width = contentWidth();
        drawFilledRect(cursor.stream(), PAGE_MARGIN, cursor.y() - 78f, width, 78f, new Color(0x0f, 0x62, 0xcc));
        drawText(cursor.stream(), fonts.sansBold(), 18f, Color.WHITE, PAGE_MARGIN + 16f, cursor.y() - 28f,
            fit(nullSafe(meta.getTitle()), fonts.sansBold(), 18f, width - 32f));
        // Skips whatever the title already states, so an endpoint-named journal does not repeat it.
        String connLine = SessionJournalHeaderSupport.connectionSubtitle(meta);
        if (!connLine.isEmpty()) {
            drawText(cursor.stream(), fonts.sans(), 9.5f, new Color(0xd9, 0xea, 0xff), PAGE_MARGIN + 16f,
                cursor.y() - 46f, fit(connLine, fonts.sans(), 9.5f, width - 32f));
        }
        String metaLine = i18n("journal.pdf.started", "Started") + ": "
            + (meta.getStartedAt() != null ? meta.getStartedAt().format(DATE_TIME) : "?")
            + "  ·  " + i18n("journal.pdf.duration", "Duration") + ": " + durationText(meta)
            + "  ·  " + i18n("journal.pdf.commands", "Commands") + ": " + meta.getCommandCount()
            + "  ·  " + i18n("journal.pdf.errors", "Errors") + ": " + meta.getErrorCount()
            + "  ·  " + i18n("journal.pdf.screenshots", "Screenshots") + ": " + meta.getScreenshotCount();
        drawText(cursor.stream(), fonts.sans(), 9f, new Color(0xd9, 0xea, 0xff), PAGE_MARGIN + 16f,
            cursor.y() - (connLine.isEmpty() ? 50f : 62f), fit(metaLine, fonts.sans(), 9f, width - 32f));
        cursor = cursor.withY(cursor.y() - 92f);
        if (meta.getDescription() != null && !meta.getDescription().isBlank()) {
            cursor = drawParagraph(pdf, cursor, fonts.sans(), 10f, new Color(0x5e, 0x6e, 0x82),
                meta.getDescription(), 6f, PAGE_MARGIN, contentWidth());
        }
        return cursor;
    }

    private Cursor drawDayDivider(PDDocument pdf, Cursor cursor, PdfFonts fonts, String label) throws IOException {
        cursor = ensureSpace(pdf, cursor, 32f);
        cursor = cursor.withY(cursor.y() - 10f);
        drawText(cursor.stream(), fonts.sansBold(), 12f, new Color(0x1f, 0x29, 0x37), PAGE_MARGIN, cursor.y(), label);
        drawLine(cursor.stream(), PAGE_MARGIN, cursor.y() - 5f, PAGE_MARGIN + contentWidth(), cursor.y() - 5f,
            new Color(0xd0, 0xd7, 0xde), 0.8f);
        return cursor.withY(cursor.y() - 18f);
    }

    private Cursor drawEntry(PDDocument pdf, Cursor cursor, PdfFonts fonts, SessionJournalEntry entry,
                             Path journalDir, Options options, ZoneId zone) throws IOException {
        cursor = ensureSpace(pdf, cursor, 36f);
        float bodyX = PAGE_MARGIN + TIME_COLUMN_WIDTH;
        float bodyWidth = contentWidth() - TIME_COLUMN_WIDTH;
        String time = entry.getCreatedAt() != null
            ? entry.getCreatedAt().atZoneSameInstant(zone).format(TIME_HM)
            : "";
        drawText(cursor.stream(), fonts.mono(), 9.5f, new Color(0x6e, 0x77, 0x81), PAGE_MARGIN,
            cursor.y() - 10f, time);

        float badgeOffset = 0f;
        if (entry.getMarker() != SessionJournalMarker.NONE) {
            String label = i18n("journal.marker." + entry.getMarker().name().toLowerCase(java.util.Locale.ROOT),
                entry.getMarker().name());
            badgeOffset = drawBadge(cursor.stream(), fonts.sansBold(), bodyX, cursor.y() - 12.5f, label,
                markerColor(entry.getMarker())) + 6f;
        }
        String title = entry.getTitle();
        if (entry.getKind() == SessionJournalEntryKind.SESSION_SUMMARY) {
            title = i18n("journal.pdf.sessionSummary", "Session summary")
                + (title != null && !title.isBlank() ? ": " + title : "");
        }
        if (title != null && !title.isBlank()) {
            drawText(cursor.stream(), fonts.sansBold(), 11.5f, new Color(0x1f, 0x29, 0x37), bodyX + badgeOffset,
                cursor.y() - 12f, fit(title, fonts.sansBold(), 11.5f, bodyWidth - badgeOffset));
        }
        cursor = cursor.withY(cursor.y() - 18f);

        if (entry.getText() != null && !entry.getText().isBlank()) {
            cursor = drawParagraph(pdf, cursor, fonts.sans(), 10.2f, new Color(0x37, 0x41, 0x51),
                entry.getText(), 3f, bodyX, bodyWidth);
        }
        if (!entry.getInputExcerpt().isEmpty()) {
            cursor = drawExcerptPanel(pdf, cursor, fonts,
                prefixedLines(entry.getInputExcerpt(), "$ "), bodyX, bodyWidth,
                new Color(0x11, 0x63, 0x29), new Color(0xef, 0xf7, 0xef));
        }
        if (!entry.getOutputExcerpt().isEmpty()) {
            cursor = drawExcerptPanel(pdf, cursor, fonts,
                entry.getOutputExcerpt(), bodyX, bodyWidth,
                new Color(0x0a, 0x30, 0x69), new Color(0xee, 0xf3, 0xfb));
        }
        if (entry.getUserNote() != null && !entry.getUserNote().isBlank()) {
            cursor = drawParagraph(pdf, cursor, fonts.sans(), 9.8f, new Color(0x9a, 0x67, 0x00),
                i18n("journal.pdf.note", "Note") + ": " + entry.getUserNote(), 3f, bodyX, bodyWidth);
        }
        if (options.includeScreenshots()
            && entry.getKind() == SessionJournalEntryKind.SCREENSHOT
            && entry.getScreenshotFile() != null) {
            cursor = drawScreenshot(pdf, cursor, journalDir.resolve(entry.getScreenshotFile()), bodyX, bodyWidth);
        }
        return cursor.withY(cursor.y() - 10f);
    }

    /** Tinted mono panel with a colored accent bar, mirroring the HTML excerpt styling. */
    private Cursor drawExcerptPanel(PDDocument pdf, Cursor cursor, PdfFonts fonts, List<String> lines,
                                    float x, float width, Color accent, Color background) throws IOException {
        float fontSize = 8.6f;
        float leading = fontSize + 3f;
        float padding = 6f;
        List<String> wrapped = new ArrayList<>();
        for (String line : lines) {
            wrapped.addAll(wrap(line, fonts.mono(), fontSize, width - padding * 2 - 4f));
        }
        float height = wrapped.size() * leading + padding * 2;
        cursor = ensureSpace(pdf, cursor, height + 4f);
        drawFilledRect(cursor.stream(), x, cursor.y() - height, width, height, background);
        drawFilledRect(cursor.stream(), x, cursor.y() - height, 3f, height, accent);
        float textY = cursor.y() - padding - fontSize;
        for (String line : wrapped) {
            drawText(cursor.stream(), fonts.mono(), fontSize, accent.darker(), x + padding + 4f, textY, line);
            textY -= leading;
        }
        return cursor.withY(cursor.y() - height - 4f);
    }

    /** Downscales pixel data (≤1200px wide) and draws at most half the content width. */
    private Cursor drawScreenshot(PDDocument pdf, Cursor cursor, Path imageFile, float x, float width)
            throws IOException {
        if (!Files.isRegularFile(imageFile)) {
            return cursor;
        }
        BufferedImage image;
        try {
            image = ImageIO.read(imageFile.toFile());
        } catch (IOException e) {
            logger.warn("Could not read screenshot {} for PDF export: {}", imageFile.getFileName(), e.getMessage());
            return cursor;
        }
        if (image == null) {
            return cursor;
        }
        if (image.getWidth() > SCREENSHOT_MAX_PIXEL_WIDTH) {
            int newHeight = (int) Math.round(
                image.getHeight() * (SCREENSHOT_MAX_PIXEL_WIDTH / (double) image.getWidth()));
            BufferedImage scaled = new BufferedImage(SCREENSHOT_MAX_PIXEL_WIDTH, newHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
                RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            graphics.drawImage(image, 0, 0, SCREENSHOT_MAX_PIXEL_WIDTH, newHeight, null);
            graphics.dispose();
            image = scaled;
        }
        float maxWidth = width / 2f;
        float scale = Math.min(Math.min(maxWidth / image.getWidth(), SCREENSHOT_MAX_HEIGHT_PT / image.getHeight()), 1f);
        float drawWidth = image.getWidth() * scale;
        float drawHeight = image.getHeight() * scale;
        cursor = ensureSpace(pdf, cursor, drawHeight + 8f);
        PDImageXObject pdImage = LosslessFactory.createFromImage(pdf, image);
        cursor.stream().drawImage(pdImage, x, cursor.y() - drawHeight, drawWidth, drawHeight);
        return cursor.withY(cursor.y() - drawHeight - 8f);
    }

    private void drawFooters(PDDocument pdf, PdfFonts fonts) throws IOException {
        int total = pdf.getNumberOfPages();
        String brand = i18n("journal.pdf.footer", "Session journal export from korTTY");
        for (int index = 0; index < total; index++) {
            PDPage page = pdf.getPage(index);
            try (PDPageContentStream stream = new PDPageContentStream(pdf, page, AppendMode.APPEND, true, true)) {
                float pageWidth = page.getMediaBox().getWidth();
                drawLine(stream, PAGE_MARGIN, 42f, pageWidth - PAGE_MARGIN, 42f, new Color(0xe5, 0xe7, 0xeb), 0.7f);
                drawText(stream, fonts.sans(), 8.4f, new Color(0x9c, 0xa3, 0xaf), PAGE_MARGIN, 28f, brand);
                String label = (index + 1) + " / " + total;
                float labelWidth = textWidth(fonts.sans(), 8.4f, label);
                drawText(stream, fonts.sans(), 8.4f, new Color(0x9c, 0xa3, 0xaf),
                    pageWidth - PAGE_MARGIN - labelWidth, 28f, label);
            }
        }
    }

    private static Color markerColor(SessionJournalMarker marker) {
        return switch (marker) {
            case ERROR -> new Color(0xcf, 0x22, 0x2e);
            case IMPORTANT -> new Color(0x9a, 0x67, 0x00);
            case INFO -> new Color(0x09, 0x69, 0xda);
            case NONE -> new Color(0x6e, 0x77, 0x81);
        };
    }

    /** Draws a filled badge and returns its width. */
    private float drawBadge(PDPageContentStream stream, PDFont font, float x, float y, String label, Color color)
            throws IOException {
        float fontSize = 7.5f;
        float labelWidth = textWidth(font, fontSize, label.toUpperCase(java.util.Locale.ROOT));
        float badgeWidth = labelWidth + 10f;
        drawFilledRect(stream, x, y - 2.5f, badgeWidth, 11f, color);
        drawText(stream, font, fontSize, Color.WHITE, x + 5f, y, label.toUpperCase(java.util.Locale.ROOT));
        return badgeWidth;
    }

    private static List<String> prefixedLines(List<String> lines, String prefix) {
        List<String> result = new ArrayList<>(lines.size());
        for (String line : lines) {
            result.add(prefix + line);
        }
        return result;
    }

    // ==== pdfbox primitives (conventions of SnippetAnalysisExportService) =======================

    private record PdfFonts(PDFont sans, PDFont sansBold, PDFont mono) {
    }

    private record Cursor(PDPageContentStream stream, float y) {
        Cursor withY(float newY) {
            return new Cursor(stream, newY);
        }
    }

    private Cursor drawParagraph(PDDocument pdf, Cursor cursor, PDFont font, float fontSize, Color color,
                                 String text, float trailing, float x, float width) throws IOException {
        float leading = fontSize + 3.4f;
        for (String line : wrap(text, font, fontSize, width)) {
            cursor = ensureSpace(pdf, cursor, leading);
            drawText(cursor.stream(), font, fontSize, color, x, cursor.y() - fontSize, line);
            cursor = cursor.withY(cursor.y() - leading);
        }
        return cursor.withY(cursor.y() - trailing);
    }

    private Cursor ensureSpace(PDDocument pdf, Cursor cursor, float needed) throws IOException {
        if (cursor.y() - needed >= CONTENT_BOTTOM_Y) {
            return cursor;
        }
        return newPage(pdf, cursor);
    }

    private Cursor newPage(PDDocument pdf, Cursor previous) throws IOException {
        if (previous != null) {
            previous.stream().close();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        pdf.addPage(page);
        return new Cursor(new PDPageContentStream(pdf, page), CONTENT_TOP_Y);
    }

    private PDType0Font loadFont(PDDocument pdf, String resourcePath) throws IOException {
        try (InputStream inputStream = SessionJournalExportService.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Missing PDF font resource " + resourcePath);
            }
            return PDType0Font.load(pdf, inputStream, true);
        }
    }

    private float contentWidth() {
        return PDRectangle.A4.getWidth() - (PAGE_MARGIN * 2f);
    }

    private void drawText(PDPageContentStream stream, PDFont font, float fontSize, Color color, float x, float y,
                          String text) throws IOException {
        String safe = prepareText(font, text);
        stream.beginText();
        stream.setFont(font, fontSize);
        stream.setNonStrokingColor(color);
        stream.newLineAtOffset(x, y);
        stream.showText(safe);
        stream.endText();
    }

    private void drawFilledRect(PDPageContentStream stream, float x, float y, float width, float height, Color color)
            throws IOException {
        stream.setNonStrokingColor(color);
        stream.addRect(x, y, width, height);
        stream.fill();
    }

    private void drawLine(PDPageContentStream stream, float x1, float y1, float x2, float y2, Color color,
                          float lineWidth) throws IOException {
        stream.setStrokingColor(color);
        stream.setLineWidth(lineWidth);
        stream.moveTo(x1, y1);
        stream.lineTo(x2, y2);
        stream.stroke();
    }

    private List<String> wrap(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> lines = new ArrayList<>();
        String normalized = normalizeForPdf(text);
        for (String rawLine : normalized.split("\\R", -1)) {
            String line = rawLine.stripTrailing();
            if (line.isEmpty()) {
                continue;
            }
            StringBuilder current = new StringBuilder();
            for (String rawWord : line.split("\\s+")) {
                String word = prepareText(font, rawWord);
                String candidate = current.length() == 0 ? word : current + " " + word;
                if (current.length() == 0 || textWidth(font, fontSize, candidate) <= maxWidth) {
                    current.setLength(0);
                    current.append(candidate);
                    continue;
                }
                lines.add(current.toString());
                if (textWidth(font, fontSize, word) <= maxWidth) {
                    current.setLength(0);
                    current.append(word);
                } else {
                    List<String> broken = breakLongToken(word, font, fontSize, maxWidth);
                    lines.addAll(broken.subList(0, broken.size() - 1));
                    current.setLength(0);
                    current.append(broken.get(broken.size() - 1));
                }
            }
            if (current.length() > 0) {
                lines.add(current.toString());
            }
        }
        if (lines.isEmpty()) {
            lines.add("");
        }
        return lines;
    }

    private List<String> breakLongToken(String token, PDFont font, float fontSize, float maxWidth) throws IOException {
        List<String> parts = new ArrayList<>();
        String remaining = token;
        while (!remaining.isEmpty()) {
            int low = 1;
            int high = remaining.length();
            int best = 1;
            while (low <= high) {
                int mid = (low + high) / 2;
                if (textWidth(font, fontSize, remaining.substring(0, mid)) <= maxWidth) {
                    best = mid;
                    low = mid + 1;
                } else {
                    high = mid - 1;
                }
            }
            parts.add(remaining.substring(0, best));
            remaining = remaining.substring(best);
        }
        return parts;
    }

    private float textWidth(PDFont font, float fontSize, String text) throws IOException {
        return font.getStringWidth(prepareText(font, text)) / 1000f * fontSize;
    }

    private String fit(String text, PDFont font, float fontSize, float maxWidth) throws IOException {
        String safe = prepareText(font, text);
        if (textWidth(font, fontSize, safe) <= maxWidth) {
            return safe;
        }
        int end = safe.length();
        while (end > 1) {
            String candidate = safe.substring(0, end).strip() + "...";
            if (textWidth(font, fontSize, candidate) <= maxWidth) {
                return candidate;
            }
            end--;
        }
        return "...";
    }

    /** Drops characters the bundled fonts cannot render (e.g. emoji) so PDFBox never throws on encode. */
    private String prepareText(PDFont font, String text) {
        String safe = text != null ? text : "";
        StringBuilder builder = new StringBuilder(safe.length());
        for (int index = 0; index < safe.length(); ) {
            int codePoint = safe.codePointAt(index);
            String glyph = new String(Character.toChars(codePoint));
            if (Character.isWhitespace(codePoint)) {
                builder.append(glyph);
            } else {
                try {
                    font.encode(glyph);
                    builder.append(glyph);
                } catch (IllegalArgumentException | IOException ex) {
                    builder.append('?');
                }
            }
            index += Character.charCount(codePoint);
        }
        return builder.toString();
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

    // ==== shared helpers ========================================================================

    private static String durationText(SessionJournalMeta meta) {
        var duration = meta.getDuration();
        if (duration == null) {
            return "?";
        }
        long hours = duration.toHours();
        long minutes = duration.toMinutesPart();
        return hours > 0 ? hours + "h " + minutes + "m" : minutes + "m " + duration.toSecondsPart() + "s";
    }

    private static String stripExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private static String i18n(String key, String fallback) {
        try {
            String value = I18n.get(key);
            return value != null && !value.equals(key) ? value : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String nullSafe(String value) {
        return value != null ? value : "";
    }
}
