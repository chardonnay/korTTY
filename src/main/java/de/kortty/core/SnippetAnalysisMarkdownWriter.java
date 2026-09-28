package de.kortty.core;

import de.kortty.core.SnippetAnalysisDiagramRasterizer.RasterizedDiagram;
import de.kortty.core.SnippetAnalysisExportService.ExportOptions;
import de.kortty.core.SnippetAnalysisReport.Change;
import de.kortty.core.SnippetAnalysisReport.DeltaItem;
import de.kortty.core.SnippetAnalysisReport.Dependency;
import de.kortty.core.SnippetAnalysisReport.Excerpt;
import de.kortty.core.SnippetAnalysisReport.Finding;

import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static de.kortty.core.SnippetAnalysisReportText.r;
import static de.kortty.core.SnippetAnalysisReportText.t;

/**
 * The report as Markdown. Every AI or user text is escaped, so a title such as
 * {@code Use `set -e` *always*} or a script name like {@code my_script_v2.sh} stays literal;
 * code is fenced with a fence longer than any backtick run inside it. The diagram is always
 * written as a {@code mermaid} block — GitHub and GitLab render it even when the PNG could not
 * be drawn — plus a link to the sibling PNG when it was.
 */
final class SnippetAnalysisMarkdownWriter {

    private static final Pattern BACKTICK_RUN = Pattern.compile("`+");
    private static final Pattern ORDERED_LIST_START = Pattern.compile("^(\\d+)([.)])");
    private static final String INLINE_SPECIALS = "\\`*_[]<>|~#";

    private SnippetAnalysisMarkdownWriter() {
    }

    /** @param pngFileName the sibling diagram image, or {@code null} when none was written */
    static String render(SnippetAnalysisReport report, ExportOptions options, RasterizedDiagram diagram,
                         String pngFileName) {
        StringBuilder md = new StringBuilder();
        md.append("# ").append(inline(SnippetAnalysisReportText.documentTitle(report))).append("\n\n");
        md.append("**").append(inline(SnippetAnalysisReportText.kindTitle(report))).append("**");
        if (report.apply() != null) {
            md.append(" · ").append(inline(SnippetAnalysisReportText.outcomeLabel(report.apply().outcome())));
        }
        md.append("\n\n");

        md.append("| | |\n|---|---|\n");
        md.append("| ").append(cell(r("meta.script"))).append(" | ")
            .append(cell(report.header().scriptName().isBlank() ? "—" : report.header().scriptName())).append(" |\n");
        for (SnippetAnalysisReportText.Meta meta : SnippetAnalysisReportText.metaRows(report, options)) {
            String value = meta.mono() && !"—".equals(meta.value()) ? "`" + meta.value() + "`" : cell(meta.value());
            md.append("| ").append(cell(meta.label())).append(" | ").append(value).append(" |\n");
        }
        md.append('\n');
        if (report.header().stale()) {
            md.append("> **⚠** ").append(inline(r("meta.stale"))).append("\n\n");
        }
        List<SnippetAnalysisReportText.Stat> stats = SnippetAnalysisReportText.stats(report, options.locale());
        for (int index = 0; index < stats.size(); index++) {
            SnippetAnalysisReportText.Stat stat = stats.get(index);
            md.append(index > 0 ? " · " : "").append("**").append(inline(stat.label())).append(":** ")
                .append(stat.value());
        }
        md.append("\n\n");

        if (!report.summary().isBlank()) {
            md.append("## ").append(inline(r("summary"))).append("\n\n");
            appendText(md, report.summary());
        }

        if (report.apply() != null) {
            appendApplyResult(md, report, options);
        }

        for (String section : SnippetAnalysisReportText.SECTIONS) {
            List<Finding> group = report.findingsOf(section);
            if (group.isEmpty()) {
                continue;
            }
            md.append("## ").append(inline(SnippetAnalysisReportText.sectionTitle(section)))
                .append(" (").append(group.size()).append(")\n\n");
            for (Finding finding : group) {
                appendFinding(md, report, finding);
            }
        }

        if (!report.dependencies().isEmpty()) {
            md.append("## ").append(inline(SnippetAnalysisReportText.sectionTitle("dependencies")))
                .append(" (").append(report.dependencies().size()).append(")\n\n");
            for (Dependency dependency : report.dependencies()) {
                appendDependency(md, report, dependency);
            }
        }

        if (report.apply() != null) {
            appendDiff(md, report);
        }
        if (SnippetAnalysisReportText.hasVerification(report)) {
            appendVerification(md, report);
        }
        appendDiagram(md, report, diagram, pngFileName);

        if (options.includeFullCode()) {
            SnippetAnalysisReport.CodeSnapshot code = SnippetAnalysisReportText.appendixCode(report);
            md.append("## ").append(inline(SnippetAnalysisReportText.codeTitle(report))).append("\n\n");
            if (code != null) {
                md.append(fence(code.content(), fenceLanguage(code.language()))).append('\n');
            } else {
                md.append(inline(r("diff.notStored"))).append("\n\n");
            }
        }

        ExportBranding branding = options.branding() != null ? options.branding() : ExportBranding.defaults();
        if (branding.footerEnabled()) {
            md.append("---\n\n");
            if (branding.footerUsesDefaultText()) {
                md.append('_').append(inline(branding.footerText())).append("_ · <")
                    .append(ExportBranding.REPOSITORY_URL).append(">\n");
            } else {
                md.append('_').append(inline(branding.footerText())).append("_\n");
            }
        }
        return md.toString();
    }

