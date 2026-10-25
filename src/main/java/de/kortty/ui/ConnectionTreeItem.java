package de.kortty.ui;

import de.kortty.model.GroupPath;
import de.kortty.model.ServerConnection;
import javafx.scene.control.TreeItem;

/**
 * Helper class for TreeView items in the ConnectionManagerTreeView.
 * Wraps either a group or a connection.
 */
public class ConnectionTreeItem extends TreeItem<ConnectionTreeItem.ItemData> {
    
    public ConnectionTreeItem(ItemData data) {
        super(data);
    }
    
    public static ConnectionTreeItem forGroup(GroupPath groupPath) {
        String displayName = groupPath.isRoot() ? "/" : groupPath.getName();
        return new ConnectionTreeItem(new ItemData(true, displayName, groupPath, null));
    }
    
    public static ConnectionTreeItem forConnection(ServerConnection connection) {
        return new ConnectionTreeItem(new ItemData(false, connection.getName(), null, connection));
    }

    /** A folder of shared (teamwork) connections shown beside the local ones: no folder actions apply to it. */
    public static ConnectionTreeItem forSharedGroup(GroupPath groupPath, String displayName) {
        return new ConnectionTreeItem(new ItemData(true, displayName, groupPath, null, true));
    }

    /** A shared (teamwork) connection shown beside the local ones: it is never edited, moved or tagged here. */
    public static ConnectionTreeItem forSharedConnection(ServerConnection connection) {
        return new ConnectionTreeItem(new ItemData(false, connection.getName(), null, connection, true));
    }
    
    /**
     * Data class holding information about a tree item.
     */
    public static class ItemData {
        private final boolean isGroup;
        private final String displayName;
        private final GroupPath groupPath;
        private final ServerConnection connection;
        /** Part of the shared (teamwork) branch of a tree that otherwise shows local connections. */
        private final boolean shared;
        
        public ItemData(boolean isGroup, String displayName, GroupPath groupPath, ServerConnection connection) {
            this(isGroup, displayName, groupPath, connection, false);
        }

        public ItemData(boolean isGroup, String displayName, GroupPath groupPath, ServerConnection connection,
                        boolean shared) {
            this.isGroup = isGroup;
            this.displayName = displayName;
            this.groupPath = groupPath;
            this.connection = connection;
            this.shared = shared;
        }

        public boolean isShared() {
            return shared;
        }
        
        public boolean isGroup() {
            return isGroup;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public GroupPath getGroupPath() {
            return groupPath;
        }
        
        public ServerConnection getConnection() {
            return connection;
        }
        
        @Override
        public String toString() {
            return displayName;
        }
    }
}
