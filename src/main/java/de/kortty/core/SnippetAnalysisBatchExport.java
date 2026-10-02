package de.kortty.core;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import de.kortty.core.SnippetAnalysisDiagramRasterizer.RasterizedDiagram;
import de.kortty.core.SnippetAnalysisExportService.ExportOptions;
import de.kortty.core.SnippetAnalysisExportService.Format;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.multipdf.PDFMergerUtility;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static de.kortty.core.SnippetAnalysisReportText.r;
import static de.kortty.core.SnippetAnalysisReportText.t;

/**
 * Exports the stored analyses of several snippets at once: as one combined document (a PDF with a
 * cover and one chapter per snippet, one self-contained HTML page with a table of contents,
 * one Markdown file with a section per snippet, or a JSON array) or as a ZIP with one report per
 * snippet. Snippets without a stored analysis are listed as skipped. Runs off the JavaFX thread;
 * {@code cancelled} is checked between snippets, and a cancelled or failed export leaves nothing
 * behind (every file is written to a temp file first).
 */
public final class SnippetAnalysisBatchExport {

    public enum Packaging { COMBINED, ZIP }

    /**
     * One report to export. {@code folderPath} is the snippet's library folder ({@code a/b}, empty
     * at the top level): a ZIP puts the report into that directory, combined reports show it.
     */
    public record Item(String snippetId, String snippetName, SnippetAnalysisReport report, String folderPath) {
        public Item {
            Objects.requireNonNull(report, "report");
            snippetName = snippetName != null ? snippetName : "";
            folderPath = safeFolderPath(folderPath);
        }

        public Item(String snippetId, String snippetName, SnippetAnalysisReport report) {
            this(snippetId, snippetName, report, "");
        }
    }

    /** Each segment made a safe directory name; empty, {@code .} and {@code ..} segments dropped. */
    static String safeFolderPath(String folderPath) {
        if (folderPath == null || folderPath.isBlank()) {
            return "";
        }
        List<String> segments = new ArrayList<>();
        for (String segment : folderPath.replace('\\', '/').split("/")) {
            String safe = SnippetManager.sanitizeFolderName(segment);
            if (!safe.isEmpty()) {
                segments.add(safe);
            }
        }
        return String.join("/", segments);
    }

    /** A selected snippet that has no stored analysis. */
    public record Skipped(String snippetId, String snippetName) {
        public Skipped {
            snippetName = snippetName != null ? snippetName : "";
        }
    }

    /** Called after every snippet (and once before the first with {@code done = 0}). */
    public interface Progress {
        void update(int done, int total, String snippetName);
    }

    /**
     * @param writtenFiles the target first, then Markdown diagram images next to it
     * @param failedDiagrams reports whose stored diagram could not be drawn
     */
    public record Result(List<Path> writtenFiles, int exported, List<Skipped> skipped, int failedDiagrams,
                         int pageCount) {
        public Result {
            writtenFiles = List.copyOf(writtenFiles);
            skipped = List.copyOf(skipped);
        }
    }

    private static final float MARGIN = 56f;
    private static final float ROW = 18f;
    private static final float TOP = 842f - 150f;
    private static final float FIRST_ROWS_TOP = TOP - 70f;
    private static final float BOTTOM = 80f;
    private static final Color HERO = new Color(0x00, 0x66, 0xCC);
    private static final Color TEXT = new Color(0x1F, 0x29, 0x37);
    private static final Color WARN = new Color(0x92, 0x40, 0x0E);

    private SnippetAnalysisBatchExport() {
    }

