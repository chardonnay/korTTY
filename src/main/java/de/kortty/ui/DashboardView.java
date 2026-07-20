package de.kortty.ui;

import de.kortty.core.AgentDashboardStatus;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.SVGPath;
import javafx.util.Duration;

import java.util.List;
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

    /** Kind of a dashboard tree node; drives icon and typography. */
    public enum NodeType {
        MAIN_WINDOW,
        ENVIRONMENT,
        GROUP,
        CONNECTION
    }

    /** Connection state of a leaf row, shown as a colored status dot. */
    private enum ConnState {
        CONNECTED,
        ERROR,
        ENDED
    }

    // 16x16 icon shapes (even-odd fill): monitor, stacked layers, folder, terminal.
    private static final String ICON_MAIN_WINDOW =
            "M1,2 h14 v9 h-14 z M3,4 h10 v5 h-10 z M6,12.5 h4 v1.5 h-4 z";
    private static final String ICON_ENVIRONMENT =
            "M8,1.5 L14,5 8,8.5 2,5 z M2,8.5 L8,12 14,8.5 14,10 8,13.5 2,10 z";
    private static final String ICON_GROUP =
            "M1,3 h5 l1.5,2 H15 v8 H1 z";
    private static final String ICON_CONNECTION =
            "M1,2 h14 v11 h-14 z M2.5,3.5 h11 v8 h-11 z M4,5.5 l2.5,1.75 -2.5,1.75 z M8,9.5 h4 v1 h-4 z";

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
        HBox.setHgrow(refreshButton, Priority.NEVER);

        treeView = new TreeView<>();
        treeView.getStyleClass().add("dashboard-tree");
        treeView.setShowRoot(false);

        treeView.setCellFactory(tv -> {
            TreeCell<DashboardItem> cell = new DashboardCell();

            // Double-click to focus
            cell.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && cell.getItem() != null && cell.getItem().getTerminalTab() != null) {
                    actionHandler.accept(cell.getItem().getTerminalTab(), DashboardAction.FOCUS);
                }
            });

            return cell;
        });

        VBox.setVgrow(treeView, Priority.ALWAYS);

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

    /**
     * Tree cell rendering one dashboard row as [type icon] [status dot] [name]
     * [protocol badge] [count] [agent badge]. All sub-nodes are created once per
     * cell and only mutated in updateItem(), so the 1s badge-refresh tick stays cheap.
     */
    private class DashboardCell extends TreeCell<DashboardItem> {
        // The item this cell's context menu was built for; avoids rebuilding it on every 1s
        // badge-refresh tick (treeView.refresh() re-runs updateItem on the same item).
        private DashboardItem builtMenuForItem;

        private final SVGPath icon = new SVGPath();
        private final StackPane iconPane = new StackPane(icon);
        private final Circle statusDot = new Circle(4);
        private final Label nameLabel = new Label();
        private final Label protocolBadge = new Label();
        private final Label countLabel = new Label();
        private final Label agentBadge = new Label();
        private final HBox rowBox = new HBox(6);
        private final Tooltip rowTooltip = new Tooltip();

        DashboardCell() {
            icon.setFillRule(FillRule.EVEN_ODD);
            icon.getStyleClass().add("dashboard-node-icon");
            iconPane.setMinSize(16, 16);
            iconPane.setPrefSize(16, 16);
            iconPane.setMaxSize(16, 16);
            statusDot.getStyleClass().add("dashboard-status-dot");
            nameLabel.getStyleClass().add("dashboard-node-name");
            protocolBadge.getStyleClass().add("dashboard-protocol-badge");
            countLabel.getStyleClass().add("dashboard-node-count");
            agentBadge.getStyleClass().add("dashboard-agent-badge");
            rowBox.setAlignment(Pos.CENTER_LEFT);
            rowBox.getStyleClass().add("dashboard-row");
        }

        @Override
        protected void updateItem(DashboardItem item, boolean empty) {
            super.updateItem(item, empty);
            if (empty || item == null) {
                setText(null);
                setGraphic(null);
                setContextMenu(null);
                setTooltip(null);
                rowBox.getStyleClass().remove("dashboard-node-header");
                builtMenuForItem = null;
                return;
            }
            setText(null);

            boolean isConnection = item.getType() == NodeType.CONNECTION;
            icon.setContent(iconPathFor(item.getType()));

            if (isConnection) {
                TerminalTab tab = item.getTerminalTab();
                ConnState state = stateOf(tab);
                statusDot.getStyleClass().setAll("dashboard-status-dot",
                        "dashboard-status-dot-" + state.name().toLowerCase());
                nameLabel.setText(item.getDisplayName());
                protocolBadge.setText(protocolLabelFor(tab));
                protocolBadge.setVisible(!protocolBadge.getText().isEmpty());
                protocolBadge.setManaged(protocolBadge.isVisible());
                String badge = agentBadgeFor(tab);
                agentBadge.setText(badge);
                agentBadge.setVisible(!badge.isEmpty());
                agentBadge.setManaged(agentBadge.isVisible());
                rowBox.getStyleClass().remove("dashboard-node-header");
                rowBox.getChildren().setAll(iconPane, statusDot, nameLabel, protocolBadge, agentBadge);
                rowTooltip.setText(tooltipTextFor(item, state));
                setTooltip(rowTooltip);
            } else {
                nameLabel.setText(item.getDisplayName());
                countLabel.setText(item.getTotalCount() >= 0
                        ? I18n.get("dashboard.count", item.getActiveCount(), item.getTotalCount())
                        : "");
                countLabel.setVisible(!countLabel.getText().isEmpty());
                countLabel.setManaged(countLabel.isVisible());
                if (!rowBox.getStyleClass().contains("dashboard-node-header")) {
                    rowBox.getStyleClass().add("dashboard-node-header");
                }
                rowBox.getChildren().setAll(iconPane, nameLabel, countLabel);
                setTooltip(null);
            }
            setGraphic(rowBox);

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

    private static String iconPathFor(NodeType type) {
        return switch (type) {
            case MAIN_WINDOW -> ICON_MAIN_WINDOW;
            case ENVIRONMENT -> ICON_ENVIRONMENT;
            case GROUP -> ICON_GROUP;
            case CONNECTION -> ICON_CONNECTION;
        };
    }

    /** Short transport label shown next to a connection row (ssh / mosh / local). */
    private static String protocolLabelFor(TerminalTab tab) {
        if (tab == null || tab.getConnection() == null) {
            return "";
        }
        ConnectionProtocol protocol = tab.getConnection().getProtocol();
        return switch (protocol) {
            case SSH_TCP -> "ssh";
            case MOSH, MOSH_CLIENT -> "mosh";
            case LOCAL_SHELL -> "local";
        };
    }

    /** Effective connection state: green = connected, red = dropped/interrupted, dim = ended cleanly. */
    private static ConnState stateOf(TerminalTab tab) {
        if (tab == null) {
            return ConnState.ENDED;
        }
        if (tab.isUnexpectedlyDisconnected()) {
            return ConnState.ERROR;
        }
        return tab.isConnected() ? ConnState.CONNECTED : ConnState.ENDED;
    }

    private static String tooltipTextFor(DashboardItem item, ConnState state) {
        StringBuilder sb = new StringBuilder(item.getDisplayName());
        TerminalTab tab = item.getTerminalTab();
        ServerConnection conn = tab != null ? tab.getConnection() : null;
        if (conn != null && conn.getHost() != null && !conn.getHost().isEmpty()) {
            sb.append('\n');
            if (conn.getUsername() != null && !conn.getUsername().isEmpty()) {
                sb.append(conn.getUsername()).append('@');
            }
            sb.append(conn.getHost());
        }
        String statusKey = switch (state) {
            case CONNECTED -> "dashboard.status.active";
            case ERROR -> "dashboard.status.disconnected";
            case ENDED -> "dashboard.status.ended";
        };
        sb.append('\n').append(I18n.get(statusKey));
        return sb.toString();
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
        TreeItem<DashboardItem> root = new TreeItem<>(
                DashboardItem.container(NodeType.MAIN_WINDOW, I18n.get("dashboard.root"), -1, -1));

        // Count active connections
        int totalTabs = 0;
        int activeTabs = 0;

        // Group tabs by group name
        java.util.Map<String, java.util.List<TerminalTab>> groups = new java.util.HashMap<>();
        java.util.List<TerminalTab> ungroupedTabs = new java.util.ArrayList<>();

        // First pass: collect all tabs
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                totalTabs++;
                if (stateOf(terminalTab) == ConnState.CONNECTED) {
                    activeTabs++;
                }

                String group = terminalTab.getGroup();
                if (group != null && !group.trim().isEmpty()) {
                    groups.computeIfAbsent(group, k -> new java.util.ArrayList<>()).add(terminalTab);
                } else {
                    ungroupedTabs.add(terminalTab);
                }
            }
        }

        TreeItem<DashboardItem> windowItem = new TreeItem<>(
                DashboardItem.container(NodeType.MAIN_WINDOW, I18n.get("dashboard.mainWindowTitle"), activeTabs, totalTabs));
        windowItem.setExpanded(true);

        // Add ungrouped tabs first
        for (TerminalTab terminalTab : ungroupedTabs) {
            windowItem.getChildren().add(new TreeItem<>(
                    DashboardItem.connection(getServerDisplayName(terminalTab), terminalTab)));
        }

        // Add grouped tabs, sorted by group name
        List<String> sortedGroups = new java.util.ArrayList<>(groups.keySet());
        sortedGroups.sort(String::compareToIgnoreCase);

        for (String groupName : sortedGroups) {
            java.util.List<TerminalTab> groupTabs = groups.get(groupName);

            // Count active tabs in group
            int groupActive = 0;
            for (TerminalTab tab : groupTabs) {
                if (stateOf(tab) == ConnState.CONNECTED) {
                    groupActive++;
                }
            }

            TreeItem<DashboardItem> groupItem = new TreeItem<>(
                    DashboardItem.container(NodeType.GROUP, groupName, groupActive, groupTabs.size()));
            groupItem.setExpanded(true);

            // Add tabs in group
            for (TerminalTab terminalTab : groupTabs) {
                groupItem.getChildren().add(new TreeItem<>(
                        DashboardItem.connection(getServerDisplayName(terminalTab), terminalTab)));
            }

            windowItem.getChildren().add(groupItem);
        }

        if (totalTabs > 0) {
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
        private final NodeType type;
        private final String displayName;
        private final TerminalTab terminalTab;
        /** Connected/total children of a container node; -1 when no count is shown. */
        private final int activeCount;
        private final int totalCount;

        private DashboardItem(NodeType type, String displayName, TerminalTab terminalTab,
                              int activeCount, int totalCount) {
            this.type = type;
            this.displayName = displayName;
            this.terminalTab = terminalTab;
            this.activeCount = activeCount;
            this.totalCount = totalCount;
        }

        static DashboardItem container(NodeType type, String displayName, int activeCount, int totalCount) {
            return new DashboardItem(type, displayName, null, activeCount, totalCount);
        }

        static DashboardItem connection(String displayName, TerminalTab terminalTab) {
            return new DashboardItem(NodeType.CONNECTION, displayName, terminalTab, -1, -1);
        }

        public NodeType getType() {
            return type;
        }

        public String getDisplayName() {
            return displayName;
        }

        public boolean isConnected() {
            return terminalTab != null && terminalTab.isConnected();
        }

        public TerminalTab getTerminalTab() {
            return terminalTab;
        }

        public int getActiveCount() {
            return activeCount;
        }

        public int getTotalCount() {
            return totalCount;
        }
    }
}