    private static void appendApplyResult(StringBuilder md, SnippetAnalysisReport report, ExportOptions options) {
        SnippetAnalysisReport.ApplyResult apply = report.apply();
        md.append("## ").append(inline(r("apply.title"))).append("\n\n");
        md.append("**").append(inline(SnippetAnalysisReportText.outcomeLabel(apply.outcome()))).append("**\n\n");
        md.append("| | |\n|---|---|\n");
        for (SnippetAnalysisReportText.KeyValue row : SnippetAnalysisReportText.applyRows(report, options)) {
            md.append("| ").append(cell(row.key())).append(" | ").append(cell(row.value())).append(" |\n");
        }
        md.append('\n');
        if (!apply.aiSummary().isBlank()) {
            md.append("### ").append(inline(r("apply.aiSummary"))).append("\n\n");
            appendText(md, apply.aiSummary());
        }
        md.append("### ").append(inline(r("apply.statusTable"))).append("\n\n");
        md.append("| ").append(cell(r("table.id"))).append(" | ").append(cell(r("table.severity")))
            .append(" | ").append(cell(r("table.category"))).append(" | ").append(cell(r("table.title")))
            .append(" | ").append(cell(t("common.line"))).append(" | ").append(cell(r("table.selected")))
            .append(" | ").append(cell(r("table.status"))).append(" |\n");
        md.append("|---|---|---|---|---:|:---:|---|\n");
        String yes = r("table.yes");
        for (String section : SnippetAnalysisReportText.SECTIONS) {
            for (Finding finding : report.findingsOf(section)) {
                md.append("| ").append(cell(finding.id())).append(" | ")
                    .append(cell(SnippetAnalysisReportText.severityLabel(finding.severity()))).append(" | ")
                    .append(cell(SnippetAnalysisReportText.sectionTitle(section))).append(" | ")
                    .append(cell(finding.title())).append(" | ")
                    .append(finding.line() != null ? finding.line() : "").append(" | ")
                    .append(finding.selected() ? cell(yes) : "").append(" | ")
                    .append(cell(SnippetAnalysisReportText.statusLabel(finding.status()))).append(" |\n");
            }
        }
        for (Dependency dependency : report.dependencies()) {
            md.append("| ").append(cell(dependency.id())).append(" | | ")
                .append(cell(SnippetAnalysisReportText.sectionTitle("dependencies"))).append(" | ")
                .append(cell(dependency.name())).append(" | | ")
                .append(dependency.selected() ? cell(yes) : "").append(" | ")
                .append(cell(SnippetAnalysisReportText.statusLabel(dependency.status()))).append(" |\n");
        }
        md.append('\n');
    }

