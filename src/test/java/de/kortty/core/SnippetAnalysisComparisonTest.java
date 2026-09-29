package de.kortty.core;

import de.kortty.core.SnippetAiResponseSupport.ScriptAnalysis;
import de.kortty.core.SnippetAiResponseSupport.ScriptDependency;
import de.kortty.core.SnippetAiResponseSupport.ScriptImprovement;
import de.kortty.core.SnippetAnalysisRecord.Verification;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

public class SnippetAnalysisComparisonTest {

    @Test
    public void classifiesResolvedPersistingAndNewFindings() {
        SnippetAnalysisRecord previous = record("prev",
            List.of(
                new ScriptImprovement("SEC-1", "security", "high", "Quote the variable expansion",
                    "Unquoted variables split on whitespace.", "Wrap $1 in double quotes.", 4),
                new ScriptImprovement("OPT-1", "optimization", "low", "Avoid useless cat",
                    "cat file | grep spawns an extra process.", "Pass the file to grep.", 9)),
            List.of(new ScriptDependency("D1", "curl", "binary", "download", ""),
                new ScriptDependency("D2", "jq", "binary", "json", "")));
        SnippetAnalysisRecord current = record("cur",
            List.of(
                new ScriptImprovement("S1", "security", "high", "Quote variable expansions",
                    "Unquoted variables are split on whitespace.", "Use double quotes around $1.", 5),
                new ScriptImprovement("D-9", "design", "info", "Add a usage message",
                    "No help is printed.", "Print usage on -h.", 1)),
            List.of(new ScriptDependency("X", " CURL ", "binary", "download", "")));

        Verification verification = SnippetAnalysisComparison.compare(previous, current);

        assertThat(verification.previousRecordId()).isEqualTo("prev");
        assertThat(verification.persistingCurrentToPrevious()).containsExactly("S1", "SEC-1", "X", "D1");
        assertThat(verification.resolvedPreviousIds()).containsExactly("OPT-1", "D2").inOrder();
        assertThat(verification.newIds()).containsExactly("D-9");
    }

    @Test
    public void differentCategoriesNeverMatch() {
        ScriptImprovement same = new ScriptImprovement("A", "security", "high", "Quote the variable", "d", "r", 1);
        SnippetAnalysisRecord previous = record("p", List.of(same), List.of());
        SnippetAnalysisRecord current = record("c",
            List.of(new ScriptImprovement("B", "design", "high", "Quote the variable", "d", "r", 1)), List.of());

        Verification verification = SnippetAnalysisComparison.compare(previous, current);

        assertThat(verification.persistingCurrentToPrevious()).isEmpty();
        assertThat(verification.resolvedPreviousIds()).containsExactly("A");
        assertThat(verification.newIds()).containsExactly("B");
    }

    @Test
    public void matchingIsOneToOneAndTiesGoToTheNearerLine() {
        SnippetAnalysisRecord previous = record("p", List.of(
            new ScriptImprovement("P1", "security", "high", "Validate the input path", "", "", 10),
            new ScriptImprovement("P2", "security", "high", "Validate the input path", "", "", 40)), List.of());
        SnippetAnalysisRecord current = record("c", List.of(
            new ScriptImprovement("C1", "security", "high", "Validate the input path", "", "", 38)), List.of());

        Verification verification = SnippetAnalysisComparison.compare(previous, current);

        assertThat(verification.persistingCurrentToPrevious()).containsExactly("C1", "P2");
        assertThat(verification.resolvedPreviousIds()).containsExactly("P1");
    }

    @Test
    public void tokensDropStopWordsShortTokensAndPluralS() {
        assertThat(SnippetAnalysisComparison.tokens("Quote the variables and die Pfade, ok?"))
            .containsExactly("quote", "variable", "pfade").inOrder();
    }

    @Test
    public void isDeterministic() {
        SnippetAnalysisRecord previous = SnippetAnalysisTestData.simpleRecord("a", "s", 1L);
        SnippetAnalysisRecord current = SnippetAnalysisTestData.simpleRecord("b", "s", 2L);

        assertThat(SnippetAnalysisComparison.compare(previous, current))
            .isEqualTo(SnippetAnalysisComparison.compare(previous, current));
        assertThat(SnippetAnalysisComparison.compare(previous, current).newIds()).isEmpty();
    }

    private static SnippetAnalysisRecord record(String id, List<ScriptImprovement> improvements,
                                                List<ScriptDependency> dependencies) {
        return SnippetAnalysisRecord.fromAnalysis(id, "s", new ScriptAnalysis("x", dependencies, improvements),
            null, null, null, null, 1L);
    }
}
