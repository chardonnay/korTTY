package de.kortty.core;

import de.kortty.core.SnippetAiResponseSupport.ScriptAnalysis;
import de.kortty.core.SnippetAiResponseSupport.ScriptDependency;
import de.kortty.core.SnippetAiResponseSupport.ScriptImprovement;
import de.kortty.ui.I18n;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.PDPageContentStream.AppendMode;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;

/**
 * Exports a {@link ScriptAnalysis} (the "AI Code Analysis" report) — summary, categorized improvements,
 * external dependencies, and the flow diagram — into an attractive, self-contained HTML page, a Markdown
 * document, or a PDF. Unlike {@link AiChatExportService} (chat transcripts, text only), this exporter
 * embeds the rendered Mermaid diagram: base64-inline for HTML, a sibling PNG for Markdown, and a scaled
 * image page for the PDF. Diagram rendering reuses {@link MermaidRenderService}; the PDF reuses the same
 * bundled Noto fonts as the chat export.
 */
public final class SnippetAnalysisExportService {

    public enum Format {
        HTML(".html", "snippets.ai.analysis.export.file.html"),
        MARKDOWN(".md", "snippets.ai.analysis.export.file.markdown"),
        PDF(".pdf", "snippets.ai.analysis.export.file.pdf");

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

    /** Report metadata surfaced in the export header (all fields optional / nullable). */
    public record Context(String scriptName, String profileName, LocalDateTime generatedAt, List<String> includedSkills) {
    }

    private static final List<String> CATEGORY_ORDER = List.of("security", "optimization", "design");

    private static final String SANS_FONT_RESOURCE = "/fonts/noto/NotoSans-Regular.ttf";
    private static final String SANS_BOLD_FONT_RESOURCE = "/fonts/noto/NotoSans-Bold.ttf";
    private static final String MONO_FONT_RESOURCE = "/fonts/noto/NotoSansMono-Regular.ttf";
    private static final String WATERMARK_TEXT = "AI code analysis export from korTTY by Daniel Mengel";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm");

    private static final float PAGE_MARGIN = 48f;
    private static final float CONTENT_BOTTOM_Y = 60f;
    private static final float CONTENT_TOP_Y = PDRectangle.A4.getHeight() - 60f;

    /**
     * Writes the analysis to {@code target} in {@code format}. When {@code diagramRequest} is present the
     * diagram is rendered once and embedded; a render failure
     * degrades gracefully to a diagram-less report.
     */
    public void export(
        Path target,
        Format format,
        ScriptAnalysis analysis,
        Context context,
        MermaidRenderService.RenderRequest diagramRequest)
        throws IOException {

        ScriptAnalysis safe = analysis != null ? analysis : new ScriptAnalysis("", List.of(), List.of());
        Context ctx = context != null ? context : new Context(null, null, LocalDateTime.now(), List.of());
        byte[] diagramPng = renderDiagramPng(diagramRequest);
        switch (format) {
            case HTML -> Files.writeString(target, buildHtml(safe, ctx, diagramPng), StandardCharsets.UTF_8);
            case MARKDOWN -> writeMarkdown(target, safe, ctx, diagramPng);
            case PDF -> writePdf(target, safe, ctx, diagramPng);
        }
    }

    // ---- diagram -------------------------------------------------------------------------------

    private byte[] renderDiagramPng(MermaidRenderService.RenderRequest request) {
        if (request == null || request.source().isBlank()) {
            return null;
        }
        try {
            MermaidRenderService.RenderRequest pngRequest = new MermaidRenderService.RenderRequest(
                request.source(), request.theme(), request.backgroundColor(), true, request.generatedFlow());
            MermaidRenderService.RenderResult result = MermaidRenderService.render(pngRequest)
                .get(31, java.util.concurrent.TimeUnit.SECONDS);
            if (result != null && result.success()) {
                return result.png();
            }
        } catch (Exception ignored) {
            // A diagram that fails to render must not fail the whole export.
        }
        return null;
    }

