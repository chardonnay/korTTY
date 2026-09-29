package de.kortty.core;

import de.kortty.core.SnippetAnalysisExportService.ExportOptions;
import de.kortty.core.SnippetAnalysisReport.Finding;
import de.kortty.core.SnippetAnalysisReport.FindingStatus;
import de.kortty.core.SnippetAnalysisReport.Header;
import de.kortty.model.SnippetDiagramType;
import de.kortty.ui.I18n;

import java.text.NumberFormat;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The labels, numbers and table rows every report writer shares, so the PDF, HTML and Markdown
 * versions of a report say exactly the same thing.
 */
final class SnippetAnalysisReportText {

    /** Display sections in report order. */
    static final List<String> SECTIONS = List.of("security", "optimization", "design");
    static final String PREFIX = "snippets.ai.analysis.report.";

    private SnippetAnalysisReportText() {
    }

    static String t(String key, Object... args) {
        return args.length == 0 ? I18n.get(key) : I18n.get(key, args);
    }

    /** A report-content key below {@code snippets.ai.analysis.report.}. */
    static String r(String key, Object... args) {
        return t(PREFIX + key, args);
    }

    static String appTitle() {
        return t("snippets.ai.analysis.title");
    }

    /** "Report before applying" / "Report after applying". */
    static String kindTitle(SnippetAnalysisReport report) {
        return r(report.isPost() ? "title.after" : "title.before");
    }

    /** "Before applying" / "After applying". */
    static String kindLabel(SnippetAnalysisReport report) {
        return r(report.isPost() ? "kind.after" : "kind.before");
    }

    /** "AI Code Analysis — script.sh" (the document title). */
    static String documentTitle(SnippetAnalysisReport report) {
        String name = report.header().scriptName();
        return name.isBlank() ? appTitle() : appTitle() + " — " + name;
    }

    static String sectionTitle(String displayCategory) {
        return t("snippets.ai.analysis.section." + displayCategory);
    }

    static String severityLabel(AnalysisSeverity severity) {
        return t(severity.i18nKey());
    }

    static String statusLabel(FindingStatus status) {
        return status != null ? t(status.i18nKey()) : "";
    }

    static String outcomeLabel(SnippetAnalysisReport.Outcome outcome) {
        return t(outcome.i18nKey());
    }

    static String lineLabel(int line) {
        return t("common.line") + " " + line;
    }

    static String recommendationLabel() {
        return stripColon(t("snippets.ai.review.recommendation"));
    }

    static String purposeLabel() {
        return stripColon(t("snippets.ai.analysis.dependency.purpose"));
    }

    static String suggestionLabel() {
        return stripColon(t("snippets.ai.analysis.dependency.suggestion"));
    }

    static String continued(String title) {
        return r("continued", title);
    }

    /** Drops a trailing colon from a UI label that is shown as a heading here. */
    static String stripColon(String label) {
        return label == null ? "" : label.replaceFirst("\\s*[:：]\\s*$", "");
    }

    /** Dates as the reader's locale writes them (medium date, short time). */
    static String dateTime(Instant instant, ExportOptions options) {
        if (instant == null) {
            return "—";
        }
        return DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
            .withLocale(options.locale())
            .withZone(options.zone())
            .format(instant);
    }

    static String number(long value, Locale locale) {
        return NumberFormat.getIntegerInstance(locale).format(value);
    }

    /** "German" for {@code de}, in the report's language; the code itself when unknown. */
    static String languageName(String code, Locale locale) {
        if (code == null || code.isBlank()) {
            return "";
        }
        Locale language = Locale.forLanguageTag(code.replace('_', '-'));
        String name = language.getDisplayLanguage(locale);
        if (name == null || name.isBlank() || name.equalsIgnoreCase(code)) {
            return code;
        }
        return name.substring(0, 1).toUpperCase(locale) + name.substring(1);
    }

    static String diagramTypeLabel(SnippetDiagramType type) {
        if (type == null) {
            return t("snippets.ai.diagram.type.unknown");
        }
        return switch (type) {
            case LOGICAL_STRUCTURE -> t("snippets.ai.diagram.type.logicalStructure");
            case SEQUENCE -> t("snippets.ai.diagram.type.sequence");
            case STATE -> t("snippets.ai.diagram.type.state");
            case CLASS -> t("snippets.ai.diagram.type.class");
            case ER -> t("snippets.ai.diagram.type.er");
        };
    }

