package de.kortty.ui;

import de.kortty.core.AgentDashboardStatus;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import javafx.animation.Animation;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.scene.shape.FillRule;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;
import javafx.scene.text.Text;
import javafx.util.Duration;

import javafx.scene.input.KeyCode;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Dashboard view showing a tree of all active terminal tabs with their connection status.
 * Shows server name or IP address, and allows reconnect/close via context menu.
 */
public class DashboardView extends VBox {

    private final TabPane tabPane;
    private final BiConsumer<TerminalTab, DashboardAction> actionHandler;
    /** Resolves a connection to its credential-environment display name (or null). */
    private final Function<ServerConnection, String> environmentResolver;
    private final TreeView<DashboardItem> treeView;
    private final Button refreshButton;
    private final Button collapseAllButton;
    /** Icon of the collapse/expand toggle; flips between chevrons up and down. */
    private final SVGPath collapseAllIcon;
    private final Label footerLabel;
    private final VBox emptyBox;
    // Lightweight 1s tick that re-renders cells so AI-agent badges stay current while the dashboard shows.
    private Timeline agentStatusTimer;

    /** Width bounds for the content-sized panel. */
    private static final double PANEL_MIN_WIDTH = 220;
    private static final double PANEL_MAX_WIDTH = 480;
    private static final Duration WIDTH_ANIM = Duration.millis(140);
    private static final Duration SHOW_HIDE_ANIM = Duration.millis(180);
    /** Width every row needs besides its text: tree padding, disclosure node, icon, gaps, scrollbar. */
    private static final double ROW_CHROME_WIDTH = 96;
    private static final double INDENT_WIDTH = 18;

    /** Panel width all entries currently fit in; refreshed together with the tree. */
    private double targetWidth = PANEL_MIN_WIDTH;
    private Timeline widthAnimation;

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
    // Header button icons: circular refresh arrow, double chevron up (collapse all).
    private static final String ICON_REFRESH =
            "M8,2.5 A5.5,5.5 0 1 0 13.5,8 L12,8 A4,4 0 1 1 8,4 L8,6.5 L11.5,3.75 L8,1 Z";
    private static final String ICON_COLLAPSE_ALL =
            "M3,7.5 L8,2.5 13,7.5 11.6,8.9 8,5.3 4.4,8.9 z M3,13 L8,8 13,13 11.6,14.4 8,10.8 4.4,14.4 z";
    private static final String ICON_EXPAND_ALL =
            "M3,3 L4.4,1.6 8,5.2 11.6,1.6 13,3 8,8 z M3,8.5 L4.4,7.1 8,10.7 11.6,7.1 13,8.5 8,13.5 z";
    // Context menu icons: right arrow (focus), overlapping squares (duplicate), X (close).
    private static final String ICON_FOCUS =
            "M2,6.5 h7 v-3.5 l5,5 -5,5 v-3.5 h-7 z";
    private static final String ICON_DUPLICATE =
            "M2,2 h8 v3 h-3 v5 h-5 z M6,6 h8 v8 h-8 z M7.5,7.5 v5 h5 v-5 z";
    private static final String ICON_CLOSE =
            "M3,4.4 L4.4,3 8,6.6 11.6,3 13,4.4 9.4,8 13,11.6 11.6,13 8,9.4 4.4,13 3,11.6 6.6,8 z";

