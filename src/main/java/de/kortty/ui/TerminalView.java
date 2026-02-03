package de.kortty.ui;

import com.techsenger.jeditermfx.core.TerminalColor;
import com.techsenger.jeditermfx.core.TextStyle;
import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.techsenger.jeditermfx.ui.split.SplitConnectorFactory;
import com.techsenger.jeditermfx.ui.split.SplitRequest;
import com.techsenger.jeditermfx.ui.split.TerminalSplitPane;
import de.kortty.core.SshTtyConnector;
import de.kortty.core.DisconnectListener;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import javafx.application.Platform;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.geometry.Orientation;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Terminal view component using JediTermFX for professional terminal emulation.
 */
public class TerminalView extends BorderPane {
    
    private static final Logger logger = LoggerFactory.getLogger(TerminalView.class);
    
    /**
     * Callback interface for creating new connections (e.g., via QuickConnect dialog).
     * Used when user requests a split with a new connection to a different server.
     */
    @FunctionalInterface
    public interface NewConnectionCallback {
        /**
         * Called when user requests a split with a new connection.
         * Implementation should show a connection dialog and return the result.
         * @return ConnectionResult with connection details, or null if cancelled
         */
        @Nullable ConnectionResult requestNewConnection();
    }
    
    /**
     * Result of a new connection request.
     */
    public static class ConnectionResult {
        public final ServerConnection connection;
        public final String password;
        
        public ConnectionResult(ServerConnection connection, String password) {
            this.connection = connection;
            this.password = password;
        }
    }
    
    private final ServerConnection connection;
    private final ConnectionSettings settings;
    private final String password;
    private de.kortty.model.TemporarySSHKey temporarySSHKey;  // For split connections with temporary key
    
    private TerminalSplitPane splitPane;
    private JediTermFxWidget terminalWidget;  // Primary widget (first terminal in split)
    private SshTtyConnector ttyConnector;
    private KorTTYSettingsProvider settingsProvider;
    private final int defaultFontSize;
    
    private DisconnectListener externalDisconnectListener;
    private Runnable onConnectedCallback;
    private de.kortty.core.TerminalLogger terminalLogger;
    private NewConnectionCallback newConnectionCallback;
    
    public TerminalView(ServerConnection connection, String password) {
        this(connection, password, null);
    }
    
    public TerminalView(ServerConnection connection, String password, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        this.connection = connection;
        this.settings = connection.getSettings();
        this.password = password;
        this.temporarySSHKey = temporarySSHKey;
        this.defaultFontSize = settings.getFontSize();
        
        initializeTerminal();
    }
    
    private void initializeTerminal() {
        // Create settings provider with dynamic font size support (enables Cmd+Plus/Minus zoom)
        settingsProvider = new KorTTYSettingsProvider(settings, defaultFontSize);
        
        // Add listener to re-render terminals when font size changes
        settingsProvider.addFontSizeListener(this::updateAllTerminalFonts);
        
        // Create SplitConnectorFactory that creates new SSH connections for splits
        SplitConnectorFactory connectorFactory = this::createSplitConnector;
        
        // Create TerminalSplitPane with split support
        // Right-click context menu will show: Font size options + Split right/down + Close split
        splitPane = new TerminalSplitPane(settingsProvider, connectorFactory, widget -> {
            // Configure each new widget in the split
            setupWidgetEventHandlers(widget);
        });
        
        // Get the primary terminal widget (first one created by TerminalSplitPane)
        terminalWidget = splitPane.getFocusedWidget();
        
        // Use split pane as the main content
        setCenter(splitPane);
        
        // Request focus on the terminal
        Platform.runLater(() -> {
            JediTermFxWidget focused = splitPane.getFocusedWidget();
            if (focused != null && focused.getPreferredFocusableNode() != null) {
                focused.getPreferredFocusableNode().requestFocus();
            }
        });
    }
    
