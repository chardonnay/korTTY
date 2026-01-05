package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SFTPSession;
import de.kortty.model.ServerConnection;
import de.kortty.security.PasswordVault;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.*;
import javafx.scene.Node;
import javafx.stage.Modality;
import javafx.stage.Stage;
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
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;

/**
 * SFTP File Manager Dialog for transferring files between local and remote systems.
 */
public class SFTPManagerDialog extends Dialog<Void> {
    
    private static final Logger logger = LoggerFactory.getLogger(SFTPManagerDialog.class);
    
    private final KorTTYApplication app;
    private final ServerConnection connection;
    private final String password;
    private SFTPSession sftpSession;
    
    private TableView<FileItem> localTable;
    private TableView<FileItem> remoteTable;
    private TextField localPathField;
    private TextField remotePathField;
    private TextField localSearchField;
    private TextField remoteSearchField;
    private Label statusLabel;
    
    private Path currentLocalPath;
    private String currentRemotePath;
    
    private FilteredList<FileItem> filteredLocalItems;
    private FilteredList<FileItem> filteredRemoteItems;
    private ObservableList<FileItem> localItems;
    private ObservableList<FileItem> remoteItems;
    
    public SFTPManagerDialog(Stage owner, KorTTYApplication app, ServerConnection connection, String password) {
        this.app = app;
        this.connection = connection;
        this.password = password;
        
        setTitle("SFTP Manager - " + connection.getDisplayName());
        setHeaderText("Dateiübertragung zwischen lokalem System und Server");
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);
        
        // Initialize paths
        currentLocalPath = Paths.get(System.getProperty("user.home"));
        currentRemotePath = "~";
        
        // Create UI
        VBox content = createContent();
        getDialogPane().setContent(content);
        getDialogPane().setPrefSize(1000, 600);
        
        getDialogPane().getButtonTypes().addAll(ButtonType.CLOSE);
        
        // Cleanup on close
        setOnCloseRequest(e -> cleanup());
        