    /**
     * The report a batch export writes for a snippet: the current analysis after its newest accepted
     * apply run when there is one; a current verification stands in for the run it verified (whose
     * after-report carries the verification); otherwise the current analysis before applying.
     * {@code null} when the snippet has no stored analysis.
     */
    public static SnippetAnalysisReport reportFor(SnippetAnalysisHistory history,
                                                  SnippetAnalysisReports.ReportContext context) {
        SnippetAnalysisRecord record = history != null ? history.current() : null;
        if (record == null) {
            return null;
        }
        SnippetAnalysisRecord.ApplyRun accepted = newestAccepted(record);
        if (accepted == null && record.purpose() == SnippetAnalysisRecord.Purpose.VERIFY
                && record.previousRecordId() != null) {
            SnippetAnalysisRecord verified = history.records().stream()
                .filter(candidate -> record.previousRecordId().equals(candidate.id()))
                .findFirst().orElse(null);
            SnippetAnalysisRecord.ApplyRun verifiedRun = newestAccepted(verified);
            if (verifiedRun != null) {
                return SnippetAnalysisReports.postApply(verified, verifiedRun.id(), history.records(), context);
            }
        }
        return accepted != null
            ? SnippetAnalysisReports.postApply(record, accepted.id(), history.records(), context)
            : SnippetAnalysisReports.preApply(record, context);
    }

    private static SnippetAnalysisRecord.ApplyRun newestAccepted(SnippetAnalysisRecord record) {
        SnippetAnalysisRecord.ApplyRun accepted = null;
        if (record != null) {
            for (SnippetAnalysisRecord.ApplyRun run : SnippetAnalysisReports.reportableRuns(record)) {
                if (run.isAccepted()) {
                    accepted = run;
                }
            }
        }
        return accepted;
    }

    // ---- entry ----

    static Result export(SnippetAnalysisDiagramRasterizer rasterizer, Path target, Format format,
                         Packaging packaging, List<Item> items, List<Skipped> skipped, ExportOptions options,
                         Progress progress, BooleanSupplier cancelled) throws IOException {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(packaging, "packaging");
        List<Item> reports = items != null ? List.copyOf(items) : List.of();
        List<Skipped> missing = skipped != null ? List.copyOf(skipped) : List.of();
        Progress sink = progress != null ? progress : (done, total, name) -> { };
        BooleanSupplier stop = cancelled != null ? cancelled : () -> false;
        if (reports.isEmpty()) {
            throw new IllegalArgumentException("No stored analysis to export");
        }
        List<Path> assets = packaging == Packaging.COMBINED && format == Format.MARKDOWN
            ? markdownAssets(target, reports) : List.of();
        if (!options.overwriteAssets()) {
            for (Path asset : assets) {
                if (Files.exists(asset)) {
                    throw new FileAlreadyExistsException(asset.toString());
                }
            }
        }
        // Rasterize and render each report once, in order; check for a cancel between snippets.
        List<Rendered> rendered = new ArrayList<>();
        int failedDiagrams = 0;
        sink.update(0, reports.size(), "");
        for (int index = 0; index < reports.size(); index++) {
            if (stop.getAsBoolean()) {
                throw new CancellationException();
            }
            Item item = reports.get(index);
            RasterizedDiagram diagram = item.report().diagram() == null || format == Format.JSON
                ? RasterizedDiagram.none()
                : rasterizer.rasterize(item.report().diagram());
            if (diagram == null) {
                diagram = RasterizedDiagram.none();
            }
            if (diagram.outcome().failed()) {
                failedDiagrams++;
            }
            rendered.add(render(item, index, format, packaging, diagram, options,
                assets.isEmpty() ? null : assets.get(index).getFileName().toString()));
            sink.update(index + 1, reports.size(), item.snippetName());
        }
        if (stop.getAsBoolean()) {
            throw new CancellationException();
        }
        List<Path> written = new ArrayList<>();
        int[] pages = new int[1];
        if (packaging == Packaging.ZIP) {
            writeAtomically(target, out -> writeZip(out, rendered, missing, format));
        } else {
            switch (format) {
                case PDF -> writeAtomically(target, out -> pages[0] = combinePdf(out, rendered, missing, options));
                case HTML -> writeAtomically(target, out -> out.write(
                    combinedHtml(rendered, missing, options).getBytes(StandardCharsets.UTF_8)));
                case JSON -> writeAtomically(target, out -> out.write(
                    combinedJson(rendered).getBytes(StandardCharsets.UTF_8)));
                case MARKDOWN -> {
                    for (int index = 0; index < rendered.size(); index++) {
                        Rendered part = rendered.get(index);
                        if (part.image() != null) {
                            Path png = assets.get(index);
                            writeAtomically(png, out -> out.write(part.image()));
                            written.add(png);
                        }
                    }
                    writeAtomically(target, out -> out.write(
                        combinedMarkdown(rendered, missing, options).getBytes(StandardCharsets.UTF_8)));
                }
            }
        }
        written.addFirst(target);
        return new Result(written, rendered.size(), missing, failedDiagrams, pages[0]);
    }

