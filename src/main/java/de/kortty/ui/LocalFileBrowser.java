package de.kortty.ui;

import javafx.collections.FXCollections;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.SelectionMode;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.Dragboard;
import javafx.scene.input.TransferMode;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.SVGPath;

import java.awt.Desktop;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.file.AccessDeniedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * Compact local file browser panel that can be docked to the left or right side
 * of the main window.
 */
public class LocalFileBrowser extends VBox {

    private static final String PANEL_BACKGROUND = "#21252b";
    private static final String FOLDER_ICON_COLOR = "#8fa1b3";
    private static final String FILE_ICON_COLOR = "#abb2bf";
    private static final String HIDDEN_ICON_COLOR = "#636d7a";
    private static final String FOLDER_ICON_PATH = "M1 5 L1 14 L15 14 L15 4 L8 4 L7 2 L1 2 Z";
    private static final String FILE_ICON_PATH = "M3 1 L10 1 L14 5 L14 15 L3 15 Z";
    private static final Path UNIX_PASSWD_FILE = Paths.get("/etc/passwd");
    private static final Path UNIX_GROUP_FILE = Paths.get("/etc/group");

    private final Path rootPath;
    private final TreeItem<FileNode> rootItem;
    private final TreeView<FileNode> treeView;
    private final Label statusLabel;
    private final ObservableList<FileNode> selectedItems = FXCollections.observableArrayList();
    private final ArrayList<File> clipboardFiles = new ArrayList<>();

    private Path currentDirectory;
    private boolean showHiddenFiles = false;
    private boolean clipboardCut = false;
    private CheckMenuItem showHiddenMenuItem;

    public LocalFileBrowser() {
        rootPath = Paths.get(System.getProperty("user.home")).toAbsolutePath().normalize();
        currentDirectory = rootPath;

        setPadding(Insets.EMPTY);
        setSpacing(0);
        setStyle("-fx-background-color: " + PANEL_BACKGROUND + ";");
        getStyleClass().add("file-browser-panel");
        addStylesheet();

        statusLabel = new Label("");
        statusLabel.getStyleClass().add("file-browser-status");
        statusLabel.setVisible(false);
        statusLabel.setManaged(false);

        rootItem = createTreeItem(rootPath);
        loadTreeChildren(rootItem);
        rootItem.setExpanded(true);

        treeView = new TreeView<>(rootItem);
        treeView.setShowRoot(true);
        treeView.setFixedCellSize(22);
        treeView.getStyleClass().add("file-browser-tree");
        treeView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        treeView.setCellFactory(view -> createTreeCell());
        treeView.setContextMenu(createContextMenu());
        treeView.getSelectionModel().getSelectedItems().addListener(
            (ListChangeListener<? super TreeItem<FileNode>>) change -> updateSelectedItems());

        VBox.setVgrow(treeView, Priority.ALWAYS);
        getChildren().addAll(treeView, statusLabel);
    }

    private void addStylesheet() {
        URL stylesheet = LocalFileBrowser.class.getResource("/styles/filebrowser.css");
        if (stylesheet != null) {
            getStylesheets().add(stylesheet.toExternalForm());
        }
    }

    private TreeCell<FileNode> createTreeCell() {
        TreeCell<FileNode> cell = new TreeCell<>() {
            @Override
            protected void updateItem(FileNode item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null || item.placeholder()) {
                    setText(null);
                    setGraphic(null);
                    return;
                }
                setText(item.name());
                setGraphic(createIcon(item));
            }
        };

        cell.setOnMouseClicked(event -> {
            FileNode node = cell.getItem();
            if (node == null || node.placeholder()) {
                return;
            }
            updateCurrentDirectory(node);
            if (event.getClickCount() == 2) {
                if (node.directory()) {
                    TreeItem<FileNode> item = cell.getTreeItem();
                    if (item != null) {
                        ensureLoaded(item);
                        item.setExpanded(!item.isExpanded());
                    }
                } else {
                    openFile(node.file());
                }
            }
        });

