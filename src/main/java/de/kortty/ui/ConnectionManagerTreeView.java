package de.kortty.ui;

import de.kortty.model.GroupPath;
import de.kortty.model.ServerConnection;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.*;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Custom TreeView for displaying and managing connection groups hierarchically.
 */
public class ConnectionManagerTreeView extends TreeView<ConnectionTreeItem.ItemData> {

    /**
     * Connection and group rows: a little larger than the default text, colour left to the theme
     * (a fixed colour was unreadable on dark designs).
     */
    static final String ENTRY_STYLE = "-fx-font-size: 1.08em;";
    /** Jump-host placeholders under a connection: smaller and dimmed, in any theme. */
    static final String PLACEHOLDER_STYLE = "-fx-font-size: 0.92em; -fx-opacity: 0.65;";
    /** Connections the enterprise policy blocks: dimmed, in any theme. */
    static final String BLOCKED_STYLE = ENTRY_STYLE + " -fx-opacity: 0.55;";
    
    /** The folder indent the tree's stylesheet sets; the connector lines are drawn from it. */
    static final double INDENT = 32;

    private final List<ServerConnection> connections;
    /** Teamwork connections shown read-only in a branch of their own below the local ones; empty for none. */
    private List<ServerConnection> sharedConnections = List.of();
    private String sharedBranchTitle = "";
    private Button undoButton;
    private final Deque<MoveOperation> moveHistory = new ArrayDeque<>();
    private Predicate<ServerConnection> currentSearchPredicate = null;
    private boolean selectionOnly;
    /** The connections come from a shared (teamwork) source and are never changed in this tree. */
    private boolean readOnlyConnections;
    
    // Callbacks
    private Consumer<GroupPath> onCreateGroup;
    private Consumer<GroupPath> onRenameGroup;
    private Consumer<GroupPath> onDeleteGroup;
    private Runnable onDoubleClick;
    private Consumer<ServerConnection> onEditConnection;
    private Consumer<List<ServerConnection>> onExportConnections;
    private Consumer<List<ServerConnection>> onDeleteConnections;
    private Consumer<GroupPath> onExportGroup;
    private Consumer<List<ServerConnection>> onAssignTag;
    private Consumer<List<ServerConnection>> onRemoveTag;
    private Consumer<GroupPath> onAssignTagToGroup;
    private Consumer<GroupPath> onRemoveTagFromGroup;
    /** Toggles host-key verification for a group; the boolean is the new "disabled" state. */
    private java.util.function.BiConsumer<GroupPath, Boolean> onToggleGroupHostKeyCheck;
    /** Reports whether a group currently has host-key verification disabled, to render the check mark. */
    private java.util.function.Predicate<GroupPath> groupHostKeyCheckDisabled;
    /** Opens the tab color of a group for editing; without it the group menu has no color entry. */
    private Consumer<GroupPath> onEditGroupColor;
    /** Sets a group's isolation level, null to follow the folder above or Settings; without it no submenu. */
    private java.util.function.BiConsumer<GroupPath, de.kortty.isolation.IsolationLevel> onSetGroupIsolation;
    /** The isolation level a group sets itself, or null. */
    private java.util.function.Function<GroupPath, de.kortty.isolation.IsolationLevel> groupIsolationProbe;
    /** The tab color a group has of its own ({@code #RRGGBB}), or null; shown as a dot after the folder's name. */
    private java.util.function.Function<GroupPath, String> groupColorProbe;
    /** Runs after a connection was dragged into another group or such a move was undone. */
    private Runnable onConnectionsMoved;
    private Runnable onAddConnection;
    /** How many tabs a connection has open in every window; drawn as a green dot and a count. */
    private java.util.function.ToIntFunction<ServerConnection> openTabsProbe;
    /** Whether a connection is a favorite; drawn as a star after its name. */
    private java.util.function.Predicate<ServerConnection> favoriteProbe;
    /** Marks the selected connections as favorites or unmarks them; without it the menu has no entry. */
    private Consumer<List<ServerConnection>> onToggleFavorite;
    /** The OS badge drawn on a connection's tile, or null. */
    private java.util.function.Function<ServerConnection, javafx.scene.Node> badgeProbe;
    /** Builds the "OS symbol" submenu for the selected connections; without it the menu has no entry. */
    private java.util.function.Function<List<ServerConnection>, Menu> osBadgeMenu;
    
    public ConnectionManagerTreeView(List<ServerConnection> connections) {
        this.connections = connections;
        
        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        setShowRoot(false);
        getStyleClass().add("cm-tree");
        getStylesheets().add(Objects.requireNonNull(
            getClass().getResource("/styles/connection-tree.css")).toExternalForm());
        
        refreshTree();
        setupDragAndDrop();
        setCellFactory();
        updateEmptyAreaContextMenu();
    }

    public void setSelectionOnly(boolean selectionOnly) {
        this.selectionOnly = selectionOnly;
        updateEmptyAreaContextMenu();
        setCellFactory();
    }

    /**
     * Marks the connections in this tree as read-only, as teamwork connections come from a shared source
     * that korTTY never writes back to: they cannot be dragged into another folder, their context menu
     * has no Edit entry, and neither its folders nor its empty space offer anything that changes them.
     */
    public void setReadOnlyConnections(boolean readOnlyConnections) {
        this.readOnlyConnections = readOnlyConnections;
        updateEmptyAreaContextMenu();
    }

    /** Sets the menu for a right-click into empty space again, after a setting or handler it depends on changed. */
    private void updateEmptyAreaContextMenu() {
        setContextMenu(selectionOnly ? null : createEmptyAreaContextMenu());
    }

