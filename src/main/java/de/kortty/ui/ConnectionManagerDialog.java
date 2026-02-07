package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.model.GroupPath;
import de.kortty.model.ServerConnection;
import de.kortty.model.StoredCredential;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Dialog for managing saved connections with tree view.
 */
public class ConnectionManagerDialog extends Dialog<ServerConnection> {
    
    private static final Logger logger = LoggerFactory.getLogger(ConnectionManagerDialog.class);
    
    private final KorTTYApplication app;
    private final ConfigurationManager configManager;
    private final CredentialManager credentialManager;
    private final char[] masterPassword;
    private final ObservableList<ServerConnection> connections;
    private final TextField searchField;
    private final Stage owner;
    private ConnectionManagerTreeView treeView;
    private Button undoButton;
    private Button renameGroupButton;
    private final TableView<ServerConnection> table; // Keep for compatibility, but hide it
    
    public ConnectionManagerDialog(Stage owner, KorTTYApplication app) {
        this.app = app;
        this.configManager = app.getConfigManager();
        this.credentialManager = app.getCredentialManager();
        this.masterPassword = app.getMasterPasswordManager().getMasterPassword();
        this.owner = owner;
        
        setTitle(I18n.get("connectionManager.title"));
        setHeaderText(null);
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);
        
        // Initialize connections list
        connections = FXCollections.observableArrayList(configManager.getConnections());
        
        // Create hidden table for compatibility
        table = new TableView<>();
        table.setVisible(false);
        table.setManaged(false);
        
        // Create TreeView
        treeView = new ConnectionManagerTreeView(connections);
        treeView.setPrefSize(600, 400);
        
        // Create search field
        searchField = new TextField();
        searchField.setPromptText(I18n.get("connManager.searchPrompt"));
        searchField.setPrefWidth(300);
        
