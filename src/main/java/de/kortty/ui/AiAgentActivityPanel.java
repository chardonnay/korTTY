package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.AiTokenUsageManager;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SnippetLanguageSupport;
import de.kortty.core.TerminalAgentActivityExportService;
import de.kortty.core.TerminalAgentService;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Snippet;
import de.kortty.model.SnippetCategory;
import de.kortty.model.TerminalAgentModels;
import de.kortty.model.Theme;
import javafx.animation.Animation;
import javafx.animation.FadeTransition;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Cursor;
import javafx.scene.Node;
import javafx.scene.Parent;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextArea;
import javafx.scene.control.Tooltip;
import javafx.scene.control.Alert;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Inline terminal-style activity feed for terminal-agent runs.
 */
public class AiAgentActivityPanel extends VBox {

    private static final Logger logger = LoggerFactory.getLogger(AiAgentActivityPanel.class);
    private static final double COLLAPSED_PANEL_HEIGHT = 70.0;
    private static final double MIN_PANEL_HEIGHT = 130.0;
    private static final double DEFAULT_PANEL_HEIGHT = 230.0;
    private static final double MIN_TERMINAL_HEIGHT = 100.0;
    private static final double DEFAULT_MAX_PANEL_HEIGHT = 720.0;
    private static final double MIN_ACTIVITY_FONT_SIZE = 10.0;
    private static final double DEFAULT_ACTIVITY_FONT_SIZE = 13.0;
    private static final double MAX_ACTIVITY_FONT_SIZE = 20.0;
    private static final double ACTIVITY_FONT_STEP = 1.0;
    private static final double PROMPT_VIEWER_MIN_HEIGHT = 42.0;
    private static final double PROMPT_VIEWER_PREF_HEIGHT = 46.0;
    private static final double PROMPT_VIEWER_MAX_HEIGHT = 54.0;
    private static final Duration EXPORT_STATUS_VISIBLE_DURATION = Duration.seconds(4);
    private static final DateTimeFormatter EXPORT_FILE_FORMAT = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    private final TerminalAgentActivityExportService exportService = new TerminalAgentActivityExportService();
    private final TextArea promptTextArea;
    private final Region headerBusyDot;
    private final Label headerBusyLabel;
    private final Label tokenLabel;
    private final Button previousRunButton;
    private final Button nextRunButton;
    private final Button reloadRunButton;
    private final ComboBox<TerminalAgentActivityExportService.Format> exportFormatComboBox;
    private final MenuButton exportButton;
    private final MenuItem exportCurrentRunItem;
    private final MenuItem exportAllRunsItem;
    private final Button decreaseFontButton;
    private final Button increaseFontButton;
    private final CheckBox expandAllCheckBox;
    private final CheckBox keepCollapsedCheckBox;
    private final CheckBox rememberLayoutCheckBox;
    private final Button cancelRunButton;
    private final Button collapseButton;
    private final Button closeButton;
    private final Region resizeHandle;
    private final HBox controls;
    private final ScrollPane scrollPane;
    private final VBox activityBox;
    private final VBox promptBox;
    private final Map<String, ActivityRow> rowsById = new LinkedHashMap<>();
    private final Set<String> accountedTokenActivityIds = new java.util.HashSet<>();
    private final List<RunSnapshot> runHistory = new ArrayList<>();

    private volatile CompletableFuture<TerminalAgentService.ApprovalDecision> pendingApproval;
    private volatile CompletableFuture<TerminalAgentModels.PasswordResponse> pendingPassword;
    private volatile boolean running;
    private Runnable cancelCallback;
    private RunSnapshot activeRunSnapshot;
    private ActivityRow lastThinkingRow;
    private PasswordField pendingPasswordField;
    private CheckBox pendingPasswordCacheCheckBox;
    private String pendingInputStatusText;
    private long reportedTokens;
    private boolean hasReportedTokens;
    private int historyIndex = -1;
    private boolean scrollToBottomPending;
    private double panelResizeStartY;
    private double panelResizeStartHeight;
    private double activityFontSize = DEFAULT_ACTIVITY_FONT_SIZE;
    private Timeline runningActivityTimer;
    private Timeline exportStatusClearTimer;
    private FadeTransition headerBusyPulseTransition;
    private long runStartedAtMillis = -1L;
    private boolean panelCollapsed;
    private boolean exportInProgress;
    private double expandedPanelHeight = DEFAULT_PANEL_HEIGHT;
    private String exportStatusText;
    private String activeThemeStylesheetUrl;

    public record RunMetadata(String profileId, String profileName, String modelName, String reasoningStatus) {
    }

    record ActivityVisual(String symbol, String styleClass) {
    }

