package de.kortty.core;

import org.testng.annotations.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * {@link AnalysisSeverity#fromLabel} replaces the exporter's private {@code severityRank}/
 * {@code severityClass} pair. Until the exporter is migrated both exist, so these tests read the
 * exporter's mapping by reflection and require the enum to agree label for label — a drift would
 * sort a finding differently in the report than in the window.
 */
class AnalysisSeverityTest {

    private static final List<String> LABELS = List.of(
        "critical", "Critical", "CRITICAL", " crit ", "crit",
        "high", "HIGH", " High",
        "medium", "Medium", "moderate", "MODERATE", "med",
        "low", "Low", "LOW ",
        "info", "informational", "urgent", "blocker", "", "  ", "null-ish");

    @Test
    void labelMappingMatchesTheExporter() throws Exception {
        Method rank = SnippetAnalysisExportService.class.getDeclaredMethod("severityRank", String.class);
        rank.setAccessible(true);
        Method cssClass = SnippetAnalysisExportService.class.getDeclaredMethod("severityClass", String.class);
        cssClass.setAccessible(true);

        for (String label : LABELS) {
            AnalysisSeverity severity = AnalysisSeverity.fromLabel(label);
            assertWithMessage("rank of '%s'", label).that(severity.rank()).isEqualTo(rank.invoke(null, label));
            assertWithMessage("css class of '%s'", label).that(severity.cssClass()).isEqualTo(cssClass.invoke(null, label));
        }
        assertThat(AnalysisSeverity.fromLabel(null)).isEqualTo(AnalysisSeverity.INFO);
        assertThat(rank.invoke(null, (Object) null)).isEqualTo(AnalysisSeverity.INFO.rank());
    }

    @Test
    void exactSpellings() {
        assertThat(AnalysisSeverity.fromLabel("critical")).isEqualTo(AnalysisSeverity.CRITICAL);
        assertThat(AnalysisSeverity.fromLabel("crit")).isEqualTo(AnalysisSeverity.CRITICAL);
        assertThat(AnalysisSeverity.fromLabel("high")).isEqualTo(AnalysisSeverity.HIGH);
        assertThat(AnalysisSeverity.fromLabel("medium")).isEqualTo(AnalysisSeverity.MEDIUM);
        assertThat(AnalysisSeverity.fromLabel("moderate")).isEqualTo(AnalysisSeverity.MEDIUM);
        assertThat(AnalysisSeverity.fromLabel("med")).isEqualTo(AnalysisSeverity.MEDIUM);
        assertThat(AnalysisSeverity.fromLabel("low")).isEqualTo(AnalysisSeverity.LOW);
        // Only these spellings count; a near miss must not be guessed.
        assertThat(AnalysisSeverity.fromLabel("hi")).isEqualTo(AnalysisSeverity.INFO);
        assertThat(AnalysisSeverity.fromLabel("severe")).isEqualTo(AnalysisSeverity.INFO);
        assertThat(AnalysisSeverity.fromLabel("info")).isEqualTo(AnalysisSeverity.INFO);
    }

    @Test
    void sortingByRankMatchesTheExporterOrdering() throws Exception {
        Method rank = SnippetAnalysisExportService.class.getDeclaredMethod("severityRank", String.class);
        rank.setAccessible(true);
        List<String> byEnum = new ArrayList<>(LABELS);
        byEnum.sort(Comparator.comparingInt(label -> AnalysisSeverity.fromLabel(label).rank()));
        List<String> byExporter = new ArrayList<>(LABELS);
        byExporter.sort(Comparator.comparingInt(label -> {
            try {
                return (int) rank.invoke(null, label);
            } catch (ReflectiveOperationException e) {
                throw new AssertionError(e);
            }
        }));
        assertThat(byEnum).isEqualTo(byExporter);

        assertThat(AnalysisSeverity.CRITICAL.rank()).isEqualTo(0);
        assertThat(AnalysisSeverity.HIGH.rank()).isEqualTo(1);
        assertThat(AnalysisSeverity.MEDIUM.rank()).isEqualTo(2);
        assertThat(AnalysisSeverity.LOW.rank()).isEqualTo(3);
        assertThat(AnalysisSeverity.INFO.rank()).isEqualTo(4);
    }

    @Test
    void keysColoursAndClasses() {
        assertThat(AnalysisSeverity.HIGH.i18nKey()).isEqualTo("snippets.ai.analysis.report.severity.high");
        assertThat(AnalysisSeverity.INFO.i18nKey()).isEqualTo("snippets.ai.analysis.report.severity.info");
        assertThat(AnalysisSeverity.CRITICAL.cssClass()).isEqualTo("sev-critical");
        assertThat(AnalysisSeverity.MEDIUM.cssClass()).isEqualTo("sev-medium");

        assertThat(AnalysisSeverity.CRITICAL.printColorHex()).isEqualTo("#991B1B");
        assertThat(AnalysisSeverity.HIGH.printColorHex()).isEqualTo("#C0392B");
        assertThat(AnalysisSeverity.MEDIUM.printColorHex()).isEqualTo("#E67E22");
        assertThat(AnalysisSeverity.LOW.printColorHex()).isEqualTo("#B7950B");
        assertThat(AnalysisSeverity.INFO.printColorHex()).isEqualTo("#7F8C8D");
        // Critical and high used to share one red in the export; they must stay distinguishable.
        assertThat(AnalysisSeverity.CRITICAL.printColorHex()).isNotEqualTo(AnalysisSeverity.HIGH.printColorHex());
        for (AnalysisSeverity severity : AnalysisSeverity.values()) {
            assertThat(severity.printColorHex()).matches("#[0-9A-F]{6}");
        }
    }
}
