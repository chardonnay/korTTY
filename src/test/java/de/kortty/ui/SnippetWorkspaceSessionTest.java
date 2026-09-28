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
        assertThat(SnippetWorkspaceDialog.shouldAutoCollapseLibrary(1280, 360, 460)).isTrue();
        assertThat(SnippetWorkspaceDialog.shouldAutoCollapseLibrary(1900, 360, 460)).isFalse();
        // Not laid out yet: never fold anything.
        assertThat(SnippetWorkspaceDialog.shouldAutoCollapseLibrary(0, 360, 460)).isFalse();
        assertThat(SnippetWorkspaceDialog.shouldAutoCollapseLibrary(1280, 0, 460)).isFalse();
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
