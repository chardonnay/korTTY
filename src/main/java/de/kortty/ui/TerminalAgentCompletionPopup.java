package de.kortty.ui;

import de.kortty.KorTTYApplication;
import javafx.collections.FXCollections;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.OverrunStyle;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.stage.Popup;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/**
 * Lightweight, themed popup that lists terminal AI-agent command variants or recent prompts beneath
 * the terminal caret. Navigable with ↑/↓, Enter selects, Esc/Tab dismisses. Each entry shows a
 * primary label (the value inserted on selection) and an optional secondary label (e.g. the
 * last-used date/time for history entries).
 *
 * <p>When shown for prompt history, the popup also lets the user prune the history: each row carries
 * a delete (✕) button, the {@code Delete} key removes the selected entry, and a footer offers a
 * two-step "Clear all" action. Deletions are reported through the {@code onDelete}/{@code onClearAll}
 * callbacks so the caller can persist them.
 */
final class TerminalAgentCompletionPopup {

    /** One selectable row. {@code value} is inserted on selection; {@code secondary} is informational. */
    record CompletionEntry(String value, String primary, String secondary) {
        static CompletionEntry of(String value) {
            return new CompletionEntry(value, value, null);
        }
    }

    private static final double MIN_WIDTH = 280;
    private static final double MAX_WIDTH = 1400;
    private static final double MIN_HEIGHT = 120;
    private static final double MAX_HEIGHT = 900;

    private final Popup popup = new Popup();
    private final ListView<CompletionEntry> listView = new ListView<>();
    private final Label footerHint = new Label();
    private final Button clearAllButton = new Button();
    private final Label resizeGrip = new Label("\u25E2");
    private final HBox footerBar;
    private final VBox body;
    private final StackPane container;
    private Consumer<String> onSelect;
    private Runnable onClose;
    private Consumer<String> onDelete;
    private Runnable onClearAll;
    private boolean clearAllArmed;

    // User-resizable history-popup geometry (persisted by the caller via onGeometryChanged).
    private double historyWidth = 460;
    private double historyHeight = 260;
    private BiConsumer<Double, Double> onGeometryChanged;
    private double dragStartWidth;
    private double dragStartHeight;
    private double dragStartScreenX;
    private double dragStartScreenY;

    private static final double ROW_HEIGHT = 28;

