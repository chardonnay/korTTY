package de.kortty.ui;

import de.kortty.core.SnippetPlaceholderResolver;
import de.kortty.core.SnippetPlaceholderScanner;
import de.kortty.core.SnippetPlaceholderScanner.Placeholder;
import de.kortty.core.SnippetStructureScanner;
import de.kortty.core.SnippetStructureScanner.Analysis;
import de.kortty.core.SnippetStructureScanner.Container;
import de.kortty.core.SnippetStructureScanner.NodeKind;
import de.kortty.core.SnippetStructureScanner.OutlineNode;
import de.kortty.core.SnippetStructureScanner.Variable;
import de.kortty.core.SnippetStructureScanner.VariableKind;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.collections.transformation.SortedList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * The snippet editor's structure panel, left of the code: a class browser (classes, modules,
 * methods, functions — headings for Markdown and AsciiDoc), the variables the code defines with
 * their kind, scope, line and number of uses, and the korTTY placeholders it contains, grouped the
 * way a copy or send treats them. A click selects the name in the editor (which scrolls to it and
 * highlights its other occurrences); a double click or Enter also moves the focus to the code.
 *
 * <p>The panel only reads: it scans the text on a background thread ({@link SnippetStructureScanner})
 * a moment after the last change, only while it is shown, and keeps expanded nodes and the
 * selection across refreshes. While the caret moves it selects the innermost outline entry around
 * it, without jumping anywhere.
 */
final class SnippetStructurePanel extends VBox {

    /** Default and smallest width; the user can drag it wider (see {@link SnippetStructureRow}). */
    static final double DEFAULT_WIDTH = 340;
    static final double MIN_WIDTH = 160;

