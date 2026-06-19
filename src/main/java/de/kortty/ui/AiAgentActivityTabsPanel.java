package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.TerminalAgentService;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TerminalAgentModels;
import de.kortty.model.Theme;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Label;
import javafx.scene.control.ProgressIndicator;
<<<<<<< HEAD
=======
import javafx.scene.control.ScrollPane;
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.Window;
import javafx.util.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * Tabbed wrapper that hosts one {@link AiAgentActivityPanel} per concurrent terminal-agent run.
 * Owns the overlay chrome (resize handle, collapse and close buttons) so the embedded panels can
 * focus on rendering a single run. Finished runs stay as closable tabs until the user closes them.
 */
public class AiAgentActivityTabsPanel extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(AiAgentActivityTabsPanel.class);
    private static final double COLLAPSED_PANEL_HEIGHT = 70.0;
    private static final double MIN_PANEL_HEIGHT = 130.0;
    private static final double DEFAULT_PANEL_HEIGHT = 230.0;
    private static final double MIN_TERMINAL_HEIGHT = 100.0;
    private static final double DEFAULT_MAX_PANEL_HEIGHT = 720.0;
    private static final int TAB_TITLE_MAX_CHARS = 24;

<<<<<<< HEAD
=======
    /** How concurrent runs are presented: as inner tabs (bottom dock) or stacked sections (side dock). */
    public enum LayoutMode { BOTTOM_TABS, SIDE_STACKED }

>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
    private final Region resizeHandle;
    private final Button collapseButton;
    private final Button closeButton;
    private final TabPane tabPane;
<<<<<<< HEAD
=======
    // Stacked container used in SIDE_STACKED mode (one section per run, vertically scrollable).
    private final ScrollPane stackedScroll;
    private final VBox stackedBox;
    private LayoutMode layoutMode = LayoutMode.BOTTOM_TABS;
    private boolean sideDocked = false;
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
    // Compact status bar shown while the panel is collapsed, so the run stays visible and controllable.
    private final Label collapsedStatusLabel;
    private final ProgressIndicator collapsedWorkingIndicator;
    private final Button collapsedPauseButton;
    private final Button collapsedCancelButton;
    private final HBox collapsedStatusBar;
    private final Timeline statusRefreshTimer;
    // Concurrent maps: written on the FX thread (beginRun/removeRun) but read from the agent worker
    // thread in requestApproval/requestPassword/cancelAllRuns, so plain HashMaps would be unsafe.
    private final Map<String, AiAgentActivityPanel> runPanels = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Tab> runTabs = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Runnable> runCancels = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, String> runPrompts = new java.util.concurrent.ConcurrentHashMap<>();
