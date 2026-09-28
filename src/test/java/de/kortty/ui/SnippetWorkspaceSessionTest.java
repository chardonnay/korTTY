package de.kortty.ui;

import de.kortty.core.SnippetDraftStore.SnippetDraft;
import org.testng.annotations.Test;

import java.util.Arrays;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

/** Pure rules of the workspace's session comfort: tab restore, library folding, orphan drafts. */
class SnippetWorkspaceSessionTest {

    @Test
    void storedTabsAreRestoredInOrderWithoutGapsDuplicatesOrDeletedSnippets() {
        List<String> stored = Arrays.asList("a", null, "", "b", "a", "deleted", "c");
        Set<String> existing = Set.of("a", "b", "c");
        assertThat(SnippetWorkspaceDialog.restorableTabIds(stored, existing::contains, 12))
            .containsExactly("a", "b", "c").inOrder();
        assertThat(SnippetWorkspaceDialog.restorableTabIds(stored, existing::contains, 2))
            .containsExactly("a", "b").inOrder();
        assertThat(SnippetWorkspaceDialog.restorableTabIds(null, existing::contains, 12)).isEmpty();
        assertThat(SnippetWorkspaceDialog.restorableTabIds(List.of("a"), id -> false, 12)).isEmpty();
    }

    @Test
    void theLibraryFoldsAwayOnlyWhenTheCodeWouldGetTooNarrow() {
        // Editor row 920 px (what the library leaves over): a 520 px panel leaves the code 394 px.
        assertThat(SnippetWorkspaceDialog.shouldAutoCollapseLibrary(920, 520)).isTrue();
        // 1540 px row (2100 px window, 0.27 library): 1540 - 6 - 740 = 794 px of code stay.
        assertThat(SnippetWorkspaceDialog.shouldAutoCollapseLibrary(1540, 740)).isFalse();
        // Measured on what the panel really gets, not on its wish: at 700 px the panel is capped
        // to its minimum, and the 334 px of code left are still too narrow.
        assertThat(SnippetWorkspaceDialog.shouldAutoCollapseLibrary(700, 1200)).isTrue();
        // Not laid out yet: never fold anything.
        assertThat(SnippetWorkspaceDialog.shouldAutoCollapseLibrary(0, 520)).isFalse();
        assertThat(SnippetWorkspaceDialog.shouldAutoCollapseLibrary(1280, 0)).isFalse();
    }

    @Test
    void onlyDraftsOfMissingSnippetsThatNoEditorOwnsAreOrphans() {
        SnippetDraft existing = SnippetDraft.of("s1", 3L, false, "a", "", "", "", "", "x", "");
        SnippetDraft orphanOld = SnippetDraft.of("new-1", 1L, true, "b", "", "", "", "", "y", "");
        SnippetDraft orphanNew = SnippetDraft.of("new-2", 5L, true, "c", "", "", "", "", "z", "");
        SnippetDraft open = SnippetDraft.of("new-3", 9L, true, "d", "", "", "", "", "w", "");
        List<SnippetDraft> orphans = SnippetWorkspaceDialog.orphanDrafts(
            List.of(existing, orphanOld, orphanNew, open), "s1"::equals, Set.of("new-3"));
        assertThat(orphans).containsExactly(orphanNew, orphanOld).inOrder();
        assertThat(SnippetWorkspaceDialog.orphanDrafts(null, id -> false, Set.of())).isEmpty();
    }
}
