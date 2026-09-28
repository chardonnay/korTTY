package de.kortty.ui;

import de.kortty.core.SnippetAiResponseSupport;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SnippetAiDiffPaneTest {

    @Test
    void findingFilterOffersEveryAnnotatedFindingOnceInModelOrder() {
        List<String> choices = SnippetAiDiffPane.findingFilterChoices(List.of(
            change("SEC-1", "Quoted the path expansion."),
            change("HARDENING-05", "Added the --help handling."),
            // A second block for a finding already listed must not produce a duplicate entry.
            change("SEC-1", "Quoted the second expansion."),
            // Entries without a reason carry no card and no decoration, so they cannot be focused.
            change("OPT-1", "  "),
            change("  ", "Unattributed change.")));

        assertThat(choices).containsExactly("SEC-1", "HARDENING-05").inOrder();
    }

    @Test
    void findingFilterIsEmptyWithoutAnnotatedChanges() {
        assertThat(SnippetAiDiffPane.findingFilterChoices(null)).isEmpty();
        assertThat(SnippetAiDiffPane.findingFilterChoices(List.of())).isEmpty();
        assertThat(SnippetAiDiffPane.findingFilterChoices(List.of(change("SEC-1", "")))).isEmpty();
    }

    @Test
    void noFilterKeepsEveryChangeAndAPickedFindingKeepsOnlyItsOwn() {
        SnippetAiResponseSupport.SecurityChange security = change("SEC-1", "Quoted the path expansion.");
        SnippetAiResponseSupport.SecurityChange hardening = change("HARDENING-05", "Added the --help handling.");

        assertThat(SnippetAiDiffPane.matchesFindingFilter(security, null)).isTrue();
        assertThat(SnippetAiDiffPane.matchesFindingFilter(security, "   ")).isTrue();
        assertThat(SnippetAiDiffPane.matchesFindingFilter(security, "SEC-1")).isTrue();
        assertThat(SnippetAiDiffPane.matchesFindingFilter(hardening, "SEC-1")).isFalse();
        // A longer id starting with the same text is a different finding, not a match.
        assertThat(SnippetAiDiffPane.matchesFindingFilter(change("SEC-11", "Other."), "SEC-1")).isFalse();
    }

    @Test
    void arrowsWalkTheFindingsAndSkipTheAllChangesEntry() {
        // Picker: [All changes, SEC-1, HARDENING-05, OPT-1] — four items, three findings.
        int items = 4;

        // From "all changes" the arrows enter the list at either end instead of doing nothing.
        assertThat(SnippetAiDiffPane.steppedFindingIndex(0, items, 1)).isEqualTo(1);
        assertThat(SnippetAiDiffPane.steppedFindingIndex(0, items, -1)).isEqualTo(3);

        assertThat(SnippetAiDiffPane.steppedFindingIndex(1, items, 1)).isEqualTo(2);
        assertThat(SnippetAiDiffPane.steppedFindingIndex(2, items, -1)).isEqualTo(1);

        // The ends wrap into each other, never back onto "all changes".
        assertThat(SnippetAiDiffPane.steppedFindingIndex(3, items, 1)).isEqualTo(1);
        assertThat(SnippetAiDiffPane.steppedFindingIndex(1, items, -1)).isEqualTo(3);

        // Nothing to walk: stay where we are rather than selecting a non-existent item.
        assertThat(SnippetAiDiffPane.steppedFindingIndex(0, 1, 1)).isEqualTo(0);
        assertThat(SnippetAiDiffPane.steppedFindingIndex(0, 0, -1)).isEqualTo(0);
    }

    @Test
    void summaryDividerIsStoredOnlyAfterTheReviewerMovedIt() {
        // Opened and left alone: the next review should keep following the summary's own height.
        assertThat(SnippetAiDiffPane.movedDividerPosition(0.18, 0.18)).isNull();
        // A layout pass rounds the applied position slightly; that is not a user decision.
        assertThat(SnippetAiDiffPane.movedDividerPosition(0.1849, 0.18)).isNull();
        // Dragged: remember exactly where it was left.
        assertThat(SnippetAiDiffPane.movedDividerPosition(0.42, 0.18)).isEqualTo(0.42);
        // Nothing was applied yet (window never shown), so there is nothing to compare against.
        assertThat(SnippetAiDiffPane.movedDividerPosition(0.42, null)).isNull();
    }

    private static SnippetAiResponseSupport.SecurityChange change(String finding, String reason) {
        return new SnippetAiResponseSupport.SecurityChange(finding, "anchor", reason);
    }
}
