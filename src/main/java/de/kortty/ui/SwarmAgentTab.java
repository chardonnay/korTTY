package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiChatExportContext;
import de.kortty.core.AiChatExportService;
import de.kortty.core.AiPdfExportOptions;
import de.kortty.core.TerminalAgentService;
import de.kortty.core.swarm.SwarmCallback;
import de.kortty.core.swarm.SwarmModels;
import de.kortty.core.swarm.SwarmRunControl;
import de.kortty.core.swarm.SwarmSnippetExecutor;
import de.kortty.core.swarm.SwarmTarget;
import de.kortty.core.swarm.SwarmTranscriptBuffer;
import de.kortty.jobscheduler.HeadlessSwarmAgentRunner;
import de.kortty.jobscheduler.ScheduledJob;
import de.kortty.model.AiProfile;
import de.kortty.model.SavedSwarmChat;
import de.kortty.model.SavedSwarmMessage;
import de.kortty.model.SavedSwarmServerSummary;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalAgentModels;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToolBar;
import javafx.scene.control.Tooltip;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Dedicated AI swarm window: a live per-server agent dashboard on the left and a chat (query +
 * bundled answer) on the right. Targets are picked from the connection manager; already-open
 * connections are used immediately and missing ones can be connected on demand.
 */
public class SwarmAgentTab extends Tab {

    private static final int FONT_SIZE = 13;
    private static final DateTimeFormatter EXPORT_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final MainWindow ownerWindow;
    private String languageCode;

    private final ComboBox<AiProfile> profileComboBox = new ComboBox<>();
    private final CheckBox readOnlyCheck = new CheckBox(I18n.get("ai.swarm.readOnly"));
    private final ComboBox<SwarmModels.BatchApprovalPolicy> approvalComboBox = new ComboBox<>();
    private final TextArea promptInputArea = new TextArea();
    private final Button sendButton = new Button(I18n.get("ai.swarm.send"));
    private final Button scheduleButton = new Button(I18n.get("ai.swarm.schedule"));
    private final Button runScriptButton = new Button(I18n.get("ai.swarm.script.button"));
    private final Button pauseButton = new Button(I18n.get("ai.swarm.control.pause"));
    private final Button resumeButton = new Button(I18n.get("ai.swarm.control.resume"));
    private final Button restartButton = new Button(I18n.get("ai.swarm.control.restart"));
    private final Button stopButton = new Button(I18n.get("ai.swarm.control.stop"));
    private final Button connectMissingButton = new Button();
    private final Label targetLabel = new Label(I18n.get("ai.swarm.target.none"));
    private final Label dashboardHeader = new Label();
    private final VBox agentRowsBox = new VBox(6);
    private final VBox messagesBox = new VBox(12);
    private final ScrollPane messagesScrollPane;
    private final ScrollPane dashboardScroll = new ScrollPane(agentRowsBox);
    private BorderPane contentRoot;
    private String currentChatStylesheetUrl;
    // Parallel to messageEntries: the top-level node (used to scroll a match into view) and the node
    // that gets the search-hit outline (the user bubble, not its full-width row).
    private final List<Node> messageNodes = new ArrayList<>();
    private final List<Node> highlightNodes = new ArrayList<>();
    private HBox searchBar;
    private TextField searchField;
    private Label searchCountLabel;
    private final List<Integer> searchMatches = new ArrayList<>();
    private int currentSearchIndex = -1;
    private final SwarmStatusStrip statusStrip = new SwarmStatusStrip();
    private final Button copyChatButton = new Button(I18n.get("ai.result.copy"));
    private final MenuButton exportChatButton = new MenuButton(I18n.get("ai.result.export"));
    private final Label chatStatusLabel = new Label();
    private final AiChatExportService exportService = new AiChatExportService();

    private final Map<String, SwarmAgentRow> rowsByAgentId = new LinkedHashMap<>();
    private final Map<String, SwarmTranscriptBuffer> transcriptBuffers = new LinkedHashMap<>();
    private final Map<String, String> headlessAgentIds = new LinkedHashMap<>();
    private final List<AutoCloseable> headlessRunners = new ArrayList<>();
    private final List<SwarmTarget> targets = new ArrayList<>();
    private final List<ServerConnection> missingConnections = new ArrayList<>();
    private final List<SavedSwarmMessage> messageEntries = new ArrayList<>();
    private final List<String> targetGroupPaths = new ArrayList<>();

    private String composerFrameBaseStyle;
    private String composerFrameFocusStyle;

    private final Circle tabIndicator = new Circle(5);
    private Timeline tabIndicatorPulse;
    private SwarmTabActivitySupport.Indicator tabIndicatorState = SwarmTabActivitySupport.Indicator.NONE;

    private boolean includeLocalShell;
    private boolean busy;
    private boolean scriptRunActive;
    private SwarmRunControl swarmControl;
    private String lastSentPrompt;
    private boolean restartPending;
    private SwarmModels.SwarmPhase lastSwarmPhase;
    private long runStartMillis;
    private final Timeline timer;

    private String savedChatId;
    private long savedChatCreatedAt;
    private String baseTitle;

