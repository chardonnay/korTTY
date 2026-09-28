package de.kortty.core;

import de.kortty.core.PdfReportKit.FontFamily;
import de.kortty.core.PdfReportKit.Fonts;
import de.kortty.core.PdfReportKit.OutlineNode;
import de.kortty.core.PdfReportKit.PageFlow;
import de.kortty.core.PdfReportKit.Row;
import de.kortty.core.PdfReportKit.SegmentChrome;
import de.kortty.core.SnippetAnalysisDiagramRasterizer.RasterizedDiagram;
import de.kortty.core.SnippetAnalysisExportService.ExportOptions;
import de.kortty.core.SnippetAnalysisReport.Change;
import de.kortty.core.SnippetAnalysisReport.DeltaItem;
import de.kortty.core.SnippetAnalysisReport.Dependency;
import de.kortty.core.SnippetAnalysisReport.Excerpt;
import de.kortty.core.SnippetAnalysisReport.Finding;
import de.kortty.core.SnippetAnalysisReport.FindingStatus;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static de.kortty.core.SnippetAnalysisReportText.r;
import static de.kortty.core.SnippetAnalysisReportText.t;

/**
 * The report as a paginated A4 PDF on {@link PdfReportKit}.
 *
 * <p>Page 1 is a cover: a blue hero with the script name, a meta grid with the three times
 * (analysed, applied, exported), stat tiles, a stacked "findings by category and severity" chart
 * and a linked table of contents that is filled in once the layout knows every page. From page 2
 * on come the summary, the apply result (after applying), one section per category with a
 * keep-together card per finding, the dependencies, the coloured diff, the verification, the
 * diagram — on a landscape page when it is wide, sliced across pages when it is tall, a note when
 * it could not be drawn — and the optional full script. Every page gets the continuation header,
 * the branded footer and "Page n of m"; the document gets an outline and metadata.</p>
 */
final class SnippetAnalysisPdfWriter {

    private static final PDRectangle PORTRAIT = PDRectangle.A4;
    private static final PDRectangle LANDSCAPE = new PDRectangle(PDRectangle.A4.getHeight(), PDRectangle.A4.getWidth());
    private static final float MARGIN = PdfReportKit.DEFAULT_MARGIN;
    private static final float TOP = PdfReportKit.DEFAULT_TOP_INSET;
    private static final float BOTTOM = PdfReportKit.DEFAULT_BOTTOM_INSET;

    private static final Color HERO = new Color(0x00, 0x66, 0xCC);
    private static final Color HERO_LIGHT = new Color(0xD9, 0xEA, 0xFF);
    private static final Color TEXT = new Color(0x1F, 0x29, 0x37);
    private static final Color BODY = new Color(0x37, 0x41, 0x51);
    private static final Color MUTED = PdfReportKit.MUTED_TEXT;
    private static final Color RULE = PdfReportKit.RULE;
    private static final Color CARD_BORDER = new Color(0xE5, 0xE7, 0xEB);
    private static final Color CARD_FILL = new Color(0xFC, 0xFC, 0xFD);
    private static final Color PANEL_FILL = new Color(0xF8, 0xFA, 0xFC);
    private static final Color REC_FILL = new Color(0xEF, 0xF6, 0xFF);
    private static final Color REC_BAR = new Color(0x3B, 0x82, 0xF6);
    private static final Color REC_LABEL = new Color(0x1D, 0x4E, 0xD8);
    private static final Color CODE_FILL = new Color(0xF8, 0xFA, 0xFC);
    private static final Color CODE_BORDER = new Color(0xE2, 0xE8, 0xF0);
    private static final Color CODE_GUTTER = new Color(0x94, 0xA3, 0xB8);
    private static final Color HIT_FILL = new Color(0xFE, 0xF3, 0xC7);
    private static final Color CHIP_FILL = new Color(0xEE, 0xF2, 0xF7);
    private static final Color CHIP_TEXT = new Color(0x33, 0x41, 0x55);
    private static final Color TRACK = new Color(0xF1, 0xF5, 0xF9);
    private static final Color TABLE_HEAD = new Color(0xF1, 0xF5, 0xF9);
    private static final Color ADD_FILL = new Color(0xE6, 0xFF, 0xEC);
    private static final Color ADD_TEXT = new Color(0x1A, 0x7F, 0x37);
    private static final Color DEL_FILL = new Color(0xFF, 0xEB, 0xE9);
    private static final Color DEL_TEXT = new Color(0xCF, 0x22, 0x2E);
    private static final Color CTX_TEXT = new Color(0x57, 0x60, 0x6A);
    private static final Color WARN_FILL = new Color(0xFF, 0xFB, 0xEB);
    private static final Color WARN_BORDER = new Color(0xF5, 0x9E, 0x0B);
    private static final Color WARN_TEXT = new Color(0x92, 0x40, 0x0E);
    private static final Color DEPENDENCY_KIND = new Color(0x64, 0x74, 0x8B);

    private static final float BODY_SIZE = 10f;
    private static final float BODY_LEADING = 14.2f;
    private static final float CODE_SIZE = 8.4f;
    private static final float CODE_LEADING = 11.6f;
    private static final float CARD_PAD_LEFT = 17f;
    private static final float CARD_PAD_RIGHT = 14f;
    private static final float CARD_GAP = 10f;
    private static final int MAX_DIFF_LINES = 1500;
    private static final int MAX_APPENDIX_LINES = 5000;
    private static final int MAX_DIAGRAM_SLICES = 8;
    private static final float MAX_DIAGRAM_SCALE = 1.25f;

    private SnippetAnalysisPdfWriter() {
    }

    /** Renders the report into {@code out}; returns the page count. */
    static int write(SnippetAnalysisReport report, ExportOptions options, RasterizedDiagram diagram, OutputStream out)
        throws IOException {

        try (PDDocument document = new PDDocument()) {
            Fonts fonts = PdfReportKit.loadFonts(document);
            Layout layout = new Layout(document, fonts, report, options,
                diagram != null ? diagram : RasterizedDiagram.none());
            layout.run();
            ExportBranding branding = options.branding() != null ? options.branding() : ExportBranding.defaults();
            PdfReportKit.applyOutline(document, SnippetAnalysisReportText.documentTitle(report), layout.outline());
            PdfReportKit.applyPageChrome(document, fonts, branding,
                new PdfReportKit.ChromeText(SnippetAnalysisReportText.documentTitle(report),
                    SnippetAnalysisReportText.kindLabel(report) + "  ·  "
                        + SnippetAnalysisReportText.dateTime(options.exportedAt(), options)),
                true, "snippets.ai.analysis.export.pdf.page", MARGIN);
            PdfReportKit.applyMetadata(document, new PdfReportKit.Metadata(
                SnippetAnalysisReportText.documentTitle(report),
                r(report.isPost() ? "pdf.subject.after" : "pdf.subject.before"),
                "korTTY, code analysis" + (report.header().scriptLanguage().isBlank() ? ""
                    : ", " + report.header().scriptLanguage()),
                "korTTY " + appVersion(),
                "korTTY",
                options.exportedAt(),
                options.locale()));
            document.save(out);
            return document.getNumberOfPages();
        }
    }

    private static String appVersion() {
        try {
            return de.kortty.KorTTYApplication.getAppVersion();
        } catch (RuntimeException | LinkageError e) {
            return "";
        }
    }

    /** A table-of-contents / outline entry; page and top are filled in when the section is laid out. */
    private static final class Section {
        final String title;
        final List<OutlineNode> children = new ArrayList<>();
        PDPage page;
        float top;

        Section(String title) {
            this.title = title;
        }
    }

    /** One layout pass over the report. */
    private static final class Layout {
        private final PDDocument document;
        private final Fonts fonts;
        private final SnippetAnalysisReport report;
        private final ExportOptions options;
        private final RasterizedDiagram diagram;
        private final List<Section> sections = new ArrayList<>();
        private PageFlow flow;
        private PDPage coverPage;
        private PDPage contentsPage;
        private float contentsTop;

        Layout(PDDocument document, Fonts fonts, SnippetAnalysisReport report, ExportOptions options,
               RasterizedDiagram diagram) {
            this.document = document;
            this.fonts = fonts;
            this.report = report;
            this.options = options;
            this.diagram = diagram;
        }

        void run() throws IOException {
            flow = new PageFlow(document, PORTRAIT, TOP, BOTTOM, MARGIN);
            try {
                coverPage = flow.page();
                Section summary = report.summary().isBlank() ? null : new Section(r("summary"));
                Section apply = report.apply() != null ? new Section(r("apply.title")) : null;
                List<Section> findingSections = new ArrayList<>();
                for (String category : SnippetAnalysisReportText.SECTIONS) {
                    int count = report.findingsOf(category).size();
                    findingSections.add(count > 0
                        ? new Section(SnippetAnalysisReportText.sectionTitle(category) + " (" + count + ")") : null);
                }
                Section dependencies = report.dependencies().isEmpty() ? null
                    : new Section(SnippetAnalysisReportText.sectionTitle("dependencies") + " ("
                        + report.dependencies().size() + ")");
                Section changes = report.apply() != null ? new Section(SnippetAnalysisReportText.diffTitle(report)) : null;
                Section verification = SnippetAnalysisReportText.hasVerification(report)
                    ? new Section(r("verify.title")) : null;
                Section diagramSection = new Section(t("snippets.ai.analysis.diagram.title"));
                Section appendix = options.includeFullCode() ? new Section(SnippetAnalysisReportText.codeTitle(report))
                    : null;
                addIfPresent(summary);
                addIfPresent(apply);
                findingSections.forEach(this::addIfPresent);
                addIfPresent(dependencies);
                addIfPresent(changes);
                addIfPresent(verification);
                addIfPresent(diagramSection);
                addIfPresent(appendix);

                cover();
                flow.newPage(PORTRAIT);
                if (summary != null) {
                    heading(summary, null, TEXT, 60f);
                    drawRows(textRows(report.summary(), MARGIN, contentWidth(), BODY_SIZE, BODY_LEADING, BODY));
                }
                if (apply != null) {
                    applyResult(apply);
                }
                for (int index = 0; index < SnippetAnalysisReportText.SECTIONS.size(); index++) {
                    Section section = findingSections.get(index);
                    if (section != null) {
                        findings(section, SnippetAnalysisReportText.SECTIONS.get(index));
                    }
                }
                if (dependencies != null) {
                    dependencies(dependencies);
                }
                if (changes != null) {
                    diff(changes);
                }
                if (verification != null) {
                    verification(verification);
                }
                diagram(diagramSection);
                if (appendix != null) {
                    appendix(appendix);
                }
            } finally {
                flow.close();
            }
            fillContents();
        }