    /**
     * Updates the font rendering for all terminal widgets when font size changes.
     * Calls reinitFontAndResize() on each TerminalPanel via reflection since it's protected.
     */
    private void updateAllTerminalFonts() {
        if (splitPane == null) return;
        
        Platform.runLater(() -> {
            for (JediTermFxWidget widget : splitPane.getAllWidgets()) {
                try {
                    var terminalPanel = widget.getTerminalPanel();
                    var method = terminalPanel.getClass().getDeclaredMethod("reinitFontAndResize");
                    method.setAccessible(true);
                    method.invoke(terminalPanel);
                } catch (Exception e) {
                    logger.warn("Failed to update font for terminal widget: {}", e.getMessage());
                }
            }
        });
    }
    
    /**
     * Sets the callback for requesting new connections (for "Split with new connection" feature).
     */
    public void setNewConnectionCallback(NewConnectionCallback callback) {
        this.newConnectionCallback = callback;
    }
    
    /**
     * Creates a new SSH TtyConnector for a split terminal.
     * Each split gets its own independent SSH session to the same server.
     * For the initial terminal (request == null), returns null - connection is made later via connect().
     */
    private @Nullable TtyConnector createSplitConnector(@Nullable SplitRequest request) {
        // Initial terminal: return null, will be connected later via connect()
        if (request == null) {
            return null;
        }
        
        // Handle NEW_CONNECTION mode - ask user for a new connection
        if (request.getSplitMode() == SplitRequest.SplitMode.NEW_CONNECTION) {
            return createNewConnectionForSplit();
        }
        
        // SAME_SERVER_NEW_SHELL mode - create new SSH session to same server
        return createSameServerConnection();
    }
    