<<<<<<< HEAD
=======
    // SIDE_STACKED only (FX thread): the section container + its title label per run, and run order.
    private final Map<String, VBox> runSections = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Label> runSectionTitles = new java.util.concurrent.ConcurrentHashMap<>();
    private final List<String> runOrder = new ArrayList<>();
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525

    private Theme currentTheme;
    private boolean panelCollapsed;
    private double expandedPanelHeight = DEFAULT_PANEL_HEIGHT;
    private double panelResizeStartY;
    private double panelResizeStartHeight;

    public AiAgentActivityTabsPanel() {
        getStyleClass().add("ai-agent-activity-panel");
        setSpacing(4);
        setPadding(new Insets(4, 4, 4, 4));
        setVisible(false);
        setManaged(false);
        setPrefHeight(DEFAULT_PANEL_HEIGHT);
        setMinHeight(MIN_PANEL_HEIGHT);
        setMaxHeight(Double.MAX_VALUE);

        resizeHandle = new Region();
        resizeHandle.getStyleClass().add("ai-agent-resize-handle");
        resizeHandle.setMinHeight(6);
        resizeHandle.setPrefHeight(6);
        resizeHandle.setMaxHeight(6);
        resizeHandle.setCursor(Cursor.V_RESIZE);
        resizeHandle.setOnMousePressed(event -> {
            panelResizeStartY = event.getSceneY();
            panelResizeStartHeight = currentPanelHeight();
            event.consume();
        });
        resizeHandle.setOnMouseDragged(event -> {
            double requestedHeight = panelResizeStartHeight - (event.getSceneY() - panelResizeStartY);
            setUserPanelHeight(requestedHeight);
            event.consume();
        });
        resizeHandle.setOnMouseReleased(event -> {
            persistLayoutSettingsIfEnabled();
            event.consume();
        });

        collapseButton = new Button("▼");
        collapseButton.getStyleClass().addAll("ai-agent-font-button", "ai-agent-collapse-button");
        collapseButton.setFocusTraversable(false);
        collapseButton.setMinWidth(30);
        collapseButton.setPrefWidth(34);
        collapseButton.setTooltip(new Tooltip(I18n.get("ai.agent.control.collapsePanel")));
        collapseButton.setOnAction(event -> toggleCollapsed());

        closeButton = new Button("x");
        closeButton.getStyleClass().add("ai-agent-close-button");
        closeButton.setFocusTraversable(false);
        closeButton.setTooltip(new Tooltip(I18n.get("dialog.close")));
        closeButton.setOnAction(event -> hideIfNoRunningRuns());

        // Compact status + controls shown when the panel is collapsed (so the run stays visible and
        // steerable, and pending user input is obvious without expanding).
        collapsedStatusLabel = new Label();
        collapsedStatusLabel.getStyleClass().add("ai-agent-collapsed-status");
        collapsedStatusLabel.setMaxWidth(Double.MAX_VALUE);
        collapsedStatusLabel.setOnMouseClicked(event -> setPanelCollapsed(false));
        HBox.setHgrow(collapsedStatusLabel, Priority.ALWAYS);
        // A small spinner makes it unmistakable that the agent is still actively working (shown only
        // while a run is executing — not when paused, awaiting input, or finished).
        collapsedWorkingIndicator = new ProgressIndicator();
        collapsedWorkingIndicator.getStyleClass().add("ai-agent-working-indicator");
        collapsedWorkingIndicator.setPrefSize(16, 16);
        collapsedWorkingIndicator.setMinSize(16, 16);
        collapsedWorkingIndicator.setMaxSize(16, 16);
        collapsedWorkingIndicator.setVisible(false);
        collapsedWorkingIndicator.setManaged(false);
        collapsedWorkingIndicator.setMouseTransparent(true);
        collapsedPauseButton = new Button("⏸");
        collapsedPauseButton.getStyleClass().addAll("ai-agent-font-button", "ai-agent-pause-button");
        collapsedPauseButton.setFocusTraversable(false);
        collapsedPauseButton.setTooltip(new Tooltip(I18n.get("ai.agent.control.pauseRun")));
        collapsedPauseButton.setOnAction(event -> toggleSelectedRunPause());
        collapsedCancelButton = new Button("■");
        collapsedCancelButton.getStyleClass().addAll("ai-agent-font-button", "ai-agent-cancel-button");
        collapsedCancelButton.setFocusTraversable(false);
        collapsedCancelButton.setTooltip(new Tooltip(I18n.get("dialog.cancel")));
        collapsedCancelButton.setOnAction(event -> cancelSelectedRun());

        collapsedStatusBar = new HBox(8, collapsedWorkingIndicator, collapsedStatusLabel,
            collapsedPauseButton, collapsedCancelButton);
        collapsedStatusBar.getStyleClass().add("ai-agent-collapsed-bar");
        collapsedStatusBar.setAlignment(Pos.CENTER_LEFT);
        collapsedStatusBar.setPadding(new Insets(0, 4, 0, 4));

        HBox chrome = new HBox(8, collapseButton, closeButton);
        chrome.getStyleClass().add("ai-agent-header-actions");
        chrome.setMinWidth(Region.USE_PREF_SIZE);

        tabPane = new TabPane();
        tabPane.getStyleClass().add("ai-agent-tab-pane");
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

<<<<<<< HEAD
=======
        // Stacked container for SIDE_STACKED mode: each run is a vertical section in a scroll pane.
        stackedBox = new VBox(6);
        stackedBox.getStyleClass().add("ai-agent-stacked-box");
        stackedBox.setFillWidth(true);
        stackedBox.setPadding(new Insets(2));
        stackedScroll = new ScrollPane(stackedBox);
        stackedScroll.getStyleClass().add("ai-agent-stacked-scroll");
        stackedScroll.setFitToWidth(true);
        stackedScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        stackedScroll.setVisible(false);
        stackedScroll.setManaged(false);
        VBox.setVgrow(stackedScroll, Priority.ALWAYS);

>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
        statusRefreshTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshRunStatus()));
        statusRefreshTimer.setCycleCount(Animation.INDEFINITE);
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> refreshRunStatus());

        // The collapsed status bar is its own row (shown only when collapsed); the chrome row keeps
        // its original collapse/close layout untouched.
<<<<<<< HEAD
        getChildren().addAll(resizeHandle, chrome, collapsedStatusBar, tabPane);