    /**
     * Context menu shown when right-clicking on empty area (no item under cursor): a new folder and a new
     * connection, each only with its handler and never in a read-only tree, or null when that leaves none.
     */
    private ContextMenu createEmptyAreaContextMenu() {
        ContextMenu menu = new ContextMenu();
        MenuItem createFolderItem = new MenuItem(I18n.get("connManager.newFolder"));
        createFolderItem.setOnAction(e -> {
            if (onCreateGroup != null) {
                onCreateGroup.accept(GroupPath.ROOT);
            }
        });
        MenuItem createConnectionItem = new MenuItem(I18n.get("connectionManager.new"));
        createConnectionItem.setOnAction(e -> {
            if (onAddConnection != null) {
                onAddConnection.run();
            }
        });
        if (onCreateGroup != null && !readOnlyConnections) {
            menu.getItems().add(createFolderItem);
        }
        if (onAddConnection != null && !readOnlyConnections) {
            menu.getItems().add(createConnectionItem);
        }
        return menu.getItems().isEmpty() ? null : menu;
    }
    
    /**
     * Refreshes the tree structure from the connections list.
     */
    public void refreshTree() {
        Map<String, TreeItem<ConnectionTreeItem.ItemData>> groupItems = new HashMap<>();
        TreeItem<ConnectionTreeItem.ItemData> root = new TreeItem<>(new ConnectionTreeItem.ItemData(true, "Root", GroupPath.ROOT, null));
        
        // Create all group items
        Set<GroupPath> allGroups = new HashSet<>();
        for (ServerConnection conn : connections) {
            if (conn.getGroup() != null && !conn.getGroup().trim().isEmpty()) {
                GroupPath path = new GroupPath(conn.getGroup());
                while (path != null && !path.isRoot()) {
                    allGroups.add(path);
                    path = path.getParent();
                }
            }
        }
        
        // Create tree items for all groups
        List<GroupPath> sortedGroups = new ArrayList<>(allGroups);
        sortedGroups.sort(Comparator.comparingInt(GroupPath::getDepth).thenComparing(GroupPath::getPath));
        
        for (GroupPath groupPath : sortedGroups) {
            TreeItem<ConnectionTreeItem.ItemData> groupItem = ConnectionTreeItem.forGroup(groupPath);
            groupItems.put(groupPath.getPath(), groupItem);
            
            GroupPath parent = groupPath.getParent();
            if (parent == null || parent.isRoot()) {
                root.getChildren().add(groupItem);
            } else {
                TreeItem<ConnectionTreeItem.ItemData> parentItem = groupItems.get(parent.getPath());
                if (parentItem != null) {
                    parentItem.getChildren().add(groupItem);
                }
            }
        }
        
        // Add connections to their groups
        List<ServerConnection> sortedConnections = new ArrayList<>(connections);
        sortedConnections.sort(Comparator.comparing(ServerConnection::getName, String.CASE_INSENSITIVE_ORDER));
        
        for (ServerConnection conn : sortedConnections) {
            if (conn.isPlaceholder()) {
                // A placeholder only keeps an empty folder alive; the folder above it is all the tree shows.
                continue;
            } else {
                TreeItem<ConnectionTreeItem.ItemData> connItem = ConnectionTreeItem.forConnection(conn);
                
                String group = conn.getGroup();
                if (group == null || group.trim().isEmpty()) {
                    root.getChildren().add(connItem);
                } else {
                    TreeItem<ConnectionTreeItem.ItemData> groupItem = groupItems.get(group);
                    if (groupItem != null) {
                        groupItem.getChildren().add(connItem);
                    }
                }
            }
        }
        
        appendSharedBranch(root, sharedConnections);
        root.setExpanded(true);
        expandAll(root);
        
        setRoot(root);
    }

    /**
     * Shows {@code shared} (teamwork) connections read-only in a branch titled {@code branchTitle} below the
     * local folders, in the folders of their shared files; an empty list removes the branch. Takes effect
     * with the next {@link #refreshTree} or {@link #filterTree}.
     */
    public void setSharedConnections(List<ServerConnection> shared, String branchTitle) {
        this.sharedConnections = shared != null ? shared : List.of();
        this.sharedBranchTitle = branchTitle != null ? branchTitle : "";
    }

    /** Whether {@code connection} is one of the shared connections of this tree's teamwork branch. */
    public boolean isShared(ServerConnection connection) {
        for (ServerConnection shared : sharedConnections) {
            if (shared == connection) {
                return true;
            }
        }
        return false;
    }

