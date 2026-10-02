package de.kortty.ui;

import de.kortty.core.SnippetManager;
import de.kortty.model.SnippetFolder;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.DataFormat;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The folder tree above the snippet table: "All snippets", the nested folders and "Top level".
 * Selecting a node filters the table; snippets dragged from the table onto a node move there, and
 * folders can be dragged onto other folders. The context menu offers the folder actions (new,
 * rename, delete, export, copy to the terminal's directory, project analysis).
 */
final class SnippetFolderTreePane extends VBox {

    /** Clipboard format of snippet rows dragged from the table: their ids, newline separated. */
    static final DataFormat SNIPPET_IDS_FORMAT = dataFormat("application/x-kortty-snippet-ids");
    /** Clipboard format of a folder dragged inside the tree: its id. */
    static final DataFormat FOLDER_ID_FORMAT = dataFormat("application/x-kortty-snippet-folder-id");

    static final String TREE_ID = "snippet-library-folder-tree";

    private static DataFormat dataFormat(String mime) {
        DataFormat existing = DataFormat.lookupMimeType(mime);
        return existing != null ? existing : new DataFormat(mime);
    }

    /** What a tree node stands for. */
    enum Kind { ALL, TOP_LEVEL, FOLDER }

    /** A tree node: {@code folderId} is set for {@link Kind#FOLDER} only. */
    record Node(Kind kind, String folderId, String label) {
        /** The folder snippets dropped here move into ({@code null} = top level). */
        String targetFolderId() {
            return kind == Kind.FOLDER ? folderId : null;
        }

        @Override
        public String toString() {
            return label;
        }
    }

    /** What the tree asks the library around it to do. */
    interface Actions {
        /** The selection or the "include sub-folders" switch changed: re-filter the table. */
        void selectionChanged();

        /** Snippets were dropped on a node; move them into {@code folderId} ({@code null} = top level). */
        void moveSnippets(List<String> snippetIds, String folderId);

        /** Persist a folder change and refresh the library; {@code false} when the save failed. */
        boolean persist();

        void exportFolder(String folderId);

        void copyFolderToTerminal(String folderId);

        void analyzeFolder(String folderId);

        void exportFolderReports(String folderId);

        /** Whether a terminal tab that can receive files is available right now. */
        boolean canCopyToTerminal();

        Window ownerWindow();
    }

    private final SnippetManager snippetManager;
    private final Actions actions;
    private final TreeView<Node> tree = new TreeView<>();
    private final CheckBox includeSubfolders = new CheckBox(I18n.get("snippets.folder.includeSubfolders"));
    private final Set<String> expandedFolderIds = new HashSet<>();
    private boolean rebuilding;

    SnippetFolderTreePane(SnippetManager snippetManager, Actions actions) {
        this.snippetManager = Objects.requireNonNull(snippetManager, "snippetManager");
        this.actions = Objects.requireNonNull(actions, "actions");
        this.deleteRequested = (folderId, deleteContents) -> {
            snippetManager.removeFolder(folderId, deleteContents);
            actions.persist();
        };
        getStyleClass().add("snippet-folder-tree-pane");

        tree.setId(TREE_ID);
        tree.setShowRoot(true);
        tree.setCellFactory(view -> new FolderCell());
        tree.setContextMenu(createContextMenu());
        tree.getSelectionModel().selectedItemProperty().addListener((obs, oldItem, newItem) -> {
            if (!rebuilding) {
                actions.selectionChanged();
            }
        });
        tree.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.F2) {
                renameSelected();
                event.consume();
            } else if (event.getCode() == KeyCode.DELETE || event.getCode() == KeyCode.BACK_SPACE) {
                deleteSelected();
                event.consume();
            }
        });

        includeSubfolders.setSelected(true);
        includeSubfolders.setTooltip(new Tooltip(I18n.get("snippets.folder.includeSubfolders.tooltip")));
        includeSubfolders.selectedProperty().addListener((obs, was, now) -> actions.selectionChanged());

        Button newFolder = new Button("📁+");
        newFolder.setTooltip(new Tooltip(I18n.get("snippets.folder.new")));
        newFolder.setOnAction(event -> createFolder());

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox toolbar = new HBox(8, includeSubfolders, spacer, newFolder);
        toolbar.setAlignment(Pos.CENTER_LEFT);
        toolbar.setPadding(new Insets(0, 0, 4, 0));

        getChildren().addAll(toolbar, tree);
        VBox.setVgrow(tree, Priority.ALWAYS);
        setMinHeight(0);
        refresh();
        tree.getSelectionModel().select(tree.getRoot());
    }

    TreeView<Node> tree() {
        return tree;
    }

    /** The selected node; "All snippets" when nothing is selected. */
    Node selectedNode() {
        TreeItem<Node> item = tree.getSelectionModel().getSelectedItem();
        return item != null && item.getValue() != null ? item.getValue() : new Node(Kind.ALL, null, "");
    }

    boolean includeSubfolders() {
        return includeSubfolders.isSelected();
    }

    /** Selects the folder's node (expanding its parents). */
    void selectFolder(String folderId) {
        for (SnippetFolder folder : snippetManager.folderChain(folderId)) {
            expandedFolderIds.add(folder.getId());
        }
        refresh();
        TreeItem<Node> item = find(tree.getRoot(), folderId);
        if (item != null) {
            tree.getSelectionModel().select(item);
            tree.scrollTo(tree.getRow(item));
        }
    }

    /** Rebuilds the tree from the manager, keeping the expanded folders and the selection. */
    void refresh() {
        TreeItem<Node> root = tree.getRoot();
        if (root != null) {
            rememberExpanded(root);
        }
        Node selected = tree.getSelectionModel().getSelectedItem() != null
            ? tree.getSelectionModel().getSelectedItem().getValue() : null;
        rebuilding = true;
        try {
            TreeItem<Node> newRoot = new TreeItem<>(new Node(Kind.ALL, null, I18n.get("snippets.folder.all")));
            newRoot.setExpanded(true);
            addChildren(newRoot, null);
            newRoot.getChildren().add(new TreeItem<>(new Node(Kind.TOP_LEVEL, null, I18n.get("snippets.folder.topLevel"))));
            tree.setRoot(newRoot);
            TreeItem<Node> reselect = selected == null ? newRoot
                : selected.kind() == Kind.FOLDER ? find(newRoot, selected.folderId())
                : selected.kind() == Kind.TOP_LEVEL ? newRoot.getChildren().getLast() : newRoot;
            tree.getSelectionModel().select(reselect != null ? reselect : newRoot);
        } finally {
            rebuilding = false;
        }
        if (selected != null && selected.kind() == Kind.FOLDER && snippetManager.findFolder(selected.folderId()).isEmpty()) {
            actions.selectionChanged(); // the shown folder vanished
        }
    }

    private void addChildren(TreeItem<Node> parentItem, String parentId) {
        for (SnippetFolder folder : snippetManager.childFolders(parentId)) {
            TreeItem<Node> item = new TreeItem<>(new Node(Kind.FOLDER, folder.getId(), folder.getName()));
            item.setExpanded(expandedFolderIds.contains(folder.getId()));
            item.expandedProperty().addListener((obs, was, now) -> {
                if (now) {
                    expandedFolderIds.add(folder.getId());
                } else {
                    expandedFolderIds.remove(folder.getId());
                }
            });
            addChildren(item, folder.getId());
            parentItem.getChildren().add(item);
        }
    }

    private void rememberExpanded(TreeItem<Node> item) {
        if (item.getValue() != null && item.getValue().kind() == Kind.FOLDER) {
            if (item.isExpanded()) {
                expandedFolderIds.add(item.getValue().folderId());
            } else {
                expandedFolderIds.remove(item.getValue().folderId());
            }
        }
        for (TreeItem<Node> child : item.getChildren()) {
            rememberExpanded(child);
        }
    }

    private static TreeItem<Node> find(TreeItem<Node> item, String folderId) {
        if (item == null || folderId == null) {
            return null;
        }
        if (item.getValue() != null && folderId.equals(item.getValue().folderId())) {
            return item;
        }
        for (TreeItem<Node> child : item.getChildren()) {
            TreeItem<Node> found = find(child, folderId);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    // ---- Folder actions ----

    private ContextMenu createContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem newFolder = new MenuItem("📁 " + I18n.get("snippets.folder.new"));
        newFolder.setOnAction(event -> createFolder());
        MenuItem rename = new MenuItem(I18n.get("snippets.folder.rename"));
        rename.setOnAction(event -> renameSelected());
        MenuItem delete = new MenuItem("✕ " + I18n.get("snippets.folder.delete"));
        delete.setOnAction(event -> deleteSelected());
        MenuItem export = new MenuItem("📤 " + I18n.get("snippets.folder.export"));
        export.setOnAction(event -> actions.exportFolder(selectedNode().targetFolderId()));
        MenuItem copyToTerminal = new MenuItem("⌨ " + I18n.get("snippets.transfer.folder"));
        copyToTerminal.setOnAction(event -> actions.copyFolderToTerminal(selectedNode().folderId()));
        MenuItem analyze = new MenuItem(I18n.get("snippets.folder.analyze"));
        analyze.setOnAction(event -> actions.analyzeFolder(selectedNode().folderId()));
        MenuItem reports = new MenuItem(I18n.get("snippets.batchExport.menu"));
        reports.setOnAction(event -> actions.exportFolderReports(selectedNode().targetFolderId()));
        menu.getItems().addAll(newFolder, rename, delete, new SeparatorMenuItem(),
            copyToTerminal, export, new SeparatorMenuItem(), analyze, reports);
        menu.setOnShowing(event -> {
            Node node = selectedNode();
            boolean folder = node.kind() == Kind.FOLDER;
            rename.setDisable(!folder);
            delete.setDisable(!folder);
            copyToTerminal.setDisable(!folder || !actions.canCopyToTerminal());
            analyze.setDisable(!folder || snippetManager.snippetsInFolder(node.folderId(), true).isEmpty());
            export.setDisable(node.kind() == Kind.TOP_LEVEL);
            reports.setDisable(node.kind() == Kind.TOP_LEVEL);
        });
        return menu;
    }

    /** New folder below the selected folder (or at the top level). */
    void createFolder() {
        String parentId = selectedNode().targetFolderId();
        Optional<String> name = askName(I18n.get("snippets.folder.new"), "");
        if (name.isEmpty()) {
            return;
        }
        try {
            SnippetFolder folder = snippetManager.addFolder(name.get(), parentId);
            if (parentId != null) {
                expandedFolderIds.add(parentId);
            }
            if (actions.persist()) {
                selectFolder(folder.getId());
            }
        } catch (IllegalArgumentException invalid) {
            showError(I18n.get("snippets.folder.invalidName", name.get()));
        }
    }

    private void renameSelected() {
        Node node = selectedNode();
        if (node.kind() != Kind.FOLDER) {
            return;
        }
        Optional<String> name = askName(I18n.get("snippets.folder.rename"), node.label());
        if (name.isEmpty() || name.get().equals(node.label())) {
            return;
        }
        try {
            snippetManager.renameFolder(node.folderId(), name.get());
            actions.persist();
        } catch (IllegalArgumentException invalid) {
            showError(I18n.get("snippets.folder.invalidName", name.get()));
        }
    }

    private void deleteSelected() {
        Node node = selectedNode();
        if (node.kind() != Kind.FOLDER) {
            return;
        }
        int count = snippetManager.snippetsInFolder(node.folderId(), true).size();
        javafx.scene.control.ButtonType keep = new javafx.scene.control.ButtonType(
            I18n.get("snippets.folder.delete.keep"), javafx.scene.control.ButtonBar.ButtonData.YES);
        javafx.scene.control.ButtonType deleteAll = new javafx.scene.control.ButtonType(
            I18n.get("snippets.folder.delete.all"), javafx.scene.control.ButtonBar.ButtonData.OTHER);
        javafx.scene.control.Alert confirm = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.CONFIRMATION, I18n.get("snippets.folder.delete.content", node.label(), count),
            keep, deleteAll, javafx.scene.control.ButtonType.CANCEL);
        confirm.setTitle(I18n.get("snippets.folder.delete"));
        confirm.setHeaderText(null);
        confirm.initOwner(actions.ownerWindow());
        Optional<javafx.scene.control.ButtonType> answer = confirm.showAndWait();
        if (answer.isEmpty() || answer.get() == javafx.scene.control.ButtonType.CANCEL) {
            return;
        }
        if (answer.get() == deleteAll && count > 0) {
            javafx.scene.control.Alert sure = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.WARNING, I18n.get("snippets.folder.delete.allConfirm", count),
                javafx.scene.control.ButtonType.OK, javafx.scene.control.ButtonType.CANCEL);
            sure.setHeaderText(null);
            sure.initOwner(actions.ownerWindow());
            if (sure.showAndWait().orElse(javafx.scene.control.ButtonType.CANCEL) != javafx.scene.control.ButtonType.OK) {
                return;
            }
        }
        deleteRequested.accept(node.folderId(), answer.get() == deleteAll);
    }

    /** Set by the library: deleting contents needs its editor/analysis cleanup. */
    private java.util.function.BiConsumer<String, Boolean> deleteRequested;

    void setOnDeleteRequested(java.util.function.BiConsumer<String, Boolean> handler) {
        this.deleteRequested = Objects.requireNonNull(handler, "handler");
    }

    private Optional<String> askName(String title, String initial) {
        TextInputDialog dialog = new TextInputDialog(initial);
        dialog.setTitle(title);
        dialog.setHeaderText(null);
        dialog.setContentText(I18n.get("snippets.folder.name"));
        dialog.initOwner(actions.ownerWindow());
        return dialog.showAndWait().map(String::trim).filter(value -> !value.isEmpty());
    }

    private void showError(String message) {
        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(javafx.scene.control.Alert.AlertType.ERROR, message);
        alert.setHeaderText(null);
        alert.initOwner(actions.ownerWindow());
        alert.showAndWait();
    }

    // ---- Drag and drop ----

    /** Whether {@code dragboard} can be dropped on {@code target}. */
    boolean acceptsDrop(Dragboard dragboard, Node target) {
        if (target == null) {
            return false;
        }
        if (dragboard.hasContent(SNIPPET_IDS_FORMAT)) {
            return true;
        }
        if (dragboard.hasContent(FOLDER_ID_FORMAT)) {
            String folderId = (String) dragboard.getContent(FOLDER_ID_FORMAT);
            String targetId = target.targetFolderId();
            if (folderId == null) {
                return false;
            }
            return targetId == null || !snippetManager.isSameOrDescendant(targetId, folderId);
        }
        return false;
    }

    /** Performs a drop on {@code target}; {@code true} when something moved. */
    boolean drop(Dragboard dragboard, Node target) {
        if (!acceptsDrop(dragboard, target)) {
            return false;
        }
        if (dragboard.hasContent(SNIPPET_IDS_FORMAT)) {
            String raw = (String) dragboard.getContent(SNIPPET_IDS_FORMAT);
            List<String> ids = raw == null ? List.of()
                : Arrays.stream(raw.split("\n")).map(String::trim).filter(id -> !id.isEmpty()).toList();
            actions.moveSnippets(ids, target.targetFolderId());
            return true;
        }
        String folderId = (String) dragboard.getContent(FOLDER_ID_FORMAT);
        try {
            snippetManager.moveFolder(folderId, target.targetFolderId());
        } catch (IllegalArgumentException invalid) {
            showError(invalid.getMessage());
            return false;
        }
        if (target.targetFolderId() != null) {
            expandedFolderIds.add(target.targetFolderId());
        }
        actions.persist();
        return true;
    }

    private final class FolderCell extends TreeCell<Node> {
        FolderCell() {
            setOnDragDetected(event -> {
                Node node = getItem();
                if (node == null || node.kind() != Kind.FOLDER) {
                    return;
                }
                Dragboard dragboard = startDragAndDrop(TransferMode.MOVE);
                ClipboardContent content = new ClipboardContent();
                content.put(FOLDER_ID_FORMAT, node.folderId());
                dragboard.setContent(content);
                event.consume();
            });
            setOnDragOver(event -> {
                if (event.getGestureSource() != this && acceptsDrop(event.getDragboard(), getItem())) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });
            setOnDragEntered(event -> {
                if (acceptsDrop(event.getDragboard(), getItem())) {
                    getStyleClass().add("snippet-folder-drop-target");
                    setStyle("-fx-background-color: rgba(56,189,248,0.25);");
                }
            });
            setOnDragExited(event -> {
                getStyleClass().remove("snippet-folder-drop-target");
                setStyle(null);
            });
            setOnDragDropped(event -> {
                boolean done = drop(event.getDragboard(), getItem());
                event.setDropCompleted(done);
                event.consume();
            });
        }

        @Override
        protected void updateItem(Node node, boolean empty) {
            super.updateItem(node, empty);
            if (empty || node == null) {
                setText(null);
                setGraphic(null);
                setTooltip(null);
                return;
            }
            String icon = switch (node.kind()) {
                case ALL -> "🗂 ";
                case TOP_LEVEL -> "⌂ ";
                case FOLDER -> "📁 ";
            };
            int count = node.kind() == Kind.ALL
                ? (int) snippetManager.getAllSnippets().stream().filter(s -> s != null).count()
                : snippetManager.snippetsInFolder(node.folderId(), node.kind() == Kind.FOLDER).size();
            setText(icon + node.label() + "  (" + count + ")");
            setTooltip(node.kind() == Kind.FOLDER
                ? new Tooltip(snippetManager.folderPath(node.folderId())) : null);
        }
    }

    /** The ids of every folder in display order (tests). */
    List<String> folderIdsInOrder() {
        List<String> ids = new ArrayList<>();
        collect(tree.getRoot(), ids);
        return ids;
    }

    private static void collect(TreeItem<Node> item, List<String> ids) {
        if (item == null) {
            return;
        }
        if (item.getValue() != null && item.getValue().kind() == Kind.FOLDER) {
            ids.add(item.getValue().folderId());
        }
        item.getChildren().forEach(child -> collect(child, ids));
    }
}
