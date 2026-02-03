package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SFTPSession;
import de.kortty.model.ServerConnection;
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
import java.nio.file.attribute.FileTime;
import java.text.SimpleDateFormat;
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
    
    private FilteredList<SFTPManagerDialog.FileItem> filteredLocalItems;
    private FilteredList<SFTPManagerDialog.FileItem> filteredRemoteItems;
    private ObservableList<SFTPManagerDialog.FileItem> localItems;
    private ObservableList<SFTPManagerDialog.FileItem> remoteItems;
    
    // Auto-close timeout
    private Timeline autoCloseTimer;
    private int remainingSeconds;
    private Label timeoutLabel;
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
                        getTabPane().getTabs().remove(this);
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
        
        // Status bar with timeout indicator
        HBox statusBox = new HBox(10);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        
        statusLabel = new Label(I18n.get("sftp.connecting"));
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        
        timeoutLabel = new Label("");
        timeoutLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        
        statusBox.getChildren().addAll(statusLabel, spacer, timeoutLabel);
        
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
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setPadding(new Insets(10, 0, 0, 0));
        
        Button uploadButton = new Button("→ " + I18n.get("sftp.upload"));
        uploadButton.setOnAction(e -> {
            resetAutoCloseTimer();
            uploadSelected();
        });
        uploadButton.setDisable(true);
        
        Button downloadButton = new Button("← " + I18n.get("sftp.download"));
        downloadButton.setOnAction(e -> {
            resetAutoCloseTimer();
            downloadSelected();
        });
        downloadButton.setDisable(true);
        
        Button refreshLocalButton = new Button(I18n.get("sftp.refreshLocal"));
        refreshLocalButton.setOnAction(e -> {
            resetAutoCloseTimer();
            refreshLocal();
        });
        
        Button refreshRemoteButton = new Button(I18n.get("sftp.refreshRemote"));
        refreshRemoteButton.setOnAction(e -> {
            resetAutoCloseTimer();
            refreshRemote();
        });
        
        Button copyLocalButton = new Button(I18n.get("sftp.copyLocal"));
        copyLocalButton.setOnAction(e -> {
            resetAutoCloseTimer();
            copyLocalSelected();
        });
        
        Button copyRemoteButton = new Button(I18n.get("sftp.copyRemote"));
        copyRemoteButton.setOnAction(e -> {
            resetAutoCloseTimer();
            copyRemoteSelected();
        });
        
        Button createZipButton = new Button(I18n.get("sftp.createZip"));
        createZipButton.setOnAction(e -> {
            resetAutoCloseTimer();
            createRemoteZipArchive();
        });
        
        buttonBox.getChildren().addAll(uploadButton, downloadButton, 
                new Separator(), copyLocalButton, copyRemoteButton,
                new Separator(), createZipButton,
                new Separator(), refreshLocalButton, refreshRemoteButton);
        
        // Enable/disable buttons based on selection
        localTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            uploadButton.setDisable(selected == null);
        });
        
        remoteTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            downloadButton.setDisable(selected == null);
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
        localTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        
        TableColumn<SFTPManagerDialog.FileItem, String> nameColumn = new TableColumn<>(I18n.get("sftp.column.name"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameColumn.setPrefWidth(200);
        nameColumn.setMinWidth(100);
        
        TableColumn<SFTPManagerDialog.FileItem, String> sizeColumn = new TableColumn<>(I18n.get("sftp.column.size"));
        sizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
        sizeColumn.setPrefWidth(100);
        sizeColumn.setMinWidth(80);
        
        TableColumn<SFTPManagerDialog.FileItem, String> dateColumn = new TableColumn<>(I18n.get("sftp.column.date"));
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateColumn.setPrefWidth(140);
        dateColumn.setMinWidth(120);
        
        TableColumn<SFTPManagerDialog.FileItem, String> permColumn = new TableColumn<>(I18n.get("sftp.column.permissions"));
        permColumn.setCellValueFactory(new PropertyValueFactory<>("permissions"));
        permColumn.setPrefWidth(90);
        permColumn.setMinWidth(70);
        
        localTable.getColumns().addAll(nameColumn, sizeColumn, dateColumn, permColumn);
        
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
        
        // Search filter
        localItems = FXCollections.observableArrayList();
        filteredLocalItems = new FilteredList<>(localItems, p -> true);
        localSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.trim().isEmpty()) {
                filteredLocalItems.setPredicate(p -> true);
            } else {
                String searchLower = newVal.toLowerCase();
                filteredLocalItems.setPredicate(item -> 
                    item.getName().toLowerCase().contains(searchLower));
            }
        });
        localTable.setItems(filteredLocalItems);
        
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
        remoteTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        
        TableColumn<SFTPManagerDialog.FileItem, String> nameColumn = new TableColumn<>(I18n.get("sftp.column.name"));
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameColumn.setPrefWidth(200);
        nameColumn.setMinWidth(100);
        
        TableColumn<SFTPManagerDialog.FileItem, String> sizeColumn = new TableColumn<>(I18n.get("sftp.column.size"));
        sizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
        sizeColumn.setPrefWidth(100);
        sizeColumn.setMinWidth(80);
        
        TableColumn<SFTPManagerDialog.FileItem, String> dateColumn = new TableColumn<>(I18n.get("sftp.column.date"));
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateColumn.setPrefWidth(140);
        dateColumn.setMinWidth(120);
        
        TableColumn<SFTPManagerDialog.FileItem, String> permColumn = new TableColumn<>(I18n.get("sftp.column.permissions"));
        permColumn.setCellValueFactory(new PropertyValueFactory<>("permissions"));
        permColumn.setPrefWidth(90);
        permColumn.setMinWidth(70);
        
        remoteTable.getColumns().addAll(nameColumn, sizeColumn, dateColumn, permColumn);
        
        // Double-click to navigate
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
        
        // Search filter
        remoteItems = FXCollections.observableArrayList();
        filteredRemoteItems = new FilteredList<>(remoteItems, p -> true);
        remoteSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.trim().isEmpty()) {
                filteredRemoteItems.setPredicate(p -> true);
            } else {
                String searchLower = newVal.toLowerCase();
                filteredRemoteItems.setPredicate(item -> 
                    item.getName().toLowerCase().contains(searchLower));
            }
        });
        remoteTable.setItems(filteredRemoteItems);
        
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
    
    private void refreshLocal() {
        localItems.clear();
        try {
            File[] files = currentLocalPath.toFile().listFiles();
            if (files != null) {
                // Add parent directory entry
                if (currentLocalPath.getParent() != null) {
                    localItems.add(new SFTPManagerDialog.FileItem("..", 
                        currentLocalPath.getParent().toString(), false, "<DIR>", "", ""));
                }
                
                SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                for (File file : files) {
                    String size = file.isDirectory() ? "<DIR>" : formatSize(file.length());
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
                String size = isDir ? "<DIR>" : formatSize(entry.getAttributes().getSize());
                
                long mtime = entry.getAttributes().getModifyTime().toMillis();
                String date = sdf.format(new Date(mtime));
                
                // Get file permissions
                String permissions = formatRemotePermissions(entry.getAttributes());
                
                String fullPath = pathToList.endsWith("/") ? pathToList + name : pathToList + "/" + name;
                remoteItems.add(new SFTPManagerDialog.FileItem(name, fullPath, !isDir, size, date, permissions));
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
        
        for (var item : selected) {
            if (item.getName().equals("..")) continue;
            uploadFile(item);
        }
    }
    
    private void uploadFile(SFTPManagerDialog.FileItem item) {
        if (sftpSession == null || !sftpSession.isConnected()) return;
        
        statusLabel.setText(I18n.get("sftp.uploading", item.getName()));
        new Thread(() -> {
            try {
                String remotePath = currentRemotePath.endsWith("/") 
                    ? currentRemotePath + item.getName() 
                    : currentRemotePath + "/" + item.getName();
                
                if (item.isFile()) {
                    sftpSession.uploadFile(Paths.get(item.getPath()), remotePath);
                } else {
                    // Upload directory recursively
                    uploadDirectoryRecursive(Paths.get(item.getPath()), remotePath);
                }
                
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("sftp.uploadComplete", item.getName()));
                    refreshRemote();
                });
            } catch (Exception e) {
                logger.error("Upload failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("sftp.uploadFailed", e.getMessage()));
                    showError(I18n.get("sftp.error.upload"), e.getMessage());
                });
            }
        }, "SFTP-Upload").start();
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
        
        for (var item : selected) {
            if (item.getName().equals("..")) continue;
            downloadFile(item);
        }
    }
    
    private void downloadFile(SFTPManagerDialog.FileItem item) {
        if (sftpSession == null || !sftpSession.isConnected()) return;
        
        statusLabel.setText(I18n.get("sftp.downloading", item.getName()));
        new Thread(() -> {
            try {
                Path localPath = currentLocalPath.resolve(item.getName());
                
                if (item.isFile()) {
                    sftpSession.downloadFile(item.getPath(), localPath);
                } else {
                    // Download directory recursively
                    downloadDirectoryRecursive(item.getPath(), localPath);
                }
                
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("sftp.downloadComplete", item.getName()));
                    refreshLocal();
                });
            } catch (Exception e) {
                logger.error("Download failed", e);
                Platform.runLater(() -> {
                    statusLabel.setText(I18n.get("sftp.downloadFailed", e.getMessage()));
                    showError(I18n.get("sftp.error.download"), e.getMessage());
                });
            }
        }, "SFTP-Download").start();
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
    
    private void createRemoteZipArchive() {
        var selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            showError(I18n.get("error.title"), I18n.get("sftp.error.selectFilesToZip"));
            return;
        }
        
        // Filter out ".." entry and collect file paths
        List<String> filesToZip = new java.util.ArrayList<>();
        long estimatedSize = 0;
        for (var item : selected) {
            if (item.getName().equals("..")) continue;
            filesToZip.add(item.getPath());
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
        
        if (filesToZip.isEmpty()) {
            showError(I18n.get("error.title"), I18n.get("sftp.error.selectFilesToZip"));
            return;
        }
        
        // Get default settings from GlobalSettings
        String defaultZipPath = "/tmp";
        int defaultCompression = 6;
        try {
            de.kortty.core.GlobalSettingsManager gsm = app.getGlobalSettingsManager();
            if (gsm != null && gsm.getSettings() != null) {
                defaultZipPath = gsm.getSettings().getSftpDefaultZipPath();
                defaultCompression = gsm.getSettings().getSftpDefaultZipCompression();
            }
        } catch (Exception e) {
            logger.debug("Could not get global settings: {}", e.getMessage());
        }
        
        // Generate default filename with timestamp
        String timestamp = new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date());
        String defaultFilename = defaultZipPath + "/archive_" + timestamp + ".zip";
        
        // Show ZIP creation dialog
        showRemoteZipDialog(filesToZip, defaultFilename, defaultCompression, estimatedSize);
    }
    
    private void showRemoteZipDialog(List<String> filesToZip, String defaultFilename, int defaultCompression, long estimatedSize) {
        Dialog<Void> dialog = new Dialog<>();
        dialog.setTitle(I18n.get("sftp.createZip"));
        dialog.setHeaderText(I18n.get("sftp.createZip.dialogHeader", filesToZip.size()));
        dialog.setResizable(true);
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        int row = 0;
        
        // ZIP file path
        grid.add(new Label(I18n.get("sftp.createZip.path")), 0, row);
        TextField pathField = new TextField(defaultFilename);
        pathField.setPrefWidth(350);
        grid.add(pathField, 1, row++);
        
        // Compression level
        grid.add(new Label(I18n.get("sftp.createZip.compression")), 0, row);
        ComboBox<String> compressionCombo = new ComboBox<>();
        compressionCombo.getItems().addAll(
            "0 - " + I18n.get("sftp.createZip.noCompression"),
            "1 - " + I18n.get("sftp.createZip.fastest"),
            "3 - " + I18n.get("sftp.createZip.fast"),
            "6 - " + I18n.get("sftp.createZip.normal"),
            "9 - " + I18n.get("sftp.createZip.best")
        );
        compressionCombo.getSelectionModel().select(
            defaultCompression == 0 ? 0 :
            defaultCompression <= 1 ? 1 :
            defaultCompression <= 3 ? 2 :
            defaultCompression <= 6 ? 3 : 4
        );
        grid.add(compressionCombo, 1, row++);
        
        // Owner (optional)
        grid.add(new Label(I18n.get("sftp.createZip.owner")), 0, row);
        TextField ownerField = new TextField();
        ownerField.setPromptText(I18n.get("sftp.createZip.ownerPrompt"));
        grid.add(ownerField, 1, row++);
        
        // Permissions (optional)
        grid.add(new Label(I18n.get("sftp.createZip.permissions")), 0, row);
        TextField permissionsField = new TextField("644");
        permissionsField.setPromptText("644");
        grid.add(permissionsField, 1, row++);
        
        // Password (optional)
        grid.add(new Label(I18n.get("sftp.createZip.password")), 0, row);
        PasswordField passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("sftp.createZip.passwordPrompt"));
        grid.add(passwordField, 1, row++);
        
        // Separator
        grid.add(new Separator(), 0, row++, 2, 1);
        
        // Progress area (initially hidden)
        Label progressLabel = new Label(I18n.get("sftp.createZip.preparing"));
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
        
        Label sizeLabel = new Label(I18n.get("sftp.createZip.estimatedSize", formatSize(estimatedSize)));
        sizeLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        grid.add(sizeLabel, 0, row++, 2, 1);
        
        dialog.getDialogPane().setContent(grid);
        
        // Buttons
        ButtonType createButton = new ButtonType(I18n.get("sftp.createZip.create"), ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(createButton, ButtonType.CANCEL);
        
        // Get the create button for enabling/disabling
        Button createBtn = (Button) dialog.getDialogPane().lookupButton(createButton);
        
        dialog.setResultConverter(buttonType -> {
            if (buttonType == createButton) {
                String zipPath = pathField.getText().trim();
                if (zipPath.isEmpty()) {
                    showError(I18n.get("error.title"), I18n.get("sftp.createZip.pathRequired"));
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
                compressionCombo.setDisable(true);
                ownerField.setDisable(true);
                permissionsField.setDisable(true);
                passwordField.setDisable(true);
                
                // Show progress
                progressLabel.setVisible(true);
                progressBar.setVisible(true);
                timeLabel.setVisible(true);
                progressBar.setProgress(ProgressIndicator.INDETERMINATE_PROGRESS);
                
                // Create ZIP in background
                executeRemoteZipCreation(dialog, filesToZip, zipPath, compression, owner, permissions, password,
                        progressLabel, progressBar, timeLabel, sizeLabel);
            }
            return null;
        });
        
        dialog.show();
    }
    
    private void executeRemoteZipCreation(Dialog<Void> dialog, List<String> filesToZip, String zipPath,
                                          int compression, String owner, String permissions, String password,
                                          Label progressLabel, ProgressBar progressBar, Label timeLabel, Label sizeLabel) {
        long startTime = System.currentTimeMillis();
        
        // Timer for elapsed time
        Timeline timer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            long elapsed = System.currentTimeMillis() - startTime;
            long seconds = (elapsed / 1000) % 60;
            long minutes = (elapsed / 1000) / 60;
            timeLabel.setText(I18n.get("sftp.createZip.elapsed", String.format("%02d:%02d", minutes, seconds)));
        }));
        timer.setCycleCount(Timeline.INDEFINITE);
        timer.play();
        
        new Thread(() -> {
            try {
                // Build the zip command using absolute paths
                // We cd to the parent directory and use relative file names for cleaner archive structure
                StringBuilder cmd = new StringBuilder();
                
                // Determine the working directory (use current remote path)
                String workDir = currentRemotePath;
                
                // Build file list with relative names (relative to workDir)
                List<String> relativeNames = new java.util.ArrayList<>();
                for (String fullPath : filesToZip) {
                    String relativeName;
                    if (fullPath.startsWith(workDir + "/")) {
                        relativeName = fullPath.substring(workDir.length() + 1);
                    } else if (fullPath.startsWith(workDir) && fullPath.length() > workDir.length()) {
                        relativeName = fullPath.substring(workDir.length());
                        if (relativeName.startsWith("/")) {
                            relativeName = relativeName.substring(1);
                        }
                    } else {
                        // Use just the filename
                        relativeName = fullPath.contains("/") ? fullPath.substring(fullPath.lastIndexOf('/') + 1) : fullPath;
                    }
                    relativeNames.add(relativeName);
                }
                
                // Build the command: cd to workDir, then zip
                cmd.append("cd '").append(workDir.replace("'", "'\\''")).append("' && ");
                
                if (password != null && !password.isEmpty()) {
                    cmd.append("zip -r -").append(compression)
                       .append(" -P '").append(password.replace("'", "'\\''")).append("' ");
                } else {
                    cmd.append("zip -r -").append(compression).append(" ");
                }
                
                // Add destination path (absolute)
                cmd.append("'").append(zipPath.replace("'", "'\\''")).append("' ");
                
                // Add all files/directories (relative to workDir)
                for (String name : relativeNames) {
                    cmd.append("'").append(name.replace("'", "'\\''")).append("' ");
                }
                
                final String zipCommand = cmd.toString().trim();
                logger.info("Executing remote ZIP command: {}", zipCommand.replaceAll("-P '[^']*'", "-P '***'"));
                
                Platform.runLater(() -> progressLabel.setText(I18n.get("sftp.createZip.creating")));
                
                // Execute the zip command with progress
                int exitCode = sftpSession.executeCommandWithProgress(zipCommand, line -> {
                    Platform.runLater(() -> {
                        progressLabel.setText(line);
                    });
                });
                
                if (exitCode != 0) {
                    throw new Exception(I18n.get("sftp.createZip.failed", exitCode));
                }
                
                // Set owner if specified
                if (owner != null && !owner.isEmpty()) {
                    String chownCmd = "chown " + owner + " '" + zipPath.replace("'", "'\\''") + "'";
                    try {
                        sftpSession.executeCommand(chownCmd);
                    } catch (Exception e) {
                        logger.warn("Could not set owner: {}", e.getMessage());
                    }
                }
                
                // Set permissions if specified
                if (permissions != null && !permissions.isEmpty()) {
                    String chmodCmd = "chmod " + permissions + " '" + zipPath.replace("'", "'\\''") + "'";
                    try {
                        sftpSession.executeCommand(chmodCmd);
                    } catch (Exception e) {
                        logger.warn("Could not set permissions: {}", e.getMessage());
                    }
                }
                
                // Get actual file size
                String sizeCmd = "stat -c%s '" + zipPath.replace("'", "'\\''") + "' 2>/dev/null || stat -f%z '" + zipPath.replace("'", "'\\''") + "'";
                String actualSize = "";
                try {
                    actualSize = sftpSession.executeCommand(sizeCmd).trim();
                } catch (Exception e) {
                    logger.debug("Could not get file size: {}", e.getMessage());
                }
                
                final String finalSize = actualSize;
                
                Platform.runLater(() -> {
                    timer.stop();
                    progressBar.setProgress(1.0);
                    progressLabel.setText(I18n.get("sftp.createZip.success"));
                    if (!finalSize.isEmpty()) {
                        try {
                            sizeLabel.setText(I18n.get("sftp.createZip.actualSize", formatSize(Long.parseLong(finalSize))));
                        } catch (NumberFormatException ignored) {}
                    }
                    statusLabel.setText(I18n.get("sftp.zipCreated", zipPath));
                    refreshRemote();
                    
                    // Close dialog after short delay
                    Timeline closeDelay = new Timeline(new KeyFrame(Duration.seconds(2), e -> dialog.close()));
                    closeDelay.play();
                });
                
            } catch (Exception e) {
                logger.error("Remote ZIP creation failed", e);
                Platform.runLater(() -> {
                    timer.stop();
                    progressBar.setProgress(0);
                    progressLabel.setText(I18n.get("sftp.createZip.error", e.getMessage()));
                    showError(I18n.get("sftp.error.zip"), e.getMessage());
                });
            }
        }, "Remote-ZIP-Creator").start();
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
    
    private void showError(String title, String message) {
        Platform.runLater(() -> {
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message);
            alert.showAndWait();
        });
    }
    
    /**
     * Returns the connection associated with this SFTP tab.
     */
    public ServerConnection getConnection() {
        return connection;
    }
}
