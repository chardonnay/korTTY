package de.kortty.core;

import de.kortty.core.SnippetAnalysisDiagramRasterizer.RasterizedDiagram;
import de.kortty.core.SnippetAnalysisExportService.ExportOptions;
import de.kortty.core.SnippetAnalysisReport.Change;
import de.kortty.core.SnippetAnalysisReport.DeltaItem;
import de.kortty.core.SnippetAnalysisReport.Dependency;
import de.kortty.core.SnippetAnalysisReport.Excerpt;
import de.kortty.core.SnippetAnalysisReport.Finding;
import de.kortty.core.SnippetAnalysisReport.FindingStatus;

import javax.imageio.ImageIO;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static de.kortty.core.SnippetAnalysisReportText.r;
import static de.kortty.core.SnippetAnalysisReportText.t;

/**
 * The report as one self-contained HTML page with the same sections as the PDF: a header with
 * meta grid, stat tiles and an inline-SVG chart, a linked table of contents, finding cards,
 * the diff, the diagram (a 2x PNG shown at 1x CSS size, so it stays crisp) and print CSS that
 * keeps cards and hunks together.
 */
final class SnippetAnalysisHtmlWriter {

    private SnippetAnalysisHtmlWriter() {
    }

    private record TocEntry(String anchor, String title, List<TocEntry> children) {
    }