    private static void appendFinding(StringBuilder md, SnippetAnalysisReport report, Finding finding) {
        md.append("### ").append(inline(finding.id())).append(" · ").append(inline(finding.title()));
        if (finding.line() != null) {
            md.append(" (").append(inline(SnippetAnalysisReportText.lineLabel(finding.line()))).append(')');
        }
        md.append("\n\n");
        md.append("**").append(inline(SnippetAnalysisReportText.severityLabel(finding.severity()))).append("**");
        if (report.isPost()) {
            md.append(" · **").append(inline(SnippetAnalysisReportText.statusLabel(finding.status()))).append("**");
        } else if (finding.selected()) {
            md.append(" · ").append(inline(r("finding.selected")));
        }
        String tag = SnippetAnalysisReportText.categoryTag(finding);
        if (!tag.isEmpty()) {
            md.append(" · _").append(inline(tag)).append('_');
        }
        md.append("\n\n");
        appendText(md, finding.detail());
        if (!finding.recommendation().isBlank()) {
            appendQuoted(md, SnippetAnalysisReportText.recommendationLabel(), finding.recommendation());
        }
        appendExcerpt(md, report, finding.excerpt());
        appendChanges(md, finding.changes());
    }

    private static void appendDependency(StringBuilder md, SnippetAnalysisReport report, Dependency dependency) {
        md.append("### ").append(inline(dependency.id())).append(" · ").append(inline(dependency.name()))
            .append("\n\n");
        StringBuilder line = new StringBuilder();
        if (!dependency.kind().isBlank()) {
            line.append('`').append(dependency.kind().replace("`", "'")).append('`');
        }
        if (report.isPost()) {
            line.append(line.isEmpty() ? "" : " · ").append("**")
                .append(inline(SnippetAnalysisReportText.statusLabel(dependency.status()))).append("**");
        } else if (dependency.selected()) {
            line.append(line.isEmpty() ? "" : " · ").append(inline(r("finding.selected")));
        }
        if (!line.isEmpty()) {
            md.append(line).append("\n\n");
        }
        if (!dependency.purpose().isBlank()) {
            md.append("**").append(inline(SnippetAnalysisReportText.purposeLabel())).append(":** ")
                .append(joinLines(dependency.purpose())).append("\n\n");
        }
        if (!dependency.suggestion().isBlank()) {
            appendQuoted(md, SnippetAnalysisReportText.suggestionLabel(), dependency.suggestion());
        }
        appendChanges(md, dependency.changes());
    }

    private static void appendExcerpt(StringBuilder md, SnippetAnalysisReport report, Excerpt excerpt) {
        if (excerpt == null || excerpt.lines().isEmpty()) {
            return;
        }
        md.append('_').append(inline(r("excerpt", excerpt.targetLine()))).append("_\n\n");
        int width = Integer.toString(excerpt.lastLine()).length();
        StringBuilder code = new StringBuilder();
        for (int index = 0; index < excerpt.lines().size(); index++) {
            int number = excerpt.firstLine() + index;
            code.append(number == excerpt.targetLine() ? "> " : "  ")
                .append(String.format(Locale.ROOT, "%" + width + "d", number)).append(" | ")
                .append(excerpt.lines().get(index).replace("\t", "    ")).append('\n');
        }
        md.append(fence(code.toString(), "")).append('\n');
    }