    /** Adds the teamwork branch with {@code shared} in their folders; nothing for an empty list. */
    private void appendSharedBranch(TreeItem<ConnectionTreeItem.ItemData> root, List<ServerConnection> shared) {
        List<ServerConnection> visible = shared.stream().filter(conn -> !conn.isPlaceholder())
            .sorted(Comparator.comparing(ServerConnection::getName, String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.toList());
        if (visible.isEmpty()) {
            return;
        }
        TreeItem<ConnectionTreeItem.ItemData> branch = ConnectionTreeItem.forSharedGroup(null, sharedBranchTitle);
        Map<String, TreeItem<ConnectionTreeItem.ItemData>> folders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        for (ServerConnection conn : visible) {
            TreeItem<ConnectionTreeItem.ItemData> parent = branch;
            if (conn.getGroup() != null && !conn.getGroup().isBlank()) {
                GroupPath path = new GroupPath(conn.getGroup());
                StringBuilder key = new StringBuilder();
                for (String segment : path.getSegments()) {
                    key.append(key.length() > 0 ? GroupPath.SEPARATOR : "").append(segment);
                    TreeItem<ConnectionTreeItem.ItemData> folderParent = parent;
                    parent = folders.computeIfAbsent(key.toString(), k -> {
                        TreeItem<ConnectionTreeItem.ItemData> folder =
                            ConnectionTreeItem.forSharedGroup(new GroupPath(k), segment);
                        folderParent.getChildren().add(folder);
                        return folder;
                    });
                }
            }
            parent.getChildren().add(ConnectionTreeItem.forSharedConnection(conn));
        }
        // Folders before connections inside every shared folder, as in the local part of the tree.
        sortFoldersFirst(branch);
        root.getChildren().add(branch);
    }

    private static void sortFoldersFirst(TreeItem<ConnectionTreeItem.ItemData> item) {
        item.getChildren().sort(Comparator.comparing((TreeItem<ConnectionTreeItem.ItemData> child) ->
            !child.getValue().isGroup()));
        for (TreeItem<ConnectionTreeItem.ItemData> child : item.getChildren()) {
            sortFoldersFirst(child);
        }
    }
    
    /**
     * Filters the tree based on a search predicate.
     * Only connections matching the predicate (and their parent groups) will be shown.
     */
    public void filterTree(Predicate<ServerConnection> searchPredicate) {
        this.currentSearchPredicate = searchPredicate;
        
        if (searchPredicate == null) {
            // Rebuilt rather than restored: the teamwork branch may have changed since the last build.
            refreshTree();
            return;
        }
        
        // Build filtered tree
        Map<String, TreeItem<ConnectionTreeItem.ItemData>> groupItems = new HashMap<>();
        TreeItem<ConnectionTreeItem.ItemData> filteredRoot = new TreeItem<>(new ConnectionTreeItem.ItemData(true, "Root", GroupPath.ROOT, null));
        Set<GroupPath> neededGroups = new HashSet<>();
        
        // Find all connections that match and collect their groups
        List<ServerConnection> matchingConnections = connections.stream()
                .filter(conn -> !conn.isPlaceholder() && searchPredicate.test(conn))
                .collect(Collectors.toList());
        
        for (ServerConnection conn : matchingConnections) {
            if (conn.getGroup() != null && !conn.getGroup().trim().isEmpty()) {
                GroupPath path = new GroupPath(conn.getGroup());
                while (path != null && !path.isRoot()) {
                    neededGroups.add(path);
                    path = path.getParent();
                }
            }
        }
        
        // Create tree items for needed groups
        List<GroupPath> sortedGroups = new ArrayList<>(neededGroups);
        sortedGroups.sort(Comparator.comparingInt(GroupPath::getDepth).thenComparing(GroupPath::getPath));
        
        for (GroupPath groupPath : sortedGroups) {
            TreeItem<ConnectionTreeItem.ItemData> groupItem = ConnectionTreeItem.forGroup(groupPath);
            groupItems.put(groupPath.getPath(), groupItem);
            
            GroupPath parent = groupPath.getParent();
            if (parent == null || parent.isRoot()) {
                filteredRoot.getChildren().add(groupItem);
            } else {
                TreeItem<ConnectionTreeItem.ItemData> parentItem = groupItems.get(parent.getPath());
                if (parentItem != null) {
                    parentItem.getChildren().add(groupItem);
                }
            }
        }
        
        // Add matching connections
        matchingConnections.sort(Comparator.comparing(ServerConnection::getName, String.CASE_INSENSITIVE_ORDER));
        
        for (ServerConnection conn : matchingConnections) {
            TreeItem<ConnectionTreeItem.ItemData> connItem = ConnectionTreeItem.forConnection(conn);
            
            String group = conn.getGroup();
            if (group == null || group.trim().isEmpty()) {
                filteredRoot.getChildren().add(connItem);
            } else {
                TreeItem<ConnectionTreeItem.ItemData> groupItem = groupItems.get(group);
                if (groupItem != null) {
                    groupItem.getChildren().add(connItem);
                }
            }
        }
        
        appendSharedBranch(filteredRoot, sharedConnections.stream().filter(searchPredicate).collect(Collectors.toList()));
        filteredRoot.setExpanded(true);
        expandAll(filteredRoot);
        setRoot(filteredRoot);
    }
    
    private void expandAll(TreeItem<?> item) {
        if (item != null && !item.isLeaf()) {
            item.setExpanded(true);
            for (TreeItem<?> child : item.getChildren()) {
                expandAll(child);
            }
        }
    }
    
    /**
     * Grays out and tooltips a connection whose target (or jump host) is blocked by the
     * enterprise server policy. Connecting is refused centrally; the marking just makes the
     * restriction visible instead of leaving the user to find out on connect.
     */
    private static void markIfPolicyBlocked(TreeCell<ConnectionTreeItem.ItemData> cell,
                                            ConnectionTreeItem.ItemData item) {
        ServerConnection connection = item.getConnection();
        if (connection == null
            || de.kortty.policy.ServerAccessPolicy.isAllowed(connection)) {
            return;
        }
        if (cell.getGraphic() instanceof javafx.scene.layout.HBox row) {
            row.getChildren().add(ToolbarIcons.create("blocked", cell.textFillProperty(), 15));
        }
        cell.setStyle(BLOCKED_STYLE);
        cell.setTooltip(new Tooltip(I18n.get("policy.server.blocked.title") + " — "
            + de.kortty.policy.PolicyUiSupport.managedByOrganizationText()));
    }

    /**
     * Sets up drag and drop functionality for moving connections between groups.
     */
    private void setupDragAndDrop() {
        setCellFactory(tv -> {
            TreeCell<ConnectionTreeItem.ItemData> cell = new TreeCell<>() {
                @Override
                protected void updateItem(ConnectionTreeItem.ItemData item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                    } else {
                        setStyle(ENTRY_STYLE);
                        if (item.isGroup()) {
                            setText("📁 " + item.getDisplayName());
                        } else if (item.getConnection() != null && item.getConnection().isPlaceholder()) {
                            setText("└─ " + item.getDisplayName());
                            setStyle(PLACEHOLDER_STYLE);
                        } else {
                            setText("🔌 " + item.getDisplayName());
                            markIfPolicyBlocked(this, item);
                        }
                    }
                }
            };

            // Drag detected (only for non-placeholder connections)
            cell.setOnDragDetected(event -> {
                if (!cell.isEmpty() && cell.getItem() != null &&
                    !cell.getItem().isGroup() &&
                    cell.getItem().getConnection() != null &&
                    !cell.getItem().getConnection().isPlaceholder()) {

                    Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent();
                    content.putString(cell.getItem().getConnection().getId());
                    db.setContent(content);
                    event.consume();
                }
            });
            
            // Drag over (only on groups)
            cell.setOnDragOver(event -> {
                if (event.getGestureSource() != cell && 
                    event.getDragboard().hasString() &&
                    cell.getItem() != null && 
                    cell.getItem().isGroup()) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });
            
            // Drag dropped
            cell.setOnDragDropped(event -> {
                Dragboard db = event.getDragboard();
                boolean success = false;
                
                if (db.hasString() && cell.getItem() != null && cell.getItem().isGroup()) {
                    String connectionId = db.getString();
                    ServerConnection conn = connections.stream()
                            .filter(c -> c.getId().equals(connectionId))
                            .findFirst()
                            .orElse(null);
                    
                    if (conn != null) {
                        String oldGroup = conn.getGroup();
                        String newGroup = cell.getItem().getGroupPath().getPath();
                        
                        conn.setGroup(newGroup);
                        moveHistory.push(new MoveOperation(conn, oldGroup, newGroup));
                        
                        if (undoButton != null) {
                            undoButton.setDisable(false);
                        }
                        
                        if (currentSearchPredicate != null) {
                            filterTree(currentSearchPredicate);
                        } else {
                            refreshTree();
                        }
                        success = true;
                    }
                }
                
                event.setDropCompleted(success);
                event.consume();
            });
            
            return cell;
        });
    }
    