    static String render(SnippetAnalysisReport report, ExportOptions options, RasterizedDiagram diagram) {
        List<TocEntry> toc = new ArrayList<>();
        StringBuilder body = new StringBuilder();

        // ---- header ----
        body.append("<header class=\"hero\"><div class=\"overline\">")
            .append(esc(SnippetAnalysisReportText.kindTitle(report))).append("</div><h1>")
            .append(esc(report.header().scriptName().isBlank() ? SnippetAnalysisReportText.appTitle()
                : report.header().scriptName()))
            .append("</h1><div class=\"subtitle\">").append(esc(SnippetAnalysisReportText.appTitle()));
        if (report.apply() != null) {
            body.append(" <span class=\"outcome\" style=\"color:").append(report.apply().outcome().colorHex())
                .append("\">").append(esc(SnippetAnalysisReportText.outcomeLabel(report.apply().outcome())))
                .append("</span>");
        }
        body.append("</div></header>");

        body.append("<section id=\"overview\"><dl class=\"meta\">");
        for (SnippetAnalysisReportText.Meta meta : SnippetAnalysisReportText.metaRows(report, options)) {
            body.append("<div><dt>").append(esc(meta.label())).append("</dt><dd")
                .append(meta.mono() ? " class=\"mono\"" : "").append('>').append(esc(meta.value()))
                .append("</dd></div>");
        }
        body.append("</dl>");
        if (report.header().stale()) {
            body.append("<div class=\"note warn\">").append(esc(r("meta.stale"))).append("</div>");
        }
        body.append("<div class=\"tiles\">");
        for (SnippetAnalysisReportText.Stat stat : SnippetAnalysisReportText.stats(report, options.locale())) {
            body.append("<div class=\"tile\"><div class=\"num\" style=\"color:").append(stat.colorHex())
                .append("\">").append(esc(stat.value())).append("</div><div class=\"lbl\">")
                .append(esc(stat.label())).append("</div></div>");
        }
        body.append("</div>");
        body.append(chartSvg(report));
        body.append("</section>");
        toc.add(new TocEntry("overview", r("overview"), List.of()));

        // ---- summary ----
        if (!report.summary().isBlank()) {
            toc.add(new TocEntry("summary", r("summary"), List.of()));
            body.append("<section id=\"summary\"><h2>").append(esc(r("summary"))).append("</h2>")
                .append(textBlocks(report.summary())).append("</section>");
        }

        // ---- apply result ----
        if (report.apply() != null) {
            toc.add(new TocEntry("apply", r("apply.title"), List.of()));
            body.append(applySection(report, options));
        }

        // ---- findings ----
        for (String section : SnippetAnalysisReportText.SECTIONS) {
            List<Finding> group = report.findingsOf(section);
            if (group.isEmpty()) {
                continue;
            }
            List<TocEntry> children = new ArrayList<>();
            String sectionAnchor = "sec-" + section;
            body.append("<section id=\"").append(sectionAnchor).append("\"><h2 class=\"sec sec-").append(section)
                .append("\">").append(AnalysisCategoryVisuals.iconSvg(section)).append(' ')
                .append(esc(SnippetAnalysisReportText.sectionTitle(section)))
                .append(" <span class=\"count\">(").append(group.size()).append(")</span></h2>");
            for (Finding finding : group) {
                String anchor = SnippetAnalysisReportText.anchor("f", finding.id());
                children.add(new TocEntry(anchor, finding.id() + " " + finding.title(), List.of()));
                body.append(findingCard(report, finding, anchor, section));
            }
            body.append("</section>");
            toc.add(new TocEntry(sectionAnchor,
                SnippetAnalysisReportText.sectionTitle(section) + " (" + group.size() + ")", children));
        }

        // ---- dependencies ----
        if (!report.dependencies().isEmpty()) {
            toc.add(new TocEntry("sec-dependencies", SnippetAnalysisReportText.sectionTitle("dependencies")
                + " (" + report.dependencies().size() + ")", List.of()));
            body.append("<section id=\"sec-dependencies\"><h2 class=\"sec sec-dependencies\">")
                .append(AnalysisCategoryVisuals.iconSvg("dependencies")).append(' ')
                .append(esc(SnippetAnalysisReportText.sectionTitle("dependencies")))
                .append(" <span class=\"count\">(").append(report.dependencies().size()).append(")</span></h2>");
            for (Dependency dependency : report.dependencies()) {
                body.append(dependencyCard(report, dependency));
            }
            body.append("</section>");
        }

        // ---- diff ----
        if (report.apply() != null) {
            toc.add(new TocEntry("changes", SnippetAnalysisReportText.diffTitle(report), List.of()));
            body.append(diffSection(report));
        }

        // ---- verification ----
        if (SnippetAnalysisReportText.hasVerification(report)) {
            toc.add(new TocEntry("verification", r("verify.title"), List.of()));
            body.append(verificationSection(report));
        }

        // ---- diagram ----
        toc.add(new TocEntry("diagram", t("snippets.ai.analysis.diagram.title"), List.of()));
        body.append(diagramSection(report, diagram));

        // ---- appendix ----
        if (options.includeFullCode()) {
            toc.add(new TocEntry("appendix", SnippetAnalysisReportText.codeTitle(report), List.of()));
            body.append("<section id=\"appendix\"><h2>").append(esc(SnippetAnalysisReportText.codeTitle(report)))
                .append("</h2>");
            SnippetAnalysisReport.CodeSnapshot code = SnippetAnalysisReportText.appendixCode(report);
            if (code != null) {
                body.append(numberedCode(code.content(), 1, -1));
            } else {
                body.append("<p class=\"muted\">").append(esc(r("diff.notStored"))).append("</p>");
            }
            body.append("</section>");
        }

        // ---- footer ----
        ExportBranding branding = options.branding() != null ? options.branding() : ExportBranding.defaults();
        StringBuilder footer = new StringBuilder();
        if (branding.footerEnabled()) {
            footer.append("<footer>").append(esc(branding.footerText()));
            if (branding.footerUsesDefaultText()) {
                footer.append(" · <a href=\"").append(ExportBranding.REPOSITORY_URL).append("\">")
                    .append(esc(ExportBranding.REPOSITORY_URL)).append("</a>");
            }
            footer.append("</footer>");
        }

        StringBuilder nav = new StringBuilder("<nav class=\"toc\"><h2>").append(esc(r("contents"))).append("</h2><ol>");
        for (TocEntry entry : toc) {
            nav.append("<li><a href=\"#").append(entry.anchor()).append("\">").append(esc(entry.title())).append("</a>");
            if (!entry.children().isEmpty()) {
                nav.append("<ol>");
                for (TocEntry child : entry.children()) {
                    nav.append("<li><a href=\"#").append(child.anchor()).append("\">").append(esc(child.title()))
                        .append("</a></li>");
                }
                nav.append("</ol>");
            }
            nav.append("</li>");
        }
        nav.append("</ol></nav>");

        String watermark = branding.watermarkEnabled()
            ? "<div class=\"watermark\" style=\"color:" + ExportBranding.toHex(branding.watermarkColor()) + "\">"
                + esc(branding.watermarkText()) + "</div>"
            : "";
        int headerEnd = body.indexOf("</section>") + "</section>".length();
        return "<!doctype html><html lang=\"" + esc(options.locale().toLanguageTag()) + "\"><head><meta charset=\"UTF-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<meta name=\"generator\" content=\"korTTY\">"
            + "<title>" + esc(SnippetAnalysisReportText.documentTitle(report)) + "</title><style>" + css()
            + "</style></head><body>" + watermark + "<main>" + body.substring(0, headerEnd) + nav
            + body.substring(headerEnd) + "</main>" + footer + "</body></html>\n";
    }

    // ---- sections ----