    private static void appendChanges(StringBuilder md, List<Change> changes) {
        if (changes.isEmpty()) {
            return;
        }
        md.append("**").append(inline(r("changes"))).append("**\n\n");
        for (Change change : changes) {
            md.append("- ").append(joinLines(change.reason().isBlank() ? "—" : change.reason()));
            if (!change.anchor().isBlank()) {
                md.append(" — ").append(codeSpan(firstLine(change.anchor())));
            }
            md.append('\n');
        }
        md.append('\n');
    }

    private static void appendDiff(StringBuilder md, SnippetAnalysisReport report) {
        SnippetAnalysisReport.ApplyResult apply = report.apply();
        md.append("## ").append(inline(SnippetAnalysisReportText.diffTitle(report))).append("\n\n");
        TextLineDiff.Result diff = apply.diff();
        if (diff == null) {
            md.append(inline(r("diff.notStored"))).append("\n\n");
            return;
        }
        if (diff.tooLarge()) {
            md.append(inline(r("diff.tooLarge", diff.oldLineCount(), diff.newLineCount()))).append("\n\n");
            return;
        }
        if (diff.unchanged()) {
            md.append(inline(r("diff.unchanged"))).append("\n\n");
            return;
        }
        md.append(inline(r("diff.stats", diff.added(), diff.removed(), diff.hunks().size()))).append("\n\n");
        for (int index = 0; index < diff.hunks().size(); index++) {
            TextLineDiff.Hunk hunk = diff.hunks().get(index);
            List<String> ids = apply.findingIdsOfHunk(index);
            md.append("**").append(inline(r("diff.region", index + 1))).append("**");
            if (!ids.isEmpty()) {
                md.append(" · ").append(inline(String.join(", ", ids)));
            }
            md.append("\n\n");
            for (Change change : apply.changes()) {
                if (ids.stream().anyMatch(id -> id.equalsIgnoreCase(change.findingId())) && !change.reason().isBlank()) {
                    md.append("- ").append(inline(change.findingId())).append(": ").append(joinLines(change.reason()))
                        .append('\n');
                }
            }
            if (!ids.isEmpty()) {
                md.append('\n');
            }
            StringBuilder text = new StringBuilder(hunk.header()).append('\n');
            for (TextLineDiff.Line line : hunk.lines()) {
                text.append(switch (line.kind()) {
                    case ADDED -> '+';
                    case REMOVED -> '-';
                    case CONTEXT -> ' ';
                }).append(line.text()).append('\n');
            }
            md.append(fence(text.toString(), "diff")).append('\n');
        }
    }

    private static void appendVerification(StringBuilder md, SnippetAnalysisReport report) {
        SnippetAnalysisReport.Verification verification = report.verification();
        md.append("## ").append(inline(r("verify.title"))).append("\n\n");
        md.append('_').append(inline(r("verify.heuristic"))).append("_\n\n");
        appendDeltaGroup(md, r("verify.resolved"), verification.resolved());
        appendDeltaGroup(md, r("verify.new"), verification.introduced());
        appendDeltaGroup(md, r("verify.persisting"), verification.persisting());
    }

    private static void appendDeltaGroup(StringBuilder md, String title, List<DeltaItem> items) {
        md.append("**").append(inline(title)).append("** (").append(items.size()).append(")\n\n");
        if (items.isEmpty()) {
            md.append("- ").append(inline(r("none"))).append("\n\n");
            return;
        }
        for (DeltaItem item : items) {
            md.append("- ").append(inline(item.id()));
            if (!item.previousId().isBlank() && !item.previousId().equals(item.id())) {
                md.append(" (").append(inline(r("verify.was", item.previousId()))).append(')');
            }
            md.append(" · ").append(inline(SnippetAnalysisReportText.severityLabel(item.severity()))).append(" · ")
                .append(inline(item.title())).append('\n');
        }
        md.append('\n');
    }

