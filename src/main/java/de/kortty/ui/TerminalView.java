package de.kortty.ui;

import com.techsenger.jeditermfx.core.TerminalColor;
import com.techsenger.jeditermfx.core.TextStyle;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.settings.DefaultSettingsProvider;
import de.kortty.core.SshTtyConnector;
import de.kortty.core.DisconnectListener;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Terminal view component using JediTermFX for professional terminal emulation.
 */
public class TerminalView extends BorderPane {
    
    private static final Logger logger = LoggerFactory.getLogger(TerminalView.class);
    
    private final ServerConnection connection;
    private final ConnectionSettings settings;
    private final String password;
    
    private JediTermFxWidget terminalWidget;
    private SshTtyConnector ttyConnector;
    private KorTTYSettingsProvider settingsProvider;
    private int currentFontSize;
    private final int defaultFontSize;
    
    private DisconnectListener externalDisconnectListener;
    
    public TerminalView(ServerConnection connection, String password) {
        this.connection = connection;
        this.settings = connection.getSettings();
        this.password = password;
        this.defaultFontSize = settings.getFontSize();
        this.currentFontSize = defaultFontSize;
        
        initializeTerminal();
    }
    
    private void initializeTerminal() {
        // Create settings provider with our custom settings
        settingsProvider = new KorTTYSettingsProvider(settings, this);
        
        // Create terminal widget
        terminalWidget = new JediTermFxWidget(settingsProvider);
        
        // Set the terminal pane as center content
        setCenter(terminalWidget.getPane());
        
        // Request focus on the terminal
        Platform.runLater(() -> {
            if (terminalWidget.getPreferredFocusableNode() != null) {
                terminalWidget.getPreferredFocusableNode().requestFocus();
            }
        });
    }
    
    /**
     * Gets the current font size for the settings provider.
     */
    int getCurrentFontSize() {
        return currentFontSize;
    }
    
    /**
     * Sets a listener to be notified when the SSH connection is disconnected.
     */
    public void setDisconnectListener(DisconnectListener listener) {
        this.externalDisconnectListener = listener;
    }
    
    /**
     * Connects to the SSH server and starts the terminal session.
     */
    public void connect() {
        try {
            // Create TtyConnector
            ttyConnector = new SshTtyConnector(connection, password);
            
            // Register disconnect listener
            ttyConnector.setDisconnectListener((reason, wasError) -> {
                logger.info("Disconnect event: {} (wasError={})", reason, wasError);
                if (externalDisconnectListener != null) {
                    externalDisconnectListener.onDisconnect(reason, wasError);
                }
            });
            
            // Connect SSH first
            if (!ttyConnector.connect()) {
                showError("SSH-Verbindung fehlgeschlagen");
                return;
            }
            
            // Set the connector and start the terminal
            terminalWidget.setTtyConnector(ttyConnector);
            terminalWidget.start();
            
            logger.info("Terminal session started for {}", connection.getDisplayName());
            
        } catch (Exception e) {
            logger.error("Failed to start terminal session: {}", e.getMessage(), e);
            showError("Verbindung fehlgeschlagen: " + e.getMessage());
        }
    }
    
    /**
     * Shows an error message in the terminal.
     */
    public void showError(String message) {
        Platform.runLater(() -> {
            // Display error in terminal if possible
            if (terminalWidget != null && terminalWidget.getTerminal() != null) {
                terminalWidget.getTerminal().writeCharacters("\r\n*** " + message + " ***\r\n");
            }
        });
    }
    
    /**
     * Cleans up resources.
     */
    public void cleanup() {
        // Close connection first
        if (ttyConnector != null) {
            try {
                ttyConnector.close();
            } catch (Exception e) {
                logger.warn("Error closing TtyConnector: {}", e.getMessage());
            }
            ttyConnector = null;
        }
        
        // Stop terminal widget - catch JediTermFX bug
        if (terminalWidget != null) {
            try {
                terminalWidget.stop();
                // Give terminal time to process stop before closing
                Platform.runLater(() -> {
                    try {
                        if (terminalWidget != null) {
                            terminalWidget.close();
                        }
                    } catch (Exception e) {
                        // Known JediTermFX bug: ClassCastException in WeakRedrawTimer
                        // This is harmless and can be ignored
                        logger.debug("Ignoring JediTermFX cleanup exception (known bug): {}", e.getMessage());
                    }
                });
            } catch (Exception e) {
                logger.debug("Ignoring JediTermFX cleanup exception (known bug): {}", e.getMessage());
            }
            terminalWidget = null;
        }
    }
    
    /**
     * Checks if connected.
     */
    public boolean isConnected() {
        return ttyConnector != null && ttyConnector.isConnected();
    }
    
    /**
     * Sends input to the terminal (for broadcast mode).
     */
    public void sendInput(String text) {
        if (ttyConnector != null && ttyConnector.isConnected()) {
            try {
                ttyConnector.write(text);
            } catch (java.io.IOException e) {
                logger.error("Failed to send input to terminal", e);
            }
        }
    }
    
    /**
     * Copies selected text to clipboard.
     */
    public void copyToClipboard() {
        if (terminalWidget != null && terminalWidget.getTerminalPanel() != null) {
            // handleCopy(withCustomSelectors, byKeyStroke)
            terminalWidget.getTerminalPanel().handleCopy(false, false);
        }
    }
    
    /**
     * Pastes from clipboard.
     */
    public void pasteFromClipboard() {
        if (terminalWidget != null && terminalWidget.getTerminalPanel() != null) {
            terminalWidget.getTerminalPanel().handlePaste();
        }
    }
    
