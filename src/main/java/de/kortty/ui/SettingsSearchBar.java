package de.kortty.ui;

import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.geometry.Bounds;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.Control;
import javafx.scene.control.Label;
import javafx.scene.control.Labeled;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextField;
import javafx.scene.control.TitledPane;
import javafx.scene.control.Tooltip;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * The search line at the top of the settings dialog. Every keystroke looks through the labels,
 * check boxes, buttons and section titles of all pages — also the pages not opened yet, whose
 * already built content {@link LazyTabContent#peek} hands out without attaching it — and lists the
 * matches under the field. Choosing one (click, or ↑/↓ and Enter) selects its page, opens the
 * nested tab or collapsed section it sits in, scrolls it into view, colours it and the controls on
 * its row, and focuses the control so it can be changed straight away.
 *
 * <p>Only what is visible counts: an option hidden for the current provider or mode is not offered.
 * Enter never reaches the dialog's default button from the field, and Esc first clears the search
 * before it may close the dialog.</p>
 */
final class SettingsSearchBar {

    /** Style class of a highlighted label, check box or button. */
    static final String HIT_STYLE_CLASS = "settings-search-hit";
    /** Style class of a highlighted input control (field, combo box, spinner). */
    static final String HIT_CONTROL_STYLE_CLASS = "settings-search-hit-control";
    private static final Duration HIGHLIGHT_DURATION = Duration.seconds(12);
    private static final double RESULTS_WIDTH = 640;
    private static final double RESULTS_MAX_HEIGHT = 380;
    private static final double RESULT_ROW_HEIGHT = 40;

    /** Where a result leads: the page, the node on it (none for the page itself) and the containers in between. */
    record Target(Tab section, Node node, List<Object> path) {
    }

    private final TabPane tabPane;
    private final TextField field = new TextField();
    private final Button clearButton = new Button("✕");
    private final StackPane fieldBox;
    private final ListView<SettingsSearchMatcher.Match<Target>> results = new ListView<>();
    private final StackPane host;
    private final List<Node> highlighted = new ArrayList<>();
    private final PauseTransition highlightTimer = new PauseTransition(HIGHLIGHT_DURATION);

    /**
     * @param tabPane the dialog's section tabs
     * @param body    what the results list floats over: the section navigation and the tabs
     */
    SettingsSearchBar(TabPane tabPane, Node body) {
        this.tabPane = Objects.requireNonNull(tabPane, "tabPane");
        String prompt = I18n.get("settings.nav.search.prompt");
        field.setId("settings-search-field");
        field.setPromptText(prompt);
        field.setAccessibleText(prompt);
        field.setTooltip(new Tooltip(I18n.get("settings.nav.search.tooltip")));
        field.setMaxWidth(UiFontScaleSupport.scaleDimension(RESULTS_WIDTH, true));
        field.getStyleClass().add("settings-search-field");

        // ✕ inside the field's right end: empties the search in one click, shown only while there is text.
        String clear = I18n.get("settings.nav.search.clear");
        clearButton.setId("settings-search-clear");
        clearButton.getStyleClass().add("settings-search-clear");
        clearButton.setTooltip(new Tooltip(clear));
        clearButton.setAccessibleText(clear);
        clearButton.setFocusTraversable(false);
        clearButton.visibleProperty().bind(field.textProperty().isNotEmpty());
        clearButton.managedProperty().bind(clearButton.visibleProperty());
        clearButton.setOnAction(event -> {
            field.clear();
            field.requestFocus();
        });
        fieldBox = new StackPane(field, clearButton);
        StackPane.setAlignment(clearButton, Pos.CENTER_RIGHT);
        StackPane.setMargin(clearButton, new Insets(0, 4, 0, 0));
        fieldBox.setMaxWidth(UiFontScaleSupport.scaleDimension(RESULTS_WIDTH, true));

        results.setId("settings-search-results");
        results.getStyleClass().add("settings-search-results");
        results.setFocusTraversable(false);
        results.setPlaceholder(new Label(I18n.get("settings.nav.search.empty")));
        results.setMaxWidth(UiFontScaleSupport.scaleDimension(RESULTS_WIDTH, true));
        results.setCellFactory(view -> new ResultCell());
        results.setVisible(false);
        results.setManaged(false);
        StackPane.setAlignment(results, Pos.TOP_CENTER);

        host = new StackPane(body, results);
        // The list is unmanaged so it never pushes the tabs around; size and place it by hand.
        host.layoutBoundsProperty().addListener((obs, was, now) -> placeResults());
        results.getItems().addListener((javafx.collections.ListChangeListener<SettingsSearchMatcher.Match<Target>>) change -> placeResults());

        field.textProperty().addListener((obs, was, query) -> refresh(query));
        field.focusedProperty().addListener((obs, was, focused) -> {
            if (focused) {
                refresh(field.getText());
            } else {
                // A click on a result moves the focus away first; let the click land before hiding.
                Platform.runLater(() -> {
                    if (!field.isFocused()) {
                        hideResults();
                    }
                });
            }
        });
        field.addEventFilter(KeyEvent.KEY_PRESSED, this::onFieldKey);
        highlightTimer.setOnFinished(event -> clearHighlight());
        // Cmd+F / Ctrl+F anywhere in the dialog jumps to the search line.
        host.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (FOCUS_SEARCH.match(event)) {
                event.consume();
                field.requestFocus();
                field.selectAll();
            }
        });
    }

    private static final KeyCombination FOCUS_SEARCH = KeyCombination.keyCombination("Shortcut+F");

    /** The search line with its clear button, for the top of the dialog. */
    StackPane node() {
        return fieldBox;
    }

    TextField field() {
        return field;
    }

    Button clearButton() {
        return clearButton;
    }

    /** The body with the results list floating over it. */
    StackPane host() {
        return host;
    }

    ListView<SettingsSearchMatcher.Match<Target>> results() {
        return results;
    }

    /** The current matches for {@code query}, best first. */
    List<SettingsSearchMatcher.Match<Target>> search(String query) {
        return SettingsSearchMatcher.search(candidates(), query);
    }

    /** Every searchable text on every page, in tab and row order. */
    List<SettingsSearchMatcher.Candidate<Target>> candidates() {
        List<SettingsSearchMatcher.Candidate<Target>> candidates = new ArrayList<>();
        for (Tab tab : tabPane.getTabs()) {
            String title = tab.getText();
            candidates.add(new SettingsSearchMatcher.Candidate<>(title, "", title, true, new Target(tab, null, List.of())));
            Node content = LazyTabContent.peek(tab);
            if (content != null) {
                collect(content, tab, title, new ArrayList<>(), candidates);
            }
        }
        return candidates;
    }

    private static void collect(Node node, Tab section, String context, List<Object> path,
                                List<SettingsSearchMatcher.Candidate<Target>> into) {
        if (node == null || !node.isVisible()) {
            return;
        }
        if (node instanceof ScrollPane scroll) {
            path.add(scroll);
            collect(scroll.getContent(), section, context, path, into);
            path.removeLast();
        } else if (node instanceof TitledPane titled) {
            add(titled, titled.getText(), section, context, path, into);
            path.add(titled);
            collect(titled.getContent(), section, context, path, into);
            path.removeLast();
        } else if (node instanceof Accordion accordion) {
            for (TitledPane pane : accordion.getPanes()) {
                collect(pane, section, context, path, into);
            }
        } else if (node instanceof SplitPane split) {
            for (Node item : split.getItems()) {
                collect(item, section, context, path, into);
            }
        } else if (node instanceof TabPane nested) {
            for (Tab tab : nested.getTabs()) {
                path.add(tab);
                // A nested tab's title is an option of its own: choosing it opens that tab.
                into.add(new SettingsSearchMatcher.Candidate<>(tab.getText(), "", context, false,
                    new Target(section, null, List.copyOf(path))));
                collect(LazyTabContent.peek(tab), section, context, path, into);
                path.removeLast();
            }
        } else if (node instanceof Labeled labeled) {
            add(labeled, labeled.getText(), section, context, path, into);
        } else if (node instanceof Control) {
            // Fields, lists and tables: their text is user data, not an option name.
        } else if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                collect(child, section, context, path, into);
            }
        }
    }

    private static void add(Control control, String text, Tab section, String context, List<Object> path,
                            List<SettingsSearchMatcher.Candidate<Target>> into) {
        String label = displayText(text);
        if (label.length() < SettingsSearchMatcher.MIN_QUERY_LENGTH) {
            return;
        }
        Tooltip tooltip = control.getTooltip();
        String detail = tooltip != null && tooltip.getText() != null ? tooltip.getText() : "";
        into.add(new SettingsSearchMatcher.Candidate<>(label, detail, context, false,
            new Target(section, control, List.copyOf(path))));
    }

    /** A label's text as a result: one line, without the trailing colon of a form label. */
    static String displayText(String text) {
        if (text == null) {
            return "";
        }
        String line = text.strip().replaceAll("\\s+", " ");
        while (line.endsWith(":") || line.endsWith("：")) {
            line = line.substring(0, line.length() - 1).strip();
        }
        return line;
    }

    private void refresh(String query) {
        clearHighlight();
        String normalized = SettingsSearchMatcher.normalize(query);
        if (normalized.length() < SettingsSearchMatcher.MIN_QUERY_LENGTH) {
            results.getItems().clear();
            hideResults();
            return;
        }
        results.getItems().setAll(search(query));
        if (!results.getItems().isEmpty()) {
            results.getSelectionModel().select(0);
            results.scrollTo(0);
        }
        results.setVisible(true);
        placeResults();
        // Again once the cells are laid out, with their real height.
        Platform.runLater(this::placeResults);
    }

    private void hideResults() {
        results.setVisible(false);
    }

    private void placeResults() {
        double width = Math.min(UiFontScaleSupport.scaleDimension(RESULTS_WIDTH, true), host.getWidth() - 16);
        int rows = Math.max(1, results.getItems().size());
        // Rows are 24 px in Modena but up to 49 px in AtlantaFX: measure a laid-out cell when there is one.
        double rowHeight = results.lookup(".list-cell") instanceof Region cell && cell.getHeight() > 0
            ? cell.getHeight() : UiFontScaleSupport.scaleDimension(RESULT_ROW_HEIGHT, false);
        double height = Math.min(UiFontScaleSupport.scaleDimension(RESULTS_MAX_HEIGHT, false), rows * rowHeight + 6);
        results.resizeRelocate(Math.max(0, (host.getWidth() - width) / 2), 0, Math.max(0, width), height);
    }

    private void onFieldKey(KeyEvent event) {
        KeyCode code = event.getCode();
        switch (code) {
            case DOWN, UP -> {
                event.consume();
                if (!results.isVisible()) {
                    refresh(field.getText());
                }
                move(code == KeyCode.DOWN ? 1 : -1);
            }
            case ENTER -> {
                // Never the dialog's Save button: Enter in the search line only ever picks a result.
                event.consume();
                SettingsSearchMatcher.Match<Target> chosen = results.getSelectionModel().getSelectedItem();
                if (chosen == null && !results.getItems().isEmpty()) {
                    chosen = results.getItems().getFirst();
                }
                if (chosen != null && results.isVisible()) {
                    choose(chosen);
                }
            }
            case ESCAPE -> {
                if (results.isVisible() || !field.getText().isEmpty()) {
                    event.consume();
                    hideResults();
                    field.clear();
                }
            }
            default -> {
            }
        }
    }

    private void move(int delta) {
        int size = results.getItems().size();
        if (size == 0) {
            return;
        }
        int index = results.getSelectionModel().getSelectedIndex();
        int next = Math.max(0, Math.min(size - 1, (index < 0 ? 0 : index) + delta));
        results.getSelectionModel().select(next);
        results.scrollTo(next);
    }

    /** Shows the result's page and option, coloured and focused. */
    void choose(SettingsSearchMatcher.Match<Target> match) {
        hideResults();
        Target target = match.candidate().target();
        reveal(target);
    }

    void reveal(Target target) {
        clearHighlight();
        tabPane.getSelectionModel().select(target.section());
        for (Object step : target.path()) {
            if (step instanceof Tab nested && nested.getTabPane() != null) {
                nested.getTabPane().getSelectionModel().select(nested);
            } else if (step instanceof TitledPane titled) {
                titled.setExpanded(true);
            }
        }
        Node node = target.node();
        if (node == null) {
            return;
        }
        // The page is attached on its selection; style and lay it out now so it can be scrolled.
        layoutNow();
        scrollIntoView(target);
        highlight(node);
        // Once more after the pulse: a page shown for the first time may still change its height.
        Platform.runLater(() -> scrollIntoView(target));
    }

    private void layoutNow() {
        if (tabPane.getScene() != null && tabPane.getScene().getRoot() != null) {
            Parent root = tabPane.getScene().getRoot();
            root.applyCss();
            root.layout();
        }
    }

    private static void scrollIntoView(Target target) {
        for (Object step : target.path()) {
            if (step instanceof ScrollPane scroll) {
                scrollTo(scroll, target.node());
            }
        }
    }

    /** Scrolls {@code scroll} so that {@code node} sits in the upper third of its viewport. */
    static void scrollTo(ScrollPane scroll, Node node) {
        Node content = scroll.getContent();
        if (content == null || node == null || node.getScene() == null) {
            return;
        }
        Bounds inScene = node.localToScene(node.getBoundsInLocal());
        Bounds inContent = content.sceneToLocal(inScene);
        if (inContent == null) {
            return;
        }
        double contentHeight = content.getLayoutBounds().getHeight();
        double viewportHeight = scroll.getViewportBounds().getHeight();
        if (contentHeight <= viewportHeight || viewportHeight <= 0) {
            return;
        }
        double y = Math.max(0, inContent.getMinY() - viewportHeight / 3);
        double fraction = Math.min(1, y / (contentHeight - viewportHeight));
        scroll.setVvalue(scroll.getVmin() + (scroll.getVmax() - scroll.getVmin()) * fraction);
    }

    private void highlight(Node node) {
        Set<Node> targets = rowOf(node);
        Node focusTarget = null;
        for (Node target : targets) {
            target.getStyleClass().add(target instanceof Labeled ? HIT_STYLE_CLASS : HIT_CONTROL_STYLE_CLASS);
            highlighted.add(target);
            if (focusTarget == null && !(target instanceof Label) && target.isFocusTraversable() && !target.isDisabled()) {
                focusTarget = target;
            }
        }
        if (focusTarget != null) {
            focusTarget.requestFocus();
        }
        highlightTimer.playFromStart();
    }

    /**
     * The node and the controls that belong to it: the other cells of its grid row (a form label and
     * its field), the children of the box it sits in when it is the box's label, and the node a
     * label is {@linkplain Label#getLabelFor() for}.
     */
    static Set<Node> rowOf(Node node) {
        Set<Node> row = new LinkedHashSet<>();
        row.add(node);
        if (node instanceof Label label && label.getLabelFor() != null) {
            row.add(label.getLabelFor());
        }
        Parent parent = node.getParent();
        if (parent instanceof GridPane grid) {
            int rowIndex = rowIndex(node);
            for (Node child : grid.getChildren()) {
                if (child != node && child.isVisible() && rowIndex(child) == rowIndex) {
                    addLeaves(child, row);
                }
            }
        } else if (parent instanceof HBox box && node instanceof Label && box.getChildren().size() <= 4) {
            for (Node child : box.getChildren()) {
                if (child != node && child.isVisible()) {
                    addLeaves(child, row);
                }
            }
        }
        return row;
    }

    private static int rowIndex(Node node) {
        Integer index = GridPane.getRowIndex(node);
        return index != null ? index : 0;
    }

    /** A control itself, or the controls of a box holding several (a spinner and its unit label). */
    private static void addLeaves(Node node, Set<Node> into) {
        if (node instanceof Control) {
            into.add(node);
            return;
        }
        if (!(node instanceof Parent parent)) {
            return;
        }
        for (Node child : parent.getChildrenUnmodifiable()) {
            if (child.isVisible()) {
                addLeaves(child, into);
            }
        }
    }

    void clearHighlight() {
        highlightTimer.stop();
        for (Node node : highlighted) {
            node.getStyleClass().removeAll(HIT_STYLE_CLASS, HIT_CONTROL_STYLE_CLASS);
        }
        highlighted.clear();
    }

    List<Node> highlighted() {
        return List.copyOf(highlighted);
    }

    /** A result row: the option's name, and right-aligned and muted the page it is on. */
    private final class ResultCell extends ListCell<SettingsSearchMatcher.Match<Target>> {
        private final Label name = new Label();
        private final Label context = new Label();
        private final HBox row;

        ResultCell() {
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            name.textFillProperty().bind(textFillProperty());
            name.setMinWidth(0);
            context.textFillProperty().bind(textFillProperty());
            context.setStyle(MutedTextStyle.HINT);
            context.setMinWidth(Region.USE_PREF_SIZE);
            row = new HBox(12, name, spacer, context);
            row.setAlignment(Pos.CENTER_LEFT);
            // As wide as the cell, never wider: a long description is cut off, the page name stays visible.
            row.setMinWidth(0);
            row.prefWidthProperty().bind(widthProperty().subtract(snappedLeftInset() + snappedRightInset() + 2));
            row.maxWidthProperty().bind(row.prefWidthProperty());
            setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && getItem() != null) {
                    event.consume();
                    choose(getItem());
                }
            });
        }

        /** No width of its own: the list then sizes every cell to its viewport instead of the longest text. */
        @Override
        protected double computePrefWidth(double height) {
            return 0;
        }

        @Override
        protected void updateItem(SettingsSearchMatcher.Match<Target> item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setAccessibleText(null);
                return;
            }
            SettingsSearchMatcher.Candidate<Target> candidate = item.candidate();
            name.setText(candidate.label());
            name.setStyle(candidate.tab() ? "-fx-font-weight: bold;" : "");
            context.setText(candidate.tab() ? I18n.get("settings.nav.search.page") : candidate.context());
            setText(null);
            setGraphic(row);
            setAccessibleText(candidate.label() + ", " + context.getText());
        }
    }
}