        cell.setOnContextMenuRequested(event -> {
            if (!cell.isEmpty() && cell.getTreeItem() != null) {
                MultipleSelectionModel<TreeItem<FileNode>> selectionModel = treeView.getSelectionModel();
                if (!selectionModel.isSelected(cell.getIndex())) {
                    selectionModel.clearSelection();
                    selectionModel.select(cell.getIndex());
                }
                updateCurrentDirectory(cell.getItem());
            }
        });

        cell.setOnDragDetected(event -> {
            FileNode item = cell.getItem();
            if (item == null || item.placeholder()) {
                return;
            }
            List<File> files = selectedItems.isEmpty()
                ? List.of(item.file())
                : selectedItems.stream()
                    .map(FileNode::file)
                    .toList();
            if (files.isEmpty()) {
                return;
            }
            Dragboard dragboard = cell.startDragAndDrop(TransferMode.COPY);
            ClipboardContent content = new ClipboardContent();
            content.putFiles(files);
            dragboard.setContent(content);
            event.consume();
        });

        return cell;
    }

    private Node createIcon(FileNode node) {
        SVGPath icon = new SVGPath();
        icon.setContent(node.directory() ? FOLDER_ICON_PATH : FILE_ICON_PATH);
        icon.setFill(Color.web(
            node.hidden() ? HIDDEN_ICON_COLOR : (node.directory() ? FOLDER_ICON_COLOR : FILE_ICON_COLOR)));
        icon.setScaleX(0.9);
        icon.setScaleY(0.9);
        icon.setOpacity(node.hidden() ? 0.75 : 1.0);
        return icon;
    }

    private ContextMenu createContextMenu() {
        ContextMenu menu = new ContextMenu();

        MenuItem openItem = new MenuItem(I18n.get("filebrowser.context.open"));
        openItem.setOnAction(event -> openSelected());

        MenuItem copyItem = new MenuItem(I18n.get("filebrowser.context.copy"));
        copyItem.setOnAction(event -> copySelectedFiles(false));

        MenuItem cutItem = new MenuItem(I18n.get("filebrowser.context.cut"));
        cutItem.setOnAction(event -> copySelectedFiles(true));

        MenuItem pasteItem = new MenuItem(I18n.get("filebrowser.context.paste"));
        pasteItem.setOnAction(event -> pasteFiles());

        MenuItem deleteItem = new MenuItem(I18n.get("filebrowser.context.delete"));
        deleteItem.setOnAction(event -> deleteSelectedFiles());

        MenuItem selectAllItem = new MenuItem(I18n.get("filebrowser.context.selectAll"));
        selectAllItem.setOnAction(event -> selectAllFiles());

        MenuItem detailsItem = new MenuItem(I18n.get("filebrowser.context.details"));
        detailsItem.setOnAction(event -> showDetails());

        Menu archiveMenu = new Menu(I18n.get("filebrowser.context.archive"));
        MenuItem zipItem = new MenuItem("ZIP");
        zipItem.setOnAction(event -> archiveSelected("zip"));
        MenuItem tarItem = new MenuItem("TAR");
        tarItem.setOnAction(event -> archiveSelected("tar"));
        MenuItem tgzItem = new MenuItem("TAR.GZ");
        tgzItem.setOnAction(event -> archiveSelected("tgz"));
        archiveMenu.getItems().addAll(zipItem, tarItem, tgzItem);

        MenuItem newFolderItem = new MenuItem(I18n.get("filebrowser.context.newFolder"));
        newFolderItem.setOnAction(event -> createNewFolder());

        MenuItem newFileItem = new MenuItem(I18n.get("filebrowser.context.newFile"));
        newFileItem.setOnAction(event -> createNewFile());

        MenuItem ownerPermissionsItem = new MenuItem(I18n.get("sftp.setOwner.title"));
        ownerPermissionsItem.setOnAction(event -> setOwnerPermissionsDialog());

        showHiddenMenuItem = new CheckMenuItem(I18n.get("filebrowser.showHidden"));
        showHiddenMenuItem.setSelected(showHiddenFiles);
        showHiddenMenuItem.setOnAction(event -> {
            showHiddenFiles = showHiddenMenuItem.isSelected();
            refresh();
        });

        menu.getItems().addAll(
            openItem,
            new SeparatorMenuItem(),
            copyItem,
            cutItem,
            pasteItem,
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
        return menu;
    }

    private void updateSelectedItems() {
        selectedItems.setAll(treeView.getSelectionModel().getSelectedItems().stream()
            .map(TreeItem::getValue)
            .filter(node -> node != null && !node.placeholder())
            .toList());
        if (!selectedItems.isEmpty()) {
            updateCurrentDirectory(selectedItems.get(0));
        }
    }

    private void updateCurrentDirectory(FileNode node) {
        if (node == null || node.placeholder()) {
            return;
        }
        currentDirectory = node.directory() ? node.file().toPath() : node.file().toPath().getParent();
        if (currentDirectory == null) {
            currentDirectory = rootPath;
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

    private TreeItem<FileNode> createTreeItem(Path path) {
        boolean directory = Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS);
        TreeItem<FileNode> item = new TreeItem<>(new FileNode(path.toFile(), directory, isHidden(path), false));
        if (directory) {
            item.getChildren().add(new TreeItem<>(FileNode.placeholderNode()));
            item.expandedProperty().addListener((observable, wasExpanded, expanded) -> {
                if (expanded) {
                    ensureLoaded(item);
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
        parent.getChildren().clear();
        Path directory = parentNode.file().toPath();
        try (var stream = Files.list(directory)) {
            List<Path> entries = stream
                .filter(this::shouldShowPath)
                .sorted(Comparator
                    .comparing((Path path) -> !Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .thenComparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                .toList();
            for (Path entry : entries) {
                parent.getChildren().add(createTreeItem(entry));
            }
            setStatus("");
        } catch (IOException | SecurityException e) {
            setStatus(I18n.get("filebrowser.error.accessDenied"));
        }
    }

    private boolean shouldShowPath(Path path) {
        if (showHiddenFiles) {
            return true;
        }
        Path fileName = path.getFileName();
        return fileName == null || !fileName.toString().startsWith(".");
    }

    private boolean isHidden(Path path) {
        try {
            return Files.isHidden(path);
        } catch (IOException e) {
            return false;
        }
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
        return currentDirectory != null ? currentDirectory : rootPath;
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
        for (FileNode node : selectedItems) {
            try {
                Path path = node.file().toPath();
                if (node.directory()) {
                    deleteDirectory(path);
                } else {
                    Files.delete(path);
                }
            } catch (IOException | SecurityException e) {
                setStatus(I18n.get("filebrowser.error.delete") + ": " + e.getMessage());
            }
        }
        selectedItems.clear();
        refresh();
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
        try {
            Path resolved = file.toPath().toRealPath();
            Path home = rootPath.toRealPath();
            if (!resolved.startsWith(home)) {
                setStatus(I18n.get("filebrowser.error.outsideHome"));
                return;
            }
        } catch (IOException e) {
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
        setStyle("-fx-background-color: " + PANEL_BACKGROUND + ";");
        treeView.refresh();
    }

    public void refresh() {
        rootItem.getChildren().clear();
        rootItem.getChildren().add(new TreeItem<>(FileNode.placeholderNode()));
        ensureLoaded(rootItem);
        treeView.refresh();
    }

    private static final class FileNode {
        private final File file;
        private final boolean directory;
        private final boolean hidden;
        private final boolean placeholder;
        private final long size;

        private FileNode(File file, boolean directory, boolean hidden, boolean placeholder) {
            this.file = file;
            this.directory = directory;
            this.hidden = hidden;
            this.placeholder = placeholder;
            this.size = directory || placeholder || !file.exists() ? 0 : file.length();
        }

        private static FileNode placeholderNode() {
            return new FileNode(new File(""), true, false, true);
        }

        private File file() {
            return file;
        }

        private String name() {
            String name = file.getName();
            return name == null || name.isBlank() ? file.getAbsolutePath() : name;
        }

        private boolean directory() {
            return directory;
        }

        private boolean hidden() {
            return hidden;
        }

        private boolean placeholder() {
            return placeholder;
        }

        private long size() {
            return size;
        }
    }
}
