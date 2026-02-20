package de.kortty.ui;

import com.techsenger.jeditermfx.core.CursorShape;
import com.techsenger.jeditermfx.core.TerminalColor;
import com.techsenger.jeditermfx.core.TextStyle;
import com.techsenger.jeditermfx.core.model.JediTerminal;
import com.techsenger.jeditermfx.core.TtyConnector;
import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import com.techsenger.jeditermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.techsenger.jeditermfx.ui.split.SplitConnectorFactory;
import com.techsenger.jeditermfx.ui.split.SplitRequest;
import com.techsenger.jeditermfx.ui.split.TerminalSplitPane;
import de.kortty.KorTTYApplication;
import de.kortty.core.SshTtyConnector;
import de.kortty.core.DisconnectListener;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.Theme;
import javafx.application.Platform;
import javafx.animation.PauseTransition;
import javafx.util.Duration;
import javafx.scene.control.ScrollBar;
import javafx.scene.layout.BorderPane;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.geometry.Orientation;
import javafx.scene.input.Dragboard;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.input.TransferMode;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javafx.scene.control.ProgressIndicator;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.stage.Window;
import javafx.scene.layout.VBox;
import javafx.scene.Scene;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

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
    
    // Timestamp gutter support: maps each widget to its gutter
    private final Map<JediTermFxWidget, TimestampGutter> gutterMap = new ConcurrentHashMap<>();
    // Last absolute line we added a timestamp for (per widget), to detect new prompt lines from server
    private final Map<JediTermFxWidget, Integer> lastTimestampLineByWidget = new ConcurrentHashMap<>();
    // Timestamp history per widget, independent from gutter visibility/UI state
    private final Map<JediTermFxWidget, TreeMap<Integer, LocalDateTime>> timestampHistoryByWidget = new ConcurrentHashMap<>();
    // Tracks whether we are waiting for "command finished" timestamp after user pressed Enter.
    private final Map<JediTermFxWidget, Boolean> awaitingCommandCompletionByWidget = new ConcurrentHashMap<>();
    // Debounce timer per widget: a short quiet period marks command completion.
    private final Map<JediTermFxWidget, PauseTransition> commandCompletionTimerByWidget = new ConcurrentHashMap<>();
    // Absolute line where the current command started (Enter pressed).
    private final Map<JediTermFxWidget, Integer> commandStartLineByWidget = new ConcurrentHashMap<>();

    // Optional listener called when timestamp gutter visibility is toggled (e.g. from context menu)
    private Runnable timestampToggleListener;
    private Runnable onReconnectRequested;
    private volatile boolean timestampGuttersVisibleState;
    
    public TerminalView(ServerConnection connection, String password) {
        this(connection, password, null);
    }
    
    public TerminalView(ServerConnection connection, String password, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        this.connection = connection;
        this.password = password;
        this.temporarySSHKey = temporarySSHKey;
        
        // Ensure settings is never null - use connection settings or create defaults
        ConnectionSettings connSettings = connection.getSettings();
        if (connSettings == null) {
            connSettings = new ConnectionSettings();
            try {
                var gs = KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
                if (gs != null && gs.getDefaultTerminalSettings() != null) {
                    connSettings = new ConnectionSettings(gs.getDefaultTerminalSettings());
                }
            } catch (Exception e) {
                // Use defaults
            }
            connection.setSettings(connSettings);
            logger.warn("Connection '{}' had no settings, using defaults", connection.getName());
        }
        // Resolve theme if themeId is set
        ConnectionSettings effective = connSettings;
        String themeId = connSettings.getThemeId();
        if (themeId != null && !themeId.isEmpty()) {
            try {
                var tm = KorTTYApplication.getInstance().getThemeManager();
                if (tm != null) {
                    effective = tm.resolveSettings(connSettings, themeId);
                }
            } catch (Exception e) {
                // Use connection settings
            }
        }
        this.settings = effective;
        this.defaultFontSize = settings.getFontSize();
        this.timestampGuttersVisibleState = isCommandTimestampsEnabled();
        
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
            setupWidgetEventHandlers(widget);
            applyCursorShape(widget);
            setupTimestampGutter(widget);
        }, widget -> gutterMap.get(widget)); // Left panel factory: returns the gutter created in setupTimestampGutter
        
        // Register extra context menu items: Theme, Reconnect, Timestamp toggle
        splitPane.setExtraMenuItemsFactory(widget -> {
            java.util.List<javafx.scene.control.MenuItem> items = new java.util.ArrayList<>();
            javafx.scene.control.Menu themeMenu = new javafx.scene.control.Menu(I18n.get("theme.menu"));
            try {
                var tm = KorTTYApplication.getInstance().getThemeManager();
                if (tm != null) {
                    for (Theme t : tm.getThemes()) {
                        javafx.scene.control.MenuItem mi = new javafx.scene.control.MenuItem(t.getName());
                        Theme theme = t;
                        mi.setOnAction(e -> applyThemeAtRuntime(theme));
                        themeMenu.getItems().add(mi);
                    }
                }
            } catch (Exception e) {
                // Theme manager not available
            }
            if (!themeMenu.getItems().isEmpty()) {
                items.add(themeMenu);
                items.add(new javafx.scene.control.SeparatorMenuItem());
            }
            javafx.scene.control.MenuItem reconnectItem = new javafx.scene.control.MenuItem(I18n.get("dashboard.reconnect"));
            reconnectItem.setOnAction(e -> {
                if (onReconnectRequested != null) Platform.runLater(onReconnectRequested);
            });
            items.add(reconnectItem);
            items.add(new javafx.scene.control.SeparatorMenuItem());
            javafx.scene.control.CheckMenuItem timestampToggle =
                new javafx.scene.control.CheckMenuItem(I18n.get("menu.view.timestamps"));
            timestampToggle.setSelected(isTimestampGuttersVisible());
            timestampToggle.setOnAction(e -> {
                toggleTimestampGutters();
                if (timestampToggleListener != null) timestampToggleListener.run();
            });
            items.add(timestampToggle);
            return items;
        });
        
        terminalWidget = splitPane.getFocusedWidget();
        if (terminalWidget != null) applyCursorShape(terminalWidget);
        
        // Require Shift+Alt/Option for pane-move drag; otherwise consume so terminal gets text selection
        splitPane.addEventFilter(MouseEvent.DRAG_DETECTED, e -> {
            if (splitPane.getWidgetCount() > 1 && !(e.isShiftDown() && e.isAltDown())) {
                logger.debug("TerminalView DnD gate: consume DRAG_DETECTED (shift={}, alt={}, ctrl={}, meta={})",
                        e.isShiftDown(), e.isAltDown(), e.isControlDown(), e.isMetaDown());
                e.consume();
            }
        });
        
        setCenter(splitPane);

        setupDragDrop();

        // Request focus on the terminal
        Platform.runLater(() -> {
            JediTermFxWidget focused = splitPane.getFocusedWidget();
            if (focused != null && focused.getPreferredFocusableNode() != null) {
                focused.getPreferredFocusableNode().requestFocus();
            }
        });
    }

    private boolean isTerminalDragDropEnabled() {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            return gs != null && gs.isTerminalDragDropEnabled();
        } catch (Exception e) {
            return true;
        }
    }

    /** MIME type used by TerminalSplitPane for internal pane-move DnD. */
    private static final String SPLIT_DRAG_MIME = "application/x-jeditermfx-terminal-widget";

    /** Check whether a dragboard carries an internal split-pane move payload. */
    private static boolean isSplitPaneDrag(javafx.scene.input.Dragboard db) {
        javafx.scene.input.DataFormat fmt = javafx.scene.input.DataFormat.lookupMimeType(SPLIT_DRAG_MIME);
        return fmt != null && db.hasContent(fmt);
    }

    private void setupDragDrop() {
        setOnDragOver(event -> {
            // Don't intercept internal split-pane move drags — let them pass through
            if (isSplitPaneDrag(event.getDragboard())) {
                logger.debug("TerminalView DragOver: pass split-pane drag through");
                return;
            }
            if (!isTerminalDragDropEnabled()) return;
            Dragboard db = event.getDragboard();
            if (db.hasFiles()) {
                TtyConnector conn = getFocusedConnector();
                if (conn instanceof SshTtyConnector ssh && ssh.isConnected() && ssh.getSession() != null) {
                    event.acceptTransferModes(TransferMode.COPY);
                }
            }
            event.consume();
        });
        setOnDragDropped(event -> {
            // Don't intercept internal split-pane move drags — let them pass through
            if (isSplitPaneDrag(event.getDragboard())) {
                logger.debug("TerminalView DragDropped: pass split-pane drag through");
                return;
            }
            if (!isTerminalDragDropEnabled()) return;
            Dragboard db = event.getDragboard();
            if (!db.hasFiles()) {
                event.setDropCompleted(false);
                return;
            }
            TtyConnector conn = getFocusedConnector();
            if (!(conn instanceof SshTtyConnector ssh) || !ssh.isConnected() || ssh.getSession() == null) {
                event.setDropCompleted(false);
                return;
            }
            List<java.io.File> dropped = db.getFiles();
            if (dropped.isEmpty()) {
                event.setDropCompleted(false);
                return;
            }
            event.setDropCompleted(true);
            copyDroppedFilesToServer(ssh, dropped);
        });
    }

    private TtyConnector getFocusedConnector() {
        JediTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : null;
        return focused != null ? focused.getTtyConnector() : null;
    }

    private void copyDroppedFilesToServer(SshTtyConnector sshConnector, List<java.io.File> dropped) {
        List<PathPair> toUpload = new ArrayList<>();
        for (java.io.File f : dropped) {
            collectFiles(f.toPath(), "", toUpload);
        }
        if (toUpload.isEmpty()) return;
        int total = toUpload.size();
        AtomicBoolean aborted = new AtomicBoolean(false);
        javafx.scene.control.ProgressBar progressBar = new javafx.scene.control.ProgressBar(0);
        progressBar.setPrefWidth(300);
        progressBar.setProgress(0);
        javafx.scene.control.Label statusLabel = new javafx.scene.control.Label(
            I18n.get("terminal.dragDrop.count", 0, total));
        javafx.scene.control.Button abortButton = new javafx.scene.control.Button(I18n.get("terminal.dragDrop.abort"));
        javafx.scene.layout.VBox vbox = new javafx.scene.layout.VBox(10,
            statusLabel, progressBar, abortButton);
        vbox.setPadding(new javafx.geometry.Insets(15));
        javafx.scene.control.Dialog<Void> dialog = new javafx.scene.control.Dialog<>();
        dialog.setTitle(I18n.get("terminal.dragDrop.title"));
        dialog.getDialogPane().setContent(vbox);
        dialog.getDialogPane().getButtonTypes().add(javafx.scene.control.ButtonType.CLOSE);
        dialog.setOnShown(e -> abortButton.requestFocus());
        abortButton.setOnAction(e -> {
            aborted.set(true);
            dialog.close();
        });
        Thread worker = new Thread(() -> {
            try {
                SftpClient sftp = SftpClientFactory.instance().createSftpClient(sshConnector.getSession());
                // Resolve remote home (SFTP server does not expand "~")
                String remoteHome;
                try {
                    remoteHome = sftp.canonicalPath(".");
                } catch (Exception e) {
                    logger.debug("Could not resolve remote home, using '.'");
                    remoteHome = ".";
                }
                if (remoteHome == null || remoteHome.isEmpty()) remoteHome = ".";
                int copied = 0;
                for (int i = 0; i < toUpload.size() && !aborted.get(); i++) {
                    PathPair p = toUpload.get(i);
                    String fullRemote = (remoteHome.endsWith("/") ? remoteHome : remoteHome + "/") + p.remote;
                    uploadOne(sftp, p, fullRemote);
                    if (aborted.get()) break;
                    copied++;
                    final int done = copied;
                    Platform.runLater(() -> {
                        statusLabel.setText(I18n.get("terminal.dragDrop.count", done, total));
                        progressBar.setProgress((double) done / total);
                    });
                }
                if (!aborted.get()) {
                    Platform.runLater(() -> {
                        statusLabel.setText(I18n.get("terminal.dragDrop.done"));
                        progressBar.setProgress(1.0);
                        javafx.animation.PauseTransition pause = new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.2));
                        pause.setOnFinished(e -> dialog.close());
                        pause.play();
                    });
                }
            } catch (Exception ex) {
                logger.warn("Drag-drop copy failed", ex);
                String msg = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName();
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("terminal.dragDrop.error", msg));
                });
            }
        }, "TerminalDragDrop");
        worker.setDaemon(true);
        worker.start();
        dialog.show();
    }

    private static class PathPair {
        final Path local;
        final String remote;
        final boolean isDir;

        PathPair(Path local, String remote, boolean isDir) {
            this.local = local;
            this.remote = remote;
            this.isDir = isDir;
        }
    }

    private void collectFiles(Path local, String remoteDir, List<PathPair> out) {
        if (Files.isRegularFile(local)) {
            String name = local.getFileName().toString();
            String remote = remoteDir.isEmpty() ? name : remoteDir + "/" + name;
            out.add(new PathPair(local, remote, false));
        } else if (Files.isDirectory(local)) {
            String dirName = local.getFileName().toString();
            String subRemote = remoteDir.isEmpty() ? dirName : remoteDir + "/" + dirName;
            out.add(new PathPair(local, subRemote, true));
            try {
                try (var stream = Files.list(local)) {
                    for (Path child : stream.toList()) {
                        collectFiles(child, subRemote, out);
                    }
                }
            } catch (Exception e) {
                logger.debug("List dir failed: {}", e.getMessage());
            }
        }
    }

    private void uploadOne(SftpClient sftp, PathPair p, String fullRemotePath) throws java.io.IOException {
        if (p.isDir) {
            try {
                sftp.mkdir(fullRemotePath);
            } catch (Exception e) {
                // may already exist
            }
            return;
        }
        try (InputStream in = Files.newInputStream(p.local);
             OutputStream out = sftp.write(fullRemotePath, java.util.EnumSet.of(
                 SftpClient.OpenMode.Write, SftpClient.OpenMode.Create, SftpClient.OpenMode.Truncate))) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
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
     * Runs connect() in a background thread so the JavaFX thread stays responsive for
     * keyboard-interactive auth dialogs (e.g. CyberArk "reason for operation").
     * Ensures Stage/Label/ProgressIndicator and showAndWait() run on the FX Application Thread.
     */
    private @Nullable TtyConnector createSameServerConnection() {
        if (Platform.isFxApplicationThread()) {
            return doCreateSameServerConnection();
        }
        final TtyConnector[] result = new TtyConnector[1];
        try {
            java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
            Platform.runLater(() -> {
                result[0] = doCreateSameServerConnection();
                latch.countDown();
            });
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
        return result[0];
    }
    
    /**
     * Must be called on the JavaFX Application Thread. Creates UI (Stage, progress dialog),
     * starts connect() in a background thread, shows the dialog and waits for completion.
     */
    private @Nullable TtyConnector doCreateSameServerConnection() {
        try {
            logger.info("Creating new SSH connection for split to {}@{}:{}",
                    connection.getUsername(), connection.getHost(), connection.getPort());
            
            // Check if using temporary SSH key and if it's still valid
            if (temporarySSHKey != null) {
                if (!temporarySSHKey.isValid()) {
                    logger.error("Temporary SSH key has expired - cannot create split connection");
                    javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                        javafx.scene.control.Alert.AlertType.ERROR);
                    alert.setTitle(I18n.get("error.title"));
                    alert.setHeaderText(I18n.get("sftp.tempKeyExpired"));
                    alert.setContentText(I18n.get("split.tempKeyExpiredMessage"));
                    alert.showAndWait();
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
            
            // Run connect() in background thread so JavaFX can show keyboard-interactive dialogs
            AtomicReference<Boolean> connectSuccess = new AtomicReference<>(false);
            java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
            Stage connectingStage = new Stage(StageStyle.UTILITY);
            connectingStage.initModality(Modality.APPLICATION_MODAL);
            connectingStage.setTitle(I18n.get("split.connecting"));
            javafx.scene.control.Label label = new javafx.scene.control.Label(I18n.get("split.connecting"));
            ProgressIndicator progress = new ProgressIndicator(-1);
            VBox root = new VBox(15, progress, label);
            root.setStyle("-fx-padding: 20; -fx-alignment: center;");
            connectingStage.setScene(new Scene(root));
            connectingStage.setResizable(false);
            
            Thread connectThread = new Thread(() -> {
                try {
                    boolean ok = newConnector.connect();
                    connectSuccess.set(ok);
                } catch (Throwable t) {
                    logger.error("Split connect error: {}", t.getMessage(), t);
                    connectSuccess.set(false);
                } finally {
                    done.countDown();
                    Platform.runLater(() -> connectingStage.close());
                }
            }, "SSH-Split-Connect");
            connectThread.setDaemon(true);
            connectThread.start();
            
            connectingStage.showAndWait();
            try {
                done.await(2, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return null;
            }
            
            if (!Boolean.TRUE.equals(connectSuccess.get())) {
                logger.error("Failed to connect split SSH session");
                return null;
            }
            return newConnector;
        } catch (Exception e) {
            logger.error("Failed to create split SSH connection: {}", e.getMessage(), e);
            javafx.scene.control.Alert alert = new javafx.scene.control.Alert(
                javafx.scene.control.Alert.AlertType.ERROR);
            alert.setTitle(I18n.get("error.title"));
            alert.setHeaderText(I18n.get("split.connectionFailed"));
            alert.setContentText(e.getMessage());
            alert.showAndWait();
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
    private void applyThemeAtRuntime(Theme theme) {
        if (theme == null || settings == null) return;
        theme.applyTo(settings);
        ConnectionSettings connSettings = connection.getSettings();
        if (connSettings != null) {
            theme.applyTo(connSettings);
            connSettings.setThemeId(theme.getId());
        }
        if (splitPane != null) {
            for (JediTermFxWidget w : splitPane.getAllWidgets()) {
                applyStyleStateColors(w);
                applyCursorShape(w);
                setCursorVisible(w, true);
            }
        }
        updateAllTerminalFonts();
    }

    private void applyStyleStateColors(JediTermFxWidget widget) {
        if (widget == null || settings == null) return;
        var terminal = widget.getTerminal();
        if (!(terminal instanceof JediTerminal jediTerminal)) return;
        var styleState = jediTerminal.getStyleState();
        Color fg;
        Color bg;
        try {
            fg = Color.web(settings.getForegroundColor());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid foreground color '{}', using white", settings.getForegroundColor(), e);
            fg = Color.WHITE;
        }
        try {
            bg = Color.web(settings.getBackgroundColor());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid background color '{}', using black", settings.getBackgroundColor(), e);
            bg = Color.BLACK;
        }
        TerminalColor fgTc = TerminalColor.rgb(
                (int) (fg.getRed() * 255),
                (int) (fg.getGreen() * 255),
                (int) (fg.getBlue() * 255));
        TerminalColor bgTc = TerminalColor.rgb(
                (int) (bg.getRed() * 255),
                (int) (bg.getGreen() * 255),
                (int) (bg.getBlue() * 255));
        TextStyle newStyle = new TextStyle(fgTc, bgTc);
        styleState.setDefaultStyle(newStyle);
        styleState.reset();
        Platform.runLater(() -> widget.getTerminalPanel().repaint());
    }

    private void applyCursorShape(JediTermFxWidget widget) {
        if (widget == null || settings == null) return;
        String style = settings.getCursorStyle();
        if (style == null || style.isEmpty()) return;
        try {
            CursorShape shape = CursorShape.valueOf(style.toUpperCase());
            widget.getTerminalPanel().setCursorShape(shape);
        } catch (IllegalArgumentException e) {
            // Use default
        }
    }

    private void setCursorVisible(JediTermFxWidget widget, boolean visible) {
        if (widget == null) return;
        var terminal = widget.getTerminal();
        if (terminal != null) {
            terminal.setCursorVisible(visible);
        }
    }

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

        // Copy-on-select: when user finishes selecting text, copy to clipboard if enabled
        var panel = widget.getTerminalPanel();
        if (panel != null) {
            panel.selectedTextProperty().addListener((obs, oldVal, newVal) -> {
                if (isTerminalCopyOnSelectEnabled() && newVal != null && !newVal.isEmpty()) {
                    panel.handleCopy(false, false);
                }
            });
        }
    }

    private boolean isTerminalCopyOnSelectEnabled() {
        try {
            var gsm = KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm != null ? gsm.getSettings() : null;
            return gs != null && gs.isTerminalCopyOnSelectEnabled();
        } catch (Exception e) {
            return true;
        }
    }
    
    /**
     * Sets up a timestamp gutter for the given widget.
     * The gutter is always created and Enter key timestamps are always recorded,
     * but the gutter is only visible if command timestamps are enabled.
     * This allows instant toggling without losing recorded timestamps.
     */
    private void setupTimestampGutter(JediTermFxWidget widget) {
        TimestampGutter gutter = new TimestampGutter();
        gutterMap.put(widget, gutter);
        timestampHistoryByWidget.computeIfAbsent(widget, w -> new TreeMap<>());
        
        // Configure gutter colors and font to match terminal
        gutter.setGutterBackgroundColor(Color.web(settings.getBackgroundColor()));
        gutter.setGutterTextColor(Color.web(settings.getForegroundColor()));
        gutter.setTimestampFont(settings.getFontFamily(), settings.getFontSize());
        
        // Set initial visibility based on current runtime state (important for new split widgets
        // created after user toggled timestamps in the active tab).
        boolean visible = timestampGuttersVisibleState;
        gutter.setVisible(visible);
        gutter.setManaged(visible);
        
        // Note: gutter is registered with the split pane via the leftPanelFactory
        // (passed in the TerminalSplitPane constructor), not here - because this method
        // runs during the TerminalSplitPane constructor when splitPane is not yet assigned.
        syncGutterFromHistory(widget, gutter);
        
        // Always listen for Enter key to record timestamps (even when gutter is hidden).
        // Register on pane + focusable node so split widgets reliably capture Enter.
        javafx.event.EventHandler<KeyEvent> enterHandler = event -> {
            if (event.getCode() == KeyCode.ENTER) {
                int startAbsoluteLine = getCurrentAbsoluteCursorLine(widget);
                if (startAbsoluteLine >= 0) {
                    recordTimestampForLine(widget, startAbsoluteLine, LocalDateTime.now());
                    commandStartLineByWidget.put(widget, startAbsoluteLine);
                }
                awaitingCommandCompletionByWidget.put(widget, true);
            }
        };
        widget.getPane().addEventFilter(KeyEvent.KEY_PRESSED, enterHandler);
        if (widget.getPreferredFocusableNode() != null) {
            widget.getPreferredFocusableNode().addEventFilter(KeyEvent.KEY_PRESSED, enterHandler);
        }
        
        // Synchronize gutter with terminal scrollbar
        var terminalPanel = widget.getTerminalPanel();
        ScrollBar scrollBar = terminalPanel.getScrollBar();
        var textBuffer = terminalPanel.getTerminalTextBuffer();
        
        // Update gutter on scroll changes
        scrollBar.valueProperty().addListener((obs, oldV, newV) -> {
            updateGutterScrollState(gutter, scrollBar, textBuffer, terminalPanel);
        });

        // Keep timestamp line mapping aligned when terminal geometry changes
        // (window resize, split resize, divider moves).
        terminalPanel.getPane().widthProperty().addListener((obs, oldV, newV) -> {
            handleTerminalGeometryChanged(widget, gutter, scrollBar, textBuffer, terminalPanel);
        });
        terminalPanel.getPane().heightProperty().addListener((obs, oldV, newV) -> {
            handleTerminalGeometryChanged(widget, gutter, scrollBar, textBuffer, terminalPanel);
        });
        
        // Update gutter when terminal content changes (new output, resize)
        textBuffer.addModelListener(() -> {
            Platform.runLater(() -> {
                updateGutterScrollState(gutter, scrollBar, textBuffer, terminalPanel);
                // Add timestamp when prompt appears (cursor at start of new line from server output).
                // For command end tracking, use a short quiet-time debounce after Enter:
                // this avoids creating timestamps for every output line.
                try {
                    var terminal = widget.getTerminal();
                    if (terminal == null) return;
                    if (Boolean.TRUE.equals(awaitingCommandCompletionByWidget.get(widget))) {
                        scheduleCommandCompletionDetection(widget);
                    }
                } catch (Exception e) {
                    logger.trace("Timestamp on prompt: {}", e.getMessage());
                }
            });
        });
        
        // Initial update
        Platform.runLater(() -> updateGutterScrollState(gutter, scrollBar, textBuffer, terminalPanel));
    }
    
    /**
     * Records a timestamp in persistent per-widget history and mirrors it to the gutter.
     * This keeps data even when gutters are hidden or recreated later.
     */
    private void recordTimestampForLine(JediTermFxWidget widget, int absoluteLine, LocalDateTime timestamp) {
        if (absoluteLine < 0 || timestamp == null) return;
        TreeMap<Integer, LocalDateTime> history =
                timestampHistoryByWidget.computeIfAbsent(widget, w -> new TreeMap<>());
        if (!history.containsKey(absoluteLine)) {
            history.put(absoluteLine, timestamp);
        }
        TimestampGutter widgetGutter = gutterMap.get(widget);
        if (widgetGutter != null && !widgetGutter.hasTimestampForLine(absoluteLine)) {
            widgetGutter.addTimestamp(absoluteLine, history.get(absoluteLine));
        }
        int last = lastTimestampLineByWidget.getOrDefault(widget, -1);
        if (absoluteLine > last) {
            lastTimestampLineByWidget.put(widget, absoluteLine);
        }
    }

    private void syncGutterFromHistory(JediTermFxWidget widget, TimestampGutter gutter) {
        TreeMap<Integer, LocalDateTime> history = timestampHistoryByWidget.get(widget);
        if (history == null || history.isEmpty()) {
            return;
        }
        gutter.setAllTimestamps(new TreeMap<>(history));
    }

    private void handleTerminalGeometryChanged(JediTermFxWidget widget, TimestampGutter gutter, ScrollBar scrollBar,
                                               com.techsenger.jeditermfx.core.model.TerminalTextBuffer textBuffer,
                                               com.techsenger.jeditermfx.ui.TerminalPanel terminalPanel) {
        Platform.runLater(() -> {
            syncGutterFromHistory(widget, gutter);
            updateGutterScrollState(gutter, scrollBar, textBuffer, terminalPanel);
        });
    }

    private void scheduleCommandCompletionDetection(JediTermFxWidget widget) {
        PauseTransition timer = commandCompletionTimerByWidget.computeIfAbsent(widget, w -> {
            PauseTransition pt = new PauseTransition(Duration.millis(500));
            pt.setOnFinished(e -> recordCommandCompletionTimestamp(w));
            return pt;
        });
        timer.playFromStart();
    }

    private void recordCommandCompletionTimestamp(JediTermFxWidget widget) {
        if (!Boolean.TRUE.equals(awaitingCommandCompletionByWidget.get(widget))) {
            return;
        }
        try {
            int absoluteLine = getCurrentAbsoluteCursorLine(widget);
            if (absoluteLine < 0) {
                return;
            }
            int startLine = commandStartLineByWidget.getOrDefault(widget, -1);
            // Ensure completion marker is never above the command start line.
            if (startLine >= 0 && absoluteLine < startLine) {
                absoluteLine = startLine;
            }
            recordTimestampForLine(widget, absoluteLine, LocalDateTime.now());
            awaitingCommandCompletionByWidget.put(widget, false);
        } catch (Exception e) {
            logger.debug("Failed to record command completion timestamp: {}", e.getMessage());
        }
    }

    /**
     * Returns the current absolute cursor line (0-based over full history+screen buffer),
     * with bounds clamped to valid terminal rows to avoid transient out-of-bounds states.
     */
    private int getCurrentAbsoluteCursorLine(JediTermFxWidget widget) {
        try {
            var terminal = widget.getTerminal();
            var textBuffer = widget.getTerminalTextBuffer();
            if (terminal == null || textBuffer == null) return -1;
            int screenHeight = Math.max(1, textBuffer.getHeight());
            int cursorY = terminal.getCursorY() - 1; // JediTerm cursor is 1-based
            int clampedCursorY = Math.min(Math.max(cursorY, 0), screenHeight - 1);
            return textBuffer.getHistoryLinesCount() + clampedCursorY;
        } catch (Exception e) {
            logger.trace("Could not resolve current cursor line: {}", e.getMessage());
            return -1;
        }
    }

    /**
     * Returns a snapshot of recorded timestamps for the primary terminal widget.
     * Used by project save/export.
     */
    public java.util.List<de.kortty.model.TerminalTimestampEntry> getPrimaryTimestampEntries() {
        JediTermFxWidget primary = terminalWidget;
        if (primary == null) {
            return java.util.Collections.emptyList();
        }
        TreeMap<Integer, LocalDateTime> history = timestampHistoryByWidget.get(primary);
        if (history == null || history.isEmpty()) {
            return java.util.Collections.emptyList();
        }
        java.util.List<de.kortty.model.TerminalTimestampEntry> entries = new java.util.ArrayList<>(history.size());
        for (Map.Entry<Integer, LocalDateTime> entry : history.entrySet()) {
            if (entry.getValue() != null) {
                entries.add(new de.kortty.model.TerminalTimestampEntry(entry.getKey(), entry.getValue()));
            }
        }
        return entries;
    }

    /**
     * Restores recorded timestamps for the primary terminal widget from project import.
     */
    public void restorePrimaryTimestampEntries(java.util.List<de.kortty.model.TerminalTimestampEntry> entries) {
        JediTermFxWidget primary = terminalWidget;
        if (primary == null) {
            return;
        }
        TreeMap<Integer, LocalDateTime> restored = new TreeMap<>();
        if (entries != null) {
            for (de.kortty.model.TerminalTimestampEntry entry : entries) {
                if (entry == null || entry.getTimestamp() == null) continue;
                int absoluteLine = entry.getAbsoluteLine();
                if (absoluteLine < 0) continue;
                restored.putIfAbsent(absoluteLine, entry.getTimestamp());
            }
        }
        timestampHistoryByWidget.put(primary, restored);
        TimestampGutter gutter = gutterMap.get(primary);
        if (gutter != null) {
            gutter.setAllTimestamps(new TreeMap<>(restored));
        }
        int lastLine = restored.isEmpty() ? -1 : restored.lastKey();
        lastTimestampLineByWidget.put(primary, lastLine);
    }
    
    /**
     * Updates the gutter's scroll state to match the terminal panel's current state.
     * Computes the scroll origin from the scrollbar value using the same formula
     * as TerminalPanel.resolveSwingScrollBarValue().
     */
    private void updateGutterScrollState(TimestampGutter gutter, ScrollBar scrollBar,
                                          com.techsenger.jeditermfx.core.model.TerminalTextBuffer textBuffer,
                                          com.techsenger.jeditermfx.ui.TerminalPanel terminalPanel) {
        try {
            int historyLines = textBuffer.getHistoryLinesCount();
            int visibleRows = textBuffer.getHeight();
            // During resize/reflow the buffer can transiently report invalid geometry.
            // Ignore those intermediate states to avoid "empty" gutter redraws.
            if (visibleRows <= 0) {
                return;
            }
            double charHeight = terminalPanel.getCellHeightPixels();
            if (charHeight <= 0) {
                double pixelHeight = terminalPanel.getPixelHeight();
                charHeight = visibleRows > 0 ? pixelHeight / visibleRows : 16;
            }
            if (charHeight <= 0) {
                return;
            }
            int scrollOrigin = terminalPanel.getScrollOrigin();
            int minOrigin = -Math.max(0, historyLines);
            if (scrollOrigin < minOrigin) scrollOrigin = minOrigin;
            if (scrollOrigin > 0) scrollOrigin = 0;
            double baselineOffset = terminalPanel.getCellBaselineOffsetPixels();
            if (baselineOffset <= 0 || baselineOffset > charHeight * 2.0) {
                baselineOffset = charHeight * 0.78;
            }
            gutter.updateScrollState(scrollOrigin, historyLines, charHeight, visibleRows, baselineOffset);
        } catch (Exception e) {
            logger.debug("Failed to update gutter scroll state: {}", e.getMessage());
        }
    }
    
    /**
     * Checks whether command timestamps are enabled, considering both connection-specific
     * and global settings. If the connection uses global settings, the global setting is checked.
     */
    private boolean isCommandTimestampsEnabled() {
        // Connection-specific setting takes priority
        if (!settings.isUseGlobalSettings()) {
            return settings.isCommandTimestampsEnabled();
        }
        // Check global setting
        try {
            var gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            var gs = gsm.getSettings();
            return gs != null && gs.isCommandTimestampsEnabled();
        } catch (Exception e) {
            return settings.isCommandTimestampsEnabled();
        }
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
    
    public void setOnReconnectRequested(Runnable r) {
        this.onReconnectRequested = r;
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
                showMessage(I18n.get("terminal.connectionAttempt", 1, retryCount));
            } else {
                showMessage(I18n.get("terminal.connecting"));
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
                        showMessage(I18n.get("terminal.connectionAttempt", attempt, retryCount));
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
                            try {
                                if (terminalWidget == null) return; // Tab was closed during connect
                                terminalWidget.setTtyConnector(ttyConnector);
                                terminalWidget.start();
                                applyCursorShape(terminalWidget);
                                if (splitPane != null) {
                                    for (JediTermFxWidget w : splitPane.getAllWidgets()) {
                                        applyCursorShape(w);
                                        setCursorVisible(w, true);
                                    }
                                }
                                setCursorVisible(terminalWidget, true);
                                if (onConnectedCallback != null) {
                                    onConnectedCallback.run();
                                }
                            } catch (RejectedExecutionException e) {
                                // Widget was already closed (e.g. tab closed) and its executor terminated
                                logger.debug("Terminal widget already closed, skipping start: {}", e.getMessage());
                            }
                        });
                        
                        logger.info("Terminal session started for {} (attempt {}/{})", 
                                   connection.getDisplayName(), attempt, retryCount);
                        return; // Success!
                    } else {
                        lastError = I18n.get("terminal.sshConnectionFailed");
                        logger.warn("Connection attempt {}/{} failed for {}", 
                                   attempt, retryCount, connection.getDisplayName());
                        
                        // Show failure message
                        showMessage(I18n.get("terminal.attemptFailed", attempt));
                        
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
                    showMessage(I18n.get("terminal.authFailed"));
                    showMessage("");
                    showMessage(I18n.get("terminal.possibleCauses"));
                    showMessage("  - " + I18n.get("terminal.noSSHKeySelected"));
                    showMessage("  - " + I18n.get("terminal.sshKeyNotAuthorized"));
                    showMessage("  - " + I18n.get("terminal.wrongUsername"));
                    showMessage("");
                    showMessage(I18n.get("error.title") + ": " + e.getMessage());
                    
                } catch (Exception e) {
                    lastError = I18n.get("terminal.connectionFailed") + ": " + e.getMessage();
                    logger.error("Failed to start terminal session (attempt {}/{}): {}", 
                                attempt, retryCount, e.getMessage(), e);
                    
                    // Show failure message
                    showMessage(I18n.get("terminal.attemptFailed", attempt) + ": " + e.getMessage());
                    
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
                    showMessage(I18n.get("terminal.allAttemptsFailed", retryCount));
                } else {
                    showMessage(I18n.get("terminal.connectionFailed"));
                }
                showMessage(I18n.get("terminal.timeout", connection.getConnectionTimeoutSeconds()));
                String finalError = lastError != null && !lastError.isEmpty() ? lastError : I18n.get("terminal.unknownError");
                showMessage(finalError);
            }
            logger.error("All connection attempts failed for {}", connection.getDisplayName());
            
            // Notify disconnect listener about failure
            String errorMessage = authenticationFailed 
                ? I18n.get("terminal.authFailed") 
                : (retryCount > 1 
                    ? I18n.get("terminal.allAttemptsFailed", retryCount) 
                    : I18n.get("terminal.connectionFailed"));
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
     * Disconnects without destroying the UI. Use for reconnect - keeps terminal widget and split pane.
     */
    public void disconnectOnly() {
        stopLogger();
        if (ttyConnector != null) {
            try {
                ttyConnector.close();
            } catch (Exception e) {
                logger.warn("Error closing TtyConnector: {}", e.getMessage());
            }
            ttyConnector = null;
        }
    }
    
    /**
     * Cleans up resources (closes connection and destroys UI). Use when closing the tab.
     */
    public void cleanup() {
        stopLogger();
        if (ttyConnector != null) {
            try {
                ttyConnector.close();
            } catch (Exception e) {
                logger.warn("Error closing TtyConnector: {}", e.getMessage());
            }
            ttyConnector = null;
        }
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
     * Toggles the visibility of all timestamp gutters in this terminal view.
     * When shown, previously recorded timestamps become visible immediately.
     *
     * @return true if gutters are now visible, false if hidden
     */
    public boolean toggleTimestampGutters() {
        boolean newVisible = !timestampGuttersVisibleState;
        setTimestampGuttersVisible(newVisible);
        return newVisible;
    }
    
    /**
     * Sets the visibility of all timestamp gutters in this terminal view.
     */
    public void setTimestampGuttersVisible(boolean visible) {
        boolean oldVisible = timestampGuttersVisibleState;
        timestampGuttersVisibleState = visible;
        for (Map.Entry<JediTermFxWidget, TimestampGutter> entry : gutterMap.entrySet()) {
            syncGutterFromHistory(entry.getKey(), entry.getValue());
            TimestampGutter gutter = entry.getValue();
            gutter.setVisible(visible);
            gutter.setManaged(visible);
        }
        if (oldVisible != visible) {
            adjustWindowWidthForTimestampToggle(oldVisible, visible);
        }
    }

    private void adjustWindowWidthForTimestampToggle(boolean oldVisible, boolean newVisible) {
        if (oldVisible == newVisible) return;
        Platform.runLater(() -> {
            Window window = getScene() != null ? getScene().getWindow() : null;
            if (!(window instanceof Stage stage)) return;
            if (stage.isMaximized() || stage.isFullScreen()) return;
            double delta = TimestampGutter.GUTTER_WIDTH;
            double targetWidth = stage.getWidth() + (newVisible ? delta : -delta);
            stage.setWidth(Math.max(640, targetWidth));
        });
        }
    
    /**
     * Sets a listener that is called whenever timestamp gutter visibility is toggled
     * from the context menu. Used by MainWindow to update the CheckMenuItem state.
     */
    public void setTimestampToggleListener(Runnable listener) {
        this.timestampToggleListener = listener;
    }
    
    /**
     * Returns whether timestamp gutters are currently visible.
     */
    public boolean isTimestampGuttersVisible() {
        return timestampGuttersVisibleState;
    }
    
    /**
     * Shows the find bar in the terminal.
     */
    public void showFind() {
        JediTermFxWidget focused = splitPane != null ? splitPane.getFocusedWidget() : terminalWidget;
        if (focused != null) {
            try {
                java.lang.reflect.Method m = focused.getClass().getDeclaredMethod("showFindComponent");
                m.setAccessible(true);
                m.invoke(focused);
            } catch (Exception e) {
                logger.warn("Could not invoke showFindComponent", e);
            }
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
            String family = settings.getFontFamily();
            if (family == null || family.isEmpty()) family = "Monospaced";
            if ("Monaco".equals(family) && !Font.getFamilies().contains("Monaco")) {
                family = "Monospaced";
            }
            return Font.font(family, getFontSize());
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
        public boolean altSendsEscape() {
            // On macOS, Option+key produces special characters (e.g. Option+7 = |, Option+5 = [,
            // Option+8 = {, Option+L = @, Option+N = ~). Returning false lets the OS handle these
            // combinations natively instead of sending ESC+key sequences.
            String os = System.getProperty("os.name", "").toLowerCase();
            return !os.contains("mac");
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
