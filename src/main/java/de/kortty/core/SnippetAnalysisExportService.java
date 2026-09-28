package de.kortty.core;

import de.kortty.core.SnippetAnalysisDiagramRasterizer.RasterizedDiagram;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Exports a {@link SnippetAnalysisReport} — the AI code analysis before applying, or one apply run
 * after it — as a PDF, a self-contained HTML page, Markdown (with the diagram as a sibling PNG and
 * its Mermaid source) or JSON. The service is a facade: it validates, rasterizes the diagram once,
 * lets one writer per format render the report and replaces the target atomically, so a failed
 * export never leaves a half-written file behind.
 *
 * <p>{@link #export} is blocking I/O and must run off the JavaFX thread with the JavaFX toolkit
 * running (the diagram is drawn by the bundled Mermaid renderer). The report is immutable, so the
 * export reads nothing a user could change meanwhile.</p>
 */
public final class SnippetAnalysisExportService {

    public enum Format {
        PDF(".pdf", "snippets.ai.analysis.export.file.pdf", "snippets.ai.analysis.export.pdf"),
        HTML(".html", "snippets.ai.analysis.export.file.html", "snippets.ai.analysis.export.html"),
        MARKDOWN(".md", "snippets.ai.analysis.export.file.markdown", "snippets.ai.analysis.export.markdown"),
        JSON(".json", "snippets.ai.analysis.export.file.json", "snippets.ai.analysis.export.json");

        private final String extension;
        private final String filterKey;
        private final String menuKey;

        Format(String extension, String filterKey, String menuKey) {
            this.extension = extension;
            this.filterKey = filterKey;
            this.menuKey = menuKey;
        }

        public String getExtension() {
            return extension;
        }

        /** Message key of the file-chooser filter description. */
        public String getFilterKey() {
            return filterKey;
        }

        /** Message key of the Export menu entry. */
        public String getMenuKey() {
            return menuKey;
        }

        boolean needsDiagramImage() {
            return this != JSON;
        }
    }

    /**
     * Render-time choices.
     *
     * @param includeFullCode append the analysed (PRE) or resulting (POST) script
     * @param overwriteAssets replace existing sibling files (the Markdown PNG) — only after the user confirmed
     * @param locale          date and number formats and the document language
     * @param branding        {@code null}: the user's watermark and footer settings
     */
    public record ExportOptions(boolean includeFullCode, boolean overwriteAssets, Locale locale, ZoneId zone,
                                Instant exportedAt, ExportBranding branding) {

        public ExportOptions {
            locale = locale != null ? locale : Locale.ENGLISH;
            zone = zone != null ? zone : ZoneId.systemDefault();
            exportedAt = exportedAt != null ? exportedAt : Instant.now();
        }

        /** The UI language, the system time zone and now; the user's branding. */
        public static ExportOptions defaults() {
            return new ExportOptions(false, false, uiLocale(), ZoneId.systemDefault(), Instant.now(), null);
        }

        public ExportOptions withIncludeFullCode(boolean value) {
            return new ExportOptions(value, overwriteAssets, locale, zone, exportedAt, branding);
        }

        public ExportOptions withOverwriteAssets(boolean value) {
            return new ExportOptions(includeFullCode, value, locale, zone, exportedAt, branding);
        }
    }

    /** What happened to the diagram; {@code message} explains FAILED and TIMED_OUT. */
    public record DiagramOutcome(Status status, String message) {

        public enum Status { NONE, RENDERED, FAILED, TIMED_OUT }

        public DiagramOutcome {
            Objects.requireNonNull(status, "status");
            message = message != null ? message : "";
        }

        public static DiagramOutcome none() {
            return new DiagramOutcome(Status.NONE, "");
        }

        public static DiagramOutcome rendered() {
            return new DiagramOutcome(Status.RENDERED, "");
        }

        /** A diagram was stored but is missing from the export. */
        public boolean failed() {
            return status == Status.FAILED || status == Status.TIMED_OUT;
        }
    }

    /** @param pageCount pages of a PDF export, 0 for the other formats */
    public record ExportResult(List<Path> writtenFiles, DiagramOutcome diagram, int pageCount) {

        public ExportResult {
            writtenFiles = writtenFiles != null ? List.copyOf(writtenFiles) : List.of();
            diagram = diagram != null ? diagram : DiagramOutcome.none();
        }
    }

    private static final int MAX_FILE_BASE_LENGTH = 80;
    private static final DateTimeFormatter FILE_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmm", Locale.ROOT);

    private final SnippetAnalysisDiagramRasterizer rasterizer;
    private final ExportBranding brandingOverride;

    /** Draws diagrams with the bundled Mermaid renderer (2x, 60 s budget) and uses the user's branding. */
    public SnippetAnalysisExportService() {
        this(SnippetAnalysisDiagramRasterizer.mermaid(SnippetAnalysisDiagramRasterizer.DEFAULT_SCALE,
            SnippetAnalysisDiagramRasterizer.DEFAULT_TIMEOUT), null);
    }

    /** Test seam: a fake rasterizer and a fixed branding ({@code null}: the user's settings). */
    SnippetAnalysisExportService(SnippetAnalysisDiagramRasterizer rasterizer, ExportBranding branding) {
        this.rasterizer = Objects.requireNonNull(rasterizer, "rasterizer");
        this.brandingOverride = branding;
    }

    /**
     * Writes {@code report} to {@code target}.
     *
     * @throws FileAlreadyExistsException when a sibling asset ({@link #assetFiles}) exists and
     *                                    {@code options.overwriteAssets()} is false; nothing is written then
     */
    public ExportResult export(Path target, Format format, SnippetAnalysisReport report, ExportOptions options)
        throws IOException {

        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(format, "format");
        Objects.requireNonNull(report, "report");
        ExportOptions safe = options != null ? options : ExportOptions.defaults();
        if (safe.branding() == null) {
            safe = new ExportOptions(safe.includeFullCode(), safe.overwriteAssets(), safe.locale(), safe.zone(),
                safe.exportedAt(), brandingOverride != null ? brandingOverride : resolveBranding());
        }
        List<Path> assets = assetFiles(target, format, report);
        if (!safe.overwriteAssets()) {
            for (Path asset : assets) {
                if (Files.exists(asset)) {
                    throw new FileAlreadyExistsException(asset.toString());
                }
            }
        }

        RasterizedDiagram diagram = report.diagram() == null || !format.needsDiagramImage()
            ? RasterizedDiagram.none()
            : rasterizer.rasterize(report.diagram());
        if (diagram == null) {
            diagram = RasterizedDiagram.none();
        }

        List<Path> written = new ArrayList<>();
        int pageCount = 0;
        ExportOptions finalOptions = safe;
        RasterizedDiagram finalDiagram = diagram;
        switch (format) {
            case PDF -> {
                int[] pages = new int[1];
                writeAtomically(target, out -> pages[0] = SnippetAnalysisPdfWriter.write(report, finalOptions,
                    finalDiagram, out));
                pageCount = pages[0];
            }
            case HTML -> writeAtomically(target, out -> out.write(
                SnippetAnalysisHtmlWriter.render(report, finalOptions, finalDiagram).getBytes(StandardCharsets.UTF_8)));
            case JSON -> writeAtomically(target, out -> out.write(
                SnippetAnalysisJsonWriter.render(report, finalOptions).getBytes(StandardCharsets.UTF_8)));
            case MARKDOWN -> {
                Path png = assets.isEmpty() ? null : assets.getFirst();
                boolean withImage = png != null && diagram.rendered();
                if (withImage) {
                    writeAtomically(png, out -> javax.imageio.ImageIO.write(finalDiagram.image(), "png", out));
                    written.add(png);
                }
                String markdown = SnippetAnalysisMarkdownWriter.render(report, finalOptions, finalDiagram,
                    withImage ? png.getFileName().toString() : null);
                writeAtomically(target, out -> out.write(markdown.getBytes(StandardCharsets.UTF_8)));
            }
        }
        written.addFirst(target);
        return new ExportResult(written, diagram.outcome(), pageCount);
    }

    /**
     * The files an export writes next to {@code target}: for Markdown with a stored diagram the PNG
     * {@code <base>.diagram.png}; nothing for the other formats.
     */
    public static List<Path> assetFiles(Path target, Format format, SnippetAnalysisReport report) {
        return assetFiles(target, format, report != null && report.diagram() != null);
    }

    /** {@link #assetFiles(Path, Format, SnippetAnalysisReport)} before the report is built. */
    public static List<Path> assetFiles(Path target, Format format, boolean hasDiagram) {
        if (format != Format.MARKDOWN || !hasDiagram || target == null) {
            return List.of();
        }
        String name = target.getFileName().toString();
        String base = name.toLowerCase(Locale.ROOT).endsWith(".md") ? name.substring(0, name.length() - 3) : name;
        return List.of(target.resolveSibling(base + ".diagram.png"));
    }

    /**
     * {@code <script>-analysis-yyyyMMdd-HHmm.<ext>} before applying, {@code <script>-result-…} after,
     * stamped with the analysis (PRE) or run (POST) time. The name is reduced to letters, digits,
     * {@code . _ -}, at most 80 characters, and falls back to {@code code} (so {@code code-analysis.pdf}).
     */
    public static String suggestFileName(SnippetAnalysisReport report, Format format, ZoneId zone) {
        if (report == null) {
            return suggestFileName(null, false, null, format, zone);
        }
        boolean post = report.isPost();
        Instant when = post && report.header().appliedAt() != null ? report.header().appliedAt()
            : report.header().analysedAt();
        return suggestFileName(report.header().scriptName(), post, when, format, zone);
    }

    /** {@link #suggestFileName(SnippetAnalysisReport, Format, ZoneId)} before the report is built. */
    public static String suggestFileName(String scriptName, boolean post, Instant when, Format format, ZoneId zone) {
        String raw = scriptName != null ? scriptName : "";
        String base = raw.replaceAll("[^\\p{L}\\p{N}._-]+", "_").replaceAll("^[._]+|[._]+$", "");
        if (base.isBlank()) {
            base = "code";
        }
        if (base.length() > MAX_FILE_BASE_LENGTH) {
            base = base.substring(0, MAX_FILE_BASE_LENGTH);
        }
        String stamp = when != null ? "-" + FILE_STAMP.format(when.atZone(zone != null ? zone : ZoneId.systemDefault()))
            : "";
        Format safeFormat = format != null ? format : Format.PDF;
        return base + (post ? "-result" : "-analysis") + stamp + safeFormat.getExtension();
    }

    // ---- writing ----

    @FunctionalInterface
    private interface Writer {
        void write(OutputStream out) throws IOException;
    }

    /** Writes a hidden temp file next to {@code target} and moves it into place (atomically where supported). */
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

    private static ExportBranding resolveBranding() {
        try {
            de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
            return ExportBranding.fromSettings(app != null && app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings() : null);
        } catch (Exception | LinkageError e) {
            return ExportBranding.defaults();
        }
    }

    private static Locale uiLocale() {
        try {
            Locale locale = LanguageManager.getInstance().getCurrentLocale();
            return locale != null ? locale : Locale.ENGLISH;
        } catch (RuntimeException e) {
            return Locale.ENGLISH;
        }
    }
}
