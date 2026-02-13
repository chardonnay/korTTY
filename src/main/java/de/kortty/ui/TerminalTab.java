package de.kortty.ui;

import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.TemporarySSHKey;
import de.kortty.ui.I18n;
import javafx.application.Platform;
import javafx.scene.control.TabPane;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;
import javafx.scene.input.MouseButton;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * A tab containing a terminal view for an SSH session.
 */
public class TerminalTab extends Tab {
    
    private final ServerConnection connection;
    private final TerminalView terminalView;
    private final ConnectionSettings settings;
    private final TemporarySSHKey temporarySSHKey;
    private boolean isConnectionFailed = false;
    private Instant connectionStartTime;
    private Timeline statusBarTimer;
    private Label statusBarLabel;
    private Label disconnectedStatusBar;
    private Instant disconnectedAt;
    
    // Tab group (independent from connection group)
    private String tabGroup = null;
    
    public TerminalTab(ServerConnection connection, String password) {
        this(connection, password, null);
    }
    
    public TerminalTab(ServerConnection connection, String password, TemporarySSHKey temporarySSHKey) {
        this.connection = connection;
        this.settings = connection.getSettings();
        this.temporarySSHKey = temporarySSHKey;
        this.connectionStartTime = Instant.now();
        this.terminalView = new TerminalView(connection, password, temporarySSHKey);
        this.terminalView.setOnReconnectRequested(this::triggerReconnect);
        
        // Create status bar (connection duration / key validity)
        createStatusBar();
        // Create disconnected status bar (red bar, shown when server disconnects; double-click to reconnect)
        createDisconnectedStatusBar();
        
        updateTabTitle();
        
        // Create container with terminal view and status bars
        javafx.scene.layout.VBox container = new javafx.scene.layout.VBox();
        container.getChildren().add(terminalView);
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
        if (temporarySSHKey == null && connection.getAuthMethod() != de.kortty.model.AuthMethod.PUBLIC_KEY) {
            // No status bar needed if no temporary key and not using key auth
            return;
        }
        
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
     */
    private void showDisconnectedStatusBar() {
        disconnectedAt = Instant.now();
        String timeStr = DateTimeFormatter.ofPattern("HH:mm").format(disconnectedAt.atZone(ZoneId.systemDefault()));
        if (disconnectedStatusBar != null) {
            disconnectedStatusBar.setText(I18n.get("statusBar.disconnectedAtDoubleClick", timeStr));
            disconnectedStatusBar.setVisible(true);
            disconnectedStatusBar.setManaged(true);
        }
        if (statusBarLabel != null) {
            statusBarLabel.setVisible(false);
            statusBarLabel.setManaged(false);
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
            long hours = durationSeconds / 3600;
            long minutes = (durationSeconds % 3600) / 60;
            long secs = durationSeconds % 60;
            
            if (status.length() > 0) {
                status.append(" | ");
            }
            String durationStr;
            if (hours > 0) {
                durationStr = String.format("%d:%02d:%02d", hours, minutes, secs);
            } else {
                durationStr = String.format("%02d:%02d", minutes, secs);
            }
            status.append(I18n.get("statusBar.connectionDuration", durationStr));
        }
        
        Platform.runLater(() -> {
            statusBarLabel.setText(status.toString());
            
            // Always use standard colors - don't change color for expired keys
            statusBarLabel.setStyle("-fx-background-color: #2d2d2d; -fx-text-fill: #cccccc; -fx-padding: 3 8 3 8; -fx-font-size: 11px;");
        });
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
        
        // Register disconnect listener: keep tab and split open, show red tab and red status bar
        terminalView.setDisconnectListener((reason, wasError) -> {
            Platform.runLater(() -> {
                isConnectionFailed = true;
                updateTabTitle(" (DISCONNECT)");
                setTabErrorColor();
                showDisconnectedStatusBar();
            });
        });
        
        // Register callback for successful connection
        // Store reference to existing callback (if any) to preserve it
        Runnable existingCallback = null;
        // Note: We can't retrieve existing callback, so external callbacks set after connect()
        // will override this. This is acceptable - MainWindow will set its own callback.
        terminalView.setOnConnectedCallback(() -> {
            Platform.runLater(() -> {
                updateTabTitle();
                resetTabColor(); // Reset to default (green/normal)
                hideDisconnectedStatusBar();
            });
        });
        
        terminalView.connect();
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