    /** The diagram images a combined Markdown export writes next to {@code target}: one per report. */
    static List<Path> markdownAssets(Path target, List<Item> items) {
        String name = target.getFileName().toString();
        String base = name.toLowerCase(Locale.ROOT).endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        List<Path> files = new ArrayList<>();
        for (int index = 0; index < items.size(); index++) {
            files.add(target.resolveSibling(base + "." + String.format(Locale.ROOT, "%02d", index + 1)
                + "-diagram.png"));
        }
        return files;
    }

    // ---- per report ----

    /**
     * One report in its target shape. {@code body} is the whole file (ZIP, PDF chapter), the article
     * (combined HTML), the section (combined Markdown) or the object (JSON); {@code image} the
     * Markdown diagram PNG.
     */
    private record Rendered(Item item, int index, String fileName, byte[] body, byte[] image, int pages) {
    }

    /** @param combinedPngName the sibling image name of this report in a combined Markdown export */
    private static Rendered render(Item item, int index, Format format, Packaging packaging, RasterizedDiagram diagram,
                                   ExportOptions options, String combinedPngName) throws IOException {
        String fileName = String.format(Locale.ROOT, "%02d-", index + 1)
            + SnippetAnalysisExportService.suggestFileName(item.report(), format, options.zone());
        boolean zip = packaging == Packaging.ZIP;
        switch (format) {
            case PDF -> {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                int pages = SnippetAnalysisPdfWriter.write(item.report(), options, diagram, out);
                return new Rendered(item, index, fileName, out.toByteArray(), null, pages);
            }
            case HTML -> {
                String html = zip
                    ? SnippetAnalysisHtmlWriter.render(item.report(), options, diagram)
                    : SnippetAnalysisHtmlWriter.renderArticle(item.report(), options, diagram, "r" + (index + 1));
                return new Rendered(item, index, fileName, html.getBytes(StandardCharsets.UTF_8), null, 0);
            }
            case JSON -> {
                String json = SnippetAnalysisJsonWriter.render(item.report(), options);
                return new Rendered(item, index, fileName, json.getBytes(StandardCharsets.UTF_8), null, 0);
            }
            case MARKDOWN -> {
                byte[] image = diagram.rendered() ? png(diagram) : null;
                String imageName = image == null ? null
                    : zip ? fileName.substring(0, fileName.length() - 3) + ".diagram.png" : combinedPngName;
                ExportOptions markdownOptions = zip ? options : withoutFooter(options);
                String markdown = SnippetAnalysisMarkdownWriter.render(item.report(), markdownOptions, diagram,
                    imageName);
                return new Rendered(item, index, fileName, markdown.getBytes(StandardCharsets.UTF_8), image, 0);
            }
            default -> throw new IllegalStateException(format.name());
        }
    }

