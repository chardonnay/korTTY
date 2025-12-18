package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ProjectManager;
import de.kortty.core.SSHSession;
import de.kortty.core.SessionManager;
import de.kortty.model.*;
import de.kortty.persistence.importer.ConnectionImporter;
import de.kortty.persistence.importer.MTPuTTYImporter;
import de.kortty.persistence.importer.MobaXTermImporter;
import de.kortty.persistence.exporter.ConnectionExporter;
import de.kortty.persistence.exporter.KorTTYExporter;
import de.kortty.persistence.exporter.MTPuTTYExporter;
import de.kortty.persistence.exporter.MobaXTermExporter;
import de.kortty.security.PasswordVault;
import javafx.application.Platform;
import javafx.geometry.Orientation;
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
    
    private static final Logger logger = LoggerFactory.getLogger(MainWindow.class);
    
    private final Stage stage;
    private final BorderPane root;
    private final TabPane tabPane;
    private final Label statusLabel;
    private final SplitPane splitPane;
    private DashboardView dashboardView;
    private boolean dashboardVisible = false;
    
    private final KorTTYApplication app;
    private final SessionManager sessionManager;
    private final ProjectManager projectManager;
    private final List<ConnectionImporter> importers;
    
    private static final List<MainWindow> openWindows = new ArrayList<>();
    
    public MainWindow(Stage stage) {
        this.stage = stage;
        this.app = KorTTYApplication.getInstance();
        this.sessionManager = app.getSessionManager();
        this.projectManager = new ProjectManager(KorTTYApplication.getConfigDirectory());
        
        // Initialize importers
        this.importers = List.of(new MTPuTTYImporter(), new MobaXTermImporter());
        
        // Create UI components
        this.root = new BorderPane();
        this.tabPane = new TabPane();
        this.statusLabel = new Label("Bereit");
        this.splitPane = new SplitPane();
        
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
        newTabButton.setOnSelectionChanged(e -> {
            if (newTabButton.isSelected()) {
                Platform.runLater(() -> {
                    tabPane.getSelectionModel().selectPrevious();
                    showQuickConnect();
                });
            }
        });
        tabPane.getTabs().add(newTabButton);
        
        // Status bar
        VBox statusBar = new VBox(statusLabel);
        statusBar.setStyle("-fx-padding: 5; -fx-background-color: #2d2d2d;");
        statusLabel.setStyle("-fx-text-fill: #cccccc;");
        
        // Split pane for dashboard
        splitPane.setOrientation(Orientation.HORIZONTAL);
        splitPane.getItems().add(tabPane);
        
        root.setCenter(splitPane);
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
            if (!confirmClose()) {
                e.consume();
            } else {
                closeAllTabs();
                openWindows.remove(this);
                
                // If this was the last window, exit the application
                if (openWindows.isEmpty()) {
                    logger.info("Last window closed, exiting application");
                    Platform.exit();
                    System.exit(0);
                }
            }
        });
    }
    
    private void setupMenuBar() {
        MenuBar menuBar = new MenuBar();
        
        // Datei Menu
        Menu fileMenu = new Menu("Datei");
        
        MenuItem newWindow = new MenuItem("Neues Fenster");
        newWindow.setAccelerator(new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        newWindow.setOnAction(e -> openNewWindow());
        
        MenuItem newTab = new MenuItem("Neuer Tab");
        newTab.setAccelerator(new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN));
        newTab.setOnAction(e -> showQuickConnect());
        
        MenuItem openProject = new MenuItem("Projekt öffnen...");
        openProject.setAccelerator(new KeyCodeCombination(KeyCode.O, KeyCombination.SHORTCUT_DOWN));
        openProject.setOnAction(e -> openProject());
        
        MenuItem saveProject = new MenuItem("Projekt speichern...");
        saveProject.setAccelerator(new KeyCodeCombination(KeyCode.S, KeyCombination.SHORTCUT_DOWN));
        saveProject.setOnAction(e -> saveProject());
        
        MenuItem closeTab = new MenuItem("Tab schließen");
        closeTab.setAccelerator(new KeyCodeCombination(KeyCode.W, KeyCombination.SHORTCUT_DOWN));
        closeTab.setOnAction(e -> closeCurrentTab());
        
        MenuItem quit = new MenuItem("Beenden");
        quit.setAccelerator(new KeyCodeCombination(KeyCode.Q, KeyCombination.SHORTCUT_DOWN));
        quit.setOnAction(e -> Platform.exit());
        
        fileMenu.getItems().addAll(newWindow, newTab, new SeparatorMenuItem(),
                openProject, saveProject, new SeparatorMenuItem(),
                closeTab, new SeparatorMenuItem(), quit);
        
        // Bearbeiten Menu
        Menu editMenu = new Menu("Bearbeiten");
        
        MenuItem copy = new MenuItem("Kopieren");
        copy.setAccelerator(new KeyCodeCombination(KeyCode.C, KeyCombination.SHORTCUT_DOWN));
        copy.setOnAction(e -> copyFromTerminal());
        
        MenuItem paste = new MenuItem("Einfügen");
        paste.setAccelerator(new KeyCodeCombination(KeyCode.V, KeyCombination.SHORTCUT_DOWN));
        paste.setOnAction(e -> pasteToTerminal());
        
        MenuItem settings = new MenuItem("Einstellungen...");
        settings.setAccelerator(new KeyCodeCombination(KeyCode.COMMA, KeyCombination.SHORTCUT_DOWN));
        settings.setOnAction(e -> showSettings());
        
        editMenu.getItems().addAll(copy, paste, new SeparatorMenuItem(), settings);
        
        // Ansicht Menu
        Menu viewMenu = new Menu("Ansicht");
        
        CheckMenuItem showDashboard = new CheckMenuItem("Dashboard anzeigen");
        showDashboard.setAccelerator(new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        showDashboard.setOnAction(e -> toggleDashboard(showDashboard.isSelected()));
        
        MenuItem zoomIn = new MenuItem("Vergrößern");
        zoomIn.setAccelerator(new KeyCodeCombination(KeyCode.PLUS, KeyCombination.ALT_DOWN));
        zoomIn.setOnAction(e -> zoomTerminal(1));
        
        MenuItem zoomOut = new MenuItem("Verkleinern");
        zoomOut.setAccelerator(new KeyCodeCombination(KeyCode.MINUS, KeyCombination.ALT_DOWN));
        zoomOut.setOnAction(e -> zoomTerminal(-1));
        
        MenuItem resetZoom = new MenuItem("Zoom zurücksetzen");
        resetZoom.setAccelerator(new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.ALT_DOWN));
        resetZoom.setOnAction(e -> resetTerminalZoom());
        
        MenuItem fullscreen = new MenuItem("Vollbild");
        fullscreen.setAccelerator(new KeyCodeCombination(KeyCode.F11));
        fullscreen.setOnAction(e -> stage.setFullScreen(!stage.isFullScreen()));
        
        viewMenu.getItems().addAll(showDashboard, new SeparatorMenuItem(),
                zoomIn, zoomOut, resetZoom, new SeparatorMenuItem(), fullscreen);
        
        // Verbindungen Menu
        Menu connectionsMenu = new Menu("Verbindungen");
        
        MenuItem quickConnect = new MenuItem("Schnellverbindung...");
        quickConnect.setOnAction(e -> showQuickConnect());
        
        MenuItem manageConnections = new MenuItem("Verbindungen verwalten...");
        manageConnections.setOnAction(e -> showConnectionManager());
        
        MenuItem importConnections = new MenuItem("Importieren...");
        importConnections.setOnAction(e -> importConnections());
        
        MenuItem exportConnections = new MenuItem("Exportieren...");
        exportConnections.setOnAction(e -> exportConnections());
        
        connectionsMenu.getItems().addAll(quickConnect, manageConnections,
                new SeparatorMenuItem(), importConnections, exportConnections);
        
        // Hilfe Menu
        Menu helpMenu = new Menu("Hilfe");
        
        MenuItem about = new MenuItem("Über " + KorTTYApplication.getAppName());
        about.setOnAction(e -> showAbout());
        
        helpMenu.getItems().add(about);
        
        menuBar.getMenus().addAll(fileMenu, editMenu, viewMenu, connectionsMenu, helpMenu);
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
        stage.show();
    }
    
    /**
     * Opens a new SSH connection in a new tab.
     */
    public void openConnection(ServerConnection connection, String password) {
        try {
            // Create terminal tab with JediTermFX
            TerminalTab terminalTab = new TerminalTab(connection, password);
            terminalTab.setOnClosed(e -> {
                updateDashboard();
            });
            
            // Insert before the "+" tab
            int insertIndex = tabPane.getTabs().size() - 1;
            tabPane.getTabs().add(insertIndex, terminalTab);
            tabPane.getSelectionModel().select(terminalTab);
            
            // Connect in background
            new Thread(() -> {
                try {
                    terminalTab.connect();
                    Platform.runLater(() -> {
                        updateStatus("Verbunden mit " + connection.getDisplayName());
                        updateDashboard();
                    });
                } catch (Exception ex) {
                    logger.error("Connection failed", ex);
                    Platform.runLater(() -> {
                        terminalTab.onConnectionFailed(ex.getMessage());
                        updateStatus("Verbindung fehlgeschlagen: " + ex.getMessage());
                    });
                }
            }).start();
            
        } catch (Exception e) {
            logger.error("Failed to create session", e);
            showError("Verbindungsfehler", "Konnte Sitzung nicht erstellen: " + e.getMessage());
        }
    }
    
    private void showQuickConnect() {
        // Create password vault for retrieving stored passwords
        PasswordVault vault = new PasswordVault(
                app.getMasterPasswordManager().getEncryptionService(),
                app.getMasterPasswordManager().getMasterPassword()
        );
        
        // Pass saved connections and vault to the dialog
        QuickConnectDialog dialog = new QuickConnectDialog(stage, app.getConfigManager().getConnections(), vault, 10);
        dialog.showAndWait().ifPresent(result -> {
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
            openConnection(result.connection(), password);
        });
    }
    
    private void showConnectionManager() {
        ConnectionManagerDialog dialog = new ConnectionManagerDialog(stage, app.getConfigManager());
        dialog.showAndWait().ifPresent(connection -> {
            // Ask for password if needed
            PasswordVault vault = new PasswordVault(
                    app.getMasterPasswordManager().getEncryptionService(),
                    app.getMasterPasswordManager().getMasterPassword()
            );
            
            String password = vault.retrievePassword(connection);
            if (password == null) {
                TextInputDialog pwDialog = new TextInputDialog();
                pwDialog.setTitle("Passwort erforderlich");
                pwDialog.setHeaderText("Passwort für " + connection.getDisplayName());
                pwDialog.setContentText("Passwort:");
                pwDialog.getEditor().setPromptText("Passwort eingeben");
                
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
        SettingsDialog dialog = new SettingsDialog(stage, app.getConfigManager());
        
        // Add listener to apply settings changes immediately to all open terminals
        dialog.addChangeListener(() -> {
            logger.info("Settings changed, notifying user");
            Platform.runLater(() -> {
                updateStatus("Globale Einstellungen gespeichert - wirksam bei neuen Verbindungen");
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
            if (tab instanceof TerminalTab) {
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
        alert.setTitle("Fenster schließen");
        alert.setHeaderText("Aktive SSH-Verbindungen");
        alert.setContentText("Es gibt " + activeConnections + " aktive SSH-Verbindung(en).\nWirklich schließen?");
        
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
    
    private void toggleDashboard(boolean show) {
        if (show && !dashboardVisible) {
            if (dashboardView == null) {
                dashboardView = new DashboardView(tabPane, this::handleDashboardAction);
            }
            splitPane.getItems().add(0, dashboardView);
            splitPane.setDividerPositions(0.2);
            dashboardVisible = true;
        } else if (!show && dashboardVisible) {
            splitPane.getItems().remove(dashboardView);
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
                updateStatus("Tab geschlossen: " + terminalTab.getConnection().getDisplayName());
                break;
                
            case RECONNECT:
                // Reconnect the terminal
                Platform.runLater(() -> {
                    try {
                        terminalTab.connect();
                        updateDashboard();
                        updateStatus("Wiederverbinde mit " + terminalTab.getConnection().getDisplayName());
                    } catch (Exception e) {
                        logger.error("Reconnect failed", e);
                        updateStatus("Wiederverbindung fehlgeschlagen: " + e.getMessage());
                    }
                });
                break;
        }
    }
    
    private void openProject() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Projekt öffnen");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("KorTTY Projekte", "*" + ProjectManager.getProjectExtension())
        );
        
        File file = fileChooser.showOpenDialog(stage);
        if (file != null) {
            try {
                Project project = projectManager.loadProject(file.toPath());
                loadProject(project);
                updateStatus("Projekt geladen: " + project.getName());
            } catch (Exception e) {
                logger.error("Failed to load project", e);
                showError("Fehler", "Projekt konnte nicht geladen werden: " + e.getMessage());
            }
        }
    }
    
    private void saveProject() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Projekt speichern");
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
                    updateStatus("Projekt gespeichert: " + file.getName());
                }
            } catch (Exception e) {
                logger.error("Failed to save project", e);
                showError("Fehler", "Projekt konnte nicht gespeichert werden: " + e.getMessage());
            }
        }
    }
    
    private Project createProjectFromCurrentState() {
        Project project = new Project("Neues Projekt");
        
        WindowState windowState = new WindowState(UUID.randomUUID().toString());
        windowState.setGeometry(new WindowGeometry(
                stage.getX(), stage.getY(), stage.getWidth(), stage.getHeight()
        ));
        windowState.getGeometry().setMaximized(stage.isMaximized());
        
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                ServerConnection connection = terminalTab.getConnection();
                SessionState sessionState = new SessionState(
                        java.util.UUID.randomUUID().toString(),
                        connection.getId()
                );
                sessionState.setSettings(connection.getSettings());
                sessionState.setTerminalHistory(terminalTab.getTerminalView().getTerminalHistory());
                windowState.addTab(sessionState);
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
                ServerConnection connection = app.getConfigManager().getConnectionById(sessionState.getConnectionId());
                if (connection != null) {
                    if (project.isAutoReconnect()) {
                        // Get password and reconnect
                        PasswordVault vault = new PasswordVault(
                                app.getMasterPasswordManager().getEncryptionService(),
                                app.getMasterPasswordManager().getMasterPassword()
                        );
                        String password = vault.retrievePassword(connection);
                        if (password != null) {
                            openConnection(connection, password);
                        }
                    }
                    // Restore history if not reconnecting
                    // TODO: Create tab with history display
                }
            }
        }
        
        // Show dashboard for projects
        toggleDashboard(true);
    }
    
    private void importConnections() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Verbindungen importieren");
        
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
                        
                        showInfo("Import erfolgreich", 
                                imported.size() + " Verbindungen aus " + importer.getName() + " importiert.");
                        return;
                    } catch (Exception e) {
                        logger.error("Import failed", e);
                        showError("Import fehlgeschlagen", e.getMessage());
                        return;
                    }
                }
            }
            showError("Import fehlgeschlagen", "Dateiformat wird nicht unterstützt.");
        }
    }
    
    private void exportConnections() {
        List<ServerConnection> allConnections = app.getConfigManager().getConnections();
        
        if (allConnections.isEmpty()) {
            showInfo("Keine Verbindungen", "Es sind keine Verbindungen zum Exportieren vorhanden.");
            return;
        }
        
        // Show export dialog
        ExportDialog dialog = new ExportDialog(stage, allConnections);
        Optional<ExportDialog.ExportResult> result = dialog.showAndWait();
        
        if (result.isEmpty() || result.get() == null) {
            return;
        }
        
        ExportDialog.ExportResult exportResult = result.get();
        
        // File chooser
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Verbindungen exportieren als " + exportResult.exporter.getName());
        
        if (exportResult.encrypted) {
            fileChooser.getExtensionFilters().add(
                    new FileChooser.ExtensionFilter("Verschlüsselte ZIP-Datei", "*.zip")
            );
            fileChooser.setInitialFileName("connections.zip");
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
                if (exportResult.encrypted) {
                    exportAsEncryptedZip(exportResult, file.toPath());
                } else {
                    exportResult.exporter.exportConnections(exportResult.connections, file.toPath());
                }
                
                showInfo("Export erfolgreich", 
                        exportResult.connections.size() + " Verbindungen exportiert nach " + file.getName() + 
                        "\n\nFormat: " + exportResult.exporter.getName() +
                        (exportResult.encrypted ? "\nVerschlüsselt: Ja" : ""));
            } catch (Exception e) {
                logger.error("Export failed", e);
                showError("Export fehlgeschlagen", e.getMessage());
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
        alert.setTitle("Über " + KorTTYApplication.getAppName());
        alert.setHeaderText(KorTTYApplication.getAppName() + " v" + KorTTYApplication.getAppVersion());
        alert.setContentText("Ein SSH-Client mit Tab-Unterstützung.\n\n" +
                "Entwickelt mit JavaFX und Apache MINA SSHD.\n\n" +
                "JMX-Monitoring verfügbar unter:\n" +
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
        
        updateStatus("Gruppe '" + groupName + "' geöffnet: " + groupConnections.size() + " Verbindungen");
        updateDashboard();
    }
    
}