        private void addIfPresent(Section section) {
            if (section != null) {
                sections.add(section);
            }
        }

        List<OutlineNode> outline() {
            List<OutlineNode> nodes = new ArrayList<>();
            nodes.add(new OutlineNode(r("overview"), coverPage, coverPage.getMediaBox().getHeight()));
            for (Section section : sections) {
                if (section.page != null) {
                    nodes.add(new OutlineNode(section.title, section.page, section.top, section.children));
                }
            }
            return nodes;
        }

        private float contentWidth() {
            return flow.contentWidth();
        }

        private PDPageContentStream stream() {
            return flow.stream();
        }

        // =============================================================================
        // Cover
        // =============================================================================

        private void cover() throws IOException {
            float pageHeight = PORTRAIT.getHeight();
            float width = contentWidth();
            float heroTop = pageHeight - 46f;
            float heroHeight = 124f;
            float heroBottom = heroTop - heroHeight;
            PDPageContentStream stream = stream();
            PdfReportKit.roundRect(stream, MARGIN, heroBottom, width, heroHeight, 12f, HERO, null, 0f);
            // Decorative rings, clipped to the hero panel.
            stream.saveGraphicsState();
            roundRectPath(stream, MARGIN, heroBottom, width, heroHeight, 12f);
            stream.clip();
            circle(stream, MARGIN + width - 36f, heroTop - 6f, 92f, new Color(0x0B, 0x72, 0xD9));
            circle(stream, MARGIN + width - 36f, heroTop - 6f, 62f, new Color(0x19, 0x7E, 0xE0));
            circle(stream, MARGIN + width - 150f, heroBottom - 30f, 60f, new Color(0x07, 0x6C, 0xD4));
            stream.restoreGraphicsState();

            float textX = MARGIN + 24f;
            float textWidth = width - 48f;
            String overline = SnippetAnalysisReportText.kindTitle(report).toUpperCase(options.locale());
            PdfReportKit.drawText(stream, fonts.bold(), 9.5f, HERO_LIGHT, textX, heroTop - 30f,
                PdfReportKit.fit(overline, fonts.bold(), 9.5f, textWidth - 150f));
            if (report.apply() != null) {
                String outcome = SnippetAnalysisReportText.outcomeLabel(report.apply().outcome());
                float pillWidth = PdfReportKit.textWidth(fonts.bold(), 8.6f, outcome) + 2f * Math.max(4f, 8.6f * 0.65f);
                PdfReportKit.pill(stream, fonts.bold(), 8.6f, outcome, Color.WHITE,
                    Color.decode(report.apply().outcome().colorHex()), MARGIN + width - 20f - pillWidth, heroTop - 30f);
            }
            String name = report.header().scriptName().isBlank() ? SnippetAnalysisReportText.appTitle()
                : report.header().scriptName();
            PdfReportKit.drawText(stream, fonts.bold(), 24f, Color.WHITE, textX, heroTop - 62f,
                PdfReportKit.fit(name, fonts.bold(), 24f, textWidth));
            PdfReportKit.drawText(stream, fonts.sans(), 12f, HERO_LIGHT, textX, heroTop - 82f,
                SnippetAnalysisReportText.appTitle());
            float chipX = textX;
            List<String> chips = new ArrayList<>();
            if (!report.header().scriptLanguage().isBlank()) {
                chips.add(report.header().scriptLanguage());
            }
            chips.add(r("chip.findings", report.findings().size()));
            if (report.isPost()) {
                chips.add(r("meta.run") + " " + r("meta.runValue", report.header().runNumber(),
                    report.header().runCount()));
            }
            for (String chip : chips) {
                chipX += PdfReportKit.pill(stream, fonts.sans(), 8.4f, chip, new Color(0x0A, 0x55, 0xAE), Color.WHITE,
                    chipX, heroBottom + 15f) + 6f;
            }
            flow.setCursor(heroBottom - 14f);

            metaGrid();
            if (report.header().stale()) {
                warningBox(r("meta.stale"));
            }
            statTiles();
            chart();
            reserveContents();
        }

        private void metaGrid() throws IOException {
            List<SnippetAnalysisReportText.Meta> rows = SnippetAnalysisReportText.metaRows(report, options);
            int columns = 3;
            float padding = 14f;
            float gap = 16f;
            float rowHeight = 31f;
            float width = contentWidth();
            float cellWidth = (width - 2f * padding - (columns - 1) * gap) / columns;
            int rowCount = (rows.size() + columns - 1) / columns;
            float height = rowCount * rowHeight + 10f;
            float top = flow.cursor();
            PdfReportKit.roundRect(stream(), MARGIN, top - height, width, height, 9f, PANEL_FILL, CARD_BORDER, 0.8f);
            for (int index = 0; index < rows.size(); index++) {
                SnippetAnalysisReportText.Meta meta = rows.get(index);
                float x = MARGIN + padding + (index % columns) * (cellWidth + gap);
                float cellTop = top - 8f - (index / columns) * rowHeight;
                PdfReportKit.drawText(stream(), fonts.sans(), 7.8f, MUTED, x, cellTop - 8f,
                    PdfReportKit.fit(meta.label().toUpperCase(options.locale()), fonts.sans(), 7.8f, cellWidth));
                FontFamily family = meta.mono() ? fonts.mono() : fonts.sans();
                PdfReportKit.drawText(stream(), family, 9.8f, TEXT, x, cellTop - 21.5f,
                    PdfReportKit.fit(meta.value(), family, 9.8f, cellWidth));
            }
            flow.setCursor(top - height - 10f);
        }

        private void warningBox(String text) throws IOException {
            float width = contentWidth();
            List<String> lines = PdfReportKit.wrapParagraph(text, fonts.sans(), 9f, width - 36f);
            float height = lines.size() * 12.4f + 14f;
            float top = flow.cursor();
            PdfReportKit.roundRect(stream(), MARGIN, top - height, width, height, 7f, WARN_FILL, WARN_BORDER, 0.9f);
            PdfReportKit.drawText(stream(), fonts.bold(), 10f, WARN_BORDER, MARGIN + 11f, top - 18f, "!");
            float y = top - 7f - 9.4f;
            for (String line : lines) {
                PdfReportKit.drawText(stream(), fonts.sans(), 9f, WARN_TEXT, MARGIN + 26f, y, line);
                y -= 12.4f;
            }
            flow.setCursor(top - height - 12f);
        }

        private void statTiles() throws IOException {
            List<SnippetAnalysisReportText.Stat> stats = SnippetAnalysisReportText.stats(report, options.locale());
            float gap = 10f;
            float width = (contentWidth() - gap * (stats.size() - 1)) / stats.size();
            float height = 50f;
            float top = flow.cursor();
            for (int index = 0; index < stats.size(); index++) {
                SnippetAnalysisReportText.Stat stat = stats.get(index);
                float x = MARGIN + index * (width + gap);
                Color color = Color.decode(stat.colorHex());
                PdfReportKit.roundRect(stream(), x, top - height, width, height, 8f, CARD_FILL, CARD_BORDER, 0.8f);
                PdfReportKit.roundRect(stream(), x + 10f, top - 7f, 22f, 3f, 1.5f, color, null, 0f);
                PdfReportKit.drawText(stream(), fonts.bold(), 19f, color, x + 10f, top - 28f, stat.value());
                PdfReportKit.drawText(stream(), fonts.sans(), 8.4f, MUTED, x + 10f, top - 41.5f,
                    PdfReportKit.fit(stat.label(), fonts.sans(), 8.4f, width - 20f));
            }
            flow.setCursor(top - height - 16f);
        }