    private static String applySection(SnippetAnalysisReport report, ExportOptions options) {
        SnippetAnalysisReport.ApplyResult apply = report.apply();
        StringBuilder html = new StringBuilder("<section id=\"apply\"><h2>").append(esc(r("apply.title")))
            .append("</h2><div class=\"banner\" style=\"border-color:").append(apply.outcome().colorHex())
            .append(";color:").append(apply.outcome().colorHex()).append("\">")
            .append(esc(SnippetAnalysisReportText.outcomeLabel(apply.outcome()))).append("</div><table class=\"kv\">");
        for (SnippetAnalysisReportText.KeyValue row : SnippetAnalysisReportText.applyRows(report, options)) {
            html.append("<tr><th>").append(esc(row.key())).append("</th><td>").append(esc(row.value()))
                .append("</td></tr>");
        }
        html.append("</table>");
        if (!apply.aiSummary().isBlank()) {
            html.append("<h3>").append(esc(r("apply.aiSummary"))).append("</h3>").append(textBlocks(apply.aiSummary()));
        }
        html.append("<h3>").append(esc(r("apply.statusTable"))).append("</h3><table class=\"grid\"><thead><tr><th>")
            .append(esc(r("table.id"))).append("</th><th>").append(esc(r("table.title"))).append("</th><th>")
            .append(esc(r("table.severity"))).append("</th><th>").append(esc(r("table.status")))
            .append("</th></tr></thead><tbody>");
        for (String section : SnippetAnalysisReportText.SECTIONS) {
            for (Finding finding : report.findingsOf(section)) {
                html.append("<tr><td class=\"mono\"><a href=\"#").append(SnippetAnalysisReportText.anchor("f", finding.id()))
                    .append("\">").append(esc(finding.id())).append("</a></td><td>").append(esc(finding.title()))
                    .append("</td><td>").append(severityPill(finding.severity())).append("</td><td>")
                    .append(statusBadge(finding.status())).append("</td></tr>");
            }
        }
        for (Dependency dependency : report.dependencies()) {
            html.append("<tr><td class=\"mono\">").append(esc(dependency.id())).append("</td><td>")
                .append(esc(dependency.name())).append("</td><td></td><td>").append(statusBadge(dependency.status()))
                .append("</td></tr>");
        }
        html.append("</tbody></table></section>");
        return html.toString();
    }

    private static String findingCard(SnippetAnalysisReport report, Finding finding, String anchor, String section) {
        StringBuilder html = new StringBuilder("<article class=\"card\" id=\"").append(anchor)
            .append("\" style=\"border-left-color:").append(AnalysisCategoryVisuals.printColorHex(section))
            .append("\"><div class=\"card-head\">").append(severityPill(finding.severity()))
            .append("<span class=\"chip mono\">").append(esc(finding.id())).append("</span>");
        if (report.isPost()) {
            html.append(statusBadge(finding.status()));
        } else if (finding.selected()) {
            html.append("<span class=\"badge sel\">").append(esc(r("finding.selected"))).append("</span>");
        }
        String tag = SnippetAnalysisReportText.categoryTag(finding);
        if (!tag.isEmpty()) {
            html.append("<span class=\"chip\">").append(esc(tag)).append("</span>");
        }
        if (finding.line() != null) {
            html.append("<span class=\"loc mono\">").append(esc(SnippetAnalysisReportText.lineLabel(finding.line())))
                .append("</span>");
        }
        html.append("</div><h3>").append(prose(finding.title())).append("</h3>");
        html.append(textBlocks(finding.detail()));
        if (!finding.recommendation().isBlank()) {
            html.append("<div class=\"rec\"><b>").append(esc(SnippetAnalysisReportText.recommendationLabel()))
                .append("</b>").append(textBlocks(finding.recommendation())).append("</div>");
        }
        Excerpt excerpt = finding.excerpt();
        if (excerpt != null) {
            html.append("<div class=\"cap\">").append(esc(r("excerpt", excerpt.targetLine()))).append("</div>")
                .append(numberedCode(String.join("\n", excerpt.lines()), excerpt.firstLine(), excerpt.targetLine()));
        }
        html.append(changesList(finding.changes()));
        return html.append("</article>").toString();
    }

    private static String dependencyCard(SnippetAnalysisReport report, Dependency dependency) {
        StringBuilder html = new StringBuilder("<article class=\"card\" id=\"")
            .append(SnippetAnalysisReportText.anchor("d", dependency.id())).append("\" style=\"border-left-color:")
            .append(AnalysisCategoryVisuals.printColorHex("dependencies")).append("\"><div class=\"card-head\">");
        if (!dependency.kind().isBlank()) {
            html.append("<span class=\"pill kind\">").append(esc(dependency.kind())).append("</span>");
        }
        html.append("<span class=\"chip mono\">").append(esc(dependency.id())).append("</span>");
        if (report.isPost()) {
            html.append(statusBadge(dependency.status()));
        } else if (dependency.selected()) {
            html.append("<span class=\"badge sel\">").append(esc(r("finding.selected"))).append("</span>");
        }
        html.append("</div><h3>").append(esc(dependency.name())).append("</h3>");
        if (!dependency.purpose().isBlank()) {
            html.append("<p><b>").append(esc(SnippetAnalysisReportText.purposeLabel())).append(":</b> ")
                .append(esc(dependency.purpose())).append("</p>");
        }
        if (!dependency.suggestion().isBlank()) {
            html.append("<div class=\"rec\"><b>").append(esc(SnippetAnalysisReportText.suggestionLabel()))
                .append("</b>").append(textBlocks(dependency.suggestion())).append("</div>");
        }
        html.append(changesList(dependency.changes()));
        return html.append("</article>").toString();
    }

