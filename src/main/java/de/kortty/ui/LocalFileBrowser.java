package de.kortty.ui;

import de.kortty.core.RemoteTextFileSelectionSupport;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.SnippetManager;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import de.kortty.model.SnippetDiagram;
import de.kortty.telemetry.Telemetry;
import de.kortty.telemetry.TelemetryEvents;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

import java.awt.Desktop;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileOwnerAttributeView;
import java.nio.file.attribute.GroupPrincipal;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Compact local file browser panel that can be docked to the left or right side
 * of the main window.
 */
public class LocalFileBrowser extends VBox {

    private static final String PANEL_BACKGROUND = "#21252b";
    private static final String FOLDER_ICON_COLOR = "#8fa1b3";
    private static final String FILE_ICON_COLOR = "#abb2bf";
    private static final String HIDDEN_ICON_COLOR = "#636d7a";
    private static final Path UNIX_PASSWD_FILE = Paths.get("/etc/passwd");
    private static final Path UNIX_GROUP_FILE = Paths.get("/etc/group");

    /** Maximum size for files opened as text in the snippet editor. */
    private static final long MAX_TEXT_FILE_SIZE_BYTES = 10L * 1024 * 1024;

    private final Path homePath;
    private final TreeView<FileNode> treeView;
    private final FileBrowserHistory history = new FileBrowserHistory();
    private final Label statusLabel;
    private final Label footerLabel;
    private final ObservableList<FileNode> selectedItems = FXCollections.observableArrayList();
    private final ArrayList<File> clipboardFiles = new ArrayList<>();
    private final MainWindow ownerWindow;

    private HBox toolbar;
    private TextField pathBar;
    private TextField filterField;
    private StackPane contentStack;
    private Node loadingOverlay;
    private Button backButton;
    private Button forwardButton;
    private Button upButton;
    private ToggleButton showHiddenButton;
    private TreeItem<FileNode> rootItem;
    private Path currentRoot;
    private Path currentDirectory;
    private boolean showHiddenFiles = false;
    private boolean clipboardCut = false;
    private boolean renameRequested = false;
    private CheckMenuItem showHiddenMenuItem;
    private FileBrowserSort.Key sortKey = FileBrowserSort.Key.NAME;
    private boolean sortAscending = true;
    private String currentFilter = "";
    private volatile long rootLoadGeneration;
    private final Set<Path> pendingExpansion = new HashSet<>();

    public LocalFileBrowser() {
        this(null);
    }

    public LocalFileBrowser(MainWindow ownerWindow) {
        this.ownerWindow = ownerWindow;
        homePath = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        showHiddenFiles = loadShowHiddenSetting();
        currentRoot = loadInitialRoot();
        currentDirectory = currentRoot;

        setPadding(Insets.EMPTY);
        setSpacing(0);
        setStyle("-fx-background-color: " + PANEL_BACKGROUND + ";");
        getStyleClass().add("file-browser-panel");
        addStylesheet();

        statusLabel = new Label("");
        statusLabel.getStyleClass().add("file-browser-status");
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        footerLabel = new Label("");
        footerLabel.getStyleClass().add("file-browser-footer");
        footerLabel.setMaxWidth(Double.MAX_VALUE);

        treeView = new TreeView<>();
        treeView.setShowRoot(true);
        treeView.setFixedCellSize(22);
        treeView.setEditable(true);
        treeView.getStyleClass().add("file-browser-tree");
        treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        treeView.setCellFactory(view -> createTreeCell());
        treeView.setContextMenu(createContextMenu());
        treeView.getSelectionModel().getSelectedItems().addListener(
            (ListChangeListener<? super TreeItem<FileNode>>) change -> updateSelectedItems());
        treeView.setOnKeyPressed(this::handleTreeKey);
        installTreeDropHandlers();

        toolbar = buildToolbar();
        pathBar = buildPathBar();
        filterField = buildFilterField();
        loadingOverlay = buildLoadingOverlay();
        contentStack = new StackPane(treeView, loadingOverlay);
        VBox.setVgrow(contentStack, Priority.ALWAYS);

        getChildren().addAll(toolbar, pathBar, filterField, contentStack, statusLabel, footerLabel);

        history.navigate(currentRoot);
        setRoot(currentRoot);
    }

    private void addStylesheet() {
        URL stylesheet = LocalFileBrowser.class.getResource("/styles/filebrowser.css");
        if (stylesheet != null) {
            getStylesheets().add(stylesheet.toExternalForm());
        }
    }

    // ---- Persisted settings ----