        private void chart() throws IOException {
            float width = contentWidth();
            float top = flow.cursor();
            PdfReportKit.drawText(stream(), fonts.bold(), 11f, TEXT, MARGIN, top - 11f, r("chart.title"));
            float y = top - 22f;
            float labelWidth = 122f;
            float countWidth = 30f;
            float barX = MARGIN + labelWidth;
            float barWidth = width - labelWidth - countWidth;
            List<SnippetAnalysisReportText.Bar> bars = SnippetAnalysisReportText.severityBars(report);
            int max = Math.max(1, bars.stream().mapToInt(SnippetAnalysisReportText.Bar::total).max().orElse(1));
            float rowHeight = 18f;
            float barHeight = 10f;
            for (SnippetAnalysisReportText.Bar bar : bars) {
                float rowTop = y;
                float baseline = rowTop - 12.5f;
                Color color = Color.decode(AnalysisCategoryVisuals.printColorHex(bar.category()));
                glyph(bar.category(), MARGIN, baseline - 1.5f, 10.5f, color);
                PdfReportKit.drawText(stream(), fonts.sans(), 9.4f, BODY, MARGIN + 16f, baseline,
                    PdfReportKit.fit(bar.label(), fonts.sans(), 9.4f, labelWidth - 22f));
                float barBottom = rowTop - 5f - barHeight;
                PdfReportKit.roundRect(stream(), barX, barBottom, barWidth, barHeight, 2.5f, TRACK, null, 0f);
                stream().saveGraphicsState();
                roundRectPath(stream(), barX, barBottom, barWidth, barHeight, 2.5f);
                stream().clip();
                float x = barX;
                if ("dependencies".equals(bar.category())) {
                    float segment = barWidth * bar.total() / max;
                    if (segment > 0) {
                        PdfReportKit.fillRect(stream(), x, barBottom, segment, barHeight, color);
                    }
                } else {
                    for (AnalysisSeverity severity : AnalysisSeverity.values()) {
                        int count = bar.counts().getOrDefault(severity, 0);
                        if (count == 0) {
                            continue;
                        }
                        float segment = barWidth * count / max;
                        PdfReportKit.fillRect(stream(), x, barBottom, segment, barHeight,
                            Color.decode(severity.printColorHex()));
                        x += segment;
                    }
                }
                stream().restoreGraphicsState();
                PdfReportKit.drawText(stream(), fonts.bold(), 9.4f, TEXT, barX + barWidth + 8f, baseline,
                    Integer.toString(bar.total()));
                y -= rowHeight;
            }
            float legendX = barX;
            float legendY = y - 11f;
            for (AnalysisSeverity severity : AnalysisSeverity.values()) {
                String label = SnippetAnalysisReportText.severityLabel(severity);
                PdfReportKit.roundRect(stream(), legendX, legendY - 0.5f, 8f, 8f, 2f,
                    Color.decode(severity.printColorHex()), null, 0f);
                PdfReportKit.drawText(stream(), fonts.sans(), 8.2f, MUTED, legendX + 11f, legendY, label);
                legendX += 11f + PdfReportKit.textWidth(fonts.sans(), 8.2f, label) + 14f;
            }
            y = legendY - 14f;

            if (report.isPost()) {
                Map<FindingStatus, Integer> counts = SnippetAnalysisReportText.resultCounts(report);
                int total = counts.values().stream().mapToInt(Integer::intValue).sum();
                if (total > 0) {
                    y -= 6f;
                    PdfReportKit.drawText(stream(), fonts.bold(), 11f, TEXT, MARGIN, y - 11f, r("chart.result"));
                    y -= 22f;
                    float barBottom = y - 5f - barHeight;
                    PdfReportKit.roundRect(stream(), MARGIN, barBottom, width, barHeight, 2.5f, TRACK, null, 0f);
                    stream().saveGraphicsState();
                    roundRectPath(stream(), MARGIN, barBottom, width, barHeight, 2.5f);
                    stream().clip();
                    float x = MARGIN;
                    for (Map.Entry<FindingStatus, Integer> entry : counts.entrySet()) {
                        float segment = width * entry.getValue() / total;
                        PdfReportKit.fillRect(stream(), x, barBottom, segment, barHeight,
                            Color.decode(entry.getKey().colorHex()));
                        x += segment;
                    }
                    stream().restoreGraphicsState();
                    y -= 19f;
                    legendX = MARGIN;
                    legendY = y - 11f;
                    for (Map.Entry<FindingStatus, Integer> entry : counts.entrySet()) {
                        String label = SnippetAnalysisReportText.statusLabel(entry.getKey()) + " (" + entry.getValue() + ")";
                        PdfReportKit.roundRect(stream(), legendX, legendY - 0.5f, 8f, 8f, 2f,
                            Color.decode(entry.getKey().colorHex()), null, 0f);
                        PdfReportKit.drawText(stream(), fonts.sans(), 8.2f, MUTED, legendX + 11f, legendY, label);
                        legendX += 11f + PdfReportKit.textWidth(fonts.sans(), 8.2f, label) + 14f;
                    }
                    y = legendY - 14f;
                }
            }
            flow.setCursor(y - 8f);
        }

        private float contentsRowHeight = 16f;

        /**
         * Reserves the contents block on the cover; its rows shrink (to 13 pt) before it moves to a
         * page of its own.
         */
        private void reserveContents() throws IOException {
            float header = 26f;
            float fitted = (flow.available() - header) / Math.max(1, sections.size());
            if (fitted >= 13f) {
                contentsRowHeight = Math.min(16f, fitted);
            } else {
                flow.newPage(PORTRAIT);
                contentsRowHeight = 16f;
            }
            contentsPage = flow.page();
            contentsTop = flow.cursor();
            flow.advance(header + sections.size() * contentsRowHeight);
        }

        /** Draws the table of contents on the reserved area, now that every section has its page. */
        private void fillContents() throws IOException {
            if (contentsPage == null) {
                return;
            }
            float width = contentsPage.getMediaBox().getWidth() - 2f * MARGIN;
            try (PDPageContentStream stream = new PDPageContentStream(document, contentsPage, AppendMode.APPEND,
                    true, true)) {
                float top = contentsTop;
                PdfReportKit.drawText(stream, fonts.bold(), 11f, TEXT, MARGIN, top - 11f, r("contents"));
                PdfReportKit.line(stream, MARGIN, top - 18f, MARGIN + width, top - 18f, RULE, 0.7f);
                float y = top - 24f;
                for (Section section : sections) {
                    if (section.page == null) {
                        continue;
                    }
                    int pageNumber = document.getPages().indexOf(section.page) + 1;
                    String number = Integer.toString(pageNumber);
                    float numberWidth = PdfReportKit.textWidth(fonts.sans(), 9.6f, number);
                    float baseline = y - contentsRowHeight + 4f;
                    String title = PdfReportKit.fit(section.title, fonts.sans(), 9.6f, width - 70f);
                    float titleWidth = PdfReportKit.textWidth(fonts.sans(), 9.6f, title);
                    PdfReportKit.drawText(stream, fonts.sans(), 9.6f, TEXT, MARGIN, baseline, title);
                    PdfReportKit.drawText(stream, fonts.sans(), 9.6f, MUTED, MARGIN + width - numberWidth, baseline,
                        number);
                    stream.setLineDashPattern(new float[] {0.8f, 2.6f}, 0f);
                    PdfReportKit.line(stream, MARGIN + titleWidth + 6f, baseline + 1f,
                        MARGIN + width - numberWidth - 6f, baseline + 1f, CODE_GUTTER, 0.9f);
                    stream.setLineDashPattern(new float[] {}, 0f);
                    PdfReportKit.addGoToLink(contentsPage, new PDRectangle(MARGIN, y - contentsRowHeight, width,
                        contentsRowHeight), section.page, section.top);
                    y -= contentsRowHeight;
                }
            }
        }

        // =============================================================================
        // Sections
        // =============================================================================

        /**
         * A section title with its rule, kept together with the first {@code keepWithNext} points of
         * what follows, and registered for the contents and the outline.
         */
        private void heading(Section section, String iconCategory, Color color, float keepWithNext) throws IOException {
            float height = 32f;
            if (!flow.atTop()) {
                if (flow.available() < height + keepWithNext + 14f) {
                    flow.newPage(PORTRAIT);
                } else {
                    flow.advance(14f);
                }
            }
            float top = flow.cursor();
            section.page = flow.page();
            section.top = top + 6f;
            float x = MARGIN;
            if (iconCategory != null) {
                glyph(iconCategory, x, top - 17f, 14f, color);
                x += 20f;
            }
            PdfReportKit.drawText(stream(), fonts.bold(), 15f, color, x, top - 15f,
                PdfReportKit.fit(section.title, fonts.bold(), 15f, contentWidth() - (x - MARGIN)));
            PdfReportKit.line(stream(), MARGIN, top - 23f, MARGIN + contentWidth(), top - 23f, RULE, 0.8f);
            flow.setCursor(top - height);
        }

        private void subheading(String title, Color color) throws IOException {
            List<Row> rows = new ArrayList<>();
            rows.add(new Row(8f, (f, top) -> { }));
            rows.add(textRow(title, fonts.bold(), 11f, color, MARGIN, 16f));
            drawRows(rows, 40f);
        }

        private void applyResult(Section section) throws IOException {
            SnippetAnalysisReport.ApplyResult apply = report.apply();
            heading(section, null, TEXT, 80f);
            Color color = Color.decode(apply.outcome().colorHex());
            float width = contentWidth();
            String run = r("meta.run") + " " + r("meta.runValue", report.header().runNumber(), report.header().runCount())
                + "  ·  " + SnippetAnalysisReportText.dateTime(apply.appliedAt(), options);
            flow.drawKeepTogether(List.of(new Row(40f, (f, top) -> {
                PdfReportKit.roundRect(f.stream(), MARGIN, top - 32f, width, 32f, 8f, tint(color, 0.9f), color, 1f);
                PdfReportKit.roundRect(f.stream(), MARGIN + 10f, top - 22f, 12f, 12f, 6f, color, null, 0f);
                PdfReportKit.drawText(f.stream(), fonts.bold(), 12f, color, MARGIN + 30f, top - 20.5f,
                    PdfReportKit.fit(SnippetAnalysisReportText.outcomeLabel(apply.outcome()), fonts.bold(), 12f,
                        width * 0.55f));
                float runWidth = PdfReportKit.textWidth(fonts.sans(), 8.8f, run);
                PdfReportKit.drawText(f.stream(), fonts.sans(), 8.8f, MUTED, MARGIN + width - 12f - runWidth,
                    top - 20f, run);
            })), null);

            // Key/value table.
            float keyWidth = 150f;
            List<Row> kv = new ArrayList<>();
            List<SnippetAnalysisReportText.KeyValue> values = SnippetAnalysisReportText.applyRows(report, options);
            for (int index = 0; index < values.size(); index++) {
                SnippetAnalysisReportText.KeyValue value = values.get(index);
                List<String> lines = PdfReportKit.wrapParagraph(value.value(), fonts.sans(), 9.6f, width - keyWidth - 12f);
                float height = lines.size() * 13f + 6f;
                boolean zebra = index % 2 == 0;
                kv.add(new Row(height, (f, top) -> {
                    if (zebra) {
                        PdfReportKit.fillRect(f.stream(), MARGIN, top - height, width, height, PANEL_FILL);
                    }
                    PdfReportKit.drawText(f.stream(), fonts.sans(), 9f, MUTED, MARGIN + 8f, top - 12.5f,
                        PdfReportKit.fit(value.key(), fonts.sans(), 9f, keyWidth - 12f));
                    float y = top - 12.5f;
                    for (String line : lines) {
                        PdfReportKit.drawText(f.stream(), fonts.sans(), 9.6f, TEXT, MARGIN + keyWidth, y, line);
                        y -= 13f;
                    }
                }));
            }
            drawRows(kv);

            if (!apply.aiSummary().isBlank()) {
                subheading(r("apply.aiSummary"), TEXT);
                drawRows(textRows(apply.aiSummary(), MARGIN, width, BODY_SIZE, BODY_LEADING, BODY));
            }
            subheading(r("apply.statusTable"), TEXT);
            statusTable();
        }