    /** The raw AI category when it differs from the section, e.g. "dependency" under Design. */
    static String categoryTag(Finding finding) {
        String raw = finding.category().trim();
        if (raw.isEmpty() || raw.equalsIgnoreCase(finding.displayCategory())) {
            return "";
        }
        return r("finding.categoryTag", raw);
    }

    /** A stable anchor id for HTML/Markdown, e.g. {@code f-SEC-1}. */
    static String anchor(String prefix, String id) {
        String safe = id == null ? "" : id.replaceAll("[^A-Za-z0-9_.-]+", "-");
        return prefix + "-" + (safe.isEmpty() ? "x" : safe);
    }

    // ---- meta grid ----

    /** One meta-grid cell; {@code mono} values are hashes. */
    record Meta(String label, String value, boolean mono) {
    }

    static List<Meta> metaRows(SnippetAnalysisReport report, ExportOptions options) {
        Header header = report.header();
        List<Meta> rows = new ArrayList<>();
        rows.add(new Meta(r("meta.language"), header.scriptLanguage().isBlank() ? "—" : header.scriptLanguage(),
            false));
        String profile = header.aiProfileName().isBlank() ? "—" : header.aiProfileName();
        if (!header.aiModel().isBlank() && !header.aiModel().equals(header.aiProfileName())) {
            profile = profile + " · " + header.aiModel();
        }
        rows.add(new Meta(stripColon(t("snippets.ai.analysis.export.meta.profile")), profile, false));
        String textLanguage = languageName(header.codeTextLanguageCode(), options.locale());
        String reportLanguage = languageName(header.analysisLanguageCode(), options.locale());
        String languages = !reportLanguage.isBlank() && !textLanguage.isBlank() && !reportLanguage.equals(textLanguage)
            ? reportLanguage + " / " + textLanguage
            : !reportLanguage.isBlank() ? reportLanguage : textLanguage;
        rows.add(new Meta(r("meta.textLanguage"), languages.isBlank() ? "—" : languages, false));
        rows.add(new Meta(r("meta.analysedAt"), dateTime(header.analysedAt(), options), false));
        rows.add(new Meta(r("meta.appliedAt"), dateTime(header.appliedAt(), options), false));
        rows.add(new Meta(r("meta.exportedAt"), dateTime(options.exportedAt(), options), false));
        rows.add(new Meta(stripColon(t("snippets.ai.analysis.export.meta.skills")),
            header.includedSkills().isEmpty() ? "—" : String.join(", ", header.includedSkills()), false));
        if (report.isPost()) {
            rows.add(new Meta(r("meta.run"), r("meta.runValue", header.runNumber(), header.runCount()), false));
        }
        rows.add(new Meta(r("meta.sourceHash"),
            header.sourceSha256().isBlank() ? "—" : shortHash(header.sourceSha256()), true));
        return rows;
    }

    static String shortHash(String sha) {
        return sha.length() > 12 ? sha.substring(0, 12) : sha;
    }

    // ---- stat tiles ----

    record Stat(String value, String label, String colorHex) {
    }

    static List<Stat> stats(SnippetAnalysisReport report, Locale locale) {
        List<Stat> stats = new ArrayList<>();
        stats.add(new Stat(number(report.findings().size(), locale), r("stat.findings"), "#1F2937"));
        long severe = report.findings().stream()
            .filter(f -> f.severity() == AnalysisSeverity.CRITICAL || f.severity() == AnalysisSeverity.HIGH).count();
        stats.add(new Stat(number(severe, locale), r("stat.criticalHigh"),
            severe > 0 ? AnalysisSeverity.HIGH.printColorHex() : "#1F2937"));
        long selected = report.findings().stream().filter(Finding::selected).count()
            + report.dependencies().stream().filter(SnippetAnalysisReport.Dependency::selected).count();
        stats.add(new Stat(number(selected, locale), r("stat.selected"), "#0066CC"));
        if (report.isPost()) {
            long applied = report.findings().stream().filter(f -> f.status() == FindingStatus.APPLIED).count()
                + report.dependencies().stream().filter(d -> d.status() == FindingStatus.APPLIED).count();
            stats.add(new Stat(number(applied, locale), r("stat.applied"), "#15803D"));
        } else {
            stats.add(new Stat(number(report.dependencies().size(), locale), r("stat.dependencies"),
                AnalysisCategoryVisuals.printColorHex("dependencies")));
        }
        return stats;
    }

    // ---- chart ----

    /** One bar: a section with its per-severity counts (dependencies: one teal segment). */
    record Bar(String category, String label, Map<AnalysisSeverity, Integer> counts, int total) {
    }

