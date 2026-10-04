package de.kortty.ui;

import de.kortty.core.SnippetFuzzyMatcher;
import de.kortty.model.Snippet;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Quick open for the snippet workspace (Shortcut+P): a search field over the snippet names and
 * tags with fuzzy matching ({@link SnippetFuzzyMatcher}); ↑/↓ move, Enter opens the chosen snippet
 * in an editor tab, Esc closes. Nothing is opened while typing, and no other key gets past the
 * popup to the workspace behind it ({@link QuickPickPopup}).
 */
final class SnippetQuickOpenPopup {

    static final String ROOT_ID = "snippet-quick-open";
    static final String FIELD_ID = "snippet-quick-open-field";
    static final String LIST_ID = "snippet-quick-open-list";
    static final int MAX_RESULTS = 50;

    private final QuickPickPopup<Snippet> picker;

    SnippetQuickOpenPopup(Supplier<List<Snippet>> snippets, Consumer<Snippet> onChosen) {
        this.picker = QuickPickPopup.<Snippet>builder(ROOT_ID, FIELD_ID, LIST_ID)
            .prompt(I18n.get("snippets.workspace.quickOpen.prompt"))
            .empty(I18n.get("snippets.workspace.quickOpen.empty"))
            .search(query -> SnippetFuzzyMatcher.rank(query, snippets.get(), MAX_RESULTS).stream()
                .map(SnippetFuzzyMatcher.Match::snippet)
                .toList())
            .text(SnippetQuickOpenPopup::rowText)
            .onChosen(onChosen)
            .passThrough(QuickPickKeyFirewall.NONE)
            .build();
    }

    /** Shows the popup centred at the top of {@code anchor}, with an empty query. */
    void show(Node anchor) {
        picker.show(anchor);
    }

    void hide() {
        picker.hide();
    }

    boolean isShowing() {
        return picker.isShowing();
    }

    TextField field() {
        return picker.field();
    }

    ListView<Snippet> list() {
        return picker.list();
    }

    /** Opens the selected (or first) result and closes the popup. */
    void choose() {
        picker.choose();
    }

    /** A result row: the name, then the category and the tags when there are any. */
    static String rowText(Snippet snippet) {
        StringBuilder text = new StringBuilder(snippet.getName() != null ? snippet.getName() : "");
        if (snippet.getCategory() != null && !snippet.getCategory().isBlank()) {
            text.append("   ·   ").append(snippet.getCategory());
        }
        String tags = snippet.getTagsAsString();
        if (tags != null && !tags.isBlank()) {
            text.append("   #").append(tags);
        }
        return text.toString();
    }
}