    /**
     * Sets up the cell factory with context menus and double-click handling.
     */
    private void setCellFactory() {
        setCellFactory(tv -> {
            TreeCell<ConnectionTreeItem.ItemData> cell = new TreeCell<>() {
                @Override
                protected void updateItem(ConnectionTreeItem.ItemData item, boolean empty) {
                    super.updateItem(item, empty);
                    
                    setText(null);
                    setGraphic(null);
                    setContextMenu(null);
                    setTooltip(null);
                    setAccessibleText(null);
                    setContentDisplay(ContentDisplay.LEFT);
                    // Text colour comes from the theme (light on dark designs); only the size is set.
                    setStyle(ENTRY_STYLE);
                    
                    if (empty || item == null) {
                        return;
                    }
                    
                    if (item.isGroup() && item.isShared()) {
                        // A folder of the teamwork branch: no colour, no folder actions.
                        setText(item.getDisplayName());
                        javafx.scene.layout.HBox row = ConnectionRowGraphics.groupRow(item.getGroupPath(),
                            item.getDisplayName(), null, sharedCount(getTreeItem()), textFillProperty(),
                            item.getGroupPath() == null ? "teamwork" : "folder");
                        ConnectionRowGraphics.addConnector(row, getTreeItem(), getTreeView().getRoot(), INDENT,
                            paddingProperty(), textFillProperty());
                        setGraphic(row);
                        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                    } else if (item.isGroup()) {
                        // The name stays the cell's text for screen readers; the row draws it with the glyph.
                        setText(item.getDisplayName());
                        javafx.scene.layout.HBox row = ConnectionRowGraphics.groupRow(item.getGroupPath(),
                            item.getDisplayName(), inheritedGroupColor(item.getGroupPath()),
                            connectionCount(item.getGroupPath()), textFillProperty(), "folder");
                        ConnectionRowGraphics.addConnector(row, getTreeItem(), getTreeView().getRoot(), INDENT,
                            paddingProperty(), textFillProperty());
                        setGraphic(row);
                        setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                        showGroupColor(this, item.getGroupPath());
                        if (!selectionOnly) {
                            setContextMenu(createGroupContextMenu(item.getGroupPath()));
                        }
                    } else if (item.getConnection() != null) {
                        if (item.getConnection().isPlaceholder()) {
                            setText("└─ " + item.getDisplayName());
                            setStyle(PLACEHOLDER_STYLE);
                        } else {
                            ServerConnection connection = item.getConnection();
                            int openTabs = openTabsProbe != null ? openTabsProbe.applyAsInt(connection) : 0;
                            setText(item.getDisplayName());
                            javafx.scene.layout.HBox row = ConnectionRowGraphics.connectionRow(connection,
                                textFillProperty(), favoriteProbe != null && favoriteProbe.test(connection), openTabs,
                                item.isShared() || readOnlyConnections,
                                badgeProbe != null ? badgeProbe.apply(connection) : null);
                            ConnectionRowGraphics.addConnector(row, getTreeItem(), getTreeView().getRoot(), INDENT,
                                paddingProperty(), textFillProperty());
                            setGraphic(row);
                            setContentDisplay(ContentDisplay.GRAPHIC_ONLY);
                            setAccessibleText(item.getDisplayName() + ", " + ConnectionRowGraphics.address(connection)
                                + (connection.getTag() != null ? ", " + connection.getTag() : ""));
                            if (!selectionOnly) {
                                setContextMenu(createConnectionContextMenu());
                            }
                            markIfPolicyBlocked(this, item);
                        }
                    }
                }
            };
            
            // Double-click handler for connections
            cell.setOnMouseClicked(event -> {
                if (event.getButton() == MouseButton.PRIMARY && 
                    event.getClickCount() == 2 && 
                    !cell.isEmpty() &&
                    cell.getItem() != null &&
                    !cell.getItem().isGroup() &&
                    cell.getItem().getConnection() != null &&
                    !cell.getItem().getConnection().isPlaceholder()) {
                    
                    if (onDoubleClick != null) {
                        onDoubleClick.run();
                    }
                }
            });
            
            // Drag detected (only for non-placeholder connections)
            cell.setOnDragDetected(event -> {
                if (selectionOnly || readOnlyConnections) {
                    return;
                }
                if (cell.getItem() != null && cell.getItem().isShared()) {
                    return; // a teamwork connection never moves into another folder
                }
                if (!cell.isEmpty() && cell.getItem() != null && 
                    !cell.getItem().isGroup() && 
                    cell.getItem().getConnection() != null &&
                    !cell.getItem().getConnection().isPlaceholder()) {
                    
                    Dragboard db = cell.startDragAndDrop(TransferMode.MOVE);
                    ClipboardContent content = new ClipboardContent();
                    content.putString(cell.getItem().getConnection().getId());
                    db.setContent(content);
                    event.consume();
                }
            });
            
            // Drag over (only on groups)
            cell.setOnDragOver(event -> {
                if (selectionOnly || readOnlyConnections) {
                    return;
                }
                if (event.getGestureSource() != cell && 
                    event.getDragboard().hasString() &&
                    cell.getItem() != null && 
                    cell.getItem().isGroup() && !cell.getItem().isShared()) {
                    event.acceptTransferModes(TransferMode.MOVE);
                }
                event.consume();
            });
            
            // Drag dropped
            cell.setOnDragDropped(event -> {
                if (selectionOnly || readOnlyConnections) {
                    return;
                }
                Dragboard db = event.getDragboard();
                boolean success = false;
                
                if (db.hasString() && cell.getItem() != null && cell.getItem().isGroup() && !cell.getItem().isShared()) {
                    String connectionId = db.getString();
                    ServerConnection conn = connections.stream()
                            .filter(c -> c.getId().equals(connectionId))
                            .findFirst()
                            .orElse(null);
                    
                    if (conn != null) {
                        String oldGroup = conn.getGroup();
                        String newGroup = cell.getItem().getGroupPath().getPath();
                        
                        conn.setGroup(newGroup);
                        moveHistory.push(new MoveOperation(conn, oldGroup, newGroup));
                        
                        if (undoButton != null) {
                            undoButton.setDisable(false);
                        }
                        
                        if (currentSearchPredicate != null) {
                            filterTree(currentSearchPredicate);
                        } else {
                            refreshTree();
                        }
                        if (onConnectionsMoved != null) {
                            onConnectionsMoved.run();
                        }
                        success = true;
                    }
                }
                
                event.setDropCompleted(success);
                event.consume();
            });
            
            return cell;
        });
    }

