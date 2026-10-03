package de.kortty.ui;

import de.kortty.model.Snippet;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The rows of the snippet quick open, unchanged by its move onto {@link QuickPickPopup}. Toolkit-free. */
class SnippetQuickOpenPopupTest {

    @Test
    void aRowShowsTheNameThenTheCategoryAndTheTags() {
        Snippet snippet = new Snippet("deploy.sh", "echo", "bash");
        snippet.setCategory("Ops");
        snippet.setTags(List.of("prod", "ci"));

        assertThat(SnippetQuickOpenPopup.rowText(snippet)).isEqualTo("deploy.sh   ·   Ops   #prod, ci");
    }

    @Test
    void aRowLeavesOutAnEmptyCategoryAndNoTags() {
        Snippet snippet = new Snippet("backup.sql", "select 1", "sql");
        snippet.setCategory(" ");

        assertThat(SnippetQuickOpenPopup.rowText(snippet)).isEqualTo("backup.sql");
    }

    @Test
    void aSnippetWithoutANameStillGetsARow() {
        Snippet snippet = new Snippet(null, "", "bash");
        snippet.setTags(List.of("draft"));

        assertThat(SnippetQuickOpenPopup.rowText(snippet)).isEqualTo("   #draft");
    }
}