    public DashboardView(TabPane tabPane, BiConsumer<TerminalTab, DashboardAction> actionHandler,
                         Function<ServerConnection, String> environmentResolver) {
        this.tabPane = tabPane;
        this.actionHandler = actionHandler;
        this.environmentResolver = environmentResolver;

        getStyleClass().add("dashboard-view");

        HBox titleBox = new HBox(6);
        titleBox.getStyleClass().add("dashboard-title");
        titleBox.setAlignment(Pos.CENTER_LEFT);

        Label titleLabel = new Label(I18n.get("dashboard.title"));
        titleLabel.getStyleClass().add("dashboard-title-label");

        Region titleSpacer = new Region();
        HBox.setHgrow(titleSpacer, Priority.ALWAYS);

        collapseAllIcon = new SVGPath();
        collapseAllIcon.setFillRule(FillRule.EVEN_ODD);
        collapseAllIcon.setContent(ICON_COLLAPSE_ALL);
        collapseAllIcon.getStyleClass().add("dashboard-button-icon");
        collapseAllButton = iconButton(collapseAllIcon, I18n.get("dashboard.collapseAll"));
        collapseAllButton.setOnAction(e -> toggleCollapseAll());

        SVGPath refreshIcon = new SVGPath();
        refreshIcon.setFillRule(FillRule.EVEN_ODD);
        refreshIcon.setContent(ICON_REFRESH);
        refreshIcon.getStyleClass().add("dashboard-button-icon");
        refreshButton = iconButton(refreshIcon, I18n.get("dashboard.refresh"));
        refreshButton.setOnAction(e -> refresh());

        titleBox.getChildren().addAll(titleLabel, titleSpacer, collapseAllButton, refreshButton);

        treeView = new TreeView<>();
        treeView.getStyleClass().add("dashboard-tree");
        treeView.setShowRoot(false);

        // Enter focuses the selected connection (same as double-click).
        treeView.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                TreeItem<DashboardItem> selected = treeView.getSelectionModel().getSelectedItem();
                if (selected != null && selected.getValue() != null && selected.getValue().getTerminalTab() != null) {
                    actionHandler.accept(selected.getValue().getTerminalTab(), DashboardAction.FOCUS);
                }
            }
        });

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

        // Empty-state placeholder shown instead of the tree when no tabs are open.
        SVGPath emptyIcon = new SVGPath();
        emptyIcon.setFillRule(FillRule.EVEN_ODD);
        emptyIcon.setContent(ICON_CONNECTION);
        emptyIcon.getStyleClass().add("dashboard-empty-icon");
        emptyIcon.setScaleX(2.5);
        emptyIcon.setScaleY(2.5);
        Label emptyLabel = new Label(I18n.get("dashboard.empty"));
        emptyLabel.getStyleClass().add("dashboard-empty-label");
        emptyLabel.setWrapText(true);
        emptyBox = new VBox(24, emptyIcon, emptyLabel);
        emptyBox.getStyleClass().add("dashboard-empty");
        emptyBox.setAlignment(Pos.CENTER);
        emptyBox.setMouseTransparent(true);
        emptyBox.setVisible(false);

        StackPane contentStack = new StackPane(treeView, emptyBox);
        VBox.setVgrow(contentStack, Priority.ALWAYS);

        footerLabel = new Label();
        footerLabel.getStyleClass().add("dashboard-footer");
        footerLabel.setMaxWidth(Double.MAX_VALUE);

        getChildren().addAll(titleBox, contentStack, footerLabel);

        // Clip so content doesn't paint outside the panel while its width animates.
        Rectangle clip = new Rectangle();
        clip.widthProperty().bind(widthProperty());
        clip.heightProperty().bind(heightProperty());
        setClip(clip);
        setPanelWidth(PANEL_MIN_WIDTH);

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

    /** Pins min/pref/max so the HBox layout gives the panel exactly this width. */
    private void setPanelWidth(double width) {
        setMinWidth(width);
        setPrefWidth(width);
        setMaxWidth(width);
    }

    private void animatePanelWidth(double toWidth, Duration duration, Runnable onFinished) {
        if (widthAnimation != null) {
            widthAnimation.stop();
        }
        widthAnimation = new Timeline(new KeyFrame(duration,
                new KeyValue(minWidthProperty(), toWidth, Interpolator.EASE_BOTH),
                new KeyValue(prefWidthProperty(), toWidth, Interpolator.EASE_BOTH),
                new KeyValue(maxWidthProperty(), toWidth, Interpolator.EASE_BOTH)));
        if (onFinished != null) {
            widthAnimation.setOnFinished(e -> onFinished.run());
        }
        widthAnimation.play();
    }

    /** Animates the panel in from zero width. Call after adding it to the layout. */
    public void playShowAnimation() {
        setPanelWidth(0);
        animatePanelWidth(targetWidth, SHOW_HIDE_ANIM, null);
    }

    /** Animates the panel out to zero width, then runs the removal callback. */
    public void playHideAnimation(Runnable onHidden) {
        animatePanelWidth(0, SHOW_HIDE_ANIM, () -> {
            if (onHidden != null) {
                onHidden.run();
            }
            setPanelWidth(targetWidth);
        });
    }

    /**
     * Recomputes the width needed to show every entry (clamped to
     * [PANEL_MIN_WIDTH, PANEL_MAX_WIDTH]) and animates the panel towards it
     * when shown. Names longer than the max width ellipsize in their labels.
     */
    private void updatePanelWidth() {
        double needed = PANEL_MIN_WIDTH;
        TreeItem<DashboardItem> root = treeView.getRoot();
        if (root != null) {
            for (TreeItem<DashboardItem> child : root.getChildren()) {
                needed = Math.max(needed, requiredRowWidth(child, 0));
            }
        }
        targetWidth = Math.min(needed, PANEL_MAX_WIDTH);
        if (getScene() != null && (widthAnimation == null || widthAnimation.getStatus() != Animation.Status.RUNNING)) {
            if (Math.abs(getPrefWidth() - targetWidth) > 1) {
                animatePanelWidth(targetWidth, WIDTH_ANIM, null);
            }
        } else if (getScene() == null) {
            setPanelWidth(targetWidth);
        }
    }

    /** Widest row in this subtree, in px, including indentation and row chrome. */
    private double requiredRowWidth(TreeItem<DashboardItem> item, int depth) {
        DashboardItem di = item.getValue();
        double width = ROW_CHROME_WIDTH + depth * INDENT_WIDTH;
        if (di != null) {
            boolean header = di.getType() != NodeType.CONNECTION;
            width += textWidth(di.getDisplayName(), header
                    ? Font.font(null, FontWeight.BOLD, 13)
                    : Font.font(13));
            if (header && di.getTotalCount() >= 0) {
                width += 6 + textWidth(I18n.get("dashboard.count", di.getActiveCount(), di.getTotalCount()),
                        Font.font(11));
            }
            if (di.getType() == NodeType.CONNECTION) {
                // status dot + protocol badge + possible agent badge
                width += 14 + 6 + textWidth(protocolLabelFor(di.getTerminalTab()), Font.font(10)) + 8 + 20;
            }
        }
        double max = width;
        for (TreeItem<DashboardItem> child : item.getChildren()) {
            max = Math.max(max, requiredRowWidth(child, depth + 1));
        }
        return max;
    }

    private static double textWidth(String text, Font font) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        Text measurer = new Text(text);
        measurer.setFont(font);
        return Math.ceil(measurer.getLayoutBounds().getWidth());
    }

    /** 16x16 SVG icon for context-menu items. Popups can't resolve the panel's
     *  looked-up colors, so it uses the static dashboard-menu-icon fill. */
    private static StackPane menuIcon(String svgPath) {
        SVGPath icon = new SVGPath();
        icon.setFillRule(FillRule.EVEN_ODD);
        icon.setContent(svgPath);
        icon.getStyleClass().add("dashboard-menu-icon");
        StackPane pane = new StackPane(icon);
        pane.setMinSize(16, 16);
        pane.setPrefSize(16, 16);
        pane.setMaxSize(16, 16);
        return pane;
    }

    /** Small transparent header button with a 16x16 SVG icon. */
    private static Button iconButton(SVGPath icon, String tooltip) {
        StackPane pane = new StackPane(icon);
        pane.setMinSize(16, 16);
        pane.setPrefSize(16, 16);
        pane.setMaxSize(16, 16);
        Button button = new Button();
        button.setGraphic(pane);
        button.getStyleClass().add("dashboard-icon-button");
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    /** Collapses everything while anything is expanded, otherwise expands everything. */
    private void toggleCollapseAll() {
        TreeItem<DashboardItem> root = treeView.getRoot();
        if (root == null) {
            return;
        }
        boolean collapse = hasExpandedNode(root);
        root.getChildren().forEach(item -> setExpandedRecursively(item, !collapse));
        updateCollapseAllButton();
    }

    private void setExpandedRecursively(TreeItem<DashboardItem> item, boolean expanded) {
        item.getChildren().forEach(child -> setExpandedRecursively(child, expanded));
        if (!item.getChildren().isEmpty()) {
            item.setExpanded(expanded);
        }
    }

    /** True when any expandable node below the (hidden) root is expanded. */
    private boolean hasExpandedNode(TreeItem<DashboardItem> root) {
        for (TreeItem<DashboardItem> child : root.getChildren()) {
            if (isExpandedDeep(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean isExpandedDeep(TreeItem<DashboardItem> item) {
        if (!item.getChildren().isEmpty() && item.isExpanded()) {
            return true;
        }
        for (TreeItem<DashboardItem> child : item.getChildren()) {
            if (isExpandedDeep(child)) {
                return true;
            }
        }
        return false;
    }

    /** Flips the toggle's icon and tooltip to describe what clicking it will do next. */
    private void updateCollapseAllButton() {
        boolean anyExpanded = treeView.getRoot() != null && hasExpandedNode(treeView.getRoot());
        collapseAllIcon.setContent(anyExpanded ? ICON_COLLAPSE_ALL : ICON_EXPAND_ALL);
        collapseAllButton.getTooltip().setText(
                I18n.get(anyExpanded ? "dashboard.collapseAll" : "dashboard.expandAll"));
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

                    MenuItem focusItem = new MenuItem(I18n.get("dashboard.focus"), menuIcon(ICON_FOCUS));
                    focusItem.setOnAction(e -> {
                        actionHandler.accept(item.getTerminalTab(), DashboardAction.FOCUS);
                    });
                    contextMenu.getItems().add(focusItem);

                    MenuItem duplicateItem = new MenuItem(I18n.get("dashboard.duplicate"), menuIcon(ICON_DUPLICATE));
                    duplicateItem.setOnAction(e -> {
                        actionHandler.accept(item.getTerminalTab(), DashboardAction.DUPLICATE);
                    });
                    contextMenu.getItems().add(duplicateItem);

                    MenuItem reconnectItem = new MenuItem(I18n.get("dashboard.reconnect"), menuIcon(ICON_REFRESH));
                    reconnectItem.setOnAction(e -> {
                        actionHandler.accept(item.getTerminalTab(), DashboardAction.RECONNECT);
                    });
                    contextMenu.getItems().add(reconnectItem);

                    contextMenu.getItems().add(new SeparatorMenuItem());

                    if (item.isConnected()) {
                        MenuItem sftpItem = new MenuItem(I18n.get("menu.connections.sftpClient"), menuIcon(ICON_GROUP));
                        sftpItem.setOnAction(e -> {
                            actionHandler.accept(item.getTerminalTab(), DashboardAction.SFTP_MANAGER);
                        });
                        contextMenu.getItems().add(sftpItem);
                        contextMenu.getItems().add(new SeparatorMenuItem());
                    }

                    MenuItem closeItem = new MenuItem(I18n.get("dialog.close"), menuIcon(ICON_CLOSE));
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

        // Ungrouped tabs: cluster by credential environment. Tabs without a
        // resolvable environment sit directly under the main window node.
        java.util.Map<String, java.util.List<TerminalTab>> environments = new java.util.HashMap<>();
        for (TerminalTab terminalTab : ungroupedTabs) {
            String env = resolveEnvironmentName(terminalTab);
            if (env == null) {
                windowItem.getChildren().add(new TreeItem<>(
                        DashboardItem.connection(getServerDisplayName(terminalTab), terminalTab)));
            } else {
                environments.computeIfAbsent(env, k -> new java.util.ArrayList<>()).add(terminalTab);
            }
        }

        List<String> sortedEnvironments = new java.util.ArrayList<>(environments.keySet());
        sortedEnvironments.sort(String::compareToIgnoreCase);
        for (String envName : sortedEnvironments) {
            java.util.List<TerminalTab> envTabs = environments.get(envName);
            int envActive = 0;
            for (TerminalTab tab : envTabs) {
                if (stateOf(tab) == ConnState.CONNECTED) {
                    envActive++;
                }
            }
            TreeItem<DashboardItem> envItem = new TreeItem<>(
                    DashboardItem.container(NodeType.ENVIRONMENT, envName, envActive, envTabs.size()));
            envItem.setExpanded(true);
            for (TerminalTab terminalTab : envTabs) {
                envItem.getChildren().add(new TreeItem<>(
                        DashboardItem.connection(getServerDisplayName(terminalTab), terminalTab)));
            }
            windowItem.getChildren().add(envItem);
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
        // Keep the collapse/expand toggle in sync with manual disclosure clicks
        // (branch events bubble up to the root; the root is rebuilt each refresh).
        root.addEventHandler(TreeItem.<DashboardItem>branchExpandedEvent(), e -> updateCollapseAllButton());
        root.addEventHandler(TreeItem.<DashboardItem>branchCollapsedEvent(), e -> updateCollapseAllButton());
        treeView.setRoot(root);
        updateCollapseAllButton();

        emptyBox.setVisible(totalTabs == 0);
        footerLabel.setText(I18n.get("dashboard.footer", activeTabs, totalTabs));
        updatePanelWidth();
    }

    /** Environment display name for a tab's connection credential, or null if none. */
    private String resolveEnvironmentName(TerminalTab terminalTab) {
        if (environmentResolver == null || terminalTab.getConnection() == null) {
            return null;
        }
        try {
            String env = environmentResolver.apply(terminalTab.getConnection());
            return env != null && !env.isBlank() ? env : null;
        } catch (Exception e) {
            return null;
        }
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
