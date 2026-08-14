package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ConnectionSettingsSupport;
import de.kortty.core.TerminalRecordingService;
import de.kortty.core.TerminalRecordingSession;
import de.kortty.core.TerminalRecordingState;
import de.kortty.core.TerminalRecordingRuntimeState;
import de.kortty.core.ThemeManager;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalRecordingScope;
import de.kortty.model.TemporarySSHKey;
import de.kortty.model.Theme;
import javafx.application.Platform;
import javafx.scene.Node;
import javafx.scene.control.TabPane;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.Tab;
import javafx.scene.control.Label;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.scene.input.MouseButton;
import javafx.scene.shape.SVGPath;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/**
 * A tab containing a terminal view for an SSH session.
 */
public class TerminalTab extends Tab {

    private static final String ICON_VIDEO =
        "M17 10.5V6c0-.55-.45-1-1-1H4c-.55 0-1 .45-1 1v12c0 .55.45 1 1 1h12c.55 0 1-.45 1-1v-4.5l4 4v-11z";
    private static final String ICON_STOP =
        "M6 6h12v12H6z";
    private static final String ICON_JOURNAL =
        "M18 2H6c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2V4c0-1.1-.9-2-2-2zM6 4h5v8l-2.5-1.5L6 12V4z";
    private static final String ICON_CAMERA =
        "M12 15.2a3.2 3.2 0 1 0 0-6.4 3.2 3.2 0 0 0 0 6.4zM9 2l-1.83 2H4c-1.1 0-2 .9-2 2v12c0 "
            + "1.1.9 2 2 2h16c1.1 0 2-.9 2-2V6c0-1.1-.9-2-2-2h-3.17L15 2H9z";
    private static final String ICON_NOTE =
        "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 "
            + "0-1.41l-2.34-2.34c-.39-.39-1.02-.39-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z";

    private final ServerConnection connection;
    private final TerminalView terminalView;
    private ConnectionSettings settings;
    private final TemporarySSHKey temporarySSHKey;
    private final String aiSessionId;
    private boolean isConnectionFailed = false;
    private Instant connectionStartTime;
    private Timeline statusBarTimer;
    private Label statusBarLabel;
    private Label disconnectedStatusBar;
    private HBox recordingBar;
    private Button recordingToggleButton;
    private Label recordingStatusLabel;
    private TerminalRecordingSession recordingSession;
    private TerminalRecordingScope activeRecordingScope;
    private boolean recordingControlsRevealedByUser;
    private HBox journalBar;
    private Button journalToggleButton;
    private Button journalScreenshotButton;
    private Button journalNoteButton;
    private Label journalStatusLabel;
    private boolean journalControlsRevealedByUser;
    private boolean previousJournalBarVisible;
    private boolean previousJournalBarManaged;
    private javafx.animation.PauseTransition journalStatusResetDelay;
    private Instant disconnectedAt;
    private volatile boolean reconnectInProgress = false;
    private boolean terminalChromeVisible = true;
    private boolean previousRecordingBarVisible;
    private boolean previousRecordingBarManaged;
    private boolean previousStatusBarVisible;
    private boolean previousStatusBarManaged;
    private boolean previousDisconnectedStatusBarVisible;
    private boolean previousDisconnectedStatusBarManaged;
    private javafx.scene.layout.HBox journalDecisionBar;
    private Label journalDecisionLabel;
    private boolean previousJournalDecisionBarVisible;
    private boolean previousJournalDecisionBarManaged;
    /** True when the red disconnected bar was shown due to mosh network interruption (so we hide it on recovery). */
    private boolean moshInterruptedBarVisible = false;
    private Runnable externalConnectedCallback;
    private Runnable journalStateListener;
    
    // Tab group (independent from connection group)
    private String tabGroup = null;
    // AI-agent status badge prefix (✋/⚡/⏸/✓ or "") and the last connection-status suffix, so the
    // title can be re-rendered with the badge without losing the suffix.
    private volatile String agentStatusBadge = "";
    private volatile String lastTitleSuffix = "";
    
    public TerminalTab(ServerConnection connection, String password) {
        this(connection, password, null);
    }
    