        private void statusTable() throws IOException {
            float width = contentWidth();
            float idWidth = 62f;
            float severityWidth = 76f;
            float statusWidth = 118f;
            float titleWidth = width - idWidth - severityWidth - statusWidth;
            List<Row> rows = new ArrayList<>();
            int index = 0;
            List<Object[]> entries = new ArrayList<>();
            for (String section : SnippetAnalysisReportText.SECTIONS) {
                for (Finding finding : report.findingsOf(section)) {
                    entries.add(new Object[] {finding.id(), finding.title(), finding.severity(), finding.status()});
                }
            }
            for (Dependency dependency : report.dependencies()) {
                entries.add(new Object[] {dependency.id(), dependency.name(), null, dependency.status()});
            }
            for (Object[] entry : entries) {
                String id = (String) entry[0];
                List<RichLine> titleLines = wrapRich((String) entry[1], fonts.sans(), 9f, titleWidth - 12f);
                float height = Math.max(20f, titleLines.size() * 12f + 8f);
                AnalysisSeverity severity = (AnalysisSeverity) entry[2];
                FindingStatus status = (FindingStatus) entry[3];
                boolean zebra = index++ % 2 == 1;
                rows.add(new Row(height, (f, top) -> {
                    if (zebra) {
                        PdfReportKit.fillRect(f.stream(), MARGIN, top - height, width, height, PANEL_FILL);
                    }
                    PdfReportKit.line(f.stream(), MARGIN, top - height, MARGIN + width, top - height, CARD_BORDER, 0.6f);
                    PdfReportKit.drawText(f.stream(), fonts.mono(), 8.4f, CHIP_TEXT, MARGIN + 6f, top - 13f,
                        PdfReportKit.fit(id, fonts.mono(), 8.4f, idWidth - 10f));
                    float y = top - 13f;
                    for (RichLine line : titleLines) {
                        drawRich(f.stream(), line, fonts.sans(), 9f, TEXT, MARGIN + idWidth + 6f, y);
                        y -= 12f;
                    }
                    if (severity != null) {
                        PdfReportKit.pill(f.stream(), fonts.bold(), 6.8f,
                            SnippetAnalysisReportText.severityLabel(severity).toUpperCase(options.locale()),
                            Color.decode(severity.printColorHex()), Color.WHITE, MARGIN + idWidth + titleWidth + 4f,
                            top - 13f);
                    }
                    if (status != null) {
                        statusBadge(f.stream(), status, MARGIN + idWidth + titleWidth + severityWidth + 4f, top - 13f,
                            statusWidth - 8f);
                    }
                }));
            }
            SegmentChrome header = new SegmentChrome() {
                @Override
                public float headerHeight(boolean continued) {
                    return 20f;
                }

                @Override
                public float footerHeight() {
                    return 0f;
                }

                @Override
                public void paint(PageFlow f, float top, float height, boolean continued) throws IOException {
                    PdfReportKit.fillRect(f.stream(), MARGIN, top - 20f, width, 20f, TABLE_HEAD);
                    float baseline = top - 13.5f;
                    PdfReportKit.drawText(f.stream(), fonts.bold(), 8.4f, MUTED, MARGIN + 6f, baseline, r("table.id"));
                    PdfReportKit.drawText(f.stream(), fonts.bold(), 8.4f, MUTED, MARGIN + idWidth + 6f, baseline,
                        continued ? SnippetAnalysisReportText.continued(r("table.title")) : r("table.title"));
                    PdfReportKit.drawText(f.stream(), fonts.bold(), 8.4f, MUTED, MARGIN + idWidth + titleWidth + 4f,
                        baseline, r("table.severity"));
                    PdfReportKit.drawText(f.stream(), fonts.bold(), 8.4f, MUTED,
                        MARGIN + idWidth + titleWidth + severityWidth + 4f, baseline, r("table.status"));
                }
            };
            if (flow.available() < 20f + (rows.isEmpty() ? 0f : rows.getFirst().height())) {
                flow.newPage(PORTRAIT);
            }
            flow.drawKeepTogether(rows, header);
        }

        private void findings(Section section, String category) throws IOException {
            Color color = Color.decode(AnalysisCategoryVisuals.printColorHex(category));
            List<Finding> group = report.findingsOf(category);
            List<List<Row>> cards = new ArrayList<>();
            for (Finding finding : group) {
                cards.add(findingRows(finding, color));
            }
            heading(section, category, color, keepWithNext(total(cards.getFirst()) + 21f));
            for (int index = 0; index < group.size(); index++) {
                Finding finding = group.get(index);
                float cardTop = card(cards.get(index), color, finding.title());
                section.children.add(new OutlineNode(finding.id() + "  " + plain(finding.title()), cardPage, cardTop));
            }
        }

        private PDPage cardPage;

        /**
         * How much of the first block a heading must be kept with: all of it when the block fits a
         * page (it would move to the next page as a whole anyway), otherwise a good first slice.
         */
        private float keepWithNext(float firstBlockHeight) {
            return firstBlockHeight <= flow.pageContentHeight() - 50f ? firstBlockHeight : 140f;
        }

        /** Draws one keep-together card and returns its top (the page is left in {@link #cardPage}). */
        private float card(List<Row> rows, Color accent, String title) throws IOException {
            float width = contentWidth();
            float[] firstTop = {Float.NaN};
            PDPage[] firstPage = {null};
            SegmentChrome chrome = new SegmentChrome() {
                @Override
                public float headerHeight(boolean continued) {
                    return continued ? 26f : 11f;
                }

                @Override
                public float footerHeight() {
                    return 10f;
                }

                @Override
                public void paint(PageFlow f, float top, float height, boolean continued) throws IOException {
                    if (Float.isNaN(firstTop[0])) {
                        firstTop[0] = top;
                        firstPage[0] = f.page();
                    }
                    PdfReportKit.roundRect(f.stream(), MARGIN, top - height, width, height, 7f, CARD_FILL,
                        CARD_BORDER, 0.8f);
                    // The accent bar follows the card's rounded left edge.
                    f.stream().saveGraphicsState();
                    roundRectPath(f.stream(), MARGIN, top - height, width, height, 7f);
                    f.stream().clip();
                    PdfReportKit.fillRect(f.stream(), MARGIN, top - height, 4f, height, accent);
                    f.stream().restoreGraphicsState();
                    if (continued) {
                        PdfReportKit.drawText(f.stream(), fonts.sans(), 8.4f, MUTED, MARGIN + CARD_PAD_LEFT, top - 17f,
                            PdfReportKit.fit(SnippetAnalysisReportText.continued(plain(title)), fonts.sans(), 8.4f,
                                width - CARD_PAD_LEFT - CARD_PAD_RIGHT));
                    }
                }
            };
            flow.drawKeepTogether(rows, chrome);
            flow.advance(CARD_GAP);
            cardPage = firstPage[0] != null ? firstPage[0] : flow.page();
            return Float.isNaN(firstTop[0]) ? flow.cursor() : firstTop[0];
        }

        private List<Row> findingRows(Finding finding, Color accent) throws IOException {
            float x = MARGIN + CARD_PAD_LEFT;
            float width = contentWidth() - CARD_PAD_LEFT - CARD_PAD_RIGHT;
            List<Row> rows = new ArrayList<>();
            // Head: severity pill, id chip, status/selection, category tag; line on the right.
            String severity = SnippetAnalysisReportText.severityLabel(finding.severity()).toUpperCase(options.locale());
            String tag = SnippetAnalysisReportText.categoryTag(finding);
            rows.add(new Row(20f, (f, top) -> {
                float baseline = top - 13f;
                float cursor = x;
                cursor += PdfReportKit.pill(f.stream(), fonts.bold(), 7.4f, severity,
                    Color.decode(finding.severity().printColorHex()), Color.WHITE, cursor, baseline) + 5f;
                cursor += chip(f.stream(), finding.id(), cursor, baseline) + 5f;
                if (report.isPost()) {
                    cursor += statusBadge(f.stream(), finding.status(), cursor, baseline, width / 2f) + 5f;
                } else if (finding.selected()) {
                    cursor += PdfReportKit.pill(f.stream(), fonts.bold(), 7.4f, r("finding.selected"),
                        new Color(0xDB, 0xEA, 0xFE), REC_LABEL, cursor, baseline) + 5f;
                }
                float lineWidth = 0f;
                if (finding.line() != null) {
                    String line = SnippetAnalysisReportText.lineLabel(finding.line());
                    lineWidth = PdfReportKit.textWidth(fonts.mono(), 8.4f, line);
                    PdfReportKit.drawText(f.stream(), fonts.mono(), 8.4f, MUTED, x + width - lineWidth, baseline, line);
                }
                if (!tag.isEmpty()) {
                    float room = x + width - lineWidth - 10f - cursor;
                    if (room > 40f) {
                        PdfReportKit.pill(f.stream(), fonts.sans(), 7.4f,
                            PdfReportKit.fit(tag, fonts.sans(), 7.4f, room - 12f), CHIP_FILL, CHIP_TEXT, cursor, baseline);
                    }
                }
            }));
            rows.addAll(richRows(finding.title(), fonts.bold(), 11.5f, TEXT, x, width, 15.5f));
            rows.add(spacer(3f));
            rows.addAll(textRows(finding.detail(), x, width, BODY_SIZE, BODY_LEADING, BODY));
            if (!finding.recommendation().isBlank()) {
                rows.add(spacer(4f));
                rows.addAll(calloutRows(SnippetAnalysisReportText.recommendationLabel(), finding.recommendation(),
                    x, width));
            }
            Excerpt excerpt = finding.excerpt();
            if (excerpt != null) {
                rows.add(spacer(6f));
                rows.add(textRow(r("excerpt", excerpt.targetLine()), fonts.sans(), 8.2f, MUTED, x, 12f));
                rows.addAll(excerptRows(excerpt, x, width));
            }
            rows.addAll(changeRows(finding.changes(), x, width));
            return rows;
        }

        private void dependencies(Section section) throws IOException {
            Color color = Color.decode(AnalysisCategoryVisuals.printColorHex("dependencies"));
            List<List<Row>> cards = new ArrayList<>();
            for (Dependency dependency : report.dependencies()) {
                cards.add(dependencyRows(dependency));
            }
            heading(section, "dependencies", color, keepWithNext(total(cards.getFirst()) + 21f));
            for (int index = 0; index < cards.size(); index++) {
                Dependency dependency = report.dependencies().get(index);
                float top = card(cards.get(index), color, dependency.name());
                section.children.add(new OutlineNode(dependency.id() + "  " + plain(dependency.name()), cardPage, top));
            }
        }