    private de.kortty.core.GlobalSettingsManager settingsManager() {
        try {
            de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
            return app != null ? app.getGlobalSettingsManager() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean loadShowHiddenSetting() {
        de.kortty.core.GlobalSettingsManager manager = settingsManager();
        return manager != null && manager.getSettings().isFileBrowserShowHidden();
    }

    private Path loadInitialRoot() {
        de.kortty.core.GlobalSettingsManager manager = settingsManager();
        if (manager != null) {
            String last = manager.getSettings().getFileBrowserLastRoot();
            if (last != null && !last.isBlank()) {
                try {
                    Path candidate = Paths.get(last).toAbsolutePath().normalize();
                    if (Files.isDirectory(candidate)) {
                        return candidate;
                    }
                } catch (RuntimeException ignored) {
                    // fall back to home below
                }
            }
        }
        return homePath;
    }

    private void persistShowHidden() {
        de.kortty.core.GlobalSettingsManager manager = settingsManager();
        if (manager == null) {
            return;
        }
        manager.getSettings().setFileBrowserShowHidden(showHiddenFiles);
        saveSettings(manager);
    }

    private void persistLastRoot(Path root) {
        de.kortty.core.GlobalSettingsManager manager = settingsManager();
        if (manager == null || root == null) {
            return;
        }
        manager.getSettings().setFileBrowserLastRoot(root.toString());
        saveSettings(manager);
    }

    private static void saveSettings(de.kortty.core.GlobalSettingsManager manager) {
        try {
            manager.save();
        } catch (Exception e) {
            // best-effort persistence; ignore save failures
        }
    }

    // ---- Navigation ----

    private void setRoot(Path root) {
        currentRoot = root;
        currentDirectory = root;
        rootItem = toTreeItem(nodeFor(root));
        treeView.setRoot(rootItem);
        rootItem.setExpanded(true);
        if (pathBar != null) {
            pathBar.setText(FileBrowserPaths.abbreviateHome(root, homePath));
        }
        updateNavButtons();
    }

    private void navigateTo(Path target) {
        if (target == null) {
            return;
        }
        Path normalized = target.toAbsolutePath().normalize();
        if (!Files.isDirectory(normalized)) {
            setStatus(I18n.get("filebrowser.error.navigate"));
            return;
        }
        history.navigate(normalized);
        setRoot(normalized);
        persistLastRoot(normalized);
    }

    private void goBack() {
        if (!history.canGoBack()) {
            return;
        }
        Path target = history.back();
        if (target != null) {
            setRoot(target);
            persistLastRoot(target);
        }
    }

    private void goForward() {
        if (!history.canGoForward()) {
            return;
        }
        Path target = history.forward();
        if (target != null) {
            setRoot(target);
            persistLastRoot(target);
        }
    }

    private void goUp() {
        if (currentRoot != null && currentRoot.getParent() != null) {
            navigateTo(currentRoot.getParent());
        }
    }

    private void goHome() {
        navigateTo(homePath);
    }

    private void updateNavButtons() {
        if (backButton != null) {
            backButton.setDisable(!history.canGoBack());
        }
        if (forwardButton != null) {
            forwardButton.setDisable(!history.canGoForward());
        }
        if (upButton != null) {
            upButton.setDisable(currentRoot == null || currentRoot.getParent() == null);
        }
    }

    // ---- Toolbar / path / filter / loading UI ----

    private HBox buildToolbar() {
        backButton = toolbarButton(FileBrowserIcons.BACK, "filebrowser.tooltip.back", this::goBack);
        forwardButton = toolbarButton(FileBrowserIcons.FORWARD, "filebrowser.tooltip.forward", this::goForward);
        upButton = toolbarButton(FileBrowserIcons.UP, "filebrowser.tooltip.up", this::goUp);
        Button homeButton = toolbarButton(FileBrowserIcons.HOME, "filebrowser.tooltip.home", this::goHome);
        Button refreshButton = toolbarButton(FileBrowserIcons.REFRESH, "filebrowser.tooltip.refresh", this::refresh);
        Button newFolderButton = toolbarButton(FileBrowserIcons.NEW_FOLDER, "filebrowser.tooltip.newFolder",
            () -> { trackFileBrowserAction("new_folder"); createNewFolder(); });
        Button newFileButton = toolbarButton(FileBrowserIcons.NEW_FILE, "filebrowser.tooltip.newFile",
            () -> { trackFileBrowserAction("new_file"); createNewFile(); });

        showHiddenButton = new ToggleButton();
        showHiddenButton.getStyleClass().add("file-browser-toolbar-button");
        showHiddenButton.setFocusTraversable(false);
        showHiddenButton.setSelected(showHiddenFiles);
        updateHiddenIcon();
        showHiddenButton.setOnAction(e -> toggleShowHidden(showHiddenButton.isSelected()));

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(backButton, forwardButton, upButton, homeButton, refreshButton,
            spacer, newFolderButton, newFileButton, buildSortMenuButton(), showHiddenButton);
        bar.getStyleClass().add("file-browser-toolbar");
        return bar;
    }

    private Button toolbarButton(String glyph, String tooltipKey, Runnable action) {
        Button button = new Button();
        FileBrowserIcons.applyToolbarIcon(button, glyph, iconTint());
        button.getStyleClass().add("file-browser-toolbar-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(I18n.get(tooltipKey)));
        button.setOnAction(e -> action.run());
        return button;
    }

    private String iconTint() {
        return AppDesignStyleSupport.isCustomAppDesignActive()
            ? AppDesignStyleSupport.activeTextColor()
            : FILE_ICON_COLOR;
    }

    private void toggleShowHidden(boolean show) {
        showHiddenFiles = show;
        updateHiddenIcon();
        if (showHiddenMenuItem != null) {
            showHiddenMenuItem.setSelected(show);
        }
        persistShowHidden();
        refresh();
    }

    private void updateHiddenIcon() {
        if (showHiddenButton != null) {
            FileBrowserIcons.applyToolbarIcon(showHiddenButton,
                showHiddenFiles ? FileBrowserIcons.EYE : FileBrowserIcons.EYE_OFF, iconTint());
            showHiddenButton.setTooltip(new Tooltip(I18n.get(
                showHiddenFiles ? "filebrowser.tooltip.hideHidden" : "filebrowser.showHidden")));
        }
    }

    private MenuButton buildSortMenuButton() {
        MenuButton button = new MenuButton();
        FileBrowserIcons.applyToolbarIcon(button, FileBrowserIcons.SORT, iconTint());
        button.getStyleClass().add("file-browser-toolbar-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(I18n.get("filebrowser.tooltip.sort")));

        ToggleGroup keyGroup = new ToggleGroup();
        RadioMenuItem byName = sortKeyItem("filebrowser.sort.name", FileBrowserSort.Key.NAME, keyGroup);
        RadioMenuItem bySize = sortKeyItem("filebrowser.sort.size", FileBrowserSort.Key.SIZE, keyGroup);
        RadioMenuItem byDate = sortKeyItem("filebrowser.sort.date", FileBrowserSort.Key.DATE, keyGroup);

        ToggleGroup dirGroup = new ToggleGroup();
        RadioMenuItem asc = sortDirItem("filebrowser.sort.ascending", true, dirGroup);
        RadioMenuItem desc = sortDirItem("filebrowser.sort.descending", false, dirGroup);

        button.getItems().addAll(byName, bySize, byDate, new SeparatorMenuItem(), asc, desc);
        return button;
    }

    private RadioMenuItem sortKeyItem(String labelKey, FileBrowserSort.Key key, ToggleGroup group) {
        RadioMenuItem item = new RadioMenuItem(I18n.get(labelKey));
        item.setToggleGroup(group);
        item.setSelected(sortKey == key);
        item.setOnAction(e -> {
            sortKey = key;
            refresh();
        });
        return item;
    }

    private RadioMenuItem sortDirItem(String labelKey, boolean ascending, ToggleGroup group) {
        RadioMenuItem item = new RadioMenuItem(I18n.get(labelKey));
        item.setToggleGroup(group);
        item.setSelected(sortAscending == ascending);
        item.setOnAction(e -> {
            sortAscending = ascending;
            refresh();
        });
        return item;
    }

    private TextField buildPathBar() {
        TextField field = new TextField();
        field.getStyleClass().add("file-browser-path");
        field.setPromptText(I18n.get("filebrowser.path.placeholder"));
        field.setOnAction(e -> navigateTo(FileBrowserPaths.expandHome(field.getText(), homePath)));
        return field;
    }

    private TextField buildFilterField() {
        TextField field = new TextField();
        field.getStyleClass().add("file-browser-filter");
        field.setPromptText(I18n.get("filebrowser.filter.placeholder"));
        field.textProperty().addListener((obs, old, value) -> {
            currentFilter = value == null ? "" : value;
            refresh();
        });
        return field;
    }

    private Node buildLoadingOverlay() {
        ProgressIndicator indicator = new ProgressIndicator();
        indicator.setMaxSize(36, 36);
        Label label = new Label(I18n.get("filebrowser.loading"));
        VBox box = new VBox(8, indicator, label);
        box.setAlignment(javafx.geometry.Pos.CENTER);
        box.getStyleClass().add("file-browser-loading");
        box.setVisible(false);
        box.setManaged(false);
        box.setMouseTransparent(true);
        return box;
    }

    private void showLoading(boolean loading) {
        if (loadingOverlay != null) {
            loadingOverlay.setVisible(loading);
            loadingOverlay.setManaged(loading);
        }
    }

    // ---- Keyboard / drop / rename / copy-path ----

    private void handleTreeKey(javafx.scene.input.KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == KeyCode.ENTER) {
            openOrToggleSelected();
            event.consume();
        } else if (code == KeyCode.F2) {
            renameSelected();
            event.consume();
        } else if (code == KeyCode.DELETE || (code == KeyCode.BACK_SPACE && event.isShortcutDown())) {
            trackFileBrowserAction("delete");
            deleteSelectedFiles();
            event.consume();
        } else if (code == KeyCode.BACK_SPACE) {
            goUp();
            event.consume();
        } else if (code == KeyCode.R && event.isShortcutDown()) {
            refresh();
            event.consume();
        } else if (code == KeyCode.C && event.isShortcutDown()) {
            trackFileBrowserAction("copy");
            copySelectedFiles(false);
            event.consume();
        } else if (code == KeyCode.V && event.isShortcutDown()) {
            trackFileBrowserAction("paste");
            pasteFiles();
            event.consume();
        } else if (code == KeyCode.F && event.isShortcutDown()) {
            if (filterField != null) {
                filterField.requestFocus();
            }
            event.consume();
        }
    }

    private void openOrToggleSelected() {
        TreeItem<FileNode> item = treeView.getSelectionModel().getSelectedItem();
        if (item == null || item.getValue() == null || item.getValue().placeholder()) {
            return;
        }
        FileNode node = item.getValue();
        if (node.directory()) {
            ensureLoaded(item);
            item.setExpanded(!item.isExpanded());
        } else {
            trackFileBrowserAction("open");
            openFile(node.file());
        }
    }

    private void installTreeDropHandlers() {
        treeView.setOnDragOver(event -> {
            if (event.getGestureSource() != treeView && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                if (!treeView.getStyleClass().contains("drop-target")) {
                    treeView.getStyleClass().add("drop-target");
                }
            }
            event.consume();
        });
        treeView.setOnDragExited(event -> {
            treeView.getStyleClass().remove("drop-target");
            event.consume();
        });
        treeView.setOnDragDropped(event -> {
            Dragboard dragboard = event.getDragboard();
            boolean completed = false;
            if (dragboard.hasFiles()) {
                boolean move = event.getTransferMode() == TransferMode.MOVE;
                completed = handleDrop(dragboard.getFiles(), currentRoot, move);
            }
            treeView.getStyleClass().remove("drop-target");
            event.setDropCompleted(completed);
            event.consume();
        });
    }

    private boolean handleDrop(List<File> files, Path targetDir, boolean move) {
        if (targetDir == null || files == null || files.isEmpty()) {
            return false;
        }
        boolean any = false;
        for (File file : files) {
            Path source = file.toPath();
            Path sourceParent = source.getParent();
            if (targetDir.equals(sourceParent)) {
                continue;
            }
            boolean directory = Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS);
            if (directory && targetDir.startsWith(source)) {
                continue;
            }
            try {
                Path destination = FileBrowserPaths.uniqueDestination(targetDir, file.getName());
                if (move) {
                    if (directory) {
                        moveDirectory(source, destination);
                    } else {
                        Files.move(source, destination);
                    }
                } else if (directory) {
                    copyDirectory(source, destination);
                } else {
                    Files.copy(source, destination);
                }
                any = true;
            } catch (IOException | SecurityException e) {
                setStatus(I18n.get("filebrowser.error.drop") + ": " + e.getMessage());
            }
        }
        if (any) {
            refresh();
        }
        return any;
    }

    private void renameSelected() {
        TreeItem<FileNode> item = treeView.getSelectionModel().getSelectedItem();
        if (item == null || item.getValue() == null || item.getValue().placeholder() || item.getValue().loading()) {
            return;
        }
        renameRequested = true;
        treeView.edit(item);
    }

    private void performRename(TreeCell<FileNode> cell, String newName) {
        FileNode node = cell.getItem();
        cell.cancelEdit();
        if (node == null || node.placeholder() || newName == null) {
            return;
        }
        String trimmed = newName.trim();
        if (trimmed.isEmpty() || trimmed.equals(node.name())) {
            return;
        }
        Path source = node.file().toPath();
        Path parent = source.getParent();
        if (parent == null) {
            return;
        }
        try {
            Path target = parent.resolve(trimmed);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                target = FileBrowserPaths.uniqueDestination(parent, trimmed);
            }
            Files.move(source, target);
            trackFileBrowserAction("rename");
            refresh();
        } catch (IOException | SecurityException e) {
            setStatus(I18n.get("filebrowser.error.rename") + ": " + e.getMessage());
        }
    }

    private void copySelectedPath() {
        FileNode node = getFirstSelectedNode();
        if (node == null) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(node.file().getAbsolutePath());
        Clipboard.getSystemClipboard().setContent(content);
        setStatus(I18n.get("filebrowser.path.copied"));
    }

    private FileNode getFirstSelectedNode() {
        for (FileNode node : selectedItems) {
            if (node != null && !node.placeholder()) {
                return node;
            }
        }
        return null;
    }

    private TreeCell<FileNode> createTreeCell() {
        return new FileBrowserTreeCell();
    }

    private final class FileBrowserTreeCell extends TreeCell<FileNode> {
        private TextField editor;

        FileBrowserTreeCell() {
            setOnMouseClicked(this::onMouseClicked);
            setOnContextMenuRequested(this::onContextMenuRequested);
            setOnDragDetected(this::onDragDetected);
            setOnDragOver(this::onDragOver);
            setOnDragExited(event -> {
                getStyleClass().remove("drop-target");
                event.consume();
            });
            setOnDragDropped(this::onDragDropped);
        }

        @Override
        protected void updateItem(FileNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null || item.placeholder()) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (item.loading()) {
                setText(I18n.get("filebrowser.loading"));
                setGraphic(null);
                return;
            }
            if (isEditing() && editor != null) {
                editor.setText(item.name());
                setText(null);
                setGraphic(editor);
                return;
            }
            setText(item.name());
            setGraphic(createIcon(item, getTreeItem()));
        }

        @Override
        public void startEdit() {
            FileNode node = getItem();
            if (node == null || node.placeholder() || node.loading()) {
                return;
            }
            if (!renameRequested) {
                // Only enter rename on an explicit request (context menu / F2),
                // never from a single- or double-click on the row.
                return;
            }
            renameRequested = false;
            super.startEdit();
            if (!isEditing()) {
                return;
            }
            if (editor == null) {
                editor = createRenameEditor(this);
            }
            editor.setText(node.name());
            setText(null);
            setGraphic(editor);
            editor.selectAll();
            editor.requestFocus();
        }

        @Override
        public void cancelEdit() {
            super.cancelEdit();
            FileNode node = getItem();
            setText(node == null ? null : node.name());
            setGraphic(node == null ? null : createIcon(node, getTreeItem()));
        }

        private void onMouseClicked(javafx.scene.input.MouseEvent event) {
            FileNode node = getItem();
            if (node == null || node.placeholder() || node.loading()) {
                return;
            }
            updateCurrentDirectory(node);
            if (event.getClickCount() == 2) {
                if (node.directory()) {
                    TreeItem<FileNode> item = getTreeItem();
                    if (item != null) {
                        ensureLoaded(item);
                        item.setExpanded(!item.isExpanded());
                    }
                } else {
                    trackFileBrowserAction("open");
                    openFile(node.file());
                }
                // Suppress the editable-cell default (which would start a rename on double-click).
                event.consume();
            }
        }

        private void onContextMenuRequested(javafx.scene.input.ContextMenuEvent event) {
            if (!isEmpty() && getTreeItem() != null) {
                MultipleSelectionModel<TreeItem<FileNode>> selectionModel = treeView.getSelectionModel();
                if (!selectionModel.isSelected(getIndex())) {
                    selectionModel.clearSelection();
                    selectionModel.select(getIndex());
                }
                updateCurrentDirectory(getItem());
            }
        }

        private void onDragDetected(javafx.scene.input.MouseEvent event) {
            FileNode node = getItem();
            if (node == null || node.placeholder() || node.loading()) {
                return;
            }
            List<File> files = selectedItems.isEmpty()
                ? List.of(node.file())
                : selectedItems.stream().map(FileNode::file).collect(Collectors.toList());
            if (files.isEmpty()) {
                return;
            }
            Dragboard dragboard = startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.putFiles(files);
            dragboard.setContent(content);
            event.consume();
        }

        private void onDragOver(javafx.scene.input.DragEvent event) {
            FileNode node = getItem();
            if (event.getGestureSource() != this && event.getDragboard().hasFiles()
                && node != null && !node.placeholder() && !node.loading()) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                if (!getStyleClass().contains("drop-target")) {
                    getStyleClass().add("drop-target");
                }
                event.consume();
            }
        }