    private static String changesList(List<Change> changes) {
        if (changes.isEmpty()) {
            return "";
        }
        StringBuilder html = new StringBuilder("<div class=\"why\"><b>").append(esc(r("changes"))).append("</b><ul>");
        for (Change change : changes) {
            html.append("<li>").append(esc(change.reason().isBlank() ? "—" : change.reason()));
            if (!change.anchor().isBlank()) {
                html.append(" <code>").append(esc(firstLine(change.anchor()))).append("</code>");
            }
            html.append("</li>");
        }
        return html.append("</ul></div>").toString();
    }

    private static String diffSection(SnippetAnalysisReport report) {
        SnippetAnalysisReport.ApplyResult apply = report.apply();
        StringBuilder html = new StringBuilder("<section id=\"changes\"><h2>")
            .append(esc(SnippetAnalysisReportText.diffTitle(report))).append("</h2>");
        TextLineDiff.Result diff = apply.diff();
        if (diff == null) {
            return html.append("<p class=\"muted\">").append(esc(r("diff.notStored"))).append("</p></section>").toString();
        }
        if (diff.tooLarge()) {
            return html.append("<p class=\"muted\">").append(esc(r("diff.tooLarge", diff.oldLineCount(),
                diff.newLineCount()))).append("</p></section>").toString();
        }
        if (diff.unchanged()) {
            return html.append("<p class=\"muted\">").append(esc(r("diff.unchanged"))).append("</p></section>").toString();
        }
        html.append("<p class=\"muted\">").append(esc(r("diff.stats", diff.added(), diff.removed(),
            diff.hunks().size()))).append("</p>");
        for (int index = 0; index < diff.hunks().size(); index++) {
            TextLineDiff.Hunk hunk = diff.hunks().get(index);
            List<String> ids = apply.findingIdsOfHunk(index);
            html.append("<div class=\"hunk\"><div class=\"hunk-head\"><span class=\"mono\">").append(esc(hunk.header()))
                .append("</span>");
            for (String id : ids) {
                html.append("<a class=\"chip mono\" href=\"#").append(SnippetAnalysisReportText.anchor("f", id))
                    .append("\">").append(esc(id)).append("</a>");
            }
            html.append("</div>");
            StringBuilder reasons = new StringBuilder();
            for (Change change : apply.changes()) {
                if (ids.stream().anyMatch(id -> id.equalsIgnoreCase(change.findingId())) && !change.reason().isBlank()) {
                    reasons.append("<li><b class=\"mono\">").append(esc(change.findingId())).append("</b> ")
                        .append(esc(change.reason())).append("</li>");
                }
            }
            if (!reasons.isEmpty()) {
                html.append("<ul class=\"reasons\">").append(reasons).append("</ul>");
            }
            html.append("<pre class=\"diff\">");
            for (TextLineDiff.Line line : hunk.lines()) {
                String css = switch (line.kind()) {
                    case ADDED -> "add";
                    case REMOVED -> "del";
                    case CONTEXT -> "ctx";
                };
                char sign = switch (line.kind()) {
                    case ADDED -> '+';
                    case REMOVED -> '-';
                    case CONTEXT -> ' ';
                };
                html.append("<span class=\"").append(css).append("\"><i>")
                    .append(line.oldNumber() > 0 ? line.oldNumber() : "").append("</i><i>")
                    .append(line.newNumber() > 0 ? line.newNumber() : "").append("</i>").append(sign)
                    .append(esc(line.text())).append("</span>\n");
            }
            html.append("</pre></div>");
        }
        return html.append("</section>").toString();
    }

    private static String verificationSection(SnippetAnalysisReport report) {
        SnippetAnalysisReport.Verification verification = report.verification();
        StringBuilder html = new StringBuilder("<section id=\"verification\"><h2>").append(esc(r("verify.title")))
            .append("</h2><p class=\"muted\">").append(esc(r("verify.heuristic"))).append("</p>");
        html.append(deltaGroup(r("verify.resolved"), "#15803D", verification.resolved()));
        html.append(deltaGroup(r("verify.new"), "#B91C1C", verification.introduced()));
        html.append(deltaGroup(r("verify.persisting"), "#B45309", verification.persisting()));
        return html.append("</section>").toString();
    }

