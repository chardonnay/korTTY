package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ConnectionColorSupport;
import de.kortty.core.ConnectionSettingsSupport;
import de.kortty.core.DisplayTextSanitizer;
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
import javafx.scene.control.ButtonBar;
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
    /** Tooltip text last put on the status bar for the tab's SSH tunnels; FX thread only. */
    private String statusBarTunnelTooltip;
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
    /** True while this tab automatically retries a lost connection (global auto-reconnect setting). */
    private boolean autoReconnectActive = false;
    /** 0-based counter of automatic attempts since the loss; drives the backoff delay. */
    private int autoReconnectAttempt = 0;
    /** Pending scheduled automatic attempt, null when none. */
    private Timeline autoReconnectTimer;
    private boolean terminalChromeVisible = true;
    private boolean previousRecordingBarVisible;
    private boolean previousRecordingBarManaged;
    private boolean previousStatusBarVisible;
    private boolean previousStatusBarManaged;
    private boolean previousDisconnectedStatusBarVisible;
    private boolean previousDisconnectedStatusBarManaged;
    private javafx.scene.layout.HBox journalDecisionBar;
    private Label journalDecisionLabel;
    /** Red bar when the AI of an automatically started journal is not reachable. */
    private javafx.scene.layout.HBox journalAiBar;
    private Label journalAiLabel;
    private boolean previousJournalAiBarVisible;
    private boolean previousJournalAiBarManaged;
    private boolean journalAiCheckRunning;
    private boolean previousJournalDecisionBarVisible;
    private boolean previousJournalDecisionBarManaged;
    /** True when the red disconnected bar was shown due to mosh network interruption (so we hide it on recovery). */
    private boolean moshInterruptedBarVisible = false;
    private Runnable externalConnectedCallback;
    private Runnable journalStateListener;
    /** Told when the user closes the tab with its close button; see {@link #setOnUserCloseApproved}. */
    private java.util.function.Consumer<TerminalTab> onUserCloseApproved;
    
    /** Longest name the user can give a tab, in characters. */
    static final int MAX_CUSTOM_TITLE_LENGTH = 120;

    // Tab group (independent from connection group)
    private String tabGroup = null;
    /** The name the user gave this tab instead of the connection's, sanitised; null when none. */
    private volatile String customTitle;
    /**
     * The title the program in the focused pane set (OSC 0/2), already cleaned by
     * {@link ShellTitleTracker}; null when there is none or the Window setting is off. Ranks below
     * {@link #customTitle} and above the connection's name, and is text only: it never changes a color.
     */
    private volatile String shellTitle;
    // AI-agent status badge prefix (✋/⚡/⏸/✓ or "") and the last connection-status suffix, so the
    // title can be re-rendered with the badge without losing the suffix.
    private volatile String agentStatusBadge = "";
    private volatile String lastTitleSuffix = "";
    /** The mark a tab gets when a program in it asks for attention while the user is not looking at it. */
    static final String ATTENTION_BADGE = "🔔";
    /**
     * The attention mark in the title, {@link #ATTENTION_BADGE} or ""; it follows the agent status.
     * Set by {@link TerminalAttentionNotifier}, cleared when the tab is seen. FX thread only.
     */
    private String attentionBadge = "";
    /** Why the tab is marked, for its tooltip; null while it is not. FX thread only. */
    private String attentionLine;
    /**
     * The tab header's graphic: one container for the tab's decorations, so markers added later sit
     * beside the connection color dot. Set as the graphic only while it holds something.
     */
    private final HBox tabDecorations = new HBox(4);
    /** The color dot in {@link #tabDecorations}, or null; FX thread only. */
    private Node connectionColorSwatch;
    /** The tooltip line about the tab color, or null without a color; FX thread only. */
    private String connectionColorLine;
    /**
     * The tab's content: the terminal view with its split panes, and the status bars below it. Its
     * border is the frame in the connection's tab color and nothing else (see {@link #showConnectionColor}).
     */
    private final javafx.scene.layout.VBox content = new javafx.scene.layout.VBox();
    
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
        this.terminalView.setShellTitleListener(this::onShellTitleChanged);
        // A bell in a pane the user is not looking at marks the tab and may notify (Settings → Terminal).
        this.terminalView.setBellListener(widget -> TerminalAttentionNotifier.shared().onBell(this, widget));
        // So does a long command the shell marked (shell integration) finishing there.
        this.terminalView.setCommandFinishedListener(
            (widget, status) -> TerminalAttentionNotifier.shared().onCommandFinished(this, widget, status));
        // And so does a program that asks for a desktop notification (OSC 9, OSC 777).
        this.terminalView.setRemoteNotificationListener(
            (widget, notification) -> TerminalAttentionNotifier.shared().onRemoteNotification(this, widget, notification));

        // Create status bar (connection duration / key validity)
        createStatusBar();
        // Create disconnected status bar (red bar, shown when server disconnects; double-click to reconnect)
        createDisconnectedStatusBar();
        createJournalDecisionBar();
        createJournalAiBar();
        this.terminalView.setJournalAiPreflightListener(this::onAutomaticJournalAiPreflight);
        createRecordingBar();
        createJournalBar();
        
        updateTabTitle();
        tabDecorations.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        
        // Fill the content with the terminal view and the status bars
        content.getChildren().add(terminalView);
        if (recordingBar != null) {
            content.getChildren().add(recordingBar);
        }
        if (journalBar != null) {
            content.getChildren().add(journalBar);
        }
        if (journalAiBar != null) {
            content.getChildren().add(journalAiBar);
        }
        if (statusBarLabel != null) {
            content.getChildren().add(statusBarLabel);
        }
        if (disconnectedStatusBar != null) {
            content.getChildren().add(disconnectedStatusBar);
        }
        if (journalDecisionBar != null) {
            content.getChildren().add(journalDecisionBar);
        }
        javafx.scene.layout.VBox.setVgrow(terminalView, Priority.ALWAYS);
        
        setContent(content);
        setClosable(true);
        
        // Handle tab close
        setOnCloseRequest(event -> {
            if (!confirmUserClose()) {
                event.consume(); // Cancel the close
                return;
            }
            // While the tab still holds its group, name and effect: Recently Closed remembers them.
            notifyUserCloseApproved();
            releaseResources();
        });
    }

    /**
     * Runs {@code callback} when the user closes this tab with its close button and agreed to any
     * question, before the tab releases anything. The main window records the tab for Recently Closed
     * there. A tab that closes on its own (its session ended) and the main window's own close commands
     * do not run it.
     */
    void setOnUserCloseApproved(java.util.function.Consumer<TerminalTab> callback) {
        this.onUserCloseApproved = callback;
    }

    private void notifyUserCloseApproved() {
        java.util.function.Consumer<TerminalTab> callback = onUserCloseApproved;
        if (callback != null) {
            callback.accept(this);
        }
    }

    /**
     * Releases what a closing tab holds: its recording, a pending automatic reconnect, every pane's
     * session and the status-bar timeline, which would otherwise keep the closed tab alive. Shared by
     * the close button, a tab that closes on its own and the main window's close paths (Cmd/Ctrl+W,
     * the Dashboard's Close, Close All), which remove the tab without firing its close events.
     * Idempotent.
     */
    void releaseResources() {
        closeRecordingResources();
        cancelAutoReconnectTimer();
        // Idempotent; also stops the session journal and closes every pane's connector.
        terminalView.cleanup();
        stopStatusBarTimer();
    }

    /**
     * Whether a close the user asked for has to be confirmed first. Only when there is something to
     * lose: a connected tab with several split panes, or a foreground command running in its single
     * pane. An idle terminal at its prompt closes straight away, and the per-connection "close
     * without confirmation" suppresses the question entirely.
     */
    static boolean closeNeedsConfirmation(boolean connected, boolean closeWithoutConfirmation, boolean busy) {
        return connected && !closeWithoutConfirmation && busy;
    }

    /** {@link #closeNeedsConfirmation} for this tab right now. */
    boolean needsCloseConfirmation() {
        return closeNeedsConfirmation(terminalView.isConnected(), settings.isCloseWithoutConfirmation(),
            terminalView.shouldConfirmClose());
    }

    /**
     * Asks before a close the user requested, when {@link #needsCloseConfirmation()} says so. Shared
     * by the tab's close button and the main window's other close commands (Cmd/Ctrl+W, the
     * Dashboard's Close), so they all ask the same question. Closes nothing itself.
     *
     * @return {@code true} when the tab may close
     */
    boolean confirmUserClose() {
        if (!needsCloseConfirmation()) {
            return true;
        }
        // Local shells are not network connections, so use dedicated wording instead of the
        // SSH-flavored message.
        boolean localShell = connection.isLocalShell();
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get(localShell ? "dialog.closeLocalShell" : "dialog.closeConnection"));
        alert.setHeaderText(I18n.get(localShell ? "dialog.closeLocalShellQuestion" : "dialog.closeConnectionQuestion"));
        alert.setContentText(I18n.get(
            localShell ? "dialog.closeLocalShellMessage" : "dialog.closeConnectionMessage",
            connection.getDisplayName()));
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
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
            // Same rule as in TerminalView: the "Cursor blinks" choice has its own setting and wins.
            Boolean cursorBlink = globalSettings != null ? globalSettings.isTerminalCursorBlink() : null;
            return ConnectionSettingsSupport.effectiveTerminalSettings(connectionSettings, globalDefaults, cursorBlink);
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

    /**
     * Red bar for a journal that started automatically on connect while its AI is not reachable:
     * capture already runs (the terminal is never blocked), the user decides whether to test
     * again, keep recording without AI (the journal can be evaluated later) or end the journal.
     */
    private void createJournalAiBar() {
        journalAiLabel = new Label();
        journalAiLabel.setStyle("-fx-text-fill: white;");
        journalAiLabel.setWrapText(true);
        journalAiLabel.setMaxWidth(Double.MAX_VALUE);
        javafx.scene.layout.HBox.setHgrow(journalAiLabel, Priority.ALWAYS);
        Button retryButton = new Button(I18n.get("terminal.journal.ai.retry"));
        retryButton.setOnAction(event -> retryAutomaticJournalAi());
        Button withoutAiButton = new Button(I18n.get("terminal.journal.ai.withoutAi"));
        withoutAiButton.setOnAction(event -> {
            terminalView.disableSessionJournalAi();
            hideJournalAiBar();
            journalStatusLabel.setText(I18n.get("terminal.journal.ai.recordingWithoutAi"));
        });
        Button stopButton = new Button(I18n.get("terminal.journal.ai.stopJournal"));
        stopButton.setOnAction(event -> {
            hideJournalAiBar();
            stopJournal();
        });
        journalAiBar = new javafx.scene.layout.HBox(10, journalAiLabel, retryButton, withoutAiButton, stopButton);
        journalAiBar.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        journalAiBar.setStyle("-fx-background-color: #8B0000; -fx-padding: 4 10;");
        journalAiBar.setMaxWidth(Double.MAX_VALUE);
        journalAiBar.setVisible(false);
        journalAiBar.setManaged(false);
    }

    private void onAutomaticJournalAiPreflight(de.kortty.core.SessionJournalAiPreflight.Result result) {
        journalAiCheckRunning = false;
        if (result.ok() || !isJournalActive()) {
            hideJournalAiBar();
            refreshJournalUi();
            return;
        }
        journalAiLabel.setText(I18n.get("terminal.journal.ai.failedBar", failureText(result)));
        applyChromeAwareVisibility(journalAiBar, true, true);
    }

    private void retryAutomaticJournalAi() {
        if (journalAiCheckRunning) {
            return;
        }
        journalAiCheckRunning = true;
        journalAiLabel.setText(I18n.get("terminal.journal.ai.checking"));
        terminalView.runJournalAiPreflight();
    }

    private void hideJournalAiBar() {
        if (journalAiBar != null) {
            applyChromeAwareVisibility(journalAiBar, false, false);
        }
    }

    private static String failureText(de.kortty.core.SessionJournalAiPreflight.Result result) {
        String profile = result.profileName() != null && !result.profileName().isBlank()
            ? result.profileName() : I18n.get("terminal.journal.ai.noProfile");
        String reason = result.message() != null ? result.message() : "";
        return profile + " — " + reason;
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
            previousJournalAiBarVisible = journalAiBar != null && journalAiBar.isVisible();
            previousJournalAiBarManaged = journalAiBar != null && journalAiBar.isManaged();
            terminalChromeVisible = false;
            applyNodeVisibility(recordingBar, false, false);
            applyNodeVisibility(journalBar, false, false);
            applyNodeVisibility(statusBarLabel, false, false);
            applyNodeVisibility(disconnectedStatusBar, false, false);
            applyNodeVisibility(journalDecisionBar, false, false);
            applyNodeVisibility(journalAiBar, false, false);
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
        applyNodeVisibility(journalAiBar, previousJournalAiBarVisible, previousJournalAiBarManaged);
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
        if (!terminalView.sessionJournalWouldUseAi()) {
            enableJournalNow(false);
            return;
        }
        checkAiThenStartJournal();
    }

    /**
     * A journal whose summaries can never be written is pointless: test the AI first and only
     * then start. The scrollback is imported when the journal starts, so output printed during
     * the test is not lost.
     */
    private void checkAiThenStartJournal() {
        if (journalAiCheckRunning) {
            return;
        }
        journalAiCheckRunning = true;
        journalStatusLabel.setText(I18n.get("terminal.journal.ai.checking"));
        de.kortty.core.SessionJournalAiPreflight.checkAsync(de.kortty.core.SessionJournalAiSupport.applicationInvoker())
            .thenAccept(result -> Platform.runLater(() -> {
                journalAiCheckRunning = false;
                if (result.ok()) {
                    enableJournalNow(false);
                } else {
                    askAfterFailedJournalAiCheck(result);
                }
            }));
    }

    private void askAfterFailedJournalAiCheck(de.kortty.core.SessionJournalAiPreflight.Result result) {
        ButtonType retry = new ButtonType(I18n.get("terminal.journal.ai.retry"), ButtonBar.ButtonData.OK_DONE);
        ButtonType withoutAi = new ButtonType(I18n.get("terminal.journal.ai.withoutAi"), ButtonBar.ButtonData.OTHER);
        ButtonType cancel = new ButtonType(I18n.get("terminal.journal.ai.cancel"), ButtonBar.ButtonData.CANCEL_CLOSE);
        Alert alert = new Alert(Alert.AlertType.WARNING, "", retry, withoutAi, cancel);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("terminal.journal.ai.dialogTitle"));
        alert.setHeaderText(I18n.get("terminal.journal.ai.dialogHeader"));
        alert.setContentText(I18n.get("terminal.journal.ai.dialogText", failureText(result)));
        alert.getDialogPane().setMinHeight(javafx.scene.layout.Region.USE_PREF_SIZE);
        ButtonType choice = alert.showAndWait().orElse(cancel);
        if (choice == retry) {
            checkAiThenStartJournal();
        } else if (choice == withoutAi) {
            enableJournalNow(true);
        } else {
            refreshJournalUi();
        }
    }

    private void enableJournalNow(boolean withoutAi) {
        if (!terminalView.isConnected()) {
            showJournalError(I18n.get("terminal.journal.error.notConnected"));
        } else if (!terminalView.enableSessionJournalRetroactively()) {
            showJournalError(I18n.get("terminal.journal.error.start", ""));
        } else if (withoutAi) {
            terminalView.disableSessionJournalAi();
        }
        refreshJournalUi();
        if (withoutAi && isJournalActive()) {
            journalStatusLabel.setText(I18n.get("terminal.journal.ai.recordingWithoutAi"));
        }
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
        javafx.scene.control.Dialog<String> dialog = new javafx.scene.control.Dialog<>();
        DialogThemeHelper.applyTheme(dialog);
        dialog.setTitle(I18n.get("terminal.journal.note.title"));
        dialog.setHeaderText(I18n.get("terminal.journal.note.header"));
        dialog.setResizable(true);
        DialogGeometrySupport.installAutomatic(dialog, "terminal.journalNote");
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        SessionJournalNoteEditor editor = new SessionJournalNoteEditor();
        editor.setPrefWidth(560);
        editor.setText(prefillText);
        dialog.getDialogPane().setContent(editor);
        dialog.setResultConverter(button -> button == ButtonType.OK ? editor.getText() : null);
        editor.focusText();
        String noteText = dialog.showAndWait().orElse(null);
        editor.cancelTranslation();
        if (noteText == null || noteText.isBlank()) {
            return;
        }
        final String text = noteText;
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
        if (!active) {
            hideJournalAiBar();
        }
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
        } else if (node == journalAiBar) {
            previousJournalAiBarVisible = visible;
            previousJournalAiBarManaged = managed;
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

        List<de.kortty.core.SshTunnelManager.TunnelStatus> tunnelStatuses = terminalView.getTunnelStatuses();
        String tunnelSummary = TunnelStatusSupport.statusBarSummary(tunnelStatuses);
        if (tunnelSummary != null) {
            if (status.length() > 0) {
                status.append(" | ");
            }
            status.append(tunnelSummary);
        }
        final String tunnelTooltip = TunnelStatusSupport.tooltip(tunnelStatuses);
        
        final boolean wasInterrupted = interrupted;
        final long interruptedMs = interruptedSinceMs;
        Platform.runLater(() -> {
            statusBarLabel.setText(status.toString());
            if (!java.util.Objects.equals(tunnelTooltip, statusBarTunnelTooltip)) {
                // Replaced only when the text changes: the bar refreshes every second.
                statusBarTunnelTooltip = tunnelTooltip;
                statusBarLabel.setTooltip(tunnelTooltip != null ? new Tooltip(tunnelTooltip) : null);
            }
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
        cancelAutoReconnectTimer(); // a running attempt supersedes any pending automatic one
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
     * Delay in seconds before the given automatic reconnect attempt (0-based): a gentle backoff
     * capped at one minute, so a long outage does not hammer the server.
     */
    static int autoReconnectDelaySeconds(int attempt) {
        final int[] delays = {3, 5, 10, 20, 30, 60};
        return delays[Math.min(Math.max(attempt, 0), delays.length - 1)];
    }

    private static boolean isAutoReconnectEnabled() {
        try {
            var app = KorTTYApplication.getInstance();
            GlobalSettings settings = app != null && app.getGlobalSettingsManager() != null
                ? app.getGlobalSettingsManager().getSettings()
                : null;
            return settings != null && settings.isAutoReconnectEnabled();
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Arms or continues automatic reconnection after the disconnected UI was shown. It arms only
     * when the global setting is on and an established connection was lost (never for failed
     * first connects), and disarms when a reconnect failure is permanent (authentication,
     * host key, configuration, enterprise policy). A permanent failure never arms it either: the
     * replaced connector of a manual reconnect can still report its earlier connection loss.
     * Runs on the JavaFX thread.
     */
    private void maybeScheduleAutoReconnect() {
        if (!isAutoReconnectEnabled()) {
            autoReconnectActive = false;
            return;
        }
        if (autoReconnectActive) {
            if (terminalView.isLastConnectFailurePermanent()) {
                autoReconnectActive = false;
                return;
            }
            autoReconnectAttempt++;
        } else {
            if (!terminalView.wasConnectionLost() || terminalView.isLastConnectFailurePermanent()) {
                return;
            }
            autoReconnectActive = true;
            autoReconnectAttempt = 0;
        }
        scheduleAutoReconnectAttempt(autoReconnectDelaySeconds(autoReconnectAttempt));
    }

    /** Counts down in the red status bar, then fires the next automatic reconnect attempt. */
    private void scheduleAutoReconnectAttempt(int delaySeconds) {
        cancelAutoReconnectTimer();
        final int[] remaining = {delaySeconds};
        showAutoReconnectCountdown(remaining[0]);
        autoReconnectTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remaining[0]--;
            if (remaining[0] > 0) {
                showAutoReconnectCountdown(remaining[0]);
                return;
            }
            cancelAutoReconnectTimer();
            if (getTabPane() == null || !autoReconnectActive || !isConnectionFailed
                    || !isAutoReconnectEnabled()) {
                autoReconnectActive = false;
                return;
            }
            retryConnection();
        }));
        autoReconnectTimer.setCycleCount(delaySeconds);
        autoReconnectTimer.play();
    }

    private void showAutoReconnectCountdown(int secondsRemaining) {
        if (disconnectedStatusBar == null) {
            return;
        }
        String timeStr = disconnectedAt != null
            ? DateTimeFormatter.ofPattern("HH:mm").format(disconnectedAt.atZone(ZoneId.systemDefault()))
            : "";
        disconnectedStatusBar.setText(
            I18n.get("statusBar.autoReconnectCountdown", timeStr, secondsRemaining));
    }

    private void cancelAutoReconnectTimer() {
        if (autoReconnectTimer != null) {
            autoReconnectTimer.stop();
            autoReconnectTimer = null;
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
                        if (wasError) {
                            // Make the dead session visibly inactive (no blinking cursor).
                            terminalView.setAllCursorsVisible(false);
                        }
                        // No automatic reconnect here: the journal decision bar is asking the
                        // user whether to reconnect (continuing the journal) or end it.
                    }
                    case KEEP_OPEN_DISCONNECTED -> {
                        isConnectionFailed = true;
                        updateTabTitle(" (DISCONNECT)");
                        setTabErrorColor();
                        showDisconnectedStatusBar();
                        if (wasError) {
                            // Make the dead session visibly inactive (no blinking cursor). Mosh
                            // transient interruptions (!wasError) recover and keep their cursor.
                            terminalView.setAllCursorsVisible(false);
                        }
                        maybeScheduleAutoReconnect();
                    }
                }
            });
        });
        
        // Register callback for successful connection and preserve optional external listeners.
        terminalView.setOnConnectedCallback(() -> {
            Platform.runLater(() -> {
                reconnectInProgress = false;
                autoReconnectActive = false;
                autoReconnectAttempt = 0;
                cancelAutoReconnectTimer();
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
        terminalView.setOnCloseTabRequest(() -> Platform.runLater(() -> {
            if (reconnectInProgress) {
                // The old session's teardown during a reconnect must not close the tab.
                return;
            }
            closeTabSilently();
        }));

        terminalView.connect();
    }

    public void setOnConnectedCallback(Runnable callback) {
        this.externalConnectedCallback = callback;
    }
    
    /**
     * Closes the tab without confirmation dialog.
     *
     * <p>Releases the same resources as a user-initiated close: removing the tab programmatically
     * fires no {@code onCloseRequest}/{@code onClosed}, so without {@link TerminalView#cleanup()}
     * a split left as the last pane stayed counted as connected by power management and the JMX
     * connection registry (splits have no disconnect listener; only a pane close reports them),
     * a local shell closed with Ctrl+D kept running, and the status-bar timeline pinned the tab.
     */
    private void closeTabSilently() {
        TabPane tabPane = getTabPane();
        if (tabPane != null) {
            releaseResources();
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
            terminalView.setAllCursorsVisible(false);
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
     * Starts quick select in the focused pane (Edit &gt; Quick Select).
     */
    public void startQuickSelect() {
        terminalView.startQuickSelect();
    }

    /**
     * Scrolls the focused pane to its previous or next prompt (Edit &gt; Previous Prompt / Next
     * Prompt); the pane needs shell integration in its shell.
     */
    ShellIntegrationController.JumpResult jumpToPrompt(de.kortty.shellintegration.PromptNavigator.Direction direction) {
        return terminalView.jumpToPrompt(direction);
    }

    /**
     * Selects or copies what the focused pane's last command printed (Edit &gt; Select Last Output /
     * Copy Last Output); the pane needs shell integration in its shell.
     */
    ShellIntegrationController.LastOutputResult lastOutput(ShellIntegrationController.LastOutputAction action) {
        return terminalView.lastOutput(action);
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
     * Re-renders the tab title and clears the connection-status suffix.
     */
    public void updateTabTitle() {
        updateTabTitle("");
    }
    
    /**
     * Re-renders the tab title from its slots (see {@link #composeTitle}) and remembers the suffix,
     * so a later badge or name change keeps it.
     * @param suffix Additional suffix to append (e.g., " (DISCONNECT)")
     */
    private void updateTabTitle(String suffix) {
        String effectiveSuffix = suffix != null ? suffix : "";
        lastTitleSuffix = effectiveSuffix;
        Platform.runLater(() -> setText(composeTitle(
            List.of(agentStatusBadge, attentionBadge), tabGroup, getEffectiveTitle(), effectiveSuffix)));
    }

    /**
     * The tab title from its slots, in this order: the badges, the {@code [group]} prefix of the tab
     * group (not the connection group), the tab's name and the connection-status suffix such as
     * {@code " (DISCONNECT)"}. {@code badges} is in slot order: the AI-agent status, then the
     * attention mark ({@link #ATTENTION_BADGE}); badges added later go after them. Empty badges and
     * a blank group are left out.
     */
    static String composeTitle(List<String> badges, String group, String name, String suffix) {
        StringBuilder title = new StringBuilder();
        if (badges != null) {
            for (String badge : badges) {
                if (badge != null && !badge.isBlank()) {
                    title.append(badge.strip()).append(' ');
                }
            }
        }
        if (group != null && !group.isBlank()) {
            title.append('[').append(group.strip()).append("] ");
        }
        return title.append(name != null ? name : "").append(suffix != null ? suffix : "").toString();
    }

    /**
     * The name a tab goes by, without badges, group or suffix: the custom title the user gave it,
     * else the title the program in the terminal set ({@code shellTitle}, already cleaned), else the
     * connection's display name, else {@code user@host} (or whichever of the two is set). Empty only
     * when there is nothing at all to show.
     */
    static String effectiveTitle(String customTitle, String shellTitle, String displayName, String username,
                                 String host) {
        if (customTitle != null && !customTitle.isBlank()) {
            return customTitle;
        }
        if (shellTitle != null && !shellTitle.isBlank()) {
            return shellTitle;
        }
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        boolean hasUser = username != null && !username.isBlank();
        boolean hasHost = host != null && !host.isBlank();
        if (hasUser && hasHost) {
            return username + "@" + host;
        }
        if (hasHost) {
            return host;
        }
        return hasUser ? username : "";
    }

    /**
     * {@link #effectiveTitle} for this tab: what the tab bar shows between the group prefix and the
     * suffix, and the title the coding-agent panel and the Control API's {@code tab.list} report.
     * Safe to call from any thread.
     */
    public String getEffectiveTitle() {
        return effectiveTitle(customTitle, shellTitle, connection.getDisplayName(), connection.getUsername(),
            connection.getHost());
    }

    /** The connection's display name or user@host: what the tab shows without a custom or shell title. */
    public String getConnectionTitle() {
        return effectiveTitle(null, null, connection.getDisplayName(), connection.getUsername(), connection.getHost());
    }

    /**
     * What the tab shows without a custom title: the title the shell set, else the connection's name.
     * The rename dialog offers it and goes back to it when the name is cleared.
     */
    public String getAutomaticTitle() {
        return effectiveTitle(null, shellTitle, connection.getDisplayName(), connection.getUsername(),
            connection.getHost());
    }

    /** The title the program in the focused pane set, cleaned; {@code null} when none is shown. */
    public String getShellTitle() {
        return shellTitle;
    }

    /**
     * The program in the focused pane set a new title, or the focus moved to a pane with another one
     * ({@code null}: none). Only the name slot changes; the badges, the group, the status suffix and
     * every color stay as they are. FX thread.
     */
    private void onShellTitleChanged(String title) {
        shellTitle = title;
        updateTabTitle(lastTitleSuffix);
        refreshTooltip();
    }

    /** Re-reads the title the program set, after the Window setting changed. FX thread. */
    public void refreshShellTitle() {
        terminalView.refreshShellTitle();
    }

    /** The name the user gave this tab, or {@code null} when it shows the shell's title or the connection's name. */
    public String getCustomTitle() {
        return customTitle;
    }

    /**
     * Gives the tab a name of its own in place of the connection's name; the badges, the group prefix
     * and the connection-status suffix stay. The name is cleaned with {@link #normalizeCustomTitle},
     * and a blank one (or {@code null}) goes back to the connection's name. Safe to call from any
     * thread.
     */
    public void setCustomTitle(String title) {
        customTitle = normalizeCustomTitle(title);
        updateTabTitle(lastTitleSuffix);
        refreshTooltip();
    }

    /**
     * A custom title as it is stored: control and bidi characters removed, trimmed and capped at
     * {@link #MAX_CUSTOM_TITLE_LENGTH} characters; {@code null} when nothing visible is left.
     */
    static String normalizeCustomTitle(String title) {
        String clean = DisplayTextSanitizer.sanitize(title, MAX_CUSTOM_TITLE_LENGTH);
        return clean.isEmpty() ? null : clean;
    }

    /**
     * The custom title the rename dialog's input stands for. A name equal to the one the tab shows
     * on its own ({@code automaticName}: the shell's title, else the connection's name) is no custom
     * title, so confirming the prefilled name unchanged keeps the tab following the shell and the
     * connection, and a later rename of the connection still shows. Blank input clears the custom
     * title.
     */
    static String customTitleFromInput(String input, String automaticName) {
        String title = normalizeCustomTitle(input);
        return title != null && title.equals(automaticName) ? null : title;
    }

    /**
     * Marks the tab with its connection's color: a dot in the tab header, a tooltip that names the
     * connection and the color, which is also what screen readers read for the dot, and, when
     * {@code showFrame} is set (Window settings), a frame of that color around the terminal.
     * {@code environmentName} is the credential environment the color comes from, which the tooltip
     * names; {@code null} when the color is set on the connection itself. {@code null} or a value
     * that is not a hex color removes all three. The tab's style is left alone: it shows the
     * connection status (yellow while connecting, dark red when the connection failed). Safe to
     * call from any thread.
     */
    public void applyConnectionColor(String hex, String environmentName, boolean showFrame) {
        String color = ConnectionColorSupport.normalizeHex(hex);
        if (Platform.isFxApplicationThread()) {
            showConnectionColor(color, environmentName, showFrame);
        } else {
            Platform.runLater(() -> showConnectionColor(color, environmentName, showFrame));
        }
    }

    private void showConnectionColor(String color, String environmentName, boolean showFrame) {
        // The frame is the content's border, outside the panes: never the terminal view's style,
        // which the see-through window mode owns. Turning it on or off resizes the terminal by 3 px,
        // so an unchanged frame is left in place rather than replaced by an equal one.
        javafx.scene.layout.Border frame = TabColorPresentation.frameFor(color, showFrame);
        if (!java.util.Objects.equals(content.getBorder(), frame)) {
            content.setBorder(frame);
        }
        if (connectionColorSwatch != null) {
            tabDecorations.getChildren().remove(connectionColorSwatch);
            connectionColorSwatch = null;
        }
        if (color == null) {
            connectionColorLine = null;
        } else {
            String family = I18n.get(TabColorPresentation.familyKey(ConnectionColorSupport.family(color)));
            String colorLine = environmentName == null
                ? I18n.get("tab.tooltip.connectionColor", family, color)
                : I18n.get("tab.tooltip.environmentColor", family, color, environmentName);
            String connectionLine = I18n.get("tab.tooltip.connection", connectionEndpoint());
            connectionColorSwatch = TabColorPresentation.swatch(color,
                TabColorPresentation.describe(colorLine, connectionLine, ", "));
            tabDecorations.getChildren().add(0, connectionColorSwatch);
            connectionColorLine = colorLine;
        }
        refreshTooltip();
        setGraphic(tabDecorations.getChildren().isEmpty() ? null : tabDecorations);
    }

    /**
     * Sets the tab's tooltip from what it shows: when the name comes from the shell, which connection
     * the tab really is, and the tab color with its source. No tooltip without either. Any thread.
     */
    private void refreshTooltip() {
        if (!Platform.isFxApplicationThread()) {
            Platform.runLater(this::refreshTooltip);
            return;
        }
        String shellLine = customTitle == null && shellTitle != null
            ? I18n.get("tab.tooltip.shellTitle", getConnectionTitle())
            : null;
        String text = tooltipText(I18n.get("tab.tooltip.connection", connectionEndpoint()), shellLine,
            connectionColorLine, attentionLine);
        if (text == null) {
            setTooltip(null);
        } else if (getTooltip() != null) {
            getTooltip().setText(text);
        } else {
            setTooltip(new Tooltip(text));
        }
    }

    /**
     * The tab tooltip: the connection line ({@code user@host}), then the note that the name comes from
     * the shell, then the tab color line, each on its own line. {@code null} when there is neither a
     * shell note nor a color, since the connection line alone would only repeat the tab's name.
     */
    static String tooltipText(String connectionLine, String shellTitleLine, String colorLine) {
        return tooltipText(connectionLine, shellTitleLine, colorLine, null);
    }

    /**
     * {@link #tooltipText(String, String, String)} with the reason for the attention mark as the last
     * line, which also makes a tooltip on its own, so the mark is always explained in words.
     */
    static String tooltipText(String connectionLine, String shellTitleLine, String colorLine, String attentionLine) {
        if ((shellTitleLine == null || shellTitleLine.isBlank()) && (colorLine == null || colorLine.isBlank())
                && (attentionLine == null || attentionLine.isBlank())) {
            return null;
        }
        java.util.StringJoiner text = new java.util.StringJoiner("\n");
        for (String line : java.util.Arrays.asList(connectionLine, shellTitleLine, colorLine, attentionLine)) {
            if (line != null && !line.isBlank()) {
                text.add(line);
            }
        }
        return text.toString();
    }

    /** {@code user@host} of the tab's connection; the connection's name for a local shell or without either. */
    private String connectionEndpoint() {
        if (connection.getProtocol() == ConnectionProtocol.LOCAL_SHELL) {
            return getConnectionTitle();
        }
        String endpoint = effectiveTitle(null, null, null, connection.getUsername(), connection.getHost());
        return endpoint.isEmpty() ? getConnectionTitle() : endpoint;
    }

    /**
     * Marks the tab with {@link #ATTENTION_BADGE} after the agent status, and adds {@code reason} to
     * its tooltip, until the user looks at the tab ({@link #clearAttention}). A later reason replaces
     * the earlier one. FX thread.
     */
    public void markAttention(String reason) {
        String line = reason != null && !reason.isBlank() ? reason : null;
        if (ATTENTION_BADGE.equals(attentionBadge) && java.util.Objects.equals(line, attentionLine)) {
            return;
        }
        attentionBadge = ATTENTION_BADGE;
        attentionLine = line;
        updateTabTitle(lastTitleSuffix);
        refreshTooltip();
    }

    /** Removes the attention mark: the user is looking at the tab now. FX thread. */
    public void clearAttention() {
        if (attentionBadge.isEmpty() && attentionLine == null) {
            return;
        }
        attentionBadge = "";
        attentionLine = null;
        updateTabTitle(lastTitleSuffix);
        refreshTooltip();
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
