package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ProjectManager;
import de.kortty.core.SSHSession;
import de.kortty.core.SessionManager;
import de.kortty.model.*;
import de.kortty.persistence.importer.ConnectionImporter;
import de.kortty.persistence.importer.MTPuTTYImporter;
import de.kortty.persistence.importer.MobaXTermImporter;
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
        
        stage.setScene(scene);
        stage.setTitle(KorTTYApplication.getAppName());
        
        // Window close handling
        stage.setOnCloseRequest(e -> {
            if (!confirmClose()) {
                e.consume();
            } else {
                closeAllTabs();
                openWindows.remove(this);
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
        zoomIn.setAccelerator(new KeyCodeCombination(KeyCode.PLUS, KeyCombination.SHORTCUT_DOWN));
        zoomIn.setOnAction(e -> zoomTerminal(1));
        
        MenuItem zoomOut = new MenuItem("Verkleinern");
        zoomOut.setAccelerator(new KeyCodeCombination(KeyCode.MINUS, KeyCombination.SHORTCUT_DOWN));
        zoomOut.setOnAction(e -> zoomTerminal(-1));
        
        MenuItem resetZoom = new MenuItem("Zoom zurücksetzen");
        resetZoom.setAccelerator(new KeyCodeCombination(KeyCode.DIGIT0, KeyCombination.SHORTCUT_DOWN));
        resetZoom.setOnAction(e -> resetTerminalZoom());
        
        viewMenu.getItems().addAll(showDashboard, new SeparatorMenuItem(),
                zoomIn, zoomOut, resetZoom);
        
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
            SSHSession session = sessionManager.createSession(connection, password);
            
            TerminalTab terminalTab = new TerminalTab(session, app.getConfigManager().getEffectiveSettings(connection));
            terminalTab.setOnClosed(e -> {
                sessionManager.closeSession(session.getSessionId());
                updateDashboard();
            });
            
            // Insert before the "+" tab
            int insertIndex = tabPane.getTabs().size() - 1;
            tabPane.getTabs().add(insertIndex, terminalTab);
            tabPane.getSelectionModel().select(terminalTab);
            
            // Connect in background
            new Thread(() -> {
                try {
                    session.connect();
                    Platform.runLater(() -> {
                        terminalTab.onConnected();
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
        QuickConnectDialog dialog = new QuickConnectDialog(stage, app.getConfigManager().getConnections(), vault);
        dialog.showAndWait().ifPresent(result -> {
            String password = result.password();
            
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
        long activeSessions = tabPane.getTabs().stream()
                .filter(t -> t instanceof TerminalTab)
                .count();
        
        if (activeSessions > 0) {
            Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
            alert.setTitle("Fenster schließen");
            alert.setHeaderText("Aktive Verbindungen");
            alert.setContentText("Es gibt " + activeSessions + " aktive Verbindung(en). Wirklich schließen?");
            
            return alert.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK;
        }
        return true;
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
                dashboardView = new DashboardView(sessionManager, this::focusSession);
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
    
    private void focusSession(String sessionId) {
        for (Tab tab : tabPane.getTabs()) {
            if (tab instanceof TerminalTab terminalTab) {
                if (terminalTab.getSessionId().equals(sessionId)) {
                    tabPane.getSelectionModel().select(tab);
                    break;
                }
            }
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
                SSHSession session = sessionManager.getSession(terminalTab.getSessionId());
                if (session != null) {
                    windowState.addTab(session.getState());
                }
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
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Verbindungen exportieren");
        fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("XML Dateien", "*.xml")
        );
        
        File file = fileChooser.showSaveDialog(stage);
        if (file != null) {
            try {
                app.getConfigManager().save(app.getMasterPasswordManager().getDerivedKey());
                showInfo("Export erfolgreich", "Verbindungen exportiert nach " + file.getName());
            } catch (Exception e) {
                logger.error("Export failed", e);
                showError("Export fehlgeschlagen", e.getMessage());
            }
        }
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
}