    /**
     * Shows the tab color a group has of its own as an outlined dot after the folder's name, with a
     * tooltip that names it, which screen readers read as well; a group without one shows none.
     */
    private void showGroupColor(TreeCell<ConnectionTreeItem.ItemData> cell, GroupPath groupPath) {
        String color = groupColorProbe != null
            ? de.kortty.core.ConnectionColorSupport.normalizeHex(groupColorProbe.apply(groupPath))
            : null;
        if (color == null) {
            return;
        }
        String text = I18n.get("connManager.group.tabColor.swatch", I18n.get(TabColorPresentation.familyKey(
            de.kortty.core.ConnectionColorSupport.family(color))), color);
        javafx.scene.Node swatch = TabColorPresentation.swatch(color, text);
        if (cell.getGraphic() instanceof javafx.scene.layout.HBox row) {
            row.getChildren().add(swatch);
        } else {
            cell.setGraphic(swatch);
            cell.setContentDisplay(ContentDisplay.RIGHT);
            cell.setGraphicTextGap(8);
        }
        cell.setTooltip(new Tooltip(text));
        cell.setAccessibleText(cell.getText() + ", " + text);
    }
    
    /** The tab color the folder shows: its own, else that of the nearest folder above with one; null without. */
    private String inheritedGroupColor(GroupPath groupPath) {
        if (groupColorProbe == null || groupPath == null) {
            return null;
        }
        var inherited = de.kortty.core.ConnectionGroupColors.inherited(groupPath.getPath(),
            path -> groupColorProbe.apply(new GroupPath(path)));
        return inherited != null ? inherited.hex() : null;
    }

    /** How many connections a folder of the teamwork branch holds, subfolders included. */
    private static int sharedCount(TreeItem<ConnectionTreeItem.ItemData> item) {
        int count = 0;
        if (item != null) {
            for (TreeItem<ConnectionTreeItem.ItemData> child : item.getChildren()) {
                count += child.getValue().isGroup() ? sharedCount(child) : 1;
            }
        }
        return count;
    }