        private List<Row> dependencyRows(Dependency dependency) throws IOException {
            float x = MARGIN + CARD_PAD_LEFT;
            float width = contentWidth() - CARD_PAD_LEFT - CARD_PAD_RIGHT;
            List<Row> rows = new ArrayList<>();
            rows.add(new Row(20f, (f, top) -> {
                float baseline = top - 13f;
                float cursor = x;
                if (!dependency.kind().isBlank()) {
                    cursor += PdfReportKit.pill(f.stream(), fonts.bold(), 7.4f,
                        PdfReportKit.fit(dependency.kind().toUpperCase(options.locale()), fonts.bold(), 7.4f, 120f),
                        DEPENDENCY_KIND, Color.WHITE, cursor, baseline) + 5f;
                }
                cursor += chip(f.stream(), dependency.id(), cursor, baseline) + 5f;
                if (report.isPost()) {
                    statusBadge(f.stream(), dependency.status(), cursor, baseline, width / 2f);
                } else if (dependency.selected()) {
                    PdfReportKit.pill(f.stream(), fonts.bold(), 7.4f, r("finding.selected"),
                        new Color(0xDB, 0xEA, 0xFE), REC_LABEL, cursor, baseline);
                }
            }));
            rows.addAll(richRows(dependency.name(), fonts.bold(), 11.5f, TEXT, x, width, 15.5f));
            if (!dependency.purpose().isBlank()) {
                rows.add(spacer(3f));
                rows.add(textRow(SnippetAnalysisReportText.purposeLabel(), fonts.bold(), 8.8f, MUTED, x, 12.5f));
                rows.addAll(textRows(dependency.purpose(), x, width, BODY_SIZE, BODY_LEADING, BODY));
            }
            if (!dependency.suggestion().isBlank()) {
                rows.add(spacer(4f));
                rows.addAll(calloutRows(SnippetAnalysisReportText.suggestionLabel(), dependency.suggestion(), x, width));
            }
            rows.addAll(changeRows(dependency.changes(), x, width));
            return rows;
        }

        private List<Row> changeRows(List<Change> changes, float x, float width) throws IOException {
            List<Row> rows = new ArrayList<>();
            if (changes.isEmpty()) {
                return rows;
            }
            rows.add(spacer(6f));
            rows.add(textRow(r("changes"), fonts.bold(), 9.2f, TEXT, x, 13f));
            for (Change change : changes) {
                List<String> lines = PdfReportKit.wrapParagraph(change.reason().isBlank() ? "—" : change.reason(),
                    fonts.sans(), 9.2f, width - 12f);
                for (int index = 0; index < lines.size(); index++) {
                    String line = lines.get(index);
                    boolean first = index == 0;
                    rows.add(new Row(12.8f, (f, top) -> {
                        if (first) {
                            PdfReportKit.roundRect(f.stream(), x + 1f, top - 8.4f, 3.4f, 3.4f, 1.7f, BODY, null, 0f);
                        }
                        PdfReportKit.drawText(f.stream(), fonts.sans(), 9.2f, BODY, x + 12f, top - 9.6f, line);
                    }));
                }
                String anchor = firstLine(change.anchor());
                if (!anchor.isEmpty()) {
                    rows.add(new Row(12f, (f, top) -> PdfReportKit.drawText(f.stream(), fonts.mono(), 7.8f, MUTED,
                        x + 12f, top - 9f, PdfReportKit.fit(anchor, fonts.mono(), 7.8f, width - 12f))));
                }
            }
            return rows;
        }

        private void diff(Section section) throws IOException {
            SnippetAnalysisReport.ApplyResult apply = report.apply();
            TextLineDiff.Result diff = apply.diff();
            String note = diff == null ? r("diff.notStored")
                : diff.tooLarge() ? r("diff.tooLarge", diff.oldLineCount(), diff.newLineCount())
                : diff.unchanged() ? r("diff.unchanged")
                : null;
            if (note != null) {
                heading(section, null, TEXT, 30f);
                drawRows(textRows(note, MARGIN, contentWidth(), 9.6f, 13.5f, MUTED));
                return;
            }
            float width = contentWidth();
            int budget = MAX_DIFF_LINES;
            int omitted = 0;
            List<List<Row>> hunks = new ArrayList<>();
            List<Integer> hunkIndexes = new ArrayList<>();
            for (int hunkIndex = 0; hunkIndex < diff.hunks().size(); hunkIndex++) {
                TextLineDiff.Hunk hunk = diff.hunks().get(hunkIndex);
                if (budget <= 0) {
                    omitted += hunk.lines().size();
                    continue;
                }
                int allowed = Math.min(budget, hunk.lines().size());
                budget -= hunk.lines().size();
                omitted += hunk.lines().size() - allowed;
                hunks.add(hunkRows(apply, hunk, apply.findingIdsOfHunk(hunkIndex), allowed, width));
                hunkIndexes.add(hunkIndex);
            }
            float statsHeight = 18f;
            heading(section, null, TEXT, statsHeight
                + (hunks.isEmpty() ? 0f : keepWithNext(total(hunks.getFirst()) + 22f)));
            drawRows(List.of(textRow(r("diff.stats", diff.added(), diff.removed(), diff.hunks().size()), fonts.sans(),
                9.6f, MUTED, MARGIN, statsHeight)));
            for (int index = 0; index < hunks.size(); index++) {
                TextLineDiff.Hunk hunk = diff.hunks().get(hunkIndexes.get(index));
                if (flow.available() < 22f + 4f * CODE_LEADING) {
                    flow.newPage(PORTRAIT);
                }
                flow.drawKeepTogether(hunks.get(index),
                    hunkChrome(hunk.header(), apply.findingIdsOfHunk(hunkIndexes.get(index)), width));
                flow.advance(10f);
            }
            if (omitted > 0) {
                drawRows(textRows(r("diff.omitted", omitted), MARGIN, width, 9.2f, 13f, MUTED));
            }
        }

        /** One hunk as rows: the change reasons of its findings, then at most {@code maxLines} diff lines. */
        private List<Row> hunkRows(SnippetAnalysisReport.ApplyResult apply, TextLineDiff.Hunk hunk, List<String> ids,
                                   int maxLines, float width) throws IOException {
            float gutter = 26f;
            float textX = MARGIN + 2f * gutter + 14f;
            float textWidth = width - (textX - MARGIN) - 6f;
            List<Row> rows = new ArrayList<>();
            for (Change change : apply.changes()) {
                if (ids.stream().anyMatch(id -> id.equalsIgnoreCase(change.findingId())) && !change.reason().isBlank()) {
                    List<String> lines = PdfReportKit.wrapParagraph(change.findingId() + ": " + change.reason(),
                        fonts.sans(), 8.6f, width - 20f);
                    for (String line : lines) {
                        rows.add(new Row(12f, (f, top) -> PdfReportKit.drawText(f.stream(), fonts.sans(), 8.6f,
                            BODY, MARGIN + 10f, top - 9f, line)));
                    }
                }
            }
            if (!rows.isEmpty()) {
                rows.addFirst(spacer(3f));
                rows.add(spacer(4f));
            }
            int drawn = 0;
            for (TextLineDiff.Line line : hunk.lines()) {
                if (drawn++ >= maxLines) {
                    break;
                }
                Color fill = switch (line.kind()) {
                    case ADDED -> ADD_FILL;
                    case REMOVED -> DEL_FILL;
                    case CONTEXT -> null;
                };
                Color color = switch (line.kind()) {
                    case ADDED -> ADD_TEXT;
                    case REMOVED -> DEL_TEXT;
                    case CONTEXT -> CTX_TEXT;
                };
                String sign = switch (line.kind()) {
                    case ADDED -> "+";
                    case REMOVED -> "-";
                    case CONTEXT -> " ";
                };
                List<String> wrapped = PdfReportKit.wrapCode(line.text(), fonts.mono(), CODE_SIZE, textWidth);
                for (int part = 0; part < wrapped.size(); part++) {
                    String text = wrapped.get(part);
                    boolean first = part == 0;
                    rows.add(new Row(CODE_LEADING, (f, top) -> {
                        if (fill != null) {
                            PdfReportKit.fillRect(f.stream(), MARGIN + 0.8f, top - CODE_LEADING, width - 1.6f,
                                CODE_LEADING + 0.5f, fill);
                        }
                        float baseline = top - 8.6f;
                        if (first) {
                            if (line.oldNumber() > 0) {
                                rightText(f.stream(), fonts.mono(), 7.6f, CODE_GUTTER, MARGIN + gutter - 4f,
                                    baseline, Integer.toString(line.oldNumber()));
                            }
                            if (line.newNumber() > 0) {
                                rightText(f.stream(), fonts.mono(), 7.6f, CODE_GUTTER, MARGIN + 2f * gutter - 4f,
                                    baseline, Integer.toString(line.newNumber()));
                            }
                            PdfReportKit.drawText(f.stream(), fonts.mono(), CODE_SIZE, color,
                                MARGIN + 2f * gutter + 3f, baseline, sign);
                        }
                        PdfReportKit.drawText(f.stream(), fonts.mono(), CODE_SIZE, color, textX, baseline, text);
                    }));
                }
            }
            rows.add(spacer(3f));
            return rows;
        }

        private SegmentChrome hunkChrome(String header, List<String> ids, float width) {
            return new SegmentChrome() {
                @Override
                public float headerHeight(boolean continued) {
                    return 22f;
                }

                @Override
                public float footerHeight() {
                    return 0f;
                }

                @Override
                public void paint(PageFlow f, float top, float height, boolean continued) throws IOException {
                    PdfReportKit.roundRect(f.stream(), MARGIN, top - height, width, height, 5f, Color.WHITE,
                        CODE_BORDER, 0.8f);
                    PdfReportKit.roundRect(f.stream(), MARGIN + 0.4f, top - 20f, width - 0.8f, 19.6f, 4.6f, CHIP_FILL,
                        null, 0f);
                    PdfReportKit.fillRect(f.stream(), MARGIN + 0.4f, top - 20f, width - 0.8f, 6f, CHIP_FILL);
                    String label = continued ? SnippetAnalysisReportText.continued(header) : header;
                    PdfReportKit.drawText(f.stream(), fonts.mono(), 8.4f, CHIP_TEXT, MARGIN + 8f, top - 13.5f, label);
                    float x = MARGIN + 16f + PdfReportKit.textWidth(fonts.mono(), 8.4f, label);
                    for (String id : ids) {
                        if (x > MARGIN + width - 60f) {
                            break;
                        }
                        x += PdfReportKit.pill(f.stream(), fonts.mono(), 7.4f, id, Color.WHITE, CHIP_TEXT, x,
                            top - 13.5f) + 4f;
                    }
                }
            };
        }

