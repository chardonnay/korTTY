package de.kortty.ui;

import com.techsenger.jeditermfx.core.TerminalColor;
import com.techsenger.jeditermfx.core.TextStyle;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.settings.DefaultSettingsProvider;
import de.kortty.core.SshTtyConnector;
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
     * Connects to the SSH server and starts the terminal session.
     */
    public void connect() {
        try {
            // Create TtyConnector
            ttyConnector = new SshTtyConnector(connection, password);
            
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
        if (terminalWidget != null) {
            terminalWidget.stop();
            terminalWidget.close();
        }
        if (ttyConnector != null) {
            ttyConnector.close();
        }
    }
    
    /**
     * Checks if connected.
     */
    public boolean isConnected() {
        return ttyConnector != null && ttyConnector.isConnected();
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
            
            // Scroll to show current output after zoom
            Platform.runLater(() -> {
                if (terminalWidget.getTerminalPanel() != null) {
                    terminalWidget.getTerminalPanel().scrollToShowAllOutput();
                }
            });
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