    // ---- grouping / ordering -------------------------------------------------------------------

    private List<ScriptImprovement> improvementsFor(ScriptAnalysis analysis, String category) {
        return analysis.improvements().stream()
            .filter(item -> belongsToDisplayCategory(item.category(), category))
            .sorted(Comparator.comparingInt(item -> severityRank(item.severity())))
            .toList();
    }

    private static boolean belongsToDisplayCategory(String itemCategory, String displayCategory) {
        if ("design".equals(displayCategory)) {
            return !"security".equals(itemCategory) && !"optimization".equals(itemCategory);
        }
        return displayCategory.equals(itemCategory);
    }

    private static int severityRank(String severity) {
        String value = severity != null ? severity.trim().toLowerCase() : "";
        return switch (value) {
            case "critical", "crit" -> 0;
            case "high" -> 1;
            case "medium", "moderate", "med" -> 2;
            case "low" -> 3;
            default -> 4;
        };
    }

    private static String sectionTitle(String category) {
        return I18n.get("snippets.ai.analysis.section." + category);
    }

    // ---- HTML ----------------------------------------------------------------------------------

    private String buildHtml(ScriptAnalysis analysis, Context context, byte[] diagramPng) {
        StringBuilder body = new StringBuilder();
        body.append("<header><h1>").append(escapeHtml(I18n.get("snippets.ai.analysis.title"))).append("</h1>")
            .append("<div class=\"meta\">").append(escapeHtml(buildMetaLine(context))).append("</div>");
        if (context.includedSkills() != null && !context.includedSkills().isEmpty()) {
            body.append("<div class=\"skills\">")
                .append(escapeHtml(I18n.get("snippets.ai.analysis.export.meta.skills"))).append(" ");
            for (String skill : context.includedSkills()) {
                body.append("<span class=\"chip\">").append(escapeHtml(skill)).append("</span>");
            }
            body.append("</div>");
        }
        body.append("</header>");

        if (!analysis.summary().isBlank()) {
            body.append("<div class=\"summary\">").append(escapeHtml(analysis.summary())).append("</div>");
        }

        for (String category : CATEGORY_ORDER) {
            List<ScriptImprovement> group = improvementsFor(analysis, category);
            if (group.isEmpty()) {
                continue;
            }
            body.append("<h2 class=\"sec-").append(category).append("\">")
                .append(sectionIcon(category)).append(' ').append(escapeHtml(sectionTitle(category)))
                .append(" <span class=\"count\">(").append(group.size()).append(")</span></h2>");
            for (ScriptImprovement item : group) {
                body.append("<div class=\"card\"><div class=\"card-head\"><span class=\"pill ")
                    .append(severityClass(item.severity())).append("\">").append(escapeHtml(item.severity()))
                    .append("</span><span class=\"title\">").append(escapeHtml(item.title()));
                if (item.line() != null && item.line() > 0) {
                    body.append(" <span class=\"loc\">").append(escapeHtml(I18n.get("common.line")))
                        .append(' ').append(item.line()).append("</span>");
                }
                body.append("</span></div>");
                if (!item.detail().isBlank()) {
                    body.append("<p>").append(escapeHtml(item.detail())).append("</p>");
                }
                if (!item.recommendation().isBlank()) {
                    body.append("<div class=\"rec\"><b>")
                        .append(escapeHtml(I18n.get("snippets.ai.review.recommendation"))).append("</b> ")
                        .append(escapeHtml(item.recommendation())).append("</div>");
                }
                body.append("</div>");
            }
        }

        if (!analysis.dependencies().isEmpty()) {
            body.append("<h2 class=\"sec-dependencies\">").append(sectionIcon("dependencies")).append(' ')
                .append(escapeHtml(sectionTitle("dependencies")))
                .append(" <span class=\"count\">(").append(analysis.dependencies().size()).append(")</span></h2>");
            for (ScriptDependency dep : analysis.dependencies()) {
                body.append("<div class=\"card\"><div class=\"card-head\"><span class=\"pill sev-info\">")
                    .append(escapeHtml(dep.kind())).append("</span><span class=\"title\">")
                    .append(escapeHtml(dep.name())).append("</span></div>");
                if (!dep.purpose().isBlank()) {
                    body.append("<p>").append(escapeHtml(I18n.get("snippets.ai.analysis.dependency.purpose")))
                        .append(' ').append(escapeHtml(dep.purpose())).append("</p>");
                }
                if (!dep.suggestion().isBlank()) {
                    body.append("<div class=\"rec\"><b>")
                        .append(escapeHtml(I18n.get("snippets.ai.analysis.dependency.suggestion"))).append("</b> ")
                        .append(escapeHtml(dep.suggestion())).append("</div>");
                }
                body.append("</div>");
            }
        }

        if (diagramPng != null) {
            body.append("<h2>").append(escapeHtml(I18n.get("snippets.ai.analysis.diagram.title"))).append("</h2>")
                .append("<div class=\"diagram\"><img alt=\"diagram\" src=\"data:image/png;base64,")
                .append(Base64.getEncoder().encodeToString(diagramPng)).append("\"></div>");
        }

        body.append("<footer>").append(escapeHtml(WATERMARK_TEXT)).append("</footer>");
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"UTF-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>" + escapeHtml(I18n.get("snippets.ai.analysis.title")) + "</title><style>"
            + htmlCss() + "</style></head><body>" + body + "</body></html>";
    }