    public TerminalTab(ServerConnection connection, String password, TemporarySSHKey temporarySSHKey) {
        this.connection = connection;
        this.connectionGroupBaseline = normalizeGroup(connection != null ? connection.getGroup() : null);
        this.settings = resolveInitialSettings(connection);
        this.temporarySSHKey = temporarySSHKey;
        this.aiSessionId = UUID.randomUUID().toString();
        this.connectionStartTime = Instant.now();
        this.terminalView = new TerminalView(connection, password, temporarySSHKey);
        applyAiAgentActivityTheme(settings);
        this.terminalView.setOnReconnectRequested(this::triggerReconnect);
        this.terminalView.setJournalTabSessionId(aiSessionId);
        this.terminalView.setJournalScreenshotHandler(widget ->
            Platform.runLater(() -> takeJournalScreenshot(widget)));
        this.terminalView.setJournalNoteHandler(() -> Platform.runLater(this::addJournalNote));

        // Create status bar (connection duration / key validity)
        createStatusBar();
        // Create disconnected status bar (red bar, shown when server disconnects; double-click to reconnect)
        createDisconnectedStatusBar();
        createJournalDecisionBar();
        createRecordingBar();
        createJournalBar();
        
        updateTabTitle();
        
        // Create container with terminal view and status bars
        javafx.scene.layout.VBox container = new javafx.scene.layout.VBox();
        container.getChildren().add(terminalView);
        if (recordingBar != null) {
            container.getChildren().add(recordingBar);
        }
        if (journalBar != null) {
            container.getChildren().add(journalBar);
        }
        if (statusBarLabel != null) {
            container.getChildren().add(statusBarLabel);
        }
        if (disconnectedStatusBar != null) {
            container.getChildren().add(disconnectedStatusBar);
        }
        if (journalDecisionBar != null) {
            container.getChildren().add(journalDecisionBar);
        }
        javafx.scene.layout.VBox.setVgrow(terminalView, Priority.ALWAYS);
        
        setContent(container);
        setClosable(true);
        
        // Handle tab close
        setOnCloseRequest(event -> {
            // Only ask before closing when there is something to lose: multiple split panes, or a
            // foreground command running in the single pane. An idle terminal at its prompt closes
            // straight away. (Per-connection "close without confirmation" still suppresses it entirely.)
            if (terminalView.isConnected() && !settings.isCloseWithoutConfirmation()
                    && terminalView.shouldConfirmClose()) {
                // Show confirmation dialog. Local shells are not network connections, so use
                // dedicated wording instead of the SSH-flavored message.
                boolean localShell = connection.isLocalShell();
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                DialogThemeHelper.applyTheme(alert);
                alert.setTitle(I18n.get(localShell ? "dialog.closeLocalShell" : "dialog.closeConnection"));
                alert.setHeaderText(I18n.get(localShell ? "dialog.closeLocalShellQuestion" : "dialog.closeConnectionQuestion"));
                alert.setContentText(I18n.get(
                    localShell ? "dialog.closeLocalShellMessage" : "dialog.closeConnectionMessage",
                    connection.getDisplayName()));
                
                if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                    event.consume(); // Cancel the close
                    return;
                }
            }
            closeRecordingResources();
            terminalView.cleanup();
        stopStatusBarTimer();
        });
    }

    private static ConnectionSettings resolveInitialSettings(ServerConnection connection) {
        return resolveEffectiveSettings(connection != null ? connection.getSettings() : null);
    }

    private static ConnectionSettings resolveEffectiveSettings(ConnectionSettings connectionSettings) {
        try {
            var app = KorTTYApplication.getInstance();
            var globalSettings = app != null && app.getGlobalSettingsManager() != null
                    ? app.getGlobalSettingsManager().getSettings()
                    : null;
            var globalDefaults = globalSettings != null ? globalSettings.getDefaultTerminalSettings() : null;
            return ConnectionSettingsSupport.effectiveTerminalSettings(connectionSettings, globalDefaults);
        } catch (Exception e) {
            return ConnectionSettingsSupport.effectiveTerminalSettings(
                    connectionSettings,
                    null);
        }
    }
    
    /**
     * Creates the status bar showing SSH key validity and connection duration.
     */
    private void createStatusBar() {
        // Always show status bar so transient network interruption details are visible.
        statusBarLabel = new Label();
        statusBarLabel.setStyle("-fx-background-color: #2d2d2d; -fx-text-fill: #cccccc; -fx-padding: 3 8 3 8; -fx-font-size: 0.8462em;");
        // Fill the row so its opaque background covers the full width. Otherwise, in the see-through
        // window mode, the transparent area to the right of the label would reveal the desktop.
        statusBarLabel.setMaxWidth(Double.MAX_VALUE);

        // Start timer to update status bar
        startStatusBarTimer();
    }
    
    /**
     * Creates the red status bar shown when the server is disconnected.
     * Displays timestamp and "Double-click to reconnect"; double-click triggers reconnect.
     */
    private void createDisconnectedStatusBar() {
        disconnectedStatusBar = new Label();
        disconnectedStatusBar.setStyle("-fx-background-color: #8B0000; -fx-text-fill: white; -fx-padding: 6 10; -fx-font-size: 0.9231em; -fx-cursor: hand;");
        disconnectedStatusBar.setMaxWidth(Double.MAX_VALUE); // full-width opaque bar (see createStatusBar note)
        disconnectedStatusBar.setVisible(false);
        disconnectedStatusBar.setManaged(false);
        disconnectedStatusBar.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                triggerReconnect();
            }
        });
    }

    /**
     * Red decision bar shown when the connection ends while a journal is running: the user
     * decides whether the session merely paused (reboot — reconnect and the journal continues)
     * or is over (end the journal, which writes its closing summary). Without this bar a clean
     * remote disconnect would silently close the tab and take the running journal with it.
     */
    private void createJournalDecisionBar() {
        journalDecisionLabel = new Label();
        journalDecisionLabel.setStyle("-fx-text-fill: white;");
        Button reconnectButton = new Button(I18n.get("terminal.journal.disconnect.reconnect"));
        reconnectButton.setOnAction(event -> triggerReconnect());
        Button stopJournalButton = new Button(I18n.get("terminal.journal.disconnect.stop"));
        stopJournalButton.setOnAction(event -> stopJournalAfterDisconnect());
        javafx.scene.layout.Region spacer = new javafx.scene.layout.Region();
        javafx.scene.layout.HBox.setHgrow(spacer, Priority.ALWAYS);
        journalDecisionBar = new javafx.scene.layout.HBox(
            10, journalDecisionLabel, spacer, reconnectButton, stopJournalButton);
        journalDecisionBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        journalDecisionBar.setStyle("-fx-background-color: #8B0000; -fx-padding: 4 10;");
        journalDecisionBar.setMaxWidth(Double.MAX_VALUE);
        journalDecisionBar.setVisible(false);
        journalDecisionBar.setManaged(false);
    }

    /** Shows the journal decision bar instead of the plain disconnected bar. */
    private void showJournalDecisionBar() {
        disconnectedAt = Instant.now();
        String timeStr = DateTimeFormatter.ofPattern("HH:mm")
            .format(disconnectedAt.atZone(ZoneId.systemDefault()));
        if (journalDecisionLabel != null) {
            journalDecisionLabel.setText(I18n.get("terminal.journal.disconnect.text", timeStr));
        }
        applyChromeAwareVisibility(journalDecisionBar, true, true);
        if (statusBarLabel != null) {
            applyChromeAwareVisibility(statusBarLabel, false, false);
        }
        refreshJournalUi();
    }

    /**
     * The user chose to end the journal after a disconnect: stop it (the closing summary is
     * written on its background pass) and fall back to the plain reconnect bar — the connection
     * is still gone, only the journal decision is made.
     */
    private void stopJournalAfterDisconnect() {
        stopJournal();
        applyChromeAwareVisibility(journalDecisionBar, false, false);
        Instant at = disconnectedAt != null ? disconnectedAt : Instant.now();
        String timeStr = DateTimeFormatter.ofPattern("HH:mm").format(at.atZone(ZoneId.systemDefault()));
        showDisconnectedStatusBar(timeStr, false);
    }

    private void createRecordingBar() {
        recordingToggleButton = new Button(I18n.get("terminal.recording.start"));
        setRecordingButtonIcon(false);
        recordingToggleButton.setGraphicTextGap(6);
        recordingToggleButton.setOnAction(event -> toggleRecordingFromUser());
        recordingStatusLabel = new Label(I18n.get("terminal.recording.idle"));
        recordingStatusLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 0.8462em;");
        recordingBar = new HBox(8, recordingToggleButton, recordingStatusLabel);
        recordingBar.setStyle("-fx-background-color: #242424; -fx-padding: 4 8 4 8;");
        recordingBar.setMaxWidth(Double.MAX_VALUE); // full-width opaque bar (see createStatusBar note)
        updateRecordingUi(TerminalRecordingState.IDLE);
    }

    public void toggleRecordingFromUser() {
        if (recordingSession != null && recordingSession.isActive()) {
            stopRecording();
        } else {
            startRecording();
        }
    }

    public void toggleRecordingFromMenuOrShortcut() {
        recordingControlsRevealedByUser = true;
        refreshRecordingControlsVisibility();
        toggleRecordingFromUser();
    }

    public boolean isRecordingActive() {
        return recordingSession != null && recordingSession.isActive();
    }

    public void setTerminalChromeVisible(boolean visible) {
        if (visible == terminalChromeVisible) {
            return;
        }
        if (!visible) {
            previousRecordingBarVisible = recordingBar != null && recordingBar.isVisible();
            previousRecordingBarManaged = recordingBar != null && recordingBar.isManaged();
            previousJournalBarVisible = journalBar != null && journalBar.isVisible();
            previousJournalBarManaged = journalBar != null && journalBar.isManaged();
            previousStatusBarVisible = statusBarLabel != null && statusBarLabel.isVisible();
            previousStatusBarManaged = statusBarLabel != null && statusBarLabel.isManaged();
            previousDisconnectedStatusBarVisible = disconnectedStatusBar != null && disconnectedStatusBar.isVisible();
            previousDisconnectedStatusBarManaged = disconnectedStatusBar != null && disconnectedStatusBar.isManaged();
            previousJournalDecisionBarVisible = journalDecisionBar != null && journalDecisionBar.isVisible();
            previousJournalDecisionBarManaged = journalDecisionBar != null && journalDecisionBar.isManaged();
            terminalChromeVisible = false;
            applyNodeVisibility(recordingBar, false, false);
            applyNodeVisibility(journalBar, false, false);
            applyNodeVisibility(statusBarLabel, false, false);
            applyNodeVisibility(disconnectedStatusBar, false, false);
            applyNodeVisibility(journalDecisionBar, false, false);
            return;
        }

        terminalChromeVisible = true;
        applyNodeVisibility(recordingBar, previousRecordingBarVisible, previousRecordingBarManaged);
        applyNodeVisibility(journalBar, previousJournalBarVisible, previousJournalBarManaged);
        applyNodeVisibility(statusBarLabel, previousStatusBarVisible, previousStatusBarManaged);
        applyNodeVisibility(
            disconnectedStatusBar,
            previousDisconnectedStatusBarVisible,
            previousDisconnectedStatusBarManaged);
        applyNodeVisibility(
            journalDecisionBar,
            previousJournalDecisionBarVisible,
            previousJournalDecisionBarManaged);
    }

    public void refreshRecordingControlsVisibility() {
        if (recordingBar == null) {
            return;
        }
        boolean visible = isTerminalRecordingEnabled()
            || recordingControlsRevealedByUser
            || isRecordingActive();
        if (!terminalChromeVisible) {
            previousRecordingBarVisible = visible;
            previousRecordingBarManaged = visible;
            visible = false;
        }
        recordingBar.setVisible(visible);
        recordingBar.setManaged(visible);
    }

    private void applyNodeVisibility(Node node, boolean visible, boolean managed) {
        if (node == null) {
            return;
        }
        node.setVisible(visible);
        node.setManaged(managed);
    }

    private void startRecording() {
        if (!isTerminalRecordingEnabled()) {
            showRecordingError(I18n.get("terminal.recording.error.disabled"));
            refreshRecordingControlsVisibility();
            return;
        }
        if (!terminalView.isConnected()) {
            showRecordingError(I18n.get("terminal.recording.error.notConnected"));
            return;
        }
        try {
            TerminalRecordingScope scope = chooseRecordingScope();
            if (scope == null) {
                return;
            }
            TerminalRecordingSession session = ensureRecordingSession();
            activeRecordingScope = scope;
            session.start(scope);
            terminalView.attachTerminalRecordingSession(session, scope);
            updateRecordingUi(session.getState());
        } catch (IOException | RuntimeException e) {
            showRecordingError(I18n.get("terminal.recording.error.start", e.getMessage()));
        }
    }

    private void stopRecording() {
        if (recordingSession == null) {
            return;
        }
        try {
            terminalView.detachTerminalRecordingSession();
            recordingSession.stop();
            updateRecordingUi(recordingSession.getState());
        } catch (IOException | RuntimeException e) {
            showRecordingError(I18n.get("terminal.recording.error.stop", e.getMessage()));
        }
    }

    public void closeRecordingResources() {
        terminalView.detachTerminalRecordingSession();
        if (recordingSession == null) {
            return;
        }
        try {
            recordingSession.close();
        } catch (IOException e) {
            showRecordingError(I18n.get("terminal.recording.error.close", e.getMessage()));
        } finally {
            recordingSession = null;
            activeRecordingScope = null;
            updateRecordingUi(TerminalRecordingState.IDLE);
        }
    }

    private TerminalRecordingSession ensureRecordingSession() throws IOException {
        if (recordingSession != null) {
            return recordingSession;
        }
        GlobalSettings globalSettings = KorTTYApplication.getInstance()
            .getGlobalSettingsManager()
            .getSettings();
        recordingSession = new TerminalRecordingService().createSession(
            globalSettings,
            connection.getDisplayName(),
            aiSessionId);
        recordingSession.setStateListener(state -> Platform.runLater(() -> updateRecordingUi(state)));
        return recordingSession;
    }

    private TerminalRecordingScope chooseRecordingScope() {
        GlobalSettings settings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
        TerminalRecordingScope defaultScope = settings != null
            ? settings.getTerminalRecordingDefaultScope()
            : TerminalRecordingScope.ACTIVE_SPLIT;
        if (terminalView.getRecordingWidgetCount() <= 1) {
            return defaultScope;
        }
        ChoiceDialog<TerminalRecordingScope> dialog = new ChoiceDialog<>(
            defaultScope,
            List.of(TerminalRecordingScope.ACTIVE_SPLIT, TerminalRecordingScope.WHOLE_TAB));
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("terminal.recording.scope.title"));
        dialog.setHeaderText(I18n.get("terminal.recording.scope.header"));
        dialog.setContentText(I18n.get("terminal.recording.scope.content"));
        return dialog.showAndWait().orElse(null);
    }

    private void updateRecordingUi(TerminalRecordingState state) {
        if (recordingBar == null || recordingToggleButton == null || recordingStatusLabel == null) {
            return;
        }
        boolean active = state == TerminalRecordingState.RECORDING || state == TerminalRecordingState.AUTO_PAUSED;
        refreshRecordingControlsVisibility();
        recordingToggleButton.setText(active
            ? I18n.get("terminal.recording.stop")
            : I18n.get("terminal.recording.start"));
        setRecordingButtonIcon(active);
        if (recordingSession == null || state == TerminalRecordingState.IDLE) {
            recordingStatusLabel.setText(I18n.get("terminal.recording.idle"));
        } else if (state == TerminalRecordingState.AUTO_PAUSED) {
            recordingStatusLabel.setText(I18n.get("terminal.recording.autoPaused", recordingSession.getReplayFile()));
        } else if (state == TerminalRecordingState.RECORDING) {
            recordingStatusLabel.setText(I18n.get("terminal.recording.active", activeRecordingScope, recordingSession.getReplayFile()));
        } else {
            recordingStatusLabel.setText(I18n.get("terminal.recording.stopped", recordingSession.getReplayFile()));
        }
    }

    private boolean isTerminalRecordingEnabled() {
        try {
            GlobalSettings globalSettings = KorTTYApplication.getInstance()
                .getGlobalSettingsManager()
                .getSettings();
            return TerminalRecordingRuntimeState.isTerminalRecordingEnabled(globalSettings);
        } catch (RuntimeException e) {
            return false;
        }
    }

    private void setRecordingButtonIcon(boolean active) {
        recordingToggleButton.setGraphic(icon(active ? ICON_STOP : ICON_VIDEO));
    }

    private static Node icon(String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.setStyle("-fx-fill: -fx-text-base-color;");
        icon.setScaleX(0.72);
        icon.setScaleY(0.72);
        return icon;
    }

    private void showRecordingError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            DialogThemeHelper.applyTheme(alert);
            alert.setTitle(I18n.get("terminal.recording.error.title"));
            alert.setHeaderText(I18n.get("terminal.recording.error.header"));
            alert.setContentText(message);
            alert.showAndWait();
        });
    }

    // ==== Session journal bar ====

    private void createJournalBar() {
        journalToggleButton = new Button(I18n.get("terminal.journal.start"));
        setJournalButtonIcon(false);
        journalToggleButton.setGraphicTextGap(6);
        journalToggleButton.setOnAction(event -> toggleJournalFromUser());
        journalScreenshotButton = new Button(I18n.get("terminal.journal.screenshot"));
        journalScreenshotButton.setGraphic(icon(ICON_CAMERA));
        journalScreenshotButton.setGraphicTextGap(6);
        journalScreenshotButton.setOnAction(event -> takeJournalScreenshot(null));
        journalNoteButton = new Button(I18n.get("terminal.journal.note"));
        journalNoteButton.setGraphic(icon(ICON_NOTE));
        journalNoteButton.setGraphicTextGap(6);
        journalNoteButton.setOnAction(event -> addJournalNote());
        journalStatusLabel = new Label(I18n.get("terminal.journal.off"));
        journalStatusLabel.setStyle("-fx-text-fill: #cccccc; -fx-font-size: 0.8462em;");
        journalBar = new HBox(8, journalToggleButton, journalScreenshotButton, journalNoteButton, journalStatusLabel);
        journalBar.setStyle("-fx-background-color: #242424; -fx-padding: 4 8 4 8;");
        journalBar.setMaxWidth(Double.MAX_VALUE); // full-width opaque bar (see createStatusBar note)
        refreshJournalUi();
    }

    public boolean isJournalActive() {
        return terminalView.isSessionJournalActive();
    }

    public void toggleJournalFromUser() {
        if (isJournalActive()) {
            if (de.kortty.policy.PolicyManager.effective().sessionJournalEnforced()) {
                showJournalError(I18n.get("terminal.journal.error.enforced"));
                return;
            }
            stopJournal();
        } else {
            startJournal();
        }
    }

    public void toggleJournalFromMenuOrShortcut() {
        journalControlsRevealedByUser = true;
        refreshJournalControlsVisibility();
        toggleJournalFromUser();
    }

    public void refreshJournalControlsVisibility() {
        if (journalBar == null) {
            return;
        }
        boolean visible = isJournalConfigEnabled()
            || journalControlsRevealedByUser
            || isJournalActive();
        if (!terminalChromeVisible) {
            previousJournalBarVisible = visible;
            previousJournalBarManaged = visible;
            visible = false;
        }
        journalBar.setVisible(visible);
        journalBar.setManaged(visible);
    }

    private boolean isJournalConfigEnabled() {
        return connection != null
            && connection.getSessionJournalConfig() != null
            && connection.getSessionJournalConfig().isEnabled();
    }

    /** Starts a journal for the running session; the existing scrollback is imported as seed. */
    private void startJournal() {
        if (!de.kortty.policy.PolicyManager.effective().sessionJournalAllowed()) {
            showJournalError(I18n.get("terminal.journal.error.policy"));
            return;
        }
        if (!terminalView.isConnected()) {
            showJournalError(I18n.get("terminal.journal.error.notConnected"));
            return;
        }
        if (!terminalView.enableSessionJournalRetroactively()) {
            showJournalError(I18n.get("terminal.journal.error.start", ""));
        }
        refreshJournalUi();
    }

    private void stopJournal() {
        // Closing flushes the writer and may run a final AI pass; keep it off the FX thread.
        Thread stopper = new Thread(() -> {
            terminalView.stopSessionJournal();
            Platform.runLater(this::refreshJournalUi);
        }, "SessionJournal-Stop");
        stopper.setDaemon(true);
        stopper.start();
        journalStatusLabel.setText(I18n.get("terminal.journal.stoppedAt",
            DateTimeFormatter.ofPattern("HH:mm").format(Instant.now().atZone(ZoneId.systemDefault()))));
    }

    /** Screenshot of the given split widget (context menu) or the whole terminal (bar/menu). */
    public void takeJournalScreenshot(com.sithtermfx.ui.SithTermFxWidget widgetOrNull) {
        if (!isJournalActive()) {
            showJournalError(I18n.get("terminal.journal.error.notActive"));
            return;
        }
        try {
            byte[] png = terminalView.captureJournalScreenshotPng(widgetOrNull);
            de.kortty.core.SessionJournalSession session = terminalView.getSessionJournalSession();
            Thread saver = new Thread(() -> {
                try {
                    de.kortty.model.SessionJournalEntry entry = session.attachScreenshot(png, null);
                    de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
                    if (app != null && app.getSessionJournalScreenshotAnalyzer() != null) {
                        // Fire-and-forget: the analyzer applies its own gates and never blocks the save.
                        app.getSessionJournalScreenshotAnalyzer().analyzeAutomatically(
                            session.getDirectory(), entry.getId(), session.isAiSummariesEnabled());
                    }
                    Platform.runLater(() -> flashJournalStatus(I18n.get("terminal.journal.screenshotAdded")));
                } catch (Exception e) {
                    showJournalError(I18n.get("terminal.journal.error.screenshot", e.getMessage()));
                }
            }, "SessionJournal-Screenshot");
            saver.setDaemon(true);
            saver.start();
        } catch (Exception e) {
            showJournalError(I18n.get("terminal.journal.error.screenshot", e.getMessage()));
        }
    }

    /** Quick note: a short user text added to the journal timeline at the current position. */
    public void addJournalNote() {
        addJournalNote(null);
    }

    /** Quick note with a pre-filled suggestion (e.g. a timestamp reference from the live panel). */
    public void addJournalNote(String prefillText) {
        if (!isJournalActive()) {
            showJournalError(I18n.get("terminal.journal.error.notActive"));
            return;
        }
        javafx.scene.control.TextInputDialog dialog =
            new javafx.scene.control.TextInputDialog(prefillText != null ? prefillText : "");
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("terminal.journal.note.title"));
        dialog.setHeaderText(I18n.get("terminal.journal.note.header"));
        dialog.setContentText(I18n.get("terminal.journal.note.content"));
        dialog.showAndWait().ifPresent(text -> {
            if (text == null || text.isBlank()) {
                return;
            }
            de.kortty.core.SessionJournalSession session = terminalView.getSessionJournalSession();
            if (session == null) {
                return;
            }
            Thread saver = new Thread(() -> {
                try {
                    de.kortty.model.SessionJournalEntry entry = new de.kortty.model.SessionJournalEntry();
                    entry.setKind(de.kortty.model.SessionJournalEntryKind.USER_NOTE);
                    entry.setText(text.strip());
                    long seq = session.getLastSequence();
                    if (seq > 0) {
                        entry.setLogStartSeq(seq);
                        entry.setLogEndSeq(seq);
                    }
                    KorTTYApplication.getInstance().getSessionJournalService()
                        .appendEntry(session.getDirectory(), entry);
                    // Timeline twin in the capture log so the live panel streams the note too.
                    session.appendUserNote(text);
                    Platform.runLater(() -> flashJournalStatus(I18n.get("terminal.journal.noteAdded")));
                } catch (Exception e) {
                    showJournalError(I18n.get("terminal.journal.error.note", e.getMessage()));
                }
            }, "SessionJournal-Note");
            saver.setDaemon(true);
            saver.start();
        });
    }

    /** Notified (on the FX thread) whenever the journal bar re-evaluates its state — the funnel all
     *  start/stop/auto-start paths go through. Used by MainWindow to (re)bind the live panel. */
    public void setJournalStateListener(Runnable listener) {
        this.journalStateListener = listener;
    }

    private void refreshJournalUi() {
        if (journalBar == null || journalToggleButton == null || journalStatusLabel == null) {
            return;
        }
        if (journalStateListener != null) {
            try {
                journalStateListener.run();
            } catch (Exception ignored) {
                // A live-panel bug must never break the journal bar itself.
            }
        }
        boolean active = isJournalActive();
        refreshJournalControlsVisibility();
        journalToggleButton.setText(I18n.get(active ? "terminal.journal.stop" : "terminal.journal.start"));
        setJournalButtonIcon(active);
        // Starting is never blocked by "enforced" (an enforced journal already auto-starts); only
        // stopping an already-active one is. toggleJournalFromUser() already refuses the action —
        // this greys the button out and explains why, instead of a click quietly doing nothing.
        boolean stopLocked = active && de.kortty.policy.PolicyManager.effective().sessionJournalEnforced();
        journalToggleButton.setDisable(stopLocked);
        journalToggleButton.setTooltip(stopLocked
            ? new Tooltip(I18n.get("terminal.journal.error.enforced"))
            : null);
        applyNodeVisibility(journalScreenshotButton, active, active);
        applyNodeVisibility(journalNoteButton, active, active);
        if (active) {
            de.kortty.core.SessionJournalSession session = terminalView.getSessionJournalSession();
            String since = session != null && session.getMetaSnapshot().getStartedAt() != null
                ? session.getMetaSnapshot().getStartedAt()
                    .atZoneSameInstant(ZoneId.systemDefault())
                    .format(DateTimeFormatter.ofPattern("HH:mm"))
                : "";
            journalStatusLabel.setText(I18n.get("terminal.journal.activeSince", since));
        } else {
            journalStatusLabel.setText(I18n.get("terminal.journal.off"));
        }
    }

    /** Shows a transient confirmation in the status label, then restores the active text. */
    private void flashJournalStatus(String message) {
        if (journalStatusLabel == null) {
            return;
        }
        journalStatusLabel.setText(message);
        if (journalStatusResetDelay != null) {
            journalStatusResetDelay.stop();
        }
        journalStatusResetDelay = new javafx.animation.PauseTransition(Duration.seconds(3));
        journalStatusResetDelay.setOnFinished(event -> refreshJournalUi());
        journalStatusResetDelay.play();
    }

    private void setJournalButtonIcon(boolean active) {
        journalToggleButton.setGraphic(icon(active ? ICON_STOP : ICON_JOURNAL));
    }

    private void showJournalError(String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            DialogThemeHelper.applyTheme(alert);
            alert.setTitle(I18n.get("terminal.journal.error.title"));
            alert.setHeaderText(I18n.get("terminal.journal.error.header"));
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    /**
     * Shows the disconnected status bar with timestamp. Hides the normal status bar.
     * Call this when the connection has fully dropped (e.g. from disconnect listener).
     */
    private void showDisconnectedStatusBar() {
        disconnectedAt = Instant.now();
        String timeStr = DateTimeFormatter.ofPattern("HH:mm").format(disconnectedAt.atZone(ZoneId.systemDefault()));
        showDisconnectedStatusBar(timeStr, false);
    }

    /**
     * Shows the red status bar with a timestamp and message.
     * @param timeStr time string (e.g. HH:mm)
     * @param forMoshInterrupt if true, use "interrupted" message and record that we showed for interrupt (so we hide on recovery)
     */
    private void showDisconnectedStatusBar(String timeStr, boolean forMoshInterrupt) {
        showDisconnectedStatusBar(timeStr, null, forMoshInterrupt);
    }

    /**
     * Shows the red status bar with optional elapsed duration (for mosh interrupt).
     * @param timeStr time when interrupt started (e.g. HH:mm)
     * @param elapsedDuration optional elapsed duration string (e.g. "2m 15s"); if non-null and forMoshInterrupt, shown in bar
     * @param forMoshInterrupt if true, use "interrupted" message and record that we showed for interrupt
     */
    private void showDisconnectedStatusBar(String timeStr, String elapsedDuration, boolean forMoshInterrupt) {
        if (disconnectedStatusBar != null) {
            String key = forMoshInterrupt && elapsedDuration != null
                    ? "statusBar.interruptedAtDoubleClickWithElapsed"
                    : forMoshInterrupt ? "statusBar.interruptedAtDoubleClick" : "statusBar.disconnectedAtDoubleClick";
            String text = forMoshInterrupt && elapsedDuration != null
                    ? I18n.get(key, timeStr, elapsedDuration)
                    : I18n.get(key, timeStr);
            disconnectedStatusBar.setText(text);
            applyChromeAwareVisibility(disconnectedStatusBar, true, true);
        }
        if (statusBarLabel != null) {
            applyChromeAwareVisibility(statusBarLabel, false, false);
        }
        moshInterruptedBarVisible = forMoshInterrupt;
    }
    
    /**
     * Called when mosh4j reports a network interruption (runs on JavaFX thread).
     * Shows the red status bar immediately so the user sees the drop without waiting for the timer.
     */
    private void showMoshInterruptedStatusBarIfNeeded() {
        long interruptedMs = terminalView.getMoshInterruptionStartedAtMs();
        if (interruptedMs > 0 && connection.getProtocol() == ConnectionProtocol.MOSH) {
            String timeStr = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(interruptedMs).atZone(ZoneId.systemDefault()));
            showDisconnectedStatusBar(timeStr, true);
            if (!isConnectionFailed) {
                setTabErrorColor();
            }
        }
    }

    /**
     * Hides the disconnected status bar and restores the normal status bar if present.
     */
    private void hideDisconnectedStatusBar() {
        if (disconnectedStatusBar != null) {
            applyChromeAwareVisibility(disconnectedStatusBar, false, false);
        }
        if (journalDecisionBar != null) {
            applyChromeAwareVisibility(journalDecisionBar, false, false);
        }
        if (statusBarLabel != null) {
            applyChromeAwareVisibility(statusBarLabel, true, true);
        }
        moshInterruptedBarVisible = false;
    }

    private void applyChromeAwareVisibility(Node node, boolean visible, boolean managed) {
        if (node == null) {
            return;
        }
        if (node == recordingBar) {
            previousRecordingBarVisible = visible;
            previousRecordingBarManaged = managed;
        } else if (node == statusBarLabel) {
            previousStatusBarVisible = visible;
            previousStatusBarManaged = managed;
        } else if (node == disconnectedStatusBar) {
            previousDisconnectedStatusBarVisible = visible;
            previousDisconnectedStatusBarManaged = managed;
        } else if (node == journalDecisionBar) {
            previousJournalDecisionBarVisible = visible;
            previousJournalDecisionBarManaged = managed;
        }
        node.setVisible(terminalChromeVisible && visible);
        node.setManaged(terminalChromeVisible && managed);
    }
    
    /**
     * Starts the status bar timer to update connection duration and key validity.
     */
    private void startStatusBarTimer() {
        stopStatusBarTimer();
        
        statusBarTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            updateStatusBar();
        }));
        statusBarTimer.setCycleCount(Timeline.INDEFINITE);
        statusBarTimer.play();
    }
    
    /**
     * Stops the status bar timer.
     */
    private void stopStatusBarTimer() {
        if (statusBarTimer != null) {
            statusBarTimer.stop();
            statusBarTimer = null;
        }
    }
    
    /**
     * Updates the status bar with current information.
     */
    private void updateStatusBar() {
        if (statusBarLabel == null) {
            return;
        }
        
        StringBuilder status = new StringBuilder();
        boolean interrupted = terminalView.isMoshNetworkInterrupted();
        long interruptedSinceMs = terminalView.getMoshInterruptionStartedAtMs();
        
        // Show temporary SSH key validity if available
        if (temporarySSHKey != null) {
            if (temporarySSHKey.isValid()) {
                long remainingSeconds = temporarySSHKey.getRemainingSeconds();
                long minutes = remainingSeconds / 60;
                long secs = remainingSeconds % 60;
                String timeStr = String.format("%02d:%02d", minutes, secs);
                
                if (remainingSeconds < 60) {
                    status.append(I18n.get("statusBar.sshKeyValidCritical", timeStr));
                } else if (remainingSeconds < 300) {
                    status.append(I18n.get("statusBar.sshKeyValidWarning", timeStr));
                } else {
                    status.append(I18n.get("statusBar.sshKeyValid", timeStr));
                }
            } else if (temporarySSHKey != null) {
                status.append(I18n.get("statusBar.sshKeyExpired"));
            }
        }
        
        // Show connection duration
        if (connectionStartTime != null) {
            long durationSeconds = Instant.now().getEpochSecond() - connectionStartTime.getEpochSecond();
            if (status.length() > 0) {
                status.append(" | ");
            }
            String durationStr = formatDuration(durationSeconds);
            status.append(I18n.get("statusBar.connectionDuration", durationStr));
        }

        if (interrupted && interruptedSinceMs > 0) {
            long nowMs = System.currentTimeMillis();
            long elapsedSeconds = Math.max(0L, (nowMs - interruptedSinceMs) / 1000L);
            String since = DateTimeFormatter.ofPattern("HH:mm:ss")
                    .format(Instant.ofEpochMilli(interruptedSinceMs).atZone(ZoneId.systemDefault()));
            String elapsed = formatDuration(elapsedSeconds);
            if (status.length() > 0) {
                status.append(" | ");
            }
            status.append(I18n.get("statusBar.networkInterruptedSinceElapsed", since, elapsed));
        }
        
        final boolean wasInterrupted = interrupted;
        final long interruptedMs = interruptedSinceMs;
        Platform.runLater(() -> {
            statusBarLabel.setText(status.toString());
            if (wasInterrupted) {
                statusBarLabel.setStyle("-fx-background-color: #8B0000; -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-font-size: 0.8462em;");
                if (!isConnectionFailed) {
                    setTabErrorColor();
                }
                // Show prominent red bar with elapsed duration so user sees disconnect and how long it has been
                if (interruptedMs > 0) {
                    long nowMs = System.currentTimeMillis();
                    long elapsedSeconds = Math.max(0L, (nowMs - interruptedMs) / 1000L);
                    String timeStr = DateTimeFormatter.ofPattern("HH:mm").format(Instant.ofEpochMilli(interruptedMs).atZone(ZoneId.systemDefault()));
                    String elapsedStr = formatDuration(elapsedSeconds);
                    showDisconnectedStatusBar(timeStr, elapsedStr, true);
                }
            } else {
                if (moshInterruptedBarVisible) {
                    hideDisconnectedStatusBar();
                }
                statusBarLabel.setStyle("-fx-background-color: #2d2d2d; -fx-text-fill: #cccccc; -fx-padding: 3 8 3 8; -fx-font-size: 0.8462em;");
                if (!isConnectionFailed) {
                    resetTabColor();
                }
            }
        });
    }

    private static String formatDuration(long durationSeconds) {
        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        long secs = durationSeconds % 60;
        if (hours > 0) {
            return String.format("%d:%02d:%02d", hours, minutes, secs);
        }
        return String.format("%02d:%02d", minutes, secs);
    }
    
    /**
     * Sets the tab color to yellow to indicate connection attempt in progress.
     */
    private void setTabConnectingColor() {
        Platform.runLater(() -> {
            // Set yellow background color with black text for good contrast
            setStyle("-fx-background-color: #FFD700; -fx-text-fill: black;");
        });
    }
    
    /**
     * Sets the tab color to dark red to indicate connection failure or disconnection.
     */
    private void setTabErrorColor() {
        Platform.runLater(() -> {
            // Set dark red background color with black text for better readability
            setStyle("-fx-background-color: #8B0000; -fx-text-fill: black;");
        });
    }
    
    /**
     * Resets the tab color to default.
     */
    private void resetTabColor() {
        Platform.runLater(() -> {
            setStyle(""); // Reset to default
        });
    }
    
    /**
     * Retries the connection.
     */
    public void retryConnection() {
        isConnectionFailed = false;
        updateTabTitle();
        connect(); // connect() will set tab to yellow automatically
    }
    
    /**
     * Disconnects if connected, then reconnects. Keeps the terminal window open.
     * Use for context-menu "Reconnect" on tab, terminal, or dashboard.
     */
    public void performReconnect() {
        reconnectInProgress = true;
        if (terminalView.isConnected()) {
            terminalView.disconnectOnly();  // Close connection only, keep UI for reconnect
        }
        retryConnection();
    }
    
    private Runnable onReconnectRequested;
    
    /**
     * Sets a callback invoked after performReconnect (e.g. to update dashboard and status).
     */
    public void setOnReconnectRequested(Runnable r) {
        this.onReconnectRequested = r;
    }
    
    /**
     * Performs reconnect and notifies the callback. Called from context menus.
     */
    public void triggerReconnect() {
        performReconnect();
        if (onReconnectRequested != null) {
            Platform.runLater(onReconnectRequested);
        }
    }
    
    /**
     * Connects to the SSH server.
     */
    public void connect() {
        // Set tab to yellow color to indicate connection attempt in progress
        setTabConnectingColor();
        
        // Show status bar immediately when mosh detects network interruption (don't wait for timer)
        terminalView.setOnMoshInterruptedCallback(this::showMoshInterruptedStatusBarIfNeeded);
        // Register disconnect listener: keep tab and split open, show red tab and red status bar
        terminalView.setDisconnectListener((reason, wasError) -> {
            // Capture the pane count synchronously (before the split's auto-close runs): in a split, one
            // pane's shell exit must close only that pane, never the whole tab.
            boolean splitHasOtherPanes = terminalView.getTerminalPaneCount() > 1;
            Platform.runLater(() -> {
                boolean isMoshSession = connection.getProtocol() == ConnectionProtocol.MOSH;
                boolean isRemoteLogout = reason != null
                        && reason.toLowerCase().contains("remote logout");
                TerminalDisconnectSupport.Reaction reaction = TerminalDisconnectSupport.reactionFor(
                    wasError, reconnectInProgress, isMoshSession, isRemoteLogout,
                    splitHasOtherPanes, isJournalActive());
                switch (reaction) {
                    case IGNORE_RECONNECT_IN_PROGRESS -> reconnectInProgress = false;
                    case PANE_CLOSED_ONLY -> {
                        // The exiting pane is removed by the split's own auto-close; the tab lives on.
                    }
                    case CLOSE_TAB -> closeTabSilently();
                    case KEEP_OPEN_JOURNAL_DECISION -> {
                        isConnectionFailed = true;
                        updateTabTitle(" (DISCONNECT)");
                        setTabErrorColor();
                        showJournalDecisionBar();
                    }
                    case KEEP_OPEN_DISCONNECTED -> {
                        isConnectionFailed = true;
                        updateTabTitle(" (DISCONNECT)");
                        setTabErrorColor();
                        showDisconnectedStatusBar();
                    }
                }
            });
        });
        
        // Register callback for successful connection and preserve optional external listeners.
        terminalView.setOnConnectedCallback(() -> {
            Platform.runLater(() -> {
                reconnectInProgress = false;
                updateTabTitle();
                resetTabColor(); // Reset to default (green/normal)
                hideDisconnectedStatusBar();
                refreshJournalUi(); // journal may have auto-started with this connect
                if (externalConnectedCallback != null) {
                    externalConnectedCallback.run();
                }
            });
        });
        
        // Let the terminal request closing this tab (e.g. Ctrl+D on a local cmd/PowerShell shell).
        terminalView.setOnCloseTabRequest(() -> Platform.runLater(this::closeTabSilently));

        terminalView.connect();
    }

    public void setOnConnectedCallback(Runnable callback) {
        this.externalConnectedCallback = callback;
    }
    
    /**
     * Closes the tab without confirmation dialog.
     */
    private void closeTabSilently() {
        TabPane tabPane = getTabPane();
        if (tabPane != null) {
            closeRecordingResources();
            terminalView.stopSessionJournal();
            // Suppress QuickConnect if + tab might be selected after removal
            MainWindow.suppressNextQuickConnect();
            // Remove close request handler temporarily to avoid confirmation
            setOnCloseRequest(null);
            tabPane.getTabs().remove(this);
        }
    }
    
    /**
     * Called when the SSH connection fails.
     */
    public void onConnectionFailed(String error) {
        isConnectionFailed = true;
        terminalView.showError(I18n.get("status.connectionFailed", error));
        Platform.runLater(() -> {
            updateTabTitle(" (DISCONNECT)");
            setTabErrorColor();
            showDisconnectedStatusBar();
        });
    }
    
    /**
     * Copies the selected text to clipboard.
     */
    public void copySelection() {
        terminalView.copyToClipboard();
    }
    
    /**
     * Pastes text from clipboard to the terminal.
     */
    public void paste() {
        terminalView.pasteFromClipboard();
    }
    
    /**
     * Zooms the terminal font.
     */
    public void zoom(int delta) {
        terminalView.zoom(delta);
    }
    
    /**
     * Resets the terminal font size.
     */
    public void resetZoom() {
        terminalView.resetZoom();
    }
    
    /**
     * Shows the find bar in the terminal.
     */
    public void showFind() {
        terminalView.showFind();
    }
    
    /**
     * Toggles the timestamp gutter visibility.
     * @return true if gutters are now visible, false if hidden
     */
    public boolean toggleTimestampGutters() {
        return terminalView.toggleTimestampGutters();
    }
    
    /**
     * Returns whether timestamp gutters are currently visible.
     */
    public boolean isTimestampGuttersVisible() {
        return terminalView.isTimestampGuttersVisible();
    }
    
    /**
     * Sets a listener called when timestamp gutter visibility is toggled from the context menu.
     */
    public void setTimestampToggleListener(Runnable listener) {
        terminalView.setTimestampToggleListener(listener);
    }
    
    public ServerConnection getConnection() {
        return connection;
    }
    
    public TerminalView getTerminalView() {
        return terminalView;
    }

    public void applyConnectionSettings(ConnectionSettings connectionSettings) {
        ConnectionSettings effectiveSettings = resolveEffectiveSettings(connectionSettings);
        this.settings = effectiveSettings;
        terminalView.applyConnectionSettings(effectiveSettings);
        applyAiAgentActivityTheme(effectiveSettings);
    }

    private void applyAiAgentActivityTheme(ConnectionSettings connectionSettings) {
        Theme theme = resolveAiAgentTheme(connectionSettings);
        terminalView.applyTerminalAgentActivityTheme(theme);
    }

    private Theme resolveAiAgentTheme(ConnectionSettings connectionSettings) {
        try {
            ConnectionSettings sourceSettings = connectionSettings;
            if (sourceSettings == null || sourceSettings.isUseGlobalSettings()) {
                var globalSettings = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
                if (globalSettings != null && globalSettings.getDefaultTerminalSettings() != null) {
                    sourceSettings = globalSettings.getDefaultTerminalSettings();
                }
            }
            String themeId = sourceSettings != null ? sourceSettings.getThemeId() : null;
            if (themeId == null || themeId.isBlank()) {
                return null;
            }
            ThemeManager themeManager = KorTTYApplication.getInstance().getThemeManager();
            return themeManager != null ? themeManager.getTheme(themeId).orElse(null) : null;
        } catch (Exception e) {
            return null;
        }
    }
    
    public boolean isConnected() {
        return terminalView.isConnected();
    }

    /**
     * True while this tab is in an error state: the connection dropped or failed
     * unexpectedly (red disconnect bar), or mosh reports a network interruption.
     * A cleanly ended session returns false.
     */
    public boolean isUnexpectedlyDisconnected() {
        return isConnectionFailed || moshInterruptedBarVisible;
    }


    /**
     * Returns the temporary SSH key if this tab was connected with one.
     * Used by SFTP Manager to use the same key for file transfers.
     */
    public TemporarySSHKey getTemporarySSHKey() {
        return temporarySSHKey;
    }

    public String getAiSessionId() {
        return aiSessionId;
    }
    
    /**
     * Updates the tab title to include group prefix if group is set.
     */
    public void updateTabTitle() {
        updateTabTitle("");
    }
    
    /**
     * Updates the tab title to include group prefix if group is set.
     * @param suffix Additional suffix to append (e.g., " (DISCONNECT)")
     */
    private void updateTabTitle(String suffix) {
        String effectiveSuffix = suffix != null ? suffix : "";
        lastTitleSuffix = effectiveSuffix;
        Platform.runLater(() -> {
            String displayName = connection.getDisplayName();
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = connection.getUsername() + "@" + connection.getHost();
            }

            String prefix = agentStatusBadge.isEmpty() ? "" : agentStatusBadge + " ";
            String group = tabGroup; // Use tab group, not connection group
            if (group != null && !group.trim().isEmpty()) {
                setText(prefix + "[" + group + "] " + displayName + effectiveSuffix);
            } else {
                setText(prefix + displayName + effectiveSuffix);
            }
        });
    }

    /** Sets the AI-agent status badge (✋/⚡/⏸/✓ or "") shown as a prefix on the tab title. */
    public void setAgentStatusBadge(String badge) {
        String normalized = badge != null ? badge : "";
        if (normalized.equals(agentStatusBadge)) {
            return;
        }
        agentStatusBadge = normalized;
        updateTabTitle(lastTitleSuffix);
    }
    
    /** The connection's group as last seen/applied by this tab (normalized, may be null).
     *  Lets MainWindow detect actual connection-group edits on Connection Manager save
     *  without clobbering a manually assigned tab group. */
    private String connectionGroupBaseline;

    public String getConnectionGroupBaseline() {
        return connectionGroupBaseline;
    }

    public void setConnectionGroupBaseline(String group) {
        this.connectionGroupBaseline = normalizeGroup(group);
    }

    private static String normalizeGroup(String group) {
        return group != null && !group.trim().isEmpty() ? group.trim() : null;
    }

    /**
     * Gets the group name for this tab (independent from connection).
     */
    public String getGroup() {
        return tabGroup;
    }
    
    /**
     * Sets the group for this tab (independent from connection) and updates the tab title.
     */
    public void setGroup(String group) {
        this.tabGroup = (group != null && !group.trim().isEmpty()) ? group.trim() : null;
        updateTabTitle();
    }
}
