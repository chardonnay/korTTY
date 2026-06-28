package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.TerminalAgentService;
import de.kortty.core.swarm.SwarmCallback;
import de.kortty.core.swarm.SwarmModels;
import de.kortty.core.swarm.SwarmTarget;
import de.kortty.model.AiProfile;
import de.kortty.model.SavedSwarmChat;
import de.kortty.model.SavedSwarmMessage;
import de.kortty.model.SavedSwarmServerSummary;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalAgentModels;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.Separator;
import javafx.scene.control.SplitPane;
import javafx.scene.control.Tab;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextInputDialog;
import javafx.scene.control.ToolBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Dedicated AI swarm window: a live per-server agent dashboard on the left and a chat (query +
 * bundled answer) on the right. Targets are picked from the connection manager; already-open
 * connections are used immediately and missing ones can be connected on demand.
 */
public class SwarmAgentTab extends Tab {

    private static final int FONT_SIZE = 13;

    private final MainWindow ownerWindow;
    private String languageCode;

    private final ComboBox<AiProfile> profileComboBox = new ComboBox<>();
    private final CheckBox readOnlyCheck = new CheckBox(I18n.get("ai.swarm.readOnly"));
    private final ComboBox<SwarmModels.BatchApprovalPolicy> approvalComboBox = new ComboBox<>();
    private final TextArea promptInputArea = new TextArea();
    private final Button sendButton = new Button(I18n.get("ai.swarm.send"));
    private final Button cancelButton = new Button(I18n.get("ai.swarm.cancel"));
    private final Button connectMissingButton = new Button();
    private final Label targetLabel = new Label(I18n.get("ai.swarm.target.none"));
    private final Label dashboardHeader = new Label();
    private final VBox agentRowsBox = new VBox(6);
    private final VBox messagesBox = new VBox(12);
    private final ScrollPane messagesScrollPane;

    private final Map<String, SwarmAgentRow> rowsByAgentId = new LinkedHashMap<>();
    private final List<SwarmTarget> targets = new ArrayList<>();
    private final List<ServerConnection> missingConnections = new ArrayList<>();
    private final List<SavedSwarmMessage> messageEntries = new ArrayList<>();
    private final List<String> targetGroupPaths = new ArrayList<>();

    private boolean includeLocalShell;
    private boolean busy;
    private AtomicBoolean swarmCancelled;
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
        setOnClosed(event -> ownerWindow.unregisterSavedSwarmChatTab(savedChatId));

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

        cancelButton.setOnAction(e -> cancelSwarm());
        cancelButton.setDisable(true);

        ToolBar toolBar = new ToolBar(
            pickButton, connectMissingButton, new Separator(),
            new Label(I18n.get("ai.result.profile")), profileComboBox,
            readOnlyCheck,
            new Label(I18n.get("ai.swarm.approvalPolicy")), approvalComboBox,
            new Separator(),
            workflowButton, saveButton, cancelButton);

        // Dashboard (left)
        dashboardHeader.setStyle("-fx-font-weight: bold;");
        ScrollPane dashboardScroll = new ScrollPane(agentRowsBox);
        dashboardScroll.setFitToWidth(true);
        agentRowsBox.setPadding(new Insets(6));
        VBox dashboard = new VBox(6, new Label(I18n.get("ai.swarm.dashboard.title")), targetLabel, dashboardHeader, dashboardScroll);
        dashboard.setPadding(new Insets(8));
        VBox.setVgrow(dashboardScroll, Priority.ALWAYS);

        // Chat (right)
        messagesBox.setFillWidth(true);
        messagesScrollPane = new ScrollPane(messagesBox);
        messagesScrollPane.setFitToWidth(true);
        promptInputArea.setWrapText(true);
        promptInputArea.setPrefRowCount(3);
        promptInputArea.setPromptText(I18n.get("ai.swarm.composer.placeholder"));
        promptInputArea.textProperty().addListener((obs, oldValue, newValue) -> updateSendAvailability());
        sendButton.setDefaultButton(true);
        sendButton.setMinWidth(110);
        sendButton.setOnAction(e -> sendSwarm());
        HBox.setHgrow(promptInputArea, Priority.ALWAYS);
        HBox composer = new HBox(10, promptInputArea, sendButton);
        composer.setAlignment(Pos.BOTTOM_RIGHT);
        VBox chat = new VBox(8, new Label(I18n.get("ai.swarm.chat.title")), messagesScrollPane, composer);
        chat.setPadding(new Insets(8));
        VBox.setVgrow(messagesScrollPane, Priority.ALWAYS);

