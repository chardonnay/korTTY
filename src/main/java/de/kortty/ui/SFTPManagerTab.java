package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.SFTPSession;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.SessionState;
import de.kortty.model.TemporarySSHKey;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.util.Duration;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.Paths;
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

/**
 * SFTP Manager as a Tab for file transfers between local and remote systems.
 * Can be embedded in the main window's TabPane instead of opening as a modal dialog.
 */
public class SFTPManagerTab extends Tab {
    
    private static final Logger logger = LoggerFactory.getLogger(SFTPManagerTab.class);
    
    private final KorTTYApplication app;
    private final ServerConnection connection;
    private final String password;
    private final TemporarySSHKey temporarySSHKey;
    private SFTPSession sftpSession;
    
    private TableView<SFTPManagerDialog.FileItem> localTable;
    private TableView<SFTPManagerDialog.FileItem> remoteTable;
    private TextField localPathField;
    private TextField remotePathField;
    private TextField localSearchField;
    private TextField remoteSearchField;
    private Label statusLabel;
    
    private Path currentLocalPath;
    private String currentRemotePath;
    
    private ObservableList<SFTPManagerDialog.FileItem> localItems;
    private ObservableList<SFTPManagerDialog.FileItem> remoteItems;
    private FilteredList<SFTPManagerDialog.FileItem> filteredLocalItems;
    private FilteredList<SFTPManagerDialog.FileItem> filteredRemoteItems;
    private javafx.collections.transformation.SortedList<SFTPManagerDialog.FileItem> sortedLocalItems;
    private javafx.collections.transformation.SortedList<SFTPManagerDialog.FileItem> sortedRemoteItems;
    
    // Auto-close timeout
    private Timeline autoCloseTimer;
    private int remainingSeconds;
    private Label timeoutLabel;
    private ProgressBar statusProgressBar;
    private Runnable onCloseCallback;
    
    public SFTPManagerTab(KorTTYApplication app, ServerConnection connection, String password) {
        this(app, connection, password, null, 0);
    }
    
    public SFTPManagerTab(KorTTYApplication app, ServerConnection connection, String password, TemporarySSHKey temporarySSHKey) {
        this(app, connection, password, temporarySSHKey, 0);
    }
    
    public SFTPManagerTab(KorTTYApplication app, ServerConnection connection, String password, TemporarySSHKey temporarySSHKey, int autoCloseTimeoutMinutes) {
        this.app = app;
        this.connection = connection;
        this.password = password;
        this.temporarySSHKey = temporarySSHKey;
        
        setText("SFTP: " + connection.getDisplayName());
        setClosable(true);
        
        // Initialize paths
        currentLocalPath = Paths.get(System.getProperty("user.home"));
        currentRemotePath = "~";
        
        // Create UI
        VBox content = createContent();
        setContent(content);
        
        // Handle tab close
        setOnCloseRequest(event -> {
            cleanup();
            if (onCloseCallback != null) {
                onCloseCallback.run();
            }
        });
        
        // Setup auto-close timeout if enabled
        if (autoCloseTimeoutMinutes > 0) {
            setupAutoCloseTimeout(autoCloseTimeoutMinutes);
        }
        
        // Connect to SFTP
        connectToSFTP();
    }
    
    /**
     * Sets a callback to be called when the tab is closed.
     */
    public void setOnCloseCallback(Runnable callback) {
        this.onCloseCallback = callback;
    }
    