    private static String htmlCss() {
        return "*{box-sizing:border-box;}"
            + "body{margin:0;padding:32px;max-width:900px;margin:0 auto;color:#1f2937;background:#ffffff;"
            + "font-family:-apple-system,'Segoe UI',Roboto,Helvetica,Arial,sans-serif;line-height:1.55;}"
            + "header{border-bottom:2px solid #e5e7eb;padding-bottom:14px;margin-bottom:20px;}"
            + "h1{font-size:1.7em;margin:0 0 6px;}"
            + ".meta{color:#6b7280;font-size:0.9em;}"
            + ".skills{margin-top:8px;font-size:0.85em;color:#6b7280;}"
            + ".chip{display:inline-block;background:rgba(59,130,246,0.14);color:#1d4ed8;border-radius:999px;"
            + "padding:1px 9px;margin:2px 4px 2px 0;font-size:0.9em;}"
            + "h2{font-size:1.15em;margin:26px 0 10px;}"
            + "h2.sec-security{color:#e5484d;} h2.sec-optimization{color:#d97706;}"
            + "h2.sec-design{color:#8b5cf6;} h2.sec-dependencies{color:#0d9488;}"
            + ".sec-ic{width:.95em;height:.95em;fill:currentColor;vertical-align:-.13em;margin-right:6px;}"
            + ".count{color:#9ca3af;font-weight:400;font-size:0.8em;}"
            + ".summary{background:#f3f4f6;border-radius:8px;padding:12px 14px;margin-bottom:6px;white-space:pre-wrap;}"
            + ".card{border:1px solid #e5e7eb;border-radius:10px;padding:12px 14px;margin-bottom:12px;background:#fcfcfd;}"
            + ".card-head{display:flex;align-items:center;gap:10px;}"
            + ".pill{font-size:0.72em;font-weight:700;letter-spacing:.04em;padding:2px 9px;border-radius:999px;"
            + "text-transform:uppercase;white-space:nowrap;color:#fff;}"
            + ".pill.sev-critical,.pill.sev-high{background:#c0392b;} .pill.sev-medium{background:#e67e22;}"
            + ".pill.sev-low{background:#b7950b;} .pill.sev-info{background:#7f8c8d;}"
            + ".title{font-weight:600;} .loc{color:#9ca3af;font-size:0.82em;font-family:monospace;}"
            + ".card p{margin:9px 0 0;white-space:pre-wrap;}"
            + ".rec{margin:10px 0 0;padding:8px 11px;border-left:3px solid #3b82f6;background:#f3f4f6;"
            + "border-radius:0 6px 6px 0;white-space:pre-wrap;}"
            + ".diagram{text-align:center;} .diagram img{max-width:100%;border:1px solid #e5e7eb;border-radius:8px;}"
            + "footer{margin-top:32px;padding-top:12px;border-top:1px solid #e5e7eb;color:#9ca3af;font-size:0.8em;}";
    }

