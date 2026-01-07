package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.model.ServerConnection;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.CompressionLevel;
import net.lingala.zip4j.model.enums.EncryptionMethod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dialog for managing saved connections.
 */
public class ConnectionManagerDialog extends Dialog<ServerConnection> {
    
    private static final Logger logger = LoggerFactory.getLogger(ConnectionManagerDialog.class);
    
    private final KorTTYApplication app;
    private final ConfigurationManager configManager;
    private final CredentialManager credentialManager;
    private final char[] masterPassword;
    private final TableView<ServerConnection> table;
    private final ObservableList<ServerConnection> connections;
    private final FilteredList<ServerConnection> filteredConnections;
    private final TextField searchField;
    private final Stage owner;
    
    public ConnectionManagerDialog(Stage owner, KorTTYApplication app) {
        this.app = app;
        this.configManager = app.getConfigManager();
        this.credentialManager = app.getCredentialManager();
        this.masterPassword = app.getMasterPasswordManager().getMasterPassword();
        this.owner = owner;
        
        setTitle("Verbindungen verwalten");
        setHeaderText(null);
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);
        
        // Initialize connections list
        connections = FXCollections.observableArrayList(configManager.getConnections());
        
        // Create filtered list for search functionality
        filteredConnections = new FilteredList<>(connections, p -> true);
        
        // Create search field
        searchField = new TextField();
        searchField.setPromptText("Suchen nach Name, Host oder IP-Adresse... (* als Wildcard)");
        searchField.setPrefWidth(300);
        
