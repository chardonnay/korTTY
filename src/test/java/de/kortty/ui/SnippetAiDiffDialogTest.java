package de.kortty.ui;

import de.kortty.core.SnippetAiResponseSupport;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SnippetAiDiffDialogTest {

    @Test
    void findingFilterOffersEveryAnnotatedFindingOnceInModelOrder() {
        List<String> choices = SnippetAiDiffDialog.findingFilterChoices(List.of(
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
        assertThat(SnippetAiDiffDialog.findingFilterChoices(null)).isEmpty();
        assertThat(SnippetAiDiffDialog.findingFilterChoices(List.of())).isEmpty();
        assertThat(SnippetAiDiffDialog.findingFilterChoices(List.of(change("SEC-1", "")))).isEmpty();
    }

    @Test
    void noFilterKeepsEveryChangeAndAPickedFindingKeepsOnlyItsOwn() {
        SnippetAiResponseSupport.SecurityChange security = change("SEC-1", "Quoted the path expansion.");
        SnippetAiResponseSupport.SecurityChange hardening = change("HARDENING-05", "Added the --help handling.");

        assertThat(SnippetAiDiffDialog.matchesFindingFilter(security, null)).isTrue();
        assertThat(SnippetAiDiffDialog.matchesFindingFilter(security, "   ")).isTrue();
        assertThat(SnippetAiDiffDialog.matchesFindingFilter(security, "SEC-1")).isTrue();
        assertThat(SnippetAiDiffDialog.matchesFindingFilter(hardening, "SEC-1")).isFalse();
        // A longer id starting with the same text is a different finding, not a match.
        assertThat(SnippetAiDiffDialog.matchesFindingFilter(change("SEC-11", "Other."), "SEC-1")).isFalse();
    }

    private static SnippetAiResponseSupport.SecurityChange change(String finding, String reason) {
        return new SnippetAiResponseSupport.SecurityChange(finding, "anchor", reason);
    }
}
