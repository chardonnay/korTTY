package de.kortty.ui;

import de.kortty.core.SnippetFuzzyMatcher;
import de.kortty.model.Snippet;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.TextField;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Quick open for the snippet workspace (Shortcut+P): a search field over the snippet names and
 * tags with fuzzy matching ({@link SnippetFuzzyMatcher}); ↑/↓ move, Enter opens the chosen snippet
 * in an editor tab, Esc closes. Nothing is opened while typing.
 */
final class SnippetQuickOpenPopup {

    static final String ROOT_ID = "snippet-quick-open";
    static final String FIELD_ID = "snippet-quick-open-field";
    static final String LIST_ID = "snippet-quick-open-list";
    static final int MAX_RESULTS = 50;

    private final Supplier<List<Snippet>> snippets;
    private final Consumer<Snippet> onChosen;
    private final Popup popup = new Popup();
    private final TextField field = new TextField();
    private final ListView<Snippet> list = new ListView<>();
    private final Label empty = new Label(I18n.get("snippets.workspace.quickOpen.empty"));

    SnippetQuickOpenPopup(Supplier<List<Snippet>> snippets, Consumer<Snippet> onChosen) {
        this.snippets = snippets;
        this.onChosen = onChosen;
        field.setId(FIELD_ID);
        field.setPromptText(I18n.get("snippets.workspace.quickOpen.prompt"));
        list.setId(LIST_ID);
        list.setPrefHeight(320);
        list.setPlaceholder(empty);
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(Snippet snippet, boolean isEmpty) {
                super.updateItem(snippet, isEmpty);
                if (isEmpty || snippet == null) {
                    setText(null);
                    return;
                }
                StringBuilder text = new StringBuilder(snippet.getName() != null ? snippet.getName() : "");
                if (snippet.getCategory() != null && !snippet.getCategory().isBlank()) {
                    text.append("   ·   ").append(snippet.getCategory());
                }
                String tags = snippet.getTagsAsString();
                if (tags != null && !tags.isBlank()) {
                    text.append("   #").append(tags);
                }
                setText(text.toString());
            }
        });
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                choose();
            }
        });
        field.textProperty().addListener((obs, was, query) -> refresh(query));
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onKey);
        list.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                event.consume();
                choose();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                event.consume();
                hide();
            }
        });

        VBox root = new VBox(6, field, list);
        root.setId(ROOT_ID);
        root.setPadding(new Insets(8));
        root.setPrefWidth(560);
        root.setStyle("-fx-background-color: -fx-control-inner-background; -fx-background-radius: 8;"
            + " -fx-border-color: -fx-box-border; -fx-border-radius: 8;"
            + " -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.35), 18, 0.2, 0, 4);");
        popup.getContent().add(root);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
    }

    /** Shows the popup centred at the top of {@code anchor}, with an empty query. */
    void show(Node anchor) {
        if (anchor == null || anchor.getScene() == null || anchor.getScene().getWindow() == null) {
            return;
        }
        field.clear();
        refresh("");
        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        double width = 560;
        double x = bounds != null ? bounds.getMinX() + Math.max(0, (bounds.getWidth() - width) / 2) : 0;
        double y = bounds != null ? bounds.getMinY() + 40 : 0;
        popup.show(anchor, x, y);
        field.requestFocus();
    }

    void hide() {
        popup.hide();
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    TextField field() {
        return field;
    }

    ListView<Snippet> list() {
        return list;
    }

    /** Opens the selected (or first) result and closes the popup. */
    void choose() {
        Snippet chosen = list.getSelectionModel().getSelectedItem();
        if (chosen == null && !list.getItems().isEmpty()) {
            chosen = list.getItems().getFirst();
        }
        if (chosen == null) {
            return;
        }
        hide();
        onChosen.accept(chosen);
    }

    private void refresh(String query) {
        List<Snippet> ranked = SnippetFuzzyMatcher.rank(query, snippets.get(), MAX_RESULTS).stream()
            .map(SnippetFuzzyMatcher.Match::snippet)
            .toList();
        list.getItems().setAll(ranked);
        if (!ranked.isEmpty()) {
            list.getSelectionModel().select(0);
            list.scrollTo(0);
        }
    }

    private void onKey(KeyEvent event) {
        switch (event.getCode()) {
            case DOWN -> {
                event.consume();
                move(1);
            }
            case UP -> {
                event.consume();
                move(-1);
            }
            case ENTER -> {
                event.consume();
                choose();
            }
            case ESCAPE -> {
                event.consume();
                hide();
            }
            default -> {
            }
        }
    }

    private void move(int delta) {
        int size = list.getItems().size();
        if (size == 0) {
            return;
        }
        int index = list.getSelectionModel().getSelectedIndex();
        int next = Math.max(0, Math.min(size - 1, (index < 0 ? 0 : index) + delta));
        list.getSelectionModel().select(next);
        list.scrollTo(next);
    }
}
