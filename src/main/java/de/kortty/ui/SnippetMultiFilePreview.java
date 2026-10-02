package de.kortty.ui;

import de.kortty.core.SnippetLanguageSupport;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * The review window of a Full code analysis result that spans several files — a folder analysis
 * or a modularization: one tab per file with the usual side-by-side diff (new files against an
 * empty original), and a directory tree of the result that can be shown or hidden. Tree and tabs
 * follow each other's selection; each file can be excluded from accepting, in the tree or on its
 * tab. Accept hands the included changes to the caller.
 */
final class SnippetMultiFilePreview extends BorderPane {

    static final String TREE_ID = "snippet-multi-file-tree";
    static final String TABS_ID = "snippet-multi-file-tabs";
    static final String TREE_TOGGLE_ID = "snippet-multi-file-tree-toggle";

    /** Whether the tree is shown; remembered for the session. */
    private static boolean treeShownPreference = true;

    enum Status {
        NEW("+", "#16a34a"), CHANGED("✎", "#d97706"), UNCHANGED("=", "#6b7280"), DELETED("−", "#dc2626");

        final String mark;
        final String color;

        Status(String mark, String color) {
            this.mark = mark;
            this.color = color;
        }

        String label() {
            return I18n.get("snippets.preview.status." + name().toLowerCase(java.util.Locale.ROOT));
        }
    }

    /**
     * One file of the result. {@code original == null} is a new file, {@code replacement == null}
     * a deleted one; {@code snippetId} names the snippet the file came from, if any.
     */
    record FileChange(String path, String original, String replacement, boolean executable, String snippetId) {
        FileChange {
            path = Objects.requireNonNull(path, "path");
        }

        Status status() {
            if (original == null) {
                return Status.NEW;
            }
            if (replacement == null) {
                return Status.DELETED;
            }
            return original.equals(replacement) ? Status.UNCHANGED : Status.CHANGED;
        }

        String fileName() {
            int slash = path.lastIndexOf('/');
            return slash >= 0 ? path.substring(slash + 1) : path;
        }
    }

    private final List<FileChange> files;
    private final Map<String, Boolean> included = new LinkedHashMap<>();
    private final Map<String, Tab> tabsByPath = new HashMap<>();
    private final Map<String, TreeItem<String>> treeItemsByPath = new HashMap<>();
    private final Map<String, CheckBox> tabChecks = new HashMap<>();
    private final List<SnippetAiDiffPane> diffPanes = new ArrayList<>();
    private final TreeView<String> tree = new TreeView<>();
    private final TabPane tabs = new TabPane();
    private final SplitPane split = new SplitPane();
    private final ToggleButton treeToggle = new ToggleButton(I18n.get("snippets.preview.tree"));
    private final Label selectionLabel = new Label();
    private final EditorSettingsHelper.Settings editorSettings;
    private boolean syncing;
    private Runnable selectionListener = () -> { };