        private void verification(Section section) throws IOException {
            SnippetAnalysisReport.Verification verification = report.verification();
            heading(section, null, TEXT, 60f);
            drawRows(textRows(r("verify.heuristic"), MARGIN, contentWidth(), 8.8f, 12.4f, MUTED));
            deltaGroup(r("verify.resolved"), new Color(0x15, 0x80, 0x3D), verification.resolved());
            deltaGroup(r("verify.new"), new Color(0xB9, 0x1C, 0x1C), verification.introduced());
            deltaGroup(r("verify.persisting"), new Color(0xB4, 0x53, 0x09), verification.persisting());
        }

        private void deltaGroup(String title, Color color, List<DeltaItem> items) throws IOException {
            subheading(title + " (" + items.size() + ")", color);
            List<Row> rows = new ArrayList<>();
            float width = contentWidth();
            if (items.isEmpty()) {
                rows.add(textRow(r("none"), fonts.sans(), 9.2f, MUTED, MARGIN + 8f, 14f));
            }
            for (DeltaItem item : items) {
                String was = !item.previousId().isBlank() && !item.previousId().equals(item.id())
                    ? "  (" + r("verify.was", item.previousId()) + ")" : "";
                rows.add(new Row(19f, (f, top) -> {
                    float baseline = top - 13f;
                    float x = MARGIN + 8f;
                    x += PdfReportKit.pill(f.stream(), fonts.bold(), 6.8f,
                        SnippetAnalysisReportText.severityLabel(item.severity()).toUpperCase(options.locale()),
                        Color.decode(item.severity().printColorHex()), Color.WHITE, x, baseline) + 5f;
                    x += chip(f.stream(), item.id(), x, baseline) + 6f;
                    PdfReportKit.drawText(f.stream(), fonts.sans(), 9.4f, TEXT, x, baseline,
                        PdfReportKit.fit(plain(item.title()) + was, fonts.sans(), 9.4f, MARGIN + width - x));
                }));
            }
            drawRows(rows);
        }

        private void diagram(Section section) throws IOException {
            String typeLabel = report.diagram() != null
                ? SnippetAnalysisReportText.diagramTypeLabel(report.diagram().type()) : "";
            if (report.diagram() == null) {
                heading(section, null, TEXT, 30f);
                drawRows(textRows(t("snippets.ai.analysis.diagram.unavailable"), MARGIN, contentWidth(), 9.6f, 13.5f,
                    MUTED));
                return;
            }
            if (!diagram.rendered()) {
                heading(section, null, TEXT, 70f);
                drawRows(textRows(typeLabel, MARGIN, contentWidth(), 9.4f, 13f, MUTED));
                flow.advance(4f);
                String message = diagram.outcome().failed() ? diagram.outcome().message() : "";
                noteBox(r("diagram.unavailable", message) + "\n" + r("diagram.sourceHint"));
                return;
            }
            BufferedImage image = diagram.image();
            double unitWidth = diagram.unitWidth() > 0 ? diagram.unitWidth() : image.getWidth() / 2.0;
            double unitHeight = diagram.unitHeight() > 0 ? diagram.unitHeight() : image.getHeight() / 2.0;
            String notice = report.diagram().fallback() || !report.diagram().fallbackNotice().isBlank()
                ? (report.diagram().fallbackNotice().isBlank() ? t("snippets.ai.analysis.diagram.fallback.generic")
                    : report.diagram().fallbackNotice())
                : "";
            float titleBlock = 32f + 16f + (notice.isEmpty() ? 0f : 26f);
            float portraitWidth = PORTRAIT.getWidth() - 2f * MARGIN;
            float portraitHeight = PORTRAIT.getHeight() - TOP - BOTTOM - titleBlock;
            float landscapeWidth = LANDSCAPE.getWidth() - 2f * MARGIN;
            float landscapeHeight = LANDSCAPE.getHeight() - TOP - BOTTOM - titleBlock;
            float portraitScale = (float) Math.min(MAX_DIAGRAM_SCALE,
                Math.min(portraitWidth / unitWidth, portraitHeight / unitHeight));
            float landscapeScale = (float) Math.min(MAX_DIAGRAM_SCALE,
                Math.min(landscapeWidth / unitWidth, landscapeHeight / unitHeight));

            List<BufferedImage> slices = List.of(image);
            float scale;
            PDRectangle pageSize;
            if (landscapeScale > 1.15f * portraitScale) {
                pageSize = LANDSCAPE;
                scale = landscapeScale;
            } else {
                pageSize = PORTRAIT;
                scale = portraitScale;
                float widthScale = (float) Math.min(MAX_DIAGRAM_SCALE, portraitWidth / unitWidth);
                if (unitHeight > 1.6 * unitWidth && portraitScale < 0.5f) {
                    double stripUnits = portraitHeight / widthScale;
                    int pages = (int) Math.ceil(unitHeight / stripUnits);
                    if (pages > 1 && pages <= MAX_DIAGRAM_SLICES) {
                        int stripPx = (int) Math.floor(stripUnits * image.getHeight() / unitHeight);
                        slices = PdfReportKit.sliceVertically(image, Math.max(1, stripPx));
                        scale = widthScale;
                    }
                }
            }
            double pixelsPerUnit = image.getHeight() / unitHeight;
            for (int index = 0; index < slices.size(); index++) {
                BufferedImage slice = slices.get(index);
                flow.newPage(pageSize);
                if (slices.size() == 1) {
                    heading(section, null, TEXT, 0f);
                } else {
                    Section part = new Section(r("diagram.part", section.title, index + 1, slices.size()));
                    heading(part, null, TEXT, 0f);
                    if (index == 0) {
                        section.page = part.page;
                        section.top = part.top;
                    }
                }
                float top = flow.cursor();
                PdfReportKit.drawText(stream(), fonts.sans(), 9.4f, MUTED, MARGIN, top - 10f, typeLabel);
                top -= 16f;
                if (!notice.isEmpty() && index == 0) {
                    PdfReportKit.drawText(stream(), fonts.sans(), 8.6f, WARN_TEXT, MARGIN, top - 10f,
                        PdfReportKit.fit(notice, fonts.sans(), 8.6f, contentWidth()));
                    top -= 26f;
                } else if (!notice.isEmpty()) {
                    top -= 26f;
                }
                float drawWidth = (float) (slice.getWidth() / pixelsPerUnit * scale);
                float drawHeight = (float) (slice.getHeight() / pixelsPerUnit * scale);
                float x = MARGIN + (contentWidth() - drawWidth) / 2f;
                float y = top - 4f - drawHeight;
                PdfReportKit.drawImage(document, stream(), slice, x, y, drawWidth, drawHeight);
                flow.setCursor(y - 10f);
            }
        }

        private void appendix(Section section) throws IOException {
            flow.newPage(PORTRAIT);
            heading(section, null, TEXT, 0f);
            SnippetAnalysisReport.CodeSnapshot code = SnippetAnalysisReportText.appendixCode(report);
            if (code == null) {
                drawRows(textRows(r("diff.notStored"), MARGIN, contentWidth(), 9.6f, 13.5f, MUTED));
                return;
            }
            String[] lines = code.content().replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
            int count = lines.length > 1 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
            int shown = Math.min(count, MAX_APPENDIX_LINES);
            float width = contentWidth();
            float gutter = Math.max(24f, PdfReportKit.textWidth(fonts.mono(), 7.6f, Integer.toString(shown)) + 12f);
            float textX = MARGIN + gutter + 6f;
            float textWidth = width - gutter - 12f;
            List<Row> rows = new ArrayList<>();
            rows.add(new Row(5f, (f, top) -> panelEdge(f.stream(), MARGIN, top, width, 5f, true)));
            for (int index = 0; index < shown; index++) {
                int number = index + 1;
                List<String> wrapped = PdfReportKit.wrapCode(lines[index], fonts.mono(), CODE_SIZE, textWidth);
                for (int part = 0; part < wrapped.size(); part++) {
                    String text = wrapped.get(part);
                    boolean first = part == 0;
                    rows.add(new Row(CODE_LEADING, (f, top) -> {
                        panelSides(f.stream(), MARGIN, top, width, CODE_LEADING);
                        PdfReportKit.line(f.stream(), MARGIN + gutter, top - CODE_LEADING, MARGIN + gutter, top + 0.5f,
                            CODE_BORDER, 0.6f);
                        float baseline = top - 8.6f;
                        if (first) {
                            rightText(f.stream(), fonts.mono(), 7.6f, CODE_GUTTER, MARGIN + gutter - 5f, baseline,
                                Integer.toString(number));
                        }
                        PdfReportKit.drawText(f.stream(), fonts.mono(), CODE_SIZE, TEXT, textX, baseline, text);
                    }));
                }
            }
            rows.add(new Row(6f, (f, top) -> panelEdge(f.stream(), MARGIN, top, width, 5f, false)));
            drawRows(rows);
            if (shown < count) {
                flow.advance(6f);
                drawRows(textRows(r("code.truncated", shown), MARGIN, width, 9f, 13f, MUTED));
            }
        }

        // =============================================================================
        // Row builders
        // =============================================================================

        private void drawRows(List<Row> rows) throws IOException {
            drawRows(rows, 0f);
        }

        /** Draws rows one after another, breaking pages between rows; {@code keepWithNext} holds a heading. */
        private void drawRows(List<Row> rows, float keepWithNext) throws IOException {
            for (int index = 0; index < rows.size(); index++) {
                Row row = rows.get(index);
                float needed = row.height() + (index == rows.size() - 1 ? keepWithNext : 0f);
                if (flow.available() < needed && !flow.atTop()) {
                    flow.newPage(PORTRAIT);
                }
                row.painter().paint(flow, flow.cursor());
                flow.advance(row.height());
            }
        }