    /**
     * Sets up the auto-close timeout feature.
     */
    private void setupAutoCloseTimeout(int minutes) {
        remainingSeconds = minutes * 60;
        
        autoCloseTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            remainingSeconds--;
            updateTimeoutLabel();
            
            if (remainingSeconds <= 0) {
                autoCloseTimer.stop();
                Platform.runLater(() -> {
                    cleanup();
                    if (getTabPane() != null) {
                        removeTabSafely();
                    }
                    if (onCloseCallback != null) {
                        onCloseCallback.run();
                    }
                });
            }
        }));
        autoCloseTimer.setCycleCount(Timeline.INDEFINITE);
        autoCloseTimer.play();
    }
    
    private void updateTimeoutLabel() {
        if (timeoutLabel != null) {
            int mins = remainingSeconds / 60;
            int secs = remainingSeconds % 60;
            Platform.runLater(() -> {
                timeoutLabel.setText(String.format("Auto-close in %d:%02d", mins, secs));
                if (remainingSeconds <= 60) {
                    timeoutLabel.setStyle("-fx-text-fill: red; -fx-font-weight: bold;");
                }
            });
        }
    }
    
    /**
     * Resets the auto-close timer (e.g., when user interacts with the tab).
     */
    public void resetAutoCloseTimer() {
        if (autoCloseTimer != null) {
            // Get original timeout from settings
            int timeoutMinutes = 0;
            try {
                var globalSettings = app.getGlobalSettingsManager().getSettings();
                if (globalSettings != null && globalSettings.getSftpAutoCloseMinutes() != null) {
                    timeoutMinutes = globalSettings.getSftpAutoCloseMinutes();
                }
            } catch (Exception e) {
                logger.debug("Could not get SFTP timeout setting: {}", e.getMessage());
            }
            
            if (timeoutMinutes > 0) {
                remainingSeconds = timeoutMinutes * 60;
                updateTimeoutLabel();
            }
        }
    }
    
    private VBox createContent() {
        VBox mainBox = new VBox(10);
        mainBox.setPadding(new Insets(10));
        
        // Status bar with timeout indicator and progress bar
        HBox statusBox = new HBox(10);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        
        statusLabel = new Label(I18n.get("sftp.connecting"));
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        
        // Progress bar for archive/copy operations (initially hidden)
        statusProgressBar = new ProgressBar(0);
        statusProgressBar.setPrefWidth(150);
        statusProgressBar.setVisible(false);
        statusProgressBar.setMaxHeight(15);
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        timeoutLabel = new Label("");
        timeoutLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        
        statusBox.getChildren().addAll(statusLabel, statusProgressBar, spacer, timeoutLabel);
        
        // Split pane for local and remote
        SplitPane splitPane = new SplitPane();
        splitPane.setDividerPositions(0.5);
        
        // Local panel
        VBox localPanel = createLocalPanel();
        
        // Remote panel
        VBox remotePanel = createRemotePanel();
        
        splitPane.getItems().addAll(localPanel, remotePanel);
        
        // Transfer buttons
        HBox buttonBox = createButtonBox();
        
        mainBox.getChildren().addAll(splitPane, buttonBox, statusBox);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        
        return mainBox;
    }
    
    private HBox createButtonBox() {
        HBox buttonBox = new HBox(0);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setPadding(new Insets(10, 5, 5, 5));
        
        // === LOCAL (left) buttons ===
        HBox localButtons = new HBox(5);
        localButtons.setAlignment(Pos.CENTER_LEFT);
        localButtons.setPadding(new Insets(0, 10, 0, 5));
        
        Label localLabel = new Label(I18n.get("sftp.localSystem"));
        localLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #666;");
        
        Button refreshLocalButton = new Button("⟳ " + I18n.get("sftp.refresh"));
        refreshLocalButton.setTooltip(new Tooltip(I18n.get("sftp.refreshLocal")));
        refreshLocalButton.setOnAction(e -> {
            resetAutoCloseTimer();
            refreshLocal();
        });
        
        Button deleteLocalButton = new Button("🗑 " + I18n.get("sftp.delete"));
        deleteLocalButton.setTooltip(new Tooltip(I18n.get("sftp.contextMenu.delete")));
        deleteLocalButton.setDisable(true);
        deleteLocalButton.setOnAction(e -> {
            resetAutoCloseTimer();
            deleteLocalSelected();
        });
        
        Button ownerLocalButton = new Button("🔐 " + I18n.get("sftp.permissionsShort"));
        ownerLocalButton.setTooltip(new Tooltip(I18n.get("sftp.contextMenu.setOwner")));
        ownerLocalButton.setDisable(true);
        ownerLocalButton.setOnAction(e -> {
            resetAutoCloseTimer();
            setLocalOwnerPermissionsDialog();
        });
        
        localButtons.getChildren().addAll(localLabel, refreshLocalButton, deleteLocalButton, ownerLocalButton);
        
        // === Vertical separator ===
        Separator verticalSeparator = new Separator();
        verticalSeparator.setOrientation(javafx.geometry.Orientation.VERTICAL);
        verticalSeparator.setPadding(new Insets(0, 15, 0, 15));
        
        // === REMOTE (right) buttons ===
        HBox remoteButtons = new HBox(5);
        remoteButtons.setAlignment(Pos.CENTER_LEFT);
        remoteButtons.setPadding(new Insets(0, 5, 0, 10));
        
        Label remoteLabel = new Label(I18n.get("sftp.remoteLabel"));
        remoteLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: #666;");
        
        Button refreshRemoteButton = new Button("⟳ " + I18n.get("sftp.refresh"));
        refreshRemoteButton.setTooltip(new Tooltip(I18n.get("sftp.refreshRemote")));
        refreshRemoteButton.setOnAction(e -> {
            resetAutoCloseTimer();
            refreshRemote();
        });
        
        Button deleteRemoteButton = new Button("🗑 " + I18n.get("sftp.delete"));
        deleteRemoteButton.setTooltip(new Tooltip(I18n.get("sftp.contextMenu.delete")));
        deleteRemoteButton.setDisable(true);
        deleteRemoteButton.setOnAction(e -> {
            resetAutoCloseTimer();
            deleteRemoteSelected();
        });
        
        Button ownerRemoteButton = new Button("🔐 " + I18n.get("sftp.permissionsShort"));
        ownerRemoteButton.setTooltip(new Tooltip(I18n.get("sftp.contextMenu.setOwner")));
        ownerRemoteButton.setDisable(true);
        ownerRemoteButton.setOnAction(e -> {
            resetAutoCloseTimer();
            setOwnerPermissionsDialog();
        });
        
        Separator remoteSep1 = new Separator();
        remoteSep1.setOrientation(javafx.geometry.Orientation.VERTICAL);
        remoteSep1.setPadding(new Insets(0, 5, 0, 5));
        
        Button uploadButton = new Button("⬆ " + I18n.get("sftp.upload"));
        uploadButton.setTooltip(new Tooltip(I18n.get("sftp.uploading", "...")));
        uploadButton.setDisable(true);
        uploadButton.setOnAction(e -> {
            resetAutoCloseTimer();
            uploadSelected();
        });
        
        Button downloadButton = new Button("⬇ " + I18n.get("sftp.download"));
        downloadButton.setTooltip(new Tooltip(I18n.get("sftp.downloading", "...")));
        downloadButton.setDisable(true);
        downloadButton.setOnAction(e -> {
            resetAutoCloseTimer();
            downloadSelected();
        });
        
        Separator remoteSep2 = new Separator();
        remoteSep2.setOrientation(javafx.geometry.Orientation.VERTICAL);
        remoteSep2.setPadding(new Insets(0, 5, 0, 5));
        
        Button archiveButton = new Button("📦 " + I18n.get("sftp.archive"));
        archiveButton.setTooltip(new Tooltip(I18n.get("sftp.contextMenu.archive")));
        archiveButton.setDisable(true);
        archiveButton.setOnAction(e -> {
            resetAutoCloseTimer();
            createRemoteArchive();
        });
        
        remoteButtons.getChildren().addAll(remoteLabel, refreshRemoteButton, deleteRemoteButton, ownerRemoteButton,
                remoteSep1, uploadButton, downloadButton, remoteSep2, archiveButton);
        
        // Assemble main button box: local | separator | remote
        buttonBox.getChildren().addAll(localButtons, verticalSeparator, remoteButtons);
        HBox.setHgrow(localButtons, Priority.ALWAYS);
        HBox.setHgrow(remoteButtons, Priority.ALWAYS);
        
        // Enable/disable buttons based on selection
        localTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            boolean hasSelection = selected != null && !selected.getName().equals("..");
            uploadButton.setDisable(!hasSelection);
            deleteLocalButton.setDisable(!hasSelection);
            ownerLocalButton.setDisable(!hasSelection);
        });
        
        remoteTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            boolean hasSelection = selected != null && !selected.getName().equals("..");
            downloadButton.setDisable(!hasSelection);
            archiveButton.setDisable(!hasSelection);
            deleteRemoteButton.setDisable(!hasSelection);
            ownerRemoteButton.setDisable(!hasSelection);
        });
        
        // Enable multiple selection
        localTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        remoteTable.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        
        return buttonBox;
    }
    
    private VBox createLocalPanel() {
        VBox panel = new VBox(5);
        panel.setPadding(new Insets(5));
        
        Label titleLabel = new Label(I18n.get("sftp.localSystem"));
        titleLabel.setStyle("-fx-font-weight: bold;");
        
        // Path field and navigation
        HBox pathBox = new HBox(5);
        localPathField = new TextField();
        localPathField.setEditable(true);
        localPathField.setOnAction(e -> navigateLocal(localPathField.getText()));
        
        Button upButton = new Button("↑");
        upButton.setOnAction(e -> navigateLocalUp());
        
        Button homeButton = new Button("~");
        homeButton.setOnAction(e -> navigateLocal(System.getProperty("user.home")));
        
        pathBox.getChildren().addAll(new Label(I18n.get("sftp.path")), localPathField, upButton, homeButton);
        HBox.setHgrow(localPathField, Priority.ALWAYS);
        
        // Search field
        HBox searchBox = new HBox(5);
        localSearchField = new TextField();
        localSearchField.setPromptText(I18n.get("sftp.searchPrompt"));
        searchBox.getChildren().addAll(new Label(I18n.get("sftp.search")), localSearchField);
        HBox.setHgrow(localSearchField, Priority.ALWAYS);
        
        // File table
        localTable = new TableView<>();
        localTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        
        // Column order: Name, Type, Size, Date, Permissions
        TableColumn<SFTPManagerDialog.FileItem, String> nameColumn = new TableColumn<>(I18n.get("sftp.column.name"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameColumn.setPrefWidth(200);
        nameColumn.setMinWidth(100);
        nameColumn.setSortable(true);
        
        TableColumn<SFTPManagerDialog.FileItem, String> typeColumn = new TableColumn<>(I18n.get("sftp.column.type"));
        typeColumn.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeColumn.setPrefWidth(60);
        typeColumn.setMinWidth(50);
        typeColumn.setMaxWidth(80);
        typeColumn.setSortable(true);
        
        TableColumn<SFTPManagerDialog.FileItem, String> sizeColumn = new TableColumn<>(I18n.get("sftp.column.size"));
        sizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
        sizeColumn.setPrefWidth(100);
        sizeColumn.setMinWidth(80);
        sizeColumn.setMaxWidth(120);
        sizeColumn.setSortable(true);
        
        TableColumn<SFTPManagerDialog.FileItem, String> dateColumn = new TableColumn<>(I18n.get("sftp.column.date"));
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateColumn.setPrefWidth(140);
        dateColumn.setMinWidth(120);
        dateColumn.setMaxWidth(160);
        dateColumn.setSortable(true);
        
        TableColumn<SFTPManagerDialog.FileItem, String> permColumn = new TableColumn<>(I18n.get("sftp.column.permissions"));
        permColumn.setCellValueFactory(new PropertyValueFactory<>("permissions"));
        permColumn.setPrefWidth(90);
        permColumn.setMinWidth(70);
        permColumn.setMaxWidth(110);
        permColumn.setSortable(true);
        
        localTable.getColumns().addAll(java.util.List.of(nameColumn, typeColumn, sizeColumn, dateColumn, permColumn));
        
        // Context menu for local table
        ContextMenu localContextMenu = createLocalContextMenu();
        localTable.setContextMenu(localContextMenu);
        
        // Double-click to navigate
        localTable.setRowFactory(tv -> {
            TableRow<SFTPManagerDialog.FileItem> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    SFTPManagerDialog.FileItem item = row.getItem();
                    if (!item.isFile()) {
                        navigateLocal(item.getPath());
                    }
                }
            });
            return row;
        });
        
        // Setup data binding with filter and sort
        localItems = FXCollections.observableArrayList();
        filteredLocalItems = new FilteredList<>(localItems, p -> true);
        sortedLocalItems = new javafx.collections.transformation.SortedList<>(filteredLocalItems);
        
        // Bind sorted list to table
        sortedLocalItems.comparatorProperty().bind(localTable.comparatorProperty());
        localTable.setItems(sortedLocalItems);
        
        // Set default sort by type (directories first)
        typeColumn.setSortType(TableColumn.SortType.ASCENDING);
        localTable.getSortOrder().add(typeColumn);
        
        // Search filter
        localSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.trim().isEmpty()) {
                filteredLocalItems.setPredicate(p -> true);
            } else {
                String searchLower = newVal.toLowerCase();
                filteredLocalItems.setPredicate(item -> 
                    item.getName().toLowerCase().contains(searchLower));
            }
        });
        
        panel.getChildren().addAll(titleLabel, pathBox, searchBox, localTable);
        VBox.setVgrow(localTable, Priority.ALWAYS);
        
        return panel;
    }
    
    private VBox createRemotePanel() {
        VBox panel = new VBox(5);
        panel.setPadding(new Insets(5));
        
        Label titleLabel = new Label(I18n.get("sftp.remoteServer", connection.getHost()));
        titleLabel.setStyle("-fx-font-weight: bold;");
        
        // Path field and navigation
        HBox pathBox = new HBox(5);
        remotePathField = new TextField();
        remotePathField.setEditable(true);
        remotePathField.setOnAction(e -> navigateRemote(remotePathField.getText()));
        
        Button upButton = new Button("↑");
        upButton.setOnAction(e -> navigateRemoteUp());
        
        Button homeButton = new Button("~");
        homeButton.setOnAction(e -> navigateRemote("~"));
        
        pathBox.getChildren().addAll(new Label(I18n.get("sftp.path")), remotePathField, upButton, homeButton);
        HBox.setHgrow(remotePathField, Priority.ALWAYS);
        
        // Search field
        HBox searchBox = new HBox(5);
        remoteSearchField = new TextField();
        remoteSearchField.setPromptText(I18n.get("sftp.searchPrompt"));
        searchBox.getChildren().addAll(new Label(I18n.get("sftp.search")), remoteSearchField);
        HBox.setHgrow(remoteSearchField, Priority.ALWAYS);
        
        // File table
        remoteTable = new TableView<>();
        remoteTable.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        
        // Column order: Name, Type, Size, Date, Permissions
        TableColumn<SFTPManagerDialog.FileItem, String> nameColumn = new TableColumn<>(I18n.get("sftp.column.name"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameColumn.setPrefWidth(200);
        nameColumn.setMinWidth(100);
        nameColumn.setSortable(true);
        
        TableColumn<SFTPManagerDialog.FileItem, String> typeColumn = new TableColumn<>(I18n.get("sftp.column.type"));
        typeColumn.setCellValueFactory(new PropertyValueFactory<>("type"));
        typeColumn.setPrefWidth(60);
        typeColumn.setMinWidth(50);
        typeColumn.setMaxWidth(80);
        typeColumn.setSortable(true);
        
        TableColumn<SFTPManagerDialog.FileItem, String> sizeColumn = new TableColumn<>(I18n.get("sftp.column.size"));
        sizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
        sizeColumn.setPrefWidth(100);
        sizeColumn.setMinWidth(80);
        sizeColumn.setMaxWidth(120);
        sizeColumn.setSortable(true);
        
        TableColumn<SFTPManagerDialog.FileItem, String> dateColumn = new TableColumn<>(I18n.get("sftp.column.date"));
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateColumn.setPrefWidth(140);
        dateColumn.setMinWidth(120);
        dateColumn.setMaxWidth(160);
        dateColumn.setSortable(true);
        
        TableColumn<SFTPManagerDialog.FileItem, String> permColumn = new TableColumn<>(I18n.get("sftp.column.permissions"));
        permColumn.setCellValueFactory(new PropertyValueFactory<>("permissions"));
        permColumn.setPrefWidth(90);
        permColumn.setMinWidth(70);
        permColumn.setMaxWidth(110);
        permColumn.setSortable(true);
        
        remoteTable.getColumns().addAll(java.util.List.of(nameColumn, typeColumn, sizeColumn, dateColumn, permColumn));
        
        // Double-click to navigate, right-click for context menu
        remoteTable.setRowFactory(tv -> {
            TableRow<SFTPManagerDialog.FileItem> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    SFTPManagerDialog.FileItem item = row.getItem();
                    if (!item.isFile()) {
                        navigateRemote(item.getPath());
                    }
                }
            });
            return row;
        });
        
        // Context menu for remote table
        ContextMenu remoteContextMenu = createRemoteContextMenu();
        remoteTable.setContextMenu(remoteContextMenu);
        
        // Setup data binding with filter and sort
        remoteItems = FXCollections.observableArrayList();
        filteredRemoteItems = new FilteredList<>(remoteItems, p -> true);
        sortedRemoteItems = new javafx.collections.transformation.SortedList<>(filteredRemoteItems);
        
        // Bind sorted list to table
        sortedRemoteItems.comparatorProperty().bind(remoteTable.comparatorProperty());
        remoteTable.setItems(sortedRemoteItems);
        
        // Set default sort by type (directories first)
        typeColumn.setSortType(TableColumn.SortType.ASCENDING);
        remoteTable.getSortOrder().add(typeColumn);
        
        // Search filter
        remoteSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.trim().isEmpty()) {
                filteredRemoteItems.setPredicate(p -> true);
            } else {
                String searchLower = newVal.toLowerCase();
                filteredRemoteItems.setPredicate(item -> 
                    item.getName().toLowerCase().contains(searchLower));
            }
        });
        
        panel.getChildren().addAll(titleLabel, pathBox, searchBox, remoteTable);
        VBox.setVgrow(remoteTable, Priority.ALWAYS);
        
        return panel;
    }
    
    private void connectToSFTP() {
        new Thread(() -> {
            try {
                // Create connection for SFTP with optional temporary SSH key
                ServerConnection connToUse = connection;
                
                // If using temporary SSH key, update connection
                if (temporarySSHKey != null && temporarySSHKey.isValid()) {
                    connToUse = new ServerConnection();
                    connToUse.setName(connection.getName());
                    connToUse.setHost(connection.getHost());
                    connToUse.setPort(connection.getPort());
                    connToUse.setUsername(connection.getUsername());
                    connToUse.setAuthMethod(de.kortty.model.AuthMethod.PUBLIC_KEY);
                    connToUse.setPrivateKeyPath("TEMPORARY:" + temporarySSHKey.getKeyContent());
                    connToUse.setSettings(connection.getSettings());
                }
                
                sftpSession = new SFTPSession(connToUse, password);
                
                // Set SSHKeyManager if using public key (for non-temporary keys)
                if (temporarySSHKey == null && connToUse.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY) {
                    if (app != null && app.getSSHKeyManager() != null) {
                        sftpSession.setSSHKeyManager(
                            app.getSSHKeyManager(),
                            app.getMasterPasswordManager().getMasterPassword()
                        );
                    }
                }
                
                sftpSession.connect();
                
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("sftp.connectedTo", connection.getHost()));
                    refreshLocal();
                    refreshRemote();
                });
            } catch (Exception e) {
                logger.error("Failed to connect SFTP", e);
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("sftp.connectionFailed", e.getMessage()));
                    showError(I18n.get("sftp.error.connection"), I18n.get("sftp.error.connectionFailed", e.getMessage()));
                });
            }
        }, "SFTP-Connect").start();
    }
    
    private void cleanup() {
        if (autoCloseTimer != null) {
            autoCloseTimer.stop();
        }
        if (sftpSession != null) {
            try {
                sftpSession.close();
            } catch (Exception e) {
                logger.warn("Error closing SFTP session", e);
            }
        }
    }
    
    private void removeTabSafely() {
        TabPane tabPane = getTabPane();
        if (tabPane == null) return;
        
        int currentIndex = tabPane.getTabs().indexOf(this);
        
        // Suppress QuickConnect if + tab might be selected
        de.kortty.ui.MainWindow.suppressNextQuickConnect();
        
        // Select a different tab before removing this one to avoid selecting the "+" tab
        if (currentIndex > 0) {
            // Select previous tab
            tabPane.getSelectionModel().select(currentIndex - 1);
        } else if (tabPane.getTabs().size() > 1) {
            // We're at index 0, select next tab (if it's not the "+" tab)
            Tab nextTab = tabPane.getTabs().get(1);
            if (nextTab.getText() != null && !nextTab.getText().equals("+")) {
                tabPane.getSelectionModel().select(1);
            }
        }
        
        // Now remove this tab
        tabPane.getTabs().remove(this);
    }
    
    private void refreshLocal() {
        localItems.clear();
        try {
            File[] files = currentLocalPath.toFile().listFiles();
            if (files != null) {
                // Add parent directory entry
                if (currentLocalPath.getParent() != null) {
                    localItems.add(new SFTPManagerDialog.FileItem("..", 
                        currentLocalPath.getParent().toString(), false, "—", "", ""));
                }
                
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (File file : files) {
                    String size = file.isDirectory() ? "—" : formatSize(file.length());
                    String date = sdf.format(new Date(file.lastModified()));
                    String permissions = getLocalFilePermissions(file.toPath());
                    localItems.add(new SFTPManagerDialog.FileItem(file.getName(), 
                        file.getAbsolutePath(), file.isFile(), size, date, permissions));
                }
            }
            localPathField.setText(currentLocalPath.toString());
        } catch (Exception e) {
            logger.error("Failed to list local files", e);
            showError(I18n.get("error.title"), I18n.get("sftp.error.listLocalFiles", e.getMessage()));
        }
    }
    
    /**
     * Gets the file permissions as a string (e.g., "rwxr-xr-x" on Unix or "rw-" on Windows).
     */
    private String getLocalFilePermissions(Path path) {
        try {
            // Try POSIX permissions first (Unix/macOS)
            java.nio.file.attribute.PosixFileAttributes attrs = 
                Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes.class);
            return java.nio.file.attribute.PosixFilePermissions.toString(attrs.permissions());
        } catch (UnsupportedOperationException e) {
            // Windows fallback
            StringBuilder perms = new StringBuilder();
            perms.append(Files.isReadable(path) ? "r" : "-");
            perms.append(Files.isWritable(path) ? "w" : "-");
            perms.append(Files.isExecutable(path) ? "x" : "-");
            return perms.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    private void refreshRemote() {
        if (sftpSession == null || !sftpSession.isConnected()) {
            return;
        }
        
        remoteItems.clear();
        try {
            // Resolve ~ to actual home directory
            String pathToList = currentRemotePath;
            if (pathToList.equals("~")) {
                pathToList = sftpSession.getCurrentDirectory();
                currentRemotePath = pathToList;
            }
            
            List<SftpClient.DirEntry> entries = sftpSession.listFiles(pathToList);
            
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
            for (SftpClient.DirEntry entry : entries) {
                String name = entry.getFilename();
                if (name.equals(".")) continue;
                
                boolean isDir = entry.getAttributes().isDirectory();
                String size = isDir ? "—" : formatSize(entry.getAttributes().getSize());
                
                long mtime = entry.getAttributes().getModifyTime().toMillis();
                String date = sdf.format(new Date(mtime));
                
                // Get file permissions, owner and group
                String permissions = formatRemotePermissions(entry.getAttributes());
                
                // Get owner - try name first, fall back to UID
                String owner = entry.getAttributes().getOwner();
                if (owner == null || owner.isEmpty()) {
                    Integer uid = entry.getAttributes().getUserId();
                    owner = uid != null ? String.valueOf(uid) : "";
                }
                
                // Get group - try name first, fall back to GID
                String group = entry.getAttributes().getGroup();
                if (group == null || group.isEmpty()) {
                    Integer gid = entry.getAttributes().getGroupId();
                    group = gid != null ? String.valueOf(gid) : "";
                }
                
                String fullPath = pathToList.endsWith("/") ? pathToList + name : pathToList + "/" + name;
                remoteItems.add(new SFTPManagerDialog.FileItem(name, fullPath, !isDir, size, date, permissions, owner, group));
            }
            
            remotePathField.setText(currentRemotePath);
        } catch (Exception e) {
            logger.error("Failed to list remote files", e);
            showError(I18n.get("error.title"), I18n.get("sftp.error.listRemoteFiles", e.getMessage()));
        }
    }
    
    /**
     * Formats remote file permissions from SFTP attributes to a string like "rwxr-xr-x".
     */
    private String formatRemotePermissions(SftpClient.Attributes attrs) {
        try {
            int perms = attrs.getPermissions();
            StringBuilder sb = new StringBuilder();
            
            // Owner permissions
            sb.append((perms & 0400) != 0 ? "r" : "-");
            sb.append((perms & 0200) != 0 ? "w" : "-");
            sb.append((perms & 0100) != 0 ? "x" : "-");
            
            // Group permissions
            sb.append((perms & 0040) != 0 ? "r" : "-");
            sb.append((perms & 0020) != 0 ? "w" : "-");
            sb.append((perms & 0010) != 0 ? "x" : "-");
            
            // Others permissions
            sb.append((perms & 0004) != 0 ? "r" : "-");
            sb.append((perms & 0002) != 0 ? "w" : "-");
            sb.append((perms & 0001) != 0 ? "x" : "-");
            
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
    }
    
    private void navigateLocal(String path) {
        try {
            Path newPath = Paths.get(path);
            if (Files.exists(newPath) && Files.isDirectory(newPath)) {
                currentLocalPath = newPath;
                refreshLocal();
            }
        } catch (Exception e) {
            showError(I18n.get("error.title"), I18n.get("sftp.error.invalidPath", path));
        }
    }
    
    private void navigateLocalUp() {
        if (currentLocalPath.getParent() != null) {
            currentLocalPath = currentLocalPath.getParent();
            refreshLocal();
        }
    }
    
    private void navigateRemote(String path) {
        currentRemotePath = path;
        refreshRemote();
    }
    
    private void navigateRemoteUp() {
        if (currentRemotePath.contains("/")) {
            int lastSlash = currentRemotePath.lastIndexOf('/');
            if (lastSlash == 0) {
                currentRemotePath = "/";
            } else {
                currentRemotePath = currentRemotePath.substring(0, lastSlash);
            }
            refreshRemote();
        }
    }
    
    private void uploadSelected() {
        var selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        if (sftpSession == null || !sftpSession.isConnected()) return;
        
        // Collect items to upload (filter out "..")
        List<SFTPManagerDialog.FileItem> itemsToUpload = new ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                itemsToUpload.add(item);
            }
        }
        if (itemsToUpload.isEmpty()) return;
        
        // Upload all files sequentially in a single thread (SFTP client is not thread-safe)
        new Thread(() -> {
            int total = itemsToUpload.size();
            int current = 0;
            int failed = 0;
            
            for (var item : itemsToUpload) {
                current++;
                final int num = current;
                Platform.runLater(() -> statusLabel.setText(
                    I18n.get("sftp.uploading", item.getName()) + " (" + num + "/" + total + ")"));
                
                try {
                    uploadSingleFile(item);
                } catch (Exception e) {
                    failed++;
                    logger.error("Upload failed: {}", item.getName(), e);
                    final String errorMsg = e.getMessage();
                    Platform.runLater(() -> showError(I18n.get("sftp.error.upload"), item.getName() + ": " + errorMsg));
                }
            }
            
            final int successCount = total - failed;
            final int failCount = failed;
            Platform.runLater(() -> {
                if (failCount == 0) {
                    statusLabel.setText(I18n.get("sftp.uploadComplete", String.valueOf(successCount)));
                } else {
                    statusLabel.setText(I18n.get("sftp.uploadComplete", successCount + "/" + total));
                }
                refreshRemote();
            });
        }, "SFTP-Upload").start();
    }
    
    private void uploadSingleFile(SFTPManagerDialog.FileItem item) throws Exception {
        String remotePath = currentRemotePath.endsWith("/") 
            ? currentRemotePath + item.getName() 
            : currentRemotePath + "/" + item.getName();
        
        if (item.isFile()) {
            sftpSession.uploadFile(Paths.get(item.getPath()), remotePath);
        } else {
            // Upload directory recursively
            uploadDirectoryRecursive(Paths.get(item.getPath()), remotePath);
        }
    }
    
    private void uploadDirectoryRecursive(Path localDir, String remotePath) throws Exception {
        sftpSession.createDirectory(remotePath);
        try (var stream = Files.list(localDir)) {
            for (Path entry : stream.toList()) {
                String remoteEntry = remotePath + "/" + entry.getFileName().toString();
                if (Files.isDirectory(entry)) {
                    uploadDirectoryRecursive(entry, remoteEntry);
                } else {
                    sftpSession.uploadFile(entry, remoteEntry);
                }
            }
        }
    }
    
    private void downloadSelected() {
        var selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        if (sftpSession == null || !sftpSession.isConnected()) return;
        
        // Collect items to download (filter out "..")
        List<SFTPManagerDialog.FileItem> itemsToDownload = new ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                itemsToDownload.add(item);
            }
        }
        if (itemsToDownload.isEmpty()) return;
        
        // Download all files sequentially in a single thread (SFTP client is not thread-safe)
        new Thread(() -> {
            int total = itemsToDownload.size();
            int current = 0;
            int failed = 0;
            
            for (var item : itemsToDownload) {
                current++;
                final int num = current;
                Platform.runLater(() -> statusLabel.setText(
                    I18n.get("sftp.downloading", item.getName()) + " (" + num + "/" + total + ")"));
                
                try {
                    downloadSingleFile(item);
                } catch (Exception e) {
                    failed++;
                    logger.error("Download failed: {}", item.getName(), e);
                    final String errorMsg = e.getMessage();
                    Platform.runLater(() -> showError(I18n.get("sftp.error.download"), item.getName() + ": " + errorMsg));
                }
            }
            
            final int successCount = total - failed;
            final int failCount = failed;
            Platform.runLater(() -> {
                if (failCount == 0) {
                    statusLabel.setText(I18n.get("sftp.downloadComplete", String.valueOf(successCount)));
                } else {
                    statusLabel.setText(I18n.get("sftp.downloadComplete", successCount + "/" + total));
                }
                refreshLocal();
            });
        }, "SFTP-Download").start();
    }
    
    private void downloadSingleFile(SFTPManagerDialog.FileItem item) throws Exception {
        Path localPath = currentLocalPath.resolve(item.getName());
        
        if (item.isFile()) {
            sftpSession.downloadFile(item.getPath(), localPath);
        } else {
            // Download directory recursively
            downloadDirectoryRecursive(item.getPath(), localPath);
        }
    }
    
    private void downloadDirectoryRecursive(String remotePath, Path localDir) throws Exception {
        Files.createDirectories(localDir);
        List<SftpClient.DirEntry> entries = sftpSession.listFiles(remotePath);
        for (SftpClient.DirEntry entry : entries) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) continue;
            
            String remoteEntry = remotePath + "/" + name;
            Path localEntry = localDir.resolve(name);
            
            if (entry.getAttributes().isDirectory()) {
                downloadDirectoryRecursive(remoteEntry, localEntry);
            } else {
                sftpSession.downloadFile(remoteEntry, localEntry);
            }
        }
    }
    
    private void copyLocalSelected() {
        var selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("sftp.selectTargetFolder"));
        chooser.setInitialDirectory(currentLocalPath.toFile());
        
        File targetDir = chooser.showDialog(null);
        if (targetDir != null) {
            for (var item : selected) {
                if (item.getName().equals("..")) continue;
                copyLocalFile(item, targetDir.toPath());
            }
        }
    }
    
    private void copyLocalFile(SFTPManagerDialog.FileItem item, Path targetDir) {
        try {
            Path source = Paths.get(item.getPath());
            Path target = targetDir.resolve(item.getName());
            
            if (item.isFile()) {
                Files.copy(source, target);
            } else {
                copyDirectory(source, target);
            }
            
            refreshLocal();
            statusLabel.setText(I18n.get("sftp.copied", item.getName()));
        } catch (Exception e) {
            showError(I18n.get("sftp.error.copy"), e.getMessage());
        }
    }
    
    private void copyDirectory(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        try (var stream = Files.walk(source)) {
            stream.forEach(sourcePath -> {
                try {
                    Path targetPath = target.resolve(source.relativize(sourcePath));
                    if (Files.isDirectory(sourcePath)) {
                        Files.createDirectories(targetPath);
                    } else {
                        Files.copy(sourcePath, targetPath);
                    }
                } catch (Exception e) {
                    logger.error("Error copying {}", sourcePath, e);
                }
            });
        }
    }
    
    private void copyRemoteSelected() {
        var selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        
        TextInputDialog dialog = new TextInputDialog(currentRemotePath);
        dialog.setTitle(I18n.get("sftp.remoteCopy"));
        dialog.setHeaderText(I18n.get("sftp.remoteCopy.header"));
        dialog.setContentText(I18n.get("sftp.path"));
        applyDarkTheme(dialog);
        
        dialog.showAndWait().ifPresent(targetPath -> {
            for (var item : selected) {
                if (item.getName().equals("..")) continue;
                copyRemoteFile(item, targetPath);
            }
        });
    }
    
    private void copyRemoteFile(SFTPManagerDialog.FileItem item, String targetPath) {
        if (sftpSession == null || !sftpSession.isConnected()) return;
        
        new Thread(() -> {
            try {
                String target = targetPath.endsWith("/") 
                    ? targetPath + item.getName() 
                    : targetPath + "/" + item.getName();
                
                sftpSession.copyFile(item.getPath(), target);
                
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("sftp.remoteCopied", item.getName()));
                    refreshRemote();
                });
            } catch (Exception e) {
                logger.error("Remote copy failed", e);
                Platform.runLater(() -> showError(I18n.get("sftp.error.remoteCopy"), e.getMessage()));
            }
        }, "SFTP-RemoteCopy").start();
    }
    
    private ContextMenu createLocalContextMenu() {
        ContextMenu menu = new ContextMenu();
        
        MenuItem copyItem = new MenuItem(I18n.get("sftp.contextMenu.copy"));
        copyItem.setOnAction(e -> {
            resetAutoCloseTimer();
            copyLocalSelected();
        });
        
        MenuItem deleteItem = new MenuItem(I18n.get("sftp.contextMenu.delete"));
        deleteItem.setOnAction(e -> {
            resetAutoCloseTimer();
            deleteLocalSelected();
        });
        
        MenuItem ownerItem = new MenuItem(I18n.get("sftp.contextMenu.setOwner"));
        ownerItem.setOnAction(e -> {
            resetAutoCloseTimer();
            setLocalOwnerPermissionsDialog();
        });
        
        MenuItem archiveItem = new MenuItem(I18n.get("sftp.contextMenu.archive"));
        archiveItem.setOnAction(e -> {
            resetAutoCloseTimer();
            createLocalArchive();
        });
        
        // Editor options
        MenuItem openTextItem = new MenuItem(I18n.get("sftp.contextMenu.openAsText"));
        openTextItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openLocalFileAsText();
        });
        
        MenuItem openSyntaxItem = new MenuItem(I18n.get("sftp.contextMenu.openWithSyntax"));
        openSyntaxItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openLocalFileWithSyntax();
        });
        
        MenuItem openImageItem = new MenuItem(I18n.get("sftp.contextMenu.openImage"));
        openImageItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openLocalImage();
        });
        
        menu.getItems().addAll(
            copyItem, deleteItem, 
            new SeparatorMenuItem(), 
            ownerItem, 
            new SeparatorMenuItem(), 
            archiveItem,
            new SeparatorMenuItem(),
            openTextItem, openSyntaxItem, openImageItem
        );
        
        // Disable items when nothing is selected
        menu.setOnShowing(e -> {
            var selected = localTable.getSelectionModel().getSelectedItems();
            boolean hasSelection = selected != null && !selected.isEmpty() && 
                !(selected.size() == 1 && selected.get(0).getName().equals(".."));
            boolean isSingleFile = hasSelection && selected.size() == 1 && selected.get(0).isFile();
            boolean isImageFile = isSingleFile && isImageFileType(selected.get(0).getName());
            
            copyItem.setDisable(!hasSelection);
            deleteItem.setDisable(!hasSelection);
            ownerItem.setDisable(!hasSelection);
            archiveItem.setDisable(!hasSelection);
            openTextItem.setDisable(!isSingleFile);
            openSyntaxItem.setDisable(!isSingleFile);
            openImageItem.setDisable(!isImageFile);
        });
        
        return menu;
    }
    
    private ContextMenu createRemoteContextMenu() {
        ContextMenu menu = new ContextMenu();
        
        MenuItem copyItem = new MenuItem(I18n.get("sftp.contextMenu.copy"));
        copyItem.setOnAction(e -> {
            resetAutoCloseTimer();
            copyRemoteSelected();
        });
        
        MenuItem deleteItem = new MenuItem(I18n.get("sftp.contextMenu.delete"));
        deleteItem.setOnAction(e -> {
            resetAutoCloseTimer();
            deleteRemoteSelected();
        });
        
        MenuItem ownerItem = new MenuItem(I18n.get("sftp.contextMenu.setOwner"));
        ownerItem.setOnAction(e -> {
            resetAutoCloseTimer();
            setOwnerPermissionsDialog();
        });
        
        MenuItem archiveItem = new MenuItem(I18n.get("sftp.contextMenu.archive"));
        archiveItem.setOnAction(e -> {
            resetAutoCloseTimer();
            createRemoteArchive();
        });
        
        // Editor options
        MenuItem openTextItem = new MenuItem(I18n.get("sftp.contextMenu.openAsText"));
        openTextItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openRemoteFileAsText();
        });
        
        MenuItem openSyntaxItem = new MenuItem(I18n.get("sftp.contextMenu.openWithSyntax"));
        openSyntaxItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openRemoteFileWithSyntax();
        });
        
        MenuItem openImageItem = new MenuItem(I18n.get("sftp.contextMenu.openImage"));
        openImageItem.setOnAction(e -> {
            resetAutoCloseTimer();
            openRemoteImage();
        });
        
        menu.getItems().addAll(
            copyItem, deleteItem, 
            new SeparatorMenuItem(), 
            ownerItem, 
            new SeparatorMenuItem(), 
            archiveItem,
            new SeparatorMenuItem(),
            openTextItem, openSyntaxItem, openImageItem
        );
        
        // Disable items when nothing is selected
        menu.setOnShowing(e -> {
            var selected = remoteTable.getSelectionModel().getSelectedItems();
            boolean hasSelection = selected != null && !selected.isEmpty() && 
                !(selected.size() == 1 && selected.get(0).getName().equals(".."));
            boolean isSingleFile = hasSelection && selected.size() == 1 && selected.get(0).isFile();
            boolean isImageFile = isSingleFile && isImageFileType(selected.get(0).getName());
            
            copyItem.setDisable(!hasSelection);
            deleteItem.setDisable(!hasSelection);
            ownerItem.setDisable(!hasSelection);
            archiveItem.setDisable(!hasSelection);
            openTextItem.setDisable(!isSingleFile);
            openSyntaxItem.setDisable(!isSingleFile);
            openImageItem.setDisable(!isImageFile);
        });
        
        return menu;
    }
    
    private void deleteRemoteSelected() {
        var selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        
        // Filter out ".." and collect items to delete
        List<SFTPManagerDialog.FileItem> toDelete = new java.util.ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                toDelete.add(item);
            }
        }
        
        if (toDelete.isEmpty()) return;
        
        // Confirm deletion
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("sftp.delete.confirm.title"));
        confirm.setHeaderText(I18n.get("sftp.delete.confirm.header", toDelete.size()));
        confirm.setContentText(I18n.get("sftp.delete.confirm.content"));
        applyDarkTheme(confirm);
        
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        
        // Show progress dialog
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle(I18n.get("sftp.delete.progress.title"));
        progressDialog.setHeaderText(I18n.get("sftp.delete.progress.header"));
        applyDarkTheme(progressDialog);
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        Label statusLbl = new Label(I18n.get("sftp.delete.progress.preparing"));
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        content.getChildren().addAll(statusLbl, progressBar);
        progressDialog.getDialogPane().setContent(content);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        
        progressDialog.show();
        
        new Thread(() -> {
            int total = toDelete.size();
            int[] current = {0};
            int[] errors = {0};
            
            for (var item : toDelete) {
                try {
                    final int idx = current[0] + 1;
                    Platform.runLater(() -> {
                        statusLbl.setText(I18n.get("sftp.delete.progress.deleting", item.getName()));
                        progressBar.setProgress((double) idx / total);
                    });
                    
                    deleteRemoteRecursive(item.getPath(), !item.isFile());
                    current[0]++;
                    
                } catch (Exception e) {
                    logger.error("Failed to delete {}: {}", item.getPath(), e.getMessage());
                    errors[0]++;
                }
            }
            
            Platform.runLater(() -> {
                progressDialog.close();
                refreshRemote();
                if (errors[0] > 0) {
                    showError(I18n.get("sftp.delete.error"), 
                        I18n.get("sftp.delete.errorCount", errors[0], total));
                } else {
                    statusLabel.setText(I18n.get("sftp.delete.success", total));
                }
            });
        }, "SFTP-Delete").start();
    }
    
    private void deleteRemoteRecursive(String path, boolean isDir) throws Exception {
        if (isDir) {
            // List directory contents and delete recursively
            List<SftpClient.DirEntry> entries = sftpSession.listFiles(path);
            for (var entry : entries) {
                String name = entry.getFilename();
                if (name.equals(".") || name.equals("..")) continue;
                String childPath = path + "/" + name;
                deleteRemoteRecursive(childPath, entry.getAttributes().isDirectory());
            }
        }
        sftpSession.deleteFile(path);
    }
    
    private void setOwnerPermissionsDialog() {
        var selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        
        // Check connection
        if (sftpSession == null || !sftpSession.isConnected()) {
            showError(I18n.get("error.title"), I18n.get("sftp.notConnected"));
            return;
        }
        
        // Filter out ".."
        List<SFTPManagerDialog.FileItem> items = new java.util.ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                items.add(item);
            }
        }
        if (items.isEmpty()) return;
        
        // Get current values from first selected item for pre-filling
        SFTPManagerDialog.FileItem firstItem = items.get(0);
        String currentOwner = firstItem.getOwner();
        String currentGroup = firstItem.getGroup();
        String currentPerms = getOctalPermissions(firstItem.getPermissions());
        
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.setOwner.title"));
        dialog.setHeaderText(I18n.get("sftp.setOwner.header", items.size()));
        applyDarkTheme(dialog);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        int row = 0;
        
        // Owner field (separate from group)
        grid.add(new Label(I18n.get("sftp.setOwner.ownerUser")), 0, row);
        TextField ownerField = new TextField(currentOwner);
        ownerField.setPromptText("user");
        grid.add(ownerField, 1, row++);
        
        // Group field
        grid.add(new Label(I18n.get("sftp.setOwner.ownerGroup")), 0, row);
        TextField groupField = new TextField(currentGroup);
        groupField.setPromptText("group");
        grid.add(groupField, 1, row++);
        
        // Permissions field with current value
        grid.add(new Label(I18n.get("sftp.setOwner.permissions")), 0, row);
        TextField permField = new TextField(currentPerms);
        permField.setPromptText("755");
        grid.add(permField, 1, row++);
        
        // Recursive checkbox
        CheckBox recursiveCheck = new CheckBox(I18n.get("sftp.setOwner.recursive"));
        grid.add(recursiveCheck, 0, row++, 2, 1);
        
        // Info
        Label info = new Label(I18n.get("sftp.setOwner.infoSeparate"));
        info.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        info.setWrapText(true);
        info.setMaxWidth(300);
        grid.add(info, 0, row++, 2, 1);
        
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        dialog.showAndWait().ifPresent(result -> {
            String newOwner = ownerField.getText().trim();
            String newGroup = groupField.getText().trim();
            String newPerms = permField.getText().trim();
            boolean recursive = recursiveCheck.isSelected();
            
            // Check if anything actually changed
            boolean ownerChanged = !newOwner.equals(currentOwner);
            boolean groupChanged = !newGroup.equals(currentGroup);
            boolean permsChanged = !newPerms.equals(currentPerms);
            
            if (!ownerChanged && !groupChanged && !permsChanged) {
                // Nothing changed, no need to apply
                return;
            }
            
            // Build owner:group string only if owner or group changed
            String ownerGroup = "";
            if (ownerChanged || groupChanged) {
                if (!newOwner.isEmpty() && !newGroup.isEmpty()) {
                    ownerGroup = newOwner + ":" + newGroup;
                } else if (!newOwner.isEmpty()) {
                    ownerGroup = newOwner;
                } else if (!newGroup.isEmpty()) {
                    ownerGroup = ":" + newGroup;
                }
            }
            
            // Only pass permissions if changed
            String permsToApply = permsChanged ? newPerms : "";
            
            // Apply changes with progress dialog
            applyOwnerPermissions(items, ownerGroup, permsToApply, recursive);
        });
    }
    
    /**
     * Converts symbolic permissions (e.g., "rwxr-xr-x") to octal (e.g., "755").
     */
    private String getOctalPermissions(String symbolic) {
        if (symbolic == null || symbolic.isEmpty()) return "";
        
        // If already looks like octal, return as-is
        if (symbolic.matches("\\d{3,4}")) return symbolic;
        
        // Handle symbolic format like "rwxr-xr-x" or "-rwxr-xr-x" (with leading type char)
        String perms = symbolic;
        if (perms.length() == 10) {
            perms = perms.substring(1); // Remove leading type character
        }
        if (perms.length() != 9) return "";
        
        try {
            int owner = 0, group = 0, other = 0;
            
            // Owner permissions (chars 0-2)
            if (perms.charAt(0) == 'r') owner += 4;
            if (perms.charAt(1) == 'w') owner += 2;
            if (perms.charAt(2) == 'x' || perms.charAt(2) == 's') owner += 1;
            
            // Group permissions (chars 3-5)
            if (perms.charAt(3) == 'r') group += 4;
            if (perms.charAt(4) == 'w') group += 2;
            if (perms.charAt(5) == 'x' || perms.charAt(5) == 's') group += 1;
            
            // Other permissions (chars 6-8)
            if (perms.charAt(6) == 'r') other += 4;
            if (perms.charAt(7) == 'w') other += 2;
            if (perms.charAt(8) == 'x' || perms.charAt(8) == 't') other += 1;
            
            return "" + owner + group + other;
        } catch (Exception e) {
            return "";
        }
    }
    
    private void applyOwnerPermissions(List<SFTPManagerDialog.FileItem> items, String owner, String perms, boolean recursive) {
        // Show progress dialog
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle(I18n.get("sftp.setOwner.title"));
        progressDialog.setHeaderText(I18n.get("sftp.setOwner.applying"));
        applyDarkTheme(progressDialog);
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        Label statusLbl = new Label(I18n.get("sftp.setOwner.applying"));
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        content.getChildren().addAll(statusLbl, progressBar);
        progressDialog.getDialogPane().setContent(content);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        progressDialog.show();
        
        new Thread(() -> {
            int[] success = {0};
            int[] errors = {0};
            StringBuilder errorMessages = new StringBuilder();
            int total = items.size();
            
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i);
                int currentIndex = i;
                Platform.runLater(() -> {
                    statusLbl.setText(item.getName());
                    progressBar.setProgress((double) currentIndex / total);
                });
                
                try {
                    String recursiveFlag = recursive ? "-R " : "";
                    
                    if (!owner.isEmpty()) {
                        String cmd = "chown " + recursiveFlag + "'" + owner.replace("'", "'\\''") + "' '" + 
                            item.getPath().replace("'", "'\\''") + "'";
                        sftpSession.executeCommand(cmd);
                    }
                    
                    if (!perms.isEmpty()) {
                        String cmd = "chmod " + recursiveFlag + perms + " '" + 
                            item.getPath().replace("'", "'\\''") + "'";
                        sftpSession.executeCommand(cmd);
                    }
                    
                    success[0]++;
                } catch (Exception e) {
                    logger.error("Failed to set owner/permissions for {}: {}", item.getPath(), e.getMessage());
                    errors[0]++;
                    // Collect error messages (limit to first 3 to avoid huge dialogs)
                    if (errorMessages.length() < 500) {
                        if (errorMessages.length() > 0) errorMessages.append("\n");
                        errorMessages.append(item.getName()).append(": ").append(e.getMessage());
                    }
                }
            }
            
            final String errorMsg = errorMessages.toString();
            Platform.runLater(() -> {
                progressDialog.close();
                refreshRemote();
                if (errors[0] > 0) {
                    String details = I18n.get("sftp.setOwner.errorCount", errors[0], total);
                    if (!errorMsg.isEmpty()) {
                        details += "\n\n" + errorMsg;
                    }
                    showError(I18n.get("sftp.setOwner.error"), details);
                } else {
                    statusLabel.setText(I18n.get("sftp.setOwner.success", success[0]));
                }
            });
        }, "SFTP-SetOwner").start();
    }
    
    // Archive format enum
    private enum ArchiveFormat {
        ZIP("zip", ".zip"),
        TAR_BZ2("tar.bz2", ".tar.bz2"),
        SEVEN_ZIP("7z", ".7z");
        
        private final String displayName;
        private final String extension;
        
        ArchiveFormat(String displayName, String extension) {
            this.displayName = displayName;
            this.extension = extension;
        }
        
        public String getDisplayName() { return displayName; }
        public String getExtension() { return extension; }
        
        @Override
        public String toString() { return displayName; }
    }
    
    private void createRemoteArchive() {
        var selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            showError(I18n.get("error.title"), I18n.get("sftp.error.selectFilesToArchive"));
            return;
        }
        
        // Filter out ".." entry and collect file paths
        List<String> filesToArchive = new java.util.ArrayList<>();
        long estimatedSize = 0;
        for (var item : selected) {
            if (item.getName().equals("..")) continue;
            filesToArchive.add(item.getPath());
            // Estimate size (rough, since directories can't be easily calculated)
            String sizeStr = item.getSize();
            if (sizeStr != null && !sizeStr.equals("-")) {
                try {
                    if (sizeStr.contains("KB")) {
                        estimatedSize += (long) (Double.parseDouble(sizeStr.replace(" KB", "")) * 1024);
                    } else if (sizeStr.contains("MB")) {
                        estimatedSize += (long) (Double.parseDouble(sizeStr.replace(" MB", "")) * 1024 * 1024);
                    } else if (sizeStr.contains("GB")) {
                        estimatedSize += (long) (Double.parseDouble(sizeStr.replace(" GB", "")) * 1024 * 1024 * 1024);
                    } else if (sizeStr.contains("B")) {
                        estimatedSize += Long.parseLong(sizeStr.replace(" B", ""));
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
        
        if (filesToArchive.isEmpty()) {
            showError(I18n.get("error.title"), I18n.get("sftp.error.selectFilesToArchive"));
            return;
        }
        
        // Get default settings from GlobalSettings
        String defaultArchivePath = "/tmp";
        int defaultCompression = 6;
        try {
            de.kortty.core.GlobalSettingsManager gsm = app.getGlobalSettingsManager();
            if (gsm != null && gsm.getSettings() != null) {
                defaultArchivePath = gsm.getSettings().getSftpDefaultZipPath();
                defaultCompression = gsm.getSettings().getSftpDefaultZipCompression();
            }
        } catch (Exception e) {
            logger.debug("Could not get global settings: {}", e.getMessage());
        }
        
        // Generate default filename with timestamp
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String defaultFilename = defaultArchivePath + "/archive_" + timestamp;
        
        // Show archive creation dialog
        showArchiveDialog(filesToArchive, defaultFilename, defaultCompression, estimatedSize);
    }
    
    // Check which archive tools are available on the remote server
    private java.util.Map<ArchiveFormat, Boolean> checkAvailableArchiveTools() {
        java.util.Map<ArchiveFormat, Boolean> available = new java.util.EnumMap<>(ArchiveFormat.class);
        
        // Default all to false
        for (ArchiveFormat fmt : ArchiveFormat.values()) {
            available.put(fmt, false);
        }
        
        if (sftpSession == null || !sftpSession.isConnected()) {
            return available;
        }
        
        try {
            // Check zip
            String zipCheck = sftpSession.executeCommand("which zip 2>/dev/null || command -v zip 2>/dev/null || echo ''");
            available.put(ArchiveFormat.ZIP, !zipCheck.trim().isEmpty());
            
            // Check tar (always available on Unix)
            String tarCheck = sftpSession.executeCommand("which tar 2>/dev/null || command -v tar 2>/dev/null || echo ''");
            available.put(ArchiveFormat.TAR_BZ2, !tarCheck.trim().isEmpty());
            
            // Check 7z or 7za
            String sevenZCheck = sftpSession.executeCommand("which 7z 2>/dev/null || which 7za 2>/dev/null || command -v 7z 2>/dev/null || command -v 7za 2>/dev/null || echo ''");
            available.put(ArchiveFormat.SEVEN_ZIP, !sevenZCheck.trim().isEmpty());
            
        } catch (Exception e) {
            logger.warn("Could not check archive tool availability: {}", e.getMessage());
            // Assume zip and tar are available as they usually are
            available.put(ArchiveFormat.ZIP, true);
            available.put(ArchiveFormat.TAR_BZ2, true);
        }
        
        logger.debug("Archive tool availability: {}", available);
        return available;
    }
    
    private void showArchiveDialog(List<String> filesToArchive, String defaultFilename, int defaultCompression, long estimatedSize) {
        // Check available tools first
        java.util.Map<ArchiveFormat, Boolean> availableTools = checkAvailableArchiveTools();
        
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.archive"));
        dialog.setHeaderText(I18n.get("sftp.archive.dialogHeader", filesToArchive.size()));
        dialog.setResizable(true);
        applyDarkTheme(dialog);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        int row = 0;
        
        // Archive format with availability info
        grid.add(new Label(I18n.get("sftp.archive.format")), 0, row);
        ComboBox<String> formatCombo = new ComboBox<>();
        
        // Add formats with availability indicator
        for (ArchiveFormat fmt : ArchiveFormat.values()) {
            boolean isAvailable = availableTools.getOrDefault(fmt, false);
            String displayName = fmt.getDisplayName();
            if (!isAvailable) {
                displayName += " " + I18n.get("sftp.archive.notInstalled");
            }
            formatCombo.getItems().add(displayName);
        }
        
        // Select first available format (prefer ZIP)
        int selectedIndex = 0;
        for (int i = 0; i < ArchiveFormat.values().length; i++) {
            if (availableTools.getOrDefault(ArchiveFormat.values()[i], false)) {
                selectedIndex = i;
                break;
            }
        }
        formatCombo.getSelectionModel().select(selectedIndex);
        grid.add(formatCombo, 1, row++);
        
        // Helper to get selected format
        java.util.function.Supplier<ArchiveFormat> getSelectedFormat = () -> {
            int idx = formatCombo.getSelectionModel().getSelectedIndex();
            return idx >= 0 && idx < ArchiveFormat.values().length ? ArchiveFormat.values()[idx] : ArchiveFormat.ZIP;
        };
        
        // Set initial extension based on first available format
        String initialExtension = ArchiveFormat.values()[selectedIndex].getExtension();
        
        // Archive file path
        grid.add(new Label(I18n.get("sftp.archive.path")), 0, row);
        TextField pathField = new TextField(defaultFilename + initialExtension);
        pathField.setPrefWidth(350);
        grid.add(pathField, 1, row++);
        
        // Compression level
        grid.add(new Label(I18n.get("sftp.archive.compression")), 0, row);
        ComboBox<String> compressionCombo = new ComboBox<>();
        compressionCombo.getItems().addAll(
            "0 - " + I18n.get("sftp.archive.noCompression"),
            "1 - " + I18n.get("sftp.archive.fastest"),
            "3 - " + I18n.get("sftp.archive.fast"),
            "6 - " + I18n.get("sftp.archive.normal"),
            "9 - " + I18n.get("sftp.archive.best")
        );
        compressionCombo.getSelectionModel().select(
            defaultCompression == 0 ? 0 :
            defaultCompression <= 1 ? 1 :
            defaultCompression <= 3 ? 2 :
            defaultCompression <= 6 ? 3 : 4
        );
        grid.add(compressionCombo, 1, row++);
        
        // Owner (optional)
        grid.add(new Label(I18n.get("sftp.archive.owner")), 0, row);
        TextField ownerField = new TextField();
        ownerField.setPromptText(I18n.get("sftp.archive.ownerPrompt"));
        grid.add(ownerField, 1, row++);
        
        // Permissions (optional)
        grid.add(new Label(I18n.get("sftp.archive.permissions")), 0, row);
        TextField permissionsField = new TextField("644");
        permissionsField.setPromptText("644");
        grid.add(permissionsField, 1, row++);
        
        // Password (optional) - only for ZIP and 7z
        grid.add(new Label(I18n.get("sftp.archive.password")), 0, row);
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("sftp.archive.passwordPrompt"));
        grid.add(passwordField, 1, row++);
        
        // Update extension and password field when format changes
        formatCombo.setOnAction(e -> {
            String currentPath = pathField.getText();
            // Remove old extension and add new one
            for (ArchiveFormat fmt : ArchiveFormat.values()) {
                if (currentPath.endsWith(fmt.getExtension())) {
                    currentPath = currentPath.substring(0, currentPath.length() - fmt.getExtension().length());
                    break;
                }
            }
            pathField.setText(currentPath + getSelectedFormat.get().getExtension());
            
            // Disable password for tar.bz2
            ArchiveFormat selectedFmt = getSelectedFormat.get();
            passwordField.setDisable(selectedFmt == ArchiveFormat.TAR_BZ2);
            if (selectedFmt == ArchiveFormat.TAR_BZ2) {
                passwordField.clear();
            }
        });
        
        // Separator
        grid.add(new Separator(), 0, row++, 2, 1);
        
        // Progress area (initially hidden)
        Label progressLabel = new Label(I18n.get("sftp.archive.preparing"));
        progressLabel.setVisible(false);
        grid.add(progressLabel, 0, row++, 2, 1);
        
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setVisible(false);
        grid.add(progressBar, 0, row++, 2, 1);
        
        Label timeLabel = new Label();
        timeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        timeLabel.setVisible(false);
        grid.add(timeLabel, 0, row++, 2, 1);
        
        Label sizeLabel = new Label(I18n.get("sftp.archive.estimatedSize", formatSize(estimatedSize)));
        sizeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        grid.add(sizeLabel, 0, row++, 2, 1);
        
        dialog.getDialogPane().setContent(grid);
        
        // Buttons
        ButtonType createButton = new ButtonType(I18n.get("sftp.archive.create"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButton, ButtonType.CANCEL);
        
        // Get the create button for enabling/disabling
        Button createBtn = (Button) dialog.getDialogPane().lookupButton(createButton);
        
        dialog.setResultConverter(buttonType -> {
            if (buttonType == createButton) {
                String archivePath = pathField.getText().trim();
                if (archivePath.isEmpty()) {
                    showError(I18n.get("error.title"), I18n.get("sftp.archive.pathRequired"));
                    return null;
                }
                
                ArchiveFormat format = getSelectedFormat.get();
                
                // Check if the tool is available
                if (!availableTools.getOrDefault(format, false)) {
                    showError(I18n.get("error.title"), I18n.get("sftp.archive.toolNotInstalled", format.getDisplayName()));
                    return null;
                }
                
                // Get compression level from selection
                int compression = switch (compressionCombo.getSelectionModel().getSelectedIndex()) {
                    case 0 -> 0;
                    case 1 -> 1;
                    case 2 -> 3;
                    case 3 -> 6;
                    case 4 -> 9;
                    default -> 6;
                };
                
                String owner = ownerField.getText().trim();
                String permissions = permissionsField.getText().trim();
                String password = passwordField.getText();
                
                // Disable controls during creation
                createBtn.setDisable(true);
                pathField.setDisable(true);
                formatCombo.setDisable(true);
                compressionCombo.setDisable(true);
                ownerField.setDisable(true);
                permissionsField.setDisable(true);
                passwordField.setDisable(true);
                
                // Show progress
                progressLabel.setVisible(true);
                progressBar.setVisible(true);
                timeLabel.setVisible(true);
                progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                
                // Create archive in background
                executeRemoteArchiveCreation(dialog, filesToArchive, archivePath, format, compression, 
                        owner, permissions, password, progressLabel, progressBar, timeLabel, sizeLabel);
            }
            return null;
        });
        
        dialog.show();
    }
    
    private void executeRemoteArchiveCreation(Dialog<Void> dialog, List<String> filesToArchive, String archivePath,
                                              ArchiveFormat format, int compression, String owner, String permissions, 
                                              String password, Label progressLabel, ProgressBar progressBar, 
                                              Label timeLabel, Label sizeLabel) {
        long startTime = System.currentTimeMillis();
        
        // Show progress in status bar
        Platform.runLater(() -> {
            statusProgressBar.setVisible(true);
            statusProgressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
            statusLabel.setText(I18n.get("sftp.archive.creating"));
        });
        
        // Timer for elapsed time
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            long elapsed = System.currentTimeMillis() - startTime;
            long seconds = (elapsed / 1000) % 60;
            long minutes = (elapsed / 1000) / 60;
            timeLabel.setText(I18n.get("sftp.archive.elapsed", String.format("%02d:%02d", minutes, seconds)));
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        
        new Thread(() -> {
            try {
                // Build the archive command based on format
                String archiveCommand = buildArchiveCommand(filesToArchive, archivePath, format, compression, password);
                
                logger.info("Executing remote archive command: {}", archiveCommand.replaceAll("-P '[^']*'", "-P '***'").replaceAll("-p'[^']*'", "-p'***'"));
                
                Platform.runLater(() -> progressLabel.setText(I18n.get("sftp.archive.creating")));
                
                // Execute the archive command with progress
                de.kortty.core.SFTPSession.CommandResult result = sftpSession.executeCommandWithProgress(archiveCommand, line -> {
                    Platform.runLater(() -> {
                        progressLabel.setText(line);
                    });
                });
                
                // Handle exit codes
                boolean hasWarning = false;
                
                // ZIP exit code 18 means some files were skipped
                if (format == ArchiveFormat.ZIP && result.getExitCode() == 18) {
                    hasWarning = true;
                    logger.warn("Archive created with warnings (exit code 18): {}", result.getStderr());
                } else if (result.getExitCode() != 0) {
                    String errorMsg = result.getStderr();
                    if (errorMsg == null || errorMsg.trim().isEmpty()) {
                        errorMsg = "Exit code: " + result.getExitCode();
                    }
                    logger.error("Archive command failed with exit code {}: {}", result.getExitCode(), errorMsg);
                    throw new Exception(errorMsg);
                }
                
                final boolean finalHasWarning = hasWarning;
                
                // Set owner if specified
                if (owner != null && !owner.isEmpty()) {
                    String chownCmd = "chown '" + owner.replace("'", "'\\''") + "' '" + archivePath.replace("'", "'\\''") + "'";
                    try {
                        sftpSession.executeCommand(chownCmd);
                    } catch (Exception e) {
                        logger.warn("Could not set owner: {}", e.getMessage());
                    }
                }
                
                // Set permissions if specified
                if (permissions != null && !permissions.isEmpty()) {
                    String chmodCmd = "chmod " + permissions + " '" + archivePath.replace("'", "'\\''") + "'";
                    try {
                        sftpSession.executeCommand(chmodCmd);
                    } catch (Exception e) {
                        logger.warn("Could not set permissions: {}", e.getMessage());
                    }
                }
                
                // Get actual file size and duration
                String sizeCmd = "stat -c%s '" + archivePath.replace("'", "'\\''") + "' 2>/dev/null || stat -f%z '" + archivePath.replace("'", "'\\''") + "'";
                String actualSize = "";
                long actualSizeBytes = 0;
                try {
                    actualSize = sftpSession.executeCommand(sizeCmd).trim();
                    actualSizeBytes = Long.parseLong(actualSize);
                } catch (Exception e) {
                    logger.debug("Could not get file size: {}", e.getMessage());
                }
                
                final long finalSizeBytes = actualSizeBytes;
                final long durationSeconds = (System.currentTimeMillis() - startTime) / 1000;
                
                Platform.runLater(() -> {
                    timer.stop();
                    progressBar.setProgress(1.0);
                    
                    if (finalHasWarning) {
                        progressLabel.setText(I18n.get("sftp.archive.successWithWarning"));
                        progressLabel.setStyle("-fx-text-fill: orange;");
                    } else {
                        progressLabel.setText(I18n.get("sftp.archive.success"));
                    }
                    
                    if (finalSizeBytes > 0) {
                        sizeLabel.setText(I18n.get("sftp.archive.actualSize", formatSize(finalSizeBytes)));
                    }
                    
                    // Update status bar with size and duration
                    statusProgressBar.setProgress(1.0);
                    statusLabel.setText(String.format("%s (%s, %s)", 
                        I18n.get("sftp.archiveCreated", archivePath),
                        formatSize(finalSizeBytes),
                        formatDuration(durationSeconds)));
                    
                    refreshRemote();
                    
                    // Hide progress bar after delay
                    Timeline hideProgressDelay = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
                        statusProgressBar.setVisible(false);
                        statusProgressBar.setProgress(0);
                    }));
                    hideProgressDelay.play();
                    
                    int delaySeconds = finalHasWarning ? 4 : 2;
                    Timeline closeDelay = new Timeline(new KeyFrame(Duration.seconds(delaySeconds), e -> dialog.close()));
                    closeDelay.play();
                });
                
            } catch (Exception e) {
                logger.error("Remote archive creation failed", e);
                Platform.runLater(() -> {
                    timer.stop();
                    progressBar.setProgress(0);
                    progressLabel.setText(I18n.get("sftp.archive.error", e.getMessage()));
                    statusProgressBar.setVisible(false);
                    statusProgressBar.setProgress(0);
                    statusLabel.setText(I18n.get("sftp.error.archive") + ": " + e.getMessage());
                    showError(I18n.get("sftp.error.archive"), e.getMessage());
                });
            }
        }, "Remote-Archive-Creator").start();
    }
    
    private String buildArchiveCommand(List<String> files, String archivePath, ArchiveFormat format, int compression, String password) {
        StringBuilder cmd = new StringBuilder();
        String escapedPath = archivePath.replace("'", "'\\''");
        
        switch (format) {
            case ZIP:
                if (password != null && !password.isEmpty()) {
                    cmd.append("zip -r -").append(compression)
                       .append(" -P '").append(password.replace("'", "'\\''")).append("' ");
                } else {
                    cmd.append("zip -r -").append(compression).append(" ");
                }
                cmd.append("'").append(escapedPath).append("' ");
                for (String file : files) {
                    cmd.append("'").append(file.replace("'", "'\\''")).append("' ");
                }
                break;
                
            case TAR_BZ2:
                // tar with bzip2 compression
                // -j = bzip2, compression level via BZIP2 env var
                cmd.append("BZIP2=-").append(compression).append(" tar -cjf '")
                   .append(escapedPath).append("' ");
                for (String file : files) {
                    cmd.append("'").append(file.replace("'", "'\\''")).append("' ");
                }
                break;
                
            case SEVEN_ZIP:
                // 7z archive - try 7z first, then 7za (p7zip uses 7za on some systems)
                // -mx=compression level, -p for password
                cmd.append("$(command -v 7z || command -v 7za) a -mx=").append(compression);
                if (password != null && !password.isEmpty()) {
                    cmd.append(" -p'").append(password.replace("'", "'\\''")).append("' -mhe=on");
                }
                cmd.append(" '").append(escapedPath).append("' ");
                for (String file : files) {
                    cmd.append("'").append(file.replace("'", "'\\''")).append("' ");
                }
                break;
        }
        
        return cmd.toString().trim();
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    /**
     * Applies the dark theme stylesheet to a dialog.
     */
    private void applyDarkTheme(Dialog<?> dialog) {
        var cssResource = getClass().getResource("/styles/terminal.css");
        if (cssResource != null) {
            dialog.getDialogPane().getStylesheets().add(cssResource.toExternalForm());
        }
    }
    
    private String formatDuration(long seconds) {
        if (seconds < 60) return seconds + "s";
        long minutes = seconds / 60;
        long secs = seconds % 60;
        if (minutes < 60) return String.format("%dm %ds", minutes, secs);
        long hours = minutes / 60;
        minutes = minutes % 60;
        return String.format("%dh %dm %ds", hours, minutes, secs);
    }
    
    
    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            applyDarkTheme(alert);
            alert.showAndWait();
        });
    }
    
    private void deleteLocalSelected() {
        var selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        
        // Filter out ".." and collect items to delete
        List<SFTPManagerDialog.FileItem> toDelete = new java.util.ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                toDelete.add(item);
            }
        }
        
        if (toDelete.isEmpty()) return;
        
        // Confirm deletion
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("sftp.delete.confirm.title"));
        confirm.setHeaderText(I18n.get("sftp.delete.confirm.header", toDelete.size()));
        confirm.setContentText(I18n.get("sftp.delete.confirm.content"));
        applyDarkTheme(confirm);
        
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        
        // Show progress dialog
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle(I18n.get("sftp.delete.progress.title"));
        progressDialog.setHeaderText(I18n.get("sftp.delete.progress.header"));
        applyDarkTheme(progressDialog);
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        Label statusLbl = new Label(I18n.get("sftp.delete.progress.preparing"));
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        content.getChildren().addAll(statusLbl, progressBar);
        progressDialog.getDialogPane().setContent(content);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        progressDialog.show();
        
        // Delete in background
        new Thread(() -> {
            int total = toDelete.size();
            int success = 0;
            int failed = 0;
            
            for (int i = 0; i < toDelete.size(); i++) {
                var item = toDelete.get(i);
                int currentIndex = i;
                Platform.runLater(() -> {
                    statusLbl.setText(I18n.get("sftp.delete.progress.deleting", item.getName()));
                    progressBar.setProgress((double) currentIndex / total);
                });
                
                try {
                    deleteLocalRecursive(Paths.get(item.getPath()));
                    success++;
                } catch (Exception e) {
                    logger.error("Failed to delete local file: {}", item.getPath(), e);
                    failed++;
                }
            }
            
            int finalSuccess = success;
            int finalFailed = failed;
            Platform.runLater(() -> {
                progressDialog.close();
                refreshLocal();
                
                if (finalFailed == 0) {
                    statusLabel.setText(I18n.get("sftp.delete.success", finalSuccess));
                } else {
                    showError(I18n.get("sftp.delete.error"), 
                        I18n.get("sftp.delete.errorCount", finalSuccess, finalFailed));
                }
            });
        }, "Local-Delete").start();
    }
    
    private void deleteLocalRecursive(Path path) throws Exception {
        if (Files.isDirectory(path)) {
            try (var stream = Files.walk(path)) {
                stream.sorted(java.util.Comparator.reverseOrder())
                      .forEach(p -> {
                          try {
                              Files.delete(p);
                          } catch (Exception e) {
                              throw new RuntimeException(e);
                          }
                      });
            }
        } else {
            Files.delete(path);
        }
    }
    
    private void setLocalOwnerPermissionsDialog() {
        var selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        
        // Filter out ".."
        List<SFTPManagerDialog.FileItem> items = new java.util.ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                items.add(item);
            }
        }
        
        if (items.isEmpty()) return;
        
        // Get current values from first selected item
        SFTPManagerDialog.FileItem firstItem = items.get(0);
        String currentOwner = getLocalFileOwner(Paths.get(firstItem.getPath()));
        String currentGroup = getLocalFileGroup(Paths.get(firstItem.getPath()));
        String currentPerms = getOctalPermissions(firstItem.getPermissions());
        
        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("win");
        
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.setOwner.title"));
        dialog.setHeaderText(I18n.get("sftp.setOwner.header", items.size()));
        applyDarkTheme(dialog);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        int row = 0;
        
        // Owner field (separate from group)
        grid.add(new Label(I18n.get("sftp.setOwner.ownerUser")), 0, row);
        TextField ownerField = new TextField(currentOwner);
        ownerField.setPromptText("user");
        if (isWindows) {
            ownerField.setDisable(true);
            ownerField.setPromptText(I18n.get("sftp.setOwner.notAvailableWindows"));
        }
        grid.add(ownerField, 1, row++);
        
        // Group field
        grid.add(new Label(I18n.get("sftp.setOwner.ownerGroup")), 0, row);
        TextField groupField = new TextField(currentGroup);
        groupField.setPromptText("group");
        if (isWindows) {
            groupField.setDisable(true);
            groupField.setPromptText(I18n.get("sftp.setOwner.notAvailableWindows"));
        }
        grid.add(groupField, 1, row++);
        
        // Permissions field with current value
        grid.add(new Label(I18n.get("sftp.setOwner.permissions")), 0, row);
        TextField permissionsField = new TextField(currentPerms);
        permissionsField.setPromptText("755");
        grid.add(permissionsField, 1, row++);
        
        // Recursive checkbox
        CheckBox recursiveCheck = new CheckBox(I18n.get("sftp.setOwner.recursive"));
        grid.add(recursiveCheck, 0, row++, 2, 1);
        
        // Info label
        Label infoLabel = new Label(I18n.get("sftp.setOwner.infoSeparate"));
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        grid.add(infoLabel, 0, row++, 2, 1);
        
        dialog.getDialogPane().setContent(grid);
        dialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        dialog.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                String newOwner = ownerField.getText().trim();
                String newGroup = groupField.getText().trim();
                String newPerms = permissionsField.getText().trim();
                boolean recursive = recursiveCheck.isSelected();
                
                // Check if anything actually changed
                boolean ownerChanged = !newOwner.equals(currentOwner);
                boolean groupChanged = !newGroup.equals(currentGroup);
                boolean permsChanged = !newPerms.equals(currentPerms);
                
                if (!ownerChanged && !groupChanged && !permsChanged) {
                    // Nothing changed
                    return;
                }
                
                // Build owner:group string only if owner or group changed
                String ownerGroup = "";
                if (ownerChanged || groupChanged) {
                    if (!newOwner.isEmpty() && !newGroup.isEmpty()) {
                        ownerGroup = newOwner + ":" + newGroup;
                    } else if (!newOwner.isEmpty()) {
                        ownerGroup = newOwner;
                    } else if (!newGroup.isEmpty()) {
                        ownerGroup = ":" + newGroup;
                    }
                }
                
                // Only pass permissions if changed
                String permsToApply = permsChanged ? newPerms : "";
                
                applyLocalOwnerPermissions(items, ownerGroup, permsToApply, recursive);
            }
        });
    }
    
    /**
     * Gets the owner name of a local file.
     */
    private String getLocalFileOwner(Path path) {
        try {
            java.nio.file.attribute.UserPrincipal owner = Files.getOwner(path);
            return owner != null ? owner.getName() : "";
        } catch (Exception e) {
            return "";
        }
    }
    
    /**
     * Gets the group name of a local file.
     */
    private String getLocalFileGroup(Path path) {
        try {
            java.nio.file.attribute.PosixFileAttributes attrs = 
                Files.readAttributes(path, java.nio.file.attribute.PosixFileAttributes.class);
            return attrs.group().getName();
        } catch (Exception e) {
            return "";
        }
    }
    
    private void applyLocalOwnerPermissions(List<SFTPManagerDialog.FileItem> items, String owner, 
                                            String permissions, boolean recursive) {
        Dialog<Void> progressDialog = new Dialog<>();
        progressDialog.setTitle(I18n.get("sftp.setOwner.title"));
        progressDialog.setHeaderText(I18n.get("sftp.setOwner.applying"));
        applyDarkTheme(progressDialog);
        
        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        Label statusLbl = new Label();
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(300);
        content.getChildren().addAll(statusLbl, progressBar);
        progressDialog.getDialogPane().setContent(content);
        progressDialog.getDialogPane().getButtonTypes().add(ButtonType.CANCEL);
        progressDialog.show();
        
        new Thread(() -> {
            int success = 0;
            int failed = 0;
            
            for (int i = 0; i < items.size(); i++) {
                var item = items.get(i);
                int currentIndex = i;
                Platform.runLater(() -> {
                    statusLbl.setText(item.getName());
                    progressBar.setProgress((double) currentIndex / items.size());
                });
                
                try {
                    Path path = Paths.get(item.getPath());
                    
                    // Set permissions (works on Unix-like systems)
                    if (!permissions.isEmpty()) {
                        try {
                            Files.setPosixFilePermissions(path, 
                                java.nio.file.attribute.PosixFilePermissions.fromString(octalToPosix(permissions)));
                        } catch (UnsupportedOperationException e) {
                            // On Windows, POSIX permissions are not supported
                            logger.debug("POSIX permissions not supported on this system");
                        }
                    }
                    
                    // Note: Java doesn't provide direct API for chown
                    // This would require native calls or ProcessBuilder
                    
                    success++;
                } catch (Exception e) {
                    logger.error("Failed to set owner/permissions for: {}", item.getPath(), e);
                    failed++;
                }
            }
            
            int finalSuccess = success;
            int finalFailed = failed;
            Platform.runLater(() -> {
                progressDialog.close();
                refreshLocal();
                
                if (finalFailed == 0) {
                    statusLabel.setText(I18n.get("sftp.setOwner.success", finalSuccess));
                } else {
                    showError(I18n.get("sftp.setOwner.error"), 
                        I18n.get("sftp.setOwner.errorCount", finalSuccess, finalFailed));
                }
            });
        }, "Local-SetOwner").start();
    }
    
    private String octalToPosix(String octal) {
        if (octal.length() != 3) return "rw-r--r--";
        
        StringBuilder posix = new StringBuilder();
        for (char c : octal.toCharArray()) {
            int val = Character.digit(c, 10);
            posix.append((val & 4) != 0 ? 'r' : '-');
            posix.append((val & 2) != 0 ? 'w' : '-');
            posix.append((val & 1) != 0 ? 'x' : '-');
        }
        return posix.toString();
    }
    
    private void createLocalArchive() {
        var selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) return;
        
        // Filter out ".." and collect items
        List<Path> filesToArchive = new java.util.ArrayList<>();
        for (var item : selected) {
            if (!item.getName().equals("..")) {
                filesToArchive.add(Paths.get(item.getPath()));
            }
        }
        
        if (filesToArchive.isEmpty()) {
            showError(I18n.get("error.title"), I18n.get("sftp.error.selectFilesToArchive"));
            return;
        }
        
        // Estimate size
        long estimatedSize = 0;
        for (Path path : filesToArchive) {
            estimatedSize += estimateLocalSize(path);
        }
        
        // Generate default filename
        String timestamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        String defaultFilename = currentLocalPath.resolve("archive_" + timestamp).toString();
        
        // Get default settings
        int defaultCompression = 6; // Default compression level
        if (app != null && app.getGlobalSettingsManager() != null) {
            GlobalSettings globalSettings = app.getGlobalSettingsManager().getSettings();
            if (globalSettings.getSftpDefaultZipCompression() != null) {
                defaultCompression = globalSettings.getSftpDefaultZipCompression();
            }
        }
        
        showLocalArchiveDialog(filesToArchive, defaultFilename, defaultCompression, estimatedSize);
    }
    
    private long estimateLocalSize(Path path) {
        try {
            if (Files.isDirectory(path)) {
                try (var stream = Files.walk(path)) {
                    return stream.filter(Files::isRegularFile)
                                 .mapToLong(p -> {
                                     try { return Files.size(p); }
                                     catch (Exception e) { return 0; }
                                 })
                                 .sum();
                }
            } else {
                return Files.size(path);
            }
        } catch (Exception e) {
            return 0;
        }
    }
    
    // Check which archive tools are available on the local system
    private java.util.Map<ArchiveFormat, Boolean> checkLocalArchiveTools() {
        java.util.Map<ArchiveFormat, Boolean> available = new java.util.EnumMap<>(ArchiveFormat.class);
        
        // ZIP is always available via zip4j library
        available.put(ArchiveFormat.ZIP, true);
        
        // TAR is always available via Apache Commons Compress
        available.put(ArchiveFormat.TAR_BZ2, true);
        
        // Check if 7z command is available locally
        try {
            Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", "command -v 7z || command -v 7za"});
            java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(process.getInputStream()));
            String result = reader.readLine();
            process.waitFor();
            available.put(ArchiveFormat.SEVEN_ZIP, result != null && !result.isEmpty());
        } catch (Exception e) {
            logger.debug("Could not check for local 7z: {}", e.getMessage());
            available.put(ArchiveFormat.SEVEN_ZIP, false);
        }
        
        logger.debug("Local archive tool availability: {}", available);
        return available;
    }
    
    private void showLocalArchiveDialog(List<Path> filesToArchive, String defaultFilename, 
                                        int defaultCompression, long estimatedSize) {
        // Check which archive tools are available locally
        java.util.Map<ArchiveFormat, Boolean> availableTools = checkLocalArchiveTools();
        
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.archive"));
        dialog.setHeaderText(I18n.get("sftp.archive.dialogHeader", filesToArchive.size()));
        dialog.setResizable(true);
        applyDarkTheme(dialog);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        int row = 0;
        
        // Archive format with availability info
        grid.add(new Label(I18n.get("sftp.archive.format")), 0, row);
        ComboBox<String> formatCombo = new ComboBox<>();
        
        // Add formats with availability indicator
        for (ArchiveFormat fmt : ArchiveFormat.values()) {
            boolean isAvailable = availableTools.getOrDefault(fmt, false);
            String displayName = fmt.getDisplayName();
            if (!isAvailable) {
                displayName += " " + I18n.get("sftp.archive.notInstalled");
            }
            formatCombo.getItems().add(displayName);
        }
        
        // Select first available format (prefer ZIP)
        int selectedIndex = 0;
        for (int i = 0; i < ArchiveFormat.values().length; i++) {
            if (availableTools.getOrDefault(ArchiveFormat.values()[i], false)) {
                selectedIndex = i;
                break;
            }
        }
        formatCombo.getSelectionModel().select(selectedIndex);
        grid.add(formatCombo, 1, row++);
        
        // Helper to get selected format
        java.util.function.Supplier<ArchiveFormat> getSelectedFormat = () -> {
            int idx = formatCombo.getSelectionModel().getSelectedIndex();
            return idx >= 0 && idx < ArchiveFormat.values().length ? ArchiveFormat.values()[idx] : ArchiveFormat.ZIP;
        };
        
        // Set initial extension based on first available format
        String initialExtension = ArchiveFormat.values()[selectedIndex].getExtension();
        
        // Archive file path
        grid.add(new Label(I18n.get("sftp.archive.path")), 0, row);
        TextField pathField = new TextField(defaultFilename + initialExtension);
        pathField.setPrefWidth(350);
        Button browseButton = new Button("...");
        browseButton.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle(I18n.get("sftp.archive.selectPath"));
            chooser.setInitialDirectory(currentLocalPath.toFile());
            
            ArchiveFormat selectedFormat = getSelectedFormat.get();
            String extension = "*" + selectedFormat.getExtension();
            chooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(selectedFormat.getDisplayName() + " Archives", extension));
            
            File file = chooser.showSaveDialog(null);
            if (file != null) {
                pathField.setText(file.getAbsolutePath());
            }
        });
        HBox pathBox = new HBox(5, pathField, browseButton);
        HBox.setHgrow(pathField, Priority.ALWAYS);
        grid.add(pathBox, 1, row++);
        
        // Compression level
        grid.add(new Label(I18n.get("sftp.archive.compression")), 0, row);
        ComboBox<String> compressionCombo = new ComboBox<>();
        compressionCombo.getItems().addAll(
            "0 - " + I18n.get("sftp.archive.noCompression"),
            "1 - " + I18n.get("sftp.archive.fastest"),
            "3 - " + I18n.get("sftp.archive.fast"),
            "6 - " + I18n.get("sftp.archive.normal"),
            "9 - " + I18n.get("sftp.archive.best")
        );
        compressionCombo.getSelectionModel().select(
            defaultCompression == 0 ? 0 :
            defaultCompression <= 1 ? 1 :
            defaultCompression <= 3 ? 2 :
            defaultCompression <= 6 ? 3 : 4
        );
        grid.add(compressionCombo, 1, row++);
        
        // Password (optional)
        grid.add(new Label(I18n.get("sftp.archive.password")), 0, row);
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("sftp.archive.passwordPrompt"));
        grid.add(passwordField, 1, row++);
        
        // Update extension and password field when format changes
        formatCombo.setOnAction(e -> {
            String currentPath = pathField.getText();
            // Remove old extension and add new one
            for (ArchiveFormat fmt : ArchiveFormat.values()) {
                if (currentPath.endsWith(fmt.getExtension())) {
                    currentPath = currentPath.substring(0, currentPath.length() - fmt.getExtension().length());
                    break;
                }
            }
            pathField.setText(currentPath + getSelectedFormat.get().getExtension());
            
            // Disable password for tar.bz2
            ArchiveFormat selectedFmt = getSelectedFormat.get();
            passwordField.setDisable(selectedFmt == ArchiveFormat.TAR_BZ2);
            if (selectedFmt == ArchiveFormat.TAR_BZ2) {
                passwordField.clear();
            }
        });
        
        // Separator
        grid.add(new Separator(), 0, row++, 2, 1);
        
        // Progress area (initially hidden)
        Label progressLabel = new Label(I18n.get("sftp.archive.preparing"));
        progressLabel.setVisible(false);
        grid.add(progressLabel, 0, row++, 2, 1);
        
        ProgressBar progressBar = new ProgressBar(0);
        progressBar.setPrefWidth(400);
        progressBar.setVisible(false);
        grid.add(progressBar, 0, row++, 2, 1);
        
        Label timeLabel = new Label();
        timeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        timeLabel.setVisible(false);
        grid.add(timeLabel, 0, row++, 2, 1);
        
        Label sizeLabel = new Label(I18n.get("sftp.archive.estimatedSize", formatSize(estimatedSize)));
        sizeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        grid.add(sizeLabel, 0, row++, 2, 1);
        
        dialog.getDialogPane().setContent(grid);
        
        // Buttons
        ButtonType createButton = new ButtonType(I18n.get("sftp.archive.create"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButton, ButtonType.CANCEL);
        
        Button createBtn = (Button) dialog.getDialogPane().lookupButton(createButton);
        
        dialog.setResultConverter(buttonType -> {
            if (buttonType == createButton) {
                String archivePath = pathField.getText().trim();
                if (archivePath.isEmpty()) {
                    showError(I18n.get("error.title"), I18n.get("sftp.archive.pathRequired"));
                    return null;
                }
                
                // Get compression level
                int compression = switch (compressionCombo.getSelectionModel().getSelectedIndex()) {
                    case 0 -> 0;
                    case 1 -> 1;
                    case 2 -> 3;
                    case 3 -> 6;
                    case 4 -> 9;
                    default -> 6;
                };
                
                String password = passwordField.getText();
                ArchiveFormat format = getSelectedFormat.get();
                
                // Check if the tool is available
                if (!availableTools.getOrDefault(format, false)) {
                    showError(I18n.get("error.title"), I18n.get("sftp.archive.toolNotInstalled", format.getDisplayName()));
                    return null;
                }
                
                // Disable controls
                createBtn.setDisable(true);
                pathField.setDisable(true);
                formatCombo.setDisable(true);
                compressionCombo.setDisable(true);
                passwordField.setDisable(true);
                browseButton.setDisable(true);
                
                // Show progress
                progressLabel.setVisible(true);
                progressBar.setVisible(true);
                timeLabel.setVisible(true);
                progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                
                // Create archive
                executeLocalArchiveCreation(dialog, filesToArchive, archivePath, format, compression, 
                        password, progressLabel, progressBar, timeLabel, sizeLabel);
            }
            return null;
        });
        
        dialog.show();
    }
    
    private void executeLocalArchiveCreation(Dialog<Void> dialog, List<Path> files, String archivePath,
                                             ArchiveFormat format, int compression, String password, 
                                             Label progressLabel, ProgressBar progressBar, 
                                             Label timeLabel, Label sizeLabel) {
        new Thread(() -> {
            long startTime = System.currentTimeMillis();
            
            // Show progress in status bar
            Platform.runLater(() -> {
                statusProgressBar.setVisible(true);
                statusProgressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                statusLabel.setText(I18n.get("sftp.archive.creating"));
            });
            
            Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
                long elapsed = (System.currentTimeMillis() - startTime) / 1000;
                timeLabel.setText(I18n.get("sftp.archive.elapsed", elapsed));
            }));
            timer.setCycleCount(Timeline.INDEFINITE);
            timer.play();
            
            try {
                Platform.runLater(() -> progressLabel.setText(I18n.get("sftp.archive.creating")));
                
                switch (format) {
                    case ZIP -> createLocalZip(files, archivePath, compression, password);
                    case TAR_BZ2 -> createLocalTarBz2(files, archivePath, compression);
                    case SEVEN_ZIP -> createLocal7z(files, archivePath, compression, password);
                }
                
                // Get actual size and duration
                long actualSize = Files.size(Paths.get(archivePath));
                long durationSeconds = (System.currentTimeMillis() - startTime) / 1000;
                
                Platform.runLater(() -> {
                    timer.stop();
                    progressBar.setProgress(1.0);
                    progressLabel.setText(I18n.get("sftp.archive.success"));
                    progressLabel.setStyle("-fx-text-fill: green;");
                    sizeLabel.setText(I18n.get("sftp.archive.actualSize", formatSize(actualSize)));
                    
                    // Update status bar with size and duration
                    statusProgressBar.setProgress(1.0);
                    statusLabel.setText(String.format("%s (%s, %s)", 
                        I18n.get("sftp.archiveCreated", archivePath),
                        formatSize(actualSize),
                        formatDuration(durationSeconds)));
                    
                    refreshLocal();
                    
                    // Hide progress bar after delay
                    Timeline hideProgressDelay = new Timeline(new KeyFrame(Duration.seconds(3), e -> {
                        statusProgressBar.setVisible(false);
                        statusProgressBar.setProgress(0);
                    }));
                    hideProgressDelay.play();
                    
                    Timeline closeDelay = new Timeline(new KeyFrame(Duration.seconds(2), e -> dialog.close()));
                    closeDelay.play();
                });
                
            } catch (Exception e) {
                logger.error("Local archive creation failed", e);
                Platform.runLater(() -> {
                    timer.stop();
                    progressBar.setProgress(0);
                    progressLabel.setText(I18n.get("sftp.archive.error", e.getMessage()));
                    statusProgressBar.setVisible(false);
                    statusProgressBar.setProgress(0);
                    statusLabel.setText(I18n.get("sftp.error.archive") + ": " + e.getMessage());
                    showError(I18n.get("sftp.error.archive"), e.getMessage());
                });
            }
        }, "Local-Archive-Creator").start();
    }
    
    private void createLocalZip(List<Path> files, String archivePath, int compression, String password) throws Exception {
        // Use zip4j for ZIP creation with password support
        net.lingala.zip4j.ZipFile zipFile = new net.lingala.zip4j.ZipFile(archivePath);
        
        if (password != null && !password.isEmpty()) {
            zipFile.setPassword(password.toCharArray());
        }
        
        net.lingala.zip4j.model.ZipParameters zipParams = new net.lingala.zip4j.model.ZipParameters();
        zipParams.setCompressionLevel(
            compression == 0 ? net.lingala.zip4j.model.enums.CompressionLevel.NO_COMPRESSION :
            compression <= 1 ? net.lingala.zip4j.model.enums.CompressionLevel.FASTEST :
            compression <= 3 ? net.lingala.zip4j.model.enums.CompressionLevel.FAST :
            compression <= 6 ? net.lingala.zip4j.model.enums.CompressionLevel.NORMAL :
            net.lingala.zip4j.model.enums.CompressionLevel.MAXIMUM
        );
        
        if (password != null && !password.isEmpty()) {
            zipParams.setEncryptFiles(true);
            zipParams.setEncryptionMethod(net.lingala.zip4j.model.enums.EncryptionMethod.AES);
        }
        
        // Add files
        for (Path file : files) {
            if (Files.isDirectory(file)) {
                zipFile.addFolder(file.toFile(), zipParams);
            } else {
                zipFile.addFile(file.toFile(), zipParams);
            }
        }
    }
    
    private void createLocalTarBz2(List<Path> files, String archivePath, int compression) throws Exception {
        // Use Apache Commons Compress for TAR.BZ2
        try (java.io.FileOutputStream fos = new java.io.FileOutputStream(archivePath);
             java.io.BufferedOutputStream bos = new java.io.BufferedOutputStream(fos);
             org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream bzos = 
                 new org.apache.commons.compress.compressors.bzip2.BZip2CompressorOutputStream(bos);
             org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tarOutput = 
                 new org.apache.commons.compress.archivers.tar.TarArchiveOutputStream(bzos)) {
            
            tarOutput.setLongFileMode(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream.LONGFILE_POSIX);
            
            for (Path file : files) {
                addToTar(tarOutput, file, "");
            }
        }
    }
    
    private void addToTar(org.apache.commons.compress.archivers.tar.TarArchiveOutputStream tarOutput, 
                          Path path, String base) throws Exception {
        String entryName = base + path.getFileName().toString();
        
        org.apache.commons.compress.archivers.tar.TarArchiveEntry entry = 
            new org.apache.commons.compress.archivers.tar.TarArchiveEntry(path.toFile(), entryName);
        tarOutput.putArchiveEntry(entry);
        
        if (Files.isRegularFile(path)) {
            Files.copy(path, tarOutput);
        }
        
        tarOutput.closeArchiveEntry();
        
        if (Files.isDirectory(path)) {
            try (var stream = Files.list(path)) {
                stream.forEach(child -> {
                    try {
                        addToTar(tarOutput, child, entryName + "/");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                });
            }
        }
    }
    
    private void createLocal7z(List<Path> files, String archivePath, int compression, String password) throws Exception {
        // Use local 7z command-line tool (like remote)
        StringBuilder cmd = new StringBuilder();
        
        // Try both 7z and 7za
        cmd.append("$(command -v 7z || command -v 7za) a -mx=").append(compression);
        
        if (password != null && !password.isEmpty()) {
            cmd.append(" -p'").append(password.replace("'", "'\\''")).append("' -mhe=on");
        }
        
        cmd.append(" '").append(archivePath.replace("'", "'\\''")).append("' ");
        
        // Add files with absolute paths
        for (Path file : files) {
            cmd.append("'").append(file.toAbsolutePath().toString().replace("'", "'\\''")).append("' ");
        }
        
        logger.info("Executing local 7z command: {}", cmd);
        
        // Execute command
        Process process = Runtime.getRuntime().exec(new String[]{"sh", "-c", cmd.toString()});
        
        // Capture output
        java.io.BufferedReader stdInput = new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getInputStream()));
        java.io.BufferedReader stdError = new java.io.BufferedReader(
            new java.io.InputStreamReader(process.getErrorStream()));
        
        // Read output
        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();
        String line;
        while ((line = stdInput.readLine()) != null) {
            output.append(line).append("\n");
        }
        while ((line = stdError.readLine()) != null) {
            errorOutput.append(line).append("\n");
        }
        
        int exitCode = process.waitFor();
        
        if (exitCode != 0) {
            throw new Exception("7z command failed with exit code " + exitCode + 
                              (errorOutput.length() > 0 ? ": " + errorOutput.toString() : ""));
        }
    }
    
    private void openRemoteFileAsText() {
        var selected = remoteTable.getSelectionModel().getSelectedItem();
        if (selected == null || !selected.isFile()) return;
        
        new Thread(() -> {
            try {
                // Download file
                byte[] content = sftpSession.downloadFileBytes(selected.getPath());
                
                // Open in editor tab
                Platform.runLater(() -> {
                    try {
                        FileEditorTab editorTab = new FileEditorTab(
                            selected.getName(), 
                            selected.getPath(), 
                            sftpSession, 
                            content
                        );
                        
                        // Add tab to the main window's tab pane
                        TabPane tabPane = (TabPane) getTabPane();
                        tabPane.getTabs().add(editorTab);
                        tabPane.getSelectionModel().select(editorTab);
                        
                        logger.info("Opened remote file in text editor: {}", selected.getPath());
                    } catch (Exception e) {
                        logger.error("Failed to open file in editor", e);
                        showError(I18n.get("error.title"), "Failed to open file: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to download file", e);
                Platform.runLater(() -> 
                    showError(I18n.get("error.title"), "Failed to download file: " + e.getMessage())
                );
            }
        }).start();
    }
    
    private void openRemoteFileWithSyntax() {
        openRemoteFileAsText(); // Same as text, but FileEditorTab auto-detects syntax
    }
    
    private void openRemoteImage() {
        var selected = remoteTable.getSelectionModel().getSelectedItem();
        if (selected == null || !selected.isFile()) return;
        
        new Thread(() -> {
            try {
                // Download image
                byte[] imageData = sftpSession.downloadFileBytes(selected.getPath());
                
                // Open in image viewer tab
                Platform.runLater(() -> {
                    try {
                        ImageViewerTab viewerTab = new ImageViewerTab(
                            selected.getName(), 
                            selected.getPath(), 
                            sftpSession, 
                            imageData
                        );
                        
                        // Add tab to the main window's tab pane
                        TabPane tabPane = (TabPane) getTabPane();
                        tabPane.getTabs().add(viewerTab);
                        tabPane.getSelectionModel().select(viewerTab);
                        
                        logger.info("Opened remote image: {}", selected.getPath());
                    } catch (Exception e) {
                        logger.error("Failed to open image", e);
                        showError(I18n.get("error.title"), "Failed to open image: " + e.getMessage());
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to download image", e);
                Platform.runLater(() -> 
                    showError(I18n.get("error.title"), "Failed to download image: " + e.getMessage())
                );
            }
        }).start();
    }
    
    private boolean isImageFileType(String filename) {
        String lower = filename.toLowerCase();
        return lower.endsWith(".png") || lower.endsWith(".jpg") || lower.endsWith(".jpeg") ||
               lower.endsWith(".gif") || lower.endsWith(".webp") || lower.endsWith(".bmp");
    }
    
    private void openLocalFileAsText() {
        var selected = localTable.getSelectionModel().getSelectedItem();
        if (selected == null || !selected.isFile()) return;
        
        Platform.runLater(() -> {
            try {
                Path filePath = Path.of(selected.getPath());
                FileEditorTab editorTab = new FileEditorTab(filePath);
                
                // Add tab to the main window's tab pane
                TabPane tabPane = (TabPane) getTabPane();
                tabPane.getTabs().add(editorTab);
                tabPane.getSelectionModel().select(editorTab);
                
                logger.info("Opened local file in text editor: {}", filePath);
            } catch (Exception e) {
                logger.error("Failed to open file in editor", e);
                showError(I18n.get("error.title"), "Failed to open file: " + e.getMessage());
            }
        });
    }
    
    private void openLocalFileWithSyntax() {
        openLocalFileAsText(); // Same as text, but FileEditorTab auto-detects syntax
    }
    
    private void openLocalImage() {
        var selected = localTable.getSelectionModel().getSelectedItem();
        if (selected == null || !selected.isFile()) return;
        
        Platform.runLater(() -> {
            try {
                Path filePath = Path.of(selected.getPath());
                ImageViewerTab viewerTab = new ImageViewerTab(filePath);
                
                // Add tab to the main window's tab pane
                TabPane tabPane = (TabPane) getTabPane();
                tabPane.getTabs().add(viewerTab);
                tabPane.getSelectionModel().select(viewerTab);
                
                logger.info("Opened local image: {}", filePath);
            } catch (Exception e) {
                logger.error("Failed to open image", e);
                showError(I18n.get("error.title"), "Failed to open image: " + e.getMessage());
            }
        });
    }
    
    /**
     * Returns the connection associated with this SFTP tab.
     */
    public ServerConnection getConnection() {
        return connection;
    }
    
    /**
     * Creates a SessionState for saving this SFTP Manager tab.
     */
    public SessionState createSessionState() {
        SessionState state = new SessionState();
        state.setTabType(SessionState.TabType.SFTP_MANAGER);
        state.setConnectionId(connection.getName());
        state.setTabTitle(getText());
        state.setSftpLocalPath(currentLocalPath != null ? currentLocalPath.toString() : null);
        state.setSftpRemotePath(currentRemotePath);
        
        // Save auto-close timeout if configured
        GlobalSettings settings = app.getGlobalSettingsManager().getSettings();
        if (settings.getSftpAutoCloseMinutes() != null && settings.getSftpAutoCloseMinutes() > 0) {
            state.setSftpAutoCloseTimeout(settings.getSftpAutoCloseMinutes());
        }
        
        return state;
    }
}