    private static String severityClass(String severity) {
        return switch (severityRank(severity)) {
            case 0 -> "sev-critical";
            case 1 -> "sev-high";
            case 2 -> "sev-medium";
            case 3 -> "sev-low";
            default -> "sev-info";
        };
    }

    /** Inline-SVG section glyph (coloured via CSS {@code currentColor}); see the note in the dialog's twin. */
    private static String sectionIcon(String category) {
        String path = switch (category) {
            case "security" -> "M8 1.3 13.5 3.3V7.6C13.5 10.8 11.2 13.4 8 14.4 4.8 13.4 2.5 10.8 2.5 7.6V3.3Z";
            case "optimization" -> "M9 1 4 8.5H7L6.5 15 12 6.5H8.5L9 1Z";
            case "dependencies" -> "M8 1.4 13.6 4.2V9.8L8 12.6 2.4 9.8V4.2Z";
            default -> "M8 1.5C11 5 13 7.5 13 10A5 5 0 0 1 3 10C3 7.5 5 5 8 1.5Z";
        };
        return "<svg class=\"sec-ic\" viewBox=\"0 0 16 16\" xmlns=\"http://www.w3.org/2000/svg\"><path d=\""
            + path + "\"/></svg>";
    }

    // ---- Markdown ------------------------------------------------------------------------------

    private void writeMarkdown(Path target, ScriptAnalysis analysis, Context context, byte[] diagramPng) throws IOException {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(I18n.get("snippets.ai.analysis.title")).append("\n\n");
        md.append("_").append(buildMetaLine(context)).append("_\n");
        if (context.includedSkills() != null && !context.includedSkills().isEmpty()) {
            md.append("\n**").append(I18n.get("snippets.ai.analysis.export.meta.skills")).append("** ")
                .append(String.join(", ", context.includedSkills())).append("\n");
        }
        if (!analysis.summary().isBlank()) {
            md.append("\n").append(analysis.summary().strip()).append("\n");
        }
        for (String category : CATEGORY_ORDER) {
            List<ScriptImprovement> group = improvementsFor(analysis, category);
            if (group.isEmpty()) {
                continue;
            }
            md.append("\n## ").append(sectionTitle(category)).append(" (").append(group.size()).append(")\n");
            for (ScriptImprovement item : group) {
                md.append("\n### ").append(item.title());
                if (item.line() != null && item.line() > 0) {
                    md.append(" (").append(I18n.get("common.line")).append(' ').append(item.line()).append(')');
                }
                md.append("\n\n- **").append(item.severity()).append("**");
                if (!item.detail().isBlank()) {
                    md.append(" — ").append(item.detail().strip());
                }
                md.append("\n");
                if (!item.recommendation().isBlank()) {
                    md.append("- **").append(I18n.get("snippets.ai.review.recommendation")).append("** ")
                        .append(item.recommendation().strip()).append("\n");
                }
            }
        }
        if (!analysis.dependencies().isEmpty()) {
            md.append("\n## ").append(sectionTitle("dependencies")).append(" (")
                .append(analysis.dependencies().size()).append(")\n");
            for (ScriptDependency dep : analysis.dependencies()) {
                md.append("\n- **").append(dep.name()).append("** (").append(dep.kind()).append(')');
                if (!dep.purpose().isBlank()) {
                    md.append(" — ").append(dep.purpose().strip());
                }
                md.append("\n");
                if (!dep.suggestion().isBlank()) {
                    md.append("  - ").append(I18n.get("snippets.ai.analysis.dependency.suggestion")).append(' ')
                        .append(dep.suggestion().strip()).append("\n");
                }
            }
        }
        if (diagramPng != null) {
            String base = target.getFileName().toString().replaceFirst("\\.md$", "");
            Path pngPath = target.resolveSibling(base + ".diagram.png");
            Files.write(pngPath, diagramPng);
            md.append("\n## ").append(I18n.get("snippets.ai.analysis.diagram.title")).append("\n\n")
                .append("![diagram](").append(pngPath.getFileName().toString()).append(")\n");
        }
        md.append("\n---\n_").append(WATERMARK_TEXT).append("_\n");
        Files.writeString(target, md.toString(), StandardCharsets.UTF_8);
    }