        // ---- prose with inline code ----

        /** One word of a line; {@code code} words sit on a chip in the mono font. */
        private record Word(String text, boolean code, boolean spaceBefore) {
        }

        private record RichLine(float indent, List<Word> words) {
        }

        private static final float CODE_PAD = 2.2f;
        private static final float CODE_SCALE = 0.9f;

        /** Prose rows where {@code `inline code`} is drawn in mono on a light chip, AI-style. */
        private List<Row> richRows(String text, FontFamily family, float size, Color color, float x, float width,
                                   float leading) throws IOException {
            List<Row> rows = new ArrayList<>();
            for (RichLine line : wrapRich(text, family, size, width)) {
                rows.add(new Row(leading, (f, top) -> drawRich(f.stream(), line, family, size, color, x,
                    top - leading * 0.72f)));
            }
            return rows;
        }

        private List<RichLine> wrapRich(String text, FontFamily family, float size, float width) throws IOException {
            List<RichLine> lines = new ArrayList<>();
            String normalized = PdfReportKit.normalizeText(text).replace("\t", "    ");
            float space = PdfReportKit.textWidth(family, size, " ");
            for (String raw : normalized.split("\n", -1)) {
                int indentEnd = 0;
                while (indentEnd < raw.length() && raw.charAt(indentEnd) == ' ') {
                    indentEnd++;
                }
                String content = raw.substring(indentEnd).strip();
                if (content.isEmpty()) {
                    lines.add(new RichLine(0f, List.of()));
                    continue;
                }
                float indent = indentEnd > 0 ? PdfReportKit.textWidth(family, size, " ".repeat(indentEnd)) : 0f;
                if (indent > width / 2f) {
                    indent = 0f;
                }
                float max = width - indent;
                List<Word> current = new ArrayList<>();
                float used = 0f;
                for (Word word : words(content)) {
                    float wordWidth = wordWidth(word, family, size);
                    float gap = current.isEmpty() || !word.spaceBefore() ? 0f : space;
                    if (continuesCode(current, word)) {
                        // Inside one inline-code span the words share a single chip and its padding.
                        wordWidth = PdfReportKit.textWidth(fonts.mono(), size * CODE_SCALE, word.text());
                        gap = PdfReportKit.textWidth(fonts.mono(), size * CODE_SCALE, " ");
                    }
                    if (!current.isEmpty() && used + gap + wordWidth > max) {
                        lines.add(new RichLine(indent, current));
                        current = new ArrayList<>();
                        used = 0f;
                        gap = 0f;
                    }
                    if (current.isEmpty() && wordWidth > max) {
                        FontFamily wordFamily = word.code() ? fonts.mono() : family;
                        float wordSize = word.code() ? size * CODE_SCALE : size;
                        List<String> parts = PdfReportKit.breakLongToken(PdfReportKit.prepareText(wordFamily,
                            word.text()), wordFamily, wordSize, max - 2f * CODE_PAD);
                        for (int index = 0; index < parts.size() - 1; index++) {
                            lines.add(new RichLine(indent, List.of(new Word(parts.get(index), word.code(), false))));
                        }
                        word = new Word(parts.getLast(), word.code(), false);
                        wordWidth = wordWidth(word, family, size);
                    }
                    current.add(word);
                    used += gap + wordWidth;
                }
                if (!current.isEmpty()) {
                    lines.add(new RichLine(indent, current));
                }
            }
            return lines;
        }

        /** Splits a line into words; a backtick pair makes its words code (an unpaired backtick stays text). */
        private static List<Word> words(String content) {
            List<Word> words = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            boolean code = false;
            boolean pendingSpace = false;
            for (int index = 0; index < content.length(); index++) {
                char character = content.charAt(index);
                if (character == '`' && (code || content.indexOf('`', index + 1) > index)) {
                    if (!current.isEmpty()) {
                        words.add(new Word(current.toString(), code, pendingSpace));
                        current.setLength(0);
                        pendingSpace = false;
                    }
                    code = !code;
                    continue;
                }
                if (Character.isWhitespace(character)) {
                    if (!current.isEmpty()) {
                        words.add(new Word(current.toString(), code, pendingSpace));
                        current.setLength(0);
                    }
                    pendingSpace = true;
                    continue;
                }
                if (current.isEmpty() && words.isEmpty()) {
                    pendingSpace = false;
                }
                current.append(character);
            }
            if (!current.isEmpty()) {
                words.add(new Word(current.toString(), code, pendingSpace));
            }
            return words;
        }

        private static boolean continuesCode(List<Word> line, Word word) {
            return word.code() && word.spaceBefore() && !line.isEmpty() && line.getLast().code();
        }

        private float wordWidth(Word word, FontFamily family, float size) throws IOException {
            return word.code()
                ? PdfReportKit.textWidth(fonts.mono(), size * CODE_SCALE, word.text()) + 2f * CODE_PAD
                : PdfReportKit.textWidth(family, size, word.text());
        }

        private void drawRich(PDPageContentStream stream, RichLine line, FontFamily family, float size, Color color,
                              float x, float baseline) throws IOException {
            float space = PdfReportKit.textWidth(family, size, " ");
            float codeSize = size * CODE_SCALE;
            float codeSpace = PdfReportKit.textWidth(fonts.mono(), codeSize, " ");
            float cursor = x + line.indent();
            List<Word> words = line.words();
            int index = 0;
            while (index < words.size()) {
                Word word = words.get(index);
                cursor += index > 0 && word.spaceBefore() ? space : 0f;
                if (!word.code()) {
                    PdfReportKit.drawText(stream, family, size, color, cursor, baseline, word.text());
                    cursor += PdfReportKit.textWidth(family, size, word.text());
                    index++;
                    continue;
                }
                // One chip per run of code words that were one span in the source.
                int end = index + 1;
                while (end < words.size() && words.get(end).code() && words.get(end).spaceBefore()) {
                    end++;
                }
                float runWidth = 2f * CODE_PAD;
                for (int part = index; part < end; part++) {
                    runWidth += PdfReportKit.textWidth(fonts.mono(), codeSize, words.get(part).text())
                        + (part > index ? codeSpace : 0f);
                }
                PdfReportKit.roundRect(stream, cursor, baseline - size * 0.3f, runWidth, size * 1.26f, 2f, CHIP_FILL,
                    null, 0f);
                float textX = cursor + CODE_PAD;
                for (int part = index; part < end; part++) {
                    String text = words.get(part).text();
                    PdfReportKit.drawText(stream, fonts.mono(), codeSize, CHIP_TEXT, textX, baseline, text);
                    textX += PdfReportKit.textWidth(fonts.mono(), codeSize, text) + codeSpace;
                }
                cursor += runWidth;
                index = end;
            }
        }

        private Row textRow(String text, FontFamily family, float size, Color color, float x, float leading) {
            return new Row(leading, (f, top) -> PdfReportKit.drawText(f.stream(), family, size, color, x,
                top - leading * 0.72f, text));
        }

        private Row spacer(float height) {
            return new Row(height, (f, top) -> { });
        }

        /** AI prose as rows: paragraphs (wrapped, indentation kept), code panels and tables. */
        private List<Row> textRows(String text, float x, float width, float size, float leading, Color color)
            throws IOException {

            List<Row> rows = new ArrayList<>();
            List<ReportTextBlocks.Block> blocks = ReportTextBlocks.split(text);
            for (int index = 0; index < blocks.size(); index++) {
                ReportTextBlocks.Block block = blocks.get(index);
                if (index > 0) {
                    rows.add(spacer(leading * 0.45f));
                }
                switch (block.type()) {
                    case PARAGRAPH -> rows.addAll(richRows(block.text(), fonts.sans(), size, color, x, width, leading));
                    case CODE -> rows.addAll(codePanelRows(block.text(), x, width));
                    case TABLE -> rows.addAll(tableRows(block.rows(), x, width));
                }
            }
            return rows;
        }

        private List<Row> codePanelRows(String code, float x, float width) throws IOException {
            List<Row> rows = new ArrayList<>();
            List<String> lines = PdfReportKit.wrapCode(code, fonts.mono(), CODE_SIZE, width - 16f);
            rows.add(new Row(4f, (f, top) -> panelEdge(f.stream(), x, top, width, 4f, true)));
            for (String line : lines) {
                rows.add(new Row(CODE_LEADING, (f, top) -> {
                    panelSides(f.stream(), x, top, width, CODE_LEADING);
                    PdfReportKit.drawText(f.stream(), fonts.mono(), CODE_SIZE, TEXT, x + 8f, top - 8.6f, line);
                }));
            }
            rows.add(new Row(5f, (f, top) -> panelEdge(f.stream(), x, top, width, 4f, false)));
            return rows;
        }

        private List<Row> tableRows(List<List<String>> table, float x, float width) throws IOException {
            List<Row> rows = new ArrayList<>();
            int columns = table.stream().mapToInt(List::size).max().orElse(0);
            if (columns == 0) {
                return rows;
            }
            float columnWidth = width / columns;
            for (int rowIndex = 0; rowIndex < table.size(); rowIndex++) {
                List<String> cells = table.get(rowIndex);
                boolean head = rowIndex == 0;
                FontFamily family = head ? fonts.bold() : fonts.sans();
                List<List<RichLine>> wrapped = new ArrayList<>();
                int maxLines = 1;
                for (int column = 0; column < columns; column++) {
                    String value = column < cells.size() ? cells.get(column) : "";
                    List<RichLine> lines = wrapRich(value, family, 8.8f, columnWidth - 10f);
                    wrapped.add(lines);
                    maxLines = Math.max(maxLines, lines.size());
                }
                float height = maxLines * 12f + 7f;
                rows.add(new Row(height, (f, top) -> {
                    if (head) {
                        PdfReportKit.fillRect(f.stream(), x, top - height, width, height, TABLE_HEAD);
                    }
                    for (int column = 0; column < columns; column++) {
                        float cellX = x + column * columnWidth;
                        PdfReportKit.strokeRect(f.stream(), cellX, top - height, columnWidth, height, CARD_BORDER, 0.6f);
                        float y = top - 12f;
                        for (RichLine line : wrapped.get(column)) {
                            drawRich(f.stream(), line, family, 8.8f, TEXT, cellX + 5f, y);
                            y -= 12f;
                        }
                    }
                }));
            }
            return rows;
        }

