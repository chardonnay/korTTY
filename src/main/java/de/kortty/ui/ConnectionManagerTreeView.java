package de.kortty.ui;

import de.kortty.model.GroupPath;
import de.kortty.model.ServerConnection;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.input.*;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;

import java.util.*;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Custom TreeView for displaying and managing connection groups hierarchically.
 */
public class ConnectionManagerTreeView extends TreeView<ConnectionTreeItem.ItemData> {
    
    private final List<ServerConnection> connections;
    private Button undoButton;
    private final Deque<MoveOperation> moveHistory = new ArrayDeque<>();
    private TreeItem<ConnectionTreeItem.ItemData> originalRoot;
    private Predicate<ServerConnection> currentSearchPredicate = null;
    
    // Callbacks
    private Consumer<GroupPath> onCreateGroup;
    private Consumer<GroupPath> onRenameGroup;
    private Consumer<GroupPath> onDeleteGroup;
    private Runnable onDoubleClick;
    private Consumer<ServerConnection> onEditConnection;
    private Consumer<List<ServerConnection>> onExportConnections;
    private Consumer<List<ServerConnection>> onDeleteConnections;
    private Consumer<GroupPath> onExportGroup;
    private Runnable onAddConnection;
    
    public ConnectionManagerTreeView(List<ServerConnection> connections) {
        this.connections = connections;
        
        getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        setShowRoot(false);
        
        refreshTree();
        setupDragAndDrop();
        setCellFactory();
        setContextMenu(createEmptyAreaContextMenu());
    }
    
    /**
     * Context menu shown when right-clicking on empty area (no item under cursor).
     * Allows creating a folder or a new connection.
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
        menu.getItems().addAll(createFolderItem, createConnectionItem);
        return menu;
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
                // Add placeholder with special styling indicator
                TreeItem<ConnectionTreeItem.ItemData> placeholderItem = ConnectionTreeItem.forConnection(conn);
                
                String group = conn.getGroup();
                if (group == null || group.trim().isEmpty()) {
                    root.getChildren().add(placeholderItem);
                } else {
                    TreeItem<ConnectionTreeItem.ItemData> groupItem = groupItems.get(group);
                    if (groupItem != null) {
                        groupItem.getChildren().add(placeholderItem);
                    }
                }
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
        
        root.setExpanded(true);
        expandAll(root);
        
        this.originalRoot = root;
        setRoot(root);
    }
    
    /**
     * Filters the tree based on a search predicate.
     * Only connections matching the predicate (and their parent groups) will be shown.
     */
    public void filterTree(Predicate<ServerConnection> searchPredicate) {
        this.currentSearchPredicate = searchPredicate;
        
        if (searchPredicate == null) {
            // Restore original tree
            setRoot(originalRoot);
            expandAll(originalRoot);
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
                        if (item.isGroup()) {
                            setText("📁 " + item.getDisplayName());
                        } else if (item.getConnection() != null && item.getConnection().isPlaceholder()) {
                            setText("└─ " + item.getDisplayName());
                            setTextFill(Color.GRAY);
                            setFont(Font.font(getFont().getFamily(), 10));
                        } else {
                            setText("🔌 " + item.getDisplayName());
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
                    setTextFill(Color.BLACK);
                    setFont(Font.font(getFont().getFamily(), 12));
                    
                    if (empty || item == null) {
                        return;
                    }
                    
                    if (item.isGroup()) {
                        setText("📁 " + item.getDisplayName());
                        setContextMenu(createGroupContextMenu(item.getGroupPath()));
                    } else if (item.getConnection() != null) {
                        if (item.getConnection().isPlaceholder()) {
                            setText("└─ " + item.getDisplayName());
                            setTextFill(Color.GRAY);
                            setFont(Font.font(getFont().getFamily(), 10));
                        } else {
                            setText("🔌 " + item.getDisplayName());
                            setContextMenu(createConnectionContextMenu());
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
     * Creates context menu for group items.
     */
    private ContextMenu createGroupContextMenu(GroupPath groupPath) {
        ContextMenu menu = new ContextMenu();
        
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
        
        menu.getItems().addAll(renameItem, createSubGroupItem, new SeparatorMenuItem(), 
                               exportGroupItem, deleteGroupItem);
        return menu;
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
        
        // Enable/disable edit based on selection
        menu.setOnShowing(e -> {
            List<ServerConnection> selected = getSelectedConnections();
            editItem.setDisable(selected.size() != 1);
            exportItem.setDisable(selected.isEmpty());
            deleteItem.setDisable(selected.isEmpty());
        });
        
        menu.getItems().addAll(editItem, exportItem, deleteItem);
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
                .filter(data -> data != null && data.isGroup())
                .map(ConnectionTreeItem.ItemData::getGroupPath)
                .collect(Collectors.toList());
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