        SplitPane split = new SplitPane(dashboard, chat);
        split.setDividerPositions(0.42);

        BorderPane content = new BorderPane();
        content.setTop(toolBar);
        content.setCenter(split);
        setContent(content);

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
        agentRowsBox.getChildren().clear();
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
        SwarmAgentRow row = new SwarmAgentRow(target.displayName());
        rowsByAgentId.put(target.agentId(), row);
        agentRowsBox.getChildren().add(row);
    }

    private void refreshTargetLabels() {
        if (targets.isEmpty()) {
            targetLabel.setText(I18n.get("ai.swarm.target.none"));
        } else {
            targetLabel.setText(I18n.get("ai.swarm.target.selected", targets.size())
                + "  •  " + I18n.get("ai.swarm.target.openCount", targets.size()));
        }
        connectMissingButton.setText(I18n.get("ai.swarm.target.connectCount", missingConnections.size()));
        connectMissingButton.setDisable(busy || missingConnections.isEmpty());
        updateSendAvailability();
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
        if (targets.isEmpty()) {
            showError(I18n.get("ai.swarm.error.noTargets"));
            return;
        }
        appendUserMessage(prompt);
        promptInputArea.clear();

        boolean readOnly = readOnlyCheck.isSelected();
        SwarmModels.BatchApprovalPolicy policy = readOnly
            ? SwarmModels.BatchApprovalPolicy.READ_ONLY
            : approvalComboBox.getValue();
        SwarmModels.SwarmRequest request = new SwarmModels.SwarmRequest(
            prompt, profile.getId(), SwarmModels.SwarmSource.CONNECTION_SELECTION,
            includeLocalShell, readOnly, 4, policy);

        // Re-key rows by the (stable) target agentIds for this run.
        rowsByAgentId.clear();
        agentRowsBox.getChildren().clear();
        for (SwarmTarget target : targets) {
            SwarmAgentRow row = new SwarmAgentRow(target.displayName());
            rowsByAgentId.put(target.agentId(), row);
            agentRowsBox.getChildren().add(row);
        }

        busy = true;
        swarmCancelled = new AtomicBoolean(false);
        AtomicBoolean cancelled = swarmCancelled;
        timer.playFromStart();
        updateSendAvailability();

        SwarmCallback callback = new SwarmCallback() {
            @Override
            public void onSwarmState(SwarmModels.SwarmRunState state) {
                Platform.runLater(() -> applySwarmState(state));
            }

            @Override
            public void onAgentStatus(SwarmModels.SwarmAgentStatus status) {
                Platform.runLater(() -> applyAgentStatus(status));
            }

            @Override
            public void onAgentTranscript(String agentId, String chunk) {
            }

            @Override
            public void onAggregationResult(SwarmModels.SwarmAggregationResult result) {
                Platform.runLater(() -> finishSwarm(result));
            }

            @Override
            public TerminalAgentService.ApprovalDecision requestBatchApproval(
                TerminalAgentModels.Approval approval, String agentId) {
                return requestApprovalBlocking(approval, agentId);
            }

            @Override
            public TerminalAgentModels.PasswordResponse requestPassword(
                TerminalAgentModels.PasswordRequest passwordRequest, String agentId) {
                return null;
            }

            @Override
            public boolean isCancelled() {
                return cancelled.get();
            }
        };
        ownerWindow.startSwarm(request, new ArrayList<>(targets), profile, callback);
    }

    private void applySwarmState(SwarmModels.SwarmRunState state) {
        if (state == null) {
            return;
        }
        long seconds = Math.max(0L, state.elapsedSeconds());
        dashboardHeader.setText(I18n.get("ai.swarm.progress",
            state.running(), state.done(), state.failed(), formatElapsed(seconds)));
    }

    private void applyAgentStatus(SwarmModels.SwarmAgentStatus status) {
        if (status == null) {
            return;
        }
        SwarmAgentRow row = rowsByAgentId.get(status.agentId());
        if (row != null) {
            row.update(status);
        }
    }

    private void finishSwarm(SwarmModels.SwarmAggregationResult result) {
        timer.stop();
        busy = false;
        swarmCancelled = null;
        if (result != null && result.markdown() != null && !result.markdown().isBlank()) {
            appendAssistantMessage(result.markdown(), buildServerSummaries());
        }
        refreshDashboardHeader();
        updateSendAvailability();
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

    private void cancelSwarm() {
        if (swarmCancelled != null) {
            swarmCancelled.set(true);
        }
    }

    private TerminalAgentService.ApprovalDecision requestApprovalBlocking(
        TerminalAgentModels.Approval approval, String agentId) {
        CompletableFuture<TerminalAgentService.ApprovalDecision> future = new CompletableFuture<>();
        SwarmAgentRow row = rowsByAgentId.get(agentId);
        String serverName = row != null ? row.displayName : agentId;
        Runnable show = () -> {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle(I18n.get("ai.swarm.approve.title"));
            alert.setHeaderText(null);
            StringBuilder commands = new StringBuilder();
            if (approval != null && approval.commands() != null) {
                for (TerminalAgentModels.PlannedCommand command : approval.commands()) {
                    if (command != null && command.command() != null) {
                        commands.append(command.command()).append('\n');
                    }
                }
            }
            alert.setContentText(I18n.get("ai.swarm.approve.message", serverName, commands.toString().trim()));
            ButtonType approveAll = new ButtonType(I18n.get("ai.swarm.approve.approveAll"), ButtonBar.ButtonData.OK_DONE);
            ButtonType cancel = new ButtonType(I18n.get("ai.swarm.approve.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
            alert.getButtonTypes().setAll(approveAll, cancel);
            Optional<ButtonType> choice = alert.showAndWait();
            future.complete(choice.isPresent() && choice.get() == approveAll
                ? TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS
                : TerminalAgentService.ApprovalDecision.CANCEL);
        };
        if (Platform.isFxApplicationThread()) {
            show.run();
        } else {
            Platform.runLater(show);
        }
        try {
            return future.get();
        } catch (Exception e) {
            return TerminalAgentService.ApprovalDecision.CANCEL;
        }
    }

    // ---- Chat / messages ----------------------------------------------------

    private void appendUserMessage(String content) {
        SavedSwarmMessage message = new SavedSwarmMessage();
        message.setRole(SavedSwarmMessage.ROLE_USER);
        message.setContent(content);
        messageEntries.add(message);
        renderMessage(message);
        persistQuietly();
    }

    private void appendAssistantMessage(String content, List<SavedSwarmServerSummary> summaries) {
        AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
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
    }

    private void renderMessage(SavedSwarmMessage message) {
        boolean assistant = SavedSwarmMessage.ROLE_ASSISTANT.equals(message.getRole());
        VBox card = new VBox(6);
        card.setFillWidth(true);
        card.setStyle(assistant
            ? "-fx-background-color: rgba(42,42,42,0.18); -fx-background-radius: 8; -fx-padding: 10;"
            : "-fx-background-color: rgba(0,102,204,0.08); -fx-background-radius: 8; -fx-padding: 10;");
        Label roleLabel = new Label(assistant ? I18n.get("ai.swarm.assistant") : I18n.get("ai.swarm.user"));
        roleLabel.setStyle("-fx-font-weight: bold;");
        card.getChildren().add(roleLabel);
        AiChatRenderSupport.renderInto(card, assistant, message.getContent(), FONT_SIZE);
        messagesBox.getChildren().add(card);
        Platform.runLater(() -> messagesScrollPane.setVvalue(1.0));
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
        refreshTargetLabels();
    }

    // ---- Helpers ------------------------------------------------------------

    private void openSwarmWorkflow() {
        AiProfile profile = profileComboBox.getSelectionModel().getSelectedItem();
        String query = lastUserPrompt();
        SwarmWorkflowScriptDialog.open(ownerWindow, profile, query, connectionsForWorkflow(),
            getTabPane() != null && getTabPane().getScene() != null ? getTabPane().getScene().getWindow() : null);
    }

    private List<ServerConnection> connectionsForWorkflow() {
        List<ServerConnection> connections = new ArrayList<>();
        for (SwarmTarget target : targets) {
            if (target.connection() != null) {
                connections.add(target.connection());
            }
        }
        return connections;
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
    }

    private void refreshDashboardHeader() {
        int done = 0;
        int failed = 0;
        int running = 0;
        for (SwarmAgentRow row : rowsByAgentId.values()) {
            switch (row.state == null ? SwarmModels.SwarmAgentState.QUEUED : row.state) {
                case DONE -> done++;
                case FAILED -> failed++;
                case CONNECTING, PROBING, RUNNING, AWAITING_APPROVAL -> running++;
                default -> {
                }
            }
        }
        dashboardHeader.setText(I18n.get("ai.swarm.progress", running, done, failed, formatElapsed(0)));
    }

    private void updateSendAvailability() {
        boolean hasPrompt = promptInputArea.getText() != null && !promptInputArea.getText().trim().isEmpty();
        sendButton.setDisable(busy || targets.isEmpty() || !hasPrompt
            || profileComboBox.getSelectionModel().getSelectedItem() == null);
        cancelButton.setDisable(!busy);
        profileComboBox.setDisable(busy);
        readOnlyCheck.setDisable(busy);
        approvalComboBox.setDisable(busy);
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

    private static String formatElapsed(long seconds) {
        long s = Math.max(0L, seconds);
        return String.format(Locale.ROOT, "%02d:%02d", s / 60L, s % 60L);
    }

    /** One dashboard row for a single per-server agent. */
    private final class SwarmAgentRow extends HBox {
        private final String displayName;
        private final Label badge = new Label();
        private final Label nameLabel;
        private final Label activityLabel = new Label();
        private final Label metaLabel = new Label();

        private SwarmModels.SwarmAgentState state = SwarmModels.SwarmAgentState.QUEUED;
        private String lastActivity = "";
        private long elapsedSeconds;
        private long totalTokens;
        private long startedAtMillis;

        SwarmAgentRow(String displayName) {
            super(8);
            this.displayName = displayName != null ? displayName : "";
            this.nameLabel = new Label(this.displayName);
            this.nameLabel.setStyle("-fx-font-weight: bold;");
            setAlignment(Pos.CENTER_LEFT);
            setPadding(new Insets(4, 8, 4, 8));
            setStyle("-fx-background-color: rgba(255,255,255,0.04); -fx-background-radius: 6;");
            activityLabel.setStyle("-fx-text-fill: gray;");
            metaLabel.setStyle("-fx-text-fill: gray; -fx-font-size: 11px;");
            HBox.setHgrow(activityLabel, Priority.ALWAYS);
            activityLabel.setMaxWidth(Double.MAX_VALUE);
            Region spacer = new Region();
            HBox.setHgrow(spacer, Priority.ALWAYS);
            getChildren().addAll(badge, nameLabel, activityLabel, spacer, metaLabel);
            applyBadge();
        }

        void update(SwarmModels.SwarmAgentStatus status) {
            this.state = status.state();
            this.lastActivity = status.currentActivity() != null ? status.currentActivity() : "";
            this.elapsedSeconds = status.elapsedSeconds();
            this.totalTokens = status.tokens() != null ? status.tokens().total() : 0L;
            if (startedAtMillis == 0 && !isTerminal()) {
                startedAtMillis = System.currentTimeMillis() - status.elapsedSeconds() * 1000L;
            }
            activityLabel.setText(lastActivity);
            applyBadge();
            refreshMeta();
        }

        void tick() {
            if (!isTerminal() && startedAtMillis > 0) {
                elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - startedAtMillis) / 1000L);
                refreshMeta();
            }
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
            badge.setText(statusLabel(state));
            String color = switch (state) {
                case DONE -> "#2e7d32";
                case FAILED -> "#c62828";
                case CANCELLED, SKIPPED -> "#757575";
                case AWAITING_APPROVAL -> "#e65100";
                default -> "#1565c0";
            };
            badge.setStyle("-fx-text-fill: white; -fx-background-radius: 4; -fx-padding: 1 6 1 6;"
                + " -fx-font-size: 11px; -fx-background-color: " + color + ";");
        }

        private String statusLabel(SwarmModels.SwarmAgentState state) {
            return switch (state) {
                case QUEUED -> I18n.get("ai.swarm.status.queued");
                case CONNECTING -> I18n.get("ai.swarm.status.connecting");
                case PROBING -> I18n.get("ai.swarm.status.probing");
                case RUNNING -> I18n.get("ai.swarm.status.running");
                case AWAITING_APPROVAL -> I18n.get("ai.swarm.status.awaitingApproval");
                case DONE -> I18n.get("ai.swarm.status.done");
                case FAILED -> I18n.get("ai.swarm.status.failed");
                case CANCELLED -> I18n.get("ai.swarm.status.cancelled");
                case SKIPPED -> I18n.get("ai.swarm.status.skipped");
            };
        }
    }
}