    private static byte[] png(RasterizedDiagram diagram) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        javax.imageio.ImageIO.write(diagram.image(), "png", out);
        return out.toByteArray();
    }

    private static ExportOptions withoutFooter(ExportOptions options) {
        ExportBranding branding = options.branding() != null ? options.branding() : ExportBranding.defaults();
        ExportBranding quiet = new ExportBranding(branding.watermarkEnabled(), branding.watermarkText(),
            branding.watermarkColor(), false, branding.footerText(), branding.footerUsesDefaultText());
        return new ExportOptions(options.includeFullCode(), options.overwriteAssets(), options.locale(), options.zone(),
            options.exportedAt(), quiet);
    }

    // ---- ZIP ----

    private static void writeZip(OutputStream out, List<Rendered> rendered, List<Skipped> skipped, Format format)
        throws IOException {
        Set<String> names = new HashSet<>();
        try (ZipOutputStream zip = new ZipOutputStream(out, StandardCharsets.UTF_8)) {
            for (Rendered part : rendered) {
                String folder = part.item().folderPath();
                String prefix = folder.isEmpty() ? "" : folder + "/";
                putEntry(zip, names, prefix + part.fileName(), part.body());
                if (format == Format.MARKDOWN && part.image() != null) {
                    String base = part.fileName().substring(0, part.fileName().length() - 3);
                    putEntry(zip, names, prefix + base + ".diagram.png", part.image());
                }
            }
            if (!skipped.isEmpty()) {
                StringBuilder text = new StringBuilder(t("snippets.batchExport.report.skipped")).append('\n');
                for (Skipped entry : skipped) {
                    text.append("- ").append(entry.snippetName()).append('\n');
                }
                putEntry(zip, names, "skipped.txt", text.toString().getBytes(StandardCharsets.UTF_8));
            }
        }
    }

    private static void putEntry(ZipOutputStream zip, Set<String> names, String name, byte[] body) throws IOException {
        String unique = name;
        for (int suffix = 2; !names.add(unique); suffix++) {
            int dot = name.lastIndexOf('.');
            unique = dot > 0 ? name.substring(0, dot) + "-" + suffix + name.substring(dot) : name + "-" + suffix;
        }
        zip.putNextEntry(new ZipEntry(unique));
        zip.write(body);
        zip.closeEntry();
    }

    // ---- JSON ----

    /** A JSON array of the reports, each in the single-report schema. */
    static String combinedJson(List<Rendered> rendered) {
        JsonArray array = new JsonArray();
        for (Rendered part : rendered) {
            array.add(JsonParser.parseString(new String(part.body(), StandardCharsets.UTF_8)));
        }
        return new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(array) + "\n";
    }

    // ---- HTML ----

    private static String combinedHtml(List<Rendered> rendered, List<Skipped> skipped, ExportOptions options) {
        String title = t("snippets.batchExport.report.title");
        StringBuilder html = new StringBuilder("<!doctype html><html lang=\"")
            .append(SnippetAnalysisHtmlWriter.esc(options.locale().toLanguageTag()))
            .append("\"><head><meta charset=\"UTF-8\">")
            .append("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">")
            .append("<meta name=\"generator\" content=\"korTTY\"><title>").append(SnippetAnalysisHtmlWriter.esc(title))
            .append("</title><style>").append(SnippetAnalysisHtmlWriter.css())
            .append(".report{margin-top:48px;padding-top:12px;border-top:3px solid #d4deea}")
            .append(".toc .kind{color:#5e6e82;font-size:.85em;margin-left:8px}")
            .append("@media print{.report{break-before:page;border-top:none;margin-top:0}}")
            .append("</style></head><body>").append(SnippetAnalysisHtmlWriter.watermark(options)).append("<main>");
        html.append("<header class=\"hero\"><div class=\"overline\">korTTY</div><h1>")
            .append(SnippetAnalysisHtmlWriter.esc(title)).append("</h1><div class=\"subtitle\">")
            .append(SnippetAnalysisHtmlWriter.esc(summaryLine(rendered.size(), options))).append("</div></header>");
        html.append("<nav class=\"toc\"><h2>").append(SnippetAnalysisHtmlWriter.esc(r("contents"))).append("</h2><ol>");
        for (Rendered part : rendered) {
            html.append("<li><a href=\"#r").append(part.index() + 1).append("\">")
                .append(SnippetAnalysisHtmlWriter.esc(entryTitle(part.item()))).append("</a><span class=\"kind\">")
                .append(SnippetAnalysisHtmlWriter.esc(SnippetAnalysisReportText.kindLabel(part.item().report())))
                .append("</span></li>");
        }
        html.append("</ol>");
        if (!skipped.isEmpty()) {
            html.append("<div class=\"note warn\"><strong>")
                .append(SnippetAnalysisHtmlWriter.esc(t("snippets.batchExport.report.skipped"))).append("</strong><ul>");
            for (Skipped entry : skipped) {
                html.append("<li>").append(SnippetAnalysisHtmlWriter.esc(entry.snippetName())).append("</li>");
            }
            html.append("</ul></div>");
        }
        html.append("</nav>");
        for (Rendered part : rendered) {
            html.append(new String(part.body(), StandardCharsets.UTF_8));
        }
        html.append("</main>").append(SnippetAnalysisHtmlWriter.footer(options)).append("</body></html>\n");
        return html.toString();
    }

    // ---- Markdown ----

    private static String combinedMarkdown(List<Rendered> rendered, List<Skipped> skipped, ExportOptions options) {
        StringBuilder md = new StringBuilder("# ").append(SnippetAnalysisMarkdownWriter.inline(
            t("snippets.batchExport.report.title"))).append("\n\n");
        md.append(SnippetAnalysisMarkdownWriter.inline(summaryLine(rendered.size(), options))).append("\n\n");
        md.append("## ").append(SnippetAnalysisMarkdownWriter.inline(r("contents"))).append("\n\n");
        for (Rendered part : rendered) {
            md.append(part.index() + 1).append(". ").append(SnippetAnalysisMarkdownWriter.inline(entryTitle(part.item())))
                .append(" — ").append(SnippetAnalysisMarkdownWriter.inline(
                    SnippetAnalysisReportText.kindLabel(part.item().report()))).append('\n');
        }
        md.append('\n');
        if (!skipped.isEmpty()) {
            md.append("> **").append(SnippetAnalysisMarkdownWriter.inline(t("snippets.batchExport.report.skipped")))
                .append("**\n>\n");
            for (Skipped entry : skipped) {
                md.append("> - ").append(SnippetAnalysisMarkdownWriter.inline(entry.snippetName())).append('\n');
            }
            md.append('\n');
        }
        for (Rendered part : rendered) {
            md.append("---\n\n");
            String section = new String(part.body(), StandardCharsets.UTF_8);
            md.append(shiftHeadings(section)).append('\n');
        }
        ExportBranding branding = options.branding() != null ? options.branding() : ExportBranding.defaults();
        if (branding.footerEnabled()) {
            md.append("---\n\n_").append(SnippetAnalysisMarkdownWriter.inline(branding.footerText())).append('_');
            if (branding.footerUsesDefaultText()) {
                md.append(" · <").append(ExportBranding.REPOSITORY_URL).append('>');
            }
            md.append('\n');
        }
        return md.toString();
    }

    /** Pushes every Markdown heading one level down (outside fenced code), so a report becomes a section. */
    static String shiftHeadings(String markdown) {
        StringBuilder out = new StringBuilder();
        String fence = null;
        for (String line : markdown.split("\n", -1)) {
            String trimmed = line.stripLeading();
            if (fence == null) {
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    fence = trimmed.substring(0, runLength(trimmed));
                } else if (line.startsWith("#")) {
                    line = "#" + line;
                }
            } else if (trimmed.startsWith(fence) && trimmed.substring(runLength(trimmed)).isBlank()
                    && runLength(trimmed) >= fence.length()) {
                fence = null;
            }
            out.append(line).append('\n');
        }
        out.setLength(Math.max(0, out.length() - 1));
        return out.toString();
    }

    private static int runLength(String text) {
        char first = text.charAt(0);
        int length = 0;
        while (length < text.length() && text.charAt(length) == first) {
            length++;
        }
        return length;
    }

    // ---- PDF ----

    private static int combinePdf(OutputStream out, List<Rendered> rendered, List<Skipped> skipped,
                                  ExportOptions options) throws IOException {
        String title = t("snippets.batchExport.report.title");
        int rows = rendered.size() + (skipped.isEmpty() ? 0 : skipped.size() + 2);
        int coverPages = coverPages(rows);
        List<PDDocument> chapters = new ArrayList<>();
        try (PDDocument document = new PDDocument()) {
            PdfReportKit.Fonts fonts = PdfReportKit.loadFonts(document);
            List<int[]> starts = new ArrayList<>();
            int next = coverPages;
            for (Rendered part : rendered) {
                starts.add(new int[] {next});
                next += part.pages();
            }
            List<LinkRow> links = drawCover(document, fonts, title, rendered, skipped, starts, options, coverPages);
            ExportBranding branding = options.branding() != null ? options.branding() : ExportBranding.defaults();
            PdfReportKit.applyPageChrome(document, fonts, branding,
                new PdfReportKit.ChromeText(title, SnippetAnalysisReportText.dateTime(options.exportedAt(), options)),
                true, null, MARGIN);
            PDFMergerUtility merger = new PDFMergerUtility();
            for (Rendered part : rendered) {
                PDDocument chapter = Loader.loadPDF(part.body());
                chapters.add(chapter);
                merger.appendDocument(document, chapter);
            }
            List<PdfReportKit.OutlineNode> outline = new ArrayList<>();
            for (int index = 0; index < rendered.size(); index++) {
                int start = starts.get(index)[0];
                if (start < document.getNumberOfPages()) {
                    PDPage page = document.getPage(start);
                    outline.add(new PdfReportKit.OutlineNode(entryTitle(rendered.get(index).item()), page,
                        page.getMediaBox().getHeight()));
                }
            }
            for (LinkRow link : links) {
                if (link.targetPage() < document.getNumberOfPages()) {
                    PDPage target = document.getPage(link.targetPage());
                    PdfReportKit.addGoToLink(document.getPage(link.coverPage()), link.rect(), target,
                        target.getMediaBox().getHeight());
                }
            }
            PdfReportKit.applyOutline(document, title, outline);
            PdfReportKit.applyMetadata(document, new PdfReportKit.Metadata(title, title, "korTTY, code analysis",
                "korTTY", "korTTY", options.exportedAt(), options.locale()));
            document.save(out);
            return document.getNumberOfPages();
        } finally {
            for (PDDocument chapter : chapters) {
                chapter.close();
            }
        }
    }

    private record LinkRow(int coverPage, PDRectangle rect, int targetPage) {
    }

    static int coverPages(int rows) {
        int firstPageRows = (int) Math.floor((FIRST_ROWS_TOP - BOTTOM) / ROW);
        int otherRows = (int) Math.floor((842f - 72f - BOTTOM) / ROW);
        if (rows <= firstPageRows) {
            return 1;
        }
        return 1 + (int) Math.ceil((rows - firstPageRows) / (double) otherRows);
    }

    private static List<LinkRow> drawCover(PDDocument document, PdfReportKit.Fonts fonts, String title,
                                           List<Rendered> rendered, List<Skipped> skipped, List<int[]> starts,
                                           ExportOptions options, int coverPages) throws IOException {
        List<LinkRow> links = new ArrayList<>();
        float width = PDRectangle.A4.getWidth() - 2 * MARGIN;
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        PDPageContentStream stream = new PDPageContentStream(document, page);
        try {
            PdfReportKit.fillRect(stream, 0, 842f - 130f, PDRectangle.A4.getWidth(), 130f, HERO);
            stream.fill();
            PdfReportKit.drawText(stream, fonts.bold(), 9f, new Color(0xD9, 0xEA, 0xFF), MARGIN, 842f - 52f, "KORTTY");
            PdfReportKit.drawText(stream, fonts.bold(), 22f, Color.WHITE, MARGIN, 842f - 82f,
                PdfReportKit.fit(title, fonts.bold(), 22f, width));
            PdfReportKit.drawText(stream, fonts.sans(), 10.5f, new Color(0xD9, 0xEA, 0xFF), MARGIN, 842f - 104f,
                PdfReportKit.fit(summaryLine(rendered.size(), options), fonts.sans(), 10.5f, width));
            PdfReportKit.drawText(stream, fonts.bold(), 14f, TEXT, MARGIN, TOP - 20f, r("contents"));
            float y = FIRST_ROWS_TOP;
            int pageIndex = 0;
            List<String[]> lines = new ArrayList<>();
            for (Rendered part : rendered) {
                lines.add(new String[] {(part.index() + 1) + ".  " + entryTitle(part.item()) + "  ·  "
                    + SnippetAnalysisReportText.kindLabel(part.item().report()),
                    String.valueOf(starts.get(part.index())[0] + 1), String.valueOf(part.index())});
            }
            if (!skipped.isEmpty()) {
                lines.add(new String[] {"", "", ""});
                lines.add(new String[] {t("snippets.batchExport.report.skipped"), "", "warn"});
                for (Skipped entry : skipped) {
                    lines.add(new String[] {"–  " + entry.snippetName(), "", "skip"});
                }
            }
            for (String[] line : lines) {
                if (y < BOTTOM) {
                    stream.close();
                    page = new PDPage(PDRectangle.A4);
                    document.addPage(page);
                    stream = new PDPageContentStream(document, page);
                    pageIndex++;
                    y = 842f - 72f;
                }
                boolean warn = "warn".equals(line[2]);
                PdfReportKit.FontFamily family = warn ? fonts.bold() : fonts.sans();
                String label = PdfReportKit.fit(line[0], family, 10.5f, width - 50f);
                PdfReportKit.drawText(stream, family, 10.5f, warn ? WARN : TEXT, MARGIN + ("skip".equals(line[2]) ? 12f : 0f),
                    y, label);
                if (!line[1].isEmpty()) {
                    float numberWidth = PdfReportKit.textWidth(fonts.sans(), 10.5f, line[1]);
                    PdfReportKit.drawText(stream, fonts.sans(), 10.5f, PdfReportKit.MUTED_TEXT,
                        MARGIN + width - numberWidth, y, line[1]);
                    int reportIndex = Integer.parseInt(line[2]);
                    links.add(new LinkRow(pageIndex, new PDRectangle(MARGIN, y - 4f, width, ROW),
                        starts.get(reportIndex)[0]));
                }
                y -= ROW;
            }
        } finally {
            stream.close();
        }
        while (document.getNumberOfPages() < coverPages) {
            document.addPage(new PDPage(PDRectangle.A4));
        }
        return links;
    }

    // ---- shared ----

    private static String entryTitle(Item item) {
        String name = item.snippetName().isBlank() ? item.report().header().scriptName() : item.snippetName();
        String title = name == null || name.isBlank() ? SnippetAnalysisReportText.appTitle() : name;
        return item.folderPath().isEmpty() ? title : item.folderPath() + " / " + title;
    }

    private static String summaryLine(int count, ExportOptions options) {
        return t("snippets.batchExport.report.summary", count,
            SnippetAnalysisReportText.dateTime(options.exportedAt(), options));
    }

    @FunctionalInterface
    private interface Writer {
        void write(OutputStream out) throws IOException;
    }

    private static void writeAtomically(Path target, Writer writer) throws IOException {
        Path absolute = target.toAbsolutePath();
        Path temp = absolute.resolveSibling("." + absolute.getFileName() + ".tmp");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                writer.write(out);
            }
            try {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, absolute, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }
}