        // Add listener to filter table based on search text
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.trim().isEmpty()) {
                filteredConnections.setPredicate(p -> true);
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
                
                filteredConnections.setPredicate(connection -> {
                    // Search in name
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
                    // Search in host
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
        
        // Create table with filtered list
        table = new TableView<>(filteredConnections);
        table.setPrefSize(600, 400);
        table.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        
        TableColumn<ServerConnection, String> nameColumn = new TableColumn<>("Name");
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        nameColumn.setPrefWidth(150);
        
        TableColumn<ServerConnection, String> hostColumn = new TableColumn<>("Host");
        hostColumn.setCellValueFactory(new PropertyValueFactory<>("host"));
        hostColumn.setPrefWidth(150);
        
        TableColumn<ServerConnection, Integer> portColumn = new TableColumn<>("Port");
        portColumn.setCellValueFactory(new PropertyValueFactory<>("port"));
        portColumn.setPrefWidth(60);
        
        TableColumn<ServerConnection, String> userColumn = new TableColumn<>("Benutzer");
        userColumn.setCellValueFactory(new PropertyValueFactory<>("username"));
        userColumn.setPrefWidth(100);
        
        TableColumn<ServerConnection, String> groupColumn = new TableColumn<>("Gruppe");
        groupColumn.setCellValueFactory(new PropertyValueFactory<>("group"));
        groupColumn.setPrefWidth(100);
        
        table.getColumns().addAll(nameColumn, hostColumn, portColumn, userColumn, groupColumn);
        table.setPlaceholder(new Label("Keine Verbindungen gespeichert"));
        
        // Double-click to connect
        table.setRowFactory(tv -> {
            TableRow<ServerConnection> row = new TableRow<>();
            row.setOnMouseClicked(e -> {
                if (e.getClickCount() == 2 && !row.isEmpty()) {
                    setResult(row.getItem());
                    close();
                }
            });
            return row;
        });
        
        // Buttons - set uniform width for all buttons
        Button addButton = new Button("Neu...");
        Button editButton = new Button("Bearbeiten...");
        Button deleteButton = new Button("Löschen");
        Button duplicateButton = new Button("Duplizieren");
        Button exportButton = new Button("Exportieren...");
        Button importButton = new Button("Importieren...");
        
        // Set uniform width for all buttons (use the widest button as reference)
        double buttonWidth = 120;
        addButton.setPrefWidth(buttonWidth);
        editButton.setPrefWidth(buttonWidth);
        deleteButton.setPrefWidth(buttonWidth);
        duplicateButton.setPrefWidth(buttonWidth);
        exportButton.setPrefWidth(buttonWidth);
        importButton.setPrefWidth(buttonWidth);
        
        editButton.setDisable(true);
        deleteButton.setDisable(true);
        duplicateButton.setDisable(true);
        exportButton.setDisable(true);
        // importButton always enabled
        
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            boolean hasSelection = selected != null;
            boolean multipleSelection = table.getSelectionModel().getSelectedItems().size() > 1;
            
            editButton.setDisable(!hasSelection || multipleSelection);  // Only single selection
            deleteButton.setDisable(!hasSelection);
            duplicateButton.setDisable(!hasSelection || multipleSelection);  // Only single selection
            exportButton.setDisable(!hasSelection);  // Allow multiple selection
        });
        
        addButton.setOnAction(e -> addConnection());
        editButton.setOnAction(e -> editConnection());
        deleteButton.setOnAction(e -> deleteConnection());
        duplicateButton.setOnAction(e -> duplicateConnection());
        exportButton.setOnAction(e -> exportConnections());
        importButton.setOnAction(e -> importConnections());
        
        VBox buttonBox = new VBox(10, addButton, editButton, deleteButton, duplicateButton, 
                              new javafx.scene.control.Separator(), exportButton, importButton);
        buttonBox.setAlignment(Pos.TOP_CENTER);
        buttonBox.setPadding(new Insets(0, 0, 0, 10));
        
        // Search field container
        HBox searchBox = new HBox(10);
        searchBox.setPadding(new Insets(0, 0, 10, 0));
        Label searchLabel = new Label("Suchen:");
        searchBox.getChildren().addAll(searchLabel, searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        
        // Table container with search
        VBox tableContainer = new VBox(10);
        tableContainer.getChildren().addAll(searchBox, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        
        BorderPane content = new BorderPane();
        content.setCenter(tableContainer);
        content.setRight(buttonBox);
        content.setPadding(new Insets(10));
        
        getDialogPane().setContent(content);
        
        // Dialog buttons
        ButtonType connectButtonType = new ButtonType("Verbinden", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(connectButtonType, ButtonType.CLOSE);
        
        Button connectButton = (Button) getDialogPane().lookupButton(connectButtonType);
        connectButton.setDisable(true);
        
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            connectButton.setDisable(selected == null);
        });
        
        setResultConverter(dialogButton -> {
            if (dialogButton == connectButtonType) {
                return table.getSelectionModel().getSelectedItem();
            }
            return null;
        });
    }
    
    private void addConnection() {
        ConnectionEditDialog dialog = new ConnectionEditDialog(owner, null, credentialManager, 
            app.getSSHKeyManager(), masterPassword);
        dialog.showAndWait().ifPresent(connection -> {
            connections.add(connection);
            configManager.addConnection(connection);
            table.getSelectionModel().select(connection);
            saveConnections();
        });
    }
    
    private void editConnection() {
        ServerConnection selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) {
            ConnectionEditDialog dialog = new ConnectionEditDialog(owner, selected, credentialManager, 
                app.getSSHKeyManager(), masterPassword);
            dialog.showAndWait().ifPresent(connection -> {
                int index = connections.indexOf(selected);
                connections.set(index, connection);
                configManager.updateConnection(connection);
                table.getSelectionModel().select(connection);
                saveConnections();
            });
        }
    }
    
    private void deleteConnection() {
        ServerConnection selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Verbindung löschen");
            confirm.setHeaderText("Verbindung \"" + selected.getDisplayName() + "\" löschen?");
            confirm.setContentText("Diese Aktion kann nicht rückgängig gemacht werden.");
            
            confirm.showAndWait().ifPresent(result -> {
                if (result == ButtonType.OK) {
                    connections.remove(selected);
                    configManager.removeConnection(selected);
                    saveConnections();
                }
            });
        }
    }
    
    private void duplicateConnection() {
        ServerConnection selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) {
            ServerConnection copy = new ServerConnection();
            copy.setName(selected.getName() + " (Kopie)");
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
            table.getSelectionModel().select(copy);
            saveConnections();
        }
    }
    
    private void saveConnections() {
        try {
            configManager.save(app.getMasterPasswordManager().getDerivedKey());
            org.slf4j.LoggerFactory.getLogger(getClass()).info("Connections saved successfully");
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Failed to save connections", e);
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle("Fehler");
            alert.setHeaderText("Speichern fehlgeschlagen");
            alert.setContentText("Die Verbindungen konnten nicht gespeichert werden: " + e.getMessage());
            alert.showAndWait();
        }
    }
    
    private void exportConnections() {
        java.util.List<ServerConnection> selectedConnections = 
            new java.util.ArrayList<>(table.getSelectionModel().getSelectedItems());
        
        if (selectedConnections.isEmpty()) {
            return;
        }
        
        ConnectionExportDialog dialog = new ConnectionExportDialog(owner, selectedConnections);
        dialog.showAndWait().ifPresent(result -> {
            try {
                exportConnectionsToFile(result);
                
                Alert success = new Alert(Alert.AlertType.INFORMATION);
                success.setTitle("Export erfolgreich");
                success.setHeaderText(String.format("%d Verbindung(en) exportiert", selectedConnections.size()));
                success.setContentText("Datei: " + result.exportFile.getAbsolutePath());
                success.showAndWait();
            } catch (Exception e) {
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle("Export fehlgeschlagen");
                error.setHeaderText("Fehler beim Exportieren");
                error.setContentText(e.getMessage());
                error.showAndWait();
            }
        });
    }
    
    private void exportConnectionsToFile(ConnectionExportDialog.ExportResult result) throws Exception {
        // Create copies of connections and filter based on options
        java.util.List<ServerConnection> exportList = new java.util.ArrayList<>();
        
        for (ServerConnection conn : result.connections) {
            ServerConnection copy = new ServerConnection();
            copy.setName(conn.getName());
            copy.setHost(conn.getHost());
            copy.setPort(conn.getPort());
            copy.setGroup(conn.getGroup());
            copy.setAuthMethod(conn.getAuthMethod());
            copy.setPrivateKeyPath(conn.getPrivateKeyPath());
            
            // Username (optional)
            if (result.includeUsername) {
                copy.setUsername(conn.getUsername());
            } else {
                copy.setUsername("");  // Empty but field exists
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
                copy.setSshTunnels(new java.util.ArrayList<>(conn.getSshTunnels()));
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
        
        // Export to XML using XMLConnectionRepository
        de.kortty.persistence.XMLConnectionRepository repo = 
            new de.kortty.persistence.XMLConnectionRepository(result.exportFile.getParentFile().toPath());
        
        // Write directly to the selected file
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
        marshaller.marshal(wrapper, result.exportFile);
    }

    
    private void importConnections() {
        ConnectionImportDialog dialog = new ConnectionImportDialog(owner, credentialManager, 
            app.getSSHKeyManager(), configManager);
        dialog.showAndWait().ifPresent(result -> {
            try {
                java.util.List<ServerConnection> importedConnections = importConnectionsFromFile(result);
                
                // Add to connections list and config
                int successCount = 0;
                for (ServerConnection conn : importedConnections) {
                    connections.add(conn);
                    configManager.addConnection(conn);
                    successCount++;
                }
                
                // Save
                saveConnections();
                
                Alert success = new Alert(Alert.AlertType.INFORMATION);
                success.setTitle("Import erfolgreich");
                success.setHeaderText(String.format("%d Verbindung(en) importiert", successCount));
                success.setContentText("Die Verbindungen wurden erfolgreich hinzugefügt.");
                success.showAndWait();
                
                // Select first imported connection
                if (!importedConnections.isEmpty()) {
                    table.getSelectionModel().select(importedConnections.get(0));
                }
            } catch (Exception e) {
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle("Import fehlgeschlagen");
                error.setHeaderText("Fehler beim Importieren");
                error.setContentText(e.getMessage());
                error.showAndWait();
            }
        });
    }
    
    private java.util.List<ServerConnection> importConnectionsFromFile(ConnectionImportDialog.ImportResult result) throws Exception {
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
                pb.redirectInput(java.lang.ProcessBuilder.Redirect.from(nullFile));
                
                java.lang.Process process = pb.start();
                
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
                    throw new Exception("GPG-Entschlüsselung fehlgeschlagen (Exit Code: " + exitCode + ")\n" +
                                       "Output: " + output.toString() + "\n" +
                                       "Stellen Sie sicher, dass GPG installiert ist und der Schlüssel verfügbar ist.");
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
                    // Ask for password
                    TextInputDialog passwordDialog = new TextInputDialog();
                    passwordDialog.setTitle("ZIP-Passwort erforderlich");
                    passwordDialog.setHeaderText("Das ZIP-Archiv ist passwortgeschützt");
                    passwordDialog.setContentText("Passwort:");
                    passwordDialog.getEditor().setPromptText("ZIP-Passwort eingeben");
                    
                    // Try to use master password as default
                    java.util.Optional<String> passwordOpt = passwordDialog.showAndWait();
                    if (!passwordOpt.isPresent() || passwordOpt.get().trim().isEmpty()) {
                        throw new Exception("ZIP-Entschlüsselung abgebrochen: Kein Passwort eingegeben");
                    }
                    
                    zipFile.setPassword(passwordOpt.get().toCharArray());
                }
                
                // Extract to temp directory
                java.nio.file.Path extractDir = java.nio.file.Files.createTempDirectory("kortty-import-extract-");
                needsCleanup = true;
                
                zipFile.extractAll(extractDir.toString());
                
                // Find XML file in extracted directory
                java.util.List<java.nio.file.Path> xmlFiles = java.nio.file.Files.walk(extractDir)
                    .filter(p -> p.toString().toLowerCase().endsWith(".xml"))
                    .collect(java.util.stream.Collectors.toList());
                
                if (xmlFiles.isEmpty()) {
                    throw new Exception("Keine XML-Datei im ZIP-Archiv gefunden");
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
        
        java.util.List<ServerConnection> importList = new java.util.ArrayList<>();
        
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
                imported.setSshTunnels(new java.util.ArrayList<>(conn.getSshTunnels()));
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