    // ---- PDF -----------------------------------------------------------------------------------

    private void writePdf(Path target, ScriptAnalysis analysis, Context context, byte[] diagramPng) throws IOException {
        try (PDDocument document = new PDDocument()) {
            PdfFonts fonts = new PdfFonts(
                loadFont(document, SANS_FONT_RESOURCE),
                loadFont(document, SANS_BOLD_FONT_RESOURCE),
                loadFont(document, MONO_FONT_RESOURCE));

            Cursor cursor = newPage(document, null);
            cursor = drawHeader(document, cursor, fonts, context);
            if (!analysis.summary().isBlank()) {
                cursor = drawParagraph(document, cursor, fonts.sans, 11f, new Color(0x1f, 0x29, 0x37),
                    analysis.summary(), 6f);
                cursor = cursor.withY(cursor.y - 6f);
            }
            for (String category : CATEGORY_ORDER) {
                List<ScriptImprovement> group = improvementsFor(analysis, category);
                if (group.isEmpty()) {
                    continue;
                }
                cursor = drawSectionTitle(document, cursor, fonts, sectionTitle(category) + " (" + group.size() + ")",
                    sectionColor(category));
                for (ScriptImprovement item : group) {
                    cursor = drawFinding(document, cursor, fonts,
                        item.severity(), item.title(), item.line(), item.detail(), item.recommendation(),
                        I18n.get("snippets.ai.review.recommendation"));
                }
            }
            if (!analysis.dependencies().isEmpty()) {
                cursor = drawSectionTitle(document, cursor, fonts,
                    sectionTitle("dependencies") + " (" + analysis.dependencies().size() + ")",
                    sectionColor("dependencies"));
                for (ScriptDependency dep : analysis.dependencies()) {
                    cursor = drawFinding(document, cursor, fonts,
                        dep.kind(), dep.name(), null, dep.purpose(), dep.suggestion(),
                        I18n.get("snippets.ai.analysis.dependency.suggestion"));
                }
            }
            cursor.stream.close();
            if (diagramPng != null) {
                drawDiagramPage(document, fonts, diagramPng);
            }

            drawFooters(document, fonts);
            document.save(target.toFile());
        }
    }

    private Cursor drawHeader(PDDocument document, Cursor cursor, PdfFonts fonts, Context context) throws IOException {
        float width = contentWidth();
        drawFilledRect(cursor.stream, PAGE_MARGIN, cursor.y - 70f, width, 70f, new Color(0x0f, 0x62, 0xcc));
        drawText(cursor.stream, fonts.sansBold, 20f, Color.WHITE, PAGE_MARGIN + 16f, cursor.y - 30f,
            fit(I18n.get("snippets.ai.analysis.title"), fonts.sansBold, 20f, width - 32f));
        drawText(cursor.stream, fonts.sans, 9.5f, new Color(0xd9, 0xea, 0xff), PAGE_MARGIN + 16f, cursor.y - 52f,
            fit(buildMetaLine(context), fonts.sans, 9.5f, width - 32f));
        cursor = cursor.withY(cursor.y - 84f);
        if (context.includedSkills() != null && !context.includedSkills().isEmpty()) {
            cursor = drawParagraph(document, cursor, fonts.sans, 9.5f, new Color(0x5e, 0x6e, 0x82),
                I18n.get("snippets.ai.analysis.export.meta.skills") + " " + String.join(", ", context.includedSkills()), 4f);
            cursor = cursor.withY(cursor.y - 4f);
        }
        return cursor;
    }

