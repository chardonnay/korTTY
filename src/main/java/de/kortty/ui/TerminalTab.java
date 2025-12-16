package de.kortty.ui;

import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;

/**
 * A tab containing a terminal view for an SSH session.
 */
public class TerminalTab extends Tab {
    
    private final ServerConnection connection;
    private final LanternaTerminalView terminalView;
    private final ConnectionSettings settings;
    
    public TerminalTab(ServerConnection connection, String password) {
        this.connection = connection;
        this.settings = connection.getSettings();
        this.terminalView = new LanternaTerminalView(connection, password);
        
        setText(connection.getDisplayName());
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
     * Connects to the SSH server.
     */
    public void connect() {
        terminalView.connect();
    }
    
    /**
     * Called when the SSH connection fails.
     */
    public void onConnectionFailed(String error) {
        terminalView.showError("Verbindung fehlgeschlagen: " + error);
        Platform.runLater(() -> setText(connection.getDisplayName() + " (Fehler)"));
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
    
    public LanternaTerminalView getTerminalView() {
        return terminalView;
    }
    
    public boolean isConnected() {
        return terminalView.isConnected();
    }
}
