package de.kortty.ui;

import de.kortty.core.SSHSession;
import de.kortty.model.ConnectionSettings;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Tab;

/**
 * A tab containing a terminal view for an SSH session.
 */
public class TerminalTab extends Tab {
    
    private final SSHSession session;
    private final TerminalView terminalView;
    private final ConnectionSettings settings;
    
    public TerminalTab(SSHSession session, ConnectionSettings settings) {
        this.session = session;
        this.settings = settings;
        this.terminalView = new TerminalView(session, settings);
        
        setText(session.getConnection().getDisplayName());
        setContent(terminalView);
        setClosable(true);
        
        // Set up output consumer that both displays output AND updates tab title
        session.setOutputConsumer(text -> {
            // Display output in terminal
            terminalView.handleOutput(text);
            // Update tab title
            Platform.runLater(() -> {
                setText(session.generateTabTitle());
            });
        });
        
        // Handle tab close
        setOnCloseRequest(event -> {
            if (session.isConnected() && !settings.isCloseWithoutConfirmation()) {
                // Show confirmation dialog
                Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
                alert.setTitle("Verbindung schließen");
                alert.setHeaderText("SSH-Verbindung beenden?");
                alert.setContentText("Die Verbindung zu " + session.getConnection().getDisplayName() + " wird getrennt.");
                
                if (alert.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
                    event.consume(); // Cancel the close
                    return;
                }
            }
            terminalView.cleanup();
            session.disconnect();
        });
    }
    
    /**
     * Called when the SSH connection is established.
     */
    public void onConnected() {
        terminalView.onConnected();
        setText(session.generateTabTitle());
    }
    
    /**
     * Called when the SSH connection fails.
     */
    public void onConnectionFailed(String error) {
        terminalView.showError("Verbindung fehlgeschlagen: " + error);
        setText(session.getConnection().getDisplayName() + " (Fehler)");
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
    
    public String getSessionId() {
        return session.getSessionId();
    }
    
    public SSHSession getSession() {
        return session;
    }
    
    public TerminalView getTerminalView() {
        return terminalView;
    }
}
