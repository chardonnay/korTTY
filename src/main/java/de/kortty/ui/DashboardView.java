package de.kortty.ui;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.VBox;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Dashboard view showing a tree of all active terminal tabs with their connection status.
 * Shows server name or IP address, and allows reconnect/close via context menu.
 */
public class DashboardView extends VBox {
    
    private final TabPane tabPane;
    private final BiConsumer<TerminalTab, DashboardAction> actionHandler;
    private final TreeView<DashboardItem> treeView;
    
    public enum DashboardAction {
        RECONNECT,
        CLOSE,
        FOCUS
    }
    
    public DashboardView(TabPane tabPane, BiConsumer<TerminalTab, DashboardAction> actionHandler) {
        this.tabPane = tabPane;
        this.actionHandler = actionHandler;
        
        setPadding(new Insets(5));
        setSpacing(5);
        setStyle("-fx-background-color: #2d2d2d;");
        
        Label titleLabel = new Label("Dashboard");
        titleLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #cccccc;");
        
        treeView = new TreeView<>();
        treeView.setShowRoot(false);
        treeView.setStyle("-fx-background-color: #2d2d2d;");
        
        // Custom cell factory with context menu
        treeView.setCellFactory(tv -> {
            TreeCell<DashboardItem> cell = new TreeCell<>() {
                @Override
                protected void updateItem(DashboardItem item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                        setStyle("-fx-background-color: transparent;");
                        setContextMenu(null);
                    } else {
                        String statusIcon = item.isConnected() ? "●" : "○";
                        String statusText = item.isConnected() ? "Aktiv" : "Beendet";
                        setText(statusIcon + " " + item.getDisplayName() + " (" + statusText + ")");
                        
                        // Color: green for active, red for disconnected
                        String color = item.isConnected() ? "#00ff00" : "#ff6666";
                        setStyle("-fx-text-fill: " + color + "; -fx-background-color: transparent;");
                        
                        // Context menu for terminal tabs only (not for window nodes)
                        if (item.getTerminalTab() != null) {
                            ContextMenu contextMenu = new ContextMenu();
                            
                            if (item.isConnected()) {
                                MenuItem closeItem = new MenuItem("Schließen");
                                closeItem.setOnAction(e -> {
                                    actionHandler.accept(item.getTerminalTab(), DashboardAction.CLOSE);
                                });
                                contextMenu.getItems().add(closeItem);
                            } else {
                                MenuItem reconnectItem = new MenuItem("Wiederverbinden");
                                reconnectItem.setOnAction(e -> {
                                    actionHandler.accept(item.getTerminalTab(), DashboardAction.RECONNECT);
                                });
                                contextMenu.getItems().add(reconnectItem);
                                
                                SeparatorMenuItem separator = new SeparatorMenuItem();
                                MenuItem closeItem = new MenuItem("Schließen");
                                closeItem.setOnAction(e -> {
                                    actionHandler.accept(item.getTerminalTab(), DashboardAction.CLOSE);
                                });
                                contextMenu.getItems().addAll(separator, closeItem);
                            }
                            
                            setContextMenu(contextMenu);
                        } else {
                            setContextMenu(null);
                        }
                    }
                }
            };
            
            // Double-click to focus
            cell.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && cell.getItem() != null && cell.getItem().getTerminalTab() != null) {
                    actionHandler.accept(cell.getItem().getTerminalTab(), DashboardAction.FOCUS);
                }
            });
            
            return cell;
        });
        
        VBox.setVgrow(treeView, javafx.scene.layout.Priority.ALWAYS);
        
        getChildren().addAll(titleLabel, treeView);
        
        refresh();
    }
    
    /**
     * Refreshes the dashboard tree with current tabs, organized by groups.
     */
    public void refresh() {
        TreeItem<DashboardItem> root = new TreeItem<>(new DashboardItem("Projekt", null, true, null));
        
        // Count active connections
        int totalTabs = 0;
        int activeTabs = 0;
        
        // Create window item
        TreeItem<DashboardItem> windowItem = new TreeItem<>(
                new DashboardItem("Hauptfenster", null, true, null)
        );
        windowItem.setExpanded(true);
        
        // Group tabs by group name
        java.util.Map<String, java.util.List<TerminalTab>> groups = new java.util.HashMap<>();
        java.util.List<TerminalTab> ungroupedTabs = new java.util.ArrayList<>();
        
        // First pass: collect all tabs
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                totalTabs++;
                boolean connected = terminalTab.isConnected();
                if (connected) activeTabs++;
                
                String group = terminalTab.getGroup();
                if (group != null && !group.trim().isEmpty()) {
                    groups.computeIfAbsent(group, k -> new java.util.ArrayList<>()).add(terminalTab);
                } else {
                    ungroupedTabs.add(terminalTab);
                }
            }
        }
        
        // Add ungrouped tabs first
        for (TerminalTab terminalTab : ungroupedTabs) {
            String displayName = getServerDisplayName(terminalTab);
            TreeItem<DashboardItem> tabItem = new TreeItem<>(
                    new DashboardItem(displayName, null, terminalTab.isConnected(), terminalTab)
            );
            windowItem.getChildren().add(tabItem);
        }
        
        // Add grouped tabs, sorted by group name
        List<String> sortedGroups = new java.util.ArrayList<>(groups.keySet());
        sortedGroups.sort(String::compareToIgnoreCase);
        
        for (String groupName : sortedGroups) {
            java.util.List<TerminalTab> groupTabs = groups.get(groupName);
            
            // Count active tabs in group
            int groupActive = 0;
            for (TerminalTab tab : groupTabs) {
                if (tab.isConnected()) {
                    groupActive++;
                }
            }
            
            // Create group item
            TreeItem<DashboardItem> groupItem = new TreeItem<>(
                    new DashboardItem(
                            "[" + groupName + "] (" + groupActive + "/" + groupTabs.size() + " aktiv)",
                            null,
                            true,
                            null
                    )
            );
            groupItem.setExpanded(true);
            
            // Add tabs in group
            for (TerminalTab terminalTab : groupTabs) {
                String displayName = getServerDisplayName(terminalTab);
                TreeItem<DashboardItem> tabItem = new TreeItem<>(
                        new DashboardItem(displayName, null, terminalTab.isConnected(), terminalTab)
                );
                groupItem.getChildren().add(tabItem);
            }
            
            windowItem.getChildren().add(groupItem);
        }
        
        // Update window title with counts
        if (totalTabs > 0) {
            windowItem.setValue(new DashboardItem(
                    "Hauptfenster (" + activeTabs + "/" + totalTabs + " aktiv)",
                    null,
                    true,
                    null
            ));
            root.getChildren().add(windowItem);
        }
        
        root.setExpanded(true);
        treeView.setRoot(root);
    }
    
    /**
     * Gets the display name for a terminal tab (server name or IP).
     */
    private String getServerDisplayName(TerminalTab terminalTab) {
        if (terminalTab.getConnection() != null) {
            String name = terminalTab.getConnection().getName();
            // If name is empty or null, use host (IP or hostname)
            if (name == null || name.trim().isEmpty()) {
                return terminalTab.getConnection().getHost();
            }
            return name;
        }
        return "Unbekannt";
    }
    
    /**
     * Dashboard tree item.
     */
    private static class DashboardItem {
        private final String displayName;
        private final String sessionId;
        private final boolean connected;
        private final TerminalTab terminalTab;
        
        public DashboardItem(String displayName, String sessionId, boolean connected, TerminalTab terminalTab) {
            this.displayName = displayName;
            this.sessionId = sessionId;
            this.connected = connected;
            this.terminalTab = terminalTab;
        }
        
        public String getDisplayName() {
            return displayName;
        }
        
        public String getSessionId() {
            return sessionId;
        }
        
        public boolean isConnected() {
            return connected;
        }
        
        public TerminalTab getTerminalTab() {
            return terminalTab;
        }
    }
}