    private static void appendDiagram(StringBuilder md, SnippetAnalysisReport report, RasterizedDiagram diagram,
                                      String pngFileName) {
        String title = t("snippets.ai.analysis.diagram.title");
        md.append("## ").append(inline(title)).append("\n\n");
        if (report.diagram() == null) {
            md.append(inline(t("snippets.ai.analysis.diagram.unavailable"))).append("\n\n");
            return;
        }
        md.append('_').append(inline(SnippetAnalysisReportText.diagramTypeLabel(report.diagram().type())))
            .append("_\n\n");
        if (report.diagram().fallback() || !report.diagram().fallbackNotice().isBlank()) {
            String notice = report.diagram().fallbackNotice().isBlank()
                ? t("snippets.ai.analysis.diagram.fallback.generic") : report.diagram().fallbackNotice();
            md.append("> ").append(inline(notice)).append("\n\n");
        }
        if (pngFileName != null) {
            md.append("![").append(inline(title)).append("](<").append(pngFileName.replace(">", "%3E"))
                .append(">)\n\n");
        } else if (diagram != null && diagram.outcome().failed()) {
            md.append("> **⚠** ").append(inline(r("diagram.unavailable", diagram.outcome().message())))
                .append("\n\n");
        }
        md.append(fence(report.diagram().mermaidSource(), "mermaid")).append('\n');
    }

    // ---- prose ----

    /** AI prose: paragraphs escaped (line breaks kept), code re-fenced, tables as Markdown tables. */
    private static void appendText(StringBuilder md, String text) {
        for (ReportTextBlocks.Block block : ReportTextBlocks.split(text)) {
            switch (block.type()) {
                case PARAGRAPH -> md.append(joinLines(block.text())).append("\n\n");
                case CODE -> md.append(fence(block.text(), fenceLanguage(block.language()))).append('\n');
                case TABLE -> {
                    List<List<String>> rows = block.rows();
                    int columns = rows.stream().mapToInt(List::size).max().orElse(0);
                    for (int row = 0; row < rows.size(); row++) {
                        md.append('|');
                        for (int column = 0; column < columns; column++) {
                            String value = column < rows.get(row).size() ? rows.get(row).get(column) : "";
                            md.append(' ').append(cell(value)).append(" |");
                        }
                        md.append('\n');
                        if (row == 0) {
                            md.append('|').append("---|".repeat(Math.max(1, columns))).append('\n');
                        }
                    }
                    md.append('\n');
                }
            }
        }
    }

    /** A labelled block quote; every line of the text keeps the quote marker. */
    private static void appendQuoted(StringBuilder md, String label, String text) {
        md.append("> **").append(inline(label)).append(":**");
        boolean first = true;
        for (ReportTextBlocks.Block block : ReportTextBlocks.split(text)) {
            if (block.type() == ReportTextBlocks.Type.CODE) {
                md.append("\n>\n");
                for (String line : fence(block.text(), fenceLanguage(block.language())).split("\n", -1)) {
                    if (!line.isEmpty()) {
                        md.append("> ").append(line).append('\n');
                    }
                }
                first = false;
                continue;
            }
            String body = block.type() == ReportTextBlocks.Type.TABLE
                ? block.rows().stream().map(row -> String.join(" · ", row)).reduce((a, b) -> a + "\n" + b).orElse("")
                : block.text();
            String escaped = joinLines(body).replace("\n", "\n> ");
            md.append(first ? " " : "\n>\n> ").append(escaped);
            first = false;
        }
        md.append("\n\n");
    }