    private static String deltaGroup(String title, String color, List<DeltaItem> items) {
        StringBuilder html = new StringBuilder("<h3 style=\"color:").append(color).append("\">").append(esc(title))
            .append(" <span class=\"count\">(").append(items.size()).append(")</span></h3><ul class=\"delta\">");
        if (items.isEmpty()) {
            html.append("<li class=\"muted\">").append(esc(r("none"))).append("</li>");
        }
        for (DeltaItem item : items) {
            html.append("<li>").append(severityPill(item.severity())).append("<span class=\"chip mono\">")
                .append(esc(item.id())).append("</span>").append(esc(item.title()));
            if (!item.previousId().isBlank() && !item.previousId().equals(item.id())) {
                html.append(" <span class=\"muted\">(").append(esc(r("verify.was", item.previousId()))).append(")</span>");
            }
            html.append("</li>");
        }
        return html.append("</ul>").toString();
    }

    private static String diagramSection(SnippetAnalysisReport report, RasterizedDiagram diagram) {
        String title = t("snippets.ai.analysis.diagram.title");
        StringBuilder html = new StringBuilder("<section id=\"diagram\" class=\"diagram\"><h2>").append(esc(title));
        if (report.diagram() == null) {
            return html.append("</h2><p class=\"muted\">").append(esc(t("snippets.ai.analysis.diagram.unavailable")))
                .append("</p></section>").toString();
        }
        html.append(" <span class=\"count\">").append(esc(SnippetAnalysisReportText.diagramTypeLabel(report.diagram().type())))
            .append("</span></h2>");
        if (report.diagram().fallback() || !report.diagram().fallbackNotice().isBlank()) {
            html.append("<p class=\"muted\">").append(esc(report.diagram().fallbackNotice().isBlank()
                ? t("snippets.ai.analysis.diagram.fallback.generic") : report.diagram().fallbackNotice())).append("</p>");
        }
        String png = diagram != null && diagram.rendered() ? pngBase64(diagram) : null;
        if (png != null) {
            long width = Math.max(1, Math.round(diagram.unitWidth()));
            long height = Math.max(1, Math.round(diagram.unitHeight()));
            html.append("<figure><img alt=\"").append(esc(title)).append("\" width=\"").append(width)
                .append("\" height=\"").append(height).append("\" src=\"data:image/png;base64,").append(png)
                .append("\"></figure>");
        } else {
            String message = diagram != null && diagram.outcome().failed() ? diagram.outcome().message() : "";
            html.append("<div class=\"note warn\">").append(esc(r("diagram.unavailable", message)))
                .append("</div><details><summary>Mermaid</summary><pre class=\"code\">")
                .append(esc(report.diagram().mermaidSource())).append("</pre></details>");
        }
        return html.append("</section>").toString();
    }

    // ---- building blocks ----

    private static String textBlocks(String text) {
        StringBuilder html = new StringBuilder();
        for (ReportTextBlocks.Block block : ReportTextBlocks.split(text)) {
            switch (block.type()) {
                case PARAGRAPH -> html.append("<p>").append(prose(block.text())).append("</p>");
                case CODE -> html.append("<pre class=\"code\"><code>").append(esc(block.text())).append("</code></pre>");
                case TABLE -> {
                    html.append("<table class=\"grid\">");
                    for (int row = 0; row < block.rows().size(); row++) {
                        html.append("<tr>");
                        for (String value : block.rows().get(row)) {
                            html.append(row == 0 ? "<th>" : "<td>").append(prose(value)).append(row == 0 ? "</th>" : "</td>");
                        }
                        html.append("</tr>");
                    }
                    html.append("</table>");
                }
            }
        }
        return html.toString();
    }

    /** A code panel with a line-number gutter; {@code target} (or -1) is highlighted. */
    private static String numberedCode(String content, int firstLine, int target) {
        StringBuilder html = new StringBuilder("<pre class=\"code numbered\">");
        String[] lines = (content != null ? content : "").replace("\r\n", "\n").split("\n", -1);
        int count = lines.length > 1 && lines[lines.length - 1].isEmpty() ? lines.length - 1 : lines.length;
        for (int index = 0; index < count; index++) {
            int number = firstLine + index;
            html.append("<span").append(number == target ? " class=\"hit\"" : "").append("><i>").append(number)
                .append("</i>").append(esc(lines[index])).append("</span>\n");
        }
        return html.append("</pre>").toString();
    }

    private static String severityPill(AnalysisSeverity severity) {
        return "<span class=\"pill " + severity.cssClass() + "\">"
            + esc(SnippetAnalysisReportText.severityLabel(severity)) + "</span>";
    }