        // Add listener to filter tree based on search text
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.trim().isEmpty()) {
                treeView.filterTree(null);
            } else {
                String searchText = newVal.trim();
                boolean useGlobPattern = searchText.contains("*");
                
                // Convert glob pattern to regex if "*" is present
                Pattern pattern = null;
                if (useGlobPattern) {
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
                        .replace("*", ".*");
                    
                    try {
                        pattern = Pattern.compile(regexPattern, Pattern.CASE_INSENSITIVE);
                    } catch (java.util.regex.PatternSyntaxException e) {
                        pattern = null;
                    }
                }
                
                final Pattern finalPattern = pattern;
                final boolean usePattern = useGlobPattern && pattern != null;
                final String lowerSearchText = searchText.toLowerCase();
                
                treeView.filterTree(connection -> {
                    if (connection.getName() != null) {
                        String name = connection.getName();
                        if (usePattern) {
                            if (finalPattern.matcher(name).matches()) {
                                return true;
                            }
                        } else {
                            if (name.toLowerCase().contains(lowerSearchText)) {
                                return true;
                            }
                        }
                    }
                    if (connection.getHost() != null) {
                        String host = connection.getHost();
                        if (usePattern) {
                            if (finalPattern.matcher(host).matches()) {
                                return true;
                            }
                        } else {
                            if (host.toLowerCase().contains(lowerSearchText)) {
                                return true;
                            }
                        }
                    }
                    return false;
                });
            }
        });
        
        // Register TreeView callbacks
        treeView.setOnCreateGroup(this::createNewGroup);
        treeView.setOnRenameGroup(this::renameGroup);
        treeView.setOnDeleteGroup(this::deleteGroup);
        treeView.setOnDoubleClick(() -> {
            List<ServerConnection> selected = treeView.getSelectedConnections();
            if (!selected.isEmpty()) {
                setResult(selected.get(0));
                close();
            }
        });
        treeView.setOnEditConnection(this::editConnection);
        treeView.setOnExportConnections(this::exportConnections);
        treeView.setOnDeleteConnections(this::deleteConnections);
        treeView.setOnExportGroup(this::exportGroup);
        
        // Buttons - set uniform width for all buttons
        Button addButton = new Button(I18n.get("connectionManager.new"));
        Button editButton = new Button(I18n.get("connectionManager.edit"));
        Button deleteButton = new Button(I18n.get("connectionManager.delete"));
        Button duplicateButton = new Button(I18n.get("connectionManager.duplicate"));
        Button exportButton = new Button(I18n.get("connectionManager.export"));
        Button importButton = new Button(I18n.get("connectionManager.import"));
        undoButton = new Button(I18n.get("connectionManager.undo"));
        renameGroupButton = new Button(I18n.get("connectionManager.renameFolder"));
        
        // Set uniform width for all buttons
        double buttonWidth = 140;
        addButton.setPrefWidth(buttonWidth);
        editButton.setPrefWidth(buttonWidth);
        deleteButton.setPrefWidth(buttonWidth);
        duplicateButton.setPrefWidth(buttonWidth);
        exportButton.setPrefWidth(buttonWidth);
        importButton.setPrefWidth(buttonWidth);
        undoButton.setPrefWidth(buttonWidth);
        renameGroupButton.setPrefWidth(buttonWidth);
        
        editButton.setDisable(true);
        deleteButton.setDisable(true);
        duplicateButton.setDisable(true);
        exportButton.setDisable(true);
        undoButton.setDisable(true);
        renameGroupButton.setDisable(true);
        
        // Set undo button for treeView
        treeView.setUndoButton(undoButton);
        
        // Selection listener for buttons
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            List<ServerConnection> selectedConnections = treeView.getSelectedConnections();
            List<GroupPath> selectedGroups = treeView.getSelectedGroups();
            
            boolean hasSingleConnection = selectedConnections.size() == 1;
            boolean hasConnections = !selectedConnections.isEmpty();
            boolean hasSingleGroup = selectedGroups.size() == 1 && selectedConnections.isEmpty();
            boolean hasMultipleSelection = selectedConnections.size() > 1;
            
            editButton.setDisable(!hasSingleConnection);
            deleteButton.setDisable(!hasConnections);
            duplicateButton.setDisable(!hasSingleConnection);
            exportButton.setDisable(!hasConnections);
            renameGroupButton.setDisable(!hasSingleGroup);
        });
        
        addButton.setOnAction(e -> addConnection());
        editButton.setOnAction(e -> editConnection(null));
        deleteButton.setOnAction(e -> {
            List<ServerConnection> selected = treeView.getSelectedConnections();
            if (!selected.isEmpty()) {
                deleteConnections(selected);
            }
        });
        duplicateButton.setOnAction(e -> duplicateConnection());
        exportButton.setOnAction(e -> exportConnections());
        importButton.setOnAction(e -> importConnections());
        undoButton.setOnAction(e -> treeView.undoLastMove());
        renameGroupButton.setOnAction(e -> {
            List<GroupPath> selected = treeView.getSelectedGroups();
            if (!selected.isEmpty()) {
                renameGroup(selected.get(0));
            }
        });
        
        VBox buttonBox = new VBox(10, addButton, editButton, deleteButton, duplicateButton, 
                              new Separator(), exportButton, importButton,
                              new Separator(), undoButton, renameGroupButton);
        buttonBox.setAlignment(Pos.TOP_CENTER);
        buttonBox.setPadding(new Insets(0, 0, 0, 10));
        
        // Search field container
        HBox searchBox = new HBox(10);
        searchBox.setPadding(new Insets(0, 0, 10, 0));
        Label searchLabel = new Label(I18n.get("ssh.search"));
        searchBox.getChildren().addAll(searchLabel, searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        
        // TreeView container with search
        VBox treeContainer = new VBox(10);
        treeContainer.getChildren().addAll(searchBox, treeView);
        VBox.setVgrow(treeView, Priority.ALWAYS);
        
        BorderPane content = new BorderPane();
        content.setCenter(treeContainer);
        content.setRight(buttonBox);
        content.setPadding(new Insets(10));
        
        getDialogPane().setContent(content);
        
        // Dialog buttons
        ButtonType connectButtonType = new ButtonType(I18n.get("quickConnect.connect"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(connectButtonType, ButtonType.CLOSE);
        
        Button connectButton = (Button) getDialogPane().lookupButton(connectButtonType);
        connectButton.setDisable(true);
        
        treeView.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            List<ServerConnection> selectedConnections = treeView.getSelectedConnections();
            connectButton.setDisable(selectedConnections.isEmpty());
        });
        
        setResultConverter(dialogButton -> {
            if (dialogButton == connectButtonType) {
                List<ServerConnection> selected = treeView.getSelectedConnections();
                if (!selected.isEmpty()) {
                    return selected.get(0);
                }
            }
            return null;
        });
    }
    
    /**
     * Gets the currently selected single non-placeholder connection.
     */
    private ServerConnection getSelectedConnection() {
        List<ServerConnection> selected = treeView.getSelectedConnections();
        if (selected.size() == 1) {
            return selected.get(0);
        }
        return null;
    }
    
    private void addConnection() {
        // Determine target group from selection
        String targetGroup = "";
        List<GroupPath> selectedGroups = treeView.getSelectedGroups();
        if (!selectedGroups.isEmpty()) {
            targetGroup = selectedGroups.get(0).getPath();
        }
        
        final String finalTargetGroup = targetGroup;
        
        ConnectionEditDialog dialog = new ConnectionEditDialog(owner, null, credentialManager, 
            app.getSSHKeyManager(), masterPassword);
        dialog.showAndWait().ifPresent(connection -> {
            if (finalTargetGroup != null && !finalTargetGroup.isEmpty()) {
                connection.setGroup(finalTargetGroup);
            }
            connections.add(connection);
            configManager.addConnection(connection);
            treeView.refreshTree();
            saveConnections();
        });
    }
    
    private void editConnection(ServerConnection connection) {
        ServerConnection selected = connection != null ? connection : getSelectedConnection();
        if (selected != null && !selected.isPlaceholder()) {
            ConnectionEditDialog dialog = new ConnectionEditDialog(owner, selected, credentialManager, 
                app.getSSHKeyManager(), masterPassword);
            dialog.showAndWait().ifPresent(editedConnection -> {
                int index = connections.indexOf(selected);
                connections.set(index, editedConnection);
                configManager.updateConnection(editedConnection);
                treeView.refreshTree();
                saveConnections();
            });
        }
    }
    
    private void deleteConnection() {
        ServerConnection selected = getSelectedConnection();
        if (selected != null) {
            deleteConnections(List.of(selected));
        }
    }
    
    private void deleteConnections(List<ServerConnection> connectionsToDelete) {
        if (connectionsToDelete.isEmpty()) {
            return;
        }
        
        // Filter out placeholders
        final List<ServerConnection> filteredConnections = connectionsToDelete.stream()
            .filter(conn -> !conn.isPlaceholder())
            .collect(Collectors.toList());
        
        if (filteredConnections.isEmpty()) {
            return;
        }
        
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("connection.delete.title"));
        confirm.setHeaderText(I18n.get("connection.delete.header", filteredConnections.size()));
        confirm.setContentText(I18n.get("connection.delete.content"));
        
        confirm.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                for (ServerConnection conn : filteredConnections) {
                    connections.remove(conn);
                    configManager.removeConnection(conn);
                }
                treeView.refreshTree();
                saveConnections();
            }
        });
    }
    
    private void duplicateConnection() {
        ServerConnection selected = getSelectedConnection();
        if (selected != null && !selected.isPlaceholder()) {
            ServerConnection copy = new ServerConnection();
            copy.setName(selected.getName() + I18n.get("connManager.copy"));
            copy.setHost(selected.getHost());
            copy.setPort(selected.getPort());
            copy.setUsername(selected.getUsername());
            copy.setAuthMethod(selected.getAuthMethod());
            copy.setPrivateKeyPath(selected.getPrivateKeyPath());
            copy.setGroup(selected.getGroup());
            
            if (selected.getSettings() != null) {
                copy.setSettings(new de.kortty.model.ConnectionSettings(selected.getSettings()));
            }
            
            connections.add(copy);
            configManager.addConnection(copy);
            treeView.refreshTree();
            saveConnections();
        }
    }
    
    private void createNewGroup(GroupPath parentPath) {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle(I18n.get("connManager.newFolder"));
        dialog.setHeaderText(I18n.get("connManager.newFolderHeader") + 
            (parentPath.isRoot() ? "" : " in \"" + parentPath.getName() + "\""));
        dialog.setContentText(I18n.get("connManager.folderName"));
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        
        dialog.showAndWait().ifPresent(name -> {
            if (name != null && !name.trim().isEmpty()) {
                GroupPath newGroupPath = parentPath.isRoot() ? new GroupPath(name) : parentPath.append(name);
                
                // Create placeholder connection for empty group
                ServerConnection placeholder = new ServerConnection();
                placeholder.setName("(Ordner: " + newGroupPath.getName() + ")");
                placeholder.setHost("placeholder");
                placeholder.setPort(22);
                placeholder.setUsername("");
                placeholder.setGroup(newGroupPath.getPath());
                
                connections.add(placeholder);
                configManager.addConnection(placeholder);
                treeView.refreshTree();
                saveConnections();
            }
        });
    }
    
    private void renameGroup(GroupPath oldPath) {
        TextInputDialog dialog = new TextInputDialog(oldPath.getName());
        dialog.setTitle(I18n.get("connManager.renameFolder"));
        dialog.setHeaderText(I18n.get("connManager.renameFolderHeader", oldPath.getName()));
        dialog.setContentText(I18n.get("connManager.newName"));
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        
        dialog.showAndWait().ifPresent(newName -> {
            if (newName != null && !newName.trim().isEmpty() && !newName.equals(oldPath.getName())) {
                GroupPath parentPath = oldPath.getParent();
                GroupPath newPath = parentPath == null || parentPath.isRoot() ? 
                    new GroupPath(newName) : parentPath.append(newName);
                
                // Update all connections in this group and sub-groups
                for (ServerConnection conn : connections) {
                    if (conn.getGroup() != null) {
                        GroupPath connPath = new GroupPath(conn.getGroup());
                        if (connPath.equals(oldPath) || connPath.isChildOf(oldPath)) {
                            // Replace old path segment with new one
                            String oldPathStr = oldPath.getPath();
                            String newPathStr = newPath.getPath();
                            String connGroup = conn.getGroup();
                            
                            if (connGroup.equals(oldPathStr)) {
                                conn.setGroup(newPathStr);
                            } else if (connGroup.startsWith(oldPathStr + GroupPath.SEPARATOR)) {
                                conn.setGroup(newPathStr + connGroup.substring(oldPathStr.length()));
                            }
                            
                            configManager.updateConnection(conn);
                        }
                    }
                }
                
                treeView.refreshTree();
                saveConnections();
            }
        });
    }
    
    private void deleteGroup(GroupPath groupPath) {
        // Count connections in this group (and sub-groups)
        long count = connections.stream()
            .filter(conn -> {
                if (conn.getGroup() == null) return false;
                GroupPath connPath = new GroupPath(conn.getGroup());
                return connPath.equals(groupPath) || connPath.isChildOf(groupPath);
            })
            .filter(conn -> !conn.isPlaceholder())
            .count();
        
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("connection.deleteFolder.title"));
        confirm.setHeaderText(I18n.get("connection.deleteFolder.header", groupPath.getName()));
        confirm.setContentText(I18n.get("connection.deleteFolder.content", count));
        
        confirm.showAndWait().ifPresent(result -> {
            if (result == ButtonType.OK) {
                List<ServerConnection> toRemove = connections.stream()
                    .filter(conn -> {
                        if (conn.getGroup() == null) return false;
                        GroupPath connPath = new GroupPath(conn.getGroup());
                        return connPath.equals(groupPath) || connPath.isChildOf(groupPath);
                    })
                    .collect(Collectors.toList());
                
                for (ServerConnection conn : toRemove) {
                    connections.remove(conn);
                    configManager.removeConnection(conn);
                }
                
                treeView.refreshTree();
                saveConnections();
            }
        });
    }
    
    private void saveConnections() {
        try {
            configManager.save(app.getMasterPasswordManager().getDerivedKey());
            logger.info("Connections saved successfully");
        } catch (Exception e) {
            logger.error("Failed to save connections", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(I18n.get("error.title"));
            alert.setHeaderText(I18n.get("error.saveFailed"));
            alert.setContentText(I18n.get("connManager.saveFailed") + e.getMessage());
            alert.showAndWait();
        }
    }
    
    private void exportConnections() {
        List<ServerConnection> selectedConnections = treeView.getSelectedConnections();
        if (!selectedConnections.isEmpty()) {
            exportConnections(selectedConnections);
        }
    }
    
    private void exportConnections(List<ServerConnection> connectionsToExport) {
        if (connectionsToExport.isEmpty()) {
            return;
        }
        
        // Filter out placeholders
        final List<ServerConnection> finalConnectionsToExport = connectionsToExport.stream()
            .filter(conn -> !conn.isPlaceholder())
            .collect(Collectors.toList());
        
        if (finalConnectionsToExport.isEmpty()) {
            return;
        }
        
        ConnectionExportDialog dialog = new ConnectionExportDialog(owner, finalConnectionsToExport);
        dialog.showAndWait().ifPresent(result -> {
            try {
                exportConnectionsToFile(result);
                
                Alert success = new Alert(Alert.AlertType.INFORMATION);
                success.setTitle(I18n.get("connManager.exportSuccess"));
                success.setHeaderText(I18n.get("connManager.exportedCount", finalConnectionsToExport.size()));
                success.setContentText(I18n.get("connManager.file") + ": " + result.exportFile.getAbsolutePath());
                success.showAndWait();
            } catch (Exception e) {
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle(I18n.get("error.exportFailed"));
                error.setHeaderText(I18n.get("connManager.exportError"));
                error.setContentText(e.getMessage());
                error.showAndWait();
            }
        });
    }
    
    private void exportGroup(GroupPath groupPath) {
        // Collect all non-placeholder connections in this group and sub-groups
        List<ServerConnection> connectionsInGroup = connections.stream()
            .filter(conn -> {
                if (conn.getGroup() == null || conn.isPlaceholder()) return false;
                GroupPath connPath = new GroupPath(conn.getGroup());
                return connPath.equals(groupPath) || connPath.isChildOf(groupPath);
            })
            .collect(Collectors.toList());
        
        if (!connectionsInGroup.isEmpty()) {
            exportConnections(connectionsInGroup);
        } else {
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle(I18n.get("connManager.noConnections"));
            alert.setHeaderText(I18n.get("connManager.folderEmpty"));
            alert.setContentText(I18n.get("connection.emptyFolder", groupPath.getName()));
            alert.showAndWait();
        }
    }
    
    private void exportConnectionsToFile(ConnectionExportDialog.ExportResult result) throws Exception {
        // Create copies of connections and filter based on options
        List<ServerConnection> exportList = new ArrayList<>();
        
        for (ServerConnection conn : result.connections) {
            ServerConnection copy = new ServerConnection();
            copy.setName(conn.getName());
            copy.setHost(conn.getHost());
            copy.setPort(conn.getPort());
            copy.setGroup(conn.getGroup());
            copy.setAuthMethod(conn.getAuthMethod());
            copy.setPrivateKeyPath(conn.getPrivateKeyPath());
            copy.setSshKeyId(conn.getSshKeyId());
            
            // Username (optional)
            if (result.includeUsername) {
                copy.setUsername(conn.getUsername());
            } else {
                copy.setUsername("");
            }
            
            // Password/CredentialId (optional)
            if (result.includePassword) {
                copy.setEncryptedPassword(conn.getEncryptedPassword());
                copy.setCredentialId(conn.getCredentialId());
            } else {
                copy.setEncryptedPassword(null);
                copy.setCredentialId(null);
            }
            
            // SSH Tunnels (optional)
            if (result.includeTunnels && conn.getSshTunnels() != null) {
                copy.setSshTunnels(new ArrayList<>(conn.getSshTunnels()));
            }
            
            // Jump Server (optional)
            if (result.includeJumpServer && conn.getJumpServer() != null) {
                copy.setJumpServer(conn.getJumpServer());
            }
            
            // Copy settings
            if (conn.getSettings() != null) {
                copy.setSettings(new de.kortty.model.ConnectionSettings(conn.getSettings()));
            }
            
            exportList.add(copy);
        }
        
        // Use the selected exporter
        if (result.exporter != null) {
            result.exporter.exportConnections(exportList, result.exportFile.toPath());
        } else {
            // Fallback to KorTTY XML format
            exportToKorTTYFormat(exportList, result.exportFile);
        }
    }
    
    /**
     * Exports connections to KorTTY XML format (fallback/default).
     */
    private void exportToKorTTYFormat(List<ServerConnection> exportList, java.io.File exportFile) throws Exception {
        jakarta.xml.bind.JAXBContext context = jakarta.xml.bind.JAXBContext.newInstance(
            de.kortty.persistence.XMLConnectionRepository.ConnectionsWrapper.class,
            ServerConnection.class,
            de.kortty.model.SSHTunnel.class,
            de.kortty.model.JumpServer.class,
            de.kortty.model.AuthMethod.class,
            de.kortty.model.TunnelType.class,
            de.kortty.model.TerminalLogConfig.class,
            de.kortty.model.TerminalLogConfig.LogFormat.class
        );
        
        de.kortty.persistence.XMLConnectionRepository.ConnectionsWrapper wrapper = 
            new de.kortty.persistence.XMLConnectionRepository.ConnectionsWrapper();
        wrapper.setConnections(exportList);
        
        jakarta.xml.bind.Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(jakarta.xml.bind.Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.marshal(wrapper, exportFile);
    }
    
    private void importConnections() {
        ConnectionImportDialog dialog = new ConnectionImportDialog(owner, credentialManager, 
            app.getSSHKeyManager(), configManager);
        dialog.showAndWait().ifPresent(result -> {
            try {
                List<ServerConnection> importedConnections = importConnectionsFromFile(result);
                
                // Add to connections list and config
                int successCount = 0;
                for (ServerConnection conn : importedConnections) {
                    connections.add(conn);
                    configManager.addConnection(conn);
                    successCount++;
                }
                
                // Refresh tree and save
                treeView.refreshTree();
                saveConnections();
                
                Alert success = new Alert(Alert.AlertType.INFORMATION);
                success.setTitle(I18n.get("info.importSuccessful", successCount, ""));
                success.setHeaderText(I18n.get("connManager.importedCount", successCount));
                success.setContentText(I18n.get("info.importSuccessful", successCount, ""));
                success.showAndWait();
            } catch (Exception e) {
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle(I18n.get("error.importFailed"));
                error.setHeaderText(I18n.get("connManager.importError"));
                error.setContentText(e.getMessage());
                error.showAndWait();
            }
        });
    }
    
    private List<ServerConnection> importConnectionsFromFile(ConnectionImportDialog.ImportResult result) throws Exception {
        // Determine file type and extract/decrypt if needed
        java.io.File actualXmlFile = result.importFile;
        java.nio.file.Path tempFile = null;
        boolean needsCleanup = false;
        
        try {
            String fileName = result.importFile.getName().toLowerCase();
            
            // Handle GPG-encrypted files
            if (fileName.endsWith(".gpg")) {
                // Decrypt GPG file first
                tempFile = java.nio.file.Files.createTempFile("kortty-import-", ".zip");
                needsCleanup = true;
                
                // Decrypt GPG file
                ProcessBuilder pb = new ProcessBuilder(
                    "gpg",
                    "--batch",
                    "--yes",
                    "--no-tty",
                    "--quiet",
                    "--decrypt",
                    "--output", tempFile.toString(),
                    result.importFile.getAbsolutePath()
                );
                
                pb.redirectErrorStream(true);
                String osName = System.getProperty("os.name").toLowerCase();
                java.io.File nullFile = new java.io.File(osName.contains("win") ? "NUL" : "/dev/null");
                pb.redirectInput(ProcessBuilder.Redirect.from(nullFile));
                
                Process process = pb.start();
                
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
                    throw new Exception(I18n.get("error.gpgEncryptionFailed", exitCode, output.toString(), "key"));
                }
                
                // After GPG decryption, we should have a ZIP file
                actualXmlFile = tempFile.toFile();
            }
            
            // Handle ZIP files (password-protected or not)
            if (fileName.endsWith(".zip") || (fileName.endsWith(".gpg") && tempFile != null)) {
                java.nio.file.Path zipTempFile = tempFile != null ? tempFile : result.importFile.toPath();
                
                // Try to extract ZIP
                net.lingala.zip4j.ZipFile zipFile = new net.lingala.zip4j.ZipFile(zipTempFile.toFile());
                
                // Check if ZIP is password protected
                if (zipFile.isEncrypted()) {
                    // Ask for password using custom dialog with password field and stored credentials
                    Dialog<String> passwordDialog = new Dialog<>();
                    passwordDialog.setTitle(I18n.get("dialog.passwordRequired"));
                    passwordDialog.setHeaderText(I18n.get("connImport.zipPasswordProtected"));
                    passwordDialog.initModality(Modality.WINDOW_MODAL);
                    passwordDialog.initOwner(owner);
                    
                    javafx.scene.layout.GridPane grid = new javafx.scene.layout.GridPane();
                    grid.setHgap(10);
                    grid.setVgap(10);
                    grid.setPadding(new javafx.geometry.Insets(20));
                    
                    Label passwordLabel = new Label(I18n.get("common.password") + ":");
                    PasswordField passwordField = new PasswordField();
                    passwordField.setPromptText(I18n.get("dialog.enterPassword"));
                    passwordField.setPrefWidth(300);
                    
                    Label storedLabel = new Label(I18n.get("connManager.orSelect"));
                    ComboBox<StoredCredential> storedCredentialCombo = new ComboBox<>();
                    storedCredentialCombo.setPromptText(I18n.get("connManager.selectStoredPassword"));
                    storedCredentialCombo.setPrefWidth(300);
                    
                    storedCredentialCombo.setCellFactory(lv -> new ListCell<StoredCredential>() {
                        @Override
                        protected void updateItem(StoredCredential item, boolean empty) {
                            super.updateItem(item, empty);
                            setText(empty || item == null ? null : item.getName() + " (" + item.getUsername() + ")");
                        }
                    });
                    storedCredentialCombo.setButtonCell(new ListCell<StoredCredential>() {
                        @Override
                        protected void updateItem(StoredCredential item, boolean empty) {
                            super.updateItem(item, empty);
                            setText(empty || item == null ? null : item.getName() + " (" + item.getUsername() + ")");
                        }
                    });
                    
                    // Load stored credentials
                    if (credentialManager != null) {
                        storedCredentialCombo.getItems().addAll(credentialManager.getAllCredentials());
                    }
                    
                    // When a stored credential is selected, decrypt and fill password field
                    storedCredentialCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
                        if (newVal != null && credentialManager != null && masterPassword != null) {
                            try {
                                String decryptedPassword = credentialManager.getPassword(newVal, masterPassword);
                                if (decryptedPassword != null && !decryptedPassword.isEmpty()) {
                                    passwordField.setText(decryptedPassword);
                                }
                            } catch (Exception e) {
                                logger.warn("Failed to decrypt password for credential: {}", newVal.getName(), e);
                                Alert alert = new Alert(Alert.AlertType.WARNING);
                                alert.setTitle(I18n.get("error.title"));
                                alert.setHeaderText(I18n.get("credential.passwordDecryptFailed"));
                                alert.setContentText(I18n.get("credential.passwordDecryptFailedMessage", e.getMessage()));
                                alert.showAndWait();
                                passwordField.clear();
                            }
                        }
                    });
                    
                    grid.add(passwordLabel, 0, 0);
                    grid.add(passwordField, 1, 0);
                    grid.add(storedLabel, 0, 1);
                    grid.add(storedCredentialCombo, 1, 1);
                    
                    javafx.scene.layout.GridPane.setHgrow(passwordField, javafx.scene.layout.Priority.ALWAYS);
                    javafx.scene.layout.GridPane.setHgrow(storedCredentialCombo, javafx.scene.layout.Priority.ALWAYS);
                    
                    passwordDialog.getDialogPane().setContent(grid);
                    
                    ButtonType okButtonType = new ButtonType("OK", ButtonBar.ButtonData.OK_DONE);
                    passwordDialog.getDialogPane().getButtonTypes().addAll(okButtonType, ButtonType.CANCEL);
                    
                    Button okButton = (Button) passwordDialog.getDialogPane().lookupButton(okButtonType);
                    okButton.setDisable(true);
                    
                    // Enable OK button only when password is entered
                    passwordField.textProperty().addListener((obs, oldVal, newVal) -> {
                        okButton.setDisable(newVal == null || newVal.trim().isEmpty());
                    });
                    
                    passwordDialog.setResultConverter(buttonType -> {
                        if (buttonType == okButtonType) {
                            return passwordField.getText();
                        }
                        return null;
                    });
                    
                    // Focus password field when dialog is shown
                    passwordField.requestFocus();
                    
                    Optional<String> passwordOpt = passwordDialog.showAndWait();
                    if (!passwordOpt.isPresent() || passwordOpt.get().trim().isEmpty()) {
                        throw new Exception("ZIP decryption cancelled: No password entered");
                    }
                    
                    zipFile.setPassword(passwordOpt.get().toCharArray());
                }
                
                // Extract to temp directory
                java.nio.file.Path extractDir = java.nio.file.Files.createTempDirectory("kortty-import-extract-");
                needsCleanup = true;
                
                zipFile.extractAll(extractDir.toString());
                
                // Find XML file in extracted directory
                List<java.nio.file.Path> xmlFiles = java.nio.file.Files.walk(extractDir)
                    .filter(p -> p.toString().toLowerCase().endsWith(".xml"))
                    .collect(java.util.stream.Collectors.toList());
                
                if (xmlFiles.isEmpty()) {
                    throw new Exception(I18n.get("connImport.noXmlInZip"));
                }
                
                actualXmlFile = xmlFiles.get(0).toFile();
                
                // Clean up temp file if it was created for GPG
                if (tempFile != null && !tempFile.equals(zipTempFile)) {
                    java.nio.file.Files.deleteIfExists(tempFile);
                }
                tempFile = extractDir; // Mark extractDir for cleanup
            }
            
            // Read XML file
            jakarta.xml.bind.JAXBContext context = jakarta.xml.bind.JAXBContext.newInstance(
                de.kortty.persistence.XMLConnectionRepository.ConnectionsWrapper.class,
                ServerConnection.class,
                de.kortty.model.SSHTunnel.class,
                de.kortty.model.JumpServer.class,
                de.kortty.model.AuthMethod.class,
                de.kortty.model.TunnelType.class,
                de.kortty.model.TerminalLogConfig.class,
                de.kortty.model.TerminalLogConfig.LogFormat.class
            );
            
            jakarta.xml.bind.Unmarshaller unmarshaller = context.createUnmarshaller();
            de.kortty.persistence.XMLConnectionRepository.ConnectionsWrapper wrapper = 
                (de.kortty.persistence.XMLConnectionRepository.ConnectionsWrapper) 
                unmarshaller.unmarshal(actualXmlFile);
        
            List<ServerConnection> importList = new ArrayList<>();
        
            for (ServerConnection conn : wrapper.getConnections()) {
                // Group filtering
                if (result.filterGroups && !result.selectedGroups.isEmpty()) {
                    String connGroup = conn.getGroup();
                    boolean isNoGroup = connGroup == null || connGroup.trim().isEmpty();
                    
                    // Check if connection matches selected groups
                    boolean matchesFilter = false;
                    for (String selectedGroup : result.selectedGroups) {
                        if (selectedGroup.equals("(keine Gruppe)") && isNoGroup) {
                            matchesFilter = true;
                            break;
                        } else if (!isNoGroup && selectedGroup.equals(connGroup)) {
                            matchesFilter = true;
                            break;
                        }
                    }
                    
                    if (!matchesFilter) {
                        continue;  // Skip this connection
                    }
                }
                
                ServerConnection imported = new ServerConnection();
                
                // Always import basic data
                imported.setName(conn.getName());
                imported.setHost(conn.getHost());
                imported.setPort(conn.getPort());
                imported.setAuthMethod(conn.getAuthMethod());
                
                // SSH Key (conditional or replaced)
                if (result.replaceSSHKey && result.replacementSSHKey != null) {
                    // Replace with selected SSH key
                    imported.setSshKeyId(result.replacementSSHKey.getId());
                    imported.setPrivateKeyPath(app.getSSHKeyManager() != null ? 
                        app.getSSHKeyManager().getEffectiveKeyPath(result.replacementSSHKey) : 
                        result.replacementSSHKey.getKeyPath());
                    imported.setPrivateKeyPassphrase(null);  // Use key manager instead
                } else {
                    imported.setPrivateKeyPath(conn.getPrivateKeyPath());
                    imported.setSshKeyId(conn.getSshKeyId());
                    imported.setPrivateKeyPassphrase(conn.getPrivateKeyPassphrase());
                }
                
                // Group assignment (target group overrides original group)
                if (result.assignToGroup && result.targetGroup != null && !result.targetGroup.trim().isEmpty()) {
                    imported.setGroup(result.targetGroup);
                } else {
                    imported.setGroup(conn.getGroup());
                }
                
                // Username (conditional)
                if (result.importUsername) {
                    imported.setUsername(conn.getUsername());
                } else {
                    imported.setUsername("");
                }
                
                // Password (conditional or replaced)
                if (result.replaceCredentials && result.replacementCredential != null) {
                    // Replace with selected credential
                    imported.setUsername(result.replacementCredential.getUsername());
                    imported.setCredentialId(result.replacementCredential.getId());
                    imported.setEncryptedPassword(null);  // Use credential instead
                } else if (result.importPassword) {
                    imported.setEncryptedPassword(conn.getEncryptedPassword());
                    imported.setCredentialId(conn.getCredentialId());
                } else {
                    imported.setEncryptedPassword(null);
                    imported.setCredentialId(null);
                }
                
                // SSH Tunnels (conditional)
                if (result.importTunnels && conn.getSshTunnels() != null) {
                    imported.setSshTunnels(new ArrayList<>(conn.getSshTunnels()));
                }
                
                // Jump Server (conditional)
                if (result.importJumpServer && conn.getJumpServer() != null) {
                    imported.setJumpServer(conn.getJumpServer());
                }
                
                // Copy settings
                if (conn.getSettings() != null) {
                    imported.setSettings(new de.kortty.model.ConnectionSettings(conn.getSettings()));
                }
                
                importList.add(imported);
            }
            
            return importList;
        } finally {
            // Clean up temporary files
            if (needsCleanup && tempFile != null) {
                try {
                    if (java.nio.file.Files.isDirectory(tempFile)) {
                        // Recursively delete directory
                        java.nio.file.Files.walk(tempFile)
                            .sorted(java.util.Comparator.reverseOrder())
                            .forEach(p -> {
                                try {
                                    java.nio.file.Files.delete(p);
                                } catch (Exception e) {
                                    // Ignore cleanup errors
                                }
                            });
                    } else {
                        java.nio.file.Files.deleteIfExists(tempFile);
                    }
                } catch (Exception e) {
                    // Ignore cleanup errors
                    logger.warn("Failed to cleanup temp file: {}", tempFile, e);
                }
            }
        }
    }
}
