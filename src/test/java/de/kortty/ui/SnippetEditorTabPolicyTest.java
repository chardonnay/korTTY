package de.kortty.ui;

import de.kortty.ui.SnippetEditorTabPolicy.Kind;
import de.kortty.ui.SnippetEditorTabPolicy.OpenTab;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/** Pure decision logic of the snippet workspace's preview / pinned tabs; no toolkit. */
class SnippetEditorTabPolicyTest {

    private static final OpenTab PINNED_A = new OpenTab("a", Kind.PINNED);
    private static final OpenTab PREVIEW_B = new OpenTab("b", Kind.PREVIEW);

    @Test
    void singleClickOnAPinnedSnippetSelectsItsTab() {
        assertThat(SnippetEditorTabPolicy.onSingleClick(List.of(PINNED_A, PREVIEW_B), "a", false))
            .isEqualTo(new SnippetEditorTabPolicy.SelectExisting("a"));
    }

    @Test
    void singleClickReplacesThePreviewContent() {
        assertThat(SnippetEditorTabPolicy.onSingleClick(List.of(PINNED_A, PREVIEW_B), "c", false))
            .isEqualTo(new SnippetEditorTabPolicy.ShowInPreview("c", false));
        assertThat(SnippetEditorTabPolicy.onSingleClick(List.of(), "c", false))
            .isEqualTo(new SnippetEditorTabPolicy.ShowInPreview("c", false));
    }

    @Test
    void singleClickNeverStealsFocusForASnippetOpenElsewhere() {
        // Arrow-key browsing must not jump to another window: preview read-only with a banner.
        assertThat(SnippetEditorTabPolicy.onSingleClick(List.of(PREVIEW_B), "c", true))
            .isEqualTo(new SnippetEditorTabPolicy.ShowInPreview("c", true));
    }

    @Test
    void openingThePreviewedSnippetPromotesThePreview() {
        assertThat(SnippetEditorTabPolicy.onOpen(List.of(PINNED_A, PREVIEW_B), "b", false, false))
            .isEqualTo(new SnippetEditorTabPolicy.Promote("b"));
    }

    @Test
    void openingAnotherSnippetOpensANewPinnedTab() {
        assertThat(SnippetEditorTabPolicy.onOpen(List.of(PINNED_A, PREVIEW_B), "c", false, false))
            .isEqualTo(new SnippetEditorTabPolicy.OpenPinned("c"));
        assertThat(SnippetEditorTabPolicy.onOpen(null, "c", false, false))
            .isEqualTo(new SnippetEditorTabPolicy.OpenPinned("c"));
    }

    @Test
    void openingAnAlreadyPinnedSnippetSelectsItInsteadOfOpeningItTwice() {
        assertThat(SnippetEditorTabPolicy.onOpen(List.of(PINNED_A), "a", false, false))
            .isEqualTo(new SnippetEditorTabPolicy.SelectExisting("a"));
        // Pinned here wins even if the registry reported it elsewhere (it is this workspace's tab).
        assertThat(SnippetEditorTabPolicy.onOpen(List.of(PINNED_A), "a", true, true))
            .isEqualTo(new SnippetEditorTabPolicy.SelectExisting("a"));
    }

    @Test
    void openingASnippetEditedElsewhereRevealsThatEditor() {
        assertThat(SnippetEditorTabPolicy.onOpen(List.of(PREVIEW_B), "b", false, true))
            .isEqualTo(new SnippetEditorTabPolicy.RevealElsewhere("b"));
    }

    @Test
    void policyManagedSnippetsStayInThePreview() {
        assertThat(SnippetEditorTabPolicy.onOpen(List.of(PREVIEW_B), "b", true, false))
            .isEqualTo(new SnippetEditorTabPolicy.Refuse("b"));
        assertThat(SnippetEditorTabPolicy.onOpen(List.of(), "p", true, false))
            .isEqualTo(new SnippetEditorTabPolicy.Refuse("p"));
    }

    @Test
    void draftIdsAreKeysLikeAnyOther() {
        OpenTab draft = new OpenTab("draft-uuid", Kind.PINNED);
        assertThat(SnippetEditorTabPolicy.onOpen(List.of(draft), "draft-uuid", false, false))
            .isEqualTo(new SnippetEditorTabPolicy.SelectExisting("draft-uuid"));
    }

    @Test
    void aPreviewEntryDoesNotCountAsPinned() {
        assertThat(SnippetEditorTabPolicy.onSingleClick(List.of(PREVIEW_B), "b", false))
            .isEqualTo(new SnippetEditorTabPolicy.ShowInPreview("b", false));
    }

    @Test
    void nullIdIsRejected() {
        expectThrows(NullPointerException.class, () -> SnippetEditorTabPolicy.onOpen(List.of(), null, false, false));
        expectThrows(NullPointerException.class, () -> SnippetEditorTabPolicy.onSingleClick(List.of(), null, false));
    }
}