    /**
     * Creates a new SSH connection to the same server (for same-server splits).
     */
    private @Nullable TtyConnector createSameServerConnection() {
        try {
            logger.info("Creating new SSH connection for split to {}@{}:{}",
                    connection.getUsername(), connection.getHost(), connection.getPort());
            
            // Check if using temporary SSH key and if it's still valid
            if (temporarySSHKey != null) {
                if (!temporarySSHKey.isValid()) {
                    logger.error("Temporary SSH key has expired - cannot create split connection");
                    Platform.runLater(() -> {
                        javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                            javafx.scene.control.Alert.AlertType.ERROR);
                        alert.setTitle(I18n.get("error.title"));
                        alert.setHeaderText(I18n.get("sftp.tempKeyExpired"));
                        alert.setContentText(I18n.get("split.tempKeyExpiredMessage"));
                        alert.showAndWait();
                    });
                    return null;
                }
                logger.debug("Using temporary SSH key for split (valid for {} more seconds)", 
                    temporarySSHKey.getRemainingSeconds());
            }
            
            SshTtyConnector newConnector = new SshTtyConnector(connection, password);
            
            // Set SSHKeyManager if using public key authentication (for non-temporary keys)
            if (connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY && temporarySSHKey == null) {
                de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
                if (app != null && app.getSSHKeyManager() != null) {
                    newConnector.setSSHKeyManager(
                            app.getSSHKeyManager(),
                            app.getMasterPasswordManager().getMasterPassword()
                    );
                }
            }
            
            // Connect the new session
            boolean connected = newConnector.connect();
            if (!connected) {
                logger.error("Failed to connect split SSH session");
                return null;
            }
            
            return newConnector;
        } catch (Exception e) {
            logger.error("Failed to create split SSH connection: {}", e.getMessage(), e);
            Platform.runLater(() -> {
                javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                    javafx.scene.control.Alert.AlertType.ERROR);
                alert.setTitle(I18n.get("error.title"));
                alert.setHeaderText(I18n.get("split.connectionFailed"));
                alert.setContentText(e.getMessage());
                alert.showAndWait();
            });
            return null;
        }
    }
    
    /**
     * Sets the temporary SSH key for this terminal view.
     * Used for split connections that need to reuse the same temporary key.
     */
    public void setTemporarySSHKey(de.kortty.model.TemporarySSHKey temporarySSHKey) {
        this.temporarySSHKey = temporarySSHKey;
    }
    
    /**
     * Gets the temporary SSH key if this terminal was connected with one.
     */
    public de.kortty.model.TemporarySSHKey getTemporarySSHKey() {
        return temporarySSHKey;
    }
    
    /**
     * Creates a new SSH connection to a different server (via QuickConnect dialog).
     */
    private @Nullable TtyConnector createNewConnectionForSplit() {
        if (newConnectionCallback == null) {
            logger.warn("No new connection callback set - cannot create new connection for split");
            return null;
        }
        
        // Call the callback on JavaFX thread and wait for result
        final TtyConnector[] result = new TtyConnector[1];
        if (Platform.isFxApplicationThread()) {
            result[0] = doCreateNewConnectionForSplit();
        } else {
            try {
                java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
                Platform.runLater(() -> {
                    result[0] = doCreateNewConnectionForSplit();
                    latch.countDown();
                });
                latch.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
        }
        return result[0];
    }
    
    private @Nullable TtyConnector doCreateNewConnectionForSplit() {
        ConnectionResult connResult = newConnectionCallback.requestNewConnection();
        if (connResult == null) {
            logger.info("User cancelled new connection for split");
            return null;
        }
        
        try {
            logger.info("Creating new SSH connection for split to {}@{}:{}",
                    connResult.connection.getUsername(), 
                    connResult.connection.getHost(), 
                    connResult.connection.getPort());
            
            SshTtyConnector newConnector = new SshTtyConnector(connResult.connection, connResult.password);
            
            // Set SSHKeyManager if using public key authentication
            if (connResult.connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
                de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
                if (app != null && app.getSSHKeyManager() != null) {
                    newConnector.setSSHKeyManager(
                            app.getSSHKeyManager(),
                            app.getMasterPasswordManager().getMasterPassword()
                    );
                }
            }
            
            // Connect the new session
            newConnector.connect();
            return newConnector;
        } catch (Exception e) {
            logger.error("Failed to create new connection for split: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * Sets up event handlers for a terminal widget (used for each widget in split).
     */
    private void setupWidgetEventHandlers(JediTermFxWidget widget) {
        // Handle ESCAPE key via KEY_PRESSED to send ESC character
        widget.getPane().addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == javafx.scene.input.KeyCode.ESCAPE) {
                TtyConnector connector = widget.getTtyConnector();
                if (connector != null && connector.isConnected()) {
                    try {
                        connector.write("\u001B");
                    } catch (java.io.IOException e) {
                        logger.error("Failed to send ESCAPE character", e);
                    }
                } else if (widget.getTerminal() != null) {
                    widget.getTerminal().writeCharacters("\u001B");
                }
                event.consume();
            }
        });
    }
    
    /**
     * Gets the current font size from the settings provider (includes zoom level).
     */
    public int getCurrentFontSize() {
        return (int) settingsProvider.getFontSize();
    }
    
    /**
     * Focuses the terminal input, making it ready for keyboard input.
     * Should be called when switching to this terminal tab.
     * 
     * Note: Currently not fully implemented due to Swing/JavaFX focus complexity.
     * User needs to click into terminal after tab switch.
     */
    public void focusTerminal() {
        // Empty implementation - focus handling is complex with JediTermFX
    }
    
    /**
     * Sets a listener to be notified when the SSH connection is disconnected.
     */
    public void setDisconnectListener(DisconnectListener listener) {
        this.externalDisconnectListener = listener;
    }
    
    /**
     * Sets a callback to be notified when the SSH connection is successfully established.
     */
    public void setOnConnectedCallback(Runnable callback) {
        this.onConnectedCallback = callback;
    }
    
    /**
     * Connects to the SSH server and starts the terminal session.
     * Implements retry logic with configurable timeout and retry count.
     * Runs asynchronously to prevent UI blocking.
     */
    public void connect() {
        // Run connection in background thread to prevent UI blocking
        Thread connectThread = new Thread(() -> {
            // Check if retries are enabled globally
            boolean retriesEnabled = true;
            try {
                de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
                if (app != null && app.getGlobalSettingsManager() != null) {
                    de.kortty.model.GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
                    if (globalSettings != null) {
                        retriesEnabled = globalSettings.isConnectionRetriesEnabled();
                    }
                }
            } catch (Exception e) {
                logger.warn("Could not check global retry setting: {}", e.getMessage());
            }
            
            int retryCount = retriesEnabled ? connection.getRetryCount() : 1;
            if (retryCount <= 0) {
                retryCount = retriesEnabled ? 4 : 1; // Default fallback
            }
            
            int attempt = 0;
            boolean connected = false;
            String lastError = null;
            boolean authenticationFailed = false;
            
            // Clear terminal before first attempt
            clearTerminal();
            if (retryCount > 1) {
                showMessage("Verbindungsversuch " + 1 + " von " + retryCount + "...");
            } else {
                showMessage("Verbinde...");
            }
            
            while (attempt < retryCount && !connected && !authenticationFailed) {
                attempt++;
                
                try {
                    // Clean up previous attempt if any
                    if (ttyConnector != null) {
                        try {
                            ttyConnector.close();
                        } catch (Exception e) {
                            // Ignore cleanup errors
                        }
                    }
                    
                    // Clear terminal before each retry attempt
                    if (attempt > 1) {
                        clearTerminal();
                        showMessage("Verbindungsversuch " + attempt + " von " + retryCount + "...");
                    }
                    
                    // Create TtyConnector
                    ttyConnector = new SshTtyConnector(connection, password);
                    
                    // Set SSHKeyManager if available
                    if (connection.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
                        de.kortty.KorTTYApplication app = de.kortty.KorTTYApplication.getInstance();
                        if (app != null && app.getSSHKeyManager() != null) {
                            ttyConnector.setSSHKeyManager(
                                app.getSSHKeyManager(),
                                app.getMasterPasswordManager().getMasterPassword()
                            );
                        }
                    }
                    
                    // Register disconnect listener
                    ttyConnector.setDisconnectListener((reason, wasError) -> {
                        logger.info("Disconnect event: {} (wasError={})", reason, wasError);
                        
                        // Stop logger if running
                        stopLogger();
                        
                        if (externalDisconnectListener != null) {
                            externalDisconnectListener.onDisconnect(reason, wasError);
                        }
                    });
                    
                    // Connect SSH
                    connected = ttyConnector.connect();
                    
                    if (connected) {
                        // Start terminal logger if enabled
                        startLogger();
                        
                        // Set the connector and start the terminal on JavaFX thread
                        Platform.runLater(() -> {
                            terminalWidget.setTtyConnector(ttyConnector);
                            terminalWidget.start();
                            
                            // Notify success callback
                            if (onConnectedCallback != null) {
                                onConnectedCallback.run();
                            }
                        });
                        
                        logger.info("Terminal session started for {} (attempt {}/{})", 
                                   connection.getDisplayName(), attempt, retryCount);
                        return; // Success!
                    } else {
                        lastError = "SSH-Verbindung fehlgeschlagen";
                        logger.warn("Connection attempt {}/{} failed for {}", 
                                   attempt, retryCount, connection.getDisplayName());
                        
                        // Show failure message
                        showMessage("Verbindungsversuch " + attempt + " fehlgeschlagen.");
                        
                        // Wait a bit before retry (except on last attempt)
                        if (attempt < retryCount) {
                            try {
                                Thread.sleep(1000); // 1 second delay between retries
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                break;
                            }
                        }
                    }
                    
                } catch (SshTtyConnector.AuthenticationException e) {
                    // Authentication failed - do NOT retry
                    authenticationFailed = true;
                    lastError = e.getMessage();
                    logger.error("Authentication failed for {} - NOT retrying: {}", 
                                connection.getDisplayName(), e.getMessage());
                    
                    // Show clear error message
                    clearTerminal();
                    showMessage("Authentifizierung fehlgeschlagen!");
                    showMessage("");
                    showMessage("Mögliche Ursachen:");
                    showMessage("  - Kein SSH-Key ausgewählt");
                    showMessage("  - SSH-Key nicht auf dem Server autorisiert");
                    showMessage("  - Falscher Benutzername");
                    showMessage("");
                    showMessage("Fehler: " + e.getMessage());
                    
                } catch (Exception e) {
                    lastError = "Verbindung fehlgeschlagen: " + e.getMessage();
                    logger.error("Failed to start terminal session (attempt {}/{}): {}", 
                                attempt, retryCount, e.getMessage(), e);
                    
                    // Show failure message
                    showMessage("Verbindungsversuch " + attempt + " fehlgeschlagen: " + e.getMessage());
                    
                    // Wait before retry (except on last attempt)
                    if (attempt < retryCount && !authenticationFailed) {
                        try {
                            Thread.sleep(1000);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                }
            }
            
            // All retries failed (or auth failed)
            if (!authenticationFailed) {
                clearTerminal();
                if (retryCount > 1) {
                    showMessage("Verbindung nach " + retryCount + " Versuchen fehlgeschlagen.");
                } else {
                    showMessage("Verbindung fehlgeschlagen.");
                }
                showMessage("Timeout: " + connection.getConnectionTimeoutSeconds() + " Sekunden.");
                String finalError = lastError != null && !lastError.isEmpty() ? lastError : "Unbekannter Fehler";
                showMessage(finalError);
            }
            logger.error("All connection attempts failed for {}", connection.getDisplayName());
            
            // Notify disconnect listener about failure
            String errorMessage = authenticationFailed 
                ? "Authentifizierung fehlgeschlagen" 
                : (retryCount > 1 
                    ? "Verbindung nach " + retryCount + " Versuchen fehlgeschlagen" 
                    : "Verbindung fehlgeschlagen");
            if (externalDisconnectListener != null) {
                Platform.runLater(() -> {
                    externalDisconnectListener.onDisconnect(errorMessage, true);
                });
            }
        }, "SSH-Connect-" + connection.getDisplayName());
        connectThread.setDaemon(true);
        connectThread.start();
    }
    
    /**
     * Starts the terminal logger if logging is enabled for this connection.
     */
    private void startLogger() {
        de.kortty.model.TerminalLogConfig logConfig = connection.getLogConfig();
        if (logConfig == null || !logConfig.isEnabled()) {
            return;
        }
        
        try {
            terminalLogger = new de.kortty.core.TerminalLogger(logConfig, connection.getDisplayName());
            terminalLogger.start();
            
            // Register data listener to capture terminal output
            if (ttyConnector != null) {
                ttyConnector.setDataListener(data -> {
                    if (terminalLogger != null) {
                        terminalLogger.log(data);
                    }
                });
            }
            
            logger.info("Terminal logging started for {}", connection.getDisplayName());
        } catch (Exception e) {
            logger.error("Failed to start terminal logger: {}", e.getMessage(), e);
            showError("Logging konnte nicht gestartet werden: " + e.getMessage());
        }
    }
    
    /**
     * Stops the terminal logger.
     */
    private void stopLogger() {
        if (terminalLogger != null) {
            terminalLogger.stop();
            terminalLogger = null;
            logger.info("Terminal logging stopped for {}", connection.getDisplayName());
        }
    }
    
    /**
     * Clears the terminal screen.
     */
    public void clearTerminal() {
        Platform.runLater(() -> {
            if (terminalWidget != null && terminalWidget.getTerminal() != null) {
                // Clear screen using ANSI escape sequence
                terminalWidget.getTerminal().writeCharacters("\033[2J\033[H");
            }
        });
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
     * Shows a message in the terminal (on new line).
     */
    public void showMessage(String message) {
        Platform.runLater(() -> {
            if (terminalWidget != null && terminalWidget.getTerminal() != null) {
                terminalWidget.getTerminal().writeCharacters("\r\n" + message + "\r\n");
            }
        });
    }
    
    /**
     * Cleans up resources.
     */
    public void cleanup() {
        // Stop logger first
        stopLogger();
        
        // Close primary connection
        if (ttyConnector != null) {
            try {
                ttyConnector.close();
            } catch (Exception e) {
                logger.warn("Error closing TtyConnector: {}", e.getMessage());
            }
            ttyConnector = null;
        }
        
        // Close all splits (this closes all SSH connections in the split pane)
        if (splitPane != null) {
            try {
                splitPane.closeAll();
            } catch (Exception e) {
                logger.debug("Ignoring split pane cleanup exception: {}", e.getMessage());
            }
            splitPane = null;
        }
        
        terminalWidget = null;
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
        JediTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
        if (focused != null && focused.getTerminalPanel() != null) {
            // handleCopy(withCustomSelectors, byKeyStroke)
            focused.getTerminalPanel().handleCopy(false, false);
        }
    }
    
    /**
     * Pastes from clipboard.
     */
    public void pasteFromClipboard() {
        JediTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
        if (focused != null && focused.getTerminalPanel() != null) {
            focused.getTerminalPanel().handlePaste();
        }
    }
    
    /**
     * Zooms the terminal font.
     * Uses JediTermFX 1.2.0's native dynamic font size support.
     */
    public void zoom(int delta) {
        if (delta > 0) {
            settingsProvider.increaseFontSize(delta);
        } else if (delta < 0) {
            settingsProvider.decreaseFontSize(-delta);
        }
        logger.debug("Zoom changed to font size: {}", settingsProvider.getFontSize());
    }
    
    /**
     * Resets the terminal font size to default.
     * Uses JediTermFX 1.2.0's native dynamic font size support.
     */
    public void resetZoom() {
        settingsProvider.setFontSize(defaultFontSize);
        logger.debug("Zoom reset to default font size: {}", defaultFontSize);
    }
    
    /**
     * Sets the terminal font size (e.g. when restoring project zoom level).
     */
    public void setFontSize(int fontSize) {
        settingsProvider.setFontSize(fontSize);
        logger.debug("Font size set to: {}", fontSize);
    }
    
    /**
     * Gets the split pane structure for saving to project.
     * Returns null if there are no splits (single terminal).
     */
    public de.kortty.model.SplitPaneState getSplitState() {
        if (splitPane == null) {
            return null;
        }
        
        int widgetCount = splitPane.getWidgetCount();
        if (widgetCount <= 1) {
            return null; // No splits
        }
        
        // Build state from rootCell
        return buildSplitState(getRootCell(), splitPane.getAllWidgets());
    }
    
    /**
     * Recursively builds SplitPaneState from the cell tree structure.
     */
    private de.kortty.model.SplitPaneState buildSplitState(Object cell, List<JediTermFxWidget> allWidgets) {
        // Use reflection to access private SplitCell fields
        try {
            Class<?> cellClass = cell.getClass();
            
            // Check if it's a leaf (has widget)
            var widgetField = cellClass.getDeclaredField("widget");
            widgetField.setAccessible(true);
            JediTermFxWidget widget = (JediTermFxWidget) widgetField.get(cell);
            
            if (widget != null) {
                // Leaf node - find widget index
                int index = allWidgets.indexOf(widget);
                return de.kortty.model.SplitPaneState.createLeaf(index);
            }
            
            // Split node - get orientation, divider, and children
            var splitPaneField = cellClass.getDeclaredField("splitPane");
            splitPaneField.setAccessible(true);
            javafx.scene.control.SplitPane splitPaneObj = (javafx.scene.control.SplitPane) splitPaneField.get(cell);
            
            var leftCellField = cellClass.getDeclaredField("leftCell");
            leftCellField.setAccessible(true);
            Object leftCell = leftCellField.get(cell);
            
            var rightCellField = cellClass.getDeclaredField("rightCell");
            rightCellField.setAccessible(true);
            Object rightCell = rightCellField.get(cell);
            
            if (splitPaneObj != null && leftCell != null && rightCell != null) {
                Orientation ori = splitPaneObj.getOrientation();
                double[] positions = splitPaneObj.getDividerPositions();
                double dividerPos = positions.length > 0 ? positions[0] : 0.5;
                
                de.kortty.model.SplitPaneState leftState = buildSplitState(leftCell, allWidgets);
                de.kortty.model.SplitPaneState rightState = buildSplitState(rightCell, allWidgets);
                
                return de.kortty.model.SplitPaneState.createSplit(ori, dividerPos, leftState, rightState);
            }
        } catch (Exception e) {
            logger.error("Failed to build split state: {}", e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Gets the rootCell from TerminalSplitPane using reflection.
     */
    private Object getRootCell() {
        try {
            var rootCellField = splitPane.getClass().getDeclaredField("rootCell");
            rootCellField.setAccessible(true);
            return rootCellField.get(splitPane);
        } catch (Exception e) {
            logger.error("Failed to get rootCell: {}", e.getMessage(), e);
            return null;
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
     * Restores split pane structure from saved state.
     * Recreates the split tree with new SSH connections for each widget.
     */
    public void restoreSplitState(de.kortty.model.SplitPaneState splitState) {
        if (splitState == null || !splitState.isSplit()) {
            logger.debug("No split structure to restore");
            return;
        }
        
        logger.info("Restoring split structure: {}", splitState);
        
        // We need to restore splits after the initial connection is established
        // Schedule split restoration for after the current terminal is connected
        Platform.runLater(() -> {
            try {
                // Start with the currently focused widget (the initial one)
                JediTermFxWidget initialWidget = splitPane.getFocusedWidget();
                if (initialWidget == null) {
                    logger.warn("No initial widget to start split restoration");
                    return;
                }
                
                // Rebuild the split structure directly from the saved state
                restoreSplitRecursive(splitState, initialWidget);
                
            } catch (Exception e) {
                logger.error("Failed to restore split structure: {}", e.getMessage(), e);
            }
        });
    }
    
    /**
     * Sets the focusedWidget field in TerminalSplitPane using reflection.
     * This is necessary because split() always uses getFocusedWidget().
     */
    private void setFocusedWidget(JediTermFxWidget widget) {
        try {
            var focusedWidgetField = splitPane.getClass().getDeclaredField("focusedWidget");
            focusedWidgetField.setAccessible(true);
            focusedWidgetField.set(splitPane, widget);
            logger.debug("Set focusedWidget to: {}", widget.hashCode());
        } catch (Exception e) {
            logger.error("Failed to set focusedWidget: {}", e.getMessage());
        }
    }
    
    /**
     * Recursively restores the split structure by creating splits as needed.
     * This method traverses the split tree and creates splits in the correct order and orientation.
     * 
     * @param state The split state to restore
     * @param widgetToSplit The specific widget that should be split
     */
    private void restoreSplitRecursive(de.kortty.model.SplitPaneState state, JediTermFxWidget widgetToSplit) {
        if (state == null || state.isLeaf()) {
            return; // Leaf node - nothing to do
        }
        
        if (widgetToSplit == null) {
            logger.warn("Widget to split is null");
            return;
        }
        
        // This is a split node - we need to create the split
        Orientation orientation = state.getOrientationEnum();
        if (orientation == null) {
            logger.warn("Invalid orientation in split state: {}", state.getOrientation());
            return;
        }
        
        logger.info("Creating split: orientation={}, dividerPos={} on widget {}", 
                     orientation, state.getDividerPosition(), widgetToSplit.hashCode());
        
        // CRITICAL: Set the focusedWidget directly using reflection
        // because split() uses getFocusedWidget() internally
        setFocusedWidget(widgetToSplit);
        
        // Remember the number of widgets before split
        int widgetCountBefore = splitPane.getAllWidgets().size();
        
        // Perform the split with the CORRECT orientation from saved state
        splitPane.split(SplitRequest.SplitMode.SAME_SERVER_NEW_SHELL, orientation);
        
        // Wait for split to complete and connection to establish
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Get all widgets after the split
        List<JediTermFxWidget> allWidgets = splitPane.getAllWidgets();
        int widgetCountAfter = allWidgets.size();
        
        if (widgetCountAfter <= widgetCountBefore) {
            logger.warn("Split did not create new widget (before: {}, after: {})", widgetCountBefore, widgetCountAfter);
            return;
        }
        
        // The new widget is the last one in the list
        JediTermFxWidget newWidget = allWidgets.get(allWidgets.size() - 1);
        
        logger.info("Split created: leftWidget={}, rightWidget={}, totalWidgets={}", 
                     widgetToSplit.hashCode(), newWidget.hashCode(), widgetCountAfter);
        
        // Set divider position for this split
        setDividerPositionForLastSplit(state.getDividerPosition());
        
        // Process left child (the original widget that was split)
        if (state.getLeftChild() != null && state.getLeftChild().isSplit()) {
            logger.debug("Processing left child");
            restoreSplitRecursive(state.getLeftChild(), widgetToSplit);
        }
        
        // Process right child (the newly created widget)
        if (state.getRightChild() != null && state.getRightChild().isSplit()) {
            logger.debug("Processing right child");
            restoreSplitRecursive(state.getRightChild(), newWidget);
        }
    }
    
    
    /**
     * Sets the divider position for the most recently created split.
     */
    private void setDividerPositionForLastSplit(double position) {
        Platform.runLater(() -> {
            try {
                // Get rootCell from TerminalSplitPane
                var rootCellField = splitPane.getClass().getDeclaredField("rootCell");
                rootCellField.setAccessible(true);
                Object rootCell = rootCellField.get(splitPane);
                
                if (rootCell != null) {
                    // Find all SplitPanes in the tree and set the last one
                    List<javafx.scene.control.SplitPane> splitPanes = new java.util.ArrayList<>();
                    collectSplitPanes(rootCell, splitPanes);
                    
                    if (!splitPanes.isEmpty()) {
                        javafx.scene.control.SplitPane lastSplit = splitPanes.get(splitPanes.size() - 1);
                        lastSplit.setDividerPositions(position);
                        logger.debug("Set divider position to: {}", position);
                    }
                }
            } catch (Exception e) {
                logger.debug("Could not set divider position: {}", e.getMessage());
            }
        });
    }
    
    /**
     * Collects all SplitPane instances from the cell tree.
     */
    private void collectSplitPanes(Object cell, List<javafx.scene.control.SplitPane> splitPanes) {
        try {
            Class<?> cellClass = cell.getClass();
            
            // Check if this cell has a splitPane
            var splitPaneField = cellClass.getDeclaredField("splitPane");
            splitPaneField.setAccessible(true);
            javafx.scene.control.SplitPane sp = (javafx.scene.control.SplitPane) splitPaneField.get(cell);
            
            if (sp != null) {
                splitPanes.add(sp);
                
                // Recurse into children
                var leftCellField = cellClass.getDeclaredField("leftCell");
                leftCellField.setAccessible(true);
                Object leftCell = leftCellField.get(cell);
                
                var rightCellField = cellClass.getDeclaredField("rightCell");
                rightCellField.setAccessible(true);
                Object rightCell = rightCellField.get(cell);
                
                if (leftCell != null) {
                    collectSplitPanes(leftCell, splitPanes);
                }
                if (rightCell != null) {
                    collectSplitPanes(rightCell, splitPanes);
                }
            }
        } catch (Exception e) {
            // Ignore - might be a leaf cell
        }
    }
    
    /**
     * Custom settings provider for KorTTY with dynamic font size support.
     * Extends DynamicFontSizeSettingsProvider to enable:
     * - Font zoom via Cmd+Plus/Minus (or Ctrl+Plus/Minus)
     * - Font zoom via right-click context menu
     */
    private static class KorTTYSettingsProvider extends DynamicFontSizeSettingsProvider {
        
        private final ConnectionSettings settings;
        
        public KorTTYSettingsProvider(ConnectionSettings settings, int initialFontSize) {
            super(initialFontSize);
            this.settings = settings;
        }
        
        @Override
        public @NotNull Font getTerminalFont() {
            return Font.font(settings.getFontFamily(), getFontSize());
        }
        
        @Override
        public @NotNull TerminalColor getDefaultForeground() {
            Color fgColor = Color.web(settings.getForegroundColor());
            return TerminalColor.rgb(
                    (int) (fgColor.getRed() * 255),
                    (int) (fgColor.getGreen() * 255),
                    (int) (fgColor.getBlue() * 255)
            );
        }
        
        @Override
        public @NotNull TerminalColor getDefaultBackground() {
            Color bgColor = Color.web(settings.getBackgroundColor());
            return TerminalColor.rgb(
                    (int) (bgColor.getRed() * 255),
                    (int) (bgColor.getGreen() * 255),
                    (int) (bgColor.getBlue() * 255)
            );
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