    public AiAgentActivityPanel() {
        getStyleClass().add("ai-agent-activity-panel");
        setSpacing(8);
        setPadding(new Insets(10));
        setFocusTraversable(true);
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

        promptTextArea = new TextArea(I18n.get("ai.agent.title"));
        promptTextArea.getStyleClass().addAll("ai-agent-activity-title", "ai-agent-prompt-viewer");
        promptTextArea.setEditable(false);
        promptTextArea.setWrapText(true);
        promptTextArea.setPrefRowCount(2);
        promptTextArea.setFocusTraversable(false);
        promptTextArea.setMinWidth(0);
        promptTextArea.setMaxWidth(Double.MAX_VALUE);
        promptTextArea.setMinHeight(PROMPT_VIEWER_MIN_HEIGHT);
        promptTextArea.setPrefHeight(PROMPT_VIEWER_PREF_HEIGHT);
        promptTextArea.setMaxHeight(PROMPT_VIEWER_MAX_HEIGHT);
        headerBusyDot = new Region();
        headerBusyDot.getStyleClass().add("ai-agent-header-busy-dot");
        headerBusyDot.setMinSize(10, 10);
        headerBusyDot.setPrefSize(10, 10);
        headerBusyDot.setMaxSize(10, 10);
        headerBusyDot.setVisible(false);
        headerBusyDot.setManaged(false);
        headerBusyLabel = new Label();
        headerBusyLabel.getStyleClass().add("ai-agent-header-busy-label");
        headerBusyLabel.setVisible(false);
        headerBusyLabel.setManaged(false);
        tokenLabel = new Label(formatReportedTokens());
        tokenLabel.getStyleClass().add("ai-agent-activity-meta");
        previousRunButton = buildControlButton("\u25C0", I18n.get("ai.agent.control.previousRun"));
        previousRunButton.setOnAction(event -> showPreviousRun());
        nextRunButton = buildControlButton("\u25B6", I18n.get("ai.agent.control.nextRun"));
        nextRunButton.setOnAction(event -> showNextRun());
        reloadRunButton = buildControlButton("\u21BB", I18n.get("ai.agent.control.reloadRun"));
        reloadRunButton.setOnAction(event -> rerunSelectedRun());
        exportFormatComboBox = new ComboBox<>();
        exportFormatComboBox.getItems().setAll(TerminalAgentActivityExportService.Format.values());
        exportFormatComboBox.getSelectionModel().select(TerminalAgentActivityExportService.Format.MARKDOWN);
        exportFormatComboBox.setConverter(new StringConverter<>() {
            @Override
            public String toString(TerminalAgentActivityExportService.Format format) {
                return format != null ? I18n.get(exportFormatLabelKey(format)) : "";
            }

            @Override
            public TerminalAgentActivityExportService.Format fromString(String value) {
                return selectedExportFormat();
            }
        });
        exportFormatComboBox.getStyleClass().add("ai-agent-export-format");
        exportFormatComboBox.setFocusTraversable(false);
        exportCurrentRunItem = new MenuItem(I18n.get("ai.agent.export.currentRun"));
        exportCurrentRunItem.setOnAction(event -> exportCurrentRun());
        exportAllRunsItem = new MenuItem(I18n.get("ai.agent.export.allRuns"));
        exportAllRunsItem.setOnAction(event -> exportAllRuns());
        exportButton = new MenuButton(I18n.get("ai.agent.export"));
        exportButton.getStyleClass().add("ai-agent-font-button");
        exportButton.setFocusTraversable(false);
        exportButton.getItems().addAll(exportCurrentRunItem, exportAllRunsItem);
        decreaseFontButton = new Button("A-");
        decreaseFontButton.getStyleClass().add("ai-agent-font-button");
        decreaseFontButton.setFocusTraversable(false);
        decreaseFontButton.setOnAction(event -> adjustActivityFontSize(-ACTIVITY_FONT_STEP));
        increaseFontButton = new Button("A+");
        increaseFontButton.getStyleClass().add("ai-agent-font-button");
        increaseFontButton.setFocusTraversable(false);
        increaseFontButton.setOnAction(event -> adjustActivityFontSize(ACTIVITY_FONT_STEP));
        expandAllCheckBox = new CheckBox(I18n.get("ai.agent.option.expandAll"));
        expandAllCheckBox.getStyleClass().add("ai-agent-option-check");
        expandAllCheckBox.setFocusTraversable(false);
        expandAllCheckBox.setOnAction(event -> updateExpandAllPreference());
        keepCollapsedCheckBox = new CheckBox(I18n.get("ai.agent.option.keepCollapsed"));
        keepCollapsedCheckBox.getStyleClass().add("ai-agent-option-check");
        keepCollapsedCheckBox.setFocusTraversable(false);
        keepCollapsedCheckBox.setOnAction(event -> updateKeepCollapsedPreference());
        rememberLayoutCheckBox = new CheckBox(I18n.get("ai.agent.option.rememberSize"));
        rememberLayoutCheckBox.getStyleClass().add("ai-agent-option-check");
        rememberLayoutCheckBox.setFocusTraversable(false);
        rememberLayoutCheckBox.setOnAction(event -> updateRememberLayoutPreference());
        cancelRunButton = buildControlButton("\u25A0 " + I18n.get("dialog.cancel"), I18n.get("dialog.cancel"));
        cancelRunButton.getStyleClass().add("ai-agent-cancel-button");
        cancelRunButton.setOnAction(event -> requestCancel());
        cancelRunButton.setVisible(false);
        cancelRunButton.setManaged(false);
        collapseButton = buildControlButton("\u25BC", I18n.get("ai.agent.control.collapsePanel"));
        collapseButton.getStyleClass().add("ai-agent-collapse-button");
        collapseButton.setMinWidth(30);
        collapseButton.setPrefWidth(34);
        collapseButton.setOnAction(event -> toggleCollapsed());
        closeButton = new Button("x");
        closeButton.getStyleClass().add("ai-agent-close-button");
        closeButton.setFocusTraversable(false);
        closeButton.setDisable(true);
        closeButton.setTooltip(new Tooltip(I18n.get("dialog.close")));
        closeButton.setOnAction(event -> hideIfIdle());

        HBox centerStatus = new HBox(8, headerBusyDot, headerBusyLabel, cancelRunButton);
        centerStatus.getStyleClass().add("ai-agent-header-status");
        centerStatus.setAlignment(Pos.CENTER);
        centerStatus.setMinWidth(Region.USE_PREF_SIZE);

        HBox rightControls = new HBox(8, tokenLabel, collapseButton, closeButton);
        rightControls.getStyleClass().add("ai-agent-header-actions");
        rightControls.setAlignment(Pos.CENTER_RIGHT);
        rightControls.setMinWidth(Region.USE_PREF_SIZE);

        HBox header = new HBox(8, promptTextArea, centerStatus, rightControls);
        header.getStyleClass().add("ai-agent-header");
        header.setAlignment(Pos.CENTER_LEFT);
        HBox.setHgrow(promptTextArea, Priority.ALWAYS);

        controls = new HBox(
            8,
            previousRunButton,
            nextRunButton,
            reloadRunButton,
            exportFormatComboBox,
            exportButton,
            decreaseFontButton,
            increaseFontButton,
            expandAllCheckBox,
            keepCollapsedCheckBox,
            rememberLayoutCheckBox);
        controls.getStyleClass().add("ai-agent-control-row");
        controls.setAlignment(Pos.CENTER_LEFT);

        activityBox = new VBox(6);
        scrollPane = new ScrollPane(activityBox);
        scrollPane.getStyleClass().add("ai-agent-activity-scroll");
        scrollPane.setFitToWidth(true);
        scrollPane.setPrefViewportHeight(170);
        scrollPane.setMinHeight(60);
        scrollPane.setMaxHeight(Double.MAX_VALUE);
        VBox.setVgrow(scrollPane, Priority.ALWAYS);
        scrollPane.viewportBoundsProperty().addListener((observable, oldBounds, newBounds) -> scrollToBottom());
        activityBox.heightProperty().addListener((observable, oldHeight, newHeight) -> scrollToBottom());

        promptBox = new VBox(8);
        promptBox.getStyleClass().add("ai-agent-prompt-box");
        promptBox.setVisible(false);
        promptBox.setManaged(false);

        getChildren().addAll(resizeHandle, header, controls, scrollPane, promptBox);
        addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (handleTerminalKeyPressed(event)) {
                event.consume();
            }
        });
        loadPersistedLayoutSettings();
        applyActivityFontSize();
        applyCollapsedState();
        updateHistoryButtons();
    }

    public void beginRun(String userPrompt, Runnable cancelCallback) {
        beginRun(userPrompt, cancelCallback, null);
    }

    public void beginRun(String userPrompt, Runnable cancelCallback, Runnable reloadCallback) {
        beginRun(userPrompt, cancelCallback, reloadCallback, null);
    }

    public void beginRun(
        String userPrompt,
        Runnable cancelCallback,
        Runnable reloadCallback,
        RunMetadata metadata) {

        Runnable task = () -> {
            String title = userPrompt == null || userPrompt.isBlank() ? I18n.get("ai.agent.title") : userPrompt.trim();
            LocalDateTime startedAt = LocalDateTime.now();
            this.cancelCallback = cancelCallback;
            running = true;
            runStartedAtMillis = System.currentTimeMillis();
            pendingApproval = null;
            pendingPassword = null;
            pendingPasswordField = null;
            pendingPasswordCacheCheckBox = null;
            pendingInputStatusText = null;
            reportedTokens = 0L;
            hasReportedTokens = false;
            clearExportStatus();
            rowsById.clear();
            accountedTokenActivityIds.clear();
            lastThinkingRow = null;
            activeRunSnapshot = new RunSnapshot(title, userPrompt, reloadCallback, metadata, startedAt);
            runHistory.add(activeRunSnapshot);
            historyIndex = runHistory.size() - 1;
            activityBox.getChildren().clear();
            promptBox.getChildren().clear();
            promptBox.setVisible(false);
            promptBox.setManaged(false);
            promptTextArea.setText(title);
            tokenLabel.setText(formatReportedTokens());
            setVisible(true);
            setManaged(true);
            if (keepCollapsedCheckBox.isSelected()) {
                panelCollapsed = true;
            }
            startRunningActivityTimer();
            updateHeaderBusyState();
            applyCollapsedState();
            updateHistoryButtons();
        };
        runOnFx(task);
    }

    public void finishRun() {
        running = false;
        Runnable task = () -> {
            completePassword(null);
            clearPendingPrompt();
            stopRunningIndicators();
            stopRunningActivityTimer();
            stopHeaderBusyAnimation();
            runStartedAtMillis = -1L;
            updateHeaderBusyState();
            syncActiveSnapshotFromCurrentView();
            if (activeRunSnapshot != null && activeRunSnapshot.finishedAt == null) {
                activeRunSnapshot.finishedAt = LocalDateTime.now();
            }
            activeRunSnapshot = null;
            updateHistoryButtons();
        };
        runOnFx(task);
    }

    public void hideIfIdle() {
        Runnable task = () -> {
            if (running || exportInProgress) {
                return;
            }
            setVisible(false);
            setManaged(false);
        };
        runOnFx(task);
    }

    public void applyTheme(Theme theme) {
        runOnFx(() -> {
            if (activeThemeStylesheetUrl != null) {
                getStylesheets().remove(activeThemeStylesheetUrl);
                activeThemeStylesheetUrl = null;
            }
            String stylesheetUrl = ThemeCssSupport.getAgentActivityStylesheetUrl(theme);
            if (stylesheetUrl != null) {
                activeThemeStylesheetUrl = stylesheetUrl;
                if (!getStylesheets().contains(stylesheetUrl)) {
                    getStylesheets().add(stylesheetUrl);
                }
            }
        });
    }

    public boolean isRunning() {
        return running;
    }

    public boolean handleTerminalKeyPressed(KeyEvent event) {
        if (event == null || !running) {
            return false;
        }
        if (event.getCode() == KeyCode.R && event.isControlDown() && !event.isAltDown() && !event.isMetaDown()) {
            toggleLastThinkingDetails();
            return true;
        }
        if (isCancelKey(event)) {
            requestCancel();
            return true;
        }
        CompletableFuture<TerminalAgentModels.PasswordResponse> password = pendingPassword;
        if (password != null && !password.isDone()) {
            if (event.getCode() == KeyCode.ENTER) {
                submitPasswordIfPresent();
                return true;
            }
            if (pendingPasswordField != null && pendingPasswordField.isFocused()) {
                return false;
            }
            if (pendingPasswordField != null) {
                pendingPasswordField.requestFocus();
            }
            return false;
        }
        CompletableFuture<TerminalAgentService.ApprovalDecision> approval = pendingApproval;
        if (approval != null && !approval.isDone()) {
            TerminalAgentService.ApprovalDecision decision = decisionForKey(event);
            if (decision != null) {
                completeApproval(decision);
                return true;
            }
            return false;
        }
        return false;
    }

    public void publishActivity(TerminalAgentModels.AgentActivity activity) {
        if (activity == null) {
            return;
        }
        runOnFx(() -> applyActivity(activity));
    }

    public TerminalAgentService.ApprovalDecision requestApproval(TerminalAgentModels.Approval approval) {
        CompletableFuture<TerminalAgentService.ApprovalDecision> future = new CompletableFuture<>();
        runOnFx(() -> showApprovalPrompt(approval, future));
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return TerminalAgentService.ApprovalDecision.CANCEL;
        } catch (ExecutionException e) {
            return TerminalAgentService.ApprovalDecision.CANCEL;
        }
    }

    public TerminalAgentModels.PasswordResponse requestPassword(TerminalAgentModels.PasswordRequest request) {
        CompletableFuture<TerminalAgentModels.PasswordResponse> future = new CompletableFuture<>();
        runOnFx(() -> showPasswordPrompt(request, future));
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (ExecutionException e) {
            return null;
        }
    }

    public void requestCancel() {
        Runnable task = () -> {
            cancelRunButton.setDisable(true);
            completeApproval(TerminalAgentService.ApprovalDecision.CANCEL);
            completePassword(null);
            if (cancelCallback != null) {
                cancelCallback.run();
            }
        };
        runOnFx(task);
    }

    public void toggleThinkingDetails() {
        runOnFx(this::toggleLastThinkingDetails);
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
        scrollToBottom();
    }

    private double parentHeight() {
        Parent parent = getParent();
        if (parent instanceof Region region) {
            return region.getHeight();
        }
        return 0.0;
    }

    private void requestContainerLayout() {
        Parent parent = getParent();
        if (parent != null) {
            parent.requestLayout();
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

    static double collapsedPanelHeight() {
        return COLLAPSED_PANEL_HEIGHT;
    }

    private void adjustActivityFontSize(double delta) {
        activityFontSize = clampActivityFontSize(activityFontSize + delta);
        applyActivityFontSize();
        persistLayoutSettingsIfEnabled();
        scrollToBottom();
    }

    static double clampActivityFontSize(double requestedSize) {
        return Math.max(MIN_ACTIVITY_FONT_SIZE, Math.min(requestedSize, MAX_ACTIVITY_FONT_SIZE));
    }

    private void applyActivityFontSize() {
        applyFontSize(promptTextArea, activityFontSize);
        applyFontSize(headerBusyLabel, activityFontSize - 1.0);
        applyFontSize(tokenLabel, activityFontSize - 1.0);
        applyFontSize(previousRunButton, activityFontSize - 2.0);
        applyFontSize(nextRunButton, activityFontSize - 2.0);
        applyFontSize(reloadRunButton, activityFontSize - 2.0);
        applyFontSize(exportFormatComboBox, activityFontSize - 2.0);
        applyFontSize(exportButton, activityFontSize - 2.0);
        applyFontSize(decreaseFontButton, activityFontSize - 2.0);
        applyFontSize(increaseFontButton, activityFontSize - 2.0);
        applyFontSize(expandAllCheckBox, activityFontSize - 2.0);
        applyFontSize(keepCollapsedCheckBox, activityFontSize - 2.0);
        applyFontSize(rememberLayoutCheckBox, activityFontSize - 2.0);
        applyFontSize(cancelRunButton, activityFontSize - 2.0);
        applyFontSize(collapseButton, activityFontSize + 3.0);
        applyFontSize(closeButton, activityFontSize - 2.0);
        for (ActivityRow row : rowsById.values()) {
            row.applyFontSize();
        }
        applyFontSizeRecursively(promptBox);
    }

    private void applyFontSizeRecursively(Node node) {
        if (node == null) {
            return;
        }
        if (node instanceof Label || node instanceof Button || node instanceof CheckBox || node instanceof PasswordField) {
            applyFontSize(node, activityFontSize);
        }
        if (node instanceof Parent parent) {
            for (Node child : parent.getChildrenUnmodifiable()) {
                applyFontSizeRecursively(child);
            }
        }
    }

    private void applyFontSize(Node node, double size) {
        node.setStyle("-fx-font-size: " + clampActivityFontSize(size) + "px;");
    }

    private void applyActivity(TerminalAgentModels.AgentActivity activity) {
        accountTokens(activity);
        ActivityRow row = rowsById.get(activity.id());
        if (row == null) {
            row = new ActivityRow(activity);
            rowsById.put(activity.id(), row);
            activityBox.getChildren().add(row);
        } else {
            row.update(activity);
        }
        row.applyFontSize();
        if (activity.type() == TerminalAgentModels.AgentActivityType.THINKING) {
            lastThinkingRow = row;
        }
        syncActiveSnapshotFromCurrentView();
        scrollToBottom();
    }

    private Button buildControlButton(String text, String tooltip) {
        Button button = new Button(text);
        button.getStyleClass().add("ai-agent-font-button");
        button.setFocusTraversable(false);
        button.setTooltip(new Tooltip(tooltip));
        return button;
    }

    private void copyToClipboard(String value) {
        ClipboardContent content = new ClipboardContent();
        content.putString(value != null ? value : "");
        Clipboard.getSystemClipboard().setContent(content);
    }

    private void openSnippetEditor(String text, TerminalAgentModels.AgentActivity activity) {
        String snippetContent = stripActivityQuotePrefixes(text);
        if (snippetContent.isBlank()) {
            return;
        }
        KorTTYApplication application = KorTTYApplication.getInstance();
        var snippetManager = application != null ? application.getSnippetManager() : null;
        if (snippetManager == null) {
            showPanelMessage(
                Alert.AlertType.ERROR,
                I18n.get("ai.result.saveSnippet.failed"),
                "Snippet Manager not initialized");
            return;
        }

        Snippet snippet = new Snippet();
        snippet.setName(buildSnippetName(activity, snippetContent));
        snippet.setContent(snippetContent);
        snippet.setLanguage(SnippetLanguageSupport.detectSnippetLanguage("", snippetContent));
        snippet.setDescription(nonBlank(activity != null ? activity.summary() : null, ""));

        List<String> categoryNames = snippetManager.getAllCategories().stream()
            .map(SnippetCategory::getName)
            .filter(Objects::nonNull)
            .sorted(String::compareToIgnoreCase)
            .toList();

        SnippetEditDialog dialog = new SnippetEditDialog(snippet, categoryNames);
        Window owner = getScene() != null ? getScene().getWindow() : null;
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.showAndWait().ifPresent(savedSnippet -> {
            try {
                ensureSnippetCategoryExists(savedSnippet.getCategory());
                snippetManager.addSnippet(savedSnippet);
                snippetManager.save();
            } catch (Exception e) {
                logger.warn("Could not save terminal agent activity as snippet", e);
                showPanelMessage(
                    Alert.AlertType.ERROR,
                    I18n.get("ai.result.saveSnippet.failed"),
                    I18n.get("ai.agent.activity.openSnippetEditor.failed",
                        e.getMessage() != null ? e.getMessage() : e.toString()));
            }
        });
    }

    private void ensureSnippetCategoryExists(String categoryName) {
        String normalized = categoryName != null ? categoryName.trim() : "";
        if (normalized.isBlank()) {
            return;
        }
        KorTTYApplication application = KorTTYApplication.getInstance();
        var snippetManager = application != null ? application.getSnippetManager() : null;
        if (snippetManager != null && snippetManager.findCategoryByName(normalized).isEmpty()) {
            snippetManager.addCategory(new SnippetCategory(normalized));
        }
    }

    private String buildSnippetName(TerminalAgentModels.AgentActivity activity, String snippetContent) {
        String seed = nonBlank(
            activity != null ? nonBlank(activity.title(), activity.summary()) : "",
            firstNonBlankLine(snippetContent));
        String normalized = seed.replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            normalized = I18n.get("ai.agent.title");
        }
        return normalized.length() > 80 ? normalized.substring(0, 80).trim() : normalized;
    }

    private String firstNonBlankLine(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.lines()
            .map(String::trim)
            .filter(line -> !line.isBlank())
            .findFirst()
            .orElse("");
    }

    private void showPreviousRun() {
        if (running || historyIndex <= 0) {
            return;
        }
        displayRunSnapshot(historyIndex - 1);
    }

    private void showNextRun() {
        if (running || historyIndex < 0 || historyIndex >= runHistory.size() - 1) {
            return;
        }
        displayRunSnapshot(historyIndex + 1);
    }

    private void rerunSelectedRun() {
        if (running || historyIndex < 0 || historyIndex >= runHistory.size()) {
            return;
        }
        Runnable reloadCallback = runHistory.get(historyIndex).reloadCallback;
        if (reloadCallback != null) {
            reloadCallback.run();
        }
    }

    private void exportCurrentRun() {
        if (running || historyIndex < 0 || historyIndex >= runHistory.size()) {
            return;
        }
        exportRuns(List.of(runHistory.get(historyIndex)), "terminal-agent");
    }

    private void exportAllRuns() {
        if (running || runHistory.isEmpty()) {
            return;
        }
        exportRuns(List.copyOf(runHistory), "terminal-agent-all");
    }

    private void exportRuns(List<RunSnapshot> snapshots, String filePrefix) {
        if (snapshots == null || snapshots.isEmpty()) {
            return;
        }
        TerminalAgentActivityExportService.Format format = selectedExportFormat();
        File targetFile = chooseExportTarget(format, filePrefix);
        if (targetFile == null) {
            return;
        }
        try {
            TerminalAgentActivityExportService.ExportDocument document = buildExportDocument(snapshots);
            startExportTask(targetFile, format, document);
        } catch (Exception e) {
            logger.warn("Failed to export terminal AI agent activity", e);
            showExportMessage(
                Alert.AlertType.ERROR,
                e.getMessage() != null ? e.getMessage() : I18n.get("ai.agent.export.failed"));
        }
    }

    private void startExportTask(
        File targetFile,
        TerminalAgentActivityExportService.Format format,
        TerminalAgentActivityExportService.ExportDocument document) {

        setExportStatus(I18n.get("ai.agent.export.running", targetFile.getName()), true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                exportService.export(targetFile.toPath(), format, document);
                return null;
            }
        };
        task.setOnSucceeded(event ->
            setExportStatus(I18n.get("ai.agent.export.success", targetFile.getName()), false));
        task.setOnFailed(event -> {
            Throwable failure = task.getException();
            logger.warn("Failed to export terminal AI agent activity", failure);
            setExportStatus("", false);
            showExportMessage(
                Alert.AlertType.ERROR,
                failure != null && failure.getMessage() != null
                    ? failure.getMessage()
                    : I18n.get("ai.agent.export.failed"));
        });
        Thread thread = new Thread(task, "ai-agent-export");
        thread.setDaemon(true);
        thread.start();
    }

    private void setExportStatus(String message, boolean inProgress) {
        stopExportStatusClearTimer();
        exportInProgress = inProgress;
        exportStatusText = nonBlank(message, "");
        updateHeaderBusyState();
        updateHistoryButtons();
        if (!inProgress && !blank(exportStatusText)) {
            scheduleExportStatusClear();
        }
    }

    private void clearExportStatus() {
        stopExportStatusClearTimer();
        exportInProgress = false;
        exportStatusText = "";
    }

    private void scheduleExportStatusClear() {
        exportStatusClearTimer = new Timeline(new KeyFrame(EXPORT_STATUS_VISIBLE_DURATION, event -> {
            exportStatusText = "";
            updateHeaderBusyState();
        }));
        exportStatusClearTimer.play();
    }

    private void stopExportStatusClearTimer() {
        if (exportStatusClearTimer != null) {
            exportStatusClearTimer.stop();
            exportStatusClearTimer = null;
        }
    }

    private TerminalAgentActivityExportService.Format selectedExportFormat() {
        TerminalAgentActivityExportService.Format selected = exportFormatComboBox.getSelectionModel().getSelectedItem();
        return selected != null ? selected : TerminalAgentActivityExportService.Format.MARKDOWN;
    }

    private String exportFormatLabelKey(TerminalAgentActivityExportService.Format format) {
        return switch (format) {
            case MARKDOWN -> "ai.agent.export.format.markdown";
            case TEXT -> "ai.agent.export.format.text";
            case YAML -> "ai.agent.export.format.yaml";
            case XML -> "ai.agent.export.format.xml";
            case JSON -> "ai.agent.export.format.json";
            case PDF -> "ai.agent.export.format.pdf";
            case ASCIIDOCTOR -> "ai.agent.export.format.asciidoctor";
        };
    }

    private File chooseExportTarget(TerminalAgentActivityExportService.Format format, String filePrefix) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("ai.agent.export.title"));
        String safePrefix = filePrefix != null && !filePrefix.isBlank() ? filePrefix : "terminal-agent";
        chooser.setInitialFileName(exportFileStem(safePrefix, LocalDateTime.now()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get(format.filterKey()), "*" + format.extension()));
        chooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(I18n.get("connEdit.allFiles"), "*.*"));
        Window owner = getScene() != null ? getScene().getWindow() : null;
        return normalizeExportTargetFile(chooser.showSaveDialog(owner), format);
    }

    static String exportFileStem(String filePrefix, LocalDateTime timestamp) {
        String safePrefix = filePrefix != null && !filePrefix.isBlank() ? filePrefix : "terminal-agent";
        LocalDateTime safeTimestamp = timestamp != null ? timestamp : LocalDateTime.now();
        return safePrefix + "-" + safeTimestamp.format(EXPORT_FILE_FORMAT);
    }

    static File normalizeExportTargetFile(File selectedFile, TerminalAgentActivityExportService.Format format) {
        if (selectedFile == null || format == null || format.extension().isBlank()) {
            return selectedFile;
        }
        String extension = format.extension();
        String path = selectedFile.getPath();
        String lowerPath = path.toLowerCase(Locale.ROOT);
        String lowerExtension = extension.toLowerCase(Locale.ROOT);
        String duplicatedExtension = lowerExtension + lowerExtension;

        while (lowerPath.endsWith(duplicatedExtension)) {
            path = path.substring(0, path.length() - extension.length());
            lowerPath = path.toLowerCase(Locale.ROOT);
        }
        if (!lowerPath.endsWith(lowerExtension)) {
            path += extension;
        }
        return path.equals(selectedFile.getPath()) ? selectedFile : new File(path);
    }

    private TerminalAgentActivityExportService.ExportDocument buildExportDocument(List<RunSnapshot> snapshots) {
        List<TerminalAgentActivityExportService.Run> runs = snapshots.stream()
            .map(this::toExportRun)
            .toList();
        String title = runs.size() == 1 ? runs.getFirst().title() : I18n.get("ai.agent.export.documentTitle");
        return new TerminalAgentActivityExportService.ExportDocument(title, LocalDateTime.now(), runs);
    }

    private TerminalAgentActivityExportService.Run toExportRun(RunSnapshot snapshot) {
        List<TerminalAgentActivityExportService.Activity> activities = snapshot.activities.stream()
            .map(this::toExportActivity)
            .toList();
        return new TerminalAgentActivityExportService.Run(
            snapshot.title,
            snapshot.prompt,
            snapshot.profileId,
            snapshot.profileName,
            snapshot.modelName,
            snapshot.reasoningStatus,
            snapshot.startedAt,
            snapshot.finishedAt,
            snapshot.elapsedSeconds(),
            snapshot.hasReportedTokens,
            snapshot.reportedTokens,
            activities);
    }

    private TerminalAgentActivityExportService.Activity toExportActivity(TerminalAgentModels.AgentActivity activity) {
        return new TerminalAgentActivityExportService.Activity(
            activity.id(),
            activity.type(),
            activity.status(),
            activity.title(),
            activity.summary(),
            activity.detail(),
            activity.tokenUsage(),
            activity.elapsedSeconds());
    }

    private void showExportMessage(Alert.AlertType type, String message) {
        showPanelMessage(
            type,
            type == Alert.AlertType.ERROR ? I18n.get("ai.agent.export.failed") : I18n.get("ai.agent.export"),
            message);
    }

    private void showPanelMessage(Alert.AlertType type, String title, String message) {
        Alert alert = new Alert(type);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        Window owner = getScene() != null ? getScene().getWindow() : null;
        if (owner != null) {
            alert.initOwner(owner);
        }
        alert.showAndWait();
    }

    private void displayRunSnapshot(int requestedIndex) {
        if (requestedIndex < 0 || requestedIndex >= runHistory.size()) {
            return;
        }
        stopRunningIndicators();
        stopRunningActivityTimer();
        stopHeaderBusyAnimation();
        clearPendingPrompt();
        RunSnapshot snapshot = runHistory.get(requestedIndex);
        historyIndex = requestedIndex;
        activeRunSnapshot = null;
        rowsById.clear();
        accountedTokenActivityIds.clear();
        activityBox.getChildren().clear();
        lastThinkingRow = null;
        reportedTokens = snapshot.reportedTokens;
        hasReportedTokens = snapshot.hasReportedTokens;
        promptTextArea.setText(snapshot.title);
        tokenLabel.setText(formatReportedTokens());
        for (TerminalAgentModels.AgentActivity activity : snapshot.activities) {
            ActivityRow row = new ActivityRow(activity);
            rowsById.put(activity.id(), row);
            activityBox.getChildren().add(row);
            row.applyFontSize();
            if (activity.type() == TerminalAgentModels.AgentActivityType.THINKING) {
                lastThinkingRow = row;
            }
        }
        stopRunningIndicators();
        updateHeaderBusyState();
        setVisible(true);
        setManaged(true);
        applyCollapsedState();
        updateHistoryButtons();
        scrollToBottom();
    }

    private void syncActiveSnapshotFromCurrentView() {
        if (activeRunSnapshot == null) {
            return;
        }
        activeRunSnapshot.activities.clear();
        for (ActivityRow row : rowsById.values()) {
            if (row.currentActivity != null) {
                activeRunSnapshot.activities.add(row.currentActivity);
            }
        }
        activeRunSnapshot.reportedTokens = reportedTokens;
        activeRunSnapshot.hasReportedTokens = hasReportedTokens;
    }

    private void updateHistoryButtons() {
        boolean idle = !running && !exportInProgress;
        previousRunButton.setDisable(!idle || historyIndex <= 0);
        nextRunButton.setDisable(!idle || historyIndex < 0 || historyIndex >= runHistory.size() - 1);
        boolean canReload = idle
            && historyIndex >= 0
            && historyIndex < runHistory.size()
            && runHistory.get(historyIndex).reloadCallback != null;
        reloadRunButton.setDisable(!canReload);
        cancelRunButton.setDisable(!running);
        cancelRunButton.setVisible(running);
        cancelRunButton.setManaged(running);
        closeButton.setDisable(running || exportInProgress);
        boolean hasHistory = !runHistory.isEmpty();
        exportFormatComboBox.setDisable(!idle || !hasHistory);
        exportButton.setDisable(!idle || !hasHistory);
        exportCurrentRunItem.setDisable(!canExportCurrentRun(!idle, runHistory.size(), historyIndex));
        exportAllRunsItem.setDisable(!canExportAllRuns(!idle, runHistory.size()));
    }

    static boolean canExportCurrentRun(boolean running, int historySize, int selectedIndex) {
        return !running && selectedIndex >= 0 && selectedIndex < historySize;
    }

    static boolean canExportAllRuns(boolean running, int historySize) {
        return !running && historySize > 0;
    }

    private void updateHeaderBusyState() {
        boolean busy = running || exportInProgress;
        boolean showStatus = busy || !blank(exportStatusText);
        headerBusyDot.setVisible(busy);
        headerBusyDot.setManaged(busy);
        headerBusyLabel.setVisible(showStatus);
        headerBusyLabel.setManaged(showStatus);
        if (running) {
            updateHeaderBusyText();
            startHeaderBusyAnimation();
        } else if (exportInProgress) {
            headerBusyLabel.setText(nonBlank(exportStatusText, I18n.get("ai.agent.export")));
            startHeaderBusyAnimation();
        } else if (showStatus) {
            stopHeaderBusyAnimation();
            headerBusyLabel.setText(exportStatusText);
        } else {
            stopHeaderBusyAnimation();
            headerBusyLabel.setText("");
        }
    }

    private void updateHeaderBusyText() {
        long elapsedSeconds = elapsedSecondsSinceMillis(runStartedAtMillis, System.currentTimeMillis());
        String statusText = currentHeaderStatusText();
        headerBusyLabel.setText(formatHeaderBusyText(
            statusText,
            I18n.get("ai.agent.activity.elapsed", elapsedSeconds)));
    }

    private String currentHeaderStatusText() {
        CompletableFuture<TerminalAgentModels.PasswordResponse> password = pendingPassword;
        if (password != null && !password.isDone()) {
            return nonBlank(pendingInputStatusText, I18n.get("ai.agent.activity.passwordRequired"));
        }
        CompletableFuture<TerminalAgentService.ApprovalDecision> approval = pendingApproval;
        if (approval != null && !approval.isDone()) {
            return nonBlank(pendingInputStatusText, I18n.get("ai.agent.activity.inputRequired"));
        }
        return I18n.get("ai.agent.activity.running");
    }

    static String formatHeaderBusyText(String runningText, String elapsedText) {
        String runningPart = runningText == null || runningText.isBlank() ? "running" : runningText.trim();
        String elapsedPart = elapsedText == null || elapsedText.isBlank() ? "0s" : elapsedText.trim();
        return runningPart + " - " + elapsedPart;
    }

    private void toggleCollapsed() {
        runOnFx(() -> setPanelCollapsed(!panelCollapsed));
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
        boolean expanded = !panelCollapsed;
        resizeHandle.setVisible(expanded);
        resizeHandle.setManaged(expanded);
        controls.setVisible(expanded);
        controls.setManaged(expanded);
        scrollPane.setVisible(expanded);
        scrollPane.setManaged(expanded);
        updatePromptVisibility();
        collapseButton.setText(panelCollapsed ? "\u25B2" : "\u25BC");
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
            scrollToBottom();
        }
        requestContainerLayout();
    }

    private void updatePromptVisibility() {
        boolean showPrompt = !panelCollapsed && !promptBox.getChildren().isEmpty();
        promptBox.setVisible(showPrompt);
        promptBox.setManaged(showPrompt);
    }

    private void refreshDetailVisibility() {
        for (ActivityRow row : rowsById.values()) {
            row.update(row.currentActivity);
            row.applyFontSize();
        }
        scrollToBottom();
    }

    private void loadPersistedLayoutSettings() {
        GlobalSettings settings = globalSettings();
        if (settings == null) {
            return;
        }
        expandAllCheckBox.setSelected(settings.isTerminalAgentPanelExpandAll());
        keepCollapsedCheckBox.setSelected(settings.isTerminalAgentPanelKeepCollapsed());
        panelCollapsed = settings.isTerminalAgentPanelKeepCollapsed();
        if (!settings.isTerminalAgentRememberPanelLayout()) {
            return;
        }
        rememberLayoutCheckBox.setSelected(true);
        Double panelHeight = settings.getTerminalAgentPanelHeight();
        if (panelHeight != null) {
            expandedPanelHeight = clampPanelHeight(panelHeight, parentHeight());
            setPrefHeight(expandedPanelHeight);
        }
        Double fontSize = settings.getTerminalAgentPanelFontSize();
        if (fontSize != null) {
            activityFontSize = clampActivityFontSize(fontSize);
        }
    }

    private void updateExpandAllPreference() {
        GlobalSettingsManager manager = globalSettingsManager();
        if (manager != null && manager.getSettings() != null) {
            manager.getSettings().setTerminalAgentPanelExpandAll(expandAllCheckBox.isSelected());
            saveGlobalSettings(manager);
        }
        refreshDetailVisibility();
    }

    private void updateKeepCollapsedPreference() {
        GlobalSettingsManager manager = globalSettingsManager();
        if (manager != null && manager.getSettings() != null) {
            manager.getSettings().setTerminalAgentPanelKeepCollapsed(keepCollapsedCheckBox.isSelected());
            saveGlobalSettings(manager);
        }
        if (keepCollapsedCheckBox.isSelected()) {
            setPanelCollapsed(true);
        }
    }

    private void updateRememberLayoutPreference() {
        GlobalSettingsManager manager = globalSettingsManager();
        if (manager == null) {
            return;
        }
        GlobalSettings settings = manager.getSettings();
        if (settings == null) {
            return;
        }
        boolean rememberLayout = rememberLayoutCheckBox.isSelected();
        settings.setTerminalAgentRememberPanelLayout(rememberLayout);
        if (rememberLayout) {
            settings.setTerminalAgentPanelHeight(panelCollapsed ? expandedPanelHeight : currentPanelHeight());
            settings.setTerminalAgentPanelFontSize(activityFontSize);
        }
        saveGlobalSettings(manager);
    }

    private void persistLayoutSettingsIfEnabled() {
        if (!rememberLayoutCheckBox.isSelected()) {
            return;
        }
        GlobalSettingsManager manager = globalSettingsManager();
        if (manager == null || manager.getSettings() == null) {
            return;
        }
        manager.getSettings().setTerminalAgentPanelHeight(panelCollapsed ? expandedPanelHeight : currentPanelHeight());
        manager.getSettings().setTerminalAgentPanelFontSize(activityFontSize);
        saveGlobalSettings(manager);
    }

    private GlobalSettings globalSettings() {
        GlobalSettingsManager manager = globalSettingsManager();
        return manager != null ? manager.getSettings() : null;
    }

    private GlobalSettingsManager globalSettingsManager() {
        KorTTYApplication application = KorTTYApplication.getInstance();
        return application != null ? application.getGlobalSettingsManager() : null;
    }

    private void saveGlobalSettings(GlobalSettingsManager manager) {
        try {
            manager.save();
        } catch (Exception e) {
            logger.warn("Failed to save terminal agent panel layout settings", e);
        }
    }

    private void accountTokens(TerminalAgentModels.AgentActivity activity) {
        TerminalAgentModels.AgentActivityTokenUsage usage = activity.tokenUsage();
        if (usage == null || !usage.known() || usage.totalTokens() <= 0L || accountedTokenActivityIds.contains(activity.id())) {
            return;
        }
        accountedTokenActivityIds.add(activity.id());
        reportedTokens += usage.totalTokens();
        hasReportedTokens = true;
        tokenLabel.setText(formatReportedTokens());
    }

    private void showApprovalPrompt(
        TerminalAgentModels.Approval approval,
        CompletableFuture<TerminalAgentService.ApprovalDecision> future) {
        pendingApproval = future;
        pendingInputStatusText = I18n.get("ai.agent.activity.inputRequired");
        promptBox.getChildren().clear();
        Label label = new Label(approval != null && approval.userMessage() != null && !approval.userMessage().isBlank()
            ? approval.userMessage()
            : I18n.get("ai.agent.activity.approvalPrompt"));
        label.setWrapText(true);
        label.getStyleClass().add("ai-agent-prompt-label");
        promptBox.getChildren().add(label);

        if (approval != null && approval.commands() != null && !approval.commands().isEmpty()) {
            promptBox.getChildren().add(buildCommandPreview(approval.commands()));
        }

        Button onceButton = new Button("1 " + I18n.get("ai.agent.approval.once"));
        onceButton.setOnAction(event -> completeApproval(TerminalAgentService.ApprovalDecision.APPROVE_ONCE));
        Button alwaysButton = new Button("2 " + I18n.get("ai.agent.approval.always"));
        alwaysButton.setOnAction(event -> completeApproval(TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS));
        Button cancelButton = new Button("3 " + I18n.get("dialog.cancel"));
        cancelButton.setOnAction(event -> completeApproval(TerminalAgentService.ApprovalDecision.CANCEL));
        HBox buttons = new HBox(8, onceButton, alwaysButton, cancelButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        promptBox.getChildren().add(buttons);
        updatePromptVisibility();
        updateHeaderBusyState();
        applyFontSizeRecursively(promptBox);
        requestFocus();
        scrollToBottom();
    }

    private void showPasswordPrompt(
        TerminalAgentModels.PasswordRequest request,
        CompletableFuture<TerminalAgentModels.PasswordResponse> future) {
        pendingPassword = future;
        pendingInputStatusText = request != null && request.summary() != null && !request.summary().isBlank()
            ? request.summary().trim()
            : I18n.get("ai.agent.activity.passwordRequired");
        promptBox.getChildren().clear();

        Label label = new Label(request != null && request.userMessage() != null && !request.userMessage().isBlank()
            ? request.userMessage()
            : I18n.get("ai.agent.password.title"));
        label.setWrapText(true);
        label.getStyleClass().add("ai-agent-prompt-label");
        promptBox.getChildren().add(label);

        if (request != null && request.command() != null && !request.command().isBlank()) {
            VBox commandBox = new VBox(4);
            commandBox.getStyleClass().add("ai-agent-command-preview");
            Label commandLabel = new Label("$ " + request.command());
            commandLabel.getStyleClass().add("ai-agent-command-line");
            commandLabel.setWrapText(true);
            commandBox.getChildren().add(commandLabel);
            promptBox.getChildren().add(commandBox);
        }

        PasswordField passwordField = new PasswordField();
        passwordField.getStyleClass().add("ai-agent-password-field");
        passwordField.setPromptText(I18n.get("common.password"));
        passwordField.setOnAction(event -> submitPasswordIfPresent());
        passwordField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.ENTER) {
                submitPasswordIfPresent();
                event.consume();
            }
        });
        pendingPasswordField = passwordField;
        promptBox.getChildren().add(passwordField);

        CheckBox cacheForSessionCheckBox = new CheckBox(I18n.get("ai.agent.password.cacheForSession"));
        cacheForSessionCheckBox.getStyleClass().add("ai-agent-option-check");
        cacheForSessionCheckBox.setSelected(true);
        pendingPasswordCacheCheckBox = cacheForSessionCheckBox;
        promptBox.getChildren().add(cacheForSessionCheckBox);

        Button submitButton = new Button(I18n.get("dialog.ok"));
        submitButton.setDefaultButton(true);
        submitButton.disableProperty().bind(Bindings.createBooleanBinding(
            () -> !hasPasswordText(passwordField.getText()),
            passwordField.textProperty()));
        submitButton.setOnAction(event -> submitPasswordIfPresent());
        Button cancelButton = new Button(I18n.get("dialog.cancel"));
        cancelButton.setCancelButton(true);
        cancelButton.setOnAction(event -> completePassword(null));
        HBox buttons = new HBox(8, submitButton, cancelButton);
        buttons.setAlignment(Pos.CENTER_LEFT);
        promptBox.getChildren().add(buttons);

        updatePromptVisibility();
        updateHeaderBusyState();
        applyFontSizeRecursively(promptBox);
        Platform.runLater(passwordField::requestFocus);
        scrollToBottom();
    }

    private Node buildCommandPreview(List<TerminalAgentModels.PlannedCommand> commands) {
        VBox commandBox = new VBox(4);
        commandBox.getStyleClass().add("ai-agent-command-preview");
        for (TerminalAgentModels.PlannedCommand command : commands) {
            Label commandLabel = new Label("$ " + command.command());
            commandLabel.getStyleClass().add("ai-agent-command-line");
            commandLabel.setWrapText(true);
            Label purposeLabel = new Label("> " + command.purpose());
            purposeLabel.getStyleClass().add("ai-agent-detail");
            purposeLabel.setWrapText(true);
            commandBox.getChildren().addAll(commandLabel, purposeLabel);
        }
        return commandBox;
    }

    private void completeApproval(TerminalAgentService.ApprovalDecision decision) {
        CompletableFuture<TerminalAgentService.ApprovalDecision> future = pendingApproval;
        pendingApproval = null;
        pendingInputStatusText = null;
        clearPendingPrompt();
        updateHeaderBusyState();
        if (future != null && !future.isDone()) {
            future.complete(decision);
        }
    }

    private void submitPassword() {
        String password = pendingPasswordField != null ? pendingPasswordField.getText() : null;
        boolean cacheForSession = pendingPasswordCacheCheckBox == null || pendingPasswordCacheCheckBox.isSelected();
        completePassword(new TerminalAgentModels.PasswordResponse(password, cacheForSession));
    }

    private boolean submitPasswordIfPresent() {
        if (!hasPendingPasswordText()) {
            return false;
        }
        submitPassword();
        return true;
    }

    private boolean hasPendingPasswordText() {
        return pendingPasswordField != null && hasPasswordText(pendingPasswordField.getText());
    }

    private boolean hasPasswordText(String password) {
        return password != null && !password.isBlank();
    }

    private void completePassword(TerminalAgentModels.PasswordResponse passwordResponse) {
        CompletableFuture<TerminalAgentModels.PasswordResponse> future = pendingPassword;
        pendingPassword = null;
        pendingPasswordField = null;
        pendingPasswordCacheCheckBox = null;
        pendingInputStatusText = null;
        clearPendingPrompt();
        updateHeaderBusyState();
        if (future != null && !future.isDone()) {
            future.complete(passwordResponse);
        }
    }

    private void clearPendingPrompt() {
        promptBox.getChildren().clear();
        promptBox.setVisible(false);
        promptBox.setManaged(false);
    }

    private void scrollToBottom() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::scrollToBottom);
            return;
        }
        if (scrollToBottomPending) {
            return;
        }
        scrollToBottomPending = true;
        Platform.runLater(() -> {
            scrollToBottomPending = false;
            snapScrollToBottom();
            Platform.runLater(this::snapScrollToBottom);
        });
    }

    private void snapScrollToBottom() {
        scrollPane.applyCss();
        activityBox.applyCss();
        scrollPane.layout();
        activityBox.layout();
        scrollPane.setVvalue(scrollPane.getVmax());
    }

    private TerminalAgentService.ApprovalDecision decisionForKey(KeyEvent event) {
        KeyCode code = event.getCode();
        if (code == KeyCode.DIGIT1 || code == KeyCode.NUMPAD1 || code == KeyCode.ENTER) {
            return TerminalAgentService.ApprovalDecision.APPROVE_ONCE;
        }
        if (code == KeyCode.DIGIT2 || code == KeyCode.NUMPAD2) {
            return TerminalAgentService.ApprovalDecision.APPROVE_ALWAYS;
        }
        if (code == KeyCode.DIGIT3 || code == KeyCode.NUMPAD3) {
            return TerminalAgentService.ApprovalDecision.CANCEL;
        }
        return null;
    }

    private boolean isCancelKey(KeyEvent event) {
        return event.getCode() == KeyCode.ESCAPE
            || (event.getCode() == KeyCode.C && event.isControlDown() && !event.isAltDown() && !event.isMetaDown());
    }

    private void toggleLastThinkingDetails() {
        if (lastThinkingRow != null) {
            lastThinkingRow.toggleDetails();
            scrollToBottom();
        }
    }

    private void stopRunningIndicators() {
        for (ActivityRow row : rowsById.values()) {
            row.stopIndicator();
        }
    }

    private void startRunningActivityTimer() {
        if (runningActivityTimer == null) {
            runningActivityTimer = new Timeline(new KeyFrame(Duration.seconds(1), event -> refreshRunningActivityRows()));
            runningActivityTimer.setCycleCount(Animation.INDEFINITE);
        }
        runningActivityTimer.playFromStart();
    }

    private void stopRunningActivityTimer() {
        if (runningActivityTimer != null) {
            runningActivityTimer.stop();
        }
    }

    private void refreshRunningActivityRows() {
        if (running) {
            updateHeaderBusyText();
        }
        boolean updated = false;
        for (ActivityRow row : rowsById.values()) {
            updated = row.refreshRunningElapsed() || updated;
        }
        if (updated) {
            syncActiveSnapshotFromCurrentView();
            scrollToBottom();
        }
    }

    private void startHeaderBusyAnimation() {
        if (headerBusyPulseTransition == null) {
            headerBusyPulseTransition = new FadeTransition(Duration.seconds(0.65), headerBusyDot);
            headerBusyPulseTransition.setFromValue(1.0);
            headerBusyPulseTransition.setToValue(0.25);
            headerBusyPulseTransition.setAutoReverse(true);
            headerBusyPulseTransition.setCycleCount(Animation.INDEFINITE);
        }
        if (headerBusyPulseTransition.getStatus() != Animation.Status.RUNNING) {
            headerBusyPulseTransition.play();
        }
    }

    private void stopHeaderBusyAnimation() {
        if (headerBusyPulseTransition != null) {
            headerBusyPulseTransition.stop();
        }
        headerBusyDot.setOpacity(1.0);
    }

    static long elapsedSecondsSinceMillis(long startedAtMillis, long nowMillis) {
        if (startedAtMillis <= 0L || nowMillis <= startedAtMillis) {
            return 0L;
        }
        return Math.max(0L, (nowMillis - startedAtMillis) / 1_000L);
    }

    private String formatReportedTokens() {
        return hasReportedTokens
            ? I18n.get("ai.agent.activity.tokens.reported", AiTokenUsageManager.formatCompact(reportedTokens))
            : I18n.get("ai.agent.activity.tokens.unknown");
    }

    private String formatTokenUsage(TerminalAgentModels.AgentActivityTokenUsage usage) {
        if (usage == null || !usage.known()) {
            return I18n.get("ai.agent.activity.tokens.unknown.short");
        }
        return I18n.get("ai.agent.activity.tokens.short", AiTokenUsageManager.formatCompact(usage.totalTokens()));
    }

    private void runOnFx(Runnable task) {
        if (Platform.isFxApplicationThread()) {
            task.run();
        } else {
            Platform.runLater(task);
        }
    }

    static ActivityVisual activityVisual(TerminalAgentModels.AgentActivity activity) {
        if (activity == null) {
            return new ActivityVisual("i", "ai-agent-marker-info");
        }
        if (activity.type() == TerminalAgentModels.AgentActivityType.ERROR
            || activity.status() == TerminalAgentModels.AgentActivityStatus.FAILED) {
            return new ActivityVisual("!", "ai-agent-marker-error");
        }
        if (activity.status() == TerminalAgentModels.AgentActivityStatus.CANCELLED) {
            return new ActivityVisual("x", "ai-agent-marker-cancelled");
        }
        if (activity.type() == TerminalAgentModels.AgentActivityType.QUESTION) {
            return new ActivityVisual("?", "ai-agent-marker-question");
        }
        if (activity.type() == TerminalAgentModels.AgentActivityType.THINKING
            || activity.type() == TerminalAgentModels.AgentActivityType.ACTION) {
            String symbol = activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING ? "\u2191" : "\u2193";
            String styleClass = activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING
                ? "ai-agent-marker-input"
                : "ai-agent-marker-output";
            return new ActivityVisual(symbol, styleClass);
        }
        return new ActivityVisual("i", "ai-agent-marker-info");
    }

    static String copyTextForActivity(TerminalAgentModels.AgentActivity activity) {
        if (activity == null) {
            return "";
        }
        String detail = stripActivityQuotePrefixes(activity.detail());
        if (!detail.isBlank()) {
            return detail;
        }
        String summary = stripActivityQuotePrefixes(activity.summary());
        if (!summary.isBlank()) {
            return summary;
        }
        return stripActivityQuotePrefixes(activity.title());
    }

    static String stripActivityQuotePrefixes(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        return text.lines()
            .map(AiAgentActivityPanel::stripActivityQuotePrefix)
            .collect(Collectors.joining("\n"))
            .stripTrailing();
    }

    private static String stripActivityQuotePrefix(String line) {
        if (line == null || line.isBlank()) {
            return line != null ? line : "";
        }
        return line.replaceFirst("^\\s*>\\s?", "");
    }

    private final class ActivityRow extends VBox {
        private final HBox header;
        private final Label indicator;
        private final Label textLabel;
        private final Button copyButton;
        private final Button snippetButton;
        private final Button toggleButton;
        private final Label detailLabel;
        private FadeTransition pulseTransition;
        private TerminalAgentModels.AgentActivity currentActivity;
        private boolean detailsVisible;
        private long runningStartedAtMillis = -1L;

        private ActivityRow(TerminalAgentModels.AgentActivity activity) {
            getStyleClass().add("ai-agent-activity-row");
            setSpacing(3);
            currentActivity = activity;

            indicator = new Label("i");
            indicator.getStyleClass().add("ai-agent-activity-marker");
            indicator.setMinWidth(18);
            indicator.setAlignment(Pos.CENTER);

            textLabel = new Label();
            textLabel.getStyleClass().add("ai-agent-activity-text");
            textLabel.setWrapText(true);
            HBox.setHgrow(textLabel, Priority.ALWAYS);

            copyButton = buildRowActionButton("\u29c9", I18n.get("ai.agent.activity.copyEntry"));
            copyButton.setOnAction(event -> copyCurrentActivityText());

            snippetButton = buildRowActionButton("{ }", I18n.get("ai.agent.activity.openSnippetEditor"));
            snippetButton.setOnAction(event -> openCurrentActivityInSnippetEditor());

            toggleButton = new Button("+");
            toggleButton.getStyleClass().add("ai-agent-toggle-button");
            toggleButton.setFocusTraversable(false);
            toggleButton.setOnAction(event -> toggleDetails());

            header = new HBox(8, indicator, textLabel, copyButton, snippetButton, toggleButton);
            header.setAlignment(Pos.CENTER_LEFT);

            detailLabel = new Label();
            detailLabel.getStyleClass().add("ai-agent-detail");
            detailLabel.setWrapText(true);
            detailLabel.setPadding(new Insets(0, 0, 0, 26));

            getChildren().addAll(header, detailLabel);
            update(activity);
        }

        private void update(TerminalAgentModels.AgentActivity activity) {
            if (activity == null) {
                return;
            }
            currentActivity = activity;
            updateRunningClock(activity);
            updateIndicator(activity);
            textLabel.setText(formatActivityText(activity));
            detailLabel.setText(formatDetail(activity.detail()));
            boolean hasCopyableText = !copyTextForActivity(activity).isBlank();
            copyButton.setDisable(!hasCopyableText);
            snippetButton.setDisable(!hasCopyableText);
            boolean hasDetail = activity.collapsible() && activity.detail() != null && !activity.detail().isBlank();
            toggleButton.setVisible(hasDetail);
            toggleButton.setManaged(hasDetail);
            detailsVisible = hasDetail && (expandAllCheckBox.isSelected() || !activity.collapsed());
            detailLabel.setVisible(detailsVisible);
            detailLabel.setManaged(detailsVisible);
            toggleButton.setText(detailsVisible ? "-" : "+");
        }

        private void applyFontSize() {
            AiAgentActivityPanel.this.applyFontSize(indicator, activityFontSize + 4.0);
            AiAgentActivityPanel.this.applyFontSize(textLabel, activityFontSize);
            AiAgentActivityPanel.this.applyFontSize(copyButton, activityFontSize - 2.0);
            AiAgentActivityPanel.this.applyFontSize(snippetButton, activityFontSize - 2.0);
            AiAgentActivityPanel.this.applyFontSize(toggleButton, activityFontSize - 2.0);
            AiAgentActivityPanel.this.applyFontSize(detailLabel, activityFontSize - 1.0);
        }

        private Button buildRowActionButton(String text, String tooltip) {
            Button button = new Button(text);
            button.getStyleClass().add("ai-agent-toggle-button");
            button.setFocusTraversable(false);
            button.setMinWidth(28);
            button.setPrefWidth(34);
            button.setTooltip(new Tooltip(tooltip));
            return button;
        }

        private void copyCurrentActivityText() {
            String text = copyTextForActivity(currentActivity);
            if (!text.isBlank()) {
                copyToClipboard(text);
            }
        }

        private void openCurrentActivityInSnippetEditor() {
            openSnippetEditor(copyTextForActivity(currentActivity), currentActivity);
        }

        private void updateIndicator(TerminalAgentModels.AgentActivity activity) {
            indicator.getStyleClass().removeAll(
                "ai-agent-marker-input",
                "ai-agent-marker-output",
                "ai-agent-marker-question",
                "ai-agent-marker-error",
                "ai-agent-marker-cancelled",
                "ai-agent-marker-info",
                "ai-agent-marker-running");
            stopPulseTransition();
            ActivityVisual visual = activityVisual(activity);
            indicator.setText(visual.symbol());
            indicator.getStyleClass().add(visual.styleClass());
            if (activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING) {
                indicator.getStyleClass().add("ai-agent-marker-running");
                startPulseTransition();
            }
        }

        private String formatActivityText(TerminalAgentModels.AgentActivity activity) {
            String base = switch (activity.type()) {
                case ACTION -> activity.title();
                case THINKING -> activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING
                    ? I18n.get("ai.agent.activity.thinking")
                    : nonBlank(activity.summary(), activity.title());
                case QUESTION -> nonBlank(activity.title(), I18n.get("ai.agent.activity.inputRequired"));
                case ERROR -> nonBlank(activity.summary(), activity.title());
                case MESSAGE -> nonBlank(activity.summary(), activity.title());
            };
            String meta = formatMeta(activity);
            return meta.isBlank() ? base : base + " (" + meta + ")";
        }

        private String formatMeta(TerminalAgentModels.AgentActivity activity) {
            if (activity.type() == TerminalAgentModels.AgentActivityType.THINKING) {
                String elapsed = I18n.get("ai.agent.activity.elapsed", elapsedSecondsFor(activity));
                String tokens = formatTokenUsage(activity.tokenUsage());
                if (activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING) {
                    return elapsed + " - " + tokens + " - " + I18n.get("ai.agent.activity.interruptHint");
                }
                return elapsed + " - " + tokens;
            }
            if (activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING) {
                return I18n.get("ai.agent.activity.running")
                    + " - "
                    + I18n.get("ai.agent.activity.elapsed", elapsedSecondsFor(activity));
            }
            if (activity.type() == TerminalAgentModels.AgentActivityType.ACTION && !blank(activity.summary())) {
                return activity.summary();
            }
            return "";
        }

        private String formatDetail(String detail) {
            if (detail == null || detail.isBlank()) {
                return "";
            }
            return detail.lines()
                .map(line -> "> " + line)
                .reduce((left, right) -> left + "\n" + right)
                .orElse("");
        }

        private boolean refreshRunningElapsed() {
            if (currentActivity == null || currentActivity.status() != TerminalAgentModels.AgentActivityStatus.RUNNING) {
                return false;
            }
            long elapsedSeconds = elapsedSecondsFor(currentActivity);
            currentActivity = new TerminalAgentModels.AgentActivity(
                currentActivity.id(),
                currentActivity.type(),
                currentActivity.status(),
                currentActivity.title(),
                currentActivity.summary(),
                currentActivity.detail(),
                currentActivity.tokenUsage(),
                elapsedSeconds,
                currentActivity.collapsible(),
                currentActivity.collapsed());
            textLabel.setText(formatActivityText(currentActivity));
            return true;
        }

        private void updateRunningClock(TerminalAgentModels.AgentActivity activity) {
            if (activity.status() != TerminalAgentModels.AgentActivityStatus.RUNNING) {
                runningStartedAtMillis = -1L;
                return;
            }
            if (runningStartedAtMillis <= 0L) {
                long initialElapsedMillis = Math.max(0L, activity.elapsedSeconds()) * 1_000L;
                runningStartedAtMillis = System.currentTimeMillis() - initialElapsedMillis;
            }
        }

        private long elapsedSecondsFor(TerminalAgentModels.AgentActivity activity) {
            if (activity.status() == TerminalAgentModels.AgentActivityStatus.RUNNING && runningStartedAtMillis > 0L) {
                return elapsedSecondsSinceMillis(runningStartedAtMillis, System.currentTimeMillis());
            }
            return Math.max(0L, activity.elapsedSeconds());
        }

        private void toggleDetails() {
            if (currentActivity == null || !currentActivity.collapsible()) {
                return;
            }
            if (expandAllCheckBox.isSelected()) {
                detailsVisible = true;
                detailLabel.setVisible(true);
                detailLabel.setManaged(true);
                toggleButton.setText("-");
                return;
            }
            detailsVisible = !detailsVisible;
            detailLabel.setVisible(detailsVisible);
            detailLabel.setManaged(detailsVisible);
            toggleButton.setText(detailsVisible ? "-" : "+");
        }

        private void startPulseTransition() {
            pulseTransition = new FadeTransition(Duration.seconds(0.7), indicator);
            pulseTransition.setFromValue(1.0);
            pulseTransition.setToValue(0.35);
            pulseTransition.setAutoReverse(true);
            pulseTransition.setCycleCount(Animation.INDEFINITE);
            pulseTransition.play();
        }

        private void stopIndicator() {
            stopPulseTransition();
        }

        private void stopPulseTransition() {
            if (pulseTransition != null) {
                pulseTransition.stop();
                pulseTransition = null;
            }
            indicator.setOpacity(1.0);
        }
    }

    private static final class RunSnapshot {
        private final String title;
        private final String prompt;
        private final Runnable reloadCallback;
        private final String profileId;
        private final String profileName;
        private final String modelName;
        private final String reasoningStatus;
        private final LocalDateTime startedAt;
        private final List<TerminalAgentModels.AgentActivity> activities = new ArrayList<>();
        private LocalDateTime finishedAt;
        private long reportedTokens;
        private boolean hasReportedTokens;

        private RunSnapshot(
            String title,
            String prompt,
            Runnable reloadCallback,
            RunMetadata metadata,
            LocalDateTime startedAt) {

            this.title = title == null || title.isBlank() ? I18n.get("ai.agent.title") : title.trim();
            this.prompt = prompt == null || prompt.isBlank() ? this.title : prompt.trim();
            this.reloadCallback = reloadCallback;
            this.profileId = metadata != null && metadata.profileId() != null && !metadata.profileId().isBlank()
                ? metadata.profileId().trim()
                : null;
            this.profileName = metadata != null && metadata.profileName() != null && !metadata.profileName().isBlank()
                ? metadata.profileName().trim()
                : null;
            this.modelName = metadata != null && metadata.modelName() != null && !metadata.modelName().isBlank()
                ? metadata.modelName().trim()
                : null;
            this.reasoningStatus = metadata != null && metadata.reasoningStatus() != null && !metadata.reasoningStatus().isBlank()
                ? metadata.reasoningStatus().trim()
                : null;
            this.startedAt = startedAt != null ? startedAt : LocalDateTime.now();
        }

        private long elapsedSeconds() {
            LocalDateTime end = finishedAt != null ? finishedAt : LocalDateTime.now();
            return Math.max(0L, java.time.Duration.between(startedAt, end).toSeconds());
        }
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private String nonBlank(String value, String fallback) {
        return blank(value) ? fallback : value.trim();
    }
}