    private Cursor drawSectionTitle(PDDocument document, Cursor cursor, PdfFonts fonts, String title, Color color)
        throws IOException {
        cursor = ensureSpace(document, cursor, 30f);
        cursor = cursor.withY(cursor.y - 8f);
        drawText(cursor.stream, fonts.sansBold, 13f, color, PAGE_MARGIN, cursor.y,
            fit(title, fonts.sansBold, 13f, contentWidth()));
        drawLine(cursor.stream, PAGE_MARGIN, cursor.y - 6f, PAGE_MARGIN + contentWidth(), cursor.y - 6f,
            new Color(0xe5, 0xe7, 0xeb), 0.7f);
        return cursor.withY(cursor.y - 20f);
    }

    private Cursor drawFinding(PDDocument document, Cursor cursor, PdfFonts fonts,
                               String badge, String title, Integer line, String detail, String recommendation,
                               String recommendationLabel) throws IOException {
        cursor = ensureSpace(document, cursor, 34f);
        String head = (badge != null && !badge.isBlank() ? "[" + badge.toUpperCase() + "] " : "")
            + (title != null ? title : "")
            + (line != null && line > 0 ? "  (" + I18n.get("common.line") + " " + line + ")" : "");
        cursor = drawParagraph(document, cursor, fonts.sansBold, 11f, sectionColorForSeverity(badge), head, 3f);
        if (detail != null && !detail.isBlank()) {
            cursor = drawParagraph(document, cursor, fonts.sans, 10.5f, new Color(0x37, 0x41, 0x51), detail, 3f);
        }
        if (recommendation != null && !recommendation.isBlank()) {
            cursor = drawParagraph(document, cursor, fonts.sans, 10.5f, new Color(0x1d, 0x4e, 0xd8),
                recommendationLabel + " " + recommendation, 3f);
        }
        return cursor.withY(cursor.y - 8f);
    }

    private void drawDiagramPage(PDDocument document, PdfFonts fonts, byte[] diagramPng) throws IOException {
        BufferedImage image = ImageIO.read(new java.io.ByteArrayInputStream(diagramPng));
        if (image == null) {
            return;
        }
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        try (PDPageContentStream stream = new PDPageContentStream(document, page)) {
            drawText(stream, fonts.sansBold, 14f, new Color(0x1f, 0x29, 0x37), PAGE_MARGIN, CONTENT_TOP_Y,
                I18n.get("snippets.ai.analysis.diagram.title"));
            PDImageXObject pdImage = LosslessFactory.createFromImage(document, image);
            float maxWidth = contentWidth();
            float maxHeight = CONTENT_TOP_Y - 30f - CONTENT_BOTTOM_Y;
            float scale = Math.min(maxWidth / image.getWidth(), maxHeight / image.getHeight());
            scale = Math.min(scale, 1f);
            float drawWidth = image.getWidth() * scale;
            float drawHeight = image.getHeight() * scale;
            float x = PAGE_MARGIN + (maxWidth - drawWidth) / 2f;
            float y = CONTENT_TOP_Y - 30f - drawHeight;
            stream.drawImage(pdImage, x, y, drawWidth, drawHeight);
        }
    }

    private void drawFooters(PDDocument document, PdfFonts fonts) throws IOException {
        int total = document.getNumberOfPages();
        for (int index = 0; index < total; index++) {
            PDPage page = document.getPage(index);
            try (PDPageContentStream stream = new PDPageContentStream(document, page, AppendMode.APPEND, true, true)) {
                float pageWidth = page.getMediaBox().getWidth();
                drawLine(stream, PAGE_MARGIN, 42f, pageWidth - PAGE_MARGIN, 42f, new Color(0xe5, 0xe7, 0xeb), 0.7f);
                drawText(stream, fonts.sans, 8.4f, new Color(0x9c, 0xa3, 0xaf), PAGE_MARGIN, 28f, WATERMARK_TEXT);
                String label = I18n.get("snippets.ai.analysis.export.pdf.page", index + 1, total);
                float labelWidth = textWidth(fonts.sans, 8.4f, label);
                drawText(stream, fonts.sans, 8.4f, new Color(0x9c, 0xa3, 0xaf),
                    pageWidth - PAGE_MARGIN - labelWidth, 28f, label);
            }
        }
    }

