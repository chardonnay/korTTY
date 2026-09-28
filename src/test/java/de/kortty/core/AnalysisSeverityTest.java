package de.kortty.core;

import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * {@link AnalysisSeverity#fromLabel} is the one severity mapping of the analysis window and every
 * report export. The golden table below is the mapping the exporter always used (its former
 * private {@code severityRank}/{@code severityClass}), so a finding keeps sorting the same.
 */
class AnalysisSeverityTest {

    /** label -> expected rank, the exporter's historic mapping. */
    private static final Map<String, Integer> LEGACY_RANKS = legacyRanks();

    private static Map<String, Integer> legacyRanks() {
        Map<String, Integer> ranks = new LinkedHashMap<>();
        for (String label : List.of("critical", "Critical", "CRITICAL", " crit ", "crit")) {
            ranks.put(label, 0);
        }
        for (String label : List.of("high", "HIGH", " High")) {
            ranks.put(label, 1);
        }
        for (String label : List.of("medium", "Medium", "moderate", "MODERATE", "med")) {
            ranks.put(label, 2);
        }
        for (String label : List.of("low", "Low", "LOW ")) {
            ranks.put(label, 3);
        }
        for (String label : List.of("info", "informational", "urgent", "blocker", "", "  ", "null-ish")) {
            ranks.put(label, 4);
        }
        return ranks;
    }

    private static final List<String> CSS_BY_RANK = List.of(
        "sev-critical", "sev-high", "sev-medium", "sev-low", "sev-info");

    @Test
    void labelMappingMatchesTheLegacyExporter() {
        LEGACY_RANKS.forEach((label, rank) -> {
            AnalysisSeverity severity = AnalysisSeverity.fromLabel(label);
            assertWithMessage("rank of '%s'", label).that(severity.rank()).isEqualTo(rank);
            assertWithMessage("css class of '%s'", label).that(severity.cssClass()).isEqualTo(CSS_BY_RANK.get(rank));
        });
        assertThat(AnalysisSeverity.fromLabel(null)).isEqualTo(AnalysisSeverity.INFO);
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
    void sortingByRankIsStableAndMostSevereFirst() {
        List<String> labels = new ArrayList<>(LEGACY_RANKS.keySet());
        labels.sort(Comparator.comparingInt(label -> AnalysisSeverity.fromLabel(label).rank()));
        List<String> expected = new ArrayList<>(LEGACY_RANKS.keySet());
        expected.sort(Comparator.comparingInt(LEGACY_RANKS::get));
        assertThat(labels).isEqualTo(expected);

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
        // Every key resolves to a label in the bundle rather than to the missing-key fallback.
        for (AnalysisSeverity severity : AnalysisSeverity.values()) {
            String label = de.kortty.ui.I18n.get(severity.i18nKey());
            assertWithMessage(severity.name()).that(label).isNotEmpty();
            assertWithMessage(severity.name()).that(label).isNotEqualTo(severity.i18nKey());
            assertWithMessage(severity.name()).that(label).doesNotContain("!");
        }
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