    static List<Bar> severityBars(SnippetAnalysisReport report) {
        List<Bar> bars = new ArrayList<>();
        for (String section : SECTIONS) {
            Map<AnalysisSeverity, Integer> counts = new EnumMap<>(AnalysisSeverity.class);
            int total = 0;
            for (Finding finding : report.findings()) {
                if (finding.displayCategory().equals(section)) {
                    counts.merge(finding.severity(), 1, Integer::sum);
                    total++;
                }
            }
            bars.add(new Bar(section, sectionTitle(section), counts, total));
        }
        bars.add(new Bar("dependencies", sectionTitle("dependencies"), Map.of(), report.dependencies().size()));
        return bars;
    }

    /** POST: the selected findings grouped by result, in legend order. */
    static Map<FindingStatus, Integer> resultCounts(SnippetAnalysisReport report) {
        Map<FindingStatus, Integer> counts = new EnumMap<>(FindingStatus.class);
        for (Finding finding : report.findings()) {
            if (finding.selected() && finding.status() != null) {
                counts.merge(finding.status(), 1, Integer::sum);
            }
        }
        for (SnippetAnalysisReport.Dependency dependency : report.dependencies()) {
            if (dependency.selected() && dependency.status() != null) {
                counts.merge(dependency.status(), 1, Integer::sum);
            }
        }
        return counts;
    }

    // ---- apply result ----

    record KeyValue(String key, String value) {
    }

    static List<KeyValue> applyRows(SnippetAnalysisReport report, ExportOptions options) {
        SnippetAnalysisReport.ApplyResult apply = report.apply();
        List<KeyValue> rows = new ArrayList<>();
        if (apply == null) {
            return rows;
        }
        rows.add(new KeyValue(r("meta.appliedAt"), dateTime(apply.appliedAt(), options)));
        if (!apply.profileName().isBlank()) {
            rows.add(new KeyValue(stripColon(t("snippets.ai.analysis.export.meta.profile")), apply.profileName()));
        }
        rows.add(new KeyValue(r("apply.duration"), AnalysisRunFormatting.formatDuration(apply.elapsedSeconds())));
        rows.add(new KeyValue(r("apply.tokens"), apply.usage() == null
            ? t("snippets.ai.analysis.progress.tokensUnavailable")
            : r("apply.tokensValue", number(apply.usage().promptTokens(), options.locale()),
                number(apply.usage().completionTokens(), options.locale()),
                number(apply.usage().totalTokens(), options.locale()),
                number(apply.usage().cachedPromptTokens(), options.locale()))));
        rows.add(new KeyValue(r("apply.retries"), number(apply.retries(), options.locale())));
        if (apply.totalItems() > 0) {
            rows.add(new KeyValue(r("apply.items"), r("meta.runValue", apply.completedItems(), apply.totalItems())));
        }
        rows.add(new KeyValue(r("apply.hardening"),
            apply.hardeningLabels().isEmpty() ? r("none") : String.join(", ", apply.hardeningLabels())));
        rows.add(new KeyValue(r("apply.inputHardening"),
            apply.inputHardeningLabels().isEmpty() ? r("none") : String.join(", ", apply.inputHardeningLabels())));
        if (!apply.headerName().isBlank()) {
            rows.add(new KeyValue(r("apply.header"), apply.headerName()));
        }
        if (!apply.migrationLabel().isBlank()) {
            rows.add(new KeyValue(r("apply.migration"), apply.migrationLabel()));
        }
        return rows;
    }

    /** "Changes to the script", or "Proposed changes (not applied)" when the result is not in the script. */
    static String diffTitle(SnippetAnalysisReport report) {
        return report.apply() != null && report.apply().outcome().applied() ? r("diff.title") : r("diff.titleRejected");
    }

    /** Title of the appendix. */
    static String codeTitle(SnippetAnalysisReport report) {
        if (!report.isPost()) {
            return r("code.before");
        }
        return report.apply() != null && report.apply().outcome().applied() ? r("code.after") : r("code.afterRejected");
    }

    /** The script the appendix shows: analysed (PRE) or the run's result (POST). */
    static SnippetAnalysisReport.CodeSnapshot appendixCode(SnippetAnalysisReport report) {
        return report.isPost() ? report.resultCode() : report.analysedCode();
    }

    static boolean hasVerification(SnippetAnalysisReport report) {
        return report.isPost() && report.verification() != null;
    }
}
