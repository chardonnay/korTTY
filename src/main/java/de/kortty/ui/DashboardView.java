package de.kortty.ui;

import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentGlyphs;
import de.kortty.codingagent.CodingAgentMonitor;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.DurationText;
import de.kortty.codingagent.PaneLocation;
import de.kortty.codingagent.PaneLocator;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.RegistryChange;
import de.kortty.codingagent.TabRollup;
import de.kortty.codingagent.TerminalScreenCapture;
import de.kortty.core.AgentDashboardStatus;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import javafx.animation.Animation;
import javafx.animation.AnimationTimer;
import javafx.animation.Interpolator;
import javafx.animation.KeyFrame;
import javafx.animation.KeyValue;
import javafx.animation.Timeline;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.control.TreeCell;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
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
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

/**
 * Dashboard view showing a tree of all active terminal tabs with their connection status.
 * Shows server name or IP address, and allows reconnect/close via context menu.
 *
 * <p>When a {@link CodingAgentRegistry} is installed ({@link #setCodingAgentRegistry}) the rows
 * additionally carry the coding-agent marks of Stage 2: a state-coloured chip with the time in
 * state, an accent bar on the tree cell, a breathing status dot while an agent is BLOCKED, rollup
 * chips on container rows and in the footer, one PANE leaf row per pane of a split tab, a one-shot
 * auto-expand of the path to a newly BLOCKED pane and pane-level context-menu
 * entries routed through the {@link PaneActionHandler} port. Without a registry the view behaves
 * exactly as before (legacy AI-agent glyph only).
 */