=======
        getChildren().addAll(resizeHandle, chrome, collapsedStatusBar, tabPane, stackedScroll);
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
        loadPersistedLayoutSettings();
        applyCollapsedState();
    }

    public void beginRun(
        String runId,
        String prompt,
        Runnable cancel,
        Consumer<Boolean> pause,
        Runnable reload,
        AiAgentActivityPanel.RunMetadata metadata) {
        if (runId == null) {
            return;
        }
        runOnFx(() -> {
            AiAgentActivityPanel panel = new AiAgentActivityPanel();
            panel.setEmbedded(true);
            if (currentTheme != null) {
                panel.applyTheme(currentTheme);
            }
            panel.setPauseHandler(pause);
            panel.beginRun(prompt, cancel, reload, metadata);

<<<<<<< HEAD
            Tab tab = new Tab(truncateTabTitle(prompt));
            tab.setTooltip(new Tooltip(prompt != null && !prompt.isBlank() ? prompt : I18n.get("ai.agent.title")));
            tab.setContent(panel);
            tab.setClosable(true);
            tab.setOnCloseRequest(event -> handleTabCloseRequest(runId, event));

            runPanels.put(runId, panel);
            runTabs.put(runId, tab);
=======
            runPanels.put(runId, panel);
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
            runPrompts.put(runId, prompt != null ? prompt : "");
            if (cancel != null) {
                runCancels.put(runId, cancel);
            }
<<<<<<< HEAD
            tabPane.getTabs().add(tab);
            tabPane.getSelectionModel().select(tab);
=======
            if (!runOrder.contains(runId)) {
                runOrder.add(runId);
            }
            addRunToContainer(runId, prompt, panel);
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
            if (statusRefreshTimer.getStatus() != Animation.Status.RUNNING) {
                statusRefreshTimer.playFromStart();
            }
            showWrapper();
            // "Keep collapsed" is now an overlay-level preference: start the wrapper collapsed (the
            // compact status bar still shows the run and lets the user control it / see input prompts).
            GlobalSettings settings = globalSettings();
            if (settings != null && settings.isTerminalAgentPanelKeepCollapsed() && !panelCollapsed) {
                setPanelCollapsed(true);
            }
            refreshRunStatus();
        });
    }

    public void publishActivity(String runId, TerminalAgentModels.AgentActivity activity) {
        runOnFx(() -> {
            AiAgentActivityPanel panel = runPanels.get(runId);
            if (panel != null) {
                panel.publishActivity(activity);
            }
        });
    }

    public TerminalAgentService.ApprovalDecision requestApproval(String runId, TerminalAgentModels.Approval approval) {
        AiAgentActivityPanel panel = runPanels.get(runId);
        if (panel == null) {
            return TerminalAgentService.ApprovalDecision.CANCEL;
        }
        Platform.runLater(() -> surfaceRunNeedingInput(runId));
        return panel.requestApproval(approval);
    }

    public TerminalAgentModels.PasswordResponse requestPassword(String runId, TerminalAgentModels.PasswordRequest request) {
        AiAgentActivityPanel panel = runPanels.get(runId);
        if (panel == null) {
            return null;
        }
        Platform.runLater(() -> surfaceRunNeedingInput(runId));
        return panel.requestPassword(request);
    }

    /** Brings a run that needs user input into view: select its tab and expand the panel if collapsed. */
    private void surfaceRunNeedingInput(String runId) {
        selectTab(runId);
<<<<<<< HEAD
        if (panelCollapsed) {
=======
        if (!sideDocked && panelCollapsed) {
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
            setPanelCollapsed(false);
        }
        refreshRunStatus();
    }

    public void finishRun(String runId) {
        runOnFx(() -> {
            AiAgentActivityPanel panel = runPanels.get(runId);
            if (panel == null) {
                return;
            }
            panel.finishRun();
            refreshRunStatus();
            stopStatusTimerIfIdle();
        });
    }

    public void toggleThinkingDetails(String runId) {
        runOnFx(() -> {
            AiAgentActivityPanel panel = runPanels.get(runId);
            if (panel != null) {
                panel.toggleThinkingDetails();
            }
        });
    }

    public void applyTheme(Theme theme) {
        runOnFx(() -> {
            currentTheme = theme;
            for (AiAgentActivityPanel panel : runPanels.values()) {
                if (panel != null) {
                    panel.applyTheme(theme);
                }
            }
        });
    }

    /** The runId of the currently selected tab, or {@code null} when nothing is selected. */
    public String selectedRunId() {
        Tab selected = tabPane.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return null;
        }
        for (Map.Entry<String, Tab> entry : runTabs.entrySet()) {
            if (entry.getValue() == selected) {
                return entry.getKey();
            }
        }
        return null;
    }

    public int runCount() {
        return runPanels.size();
    }

    public boolean hasActiveRuns() {
        for (AiAgentActivityPanel panel : runPanels.values()) {
            if (panel != null && panel.isRunning()) {
                return true;
            }
        }
        return false;
    }

    /** Cancels every run hosted in this wrapper (used when the terminal/split widget is closed). */
    public void cancelAllRuns() {
        for (Runnable cancel : new ArrayList<>(runCancels.values())) {
            if (cancel != null) {
                try {
                    cancel.run();
                } catch (Exception e) {
                    logger.debug("Failed to cancel terminal agent run: {}", e.getMessage());
                }
            }
        }
    }

    private void selectTab(String runId) {
<<<<<<< HEAD
=======
        if (layoutMode == LayoutMode.SIDE_STACKED) {
            scrollToSection(runId);
            return;
        }
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
        Tab tab = runTabs.get(runId);
        if (tab != null) {
            tabPane.getSelectionModel().select(tab);
        }
    }

    private void handleTabCloseRequest(String runId, javafx.event.Event event) {
        AiAgentActivityPanel panel = runPanels.get(runId);
        if (panel != null && panel.isRunning()) {
            if (!confirmCloseRunningRun()) {
                event.consume();
                return;
            }
            Runnable cancel = runCancels.get(runId);
            if (cancel != null) {
                try {
                    cancel.run();
                } catch (Exception e) {
                    logger.debug("Failed to cancel terminal agent run on tab close: {}", e.getMessage());
                }
            }
        }
        removeRun(runId);
    }

    private boolean confirmCloseRunningRun() {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.get("ai.agent.title"));
        alert.setHeaderText(null);
        alert.setContentText(I18n.get("ai.agent.tab.close.confirmRunning"));
        applyThemeToDialog(alert);
        Window owner = getScene() != null ? getScene().getWindow() : null;
        if (owner != null) {
            alert.initOwner(owner);
        }
        Optional<ButtonType> result = alert.showAndWait();
        return result.isPresent() && result.get() == ButtonType.OK;
    }

    private void applyThemeToDialog(Alert alert) {
        String stylesheetUrl = ThemeCssSupport.getAgentActivityStylesheetUrl(currentTheme);
        if (stylesheetUrl != null && alert.getDialogPane() != null) {
            alert.getDialogPane().getStylesheets().add(stylesheetUrl);
        }
    }

    private void removeRun(String runId) {
        AiAgentActivityPanel panel = runPanels.get(runId);
        if (panel != null) {
            // Unblock any worker thread parked on this run's approval/password future before discarding.
            panel.cancelPendingPrompts();
        }
        runPanels.remove(runId);
        runCancels.remove(runId);
        runPrompts.remove(runId);
<<<<<<< HEAD
        Tab tab = runTabs.remove(runId);
        if (tab != null) {
            tabPane.getTabs().remove(tab);
        }
        if (tabPane.getTabs().isEmpty()) {
=======
        runOrder.remove(runId);
        removeRunFromContainer(runId);
        // When docked to the side the (possibly empty) panel stays visible as its terminal's tab.
        if (runPanels.isEmpty() && !sideDocked) {
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
            setVisible(false);
            setManaged(false);
        }
        stopStatusTimerIfIdle();
        refreshRunStatus();
    }

<<<<<<< HEAD
=======
    private void removeRunFromContainer(String runId) {
        Tab tab = runTabs.remove(runId);
        if (tab != null) {
            tab.setContent(null);
            tabPane.getTabs().remove(tab);
        }
        VBox section = runSections.remove(runId);
        runSectionTitles.remove(runId);
        if (section != null) {
            section.getChildren().clear();
            stackedBox.getChildren().remove(section);
        }
    }

    // ----------------------------------------------------------------- layout mode (tabs vs stacked)

    /** Switches between inner-tabs and stacked-sections presentation, migrating existing runs. */
    public void setLayoutMode(LayoutMode mode) {
        runOnFx(() -> {
            if (mode == null || mode == layoutMode) {
                return;
            }
            // Detach all runs from the current container, switch mode, then re-add to the new one.
            List<String> order = new ArrayList<>(runOrder);
            for (String runId : order) {
                removeRunFromContainer(runId);
            }
            layoutMode = mode;
            for (String runId : order) {
                AiAgentActivityPanel panel = runPanels.get(runId);
                if (panel != null) {
                    addRunToContainer(runId, runPrompts.get(runId), panel);
                }
            }
            refreshRunStatus();
        });
    }

    /**
     * Docks (true) the panel into a side container: stacked layout, no bottom-style chrome/collapse,
     * always expanded and grown to fill. Undocking (false) restores the bottom tabs presentation.
     */
    public void setSideDocked(boolean docked) {
        runOnFx(() -> {
            this.sideDocked = docked;
            setLayoutMode(docked ? LayoutMode.SIDE_STACKED : LayoutMode.BOTTOM_TABS);
            if (docked) {
                panelCollapsed = false;
                setVisible(true);
                setManaged(true);
            }
            applyCollapsedState();
        });
    }

    private void addRunToContainer(String runId, String prompt, AiAgentActivityPanel panel) {
        if (layoutMode == LayoutMode.SIDE_STACKED) {
            addRunSection(runId, prompt, panel);
            scrollToSection(runId);
        } else {
            Tab tab = createRunTab(runId, prompt, panel);
            runTabs.put(runId, tab);
            tabPane.getTabs().add(tab);
            tabPane.getSelectionModel().select(tab);
        }
    }

    private Tab createRunTab(String runId, String prompt, AiAgentActivityPanel panel) {
        // Leaving stacked mode: clear any stacked-section sizing so the panel fills the tab again.
        panel.setPrefHeight(Region.USE_COMPUTED_SIZE);
        panel.setMinHeight(Region.USE_COMPUTED_SIZE);
        Tab tab = new Tab(truncateTabTitle(prompt));
        tab.setTooltip(new Tooltip(prompt != null && !prompt.isBlank() ? prompt : I18n.get("ai.agent.title")));
        tab.setContent(panel);
        tab.setClosable(true);
        tab.setOnCloseRequest(event -> handleTabCloseRequest(runId, event));
        return tab;
    }

    private void addRunSection(String runId, String prompt, AiAgentActivityPanel panel) {
        Label title = new Label(truncateTabTitle(prompt));
        title.getStyleClass().add("ai-agent-stacked-title");
        title.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(title, Priority.ALWAYS);
        Button close = new Button("\u2715");
        close.getStyleClass().add("ai-agent-stacked-close");
        close.setFocusTraversable(false);
        close.setTooltip(new Tooltip(I18n.get("dialog.close")));
        close.setOnAction(event -> {
            event.consume();
            handleRunCloseRequest(runId);
        });
        HBox header = new HBox(6, title, close);
        header.getStyleClass().add("ai-agent-stacked-header");
        header.setAlignment(Pos.CENTER_LEFT);
        // A bounded panel height lets several runs stack; each panel scrolls its own feed internally.
        panel.setPrefHeight(240);
        panel.setMinHeight(140);
        VBox section = new VBox(2, header, panel);
        section.getStyleClass().add("ai-agent-stacked-section");
        VBox.setVgrow(panel, Priority.ALWAYS);
        runSections.put(runId, section);
        runSectionTitles.put(runId, title);
        stackedBox.getChildren().add(section);
    }

    private void scrollToSection(String runId) {
        VBox section = runSections.get(runId);
        if (section == null) {
            return;
        }
        Platform.runLater(() -> {
            double contentHeight = stackedBox.getHeight();
            double viewportHeight = stackedScroll.getViewportBounds() != null
                ? stackedScroll.getViewportBounds().getHeight() : 0;
            if (contentHeight > viewportHeight && contentHeight - viewportHeight > 0) {
                double y = section.getBoundsInParent().getMinY();
                stackedScroll.setVvalue(Math.max(0, Math.min(1, y / (contentHeight - viewportHeight))));
            }
        });
    }

    private void handleRunCloseRequest(String runId) {
        AiAgentActivityPanel panel = runPanels.get(runId);
        if (panel != null && panel.isRunning()) {
            if (!confirmCloseRunningRun()) {
                return;
            }
            Runnable cancel = runCancels.get(runId);
            if (cancel != null) {
                try {
                    cancel.run();
                } catch (Exception e) {
                    logger.debug("Failed to cancel terminal agent run on section close: {}", e.getMessage());
                }
            }
        }
        removeRun(runId);
    }

>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
    private void showWrapper() {
        setVisible(true);
        setManaged(true);
        applyCollapsedState();
    }

    private void hideIfNoRunningRuns() {
        if (hasActiveRuns()) {
            return;
        }
        setVisible(false);
        setManaged(false);
    }

    static String truncateTabTitle(String prompt) {
        String base = prompt == null || prompt.isBlank() ? I18n.get("ai.agent.title") : prompt.trim();
        base = base.replaceAll("\\s+", " ");
        if (base.length() <= TAB_TITLE_MAX_CHARS) {
            return base;
        }
        return base.substring(0, TAB_TITLE_MAX_CHARS - 1).trim() + "…";
    }

    private double currentPanelHeight() {
        double height = getHeight();
        if (height > 0.0) {
            return height;
        }
        double prefHeight = getPrefHeight();
        return prefHeight > 0.0 ? prefHeight : DEFAULT_PANEL_HEIGHT;
    }

    private void setUserPanelHeight(double requestedHeight) {
        double clampedHeight = clampPanelHeight(requestedHeight, parentHeight());
        expandedPanelHeight = clampedHeight;
        setPrefHeight(clampedHeight);
        setMinHeight(MIN_PANEL_HEIGHT);
        requestContainerLayout();
    }

    private double parentHeight() {
        if (getParent() instanceof Region region) {
            return region.getHeight();
        }
        return 0.0;
    }

    private void requestContainerLayout() {
        if (getParent() != null) {
            getParent().requestLayout();
        }
        requestLayout();
    }

    static double clampPanelHeight(double requestedHeight, double parentHeight) {
        double maxHeight = DEFAULT_MAX_PANEL_HEIGHT;
        if (Double.isFinite(parentHeight) && parentHeight > 0.0) {
            maxHeight = Math.max(MIN_PANEL_HEIGHT, parentHeight - MIN_TERMINAL_HEIGHT);
        }
        return Math.max(MIN_PANEL_HEIGHT, Math.min(requestedHeight, maxHeight));
    }

    private void toggleCollapsed() {
        setPanelCollapsed(!panelCollapsed);
    }

    private void setPanelCollapsed(boolean collapsed) {
        if (panelCollapsed == collapsed) {
            return;
        }
        if (collapsed) {
            expandedPanelHeight = Math.max(MIN_PANEL_HEIGHT, currentPanelHeight());
        }
        panelCollapsed = collapsed;
        applyCollapsedState();
    }

    private void applyCollapsedState() {
<<<<<<< HEAD
=======
        if (sideDocked) {
            // Docked to the side: always expanded, stacked container fills, no bottom-style chrome.
            resizeHandle.setVisible(false);
            resizeHandle.setManaged(false);
            collapseButton.setVisible(false);
            collapseButton.setManaged(false);
            collapsedStatusBar.setVisible(false);
            collapsedStatusBar.setManaged(false);
            tabPane.setVisible(false);
            tabPane.setManaged(false);
            stackedScroll.setVisible(true);
            stackedScroll.setManaged(true);
            setMinHeight(0);
            setPrefHeight(Region.USE_COMPUTED_SIZE);
            setMaxHeight(Double.MAX_VALUE);
            refreshRunStatus();
            return;
        }
        collapseButton.setVisible(true);
        collapseButton.setManaged(true);
        stackedScroll.setVisible(false);
        stackedScroll.setManaged(false);
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
        boolean expanded = !panelCollapsed;
        resizeHandle.setVisible(expanded);
        resizeHandle.setManaged(expanded);
        tabPane.setVisible(expanded);
        tabPane.setManaged(expanded);
        // The compact status bar only shows while collapsed; expanded, each tab's own header carries it.
        collapsedStatusBar.setVisible(panelCollapsed);
        collapsedStatusBar.setManaged(panelCollapsed);
        collapseButton.setText(panelCollapsed ? "▲" : "▼");
        collapseButton.setTooltip(new Tooltip(
            panelCollapsed
                ? I18n.get("ai.agent.control.expandPanel")
                : I18n.get("ai.agent.control.collapsePanel")));
        if (panelCollapsed) {
            setMinHeight(COLLAPSED_PANEL_HEIGHT);
            setPrefHeight(COLLAPSED_PANEL_HEIGHT);
        } else {
            setMinHeight(MIN_PANEL_HEIGHT);
            setPrefHeight(clampPanelHeight(expandedPanelHeight, parentHeight()));
        }
        refreshRunStatus();
        requestContainerLayout();
    }

    // ----------------------------------------------------------------- run status / collapsed bar

    /** Refreshes the collapsed status bar and decorates every tab title with its run's current state. */
    private void refreshRunStatus() {
<<<<<<< HEAD
        // Decorate each tab so input-needed (✋) and paused (⏸) runs are obvious even in the background.
        for (Map.Entry<String, Tab> entry : runTabs.entrySet()) {
            String runId = entry.getKey();
            Tab tab = entry.getValue();
            AiAgentActivityPanel panel = runPanels.get(runId);
            String prefix = statePrefix(panel);
            tab.setText(prefix + truncateTabTitle(runPrompts.get(runId)));
=======
        // Decorate each run's tab (bottom) or section title (side) so input-needed (✋) and paused (⏸)
        // runs are obvious even in the background.
        for (String runId : runPanels.keySet()) {
            AiAgentActivityPanel panel = runPanels.get(runId);
            String label = statePrefix(panel) + truncateTabTitle(runPrompts.get(runId));
            Tab tab = runTabs.get(runId);
            if (tab != null) {
                tab.setText(label);
            }
            Label sectionTitle = runSectionTitles.get(runId);
            if (sectionTitle != null) {
                sectionTitle.setText(label);
            }
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
        }
        // Collapsed bar focuses the same run its controls act on (see focusedRunId): awaiting-input
        // first, then any actively-working run (so ongoing work is obvious even if the selected tab is
        // already finished), then the selected run, then any run.
        String focusRunId = focusedRunId();
        AiAgentActivityPanel panel = focusRunId != null ? runPanels.get(focusRunId) : null;
        boolean awaitingInput = panel != null && panel.isAwaitingInput();
        collapsedStatusLabel.setText(statusText(focusRunId, panel));
        // Make a pending sudo/approval prompt stand out: themed accent colour (reused marker class) + bold.
        collapsedStatusLabel.getStyleClass().remove("ai-agent-marker-question");
        if (awaitingInput) {
            collapsedStatusLabel.getStyleClass().add("ai-agent-marker-question");
        }
        collapsedStatusLabel.setStyle(awaitingInput ? "-fx-font-weight: bold;" : "");
        // Spin only while collapsed AND a run is actively executing (gating on collapsed stops the
        // indeterminate animation from running off-screen while the panel is expanded).
        boolean showSpinner = panelCollapsed && runCounts()[1] > 0;
        collapsedWorkingIndicator.setVisible(showSpinner);
        collapsedWorkingIndicator.setManaged(showSpinner);
        boolean running = panel != null && panel.isRunning();
        collapsedPauseButton.setDisable(!running || awaitingInput);
        collapsedPauseButton.setText(panel != null && panel.isPaused() ? "▶" : "⏸");
        collapsedPauseButton.setTooltip(new Tooltip(
            panel != null && panel.isPaused()
                ? I18n.get("ai.agent.control.resumeRun")
                : I18n.get("ai.agent.control.pauseRun")));
        collapsedCancelButton.setDisable(!running);
    }

    private String statusText(String runId, AiAgentActivityPanel panel) {
        if (runId == null || panel == null) {
            return I18n.get("ai.agent.collapsed.idle");
        }
        String prompt = truncateTabTitle(runPrompts.get(runId));
        String state;
        if (panel.isAwaitingInput()) {
            state = I18n.get("ai.agent.collapsed.inputRequired");
        } else if (panel.isPaused()) {
            state = I18n.get("ai.agent.collapsed.paused");
        } else if (panel.isRunning()) {
            state = I18n.get("ai.agent.collapsed.running");
        } else {
            state = I18n.get("ai.agent.collapsed.done");
        }
<<<<<<< HEAD
        if (runTabs.size() > 1) {
=======
        if (runPanels.size() > 1) {
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
            // Multi-run: show the focused run's state + prompt, plus how many are active vs finished.
            int[] c = runCounts();
            return I18n.get("ai.agent.collapsed.statusMulti", state, prompt,
                formatRunBreakdown(c[0], c[1], c[2], c[3]));
        }
        return I18n.get("ai.agent.collapsed.status", state, prompt);
    }

    /** Counts of runs by state: [awaitingInput, working, paused, done]. */
<<<<<<< HEAD
    private int[] runCounts() {
=======
    public int[] runCounts() {
>>>>>>> 4dd85dbfeb5be070d89796c0d57ef9c10b930525
        int input = 0;
        int working = 0;
        int paused = 0;
        int done = 0;
        for (AiAgentActivityPanel panel : runPanels.values()) {
            if (panel == null) {
                continue;
            }
            if (panel.isAwaitingInput()) {
                input++;
            } else if (!panel.isRunning()) {
                done++;
            } else if (panel.isPaused()) {
                paused++;
            } else {
                working++;
            }
        }
        return new int[]{input, working, paused, done};
    }

    /**
     * Builds the localized run-count breakdown shown next to a multi-run collapsed status, e.g.
     * "1 running · 2 done". Zero categories are omitted; order is input → running → paused → done.
     */
    static String formatRunBreakdown(int input, int working, int paused, int done) {
        List<String> parts = new ArrayList<>();
        if (input > 0) {
            parts.add(I18n.get("ai.agent.collapsed.count.input", input));
        }
        if (working > 0) {
            parts.add(I18n.get("ai.agent.collapsed.count.running", working));
        }
        if (paused > 0) {
            parts.add(I18n.get("ai.agent.collapsed.count.paused", paused));
        }
        if (done > 0) {
            parts.add(I18n.get("ai.agent.collapsed.count.done", done));
        }
        return String.join(" \u00B7 ", parts);
    }

    /** A runId that is actively executing (running, not paused, not awaiting input), or null. */
    private String findRunWorking() {
        for (Map.Entry<String, AiAgentActivityPanel> entry : runPanels.entrySet()) {
            AiAgentActivityPanel panel = entry.getValue();
            if (panel != null && panel.isRunning() && !panel.isPaused() && !panel.isAwaitingInput()) {
                return entry.getKey();
            }
        }
        return null;
    }

    /**
     * The single run the collapsed bar displays AND its pause/cancel controls act on, so the status,
     * the button enablement, and the actions always agree: awaiting-input first, then an actively
     * working run, then the selected run, then any run.
     */
    private String focusedRunId() {
        String runId = findRunNeedingInput();
        if (runId == null) {
            runId = findRunWorking();
        }
        if (runId == null) {
            runId = selectedRunId();
        }
        if (runId == null && !runPanels.isEmpty()) {
            runId = runPanels.keySet().iterator().next();
        }
        return runId;
    }

    private String statePrefix(AiAgentActivityPanel panel) {
        if (panel == null) {
            return "";
        }
        if (panel.isAwaitingInput()) {
            return "✋ ";
        }
        if (panel.isPaused()) {
            return "⏸ ";
        }
        if (!panel.isRunning()) {
            return "✓ ";
        }
        return "";
    }

    /** A runId whose run is currently blocked waiting for user input, or null. */
    private String findRunNeedingInput() {
        for (Map.Entry<String, AiAgentActivityPanel> entry : runPanels.entrySet()) {
            if (entry.getValue() != null && entry.getValue().isAwaitingInput()) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void toggleSelectedRunPause() {
        AiAgentActivityPanel panel = focusedPanel();
        if (panel != null) {
            panel.togglePause();
        }
    }

    private void cancelSelectedRun() {
        String runId = focusedRunId();
        Runnable cancel = runId != null ? runCancels.get(runId) : null;
        if (cancel != null) {
            try {
                cancel.run();
            } catch (Exception e) {
                logger.debug("Failed to cancel terminal agent run from collapsed bar: {}", e.getMessage());
            }
        }
    }

    private AiAgentActivityPanel focusedPanel() {
        String runId = findRunNeedingInput();
        if (runId == null) {
            runId = selectedRunId();
        }
        return runId != null ? runPanels.get(runId) : null;
    }

    private void stopStatusTimerIfIdle() {
        if (!hasActiveRuns()) {
            statusRefreshTimer.stop();
        }
    }

    private void loadPersistedLayoutSettings() {
        GlobalSettings settings = globalSettings();
        if (settings == null || !settings.isTerminalAgentRememberPanelLayout()) {
            return;
        }
        Double panelHeight = settings.getTerminalAgentPanelHeight();
        if (panelHeight != null) {
            expandedPanelHeight = clampPanelHeight(panelHeight, parentHeight());
            setPrefHeight(expandedPanelHeight);
        }
    }

    private void persistLayoutSettingsIfEnabled() {
        GlobalSettingsManager manager = globalSettingsManager();
        if (manager == null || manager.getSettings() == null) {
            return;
        }
        GlobalSettings settings = manager.getSettings();
        if (!settings.isTerminalAgentRememberPanelLayout()) {
            return;
        }
        settings.setTerminalAgentPanelHeight(panelCollapsed ? expandedPanelHeight : currentPanelHeight());
        try {
            manager.save();
        } catch (Exception e) {
            logger.warn("Failed to save terminal agent panel layout settings", e);
        }
    }

    private GlobalSettings globalSettings() {
        GlobalSettingsManager manager = globalSettingsManager();
        return manager != null ? manager.getSettings() : null;
    }

    private GlobalSettingsManager globalSettingsManager() {
        KorTTYApplication application = KorTTYApplication.getInstance();
        return application != null ? application.getGlobalSettingsManager() : null;
    }

    private void runOnFx(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }
}