        // Connect to SFTP
        connectToSFTP();
    }
    
    private VBox createContent() {
        VBox mainBox = new VBox(10);
        mainBox.setPadding(new Insets(10));
        
        // Status bar
        statusLabel = new Label("Verbinde...");
        statusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        
        // Split pane for local and remote
        SplitPane splitPane = new SplitPane();
        splitPane.setDividerPositions(0.5);
        
        // Local panel
        VBox localPanel = createLocalPanel();
        
        // Remote panel
        VBox remotePanel = createRemotePanel();
        
        splitPane.getItems().addAll(localPanel, remotePanel);
        
        // Transfer buttons
        HBox buttonBox = new HBox(10);
        buttonBox.setAlignment(Pos.CENTER);
        buttonBox.setPadding(new Insets(10, 0, 0, 0));
        
        Button uploadButton = new Button("→ Hochladen");
        uploadButton.setOnAction(e -> uploadSelected());
        uploadButton.setDisable(true);
        
        Button downloadButton = new Button("← Herunterladen");
        downloadButton.setOnAction(e -> downloadSelected());
        downloadButton.setDisable(true);
        
        Button refreshLocalButton = new Button("Lokal aktualisieren");
        refreshLocalButton.setOnAction(e -> refreshLocal());
        
        Button refreshRemoteButton = new Button("Remote aktualisieren");
        refreshRemoteButton.setOnAction(e -> refreshRemote());
        
        // Copy buttons
        Button copyLocalButton = new Button("Lokal kopieren");
        copyLocalButton.setOnAction(e -> copyLocalSelected());
        
        Button copyRemoteButton = new Button("Remote kopieren");
        copyRemoteButton.setOnAction(e -> copyRemoteSelected());
        
        // ZIP button
        Button createZipButton = new Button("ZIP erstellen");
        createZipButton.setOnAction(e -> createZipArchive());
        
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
        
        mainBox.getChildren().addAll(splitPane, buttonBox, statusLabel);
        VBox.setVgrow(splitPane, Priority.ALWAYS);
        
        return mainBox;
    }
    
    private VBox createLocalPanel() {
        VBox panel = new VBox(5);
        panel.setPadding(new Insets(5));
        
        Label titleLabel = new Label("Lokales System");
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
        
        pathBox.getChildren().addAll(new Label("Pfad:"), localPathField, upButton, homeButton);
        HBox.setHgrow(localPathField, Priority.ALWAYS);
        
        // Search field
        HBox searchBox = new HBox(5);
        localSearchField = new TextField();
        localSearchField.setPromptText("Dateien suchen... (* als Wildcard)");
        searchBox.getChildren().addAll(new Label("Suchen:"), localSearchField);
        HBox.setHgrow(localSearchField, Priority.ALWAYS);
        
        // File table
        localTable = new TableView<>();
        localTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        
        TableColumn<FileItem, String> nameColumn = new TableColumn<>();
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameColumn.setPrefWidth(200);
        nameColumn.setMinWidth(100);
        nameColumn.setSortable(false); // Disable default sorting
        nameColumn.setComparator((a, b) -> {
            // Always put ".." first
            if (a.equals("..")) return -1;
            if (b.equals("..")) return 1;
            return a.compareToIgnoreCase(b);
        });
        setupSortableColumnHeader(nameColumn, "Name", localTable, nameColumn);
        
        TableColumn<FileItem, String> sizeColumn = new TableColumn<>();
        sizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
        sizeColumn.setPrefWidth(100);
        sizeColumn.setMinWidth(80);
        sizeColumn.setSortable(false); // Disable default sorting
        sizeColumn.setComparator((a, b) -> {
            // Parse size for comparison
            if (a.equals("<DIR>") && b.equals("<DIR>")) return 0;
            if (a.equals("<DIR>")) return -1;
            if (b.equals("<DIR>")) return 1;
            return parseSize(a).compareTo(parseSize(b));
        });
        setupSortableColumnHeader(sizeColumn, "Größe", localTable, sizeColumn);
        
        TableColumn<FileItem, String> dateColumn = new TableColumn<>();
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateColumn.setPrefWidth(150);
        dateColumn.setMinWidth(120);
        dateColumn.setSortable(false); // Disable default sorting
        dateColumn.setComparator(String::compareTo);
        setupSortableColumnHeader(dateColumn, "Datum", localTable, dateColumn);
        
        localTable.getColumns().addAll(nameColumn, sizeColumn, dateColumn);
        
        // Double-click to navigate and context menu for local files
        localTable.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();
            ContextMenu contextMenu = new ContextMenu();
            
            MenuItem renameItem = new MenuItem("Umbenennen");
            renameItem.setOnAction(e -> {
                if (!row.isEmpty()) {
                    renameLocalFile(row.getItem());
                }
            });
            
            MenuItem deleteItem = new MenuItem("Löschen");
            deleteItem.setOnAction(e -> {
                if (!row.isEmpty()) {
                    deleteLocalFile(row.getItem());
                }
            });
            
            contextMenu.getItems().addAll(renameItem, deleteItem);
            
            row.contextMenuProperty().bind(
                javafx.beans.binding.Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null)
                    .otherwise(contextMenu)
            );
            
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    FileItem item = row.getItem();
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
        localSearchField.setPromptText("Dateien suchen... (* als Wildcard)");
        localSearchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.trim().isEmpty()) {
                filteredLocalItems.setPredicate(p -> true);
            } else {
                String searchText = newVal.trim();
                boolean useGlobPattern = searchText.contains("*");
                
                // Convert glob pattern to regex if "*" is present
                java.util.regex.Pattern pattern = null;
                if (useGlobPattern) {
                    // Escape regex special characters except *
                    String regexPattern = searchText
                        .replace("\\", "\\\\")
                        .replace(".", "\\.")
                        .replace("+", "\\+")
                        .replace("?", "\\?")
                        .replace("^", "\\^")
                        .replace("$", "\\$")
                        .replace("|", "\\|")
                        .replace("(", "\\(")
                        .replace(")", "\\)")
                        .replace("[", "\\[")
                        .replace("]", "\\]")
                        .replace("{", "\\{")
                        .replace("}", "\\}")
                        .replace("*", ".*"); // Convert * to .* for regex
                    
                    try {
                        pattern = java.util.regex.Pattern.compile(regexPattern, 
                            java.util.regex.Pattern.CASE_INSENSITIVE);
                    } catch (java.util.regex.PatternSyntaxException e) {
                        // Invalid pattern, fall back to simple contains
                        pattern = null;
                    }
                }
                
                final java.util.regex.Pattern finalPattern = pattern;
                final boolean usePattern = useGlobPattern && pattern != null;
                final String lowerSearchText = searchText.toLowerCase();
                
                filteredLocalItems.setPredicate(item -> {
                    String name = item.getName();
                    if (usePattern) {
                        return finalPattern.matcher(name).matches();
                    } else {
                        return name.toLowerCase().contains(lowerSearchText);
                    }
                });
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
        
        Label titleLabel = new Label("Remote Server");
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
        
        pathBox.getChildren().addAll(new Label("Pfad:"), remotePathField, upButton, homeButton);
        HBox.setHgrow(remotePathField, Priority.ALWAYS);
        
        // Search field
        HBox searchBox = new HBox(5);
        remoteSearchField = new TextField();
        remoteSearchField.setPromptText("Dateien suchen... (* als Wildcard)");
        searchBox.getChildren().addAll(new Label("Suchen:"), remoteSearchField);
        HBox.setHgrow(remoteSearchField, Priority.ALWAYS);
        
        // File table
        remoteTable = new TableView<>();
        remoteTable.setColumnResizePolicy(TableView.UNCONSTRAINED_RESIZE_POLICY);
        
        TableColumn<FileItem, String> nameColumn = new TableColumn<>();
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameColumn.setPrefWidth(200);
        nameColumn.setMinWidth(100);
        nameColumn.setSortable(false); // Disable default sorting
        nameColumn.setComparator((a, b) -> {
            // Always put ".." first
            if (a.equals("..")) return -1;
            if (b.equals("..")) return 1;
            return a.compareToIgnoreCase(b);
        });
        setupSortableColumnHeader(nameColumn, "Name", remoteTable, nameColumn);
        
        TableColumn<FileItem, String> sizeColumn = new TableColumn<>();
        sizeColumn.setCellValueFactory(new PropertyValueFactory<>("size"));
        sizeColumn.setPrefWidth(100);
        sizeColumn.setMinWidth(80);
        sizeColumn.setSortable(false); // Disable default sorting
        sizeColumn.setComparator((a, b) -> {
            // Parse size for comparison
            if (a.equals("<DIR>") && b.equals("<DIR>")) return 0;
            if (a.equals("<DIR>")) return -1;
            if (b.equals("<DIR>")) return 1;
            return parseSize(a).compareTo(parseSize(b));
        });
        setupSortableColumnHeader(sizeColumn, "Größe", remoteTable, sizeColumn);
        
        TableColumn<FileItem, String> dateColumn = new TableColumn<>();
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateColumn.setPrefWidth(150);
        dateColumn.setMinWidth(120);
        dateColumn.setSortable(false); // Disable default sorting
        dateColumn.setComparator(String::compareTo);
        setupSortableColumnHeader(dateColumn, "Datum", remoteTable, dateColumn);
        
        remoteTable.getColumns().addAll(nameColumn, sizeColumn, dateColumn);
        
        // Double-click to navigate and context menu
        remoteTable.setRowFactory(tv -> {
            TableRow<FileItem> row = new TableRow<>();
            ContextMenu contextMenu = new ContextMenu();
            
            MenuItem renameItem = new MenuItem("Umbenennen");
            renameItem.setOnAction(e -> {
                if (!row.isEmpty()) {
                    renameRemoteFile(row.getItem());
                }
            });
            
            MenuItem deleteItem = new MenuItem("Löschen");
            deleteItem.setOnAction(e -> {
                if (!row.isEmpty()) {
                    deleteRemoteFile(row.getItem());
                }
            });
            
            MenuItem permissionsItem = new MenuItem("Berechtigungen ändern...");
            permissionsItem.setOnAction(e -> {
                if (!row.isEmpty()) {
                    changeRemotePermissions(row.getItem());
                }
            });
            
            contextMenu.getItems().addAll(renameItem, deleteItem, new SeparatorMenuItem(), permissionsItem);
            
            row.contextMenuProperty().bind(
                javafx.beans.binding.Bindings.when(row.emptyProperty())
                    .then((ContextMenu) null)
                    .otherwise(contextMenu)
            );
            
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    FileItem item = row.getItem();
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
                String searchText = newVal.trim();
                boolean useGlobPattern = searchText.contains("*");
                
                // Convert glob pattern to regex if "*" is present
                java.util.regex.Pattern pattern = null;
                if (useGlobPattern) {
                    // Escape regex special characters except *
                    String regexPattern = searchText
                        .replace("\\", "\\\\")
                        .replace(".", "\\.")
                        .replace("+", "\\+")
                        .replace("?", "\\?")
                        .replace("^", "\\^")
                        .replace("$", "\\$")
                        .replace("|", "\\|")
                        .replace("(", "\\(")
                        .replace(")", "\\)")
                        .replace("[", "\\[")
                        .replace("]", "\\]")
                        .replace("{", "\\{")
                        .replace("}", "\\}")
                        .replace("*", ".*"); // Convert * to .* for regex
                    
                    try {
                        pattern = java.util.regex.Pattern.compile(regexPattern, 
                            java.util.regex.Pattern.CASE_INSENSITIVE);
                    } catch (java.util.regex.PatternSyntaxException e) {
                        // Invalid pattern, fall back to simple contains
                        pattern = null;
                    }
                }
                
                final java.util.regex.Pattern finalPattern = pattern;
                final boolean usePattern = useGlobPattern && pattern != null;
                final String lowerSearchText = searchText.toLowerCase();
                
                filteredRemoteItems.setPredicate(item -> {
                    String name = item.getName();
                    if (usePattern) {
                        return finalPattern.matcher(name).matches();
                    } else {
                        return name.toLowerCase().contains(lowerSearchText);
                    }
                });
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
                sftpSession = new SFTPSession(connection, password);
                sftpSession.connect();
                
                Platform.runLater(() -> {
                    try {
                        statusLabel.setText("Verbunden");
                        currentRemotePath = sftpSession.getCurrentDirectory();
                        remotePathField.setText(currentRemotePath);
                        refreshLocal();
                        refreshRemote();
                    } catch (java.io.IOException e) {
                        logger.error("Failed to get current directory", e);
                        statusLabel.setText("Fehler beim Abrufen des Verzeichnisses");
                    }
                });
            } catch (Exception e) {
                logger.error("Failed to connect to SFTP", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Verbindungsfehler: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("SFTP Verbindungsfehler");
                    alert.setHeaderText("Verbindung zum Server fehlgeschlagen");
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                    close();
                });
            }
        }).start();
    }
    
    private void refreshLocal() {
        try {
            java.util.List<FileItem> items = new java.util.ArrayList<>();
            
            // Add parent directory
            if (currentLocalPath.getParent() != null) {
                items.add(new FileItem("..", currentLocalPath.getParent().toString(), false, "", ""));
            }
            
            // List files
            File[] files = currentLocalPath.toFile().listFiles();
            if (files != null) {
                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
                for (File file : files) {
                    String size = file.isDirectory() ? "<DIR>" : formatSize(file.length());
                    String date = sdf.format(new Date(file.lastModified()));
                    items.add(new FileItem(file.getName(), file.getAbsolutePath(), file.isFile(), size, date));
                }
            }
            
            localItems.clear();
            localItems.addAll(items);
            localPathField.setText(currentLocalPath.toString());
        } catch (Exception e) {
            logger.error("Failed to refresh local files", e);
            statusLabel.setText("Fehler beim Aktualisieren: " + e.getMessage());
        }
    }
    
    private void refreshRemote() {
        if (sftpSession == null || !sftpSession.isConnected()) {
            return;
        }
        
        new Thread(() -> {
            try {
                List<SftpClient.DirEntry> entries = sftpSession.listFiles(currentRemotePath);
                java.util.List<FileItem> items = new java.util.ArrayList<>();
                
                // Add parent directory
                if (!currentRemotePath.equals("/") && !currentRemotePath.equals("~")) {
                    String parent = currentRemotePath.substring(0, currentRemotePath.lastIndexOf('/'));
                    if (parent.isEmpty()) parent = "/";
                    items.add(new FileItem("..", parent, false, "", ""));
                }
                
                SimpleDateFormat sdf = new SimpleDateFormat("dd.MM.yyyy HH:mm");
                for (SftpClient.DirEntry entry : entries) {
                    String name = entry.getFilename();
                    if (name.equals(".") || name.equals("..")) continue;
                    
                    SftpClient.Attributes attrs = entry.getAttributes();
                    boolean isFile = attrs.isRegularFile();
                    String size = isFile ? formatSize(attrs.getSize()) : "<DIR>";
                    
                    long mtime = attrs.getModifyTime().toMillis();
                    String date = sdf.format(new Date(mtime));
                    
                    String fullPath = currentRemotePath.endsWith("/") ? 
                        currentRemotePath + name : currentRemotePath + "/" + name;
                    
                    items.add(new FileItem(name, fullPath, isFile, size, date));
                }
                
                Platform.runLater(() -> {
                    remoteItems.clear();
                    remoteItems.addAll(items);
                    remotePathField.setText(currentRemotePath);
                });
            } catch (Exception e) {
                logger.error("Failed to refresh remote files", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Fehler beim Aktualisieren: " + e.getMessage());
                });
            }
        }).start();
    }
    
    private void navigateLocal(String path) {
        try {
            Path newPath = Paths.get(path);
            if (Files.exists(newPath) && Files.isDirectory(newPath)) {
                currentLocalPath = newPath.toAbsolutePath();
                refreshLocal();
            } else {
                statusLabel.setText("Pfad existiert nicht: " + path);
            }
        } catch (Exception e) {
            statusLabel.setText("Fehler: " + e.getMessage());
        }
    }
    
    private void navigateLocalUp() {
        if (currentLocalPath.getParent() != null) {
            navigateLocal(currentLocalPath.getParent().toString());
        }
    }
    
    private void navigateRemote(String path) {
        if (sftpSession == null || !sftpSession.isConnected()) {
            return;
        }
        
        new Thread(() -> {
            try {
                String resolvedPath = path.equals("~") ? sftpSession.getCurrentDirectory() : path;
                sftpSession.changeDirectory(resolvedPath);
                currentRemotePath = sftpSession.getCurrentDirectory();
                Platform.runLater(() -> refreshRemote());
            } catch (Exception e) {
                logger.error("Failed to navigate remote", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Fehler: " + e.getMessage());
                });
            }
        }).start();
    }
    
    private void navigateRemoteUp() {
        if (currentRemotePath.equals("/") || currentRemotePath.equals("~")) {
            return;
        }
        String parent = currentRemotePath.substring(0, currentRemotePath.lastIndexOf('/'));
        if (parent.isEmpty()) parent = "/";
        navigateRemote(parent);
    }
    
    private void uploadSelected() {
        List<FileItem> selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            return;
        }
        
        for (FileItem item : selected) {
            if (item.getName().equals("..")) continue;
            
            File file = new File(item.getPath());
            if (!file.exists()) {
                continue;
            }
            
            if (file.isDirectory()) {
                // Upload directory recursively
                uploadDirectory(file, currentRemotePath);
            } else {
                uploadSingleFile(file);
            }
        }
    }
    
    private void uploadSingleFile(File file) {
        String remoteFileName = file.getName();
        String remotePath = currentRemotePath.endsWith("/") ? 
            currentRemotePath + remoteFileName : currentRemotePath + "/" + remoteFileName;
        
        new Thread(() -> {
            try {
                Platform.runLater(() -> statusLabel.setText("Lade hoch: " + file.getName()));
                sftpSession.uploadFile(file.toPath(), remotePath);
                Platform.runLater(() -> {
                    statusLabel.setText("Hochgeladen: " + file.getName());
                    refreshRemote();
                });
            } catch (Exception e) {
                logger.error("Failed to upload file", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Fehler beim Hochladen: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Upload Fehler");
                    alert.setHeaderText("Datei konnte nicht hochgeladen werden");
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }).start();
    }
    
    private void uploadDirectory(File dir, String remoteBasePath) {
        new Thread(() -> {
            try {
                String remoteDirPath = remoteBasePath.endsWith("/") ? 
                    remoteBasePath + dir.getName() : remoteBasePath + "/" + dir.getName();
                
                Platform.runLater(() -> statusLabel.setText("Erstelle Verzeichnis: " + dir.getName()));
                sftpSession.createDirectory(remoteDirPath);
                
                File[] files = dir.listFiles();
                if (files != null) {
                    for (File file : files) {
                        if (file.isDirectory()) {
                            uploadDirectory(file, remoteDirPath);
                        } else {
                            uploadSingleFileToPath(file, remoteDirPath);
                        }
                    }
                }
                
                Platform.runLater(() -> {
                    statusLabel.setText("Verzeichnis hochgeladen: " + dir.getName());
                    refreshRemote();
                });
            } catch (Exception e) {
                logger.error("Failed to upload directory", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Fehler beim Hochladen des Verzeichnisses: " + e.getMessage());
                });
            }
        }).start();
    }
    
    private void uploadSingleFileToPath(File file, String remotePath) {
        String remoteFilePath = remotePath.endsWith("/") ? 
            remotePath + file.getName() : remotePath + "/" + file.getName();
        
        try {
            sftpSession.uploadFile(file.toPath(), remoteFilePath);
        } catch (Exception e) {
            logger.error("Failed to upload file to path", e);
        }
    }
    
    private void downloadSelected() {
        List<FileItem> selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            return;
        }
        
        // If single file, ask for destination file
        if (selected.size() == 1 && selected.get(0).isFile()) {
            FileItem item = selected.get(0);
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle("Datei speichern als...");
            fileChooser.setInitialFileName(item.getName());
            fileChooser.setInitialDirectory(currentLocalPath.toFile());
            
            File destination = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
            if (destination == null) {
                return;
            }
            
            downloadSingleFile(item, destination.toPath());
        } else {
            // Multiple items or directory - ask for destination directory
            javafx.stage.DirectoryChooser dirChooser = new javafx.stage.DirectoryChooser();
            dirChooser.setTitle("Zielverzeichnis auswählen");
            dirChooser.setInitialDirectory(currentLocalPath.toFile());
            
            File destinationDir = dirChooser.showDialog(getDialogPane().getScene().getWindow());
            if (destinationDir == null) {
                return;
            }
            
            for (FileItem item : selected) {
                if (item.getName().equals("..")) continue;
                
                if (item.isFile()) {
                    downloadSingleFile(item, destinationDir.toPath().resolve(item.getName()));
                } else {
                    downloadDirectory(item, destinationDir.toPath());
                }
            }
        }
    }
    
    private void downloadSingleFile(FileItem item, Path localPath) {
        new Thread(() -> {
            try {
                Platform.runLater(() -> statusLabel.setText("Lade herunter: " + item.getName()));
                sftpSession.downloadFile(item.getPath(), localPath);
                Platform.runLater(() -> {
                    statusLabel.setText("Heruntergeladen: " + item.getName());
                    refreshLocal();
                });
            } catch (Exception e) {
                logger.error("Failed to download file", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Fehler beim Herunterladen: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Download Fehler");
                    alert.setHeaderText("Datei konnte nicht heruntergeladen werden");
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }).start();
    }
    
    private void downloadDirectory(FileItem item, Path localBasePath) {
        new Thread(() -> {
            try {
                Path localDirPath = localBasePath.resolve(item.getName());
                Files.createDirectories(localDirPath);
                
                Platform.runLater(() -> statusLabel.setText("Lade Verzeichnis: " + item.getName()));
                
                try {
                    downloadDirectoryRecursive(item.getPath(), localDirPath);
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
                
                Platform.runLater(() -> {
                    statusLabel.setText("Verzeichnis heruntergeladen: " + item.getName());
                    refreshLocal();
                });
            } catch (Exception e) {
                logger.error("Failed to download directory", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Fehler beim Herunterladen des Verzeichnisses: " + e.getMessage());
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Download Fehler");
                    alert.setHeaderText("Verzeichnis konnte nicht heruntergeladen werden");
                    alert.setContentText(e.getMessage());
                    alert.showAndWait();
                });
            }
        }).start();
    }
    
    private void downloadDirectoryRecursive(String remotePath, Path localPath) throws Exception {
        List<SftpClient.DirEntry> entries = sftpSession.listFiles(remotePath);
        
        for (SftpClient.DirEntry entry : entries) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) continue;
            
            String remoteEntryPath = remotePath.endsWith("/") ? remotePath + name : remotePath + "/" + name;
            Path localEntryPath = localPath.resolve(name);
            
            SftpClient.Attributes attrs = entry.getAttributes();
            if (attrs.isDirectory()) {
                Files.createDirectories(localEntryPath);
                downloadDirectoryRecursive(remoteEntryPath, localEntryPath);
            } else {
                sftpSession.downloadFile(remoteEntryPath, localEntryPath);
            }
        }
    }
    
    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.1f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }
    
    private Long parseSize(String sizeStr) {
        if (sizeStr == null || sizeStr.isEmpty() || sizeStr.equals("<DIR>")) {
            return Long.MIN_VALUE; // Directories sort first
        }
        try {
            if (sizeStr.endsWith(" B")) {
                return Long.parseLong(sizeStr.replace(" B", "").trim());
            } else if (sizeStr.endsWith(" KB")) {
                return (long)(Double.parseDouble(sizeStr.replace(" KB", "").trim()) * 1024);
            } else if (sizeStr.endsWith(" MB")) {
                return (long)(Double.parseDouble(sizeStr.replace(" MB", "").trim()) * 1024 * 1024);
            } else if (sizeStr.endsWith(" GB")) {
                return (long)(Double.parseDouble(sizeStr.replace(" GB", "").trim()) * 1024 * 1024 * 1024);
            }
        } catch (NumberFormatException e) {
            // Ignore
        }
        return 0L;
    }
    
    /**
     * Sets up a sortable column header with an icon button.
     */
    private void setupSortableColumnHeader(TableColumn<FileItem, String> column, String title, 
                                           TableView<FileItem> table, TableColumn<FileItem, String> sortColumn) {
        HBox headerBox = new HBox(5);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        
        Label titleLabel = new Label(title);
        
        Button sortButton = new Button("⇅");
        sortButton.setStyle("-fx-font-size: 10px; -fx-padding: 2 4 2 4; -fx-min-width: 20px; -fx-pref-width: 20px;");
        sortButton.setTooltip(new Tooltip("Sortieren"));
        
        // Track sort state: 0 = unsorted, 1 = ascending, 2 = descending
        javafx.beans.property.SimpleIntegerProperty sortState = new javafx.beans.property.SimpleIntegerProperty(0);
        
        sortButton.setOnAction(e -> {
            javafx.collections.ObservableList<TableColumn<FileItem, ?>> sortOrder = table.getSortOrder();
            int currentState = sortState.get();
            
            // Determine next sort state: 0 -> 1 (asc), 1 -> 2 (desc), 2 -> 1 (asc)
            boolean isAscending = (currentState == 0 || currentState == 2);
            
            // Clear existing sort order
            sortOrder.clear();
            
            // Reset all column sort states
            for (TableColumn<FileItem, ?> col : table.getColumns()) {
                if (col != sortColumn) {
                    col.setSortType(null);
                }
            }
            
            // Set sort type
            sortColumn.setSortType(isAscending ? TableColumn.SortType.ASCENDING : TableColumn.SortType.DESCENDING);
            sortOrder.add(sortColumn);
            
            // Update button text and state
            sortButton.setText(isAscending ? "↑" : "↓");
            sortState.set(isAscending ? 1 : 2);
            
            // Apply sorting - need to sort the underlying list
            if (table == localTable) {
                FXCollections.sort(localItems, (a, b) -> {
                    int result = sortColumn.getComparator().compare(
                        sortColumn.getCellData(a), 
                        sortColumn.getCellData(b)
                    );
                    return isAscending ? result : -result;
                });
            } else if (table == remoteTable) {
                FXCollections.sort(remoteItems, (a, b) -> {
                    int result = sortColumn.getComparator().compare(
                        sortColumn.getCellData(a), 
                        sortColumn.getCellData(b)
                    );
                    return isAscending ? result : -result;
                });
            }
        });
        
        headerBox.getChildren().addAll(titleLabel, sortButton);
        column.setGraphic(headerBox);
    }
    
    private void deleteLocalFile(FileItem item) {
        if (item == null || item.getName().equals("..")) {
            return;
        }
        
        File file = new File(item.getPath());
        if (!file.exists()) {
            return;
        }
        
        String itemType = file.isDirectory() ? "Verzeichnis" : "Datei";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Löschen bestätigen");
        confirm.setHeaderText(itemType + " löschen");
        confirm.setContentText("Möchten Sie '" + item.getName() + "' wirklich löschen?");
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                try {
                    if (file.isDirectory()) {
                        deleteDirectory(file);
                    } else {
                        file.delete();
                    }
                    statusLabel.setText("Gelöscht: " + item.getName());
                    refreshLocal();
                } catch (Exception e) {
                    logger.error("Failed to delete local file", e);
                    Alert error = new Alert(Alert.AlertType.ERROR);
                    error.setTitle("Löschen fehlgeschlagen");
                    error.setHeaderText("Datei konnte nicht gelöscht werden");
                    error.setContentText(e.getMessage());
                    error.showAndWait();
                }
            }
        });
    }
    
    private void deleteDirectory(File dir) throws Exception {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectory(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
    
    private void renameLocalFile(FileItem item) {
        if (item == null || item.getName().equals("..")) {
            return;
        }
        
        File file = new File(item.getPath());
        if (!file.exists()) {
            return;
        }
        
        TextInputDialog dialog = new TextInputDialog(item.getName());
        dialog.setTitle("Umbenennen");
        dialog.setHeaderText("Neuer Name für '" + item.getName() + "':");
        dialog.setContentText("Name:");
        
        dialog.showAndWait().ifPresent(newName -> {
            if (newName != null && !newName.trim().isEmpty() && !newName.equals(item.getName())) {
                try {
                    File newFile = new File(file.getParent(), newName.trim());
                    if (file.renameTo(newFile)) {
                        statusLabel.setText("Umbenannt: " + item.getName() + " → " + newName);
                        refreshLocal();
                    } else {
                        Alert error = new Alert(Alert.AlertType.ERROR);
                        error.setTitle("Umbenennen fehlgeschlagen");
                        error.setHeaderText("Datei konnte nicht umbenannt werden");
                        error.setContentText("Möglicherweise existiert bereits eine Datei mit diesem Namen.");
                        error.showAndWait();
                    }
                } catch (Exception e) {
                    logger.error("Failed to rename local file", e);
                    Alert error = new Alert(Alert.AlertType.ERROR);
                    error.setTitle("Umbenennen fehlgeschlagen");
                    error.setHeaderText("Fehler beim Umbenennen");
                    error.setContentText(e.getMessage());
                    error.showAndWait();
                }
            }
        });
    }
    
    private void deleteRemoteFile(FileItem item) {
        if (item == null || item.getName().equals("..")) {
            return;
        }
        
        if (sftpSession == null || !sftpSession.isConnected()) {
            return;
        }
        
        String itemType = item.isFile() ? "Datei" : "Verzeichnis";
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle("Löschen bestätigen");
        confirm.setHeaderText(itemType + " löschen");
        confirm.setContentText("Möchten Sie '" + item.getName() + "' wirklich löschen?");
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                new Thread(() -> {
                    try {
                        Platform.runLater(() -> statusLabel.setText("Lösche: " + item.getName()));
                        sftpSession.deleteFile(item.getPath());
                        Platform.runLater(() -> {
                            statusLabel.setText("Gelöscht: " + item.getName());
                            refreshRemote();
                        });
                    } catch (Exception e) {
                        logger.error("Failed to delete remote file", e);
                        Platform.runLater(() -> {
                            statusLabel.setText("Fehler beim Löschen: " + e.getMessage());
                            Alert error = new Alert(Alert.AlertType.ERROR);
                            error.setTitle("Löschen fehlgeschlagen");
                            error.setHeaderText("Datei konnte nicht gelöscht werden");
                            error.setContentText(e.getMessage());
                            error.showAndWait();
                        });
                    }
                }).start();
            }
        });
    }
    
    private void renameRemoteFile(FileItem item) {
        if (item == null || item.getName().equals("..")) {
            return;
        }
        
        if (sftpSession == null || !sftpSession.isConnected()) {
            return;
        }
        
        TextInputDialog dialog = new TextInputDialog(item.getName());
        dialog.setTitle("Umbenennen");
        dialog.setHeaderText("Neuer Name für '" + item.getName() + "':");
        dialog.setContentText("Name:");
        
        dialog.showAndWait().ifPresent(newName -> {
            if (newName != null && !newName.trim().isEmpty() && !newName.equals(item.getName())) {
                new Thread(() -> {
                    try {
                        String parentPath = item.getPath().substring(0, item.getPath().lastIndexOf('/'));
                        if (parentPath.isEmpty()) parentPath = "/";
                        String newPath = parentPath + "/" + newName.trim();
                        
                        Platform.runLater(() -> statusLabel.setText("Benenne um: " + item.getName() + " → " + newName));
                        sftpSession.renameFile(item.getPath(), newPath);
                        Platform.runLater(() -> {
                            statusLabel.setText("Umbenannt: " + item.getName() + " → " + newName);
                            refreshRemote();
                        });
                    } catch (Exception e) {
                        logger.error("Failed to rename remote file", e);
                        Platform.runLater(() -> {
                            statusLabel.setText("Fehler beim Umbenennen: " + e.getMessage());
                            Alert error = new Alert(Alert.AlertType.ERROR);
                            error.setTitle("Umbenennen fehlgeschlagen");
                            error.setHeaderText("Datei konnte nicht umbenannt werden");
                            error.setContentText(e.getMessage());
                            error.showAndWait();
                        });
                    }
                }).start();
            }
        });
    }
    
    private void changeRemotePermissions(FileItem item) {
        if (item == null || item.getName().equals("..")) {
            return;
        }
        
        if (sftpSession == null || !sftpSession.isConnected()) {
            return;
        }
        
        new Thread(() -> {
            try {
                String currentPerms = sftpSession.getPermissions(item.getPath());
                int currentPermsInt = Integer.parseInt(currentPerms, 8);
                
                Platform.runLater(() -> {
                    Dialog<int[]> permDialog = new Dialog<>();
                    permDialog.setTitle("Berechtigungen ändern");
                    permDialog.setHeaderText("Berechtigungen für '" + item.getName() + "'");
                    permDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
                    
                    VBox content = new VBox(15);
                    content.setPadding(new Insets(20));
                    
                    Label infoLabel = new Label("Aktuelle Berechtigungen: " + currentPerms);
                    infoLabel.setStyle("-fx-font-weight: bold;");
                    
                    // Owner permissions
                    Label ownerLabel = new Label("Besitzer (Owner):");
                    ownerLabel.setStyle("-fx-font-weight: bold;");
                    CheckBox ownerRead = new CheckBox("Lesen (r)");
                    CheckBox ownerWrite = new CheckBox("Schreiben (w)");
                    CheckBox ownerExecute = new CheckBox("Ausführen (x)");
                    ownerRead.setSelected((currentPermsInt & 0400) != 0);
                    ownerWrite.setSelected((currentPermsInt & 0200) != 0);
                    ownerExecute.setSelected((currentPermsInt & 0100) != 0);
                    
                    HBox ownerBox = new HBox(10);
                    ownerBox.getChildren().addAll(ownerRead, ownerWrite, ownerExecute);
                    
                    // Group permissions
                    Label groupLabel = new Label("Gruppe (Group):");
                    groupLabel.setStyle("-fx-font-weight: bold;");
                    CheckBox groupRead = new CheckBox("Lesen (r)");
                    CheckBox groupWrite = new CheckBox("Schreiben (w)");
                    CheckBox groupExecute = new CheckBox("Ausführen (x)");
                    groupRead.setSelected((currentPermsInt & 0040) != 0);
                    groupWrite.setSelected((currentPermsInt & 0020) != 0);
                    groupExecute.setSelected((currentPermsInt & 0010) != 0);
                    
                    HBox groupBox = new HBox(10);
                    groupBox.getChildren().addAll(groupRead, groupWrite, groupExecute);
                    
                    // Other permissions
                    Label otherLabel = new Label("Andere (Other):");
                    otherLabel.setStyle("-fx-font-weight: bold;");
                    CheckBox otherRead = new CheckBox("Lesen (r)");
                    CheckBox otherWrite = new CheckBox("Schreiben (w)");
                    CheckBox otherExecute = new CheckBox("Ausführen (x)");
                    otherRead.setSelected((currentPermsInt & 0004) != 0);
                    otherWrite.setSelected((currentPermsInt & 0002) != 0);
                    otherExecute.setSelected((currentPermsInt & 0001) != 0);
                    
                    HBox otherBox = new HBox(10);
                    otherBox.getChildren().addAll(otherRead, otherWrite, otherExecute);
                    
                    content.getChildren().addAll(infoLabel, 
                        ownerLabel, ownerBox,
                        groupLabel, groupBox,
                        otherLabel, otherBox);
                    
                    permDialog.getDialogPane().setContent(content);
                    
                    permDialog.setResultConverter(buttonType -> {
                        if (buttonType == ButtonType.OK) {
                            int perms = 0;
                            if (ownerRead.isSelected()) perms |= 0400;
                            if (ownerWrite.isSelected()) perms |= 0200;
                            if (ownerExecute.isSelected()) perms |= 0100;
                            if (groupRead.isSelected()) perms |= 0040;
                            if (groupWrite.isSelected()) perms |= 0020;
                            if (groupExecute.isSelected()) perms |= 0010;
                            if (otherRead.isSelected()) perms |= 0004;
                            if (otherWrite.isSelected()) perms |= 0002;
                            if (otherExecute.isSelected()) perms |= 0001;
                            return new int[]{perms};
                        }
                        return null;
                    });
                    
                    permDialog.showAndWait().ifPresent(result -> {
                        if (result != null && result[0] != currentPermsInt) {
                            new Thread(() -> {
                                try {
                                    String newPerms = String.format("%04o", result[0]);
                                    Platform.runLater(() -> statusLabel.setText("Ändere Berechtigungen: " + item.getName()));
                                    sftpSession.setPermissions(item.getPath(), newPerms);
                                    Platform.runLater(() -> {
                                        statusLabel.setText("Berechtigungen geändert: " + item.getName());
                                        refreshRemote();
                                    });
                                } catch (Exception e) {
                                    logger.error("Failed to change permissions", e);
                                    Platform.runLater(() -> {
                                        statusLabel.setText("Fehler beim Ändern der Berechtigungen: " + e.getMessage());
                                        Alert error = new Alert(Alert.AlertType.ERROR);
                                        error.setTitle("Berechtigungen ändern fehlgeschlagen");
                                        error.setHeaderText("Berechtigungen konnten nicht geändert werden");
                                        error.setContentText(e.getMessage());
                                        error.showAndWait();
                                    });
                                }
                            }).start();
                        }
                    });
                });
            } catch (Exception e) {
                logger.error("Failed to get current permissions", e);
                Platform.runLater(() -> {
                    Alert error = new Alert(Alert.AlertType.ERROR);
                    error.setTitle("Fehler");
                    error.setHeaderText("Berechtigungen konnten nicht abgerufen werden");
                    error.setContentText(e.getMessage());
                    error.showAndWait();
                });
            }
        }).start();
    }
    
    private void copyLocalSelected() {
        List<FileItem> selected = localTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            return;
        }
        
        TextInputDialog dialog = new TextInputDialog(currentLocalPath.toString());
        dialog.setTitle("Kopieren");
        dialog.setHeaderText("Zielverzeichnis für Kopie:");
        dialog.setContentText("Pfad:");
        
        dialog.showAndWait().ifPresent(destPath -> {
            if (destPath != null && !destPath.trim().isEmpty()) {
                new Thread(() -> {
                    try {
                        Path dest = Paths.get(destPath.trim());
                        if (!Files.exists(dest)) {
                            Files.createDirectories(dest);
                        }
                        
                        for (FileItem item : selected) {
                            if (item.getName().equals("..")) continue;
                            
                            File source = new File(item.getPath());
                            Path target = dest.resolve(item.getName());
                            
                            Platform.runLater(() -> statusLabel.setText("Kopiere: " + item.getName()));
                            
                            if (source.isDirectory()) {
                                copyDirectory(source.toPath(), target);
                            } else {
                                Files.copy(source.toPath(), target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            }
                        }
                        
                        Platform.runLater(() -> {
                            statusLabel.setText("Kopieren abgeschlossen");
                            refreshLocal();
                        });
                    } catch (Exception e) {
                        logger.error("Failed to copy local files", e);
                        Platform.runLater(() -> {
                            statusLabel.setText("Fehler beim Kopieren: " + e.getMessage());
                            Alert error = new Alert(Alert.AlertType.ERROR);
                            error.setTitle("Kopieren fehlgeschlagen");
                            error.setHeaderText("Dateien konnten nicht kopiert werden");
                            error.setContentText(e.getMessage());
                            error.showAndWait();
                        });
                    }
                }).start();
            }
        });
    }
    
    private void copyDirectory(Path source, Path target) throws Exception {
        Files.createDirectories(target);
        Files.walk(source).forEach(sourcePath -> {
            try {
                Path targetPath = target.resolve(source.relativize(sourcePath));
                if (Files.isDirectory(sourcePath)) {
                    Files.createDirectories(targetPath);
                } else {
                    Files.copy(sourcePath, targetPath, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
    
    private void copyRemoteSelected() {
        List<FileItem> selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            return;
        }
        
        if (sftpSession == null || !sftpSession.isConnected()) {
            return;
        }
        
        TextInputDialog dialog = new TextInputDialog(currentRemotePath);
        dialog.setTitle("Kopieren");
        dialog.setHeaderText("Zielverzeichnis für Kopie:");
        dialog.setContentText("Pfad:");
        
        dialog.showAndWait().ifPresent(destPath -> {
            if (destPath != null && !destPath.trim().isEmpty()) {
                new Thread(() -> {
                    try {
                        String dest = destPath.trim();
                        if (!dest.endsWith("/")) dest += "/";
                        
                        for (FileItem item : selected) {
                            if (item.getName().equals("..")) continue;
                            
                            String sourcePath = item.getPath();
                            String targetPath = dest + item.getName();
                            
                            Platform.runLater(() -> statusLabel.setText("Kopiere: " + item.getName()));
                            sftpSession.copyFile(sourcePath, targetPath);
                        }
                        
                        Platform.runLater(() -> {
                            statusLabel.setText("Kopieren abgeschlossen");
                            refreshRemote();
                        });
                    } catch (Exception e) {
                        logger.error("Failed to copy remote files", e);
                        Platform.runLater(() -> {
                            statusLabel.setText("Fehler beim Kopieren: " + e.getMessage());
                            Alert error = new Alert(Alert.AlertType.ERROR);
                            error.setTitle("Kopieren fehlgeschlagen");
                            error.setHeaderText("Dateien konnten nicht kopiert werden");
                            error.setContentText(e.getMessage());
                            error.showAndWait();
                        });
                    }
                }).start();
            }
        });
    }
    
    private void createZipArchive() {
        List<FileItem> selected = remoteTable.getSelectionModel().getSelectedItems();
        if (selected == null || selected.isEmpty()) {
            // Try local selection
            selected = localTable.getSelectionModel().getSelectedItems();
            if (selected == null || selected.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle("Keine Auswahl");
                alert.setHeaderText("Bitte wählen Sie Dateien oder Verzeichnisse aus");
                alert.showAndWait();
                return;
            }
            createZipFromLocal(selected);
        } else {
            createZipFromRemote(selected);
        }
    }
    
    private void createZipFromLocal(List<FileItem> selected) {
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle("ZIP-Datei speichern als...");
        fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("ZIP-Dateien", "*.zip"));
        fileChooser.setInitialFileName("archive.zip");
        fileChooser.setInitialDirectory(currentLocalPath.toFile());
        
        File zipFile = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (zipFile == null) {
            return;
        }
        
        new Thread(() -> {
            try {
                ZipFile zip = new ZipFile(zipFile);
                ZipParameters parameters = new ZipParameters();
                parameters.setCompressionLevel(CompressionLevel.NORMAL);
                
                for (FileItem item : selected) {
                    if (item.getName().equals("..")) continue;
                    
                    File source = new File(item.getPath());
                    Platform.runLater(() -> statusLabel.setText("Füge hinzu: " + item.getName()));
                    
                    if (source.isDirectory()) {
                        zip.addFolder(source, parameters);
                    } else {
                        zip.addFile(source, parameters);
                    }
                }
                
                Platform.runLater(() -> {
                    statusLabel.setText("ZIP-Archiv erstellt: " + zipFile.getName());
                });
            } catch (Exception e) {
                logger.error("Failed to create ZIP archive", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Fehler beim Erstellen des ZIP-Archivs: " + e.getMessage());
                    Alert error = new Alert(Alert.AlertType.ERROR);
                    error.setTitle("ZIP-Erstellung fehlgeschlagen");
                    error.setHeaderText("ZIP-Archiv konnte nicht erstellt werden");
                    error.setContentText(e.getMessage());
                    error.showAndWait();
                });
            }
        }).start();
    }
    
    private void createZipFromRemote(List<FileItem> selected) {
        if (sftpSession == null || !sftpSession.isConnected()) {
            return;
        }
        
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle("ZIP-Datei speichern als...");
        fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("ZIP-Dateien", "*.zip"));
        fileChooser.setInitialFileName("archive.zip");
        fileChooser.setInitialDirectory(currentLocalPath.toFile());
        
        File zipFile = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (zipFile == null) {
            return;
        }
        
        new Thread(() -> {
            try {
                ZipFile zip = new ZipFile(zipFile);
                ZipParameters parameters = new ZipParameters();
                parameters.setCompressionLevel(CompressionLevel.NORMAL);
                
                // Create temporary directory for downloaded files
                Path tempDir = Files.createTempDirectory("sftp_zip_");
                
                try {
                    for (FileItem item : selected) {
                        if (item.getName().equals("..")) continue;
                        
                        Platform.runLater(() -> statusLabel.setText("Lade herunter: " + item.getName()));
                        Path tempFile = tempDir.resolve(item.getName());
                        
                        if (item.isFile()) {
                            sftpSession.downloadFile(item.getPath(), tempFile);
                            zip.addFile(tempFile.toFile(), parameters);
                        } else {
                            // Download directory recursively
                            downloadDirectoryRecursiveForZip(item.getPath(), tempFile, zip, parameters);
                        }
                    }
                    
                    Platform.runLater(() -> {
                        statusLabel.setText("ZIP-Archiv erstellt: " + zipFile.getName());
                    });
                } finally {
                    // Cleanup temp directory
                    deleteDirectoryRecursive(tempDir.toFile());
                }
            } catch (Exception e) {
                logger.error("Failed to create ZIP archive from remote", e);
                Platform.runLater(() -> {
                    statusLabel.setText("Fehler beim Erstellen des ZIP-Archivs: " + e.getMessage());
                    Alert error = new Alert(Alert.AlertType.ERROR);
                    error.setTitle("ZIP-Erstellung fehlgeschlagen");
                    error.setHeaderText("ZIP-Archiv konnte nicht erstellt werden");
                    error.setContentText(e.getMessage());
                    error.showAndWait();
                });
            }
        }).start();
    }
    
    private void downloadDirectoryRecursiveForZip(String remotePath, Path localPath, ZipFile zip, ZipParameters parameters) throws Exception {
        Files.createDirectories(localPath);
        List<SftpClient.DirEntry> entries = sftpSession.listFiles(remotePath);
        
        for (SftpClient.DirEntry entry : entries) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals("..")) continue;
            
            String remoteEntryPath = remotePath.endsWith("/") ? remotePath + name : remotePath + "/" + name;
            Path localEntryPath = localPath.resolve(name);
            
            SftpClient.Attributes attrs = entry.getAttributes();
            if (attrs.isDirectory()) {
                downloadDirectoryRecursiveForZip(remoteEntryPath, localEntryPath, zip, parameters);
            } else {
                sftpSession.downloadFile(remoteEntryPath, localEntryPath);
                zip.addFile(localEntryPath.toFile(), parameters);
            }
        }
    }
    
    private void deleteDirectoryRecursive(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file.isDirectory()) {
                    deleteDirectoryRecursive(file);
                } else {
                    file.delete();
                }
            }
        }
        dir.delete();
    }
    
    private void cleanup() {
        if (sftpSession != null) {
            sftpSession.close();
        }
    }
    
    /**
     * Represents a file or directory item in the file manager.
     */
    public static class FileItem {
        private final String name;
        private final String path;
        private final boolean file;
        private final String size;
        private final String date;
        
        public FileItem(String name, String path, boolean file, String size, String date) {
            this.name = name;
            this.path = path;
            this.file = file;
            this.size = size;
            this.date = date;
        }
        
        public String getName() { return name; }
        public String getPath() { return path; }
        public boolean isFile() { return file; }
        public String getSize() { return size; }
        public String getDate() { return date; }
    }
}