    /**
     * Escapes each line of AI prose and joins them with hard line breaks, so the line structure
     * survives; a paired {@code `inline code`} span stays a code span.
     */
    static String joinLines(String text) {
        String[] lines = (text != null ? text : "").replace("\r\n", "\n").replace('\r', '\n').strip().split("\n");
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                out.append("  \n");
            }
            out.append(prose(lines[index].strip()));
        }
        return out.toString();
    }

    /** One line of prose: text escaped by {@link #inline}, paired backtick spans kept as code spans. */
    static String prose(String line) {
        StringBuilder out = new StringBuilder();
        int index = 0;
        while (index < line.length()) {
            int open = line.indexOf('`', index);
            int close = open >= 0 ? line.indexOf('`', open + 1) : -1;
            if (open < 0 || close < 0 || close == open + 1) {
                out.append(escapeFragment(line.substring(index), out.isEmpty()));
                break;
            }
            out.append(escapeFragment(line.substring(index, open), out.isEmpty()));
            out.append(codeSpan(line.substring(open + 1, close)));
            index = close + 1;
        }
        return out.toString();
    }

    /** {@link #inline} for a fragment; block-start escaping only applies at the start of the line. */
    private static String escapeFragment(String fragment, boolean lineStart) {
        if (fragment.isEmpty()) {
            return "";
        }
        if (lineStart) {
            return inline(fragment);
        }
        String escaped = inline("x" + fragment);
        return escaped.substring(1);
    }

    // ---- escaping ----

    /**
     * Escapes Markdown syntax in one line of text: {@code \ ` * _ [ ] < > | ~ #} anywhere, and a
     * leading {@code + - =} or ordered-list number ({@code 1.}) that would start a block.
     */
    static String inline(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String value = text.replace("\r\n", " ").replace('\n', ' ').replace('\r', ' ');
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (INLINE_SPECIALS.indexOf(character) >= 0) {
                out.append('\\');
            }
            out.append(character);
        }
        String escaped = out.toString();
        String trimmed = escaped.stripLeading();
        String indent = escaped.substring(0, escaped.length() - trimmed.length());
        if (!trimmed.isEmpty() && "+-=".indexOf(trimmed.charAt(0)) >= 0) {
            return indent + "\\" + trimmed;
        }
        Matcher ordered = ORDERED_LIST_START.matcher(trimmed);
        if (ordered.find()) {
            return indent + ordered.group(1) + "\\" + ordered.group(2) + trimmed.substring(ordered.end());
        }
        return escaped;
    }

    /** A table cell: {@link #inline} (which escapes {@code |}) with line breaks as {@code <br>}. */
    static String cell(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String[] lines = text.replace("\r\n", "\n").replace('\r', '\n').strip().split("\n");
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) {
                out.append("<br>");
            }
            out.append(inline(lines[index].strip()));
        }
        return out.toString();
    }

    /** A fenced block whose fence is longer than any backtick run in {@code content} (at least three). */
    static String fence(String content, String language) {
        String body = content != null ? content : "";
        int longest = 0;
        Matcher matcher = BACKTICK_RUN.matcher(body);
        while (matcher.find()) {
            longest = Math.max(longest, matcher.group().length());
        }
        String marker = "`".repeat(Math.max(3, longest + 1));
        StringBuilder out = new StringBuilder(body.length() + 16);
        out.append(marker).append(language != null ? language : "").append('\n').append(body);
        if (!body.endsWith("\n")) {
            out.append('\n');
        }
        out.append(marker).append('\n');
        return out.toString();
    }

    private static String codeSpan(String text) {
        String body = text != null ? text : "";
        int longest = 0;
        Matcher matcher = BACKTICK_RUN.matcher(body);
        while (matcher.find()) {
            longest = Math.max(longest, matcher.group().length());
        }
        String marker = "`".repeat(longest + 1);
        String pad = body.startsWith("`") || body.endsWith("`") ? " " : "";
        return marker + pad + body + pad + marker;
    }

    static String fenceLanguage(String language) {
        String value = language != null ? language.trim().toLowerCase(Locale.ROOT) : "";
        return value.replaceAll("[^a-z0-9+#_-]", "");
    }

    private static String firstLine(String text) {
        for (String line : text.split("\\R")) {
            if (!line.isBlank()) {
                return line.strip();
            }
        }
        return "";
    }
}
