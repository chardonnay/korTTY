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
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ProgressIndicator;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableRow;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.TransferMode;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import javafx.scene.layout.FlowPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;

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
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Local file browser panel that can be docked to the left or right side of the main window.
 * Laid out like the terminal's remote files sidebar: a title bar with the actions, a clickable
 * path, a name filter and a flat listing of one folder (Name, Size, Modified) with a ".." row;
 * opening a folder changes into it.
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
    private static final DateTimeFormatter MODIFIED_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");
    private static final String DIRECTORY_SIZE_LABEL = "<DIR>";

    private final Path homePath;
    private final TableView<FileNode> table;
    private final ObservableList<FileNode> rows = FXCollections.observableArrayList();
    private TableColumn<FileNode, FileNode> nameColumn;
    /** The name cells alive in the table (weakly held: a refresh may replace them). */
    private final Set<NameCell> nameCells = java.util.Collections.newSetFromMap(new java.util.WeakHashMap<>());
    private Tooltip shownNameTip;
    private final FileBrowserHistory history = new FileBrowserHistory();
    private final Label statusLabel;
    private final Label footerLabel;
    private final ObservableList<FileNode> selectedItems = FXCollections.observableArrayList();
    private final ArrayList<File> clipboardFiles = new ArrayList<>();
    private final MainWindow ownerWindow;

    private HBox toolbar;
    private FlowPane breadcrumb;
    private TextField pathBar;
    private TextField filterField;
    private StackPane contentStack;
    private Node loadingOverlay;
    private Button backButton;
    private Button forwardButton;
    private Button upButton;
    private ToggleButton showHiddenButton;
    private Button hideButton;
    private Runnable onHideRequested;
    private Path currentRoot;
    private boolean showHiddenFiles = false;
    private boolean clipboardCut = false;
    private boolean renameRequested = false;
    private CheckMenuItem showHiddenMenuItem;
    private FileBrowserSort.Key sortKey = FileBrowserSort.Key.NAME;
    private final java.util.Map<FileBrowserSort.Key, TableColumn<FileNode, FileNode>> sortColumns =
        new java.util.EnumMap<>(FileBrowserSort.Key.class);
    private final java.util.Map<FileBrowserSort.Key, RadioMenuItem> sortKeyItems =
        new java.util.EnumMap<>(FileBrowserSort.Key.class);
    private RadioMenuItem sortAscendingItem;
    private RadioMenuItem sortDescendingItem;
    private boolean sortingRows;
    private boolean sortAscending = true;
    private String currentFilter = "";
    private long listingGeneration;

    public LocalFileBrowser() {
        this(null);
    }

    public LocalFileBrowser(MainWindow ownerWindow) {
        this.ownerWindow = ownerWindow;
        homePath = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        showHiddenFiles = loadShowHiddenSetting();
        currentRoot = loadInitialRoot();

        setPadding(new Insets(4));
        setSpacing(4);
        setStyle("-fx-background-color: " + PANEL_BACKGROUND + ";");
        getStyleClass().addAll("file-browser-panel", "file-browser-sidebar", "local-file-browser");
        addStylesheet();

        statusLabel = new Label("");
        statusLabel.getStyleClass().add("file-browser-status");
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        footerLabel = new Label("");
        footerLabel.getStyleClass().add("file-browser-footer");
        footerLabel.setMaxWidth(Double.MAX_VALUE);

        table = buildTable();
        table.setContextMenu(createContextMenu());
        table.getSelectionModel().getSelectedItems().addListener(
            (ListChangeListener<? super FileNode>) change -> updateSelectedItems());
        installSelectedNameTip();
        table.setOnKeyPressed(this::handleTableKey);
        installTableDropHandlers();

        toolbar = buildToolbar();
        Node pathRow = buildPathRow();
        filterField = buildFilterField();
        loadingOverlay = buildLoadingOverlay();
        contentStack = new StackPane(table, loadingOverlay);
        VBox.setVgrow(contentStack, Priority.ALWAYS);

        getChildren().addAll(toolbar, pathRow, filterField, contentStack, statusLabel, footerLabel);
        applyDesignTokens();
        // Dragged narrower than the title bar's buttons, the bar must not paint over the terminal.
        javafx.scene.shape.Rectangle clip = new javafx.scene.shape.Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);

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
        boolean sameFolder = root.equals(currentRoot);
        currentRoot = root;
        if (pathBar != null) {
            pathBar.setText(FileBrowserPaths.abbreviateHome(root, homePath));
        }
        rebuildBreadcrumb(root);
        updateNavButtons();
        if (!sameFolder) {
            rows.clear();
        }
        loadListing();
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

        hideButton = toolbarButton(FileBrowserIcons.CLOSE, "filebrowser.tooltip.hide", () -> {
            Runnable hide = onHideRequested;
            if (hide != null) {
                hide.run();
            }
        });
        hideButton.setVisible(false);
        hideButton.setManaged(false);

        Label title = new Label(I18n.get("filebrowser.header"));
        title.getStyleClass().add("file-browser-title");
        title.setMinWidth(0);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);

        HBox bar = new HBox(title, spacer, backButton, forwardButton, upButton, homeButton, refreshButton,
            newFolderButton, newFileButton, buildSortMenuButton(), showHiddenButton, hideButton);
        bar.getStyleClass().add("file-browser-toolbar");
        return bar;
    }

    /** The panel's own close button; without one (e.g. not docked by a window) it stays hidden. */
    public void setOnHideRequested(Runnable onHideRequested) {
        this.onHideRequested = onHideRequested;
        hideButton.setVisible(onHideRequested != null);
        hideButton.setManaged(onHideRequested != null);
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
        sortKeyItems.put(FileBrowserSort.Key.NAME, byName);
        sortKeyItems.put(FileBrowserSort.Key.SIZE, bySize);
        sortKeyItems.put(FileBrowserSort.Key.DATE, byDate);
        sortAscendingItem = asc;
        sortDescendingItem = desc;

        button.getItems().addAll(byName, bySize, byDate, new SeparatorMenuItem(), asc, desc);
        return button;
    }

    private RadioMenuItem sortKeyItem(String labelKey, FileBrowserSort.Key key, ToggleGroup group) {
        RadioMenuItem item = new RadioMenuItem(I18n.get(labelKey));
        item.setToggleGroup(group);
        item.setSelected(sortKey == key);
        item.setOnAction(e -> setSort(key, sortAscending));
        return item;
    }

    private RadioMenuItem sortDirItem(String labelKey, boolean ascending, ToggleGroup group) {
        RadioMenuItem item = new RadioMenuItem(I18n.get(labelKey));
        item.setToggleGroup(group);
        item.setSelected(sortAscending == ascending);
        item.setOnAction(e -> setSort(sortKey, ascending));
        return item;
    }

    /**
     * The folder shown as clickable crumbs; a click beside them (or {@code Ctrl/Cmd+L}) swaps in a
     * text field to type a path, Enter goes there, Escape or leaving the field returns to the crumbs.
     */
    private Node buildPathRow() {
        breadcrumb = new FlowPane(2, 2);
        breadcrumb.getStyleClass().add("file-browser-breadcrumb");
        breadcrumb.setCursor(javafx.scene.Cursor.TEXT);
        Tooltip.install(breadcrumb, new Tooltip(I18n.get("filebrowser.path.placeholder")));
        breadcrumb.setOnMouseClicked(event -> {
            if (event.getTarget() == breadcrumb) {
                showPathEditor(true);
                event.consume();
            }
        });

        pathBar = new TextField();
        pathBar.getStyleClass().add("file-browser-path");
        pathBar.setPromptText(I18n.get("filebrowser.path.placeholder"));
        pathBar.setOnAction(e -> {
            showPathEditor(false);
            navigateTo(FileBrowserPaths.expandHome(pathBar.getText(), homePath));
        });
        pathBar.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ESCAPE) {
                showPathEditor(false);
                table.requestFocus();
                event.consume();
            }
        });
        pathBar.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                showPathEditor(false);
            }
        });
        showPathEditor(false);
        return new StackPane(breadcrumb, pathBar);
    }

    private void showPathEditor(boolean editing) {
        if (editing && currentRoot != null) {
            pathBar.setText(FileBrowserPaths.abbreviateHome(currentRoot, homePath));
        }
        pathBar.setVisible(editing);
        pathBar.setManaged(editing);
        breadcrumb.setVisible(!editing);
        breadcrumb.setManaged(!editing);
        if (editing) {
            pathBar.requestFocus();
            pathBar.selectAll();
        }
    }

    /** {@code ~} (or the file-system root) followed by one link per folder down to {@code root}. */
    private void rebuildBreadcrumb(Path root) {
        if (breadcrumb == null) {
            return;
        }
        breadcrumb.getChildren().clear();
        boolean underHome = root.startsWith(homePath);
        Path base = underHome ? homePath : root.getRoot();
        if (base == null) {
            base = root;
        }
        breadcrumb.getChildren().add(crumb(underHome ? "~" : base.toString(), base));
        Path sofar = base;
        for (Path part : base.relativize(root)) {
            String name = part.toString();
            if (name.isEmpty()) {
                continue;
            }
            sofar = sofar.resolve(name);
            breadcrumb.getChildren().add(crumb(name, sofar));
        }
    }

    private Hyperlink crumb(String label, Path target) {
        Hyperlink link = new Hyperlink(label);
        link.setPadding(new Insets(0, 2, 0, 2));
        link.setFocusTraversable(false);
        link.setOnAction(e -> navigateTo(target));
        return link;
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
        return FileBrowserLoadingOverlay.create();
    }

    private void showLoading(boolean loading) {
        FileBrowserLoadingOverlay.show(loadingOverlay, loading);
    }

    // ---- Keyboard / drop / rename / copy-path ----

    private void handleTableKey(javafx.scene.input.KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == KeyCode.ENTER) {
            activate(table.getSelectionModel().getSelectedItem());
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
        } else if (code == KeyCode.L && event.isShortcutDown()) {
            showPathEditor(true);
            event.consume();
        }
    }

    /** Double-click / Enter: ".." and folders change into the folder, files open. */
    private void activate(FileNode node) {
        if (node == null) {
            return;
        }
        if (node.directory()) {
            navigateTo(node.file().toPath());
        } else {
            trackFileBrowserAction("open");
            openFile(node.file());
        }
    }

    private void installTableDropHandlers() {
        table.setOnDragOver(event -> {
            if (!isOwnDrag(event) && event.getDragboard().hasFiles()) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                if (!table.getStyleClass().contains("drop-target")) {
                    table.getStyleClass().add("drop-target");
                }
            }
            event.consume();
        });
        table.setOnDragExited(event -> {
            table.getStyleClass().remove("drop-target");
            event.consume();
        });
        table.setOnDragDropped(event -> {
            Dragboard dragboard = event.getDragboard();
            boolean completed = false;
            if (dragboard.hasFiles()) {
                boolean move = event.getTransferMode() == TransferMode.MOVE;
                completed = handleDrop(dragboard.getFiles(), currentRoot, move);
            }
            table.getStyleClass().remove("drop-target");
            event.setDropCompleted(completed);
            event.consume();
        });
    }

    /** A drag started in this listing: dropping it on the folder shown would copy files onto themselves. */
    private boolean isOwnDrag(javafx.scene.input.DragEvent event) {
        return event.getGestureSource() instanceof Node source && isInsideTable(source);
    }

    private boolean isInsideTable(Node node) {
        for (Node current = node; current != null; current = current.getParent()) {
            if (current == table) {
                return true;
            }
        }
        return false;
    }

    private boolean handleDrop(List<File> files, Path targetDir, boolean move) {
        if (targetDir == null || files == null || files.isEmpty()) {
            return false;
        }
        List<File> sources = List.copyOf(files);
        runFileOperation(() -> {
            String error = null;
            for (File file : sources) {
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
                } catch (IOException | SecurityException e) {
                    error = I18n.get("filebrowser.error.drop") + ": " + e.getMessage();
                }
            }
            return error;
        });
        // The drop was accepted; the copy/move runs asynchronously and refreshes when done.
        return true;
    }

    private void renameSelected() {
        FileNode node = table.getSelectionModel().getSelectedItem();
        int index = table.getSelectionModel().getSelectedIndex();
        if (node == null || node.parentEntry() || index < 0) {
            return;
        }
        renameRequested = true;
        // The editor lives in the row's cell: off screen there is none to start.
        table.scrollTo(index);
        table.layout();
        table.edit(index, nameColumn);
    }

    private void performRename(FileNode node, String newName) {
        if (node == null || node.parentEntry() || newName == null) {
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
        de.kortty.core.KorttyClipboard.setText(node.file().getAbsolutePath());
        setStatus(I18n.get("filebrowser.path.copied"));
    }

    private FileNode getFirstSelectedNode() {
        for (FileNode node : selectedItems) {
            if (node != null && !node.parentEntry()) {
                return node;
            }
        }
        return null;
    }

    // ---- Listing table ----

    private TableView<FileNode> buildTable() {
        TableView<FileNode> view = new TableView<>(rows);
        view.getStyleClass().add("file-browser-table");
        view.setEditable(true);
        view.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        view.setPlaceholder(new Label(""));

        TableColumn<FileNode, FileNode> iconColumn = column("", 28);
        iconColumn.setMinWidth(28);
        iconColumn.setMaxWidth(28);
        iconColumn.setResizable(false);
        iconColumn.setCellFactory(col -> new IconCell());
        nameColumn = column(I18n.get("filebrowser.column.name"), 150);
        nameColumn.setMinWidth(80);
        nameColumn.setEditable(true);
        nameColumn.setCellFactory(col -> new NameCell());
        TableColumn<FileNode, FileNode> sizeColumn = column(I18n.get("filebrowser.column.size"), 72);
        sizeColumn.setCellFactory(col -> textCell(LocalFileBrowser::sizeText, col, "888.8 MB"));
        TableColumn<FileNode, FileNode> modifiedColumn = column(I18n.get("filebrowser.column.modified"), 128);
        modifiedColumn.setCellFactory(col -> textCell(LocalFileBrowser::modifiedText, col, "2026-12-31 23:59"));
        view.getColumns().addAll(List.of(iconColumn, nameColumn, sizeColumn, modifiedColumn));
        sortColumns.put(FileBrowserSort.Key.NAME, nameColumn);
        sortColumns.put(FileBrowserSort.Key.SIZE, sizeColumn);
        sortColumns.put(FileBrowserSort.Key.DATE, modifiedColumn);
        for (TableColumn<FileNode, FileNode> sortable : sortColumns.values()) {
            sortable.setSortable(true);
        }
        // A click on Name, Size or Modified sorts by it (again: the other way round); the rows are
        // ordered by FileBrowserSort, which keeps hidden entries and folders first, ".." on top.
        view.setSortPolicy(tableView -> {
            applyColumnSort();
            return true;
        });
        // The name takes whatever the panel's width leaves; size and date keep their widths.
        nameColumn.prefWidthProperty().bind(view.widthProperty()
            .subtract(iconColumn.widthProperty())
            .subtract(sizeColumn.widthProperty())
            .subtract(modifiedColumn.widthProperty())
            .subtract(18));
        view.setRowFactory(tableView -> new FileRow());
        nameColumn.setSortType(TableColumn.SortType.ASCENDING);
        view.getSortOrder().add(nameColumn);
        return view;
    }

    /** Sorts by {@code key} in that direction, as a header click would; the header shows the arrow. */
    private void setSort(FileBrowserSort.Key key, boolean ascending) {
        TableColumn<FileNode, FileNode> column = sortColumns.get(key);
        column.setSortType(ascending ? TableColumn.SortType.ASCENDING : TableColumn.SortType.DESCENDING);
        if (table.getSortOrder().size() != 1 || table.getSortOrder().get(0) != column) {
            table.getSortOrder().setAll(List.of(column));
        }
        table.sort();
    }

    /** Takes the sort from the header (the table's sort policy) and reorders the rows shown. */
    private void applyColumnSort() {
        if (table == null || sortingRows) {
            return;
        }
        if (table.getSortOrder().isEmpty()) {
            // A third click on a header clears its sort: fall back to Name, ascending, with its arrow.
            Platform.runLater(() -> setSort(FileBrowserSort.Key.NAME, true));
            return;
        }
        TableColumn<FileNode, ?> column = table.getSortOrder().get(0);
        sortKey = sortColumns.entrySet().stream()
            .filter(entry -> entry.getValue() == column)
            .map(java.util.Map.Entry::getKey)
            .findFirst()
            .orElse(FileBrowserSort.Key.NAME);
        sortAscending = column.getSortType() == TableColumn.SortType.ASCENDING;
        syncSortMenu();
        sortRows();
    }

    private void syncSortMenu() {
        RadioMenuItem keyItem = sortKeyItems.get(sortKey);
        if (keyItem != null) {
            keyItem.setSelected(true);
        }
        RadioMenuItem directionItem = sortAscending ? sortAscendingItem : sortDescendingItem;
        if (directionItem != null) {
            directionItem.setSelected(true);
        }
    }

    /** Reorders the listing in place (the selection follows its rows); ".." stays the first row. */
    private void sortRows() {
        java.util.Comparator<FileBrowserSort.Entry> order = FileBrowserSort.comparator(sortKey, sortAscending);
        sortingRows = true;
        try {
            FXCollections.sort(rows, (a, b) -> a.parentEntry() != b.parentEntry()
                ? (a.parentEntry() ? -1 : 1)
                : order.compare(a, b));
        } finally {
            sortingRows = false;
        }
    }

    private static TableColumn<FileNode, FileNode> column(String title, double width) {
        TableColumn<FileNode, FileNode> column = new TableColumn<>(title);
        column.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue()));
        column.setPrefWidth(width);
        column.setEditable(false);
        // Only Name, Size and Modified are sort controls (see buildTable).
        column.setSortable(false);
        column.setReorderable(false);
        return column;
    }

    /**
     * A plain text cell whose column is as wide as {@code widest} in the cell's font: the font
     * follows the app's UI scale, so a fixed pixel width would cut sizes and dates off at 125 %.
     */
    private static TableCell<FileNode, FileNode> textCell(java.util.function.Function<FileNode, String> text,
                                                          TableColumn<FileNode, FileNode> column, String widest) {
        TableCell<FileNode, FileNode> cell = new TableCell<>() {
            @Override
            protected void updateItem(FileNode item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : text.apply(item));
                setGraphic(null);
            }
        };
        cell.fontProperty().addListener((obs, was, font) -> fitColumn(column, cell, widest));
        return cell;
    }

    private static void fitColumn(TableColumn<FileNode, FileNode> column, TableCell<FileNode, FileNode> cell,
                                  String widest) {
        javafx.scene.text.Text probe = new javafx.scene.text.Text(widest);
        probe.setFont(cell.getFont());
        double width = Math.ceil(probe.getLayoutBounds().getWidth()
            + cell.snappedLeftInset() + cell.snappedRightInset() + 4);
        if (Math.abs(column.getPrefWidth() - width) > 0.5) {
            column.setPrefWidth(width);
        }
    }

    private static String sizeText(FileNode node) {
        if (node.parentEntry()) {
            return "";
        }
        return node.directory() ? DIRECTORY_SIZE_LABEL : formatSize(node.size());
    }

    private static String modifiedText(FileNode node) {
        if (node.parentEntry() || node.lastModified() <= 0) {
            return "";
        }
        return MODIFIED_FORMAT.format(Instant.ofEpochMilli(node.lastModified()).atZone(ZoneId.systemDefault()));
    }

    private final class IconCell extends TableCell<FileNode, FileNode> {
        @Override
        protected void updateItem(FileNode item, boolean empty) {
            super.updateItem(item, empty);
            setText(null);
            setGraphic(empty || item == null ? null : createIcon(item));
            setAlignment(Pos.CENTER);
        }
    }

    /** The name, renamed in place only on an explicit request (context menu / F2). */
    private final class NameCell extends TableCell<FileNode, FileNode> {
        private TextField editor;
        private final Tooltip fullName = new Tooltip();
        private boolean truncated;

        NameCell() {
            nameCells.add(this);
            fullName.setShowDelay(Duration.millis(400));
        }

        /** Shows the whole name as a tooltip only while the column is too narrow for it. */
        @Override
        protected void layoutChildren() {
            super.layoutChildren();
            FileNode node = getItem();
            boolean cut = !isEmpty() && node != null && !node.parentEntry() && !isEditing()
                && textWidth(node.name(), getFont()) > getWidth() - snappedLeftInset() - snappedRightInset();
            if (cut != truncated || cut && !node.name().equals(fullName.getText())) {
                truncated = cut;
                if (cut) {
                    fullName.setText(node.name());
                }
                setTooltip(cut ? fullName : null);
                if (!cut && fullName.isShowing()) {
                    fullName.hide();
                }
            }
        }

        @Override
        protected void updateItem(FileNode item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                return;
            }
            if (isEditing() && editor != null) {
                editor.setText(item.name());
                setText(null);
                setGraphic(editor);
                return;
            }
            setText(item.parentEntry() ? ".." : item.name());
            setGraphic(null);
        }

        @Override
        public void startEdit() {
            FileNode node = getItem();
            if (node == null || node.parentEntry() || !renameRequested) {
                // The default cell behavior may already have set the table's editing cell before
                // calling this; clear it so the refused edit does not block a later rename.
                if (getTableView() != null) {
                    Platform.runLater(() -> getTableView().edit(-1, null));
                }
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
            setText(node == null ? null : node.parentEntry() ? ".." : node.name());
            setGraphic(null);
        }
    }

    private final class FileRow extends TableRow<FileNode> {
        FileRow() {
            setOnMouseClicked(this::onMouseClicked);
            setOnContextMenuRequested(this::onContextMenuRequested);
            setOnDragDetected(this::onDragDetected);
            setOnDragOver(this::onDragOver);
            setOnDragExited(event -> getStyleClass().remove("drop-target"));
            setOnDragDropped(this::onDragDropped);
        }

        private void onMouseClicked(javafx.scene.input.MouseEvent event) {
            FileNode node = getItem();
            if (node != null && event.getButton() == javafx.scene.input.MouseButton.PRIMARY
                    && event.getClickCount() == 2) {
                activate(node);
                // Suppress the editable-cell default (which would start a rename on double-click).
                event.consume();
            }
        }

        private void onContextMenuRequested(javafx.scene.input.ContextMenuEvent event) {
            if (!isEmpty() && !table.getSelectionModel().isSelected(getIndex())) {
                table.getSelectionModel().clearAndSelect(getIndex());
            }
        }

        private void onDragDetected(javafx.scene.input.MouseEvent event) {
            FileNode node = getItem();
            if (node == null || node.parentEntry()) {
                return;
            }
            List<File> files = selectedItems.isEmpty()
                ? List.of(node.file())
                : selectedItems.stream().map(FileNode::file).collect(Collectors.toList());
            Dragboard dragboard = startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.putFiles(files);
            dragboard.setContent(content);
            event.consume();
        }

        /** A folder row takes a drop into that folder; other rows leave it to the table (the folder shown). */
        private void onDragOver(javafx.scene.input.DragEvent event) {
            FileNode node = getItem();
            if (node != null && node.directory() && event.getDragboard().hasFiles()
                    && !draggedOntoItself(event, node)) {
                event.acceptTransferModes(TransferMode.COPY_OR_MOVE);
                if (!getStyleClass().contains("drop-target")) {
                    getStyleClass().add("drop-target");
                }
                event.consume();
            }
        }

        private void onDragDropped(javafx.scene.input.DragEvent event) {
            FileNode node = getItem();
            if (node == null || !node.directory()) {
                return;
            }
            Dragboard dragboard = event.getDragboard();
            boolean completed = false;
            if (dragboard.hasFiles()) {
                boolean move = event.getTransferMode() == TransferMode.MOVE;
                completed = handleDrop(dragboard.getFiles(), node.file().toPath(), move);
            }
            getStyleClass().remove("drop-target");
            event.setDropCompleted(completed);
            event.consume();
        }

        private static boolean draggedOntoItself(javafx.scene.input.DragEvent event, FileNode node) {
            return event.getDragboard().getFiles().stream().anyMatch(file -> file.equals(node.file()));
        }
    }

    private static double textWidth(String text, javafx.scene.text.Font font) {
        javafx.scene.text.Text probe = new javafx.scene.text.Text(text);
        probe.setFont(font);
        return probe.getLayoutBounds().getWidth();
    }

    /**
     * Marking one entry whose name the column cuts off (with the arrow keys or a click) shows
     * its whole name right below the row, as hovering does; the next selection, scrolling,
     * a new listing or leaving the table hides it again.
     */
    private void installSelectedNameTip() {
        table.getSelectionModel().selectedIndexProperty().addListener((obs, was, now) -> {
            hideSelectedNameTip();
            // After the table scrolled the newly selected row into view and laid it out.
            Platform.runLater(this::showSelectedNameTip);
        });
        table.focusedProperty().addListener((obs, was, focused) -> {
            if (!focused) {
                hideSelectedNameTip();
            }
        });
        table.addEventFilter(javafx.scene.input.ScrollEvent.ANY, event -> hideSelectedNameTip());
        rows.addListener((ListChangeListener<FileNode>) change -> hideSelectedNameTip());
    }

    private void showSelectedNameTip() {
        hideSelectedNameTip();
        if (!table.isFocused() || table.getSelectionModel().getSelectedIndices().size() != 1) {
            return;
        }
        int index = table.getSelectionModel().getSelectedIndex();
        for (NameCell cell : nameCells) {
            if (cell.getIndex() != index || cell.getTableView() != table || cell.getScene() == null
                    || !cell.isVisible() || !cell.truncated) {
                continue;
            }
            javafx.geometry.Bounds bounds = cell.localToScreen(cell.getBoundsInLocal());
            if (bounds != null) {
                shownNameTip = cell.fullName;
                cell.fullName.show(cell, bounds.getMinX(), bounds.getMaxY() + 2);
            }
            return;
        }
    }

    private void hideSelectedNameTip() {
        Tooltip tip = shownNameTip;
        shownNameTip = null;
        if (tip != null && tip.isShowing()) {
            tip.hide();
        }
    }

    private TextField createRenameEditor(NameCell cell) {
        TextField field = new TextField();
        field.getStyleClass().add("file-browser-rename");
        field.setOnAction(event -> {
            FileNode node = cell.getItem();
            String name = field.getText();
            cell.cancelEdit();
            performRename(node, name);
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

    private Node createIcon(FileNode node) {
        FileBrowserIcons.IconKind kind =
            FileBrowserIcons.kindFor(node.name(), node.directory(), false, node.executable());
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
        selectedItems.setAll(table.getSelectionModel().getSelectedItems().stream()
            .filter(node -> node != null && !node.parentEntry())
            .toList());
        updateCounts();
    }

    /** Context menu "Open": a single folder (or "..") is changed into, files are opened. */
    private void openSelected() {
        List<FileNode> chosen = List.copyOf(table.getSelectionModel().getSelectedItems());
        if (chosen.size() == 1 && chosen.get(0).directory()) {
            activate(chosen.get(0));
            return;
        }
        for (FileNode node : chosen) {
            if (node != null && !node.directory()) {
                openFile(node.file());
            }
        }
    }

    private FileNode getSingleSelectedFile() {
        if (selectedItems.size() != 1) {
            return null;
        }
        FileNode node = selectedItems.get(0);
        return node != null && !node.directory() ? node : null;
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
        // The editor runs this on a worker thread; the SnippetManager is FX-thread state (its
        // lists are read by open dialogs and its change listeners expect FX), so mutate and save
        // there and let any failure propagate unchanged.
        return callOnFxThread(() -> {
            snippetManager.ensureCategory(snippet.getCategory());
            snippetManager.addSnippet(snippet);
            snippetManager.save();
            return true;
        });
    }

    /** Runs {@code action} on the FX thread and rethrows its failure as-is (not wrapped). */
    private <T> T callOnFxThread(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) {
            return action.call();
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                future.complete(action.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while waiting for UI action", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof Exception exception) {
                throw exception;
            }
            throw new IllegalStateException(cause);
        }
    }

    /**
     * Lists the folder shown off the FX thread with the loading overlay up; a listing that
     * arrives after the user moved on is dropped. The selection survives a refresh by path.
     */
    private void loadListing() {
        Path directory = currentRoot;
        if (directory == null) {
            return;
        }
        long generation = ++listingGeneration;
        showLoading(true);
        boolean showHidden = showHiddenFiles;
        String filter = currentFilter;
        FileBrowserSort.Key key = sortKey;
        boolean ascending = sortAscending;
        CompletableFuture
            .supplyAsync(() -> listChildren(directory, showHidden, filter, key, ascending))
            .whenComplete((children, error) -> Platform.runLater(
                () -> applyListing(directory, generation, children, error)));
    }

    private void applyListing(Path directory, long generation, List<FileNode> children, Throwable error) {
        if (generation != listingGeneration || !directory.equals(currentRoot)) {
            return;
        }
        showLoading(false);
        Set<File> selected = selectedItems.stream().map(FileNode::file).collect(Collectors.toSet());
        List<FileNode> listing = new ArrayList<>();
        Path parent = directory.getParent();
        if (parent != null) {
            listing.add(FileNode.parentNode(parent));
        }
        if (error != null) {
            setStatus(I18n.get("filebrowser.error.accessDenied"));
        } else {
            listing.addAll(children);
            clearAccessError();
        }
        rows.setAll(listing);
        table.getSelectionModel().clearSelection();
        for (int index = 0; index < rows.size(); index++) {
            FileNode node = rows.get(index);
            if (!node.parentEntry() && selected.contains(node.file())) {
                table.getSelectionModel().select(index);
            }
        }
        updateSelectedItems();
    }

    /**
     * Clears only a stale "access denied" message, so a status set by a file operation
     * (which finishes by calling {@link #refresh()}) is not wiped by the reload that follows.
     */
    private void clearAccessError() {
        if (statusLabel != null && I18n.get("filebrowser.error.accessDenied").equals(statusLabel.getText())) {
            setStatus("");
        }
    }

    /**
     * Runs a filesystem mutation off the FX thread with the loading overlay shown, then refreshes
     * the tree. The supplier returns a status message to display when done (or {@code null}); an
     * unexpected exception surfaces its message instead. Keeps large copy/move/archive operations
     * from freezing the UI.
     */
    private void runFileOperation(Supplier<String> operation) {
        showLoading(true);
        CompletableFuture
            .supplyAsync(operation)
            .whenComplete((message, throwable) -> Platform.runLater(() -> {
                refresh();
                if (throwable != null) {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    setStatus(cause.getMessage() != null ? cause.getMessage() : cause.toString());
                } else if (message != null && !message.isBlank()) {
                    setStatus(message);
                }
            }));
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
        return new FileNode(path.toFile(), directory, hidden, false, symlink, executable, size, lastModified);
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
        if (footerLabel == null) {
            return;
        }
        long folders = 0;
        long files = 0;
        for (FileNode node : rows) {
            if (node.parentEntry()) {
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
        List<File> sources = List.copyOf(clipboardFiles);
        boolean cut = clipboardCut;
        if (cut) {
            clipboardFiles.clear();
            clipboardCut = false;
        }
        runFileOperation(() -> {
            String error = null;
            for (File source : sources) {
                try {
                    Path destination = target.resolve(source.getName());
                    if (cut) {
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
                    error = I18n.get("filebrowser.error.paste") + ": " + e.getMessage();
                }
            }
            return error;
        });
    }

    private Path selectedTargetDirectory() {
        if (selectedItems.size() == 1 && selectedItems.get(0).directory()) {
            return selectedItems.get(0).file().toPath();
        }
        return currentRoot;
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

    private enum DeleteMode { TRASH, PERMANENT }

    private void deleteSelectedFiles() {
        if (selectedItems.isEmpty()) {
            return;
        }
        boolean trashSupported = Desktop.isDesktopSupported()
            && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH);
        DeleteMode mode = confirmDelete(selectedItems.size(), trashSupported);
        if (mode == null) {
            return; // cancelled
        }
        boolean permanent = mode == DeleteMode.PERMANENT;
        List<File> targets = selectedItems.stream().map(FileNode::file).collect(Collectors.toList());
        selectedItems.clear();
        runFileOperation(() -> {
            int done = 0;
            String error = null;
            for (File file : targets) {
                try {
                    if (permanent) {
                        Path path = file.toPath();
                        if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                            deleteDirectory(path);
                        } else {
                            Files.delete(path);
                        }
                        done++;
                    } else if (Desktop.getDesktop().moveToTrash(file)) {
                        done++;
                    } else {
                        error = I18n.get("filebrowser.error.trashNotSupported");
                    }
                } catch (IOException | SecurityException | IllegalArgumentException e) {
                    error = I18n.get("filebrowser.error.delete") + ": " + e.getMessage();
                }
            }
            if (error != null) {
                return error;
            }
            return I18n.get(permanent ? "filebrowser.deleted" : "filebrowser.trashed", done);
        });
    }

    /** Asks whether to trash or permanently delete; returns {@code null} if the user cancels. */
    private DeleteMode confirmDelete(int count, boolean trashSupported) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.get("filebrowser.context.delete"));
        alert.setHeaderText(null);
        alert.setContentText(I18n.get(
            trashSupported ? "filebrowser.delete.content" : "filebrowser.delete.contentNoTrash", count));
        ButtonType trashButton =
            new ButtonType(I18n.get("filebrowser.delete.confirm.title"), ButtonBar.ButtonData.OK_DONE);
        ButtonType permanentButton =
            new ButtonType(I18n.get("filebrowser.delete.permanent"), ButtonBar.ButtonData.OTHER);
        if (trashSupported) {
            alert.getButtonTypes().setAll(trashButton, permanentButton, ButtonType.CANCEL);
        } else {
            alert.getButtonTypes().setAll(permanentButton, ButtonType.CANCEL);
        }
        DialogThemeHelper.applyTheme(alert);
        if (getScene() != null && getScene().getWindow() != null) {
            alert.initOwner(getScene().getWindow());
        }
        ButtonType result = alert.showAndWait().orElse(ButtonType.CANCEL);
        if (result == trashButton) {
            return DeleteMode.TRASH;
        }
        if (result == permanentButton) {
            return DeleteMode.PERMANENT;
        }
        return null;
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
        infoLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
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
        table.getSelectionModel().clearSelection();
        for (int index = 0; index < rows.size(); index++) {
            if (!rows.get(index).parentEntry()) {
                table.getSelectionModel().select(index);
            }
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
        List<File> files = selectedItems.stream().map(FileNode::file).collect(Collectors.toList());
        Path archivePath = selectedTargetDirectory().resolve("archive." + format);
        runFileOperation(() -> {
            try {
                if ("zip".equals(format)) {
                    archiveToZip(archivePath, files);
                } else if ("tar".equals(format)) {
                    archiveToTar(archivePath, false, files);
                } else if ("tgz".equals(format)) {
                    archiveToTar(archivePath, true, files);
                }
                return I18n.get("filebrowser.archive.created") + ": " + archivePath.getFileName();
            } catch (IOException | SecurityException e) {
                return I18n.get("filebrowser.error.archive") + ": " + e.getMessage();
            }
        });
    }

    private void archiveToZip(Path zipPath, List<File> files) throws IOException {
        try (var zipOutput = new java.util.zip.ZipOutputStream(Files.newOutputStream(zipPath))) {
            for (File file : files) {
                addToZip(zipOutput, file.toPath(), "");
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

    private void archiveToTar(Path tarPath, boolean gzip, List<File> files) throws IOException {
        try (var output = Files.newOutputStream(tarPath);
             var compressedOutput = gzip ? new java.util.zip.GZIPOutputStream(output) : output;
             var dataOutput = new DataOutputStream(compressedOutput)) {
            for (File file : files) {
                addToTar(dataOutput, file.toPath(), "");
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
                    Files.createDirectory(currentRoot.resolve(name));
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
                    Files.createFile(currentRoot.resolve(name));
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

    private static String formatSize(long size) {
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
        AppDesignStyleSupport.applyToParent(this);
        applyDesignTokens();
        if (toolbar != null) {
            FileBrowserIcons.retintGlyphs(toolbar, iconTint());
        }
        table.refresh();
    }

    /**
     * A korTTY design of its own (Gruvbox, Nord, ...) sets the panel's colors through
     * filebrowser.css's -kortty-fb-* tokens, derived from the design's palette, so the listing
     * matches the rest of the window; AtlantaFX designs bring their own tokens and the Normal
     * design keeps the stylesheet's defaults.
     */
    private void applyDesignTokens() {
        de.kortty.model.AppDesign design = AppDesignStyleSupport.activeDesign();
        if (design == de.kortty.model.AppDesign.NORMAL) {
            setStyle("-fx-background-color: " + PANEL_BACKGROUND + ";");
            return;
        }
        if (design.isAtlantaFx()) {
            setStyle(null);
            return;
        }
        setStyle(FileBrowserPalette.tokenStyle(AppDesignStyleSupport.activeBackgroundColor(),
            AppDesignStyleSupport.activeTextColor(), AppDesignStyleSupport.activeDimColor(),
            AppDesignStyleSupport.activeAccentColor()));
    }

    public void refresh() {
        loadListing();
    }

    private static final class FileNode implements FileBrowserSort.Entry {
        private final File file;
        private final boolean directory;
        private final boolean hidden;
        private final boolean parentEntry;
        private final boolean symlink;
        private final boolean executable;
        private final long size;
        private final long lastModified;

        private FileNode(File file, boolean directory, boolean hidden, boolean parentEntry,
                         boolean symlink, boolean executable, long size, long lastModified) {
            this.file = file;
            this.directory = directory;
            this.hidden = hidden;
            this.parentEntry = parentEntry;
            this.symlink = symlink;
            this.executable = executable;
            this.size = size;
            this.lastModified = lastModified;
        }

        /** The ".." row: the parent of the folder shown. */
        private static FileNode parentNode(Path parent) {
            return new FileNode(parent.toFile(), true, false, true, false, false, 0, 0);
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

        private boolean parentEntry() {
            return parentEntry;
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