    /**
     * Zooms the terminal font.
     */
    public void zoom(int delta) {
        int newSize = Math.max(8, Math.min(72, currentFontSize + delta));
        if (newSize != currentFontSize) {
            currentFontSize = newSize;
            applyZoom();
            logger.debug("Zoom changed to font size: {}", currentFontSize);
        }
    }
    
    /**
     * Resets the terminal font size.
     */
    public void resetZoom() {
        if (currentFontSize != defaultFontSize) {
            currentFontSize = defaultFontSize;
            applyZoom();
            logger.debug("Zoom reset to default font size: {}", currentFontSize);
        }
    }
    
    /**
     * Applies the zoom by scaling the terminal pane.
     * Uses Scale transform with pivot at top-left corner (0,0) to keep text aligned.
     */
    private void applyZoom() {
        if (terminalWidget != null && terminalWidget.getPane() != null) {
            // Calculate scale factor based on font size ratio
            double scale = (double) currentFontSize / defaultFontSize;
            
            // Clear existing transforms and add scale with pivot at top-left (0,0)
            javafx.scene.transform.Scale scaleTransform = new javafx.scene.transform.Scale(scale, scale, 0, 0);
            terminalWidget.getPane().getTransforms().clear();
            terminalWidget.getPane().getTransforms().add(scaleTransform);
        }
    }
    
    /**
     * Gets the terminal history/buffer.
     */
    public String getTerminalHistory() {
        if (terminalWidget != null && terminalWidget.getTerminalTextBuffer() != null) {
            return terminalWidget.getTerminalTextBuffer().getScreenLines();
        }
        return "";
    }
    
    /**
     * Restores terminal history by writing to temp file first, then displaying it.
     * This approach shows only one short command line instead of multi-line here-doc.
     */
    public void restoreHistory(String history) {
        if (history == null || history.isEmpty()) {
            return;
        }
        
        Platform.runLater(() -> {
            if (ttyConnector != null && ttyConnector.isConnected()) {
                try {
                    // Clean up the history - remove excessive empty lines
                    String cleanHistory = history
                        .replaceAll("\\n{3,}", "\n\n") // Max 2 consecutive newlines
                        .trim();
                    
                    // Escape for shell (for writing to file via here-doc)
                    String escapedForFile = cleanHistory;
                    
                    // Use a temp file approach - much cleaner!
                    // 1. Write history to temp file (using very short here-doc 'H')
                    // 2. Clear screen and cat the file  
                    // 3. Delete the temp file
                    // This way only "clear;cat ..." is visible, not the multi-line content
                    
                    StringBuilder command = new StringBuilder();
                    String tmpFile = "/tmp/.kortty_hist_$$"; // $$ = current shell PID (unique)
                    
                    // Write to temp file (this command is short)
                    command.append("cat>").append(tmpFile).append("<<H\n");
                    command.append(escapedForFile);
                    if (!escapedForFile.endsWith("\n")) {
                        command.append("\n");
                    }
                    command.append("H\n");
                    
                    // Clear screen, show file content, delete file (all in one short line)
                    command.append("clear;cat ").append(tmpFile).append(";rm ").append(tmpFile).append("\n");
                    
                    ttyConnector.write(command.toString());
                    
                    logger.info("Restored history ({} bytes)", cleanHistory.length());
                } catch (Exception e) {
                    logger.error("Failed to restore terminal history", e);
                }
            } else {
                logger.warn("Cannot restore history: terminal not connected");
            }
        });
    }
    
    public ServerConnection getConnection() {
        return connection;
    }
    
    public SshTtyConnector getTtyConnector() {
        return ttyConnector;
    }
    
    /**
     * Custom settings provider for KorTTY.
     */
    private static class KorTTYSettingsProvider extends DefaultSettingsProvider {
        
        private final ConnectionSettings settings;
        private final TerminalView terminalView;
        
        public KorTTYSettingsProvider(ConnectionSettings settings, TerminalView terminalView) {
            this.settings = settings;
            this.terminalView = terminalView;
        }
        
        @Override
        public @NotNull Font getTerminalFont() {
            int fontSize = terminalView != null ? terminalView.getCurrentFontSize() : settings.getFontSize();
            return Font.font(settings.getFontFamily(), fontSize);
        }
        
        @Override
        public float getTerminalFontSize() {
            return terminalView != null ? terminalView.getCurrentFontSize() : settings.getFontSize();
        }
        
        @Override
        public @NotNull TextStyle getDefaultStyle() {
            Color fgColor = Color.web(settings.getForegroundColor());
            Color bgColor = Color.web(settings.getBackgroundColor());
            
            TerminalColor fg = TerminalColor.rgb(
                    (int) (fgColor.getRed() * 255),
                    (int) (fgColor.getGreen() * 255),
                    (int) (fgColor.getBlue() * 255)
            );
            TerminalColor bg = TerminalColor.rgb(
                    (int) (bgColor.getRed() * 255),
                    (int) (bgColor.getGreen() * 255),
                    (int) (bgColor.getBlue() * 255)
            );
            
            return new TextStyle(fg, bg);
        }
        
        @Override
        public boolean audibleBell() {
            return false; // Disable bell sound!
        }
        
        @Override
        public boolean enableMouseReporting() {
            return true;
        }
        
        @Override
        public boolean copyOnSelect() {
            return false;
        }
        
        @Override
        public boolean pasteOnMiddleMouseClick() {
            return true;
        }
        
        @Override
        public boolean emulateX11CopyPaste() {
            return false;
        }
        
        @Override
        public boolean useInverseSelectionColor() {
            return true;
        }
        
        @Override
        public int getBufferMaxLinesCount() {
            return 10000;
        }
    }
}