    /** How many connections (no folder placeholders) the folder and its subfolders hold. */
    private int connectionCount(GroupPath groupPath) {
        int count = 0;
        for (ServerConnection connection : connections) {
            if (!connection.isPlaceholder() && connection.getGroup() != null && !connection.getGroup().isBlank()) {
                GroupPath path = new GroupPath(connection.getGroup());
                if (path.equals(groupPath) || path.isChildOf(groupPath)) {
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Creates the context menu for a group, or null when it would have no entry. Every entry needs its
     * handler, and a read-only tree offers nothing that changes its folders (rename, subfolder, host-key
     * check, tab color, tags), so the Teamwork tab shows no folder menu at all.
     */
    private ContextMenu createGroupContextMenu(GroupPath groupPath) {
        MenuItem renameItem = new MenuItem(I18n.get("connManager.renameFolder"));
        renameItem.setOnAction(e -> {
            if (onRenameGroup != null) {
                onRenameGroup.accept(groupPath);
            }
        });
        
        MenuItem createSubGroupItem = new MenuItem(I18n.get("connManager.createSubfolder"));
        createSubGroupItem.setOnAction(e -> {
            if (onCreateGroup != null) {
                onCreateGroup.accept(groupPath);
            }
        });
        
        MenuItem exportGroupItem = new MenuItem(I18n.get("connManager.exportFolder"));
        exportGroupItem.setOnAction(e -> {
            if (onExportGroup != null) {
                onExportGroup.accept(groupPath);
            }
        });
        
        MenuItem deleteGroupItem = new MenuItem(I18n.get("connManager.deleteFolder"));
        deleteGroupItem.setOnAction(e -> {
            if (onDeleteGroup != null) {
                onDeleteGroup.accept(groupPath);
            }
        });
        
        CheckMenuItem disableHostKeyItem = new CheckMenuItem(I18n.get("connManager.group.disableHostKeyCheck"));
        disableHostKeyItem.setSelected(groupHostKeyCheckDisabled != null && groupHostKeyCheckDisabled.test(groupPath));
        disableHostKeyItem.setOnAction(e -> {
            if (onToggleGroupHostKeyCheck != null) {
                onToggleGroupHostKeyCheck.accept(groupPath, disableHostKeyItem.isSelected());
            }
        });

        MenuItem assignTagItem = new MenuItem(I18n.get("connManager.assignTag"));
        assignTagItem.setOnAction(e -> {
            if (onAssignTagToGroup != null) {
                onAssignTagToGroup.accept(groupPath);
            }
        });

        MenuItem removeTagItem = new MenuItem(I18n.get("connManager.removeTag"));
        removeTagItem.setOnAction(e -> {
            if (onRemoveTagFromGroup != null) {
                onRemoveTagFromGroup.accept(groupPath);
            }
        });

        boolean changeable = !readOnlyConnections;
        List<MenuItem> structure = new ArrayList<>();
        if (changeable && onRenameGroup != null) {
            structure.add(renameItem);
        }
        if (changeable && onCreateGroup != null) {
            structure.add(createSubGroupItem);
        }
        List<MenuItem> settings = new ArrayList<>();
        // A teamwork folder never relaxes host-key verification, whatever handler is set.
        if (changeable && onToggleGroupHostKeyCheck != null) {
            settings.add(disableHostKeyItem);
        }
        if (changeable && onEditGroupColor != null) {
            MenuItem tabColorItem = new MenuItem(I18n.get("connManager.group.tabColor"));
            tabColorItem.setOnAction(e -> onEditGroupColor.accept(groupPath));
            settings.add(tabColorItem);
        }
        if (changeable && onSetGroupIsolation != null) {
            settings.add(groupIsolationMenu(groupPath));
        }
        List<MenuItem> tags = new ArrayList<>();
        if (changeable && (onAssignTagToGroup != null || onRemoveTagFromGroup != null)) {
            tags.addAll(List.of(assignTagItem, removeTagItem));
        }
        List<MenuItem> folder = new ArrayList<>();
        if (onExportGroup != null) {
            folder.add(exportGroupItem);
        }
        if (onDeleteGroup != null) {
            folder.add(deleteGroupItem);
        }

        ContextMenu menu = new ContextMenu();
        for (List<MenuItem> section : List.of(structure, settings, tags, folder)) {
            if (section.isEmpty()) {
                continue;
            }
            if (!menu.getItems().isEmpty()) {
                menu.getItems().add(new SeparatorMenuItem());
            }
            menu.getItems().addAll(section);
        }
        return menu.getItems().isEmpty() ? null : menu;
    }

    /**
     * The folder's "Session Isolation" submenu: Use the Default, then None, Own Process and Sandbox; the
     * levels below the organization's minimum are greyed out.
     */
    private Menu groupIsolationMenu(GroupPath groupPath) {
        Menu menu = new Menu(I18n.get("connManager.group.isolation"));
        de.kortty.isolation.IsolationLevel current =
            groupIsolationProbe != null ? groupIsolationProbe.apply(groupPath) : null;
        de.kortty.isolation.IsolationLevel floor = de.kortty.policy.PolicyManager.effective().isolationFloor();
        ToggleGroup toggle = new ToggleGroup();
        RadioMenuItem inherit = new RadioMenuItem(I18n.get("connManager.group.isolation.default"));
        inherit.setToggleGroup(toggle);
        inherit.setSelected(current == null);
        inherit.setOnAction(e -> onSetGroupIsolation.accept(groupPath, null));
        menu.getItems().addAll(inherit, new SeparatorMenuItem());
        for (de.kortty.isolation.IsolationLevel level : de.kortty.isolation.IsolationLevel.values()) {
            RadioMenuItem item = new RadioMenuItem(IsolationConnectionSupport.levelName(level));
            item.setToggleGroup(toggle);
            item.setSelected(level == current);
            item.setDisable(!level.atLeast(floor));
            item.setOnAction(e -> onSetGroupIsolation.accept(groupPath, level));
            menu.getItems().add(item);
        }
        return menu;
    }

    /** Adds a "Session Isolation" submenu to every group's context menu that hands the choice to {@code handler}. */
    public void setOnSetGroupIsolation(java.util.function.BiConsumer<GroupPath, de.kortty.isolation.IsolationLevel> handler) {
        this.onSetGroupIsolation = handler;
    }

    /** Tells the submenu which level a group sets itself (null for none). */
    public void setGroupIsolationProbe(java.util.function.Function<GroupPath, de.kortty.isolation.IsolationLevel> probe) {
        this.groupIsolationProbe = probe;
    }

    public void setOnToggleGroupHostKeyCheck(java.util.function.BiConsumer<GroupPath, Boolean> handler) {
        this.onToggleGroupHostKeyCheck = handler;
    }

    public void setGroupHostKeyCheckDisabledProbe(java.util.function.Predicate<GroupPath> probe) {
        this.groupHostKeyCheckDisabled = probe;
    }

    /** Adds a "Tab Color..." entry to every group's context menu that hands the group to {@code handler}. */
    public void setOnEditGroupColor(Consumer<GroupPath> handler) {
        this.onEditGroupColor = handler;
    }

    /** Shows the tab color {@code probe} gives a group ({@code #RRGGBB}, or null) after the folder's name. */
    public void setGroupColorProbe(java.util.function.Function<GroupPath, String> probe) {
        this.groupColorProbe = probe;
    }

    /** Runs {@code callback} after a connection was dragged into another group or that move was undone. */
    public void setOnConnectionsMoved(Runnable callback) {
        this.onConnectionsMoved = callback;
    }
    
    /**
     * Creates context menu for connection items.
     */
    private ContextMenu createConnectionContextMenu() {
        ContextMenu menu = new ContextMenu();
        
        MenuItem editItem = new MenuItem(I18n.get("dialog.edit"));
        editItem.setOnAction(e -> {
            List<ServerConnection> selected = getSelectedConnections();
            if (selected.size() == 1 && onEditConnection != null) {
                onEditConnection.accept(selected.get(0));
            }
        });
        
        MenuItem exportItem = new MenuItem(I18n.get("connExport.export"));
        exportItem.setOnAction(e -> {
            List<ServerConnection> selected = getSelectedConnections();
            if (!selected.isEmpty() && onExportConnections != null) {
                onExportConnections.accept(selected);
            }
        });
        
        MenuItem deleteItem = new MenuItem(I18n.get("sftp.delete"));
        deleteItem.setOnAction(e -> {
            List<ServerConnection> selected = getSelectedConnections();
            if (!selected.isEmpty() && onDeleteConnections != null) {
                onDeleteConnections.accept(selected);
            }
        });

        MenuItem assignTagItem = new MenuItem(I18n.get("connManager.assignTag"));
        assignTagItem.setOnAction(e -> {
            List<ServerConnection> selected = getSelectedConnections();
            if (!selected.isEmpty() && onAssignTag != null) {
                onAssignTag.accept(selected);
            }
        });

        MenuItem removeTagItem = new MenuItem(I18n.get("connManager.removeTag"));
        removeTagItem.setOnAction(e -> {
            List<ServerConnection> selected = getSelectedConnections();
            if (!selected.isEmpty() && onRemoveTag != null) {
                onRemoveTag.accept(selected);
            }
        });

        MenuItem favoriteItem = new MenuItem(I18n.get("connectionManager.favorite.toggle"));
        favoriteItem.setOnAction(e -> {
            List<ServerConnection> selected = getSelectedConnections();
            if (!selected.isEmpty() && onToggleFavorite != null) {
                onToggleFavorite.accept(selected);
            }
        });

        // Enable/disable edit based on selection
        menu.setOnShowing(e -> {
            favoriteItem.setDisable(getSelectedConnections().isEmpty());
            // Teamwork connections shown beside the local ones are read-only: no edit, no tags.
            boolean anyShared = getSelectedConnections().stream().anyMatch(this::isShared);
            editItem.setVisible(!anyShared);
            assignTagItem.setVisible(!anyShared);
            removeTagItem.setVisible(!anyShared);
            List<ServerConnection> selected = getSelectedConnections();
            editItem.setDisable(selected.size() != 1);
            exportItem.setDisable(selected.isEmpty());
            deleteItem.setDisable(selected.isEmpty());
            assignTagItem.setDisable(selected.isEmpty());
            removeTagItem.setDisable(selected.stream().allMatch(conn -> conn.getTag() == null));
        });

        if (onEditConnection != null && !readOnlyConnections) {
            menu.getItems().add(editItem);
        }
        if (onToggleFavorite != null) {
            menu.getItems().add(favoriteItem);
        }
        if (osBadgeMenu != null && !readOnlyConnections) {
            Menu badges = new Menu(I18n.get("connectionManager.os.menu"));
            // Filled when the menu opens, for the selection of that moment.
            badges.getItems().add(new MenuItem());
            badges.setOnShowing(e -> {
                List<ServerConnection> selected = getSelectedConnections().stream()
                    .filter(conn -> !isShared(conn)).collect(Collectors.toList());
                badges.getItems().setAll(osBadgeMenu.apply(selected).getItems());
            });
            menu.getItems().add(badges);
        }
        menu.getItems().add(exportItem);
        if (onAssignTag != null || onRemoveTag != null) {
            menu.getItems().addAll(new SeparatorMenuItem(), assignTagItem, removeTagItem, new SeparatorMenuItem());
        }
        menu.getItems().add(deleteItem);
        return menu;
    }
    
    /**
     * Returns all selected connections (excluding placeholders).
     */
    public List<ServerConnection> getSelectedConnections() {
        return getSelectionModel().getSelectedItems().stream()
                .filter(Objects::nonNull)
                .map(TreeItem::getValue)
                .filter(data -> data != null && !data.isGroup() && data.getConnection() != null)
                .map(ConnectionTreeItem.ItemData::getConnection)
                .filter(conn -> !conn.isPlaceholder())
                .collect(Collectors.toList());
    }
    
    /**
     * Returns all selected groups.
     */
    public List<GroupPath> getSelectedGroups() {
        return getSelectionModel().getSelectedItems().stream()
                .filter(Objects::nonNull)
                .map(TreeItem::getValue)
                .filter(data -> data != null && data.isGroup() && !data.isShared())
                .map(ConnectionTreeItem.ItemData::getGroupPath)
                .collect(Collectors.toList());
    }
    
    /**
     * Returns true if there is a move operation to undo.
     */
    public boolean isUndoAvailable() {
        return !moveHistory.isEmpty();
    }
    
    /**
     * Undoes the last move operation.
     */
    public void undoLastMove() {
        if (!moveHistory.isEmpty()) {
            MoveOperation op = moveHistory.pop();
            op.connection.setGroup(op.oldGroup);
            
            if (currentSearchPredicate != null) {
                filterTree(currentSearchPredicate);
            } else {
                refreshTree();
            }
            if (onConnectionsMoved != null) {
                onConnectionsMoved.run();
            }
            
            if (undoButton != null) {
                undoButton.setDisable(moveHistory.isEmpty());
            }
        }
    }
    
    public void setUndoButton(Button undoButton) {
        this.undoButton = undoButton;
        if (undoButton != null) {
            undoButton.setDisable(moveHistory.isEmpty());
        }
    }
    
    public void setOnCreateGroup(Consumer<GroupPath> callback) {
        this.onCreateGroup = callback;
        updateEmptyAreaContextMenu();
    }
    
    public void setOnRenameGroup(Consumer<GroupPath> callback) {
        this.onRenameGroup = callback;
    }
    
    public void setOnDeleteGroup(Consumer<GroupPath> callback) {
        this.onDeleteGroup = callback;
    }
    
    public void setOnDoubleClick(Runnable callback) {
        this.onDoubleClick = callback;
    }
    
    public void setOnEditConnection(Consumer<ServerConnection> callback) {
        this.onEditConnection = callback;
    }
    
    public void setOnExportConnections(Consumer<List<ServerConnection>> callback) {
        this.onExportConnections = callback;
    }
    
    public void setOnDeleteConnections(Consumer<List<ServerConnection>> callback) {
        this.onDeleteConnections = callback;
    }
    
    public void setOnExportGroup(Consumer<GroupPath> callback) {
        this.onExportGroup = callback;
    }
    
    public void setOnAddConnection(Runnable callback) {
        this.onAddConnection = callback;
        updateEmptyAreaContextMenu();
    }

    public void setOnAssignTag(Consumer<List<ServerConnection>> callback) {
        this.onAssignTag = callback;
    }

    public void setOnRemoveTag(Consumer<List<ServerConnection>> callback) {
        this.onRemoveTag = callback;
    }

    public void setOnAssignTagToGroup(Consumer<GroupPath> callback) {
        this.onAssignTagToGroup = callback;
    }

    public void setOnRemoveTagFromGroup(Consumer<GroupPath> callback) {
        this.onRemoveTagFromGroup = callback;
    }

    /** Draws the OS badge {@code probe} gives a connection (null for none) on its tile. */
    public void setBadgeProbe(java.util.function.Function<ServerConnection, javafx.scene.Node> probe) {
        this.badgeProbe = probe;
    }

    /** Adds an "OS symbol" submenu whose entries {@code builder} makes for the selected local connections. */
    public void setOsBadgeMenu(java.util.function.Function<List<ServerConnection>, Menu> builder) {
        this.osBadgeMenu = builder;
    }

    /** Draws a green dot and the tab count for connections {@code probe} reports open tabs for. */
    public void setOpenTabsProbe(java.util.function.ToIntFunction<ServerConnection> probe) {
        this.openTabsProbe = probe;
    }

    /** Draws a star after the connections {@code probe} reports as favorites. */
    public void setFavoriteProbe(java.util.function.Predicate<ServerConnection> probe) {
        this.favoriteProbe = probe;
    }

    /** Adds a "Favorite" entry to the connection menu that hands the selected connections to {@code handler}. */
    public void setOnToggleFavorite(Consumer<List<ServerConnection>> handler) {
        this.onToggleFavorite = handler;
    }

    /** The filter currently applied with {@link #filterTree}, or null for the whole tree. */
    public Predicate<ServerConnection> getFilter() {
        return currentSearchPredicate;
    }

    /** Selects {@code connection}'s row (only that one) and scrolls to it; false when it is not shown. */
    public boolean selectConnection(ServerConnection connection) {
        TreeItem<ConnectionTreeItem.ItemData> item = findItem(getRoot(), connection);
        if (item == null) {
            return false;
        }
        getSelectionModel().clearSelection();
        getSelectionModel().select(item);
        int row = getRow(item);
        if (row >= 0) {
            scrollTo(Math.max(0, row - 3));
        }
        return true;
    }

    private static TreeItem<ConnectionTreeItem.ItemData> findItem(TreeItem<ConnectionTreeItem.ItemData> item,
                                                                  ServerConnection connection) {
        if (item == null || connection == null) {
            return null;
        }
        if (item.getValue() != null && item.getValue().getConnection() == connection) {
            return item;
        }
        for (TreeItem<ConnectionTreeItem.ItemData> child : item.getChildren()) {
            TreeItem<ConnectionTreeItem.ItemData> found = findItem(child, connection);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    /**
     * Moves {@code connection} into the folder {@code newGroup} as a drop onto that folder does, so
     * Undo takes it back; nothing happens in a read-only or selection-only tree or for the same folder.
     *
     * @return whether the connection moved
     */
    public boolean moveConnection(ServerConnection connection, String newGroup) {
        if (selectionOnly || readOnlyConnections || connection == null || connection.isPlaceholder()
                || !connections.contains(connection)) {
            return false;
        }
        String oldGroup = connection.getGroup();
        String normalizedOld = oldGroup != null ? oldGroup : "";
        String normalizedNew = newGroup != null ? newGroup : "";
        if (normalizedOld.equals(normalizedNew)) {
            return false;
        }
        connection.setGroup(newGroup);
        moveHistory.push(new MoveOperation(connection, oldGroup, newGroup));
        if (undoButton != null) {
            undoButton.setDisable(false);
        }
        refreshPreservingFilter();
        if (onConnectionsMoved != null) {
            onConnectionsMoved.run();
        }
        return true;
    }

    /**
     * Rebuilds the tree, keeping the currently active search filter applied (if any).
     */
    public void refreshPreservingFilter() {
        if (currentSearchPredicate != null) {
            filterTree(currentSearchPredicate);
        } else {
            refreshTree();
        }
    }
    
    /**
     * Represents a move operation for undo functionality.
     */
    private static class MoveOperation {
        final ServerConnection connection;
        final String oldGroup;
        final String newGroup;
        
        MoveOperation(ServerConnection connection, String oldGroup, String newGroup) {
            this.connection = connection;
            this.oldGroup = oldGroup;
            this.newGroup = newGroup;
        }
    }
}