    private static String statusBadge(FindingStatus status) {
        if (status == null) {
            return "";
        }
        return "<span class=\"badge st-" + status.id() + "\" style=\"color:" + status.colorHex() + ";border-color:"
            + status.colorHex() + "\">" + esc(SnippetAnalysisReportText.statusLabel(status)) + "</span>";
    }

    /** The severity-by-category chart as inline SVG, drawn from the same data as the PDF's. */
    private static String chartSvg(SnippetAnalysisReport report) {
        List<SnippetAnalysisReportText.Bar> bars = SnippetAnalysisReportText.severityBars(report);
        int max = Math.max(1, bars.stream().mapToInt(SnippetAnalysisReportText.Bar::total).max().orElse(1));
        int labelWidth = 150;
        int barWidth = 520;
        int rowHeight = 26;
        int height = bars.size() * rowHeight + 34;
        StringBuilder svg = new StringBuilder("<figure class=\"chart\"><figcaption>").append(esc(r("chart.title")))
            .append("</figcaption><svg role=\"img\" viewBox=\"0 0 ").append(labelWidth + barWidth + 40).append(' ')
            .append(height).append("\" xmlns=\"http://www.w3.org/2000/svg\"><title>").append(esc(r("chart.title")))
            .append("</title>");
        int y = 4;
        for (SnippetAnalysisReportText.Bar bar : bars) {
            svg.append("<text x=\"0\" y=\"").append(y + 15).append("\" font-size=\"13\" fill=\"#374151\">")
                .append(esc(bar.label())).append("</text>");
            double x = labelWidth;
            if ("dependencies".equals(bar.category())) {
                double w = barWidth * bar.total() / (double) max;
                if (w > 0) {
                    svg.append(rect(x, y + 3, w, 16, AnalysisCategoryVisuals.printColorHex("dependencies")));
                }
                x += w;
            } else {
                for (AnalysisSeverity severity : AnalysisSeverity.values()) {
                    int count = bar.counts().getOrDefault(severity, 0);
                    if (count == 0) {
                        continue;
                    }
                    double w = barWidth * count / (double) max;
                    svg.append(rect(x, y + 3, w, 16, severity.printColorHex()));
                    x += w;
                }
            }
            svg.append("<text x=\"").append(fmt(x + 6)).append("\" y=\"").append(y + 15)
                .append("\" font-size=\"12\" font-weight=\"700\" fill=\"#1F2937\">").append(bar.total()).append("</text>");
            y += rowHeight;
        }
        double x = labelWidth;
        for (AnalysisSeverity severity : AnalysisSeverity.values()) {
            svg.append(rect(x, y + 6, 10, 10, severity.printColorHex())).append("<text x=\"").append(fmt(x + 14))
                .append("\" y=\"").append(y + 15).append("\" font-size=\"11\" fill=\"#5E6E82\">")
                .append(esc(SnippetAnalysisReportText.severityLabel(severity))).append("</text>");
            x += 90;
        }
        svg.append("</svg></figure>");
        if (report.isPost()) {
            Map<FindingStatus, Integer> counts = SnippetAnalysisReportText.resultCounts(report);
            int total = counts.values().stream().mapToInt(Integer::intValue).sum();
            if (total > 0) {
                svg.append("<figure class=\"chart\"><figcaption>").append(esc(r("chart.result")))
                    .append("</figcaption><div class=\"stack\">");
                for (Map.Entry<FindingStatus, Integer> entry : counts.entrySet()) {
                    svg.append("<span style=\"flex:").append(entry.getValue()).append(";background:")
                        .append(entry.getKey().colorHex()).append("\" title=\"")
                        .append(esc(SnippetAnalysisReportText.statusLabel(entry.getKey()))).append("\">")
                        .append(entry.getValue()).append("</span>");
                }
                svg.append("</div><div class=\"legend\">");
                for (Map.Entry<FindingStatus, Integer> entry : counts.entrySet()) {
                    svg.append("<span><i style=\"background:").append(entry.getKey().colorHex()).append("\"></i>")
                        .append(esc(SnippetAnalysisReportText.statusLabel(entry.getKey()))).append(" (")
                        .append(entry.getValue()).append(")</span>");
                }
                svg.append("</div></figure>");
            }
        }
        return svg.toString();
    }

    private static String rect(double x, double y, double width, double height, String color) {
        return "<rect x=\"" + fmt(x) + "\" y=\"" + fmt(y) + "\" width=\"" + fmt(width) + "\" height=\"" + fmt(height)
            + "\" rx=\"3\" fill=\"" + color + "\"/>";
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.1f", value);
    }