    public SwarmAgentTab(
        MainWindow ownerWindow,
        String title,
        AiProfile initialProfile,
        String languageCode,
        SavedSwarmChat savedChat,
        boolean readOnly) {
        this.ownerWindow = ownerWindow;
        this.languageCode = languageCode;
        this.baseTitle = title != null ? title : I18n.get("ai.swarm.tab.title");

        setClosable(true);
        setText(this.baseTitle);
        setOnCloseRequest(event -> cancelSwarm());
        setOnClosed(event -> handleTabClosed());

        // Profile combo
        profileComboBox.setPrefWidth(220);
        profileComboBox.setCellFactory(list -> profileCell());
        profileComboBox.setButtonCell(profileCell());
        profileComboBox.getItems().setAll(ownerWindow.getAvailableAiProfiles());
        if (initialProfile != null) {
            for (AiProfile profile : profileComboBox.getItems()) {
                if (profile.getId() != null && profile.getId().equals(initialProfile.getId())) {
                    profileComboBox.getSelectionModel().select(profile);
                    break;
                }
            }
        }
        if (profileComboBox.getSelectionModel().getSelectedItem() == null && !profileComboBox.getItems().isEmpty()) {
            profileComboBox.getSelectionModel().selectFirst();
        }

        approvalComboBox.getItems().setAll(
            SwarmModels.BatchApprovalPolicy.ONE_APPROVAL_FOR_ALL,
            SwarmModels.BatchApprovalPolicy.PER_SERVER);
        approvalComboBox.setValue(SwarmModels.BatchApprovalPolicy.ONE_APPROVAL_FOR_ALL);
        approvalComboBox.setCellFactory(list -> approvalCell());
        approvalComboBox.setButtonCell(approvalCell());
        readOnlyCheck.setSelected(readOnly);

        Button pickButton = new Button(I18n.get("ai.swarm.target.pick"));
        pickButton.setOnAction(e -> pickTargets());
        connectMissingButton.setOnAction(e -> connectMissing());
        connectMissingButton.setDisable(true);

        Button workflowButton = new Button(I18n.get("ai.swarm.workflow"));
        workflowButton.setOnAction(e -> openSwarmWorkflow());
        Button saveButton = new Button(I18n.get("ai.swarm.save"));
        saveButton.setOnAction(e -> saveChat());
        scheduleButton.setTooltip(new Tooltip(I18n.get("ai.swarm.schedule.tooltip")));
        scheduleButton.setOnAction(e -> openScheduleDraft());
        runScriptButton.setOnAction(e -> openRunScriptDialog());

        pauseButton.setOnAction(e -> pauseSwarm());
        resumeButton.setOnAction(e -> resumeSwarm());
        restartButton.setOnAction(e -> restartSwarm());
        stopButton.setOnAction(e -> stopSwarm());

        Label profileLabel = new Label(I18n.get("ai.result.profile"));
        Label approvalLabel = new Label(I18n.get("ai.swarm.approvalPolicy"));
        ToolBar toolBar = new ToolBar(
            pickButton, connectMissingButton, new Separator(),
            profileLabel, profileComboBox,
            readOnlyCheck,
            approvalLabel, approvalComboBox,
            new Separator(),
            workflowButton, saveButton, scheduleButton, runScriptButton,
            new Separator(),
            pauseButton, resumeButton, restartButton, stopButton);
        // The dynamic theme stylesheet styles .label/.button but not .tool-bar, so a ToolBar keeps the
        // light JavaFX default background while its labels get the (light) theme foreground -> unreadable.
        // Paint the bar and its labels from the resolved theme colors so contrast is correct in any theme.
        applyTopBarTheme(toolBar, profileLabel, approvalLabel);

        // Dashboard (left)
        dashboardHeader.setStyle("-fx-font-weight: bold;");
        dashboardScroll.setFitToWidth(true);
        agentRowsBox.setPadding(new Insets(6));
        VBox dashboard = new VBox(6, new Label(I18n.get("ai.swarm.dashboard.title")), targetLabel, dashboardHeader, dashboardScroll);
        dashboard.setPadding(new Insets(8));
        VBox.setVgrow(dashboardScroll, Priority.ALWAYS);

        // Chat (right)
        messagesBox.setFillWidth(true);
        messagesScrollPane = new ScrollPane(messagesBox);
        messagesScrollPane.setFitToWidth(true);
        messagesScrollPane.getStyleClass().add("ai-chat-scroll");
        messagesBox.getStyleClass().add("ai-chat-messages");
        messagesBox.setPadding(new Insets(14, 16, 14, 16));
        promptInputArea.setWrapText(true);
        promptInputArea.setPrefRowCount(3);
        promptInputArea.setMinHeight(Region.USE_PREF_SIZE);
        promptInputArea.getStyleClass().add("swarm-composer-input");
        promptInputArea.setPromptText(I18n.get("ai.swarm.composer.placeholder"));
        promptInputArea.textProperty().addListener((obs, oldValue, newValue) -> updateSendAvailability());
        sendButton.setDefaultButton(true);
        sendButton.setMinWidth(110);
        sendButton.setOnAction(e -> sendSwarm());
        initComposerFrameStyles();
        StackPane promptFrame = new StackPane(promptInputArea);
        promptFrame.setPadding(new Insets(1));
        promptFrame.setStyle(composerFrameBaseStyle);
        promptInputArea.focusedProperty().addListener((obs, wasFocused, focused) ->
            promptFrame.setStyle(focused ? composerFrameFocusStyle : composerFrameBaseStyle));
        HBox.setHgrow(promptFrame, Priority.ALWAYS);
        HBox composer = new HBox(10, promptFrame, sendButton);
        composer.setAlignment(Pos.BOTTOM_RIGHT);
        searchBar = createChatSearchBar();
        VBox chat = new VBox(8, buildChatHeader(), searchBar, messagesScrollPane, composer);
        chat.setPadding(new Insets(8));
        VBox.setVgrow(messagesScrollPane, Priority.ALWAYS);

        SplitPane split = new SplitPane(dashboard, chat);
        split.setDividerPositions(0.42);

        BorderPane content = new BorderPane();
        contentRoot = content;
        content.setTop(new VBox(toolBar, statusStrip));
        content.setCenter(split);
        content.addEventFilter(KeyEvent.KEY_PRESSED, this::handleChatShortcut);
        setContent(content);
        applyChatTheme();

        statusStrip.setOnOrbClicked(this::highlightAgentRow);
        statusStrip.setTabSelected(isSelected());
        selectedProperty().addListener((obs, wasSelected, selected) -> statusStrip.setTabSelected(selected));

        timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> tickTimers()));
        timer.setCycleCount(Timeline.INDEFINITE);

        if (savedChat != null) {
            rehydrate(savedChat);
        }
        refreshDashboardHeader();
        updateSendAvailability();
    }

    private ListCell<AiProfile> profileCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.getName());
            }
        };
    }

    private ListCell<SwarmModels.BatchApprovalPolicy> approvalCell() {
        return new ListCell<>() {
            @Override
            protected void updateItem(SwarmModels.BatchApprovalPolicy item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : I18n.get(
                    item == SwarmModels.BatchApprovalPolicy.PER_SERVER
                        ? "ai.swarm.approvalPolicy.perServer" : "ai.swarm.approvalPolicy.all"));
            }
        };
    }

    public String getSavedChatId() {
        return savedChatId;
    }

    // ---- Target selection ---------------------------------------------------

    private void pickTargets() {
        SwarmTargetPickerDialog dialog = new SwarmTargetPickerDialog(includeLocalShell);
        if (getTabPane() != null && getTabPane().getScene() != null) {
            dialog.initOwner(getTabPane().getScene().getWindow());
        }
        Optional<SwarmTargetPickerDialog.Selection> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        SwarmTargetPickerDialog.Selection selection = result.get();
        includeLocalShell = selection.includeLocalShell();
        targetGroupPaths.clear();
        for (var group : selection.groups()) {
            if (group != null) {
                targetGroupPaths.add(group.getPath());
            }
        }
        SwarmTargetCollector.ResolveResult resolved =
            SwarmTargetCollector.resolveSelection(ownerWindow, selection.connections(), includeLocalShell);
        targets.clear();
        rowsByAgentId.clear();
        transcriptBuffers.clear();
        agentRowsBox.getChildren().clear();
        statusStrip.clearAgents();
        for (SwarmTarget target : resolved.openTargets()) {
            addTarget(target);
        }
        missingConnections.clear();
        missingConnections.addAll(resolved.missing());
        refreshTargetLabels();
    }

    private void connectMissing() {
        if (missingConnections.isEmpty() || busy) {
            return;
        }
        List<ServerConnection> toConnect = new ArrayList<>(missingConnections);
        connectMissingButton.setDisable(true);
        ownerWindow.connectSwarmTargets(toConnect, includeLocalShell,
            target -> {
                addTarget(target);
                missingConnections.removeIf(connection ->
                    SwarmModels.SwarmTargetKey.of(connection).equals(SwarmModels.SwarmTargetKey.of(target.connection())));
                refreshTargetLabels();
            },
            this::refreshTargetLabels);
    }

    private void addTarget(SwarmTarget target) {
        if (target == null) {
            return;
        }
        targets.add(target);
        SwarmAgentRow row = new SwarmAgentRow(target.agentId(), target.displayName());
        rowsByAgentId.put(target.agentId(), row);
        agentRowsBox.getChildren().add(row);
        if (statusStrip.isStaticMode()) {
            // Leaving the rehydrated (static) view: re-seed the strip from the full target list,
            // not just this target — otherwise "Connect missing" would drop the already-open ones.
            statusStrip.clearAgents();
            for (SwarmTarget existing : targets) {
                statusStrip.addAgent(existing.agentId(), existing.displayName());
            }
        } else {
            statusStrip.addAgent(target.agentId(), target.displayName());
        }
    }

    private void refreshTargetLabels() {
        int headlessCount = headlessCandidates().size();
        if (targets.isEmpty() && headlessCount == 0) {
            targetLabel.setText(I18n.get("ai.swarm.target.none"));
        } else {
            StringBuilder text = new StringBuilder(
                I18n.get("ai.swarm.target.selected", targets.size() + headlessCount));
            text.append("  •  ").append(I18n.get("ai.swarm.target.openCount", targets.size()));
            if (headlessCount > 0) {
                text.append("  •  ").append(I18n.get("ai.swarm.target.headlessCount", headlessCount));
            }
            targetLabel.setText(text.toString());
        }
        connectMissingButton.setText(I18n.get("ai.swarm.target.connectCount", missingConnections.size()));
        connectMissingButton.setDisable(busy || missingConnections.isEmpty());
        updateSendAvailability();
    }

    // ---- Headless targets (selected connections without an open terminal) ------

    private List<ServerConnection> headlessCandidates() {
        return SwarmHeadlessTargetSupport.schedulableConnections(missingConnections);
    }

    private boolean hasRunnableTargets() {
        return !targets.isEmpty() || !headlessCandidates().isEmpty();
    }

    /**
     * Open targets plus ephemeral headless targets for this run. Returns {@code null} (after
     * showing the vault error) when headless targets need stored secrets but the vault is locked.
     */
    private List<SwarmTarget> buildRunTargets() {
        List<SwarmTarget> runTargets = new ArrayList<>(targets);
        List<ServerConnection> headless = headlessCandidates();
        if (headless.isEmpty()) {
            return runTargets;
        }
        char[] masterPassword = KorTTYApplication.getInstance().getMasterPasswordManager() != null
            ? KorTTYApplication.getInstance().getMasterPasswordManager().getMasterPassword()
            : null;
        if (masterPassword == null) {
            showError(I18n.get("ai.swarm.error.masterPasswordLocked"));
            return null;
        }
        for (ServerConnection connection : headless) {
            String agentId = SwarmHeadlessTargetSupport.stableAgentId(headlessAgentIds, connection.getId());
            HeadlessSwarmAgentRunner runner = new HeadlessSwarmAgentRunner(
                KorTTYApplication.getInstance(), connection, masterPassword, null);
            headlessRunners.add(runner);
            runTargets.add(new SwarmTarget(agentId, connection, runner, null,
                connection.getId(), SwarmTargetCollector.displayName(connection)));
        }
        return runTargets;
    }

    private void closeHeadlessRunners() {
        for (AutoCloseable runner : headlessRunners) {
            try {
                runner.close();
            } catch (Exception e) {
                // best effort — the run is over
            }
        }
        headlessRunners.clear();
    }

    // ---- Running the swarm --------------------------------------------------

    private void sendSwarm() {
        String prompt = promptInputArea.getText() != null ? promptInputArea.getText().trim() : "";
        AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
        if (prompt.isEmpty() || busy) {
            return;
        }
        if (profile == null) {
            showError(I18n.get("ai.swarm.error.notConfigured"));
            return;
        }
        if (!hasRunnableTargets()) {
            showError(I18n.get("ai.swarm.error.noTargets"));
            return;
        }
        // Clear the composer only once the run actually started — an aborted start (e.g. locked
        // vault for headless targets) must not throw away the typed prompt.
        if (startSwarmRun(prompt)) {
            promptInputArea.clear();
        }
    }

    /**
     * Launches one swarm run; also the re-entry point for the whole-swarm restart.
     *
     * @return whether the run was actually started
     */
    private boolean startSwarmRun(String prompt) {
        AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
        if (busy || prompt == null || prompt.isBlank() || profile == null || !hasRunnableTargets()) {
            return false;
        }
        List<SwarmTarget> runTargets = buildRunTargets();
        if (runTargets == null || runTargets.isEmpty()) {
            return false;
        }
        lastSentPrompt = prompt;
        appendUserMessage(prompt);

        boolean readOnly = readOnlyCheck.isSelected();
        SwarmModels.BatchApprovalPolicy policy = readOnly
            ? SwarmModels.BatchApprovalPolicy.READ_ONLY
            : approvalComboBox.getValue();
        SwarmModels.SwarmRequest request = new SwarmModels.SwarmRequest(
            prompt, profile.getId(), SwarmModels.SwarmSource.CONNECTION_SELECTION,
            includeLocalShell, readOnly, 4, policy);

        // Re-key rows by the (stable) target agentIds for this run (open + headless targets).
        resetRowsForRun(runTargets);

        busy = true;
        restartPending = false;
        lastSwarmPhase = null;
        runStartMillis = System.currentTimeMillis();
        swarmControl = new SwarmRunControl();
        SwarmRunControl control = swarmControl;
        timer.playFromStart();
        updateSendAvailability();
        updateTabIndicator();

        // Run-identity guard: agentIds are stable across runs, so after a swarm restart a
        // straggler attempt from the superseded run could otherwise write into the new run's
        // rows/orbs/transcripts. Deliveries are dropped unless this run is still the current one.
        SwarmCallback callback = new SwarmCallback() {
            @Override
            public void onSwarmState(SwarmModels.SwarmRunState state) {
                Platform.runLater(() -> {
                    if (swarmControl == control) {
                        applySwarmState(state);
                    }
                });
            }

            @Override
            public void onAgentStatus(SwarmModels.SwarmAgentStatus status) {
                Platform.runLater(() -> {
                    if (swarmControl == control) {
                        applyAgentStatus(status);
                    }
                });
            }

            @Override
            public void onAgentTranscript(String agentId, String chunk) {
                Platform.runLater(() -> {
                    if (swarmControl == control) {
                        appendAgentTranscript(agentId, chunk);
                    }
                });
            }

            @Override
            public void onAggregationResult(SwarmModels.SwarmAggregationResult result) {
                Platform.runLater(() -> {
                    if (swarmControl == control) {
                        finishSwarm(result);
                    }
                });
            }

            @Override
            public TerminalAgentService.ApprovalDecision requestBatchApproval(
                TerminalAgentModels.Approval approval, String agentId) {
                return requestApprovalBlocking(approval, agentId, control);
            }

            @Override
            public TerminalAgentModels.PasswordResponse requestPassword(
                TerminalAgentModels.PasswordRequest passwordRequest, String agentId) {
                return null;
            }

            @Override
            public boolean isCancelled() {
                return control.isSwarmCancelled();
            }
        };
        ownerWindow.startSwarm(request, runTargets, profile, callback, control);
        return true;
    }

    private void applySwarmState(SwarmModels.SwarmRunState state) {
        if (state == null) {
            return;
        }
        lastSwarmPhase = state.phase();
        long seconds = Math.max(0L, state.elapsedSeconds());
        dashboardHeader.setText(I18n.get("ai.swarm.progress",
            state.running(), state.done(), state.failed(), formatElapsed(seconds)));
        updateSendAvailability();
    }

    private void applyAgentStatus(SwarmModels.SwarmAgentStatus status) {
        if (status == null) {
            return;
        }
        SwarmAgentRow row = rowsByAgentId.get(status.agentId());
        if (row != null) {
            row.update(status);
        }
        statusStrip.applyAgentStatus(status);
        updateTabIndicator();
    }

    // ---- Tab activity indicator ----------------------------------------------

    /**
     * Small pulsing dot in the tab header so a running swarm stays visible from other tabs:
     * blue breathing while agents work, fast orange blink when an agent waits for approval,
     * static violet when everything is paused, and a short green flash once the run finished.
     */
    private void updateTabIndicator() {
        List<SwarmModels.SwarmAgentState> states = new ArrayList<>(rowsByAgentId.size());
        for (SwarmAgentRow row : rowsByAgentId.values()) {
            states.add(row.state);
        }
        SwarmTabActivitySupport.Indicator indicator =
            SwarmTabActivitySupport.dominantIndicator(busy, states);
        if (indicator == tabIndicatorState) {
            return;
        }
        boolean wasBusyIndicator = tabIndicatorState != SwarmTabActivitySupport.Indicator.NONE;
        tabIndicatorState = indicator;
        stopTabIndicatorAnimation();
        switch (indicator) {
            case ACTIVE -> showTabIndicator(Color.web("#4f9cf0"), javafx.util.Duration.seconds(1.2));
            case WAITING -> showTabIndicator(Color.web("#ff9800"), javafx.util.Duration.seconds(0.55));
            case PAUSED -> showTabIndicator(Color.web("#b39ddb"), null);
            case NONE -> {
                if (wasBusyIndicator && !restartPending) {
                    showTabIndicatorDone();
                } else {
                    setGraphic(null);
                }
            }
        }
    }

    private void showTabIndicator(Color color, javafx.util.Duration pulsePeriod) {
        tabIndicator.setFill(color);
        tabIndicator.setOpacity(1.0);
        setGraphic(tabIndicator);
        if (pulsePeriod != null) {
            tabIndicatorPulse = new Timeline(
                new KeyFrame(javafx.util.Duration.ZERO,
                    new javafx.animation.KeyValue(tabIndicator.opacityProperty(), 1.0)),
                new KeyFrame(pulsePeriod,
                    new javafx.animation.KeyValue(tabIndicator.opacityProperty(), 0.3)));
            tabIndicatorPulse.setAutoReverse(true);
            tabIndicatorPulse.setCycleCount(Timeline.INDEFINITE);
            tabIndicatorPulse.play();
        }
    }

    /** The green "finished" dot stays until the next run starts (or the tab closes). */
    private void showTabIndicatorDone() {
        tabIndicator.setFill(Color.web("#4caf50"));
        tabIndicator.setOpacity(1.0);
        setGraphic(tabIndicator);
    }

    private void stopTabIndicatorAnimation() {
        if (tabIndicatorPulse != null) {
            tabIndicatorPulse.stop();
            tabIndicatorPulse = null;
        }
        tabIndicator.setOpacity(1.0);
    }

    /** Live agent output for the expandable row detail; buffered even while the row is collapsed. */
    private void appendAgentTranscript(String agentId, String chunk) {
        if (agentId == null || chunk == null || chunk.isEmpty()) {
            return;
        }
        SwarmTranscriptBuffer buffer = transcriptBuffers.computeIfAbsent(
            agentId, id -> new SwarmTranscriptBuffer(200_000, 150_000));
        boolean trimmed = buffer.append(chunk);
        SwarmAgentRow row = rowsByAgentId.get(agentId);
        if (row != null && row.isExpanded()) {
            if (trimmed) {
                row.setDetail(buffer.snapshot());
            } else {
                row.appendDetail(chunk);
            }
        }
    }

    private void finishSwarm(SwarmModels.SwarmAggregationResult result) {
        timer.stop();
        busy = false;
        boolean restartRequested = restartPending;
        restartPending = false;
        swarmControl = null;
        lastSwarmPhase = null;
        closeHeadlessRunners();
        statusStrip.markRunFinished();
        // A swarm restart discards the cancelled run's partial aggregation instead of
        // polluting the chat (and the autosave) with a half answer.
        if (!restartRequested && result != null && result.markdown() != null && !result.markdown().isBlank()) {
            appendAssistantMessage(result.markdown(), buildServerSummaries());
        }
        refreshDashboardHeader();
        refreshAgentControlIndicators();
        updateSendAvailability();
        updateTabIndicator();
        if (restartRequested && getTabPane() != null && lastSentPrompt != null) {
            startSwarmRun(lastSentPrompt);
        }
    }

    private List<SavedSwarmServerSummary> buildServerSummaries() {
        List<SavedSwarmServerSummary> summaries = new ArrayList<>();
        for (SwarmAgentRow row : rowsByAgentId.values()) {
            SavedSwarmServerSummary summary = new SavedSwarmServerSummary();
            summary.setServerDisplayName(row.displayName);
            summary.setFinalState(row.state != null ? row.state.name() : null);
            summary.setSummaryText(row.lastActivity);
            summary.setElapsedSeconds(row.elapsedSeconds);
            summary.setTotalTokens(row.totalTokens);
            summaries.add(summary);
        }
        return summaries;
    }

    /** Rebuilds the agent rows/orbs keyed by the (stable) target agentIds for a fresh AI or script run. */
    private void resetRowsForRun(List<SwarmTarget> runTargets) {
        rowsByAgentId.clear();
        transcriptBuffers.clear();
        agentRowsBox.getChildren().clear();
        statusStrip.clearAgents();
        for (SwarmTarget target : runTargets) {
            SwarmAgentRow row = new SwarmAgentRow(target.agentId(), target.displayName());
            rowsByAgentId.put(target.agentId(), row);
            agentRowsBox.getChildren().add(row);
            statusStrip.addAgent(target.agentId(), target.displayName());
        }
    }

    private void cancelSwarm() {
        if (swarmControl != null) {
            swarmControl.cancelAll();
        }
    }

    /** Cancels any active run and removes this tab, e.g. when its saved chat is deleted elsewhere. */
    public void closeTab() {
        cancelSwarm();
        if (getTabPane() != null) {
            getTabPane().getTabs().remove(this);
        }
    }

    // ---- Whole-swarm controls -------------------------------------------------

    private void pauseSwarm() {
        if (swarmControl != null) {
            swarmControl.pauseAll();
            refreshAgentControlIndicators();
            updateSendAvailability();
        }
    }

    private void resumeSwarm() {
        if (swarmControl != null) {
            swarmControl.resumeAll();
            refreshAgentControlIndicators();
            updateSendAvailability();
        }
    }

    private void stopSwarm() {
        restartPending = false;
        cancelSwarm();
        updateSendAvailability();
    }

    // ---- Snippet script run (no AI) --------------------------------------------

    private void openRunScriptDialog() {
        if (busy || !hasRunnableTargets()) {
            return;
        }
        SwarmSnippetRunDialog dialog = new SwarmSnippetRunDialog(
            ownerWindowRef(), targets.size() + headlessCandidates().size(),
            KorTTYApplication.getInstance().getSnippetManager(),
            KorTTYApplication.getInstance().getSnippetVariableManager());
        dialog.showAndWait().ifPresent(this::startScriptRun);
    }

    /** Runs the prepared snippet one-liner on all targets in parallel — without any AI agent. */
    private void startScriptRun(SwarmSnippetRunSupport.PreparedRun prepared) {
        if (busy || !hasRunnableTargets() || prepared == null) {
            return;
        }
        List<SwarmTarget> runTargets = buildRunTargets();
        if (runTargets == null || runTargets.isEmpty()) {
            return;
        }
        appendUserMessage(I18n.get("ai.swarm.script.user.message",
            prepared.snippetName(), prepared.arguments().size()));

        resetRowsForRun(runTargets);

        busy = true;
        scriptRunActive = true;
        restartPending = false;
        lastSwarmPhase = null;
        runStartMillis = System.currentTimeMillis();
        swarmControl = new SwarmRunControl();
        SwarmRunControl control = swarmControl;
        timer.playFromStart();
        updateSendAvailability();
        updateTabIndicator();

        Map<String, SwarmTarget> targetsById = new LinkedHashMap<>();
        for (SwarmTarget target : runTargets) {
            targetsById.put(target.agentId(), target);
        }
        new SwarmSnippetExecutor().run(runTargets, prepared.command(), control, new SwarmSnippetExecutor.Listener() {
            @Override
            public void onTargetStarted(String agentId) {
                Platform.runLater(() -> {
                    if (swarmControl == control) {
                        applyAgentStatus(scriptStatus(targetsById.get(agentId),
                            SwarmModels.SwarmAgentState.RUNNING, I18n.get("ai.swarm.script.running"), 0L, null));
                        refreshDashboardHeader();
                    }
                });
            }

            @Override
            public void onTargetOutput(String agentId, String chunk) {
                Platform.runLater(() -> {
                    if (swarmControl == control) {
                        appendAgentTranscript(agentId, chunk);
                    }
                });
            }

            @Override
            public void onTargetFinished(SwarmSnippetExecutor.TargetOutcome outcome) {
                Platform.runLater(() -> {
                    if (swarmControl == control) {
                        applyAgentStatus(scriptStatus(targetsById.get(outcome.agentId()),
                            scriptStateFor(outcome), scriptActivityFor(outcome),
                            outcome.elapsedSeconds(), outcome.errorDetail()));
                        refreshDashboardHeader();
                    }
                });
            }

            @Override
            public void onAllFinished(List<SwarmSnippetExecutor.TargetOutcome> outcomes) {
                Platform.runLater(() -> {
                    if (swarmControl == control) {
                        finishScriptRun(prepared, outcomes);
                    }
                });
            }
        });
    }

    private void finishScriptRun(SwarmSnippetRunSupport.PreparedRun prepared,
                                 List<SwarmSnippetExecutor.TargetOutcome> outcomes) {
        timer.stop();
        busy = false;
        scriptRunActive = false;
        restartPending = false;
        swarmControl = null;
        lastSwarmPhase = null;
        closeHeadlessRunners();
        statusStrip.markRunFinished();
        String markdown = SwarmSnippetRunSupport.buildResultMarkdown(
            I18n.get("ai.swarm.script.result.heading", prepared.snippetName()),
            List.of(I18n.get("ai.swarm.script.table.server"),
                I18n.get("ai.swarm.script.table.exit"),
                I18n.get("ai.swarm.script.table.output")),
            outcomes,
            new SwarmSnippetRunSupport.OutcomeLabels(
                I18n.get("ai.swarm.status.cancelled"),
                I18n.get("ai.swarm.script.outcome.timeout"),
                I18n.get("ai.swarm.script.outcome.notConnected"),
                I18n.get("ai.swarm.script.outcome.unsupportedShell"),
                I18n.get("ai.swarm.script.outcome.error")),
            SwarmSnippetRunSupport.DEFAULT_OUTPUT_CAP);
        appendAssistantMessage(markdown, buildServerSummaries(), false);
        refreshDashboardHeader();
        refreshAgentControlIndicators();
        updateSendAvailability();
        updateTabIndicator();
    }

    private SwarmModels.SwarmAgentStatus scriptStatus(SwarmTarget target, SwarmModels.SwarmAgentState state,
                                                      String activity, long elapsedSeconds, String error) {
        return new SwarmModels.SwarmAgentStatus(
            target.agentId(), target.displayName(), SwarmModels.SwarmTargetKey.of(target.connection()),
            state, activity, elapsedSeconds, SwarmModels.TokenTotals.zero(), null, null, error);
    }

    private static SwarmModels.SwarmAgentState scriptStateFor(SwarmSnippetExecutor.TargetOutcome outcome) {
        return switch (outcome.kind()) {
            case COMPLETED -> outcome.exitCode() == 0
                ? SwarmModels.SwarmAgentState.DONE
                : SwarmModels.SwarmAgentState.FAILED;
            case CANCELLED -> SwarmModels.SwarmAgentState.CANCELLED;
            case UNSUPPORTED_SHELL -> SwarmModels.SwarmAgentState.SKIPPED;
            case TIMED_OUT, NOT_CONNECTED, ERROR -> SwarmModels.SwarmAgentState.FAILED;
        };
    }

    private static String scriptActivityFor(SwarmSnippetExecutor.TargetOutcome outcome) {
        return switch (outcome.kind()) {
            case COMPLETED -> I18n.get("ai.swarm.script.activity.exit", outcome.exitCode());
            case CANCELLED -> I18n.get("ai.swarm.status.cancelled");
            case TIMED_OUT -> I18n.get("ai.swarm.script.outcome.timeout");
            case NOT_CONNECTED -> I18n.get("ai.swarm.script.outcome.notConnected");
            case UNSUPPORTED_SHELL -> I18n.get("ai.swarm.script.outcome.unsupportedShell");
            case ERROR -> I18n.get("ai.swarm.script.outcome.error",
                outcome.errorDetail() != null ? outcome.errorDetail() : "");
        };
    }

    /** Opens the Job Scheduler with a prefilled AI_SWARM draft built from this window's state. */
    private void openScheduleDraft() {
        AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
        String prompt = SwarmScheduleDraftSupport.resolvePromptForDraft(messageEntries, promptInputArea.getText());
        ScheduledJob draft = SwarmScheduleDraftSupport.buildDraft(
            baseTitle,
            prompt,
            profile != null ? profile.getId() : null,
            connectionsForWorkflow(),
            readOnlyCheck.isSelected());
        if (draft == null) {
            return;
        }
        ownerWindow.showJobSchedulerWithDraft(draft);
    }

    /** Mid-run: cancel and re-send the same prompt once the run wound down; idle: re-send directly. */
    private void restartSwarm() {
        if (busy) {
            restartPending = true;
            cancelSwarm();
        } else if (lastSentPrompt != null) {
            startSwarmRun(lastSentPrompt);
        }
    }

    /** Stops all animation/timers when the tab closes; late callback deliveries become no-ops. */
    private void handleTabClosed() {
        restartPending = false;
        closeHeadlessRunners();
        stopTabIndicatorAnimation();
        statusStrip.dispose();
        timer.stop();
        ownerWindow.unregisterSavedSwarmChatTab(savedChatId);
    }

    private TerminalAgentService.ApprovalDecision requestApprovalBlocking(
        TerminalAgentModels.Approval approval, String agentId, SwarmRunControl control) {
        SwarmAgentRow row = rowsByAgentId.get(agentId);
        String serverName = row != null ? row.displayName : agentId;
        return SwarmApprovalDialogSupport.requestBlocking(
            approval, serverName, ownerWindowRef(), () -> control != null && control.isSwarmCancelled());
    }

    // ---- Chat / messages ----------------------------------------------------

    private HBox buildChatHeader() {
        chatStatusLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
        copyChatButton.setOnAction(e -> copyConversation());
        MenuItem plainItem = new MenuItem(I18n.get("ai.result.export.text"));
        plainItem.setOnAction(e -> exportConversation(AiChatExportService.Format.TEXT));
        MenuItem markdownItem = new MenuItem(I18n.get("ai.result.export.markdown"));
        markdownItem.setOnAction(e -> exportConversation(AiChatExportService.Format.MARKDOWN));
        MenuItem pdfItem = new MenuItem(I18n.get("ai.result.export.pdf"));
        pdfItem.setOnAction(e -> exportConversation(AiChatExportService.Format.PDF));
        exportChatButton.getItems().setAll(plainItem, markdownItem, pdfItem);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        Button findButton = new Button(I18n.get("ai.chat.search"));
        findButton.setTooltip(new Tooltip(I18n.get("ai.chat.search.tooltip")));
        findButton.setOnAction(e -> toggleChatSearch());
        HBox header = new HBox(8,
            new Label(I18n.get("ai.swarm.chat.title")), chatStatusLabel, spacer,
            findButton, copyChatButton, exportChatButton);
        header.setAlignment(Pos.CENTER_LEFT);
        return header;
    }

    private void copyConversation() {
        String text = exportService.buildPlainTextExport(SwarmChatExportSupport.toChatMessages(messageEntries));
        ClipboardContent content = new ClipboardContent();
        content.putString(text != null ? text : "");
        Clipboard.getSystemClipboard().setContent(content);
        showChatStatus(I18n.get("ai.swarm.chat.copied"));
    }

    private void exportConversation(AiChatExportService.Format format) {
        AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
        AiChatExportContext exportContext = new AiChatExportContext(
            baseTitle, LocalDateTime.now(), profile != null ? profile.getName() : null, messageEntries.size());
        AiPdfExportOptions pdfOptions = null;
        if (format == AiChatExportService.Format.PDF) {
            AiPdfExportDialog dialog = new AiPdfExportDialog(
                ownerWindowRef(), exportContext, AiPdfExportOptions.defaults(exportContext.title()));
            pdfOptions = dialog.showAndWait().orElse(null);
            if (pdfOptions == null) {
                return;
            }
        }
        File targetFile = chooseExportTarget(format);
        if (targetFile == null) {
            return;
        }
        try {
            exportService.exportChat(targetFile.toPath(), format,
                SwarmChatExportSupport.toChatMessages(messageEntries), FONT_SIZE, exportContext, pdfOptions);
            showChatStatus(I18n.get("ai.result.export.success", targetFile.getName()));
        } catch (Exception ex) {
            showError(I18n.get("ai.result.export.failed") + "\n"
                + (ex.getMessage() != null ? ex.getMessage() : ex.toString()));
        }
    }

    private File chooseExportTarget(AiChatExportService.Format format) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("ai.result.export.title"));
        fileChooser.setInitialFileName("swarm-chat-" + LocalDateTime.now().format(EXPORT_FILE_FORMAT) + format.getExtension());
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get(format.getFilterKey()), "*" + format.getExtension()));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get("connEdit.allFiles"), "*.*"));
        return fileChooser.showSaveDialog(ownerWindowRef());
    }

    private Window ownerWindowRef() {
        return getTabPane() != null && getTabPane().getScene() != null
            ? getTabPane().getScene().getWindow()
            : null;
    }

    /** Shows a transient status note next to the conversation title. */
    private void showChatStatus(String message) {
        chatStatusLabel.setText(message);
        PauseTransition clear = new PauseTransition(Duration.seconds(4));
        clear.setOnFinished(e -> chatStatusLabel.setText(""));
        clear.play();
    }

    private void appendUserMessage(String content) {
        SavedSwarmMessage message = new SavedSwarmMessage();
        message.setRole(SavedSwarmMessage.ROLE_USER);
        message.setContent(content);
        messageEntries.add(message);
        renderMessage(message);
        persistQuietly();
        updateSendAvailability();
    }

    private void appendAssistantMessage(String content, List<SavedSwarmServerSummary> summaries) {
        appendAssistantMessage(content, summaries, true);
    }

    /** {@code attributeProfile=false} for script-run results — no AI was involved in producing them. */
    private void appendAssistantMessage(
        String content, List<SavedSwarmServerSummary> summaries, boolean attributeProfile) {
        AiProfile profile = attributeProfile
            ? profileComboBox.getSelectionModel().getSelectedItem()
            : null;
        SavedSwarmMessage message = new SavedSwarmMessage();
        message.setRole(SavedSwarmMessage.ROLE_ASSISTANT);
        message.setContent(content);
        if (profile != null) {
            message.setAiProfileId(profile.getId());
            message.setAiProfileName(profile.getName());
        }
        message.setServerSummaries(summaries);
        messageEntries.add(message);
        renderMessage(message);
        persistQuietly();
        updateSendAvailability();
    }

    private void renderMessage(SavedSwarmMessage message) {
        boolean assistant = SavedSwarmMessage.ROLE_ASSISTANT.equals(message.getRole());
        Node topNode;
        Node highlightTarget;
        if (assistant) {
            VBox block = new VBox(6);
            block.setFillWidth(true);
            block.getStyleClass().add("ai-chat-assistant");
            Label roleLabel = new Label(I18n.get("ai.swarm.assistant"));
            roleLabel.getStyleClass().addAll("ai-chat-role", "ai-chat-role-assistant");
            roleLabel.setStyle("-fx-font-weight: bold;");
            block.getChildren().add(roleLabel);
            AiChatRenderSupport.renderInto(block, true, message.getContent(), FONT_SIZE);
            topNode = block;
            highlightTarget = block;
        } else {
            VBox bubble = new VBox(4);
            bubble.setFillWidth(true);
            bubble.getStyleClass().add("ai-chat-user-bubble");
            Label roleLabel = new Label(I18n.get("ai.swarm.user"));
            roleLabel.getStyleClass().add("ai-chat-role");
            roleLabel.setStyle("-fx-font-weight: bold;");
            roleLabel.setMaxWidth(Double.MAX_VALUE);
            roleLabel.setAlignment(Pos.CENTER_RIGHT);
            bubble.getChildren().add(roleLabel);
            AiChatRenderSupport.renderInto(bubble, false, message.getContent(), FONT_SIZE);
            HBox row = new HBox(bubble);
            row.getStyleClass().add("ai-chat-user-row");
            row.setAlignment(Pos.CENTER_RIGHT);
            // Cap the bubble to ~74% of the viewport so the user's turn reads as an indented reply.
            bubble.maxWidthProperty().bind(messagesScrollPane.widthProperty().multiply(0.74));
            topNode = row;
            highlightTarget = bubble;
        }
        messagesBox.getChildren().add(topNode);
        messageNodes.add(topNode);
        highlightNodes.add(highlightTarget);
        Platform.runLater(() -> messagesScrollPane.setVvalue(1.0));
    }

    // ---- Chat theming + search ---------------------------------------------

    /** Re-applies the globally selected chat color profile; called when the profile changes. */
    public void refreshChatColorProfile() {
        applyChatTheme();
    }

    private void applyChatTheme() {
        if (contentRoot == null) {
            return;
        }
        ThemeCssSupport.ChatPalette palette = ChatColorProfileSupport.resolvePalette(
            ChatColorProfileSupport.activeProfile(KorTTYApplication.getInstance()),
            KorTTYApplication.getInstance());
        String url = ThemeCssSupport.getChatStylesheetUrl(palette);
        if (currentChatStylesheetUrl != null) {
            contentRoot.getStylesheets().remove(currentChatStylesheetUrl);
        }
        if (url != null) {
            contentRoot.getStylesheets().add(url);
        }
        currentChatStylesheetUrl = url;
    }

    private HBox createChatSearchBar() {
        searchField = new TextField();
        searchField.getStyleClass().add("ai-chat-search-field");
        searchField.setPromptText(I18n.get("ai.chat.search.prompt"));
        HBox.setHgrow(searchField, Priority.ALWAYS);
        searchField.textProperty().addListener((obs, oldValue, newValue) -> runChatSearch());
        searchField.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER) {
                gotoRelativeMatch(event.isShiftDown() ? -1 : 1);
                event.consume();
            } else if (event.getCode() == KeyCode.ESCAPE) {
                hideChatSearch();
                event.consume();
            }
        });

        Button prevButton = new Button("↑");
        prevButton.getStyleClass().add("ai-chat-icon-button");
        prevButton.setTooltip(new Tooltip(I18n.get("ai.chat.search.previous")));
        prevButton.setOnAction(e -> gotoRelativeMatch(-1));
        Button nextButton = new Button("↓");
        nextButton.getStyleClass().add("ai-chat-icon-button");
        nextButton.setTooltip(new Tooltip(I18n.get("ai.chat.search.next")));
        nextButton.setOnAction(e -> gotoRelativeMatch(1));

        searchCountLabel = new Label("");
        searchCountLabel.getStyleClass().add("ai-chat-search-count");

        Button closeSearch = new Button("✕");
        closeSearch.getStyleClass().add("ai-chat-icon-button");
        closeSearch.setOnAction(e -> hideChatSearch());

        HBox bar = new HBox(8, new Label(I18n.get("ai.chat.search")), searchField, prevButton, nextButton,
            searchCountLabel, closeSearch);
        bar.getStyleClass().add("ai-chat-search-bar");
        bar.setAlignment(Pos.CENTER_LEFT);
        bar.setVisible(false);
        bar.setManaged(false);
        return bar;
    }

    private void handleChatShortcut(KeyEvent event) {
        if (event.getCode() == KeyCode.F && event.isShortcutDown()) {
            toggleChatSearch();
            event.consume();
        }
    }

    private void toggleChatSearch() {
        if (searchBar == null) {
            return;
        }
        if (searchBar.isVisible()) {
            hideChatSearch();
        } else {
            searchBar.setVisible(true);
            searchBar.setManaged(true);
            searchField.requestFocus();
            searchField.selectAll();
            runChatSearch();
        }
    }

    private void hideChatSearch() {
        if (searchBar == null) {
            return;
        }
        searchBar.setVisible(false);
        searchBar.setManaged(false);
        clearSearchHighlights();
        searchMatches.clear();
        currentSearchIndex = -1;
    }

    private void clearSearchHighlights() {
        for (Node node : highlightNodes) {
            node.getStyleClass().removeAll("ai-chat-hit", "ai-chat-hit-current");
        }
    }

    private void runChatSearch() {
        clearSearchHighlights();
        searchMatches.clear();
        currentSearchIndex = -1;
        String query = searchField != null ? searchField.getText() : null;
        if (query == null || query.isBlank()) {
            updateSearchCount();
            return;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        for (int i = 0; i < messageEntries.size() && i < highlightNodes.size(); i++) {
            SavedSwarmMessage entry = messageEntries.get(i);
            String content = entry != null ? entry.getContent() : null;
            if (content != null && content.toLowerCase(Locale.ROOT).contains(needle)) {
                searchMatches.add(i);
                highlightNodes.get(i).getStyleClass().add("ai-chat-hit");
            }
        }
        if (!searchMatches.isEmpty()) {
            currentSearchIndex = 0;
            focusCurrentMatch();
        }
        updateSearchCount();
    }

    private void gotoRelativeMatch(int delta) {
        if (searchMatches.isEmpty()) {
            return;
        }
        currentSearchIndex = (currentSearchIndex + delta + searchMatches.size()) % searchMatches.size();
        focusCurrentMatch();
        updateSearchCount();
    }

    private void focusCurrentMatch() {
        for (Node node : highlightNodes) {
            node.getStyleClass().remove("ai-chat-hit-current");
        }
        if (currentSearchIndex < 0 || currentSearchIndex >= searchMatches.size()) {
            return;
        }
        int messageIndex = searchMatches.get(currentSearchIndex);
        if (messageIndex < 0 || messageIndex >= messageNodes.size()) {
            return;
        }
        highlightNodes.get(messageIndex).getStyleClass().add("ai-chat-hit-current");
        scrollNodeIntoView(messageNodes.get(messageIndex));
    }

    private void scrollNodeIntoView(Node node) {
        Platform.runLater(() -> {
            double contentHeight = messagesBox.getHeight();
            double viewportHeight = messagesScrollPane.getViewportBounds().getHeight();
            double scrollable = contentHeight - viewportHeight;
            if (scrollable <= 0) {
                return;
            }
            double nodeTop = node.getBoundsInParent().getMinY();
            double target = (nodeTop - 12) / scrollable;
            messagesScrollPane.setVvalue(Math.max(0, Math.min(1, target)));
        });
    }

    private void updateSearchCount() {
        if (searchCountLabel == null) {
            return;
        }
        if (searchMatches.isEmpty()) {
            String query = searchField != null ? searchField.getText() : null;
            searchCountLabel.setText(query == null || query.isBlank() ? "" : I18n.get("ai.chat.search.noMatches"));
        } else {
            searchCountLabel.setText(I18n.get("ai.chat.search.count",
                Integer.toString(currentSearchIndex + 1), Integer.toString(searchMatches.size())));
        }
    }

    // ---- Persistence --------------------------------------------------------

    private void saveChat() {
        if (messageEntries.isEmpty() && targets.isEmpty()) {
            return;
        }
        TextInputDialog dialog = new TextInputDialog(baseTitle);
        dialog.setTitle(I18n.get("ai.swarm.save"));
        dialog.setHeaderText(null);
        if (getTabPane() != null && getTabPane().getScene() != null) {
            dialog.initOwner(getTabPane().getScene().getWindow());
        }
        Optional<String> result = dialog.showAndWait();
        if (result.isEmpty()) {
            return;
        }
        baseTitle = result.get().isBlank() ? baseTitle : result.get().trim();
        setText(baseTitle);
        try {
            SavedSwarmChat saved = KorTTYApplication.getInstance().getSwarmChatManager().saveChat(buildSnapshot(baseTitle));
            savedChatId = saved.getId();
            savedChatCreatedAt = saved.getCreatedAt();
            ownerWindow.registerSavedSwarmChatTab(this);
            ownerWindow.updateStatusMessage(I18n.get("ai.swarm.save.success"));
        } catch (Exception e) {
            showError(e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
        }
    }

    private void persistQuietly() {
        if (savedChatId == null) {
            return;
        }
        try {
            KorTTYApplication.getInstance().getSwarmChatManager().saveChat(buildSnapshot(baseTitle));
        } catch (Exception ignored) {
            // Autosave is best-effort.
        }
    }

    private SavedSwarmChat buildSnapshot(String title) {
        SavedSwarmChat chat = new SavedSwarmChat();
        if (savedChatId != null) {
            chat.setId(savedChatId);
            chat.setCreatedAt(savedChatCreatedAt);
        }
        chat.setTitle(title);
        chat.setResponseLanguageCode(languageCode);
        AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
        if (profile != null) {
            chat.setActiveAiProfileId(profile.getId());
            chat.setActiveAiProfileName(profile.getName());
        }
        List<String> connectionIds = new ArrayList<>();
        for (SwarmTarget target : targets) {
            if (target.connection() != null && target.connection().getId() != null) {
                connectionIds.add(target.connection().getId());
            }
        }
        chat.setTargetConnectionIds(connectionIds);
        chat.setTargetGroupPaths(new ArrayList<>(targetGroupPaths));
        chat.setMessages(new ArrayList<>(messageEntries));
        return chat;
    }

    private void rehydrate(SavedSwarmChat savedChat) {
        savedChatId = savedChat.getId();
        savedChatCreatedAt = savedChat.getCreatedAt();
        if (savedChat.getTitle() != null && !savedChat.getTitle().isBlank()) {
            baseTitle = savedChat.getTitle();
            setText(baseTitle);
        }
        if (savedChat.getResponseLanguageCode() != null) {
            languageCode = savedChat.getResponseLanguageCode();
        }
        targetGroupPaths.clear();
        if (savedChat.getTargetGroupPaths() != null) {
            targetGroupPaths.addAll(savedChat.getTargetGroupPaths());
        }
        if (savedChat.getMessages() != null) {
            for (SavedSwarmMessage message : savedChat.getMessages()) {
                if (message != null) {
                    messageEntries.add(new SavedSwarmMessage(message));
                    renderMessage(message);
                }
            }
        }
        // Re-resolve saved targets against currently open terminals (missing ones can be connected).
        List<ServerConnection> savedConnections = new ArrayList<>();
        if (savedChat.getTargetConnectionIds() != null) {
            for (String id : savedChat.getTargetConnectionIds()) {
                ServerConnection connection = KorTTYApplication.getInstance().getConfigManager().getConnectionById(id);
                if (connection != null) {
                    savedConnections.add(connection);
                }
            }
        }
        if (!savedConnections.isEmpty()) {
            SwarmTargetCollector.ResolveResult resolved =
                SwarmTargetCollector.resolveSelection(ownerWindow, savedConnections, includeLocalShell);
            for (SwarmTarget target : resolved.openTargets()) {
                addTarget(target);
            }
            missingConnections.clear();
            missingConnections.addAll(resolved.missing());
        }
        // Show the persisted final states in the status strip (static, no animation) until the
        // next run replaces them with live agents.
        List<SavedSwarmServerSummary> lastSummaries = List.of();
        for (int i = messageEntries.size() - 1; i >= 0; i--) {
            SavedSwarmMessage message = messageEntries.get(i);
            if (message != null && SavedSwarmMessage.ROLE_ASSISTANT.equals(message.getRole())
                && message.getServerSummaries() != null && !message.getServerSummaries().isEmpty()) {
                lastSummaries = message.getServerSummaries();
                break;
            }
        }
        statusStrip.showFinalSummaries(lastSummaries);
        refreshTargetLabels();
    }

    // ---- Helpers ------------------------------------------------------------

    private void openSwarmWorkflow() {
        AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
        String query = lastUserPrompt();
        SwarmWorkflowScriptDialog.open(ownerWindow, profile, query, connectionsForWorkflow(),
            getTabPane() != null && getTabPane().getScene() != null ? getTabPane().getScene().getWindow() : null);
    }

    /** Open-terminal targets plus selected-but-unconnected headless candidates: schedule/workflow drafts don't require an open terminal. */
    private List<ServerConnection> connectionsForWorkflow() {
        java.util.LinkedHashMap<String, ServerConnection> byId = new java.util.LinkedHashMap<>();
        for (SwarmTarget target : targets) {
            if (target.connection() != null && target.connection().getId() != null) {
                byId.put(target.connection().getId(), target.connection());
            }
        }
        for (ServerConnection connection : headlessCandidates()) {
            if (connection != null && connection.getId() != null) {
                byId.putIfAbsent(connection.getId(), connection);
            }
        }
        return new ArrayList<>(byId.values());
    }

    private String lastUserPrompt() {
        for (int i = messageEntries.size() - 1; i >= 0; i--) {
            SavedSwarmMessage message = messageEntries.get(i);
            if (message != null && SavedSwarmMessage.ROLE_USER.equals(message.getRole())) {
                return message.getContent();
            }
        }
        return "";
    }

    private void tickTimers() {
        for (SwarmAgentRow row : rowsByAgentId.values()) {
            row.tick();
        }
        statusStrip.tick();
        refreshAgentControlIndicators();
        // AI runs also get a real elapsed time from applySwarmState's periodic rollup; script runs
        // have no such rollup, so this tick is their only source of an advancing elapsed clock.
        if (busy) {
            refreshDashboardHeader();
        }
    }

    /** Scrolls the dashboard to the row of the clicked orb and flashes it briefly. */
    private void highlightAgentRow(String agentId) {
        SwarmAgentRow row = rowsByAgentId.get(agentId);
        if (row == null) {
            return;
        }
        double contentHeight = agentRowsBox.getBoundsInLocal().getHeight();
        double viewportHeight = dashboardScroll.getViewportBounds().getHeight();
        if (contentHeight > viewportHeight) {
            dashboardScroll.setVvalue(row.getBoundsInParent().getMinY() / (contentHeight - viewportHeight));
        }
        row.flash();
    }

    private void refreshDashboardHeader() {
        int done = 0;
        int failed = 0;
        int running = 0;
        for (SwarmAgentRow row : rowsByAgentId.values()) {
            switch (row.state == null ? SwarmModels.SwarmAgentState.QUEUED : row.state) {
                case DONE -> done++;
                case FAILED -> failed++;
                case CONNECTING, PROBING, RUNNING, AWAITING_APPROVAL, PAUSED -> running++;
                default -> {
                }
            }
        }
        long elapsed = busy && runStartMillis > 0
            ? Math.max(0L, (System.currentTimeMillis() - runStartMillis) / 1000L)
            : 0L;
        dashboardHeader.setText(I18n.get("ai.swarm.progress", running, done, failed, formatElapsed(elapsed)));
    }

    private void updateSendAvailability() {
        boolean hasPrompt = promptInputArea.getText() != null && !promptInputArea.getText().trim().isEmpty();
        sendButton.setDisable(busy || !hasRunnableTargets() || !hasPrompt
            || profileComboBox.getSelectionModel().getSelectedItem() == null);
        profileComboBox.setDisable(busy);
        readOnlyCheck.setDisable(busy);
        approvalComboBox.setDisable(busy);
        copyChatButton.setDisable(messageEntries.isEmpty());
        exportChatButton.setDisable(messageEntries.isEmpty());

        boolean paused = swarmControl != null && swarmControl.isSwarmPaused();
        boolean controls = controlsActive();
        pauseButton.setVisible(!paused);
        pauseButton.setManaged(!paused);
        resumeButton.setVisible(paused);
        resumeButton.setManaged(paused);
        pauseButton.setDisable(!controls);
        resumeButton.setDisable(!busy || !paused);
        stopButton.setDisable(!busy);
        restartButton.setDisable(scriptRunActive || lastSentPrompt == null || !hasRunnableTargets()
            || profileComboBox.getSelectionModel().getSelectedItem() == null);
        runScriptButton.setDisable(busy || !hasRunnableTargets());
        scheduleButton.setDisable(
            SwarmScheduleDraftSupport.sshConnectionIds(connectionsForWorkflow()).isEmpty()
                || SwarmScheduleDraftSupport.resolvePromptForDraft(messageEntries, promptInputArea.getText()) == null);
    }

    /**
     * Whether pause/restart/stop can still influence the run: once the orchestrator reached
     * AGGREGATING the coordinator loop no longer drains the control, so requests would be lost.
     */
    private boolean controlsActive() {
        return busy && !scriptRunActive && swarmControl != null
            && (lastSwarmPhase == null
                || lastSwarmPhase == SwarmModels.SwarmPhase.PREPARING
                || lastSwarmPhase == SwarmModels.SwarmPhase.CONNECTING
                || lastSwarmPhase == SwarmModels.SwarmPhase.RUNNING_AGENTS);
    }

    /** Rebuilt on every open so the enablement reflects the current state (F4 context menu). */
    private ContextMenu buildAgentContextMenu(String agentId, SwarmModels.SwarmAgentState state) {
        SwarmRunControl control = swarmControl;
        boolean active = controlsActive() && control != null && !control.isSwarmCancelled();
        boolean pauseRequested = active && control.isAgentPauseRequested(agentId);
        boolean workingState = state == SwarmModels.SwarmAgentState.CONNECTING
            || state == SwarmModels.SwarmAgentState.PROBING
            || state == SwarmModels.SwarmAgentState.RUNNING;
        boolean terminal = state == SwarmModels.SwarmAgentState.DONE
            || state == SwarmModels.SwarmAgentState.FAILED
            || state == SwarmModels.SwarmAgentState.CANCELLED
            || state == SwarmModels.SwarmAgentState.SKIPPED;

        MenuItem pauseItem = new MenuItem(I18n.get("ai.swarm.control.pause"));
        pauseItem.setDisable(!(active && workingState && !pauseRequested));
        pauseItem.setOnAction(e -> {
            if (swarmControl != null) {
                swarmControl.pauseAgent(agentId);
                refreshAgentControlIndicators();
            }
        });

        MenuItem resumeItem = new MenuItem(I18n.get("ai.swarm.control.resume"));
        resumeItem.setDisable(!(active
            && (state == SwarmModels.SwarmAgentState.PAUSED || pauseRequested)
            && !control.isSwarmPaused()));
        resumeItem.setOnAction(e -> {
            if (swarmControl != null) {
                swarmControl.resumeAgent(agentId);
                refreshAgentControlIndicators();
            }
        });

        MenuItem restartItem = new MenuItem(I18n.get("ai.swarm.control.restart"));
        restartItem.setDisable(!(active && state != SwarmModels.SwarmAgentState.QUEUED));
        restartItem.setOnAction(e -> {
            if (swarmControl != null) {
                swarmControl.requestRestart(agentId);
                refreshAgentControlIndicators();
            }
        });

        MenuItem stopItem = new MenuItem(I18n.get("ai.swarm.control.stop"));
        stopItem.setDisable(!(active && !terminal));
        stopItem.setOnAction(e -> {
            if (swarmControl != null) {
                swarmControl.stopAgent(agentId);
                refreshAgentControlIndicators();
            }
        });

        return new ContextMenu(pauseItem, resumeItem, new SeparatorMenuItem(), restartItem, stopItem);
    }

    /** Mirrors pending pause requests onto the row badges ("Pausing…" until the agent parks). */
    private void refreshAgentControlIndicators() {
        SwarmRunControl control = swarmControl;
        for (Map.Entry<String, SwarmAgentRow> entry : rowsByAgentId.entrySet()) {
            boolean pending = control != null && busy && control.isAgentPauseRequested(entry.getKey());
            entry.getValue().setPausePending(pending);
        }
    }

    private void showError(String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(I18n.get("ai.swarm.window.title"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        if (getTabPane() != null && getTabPane().getScene() != null) {
            alert.initOwner(getTabPane().getScene().getWindow());
        }
        alert.showAndWait();
    }

    /** Frame around the composer: lighter surface + visible border, accent border while focused. */
    private void initComposerFrameStyles() {
        ThemeCssSupport.ThemeColors colors = ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance());
        Color bg;
        try {
            bg = Color.web(colors != null ? colors.backgroundColor() : "#1f2933");
        } catch (Exception e) {
            bg = Color.web("#1f2933");
        }
        double luminance = 0.299 * bg.getRed() + 0.587 * bg.getGreen() + 0.114 * bg.getBlue();
        Color blend = luminance < 0.5 ? Color.WHITE : Color.BLACK;
        String frameBg = ThemeCssSupport.toHex(bg.interpolate(blend, 0.08));
        String border = ThemeCssSupport.toHex(bg.interpolate(blend, 0.35));
        String common = "-fx-background-color: " + frameBg + "; -fx-background-radius: 8; -fx-border-radius: 8;";
        composerFrameBaseStyle = common + " -fx-border-width: 1; -fx-border-color: " + border + ";";
        composerFrameFocusStyle = common + " -fx-border-width: 1.5; -fx-border-color: #0066cc;";
    }

    /**
     * Colors the top toolbar from the active theme so its labels stay readable in any theme. The
     * shared stylesheet does not theme {@code .tool-bar}, which otherwise keeps a light default
     * background under the (theme-foreground-colored) labels.
     */
    private void applyTopBarTheme(ToolBar bar, Label... labels) {
        ThemeCssSupport.ThemeColors colors = ThemeCssSupport.resolveThemeColors(KorTTYApplication.getInstance());
        if (colors == null) {
            return;
        }
        bar.setStyle("-fx-background-color: " + colors.backgroundColor() + ";");
        String labelStyle = "-fx-text-fill: " + colors.foregroundColor() + ";";
        for (Label label : labels) {
            if (label != null) {
                label.setStyle(labelStyle);
            }
        }
        readOnlyCheck.setStyle(labelStyle);
    }

    private static String formatElapsed(long seconds) {
        long s = Math.max(0L, seconds);
        return String.format(Locale.ROOT, "%02d:%02d", s / 60L, s % 60L);
    }

    /** One dashboard row for a single per-server agent; expands inline to a live transcript view. */
    private final class SwarmAgentRow extends VBox {
        private static final String BASE_STYLE =
            "-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 6;";

        private final String agentId;
        private final String displayName;
        private final Label chevron = new Label("▸");
        private final Label badge = new Label();
        private final Label nameLabel;
        private final Label activityLabel = new Label();
        private final Label metaLabel = new Label();
        private final HBox header;
        private TextArea detailArea;
        private boolean expanded;
        private boolean pausePending;

        private SwarmModels.SwarmAgentState state = SwarmModels.SwarmAgentState.QUEUED;
        private String lastActivity = "";
        private long elapsedSeconds;
        private long totalTokens;
        private long startedAtMillis;

        SwarmAgentRow(String agentId, String displayName) {
            super(4);
            this.agentId = agentId != null ? agentId : "";
            this.displayName = displayName != null ? displayName : "";
            this.nameLabel = new Label(this.displayName);
            this.nameLabel.setStyle("-fx-font-weight: bold;");
            setPadding(new Insets(4, 8, 4, 8));
            setStyle(BASE_STYLE);
            chevron.setStyle("-fx-text-fill: gray;");
            activityLabel.setStyle("-fx-text-fill: gray;");
            metaLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
            HBox.setHgrow(activityLabel, Priority.ALWAYS);
            activityLabel.setMaxWidth(Double.MAX_VALUE);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            header = new HBox(8, chevron, badge, nameLabel, activityLabel, spacer, metaLabel);
            header.setAlignment(Pos.CENTER_LEFT);
            header.setCursor(Cursor.HAND);
            header.setOnMouseClicked(e -> {
                if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 1) {
                    toggleExpanded();
                }
            });
            // Note: over the expanded TextArea its built-in edit menu wins; the agent menu is
            // reachable via the header and the row background.
            setOnContextMenuRequested(e -> {
                ContextMenu menu = buildAgentContextMenu(this.agentId, this.state);
                menu.setAutoHide(true);
                menu.show(this, e.getScreenX(), e.getScreenY());
                e.consume();
            });
            getChildren().add(header);
            applyBadge();
        }

        /** Shows "Pausing…" until the parked PAUSED status arrives from the agent. */
        void setPausePending(boolean value) {
            if (this.pausePending != value) {
                this.pausePending = value;
                applyBadge();
            }
        }

        void toggleExpanded() {
            setExpanded(!expanded);
        }

        void setExpanded(boolean value) {
            if (value == expanded) {
                return;
            }
            expanded = value;
            chevron.setText(expanded ? "▾" : "▸");
            if (expanded) {
                if (detailArea == null) {
                    detailArea = new TextArea();
                    detailArea.setEditable(false);
                    detailArea.setWrapText(true);
                    detailArea.setPrefRowCount(10);
                    detailArea.setMaxHeight(220);
                    detailArea.setPromptText(I18n.get("ai.swarm.detail.empty"));
                    detailArea.setStyle("-fx-font-family: 'monospace'; -fx-font-size: 11px;");
                }
                SwarmTranscriptBuffer buffer = transcriptBuffers.get(agentId);
                setDetail(buffer != null ? buffer.snapshot() : "");
                getChildren().add(detailArea);
            } else {
                getChildren().remove(detailArea);
            }
        }

        boolean isExpanded() {
            return expanded;
        }

        void appendDetail(String chunk) {
            if (detailArea != null) {
                detailArea.appendText(chunk);
            }
        }

        void setDetail(String fullText) {
            if (detailArea != null) {
                detailArea.setText(fullText != null ? fullText : "");
                detailArea.positionCaret(detailArea.getText().length());
            }
        }

        void update(SwarmModels.SwarmAgentStatus status) {
            this.state = status.state();
            this.lastActivity = status.currentActivity() != null ? status.currentActivity() : "";
            this.elapsedSeconds = status.elapsedSeconds();
            this.totalTokens = status.tokens() != null ? status.tokens().total() : 0L;
            // Every status carries the agent's authoritative elapsed (pause-adjusted, restart-reset);
            // rebase unconditionally so the local 1s tick merely interpolates between statuses.
            if (!isTerminal()) {
                startedAtMillis = System.currentTimeMillis() - status.elapsedSeconds() * 1000L;
            }
            activityLabel.setText(lastActivity);
            applyBadge();
            refreshMeta();
        }

        void tick() {
            // PAUSED freezes the visible timer; the authoritative pause-adjusted elapsed arrives
            // with the resume status and rebases the local clock.
            if (!isTerminal() && state != SwarmModels.SwarmAgentState.PAUSED && startedAtMillis > 0) {
                elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1000L);
                refreshMeta();
            }
        }

        /** Briefly outlines the row after its orb was clicked in the status strip. */
        void flash() {
            setStyle(BASE_STYLE + " -fx-border-color: #4f9cf0; -fx-border-radius: 6; -fx-border-width: 1.5;");
            PauseTransition reset = new PauseTransition(Duration.seconds(1.2));
            reset.setOnFinished(e -> setStyle(BASE_STYLE));
            reset.play();
        }

        private boolean isTerminal() {
            return state == SwarmModels.SwarmAgentState.DONE
                || state == SwarmModels.SwarmAgentState.FAILED
                || state == SwarmModels.SwarmAgentState.CANCELLED
                || state == SwarmModels.SwarmAgentState.SKIPPED;
        }

        private void refreshMeta() {
            String tokens = totalTokens > 0 ? I18n.get("ai.swarm.tokens", totalTokens) + "  " : "";
            metaLabel.setText(tokens + formatElapsed(elapsedSeconds));
        }

        private void applyBadge() {
            boolean showPending = pausePending
                && !isTerminal()
                && state != SwarmModels.SwarmAgentState.PAUSED;
            badge.setText(showPending
                ? I18n.get("ai.swarm.status.pausePending")
                : statusLabel(state));
            String color = showPending ? "#512da8" : switch (state) {
                case DONE -> "#2e7d32";
                case FAILED -> "#c62828";
                case CANCELLED, SKIPPED -> "#757575";
                case AWAITING_APPROVAL -> "#e65100";
                case PAUSED -> "#512da8";
                default -> "#1565c0";
            };
            badge.setStyle("-fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 1 6 1 6;"
                + " -fx-font-size: 11px; -fx-background-color: " + color + ";"
                + (showPending ? " -fx-opacity: 0.75;" : ""));
        }

        private String statusLabel(SwarmModels.SwarmAgentState state) {
            return switch (state) {
                case QUEUED -> I18n.get("ai.swarm.status.queued");
                case CONNECTING -> I18n.get("ai.swarm.status.connecting");
                case PROBING -> I18n.get("ai.swarm.status.probing");
                case RUNNING -> I18n.get("ai.swarm.status.running");
                case AWAITING_APPROVAL -> I18n.get("ai.swarm.status.awaitingApproval");
                case PAUSED -> I18n.get("ai.swarm.status.paused");
                case DONE -> I18n.get("ai.swarm.status.done");
                case FAILED -> I18n.get("ai.swarm.status.failed");
                case CANCELLED -> I18n.get("ai.swarm.status.cancelled");
                case SKIPPED -> I18n.get("ai.swarm.status.skipped");
            };
        }
    }
}