        /** A labelled callout (recommendation, suggestion): light blue fill with a blue bar, row by row. */
        private List<Row> calloutRows(String label, String text, float x, float width) throws IOException {
            List<Row> rows = new ArrayList<>();
            float innerX = x + 12f;
            float innerWidth = width - 20f;
            rows.add(new Row(6f, (f, top) -> callout(f.stream(), x, top, width, 6f)));
            rows.add(new Row(13f, (f, top) -> {
                callout(f.stream(), x, top, width, 13f);
                PdfReportKit.drawText(f.stream(), fonts.bold(), 8.8f, REC_LABEL, innerX, top - 9.4f, label);
            }));
            for (Row row : textRows(text, innerX, innerWidth, 9.6f, 13.6f, TEXT)) {
                rows.add(new Row(row.height(), (f, top) -> {
                    callout(f.stream(), x, top, width, row.height());
                    row.painter().paint(f, top);
                }));
            }
            rows.add(new Row(6f, (f, top) -> callout(f.stream(), x, top, width, 6f)));
            return rows;
        }

        private void callout(PDPageContentStream stream, float x, float top, float width, float height)
            throws IOException {
            PdfReportKit.fillRect(stream, x, top - height, width, height + 0.5f, REC_FILL);
            PdfReportKit.fillRect(stream, x, top - height, 3f, height + 0.5f, REC_BAR);
        }

        private List<Row> excerptRows(Excerpt excerpt, float x, float width) throws IOException {
            List<Row> rows = new ArrayList<>();
            float gutter = PdfReportKit.textWidth(fonts.mono(), 7.6f, Integer.toString(excerpt.lastLine())) + 14f;
            float textX = x + gutter + 6f;
            float textWidth = width - gutter - 12f;
            rows.add(new Row(4f, (f, top) -> panelEdge(f.stream(), x, top, width, 4f, true)));
            for (int index = 0; index < excerpt.lines().size(); index++) {
                int number = excerpt.firstLine() + index;
                String text = PdfReportKit.fit(excerpt.lines().get(index).replace("\t", "    "), fonts.mono(),
                    CODE_SIZE, textWidth);
                boolean hit = number == excerpt.targetLine();
                rows.add(new Row(CODE_LEADING, (f, top) -> {
                    panelSides(f.stream(), x, top, width, CODE_LEADING);
                    if (hit) {
                        PdfReportKit.fillRect(f.stream(), x + 0.6f, top - CODE_LEADING, width - 1.2f, CODE_LEADING,
                            HIT_FILL);
                    }
                    float baseline = top - 8.6f;
                    rightText(f.stream(), fonts.mono(), 7.6f, hit ? WARN_TEXT : CODE_GUTTER, x + gutter - 4f, baseline,
                        Integer.toString(number));
                    PdfReportKit.drawText(f.stream(), fonts.mono(), CODE_SIZE, TEXT, textX, baseline, text);
                }));
            }
            rows.add(new Row(5f, (f, top) -> panelEdge(f.stream(), x, top, width, 4f, false)));
            return rows;
        }

        /**
         * The rounded top ({@code upper}) or bottom edge of a code panel that is drawn row by row;
         * it paints only inside its own row, so it never covers the neighbouring line of code.
         */
        private void panelEdge(PDPageContentStream stream, float x, float top, float width, float height, boolean upper)
            throws IOException {
            float radius = Math.min(4f, height);
            float k = radius * 0.5523f;
            float right = x + width;
            float bottom = top - height;
            for (int pass = 0; pass < 2; pass++) {
                boolean fill = pass == 0;
                if (fill) {
                    stream.setNonStrokingColor(CODE_FILL);
                } else {
                    stream.setStrokingColor(CODE_BORDER);
                    stream.setLineWidth(0.7f);
                }
                float innerLeft = x + 0.35f;
                float innerRight = right - 0.35f;
                if (upper) {
                    float edge = top - 0.35f;
                    stream.moveTo(innerLeft, bottom);
                    stream.lineTo(innerLeft, edge - radius);
                    stream.curveTo(innerLeft, edge - radius + k, innerLeft + radius - k, edge, innerLeft + radius, edge);
                    stream.lineTo(innerRight - radius, edge);
                    stream.curveTo(innerRight - radius + k, edge, innerRight, edge - radius + k, innerRight, edge - radius);
                    stream.lineTo(innerRight, bottom);
                } else {
                    float edge = bottom + 0.35f;
                    stream.moveTo(innerLeft, top);
                    stream.lineTo(innerLeft, edge + radius);
                    stream.curveTo(innerLeft, edge + radius - k, innerLeft + radius - k, edge, innerLeft + radius, edge);
                    stream.lineTo(innerRight - radius, edge);
                    stream.curveTo(innerRight - radius + k, edge, innerRight, edge + radius - k, innerRight, edge + radius);
                    stream.lineTo(innerRight, top);
                }
                if (fill) {
                    stream.closePath();
                    stream.fill();
                } else {
                    stream.stroke();
                }
            }
        }

        private void panelSides(PDPageContentStream stream, float x, float top, float width, float height)
            throws IOException {
            // Overlap the row above by half a point so viewers do not show hairline seams.
            PdfReportKit.fillRect(stream, x, top - height, width, height + 0.5f, CODE_FILL);
            PdfReportKit.line(stream, x + 0.35f, top - height, x + 0.35f, top, CODE_BORDER, 0.7f);
            PdfReportKit.line(stream, x + width - 0.35f, top - height, x + width - 0.35f, top, CODE_BORDER, 0.7f);
        }

        private void noteBox(String text) throws IOException {
            float width = contentWidth();
            List<String> lines = PdfReportKit.wrapParagraph(text, fonts.sans(), 9.4f, width - 36f);
            float height = lines.size() * 13f + 16f;
            if (flow.available() < height) {
                flow.newPage(PORTRAIT);
            }
            float top = flow.cursor();
            PdfReportKit.roundRect(stream(), MARGIN, top - height, width, height, 7f, WARN_FILL, WARN_BORDER, 0.9f);
            PdfReportKit.drawText(stream(), fonts.bold(), 11f, WARN_BORDER, MARGIN + 12f, top - 19f, "!");
            float y = top - 8f - 10f;
            for (String line : lines) {
                PdfReportKit.drawText(stream(), fonts.sans(), 9.4f, WARN_TEXT, MARGIN + 28f, y, line);
                y -= 13f;
            }
            flow.setCursor(top - height - 10f);
        }

        // =============================================================================
        // Small drawing helpers
        // =============================================================================

        private float chip(PDPageContentStream stream, String text, float x, float baseline) throws IOException {
            return PdfReportKit.pill(stream, fonts.mono(), 7.8f, text, CHIP_FILL, CHIP_TEXT, x, baseline);
        }

        private float statusBadge(PDPageContentStream stream, FindingStatus status, float x, float baseline, float max)
            throws IOException {
            if (status == null) {
                return 0f;
            }
            Color color = Color.decode(status.colorHex());
            String label = PdfReportKit.fit(SnippetAnalysisReportText.statusLabel(status), fonts.bold(), 7.6f,
                Math.max(30f, max - 12f));
            return PdfReportKit.pill(stream, fonts.bold(), 7.6f, label, tint(color, 0.86f), color, x, baseline);
        }

        private void glyph(String category, float x, float y, float size, Color color) throws IOException {
            PdfReportKit.fillSvgPath(stream(), AnalysisCategoryVisuals.iconPath(category), x, y, size, color);
        }

        private void rightText(PDPageContentStream stream, FontFamily family, float size, Color color, float right,
                               float baseline, String text) throws IOException {
            float width = PdfReportKit.textWidth(family, size, text);
            PdfReportKit.drawText(stream, family, size, color, right - width, baseline, text);
        }

        private static float total(List<Row> rows) {
            float total = 0f;
            for (Row row : rows) {
                total += row.height();
            }
            return total;
        }
    }

    // ---- static helpers ----

    /** {@code color} mixed with white; {@code amount} 0 keeps the colour, 1 is white. */
    static Color tint(Color color, float amount) {
        float keep = 1f - amount;
        return new Color(
            Math.round(color.getRed() * keep + 255 * amount),
            Math.round(color.getGreen() * keep + 255 * amount),
            Math.round(color.getBlue() * keep + 255 * amount));
    }

    private static void circle(PDPageContentStream stream, float cx, float cy, float radius, Color color)
        throws IOException {
        float k = radius * 0.5523f;
        stream.setNonStrokingColor(color);
        stream.moveTo(cx + radius, cy);
        stream.curveTo(cx + radius, cy + k, cx + k, cy + radius, cx, cy + radius);
        stream.curveTo(cx - k, cy + radius, cx - radius, cy + k, cx - radius, cy);
        stream.curveTo(cx - radius, cy - k, cx - k, cy - radius, cx, cy - radius);
        stream.curveTo(cx + k, cy - radius, cx + radius, cy - k, cx + radius, cy);
        stream.closePath();
        stream.fill();
    }

    /** The path of a rounded rectangle without painting it (for clipping). */
    private static void roundRectPath(PDPageContentStream stream, float x, float y, float width, float height,
                                      float radius) throws IOException {
        float r = Math.max(0f, Math.min(radius, Math.min(width, height) / 2f));
        float k = r * 0.5523f;
        float right = x + width;
        float top = y + height;
        stream.moveTo(x + r, y);
        stream.lineTo(right - r, y);
        stream.curveTo(right - r + k, y, right, y + r - k, right, y + r);
        stream.lineTo(right, top - r);
        stream.curveTo(right, top - r + k, right - r + k, top, right - r, top);
        stream.lineTo(x + r, top);
        stream.curveTo(x + r - k, top, x, top - r + k, x, top - r);
        stream.lineTo(x, y + r);
        stream.curveTo(x, y + r - k, x + r - k, y, x + r, y);
        stream.closePath();
    }

    /** One-line text without the backticks of paired inline-code spans (bookmarks, fitted labels). */
    static String plain(String text) {
        return text == null ? "" : text.replaceAll("`([^`\\n]+)`", "$1");
    }

    private static String firstLine(String text) {
        if (text == null) {
            return "";
        }
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

}
