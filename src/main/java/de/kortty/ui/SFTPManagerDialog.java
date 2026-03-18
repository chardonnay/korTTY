package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.SftpFileTransferService;
import de.kortty.core.SFTPSession;
import de.kortty.model.ServerConnection;
import de.kortty.model.TemporarySSHKey;
import de.kortty.ui.sftp.SftpFileItem;
import de.kortty.ui.sftp.SftpManagerViewModel;
import javafx.application.Platform;
import javafx.concurrent.Task;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private final TemporarySSHKey temporarySSHKey;
    private final SftpManagerViewModel viewModel = new SftpManagerViewModel();
    private final SftpFileTransferService transferService = new SftpFileTransferService();
    private final ExecutorService backgroundExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "SFTP-Dialog-Worker");
        thread.setDaemon(true);
        return thread;
    });
    
    private TableView<SftpFileItem> localTable;
    private TableView<SftpFileItem> remoteTable;
    private TextField localPathField;
    private TextField remotePathField;
    private TextField localSearchField;
    private TextField remoteSearchField;
    private Label statusLabel;
    
    private FilteredList<SftpFileItem> filteredLocalItems;
    private FilteredList<SftpFileItem> filteredRemoteItems;
    
    public SFTPManagerDialog(Stage owner, KorTTYApplication app, ServerConnection connection, String password) {
        this(owner, app, connection, password, null);
    }
    
    public SFTPManagerDialog(Stage owner, KorTTYApplication app, ServerConnection connection, String password, TemporarySSHKey temporarySSHKey) {
        this.app = app;
        this.connection = connection;
        this.password = password;
        this.temporarySSHKey = temporarySSHKey;
        
        setTitle("SFTP Manager - " + connection.getDisplayName());
        setHeaderText(I18n.get("sftp.headerText"));
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);
        
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
        viewModel.setStatusText(I18n.get("sftp.connecting"));
        statusLabel = new Label();
        statusLabel.textProperty().bind(viewModel.statusTextProperty());
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
        
        Button uploadButton = new Button("→ " + I18n.get("sftp.upload"));
        uploadButton.setOnAction(e -> uploadSelected());
        
        Button downloadButton = new Button("← " + I18n.get("sftp.download"));
        downloadButton.setOnAction(e -> downloadSelected());
        
        Button refreshLocalButton = new Button(I18n.get("sftp.refreshLocal"));
        refreshLocalButton.setOnAction(e -> refreshLocal());
        
        Button refreshRemoteButton = new Button(I18n.get("sftp.refreshRemote"));
        refreshRemoteButton.setOnAction(e -> refreshRemote());
        
        // Copy buttons
        Button copyLocalButton = new Button(I18n.get("sftp.copyLocal"));
        copyLocalButton.setOnAction(e -> copyLocalSelected());
        
        Button copyRemoteButton = new Button(I18n.get("sftp.copyRemote"));
        copyRemoteButton.setOnAction(e -> copyRemoteSelected());
        
        // ZIP button
        Button createZipButton = new Button(I18n.get("sftp.createZip"));
        createZipButton.setOnAction(e -> createZipArchive());
        
        buttonBox.getChildren().addAll(uploadButton, downloadButton, 
                new Separator(), copyLocalButton, copyRemoteButton,
                new Separator(), createZipButton,
                new Separator(), refreshLocalButton, refreshRemoteButton);
        
        // Enable/disable buttons based on selection
        localTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            uploadButton.setDisable(selected == null || viewModel.isBusy());
        });
        
        remoteTable.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            downloadButton.setDisable(selected == null || viewModel.isBusy());
        });
        viewModel.busyProperty().addListener((obs, old, busy) -> {
            uploadButton.setDisable(busy || localTable.getSelectionModel().getSelectedItem() == null);
            downloadButton.setDisable(busy || remoteTable.getSelectionModel().getSelectedItem() == null);
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
        
        TableColumn<SftpFileItem, String> nameColumn = new TableColumn<>();
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
        
        TableColumn<SftpFileItem, String> sizeColumn = new TableColumn<>();
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
        setupSortableColumnHeader(sizeColumn, I18n.get("sftp.column.size"), localTable, sizeColumn);
        
        TableColumn<SftpFileItem, String> dateColumn = new TableColumn<>();
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateColumn.setPrefWidth(150);
        dateColumn.setMinWidth(120);
        dateColumn.setSortable(false); // Disable default sorting
        dateColumn.setComparator(String::compareTo);
        setupSortableColumnHeader(dateColumn, I18n.get("sftp.column.date"), localTable, dateColumn);
        
        localTable.getColumns().addAll(java.util.List.of(nameColumn, sizeColumn, dateColumn));
        
        // Double-click to navigate and context menu for local files
        localTable.setRowFactory(tv -> {
            TableRow<SftpFileItem> row = new TableRow<>();
            ContextMenu contextMenu = new ContextMenu();
            
            MenuItem renameItem = new MenuItem(I18n.get("sftp.rename"));
            renameItem.setOnAction(e -> {
                if (!row.isEmpty()) {
                    renameLocalFile(row.getItem());
                }
            });
            
            MenuItem deleteItem = new MenuItem(I18n.get("sftp.contextMenu.delete"));
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
                    SftpFileItem item = row.getItem();
                    if (!item.isFile()) {
                        navigateLocal(item.getPath());
                    }
                }
            });
            
            return row;
        });
        
        // Search filter
        filteredLocalItems = new FilteredList<>(viewModel.getLocalItems(), p -> true);
        localSearchField.setPromptText(I18n.get("sftp.searchPrompt"));
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
        localPathField.setText(viewModel.getCurrentLocalPath().toString());
        
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
        
        TableColumn<SftpFileItem, String> nameColumn = new TableColumn<>();
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
        
        TableColumn<SftpFileItem, String> sizeColumn = new TableColumn<>();
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
        setupSortableColumnHeader(sizeColumn, I18n.get("sftp.column.size"), remoteTable, sizeColumn);
        
        TableColumn<SftpFileItem, String> dateColumn = new TableColumn<>();
        dateColumn.setCellValueFactory(new PropertyValueFactory<>("date"));
        dateColumn.setPrefWidth(150);
        dateColumn.setMinWidth(120);
        dateColumn.setSortable(false); // Disable default sorting
        dateColumn.setComparator(String::compareTo);
        setupSortableColumnHeader(dateColumn, I18n.get("sftp.column.date"), remoteTable, dateColumn);
        
        remoteTable.getColumns().addAll(java.util.List.of(nameColumn, sizeColumn, dateColumn));
        
        // Double-click to navigate and context menu
        remoteTable.setRowFactory(tv -> {
            TableRow<SftpFileItem> row = new TableRow<>();
            ContextMenu contextMenu = new ContextMenu();
            
            MenuItem renameItem = new MenuItem(I18n.get("sftp.rename"));
            renameItem.setOnAction(e -> {
                if (!row.isEmpty()) {
                    renameRemoteFile(row.getItem());
                }
            });
            
            MenuItem deleteItem = new MenuItem(I18n.get("sftp.contextMenu.delete"));
            deleteItem.setOnAction(e -> {
                if (!row.isEmpty()) {
                    deleteRemoteFile(row.getItem());
                }
            });
            
            MenuItem permissionsItem = new MenuItem(I18n.get("sftp.permissions") + "...");
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
                    SftpFileItem item = row.getItem();
                    if (!item.isFile()) {
                        navigateRemote(item.getPath());
                    }
                }
            });
            
            return row;
        });
        
        // Search filter
        filteredRemoteItems = new FilteredList<>(viewModel.getRemoteItems(), p -> true);
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
        remotePathField.setText(viewModel.getCurrentRemotePath());
        
        panel.getChildren().addAll(titleLabel, pathBox, searchBox, remoteTable);
        VBox.setVgrow(remoteTable, Priority.ALWAYS);
        
        return panel;
    }
    
    private void connectToSFTP() {
        executeTask(
            I18n.get("sftp.connecting"),
            () -> transferService.connect(createConfiguredSession()),
            remotePath -> {
                viewModel.setConnected(true);
                viewModel.setCurrentRemotePath(remotePath);
                remotePathField.setText(remotePath);
                viewModel.setStatusText(I18n.get("sftp.connected"));
                refreshLocal();
                refreshRemote();
            },
            error -> {
                logger.error("Failed to connect to SFTP", error);
                viewModel.setConnected(false);
                viewModel.setStatusText(I18n.get("sftp.connectionError") + ": " + safeMessage(error));
                showErrorAlert(I18n.get("sftp.error.connection"), I18n.get("sftp.error.connectionFailedHeader"), error);
                close();
            }
        );
    }

    private SFTPSession createConfiguredSession() {
        ServerConnection connToUse = connection;
        if (temporarySSHKey != null && temporarySSHKey.isValid()) {
            connToUse = new ServerConnection();
            connToUse.setId(connection.getId());
            connToUse.setName(connection.getName());
            connToUse.setHost(connection.getHost());
            connToUse.setPort(connection.getPort());
            connToUse.setUsername(connection.getUsername());
            connToUse.setSettings(connection.getSettings());
            connToUse.setConnectionTimeoutSeconds(connection.getConnectionTimeoutSeconds());
            connToUse.setAuthMethod(de.kortty.model.AuthMethod.PUBLIC_KEY);
            connToUse.setPrivateKeyPath("TEMPORARY:" + temporarySSHKey.getKeyContent());
        }

        SFTPSession session = new SFTPSession(connToUse, password);
        if (temporarySSHKey == null
                && connToUse.getAuthMethod() == de.kortty.model.AuthMethod.PUBLIC_KEY
                && app.getSSHKeyManager() != null) {
            session.setSSHKeyManager(app.getSSHKeyManager(), app.getMasterPasswordManager().getMasterPassword());
        }
        return session;
    }

    private void refreshLocal() {
        Path targetPath = viewModel.getCurrentLocalPath();
        executeTask(
            null,
            () -> new LocalListing(targetPath, transferService.listLocal(targetPath)),
            listing -> {
                viewModel.setCurrentLocalPath(listing.path());
                localPathField.setText(listing.path().toString());
                viewModel.replaceLocalItems(listing.items());
            },
            error -> {
                logger.error("Failed to refresh local files", error);
                viewModel.setStatusText(I18n.get("sftp.error.refresh", safeMessage(error)));
            }
        );
    }

    private void refreshRemote() {
        if (!viewModel.isConnected() || !transferService.isConnected()) {
            return;
        }
        String remotePath = viewModel.getCurrentRemotePath();
        executeTask(
            null,
            () -> new RemoteListing(remotePath, transferService.listRemote(remotePath)),
            listing -> {
                viewModel.setCurrentRemotePath(listing.path());
                remotePathField.setText(listing.path());
                viewModel.replaceRemoteItems(listing.items());
            },
            error -> {
                logger.error("Failed to refresh remote files", error);
                viewModel.setStatusText(I18n.get("sftp.error.refresh", safeMessage(error)));
            }
        );
    }

    private void navigateLocal(String path) {
        try {
            Path newPath = Paths.get(path).toAbsolutePath();
            if (!Files.exists(newPath) || !Files.isDirectory(newPath)) {
                viewModel.setStatusText(I18n.get("sftp.error.pathNotExists", path));
                return;
            }
            executeTask(
                null,
                () -> new LocalListing(newPath, transferService.listLocal(newPath)),
                listing -> {
                    viewModel.setCurrentLocalPath(listing.path());
                    localPathField.setText(listing.path().toString());
                    viewModel.replaceLocalItems(listing.items());
                },
                error -> {
                    logger.error("Failed to navigate local path", error);
                    viewModel.setStatusText(I18n.get("sftp.error.generic", safeMessage(error)));
                }
            );
        } catch (Exception e) {
            viewModel.setStatusText(I18n.get("sftp.error.generic", safeMessage(e)));
        }
    }

    private void navigateLocalUp() {
        Path currentPath = viewModel.getCurrentLocalPath();
        if (currentPath.getParent() != null) {
            navigateLocal(currentPath.getParent().toString());
        }
    }

    private void navigateRemote(String path) {
        if (!viewModel.isConnected() || !transferService.isConnected()) {
            return;
        }
        executeTask(
            null,
            () -> {
                String resolvedPath = transferService.changeRemoteDirectory(path);
                return new RemoteListing(resolvedPath, transferService.listRemote(resolvedPath));
            },
            listing -> {
                viewModel.setCurrentRemotePath(listing.path());
                remotePathField.setText(listing.path());
                viewModel.replaceRemoteItems(listing.items());
            },
            error -> {
                logger.error("Failed to navigate remote path", error);
                viewModel.setStatusText(I18n.get("sftp.error.generic", safeMessage(error)));
            }
        );
    }

    private void navigateRemoteUp() {
        String currentRemotePath = viewModel.getCurrentRemotePath();
        if (currentRemotePath.equals("/") || currentRemotePath.equals("~")) {
            return;
        }
        String parent = currentRemotePath.substring(0, currentRemotePath.lastIndexOf('/'));
        navigateRemote(parent.isEmpty() ? "/" : parent);
    }

    private void uploadSelected() {
        List<SftpFileItem> selected = snapshotSelection(localTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty() || !viewModel.isConnected()) {
            return;
        }
        String remotePath = viewModel.getCurrentRemotePath();
        executeTask(
            I18n.get("sftp.upload"),
            () -> {
                for (SftpFileItem item : selected) {
                    Path localPath = Paths.get(item.getPath());
                    if (Files.isDirectory(localPath)) {
                        transferService.uploadDirectory(localPath, remotePath);
                    } else {
                        transferService.uploadFile(localPath, remotePath);
                    }
                }
                return null;
            },
            ignored -> {
                viewModel.setStatusText(selected.size() == 1
                    ? I18n.get("sftp.uploadComplete", selected.get(0).getName())
                    : I18n.get("sftp.copyComplete"));
                refreshRemote();
            },
            error -> {
                logger.error("Failed to upload selected files", error);
                viewModel.setStatusText(I18n.get("sftp.uploadFailed", safeMessage(error)));
                showErrorAlert(I18n.get("sftp.error.upload"), I18n.get("sftp.error.uploadHeader"), error);
            }
        );
    }

    private void downloadSelected() {
        List<SftpFileItem> selected = snapshotSelection(remoteTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty() || !viewModel.isConnected()) {
            return;
        }

        if (selected.size() == 1 && selected.get(0).isFile()) {
            SftpFileItem item = selected.get(0);
            javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
            fileChooser.setTitle(I18n.get("sftp.saveFileAs"));
            fileChooser.setInitialFileName(item.getName());
            fileChooser.setInitialDirectory(viewModel.getCurrentLocalPath().toFile());

            File destination = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
            if (destination == null) {
                return;
            }

            executeTask(
                I18n.get("sftp.downloading", item.getName()),
                () -> {
                    transferService.downloadFile(item.getPath(), destination.toPath());
                    return destination.toPath();
                },
                ignored -> {
                    viewModel.setStatusText(I18n.get("sftp.downloadComplete", item.getName()));
                    refreshLocal();
                },
                error -> {
                    logger.error("Failed to download file", error);
                    viewModel.setStatusText(I18n.get("sftp.downloadFailed", safeMessage(error)));
                    showErrorAlert(I18n.get("sftp.error.download"), I18n.get("sftp.error.downloadHeader"), error);
                }
            );
            return;
        }

        javafx.stage.DirectoryChooser dirChooser = new javafx.stage.DirectoryChooser();
        dirChooser.setTitle(I18n.get("sftp.selectTargetFolder"));
        dirChooser.setInitialDirectory(viewModel.getCurrentLocalPath().toFile());

        File destinationDir = dirChooser.showDialog(getDialogPane().getScene().getWindow());
        if (destinationDir == null) {
            return;
        }

        Path destinationPath = destinationDir.toPath();
        executeTask(
            I18n.get("sftp.downloadingDirectory", selected.get(0).getName()),
            () -> {
                for (SftpFileItem item : selected) {
                    if (item.isFile()) {
                        transferService.downloadFile(item.getPath(), destinationPath.resolve(item.getName()));
                    } else {
                        transferService.downloadDirectory(item.getPath(), destinationPath);
                    }
                }
                return null;
            },
            ignored -> {
                viewModel.setStatusText(selected.size() == 1
                    ? I18n.get("sftp.directoryDownloaded", selected.get(0).getName())
                    : I18n.get("sftp.copyComplete"));
                refreshLocal();
            },
            error -> {
                logger.error("Failed to download selected entries", error);
                viewModel.setStatusText(I18n.get("sftp.error.downloadDirectory", safeMessage(error)));
                showErrorAlert(I18n.get("sftp.error.download"), I18n.get("sftp.error.downloadDirectoryHeader"), error);
            }
        );
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
    private void setupSortableColumnHeader(TableColumn<SftpFileItem, String> column, String title,
                                           TableView<SftpFileItem> table, TableColumn<SftpFileItem, String> sortColumn) {
        HBox headerBox = new HBox(5);
        headerBox.setAlignment(Pos.CENTER_LEFT);
        
        Label titleLabel = new Label(title);
        
        Button sortButton = new Button("⇅");
        sortButton.setStyle("-fx-font-size: 10px; -fx-padding: 2 4 2 4; -fx-min-width: 20px; -fx-pref-width: 20px;");
        sortButton.setTooltip(new Tooltip(I18n.get("sftp.sort")));
        
        // Track sort state: 0 = unsorted, 1 = ascending, 2 = descending
        javafx.beans.property.SimpleIntegerProperty sortState = new javafx.beans.property.SimpleIntegerProperty(0);
        
        sortButton.setOnAction(e -> {
            javafx.collections.ObservableList<TableColumn<SftpFileItem, ?>> sortOrder = table.getSortOrder();
            int currentState = sortState.get();
            
            // Determine next sort state: 0 -> 1 (asc), 1 -> 2 (desc), 2 -> 1 (asc)
            boolean isAscending = (currentState == 0 || currentState == 2);
            
            // Clear existing sort order
            sortOrder.clear();
            
            // Reset all column sort states
            for (TableColumn<SftpFileItem, ?> col : table.getColumns()) {
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
                FXCollections.sort(viewModel.getLocalItems(), (a, b) -> {
                    int result = sortColumn.getComparator().compare(
                        sortColumn.getCellData(a), 
                        sortColumn.getCellData(b)
                    );
                    return isAscending ? result : -result;
                });
            } else if (table == remoteTable) {
                FXCollections.sort(viewModel.getRemoteItems(), (a, b) -> {
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
    
    private void deleteLocalFile(SftpFileItem item) {
        if (item == null || item.isParentEntry()) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("sftp.confirmDelete"));
        confirm.setHeaderText(I18n.get("sftp.confirmDelete"));
        confirm.setContentText(I18n.get("sftp.confirmDeleteMessage", item.getName()));
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                executeTask(
                    I18n.get("sftp.deleting", item.getName()),
                    () -> {
                        transferService.deleteLocal(Paths.get(item.getPath()));
                        return null;
                    },
                    ignored -> {
                        viewModel.setStatusText(I18n.get("sftp.deleted", item.getName()));
                        refreshLocal();
                    },
                    error -> {
                        logger.error("Failed to delete local file", error);
                        showErrorAlert(I18n.get("sftp.error.deleteFailed"), I18n.get("sftp.error.deleteFailedHeader"), error);
                    }
                );
            }
        });
    }

    private void renameLocalFile(SftpFileItem item) {
        if (item == null || item.isParentEntry()) {
            return;
        }

        TextInputDialog dialog = new TextInputDialog(item.getName());
        dialog.setTitle(I18n.get("sftp.rename"));
        dialog.setHeaderText(I18n.get("sftp.renamePrompt", item.getName()));
        dialog.setContentText(I18n.get("sftp.nameLabel"));
        
        dialog.showAndWait().ifPresent(newName -> {
            if (newName != null && !newName.trim().isEmpty() && !newName.equals(item.getName())) {
                executeTask(
                    I18n.get("sftp.renaming", item.getName(), newName),
                    () -> transferService.renameLocal(Paths.get(item.getPath()), newName.trim()),
                    ignored -> {
                        viewModel.setStatusText(I18n.get("sftp.renamed", item.getName(), newName));
                        refreshLocal();
                    },
                    error -> {
                        logger.error("Failed to rename local file", error);
                        showErrorAlert(I18n.get("sftp.error.renameFailed"), I18n.get("sftp.error.renameFailedHeader"), error);
                    }
                );
            }
        });
    }

    private void deleteRemoteFile(SftpFileItem item) {
        if (item == null || item.isParentEntry() || !viewModel.isConnected()) {
            return;
        }

        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("sftp.confirmDelete"));
        confirm.setHeaderText(I18n.get("sftp.confirmDelete"));
        confirm.setContentText(I18n.get("sftp.confirmDeleteMessage", item.getName()));
        
        confirm.showAndWait().ifPresent(response -> {
            if (response == ButtonType.OK) {
                executeTask(
                    I18n.get("sftp.deleting", item.getName()),
                    () -> {
                        transferService.deleteRemote(item.getPath());
                        return null;
                    },
                    ignored -> {
                        viewModel.setStatusText(I18n.get("sftp.deleted", item.getName()));
                        refreshRemote();
                    },
                    error -> {
                        logger.error("Failed to delete remote file", error);
                        viewModel.setStatusText(I18n.get("sftp.error.deleteMessage", safeMessage(error)));
                        showErrorAlert(I18n.get("sftp.error.deleteFailed"), I18n.get("sftp.error.deleteFailedHeader"), error);
                    }
                );
            }
        });
    }

    private void renameRemoteFile(SftpFileItem item) {
        if (item == null || item.isParentEntry() || !viewModel.isConnected()) {
            return;
        }

        TextInputDialog dialog = new TextInputDialog(item.getName());
        dialog.setTitle(I18n.get("sftp.rename"));
        dialog.setHeaderText(I18n.get("sftp.renamePrompt", item.getName()));
        dialog.setContentText(I18n.get("sftp.nameLabel"));
        
        dialog.showAndWait().ifPresent(newName -> {
            if (newName != null && !newName.trim().isEmpty() && !newName.equals(item.getName())) {
                executeTask(
                    I18n.get("sftp.renaming", item.getName(), newName),
                    () -> transferService.renameRemote(item.getPath(), newName.trim()),
                    ignored -> {
                        viewModel.setStatusText(I18n.get("sftp.renamed", item.getName(), newName));
                        refreshRemote();
                    },
                    error -> {
                        logger.error("Failed to rename remote file", error);
                        viewModel.setStatusText(I18n.get("sftp.error.renameMessage", safeMessage(error)));
                        showErrorAlert(I18n.get("sftp.error.renameFailed"), I18n.get("sftp.error.renameFailedHeader"), error);
                    }
                );
            }
        });
    }

    private void changeRemotePermissions(SftpFileItem item) {
        if (item == null || item.isParentEntry() || !viewModel.isConnected()) {
            return;
        }
        executeTask(
            null,
            () -> transferService.getRemotePermissions(item.getPath()),
            currentPerms -> showPermissionDialog(item, currentPerms),
            error -> {
                logger.error("Failed to get current permissions", error);
                showErrorAlert(I18n.get("sftp.error.title"), I18n.get("sftp.error.getPermissionsHeader"), error);
            }
        );
    }

    private void copyLocalSelected() {
        List<SftpFileItem> selected = snapshotSelection(localTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            return;
        }

        TextInputDialog dialog = new TextInputDialog(viewModel.getCurrentLocalPath().toString());
        dialog.setTitle(I18n.get("sftp.copyTo"));
        dialog.setHeaderText(I18n.get("sftp.targetDir"));
        dialog.setContentText(I18n.get("sftp.path"));
        
        dialog.showAndWait().ifPresent(destPath -> {
            if (destPath != null && !destPath.trim().isEmpty()) {
                executeTask(
                    I18n.get("sftp.copying", selected.get(0).getName()),
                    () -> {
                        transferService.copyLocal(toLocalPaths(selected), Paths.get(destPath.trim()));
                        return null;
                    },
                    ignored -> {
                        viewModel.setStatusText(I18n.get("sftp.copyComplete"));
                        refreshLocal();
                    },
                    error -> {
                        logger.error("Failed to copy local files", error);
                        viewModel.setStatusText(I18n.get("sftp.error.copyMessage", safeMessage(error)));
                        showErrorAlert(I18n.get("sftp.error.copy"), I18n.get("sftp.error.copyFailedHeader"), error);
                    }
                );
            }
        });
    }

    private void copyRemoteSelected() {
        List<SftpFileItem> selected = snapshotSelection(remoteTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty() || !viewModel.isConnected()) {
            return;
        }

        TextInputDialog dialog = new TextInputDialog(viewModel.getCurrentRemotePath());
        dialog.setTitle(I18n.get("sftp.copyTo"));
        dialog.setHeaderText(I18n.get("sftp.targetDir"));
        dialog.setContentText(I18n.get("sftp.path"));
        
        dialog.showAndWait().ifPresent(destPath -> {
            if (destPath != null && !destPath.trim().isEmpty()) {
                executeTask(
                    I18n.get("sftp.copying", selected.get(0).getName()),
                    () -> {
                        transferService.copyRemote(toRemotePaths(selected), destPath.trim());
                        return null;
                    },
                    ignored -> {
                        viewModel.setStatusText(I18n.get("sftp.copyComplete"));
                        refreshRemote();
                    },
                    error -> {
                        logger.error("Failed to copy remote files", error);
                        viewModel.setStatusText(I18n.get("sftp.error.copyMessage", safeMessage(error)));
                        showErrorAlert(I18n.get("sftp.error.copy"), I18n.get("sftp.error.copyFailedHeader"), error);
                    }
                );
            }
        });
    }
    
    private void createZipArchive() {
        List<SftpFileItem> selected = snapshotSelection(remoteTable.getSelectionModel().getSelectedItems());
        if (selected.isEmpty()) {
            // Try local selection
            selected = snapshotSelection(localTable.getSelectionModel().getSelectedItems());
            if (selected.isEmpty()) {
                Alert alert = new Alert(Alert.AlertType.INFORMATION);
                alert.setTitle(I18n.get("sftp.noSelection"));
                alert.setHeaderText(I18n.get("sftp.selectFilesPrompt"));
                alert.showAndWait();
                return;
            }
            createZipFromLocal(selected);
        } else {
            createZipFromRemote(selected);
        }
    }
    
    private void createZipFromLocal(List<SftpFileItem> selected) {
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle(I18n.get("sftp.saveZipAs"));
        fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("ZIP-Dateien", "*.zip"));
        fileChooser.setInitialFileName("archive.zip");
        fileChooser.setInitialDirectory(viewModel.getCurrentLocalPath().toFile());
        
        File zipFile = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (zipFile == null) {
            return;
        }

        executeTask(
            I18n.get("sftp.adding", selected.get(0).getName()),
            () -> {
                transferService.createLocalZip(toLocalPaths(selected), zipFile.toPath());
                return null;
            },
            ignored -> viewModel.setStatusText(I18n.get("sftp.zipCreated", zipFile.getName())),
            error -> {
                logger.error("Failed to create ZIP archive", error);
                viewModel.setStatusText(I18n.get("sftp.error.createZip", safeMessage(error)));
                showErrorAlert(I18n.get("sftp.error.createZipFailed"), I18n.get("sftp.error.createZipFailedHeader"), error);
            }
        );
    }
    
    private void createZipFromRemote(List<SftpFileItem> selected) {
        if (!viewModel.isConnected()) {
            return;
        }
        
        javafx.stage.FileChooser fileChooser = new javafx.stage.FileChooser();
        fileChooser.setTitle(I18n.get("sftp.saveZipAs"));
        fileChooser.getExtensionFilters().add(new javafx.stage.FileChooser.ExtensionFilter("ZIP-Dateien", "*.zip"));
        fileChooser.setInitialFileName("archive.zip");
        fileChooser.setInitialDirectory(viewModel.getCurrentLocalPath().toFile());
        
        File zipFile = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (zipFile == null) {
            return;
        }

        executeTask(
            I18n.get("sftp.downloading", selected.get(0).getName()),
            () -> {
                transferService.createRemoteZip(toRemotePaths(selected), zipFile.toPath());
                return null;
            },
            ignored -> viewModel.setStatusText(I18n.get("sftp.zipCreated", zipFile.getName())),
            error -> {
                logger.error("Failed to create ZIP archive from remote", error);
                viewModel.setStatusText(I18n.get("sftp.error.createZip", safeMessage(error)));
                showErrorAlert(I18n.get("sftp.error.createZipFailed"), I18n.get("sftp.error.createZipFailedHeader"), error);
            }
        );
    }

    private void showPermissionDialog(SftpFileItem item, String currentPermissions) {
        String normalizedPermissions = currentPermissions != null ? currentPermissions.trim() : "";
        int fallbackPermissions = item.isFile() ? 0644 : 0755;
        int currentPermissionsInt = fallbackPermissions;
        if (normalizedPermissions.matches("^[0-7]{1,4}$")) {
            currentPermissionsInt = Integer.parseInt(normalizedPermissions, 8);
        } else {
            logger.warn(
                "Received invalid octal permissions '{}' for {}, using fallback {}",
                currentPermissions,
                item.getPath(),
                String.format("%04o", fallbackPermissions)
            );
        }
        final int initialPermissionsInt = currentPermissionsInt;
        String displayedPermissions = String.format("%04o", currentPermissionsInt);

        Dialog<int[]> permissionDialog = new Dialog<>();
        permissionDialog.setTitle(I18n.get("sftp.permissions"));
        permissionDialog.setHeaderText(I18n.get("sftp.permissionsFor", item.getName()));
        permissionDialog.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);

        VBox content = new VBox(15);
        content.setPadding(new Insets(20));

        Label infoLabel = new Label(I18n.get("sftp.currentPermissions", displayedPermissions));
        infoLabel.setStyle("-fx-font-weight: bold;");

        CheckBox ownerRead = new CheckBox(I18n.get("sftp.read"));
        CheckBox ownerWrite = new CheckBox(I18n.get("sftp.write"));
        CheckBox ownerExecute = new CheckBox(I18n.get("sftp.execute"));
        ownerRead.setSelected((currentPermissionsInt & 0400) != 0);
        ownerWrite.setSelected((currentPermissionsInt & 0200) != 0);
        ownerExecute.setSelected((currentPermissionsInt & 0100) != 0);

        CheckBox groupRead = new CheckBox(I18n.get("sftp.read"));
        CheckBox groupWrite = new CheckBox(I18n.get("sftp.write"));
        CheckBox groupExecute = new CheckBox(I18n.get("sftp.execute"));
        groupRead.setSelected((currentPermissionsInt & 0040) != 0);
        groupWrite.setSelected((currentPermissionsInt & 0020) != 0);
        groupExecute.setSelected((currentPermissionsInt & 0010) != 0);

        CheckBox otherRead = new CheckBox(I18n.get("sftp.read"));
        CheckBox otherWrite = new CheckBox(I18n.get("sftp.write"));
        CheckBox otherExecute = new CheckBox(I18n.get("sftp.execute"));
        otherRead.setSelected((currentPermissionsInt & 0004) != 0);
        otherWrite.setSelected((currentPermissionsInt & 0002) != 0);
        otherExecute.setSelected((currentPermissionsInt & 0001) != 0);

        content.getChildren().addAll(
            infoLabel,
            createPermissionSection(I18n.get("sftp.ownerLabel"), ownerRead, ownerWrite, ownerExecute),
            createPermissionSection(I18n.get("sftp.groupLabel"), groupRead, groupWrite, groupExecute),
            createPermissionSection(I18n.get("sftp.otherLabel"), otherRead, otherWrite, otherExecute)
        );

        permissionDialog.getDialogPane().setContent(content);
        permissionDialog.setResultConverter(buttonType -> {
            if (buttonType != ButtonType.OK) {
                return null;
            }
            int permissions = 0;
            if (ownerRead.isSelected()) permissions |= 0400;
            if (ownerWrite.isSelected()) permissions |= 0200;
            if (ownerExecute.isSelected()) permissions |= 0100;
            if (groupRead.isSelected()) permissions |= 0040;
            if (groupWrite.isSelected()) permissions |= 0020;
            if (groupExecute.isSelected()) permissions |= 0010;
            if (otherRead.isSelected()) permissions |= 0004;
            if (otherWrite.isSelected()) permissions |= 0002;
            if (otherExecute.isSelected()) permissions |= 0001;
            return new int[]{permissions};
        });

        permissionDialog.showAndWait().ifPresent(result -> {
            if (result != null && result[0] != initialPermissionsInt) {
                String newPermissions = String.format("%04o", result[0]);
                executeTask(
                    I18n.get("sftp.changingPermissions", item.getName()),
                    () -> {
                        transferService.setRemotePermissions(item.getPath(), newPermissions);
                        return null;
                    },
                    ignored -> {
                        viewModel.setStatusText(I18n.get("sftp.permissionsChanged", item.getName()));
                        refreshRemote();
                    },
                    error -> {
                        logger.error("Failed to change permissions", error);
                        viewModel.setStatusText(I18n.get("sftp.error.changePermissions", safeMessage(error)));
                        showErrorAlert(I18n.get("sftp.error.changePermissionsFailed"), I18n.get("sftp.error.changePermissionsFailedHeader"), error);
                    }
                );
            }
        });
    }

    private VBox createPermissionSection(String title, CheckBox read, CheckBox write, CheckBox execute) {
        Label titleLabel = new Label(title);
        titleLabel.setStyle("-fx-font-weight: bold;");
        HBox checkBoxRow = new HBox(10, read, write, execute);
        VBox section = new VBox(5, titleLabel, checkBoxRow);
        return section;
    }

    private List<SftpFileItem> snapshotSelection(List<SftpFileItem> selectedItems) {
        if (selectedItems == null || selectedItems.isEmpty()) {
            return List.of();
        }
        return selectedItems.stream()
            .filter(item -> item != null && !item.isParentEntry())
            .toList();
    }

    private List<Path> toLocalPaths(List<SftpFileItem> items) {
        List<Path> paths = new ArrayList<>();
        for (SftpFileItem item : items) {
            paths.add(Paths.get(item.getPath()));
        }
        return paths;
    }

    private List<String> toRemotePaths(List<SftpFileItem> items) {
        List<String> paths = new ArrayList<>();
        for (SftpFileItem item : items) {
            paths.add(item.getPath());
        }
        return paths;
    }

    private void showErrorAlert(String title, String header, Throwable error) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(header);
        alert.setContentText(safeMessage(error));
        alert.showAndWait();
    }

    private String safeMessage(Throwable error) {
        if (error == null) {
            return "";
        }
        if (error.getMessage() == null || error.getMessage().isBlank()) {
            return error.getClass().getSimpleName();
        }
        return error.getMessage();
    }

    private <T> void executeTask(String initialStatus, CheckedSupplier<T> action,
                                 java.util.function.Consumer<T> onSuccess,
                                 java.util.function.Consumer<Throwable> onFailure) {
        Task<T> task = new Task<>() {
            @Override
            protected T call() throws Exception {
                return action.get();
            }
        };
        if (initialStatus != null && !initialStatus.isBlank()) {
            viewModel.setStatusText(initialStatus);
        }
        task.setOnRunning(event -> viewModel.setBusy(true));
        task.setOnSucceeded(event -> {
            viewModel.setBusy(false);
            onSuccess.accept(task.getValue());
        });
        task.setOnFailed(event -> {
            viewModel.setBusy(false);
            onFailure.accept(task.getException());
        });
        task.setOnCancelled(event -> viewModel.setBusy(false));
        backgroundExecutor.submit(task);
    }
    
    private void cleanup() {
        backgroundExecutor.shutdownNow();
        transferService.close();
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    private record LocalListing(Path path, List<SftpFileItem> items) { }

    private record RemoteListing(String path, List<SftpFileItem> items) { }

    /**
     * Legacy compatibility wrapper for older SFTP views still referencing SFTPManagerDialog.FileItem.
     */
    @Deprecated
    public static class FileItem extends SftpFileItem {

        public FileItem(String name, String path, boolean file, String size, String date) {
            this(name, path, file, size, date, "", "", "");
        }

        public FileItem(String name, String path, boolean file, String size, String date, String permissions) {
            this(name, path, file, size, date, permissions, "", "");
        }

        public FileItem(String name, String path, boolean file, String size, String date,
                        String permissions, String owner, String group) {
            super(
                name,
                path,
                file,
                size,
                date,
                permissions,
                owner,
                group,
                parseSizeToBytes(size),
                "..".equals(name)
            );
        }

        private static long parseSizeToBytes(String sizeStr) {
            if (sizeStr == null || sizeStr.isEmpty() || sizeStr.equals("-")
                || sizeStr.equals("<DIR>") || sizeStr.equals("...") || sizeStr.equals("—")) {
                return 0L;
            }
            try {
                String cleaned = sizeStr.replaceAll("[^0-9.]", "");
                if (cleaned.isEmpty()) {
                    return 0L;
                }
                if (sizeStr.contains("KB")) {
                    return (long) (Double.parseDouble(cleaned) * 1024);
                }
                if (sizeStr.contains("MB")) {
                    return (long) (Double.parseDouble(cleaned) * 1024 * 1024);
                }
                if (sizeStr.contains("GB")) {
                    return (long) (Double.parseDouble(cleaned) * 1024 * 1024 * 1024);
                }
                return Long.parseLong(cleaned);
            } catch (NumberFormatException e) {
                return 0L;
            }
        }
    }
}
