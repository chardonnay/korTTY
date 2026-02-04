package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.ui.I18n;
import de.kortty.core.ProjectManager;
import de.kortty.core.SSHSession;
import de.kortty.core.SessionManager;
import de.kortty.model.*;
import de.kortty.persistence.importer.ConnectionImporter;
import de.kortty.persistence.importer.MTPuTTYImporter;
import de.kortty.persistence.importer.MobaXTermImporter;
import de.kortty.persistence.importer.PuTTYCMImporter;
import de.kortty.persistence.exporter.ConnectionExporter;
import de.kortty.persistence.exporter.KorTTYExporter;
import de.kortty.persistence.exporter.MTPuTTYExporter;
import de.kortty.persistence.exporter.MobaXTermExporter;
import de.kortty.security.PasswordVault;
import javafx.application.Platform;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Path;
import java.util.zip.ZipOutputStream;
import java.util.zip.ZipEntry;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Main application window with TabPane for SSH terminals.
 */
public class MainWindow {
    
    private static MainWindow instance;  // Singleton instance for global access
    private static final Logger logger = LoggerFactory.getLogger(MainWindow.class);
    
    private final Stage stage;
    private final BorderPane root;
    private final TabPane tabPane;
    private final Label statusLabel;
    private final HBox mainContentBox;
    private DashboardView dashboardView;
    private boolean dashboardVisible = false;
    
    private final KorTTYApplication app;
    private final SessionManager sessionManager;
    private final ProjectManager projectManager;
    private final List<ConnectionImporter> importers;
    
    private static final List<MainWindow> openWindows = new ArrayList<>();
    
    private volatile boolean quickConnectDialogOpen = false;
    private volatile boolean suppressQuickConnect = false;  // Flag to suppress QuickConnect on programmatic tab selection
    private volatile boolean allowAutoQuickConnect = false; // Only allow QuickConnect via explicit user action
    private volatile boolean startupComplete = false; // Prevent QuickConnect during startup
    
    public MainWindow(Stage stage) {
        instance = this;  // Set singleton instance
        this.stage = stage;
        this.app = KorTTYApplication.getInstance();
        this.sessionManager = app.getSessionManager();
        this.projectManager = new ProjectManager(KorTTYApplication.getConfigDirectory());
        
        // Initialize importers
        this.importers = List.of(new MTPuTTYImporter(), new MobaXTermImporter(), new PuTTYCMImporter());
        
        // Create UI components
        this.root = new BorderPane();
        this.tabPane = new TabPane();
        this.statusLabel = new Label(I18n.get("app.ready"));
        this.mainContentBox = new HBox();
        
        setupUI();
        setupMenuBar();
        setupKeyBindings();
        
        openWindows.add(this);
    }
    
    private void setupUI() {
        // Tab pane configuration
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.ALL_TABS);
        tabPane.setTabDragPolicy(TabPane.TabDragPolicy.REORDER);
        
        // Add "new tab" button tab
        Tab newTabButton = new Tab("+");
        newTabButton.setClosable(false);
        tabPane.getTabs().add(newTabButton);
        
        // Handle clicks on the + tab - show QuickConnect dialog
        // Only trigger when user actually clicks (not programmatic selection)
        newTabButton.setOnSelectionChanged(e -> {
            if (newTabButton.isSelected() && startupComplete && !quickConnectDialogOpen && !suppressQuickConnect) {
                Platform.runLater(() -> {
                    int plusTabIndex = tabPane.getTabs().indexOf(newTabButton);
                    if (plusTabIndex > 0) {
                        tabPane.getSelectionModel().select(plusTabIndex - 1);
                    }
                    showQuickConnect();
                });
            }
            // Reset flag after handling
            suppressQuickConnect = false;
        });
        