    SnippetMultiFilePreview(String summary, List<FileChange> files, EditorSettingsHelper.Settings editorSettings) {
        this.files = List.copyOf(files);
        this.editorSettings = editorSettings;
        for (FileChange file : this.files) {
            included.put(file.path(), file.status() != Status.UNCHANGED);
        }
        setId("snippet-multi-file-preview");

        treeToggle.setId(TREE_TOGGLE_ID);
        treeToggle.setSelected(treeShownPreference);
        treeToggle.setTooltip(new Tooltip(I18n.get("snippets.preview.tree.tooltip")));
        treeToggle.selectedProperty().addListener((obs, was, now) -> {
            treeShownPreference = now;
            applyTreeVisibility();
        });
        Label heading = new Label(I18n.get("snippets.preview.files", this.files.size()));
        heading.setStyle("-fx-font-weight: bold;");
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(10, treeToggle, heading, spacer, selectionLabel);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 6, 0));

        VBox top = new VBox(6);
        if (summary != null && !summary.isBlank()) {
            TextArea summaryArea = new TextArea(summary.strip());
            summaryArea.setEditable(false);
            summaryArea.setWrapText(true);
            summaryArea.setPrefRowCount(3);
            summaryArea.setMaxHeight(110);
            top.getChildren().add(summaryArea);
        }
        top.getChildren().add(toolbar);
        setTop(top);

        buildTree();
        buildTabs();
        split.setOrientation(Orientation.HORIZONTAL);
        applyTreeVisibility();
        setCenter(split);
        updateSelectionLabel();
        if (!tabs.getTabs().isEmpty()) {
            selectPath(firstInterestingPath());
        }
    }

    // ---- Tree ----

    private void buildTree() {
        tree.setId(TREE_ID);
        TreeItem<String> root = new TreeItem<>("");
        root.setExpanded(true);
        Map<String, TreeItem<String>> directories = new HashMap<>();
        for (FileChange file : files) {
            TreeItem<String> parent = root;
            String[] segments = file.path().split("/");
            StringBuilder prefix = new StringBuilder();
            for (int i = 0; i < segments.length - 1; i++) {
                if (prefix.length() > 0) {
                    prefix.append('/');
                }
                prefix.append(segments[i]);
                String key = prefix.toString() + "/";
                TreeItem<String> directory = directories.get(key);
                if (directory == null) {
                    directory = new TreeItem<>(key);
                    directory.setExpanded(true);
                    directories.put(key, directory);
                    parent.getChildren().add(directory);
                }
                parent = directory;
            }
            TreeItem<String> item = new TreeItem<>(file.path());
            treeItemsByPath.put(file.path(), item);
            parent.getChildren().add(item);
        }
        tree.setRoot(root);
        tree.setShowRoot(false);
        tree.setCellFactory(view -> new FileCell());
        tree.setMinWidth(170);
        tree.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            if (!syncing && now != null && tabsByPath.containsKey(now.getValue())) {
                selectPath(now.getValue());
            }
        });
    }

    private final class FileCell extends TreeCell<String> {
        private final CheckBox check = new CheckBox();

        FileCell() {
            check.setOnAction(event -> {
                if (getItem() != null) {
                    setIncluded(getItem(), check.isSelected());
                }
            });
        }

        @Override
        protected void updateItem(String value, boolean empty) {
            super.updateItem(value, empty);
            FileChange file = empty || value == null ? null : file(value);
            if (empty || value == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                setStyle(null);
                return;
            }
            if (file == null) {
                String trimmed = value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
                int slash = trimmed.lastIndexOf('/');
                setText("📁 " + (slash >= 0 ? trimmed.substring(slash + 1) : trimmed));
                setGraphic(null);
                setTooltip(null);
                setStyle(null);
                return;
            }
            check.setSelected(Boolean.TRUE.equals(included.get(value)));
            check.setDisable(file.status() == Status.UNCHANGED);
            setGraphic(check);
            setText(file.status().mark + " " + file.fileName() + (file.executable() ? "  ⚙" : ""));
            setStyle("-fx-text-fill: " + file.status().color + ";");
            setTooltip(new Tooltip(file.path() + "\n" + file.status().label()
                + (file.executable() ? "\n" + I18n.get("snippets.executable.on") : "")));
        }
    }

    private void applyTreeVisibility() {
        if (treeToggle.isSelected()) {
            split.getItems().setAll(tree, tabs);
            split.setDividerPositions(0.24);
            SplitPane.setResizableWithParent(tree, false);
        } else {
            split.getItems().setAll(tabs);
        }
    }

    // ---- Tabs ----

    private void buildTabs() {
        tabs.setId(TABS_ID);
        tabs.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        for (FileChange file : files) {
            Tab tab = new Tab(file.status().mark + " " + file.fileName());
            tab.setTooltip(new Tooltip(file.path() + " — " + file.status().label()));
            tab.setUserData(file.path());
            tab.setStyle("-fx-text-base-color: " + file.status().color + ";");
            CheckBox check = new CheckBox();
            check.setSelected(Boolean.TRUE.equals(included.get(file.path())));
            check.setDisable(file.status() == Status.UNCHANGED);
            check.setTooltip(new Tooltip(I18n.get("snippets.preview.include")));
            check.setOnAction(event -> setIncluded(file.path(), check.isSelected()));
            tab.setGraphic(check);
            tabChecks.put(file.path(), check);
            tabsByPath.put(file.path(), tab);
            tabs.getTabs().add(tab);
        }
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, was, now) -> {
            if (now == null) {
                return;
            }
            ensureContent(now);
            if (!syncing) {
                syncing = true;
                try {
                    TreeItem<String> item = treeItemsByPath.get((String) now.getUserData());
                    if (item != null) {
                        tree.getSelectionModel().select(item);
                        int row = tree.getRow(item);
                        if (row >= 0) {
                            tree.scrollTo(row);
                        }
                    }
                } finally {
                    syncing = false;
                }
            }
        });
    }

    /** Builds a tab's diff only when it is first shown: each diff is a web view. */
    private void ensureContent(Tab tab) {
        if (tab.getContent() != null) {
            return;
        }
        FileChange file = file((String) tab.getUserData());
        if (file == null) {
            return;
        }
        String language = SnippetLanguageSupport.detectFileLanguage(file.fileName(),
            file.replacement() != null ? file.replacement() : file.original());
        SnippetAiDiffPane pane = new SnippetAiDiffPane(null, file.original() != null ? file.original() : "",
            file.replacement() != null ? file.replacement() : "", language, editorSettings, false);
        pane.setHeading(file.path() + " — " + file.status().label());
        diffPanes.add(pane);
        tab.setContent(pane);
    }

    // ---- Selection and inclusion ----

    void selectPath(String path) {
        Tab tab = tabsByPath.get(path);
        if (tab == null) {
            return;
        }
        syncing = true;
        try {
            tabs.getSelectionModel().select(tab);
            TreeItem<String> item = treeItemsByPath.get(path);
            if (item != null) {
                tree.getSelectionModel().select(item);
            }
        } finally {
            syncing = false;
        }
        ensureContent(tab);
    }

    void setIncluded(String path, boolean value) {
        FileChange file = file(path);
        if (file == null || file.status() == Status.UNCHANGED) {
            return;
        }
        included.put(path, value);
        CheckBox check = tabChecks.get(path);
        if (check != null && check.isSelected() != value) {
            check.setSelected(value);
        }
        tree.refresh();
        updateSelectionLabel();
        selectionListener.run();
    }

    /** The changes that will be applied: included and not unchanged, in the original order. */
    List<FileChange> includedChanges() {
        return files.stream()
            .filter(file -> file.status() != Status.UNCHANGED && Boolean.TRUE.equals(included.get(file.path())))
            .toList();
    }

    String selectedPath() {
        Tab tab = tabs.getSelectionModel().getSelectedItem();
        return tab != null ? (String) tab.getUserData() : null;
    }

    String treeSelectedPath() {
        TreeItem<String> item = tree.getSelectionModel().getSelectedItem();
        return item != null ? item.getValue() : null;
    }

    boolean isTreeShown() {
        return split.getItems().contains(tree);
    }

    void setTreeShown(boolean shown) {
        treeToggle.setSelected(shown);
    }

    void setOnSelectionChanged(Runnable listener) {
        this.selectionListener = listener != null ? listener : () -> { };
    }

    private void updateSelectionLabel() {
        long changed = files.stream().filter(file -> file.status() != Status.UNCHANGED).count();
        selectionLabel.setText(I18n.get("snippets.preview.selected", includedChanges().size(), changed));
    }

    private FileChange file(String path) {
        return files.stream().filter(file -> file.path().equals(path)).findFirst().orElse(null);
    }

    private String firstInterestingPath() {
        return files.stream().filter(file -> file.status() != Status.UNCHANGED).map(FileChange::path).findFirst()
            .orElse(files.getFirst().path());
    }

    void dispose() {
        diffPanes.forEach(SnippetAiDiffPane::dispose);
        diffPanes.clear();
    }

    // ---- Window ----

    /**
     * Opens the review window (non-modal). {@code onAccept} receives the included changes; it is
     * not called on Discard or when the window is closed.
     */
    static ThemeAwareDialog<Void> show(Window owner, String title, String summary, List<FileChange> files,
                                       EditorSettingsHelper.Settings editorSettings,
                                       Consumer<List<FileChange>> onAccept) {
        SnippetMultiFilePreview preview = new SnippetMultiFilePreview(summary, files, editorSettings);
        ThemeAwareDialog<Void> dialog = new ThemeAwareDialog<>();
        dialog.initOwner(owner);
        dialog.initModality(Modality.NONE);
        dialog.setResizable(true);
        dialog.setTitle(title);
        dialog.getDialogPane().setContent(preview);
        dialog.getDialogPane().setPrefSize(1180, 760);
        ButtonType accept = new ButtonType(I18n.get("snippets.preview.accept"), ButtonBar.ButtonData.OK_DONE);
        ButtonType discard = new ButtonType(I18n.get("snippets.preview.discard"), ButtonBar.ButtonData.CANCEL_CLOSE);
        dialog.getDialogPane().getButtonTypes().setAll(accept, discard);
        javafx.scene.Node acceptButton = dialog.getDialogPane().lookupButton(accept);
        acceptButton.setDisable(preview.includedChanges().isEmpty());
        preview.setOnSelectionChanged(() -> acceptButton.setDisable(preview.includedChanges().isEmpty()));
        dialog.setResultConverter(button -> {
            List<FileChange> chosen = button == accept ? preview.includedChanges() : null;
            preview.dispose();
            if (chosen != null && onAccept != null) {
                onAccept.accept(chosen);
            }
            return null;
        });
        dialog.show();
        return dialog;
    }
}
