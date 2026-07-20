package de.kortty.ui;

import de.kortty.core.AgentDashboardStatus;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

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
    private final Button refreshButton;
    // Lightweight 1s tick that re-renders cells so AI-agent badges stay current while the dashboard shows.
    private Timeline agentStatusTimer;
    
    public enum DashboardAction {
        RECONNECT,
        CLOSE,
        FOCUS,
        SFTP_MANAGER,
        DUPLICATE
    }
    
    public DashboardView(TabPane tabPane, BiConsumer<TerminalTab, DashboardAction> actionHandler) {
        this.tabPane = tabPane;
        this.actionHandler = actionHandler;
        
        getStyleClass().add("dashboard-view");

        HBox titleBox = new HBox(10);
        titleBox.getStyleClass().add("dashboard-title");

        refreshButton = new Button("⟳");
        refreshButton.getStyleClass().add("dashboard-refresh-button");
        refreshButton.setTooltip(new Tooltip(I18n.get("dashboard.refresh")));
        refreshButton.setOnAction(e -> refresh());
        
        titleBox.getChildren().add(refreshButton);
        HBox.setHgrow(refreshButton, javafx.scene.layout.Priority.NEVER);
        
        treeView = new TreeView<>();
        treeView.getStyleClass().add("dashboard-tree");
        treeView.setShowRoot(false);
        
        // Custom cell factory with context menu
        treeView.setCellFactory(tv -> {
            TreeCell<DashboardItem> cell = new TreeCell<>() {
                // The item this cell's context menu was built for; avoids rebuilding it on every 1s
                // badge-refresh tick (treeView.refresh() re-runs updateItem on the same item).
                private DashboardItem builtMenuForItem;

                @Override
                protected void updateItem(DashboardItem item, boolean empty) {
                    super.updateItem(item, empty);
                    if (empty || item == null) {
                        setText(null);
                        setGraphic(null);
                        setContextMenu(null);
                        builtMenuForItem = null;
                    } else {
                        String statusIcon = item.isConnected() ? "●" : "○";
                        String statusText = item.isConnected() ? I18n.get("dashboard.status.active") : I18n.get("dashboard.status.ended");
                        String text = statusIcon + " " + item.getDisplayName() + " (" + statusText + ")";
                        // Prefix an AI-agent status badge (✋/⚡/⏸/✓) when this terminal has agent runs.
                        String agentBadge = agentBadgeFor(item.getTerminalTab());
                        if (!agentBadge.isEmpty()) {
                            text = agentBadge + " " + text;
                        }
                        setText(text);

                        // Context menu for terminal tabs only (not for window nodes). Rebuilt only when
                        // the row's item changes, so the 1s badge-refresh tick stays cheap.
                        if (item != builtMenuForItem) {
                            if (item.getTerminalTab() != null) {
                                ContextMenu contextMenu = new ContextMenu();

                                MenuItem duplicateItem = new MenuItem(I18n.get("dashboard.duplicate"));
                                duplicateItem.setOnAction(e -> {
                                    actionHandler.accept(item.getTerminalTab(), DashboardAction.DUPLICATE);
                                });
                                contextMenu.getItems().add(duplicateItem);

                                MenuItem reconnectItem = new MenuItem(I18n.get("dashboard.reconnect"));
                                reconnectItem.setOnAction(e -> {
                                    actionHandler.accept(item.getTerminalTab(), DashboardAction.RECONNECT);
                                });
                                contextMenu.getItems().add(reconnectItem);

                                contextMenu.getItems().add(new SeparatorMenuItem());

                                if (item.isConnected()) {
                                    MenuItem sftpItem = new MenuItem(I18n.get("menu.connections.sftpClient"));
                                    sftpItem.setOnAction(e -> {
                                        actionHandler.accept(item.getTerminalTab(), DashboardAction.SFTP_MANAGER);
                                    });
                                    contextMenu.getItems().add(sftpItem);
                                    contextMenu.getItems().add(new SeparatorMenuItem());
                                }

                                MenuItem closeItem = new MenuItem(I18n.get("dialog.close"));
                                closeItem.setOnAction(e -> {
                                    actionHandler.accept(item.getTerminalTab(), DashboardAction.CLOSE);
                                });
                                contextMenu.getItems().add(closeItem);

                                setContextMenu(contextMenu);
                            } else {
                                setContextMenu(null);
                            }
                            builtMenuForItem = item;
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
        
        getChildren().addAll(titleBox, treeView);

        // Run the badge refresh tick only while the dashboard is actually shown (in a scene).
        agentStatusTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> treeView.refresh()));
        agentStatusTimer.setCycleCount(Animation.INDEFINITE);
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null) {
                agentStatusTimer.playFromStart();
            } else {
                agentStatusTimer.stop();
            }
        });

        refresh();
    }

    /** AI-agent status badge (✋/⚡/⏸/✓ or "") aggregated across a terminal tab's widgets. */
    private String agentBadgeFor(TerminalTab tab) {
        if (tab == null || tab.getTerminalView() == null) {
            return "";
        }
        try {
            return AgentDashboardStatus.icon(tab.getTerminalView().aggregateTerminalAgentRunCounts());
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Applies theme background and foreground colors to the dashboard and its controls.
     * Called from MainWindow when global theme settings change (or when dashboard is first shown).
     */
    public void applyTheme(String bgColor, String fgColor) {
        if (AppDesignStyleSupport.isCustomAppDesignActive()) {
            // Per-design stylesheets are authoritative; drop any inline overrides.
            setStyle(null);
            return;
        }
        // Override the panel's looked-up colors; all descendants (cells, buttons,
        // separators) resolve them via CSS, so no per-cell styling is needed.
        StringBuilder style = new StringBuilder();
        if (bgColor != null && !bgColor.isEmpty()) {
            style.append("-kortty-dash-bg: ").append(bgColor).append(";");
        }
        if (fgColor != null && !fgColor.isEmpty()) {
            style.append("-kortty-dash-fg: ").append(fgColor).append(";");
        }
        setStyle(style.length() == 0 ? null : style.toString());
    }
    
    /**
     * Refreshes the dashboard tree with current tabs, organized by groups.
     */
    public void refresh() {
        TreeItem<DashboardItem> root = new TreeItem<>(new DashboardItem(I18n.get("dashboard.root"), null, true, null));
        
        // Count active connections
        int totalTabs = 0;
        int activeTabs = 0;
        
        // Create window item
        TreeItem<DashboardItem> windowItem = new TreeItem<>(
                new DashboardItem(I18n.get("dashboard.mainWindowTitle"), null, true, null)
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
                            "[" + groupName + "] (" + groupActive + "/" + groupTabs.size() + " " + I18n.get("dashboard.active") + ")",
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
                    I18n.get("dashboard.mainWindow", activeTabs, totalTabs),
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
        return I18n.get("dashboard.unknown");
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
