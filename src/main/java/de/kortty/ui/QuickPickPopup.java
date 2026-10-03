package de.kortty.ui;

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
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Objects;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * A themed search popup: a field over a result list that is searched again on every keystroke.
 * ↑/↓ move, Enter (or a double click) chooses the selected or first result and closes the popup,
 * Esc closes it. Nothing is chosen while typing.
 *
 * <p>Every key event the field and the list leave unconsumed is stopped by a
 * {@link QuickPickKeyFirewall} on the popup's scene, so it can never reach the terminal or editor in
 * the window behind; only the keys the pass-through predicate names get through. A result the
 * choosable predicate rejects is shown with {@link #UNAVAILABLE_STYLE_CLASS}, keeps the popup open
 * when chosen and is read out as disabled. The field reads out its prompt, every row its text, and
 * the width follows the UI font scale.
 *
 * @param <T> the result type
 */
final class QuickPickPopup<T> {

    /** Style class of a result row that cannot be chosen. */
    static final String UNAVAILABLE_STYLE_CLASS = "quick-pick-unavailable";
    static final double DEFAULT_WIDTH = 560;
    static final double LIST_HEIGHT = 320;

    private final Function<String, List<T>> search;
    private final Consumer<? super T> onChosen;
    private final Predicate<? super T> choosable;
    private final Consumer<? super T> onUnchoosable;
    private final double width;
    private final Popup popup = new Popup();
    private final TextField field = new TextField();
    private final ListView<T> list = new ListView<>();

    private QuickPickPopup(Builder<T> builder) {
        this.search = Objects.requireNonNull(builder.search, "search");
        this.onChosen = Objects.requireNonNull(builder.onChosen, "onChosen");
        this.choosable = builder.choosable;
        this.onUnchoosable = builder.onUnchoosable;
        Function<? super T, String> text = Objects.requireNonNull(builder.text, "text");
        Function<? super T, String> accessibleText = builder.accessibleText != null ? builder.accessibleText : text;
        BiConsumer<ListCell<T>, T> renderer = builder.renderer;
        String unavailable = I18n.get("common.disabled");
        this.width = UiFontScaleSupport.scaleDimension(builder.width, true);

        field.setId(builder.fieldId);
        field.setPromptText(builder.prompt);
        field.setAccessibleText(builder.prompt);
        list.setId(builder.listId);
        list.setPrefHeight(UiFontScaleSupport.scaleDimension(LIST_HEIGHT, false));
        list.setPlaceholder(new Label(builder.empty));
        list.setCellFactory(view -> new ListCell<>() {
            @Override
            protected void updateItem(T item, boolean isEmpty) {
                super.updateItem(item, isEmpty);
                getStyleClass().remove(UNAVAILABLE_STYLE_CLASS);
                if (isEmpty || item == null) {
                    setText(null);
                    setGraphic(null);
                    setAccessibleText(null);
                    return;
                }
                boolean available = choosable.test(item);
                if (!available) {
                    getStyleClass().add(UNAVAILABLE_STYLE_CLASS);
                }
                if (renderer != null) {
                    renderer.accept(this, item);
                } else {
                    setText(text.apply(item));
                    setGraphic(null);
                }
                setAccessibleText(accessibleText(accessibleText.apply(item), available, unavailable));
            }
        });
        list.setOnMouseClicked(event -> {
            if (event.getButton() == MouseButton.PRIMARY && event.getClickCount() == 2) {
                choose();
            }
        });
        field.textProperty().addListener((obs, was, query) -> refresh(query));
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onFieldKey);
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
        root.setId(builder.rootId);
        root.setPadding(new Insets(8));
        root.setPrefWidth(width);
        root.getStyleClass().add("kortty-popup-surface");
        // A raw Popup inherits no author stylesheets: give the root the design's base sheet so
        // the surface class resolves colours the active user-agent stylesheet actually defines.
        AppDesignStyleSupport.registerApplicationBaseStyles(root);
        AppDesignStyleSupport.applyToParent(root);
        popup.getContent().add(root);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        // On the popup's scene, not its root: with no focus owner the owner window sends the key
        // events to the scene itself, and they must not slip past the firewall then either.
        popup.getScene().addEventHandler(KeyEvent.ANY,
            new QuickPickKeyFirewall(builder.passThrough, forward -> moveFocus(), this::hide));
    }

    static <T> Builder<T> builder(@NotNull String rootId, @NotNull String fieldId, @NotNull String listId) {
        return new Builder<>(rootId, fieldId, listId);
    }

    /** Shows the popup centred at the top of {@code anchor}, with an empty query. */
    void show(Node anchor) {
        if (anchor == null || anchor.getScene() == null || anchor.getScene().getWindow() == null) {
            return;
        }
        field.clear();
        refresh("");
        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
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

    ListView<T> list() {
        return list;
    }

    /**
     * Chooses the selected (or first) result: closes the popup and hands it on. A result that cannot
     * be chosen keeps the popup open and goes to the unchoosable callback instead.
     */
    void choose() {
        T chosen = list.getSelectionModel().getSelectedItem();
        if (chosen == null && !list.getItems().isEmpty()) {
            chosen = list.getItems().getFirst();
        }
        if (chosen == null) {
            return;
        }
        if (!choosable.test(chosen)) {
            onUnchoosable.accept(chosen);
            return;
        }
        hide();
        onChosen.accept(chosen);
    }

    /** The text a screen reader reads for a row: its own, plus the disabled label when it cannot be chosen. */
    static String accessibleText(String text, boolean available, String unavailableLabel) {
        String base = text != null ? text : "";
        if (available || unavailableLabel == null || unavailableLabel.isBlank()) {
            return base;
        }
        return base.isEmpty() ? unavailableLabel : base + ", " + unavailableLabel;
    }

    private void refresh(String query) {
        List<T> results = search.apply(query);
        list.getItems().setAll(results != null ? results : List.of());
        if (!list.getItems().isEmpty()) {
            list.getSelectionModel().select(0);
            list.scrollTo(0);
        }
    }

    private void onFieldKey(KeyEvent event) {
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

    /** Tab and Shift+Tab: between the field and a non-empty list (the firewall stops the scene's own traversal). */
    private void moveFocus() {
        if (field.isFocused() && !list.getItems().isEmpty()) {
            list.requestFocus();
        } else {
            field.requestFocus();
        }
    }

    /** Builds a {@link QuickPickPopup}; search, text and onChosen are required. */
    static final class Builder<T> {
        private final String rootId;
        private final String fieldId;
        private final String listId;
        private String prompt = "";
        private String empty = "";
        private Function<String, List<T>> search;
        private Consumer<? super T> onChosen;
        private Function<? super T, String> text;
        private Function<? super T, String> accessibleText;
        private BiConsumer<ListCell<T>, T> renderer;
        private Predicate<? super T> choosable = item -> true;
        private Consumer<? super T> onUnchoosable = item -> {
        };
        private Predicate<? super KeyEvent> passThrough = QuickPickKeyFirewall.NONE;
        private double width = DEFAULT_WIDTH;

        private Builder(String rootId, String fieldId, String listId) {
            this.rootId = Objects.requireNonNull(rootId, "rootId");
            this.fieldId = Objects.requireNonNull(fieldId, "fieldId");
            this.listId = Objects.requireNonNull(listId, "listId");
        }

        /** The field's prompt, which is also its accessible text. */
        Builder<T> prompt(@NotNull String prompt) {
            this.prompt = Objects.requireNonNull(prompt, "prompt");
            return this;
        }

        /** The list's placeholder when nothing matches. */
        Builder<T> empty(@NotNull String empty) {
            this.empty = Objects.requireNonNull(empty, "empty");
            return this;
        }

        /** The results for a query, best first; called with "" when the popup opens. */
        Builder<T> search(@NotNull Function<String, List<T>> search) {
            this.search = Objects.requireNonNull(search, "search");
            return this;
        }

        /** Runs with the chosen result after the popup has closed. */
        Builder<T> onChosen(@NotNull Consumer<? super T> onChosen) {
            this.onChosen = Objects.requireNonNull(onChosen, "onChosen");
            return this;
        }

        /** A row's text, and its accessible text unless {@link #accessibleText} is given. */
        Builder<T> text(@NotNull Function<? super T, String> text) {
            this.text = Objects.requireNonNull(text, "text");
            return this;
        }

        /** What a screen reader reads for a row, for rows whose renderer draws a graphic. */
        Builder<T> accessibleText(@NotNull Function<? super T, String> accessibleText) {
            this.accessibleText = Objects.requireNonNull(accessibleText, "accessibleText");
            return this;
        }

        /** Draws a row in place of the plain text; the popup sets the accessible text afterwards. */
        Builder<T> renderer(@NotNull BiConsumer<ListCell<T>, T> renderer) {
            this.renderer = Objects.requireNonNull(renderer, "renderer");
            return this;
        }

        /** Which results can be chosen; the others stay listed but unavailable. */
        Builder<T> choosable(@NotNull Predicate<? super T> choosable) {
            this.choosable = Objects.requireNonNull(choosable, "choosable");
            return this;
        }

        /** Runs, with the popup still open, when the user chooses a result that cannot be chosen. */
        Builder<T> onUnchoosable(@NotNull Consumer<? super T> onUnchoosable) {
            this.onUnchoosable = Objects.requireNonNull(onUnchoosable, "onUnchoosable");
            return this;
        }

        /** The key events that may carry on to the window behind; {@link QuickPickKeyFirewall#NONE} by default. */
        Builder<T> passThrough(@NotNull Predicate<? super KeyEvent> passThrough) {
            this.passThrough = Objects.requireNonNull(passThrough, "passThrough");
            return this;
        }

        /** The width before the UI font scale is applied. */
        Builder<T> width(double width) {
            this.width = width;
            return this;
        }

        QuickPickPopup<T> build() {
            return new QuickPickPopup<>(this);
        }
    }
}