        // Auto-focus terminal when tab is selected
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab instanceof TerminalTab terminalTab) {
                Platform.runLater(() -> {
                    terminalTab.getTerminalView().requestFocus();
                });
            }
        });
        
        // Listen for tab removals to update dashboard
        tabPane.getTabs().addListener((javafx.collections.ListChangeListener.Change<? extends Tab> change) -> {
            while (change.next()) {
                if (change.wasRemoved()) {
                    // Tab was removed - update dashboard
                    Platform.runLater(() -> {
                        updateDashboard();
                    });
                    // Prevent QuickConnect from opening due to automatic selection of "+" after tab removal
                    suppressQuickConnect = true;
                }
            }
        });
        
        // Handle double-click on tab for retry
        tabPane.addEventFilter(javafx.scene.input.MouseEvent.MOUSE_CLICKED, event -> {
            if (event.getClickCount() == 2) {
                Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
                if (selectedTab instanceof TerminalTab terminalTab) {
                    // Check if tab is in failed state (dark red)
                    String style = selectedTab.getStyle();
                    if (style != null && style.contains("#8B0000")) {
                        // Retry connection
                        terminalTab.retryConnection();
                        event.consume();
                    }
                }
            }
        });
        
        // Status bar
        VBox statusBar = new VBox(statusLabel);
        statusBar.setStyle("-fx-padding: 5; -fx-background-color: #2d2d2d;");
        statusLabel.setStyle("-fx-text-fill: #cccccc;");
        
        // HBox for dashboard (fixed width) + tab pane (grows with window)
        mainContentBox.getChildren().add(tabPane);
        HBox.setHgrow(tabPane, Priority.ALWAYS);
        
        root.setCenter(mainContentBox);
        root.setBottom(statusBar);
        
        // Scene setup
        Scene scene = new Scene(root, 1000, 700);
        
        // Load CSS stylesheet (safely)
        var cssResource = getClass().getResource("/styles/terminal.css");
        if (cssResource != null) {
            scene.getStylesheets().add(cssResource.toExternalForm());
        } else {
            logger.warn("Could not load terminal.css stylesheet");
        }
        
        // Global keyboard shortcuts for zoom and fullscreen (works on all keyboard layouts)
        // Track if zoom was triggered to also consume KEY_TYPED event
        final boolean[] zoomTriggered = {false};
        
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_PRESSED, event -> {
            boolean ctrl = event.isControlDown();
            boolean alt = event.isAltDown();
            KeyCode code = event.getCode();
            String text = event.getText();
            String character = event.getCharacter();
            zoomTriggered[0] = false;
            
            // Fullscreen toggle: F11
            if (code == KeyCode.F11) {
                boolean goFullscreen = !stage.isFullScreen();
                stage.setFullScreen(goFullscreen);
                // Force terminal resize after fullscreen change
                Platform.runLater(() -> {
                    Platform.runLater(() -> {
                        // Double runLater to ensure layout is complete
                        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
                        if (selectedTab instanceof TerminalTab terminalTab) {
                            terminalTab.getTerminalView().requestFocus();
                        }
                    });
                });
                event.consume();
                return;
            }
            
            if (ctrl || alt) {
                // Zoom in: Ctrl/Alt + Plus (various key codes for different keyboards)
                if (code == KeyCode.PLUS || code == KeyCode.ADD || 
                    code == KeyCode.EQUALS || "+".equals(text) || "+".equals(character)) {
                    zoomTerminal(1);
                    zoomTriggered[0] = true;
                    event.consume();
                }
                // Zoom out: Ctrl/Alt + Minus
                else if (code == KeyCode.MINUS || code == KeyCode.SUBTRACT || 
                         "-".equals(text) || "-".equals(character)) {
                    zoomTerminal(-1);
                    zoomTriggered[0] = true;
                    event.consume();
                }
                // Reset zoom: Ctrl/Alt + 0
                else if (code == KeyCode.DIGIT0 || code == KeyCode.NUMPAD0) {
                    resetTerminalZoom();
                    zoomTriggered[0] = true;
                    event.consume();
                }
            }
        });
        
        // Also consume KEY_TYPED events for zoom to prevent +/- appearing in terminal
        scene.addEventFilter(javafx.scene.input.KeyEvent.KEY_TYPED, event -> {
            if (zoomTriggered[0]) {
                // Consume early to prevent JediTermFX from processing empty character
                event.consume();
                zoomTriggered[0] = false; // Reset for next event
            }
        });
        
        stage.setScene(scene);
        stage.setTitle(KorTTYApplication.getAppName());
        
        // Handle fullscreen changes - resize terminal properly
        stage.fullScreenProperty().addListener((obs, wasFullscreen, isFullscreen) -> {
            Platform.runLater(() -> {
                // Give layout time to update, then resize terminal
                Platform.runLater(() -> {
                    Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
                    if (selectedTab instanceof TerminalTab terminalTab) {
                        // Request focus to trigger proper resize
                        terminalTab.getTerminalView().requestFocus();
                    }
                });
            });
        });
        
        // Window close handling
        stage.setOnCloseRequest(e -> {
            // Save window geometry on close if enabled (not when using fixed geometry)
            GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
            
            // Always save last geometry (for next session) unless fixed geometry is used
            if (!globalSettings.isUseFixedWindowGeometry()) {
                WindowGeometry geo = new WindowGeometry(
                    stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()
                );
                geo.setMaximized(stage.isMaximized());
                globalSettings.setLastWindowGeometry(geo);
                logger.info("Saving window geometry: x={}, y={}, w={}, h={}, maximized={}", 
                    geo.getX(), geo.getY(), geo.getWidth(), geo.getHeight(), geo.isMaximized());
            }
            
            // Save dashboard state on close if enabled
            if (globalSettings.isRememberDashboardState()) {
                globalSettings.setDashboardVisible(dashboardVisible);
            }
            
            // Save settings BEFORE confirmClose (which might exit the app)
            try {
                app.getGlobalSettingsManager().save();
                logger.info("Window settings saved successfully");
            } catch (Exception ex) {
                logger.error("Failed to save window settings", ex);
            }
            
            if (!confirmClose()) {
                e.consume();
            } else {
                closeAllTabs();
                openWindows.remove(this);
                
                // If this was the last window, exit the application
                if (openWindows.isEmpty()) {
                    logger.info("Last window closed, exiting application");
                    // Close all sessions before exiting
                    if (app.getSessionManager() != null) {
                        app.getSessionManager().closeAllSessions();
                    }
                    // Use Platform.exit() - it will call Application.stop() which handles cleanup
                    // After stop() completes, System.exit() will be called to ensure all threads terminate
                    Platform.exit();
                }
            }
        });
    }
    
    private void setupMenuBar() {
        MenuBar menuBar = new MenuBar();
        
        // File Menu
        Menu fileMenu = new Menu(I18n.get("menu.file"));
        
        MenuItem newWindow = new MenuItem(I18n.get("menu.file.newWindow"));
        newWindow.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        newWindow.setOnAction(e -> openNewWindow());
        
        MenuItem newTab = new MenuItem(I18n.get("menu.file.newTab"));
        newTab.setAccelerator(new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN));
        newTab.setOnAction(e -> showQuickConnect());
        
        MenuItem openProject = new MenuItem(I18n.get("menu.file.openProject"));
        openProject.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        openProject.setOnAction(e -> openProject());
        
        MenuItem saveProject = new MenuItem(I18n.get("menu.file.saveProject"));
        saveProject.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        saveProject.setOnAction(e -> saveProject());
        
        MenuItem closeTab = new MenuItem(I18n.get("menu.file.closeTab"));
        closeTab.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));
        closeTab.setOnAction(e -> closeCurrentTab());
        
        MenuItem quit = new MenuItem(I18n.get("menu.file.quit"));
        quit.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
        quit.setOnAction(e -> Platform.exit());
        
        fileMenu.getItems().addAll(newWindow, newTab, new SeparatorMenuItem(),
                openProject, saveProject, new SeparatorMenuItem(),
                closeTab, new SeparatorMenuItem(), quit);
        
        // Edit Menu
        Menu editMenu = new Menu(I18n.get("menu.edit"));
        
        MenuItem copy = new MenuItem(I18n.get("menu.edit.copy"));
        copy.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN));
        copy.setOnAction(e -> copyFromTerminal());
        
        MenuItem paste = new MenuItem(I18n.get("menu.edit.paste"));
        paste.setAccelerator(new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN));
        paste.setOnAction(e -> pasteToTerminal());
        
        MenuItem settings = new MenuItem(I18n.get("menu.settings.global"));
        settings.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        settings.setOnAction(e -> showSettings());
        
        MenuItem createBackup = new MenuItem(I18n.get("menu.edit.createBackup"));
        createBackup.setAccelerator(new KeyCodeCombination(KeyCode.B, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        createBackup.setOnAction(e -> createBackup());
        
        MenuItem importBackup = new MenuItem(I18n.get("menu.edit.importBackup"));
        importBackup.setOnAction(e -> importBackup());
        
        editMenu.getItems().addAll(copy, paste, new SeparatorMenuItem(), settings, createBackup, importBackup);
        
        // Connections Menu
        Menu connectionsMenu = new Menu(I18n.get("menu.connections"));
        
        MenuItem quickConnect = new MenuItem(I18n.get("menu.connections.quickConnect"));
        quickConnect.setOnAction(e -> showQuickConnect());
        
        MenuItem manageConnections = new MenuItem(I18n.get("menu.connections.manage"));
        manageConnections.setAccelerator(new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN));
        manageConnections.setOnAction(e -> showConnectionManager());
        
        MenuItem importConnections = new MenuItem(I18n.get("menu.connections.import"));
        importConnections.setOnAction(e -> importConnections());
        
        MenuItem exportConnections = new MenuItem(I18n.get("menu.connections.export"));
        exportConnections.setOnAction(e -> exportConnections());
        
        connectionsMenu.getItems().addAll(quickConnect, manageConnections,
                new SeparatorMenuItem(), importConnections, exportConnections);
        
        // Management Menu
        Menu managementMenu = new Menu(I18n.get("menu.management"));
        
        MenuItem manageCredentials = new MenuItem(I18n.get("menu.management.credentials"));
        manageCredentials.setOnAction(e -> showCredentialManagement());
        
        MenuItem manageGPGKeys = new MenuItem(I18n.get("menu.management.gpgKeys"));
        manageGPGKeys.setOnAction(e -> showGPGKeyManagement());
        
        MenuItem manageSSHKeys = new MenuItem(I18n.get("menu.management.sshKeys"));
        manageSSHKeys.setOnAction(e -> showSSHKeyManagement());
        
        managementMenu.getItems().addAll(manageCredentials, manageGPGKeys, manageSSHKeys);
        
        // Tools Menu
        Menu sftpMenu = new Menu(I18n.get("menu.tools"));
        
        MenuItem openSFTPManager = new MenuItem(I18n.get("menu.tools.sftpManager"));
        openSFTPManager.setOnAction(e -> showSFTPManager());
        
        sftpMenu.getItems().add(openSFTPManager);
        
        // View Menu
        Menu viewMenu = new Menu(I18n.get("menu.view"));
        
        CheckMenuItem showDashboard = new CheckMenuItem(I18n.get("menu.view.dashboard"));
        showDashboard.setAccelerator(new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        showDashboard.setOnAction(e -> toggleDashboard(showDashboard.isSelected()));
        
        MenuItem zoomIn = new MenuItem(I18n.get("menu.view.zoomIn"));
        zoomIn.setAccelerator(new KeyCodeCombination(KeyCode.PLUS, KeyCombination.ALT_DOWN));
        zoomIn.setOnAction(e -> zoomTerminal(1));
        
        MenuItem zoomOut = new MenuItem(I18n.get("menu.view.zoomOut"));
        zoomOut.setAccelerator(new KeyCodeCombination(KeyCode.MINUS, KeyCombination.ALT_DOWN));
        zoomOut.setOnAction(e -> zoomTerminal(-1));
        
        MenuItem resetZoom = new MenuItem(I18n.get("menu.view.resetZoom"));
        resetZoom.setAccelerator(new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.ALT_DOWN));
        resetZoom.setOnAction(e -> resetTerminalZoom());
        
        MenuItem fullscreen = new MenuItem(I18n.get("menu.view.fullscreen"));
        fullscreen.setAccelerator(new KeyCodeCombination(KeyCode.F11));
        fullscreen.setOnAction(e -> stage.setFullScreen(!stage.isFullScreen()));
        
        viewMenu.getItems().addAll(showDashboard, new SeparatorMenuItem(),
                zoomIn, zoomOut, resetZoom, new SeparatorMenuItem(), fullscreen);
        
        // Help Menu
        Menu helpMenu = new Menu(I18n.get("menu.help"));
        
        MenuItem about = new MenuItem(I18n.get("menu.help.about") + " " + KorTTYApplication.getAppName());
        about.setOnAction(e -> showAbout());
        
        helpMenu.getItems().add(about);
        
        // Menu order: File, Edit, Connections, Management, Tools, View, Help
        menuBar.getMenus().addAll(fileMenu, editMenu, connectionsMenu, managementMenu, sftpMenu, viewMenu, helpMenu);
        root.setTop(menuBar);
    }
    
    private void setupKeyBindings() {
        stage.getScene().setOnKeyPressed(e -> {
            // Tab switching with Ctrl+Tab / Ctrl+Shift+Tab
            if (e.isControlDown() && e.getCode() == KeyCode.TAB) {
                if (e.isShiftDown()) {
                    selectPreviousTab();
                } else {
                    selectNextTab();
                }
                e.consume();
            }
        });
    }
    
    public void show() {
        // Restore window geometry if enabled
        GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
        
        // Determine which geometry to use
        WindowGeometry geoToUse = null;
        
        if (globalSettings.isUseFixedWindowGeometry() && globalSettings.getFixedWindowGeometry() != null) {
            // Use fixed geometry
            geoToUse = globalSettings.getFixedWindowGeometry();
        } else if (globalSettings.isRememberWindowGeometry() && globalSettings.getLastWindowGeometry() != null) {
            // Use last geometry
            geoToUse = globalSettings.getLastWindowGeometry();
        }
        
        if (geoToUse != null) {
            stage.setX(geoToUse.getX());
            stage.setY(geoToUse.getY());
            stage.setWidth(geoToUse.getWidth());
            stage.setHeight(geoToUse.getHeight());
            if (geoToUse.isMaximized()) {
                stage.setMaximized(true);
            }
        }
        
        stage.show();
        
        // Mark startup as complete after a short delay to allow UI to settle
        Platform.runLater(() -> {
            Platform.runLater(() -> {
                startupComplete = true;
            });
        });
        
        // Restore dashboard state if enabled
        if (globalSettings.isRememberDashboardState() && globalSettings.isDashboardVisible()) {
            Platform.runLater(() -> toggleDashboard(true));
        }
    }
    
    /**
     * Static method to suppress QuickConnect dialog on next + tab selection.
     * Called by tabs that are closing programmatically.
     */
    public static void suppressNextQuickConnect() {
        if (instance != null) {
            instance.suppressQuickConnect = true;
        }
    }
    
    /**
     * Opens a new SSH connection in a new tab.
     */
    public void openConnection(ServerConnection connection, String password) {
        openConnection(connection, password, null, null);
    }
    
    /**
     * Opens a new SSH connection in a new tab with optional history restore.
     */
    public void openConnection(ServerConnection connection, String password, String historyToRestore) {
        openConnection(connection, password, historyToRestore, null);
    }
    
    /**
     * Opens a new SSH connection in a new tab with optional history restore and temporary SSH key.
     */
    public void openConnection(ServerConnection connection, String password, String historyToRestore, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        openConnectionAndReturnTab(connection, password, historyToRestore, temporarySSHKey);
    }
    
    /**
     * Opens a new SSH connection in a new tab with optional history restore and returns the tab.
     * The tab starts with NO group (independent from connection group).
     */
    private TerminalTab openConnectionAndReturnTab(ServerConnection connection, String password, String historyToRestore, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        try {
            // Check if connection has a permanent temporary key that should be used automatically
            de.kortty.model.TemporarySSHKey keyToUse = temporarySSHKey;
            if (keyToUse == null && connection.isTemporaryKeyPermanent() && 
                connection.getTemporaryKeyContent() != null && !connection.getTemporaryKeyContent().trim().isEmpty()) {
                // Create TemporarySSHKey from stored content if still valid
                Long expirationMinutes = connection.getTemporaryKeyExpirationMinutes();
                if (expirationMinutes != null && expirationMinutes > 0) {
                    // Check if we can get it from manager first
                    de.kortty.core.TemporarySSHKeyManager keyManager = de.kortty.core.TemporarySSHKeyManager.getInstance();
                    de.kortty.model.TemporarySSHKey existingKey = keyManager.getTemporaryKey(connection.getTemporaryKeyContent());
                    
                    if (existingKey != null && existingKey.isValid()) {
                        // Use existing key from manager
                        keyToUse = existingKey;
                    } else {
                        // Create new TemporarySSHKey from stored content
                        // Calculate remaining time based on when it was created
                        // Since we don't store creation time, we'll create it with the full expiration time
                        keyToUse = keyManager.storeTemporaryKey(connection.getTemporaryKeyContent(), expirationMinutes);
                    }
                    
                    // Update privateKeyPath to use TEMPORARY: prefix
                    connection.setPrivateKeyPath("TEMPORARY:" + connection.getTemporaryKeyContent());
                    connection.setAuthMethod(de.kortty.model.AuthMethod.PUBLIC_KEY);
                }
            }
            
            // Create terminal tab with JediTermFX
            // Note: Tab starts with NO group (tabGroup = null), even if connection has a group
            TerminalTab terminalTab = new TerminalTab(connection, password, keyToUse);
            
            // Set callback for "Split with new connection" feature
            terminalTab.getTerminalView().setNewConnectionCallback(this::requestNewConnectionForSplit);
            
            terminalTab.setOnClosed(e -> {
                updateDashboard();
                organizeTabsByGroup();
                updateAllTabContextMenus(); // Update context menus when tab closes
            });
            
            // Setup context menu for group assignment (before group assignment)
            setupTabContextMenu(terminalTab);
            
            // Assign group from connection if present (for initial assignment)
            // This allows connection groups to be used as default for new tabs
            // Groups are automatically created if they don't exist yet
            if (connection.getGroup() != null && !connection.getGroup().trim().isEmpty()) {
                terminalTab.setGroup(connection.getGroup().trim());
            }
            
            // Insert before the "+" tab, maintaining group order
            insertTabInGroupOrder(terminalTab);
            tabPane.getSelectionModel().select(terminalTab);
            
            // Update dashboard and context menus after group assignment
            updateDashboard();
            updateAllTabContextMenus();
            
            // Connect in background
            Thread connectThread = new Thread(() -> {
                try {
                    terminalTab.connect();
                    
                    // Set callback AFTER connect() to update dashboard when connection succeeds
                    // Note: TerminalTab.connect() sets a callback for tab title/color update.
                    // Since we're overwriting it, we need to also do what TerminalTab's callback does:
                    // - Update tab title
                    // - Reset tab color (setStyle(""))
                    terminalTab.getTerminalView().setOnConnectedCallback(() -> {
                        Platform.runLater(() -> {
                            // Update tab title (what TerminalTab's callback does)
                            terminalTab.updateTabTitle();
                            // Reset tab color (TerminalTab's resetTabColor() does setStyle(""))
                            terminalTab.setStyle("");
                            // Update status and dashboard
                            updateStatus(I18n.get("status.connectedTo", connection.getDisplayName()));
                            updateDashboard(); // Update dashboard when connection succeeds
                        });
                    });
                    
                    // Restore history after connection is established
                    if (historyToRestore != null && !historyToRestore.isEmpty()) {
                        // Wait a bit for terminal to be fully initialized
                        new Thread(() -> {
                            try {
                                Thread.sleep(500); // Give terminal time to settle
                                Platform.runLater(() -> {
                                    terminalTab.getTerminalView().restoreHistory(historyToRestore);
                                    logger.info("Terminal history restored for {}", connection.getDisplayName());
                                });
                            } catch (InterruptedException e) {
                                logger.error("History restore interrupted", e);
                            }
                        }).start();
                    }
                } catch (Exception ex) {
                    logger.error("Connection failed", ex);
                    Platform.runLater(() -> {
                        terminalTab.onConnectionFailed(ex.getMessage());
                        updateStatus(I18n.get("status.connectionFailed", ex.getMessage()));
                        updateDashboard(); // Update dashboard on failure too
                    });
                }
            });
            connectThread.setDaemon(true);
            connectThread.start();
            
            // Don't update dashboard immediately - wait for connection to establish
            // Dashboard will be updated after connection succeeds/fails
            return terminalTab;
        } catch (Exception e) {
            logger.error("Failed to create session", e);
            showError(I18n.get("error.connectionError"), I18n.get("status.sessionCreationFailed", e.getMessage()));
            return null;
        }
    }
    
    private void showQuickConnect() {
        // Prevent double-opening
        if (quickConnectDialogOpen) {
            return;
        }
        
        quickConnectDialogOpen = true;
        
        try {
            // Create password vault for retrieving stored passwords
            PasswordVault vault = new PasswordVault(
                    app.getMasterPasswordManager().getEncryptionService(),
                    app.getMasterPasswordManager().getMasterPassword()
            );
            
            // Pass saved connections and vault to the dialog
            QuickConnectDialog dialog = new QuickConnectDialog(stage, app.getConfigManager().getConnections(), vault, 
                    app.getCredentialManager(), app.getSSHKeyManager(), 
                    app.getMasterPasswordManager().getMasterPassword(), 10);
            dialog.showAndWait().ifPresent(result -> {
            // Handle load project request
            if (result.isLoadProject()) {
                openProject();
                return;
            }
            
            // Handle group connection
            if (result.isGroupConnection()) {
                openGroupConnections(result.groupName());
                return;
            }
            
            String password = result.password();
            
            // Increment usage count for existing connection
            if (result.existingSaved() && result.connection() != null) {
                result.connection().incrementUsageCount();
                app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
            }
            
            // Save connection if requested (for new connections)
            if (result.save() && !result.existingSaved()) {
                // Store password encrypted
                if (password != null && !password.isEmpty()) {
                    vault.storePassword(result.connection(), password);
                }
                app.getConfigManager().addConnection(result.connection());
                try {
                    app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                    logger.info("Connection saved: {}", result.connection().getDisplayName());
                } catch (Exception e) {
                    logger.error("Failed to save connection", e);
                }
            }
            // Pass temporary SSH key if available
            openConnection(result.connection(), password, null, result.temporarySSHKey());
            });
        } finally {
            quickConnectDialogOpen = false;
        }
    }
    
    /**
     * Opens QuickConnect dialog for split terminal and returns the connection result.
     * Used by TerminalView when user requests "Split with new connection".
     */
    private TerminalView.ConnectionResult requestNewConnectionForSplit() {
        logger.info("requestNewConnectionForSplit() called - Opening QuickConnect for split");
        
        try {
            // Create password vault for retrieving stored passwords
            PasswordVault vault = new PasswordVault(
                    app.getMasterPasswordManager().getEncryptionService(),
                    app.getMasterPasswordManager().getMasterPassword()
            );
            
            // Open QuickConnect dialog
            QuickConnectDialog dialog = new QuickConnectDialog(stage, app.getConfigManager().getConnections(), vault,
                    app.getCredentialManager(), app.getSSHKeyManager(),
                    app.getMasterPasswordManager().getMasterPassword(), 10);
            
            var optResult = dialog.showAndWait();
            if (optResult.isEmpty()) {
                return null; // User cancelled
            }
            
            var result = optResult.get();
            
            // Don't handle load project or group connection for splits
            if (result.isLoadProject() || result.isGroupConnection()) {
                return null;
            }
            
            String password = result.password();
            
            // Increment usage count for existing connection
            if (result.existingSaved() && result.connection() != null) {
                result.connection().incrementUsageCount();
                app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
            }
            
            // Save connection if requested (for new connections)
            if (result.save() && !result.existingSaved()) {
                if (password != null && !password.isEmpty()) {
                    vault.storePassword(result.connection(), password);
                }
                app.getConfigManager().addConnection(result.connection());
                try {
                    app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                    logger.info("Connection saved: {}", result.connection().getDisplayName());
                } catch (Exception e) {
                    logger.error("Failed to save connection", e);
                }
            }
            
            return new TerminalView.ConnectionResult(result.connection(), password);
        } catch (Exception e) {
            logger.error("Failed to request new connection for split", e);
            return null;
        }
    }
    
    private void showConnectionManager() {
        logger.info("showConnectionManager() called - Opening Connection Manager");
        ConnectionManagerDialog dialog = new ConnectionManagerDialog(stage, app);
        dialog.showAndWait().ifPresent(connection -> {
            // Ask for password if needed
            PasswordVault vault = new PasswordVault(
                    app.getMasterPasswordManager().getEncryptionService(),
                    app.getMasterPasswordManager().getMasterPassword()
            );
            
            String password = getConnectionPassword(connection);
            if (password == null) {
                TextInputDialog pwDialog = new TextInputDialog();
                pwDialog.setTitle(I18n.get("dialog.passwordRequired"));
                pwDialog.setHeaderText(I18n.get("dialog.passwordFor", connection.getDisplayName()));
                pwDialog.setContentText(I18n.get("common.password") + ":");
                pwDialog.getEditor().setPromptText(I18n.get("dialog.enterPassword"));
                
                // Make it a password field
                pwDialog.showAndWait().ifPresent(pw -> {
                    openConnection(connection, pw);
                });
            } else {
                openConnection(connection, password);
            }
        });
    }
    
    private void showSettings() {
        SettingsDialog dialog = new SettingsDialog(stage, app, app.getConfigManager(), 
                app.getGlobalSettingsManager().getSettings(),
                app.getCredentialManager(), app.getGpgKeyManager());
        
        // Add listener to apply settings changes immediately to all open terminals
        dialog.addChangeListener(() -> {
            logger.info("Settings changed, updating all terminal views");
            Platform.runLater(() -> {
                // Update scrollbar visibility for all open terminal views
                for (Tab tab : tabPane.getTabs()) {
                    if (tab instanceof TerminalTab terminalTab) {
                        // Scrollbar functionality removed
                    }
                }
                updateStatus(I18n.get("status.globalSettingsSaved"));
            });
        });
        
        dialog.showAndWait();
    }
    
    private void openNewWindow() {
        Stage newStage = new Stage();
        MainWindow newWindow = new MainWindow(newStage);
        newWindow.show();
    }
    
    private void closeCurrentTab() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab != null && currentTab.isClosable()) {
            tabPane.getTabs().remove(currentTab);
        }
    }
    
    private void closeAllTabs() {
        List<Tab> tabsToClose = new ArrayList<>(tabPane.getTabs());
        for (Tab tab : tabsToClose) {
            if (tab instanceof TerminalTab terminalTab) {
                // Temporarily disable close confirmation to avoid dialogs when closing all tabs
                terminalTab.setOnCloseRequest(null);
                // Cleanup terminal view before removing tab to ensure SSH connections are closed
                terminalTab.getTerminalView().cleanup();
                tabPane.getTabs().remove(tab);
            }
        }
    }
    
    private boolean confirmClose() {
        // Count only active (connected) sessions
        long activeConnections = tabPane.getTabs().stream()
                .filter(t -> t instanceof TerminalTab)
                .map(t -> (TerminalTab) t)
                .filter(TerminalTab::isConnected)
                .count();
        
        // No confirmation needed if no active connections
        if (activeConnections == 0) {
            return true;
        }
        
        // Show confirmation for active connections
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        alert.setTitle(I18n.get("dialog.closeWindow"));
        alert.setHeaderText(I18n.get("dialog.activeConnections"));
        alert.setContentText(I18n.get("dialog.activeConnectionsMessage", activeConnections));
        
        return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
    }
    
    private void selectNextTab() {
        int current = tabPane.getSelectionModel().getSelectedIndex();
        int next = (current + 1) % (tabPane.getTabs().size() - 1); // Skip the "+" tab
        tabPane.getSelectionModel().select(next);
    }
    
    private void selectPreviousTab() {
        int current = tabPane.getSelectionModel().getSelectedIndex();
        int prev = current - 1;
        if (prev < 0) prev = tabPane.getTabs().size() - 2; // Skip the "+" tab
        tabPane.getSelectionModel().select(prev);
    }
    
    private void copyFromTerminal() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            terminalTab.copySelection();
        }
    }
    
    private void pasteToTerminal() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            terminalTab.paste();
        }
    }
    
    private void zoomTerminal(int delta) {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            terminalTab.zoom(delta);
        }
    }
    
    private void resetTerminalZoom() {
        Tab currentTab = tabPane.getSelectionModel().getSelectedItem();
        if (currentTab instanceof TerminalTab terminalTab) {
            terminalTab.resetZoom();
        }
    }
    
    private static final int DASHBOARD_FIXED_WIDTH = 300;
    
    private void toggleDashboard(boolean show) {
        if (show && !dashboardVisible) {
            if (dashboardView == null) {
                dashboardView = new DashboardView(tabPane, this::handleDashboardAction);
                // Fixed width - dashboard does not resize when main window resizes
                dashboardView.setMinWidth(DASHBOARD_FIXED_WIDTH);
                dashboardView.setMaxWidth(DASHBOARD_FIXED_WIDTH);
                dashboardView.setPrefWidth(DASHBOARD_FIXED_WIDTH);
            }
            mainContentBox.getChildren().add(0, dashboardView);
            dashboardVisible = true;
        } else if (!show && dashboardVisible) {
            mainContentBox.getChildren().remove(dashboardView);
            dashboardVisible = false;
        }
    }
    
    private void updateDashboard() {
        if (dashboardView != null && dashboardVisible) {
            dashboardView.refresh();
        }
    }
    
    private void handleDashboardAction(TerminalTab terminalTab, DashboardView.DashboardAction action) {
        switch (action) {
            case FOCUS:
                // Focus the tab
                tabPane.getSelectionModel().select(terminalTab);
                break;
                
            case CLOSE:
                // Close the tab
                tabPane.getTabs().remove(terminalTab);
                updateDashboard();
                updateStatus(I18n.get("status.tabClosed", terminalTab.getConnection().getDisplayName()));
                break;
                
            case RECONNECT:
                // Reconnect the terminal
                Platform.runLater(() -> {
                    try {
                        terminalTab.connect();
                        updateDashboard();
                        updateStatus(I18n.get("status.reconnecting", terminalTab.getConnection().getDisplayName()));
                    } catch (Exception e) {
                        logger.error("Reconnect failed", e);
                        updateStatus(I18n.get("status.reconnectionFailed", e.getMessage()));
                    }
                });
                break;
                
            case SFTP_MANAGER:
                // Open SFTP Manager for this connection
                if (terminalTab.isConnected()) {
                    de.kortty.model.TemporarySSHKey tempKey = terminalTab.getTemporarySSHKey() != null ?
                        de.kortty.core.TemporarySSHKeyManager.getInstance().getCurrentTemporaryKey() : null;
                    openSFTPManagerForConnection(terminalTab.getConnection(), tempKey);
                } else {
                    showError(I18n.get("error.notConnected"), I18n.get("error.notConnectedMessage"));
                }
                break;
                
            case DUPLICATE:
                // Duplicate the tab
                duplicateTab(terminalTab);
                break;
        }
    }
    
    private void openProject() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("menu.file.openProject"));
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("KorTTY Projekte", "*" + ProjectManager.getProjectExtension())
        );
        
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            try {
                Project project = projectManager.loadProject(file.toPath());
                loadProject(project);
                updateStatus(I18n.get("status.projectLoaded", project.getName()));
            } catch (Exception e) {
                logger.error("Failed to load project", e);
                showError(I18n.get("error.title"), I18n.get("error.projectLoadFailed", e.getMessage()));
            }
        }
    }
    
    private void saveProject() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("menu.file.saveProject"));
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("KorTTY Projekte", "*" + ProjectManager.getProjectExtension())
        );
        
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            String path = file.getPath();
            if (!path.endsWith(ProjectManager.getProjectExtension())) {
                path += ProjectManager.getProjectExtension();
                file = new File(path);
            }
            
            try {
                Project project = createProjectFromCurrentState();
                
                // Show project settings dialog
                ProjectSettingsDialog dialog = new ProjectSettingsDialog(stage, project);
                Optional<Project> result = dialog.showAndWait();
                
                if (result.isPresent()) {
                    projectManager.saveProject(result.get(), file.toPath());
                    updateStatus(I18n.get("status.projectSaved", file.getName()));
                }
            } catch (Exception e) {
                logger.error("Failed to save project", e);
                showError(I18n.get("error.title"), I18n.get("error.projectSaveFailed", e.getMessage()));
            }
        }
    }
    
    private Project createProjectFromCurrentState() {
        Project project = new Project("New Project");
        
        WindowState windowState = new WindowState(UUID.randomUUID().toString());
        windowState.setGeometry(new WindowGeometry(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()
        ));
        windowState.getGeometry().setMaximized(stage.isMaximized());
        
        // Save dashboard state (visibility)
        windowState.setDashboardVisible(dashboardVisible);
        
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                ServerConnection connection = terminalTab.getConnection();
                SessionState sessionState = new SessionState(
                        java.util.UUID.randomUUID().toString(),
                        connection.getId()
                );
                sessionState.setTabType(SessionState.TabType.TERMINAL);
                sessionState.setSettings(connection.getSettings());
                sessionState.setTerminalHistory(terminalTab.getTerminalView().getTerminalHistory());
                sessionState.setGroup(terminalTab.getGroup()); // Save tab group (not connection group)
                // Save current font size (zoom level) - may differ from settings when user zoomed
                int currentFontSize = terminalTab.getTerminalView().getCurrentFontSize();
                if (currentFontSize != connection.getSettings().getFontSize()) {
                    sessionState.setFontSizeOverride(currentFontSize);
                }
                // Save split pane structure (if terminal has splits)
                de.kortty.model.SplitPaneState splitState = terminalTab.getTerminalView().getSplitState();
                if (splitState != null) {
                    sessionState.setSplitPaneState(splitState);
                    logger.info("Saving split structure for tab: {}", connection.getDisplayName());
                }
                windowState.addTab(sessionState);
            } else if (tab instanceof SFTPManagerTab sftpTab) {
                SessionState sessionState = sftpTab.createSessionState();
                windowState.addTab(sessionState);
                logger.info("Saving SFTP Manager tab: {}", sftpTab.getText());
            } else if (tab instanceof FileEditorTab editorTab) {
                SessionState sessionState = editorTab.createSessionState();
                // Find connection ID if this is a remote file
                if (editorTab.isRemote() && editorTab.getSftpSession() != null) {
                    // Try to find matching SFTP tab to get connection
                    for (Tab t : tabPane.getTabs()) {
                        if (t instanceof SFTPManagerTab sftpTab && 
                            sftpTab.getConnection() != null) {
                            sessionState.setConnectionId(sftpTab.getConnection().getId());
                            break;
                        }
                    }
                }
                windowState.addTab(sessionState);
                logger.info("Saving File Editor tab: {}", editorTab.getText());
            } else if (tab instanceof ImageViewerTab viewerTab) {
                SessionState sessionState = viewerTab.createSessionState();
                // Find connection ID if this is a remote image
                if (viewerTab.isRemote() && viewerTab.getSftpSession() != null) {
                    // Try to find matching SFTP tab to get connection
                    for (Tab t : tabPane.getTabs()) {
                        if (t instanceof SFTPManagerTab sftpTab && 
                            sftpTab.getConnection() != null) {
                            sessionState.setConnectionId(sftpTab.getConnection().getId());
                            break;
                        }
                    }
                }
                windowState.addTab(sessionState);
                logger.info("Saving Image Viewer tab: {}", viewerTab.getText());
            }
        }
        
        windowState.setActiveTabIndex(tabPane.getSelectionModel().getSelectedIndex());
        project.addWindow(windowState);
        
        return project;
    }
    
    private void loadProject(Project project) {
        // Close existing tabs
        closeAllTabs();
        
        for (WindowState windowState : project.getWindows()) {
            // Apply window geometry
            WindowGeometry geo = windowState.getGeometry();
            if (geo != null) {
                stage.setX(geo.getX());
                stage.setY(geo.getY());
                stage.setWidth(geo.getWidth());
                stage.setHeight(geo.getHeight());
                stage.setMaximized(geo.isMaximized());
            }
            
            // Restore tabs
            for (SessionState sessionState : windowState.getTabs()) {
                SessionState.TabType tabType = sessionState.getTabType();
                if (tabType == null) {
                    tabType = SessionState.TabType.TERMINAL; // Backward compatibility
                }
                
                switch (tabType) {
                    case TERMINAL -> {
                        ServerConnection connection = app.getConfigManager().getConnectionById(sessionState.getConnectionId());
                        if (connection != null) {
                            if (project.isAutoReconnect()) {
                                // Get password and reconnect with history restore
                                String password = getConnectionPassword(connection);
                                if (password != null) {
                                    String history = sessionState.getTerminalHistory();
                                    TerminalTab restoredTab = openConnectionAndReturnTab(connection, password, history, null);
                                    // Restore tab group (not connection group)
                                    if (sessionState.getGroup() != null && !sessionState.getGroup().trim().isEmpty()) {
                                        restoredTab.setGroup(sessionState.getGroup());
                                        organizeTabsByGroup();
                                    }
                                    // Restore font size (zoom level) if saved
                                    Integer fontSizeOverride = sessionState.getFontSizeOverride();
                                    if (fontSizeOverride != null && fontSizeOverride > 0) {
                                        restoredTab.getTerminalView().setFontSize(fontSizeOverride);
                                    }
                                    // Restore split pane structure if saved (delayed until connection is ready)
                                    de.kortty.model.SplitPaneState splitState = sessionState.getSplitPaneState();
                                    if (splitState != null) {
                                        logger.info("Scheduling split structure restoration for tab: {}", connection.getDisplayName());
                                        // Wait for initial connection to be fully established before restoring splits
                                        Platform.runLater(() -> {
                                            try {
                                                Thread.sleep(1000); // Give time for initial connection
                                            } catch (InterruptedException e) {
                                                Thread.currentThread().interrupt();
                                            }
                                            restoredTab.getTerminalView().restoreSplitState(splitState);
                                        });
                                    }
                                    logger.info("Restoring tab for {} with {} chars of history", 
                                            connection.getDisplayName(), 
                                            history != null ? history.length() : 0);
                                }
                            } else {
                                // TODO: Create read-only tab with history display only (no connection)
                                logger.info("Auto-reconnect disabled, skipping connection for {}", 
                                        connection.getDisplayName());
                            }
                        }
                    }
                    case SFTP_MANAGER -> {
                        ServerConnection connection = app.getConfigManager().getConnectionById(sessionState.getConnectionId());
                        if (connection != null && project.isAutoReconnect()) {
                            String password = getConnectionPassword(connection);
                            if (password != null) {
                                Integer timeout = sessionState.getSftpAutoCloseTimeout();
                                int timeoutMinutes = (timeout != null && timeout > 0) ? timeout : 0;
                                
                                SFTPManagerTab sftpTab = new SFTPManagerTab(app, connection, password, null, timeoutMinutes);
                                tabPane.getTabs().add(sftpTab);
                                
                                // Restore paths if saved
                                if (sessionState.getSftpLocalPath() != null) {
                                    // Will be restored after connection
                                }
                                if (sessionState.getSftpRemotePath() != null) {
                                    // Will be restored after connection
                                }
                                
                                logger.info("Restored SFTP Manager tab for {}", connection.getDisplayName());
                            }
                        }
                    }
                    case FILE_EDITOR -> {
                        Boolean isRemote = sessionState.getEditorIsRemote();
                        String filePath = sessionState.getEditorFilePath();
                        
                        if (filePath != null) {
                            if (Boolean.TRUE.equals(isRemote)) {
                                // Remote file - need connection
                                ServerConnection connection = app.getConfigManager().getConnectionById(sessionState.getConnectionId());
                                if (connection != null && project.isAutoReconnect()) {
                                    String password = getConnectionPassword(connection);
                                    if (password != null) {
                                        // Open SFTP session and download file
                                        new Thread(() -> {
                                            try {
                                                de.kortty.core.SFTPSession sftpSession = new de.kortty.core.SFTPSession(connection, password);
                                                sftpSession.connect();
                                                
                                                byte[] content = sftpSession.downloadFileBytes(filePath);
                                                String filename = java.nio.file.Paths.get(filePath).getFileName().toString();
                                                
                                                Platform.runLater(() -> {
                                                    FileEditorTab editorTab = new FileEditorTab(filename, filePath, sftpSession, content);
                                                    tabPane.getTabs().add(editorTab);
                                                    logger.info("Restored remote file editor: {}", filePath);
                                                });
                                            } catch (Exception e) {
                                                logger.error("Failed to restore remote file editor", e);
                                            }
                                        }).start();
                                    }
                                }
                            } else {
                                // Local file
                                try {
                                    java.nio.file.Path path = java.nio.file.Paths.get(filePath);
                                    if (java.nio.file.Files.exists(path)) {
                                        FileEditorTab editorTab = new FileEditorTab(path);
                                        tabPane.getTabs().add(editorTab);
                                        logger.info("Restored local file editor: {}", filePath);
                                    }
                                } catch (Exception e) {
                                    logger.error("Failed to restore local file editor", e);
                                }
                            }
                        }
                    }
                    case IMAGE_VIEWER -> {
                        Boolean isRemote = sessionState.getImageIsRemote();
                        String filePath = sessionState.getImageFilePath();
                        Double zoomLevel = sessionState.getImageZoomLevel();
                        
                        if (filePath != null) {
                            if (Boolean.TRUE.equals(isRemote)) {
                                // Remote image - need connection
                                ServerConnection connection = app.getConfigManager().getConnectionById(sessionState.getConnectionId());
                                if (connection != null && project.isAutoReconnect()) {
                                    String password = getConnectionPassword(connection);
                                    if (password != null) {
                                        // Open SFTP session and download image
                                        new Thread(() -> {
                                            try {
                                                de.kortty.core.SFTPSession sftpSession = new de.kortty.core.SFTPSession(connection, password);
                                                sftpSession.connect();
                                                
                                                byte[] imageData = sftpSession.downloadFileBytes(filePath);
                                                String filename = java.nio.file.Paths.get(filePath).getFileName().toString();
                                                
                                                Platform.runLater(() -> {
                                                    ImageViewerTab viewerTab = new ImageViewerTab(filename, filePath, sftpSession, imageData);
                                                    tabPane.getTabs().add(viewerTab);
                                                    logger.info("Restored remote image viewer: {}", filePath);
                                                });
                                            } catch (Exception e) {
                                                logger.error("Failed to restore remote image viewer", e);
                                            }
                                        }).start();
                                    }
                                }
                            } else {
                                // Local image
                                try {
                                    java.nio.file.Path path = java.nio.file.Paths.get(filePath);
                                    if (java.nio.file.Files.exists(path)) {
                                        ImageViewerTab viewerTab = new ImageViewerTab(path);
                                        tabPane.getTabs().add(viewerTab);
                                        logger.info("Restored local image viewer: {}", filePath);
                                    }
                                } catch (Exception e) {
                                    logger.error("Failed to restore local image viewer", e);
                                }
                            }
                        }
                    }
                }
            }
        }
        
        // Restore dashboard state from project (visibility)
        WindowState firstWindow = project.getWindows().isEmpty() ? null : project.getWindows().get(0);
        if (firstWindow != null && Boolean.TRUE.equals(firstWindow.getDashboardVisible())) {
            toggleDashboard(true);
        }
    }
    
    private void importConnections() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("menu.connections.import"));
        
        // Add filters for each importer
        for (ConnectionImporter importer : importers) {
            List<String> extensions = new ArrayList<>();
            for (String ext : importer.getSupportedExtensions()) {
                extensions.add("*." + ext);
            }
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(importer.getFileDescription(), extensions)
            );
        }
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("Alle Dateien", "*.*")
        );
        
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            for (ConnectionImporter importer : importers) {
                if (importer.canImport(file.toPath())) {
                    try {
                        List<ServerConnection> imported = importer.importConnections(file.toPath());
                        for (ServerConnection conn : imported) {
                            app.getConfigManager().addConnection(conn);
                        }
                        app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                        
                        showInfo(I18n.get("info.importSuccessful", imported.size(), importer.getName()), "");
                        return;
                    } catch (Exception e) {
                        logger.error("Import failed", e);
                        showError(I18n.get("error.importFailed"), e.getMessage());
                        return;
                    }
                }
            }
            showError(I18n.get("error.importFailed"), I18n.get("error.importUnsupportedFormat"));
        }
    }
    
    private void exportConnections() {
        List<ServerConnection> allConnections = app.getConfigManager().getConnections();
        
        if (allConnections.isEmpty()) {
            showInfo(I18n.get("error.noConnectionsToExport"), "");
            return;
        }
        
        // Show export dialog
        ExportDialog dialog = new ExportDialog(stage, allConnections, app.getGpgKeyManager());
        Optional<ExportDialog.ExportResult> result = dialog.showAndWait();
        
        if (result.isEmpty() || result.get() == null) {
            return;
        }
        
        ExportDialog.ExportResult exportResult = result.get();
        
        // File chooser
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("menu.connections.export") + " " + I18n.get("dialog.as") + " " + exportResult.exporter.getName());
        
        if (exportResult.encryptionType == ExportDialog.EncryptionType.PASSWORD) {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(I18n.get("backup.encryption.password") + " (*.zip)", "*.zip")
            );
            fileChooser.setInitialFileName("connections.zip");
        } else if (exportResult.encryptionType == ExportDialog.EncryptionType.GPG) {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(I18n.get("backup.encryption.gpg") + " (*.gpg)", "*.gpg")
            );
            fileChooser.setInitialFileName("connections." + exportResult.exporter.getFileExtension() + ".gpg");
        } else {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter(exportResult.exporter.getFileDescription(), 
                            "*." + exportResult.exporter.getFileExtension())
            );
            fileChooser.setInitialFileName("connections." + exportResult.exporter.getFileExtension());
        }
        
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try {
                if (exportResult.encrypted()) {
                    exportAsEncryptedZip(exportResult, file.toPath());
                } else {
                    exportResult.exporter.exportConnections(exportResult.connections, file.toPath());
                }
                
                showInfo(I18n.get("info.exportSuccessful", 
                        exportResult.connections.size(), file.getName(),
                        "\n\nFormat: " + exportResult.exporter.getName() +
                        (exportResult.encrypted() ? "\n" + I18n.get("info.encrypted") : "")), "");
            } catch (Exception e) {
                logger.error("Export failed", e);
                showError(I18n.get("error.exportFailed"), e.getMessage());
            }
        }
    }
    
    private void exportAsEncryptedZip(ExportDialog.ExportResult exportResult, Path zipFile) throws Exception {
        // Create temporary file for export
        Path tempFile = Files.createTempFile("kortty-export", "." + exportResult.exporter.getFileExtension());
        
        try {
            // Export to temp file
            exportResult.exporter.exportConnections(exportResult.connections, tempFile);
            
            // Create encrypted ZIP
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipFile))) {
                // Set password if provided (Note: Standard Java ZIP doesn't support encryption)
                // We'll use a workaround with encrypted content
                
                ZipEntry entry = new ZipEntry("connections." + exportResult.exporter.getFileExtension());
                zos.putNextEntry(entry);
                
                // Read and encrypt content
                byte[] content = Files.readAllBytes(tempFile);
                byte[] encrypted = encryptContent(content, exportResult.password);
                zos.write(encrypted);
                
                zos.closeEntry();
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
    
    
    private void exportAsGPGEncrypted(ExportDialog.ExportResult exportResult, Path gpgFile) throws Exception {
        // Create temporary file for export
        Path tempFile = Files.createTempFile("kortty-export", "." + exportResult.exporter.getFileExtension());
        
        try {
            // Export to temp file
            exportResult.exporter.exportConnections(exportResult.connections, tempFile);
            
            // Encrypt with GPG
            String keyId = exportResult.gpgKey.getKeyId();
            
            ProcessBuilder pb = new ProcessBuilder(
                "gpg",
                "--encrypt",
                "--recipient", keyId,
                "--trust-model", "always",  // Trust the key automatically
                "--output", gpgFile.toString(),
                tempFile.toString()
            );
            
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read output
            StringBuilder output = new StringBuilder();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(
                    new java.io.InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = process.waitFor();
            
            if (exitCode != 0) {
                throw new Exception(I18n.get("error.gpgEncryptionFailed", exitCode, output.toString(), keyId));
            }
            
            logger.info("File encrypted with GPG using key {}: {}", keyId, gpgFile);
            
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }
    
    private byte[] encryptContent(byte[] data, String password) throws Exception {
        // Use AES encryption
        javax.crypto.Cipher cipher = javax.crypto.Cipher.getInstance("AES/GCM/NoPadding");
        
        // Derive key from password
        java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
        byte[] key = digest.digest(password.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        javax.crypto.spec.SecretKeySpec secretKey = new javax.crypto.spec.SecretKeySpec(key, "AES");
        
        // Generate IV
        byte[] iv = new byte[12];
        new java.security.SecureRandom().nextBytes(iv);
        javax.crypto.spec.GCMParameterSpec gcmSpec = new javax.crypto.spec.GCMParameterSpec(128, iv);
        
        cipher.init(javax.crypto.Cipher.ENCRYPT_MODE, secretKey, gcmSpec);
        byte[] encrypted = cipher.doFinal(data);
        
        // Prepend IV to encrypted data
        byte[] result = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, result, 0, iv.length);
        System.arraycopy(encrypted, 0, result, iv.length, encrypted.length);
        
        return result;
    }
    
    private void showAbout() {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(I18n.get("dialog.about") + " " + KorTTYApplication.getAppName());
        alert.setHeaderText(KorTTYApplication.getAppName() + " v" + KorTTYApplication.getAppVersion());
        alert.setContentText(I18n.get("dialog.aboutText") + "\n\n" +
                I18n.get("dialog.aboutDeveloped") + "\n\n" +
                I18n.get("dialog.aboutJMX") + "\n" +
                "de.kortty:type=SSHClient");
        alert.showAndWait();
    }
    
    private void updateStatus(String message) {
        statusLabel.setText(message);
    }
    
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    public static List<MainWindow> getOpenWindows() {
        return openWindows;
    }
    
    public Stage getStage() {
        return stage;
    }
    
    /**
     * Opens all connections in a group as tabs.
     */
    private void openGroupConnections(String groupName) {
        List<ServerConnection> groupConnections = app.getConfigManager().getConnections().stream()
                .filter(c -> groupName.equals(c.getGroup()))
                .collect(java.util.stream.Collectors.toList());
        
        if (groupConnections.isEmpty()) {
            logger.warn("No connections found in group: {}", groupName);
            return;
        }
        
        logger.info("Opening {} connections from group: {}", groupConnections.size(), groupName);
        
        // Create password vault for retrieving stored passwords
        PasswordVault vault = new PasswordVault(
                app.getMasterPasswordManager().getEncryptionService(),
                app.getMasterPasswordManager().getMasterPassword()
        );
        
        for (ServerConnection conn : groupConnections) {
            // Retrieve password from vault
            String password = vault != null ? vault.retrievePassword(conn) : "";
            
            if (password == null || password.isEmpty()) {
                logger.warn("No password found for connection: {}", conn.getDisplayName());
                // Skip this connection or show password dialog
                continue;
            }
            
            // Increment usage count
            conn.incrementUsageCount();
            
            // Open tab
            TerminalTab tab = new TerminalTab(conn, password);
            tabPane.getTabs().add(tab);
            tab.connect();
            
            // Select the first tab
            if (tabPane.getTabs().size() == 1) {
                tabPane.getSelectionModel().select(tab);
            }
        }
        
        // Save updated usage counts
        try {
            app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
        } catch (Exception e) {
            logger.error("Failed to save usage counts", e);
        }
        
        updateStatus(I18n.get("status.groupOpened", groupName, groupConnections.size()));
        updateDashboard();
    }
    
    
    private void showCredentialManagement() {
        try {
            CredentialManagementDialog dialog = new CredentialManagementDialog(
                app.getCredentialManager(),
                app.getMasterPasswordManager().getMasterPassword()
            );
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show credential management", e);
            showError(I18n.get("error.title"), I18n.get("error.credentialManagementFailed", e.getMessage()));
        }
    }
    
    private void showGPGKeyManagement() {
        try {
            GPGKeyManagementDialog dialog = new GPGKeyManagementDialog(app.getGpgKeyManager());
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show GPG key management", e);
            showError(I18n.get("error.title"), I18n.get("error.gpgKeyManagementFailed", e.getMessage()));
        }
    }
    
    private void showSSHKeyManagement() {
        try {
            SSHKeyManagementDialog dialog = new SSHKeyManagementDialog(
                app.getSSHKeyManager(),
                app.getMasterPasswordManager().getMasterPassword()
            );
            dialog.showAndWait();
        } catch (Exception e) {
            logger.error("Failed to show SSH key management", e);
            showError(I18n.get("error.title"), I18n.get("error.sshKeyManagementFailed", e.getMessage()));
        }
    }
    
    /**
     * Shows SFTP Manager dialog. If a connection is selected, opens it directly.
     * Otherwise, shows a dialog to select a connection.
     */
    private void showSFTPManager() {
        logger.info("showSFTPManager() called - Opening SFTP Manager");
        
        // Check if there's an active connection in the current tab
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        if (selectedTab instanceof TerminalTab terminalTab && terminalTab.isConnected()) {
            // Use current connection - pass temporary key if tab was connected with one
            logger.info("Using active connection: {}", terminalTab.getConnection().getDisplayName());
            de.kortty.model.TemporarySSHKey tempKey = null;
            if (terminalTab.getTemporarySSHKey() != null) {
                // Tab was connected with temp key - use current global key (may have been updated)
                tempKey = de.kortty.core.TemporarySSHKeyManager.getInstance().getCurrentTemporaryKey();
            }
            openSFTPManagerForConnection(terminalTab.getConnection(), tempKey);
            return;
        }
        
        logger.info("No active connection - showing connection selection dialog");
        
        // Show connection selection dialog
        ConnectionSelectionDialog dialog = new ConnectionSelectionDialog(
            stage, 
            app.getConfigManager().getConnections(),
            I18n.get("sftp.selectConnection")
        );
        
        dialog.showAndWait().ifPresent(connection -> {
            logger.info("Connection selected: {}", connection.getDisplayName());
            // Connection selection - do NOT use temp key (only when opened from tab with temp key)
            openSFTPManagerForConnection(connection, null);
        });
    }
    
    /**
     * Opens SFTP Manager for a specific connection.
     * @param connection The connection to use
     * @param temporarySSHKey Optional temporary SSH key (only when opened from tab that used temp key)
     */
    private void openSFTPManagerForConnection(ServerConnection connection, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        try {
            de.kortty.model.TemporarySSHKey keyToUse = temporarySSHKey;
            
            // Check if this connection was originally connected with a temporary SSH key
            boolean wasConnectedWithTempKey = (temporarySSHKey != null) || 
                (connection.getTemporaryKeyContent() != null && !connection.getTemporaryKeyContent().trim().isEmpty());
            
            // When tab was connected with temp key: check if still valid, otherwise ask for new key
            if (wasConnectedWithTempKey) {
                if (keyToUse == null || !keyToUse.isValid()) {
                    // Ask for new temporary SSH key
                    keyToUse = requestNewTemporarySSHKey(connection);
                    if (keyToUse == null) {
                        return; // User cancelled
                    }
                }
            }
            
            // When using valid temporary SSH key, no password needed - skip password dialog
            String password = null;
            if (keyToUse == null) {
                password = getConnectionPassword(connection);
            }
            if (password == null && keyToUse == null) {
                // Ask for password using a simple dialog (only when NOT using temp key)
                Dialog<String> pwdDialog = new Dialog<>();
                pwdDialog.setTitle(I18n.get("dialog.passwordRequired"));
                pwdDialog.setHeaderText(I18n.get("dialog.passwordFor", connection.getDisplayName()));
                pwdDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                
                PasswordField passwordField = new PasswordField();
                passwordField.setPromptText(I18n.get("common.password"));
                VBox content = new VBox(10);
                content.setPadding(new javafx.geometry.Insets(20));
                content.getChildren().addAll(new Label(I18n.get("dialog.pleaseEnterPassword")), passwordField);
                pwdDialog.getDialogPane().setContent(content);
                
                pwdDialog.setResultConverter(buttonType -> {
                    if (buttonType == ButtonType.OK) {
                        return passwordField.getText();
                    }
                    return null;
                });
                
                Optional<String> result = pwdDialog.showAndWait();
                if (result.isPresent() && !result.get().isEmpty()) {
                    password = result.get();
                } else {
                    return; // User cancelled
                }
            }
            
            // Use empty password when temp key auth - SFTPSession uses key, not password
            String passwordToUse = (keyToUse != null && keyToUse.isValid()) ? "" : (password != null ? password : "");
            
            // Open SFTP Manager as a TAB instead of a dialog
            openSFTPManagerTab(connection, passwordToUse, keyToUse);
        } catch (Exception e) {
            logger.error("Failed to open SFTP manager", e);
            showError(I18n.get("error.title"), I18n.get("error.sftpManagerFailed", e.getMessage()));
        }
    }
    
    /**
     * Requests a new temporary SSH key from the user.
     * Shows a dialog where the user can enter a new valid temporary SSH key.
     * @param connection The connection that requires a temporary SSH key
     * @return The new temporary SSH key, or null if cancelled
     */
    private de.kortty.model.TemporarySSHKey requestNewTemporarySSHKey(ServerConnection connection) {
        Dialog<de.kortty.model.TemporarySSHKey> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.tempKeyRequired"));
        dialog.setHeaderText(I18n.get("sftp.tempKeyRequiredMessage"));
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.setResizable(true);
        
        // Create content
        VBox content = new VBox(10);
        content.setPadding(new javafx.geometry.Insets(20));
        content.setPrefWidth(500);
        
        Label infoLabel = new Label(I18n.get("sftp.enterNewTempKey"));
        
        TextArea keyArea = new TextArea();
        keyArea.setPromptText("-----BEGIN OPENSSH PRIVATE KEY-----\n...\n-----END OPENSSH PRIVATE KEY-----");
        keyArea.setPrefRowCount(8);
        keyArea.setWrapText(true);
        
        // Expiration spinner
        HBox expirationBox = new HBox(10);
        expirationBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        Label expirationLabel = new Label(I18n.get("quickConnect.expirationMinutes"));
        Spinner<Integer> expirationSpinner = new Spinner<>(1, 1440, 60);
        expirationSpinner.setEditable(true);
        expirationSpinner.setPrefWidth(100);
        expirationBox.getChildren().addAll(expirationLabel, expirationSpinner);
        
        content.getChildren().addAll(infoLabel, keyArea, expirationBox);
        dialog.getDialogPane().setContent(content);
        
        // Disable OK button until key is entered
        Button okButton = (Button) dialog.getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        keyArea.textProperty().addListener((obs, old, newVal) -> {
            okButton.setDisable(newVal == null || newVal.trim().isEmpty());
        });
        
        dialog.setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                String keyContent = keyArea.getText().trim();
                if (!keyContent.isEmpty()) {
                    long expirationMinutes = expirationSpinner.getValue();
                    de.kortty.model.TemporarySSHKey newKey = de.kortty.core.TemporarySSHKeyManager.getInstance()
                        .storeTemporaryKey(keyContent, expirationMinutes);
                    
                    // Update connection with new temporary key
                    connection.setTemporaryKeyContent(keyContent);
                    connection.setTemporaryKeyExpirationMinutes(expirationMinutes);
                    connection.setPrivateKeyPath("TEMPORARY:" + keyContent);
                    
                    return newKey;
                }
            }
            return null;
        });
        
        return dialog.showAndWait().orElse(null);
    }
    
    /**
     * Opens the SFTP Manager in a new tab.
     * @param connection The connection to use
     * @param password The password (or empty for temp key auth)
     * @param temporarySSHKey Optional temporary SSH key
     */
    private void openSFTPManagerTab(ServerConnection connection, String password, de.kortty.model.TemporarySSHKey temporarySSHKey) {
        // Check if SFTP tab for this connection already exists
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof SFTPManagerTab sftpTab) {
                if (sftpTab.getConnection().getId() != null && 
                    sftpTab.getConnection().getId().equals(connection.getId())) {
                    // Tab already exists - select it
                    tabPane.getSelectionModel().select(tab);
                    return;
                }
            }
        }
        
        // Get auto-close timeout from global settings (0 = disabled)
        int autoCloseMinutes = 0;
        try {
            var globalSettings = app.getGlobalSettingsManager().getSettings();
            if (globalSettings != null && globalSettings.getSftpAutoCloseMinutes() != null) {
                autoCloseMinutes = globalSettings.getSftpAutoCloseMinutes();
            }
        } catch (Exception e) {
            logger.debug("Could not get SFTP timeout setting: {}", e.getMessage());
        }
        
        // Create new SFTP tab
        SFTPManagerTab sftpTab = new SFTPManagerTab(app, connection, password, temporarySSHKey, autoCloseMinutes);
        
        // Insert before the "+" tab (if exists)
        int insertIndex = tabPane.getTabs().size();
        for (int i = 0; i < tabPane.getTabs().size(); i++) {
            if ("+".equals(tabPane.getTabs().get(i).getText())) {
                insertIndex = i;
                break;
            }
        }
        
        tabPane.getTabs().add(insertIndex, sftpTab);
        tabPane.getSelectionModel().select(sftpTab);
        
        logger.info("Opened SFTP Manager tab for: {}", connection.getDisplayName());
    }

    
    /**
     * Retrieves password for a connection, either from credential store or from encrypted password.
     * This ensures password changes in credential management are immediately reflected.
     */
    private String getConnectionPassword(ServerConnection connection) {
        // Try credential store first (if credentialId is set)
        if (connection.getCredentialId() != null) {
            try {
                java.util.Optional<de.kortty.model.StoredCredential> credential = 
                    app.getCredentialManager().findCredentialById(connection.getCredentialId());
                
                if (credential.isPresent()) {
                    String password = app.getCredentialManager().getPassword(
                        credential.get(), 
                        app.getMasterPasswordManager().getMasterPassword()
                    );
                    if (password != null) {
                        logger.debug("Using password from credential store for: {}", connection.getDisplayName());
                        return password;
                    }
                }
            } catch (Exception e) {
                logger.warn("Failed to retrieve password from credential store: {}", e.getMessage());
                // Fall back to stored password
            }
        }
        
        // Fall back to stored encrypted password in connection
        PasswordVault vault = new PasswordVault(
            app.getMasterPasswordManager().getEncryptionService(),
            app.getMasterPasswordManager().getMasterPassword()
        );
        return vault.retrievePassword(connection);
    }
    
    /**
     * Duplicates a tab with the same connection details.
     * The new tab is inserted directly to the right of the source tab.
     */
    private void duplicateTab(TerminalTab sourceTab) {
        ServerConnection connection = sourceTab.getConnection();
        String password = getConnectionPassword(connection);
        
        if (password == null) {
            // Password not available, show dialog
            TextInputDialog pwDialog = new TextInputDialog();
            pwDialog.setTitle(I18n.get("dialog.passwordRequired"));
            pwDialog.setHeaderText(I18n.get("dialog.passwordFor", connection.getDisplayName()));
            pwDialog.setContentText(I18n.get("common.password") + ":");
            pwDialog.getEditor().setPromptText(I18n.get("dialog.enterPassword"));
            
            pwDialog.showAndWait().ifPresent(pw -> {
                if (pw != null && !pw.trim().isEmpty()) {
                    createDuplicateTab(sourceTab, connection, pw.trim());
                }
            });
        } else {
            createDuplicateTab(sourceTab, connection, password);
        }
    }
    
    /**
     * Creates a duplicated tab directly to the right of the source tab.
     */
    private void createDuplicateTab(TerminalTab sourceTab, ServerConnection connection, String password) {
        try {
            // Find the position of the source tab
            int sourceIndex = tabPane.getTabs().indexOf(sourceTab);
            if (sourceIndex == -1) {
                logger.warn("Source tab not found in tab pane");
                return;
            }
            
            // Create new tab with the same connection
            TerminalTab newTab = new TerminalTab(connection, password);
            newTab.setOnClosed(e -> {
                updateDashboard();
                organizeTabsByGroup();
                updateAllTabContextMenus();
            });
            
            // Setup context menu
            setupTabContextMenu(newTab);
            
            // Take over the tab group from the source tab (if present)
            String sourceGroup = sourceTab.getGroup();
            if (sourceGroup != null && !sourceGroup.trim().isEmpty()) {
                newTab.setGroup(sourceGroup);
            }
            
            // Find the position of the "+" tab
            int plusTabIndex = -1;
            for (int i = 0; i < tabPane.getTabs().size(); i++) {
                Tab tab = tabPane.getTabs().get(i);
                if (tab.getText().equals("+")) {
                    plusTabIndex = i;
                    break;
                }
            }
            
            // Insert the new tab directly to the right of the source tab
            // Consider that the "+" tab should always be at the end
            int insertIndex = sourceIndex + 1;
            if (plusTabIndex != -1 && insertIndex > plusTabIndex) {
                insertIndex = plusTabIndex;
            }
            
            tabPane.getTabs().add(insertIndex, newTab);
            tabPane.getSelectionModel().select(newTab);
            
            // Update dashboard und context menus
            updateDashboard();
            updateAllTabContextMenus();
            
            // Verbinde den neuen Tab
            new Thread(() -> {
                try {
                    newTab.connect();
                    
                    // Set callback to update dashboard and reset tab color when connection succeeds
                    // This callback will be called AFTER TerminalTab.connect() sets its own callback,
                    // so we need to ensure the color is reset
                    newTab.getTerminalView().setOnConnectedCallback(() -> {
                        Platform.runLater(() -> {
                            // Update tab title
                            newTab.updateTabTitle();
                            // Reset tab color (remove yellow connecting color)
                            newTab.setStyle("");
                            // Update status and dashboard
                            updateStatus(I18n.get("status.connectedTo", connection.getDisplayName()));
                            updateDashboard();
                        });
                    });
                } catch (Exception e) {
                    logger.error("Failed to connect duplicated tab", e);
                }
            }).start();
            
            logger.info("Tab duplicated: {} -> {}", sourceTab.getConnection().getDisplayName(), 
                        newTab.getConnection().getDisplayName());
        } catch (Exception e) {
            logger.error("Failed to duplicate tab", e);
            showError(I18n.get("error.title"), I18n.get("error.tabDuplicateFailed", e.getMessage()));
        }
    }

    
    /**
     * Creates an encrypted backup of all settings.
     */
    private void createBackup() {
        // Show directory chooser
        javafx.stage.DirectoryChooser dirChooser = new javafx.stage.DirectoryChooser();
        dirChooser.setTitle(I18n.get("backup.selectDestination"));
        
        // Use last backup path as initial directory if available
        String lastPath = app.getGlobalSettingsManager().getSettings().getLastBackupPath();
        if (lastPath != null) {
            java.io.File lastDir = new java.io.File(lastPath);
            if (lastDir.exists() && lastDir.isDirectory()) {
                dirChooser.setInitialDirectory(lastDir);
            }
        } else {
            // Default to user home
            dirChooser.setInitialDirectory(new java.io.File(System.getProperty("user.home")));
        }
        
        java.io.File selectedDir = dirChooser.showDialog(stage);
        if (selectedDir == null) {
            return; // User cancelled
        }
        
        // Create backup in background
        javafx.concurrent.Task<java.nio.file.Path> backupTask = new javafx.concurrent.Task<>() {
            @Override
            protected java.nio.file.Path call() throws Exception {
                updateMessage(I18n.get("backup.creating"));
                return app.getBackupManager().createBackup(
                    selectedDir.toPath(),
                    app.getCredentialManager(),
                    app.getGpgKeyManager(),
                    app.getMasterPasswordManager().getMasterPassword()
                );
            }
        };
        
        backupTask.setOnSucceeded(e -> {
            java.nio.file.Path backupFile = backupTask.getValue();
            try {
                long fileSize = java.nio.file.Files.size(backupFile) / 1024; // KB
                
                // Determine encryption description
                String encryptionDesc = I18n.get("backup.encryption.unknown");
                if (app.getGlobalSettingsManager().getSettings().getBackupEncryptionType() 
                    == de.kortty.model.GlobalSettings.BackupEncryptionType.PASSWORD) {
                    encryptionDesc = I18n.get("backup.encryption.password");
                } else if (app.getGlobalSettingsManager().getSettings().getBackupEncryptionType() 
                           == de.kortty.model.GlobalSettings.BackupEncryptionType.GPG) {
                    encryptionDesc = I18n.get("backup.encryption.gpg");
                }
                
                Alert success = new Alert(Alert.AlertType.INFORMATION);
                success.setTitle(I18n.get("backup.created"));
                success.setHeaderText(I18n.get("backup.createdSuccess"));
                success.setContentText(String.format(
                    I18n.get("backup.createdMessage"),
                    backupFile.getFileName(),
                    fileSize,
                    encryptionDesc
                ));
                success.showAndWait();
                
                // Save global settings (updated by BackupManager)
                app.getGlobalSettingsManager().save();
                
                updateStatus(I18n.get("backup.createdSuccess") + ": " + backupFile.getFileName());
                
            } catch (Exception ex) {
                logger.error("Failed to get backup file size", ex);
            }
        });
        
        backupTask.setOnFailed(e -> {
            Throwable ex = backupTask.getException();
            logger.error("Backup creation failed", ex);
            
            Alert error = new Alert(Alert.AlertType.ERROR);
            error.setTitle(I18n.get("error.title"));
            error.setHeaderText(I18n.get("backup.failed"));
            error.setContentText(I18n.get("backup.failedMessage") + "\n" + ex.getMessage());
            error.showAndWait();
        });
        
        // Update status and run task
        updateStatus(I18n.get("backup.creating"));
        
        Thread thread = new Thread(backupTask);
        thread.setDaemon(true);
        thread.start();
    }
    
    /**
     * Imports a backup from an encrypted backup file.
     */
    private void importBackup() {
        // Show file chooser
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle(I18n.get("backup.import.selectFile"));
        
        // Add filters for backup files
        fileChooser.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("Backup Files", "*.zip", "*.gpg")
        );
        fileChooser.getExtensionFilters().add(
            new javafx.stage.FileChooser.ExtensionFilter("All Files", "*.*")
        );
        
        // Use last backup path as initial directory if available
        String lastPath = app.getGlobalSettingsManager().getSettings().getLastBackupPath();
        if (lastPath != null) {
            java.io.File lastDir = new java.io.File(lastPath);
            if (lastDir.exists() && lastDir.isDirectory()) {
                fileChooser.setInitialDirectory(lastDir);
            }
        } else {
            // Default to user home
            fileChooser.setInitialDirectory(new java.io.File(System.getProperty("user.home")));
        }
        
        java.io.File backupFile = fileChooser.showOpenDialog(stage);
        if (backupFile == null) {
            return; // User cancelled
        }
        
        // Check if file is GPG-encrypted or password-encrypted
        String fileName = backupFile.getName().toLowerCase();
        boolean isGPGEncrypted = fileName.endsWith(".gpg");
        final String[] password = {null}; // Use array to allow modification in lambda
        
        if (!isGPGEncrypted) {
            // Ask for password
            javafx.scene.control.TextInputDialog passwordDialog = new javafx.scene.control.TextInputDialog();
            passwordDialog.setTitle(I18n.get("backup.import.password.title"));
            passwordDialog.setHeaderText(I18n.get("backup.import.password.header"));
            passwordDialog.setContentText(I18n.get("backup.import.password.content"));
            
            java.util.Optional<String> passwordResult = passwordDialog.showAndWait();
            if (!passwordResult.isPresent() || passwordResult.get().isEmpty()) {
                return; // User cancelled or entered empty password
            }
            password[0] = passwordResult.get();
        }
        
        // Ask if existing files should be overwritten
        javafx.scene.control.Alert overwriteDialog = new javafx.scene.control.Alert(
            javafx.scene.control.Alert.AlertType.CONFIRMATION
        );
        overwriteDialog.setTitle(I18n.get("backup.import.overwrite.title"));
        overwriteDialog.setHeaderText(I18n.get("backup.import.overwrite.header"));
        overwriteDialog.setContentText(I18n.get("backup.import.overwrite.content"));
        
        java.util.Optional<javafx.scene.control.ButtonType> overwriteResult = overwriteDialog.showAndWait();
        final boolean overwriteExisting = overwriteResult.isPresent() && 
            overwriteResult.get() == javafx.scene.control.ButtonType.OK;
        
        // Import backup in background
        final java.nio.file.Path backupFilePath = backupFile.toPath();
        javafx.concurrent.Task<Integer> importTask = new javafx.concurrent.Task<>() {
            @Override
            protected Integer call() throws Exception {
                updateMessage(I18n.get("backup.import.importing"));
                return app.getBackupManager().importBackup(
                    backupFilePath,
                    password[0],
                    overwriteExisting
                );
            }
        };
        
        importTask.setOnSucceeded(e -> {
            int filesImported = importTask.getValue();
            
            Alert success = new Alert(Alert.AlertType.INFORMATION);
            success.setTitle(I18n.get("backup.import.success"));
            success.setHeaderText(I18n.get("backup.import.successHeader"));
            success.setContentText(String.format(
                I18n.get("backup.import.successMessage"),
                filesImported
            ));
            success.showAndWait();
            
            // Reload all managers to reflect imported data
            try {
                app.getConfigManager().load(app.getMasterPasswordManager().getDerivedKey());
                app.getCredentialManager().load();
                app.getGpgKeyManager().load();
                app.getGlobalSettingsManager().load();
            } catch (Exception ex) {
                logger.error("Failed to reload managers after backup import", ex);
            }
            
            updateStatus(I18n.get("backup.import.successHeader") + ": " + filesImported + " " + I18n.get("backup.import.files"));
        });
        
        importTask.setOnFailed(e -> {
            Throwable ex = importTask.getException();
            logger.error("Backup import failed", ex);
            
            Alert error = new Alert(Alert.AlertType.ERROR);
            error.setTitle(I18n.get("error.title"));
            error.setHeaderText(I18n.get("backup.import.failed"));
            error.setContentText(I18n.get("backup.import.failedMessage") + "\n" + ex.getMessage());
            error.showAndWait();
        });
        
        // Update status and run task
        updateStatus(I18n.get("backup.import.importing"));
        
        Thread thread = new Thread(importTask);
        thread.setDaemon(true);
        thread.start();
    }
    
    /**
     * Sets up context menu for a terminal tab to assign/change groups.
     */
    private void setupTabContextMenu(TerminalTab terminalTab) {
        ContextMenu contextMenu = new ContextMenu();
        
        // Duplicate menu item
        MenuItem duplicateItem = new MenuItem("Duplizieren");
        duplicateItem.setOnAction(e -> {
            duplicateTab(terminalTab);
        });
        contextMenu.getItems().add(duplicateItem);
        
        // Separator
        contextMenu.getItems().add(new SeparatorMenuItem());
        
        // Get all available groups
        List<String> groups = getAllGroups();
        String currentGroup = terminalTab.getGroup();
        
        // Menu item to remove from group
        MenuItem removeGroupItem = new MenuItem("Keine Gruppe");
        removeGroupItem.setOnAction(e -> {
            terminalTab.setGroup(null);
            organizeTabsByGroup();
            updateAllTabContextMenus(); // Update all context menus to reflect changes
            updateDashboard(); // Refresh dashboard to show group changes
        });
        if (currentGroup == null || currentGroup.trim().isEmpty()) {
            removeGroupItem.setDisable(true);
        }
        contextMenu.getItems().add(removeGroupItem);
        
        // Separator
        contextMenu.getItems().add(new SeparatorMenuItem());
        
        // Menu items for each group
        if (!groups.isEmpty()) {
            for (String group : groups) {
                MenuItem groupItem = new MenuItem(group);
                groupItem.setOnAction(e -> {
                    terminalTab.setGroup(group);
                    organizeTabsByGroup();
                    updateAllTabContextMenus(); // Update all context menus to reflect changes
                    updateDashboard(); // Refresh dashboard to show group changes
                });
                if (group.equals(currentGroup)) {
                    groupItem.setDisable(true);
                }
                contextMenu.getItems().add(groupItem);
            }
            contextMenu.getItems().add(new SeparatorMenuItem());
        }
        
        // Menu item to create new group
        MenuItem newGroupItem = new MenuItem(I18n.get("group.new"));
        newGroupItem.setOnAction(e -> {
            TextInputDialog dialog = new TextInputDialog();
            dialog.setTitle(I18n.get("group.new"));
            dialog.setHeaderText(I18n.get("group.enterName"));
            dialog.setContentText(I18n.get("group.name") + ":");
            dialog.getEditor().setPromptText(I18n.get("group.nameExample"));
            
            dialog.showAndWait().ifPresent(groupName -> {
                if (groupName != null && !groupName.trim().isEmpty()) {
                    String trimmedName = groupName.trim();
                    terminalTab.setGroup(trimmedName);
                    organizeTabsByGroup();
                    updateAllTabContextMenus(); // Update all context menus to reflect new group
                    updateDashboard(); // Refresh dashboard to show new group
                }
            });
        });
        contextMenu.getItems().add(newGroupItem);
        
        // Menu item to rename current group (if tab has a group)
        if (currentGroup != null && !currentGroup.trim().isEmpty()) {
            contextMenu.getItems().add(new SeparatorMenuItem());
            MenuItem renameGroupItem = new MenuItem(I18n.get("group.rename"));
            renameGroupItem.setOnAction(e -> {
                TextInputDialog dialog = new TextInputDialog(currentGroup);
                dialog.setTitle(I18n.get("group.rename"));
                dialog.setHeaderText(I18n.get("group.enterNewName"));
                dialog.setContentText(I18n.get("group.newName") + ":");
                
                dialog.showAndWait().ifPresent(newGroupName -> {
                    if (newGroupName != null && !newGroupName.trim().isEmpty()) {
                        String trimmedName = newGroupName.trim();
                        if (!trimmedName.equals(currentGroup)) {
                            renameGroupForAllTabs(currentGroup, trimmedName);
                            organizeTabsByGroup();
                            updateAllTabContextMenus(); // Update all context menus
                            updateDashboard(); // Refresh dashboard to show renamed group
                        }
                    }
                });
            });
            contextMenu.getItems().add(renameGroupItem);
        }
        
        terminalTab.setContextMenu(contextMenu);
    }
    
    /**
     * Gets all unique group names from open tabs (not from connections).
     */
    private List<String> getAllGroups() {
        List<String> groups = new ArrayList<>();
        // Get groups from open tabs only (tab groups, not connection groups)
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                String group = terminalTab.getGroup();
                if (group != null && !group.trim().isEmpty() && !groups.contains(group)) {
                    groups.add(group);
                }
            }
        }
        groups.sort(String::compareToIgnoreCase);
        return groups;
    }
    
    /**
     * Renames a group for all tabs that have this group.
     */
    private void renameGroupForAllTabs(String oldGroupName, String newGroupName) {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                String group = terminalTab.getGroup();
                if (oldGroupName.equals(group)) {
                    terminalTab.setGroup(newGroupName);
                }
            }
        }
    }
    
    /**
     * Updates context menus for all tabs to reflect current group state.
     */
    private void updateAllTabContextMenus() {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                setupTabContextMenu(terminalTab);
            }
        }
    }
    
    /**
     * Inserts a tab in the correct position based on group ordering.
     * Tabs are organized: no group first, then grouped tabs alphabetically by group name.
     */
    private void insertTabInGroupOrder(TerminalTab newTab) {
        String newTabGroup = newTab.getGroup();
        if (newTabGroup == null || newTabGroup.trim().isEmpty()) {
            newTabGroup = null;
        }
        
        int plusTabIndex = tabPane.getTabs().size() - 1; // "+" tab is always last
        
        // Find insertion point
        int insertIndex = plusTabIndex;
        for (int i = 0; i < plusTabIndex; i++) {
            Tab tab = tabPane.getTabs().get(i);
            if (tab instanceof TerminalTab terminalTab) {
                String tabGroup = terminalTab.getGroup();
                if (tabGroup == null || tabGroup.trim().isEmpty()) {
                    tabGroup = null;
                }
                
                // Compare groups
                if (newTabGroup == null) {
                    // New tab has no group - insert before first grouped tab
                    if (tabGroup != null) {
                        insertIndex = i;
                        break;
                    }
                } else {
                    // New tab has group - insert after last tab with same group or before first tab with larger group
                    if (tabGroup != null && tabGroup.compareToIgnoreCase(newTabGroup) > 0) {
                        insertIndex = i;
                        break;
                    } else if (tabGroup != null && tabGroup.equals(newTabGroup)) {
                        // Continue until we find the end of this group
                        insertIndex = i + 1;
                    }
                }
            }
        }
        
        tabPane.getTabs().add(insertIndex, newTab);
    }
    
    /**
     * Reorganizes all tabs by group order.
     * Tabs without group come first, then grouped tabs sorted alphabetically by group name.
     */
    private void organizeTabsByGroup() {
        // Get all terminal tabs (excluding "+" tab)
        List<TerminalTab> terminalTabs = new ArrayList<>();
        Tab plusTab = null;
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                terminalTabs.add(terminalTab);
            } else if ("+".equals(tab.getText())) {
                plusTab = tab;
            }
        }
        
        // Sort tabs: no group first, then by group name alphabetically
        terminalTabs.sort((t1, t2) -> {
            String g1 = t1.getGroup();
            String g2 = t2.getGroup();
            
            if (g1 == null || g1.trim().isEmpty()) {
                g1 = null;
            }
            if (g2 == null || g2.trim().isEmpty()) {
                g2 = null;
            }
            
            if (g1 == null && g2 == null) {
                return 0; // Keep original order for tabs without group
            }
            if (g1 == null) {
                return -1; // No group comes first
            }
            if (g2 == null) {
                return 1; // No group comes first
            }
            
            return g1.compareToIgnoreCase(g2);
        });
        
        // Clear all tabs
        Tab selectedTab = tabPane.getSelectionModel().getSelectedItem();
        tabPane.getTabs().clear();
        
        // Re-add sorted tabs
        for (TerminalTab tab : terminalTabs) {
            tabPane.getTabs().add(tab);
            setupTabContextMenu(tab); // Re-setup context menu
        }
        
        // Re-add "+" tab at the end
        if (plusTab != null) {
            tabPane.getTabs().add(plusTab);
        }
        
        // Restore selection
        if (selectedTab != null) {
            tabPane.getSelectionModel().select(selectedTab);
        }
    }

}