public class DashboardView extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(DashboardView.class);

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
    /** Pulse frames are capped at ~30 fps like SwarmStatusStrip's driver. */
    private static final long FRAME_INTERVAL_NANOS = 33_000_000L;

    /** Panel width all entries currently fit in; refreshed together with the tree. */
    private double targetWidth = PANEL_MIN_WIDTH;
    private Timeline widthAnimation;
    /** Inline fill for context-menu icons (popups can't resolve the panel's looked-up colors). */
    private String menuIconColor;

    // ---- Coding-agent integration (all on the FX thread) ----
    /** Null until MainWindow installs the application registry; every read is guarded. */
    private CodingAgentRegistry registry;
    private AutoCloseable registryHandle;
    private PaneActionHandler paneActionHandler;
    private PaneLocator paneLocator;
    /** Status dots of rows whose agent is BLOCKED; identity-compared (Circle does not override equals). */
    private final java.util.ArrayList<Circle> pulsingDots = new java.util.ArrayList<>();
    private final long originNanos = System.nanoTime();
    private long lastFrameNanos;
    private boolean pulseTimerRunning;
    private boolean disposed;
    /** (pane, stateSinceMillis) of the last auto-expanded BLOCKED transition, so it fires once per transition. */
    private String lastAutoExpandKey;
    private final AnimationTimer pulseTimer = new AnimationTimer() {
        @Override
        public void handle(long now) {
            if (now - lastFrameNanos < FRAME_INTERVAL_NANOS) {
                return;
            }
            lastFrameNanos = now;
            renderPulseFrame(nowSeconds());
        }
    };

    public enum DashboardAction {
        RECONNECT,
        CLOSE,
        FOCUS,
        SFTP_MANAGER,
        DUPLICATE
    }

    /** Pane-level actions of agent rows; routed through {@link PaneActionHandler}. */
    public enum PaneAction {
        FOCUS,
        OPEN_PANEL,
        SEND_ENTER,
        SEND_ESC,
        INTERRUPT
    }

    /**
     * Port through which pane-level actions leave the dashboard; MainWindow supplies the
     * implementation (widget focus, panel, key writes via CodingAgentActions).
     */
    @FunctionalInterface
    public interface PaneActionHandler {
        void handle(TerminalTab tab, SithTermFxWidget widget, PaneRef pane, PaneAction action);
    }

    /** Kind of a dashboard tree node; drives icon and typography. */
    public enum NodeType {
        MAIN_WINDOW,
        ENVIRONMENT,
        GROUP,
        CONNECTION,
        PANE
    }

    /** Connection state of a leaf row, shown as a colored status dot. */
    private enum ConnState {
        CONNECTED,
        ERROR,
        ENDED
    }

    // 16x16 icon shapes (even-odd fill): monitor, stacked layers, folder, terminal, split pane.
    private static final String ICON_MAIN_WINDOW =
            "M1,2 h14 v9 h-14 z M3,4 h10 v5 h-10 z M6,12.5 h4 v1.5 h-4 z";
    private static final String ICON_ENVIRONMENT =
            "M8,1.5 L14,5 8,8.5 2,5 z M2,8.5 L8,12 14,8.5 14,10 8,13.5 2,10 z";
    private static final String ICON_GROUP =
            "M1,3 h5 l1.5,2 H15 v8 H1 z";
    private static final String ICON_CONNECTION =
            "M1,2 h14 v11 h-14 z M2.5,3.5 h11 v8 h-11 z M4,5.5 l2.5,1.75 -2.5,1.75 z M8,9.5 h4 v1 h-4 z";
    private static final String ICON_PANE =
            "M1,2 h14 v11 h-14 z M2.5,3.5 h5 v8 h-5 z M8.5,3.5 h5 v8 h-5 z";
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
    // Coding-agent context menu icons: return arrow (Enter), corner-out arrow (Esc), ring with a bar (interrupt).
    private static final String ICON_KEY_ENTER =
            "M13,3 v5.5 h-7 v-2.5 l-4,3.75 4,3.75 v-2.5 h9 v-8 z";
    private static final String ICON_KEY_ESC =
            "M3,3 h5 v2 h-3 v6 h6 v-3 h2 v5 h-10 z M9,2 h5 v5 l-1.8,-1.8 -3.2,3.2 -1.4,-1.4 3.2,-3.2 z";
    private static final String ICON_INTERRUPT =
            "M8,1.5 A6.5,6.5 0 1 0 8,14.5 A6.5,6.5 0 1 0 8,1.5 z M8,3.5 A4.5,4.5 0 1 1 8,12.5 A4.5,4.5 0 1 1 8,3.5 z "
            + "M5.5,5.5 h5 v5 h-5 z";

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

        // Enter focuses the selected connection or pane (same as double-click).
        treeView.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ENTER) {
                TreeItem<DashboardItem> selected = treeView.getSelectionModel().getSelectedItem();
                if (selected != null) {
                    fireFocus(selected.getValue());
                }
            }
        });

        treeView.setCellFactory(tv -> {
            TreeCell<DashboardItem> cell = new DashboardCell();

            // Double-click to focus
            cell.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2) {
                    fireFocus(cell.getItem());
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
        // The tick also re-evaluates the pulse gate (window showing, animations setting).
        agentStatusTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            treeView.refresh();
            updatePulseTimer();
        }));
        agentStatusTimer.setCycleCount(Animation.INDEFINITE);
        // Subscription lifecycle: hiding the dashboard removes it from the main content box,
        // so leaving the scene always unsubscribes and stops every timer.
        sceneProperty().addListener((obs, oldScene, newScene) -> {
            if (newScene != null && !disposed) {
                subscribeRegistry();
                agentStatusTimer.playFromStart();
                updatePulseTimer();
            } else {
                unsubscribeRegistry();
                agentStatusTimer.stop();
                stopPulseTimer();
                clearPulsingDots();
            }
        });

        refresh();
    }

    // ---- Coding-agent wiring --------------------------------------------------------------------

    /**
     * Installs (or replaces, or removes with null) the application's coding-agent registry. The
     * view subscribes while it is in a scene and re-renders immediately. Without a registry the
     * rows show the legacy AI-agent glyph only.
     */
    public void setCodingAgentRegistry(CodingAgentRegistry registry) {
        if (this.registry == registry) {
            return;
        }
        unsubscribeRegistry();
        this.registry = registry;
        lastAutoExpandKey = null;
        if (getScene() != null && !disposed) {
            subscribeRegistry();
        }
        refresh();
    }

    /** Installs the port that carries pane-level actions (focus pane, open panel, send keys). */
    public void setPaneActionHandler(PaneActionHandler handler) {
        this.paneActionHandler = handler;
    }

    /**
     * Installs the locator that resolves a pane's working directory for PANE row titles
     * ("Pane 2 · api"). Optional; without it pane rows show the index only.
     */
    public void setPaneLocator(PaneLocator locator) {
        this.paneLocator = locator;
        refresh();
    }

    /** True while the BLOCKED pulse AnimationTimer is running (smoke assertion hook). */
    public boolean isPulseTimerRunning() {
        return pulseTimerRunning;
    }

    /** Renders one pulse frame at animation time {@code t} seconds without the timer (smoke hook). */
    public void renderFrameForTest(double t) {
        renderPulseFrame(t);
    }

    /** Unsubscribes from the registry and stops every timer; called from MainWindow's close handler. */
    public void dispose() {
        disposed = true;
        unsubscribeRegistry();
        if (agentStatusTimer != null) {
            agentStatusTimer.stop();
        }
        stopPulseTimer();
        clearPulsingDots();
        if (widthAnimation != null) {
            widthAnimation.stop();
        }
    }

    private void subscribeRegistry() {
        if (registry == null || registryHandle != null) {
            return;
        }
        try {
            registryHandle = registry.addListener(this::onRegistryChanged);
        } catch (Exception e) {
            logger.debug("Dashboard could not subscribe to the coding-agent registry: {}", e.toString());
        }
    }

    private void unsubscribeRegistry() {
        AutoCloseable handle = registryHandle;
        registryHandle = null;
        if (handle != null) {
            try {
                handle.close();
            } catch (Exception e) {
                logger.debug("Dashboard registry handle close failed: {}", e.toString());
            }
        }
    }

    /** Registry callback (FX thread): re-render and auto-expand the path to a newly BLOCKED pane once. */
    private void onRegistryChanged(RegistryChange change) {
        if (disposed) {
            return;
        }
        if (change != null && change.kind() == RegistryChange.Kind.EVIDENCE_CHANGED) {
            // Only the detection evidence moved (a WORKING agent's animated status line does that
            // every coalescing window): nothing the tree renders — state, rollups, counts, durations
            // — changed, so take the same in-place path as the 1s badge tick instead of rebuilding
            // the whole tree, which would replace the root and reset focus, anchor and every cell's
            // context menu several times per second.
            treeView.refresh();
            return;
        }
        refresh();
        if (change == null || !change.entered(CodingAgentState.BLOCKED) || change.current() == null
                || change.current().pane() == null) {
            return;
        }
        CodingAgentEntry entry = change.current();
        TerminalTab tab = tabForId(entry.pane().tabId());
        if (tab == null) {
            return;
        }
        String key = entry.pane().tabId() + "|" + entry.pane().paneId() + "|" + entry.stateSinceMillis();
        if (key.equals(lastAutoExpandKey)) {
            return;
        }
        lastAutoExpandKey = key;
        expandPathTo(tab);
    }

    private void expandPathTo(TerminalTab tab) {
        TreeItem<DashboardItem> root = treeView.getRoot();
        if (root == null) {
            return;
        }
        TreeItem<DashboardItem> hit = findItem(root, tab, null, null);
        if (hit == null) {
            return;
        }
        if (!hit.getChildren().isEmpty()) {
            hit.setExpanded(true);
        }
        for (TreeItem<DashboardItem> parent = hit.getParent(); parent != null; parent = parent.getParent()) {
            parent.setExpanded(true);
        }
        updateCollapseAllButton();
    }

    /** The tab of this window whose TerminalView id equals {@code tabId}, or null. */
    private TerminalTab tabForId(String tabId) {
        if (tabId == null) {
            return null;
        }
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab && tabId.equals(terminalViewIdOf(terminalTab))) {
                return terminalTab;
            }
        }
        return null;
    }

    private static String terminalViewIdOf(TerminalTab tab) {
        try {
            TerminalView view = tab != null ? tab.getTerminalView() : null;
            return view != null ? view.getTerminalViewId() : null;
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Guarded registry reads (neutral values when absent or failing) ----

    private TabRollup rollupFor(String terminalViewId) {
        if (registry == null || terminalViewId == null) {
            return TabRollup.EMPTY;
        }
        try {
            TabRollup rollup = registry.rollupFor(terminalViewId);
            return rollup != null ? rollup : TabRollup.EMPTY;
        } catch (Exception e) {
            return TabRollup.EMPTY;
        }
    }

    private List<CodingAgentEntry> entriesFor(String terminalViewId) {
        if (registry == null || terminalViewId == null) {
            return List.of();
        }
        try {
            return registry.entriesForTab(terminalViewId);
        } catch (Exception e) {
            return List.of();
        }
    }

    private CodingAgentEntry entryFor(PaneRef pane) {
        if (registry == null || pane == null) {
            return null;
        }
        try {
            return registry.entry(pane).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /** Component-wise sum of two rollups; the most urgent state wins. */
    private static TabRollup sumRollups(TabRollup a, TabRollup b) {
        if (a == null || !a.hasAgents()) {
            return b == null ? TabRollup.EMPTY : b;
        }
        if (b == null || !b.hasAgents()) {
            return a;
        }
        return new TabRollup("", a.blocked() + b.blocked(), a.working() + b.working(), a.done() + b.done(),
                a.idle() + b.idle(), a.agentCount() + b.agentCount(),
                CodingAgentState.mostUrgent(a.mostUrgent(), b.mostUrgent()));
    }

    private static String rollupTextOf(TabRollup rollup) {
        if (rollup == null || !rollup.hasAgents()) {
            return "";
        }
        return CodingAgentGlyphs.rollupText(rollup.blocked(), rollup.working(), rollup.done());
    }

    private static PaneRef paneRefFor(TerminalView view, SithTermFxWidget widget) {
        try {
            return view.codingAgentMonitorFor(widget).map(CodingAgentMonitor::pane)
                    .orElseGet(() -> new PaneRef(view.getTerminalViewId(), TerminalScreenCapture.paneIdOf(widget)));
        } catch (Exception e) {
            return null;
        }
    }

    private static List<SithTermFxWidget> orderedWidgets(TerminalView view) {
        try {
            List<SithTermFxWidget> widgets = view.getOrderedWidgets();
            return widgets != null ? widgets : List.of();
        } catch (Exception e) {
            return List.of();
        }
    }

    /**
     * Last path segment of the pane's working directory via the locator port, or null when unknown.
     *
     * <p>The home directory is abbreviated to {@code ~} first, with the same helper the Coding Agents
     * panel uses, so both views write one directory the same way. Without it the home directory
     * contributes its last segment, which is the user's account name — and a pane row reading
     * "Pane 1 · claude" is then indistinguishable from one naming a detected agent.
     */
    private String cwdTailFor(PaneRef pane) {
        if (paneLocator == null || pane == null) {
            return null;
        }
        try {
            return paneLocator.locate(pane)
                .map(PaneLocation::workingDirectory)
                .map(directory -> cwdLabel(directory, System.getProperty("user.home")))
                .orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * What a pane row puts after its number: the working directory's last segment, with the home
     * directory abbreviated first so it reads {@code ~} rather than the account name.
     *
     * <p>Separated from the locator lookup so the rule itself is assertable without a toolkit.
     */
    static String cwdLabel(String workingDirectory, String home) {
        return pathTail(PaneLocation.abbreviateHome(workingDirectory, home));
    }

    private static String pathTail(String path) {
        if (path == null) {
            return null;
        }
        String trimmed = path.trim();
        while (trimmed.length() > 1 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int cut = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
        String tail = cut >= 0 && cut < trimmed.length() - 1 ? trimmed.substring(cut + 1) : trimmed;
        return tail.isEmpty() ? null : tail;
    }

    // ---- Focus / pane actions ----

    private void fireFocus(DashboardItem item) {
        if (item == null || item.getTerminalTab() == null) {
            return;
        }
        if (item.getType() == NodeType.PANE) {
            firePaneAction(item, PaneAction.FOCUS);
            return;
        }
        actionHandler.accept(item.getTerminalTab(), DashboardAction.FOCUS);
    }

    private void firePaneAction(DashboardItem item, PaneAction action) {
        TerminalTab tab = item.getTerminalTab();
        if (paneActionHandler == null) {
            logger.debug("No pane action handler installed; {} on '{}' ignored", action, item.getDisplayName());
            if (action == PaneAction.FOCUS && tab != null) {
                actionHandler.accept(tab, DashboardAction.FOCUS);
            }
            return;
        }
        PaneRef pane = item.getEntry() != null ? item.getEntry().pane() : null;
        SithTermFxWidget widget = widgetFor(item, pane);
        if (pane == null && widget != null && tab != null && tab.getTerminalView() != null) {
            pane = paneRefFor(tab.getTerminalView(), widget);
        }
        try {
            paneActionHandler.handle(tab, widget, pane, action);
        } catch (Exception e) {
            logger.warn("Dashboard pane action {} failed: {}", action, e.toString());
        }
    }

    private static SithTermFxWidget widgetFor(DashboardItem item, PaneRef pane) {
        if (item.getWidget() != null) {
            return item.getWidget();
        }
        TerminalTab tab = item.getTerminalTab();
        if (pane == null || tab == null || tab.getTerminalView() == null) {
            return null;
        }
        try {
            return tab.getTerminalView().codingAgentWidgetFor(pane).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    // ---- Pulse timer (BLOCKED rows only) ----

    private double nowSeconds() {
        return (System.nanoTime() - originNanos) / 1_000_000_000.0;
    }

    private void renderPulseFrame(double t) {
        double scale = SwarmStatusStripSupport.pulseScale(t, 0);
        double alpha = SwarmStatusStripSupport.pulseGlowAlpha(t, 0);
        for (int i = 0; i < pulsingDots.size(); i++) {
            Circle dot = pulsingDots.get(i);
            dot.setScaleX(scale);
            dot.setScaleY(scale);
            dot.setOpacity(alpha);
        }
    }

    private boolean isWindowShowing() {
        if (getScene() == null) {
            return false;
        }
        Window window = getScene().getWindow();
        return window != null && window.isShowing();
    }

    private void updatePulseTimer() {
        boolean shouldRun = !disposed
                && !pulsingDots.isEmpty()
                && getScene() != null
                && isWindowShowing()
                && AppDesignStyleSupport.appDesignAnimationsEnabled();
        if (shouldRun && !pulseTimerRunning) {
            pulseTimerRunning = true;
            lastFrameNanos = 0L;
            pulseTimer.start();
        } else if (!shouldRun && pulseTimerRunning) {
            stopPulseTimer();
            resetDots();
        }
    }

    private void stopPulseTimer() {
        if (pulseTimerRunning) {
            pulseTimer.stop();
            pulseTimerRunning = false;
        }
    }

    private void resetDots() {
        for (int i = 0; i < pulsingDots.size(); i++) {
            resetDot(pulsingDots.get(i));
        }
    }

    private static void resetDot(Circle dot) {
        dot.setScaleX(1);
        dot.setScaleY(1);
        dot.setOpacity(1);
    }

    private void clearPulsingDots() {
        resetDots();
        pulsingDots.clear();
    }

    private void registerPulsingDot(Circle dot) {
        if (!pulsingDots.contains(dot)) {
            pulsingDots.add(dot);
            updatePulseTimer();
        }
    }

    private void unregisterPulsingDot(Circle dot) {
        if (pulsingDots.remove(dot)) {
            resetDot(dot);
            updatePulseTimer();
        }
    }

    // ---- Panel width ----

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
        widthAnimation.setOnFinished(e -> {
            if (onFinished != null) {
                onFinished.run();
            }
            // A refresh() during this animation may have computed a new targetWidth
            // and skipped animating; catch up now. Not after the hide animation
            // (toWidth 0 removes the panel from the scene).
            if (toWidth != 0 && getScene() != null && Math.abs(getPrefWidth() - targetWidth) > 1) {
                animatePanelWidth(targetWidth, WIDTH_ANIM, null);
            }
        });
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
        // Measure with the font the rows actually render in (design stylesheets may
        // switch the tree to e.g. Monospaced); fall back to the system default until
        // a first cell exists to sample from.
        Font rowFont = Font.font(13);
        javafx.scene.Node sample = treeView.lookup(".dashboard-node-name");
        if (sample instanceof Label sampleLabel && sampleLabel.getFont() != null) {
            rowFont = sampleLabel.getFont();
        }
        String family = rowFont.getFamily();
        Font nameFont = Font.font(family, rowFont.getSize());
        Font headerFont = Font.font(family, FontWeight.BOLD, rowFont.getSize());
        Font countFont = Font.font(family, 11);
        Font badgeFont = Font.font(family, 10);

        double needed = PANEL_MIN_WIDTH;
        TreeItem<DashboardItem> root = treeView.getRoot();
        if (root != null) {
            for (TreeItem<DashboardItem> child : root.getChildren()) {
                needed = Math.max(needed, requiredRowWidth(child, 0, nameFont, headerFont, countFont, badgeFont));
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
    private double requiredRowWidth(TreeItem<DashboardItem> item, int depth,
                                    Font nameFont, Font headerFont, Font countFont, Font badgeFont) {
        DashboardItem di = item.getValue();
        double width = ROW_CHROME_WIDTH + depth * INDENT_WIDTH;
        if (di != null) {
            boolean header = DashboardAgentMarks.isHeader(di.getType());
            width += textWidth(di.getDisplayName(), header ? headerFont : nameFont);
            if (header) {
                if (di.getTotalCount() >= 0) {
                    width += 6 + textWidth(I18n.get("dashboard.count", di.getActiveCount(), di.getTotalCount()),
                            countFont);
                }
                String rollupText = rollupTextOf(di.getRollup());
                if (!rollupText.isEmpty()) {
                    width += 6 + textWidth(rollupText, badgeFont) + 12;
                }
            } else {
                // status dot (+ protocol badge on connection rows) + agent chip or legacy badge
                width += 14 + 6;
                if (di.getType() == NodeType.CONNECTION) {
                    width += textWidth(protocolLabelFor(di.getTerminalTab()), badgeFont) + 8;
                }
                DashboardAgentMarks.ChipText chip = DashboardAgentMarks.chipFor(di.getEntry());
                if (chip != null) {
                    width += textWidth(chip.label() + " 88:88", badgeFont) + 16;
                } else {
                    width += 20;
                }
            }
        }
        double max = width;
        for (TreeItem<DashboardItem> child : item.getChildren()) {
            max = Math.max(max, requiredRowWidth(child, depth + 1, nameFont, headerFont, countFont, badgeFont));
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
     *  looked-up colors, so the fill is set inline from the current theme
     *  (menuIconColor, updated by applyTheme), with the CSS class as fallback. */
    private StackPane menuIcon(String svgPath) {
        SVGPath icon = new SVGPath();
        icon.setFillRule(FillRule.EVEN_ODD);
        icon.setContent(svgPath);
        icon.getStyleClass().add("dashboard-menu-icon");
        if (menuIconColor != null && !menuIconColor.isEmpty()) {
            icon.setStyle("-fx-fill: " + menuIconColor + ";");
        }
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
     * [protocol badge] [count] [rollup chip] [agent chip]. All sub-nodes are created once per
     * cell and only mutated in updateItem(), so the 1s badge-refresh tick stays cheap: it
     * reads cached records only and touches the chip's duration label when its cached
     * String identity changed.
     */
    private class DashboardCell extends TreeCell<DashboardItem> {
        // The item this cell's context menu was built for; avoids rebuilding it on every 1s
        // badge-refresh tick (treeView.refresh() re-runs updateItem on the same item). The
        // menu is also rebuilt when the connectivity or the agent mark changes, because the
        // SFTP and send-key entries depend on them.
        private DashboardItem builtMenuForItem;
        private boolean builtMenuConnected;
        private CodingAgentState builtMenuMarkState;
        // Last-rendered signature; the 1s tick must not mutate children/styles when
        // nothing changed, or it causes a CSS+layout pass per second on every row.
        private DashboardItem lastItem;
        private ConnState lastState;
        private AgentDashboardStatus.State lastLegacy;
        /** Identity-compared cached duration text (DurationText caches below one hour). */
        private String lastDuration;
        private boolean showsDuration;
        private boolean pulse;
        /** Accent class currently on this TreeCell, or null. */
        private String accentClass;

        private final SVGPath icon = new SVGPath();
        private final StackPane iconPane = new StackPane(icon);
        private final Circle statusDot = new Circle(4);
        private final Label nameLabel = new Label();
        private final Label protocolBadge = new Label();
        private final Label countLabel = new Label();
        /** Glyph-only badge of legacy AI-agent rows (no coding agent). */
        private final Label agentBadge = new Label();
        private final Label agentChipLabel = new Label();
        private final Label agentChipDuration = new Label();
        private final HBox agentChip = new HBox(4, agentChipLabel, agentChipDuration);
        private final Label rollupChip = new Label();
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
            agentBadge.getStyleClass().addAll("dashboard-agent-badge", DashboardAgentMarks.LEGACY_CHIP_CLASS);
            agentChip.getStyleClass().add("dashboard-agent-chip");
            agentChip.setAlignment(Pos.CENTER_LEFT);
            agentChipDuration.setVisible(false);
            agentChipDuration.setManaged(false);
            rollupChip.getStyleClass().add("dashboard-rollup-chip");
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
                setAccentClass(null);
                pulse = false;
                unregisterPulsingDot(statusDot);
                builtMenuForItem = null;
                builtMenuMarkState = null;
                lastItem = null;
                lastState = null;
                lastLegacy = null;
                lastDuration = null;
                showsDuration = false;
                return;
            }
            setText(null);

            NodeType type = item.getType();
            boolean leaf = !DashboardAgentMarks.isHeader(type);
            TerminalTab tab = item.getTerminalTab();
            ConnState state = leaf ? stateOf(tab) : null;
            AgentDashboardStatus.State legacy = type == NodeType.CONNECTION ? legacyStateFor(tab) : null;
            CodingAgentEntry entry = item.getEntry();

            boolean changed = item != lastItem || state != lastState || legacy != lastLegacy;
            if (changed) {
                icon.setContent(iconPathFor(type));
                if (leaf) {
                    statusDot.getStyleClass().setAll("dashboard-status-dot",
                            "dashboard-status-dot-" + state.name().toLowerCase());
                    nameLabel.setText(item.getDisplayName());
                    protocolBadge.setText(type == NodeType.CONNECTION ? protocolLabelFor(tab) : "");
                    protocolBadge.setVisible(!protocolBadge.getText().isEmpty());
                    protocolBadge.setManaged(protocolBadge.isVisible());

                    DashboardAgentMarks.ChipText chip;
                    if (entry != null) {
                        chip = type == NodeType.CONNECTION
                                ? DashboardAgentMarks.chipFor(entry, legacy)
                                : DashboardAgentMarks.chipFor(entry);
                    } else {
                        chip = DashboardAgentMarks.legacyChip(AgentDashboardStatus.icon(legacy));
                    }
                    boolean legacyChip = chip != null && DashboardAgentMarks.LEGACY_CHIP_CLASS.equals(chip.styleClass());
                    agentBadge.setText(legacyChip ? chip.label() : "");
                    agentBadge.setVisible(legacyChip);
                    agentBadge.setManaged(legacyChip);
                    boolean codingChip = chip != null && !legacyChip;
                    if (codingChip) {
                        agentChipLabel.setText(chip.label());
                        agentChip.getStyleClass().setAll("dashboard-agent-chip", chip.styleClass());
                    }
                    agentChip.setVisible(codingChip);
                    agentChip.setManaged(codingChip);
                    showsDuration = codingChip && entry != null && hasTimedState(entry);
                    pulse = chip != null && chip.pulse();
                    setAccentClass(accentClassOf(chip));

                    rowBox.getStyleClass().remove("dashboard-node-header");
                    rowBox.getChildren().setAll(iconPane, statusDot, nameLabel, protocolBadge, agentBadge, agentChip);
                    rowTooltip.setText(tooltipTextFor(item, state, entry));
                    setTooltip(rowTooltip);
                } else {
                    nameLabel.setText(item.getDisplayName());
                    countLabel.setText(item.getTotalCount() >= 0
                            ? I18n.get("dashboard.count", item.getActiveCount(), item.getTotalCount())
                            : "");
                    countLabel.setVisible(!countLabel.getText().isEmpty());
                    countLabel.setManaged(countLabel.isVisible());
                    TabRollup rollup = item.getRollup();
                    String rollupText = rollupTextOf(rollup);
                    rollupChip.setText(rollupText);
                    rollupChip.setVisible(!rollupText.isEmpty());
                    rollupChip.setManaged(rollupChip.isVisible());
                    setAccentClass(rollup != null && rollup.hasAgents()
                            ? DashboardAgentMarks.accentClassFor(rollup.mostUrgent()) : null);
                    showsDuration = false;
                    pulse = false;
                    if (!rowBox.getStyleClass().contains("dashboard-node-header")) {
                        rowBox.getStyleClass().add("dashboard-node-header");
                    }
                    rowBox.getChildren().setAll(iconPane, nameLabel, countLabel, rollupChip);
                    setTooltip(null);
                }
                setGraphic(rowBox);
                lastItem = item;
                lastState = state;
                lastLegacy = legacy;
                lastDuration = null;
            }

            // 1s tick: only the cached duration String is compared (by identity) and set on change.
            if (showsDuration && entry != null) {
                String duration = DurationText.mmss(entry.secondsInState(System.currentTimeMillis()));
                if (duration != lastDuration) {
                    agentChipDuration.setText(duration);
                    lastDuration = duration;
                }
                if (!agentChipDuration.isVisible()) {
                    agentChipDuration.setVisible(true);
                    agentChipDuration.setManaged(true);
                }
            } else if (agentChipDuration.isVisible()) {
                agentChipDuration.setVisible(false);
                agentChipDuration.setManaged(false);
                lastDuration = null;
            }

            // Pulse registration is re-checked every pass (cheap identity contains) so a cell
            // whose dots were cleared while the panel was hidden re-registers by itself.
            if (pulse) {
                registerPulsingDot(statusDot);
            } else {
                unregisterPulsingDot(statusDot);
            }

            // Context menu for terminal tabs only (not for window nodes). Rebuilt only when
            // the row's item, its connectivity or its agent mark changes (the SFTP and the
            // send-key entries depend on them), so the 1s badge-refresh tick stays cheap.
            CodingAgentState markState = entry != null ? entry.state() : null;
            if (item != builtMenuForItem
                    || (item.getTerminalTab() != null && builtMenuConnected != item.isConnected())
                    || markState != builtMenuMarkState) {
                if (item.getTerminalTab() != null) {
                    setContextMenu(buildContextMenu(item, entry));
                } else {
                    setContextMenu(null);
                }
                builtMenuForItem = item;
                builtMenuConnected = item.isConnected();
                builtMenuMarkState = markState;
            }
        }

        private ContextMenu buildContextMenu(DashboardItem item, CodingAgentEntry entry) {
            ContextMenu contextMenu = new ContextMenu();

            MenuItem focusItem = new MenuItem(I18n.get("dashboard.focus"), menuIcon(ICON_FOCUS));
            focusItem.setOnAction(e -> actionHandler.accept(item.getTerminalTab(), DashboardAction.FOCUS));
            contextMenu.getItems().add(focusItem);

            MenuItem duplicateItem = new MenuItem(I18n.get("dashboard.duplicate"), menuIcon(ICON_DUPLICATE));
            duplicateItem.setOnAction(e -> actionHandler.accept(item.getTerminalTab(), DashboardAction.DUPLICATE));
            contextMenu.getItems().add(duplicateItem);

            MenuItem reconnectItem = new MenuItem(I18n.get("dashboard.reconnect"), menuIcon(ICON_REFRESH));
            reconnectItem.setOnAction(e -> actionHandler.accept(item.getTerminalTab(), DashboardAction.RECONNECT));
            contextMenu.getItems().add(reconnectItem);

            contextMenu.getItems().add(new SeparatorMenuItem());

            if (item.isConnected()) {
                MenuItem sftpItem = new MenuItem(I18n.get("menu.connections.sftpClient"), menuIcon(ICON_GROUP));
                sftpItem.setOnAction(e -> actionHandler.accept(item.getTerminalTab(), DashboardAction.SFTP_MANAGER));
                contextMenu.getItems().add(sftpItem);
                contextMenu.getItems().add(new SeparatorMenuItem());
            }

            MenuItem closeItem = new MenuItem(I18n.get("dialog.close"), menuIcon(ICON_CLOSE));
            closeItem.setOnAction(e -> actionHandler.accept(item.getTerminalTab(), DashboardAction.CLOSE));
            contextMenu.getItems().add(closeItem);

            // Focusing one pane of a split tab is useful whether or not an agent was detected in it,
            // so it sits outside the agent-only block below.
            boolean singleAgentConnection = item.getType() == NodeType.CONNECTION
                    && item.getRollup() != null && item.getRollup().agentCount() == 1;
            if (item.getType() == NodeType.PANE || (entry != null && singleAgentConnection)) {
                contextMenu.getItems().add(new SeparatorMenuItem());
                MenuItem focusPane = new MenuItem(I18n.get("dashboard.codingAgent.focusPane"), menuIcon(ICON_FOCUS));
                focusPane.setOnAction(e -> firePaneAction(item, PaneAction.FOCUS));
                contextMenu.getItems().add(focusPane);
            }
            if (entry != null) {
                if (item.getType() != NodeType.PANE && !singleAgentConnection) {
                    contextMenu.getItems().add(new SeparatorMenuItem());
                }
                MenuItem openPanel = new MenuItem(I18n.get("dashboard.codingAgent.openPanel"), menuIcon(ICON_PANE));
                openPanel.setOnAction(e -> firePaneAction(item, PaneAction.OPEN_PANEL));
                contextMenu.getItems().add(openPanel);
                if (entry.state() == CodingAgentState.BLOCKED) {
                    MenuItem sendEnter = new MenuItem(I18n.get("dashboard.codingAgent.sendEnter"), menuIcon(ICON_KEY_ENTER));
                    sendEnter.setOnAction(e -> firePaneAction(item, PaneAction.SEND_ENTER));
                    MenuItem sendEsc = new MenuItem(I18n.get("dashboard.codingAgent.sendEsc"), menuIcon(ICON_KEY_ESC));
                    sendEsc.setOnAction(e -> firePaneAction(item, PaneAction.SEND_ESC));
                    MenuItem interrupt = new MenuItem(I18n.get("dashboard.codingAgent.interrupt"), menuIcon(ICON_INTERRUPT));
                    interrupt.setOnAction(e -> firePaneAction(item, PaneAction.INTERRUPT));
                    contextMenu.getItems().addAll(sendEnter, sendEsc, interrupt);
                }
            }
            return contextMenu;
        }

        /** Swaps the accent class on the TreeCell itself (the 3 px left-border slot of .tree-cell). */
        private void setAccentClass(String styleClass) {
            if (java.util.Objects.equals(styleClass, accentClass)) {
                return;
            }
            if (accentClass != null) {
                getStyleClass().remove(accentClass);
            }
            if (styleClass != null) {
                getStyleClass().add(styleClass);
            }
            accentClass = styleClass;
        }
    }

    /** Accent class matching a chip's variant; none for legacy, idle or absent chips. */
    private static String accentClassOf(DashboardAgentMarks.ChipText chip) {
        if (chip == null || chip.styleClass() == null
                || !chip.styleClass().startsWith(DashboardAgentMarks.CHIP_CLASS_PREFIX)) {
            return null;
        }
        String variant = chip.styleClass().substring(DashboardAgentMarks.CHIP_CLASS_PREFIX.length());
        return switch (variant) {
            case "blocked" -> DashboardAgentMarks.ACCENT_CLASS_PREFIX + "blocked";
            case "working" -> DashboardAgentMarks.ACCENT_CLASS_PREFIX + "working";
            case "done" -> DashboardAgentMarks.ACCENT_CLASS_PREFIX + "done";
            default -> null;
        };
    }

    /** Only BLOCKED, WORKING and DONE carry a time-in-state; idle chips show the name only. */
    private static boolean hasTimedState(CodingAgentEntry entry) {
        CodingAgentState state = entry.state();
        return state == CodingAgentState.BLOCKED || state == CodingAgentState.WORKING || state == CodingAgentState.DONE;
    }

    private static String iconPathFor(NodeType type) {
        return switch (type) {
            case MAIN_WINDOW -> ICON_MAIN_WINDOW;
            case ENVIRONMENT -> ICON_ENVIRONMENT;
            case GROUP -> ICON_GROUP;
            case CONNECTION -> ICON_CONNECTION;
            case PANE -> ICON_PANE;
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

    private static String tooltipTextFor(DashboardItem item, ConnState state, CodingAgentEntry entry) {
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
        if (entry != null) {
            sb.append('\n').append(DashboardAgentMarks.tooltipLine(entry, System.currentTimeMillis(),
                    (key, args) -> I18n.get(key, args)));
            if (entry.detection() != null) {
                sb.append('\n').append(entry.detection().explain());
            }
        }
        return sb.toString();
    }

    /** korTTY's own AI-agent status aggregated across a terminal tab's widgets; NONE when unavailable. */
    private static AgentDashboardStatus.State legacyStateFor(TerminalTab tab) {
        if (tab == null || tab.getTerminalView() == null) {
            return AgentDashboardStatus.State.NONE;
        }
        try {
            AgentDashboardStatus.State state =
                    AgentDashboardStatus.aggregate(tab.getTerminalView().aggregateTerminalAgentRunCounts());
            return state != null ? state : AgentDashboardStatus.State.NONE;
        } catch (Exception e) {
            return AgentDashboardStatus.State.NONE;
        }
    }

    /**
     * Applies theme background and foreground colors to the dashboard and its controls.
     * Called from MainWindow when global theme settings change (or when dashboard is first shown).
     */
    public void applyTheme(String bgColor, String fgColor) {
        if (AppDesignStyleSupport.isCustomAppDesignActive()) {
            // Per-design stylesheets are authoritative; drop any inline overrides.
            menuIconColor = AppDesignStyleSupport.activeDimColor();
            setStyle(null);
            return;
        }
        // Override the panel's looked-up colors; all descendants (cells, buttons,
        // separators) resolve them via CSS, so no per-cell styling is needed.
        // hover/selected/border/dim must be derived too — the terminal.css defaults
        // are dark and would be unreadable on a light theme background.
        StringBuilder style = new StringBuilder();
        Boolean bgLight = isLightColor(bgColor);
        if (bgColor != null && !bgColor.isEmpty()) {
            style.append("-kortty-dash-bg: ").append(bgColor).append(";");
            if (bgLight != null) {
                style.append("-kortty-dash-hover: derive(").append(bgColor).append(", ").append(bgLight ? "-8%" : "15%").append(");");
                style.append("-kortty-dash-selected: derive(").append(bgColor).append(", ").append(bgLight ? "-14%" : "25%").append(");");
                style.append("-kortty-dash-border: derive(").append(bgColor).append(", ").append(bgLight ? "-25%" : "30%").append(");");
            }
        }
        Boolean fgLight = isLightColor(fgColor);
        if (fgColor != null && !fgColor.isEmpty()) {
            style.append("-kortty-dash-fg: ").append(fgColor).append(";");
            if (fgLight != null) {
                // dim = fg pulled toward the background.
                style.append("-kortty-dash-dim: derive(").append(fgColor).append(", ").append(fgLight ? "-30%" : "45%").append(");");
            }
        }
        if (bgLight != null) {
            // Agent chip/accent tokens are not derived from the theme: readable-on-light
            // values for light panels, the stylesheet's dark defaults otherwise.
            style.append(DashboardAgentMarks.agentTokensFor(bgLight));
        }
        menuIconColor = fgColor != null && !fgColor.isEmpty() ? fgColor : null;
        setStyle(style.length() == 0 ? null : style.toString());
    }

    /** True/false for a parseable CSS color's perceived lightness; null when unparseable. */
    private static Boolean isLightColor(String cssColor) {
        if (cssColor == null || cssColor.isEmpty()) {
            return null;
        }
        try {
            return javafx.scene.paint.Color.web(cssColor).getBrightness() > 0.55;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Refreshes the dashboard tree with current tabs, organized by groups.
     */
    public void refresh() {
        // The tree is rebuilt from scratch; carry over what the user arranged so a
        // background updateDashboard() doesn't wipe collapse state and selection.
        java.util.Map<String, Boolean> expansion = new java.util.HashMap<>();
        TerminalTab selectedTab = null;
        SithTermFxWidget selectedWidget = null;
        String selectedContainerKey = null;
        TreeItem<DashboardItem> oldRoot = treeView.getRoot();
        if (oldRoot != null) {
            collectExpansion(oldRoot, expansion);
            TreeItem<DashboardItem> selected = treeView.getSelectionModel().getSelectedItem();
            if (selected != null && selected.getValue() != null) {
                if (selected.getValue().getTerminalTab() != null) {
                    selectedTab = selected.getValue().getTerminalTab();
                    selectedWidget = selected.getValue().getWidget();
                } else {
                    selectedContainerKey = containerKey(selected.getValue());
                }
            }
        }

        TreeItem<DashboardItem> root = new TreeItem<>(
                DashboardItem.container(NodeType.MAIN_WINDOW, I18n.get("dashboard.root"), -1, -1, TabRollup.EMPTY));

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

        // Children of the window node are built first so its rollup can be summed.
        java.util.List<TreeItem<DashboardItem>> windowChildren = new java.util.ArrayList<>();
        TabRollup windowRollup = TabRollup.EMPTY;

        // Ungrouped tabs: cluster by credential environment. Tabs without a
        // resolvable environment sit directly under the main window node.
        java.util.Map<String, java.util.List<TerminalTab>> environments = new java.util.HashMap<>();
        for (TerminalTab terminalTab : ungroupedTabs) {
            String env = resolveEnvironmentName(terminalTab);
            if (env == null) {
                TreeItem<DashboardItem> connectionItem = connectionItem(terminalTab, expansion);
                windowChildren.add(connectionItem);
                windowRollup = sumRollups(windowRollup, connectionItem.getValue().getRollup());
            } else {
                environments.computeIfAbsent(env, k -> new java.util.ArrayList<>()).add(terminalTab);
            }
        }

        List<String> sortedEnvironments = new java.util.ArrayList<>(environments.keySet());
        sortedEnvironments.sort(String::compareToIgnoreCase);
        for (String envName : sortedEnvironments) {
            java.util.List<TerminalTab> envTabs = environments.get(envName);
            int envActive = 0;
            TabRollup envRollup = TabRollup.EMPTY;
            java.util.List<TreeItem<DashboardItem>> envChildren = new java.util.ArrayList<>();
            for (TerminalTab tab : envTabs) {
                if (stateOf(tab) == ConnState.CONNECTED) {
                    envActive++;
                }
                TreeItem<DashboardItem> connectionItem = connectionItem(tab, expansion);
                envChildren.add(connectionItem);
                envRollup = sumRollups(envRollup, connectionItem.getValue().getRollup());
            }
            TreeItem<DashboardItem> envItem = new TreeItem<>(
                    DashboardItem.container(NodeType.ENVIRONMENT, envName, envActive, envTabs.size(), envRollup));
            envItem.setExpanded(restoredExpansion(expansion, envItem));
            envItem.getChildren().addAll(envChildren);
            windowChildren.add(envItem);
            windowRollup = sumRollups(windowRollup, envRollup);
        }

        // Add grouped tabs, sorted by group name
        List<String> sortedGroups = new java.util.ArrayList<>(groups.keySet());
        sortedGroups.sort(String::compareToIgnoreCase);

        for (String groupName : sortedGroups) {
            java.util.List<TerminalTab> groupTabs = groups.get(groupName);

            // Count active tabs in group
            int groupActive = 0;
            TabRollup groupRollup = TabRollup.EMPTY;
            java.util.List<TreeItem<DashboardItem>> groupChildren = new java.util.ArrayList<>();
            for (TerminalTab tab : groupTabs) {
                if (stateOf(tab) == ConnState.CONNECTED) {
                    groupActive++;
                }
                TreeItem<DashboardItem> connectionItem = connectionItem(tab, expansion);
                groupChildren.add(connectionItem);
                groupRollup = sumRollups(groupRollup, connectionItem.getValue().getRollup());
            }

            TreeItem<DashboardItem> groupItem = new TreeItem<>(
                    DashboardItem.container(NodeType.GROUP, groupName, groupActive, groupTabs.size(), groupRollup));
            groupItem.setExpanded(restoredExpansion(expansion, groupItem));
            groupItem.getChildren().addAll(groupChildren);
            windowChildren.add(groupItem);
            windowRollup = sumRollups(windowRollup, groupRollup);
        }

        TreeItem<DashboardItem> windowItem = new TreeItem<>(
                DashboardItem.container(NodeType.MAIN_WINDOW, I18n.get("dashboard.mainWindowTitle"),
                        activeTabs, totalTabs, windowRollup));
        windowItem.setExpanded(restoredExpansion(expansion, windowItem));
        windowItem.getChildren().addAll(windowChildren);

        if (totalTabs > 0) {
            root.getChildren().add(windowItem);
        }

        root.setExpanded(true);
        // Keep the collapse/expand toggle in sync with manual disclosure clicks
        // (branch events bubble up to the root; the root is rebuilt each refresh).
        root.addEventHandler(TreeItem.<DashboardItem>branchExpandedEvent(), e -> updateCollapseAllButton());
        root.addEventHandler(TreeItem.<DashboardItem>branchCollapsedEvent(), e -> updateCollapseAllButton());
        treeView.setRoot(root);
        restoreSelection(root, selectedTab, selectedWidget, selectedContainerKey);
        updateCollapseAllButton();

        emptyBox.setVisible(totalTabs == 0);
        String footer = I18n.get("dashboard.footer", activeTabs, totalTabs);
        String agents = rollupTextOf(windowRollup);
        footerLabel.setText(agents.isEmpty() ? footer : I18n.get("dashboard.footerAgents", footer, agents));
        updatePanelWidth();
    }

    /**
     * The CONNECTION row of a tab, with one PANE leaf per pane ("Pane n · cwd tail") below it once
     * the tab is split.
     *
     * <p>Every pane of a split tab gets a row, whether or not an agent was detected in it. A split
     * tab where one pane runs an agent and the other does not is the ordinary case, and listing only
     * the agent's pane would leave the user looking for the half of their tab that the dashboard had
     * silently dropped. A pane with no agent simply carries no chip and no accent, and its row still
     * focuses that pane. An unsplit tab keeps no children at all: its connection row already <em>is</em>
     * the pane.
     */
    private TreeItem<DashboardItem> connectionItem(TerminalTab terminalTab, java.util.Map<String, Boolean> expansion) {
        String viewId = terminalViewIdOf(terminalTab);
        TabRollup rollup = rollupFor(viewId);
        List<CodingAgentEntry> entries = entriesFor(viewId);
        CodingAgentEntry topEntry = entries.isEmpty() ? null : entries.get(0);
        TreeItem<DashboardItem> item = new TreeItem<>(
                DashboardItem.connection(getServerDisplayName(terminalTab), terminalTab, topEntry, rollup));
        TerminalView view = terminalTab.getTerminalView();
        List<SithTermFxWidget> widgets = view == null ? List.of() : orderedWidgets(view);
        // More than one pane is involved when the tab is really split, and also when the registry
        // knows about more panes than this view can hand out widgets for — a pane whose widget has
        // already gone, or a harness that registers panes without backing them. Keying on the widget
        // count alone would drop the rows the registry does know about.
        if (widgets.size() >= 2 || entries.size() >= 2) {
            for (int i = 0; i < widgets.size(); i++) {
                SithTermFxWidget widget = widgets.get(i);
                if (widget == null) {
                    continue;
                }
                PaneRef pane = paneRefFor(view, widget);
                CodingAgentEntry entry = entryFor(pane);
                String name = I18n.get("dashboard.paneTitle", i + 1);
                String tail = cwdTailFor(pane);
                if (tail != null) {
                    name = name + CodingAgentGlyphs.SEPARATOR + tail;
                }
                item.getChildren().add(new TreeItem<>(DashboardItem.pane(name, terminalTab, widget, entry)));
            }
            if (!item.getChildren().isEmpty()) {
                item.setExpanded(restoredExpansion(expansion, item));
            }
        }
        return item;
    }

    /** Stable identity of a container row (or a connection row with pane children) across tree rebuilds. */
    private static String containerKey(DashboardItem item) {
        if (item.getType() == NodeType.CONNECTION) {
            return "CONNECTION|" + item.getTerminalViewId();
        }
        return item.getType() + "|" + item.getDisplayName();
    }

    private void collectExpansion(TreeItem<DashboardItem> item, java.util.Map<String, Boolean> into) {
        DashboardItem value = item.getValue();
        if (value != null && !item.getChildren().isEmpty()
                && (value.getTerminalTab() == null || value.getType() == NodeType.CONNECTION)) {
            into.put(containerKey(value), item.isExpanded());
        }
        for (TreeItem<DashboardItem> child : item.getChildren()) {
            collectExpansion(child, into);
        }
    }

    /** Previous expansion of the same container, or expanded for nodes new to the tree. */
    private boolean restoredExpansion(java.util.Map<String, Boolean> expansion, TreeItem<DashboardItem> item) {
        Boolean was = expansion.get(containerKey(item.getValue()));
        return was == null || was;
    }

    private void restoreSelection(TreeItem<DashboardItem> root, TerminalTab selectedTab, SithTermFxWidget selectedWidget,
                                  String containerKey) {
        if (selectedTab == null && containerKey == null) {
            return;
        }
        TreeItem<DashboardItem> match = findItem(root, selectedTab, selectedWidget, containerKey);
        if (match == null && selectedWidget != null) {
            // The pane row vanished (agents left); fall back to its connection row.
            match = findItem(root, selectedTab, null, null);
        }
        if (match != null) {
            treeView.getSelectionModel().select(match);
        }
    }

    /**
     * Finds the row of {@code tab} (its CONNECTION row when {@code widget} is null, else the PANE row
     * of that widget) or the container with {@code containerKey}; parents are visited before children.
     */
    private TreeItem<DashboardItem> findItem(TreeItem<DashboardItem> item, TerminalTab tab, SithTermFxWidget widget,
                                             String containerKey) {
        DashboardItem value = item.getValue();
        if (value != null) {
            if (tab != null && value.getTerminalTab() == tab && value.getWidget() == widget) {
                return item;
            }
            if (tab == null && containerKey != null && value.getTerminalTab() == null
                    && containerKey.equals(containerKey(value))) {
                return item;
            }
        }
        for (TreeItem<DashboardItem> child : item.getChildren()) {
            TreeItem<DashboardItem> found = findItem(child, tab, widget, containerKey);
            if (found != null) {
                return found;
            }
        }
        return null;
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
     * Dashboard tree item. Immutable; a new instance per refresh.
     */
    private static class DashboardItem {
        private final NodeType type;
        private final String displayName;
        private final TerminalTab terminalTab;
        /** Connected/total children of a container node; -1 when no count is shown. */
        private final int activeCount;
        private final int totalCount;
        /** Container rows: summed over their tabs; CONNECTION rows: the registry's tab rollup. */
        private final TabRollup rollup;
        /** CONNECTION: the most urgent pane entry or null; PANE: its entry; containers: null. */
        private final CodingAgentEntry entry;
        /** PANE rows only. */
        private final SithTermFxWidget widget;
        /** TerminalView id of CONNECTION/PANE rows (containerKey of a connection with pane children). */
        private final String terminalViewId;

        private DashboardItem(NodeType type, String displayName, TerminalTab terminalTab,
                              int activeCount, int totalCount, TabRollup rollup, CodingAgentEntry entry,
                              SithTermFxWidget widget, String terminalViewId) {
            this.type = type;
            this.displayName = displayName;
            this.terminalTab = terminalTab;
            this.activeCount = activeCount;
            this.totalCount = totalCount;
            this.rollup = rollup != null ? rollup : TabRollup.EMPTY;
            this.entry = entry;
            this.widget = widget;
            this.terminalViewId = terminalViewId;
        }

        static DashboardItem container(NodeType type, String displayName, int activeCount, int totalCount,
                                       TabRollup rollup) {
            return new DashboardItem(type, displayName, null, activeCount, totalCount, rollup, null, null, null);
        }

        static DashboardItem connection(String displayName, TerminalTab terminalTab, CodingAgentEntry entry,
                                        TabRollup rollup) {
            return new DashboardItem(NodeType.CONNECTION, displayName, terminalTab, -1, -1, rollup, entry, null,
                    terminalViewIdOf(terminalTab));
        }

        static DashboardItem pane(String displayName, TerminalTab terminalTab, SithTermFxWidget widget,
                                  CodingAgentEntry entry) {
            return new DashboardItem(NodeType.PANE, displayName, terminalTab, -1, -1, TabRollup.EMPTY, entry, widget,
                    terminalViewIdOf(terminalTab));
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

        public TabRollup getRollup() {
            return rollup;
        }

        public CodingAgentEntry getEntry() {
            return entry;
        }

        public SithTermFxWidget getWidget() {
            return widget;
        }

        public String getTerminalViewId() {
            return terminalViewId;
        }
    }
}