    TerminalAgentCompletionPopup() {
        listView.setFocusTraversable(true);
        listView.setPrefWidth(440);
        // Fixed row height makes the height computations exact (a ListView does not otherwise compute
        // its preferred height from its content) and keeps scrolling predictable.
        listView.setFixedCellSize(ROW_HEIGHT);
        // Let the bounded preferred height drive the box; ListView shows its own vertical scrollbar
        // automatically once the entries overflow that height.
        listView.setMaxHeight(Double.MAX_VALUE);
        listView.setCellFactory(list -> new CompletionCell());

        footerHint.getStyleClass().add("ai-agent-completion-hint");
        footerHint.setStyle("-fx-opacity: 0.65; -fx-font-size: 0.8462em;");
        clearAllButton.getStyleClass().add("ai-agent-completion-clear-all");
        clearAllButton.setFocusTraversable(false);
        clearAllButton.setStyle("-fx-font-size: 0.8462em; -fx-padding: 1 8 1 8;");
        clearAllButton.setOnAction(event -> {
            event.consume();
            onClearAllClicked();
        });
        resizeGrip.getStyleClass().add("ai-agent-completion-resize");
        resizeGrip.setCursor(Cursor.SE_RESIZE);
        resizeGrip.setStyle("-fx-opacity: 0.5; -fx-font-size: 0.8462em; -fx-padding: 0 0 0 2;");
        resizeGrip.setTooltip(new Tooltip(I18n.get("ai.agent.completion.resize")));
        resizeGrip.setOnMousePressed(this::beginResize);
        resizeGrip.setOnMouseDragged(this::dragResize);
        resizeGrip.setOnMouseReleased(this::endResize);

        Region footerSpacer = new Region();
        HBox.setHgrow(footerSpacer, Priority.ALWAYS);
        // The resize grip lives at the right end of the footer (bottom-right corner) so it never
        // overlaps the "Clear all" button, and it follows the footer's history-only visibility.
        footerBar = new HBox(8, footerHint, footerSpacer, clearAllButton, resizeGrip);
        footerBar.setAlignment(Pos.CENTER_LEFT);
        footerBar.setPadding(new Insets(4, 2, 0, 2));
        footerBar.setVisible(false);
        footerBar.setManaged(false);

        body = new VBox(4, listView, footerBar);
        container = new StackPane(body);
        container.getStyleClass().add("ai-agent-completion-popup");
        container.setStyle("-fx-background-color: derive(-fx-base, -8%); -fx-border-color: -fx-box-border; "
            + "-fx-border-width: 1; -fx-padding: 2;");
        applyTheme();
        popup.getContent().add(container);
        popup.setAutoHide(true);
        popup.setHideOnEscape(true);
        popup.setAutoFix(true);
        listView.setOnKeyPressed(this::handleKey);
        listView.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                chooseSelected();
            }
        });
        listView.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> disarmClearAll());
        popup.setOnHidden(event -> runClose());
    }

    private void applyTheme() {
        try {
            AppDesignStyleSupport.registerApplicationBaseStyles(container);
            ThemeCssSupport.ThemeColors colors = ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance());
            String dynamic = ThemeCssSupport.getDynamicStylesheetUrl(colors);
            if (dynamic != null && !container.getStylesheets().contains(dynamic)) {
                container.getStylesheets().add(dynamic);
            }
            // A raw Popup does not inherit the owner Scene's author stylesheets. Apply the selected
            // app design and UI scale directly to its content root; the global UAS still reaches it.
            AppDesignStyleSupport.applyToParent(container);
        } catch (Exception ignored) {
            // best-effort theming
        }
    }

    boolean isShowing() {
        return popup.isShowing();
    }

    /** Shows the popup without deletion affordances (e.g. for command-variant completion). */
    void show(Node anchor, List<CompletionEntry> items, Consumer<String> onSelect, Runnable onClose) {
        show(anchor, items, onSelect, onClose, null, null);
    }

    /**
     * Shows the popup. When {@code onDelete}/{@code onClearAll} are non-null, the rows gain delete
     * buttons, the {@code Delete} key removes the selected entry, and a "Clear all" footer is shown.
     */
    void show(Node anchor, List<CompletionEntry> items, Consumer<String> onSelect, Runnable onClose,
              Consumer<String> onDelete, Runnable onClearAll) {
        if (anchor == null || anchor.getScene() == null || anchor.getScene().getWindow() == null
            || items == null || items.isEmpty()) {
            return;
        }
        this.onSelect = onSelect;
        this.onClose = onClose;
        this.onDelete = onDelete;
        this.onClearAll = onClearAll;
        boolean deletionEnabled = onClearAll != null;
        disarmClearAll();
        footerBar.setVisible(deletionEnabled);
        footerBar.setManaged(deletionEnabled);
        if (deletionEnabled) {
            // History mode: user-resizable, restore the persisted size.
            footerHint.setText(I18n.get("ai.agent.completion.hint"));
            listView.setPrefWidth(historyWidth);
            listView.setPrefHeight(historyHeight);
        } else {
            // Command-variant mode: compact, sized to the few entries (ListView would otherwise use a
            // fixed multi-row default height), not resizable.
            listView.setPrefWidth(360);
            listView.setPrefHeight(Math.min(items.size(), 8) * ROW_HEIGHT + 4);
        }
        listView.setItems(FXCollections.observableArrayList(items));
        listView.getSelectionModel().select(0);
        // The popup instance is reused. Reconcile its direct author stylesheets immediately before
        // every show so a design change made while it was hidden cannot surface stale chrome.
        applyTheme();
        Bounds bounds = anchor.localToScreen(anchor.getBoundsInLocal());
        if (bounds != null) {
            popup.show(anchor.getScene().getWindow(), bounds.getMinX() + 16, bounds.getMaxY() - 60);
        } else {
            popup.show(anchor.getScene().getWindow());
        }
        listView.requestFocus();
    }

    void hide() {
        popup.hide();
    }

    /**
     * Sets the initial size for the next history-mode {@link #show}, and the callback used to persist
     * a user-chosen size after a resize drag. Width/height are clamped to the allowed range.
     */
    void setHistoryGeometry(double width, double height, BiConsumer<Double, Double> onChanged) {
        this.historyWidth = clamp(width, MIN_WIDTH, MAX_WIDTH);
        this.historyHeight = clamp(height, MIN_HEIGHT, MAX_HEIGHT);
        this.onGeometryChanged = onChanged;
    }

    private void beginResize(MouseEvent event) {
        dragStartScreenX = event.getScreenX();
        dragStartScreenY = event.getScreenY();
        double w = listView.getWidth();
        double h = listView.getHeight();
        dragStartWidth = w > 1 ? w : historyWidth;
        dragStartHeight = h > 1 ? h : historyHeight;
        event.consume();
    }

    private void dragResize(MouseEvent event) {
        historyWidth = clamp(dragStartWidth + (event.getScreenX() - dragStartScreenX), MIN_WIDTH, MAX_WIDTH);
        historyHeight = clamp(dragStartHeight + (event.getScreenY() - dragStartScreenY), MIN_HEIGHT, MAX_HEIGHT);
        listView.setPrefWidth(historyWidth);
        listView.setPrefHeight(historyHeight);
        event.consume();
    }

    private void endResize(MouseEvent event) {
        if (onGeometryChanged != null) {
            onGeometryChanged.accept(historyWidth, historyHeight);
        }
        event.consume();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }

    private void handleKey(KeyEvent event) {
        switch (event.getCode()) {
            case ENTER -> {
                chooseSelected();
                event.consume();
            }
            case ESCAPE, TAB -> {
                hide();
                event.consume();
            }
            case DELETE -> {
                // Only forward-delete removes an entry. Backspace is deliberately NOT mapped: it is the
                // most common editing key, and deleting history on every Backspace empties the list by
                // accident. The per-row ✕ button is the universal (and macOS-friendly) delete affordance.
                if (onDelete != null) {
                    deleteSelected();
                    event.consume();
                }
            }
            default -> {
                // UP/DOWN handled by the ListView itself.
            }
        }
    }

    private void chooseSelected() {
        CompletionEntry selected = listView.getSelectionModel().getSelectedItem();
        Consumer<String> handler = onSelect;
        hide();
        if (selected != null && handler != null) {
            handler.accept(selected.value());
        }
    }

    private void deleteSelected() {
        CompletionEntry selected = listView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            deleteEntry(selected);
        }
    }

    /** Removes a single entry from the list, persists via {@code onDelete}, and hides if now empty. */
    private void deleteEntry(CompletionEntry entry) {
        if (entry == null) {
            return;
        }
        if (onDelete != null) {
            try {
                onDelete.accept(entry.value());
            } catch (Exception ignored) {
                // persistence is best-effort; still update the visible list
            }
        }
        int index = listView.getItems().indexOf(entry);
        listView.getItems().remove(entry);
        if (listView.getItems().isEmpty()) {
            hide();
            return;
        }
        int next = Math.min(index, listView.getItems().size() - 1);
        if (next >= 0) {
            listView.getSelectionModel().select(next);
        }
        listView.requestFocus();
    }

    private void onClearAllClicked() {
        if (!clearAllArmed) {
            clearAllArmed = true;
            clearAllButton.setText(I18n.get("ai.agent.completion.clearAll.confirm"));
            clearAllButton.setStyle("-fx-font-size: 0.8462em; -fx-padding: 1 8 1 8; "
                + "-fx-text-fill: #f87171; -fx-font-weight: bold;");
            return;
        }
        Runnable clear = onClearAll;
        hide();
        if (clear != null) {
            try {
                clear.run();
            } catch (Exception ignored) {
                // best-effort
            }
        }
    }

    private void disarmClearAll() {
        if (clearAllArmed) {
            clearAllArmed = false;
        }
        clearAllButton.setText(I18n.get("ai.agent.completion.clearAll"));
        clearAllButton.setStyle("-fx-font-size: 0.8462em; -fx-padding: 1 8 1 8;");
    }

    private void runClose() {
        Runnable close = onClose;
        onClose = null;
        onSelect = null;
        onDelete = null;
        onClearAll = null;
        disarmClearAll();
        if (close != null) {
            close.run();
        }
    }

    /**
     * Renders the primary value (ellipsized so it can never hide the timestamp), the optional
     * secondary date/time on the right, and—when deletion is enabled—a trailing delete button.
     */
    private final class CompletionCell extends ListCell<CompletionEntry> {
        private final Label primaryLabel = new Label();
        private final Label secondaryLabel = new Label();
        private final Button deleteButton = new Button("\u2715");
        private final Region gap = new Region();
        private final HBox row;

        CompletionCell() {
            primaryLabel.setMaxWidth(Double.MAX_VALUE);
            primaryLabel.setTextOverrun(OverrunStyle.ELLIPSIS);
            HBox.setHgrow(primaryLabel, Priority.ALWAYS);
            secondaryLabel.getStyleClass().add("ai-agent-completion-secondary");
            secondaryLabel.setStyle("-fx-opacity: 0.65;");
            // Never let a long prompt squeeze the timestamp out of view.
            secondaryLabel.setMinWidth(Region.USE_PREF_SIZE);
            gap.setMinWidth(8);
            deleteButton.getStyleClass().add("ai-agent-completion-delete");
            deleteButton.setFocusTraversable(false);
            deleteButton.setMinWidth(Region.USE_PREF_SIZE);
            deleteButton.setStyle("-fx-font-size: 0.7692em; -fx-padding: 0 5 0 5; -fx-opacity: 0.7; "
                + "-fx-background-radius: 4;");
            deleteButton.setTooltip(new Tooltip(I18n.get("ai.agent.completion.delete")));
            deleteButton.setOnAction(event -> {
                event.consume();
                // Anchor selection on the clicked row first so the mouse and keyboard delete paths
                // re-position the highlight identically after removal.
                int idx = getIndex();
                if (idx >= 0) {
                    getListView().getSelectionModel().select(idx);
                }
                deleteEntry(getItem());
            });
            row = new HBox(8, primaryLabel, secondaryLabel, gap, deleteButton);
            row.setAlignment(Pos.CENTER_LEFT);
        }

        @Override
        protected void updateItem(CompletionEntry item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            primaryLabel.setText(item.primary());
            boolean hasSecondary = item.secondary() != null && !item.secondary().isBlank();
            secondaryLabel.setText(hasSecondary ? item.secondary() : "");
            secondaryLabel.setVisible(hasSecondary);
            secondaryLabel.setManaged(hasSecondary);
            boolean deletable = onDelete != null;
            deleteButton.setVisible(deletable);
            deleteButton.setManaged(deletable);
            gap.setVisible(deletable);
            gap.setManaged(deletable);
            setText(null);
            setGraphic(row);
        }
    }
}