    private static final Duration SCAN_DELAY = Duration.millis(350);
    private static final Duration CARET_DELAY = Duration.millis(150);
    private static final ExecutorService SCANNER = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "snippet-structure-scan");
        thread.setDaemon(true);
        return thread;
    });

    /** What the panel reads from, and where a jump goes: the editor that owns it. */
    interface Source {
        String text();

        String language();

        int caretOffset();

        /** Selects {@code [start, end)} in the editor; {@code focus} also moves the keyboard focus there. */
        void select(int start, int end, boolean focus);

        /** Whether the code is on screen (not replaced by a change review). */
        boolean isCodeShown();
    }

    private final Source source;
    private final Function<String, String> placeholderValues;
    private final SnippetPlaceholderResolver.Declarations placeholderDeclarations;

    private final TextField filterField = new TextField();
    private final Label statusLabel = new Label();
    private final Label outlineTitle = sectionTitle("snippets.structure.outline.code");
    private final TreeView<OutlineNode> outlineTree = new TreeView<>(new TreeItem<>());
    private final Label outlineEmpty = emptyLabel();
    private final ObservableList<Variable> variableItems = FXCollections.observableArrayList();
    private final FilteredList<Variable> filteredVariables = new FilteredList<>(variableItems);
    private final TableView<Variable> variableTable = new TableView<>();
    private final Label variablesEmpty = emptyLabel();
    private final TreeView<Object> placeholderTree = new TreeView<>(new TreeItem<>());
    private final Label placeholdersEmpty = emptyLabel();

    private final PauseTransition scanDelay = new PauseTransition(SCAN_DELAY);
    private final PauseTransition caretDelay = new PauseTransition(CARET_DELAY);
    private final Set<String> collapsedOutline = new HashSet<>();
    private final Set<SnippetPlaceholderScanner.Status> collapsedGroups =
        new HashSet<>(Set.of(SnippetPlaceholderScanner.Status.UNDECLARED));

    private Analysis analysis = new Analysis("plain", SnippetStructureScanner.Status.OK, List.of(), List.of(), false);
    private List<Placeholder> placeholders = List.of();
    private boolean active;
    private boolean stale = true;
    private boolean rebuilding;
    private long generation;
    private boolean disposed;

    SnippetStructurePanel(Source source, SnippetPlaceholderResolver.Declarations declarations,
                          Function<String, String> values) {
        this.source = source;
        this.placeholderDeclarations = declarations;
        this.placeholderValues = values;
        setId("snippet-structure-panel");
        getStyleClass().add("snippet-structure-panel");
        setSpacing(6);
        setPadding(new Insets(0, 2, 0, 0));
        setMinWidth(MIN_WIDTH);
        setPrefWidth(DEFAULT_WIDTH);
        setMinHeight(0);

        filterField.setPromptText(I18n.get("snippets.structure.filter.prompt"));
        filterField.setId("snippet-structure-filter");
        filterField.textProperty().addListener((obs, oldValue, newValue) -> applyFilter());
        filterField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE && !filterField.getText().isEmpty()) {
                filterField.clear();
                event.consume();
            }
        });

        statusLabel.setWrapText(true);
        statusLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 0.9231em;");
        setShown(statusLabel, false);

        configureOutline();
        configureVariables();
        configurePlaceholders();

        VBox outlineBox = section(outlineTitle, new StackPane(outlineTree, outlineEmpty));
        VBox variableBox = section(sectionTitle("snippets.structure.variables"), new StackPane(variableTable, variablesEmpty));
        VBox placeholderBox = section(sectionTitle("snippets.structure.placeholders"),
            new StackPane(placeholderTree, placeholdersEmpty));
        SplitPane sections = new SplitPane(outlineBox, variableBox, placeholderBox);
        sections.setOrientation(Orientation.VERTICAL);
        sections.setDividerPositions(0.34, 0.74);
        sections.setMinHeight(0);
        sections.setStyle("-fx-background-color: transparent; -fx-padding: 0;");
        VBox.setVgrow(sections, Priority.ALWAYS);
        getChildren().addAll(filterField, statusLabel, sections);

        scanDelay.setOnFinished(event -> refreshNow());
        caretDelay.setOnFinished(event -> followCaret());
    }

    // ------------------------------------------------------------------ lifecycle

    /** Shown or hidden; a panel that was hidden while the text changed scans when it comes back. */
    void setActive(boolean shown) {
        if (active == shown) {
            return;
        }
        active = shown;
        if (shown && stale) {
            refreshNow();
        } else if (!shown) {
            scanDelay.stop();
            caretDelay.stop();
        }
    }

    boolean isActive() {
        return active;
    }

    /** The text changed: rescan a moment after the last change (only while shown). */
    void contentChanged() {
        stale = true;
        if (active && !disposed) {
            scanDelay.playFromStart();
        }
    }

    /** The language changed: the rules change too, so rescan at once. */
    void languageChanged() {
        stale = true;
        if (active && !disposed) {
            refreshNow();
        }
    }

    /** The caret moved: select the outline entry around it a moment later. */
    void caretMoved() {
        if (active && !disposed && !stale) {
            caretDelay.playFromStart();
        }
    }

    /** The code came back after a change review: catch up on what changed meanwhile. */
    void codeShown() {
        if (active && stale) {
            refreshNow();
        }
    }

    void dispose() {
        disposed = true;
        generation++;
        scanDelay.stop();
        caretDelay.stop();
    }

    /** Scans the current text now (on the background thread) and shows the result when it is still current. */
    void refreshNow() {
        scanDelay.stop();
        if (disposed || !active) {
            return;
        }
        if (!source.isCodeShown()) {
            stale = true;
            return;
        }
        stale = false;
        String text = source.text() != null ? source.text() : "";
        String language = source.language();
        long scan = ++generation;
        SCANNER.execute(() -> {
            Analysis result;
            try {
                result = SnippetStructureScanner.analyze(language, text);
            } catch (RuntimeException failure) {
                result = new Analysis(language, SnippetStructureScanner.Status.UNSUPPORTED, List.of(), List.of(), false);
            }
            Analysis scanned = result;
            Platform.runLater(() -> {
                if (scan == generation && !disposed) {
                    show(scanned, SnippetPlaceholderScanner.scan(text, placeholderDeclarations, placeholderValues));
                }
            });
        });
    }

    // ------------------------------------------------------------------ result

    /** Shows a scan result (tests call it directly). */
    void show(Analysis result, List<Placeholder> found) {
        analysis = result != null ? result : analysis;
        placeholders = found != null ? List.copyOf(found) : List.of();
        outlineTitle.setText(I18n.get(outlineTitleKey(analysis.language())));
        String message = switch (analysis.status()) {
            case UNSUPPORTED -> I18n.get("snippets.structure.unsupported", languageName(analysis.language()));
            case TOO_LARGE -> I18n.get("snippets.structure.tooLarge");
            case OK -> analysis.truncated()
                ? I18n.get("snippets.structure.truncated", SnippetStructureScanner.MAX_ENTRIES) : null;
        };
        statusLabel.setText(message != null ? message : "");
        setShown(statusLabel, message != null);
        boolean markup = "markdown".equals(analysis.language()) || "asciidoctor".equals(analysis.language());
        variablesEmpty.setText(I18n.get(markup ? "snippets.structure.variables.none" : "snippets.structure.variables.empty"));
        String selectedVariable = keyOf(variableTable.getSelectionModel().getSelectedItem());
        variableItems.setAll(analysis.variables());
        reselectVariable(selectedVariable);
        applyFilter();
        followCaret();
    }

    Analysis analysis() {
        return analysis;
    }

    List<Placeholder> placeholders() {
        return placeholders;
    }

    TreeView<OutlineNode> outlineTree() {
        return outlineTree;
    }

    TableView<Variable> variableTable() {
        return variableTable;
    }

    TreeView<Object> placeholderTree() {
        return placeholderTree;
    }

    TextField filterField() {
        return filterField;
    }

    private void applyFilter() {
        String filter = filterField.getText() != null ? filterField.getText().strip().toLowerCase(Locale.ROOT) : "";
        filteredVariables.setPredicate(variable -> filter.isEmpty()
            || variable.name().toLowerCase(Locale.ROOT).contains(filter));
        rebuildOutline(filter);
        rebuildPlaceholders(filter);
        setShown(variablesEmpty, filteredVariables.isEmpty());
    }

    // ------------------------------------------------------------------ outline

    private void configureOutline() {
        outlineTree.setId("snippet-structure-outline");
        outlineTree.setShowRoot(false);
        outlineTree.setMinHeight(0);
        outlineTree.setCellFactory(tree -> new OutlineCell());
        outlineTree.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                TreeItem<OutlineNode> item = outlineTree.getSelectionModel().getSelectedItem();
                if (item != null && item.getValue() != null) {
                    jump(item.getValue().nameStart(), item.getValue().nameEnd(), true);
                    event.consume();
                }
            }
        });
    }

    private void rebuildOutline(String filter) {
        rebuilding = true;
        try {
            TreeItem<OutlineNode> selected = outlineTree.getSelectionModel().getSelectedItem();
            String selectedKey = selected != null && selected.getValue() != null ? outlineKey(selected.getValue()) : null;
            TreeItem<OutlineNode> root = new TreeItem<>();
            Map<String, Integer> seen = new HashMap<>();
            addOutline(root, analysis.outline(), filter, seen);
            root.setExpanded(true);
            outlineTree.setRoot(root);
            if (selectedKey != null) {
                TreeItem<OutlineNode> again = findOutline(root, selectedKey);
                if (again != null) {
                    outlineTree.getSelectionModel().select(again);
                }
            }
            outlineEmpty.setText(I18n.get(filter.isEmpty() ? "snippets.structure.outline.empty"
                : "snippets.structure.filter.noMatch"));
            setShown(outlineEmpty, root.getChildren().isEmpty() && analysis.status() == SnippetStructureScanner.Status.OK);
        } finally {
            rebuilding = false;
        }
    }

    /** Adds the nodes that match {@code filter} (or have a matching descendant); returns whether any did. */
    private boolean addOutline(TreeItem<OutlineNode> parent, List<OutlineNode> nodes, String filter,
                               Map<String, Integer> seen) {
        boolean any = false;
        for (OutlineNode node : nodes) {
            TreeItem<OutlineNode> item = new TreeItem<>(node);
            boolean childMatch = addOutline(item, node.children(), filter, seen);
            boolean match = filter.isEmpty() || node.name().toLowerCase(Locale.ROOT).contains(filter);
            if (!match && !childMatch) {
                continue;
            }
            String key = outlineKey(node);
            item.setExpanded(!filter.isEmpty() || !collapsedOutline.contains(key));
            item.expandedProperty().addListener((obs, was, expanded) -> {
                if (!rebuilding && filter.isEmpty()) {
                    if (expanded) {
                        collapsedOutline.remove(key);
                    } else {
                        collapsedOutline.add(key);
                    }
                }
            });
            parent.getChildren().add(item);
            any = true;
        }
        return any;
    }

    private static TreeItem<OutlineNode> findOutline(TreeItem<OutlineNode> item, String key) {
        for (TreeItem<OutlineNode> child : item.getChildren()) {
            if (child.getValue() != null && outlineKey(child.getValue()).equals(key)) {
                return child;
            }
            TreeItem<OutlineNode> found = findOutline(child, key);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /** Stable across edits that do not rename or move a node into another parent. */
    static String outlineKey(OutlineNode node) {
        return node.kind() + ":" + node.qualifiedName();
    }

    /** Selects the innermost outline entry around the caret, without jumping (the caret is already there). */
    private void followCaret() {
        if (!active || analysis.outline().isEmpty()) {
            return;
        }
        int line = lineOf(source.text(), source.caretOffset());
        OutlineNode node = analysis.nodeAt(line);
        if (node == null) {
            return;
        }
        TreeItem<OutlineNode> item = findOutline(outlineTree.getRoot(), outlineKey(node));
        if (item == null || outlineTree.getSelectionModel().getSelectedItem() == item) {
            return;
        }
        for (TreeItem<OutlineNode> parent = item.getParent(); parent != null; parent = parent.getParent()) {
            parent.setExpanded(true);
        }
        outlineTree.getSelectionModel().select(item);
        int row = outlineTree.getRow(item);
        if (row >= 0) {
            outlineTree.scrollTo(Math.max(0, row - 2));
        }
    }

    static int lineOf(String text, int offset) {
        if (text == null) {
            return 1;
        }
        int line = 1;
        int end = Math.max(0, Math.min(offset, text.length()));
        for (int i = 0; i < end; i++) {
            if (text.charAt(i) == '\n') {
                line++;
            }
        }
        return line;
    }

    private final class OutlineCell extends TreeCell<OutlineNode> {

        private final Label glyph = glyphLabel();
        private final Label name = new Label();
        private final Label detail = new Label();
        private final Label line = new Label();
        private final HBox box = new HBox(5, glyph, name, detail, line);

        OutlineCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            name.textFillProperty().bind(textFillProperty());
            detail.textFillProperty().bind(textFillProperty());
            line.textFillProperty().bind(textFillProperty());
            detail.setStyle("-fx-opacity: 0.65;");
            line.setStyle("-fx-opacity: 0.5; -fx-font-size: 0.9231em;");
            detail.setMinWidth(0);
            name.setMinWidth(javafx.scene.layout.Region.USE_PREF_SIZE);
            addEventFilter(MouseEvent.MOUSE_CLICKED, this::clicked);
        }

        @Override
        protected void updateItem(OutlineNode node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            glyph.setText(glyphOf(node.kind()));
            glyph.setStyle(glyphStyle(node.kind()));
            name.setText(node.name());
            detail.setText(node.detail());
            setShown(detail, !node.detail().isEmpty());
            line.setText(String.valueOf(node.line()));
            setText(null);
            setGraphic(box);
            setTooltip(new Tooltip(I18n.get(nodeKindKey(node.kind())) + " · " + node.qualifiedName() + " · "
                + I18n.get("snippets.structure.lines", node.line(), node.endLine())));
        }

        private void clicked(MouseEvent event) {
            if (isEmpty() || getItem() == null || event.getButton() != MouseButton.PRIMARY
                || isInside(event.getTarget(), getDisclosureNode())) {
                return;
            }
            jump(getItem().nameStart(), getItem().nameEnd(), event.getClickCount() > 1);
        }
    }

    // ------------------------------------------------------------------ variables

    private void configureVariables() {
        variableTable.setId("snippet-structure-variables");
        variableTable.setMinHeight(0);
        SortedList<Variable> sorted = new SortedList<>(filteredVariables);
        sorted.comparatorProperty().bind(variableTable.comparatorProperty());
        variableTable.setItems(sorted);
        variableTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_ALL_COLUMNS);
        variableTable.setPlaceholder(new Label(""));

        // The two number columns keep room for their headers; the text columns give way in a narrow panel.
        TableColumn<Variable, String> name = new TableColumn<>(I18n.get("snippets.structure.column.name"));
        name.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().name()));
        name.setPrefWidth(100);
        name.setMinWidth(56);
        TableColumn<Variable, String> kind = new TableColumn<>(I18n.get("snippets.structure.column.kind"));
        kind.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(kindText(cell.getValue())));
        kind.setPrefWidth(76);
        kind.setMinWidth(44);
        TableColumn<Variable, String> scope = new TableColumn<>(I18n.get("snippets.structure.column.scope"));
        scope.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(scopeText(cell.getValue())));
        scope.setPrefWidth(64);
        scope.setMinWidth(40);
        TableColumn<Variable, Integer> line = new TableColumn<>(I18n.get("snippets.structure.column.line"));
        line.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().line()));
        line.setCellFactory(column -> numberCell());
        TableColumn<Variable, Integer> uses = new TableColumn<>();
        uses.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().references()));
        uses.setCellFactory(column -> numberCell());
        Label usesHeader = new Label(I18n.get("snippets.structure.column.uses"));
        usesHeader.setMinWidth(Region.USE_PREF_SIZE);
        usesHeader.setTooltip(new Tooltip(I18n.get("snippets.structure.column.uses.tooltip")));
        uses.setGraphic(usesHeader);
        for (TableColumn<Variable, Integer> number : List.of(line, uses)) {
            number.setMinWidth(50);
            number.setPrefWidth(54);
        }
        variableTable.getColumns().setAll(List.of(name, kind, scope, line, uses));

        variableTable.setRowFactory(table -> {
            TableRow<Variable> row = new TableRow<>() {
                @Override
                protected void updateItem(Variable variable, boolean empty) {
                    super.updateItem(variable, empty);
                    setTooltip(empty || variable == null ? null : new Tooltip(variableTooltip(variable)));
                }
            };
            row.setOnMouseClicked(event -> {
                if (!row.isEmpty() && row.getItem() != null && event.getButton() == MouseButton.PRIMARY) {
                    jump(row.getItem().nameStart(), row.getItem().nameEnd(), event.getClickCount() > 1);
                }
            });
            return row;
        });
        variableTable.setOnKeyPressed(event -> {
            Variable selected = variableTable.getSelectionModel().getSelectedItem();
            if (event.getCode() == KeyCode.ENTER && selected != null) {
                jump(selected.nameStart(), selected.nameEnd(), true);
                event.consume();
            }
        });
    }

    private static <T> TableCell<T, Integer> numberCell() {
        return new TableCell<>() {
            @Override
            protected void updateItem(Integer value, boolean empty) {
                super.updateItem(value, empty);
                setText(empty || value == null ? null : String.valueOf(value));
                setAlignment(Pos.CENTER_RIGHT);
            }
        };
    }

    private void reselectVariable(String key) {
        if (key == null) {
            return;
        }
        for (Variable variable : variableTable.getItems()) {
            if (key.equals(keyOf(variable))) {
                variableTable.getSelectionModel().select(variable);
                return;
            }
        }
    }

    private static String keyOf(Variable variable) {
        return variable != null ? variable.scope() + "\u0000" + variable.name() : null;
    }

    static String kindText(Variable variable) {
        return I18n.get(variableKindKey(variable.kind()));
    }

    static String scopeText(Variable variable) {
        return variable.scope().isEmpty() ? I18n.get("snippets.structure.scope.global") : variable.scope();
    }

    private static String variableTooltip(Variable variable) {
        String kind = kindText(variable);
        if (variable.container() != Container.SCALAR && variable.kind() != VariableKind.ARRAY
            && variable.kind() != VariableKind.HASH) {
            kind = I18n.get("snippets.structure.kind.withContainer", kind, I18n.get(variable.container() == Container.ARRAY
                ? "snippets.structure.container.array" : "snippets.structure.container.hash"));
        }
        return variable.name() + " · " + kind + " · " + scopeText(variable) + " · "
            + I18n.get("snippets.structure.line", variable.line()) + "\n"
            + I18n.get("snippets.structure.column.uses.tooltip") + ": " + variable.references();
    }

    // ------------------------------------------------------------------ placeholders

    private void configurePlaceholders() {
        placeholderTree.setId("snippet-structure-placeholders");
        placeholderTree.setShowRoot(false);
        placeholderTree.setMinHeight(0);
        placeholderTree.setCellFactory(tree -> new PlaceholderCell());
        placeholderTree.setOnKeyPressed(event -> {
            TreeItem<Object> item = placeholderTree.getSelectionModel().getSelectedItem();
            if (event.getCode() == KeyCode.ENTER && item != null && item.getValue() instanceof Placeholder p) {
                jump(p.start(), p.end(), true);
                event.consume();
            }
        });
    }

    private void rebuildPlaceholders(String filter) {
        rebuilding = true;
        try {
            Map<SnippetPlaceholderScanner.Status, TreeItem<Object>> groups = new EnumMap<>(SnippetPlaceholderScanner.Status.class);
            Map<SnippetPlaceholderScanner.Status, Integer> counts = new EnumMap<>(SnippetPlaceholderScanner.Status.class);
            List<TreeItem<Object>> leaves = new ArrayList<>();
            for (Placeholder placeholder : placeholders) {
                if (!filter.isEmpty() && !placeholder.name().toLowerCase(Locale.ROOT).contains(filter)) {
                    continue;
                }
                TreeItem<Object> group = groups.computeIfAbsent(placeholder.status(), status -> {
                    TreeItem<Object> item = new TreeItem<>(status);
                    item.setExpanded(!filter.isEmpty() || !collapsedGroups.contains(status));
                    item.expandedProperty().addListener((obs, was, expanded) -> {
                        if (!rebuilding) {
                            if (expanded) {
                                collapsedGroups.remove(status);
                            } else {
                                collapsedGroups.add(status);
                            }
                        }
                    });
                    return item;
                });
                counts.merge(placeholder.status(), 1, Integer::sum);
                TreeItem<Object> leaf = new TreeItem<>(placeholder);
                group.getChildren().add(leaf);
                leaves.add(leaf);
            }
            TreeItem<Object> root = new TreeItem<>();
            for (SnippetPlaceholderScanner.Status status : SnippetPlaceholderScanner.Status.values()) {
                TreeItem<Object> group = groups.get(status);
                if (group != null) {
                    root.getChildren().add(group);
                }
            }
            root.setExpanded(true);
            placeholderTree.setRoot(root);
            placeholdersEmpty.setText(I18n.get(filter.isEmpty() ? "snippets.structure.placeholders.empty"
                : "snippets.structure.filter.noMatch"));
            setShown(placeholdersEmpty, leaves.isEmpty());
        } finally {
            rebuilding = false;
        }
    }

    private final class PlaceholderCell extends TreeCell<Object> {

        private final Label name = new Label();
        private final Label detail = new Label();
        private final HBox box = new HBox(6, name, detail);

        PlaceholderCell() {
            box.setAlignment(Pos.CENTER_LEFT);
            name.textFillProperty().bind(textFillProperty());
            detail.textFillProperty().bind(textFillProperty());
            detail.setStyle("-fx-opacity: 0.6; -fx-font-size: 0.9231em;");
            addEventFilter(MouseEvent.MOUSE_CLICKED, event -> {
                if (!isEmpty() && getItem() instanceof Placeholder p && event.getButton() == MouseButton.PRIMARY) {
                    jump(p.start(), p.end(), event.getClickCount() > 1);
                }
            });
        }

        @Override
        protected void updateItem(Object value, boolean empty) {
            super.updateItem(value, empty);
            setTooltip(null);
            if (empty || value == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (value instanceof SnippetPlaceholderScanner.Status status) {
                name.setText(I18n.get(groupKey(status)) + " (" + getTreeItem().getChildren().size() + ")");
                name.setStyle("-fx-font-weight: bold;");
                setShown(detail, false);
                setTooltip(new Tooltip(I18n.get(groupTooltipKey(status))));
            } else if (value instanceof Placeholder placeholder) {
                name.setText("${" + placeholder.name() + "}");
                name.setStyle("");
                detail.setText(I18n.get("snippets.structure.placeholders.occurrences", placeholder.occurrences(),
                    placeholder.line()));
                setShown(detail, true);
            }
            setText(null);
            setGraphic(box);
        }
    }

    private static String groupKey(SnippetPlaceholderScanner.Status status) {
        return switch (status) {
            case BUILT_IN -> "snippets.structure.placeholders.builtIn";
            case STORED -> "snippets.structure.placeholders.stored";
            case ASKS -> "snippets.structure.placeholders.asks";
            case UNDECLARED -> "snippets.structure.placeholders.undeclared";
        };
    }

    private static String groupTooltipKey(SnippetPlaceholderScanner.Status status) {
        return switch (status) {
            case BUILT_IN -> "snippets.structure.placeholders.builtIn.tooltip";
            case STORED -> "snippets.structure.placeholders.stored.tooltip";
            case ASKS -> "snippets.structure.placeholders.asks.tooltip";
            case UNDECLARED -> "snippets.structure.placeholders.undeclared.tooltip";
        };
    }

    // ------------------------------------------------------------------ helpers

    private void jump(int start, int end, boolean focus) {
        source.select(start, end, focus);
    }

    private static boolean isInside(Object target, Node container) {
        if (container == null || !(target instanceof Node node)) {
            return false;
        }
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == container) {
                return true;
            }
        }
        return false;
    }

    private static VBox section(Label title, StackPane content) {
        VBox.setVgrow(content, Priority.ALWAYS);
        content.setMinHeight(0);
        VBox box = new VBox(3, title, content);
        box.setMinHeight(0);
        box.setPadding(new Insets(4, 0, 2, 0));
        return box;
    }

    private static Label sectionTitle(String key) {
        Label label = new Label(I18n.get(key));
        label.setStyle("-fx-font-weight: bold; -fx-padding: 0 2 0 2;");
        return label;
    }

    private static Label emptyLabel() {
        Label label = new Label();
        label.setWrapText(true);
        label.setMouseTransparent(true);
        label.setStyle("-fx-text-fill: gray; -fx-padding: 8;");
        StackPane.setAlignment(label, Pos.TOP_LEFT);
        return label;
    }

    private static Label glyphLabel() {
        Label glyph = new Label();
        glyph.setMinWidth(18);
        glyph.setAlignment(Pos.CENTER);
        return glyph;
    }

    private static void setShown(Node node, boolean shown) {
        node.setVisible(shown);
        node.setManaged(shown);
    }

    static String glyphOf(NodeKind kind) {
        return switch (kind) {
            case MODULE -> "N";
            case CLASS -> "C";
            case METHOD -> "m";
            case FUNCTION -> "f";
            case SECTION -> "§";
            case SQL_OBJECT -> "T";
            case STAGE -> "S";
        };
    }

    /** Literal colours: they must read in the Normal design and in every AtlantaFX design alike. */
    private static String glyphStyle(NodeKind kind) {
        String rgb = switch (kind) {
            case MODULE -> "19,168,173";
            case CLASS -> "168,85,247";
            case METHOD, FUNCTION -> "56,189,248";
            case SECTION -> "148,163,184";
            case SQL_OBJECT -> "245,158,11";
            case STAGE -> "34,197,94";
        };
        return "-fx-background-color: rgba(" + rgb + ",0.18); -fx-text-fill: rgb(" + rgb + ");"
            + " -fx-font-weight: bold; -fx-font-size: 0.8462em; -fx-padding: 0 3 0 3; -fx-background-radius: 3;";
    }

    private static String nodeKindKey(NodeKind kind) {
        return switch (kind) {
            case MODULE -> "snippets.structure.node.module";
            case CLASS -> "snippets.structure.node.class";
            case METHOD -> "snippets.structure.node.method";
            case FUNCTION -> "snippets.structure.node.function";
            case SECTION -> "snippets.structure.node.section";
            case SQL_OBJECT -> "snippets.structure.node.sqlObject";
            case STAGE -> "snippets.structure.node.stage";
        };
    }

    private static String variableKindKey(VariableKind kind) {
        return switch (kind) {
            case VARIABLE -> "snippets.structure.kind.variable";
            case ARRAY -> "snippets.structure.kind.array";
            case HASH -> "snippets.structure.kind.hash";
            case CONSTANT -> "snippets.structure.kind.constant";
            case PARAMETER -> "snippets.structure.kind.parameter";
            case EXPORTED -> "snippets.structure.kind.exported";
            case FIELD -> "snippets.structure.kind.field";
        };
    }

    private static String outlineTitleKey(String language) {
        return switch (language != null ? language : "") {
            case "markdown", "asciidoctor" -> "snippets.structure.outline.markup";
            case "sql" -> "snippets.structure.outline.sql";
            case "dockerfile" -> "snippets.structure.outline.dockerfile";
            default -> "snippets.structure.outline.code";
        };
    }

    private static String languageName(String language) {
        return language == null || language.isBlank() || "plain".equals(language)
            ? I18n.get("snippets.structure.plainText") : language;
    }
}
