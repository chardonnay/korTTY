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
    
    /**
     * Data class holding information about a tree item.
     */
    public static class ItemData {
        private final boolean isGroup;
        private final String displayName;
        private final GroupPath groupPath;
        private final ServerConnection connection;
        
        public ItemData(boolean isGroup, String displayName, GroupPath groupPath, ServerConnection connection) {
            this.isGroup = isGroup;
            this.displayName = displayName;
            this.groupPath = groupPath;
            this.connection = connection;
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
