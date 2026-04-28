package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ThemeManager;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import de.kortty.model.TemporarySSHKey;
import de.kortty.model.Theme;
import javafx.application.Platform;
import javafx.scene.control.TabPane;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.scene.control.Label;
import javafx.scene.layout.Priority;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.scene.input.MouseButton;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * A tab containing a terminal view for an SSH session.
 */
public class TerminalTab extends Tab {

    private final ServerConnection connection;
    private final TerminalView terminalView;
    private final AiAgentActivityPanel aiAgentActivityPanel;
    private ConnectionSettings settings;
    private final TemporarySSHKey temporarySSHKey;
    private final String aiSessionId;
    private boolean isConnectionFailed = false;
    private Instant connectionStartTime;
    private Timeline statusBarTimer;
    private Label statusBarLabel;
    private Label disconnectedStatusBar;
    private Instant disconnectedAt;
    private volatile boolean reconnectInProgress = false;
    /** True when the red disconnected bar was shown due to mosh network interruption (so we hide it on recovery). */
    private boolean moshInterruptedBarVisible = false;
    private Runnable externalConnectedCallback;
    
    // Tab group (independent from connection group)
    private String tabGroup = null;
    
    public TerminalTab(ServerConnection connection, String password) {
        this(connection, password, null);
    }
    
    public TerminalTab(ServerConnection connection, String password, TemporarySSHKey temporarySSHKey) {
        this.connection = connection;
        this.settings = connection.getSettings();
        this.temporarySSHKey = temporarySSHKey;
        this.aiSessionId = UUID.randomUUID().toString();
        this.connectionStartTime = Instant.now();
        this.terminalView = new TerminalView(connection, password, temporarySSHKey);
        this.aiAgentActivityPanel = new AiAgentActivityPanel();
        applyAiAgentActivityTheme(settings);
        this.terminalView.setOnReconnectRequested(this::triggerReconnect);
        
        // Create status bar (connection duration / key validity)
        createStatusBar();
        // Create disconnected status bar (red bar, shown when server disconnects; double-click to reconnect)
        createDisconnectedStatusBar();
        
        updateTabTitle();
        
        // Create container with terminal view and status bars
        javafx.scene.layout.VBox container = new javafx.scene.layout.VBox();
        container.getChildren().add(terminalView);
        container.getChildren().add(aiAgentActivityPanel);
        if (statusBarLabel != null) {
            container.getChildren().add(statusBarLabel);
        }
        if (disconnectedStatusBar != null) {
            container.getChildren().add(disconnectedStatusBar);
        }
        javafx.scene.layout.VBox.setVgrow(terminalView, Priority.ALWAYS);
        
        setContent(container);
        setClosable(true);
        
        // Handle tab close
        setOnCloseRequest(event -> {
            if (terminalView.isConnected() && !settings.isCloseWithoutConfirmation()) {
                // Show confirmation dialog
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle(I18n.get("dialog.closeConnection"));
                alert.setHeaderText(I18n.get("dialog.closeConnectionQuestion"));
                alert.setContentText(I18n.get("dialog.closeConnectionMessage", connection.getDisplayName()));
                
                if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                    event.consume(); // Cancel the close
                    return;
                }
            }
            terminalView.cleanup();
        stopStatusBarTimer();
        });
    }
    
    /**
     * Creates the status bar showing SSH key validity and connection duration.
     */
    private void createStatusBar() {
        // Always show status bar so transient network interruption details are visible.
        statusBarLabel = new Label();
        statusBarLabel.setStyle("-fx-background-color: #2d2d2d; -fx-text-fill: #cccccc; -fx-padding: 3 8 3 8; -fx-font-size: 11px;");
        
        // Start timer to update status bar
        startStatusBarTimer();
    }
    
    /**
     * Creates the red status bar shown when the server is disconnected.
     * Displays timestamp and "Double-click to reconnect"; double-click triggers reconnect.
     */
    private void createDisconnectedStatusBar() {
        disconnectedStatusBar = new Label();
        disconnectedStatusBar.setStyle("-fx-background-color: #8B0000; -fx-text-fill: white; -fx-padding: 6 10; -fx-font-size: 12px; -fx-cursor: hand;");
        disconnectedStatusBar.setVisible(false);
        disconnectedStatusBar.setManaged(false);
        disconnectedStatusBar.setOnMouseClicked(e -> {
            if (e.getButton() == MouseButton.PRIMARY && e.getClickCount() == 2) {
                triggerReconnect();
            }
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
            disconnectedStatusBar.setVisible(true);
            disconnectedStatusBar.setManaged(true);
        }
        if (statusBarLabel != null) {
            statusBarLabel.setVisible(false);
            statusBarLabel.setManaged(false);
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
            disconnectedStatusBar.setVisible(false);
            disconnectedStatusBar.setManaged(false);
        }
        if (statusBarLabel != null) {
            statusBarLabel.setVisible(true);
            statusBarLabel.setManaged(true);
        }
        moshInterruptedBarVisible = false;
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
                statusBarLabel.setStyle("-fx-background-color: #8B0000; -fx-text-fill: white; -fx-padding: 3 8 3 8; -fx-font-size: 11px;");
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
                statusBarLabel.setStyle("-fx-background-color: #2d2d2d; -fx-text-fill: #cccccc; -fx-padding: 3 8 3 8; -fx-font-size: 11px;");
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
            Platform.runLater(() -> {
                if (!wasError) {
                    if (reconnectInProgress) {
                        reconnectInProgress = false;
                        return;
                    }
                    boolean isMoshSession = connection.getProtocol() == ConnectionProtocol.MOSH;
                    boolean isRemoteLogout = reason != null
                            && reason.toLowerCase().contains("remote logout");
                    if (!isMoshSession || isRemoteLogout) {
                        closeTabSilently();
                        return;
                    }
                    // Transient mosh disconnect without hard error: keep tab open
                    // and show reconnect/disconnect UI.
                }
                isConnectionFailed = true;
                updateTabTitle(" (DISCONNECT)");
                setTabErrorColor();
                showDisconnectedStatusBar();
            });
        });
        
        // Register callback for successful connection and preserve optional external listeners.
        terminalView.setOnConnectedCallback(() -> {
            Platform.runLater(() -> {
                reconnectInProgress = false;
                updateTabTitle();
                resetTabColor(); // Reset to default (green/normal)
                hideDisconnectedStatusBar();
                if (externalConnectedCallback != null) {
                    externalConnectedCallback.run();
                }
            });
        });
        
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
        this.settings = connectionSettings;
        terminalView.applyConnectionSettings(connectionSettings);
        applyAiAgentActivityTheme(connectionSettings);
    }

    public AiAgentActivityPanel getAiAgentActivityPanel() {
        return aiAgentActivityPanel;
    }

    private void applyAiAgentActivityTheme(ConnectionSettings connectionSettings) {
        Theme theme = resolveAiAgentTheme(connectionSettings);
        aiAgentActivityPanel.applyTheme(theme);
        terminalView.applyTerminalAgentBusyTheme(theme);
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
        Platform.runLater(() -> {
            String displayName = connection.getDisplayName();
            if (displayName == null || displayName.trim().isEmpty()) {
                displayName = connection.getUsername() + "@" + connection.getHost();
            }
            
            String group = tabGroup; // Use tab group, not connection group
            if (group != null && !group.trim().isEmpty()) {
                setText("[" + group + "] " + displayName + suffix);
            } else {
                setText(displayName + suffix);
            }
        });
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