    private static String pngBase64(RasterizedDiagram diagram) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(diagram.image(), "png", out);
            return Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (IOException e) {
            return null;
        }
    }

    private static String firstLine(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }

    private static final java.util.regex.Pattern INLINE_CODE = java.util.regex.Pattern.compile("`([^`\\n]+)`");

    /** Escaped prose with {@code `inline code`} as {@code <code>}. */
    static String prose(String value) {
        return INLINE_CODE.matcher(esc(value)).replaceAll(match -> "<code>"
            + java.util.regex.Matcher.quoteReplacement(match.group(1)) + "</code>");
    }

    static String esc(String value) {
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

    private static String css() {
        StringBuilder css = new StringBuilder();
        css.append("*{box-sizing:border-box}")
            .append("body{margin:0;background:#fff;color:#1f2937;font-family:-apple-system,'Segoe UI',Roboto,")
            .append("'Noto Sans',Helvetica,Arial,sans-serif;line-height:1.55}")
            .append("main{max-width:920px;margin:0 auto;padding:32px 28px}")
            .append(".mono,code,pre{font-family:ui-monospace,'SF Mono',Menlo,Consolas,'Noto Sans Mono',monospace}")
            .append(".hero{background:linear-gradient(135deg,#0066cc,#0a4f9e);color:#fff;border-radius:14px;")
            .append("padding:22px 26px;margin-bottom:18px}")
            .append(".overline{font-size:.78em;font-weight:700;letter-spacing:.08em;text-transform:uppercase;color:#d9eaff}")
            .append(".hero h1{margin:4px 0 2px;font-size:1.9em;word-break:break-word}")
            .append(".subtitle{color:#d9eaff}")
            .append(".outcome{background:#fff;border-radius:999px;padding:1px 10px;margin-left:10px;font-weight:700;")
            .append("font-size:.85em}")
            .append(".meta{display:grid;grid-template-columns:repeat(auto-fill,minmax(200px,1fr));gap:10px 18px;")
            .append("background:#f8fafc;border:1px solid #e5e7eb;border-radius:10px;padding:14px 16px;margin:0}")
            .append(".meta dt{font-size:.75em;color:#5e6e82}.meta dd{margin:0;font-size:.92em;word-break:break-word}")
            .append(".tiles{display:grid;grid-template-columns:repeat(4,1fr);gap:10px;margin:14px 0}")
            .append(".tile{border:1px solid #e5e7eb;border-radius:10px;padding:10px 12px;background:#fcfcfd}")
            .append(".tile .num{font-size:1.6em;font-weight:700;line-height:1.2}.tile .lbl{font-size:.8em;color:#5e6e82}")
            .append(".chart{margin:14px 0}.chart figcaption{font-weight:700;margin-bottom:6px}")
            .append(".chart svg{width:100%;max-width:710px;height:auto}")
            .append(".stack{display:flex;height:20px;border-radius:4px;overflow:hidden;max-width:710px}")
            .append(".stack span{color:#fff;font-size:.75em;font-weight:700;text-align:center;line-height:20px}")
            .append(".legend{display:flex;flex-wrap:wrap;gap:14px;font-size:.8em;color:#5e6e82;margin-top:6px}")
            .append(".legend i{display:inline-block;width:10px;height:10px;border-radius:2px;margin-right:5px}")
            .append(".toc{border:1px solid #e5e7eb;border-radius:10px;padding:10px 18px;margin:18px 0}")
            .append(".toc h2{font-size:1em;margin:4px 0}.toc ol{margin:4px 0;padding-left:20px}")
            .append(".toc ol ol{font-size:.9em;color:#5e6e82}.toc a{color:#1d4ed8;text-decoration:none}")
            .append("h2{font-size:1.3em;margin:30px 0 12px;padding-bottom:6px;border-bottom:1px solid #d4deea}")
            .append("h3{font-size:1.02em;margin:8px 0 4px}")
            .append("h2.sec-security{color:").append(AnalysisCategoryVisuals.printColorHex("security"))
            .append("}h2.sec-optimization{color:").append(AnalysisCategoryVisuals.printColorHex("optimization"))
            .append("}h2.sec-design{color:").append(AnalysisCategoryVisuals.printColorHex("design"))
            .append("}h2.sec-dependencies{color:").append(AnalysisCategoryVisuals.printColorHex("dependencies")).append('}')
            .append(".sec-ic{width:.95em;height:.95em;fill:currentColor;vertical-align:-.13em;margin-right:4px}")
            .append(".count{color:#9ca3af;font-weight:400;font-size:.8em}.muted{color:#5e6e82}")
            .append(".card{border:1px solid #e5e7eb;border-left:4px solid #8b5cf6;border-radius:10px;padding:12px 16px;")
            .append("margin-bottom:12px;background:#fcfcfd}")
            .append(".card-head{display:flex;flex-wrap:wrap;align-items:center;gap:8px}")
            .append(".card p{margin:6px 0;white-space:pre-wrap}")
            .append("p code,h3 code,td code,li code{background:#eef2f7;color:#334155;border-radius:4px;")
            .append("padding:0 4px;font-size:.88em}")
            .append(".pill{font-size:.7em;font-weight:700;letter-spacing:.04em;padding:2px 9px;border-radius:999px;")
            .append("text-transform:uppercase;white-space:nowrap;color:#fff;background:#7f8c8d}");
        for (AnalysisSeverity severity : AnalysisSeverity.values()) {
            css.append(".pill.").append(severity.cssClass()).append("{background:").append(severity.printColorHex())
                .append('}');
        }
        css.append(".pill.kind{background:#64748b}")
            .append(".chip{font-size:.78em;background:#eef2f7;color:#334155;border-radius:6px;padding:1px 7px;")
            .append("margin-right:4px;text-decoration:none}")
            .append(".badge{font-size:.75em;font-weight:700;border:1.5px solid;border-radius:999px;padding:0 9px;")
            .append("background:#fff}.badge.sel{color:#1d4ed8;border-color:#1d4ed8}")
            .append(".loc{margin-left:auto;color:#6b7280;font-size:.82em}")
            .append(".rec{margin:10px 0 0;padding:8px 12px;border-left:3px solid #3b82f6;background:#eff6ff;")
            .append("border-radius:0 6px 6px 0}.rec p{margin:4px 0}")
            .append(".cap{font-size:.78em;color:#5e6e82;margin:10px 0 2px}")
            .append("pre.code{background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px;padding:8px 10px;")
            .append("overflow-x:auto;font-size:.82em;line-height:1.5;margin:6px 0;white-space:pre}")
            .append("pre.numbered span{display:block}pre.numbered i,pre.diff i{display:inline-block;min-width:3.2em;")
            .append("color:#94a3b8;font-style:normal;text-align:right;padding-right:10px;user-select:none}")
            .append("pre.numbered .hit{background:#fef3c7}")
            .append(".why{margin-top:8px;font-size:.92em}.why ul{margin:4px 0;padding-left:20px}")
            .append(".banner{border:2px solid;border-radius:10px;padding:8px 14px;font-weight:700;background:#fff;")
            .append("margin-bottom:10px}")
            .append("table{border-collapse:collapse;margin:8px 0;font-size:.92em}")
            .append(".kv th{text-align:left;color:#5e6e82;font-weight:400;padding:3px 18px 3px 0;vertical-align:top}")
            .append(".kv td{padding:3px 0}")
            .append(".grid{width:100%}.grid th,.grid td{border:1px solid #e5e7eb;padding:5px 8px;text-align:left;")
            .append("vertical-align:top}.grid thead th,.grid tr:first-child th{background:#f1f5f9}")
            .append(".hunk{border:1px solid #e2e8f0;border-radius:8px;margin:12px 0;overflow:hidden}")
            .append(".hunk-head{background:#eef2f7;padding:5px 10px;font-size:.85em;display:flex;gap:8px;align-items:center}")
            .append(".reasons{margin:6px 0;padding-left:28px;font-size:.88em}")
            .append("pre.diff{margin:0;font-size:.8em;line-height:1.45;overflow-x:auto}")
            .append("pre.diff span{display:block;padding:0 8px;white-space:pre}")
            .append("pre.diff .add{background:#e6ffec;color:#1a7f37}pre.diff .del{background:#ffebe9;color:#cf222e}")
            .append("pre.diff .ctx{color:#57606a}")
            .append(".delta{list-style:none;padding:0}.delta li{margin:4px 0;display:flex;gap:8px;align-items:center}")
            .append(".note{border-radius:8px;padding:9px 12px;margin:10px 0}")
            .append(".note.warn{background:#fffbeb;border:1px solid #f59e0b;color:#92400e}")
            .append(".diagram figure{margin:10px 0;text-align:center}")
            .append(".diagram img{max-width:100%;height:auto;border:1px solid #e5e7eb;border-radius:8px}")
            .append("footer{max-width:920px;margin:0 auto;padding:12px 28px 32px;color:#6b7280;font-size:.8em;")
            .append("border-top:1px solid #e5e7eb}footer a{color:#6b7280}")
            .append(".watermark{position:fixed;top:45%;left:0;right:0;text-align:center;font-size:44px;font-weight:700;")
            .append("opacity:.12;transform:rotate(-30deg);pointer-events:none;z-index:10}")
            .append("@media (max-width:640px){.tiles{grid-template-columns:repeat(2,1fr)}}")
            .append("@media print{main{max-width:none;padding:0}.card,.hunk,.tile,figure{break-inside:avoid}")
            .append("h2,h3{break-after:avoid}.toc{break-after:page}.hero{-webkit-print-color-adjust:exact;")
            .append("print-color-adjust:exact}pre.diff span,.pill,.stack span{-webkit-print-color-adjust:exact;")
            .append("print-color-adjust:exact}}");
        return css.toString();
    }
}