    private Cursor drawParagraph(PDDocument document, Cursor cursor, PDFont font, float fontSize, Color color,
                                 String text, float trailing) throws IOException {
        float leading = fontSize + 3.4f;
        List<String> lines = wrap(text, font, fontSize, contentWidth());
        for (String line : lines) {
            cursor = ensureSpace(document, cursor, leading);
            drawText(cursor.stream, font, fontSize, color, PAGE_MARGIN, cursor.y - fontSize, line);
            cursor = cursor.withY(cursor.y - leading);
        }
        return cursor.withY(cursor.y - trailing);
    }

    private Cursor ensureSpace(PDDocument document, Cursor cursor, float needed) throws IOException {
        if (cursor.y - needed >= CONTENT_BOTTOM_Y) {
            return cursor;
        }
        return newPage(document, cursor);
    }

    private Cursor newPage(PDDocument document, Cursor previous) throws IOException {
        if (previous != null) {
            previous.stream.close();
        }
        PDPage page = new PDPage(PDRectangle.A4);
        document.addPage(page);
        return new Cursor(new PDPageContentStream(document, page), CONTENT_TOP_Y);
    }

    private String buildMetaLine(Context context) {
        List<String> parts = new ArrayList<>();
        if (context.scriptName() != null && !context.scriptName().isBlank()) {
            parts.add(context.scriptName().trim());
        }
        if (context.profileName() != null && !context.profileName().isBlank()) {
            parts.add(I18n.get("snippets.ai.analysis.export.meta.profile") + ": " + context.profileName().trim());
        }
        LocalDateTime when = context.generatedAt() != null ? context.generatedAt() : LocalDateTime.now();
        parts.add(when.format(TIMESTAMP_FORMAT));
        return String.join("  |  ", parts);
    }

    private static Color sectionColor(String category) {
        return switch (category) {
            case "security" -> new Color(0xe5, 0x48, 0x4d);
            case "optimization" -> new Color(0xd9, 0x77, 0x06);
            case "dependencies" -> new Color(0x0d, 0x94, 0x88);
            default -> new Color(0x8b, 0x5c, 0xf6);
        };
    }

    private static Color sectionColorForSeverity(String severity) {
        return switch (severityRank(severity)) {
            case 0, 1 -> new Color(0xc0, 0x39, 0x2b);
            case 2 -> new Color(0xb9, 0x5c, 0x00);
            case 3 -> new Color(0x8a, 0x6d, 0x00);
            default -> new Color(0x1f, 0x29, 0x37);
        };
    }

    // ---- pdfbox primitives ---------------------------------------------------------------------

    private PDType0Font loadFont(PDDocument document, String resourcePath) throws IOException {
        try (InputStream inputStream = SnippetAnalysisExportService.class.getResourceAsStream(resourcePath)) {
            if (inputStream == null) {
                throw new IOException("Missing PDF font resource " + resourcePath);
            }
            return PDType0Font.load(document, inputStream, true);
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

    private void drawLine(PDPageContentStream stream, float x1, float y1, float x2, float y2, Color color, float lineWidth)
        throws IOException {
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
            String line = rawLine.strip();
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

    private static String escapeHtml(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            switch (character) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(character);
            }
        }
        return out.toString();
    }

    private record PdfFonts(PDFont sans, PDFont sansBold, PDFont mono) {
    }

    private static final class Cursor {
        private final PDPageContentStream stream;
        private final float y;

        private Cursor(PDPageContentStream stream, float y) {
            this.stream = stream;
            this.y = y;
        }

        private Cursor withY(float newY) {
            return new Cursor(stream, newY);
        }
    }
}
