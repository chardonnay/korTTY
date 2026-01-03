package de.kortty.ui;

import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import javafx.application.Platform;
import javafx.scene.control.TabPane;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;
import javafx.scene.paint.Color;

/**
 * A tab containing a terminal view for an SSH session.
 */
public class TerminalTab extends Tab {
    
    private final ServerConnection connection;
    private final TerminalView terminalView;
    private final ConnectionSettings settings;
    private boolean isConnectionFailed = false;
    
    public TerminalTab(ServerConnection connection, String password) {
        this.connection = connection;
        this.settings = connection.getSettings();
        this.terminalView = new TerminalView(connection, password);
        
        updateTabTitle();
        setContent(terminalView);
        setClosable(true);
        
        // Handle tab close
        setOnCloseRequest(event -> {
            if (terminalView.isConnected() && !settings.isCloseWithoutConfirmation()) {
                // Show confirmation dialog
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Verbindung schließen");
                alert.setHeaderText("SSH-Verbindung beenden?");
                alert.setContentText("Die Verbindung zu " + connection.getDisplayName() + " wird getrennt.");
                
                if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                    event.consume(); // Cancel the close
                    return;
                }
            }
            terminalView.cleanup();
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
            // Set dark red background color
            setStyle("-fx-background-color: #8B0000; -fx-text-fill: white;");
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
     * Connects to the SSH server.
     */
    public void connect() {
        // Set tab to yellow color to indicate connection attempt in progress
        setTabConnectingColor();
        
        // Register disconnect listener for auto-close on normal exit
        terminalView.setDisconnectListener((reason, wasError) -> {
            Platform.runLater(() -> {
                if (!wasError) {
                    // Normal exit - auto-close the tab
                    closeTabSilently();
                } else {
                    // Error or disconnection - mark as failed and color tab red
                    isConnectionFailed = true;
                    updateTabTitle(" (DISCONNECT)");
                    setTabErrorColor();
                }
            });
        });
        
        // Register callback for successful connection
        terminalView.setOnConnectedCallback(() -> {
            Platform.runLater(() -> {
                updateTabTitle();
                resetTabColor(); // Reset to default (green/normal)
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
        terminalView.showError("Verbindung fehlgeschlagen: " + error);
        Platform.runLater(() -> {
            updateTabTitle(" (DISCONNECT)");
            setTabErrorColor();
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
            
            String group = connection.getGroup();
            if (group != null && !group.trim().isEmpty()) {
                setText("[" + group + "] " + displayName + suffix);
            } else {
                setText(displayName + suffix);
            }
        });
    }
    
    /**
     * Gets the group name for this tab's connection.
     */
    public String getGroup() {
        return connection.getGroup();
    }
    
    /**
     * Sets the group for this tab's connection and updates the tab title.
     */
    public void setGroup(String group) {
        connection.setGroup(group);
        updateTabTitle();
    }
}