        private void onDragDropped(javafx.scene.input.DragEvent event) {
            FileNode node = getItem();
            Dragboard dragboard = event.getDragboard();
            boolean completed = false;
            if (node != null && dragboard.hasFiles()) {
                Path target = node.directory() ? node.file().toPath() : node.file().toPath().getParent();
                boolean move = event.getTransferMode() == TransferMode.MOVE;
                completed = handleDrop(dragboard.getFiles(), target, move);
            }
            getStyleClass().remove("drop-target");
            event.setDropCompleted(completed);
            event.consume();
        }
    }

    private TextField createRenameEditor(TreeCell<FileNode> cell) {
        TextField field = new TextField();
        field.getStyleClass().add("file-browser-rename");
        field.setOnAction(event -> {
            performRename(cell, field.getText());
            event.consume();
        });
        field.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                cell.cancelEdit();
                event.consume();
            }
        });
        return field;
    }

    private Node createIcon(FileNode node, TreeItem<FileNode> item) {
        boolean expanded = item != null && item.isExpanded();
        FileBrowserIcons.IconKind kind =
            FileBrowserIcons.kindFor(node.name(), node.directory(), expanded, node.executable());
        return FileBrowserIcons.treeIcon(kind, resolveIconColor(node), node.hidden(), node.symlink(), PANEL_BACKGROUND);
    }

    private String resolveIconColor(FileNode node) {
        if (AppDesignStyleSupport.isCustomAppDesignActive()) {
            return node.hidden() ? AppDesignStyleSupport.activeDimColor() : AppDesignStyleSupport.activeTextColor();
        }
        return node.hidden() ? HIDDEN_ICON_COLOR : (node.directory() ? FOLDER_ICON_COLOR : FILE_ICON_COLOR);
    }

    private void trackFileBrowserAction(String action) {
        Telemetry.track(TelemetryEvents.FILE_BROWSER_ACTION, java.util.Map.of("action", action));
    }

    private ContextMenu createContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem openItem = new MenuItem(I18n.get("filebrowser.context.open"));
        openItem.setOnAction(event -> { trackFileBrowserAction("open"); openSelected(); });

        MenuItem loadAsTextFileItem = new MenuItem(I18n.get("filebrowser.context.loadAsTextFile"));
        loadAsTextFileItem.setOnAction(event -> { trackFileBrowserAction("load_as_text"); loadSelectedFileAsTextFile(); });

        MenuItem copyItem = new MenuItem(I18n.get("filebrowser.context.copy"));
        copyItem.setOnAction(event -> { trackFileBrowserAction("copy"); copySelectedFiles(false); });

        MenuItem cutItem = new MenuItem(I18n.get("filebrowser.context.cut"));
        cutItem.setOnAction(event -> { trackFileBrowserAction("cut"); copySelectedFiles(true); });

        MenuItem pasteItem = new MenuItem(I18n.get("filebrowser.context.paste"));
        pasteItem.setOnAction(event -> { trackFileBrowserAction("paste"); pasteFiles(); });

        MenuItem deleteItem = new MenuItem(I18n.get("filebrowser.context.delete"));
        deleteItem.setOnAction(event -> { trackFileBrowserAction("delete"); deleteSelectedFiles(); });

        MenuItem renameItem = new MenuItem(I18n.get("filebrowser.context.rename"));
        renameItem.setOnAction(event -> renameSelected());

        MenuItem copyPathItem = new MenuItem(I18n.get("filebrowser.context.copyPath"));
        copyPathItem.setOnAction(event -> { trackFileBrowserAction("copy_path"); copySelectedPath(); });

        MenuItem selectAllItem = new MenuItem(I18n.get("filebrowser.context.selectAll"));
        selectAllItem.setOnAction(event -> selectAllFiles());

        MenuItem detailsItem = new MenuItem(I18n.get("filebrowser.context.details"));
        detailsItem.setOnAction(event -> { trackFileBrowserAction("details"); showDetails(); });

        Menu archiveMenu = new Menu(I18n.get("filebrowser.context.archive"));
        MenuItem zipItem = new MenuItem("ZIP");
        zipItem.setOnAction(event -> { trackFileBrowserAction("archive"); archiveSelected("zip"); });
        MenuItem tarItem = new MenuItem("TAR");
        tarItem.setOnAction(event -> { trackFileBrowserAction("archive"); archiveSelected("tar"); });
        MenuItem tgzItem = new MenuItem("TAR.GZ");
        tgzItem.setOnAction(event -> { trackFileBrowserAction("archive"); archiveSelected("tgz"); });
        archiveMenu.getItems().addAll(zipItem, tarItem, tgzItem);

        MenuItem newFolderItem = new MenuItem(I18n.get("filebrowser.context.newFolder"));
        newFolderItem.setOnAction(event -> { trackFileBrowserAction("new_folder"); createNewFolder(); });

        MenuItem newFileItem = new MenuItem(I18n.get("filebrowser.context.newFile"));
        newFileItem.setOnAction(event -> { trackFileBrowserAction("new_file"); createNewFile(); });

        MenuItem ownerPermissionsItem = new MenuItem(I18n.get("sftp.setOwner.title"));
        ownerPermissionsItem.setOnAction(event -> { trackFileBrowserAction("owner_permissions"); setOwnerPermissionsDialog(); });

        showHiddenMenuItem = new CheckMenuItem(I18n.get("filebrowser.showHidden"));
        showHiddenMenuItem.setSelected(showHiddenFiles);
        showHiddenMenuItem.setOnAction(event -> toggleShowHidden(showHiddenMenuItem.isSelected()));

        menu.getItems().addAll(
            openItem,
            loadAsTextFileItem,
            renameItem,
            new SeparatorMenuItem(),
            copyItem,
            cutItem,
            pasteItem,
            copyPathItem,
            deleteItem,
            new SeparatorMenuItem(),
            newFolderItem,
            newFileItem,
            ownerPermissionsItem,
            archiveMenu,
            detailsItem,
            new SeparatorMenuItem(),
            showHiddenMenuItem,
            selectAllItem);
        menu.setOnShowing(event -> loadAsTextFileItem.setDisable(getSingleSelectedFile() == null));
        return menu;
    }

    private void updateSelectedItems() {
        selectedItems.setAll(treeView.getSelectionModel().getSelectedItems().stream()
            .map(TreeItem::getValue)
            .filter(node -> node != null && !node.placeholder() && !node.loading())
            .toList());
        if (!selectedItems.isEmpty()) {
            updateCurrentDirectory(selectedItems.get(0));
        }
        updateCounts();
    }

    private void updateCurrentDirectory(FileNode node) {
        if (node == null || node.placeholder() || node.loading()) {
            return;
        }
        currentDirectory = node.directory() ? node.file().toPath() : node.file().toPath().getParent();
        if (currentDirectory == null) {
            currentDirectory = currentRoot;
        }
    }

    private void openSelected() {
        for (FileNode node : selectedItems) {
            if (node.directory()) {
                currentDirectory = node.file().toPath();
            } else {
                openFile(node.file());
            }
        }
    }

    private FileNode getSingleSelectedFile() {
        if (selectedItems.size() != 1) {
            return null;
        }
        FileNode node = selectedItems.get(0);
        return node != null && !node.placeholder() && !node.directory() ? node : null;
    }

    private void loadSelectedFileAsTextFile() {
        FileNode node = getSingleSelectedFile();
        if (node == null) {
            return;
        }
        Path filePath = node.file().toPath();
        if (node.size() > MAX_TEXT_FILE_SIZE_BYTES) {
            showLoadAsTextAlert(Alert.AlertType.WARNING,
                I18n.get("filebrowser.loadAsTextFile.tooLarge", MAX_TEXT_FILE_SIZE_BYTES / (1024 * 1024)));
            return;
        }
        Thread loader = new Thread(() -> {
            try {
                byte[] bytes = Files.readAllBytes(filePath);
                String content = RemoteTextFileSelectionSupport.decodeUtf8TextFile(bytes);
                Telemetry.track(TelemetryEvents.FILE_LOADED_AS_TEXT, java.util.Map.of("source", "file_browser"));
                Platform.runLater(() -> openSnippetFileDialog(filePath, content));
            } catch (RemoteTextFileSelectionSupport.BinaryOrNonTextFileException e) {
                Platform.runLater(() -> showLoadAsTextAlert(Alert.AlertType.WARNING,
                    I18n.get("filebrowser.loadAsTextFile.binary")));
            } catch (Exception e) {
                Platform.runLater(() -> showLoadAsTextAlert(Alert.AlertType.ERROR,
                    I18n.get("sftp.snippetEditor.loadFailed",
                        e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName())));
            }
        }, "filebrowser-text-loader");
        loader.setDaemon(true);
        loader.start();
    }

    private void showLoadAsTextAlert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message);
        alert.setTitle(I18n.get("filebrowser.context.loadAsTextFile"));
        alert.setHeaderText(null);
        DialogThemeHelper.applyTheme(alert);
        if (getScene() != null && getScene().getWindow() != null) {
            alert.initOwner(getScene().getWindow());
        }
        alert.showAndWait();
    }

    private void openSnippetFileDialog(Path filePath, String content) {
        String fileName = filePath.getFileName() != null ? filePath.getFileName().toString() : filePath.toString();
        Snippet snippet = new Snippet();
        snippet.setName(fileName);
        snippet.setContent(content);
        snippet.setLanguage(SnippetLanguageSupport.detectFileLanguage(fileName, content));
        snippet.setCategory("");
        snippet.setDescription("");
        snippet.setTagsFromString("");

        SnippetEditDialog.ExternalFileActionConfig config = new SnippetEditDialog.ExternalFileActionConfig(
            filePath.toString(),
            I18n.get("sftp.snippetEditor.overwriteLocal"),
            I18n.get("sftp.snippetEditor.saveAs"),
            I18n.get("sftp.snippetEditor.saveSnippet"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedFile"),
            I18n.get("sftp.snippetEditor.savedSnippet"),
            draft -> overwriteTextFile(filePath, draft),
            draft -> saveTextFileAs(filePath, draft),
            this::saveDraftAsSnippet);

        List<String> categoryNames = List.of();
        SnippetManager snippetManager = getSnippetManager();
        if (snippetManager != null) {
            categoryNames = snippetManager.getAllCategories().stream()
                .map(SnippetCategory::getName)
                .toList();
        }
        SnippetEditDialog.AiAssist aiAssist = SnippetAiAssistFactory.create(ownerWindow);
        SnippetEditDialog dialog = new SnippetEditDialog(snippet, categoryNames, aiAssist, config);
        if (getScene() != null && getScene().getWindow() != null) {
            dialog.initOwner(getScene().getWindow());
        }
        dialog.showNonBlocking(null);
    }

    private SnippetManager getSnippetManager() {
        try {
            de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
            return app != null ? app.getSnippetManager() : null;
        } catch (Exception e) {
            return null;
        }
    }

    private boolean overwriteTextFile(Path filePath, Snippet draft) throws IOException {
        Files.writeString(
            filePath,
            draft.getContent(),
            StandardCharsets.UTF_8,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);
        Platform.runLater(this::refresh);
        return true;
    }

    private boolean saveTextFileAs(Path sourcePath, Snippet draft) throws Exception {
        File targetFile = callOnFxThread(() -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get("sftp.snippetEditor.saveAs"));
            if (sourcePath.getParent() != null && Files.isDirectory(sourcePath.getParent())) {
                chooser.setInitialDirectory(sourcePath.getParent().toFile());
            }
            if (sourcePath.getFileName() != null) {
                chooser.setInitialFileName(sourcePath.getFileName().toString());
            }
            return chooser.showSaveDialog(getScene() != null ? getScene().getWindow() : null);
        });
        if (targetFile == null) {
            return false;
        }
        Path targetPath = targetFile.toPath();
        if (Files.exists(targetPath) && !confirmTextFileOverwrite(targetPath.toString())) {
            return false;
        }
        Files.writeString(
            targetPath,
            draft.getContent(),
            StandardCharsets.UTF_8,
            StandardOpenOption.CREATE,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING);
        Platform.runLater(this::refresh);
        return true;
    }

    private boolean confirmTextFileOverwrite(String targetPath) throws Exception {
        return callOnFxThread(() -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(I18n.get("sftp.snippetEditor.confirmOverwrite.title"));
            alert.setHeaderText(I18n.get("sftp.snippetEditor.confirmOverwrite.header"));
            alert.setContentText(I18n.get("sftp.snippetEditor.confirmOverwrite.content", targetPath));
            DialogThemeHelper.applyTheme(alert);
            if (getScene() != null && getScene().getWindow() != null) {
                alert.initOwner(getScene().getWindow());
            }
            return alert.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
        });
    }

    private boolean saveDraftAsSnippet(Snippet draft) throws Exception {
        SnippetManager snippetManager = getSnippetManager();
        if (snippetManager == null) {
            throw new IllegalStateException("Snippet manager not initialized");
        }
        Snippet snippet = new Snippet();
        snippet.setName(draft.getName());
        snippet.setContent(draft.getContent());
        snippet.setLanguage(draft.getLanguage());
        snippet.setCategory(draft.getCategory());
        snippet.setDescription(draft.getDescription());
        snippet.setTags(new ArrayList<>(draft.getTags()));
        List<SnippetDiagram> diagramCopies = new ArrayList<>();
        for (SnippetDiagram diagram : draft.getDiagrams()) {
            if (diagram != null) {
                diagramCopies.add(new SnippetDiagram(diagram));
            }
        }
        snippet.setDiagrams(diagramCopies);
        String categoryName = snippet.getCategory();
        if (categoryName != null && !categoryName.isBlank()
            && snippetManager.findCategoryByName(categoryName.trim()).isEmpty()) {
            snippetManager.addCategory(new SnippetCategory(categoryName.trim()));
        }
        snippetManager.addSnippet(snippet);
        snippetManager.save();
        return true;
    }

    private <T> T callOnFxThread(Supplier<T> supplier) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return supplier.get();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future.get();
    }

    private TreeItem<FileNode> toTreeItem(FileNode node) {
        TreeItem<FileNode> item = new TreeItem<>(node);
        if (node.directory() && !node.placeholder() && !node.loading()) {
            item.getChildren().add(new TreeItem<>(FileNode.placeholderNode()));
            item.expandedProperty().addListener((observable, wasExpanded, expanded) -> {
                if (expanded) {
                    ensureLoaded(item);
                    treeView.refresh();
                }
            });
        }
        return item;
    }

    private void ensureLoaded(TreeItem<FileNode> item) {
        if (item == null || item.getValue() == null || !item.getValue().directory()) {
            return;
        }
        if (item.getChildren().size() == 1 && item.getChildren().get(0).getValue().placeholder()) {
            loadTreeChildren(item);
        }
    }

    private void loadTreeChildren(TreeItem<FileNode> parent) {
        FileNode parentNode = parent.getValue();
        if (parentNode == null || !parentNode.directory()) {
            return;
        }
        // Swap the placeholder for a loading sentinel so a re-expansion mid-load cannot start a second load.
        parent.getChildren().setAll(new TreeItem<>(FileNode.loadingNode()));
        boolean rootLevel = parent == rootItem;
        long generation = rootLevel ? ++rootLoadGeneration : 0;
        if (rootLevel) {
            showLoading(true);
        }
        Path directory = parentNode.file().toPath();
        boolean showHidden = showHiddenFiles;
        String filter = currentFilter;
        FileBrowserSort.Key key = sortKey;
        boolean ascending = sortAscending;
        CompletableFuture
            .supplyAsync(() -> listChildren(directory, showHidden, filter, key, ascending))
            .whenComplete((children, error) -> Platform.runLater(
                () -> applyLoadedChildren(parent, rootLevel, generation, children, error)));
    }

    private void applyLoadedChildren(TreeItem<FileNode> parent, boolean rootLevel, long generation,
                                     List<FileNode> children, Throwable error) {
        if (rootLevel && generation == rootLoadGeneration) {
            showLoading(false);
        }
        if (!hasSingleLoadingChild(parent)) {
            return;
        }
        if (error != null) {
            parent.getChildren().clear();
            setStatus(I18n.get("filebrowser.error.accessDenied"));
            return;
        }
        List<TreeItem<FileNode>> items = children.stream().map(this::toTreeItem).collect(Collectors.toList());
        parent.getChildren().setAll(items);
        setStatus("");
        restorePendingExpansion(items);
        if (rootLevel) {
            updateCounts();
        }
    }

    private Set<Path> captureExpandedPaths() {
        Set<Path> expanded = new HashSet<>();
        if (rootItem != null) {
            for (TreeItem<FileNode> child : rootItem.getChildren()) {
                collectExpanded(child, expanded);
            }
        }
        return expanded;
    }

    private void collectExpanded(TreeItem<FileNode> item, Set<Path> out) {
        FileNode node = item.getValue();
        if (node == null || node.placeholder() || node.loading() || !node.directory()) {
            return;
        }
        if (item.isExpanded()) {
            out.add(node.file().toPath());
            for (TreeItem<FileNode> child : item.getChildren()) {
                collectExpanded(child, out);
            }
        }
    }

    private void restorePendingExpansion(List<TreeItem<FileNode>> items) {
        if (pendingExpansion.isEmpty()) {
            return;
        }
        for (TreeItem<FileNode> child : items) {
            FileNode node = child.getValue();
            if (node != null && node.directory() && pendingExpansion.remove(node.file().toPath())) {
                child.setExpanded(true);
            }
        }
    }

    private static boolean hasSingleLoadingChild(TreeItem<FileNode> parent) {
        return parent.getChildren().size() == 1
            && parent.getChildren().get(0).getValue() != null
            && parent.getChildren().get(0).getValue().loading();
    }

    private static List<FileNode> listChildren(Path directory, boolean showHidden, String filter,
                                               FileBrowserSort.Key key, boolean ascending) {
        try (var stream = Files.list(directory)) {
            return stream
                .map(LocalFileBrowser::nodeFor)
                .filter(node -> (showHidden || !node.name().startsWith("."))
                    && FileBrowserPaths.matchesFilter(node.name(), filter))
                .sorted(FileBrowserSort.comparator(key, ascending))
                .collect(Collectors.toList());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static FileNode nodeFor(Path path) {
        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        boolean symlink = Files.isSymbolicLink(path);
        boolean executable = !directory && Files.isExecutable(path);
        boolean hidden = isHidden(path);
        long size = 0;
        long lastModified = 0;
        try {
            BasicFileAttributes attributes =
                Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            size = directory ? 0 : attributes.size();
            lastModified = attributes.lastModifiedTime().toMillis();
        } catch (IOException | SecurityException ignored) {
            // leave size/lastModified at defaults
        }
        return new FileNode(path.toFile(), directory, hidden, false, false, symlink, executable, size, lastModified);
    }

    private static boolean isHidden(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException e) {
            Path name = path.getFileName();
            return name != null && name.toString().startsWith(".");
        }
    }

    private void updateCounts() {
        if (rootItem == null || footerLabel == null) {
            return;
        }
        long folders = 0;
        long files = 0;
        for (TreeItem<FileNode> child : rootItem.getChildren()) {
            FileNode node = child.getValue();
            if (node == null || node.placeholder() || node.loading()) {
                continue;
            }
            if (node.directory()) {
                folders++;
            } else {
                files++;
            }
        }
        footerLabel.setText(I18n.get("filebrowser.status.summary", folders, files, selectedItems.size()));
    }

    private void copySelectedFiles(boolean cut) {
        clipboardFiles.clear();
        clipboardCut = cut;
        for (FileNode node : selectedItems) {
            clipboardFiles.add(node.file());
        }
        clipboardFiles.trimToSize();
        setStatus(I18n.get("filebrowser.copied", clipboardFiles.size()));
    }

    private void pasteFiles() {
        if (clipboardFiles.isEmpty()) {
            return;
        }
        Path target = selectedTargetDirectory();
        for (File source : clipboardFiles) {
            try {
                Path destination = target.resolve(source.getName());
                if (clipboardCut) {
                    if (source.isDirectory()) {
                        moveDirectory(source.toPath(), destination);
                    } else {
                        Files.move(source.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
                    }
                } else if (source.isDirectory()) {
                    copyDirectory(source.toPath(), destination);
                } else {
                    Files.copy(source.toPath(), destination, StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (IOException | SecurityException e) {
                setStatus(I18n.get("filebrowser.error.paste") + ": " + e.getMessage());
            }
        }
        if (clipboardCut) {
            clipboardFiles.clear();
            clipboardCut = false;
        }
        refresh();
    }

    private Path selectedTargetDirectory() {
        if (selectedItems.size() == 1 && selectedItems.get(0).directory()) {
            return selectedItems.get(0).file().toPath();
        }
        return currentDirectory != null ? currentDirectory : currentRoot;
    }

    static void moveDirectory(Path source, Path destination) throws IOException {
        try {
            Files.move(
                source,
                destination,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException atomicMoveFailure) {
            validateDirectoryMoveFallback(source, destination);
            copyDirectory(source, destination);
            try {
                deleteDirectory(source);
            } catch (IOException | SecurityException deleteFailure) {
                PartialMoveException partialMoveException =
                    new PartialMoveException(source, destination, deleteFailure);
                partialMoveException.addSuppressed(atomicMoveFailure);
                throw partialMoveException;
            }
        }
    }

    private static void validateDirectoryMoveFallback(Path source, Path destination) throws IOException {
        Path sourceParent = source.getParent();
        if (sourceParent != null) {
            requireWritable(sourceParent, "Source parent directory is not writable; source may not be deletable");
        }
        requireDirectoryTreeWritable(source);

        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(destination.toString());
            }
            requireWritable(destination, "Destination directory is not writable");
            return;
        }
        Path destinationParent = destination.getParent();
        if (destinationParent != null) {
            requireWritable(destinationParent, "Destination parent directory is not writable");
        }
    }

    private static void requireDirectoryTreeWritable(Path directory) throws IOException {
        requireWritable(directory, "Source directory tree is not writable; fallback cleanup may fail");
        try (var stream = Files.list(directory)) {
            for (Path file : stream.toList()) {
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    requireDirectoryTreeWritable(file);
                }
            }
        }
    }

    private static void requireWritable(Path path, String reason) throws IOException {
        if (!Files.isWritable(path)) {
            throw new AccessDeniedException(path.toString(), null, reason);
        }
    }

    private static void copyDirectory(Path source, Path destination) throws IOException {
        if (Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)) {
                throw new java.nio.file.FileAlreadyExistsException(destination.toString());
            }
        } else {
            Files.createDirectory(destination);
        }
        try (var stream = Files.list(source)) {
            for (Path file : stream.toList()) {
                Path destinationFile = destination.resolve(file.getFileName());
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    copyDirectory(file, destinationFile);
                } else {
                    Files.copy(file, destinationFile, StandardCopyOption.REPLACE_EXISTING);
                }
            }
        }
    }

    private void deleteSelectedFiles() {
        if (selectedItems.isEmpty()) {
            return;
        }
        if (!Desktop.isDesktopSupported()
            || !Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH)) {
            setStatus(I18n.get("filebrowser.error.trashNotSupported"));
            return;
        }
        if (!confirmMoveToTrash(selectedItems.size())) {
            return;
        }
        List<FileNode> targets = List.copyOf(selectedItems);
        int trashed = 0;
        for (FileNode node : targets) {
            try {
                if (Desktop.getDesktop().moveToTrash(node.file())) {
                    trashed++;
                } else {
                    setStatus(I18n.get("filebrowser.error.trashNotSupported"));
                }
            } catch (SecurityException | IllegalArgumentException e) {
                setStatus(I18n.get("filebrowser.error.delete") + ": " + e.getMessage());
            }
        }
        selectedItems.clear();
        refresh();
        if (trashed > 0) {
            setStatus(I18n.get("filebrowser.trashed", trashed));
        }
    }

    private boolean confirmMoveToTrash(int count) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.get("filebrowser.delete.confirm.title"));
        alert.setHeaderText(I18n.get("filebrowser.delete.confirm.header"));
        alert.setContentText(I18n.get("filebrowser.delete.confirm.content", count));
        DialogThemeHelper.applyTheme(alert);
        if (getScene() != null && getScene().getWindow() != null) {
            alert.initOwner(getScene().getWindow());
        }
        return alert.showAndWait().filter(button -> button == ButtonType.OK).isPresent();
    }

    private static void deleteDirectory(Path directory) throws IOException {
        try (var stream = Files.list(directory)) {
            for (Path file : stream.toList()) {
                if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
                    deleteDirectory(file);
                } else {
                    Files.delete(file);
                }
            }
        }
        Files.delete(directory);
    }

    static class PartialMoveException extends IOException {
        PartialMoveException(Path source, Path destination, Throwable cause) {
            super(
                "Directory was copied to " + destination
                    + " but deleting source " + source
                    + " failed; source may remain: " + cause.getMessage(),
                cause);
        }
    }

    private void setOwnerPermissionsDialog() {
        if (selectedItems.isEmpty()) {
            return;
        }

        List<FileNode> items = List.copyOf(selectedItems);
        Path firstPath = items.get(0).file().toPath();
        boolean ownerSupported = isOwnerSupported(firstPath);
        boolean posixSupported = isPosixSupported(firstPath);
        if (!ownerSupported && !posixSupported) {
            setStatus(I18n.get("filebrowser.setOwner.notSupported"));
            return;
        }

        String currentOwner = ownerSupported ? getLocalFileOwner(firstPath) : "";
        String currentGroup = posixSupported ? getLocalFileGroup(firstPath) : "";
        String currentPermissions = posixSupported ? getOctalPermissions(firstPath) : "";

        javafx.scene.control.Dialog<ButtonType> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle(I18n.get("sftp.setOwner.title"));
        dialog.setHeaderText(I18n.get("sftp.setOwner.header", items.size()));
        DialogThemeHelper.applyTheme(dialog);

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(16));

        int row = 0;
        grid.add(new Label(I18n.get("sftp.setOwner.ownerUser")), 0, row);
        ComboBox<String> ownerField = editableComboBox(availableOwners(currentOwner), currentOwner);
        ownerField.setPromptText("user");
        ownerField.setDisable(!ownerSupported);
        grid.add(ownerField, 1, row++);

        grid.add(new Label(I18n.get("sftp.setOwner.ownerGroup")), 0, row);
        ComboBox<String> groupField = editableComboBox(availableGroups(currentGroup), currentGroup);
        groupField.setPromptText("group");
        groupField.setDisable(!posixSupported);
        grid.add(groupField, 1, row++);

        grid.add(new Label(I18n.get("sftp.setOwner.permissions")), 0, row);
        TextField permissionsField = new TextField(currentPermissions);
        permissionsField.setPromptText("755");
        permissionsField.setDisable(!posixSupported);
        grid.add(permissionsField, 1, row++);

        Label infoLabel = new Label(I18n.get("sftp.setOwner.infoSeparate"));
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        grid.add(infoLabel, 0, row, 2, 1);

        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.showAndWait().ifPresent(result -> {
            if (result != ButtonType.OK) {
                return;
            }
            String newOwner = ownerSupported ? comboBoxText(ownerField) : "";
            String newGroup = posixSupported ? comboBoxText(groupField) : "";
            String newPermissions = posixSupported ? permissionsField.getText().trim() : "";
            boolean ownerChanged = ownerSupported && !newOwner.isBlank() && !newOwner.equals(currentOwner);
            boolean groupChanged = posixSupported && !newGroup.isBlank() && !newGroup.equals(currentGroup);
            boolean permissionsChanged =
                posixSupported && !newPermissions.isBlank() && !newPermissions.equals(currentPermissions);
            if (!ownerChanged && !groupChanged && !permissionsChanged) {
                return;
            }
            if (permissionsChanged && !isValidOctalPermissions(newPermissions)) {
                setStatus(I18n.get("error.invalidInput") + ": " + I18n.get("sftp.setOwner.permissions"));
                return;
            }
            applyOwnerPermissions(
                items,
                ownerChanged ? newOwner : "",
                groupChanged ? newGroup : "",
                permissionsChanged ? newPermissions : "");
        });
    }

    private ComboBox<String> editableComboBox(List<String> values, String currentValue) {
        ComboBox<String> comboBox = new ComboBox<>(FXCollections.observableArrayList(values));
        comboBox.setEditable(true);
        comboBox.setMaxWidth(Double.MAX_VALUE);
        if (currentValue != null && !currentValue.isBlank()) {
            comboBox.setValue(currentValue);
        }
        return comboBox;
    }

    private String comboBoxText(ComboBox<String> comboBox) {
        String text = comboBox.getEditor() != null ? comboBox.getEditor().getText() : comboBox.getValue();
        return text != null ? text.trim() : "";
    }

    private boolean isOwnerSupported(Path path) {
        try {
            return Files.getFileAttributeView(path, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null;
        } catch (SecurityException e) {
            return false;
        }
    }

    private boolean isPosixSupported(Path path) {
        try {
            return Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null;
        } catch (SecurityException e) {
            return false;
        }
    }

    private String getLocalFileOwner(Path path) {
        try {
            FileOwnerAttributeView view =
                Files.getFileAttributeView(path, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            if (view == null) {
                return "";
            }
            UserPrincipal owner = view.getOwner();
            return owner != null ? owner.getName() : "";
        } catch (IOException | SecurityException e) {
            return "";
        }
    }

    private String getLocalFileGroup(Path path) {
        try {
            PosixFileAttributeView view =
                Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            return view != null ? view.readAttributes().group().getName() : "";
        } catch (IOException | SecurityException e) {
            return "";
        }
    }

    private String getOctalPermissions(Path path) {
        try {
            PosixFileAttributeView view =
                Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            return view != null ? permissionsToOctal(view.readAttributes().permissions()) : "";
        } catch (IOException | SecurityException e) {
            return "";
        }
    }

    private void applyOwnerPermissions(List<FileNode> items, String owner, String group, String permissions) {
        int changed = 0;
        int failed = 0;
        String lastError = "";

        for (FileNode item : items) {
            Path path = item.file().toPath();
            try {
                if (!owner.isBlank()) {
                    setOwner(path, owner);
                }
                if (!group.isBlank()) {
                    setGroup(path, group);
                }
                if (!permissions.isBlank()) {
                    setPermissions(path, permissions);
                }
                changed++;
            } catch (IOException | RuntimeException e) {
                failed++;
                lastError = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            }
        }

        refresh();
        if (failed == 0) {
            setStatus(I18n.get("sftp.setOwner.success", changed));
        } else {
            setStatus(I18n.get("sftp.setOwner.errorCount", failed, items.size())
                + (lastError.isBlank() ? "" : " " + lastError));
        }
    }

    private void setOwner(Path path, String ownerName) throws IOException {
        FileOwnerAttributeView view =
            Files.getFileAttributeView(path, FileOwnerAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new UnsupportedOperationException(I18n.get("filebrowser.setOwner.notSupported"));
        }
        UserPrincipal owner = path.getFileSystem()
            .getUserPrincipalLookupService()
            .lookupPrincipalByName(ownerName);
        view.setOwner(owner);
    }

    private void setGroup(Path path, String groupName) throws IOException {
        PosixFileAttributeView view =
            Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new UnsupportedOperationException(I18n.get("filebrowser.setOwner.notSupported"));
        }
        GroupPrincipal group = path.getFileSystem()
            .getUserPrincipalLookupService()
            .lookupPrincipalByGroupName(groupName);
        view.setGroup(group);
    }

    private void setPermissions(Path path, String permissions) throws IOException {
        PosixFileAttributeView view =
            Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (view == null) {
            throw new UnsupportedOperationException(I18n.get("filebrowser.setOwner.notSupported"));
        }
        view.setPermissions(PosixFilePermissions.fromString(octalToPosix(permissions)));
    }

    private static List<String> availableOwners(String currentOwner) {
        return principalOptions(currentOwner, readUnixPrincipalNames(UNIX_PASSWD_FILE));
    }

    private static List<String> availableGroups(String currentGroup) {
        return principalOptions(currentGroup, readUnixPrincipalNames(UNIX_GROUP_FILE));
    }

    private static List<String> principalOptions(String currentValue, List<String> discoveredValues) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (currentValue != null && !currentValue.isBlank()) {
            values.add(currentValue);
        }
        values.addAll(discoveredValues);
        return List.copyOf(values);
    }

    static List<String> readUnixPrincipalNames(Path file) {
        if (file == null || !Files.isRegularFile(file)) {
            return List.of();
        }
        TreeSet<String> names = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try {
            for (String line : Files.readAllLines(file)) {
                String trimmed = line.trim();
                if (trimmed.isBlank() || trimmed.startsWith("#")) {
                    continue;
                }
                int separator = trimmed.indexOf(':');
                String name = trimmed.substring(0, separator >= 0 ? separator : trimmed.length());
                if (!name.isBlank()) {
                    names.add(name);
                }
            }
        } catch (IOException | SecurityException e) {
            return List.of();
        }
        return List.copyOf(names);
    }

    static boolean isValidOctalPermissions(String permissions) {
        return permissions != null && permissions.matches("[0-7]{3}");
    }

    static String octalToPosix(String octal) {
        if (!isValidOctalPermissions(octal)) {
            throw new IllegalArgumentException("Expected three octal permission digits");
        }
        StringBuilder posix = new StringBuilder(9);
        for (char c : octal.toCharArray()) {
            int value = Character.digit(c, 8);
            posix.append((value & 4) != 0 ? 'r' : '-');
            posix.append((value & 2) != 0 ? 'w' : '-');
            posix.append((value & 1) != 0 ? 'x' : '-');
        }
        return posix.toString();
    }

    static String permissionsToOctal(Set<PosixFilePermission> permissions) {
        int owner = permissionDigit(
            permissions,
            PosixFilePermission.OWNER_READ,
            PosixFilePermission.OWNER_WRITE,
            PosixFilePermission.OWNER_EXECUTE);
        int group = permissionDigit(
            permissions,
            PosixFilePermission.GROUP_READ,
            PosixFilePermission.GROUP_WRITE,
            PosixFilePermission.GROUP_EXECUTE);
        int others = permissionDigit(
            permissions,
            PosixFilePermission.OTHERS_READ,
            PosixFilePermission.OTHERS_WRITE,
            PosixFilePermission.OTHERS_EXECUTE);
        return "" + owner + group + others;
    }

    private static int permissionDigit(
        Set<PosixFilePermission> permissions,
        PosixFilePermission read,
        PosixFilePermission write,
        PosixFilePermission execute) {

        int value = 0;
        if (permissions.contains(read)) {
            value += 4;
        }
        if (permissions.contains(write)) {
            value += 2;
        }
        if (permissions.contains(execute)) {
            value += 1;
        }
        return value;
    }

    private void selectAllFiles() {
        MultipleSelectionModel<TreeItem<FileNode>> selectionModel = treeView.getSelectionModel();
        selectionModel.clearSelection();
        for (int index = 0; index < treeView.getExpandedItemCount(); index++) {
            selectionModel.select(index);
        }
        updateSelectedItems();
    }

    private void showDetails() {
        if (selectedItems.isEmpty()) {
            return;
        }
        StringBuilder details = new StringBuilder();
        SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        for (FileNode node : selectedItems) {
            File file = node.file();
            details.append(node.name()).append("\n");
            details.append("  ").append(I18n.get("filebrowser.details.type")).append(": ")
                .append(node.directory() ? I18n.get("filebrowser.details.folder") : I18n.get("filebrowser.details.file"))
                .append("\n");
            if (!node.directory()) {
                details.append("  ").append(I18n.get("filebrowser.details.size")).append(": ")
                    .append(formatSize(node.size())).append("\n");
            }
            details.append("  ").append(I18n.get("filebrowser.details.path")).append(": ")
                .append(file.getAbsolutePath()).append("\n");
            if (file.exists()) {
                details.append("  ").append(I18n.get("filebrowser.details.modified")).append(": ")
                    .append(dateFormat.format(new Date(file.lastModified()))).append("\n");
                details.append("  ").append(I18n.get("filebrowser.details.permissions")).append(": ")
                    .append(getPermissions(file)).append("\n");
            }
            details.append("\n");
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(I18n.get("filebrowser.context.details"));
        alert.setHeaderText(selectedItems.size() == 1
            ? selectedItems.get(0).name()
            : selectedItems.size() + " " + I18n.get("filebrowser.selected"));
        alert.setContentText(details.toString());
        DialogThemeHelper.applyTheme(alert);
        alert.showAndWait();
    }

    private String getPermissions(File file) {
        StringBuilder permissions = new StringBuilder();
        permissions.append(file.canRead() ? "r" : "-");
        permissions.append(file.canWrite() ? "w" : "-");
        permissions.append(file.canExecute() ? "x" : "-");
        return permissions.toString();
    }

    private void archiveSelected(String format) {
        if (selectedItems.isEmpty()) {
            return;
        }
        Path archivePath = selectedTargetDirectory().resolve("archive." + format);
        try {
            if ("zip".equals(format)) {
                archiveToZip(archivePath);
            } else if ("tar".equals(format)) {
                archiveToTar(archivePath, false);
            } else if ("tgz".equals(format)) {
                archiveToTar(archivePath, true);
            }
            setStatus(I18n.get("filebrowser.archive.created") + ": " + archivePath.getFileName());
        } catch (IOException | SecurityException e) {
            setStatus(I18n.get("filebrowser.error.archive") + ": " + e.getMessage());
        }
    }

    private void archiveToZip(Path zipPath) throws IOException {
        try (var zipOutput = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (FileNode node : selectedItems) {
                addToZip(zipOutput, node.file().toPath(), "");
            }
        }
    }

    private void addToZip(java.util.zip.ZipOutputStream zipOutput, Path file, String basePath) throws IOException {
        String name = basePath.isEmpty()
            ? file.getFileName().toString()
            : basePath + "/" + file.getFileName();
        if (Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)) {
            zipOutput.putNextEntry(new java.util.zip.ZipEntry(name + "/"));
            zipOutput.closeEntry();
            try (var stream = Files.list(file)) {
                for (Path child : stream.toList()) {
                    addToZip(zipOutput, child, name);
                }
            }
        } else {
            zipOutput.putNextEntry(new java.util.zip.ZipEntry(name));
            Files.copy(file, zipOutput);
            zipOutput.closeEntry();
        }
    }

    private void archiveToTar(Path tarPath, boolean gzip) throws IOException {
        try (var output = Files.newOutputStream(tarPath);
             var compressedOutput = gzip ? new java.util.zip.GZIPOutputStream(output) : output;
             var dataOutput = new DataOutputStream(compressedOutput)) {
            for (FileNode node : selectedItems) {
                addToTar(dataOutput, node.file().toPath(), "");
            }
        }
    }

    private void addToTar(DataOutputStream output, Path file, String basePath) throws IOException {
        String name = basePath.isEmpty()
            ? file.getFileName().toString()
            : basePath + "/" + file.getFileName();
        byte[] nameBytes = name.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        boolean directory = Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS);

        byte[] header = new byte[512];
        System.arraycopy(nameBytes, 0, header, 0, Math.min(nameBytes.length, 100));
        writeTarOctal(header, 100, 8, directory ? 0755 : 0644);
        writeTarOctal(header, 108, 8, 1000);
        writeTarOctal(header, 116, 8, 1000);

        long size = directory ? 0 : Files.size(file);
        writeTarOctal(header, 124, 12, size);
        long mtime = Files.exists(file) ? Files.getLastModifiedTime(file).toMillis() / 1000 : System.currentTimeMillis() / 1000;
        writeTarOctal(header, 136, 12, mtime);
        header[156] = (byte) (directory ? '5' : '0');
        writeTarChecksum(header);

        output.write(header);
        output.flush();

        if (!directory) {
            try (var input = Files.newInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = input.read(buffer)) > 0) {
                    output.write(buffer, 0, read);
                }
            }
            int padding = (int) (512 - (size % 512));
            if (padding < 512) {
                output.write(new byte[padding == 0 ? 512 : padding]);
            }
        }
    }

    static void writeTarOctal(byte[] header, int offset, int length, long value) {
        if (value < 0) {
            throw new IllegalArgumentException("TAR octal fields cannot store negative values: " + value);
        }
        int valueLength = length - 1;
        String octal = Long.toOctalString(value);
        if (octal.length() > valueLength) {
            throw new IllegalArgumentException(
                "TAR octal field at offset " + offset
                    + " can store at most " + valueLength
                    + " digits, but value " + value
                    + " needs " + octal.length()
                    + "; GNU/POSIX extended TAR headers are not supported.");
        }
        byte[] octalBytes = String.format("%0" + valueLength + "o", value)
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(octalBytes, 0, header, offset, octalBytes.length);
        header[offset + length - 1] = 0;
    }

    private void writeTarChecksum(byte[] header) {
        for (int i = 148; i < 156; i++) {
            header[i] = 0x20;
        }
        int checksum = 0;
        for (byte value : header) {
            checksum += value & 0xff;
        }
        byte[] checksumBytes = String.format("%06o\0 ", checksum)
            .getBytes(java.nio.charset.StandardCharsets.US_ASCII);
        System.arraycopy(checksumBytes, 0, header, 148, Math.min(checksumBytes.length, 8));
    }

    private void createNewFolder() {
        TextInputDialog dialog = new TextInputDialog("NewFolder");
        dialog.setTitle(I18n.get("filebrowser.newFolder.title"));
        dialog.setHeaderText(I18n.get("filebrowser.newFolder.header"));
        DialogThemeHelper.applyTheme(dialog);
        Optional<String> result = dialog.showAndWait();
        result.map(String::trim)
            .filter(name -> !name.isEmpty())
            .ifPresent(name -> {
                try {
                    Files.createDirectory(selectedTargetDirectory().resolve(name));
                    refresh();
                    setStatus(I18n.get("filebrowser.folder.created") + ": " + name);
                } catch (IOException | SecurityException e) {
                    setStatus(I18n.get("filebrowser.error.createFolder") + ": " + e.getMessage());
                }
            });
    }

    private void createNewFile() {
        TextInputDialog dialog = new TextInputDialog("NewFile.txt");
        dialog.setTitle(I18n.get("filebrowser.newFile.title"));
        dialog.setHeaderText(I18n.get("filebrowser.newFile.header"));
        DialogThemeHelper.applyTheme(dialog);
        Optional<String> result = dialog.showAndWait();
        result.map(String::trim)
            .filter(name -> !name.isEmpty())
            .ifPresent(name -> {
                try {
                    Files.createFile(selectedTargetDirectory().resolve(name));
                    refresh();
                    setStatus(I18n.get("filebrowser.file.created") + ": " + name);
                } catch (IOException | SecurityException e) {
                    setStatus(I18n.get("filebrowser.error.createFile") + ": " + e.getMessage());
                }
            });
    }

    private void openFile(File file) {
        if (file == null || !file.exists() || !file.canRead()) {
            setStatus(I18n.get("filebrowser.error.cannotOpen"));
            return;
        }
        if (!Desktop.isDesktopSupported()) {
            setStatus(I18n.get("filebrowser.error.noDesktop"));
            return;
        }
        try {
            Desktop.getDesktop().open(file);
        } catch (IOException e) {
            setStatus(I18n.get("filebrowser.error.cannotOpen"));
        } catch (SecurityException e) {
            setStatus(I18n.get("filebrowser.error.accessDenied"));
        }
    }

    private String formatSize(long size) {
        if (size < 0) {
            return "0 B";
        }
        if (size < 1024) {
            return size + " B";
        }
        if (size < 1024 * 1024) {
            return String.format("%.1f KB", size / 1024.0);
        }
        if (size < 1024 * 1024 * 1024) {
            return String.format("%.1f MB", size / (1024.0 * 1024));
        }
        return String.format("%.1f GB", size / (1024.0 * 1024 * 1024));
    }

    private void setStatus(String text) {
        boolean visible = text != null && !text.isBlank();
        statusLabel.setText(visible ? text : "");
        statusLabel.setVisible(visible);
        statusLabel.setManaged(visible);
    }

    /**
     * Keeps the file browser in an editor-sidebar style independent of the
     * terminal theme while still refreshing cells after theme changes.
     */
    public void applyTheme(String bgColor, String fgColor) {
        if (AppDesignStyleSupport.isCustomAppDesignActive()) {
            setStyle(null);
        } else {
            setStyle("-fx-background-color: " + PANEL_BACKGROUND + ";");
        }
        AppDesignStyleSupport.applyToParent(this);
        if (toolbar != null) {
            FileBrowserIcons.retintGlyphs(toolbar, iconTint());
        }
        treeView.refresh();
    }

    public void refresh() {
        if (rootItem == null) {
            return;
        }
        pendingExpansion.clear();
        pendingExpansion.addAll(captureExpandedPaths());
        rootItem.getChildren().setAll(new TreeItem<>(FileNode.placeholderNode()));
        rootItem.setExpanded(true);
        ensureLoaded(rootItem);
        treeView.refresh();
    }

    private static final class FileNode implements FileBrowserSort.Entry {
        private final File file;
        private final boolean directory;
        private final boolean hidden;
        private final boolean placeholder;
        private final boolean loading;
        private final boolean symlink;
        private final boolean executable;
        private final long size;
        private final long lastModified;

        private FileNode(File file, boolean directory, boolean hidden, boolean placeholder, boolean loading,
                         boolean symlink, boolean executable, long size, long lastModified) {
            this.file = file;
            this.directory = directory;
            this.hidden = hidden;
            this.placeholder = placeholder;
            this.loading = loading;
            this.symlink = symlink;
            this.executable = executable;
            this.size = size;
            this.lastModified = lastModified;
        }

        private static FileNode placeholderNode() {
            return new FileNode(new File(""), true, false, true, false, false, false, 0, 0);
        }

        private static FileNode loadingNode() {
            return new FileNode(new File(""), false, false, false, true, false, false, 0, 0);
        }

        private File file() {
            return file;
        }

        @Override
        public String name() {
            String name = file.getName();
            return name == null || name.isBlank() ? file.getAbsolutePath() : name;
        }

        @Override
        public boolean directory() {
            return directory;
        }

        private boolean hidden() {
            return hidden;
        }

        private boolean placeholder() {
            return placeholder;
        }

        private boolean loading() {
            return loading;
        }

        private boolean symlink() {
            return symlink;
        }

        private boolean executable() {
            return executable;
        }

        @Override
        public long size() {
            return size;
        }

        @Override
        public long lastModified() {
            return lastModified;
        }
    }
}
