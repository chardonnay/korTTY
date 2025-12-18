package de.kortty.ui;

import de.kortty.model.AuthMethod;

import de.kortty.KorTTYApplication;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.model.ServerConnection;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.Optional;

/**
 * Dialog for managing saved connections.
 */
public class ConnectionManagerDialog extends Dialog<ServerConnection> {
    
    private final KorTTYApplication app;
    private final ConfigurationManager configManager;
    private final CredentialManager credentialManager;
    private final char[] masterPassword;
    private final TableView<ServerConnection> table;
    private final ObservableList<ServerConnection> connections;
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
        
        // Create table
        table = new TableView<>(connections);
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
        
        // Buttons
        Button addButton = new Button("Neu...");
        Button editButton = new Button("Bearbeiten...");
        Button deleteButton = new Button("Löschen");
        Button duplicateButton = new Button("Duplizieren");
        Button exportButton = new Button("Exportieren...");
        
        editButton.setDisable(true);
        deleteButton.setDisable(true);
        duplicateButton.setDisable(true);
        exportButton.setDisable(true);
        
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
        
        VBox buttonBox = new VBox(10, addButton, editButton, deleteButton, duplicateButton, 
                              new javafx.scene.control.Separator(), exportButton);
        buttonBox.setAlignment(Pos.TOP_CENTER);
        buttonBox.setPadding(new Insets(0, 0, 0, 10));
        
        BorderPane content = new BorderPane();
        content.setCenter(table);
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
        ConnectionEditDialog dialog = new ConnectionEditDialog(owner, null, credentialManager, masterPassword);
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
            ConnectionEditDialog dialog = new ConnectionEditDialog(owner, selected, credentialManager, masterPassword);
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
            de.kortty.model.TunnelType.class
        );
        
        de.kortty.persistence.XMLConnectionRepository.ConnectionsWrapper wrapper = 
            new de.kortty.persistence.XMLConnectionRepository.ConnectionsWrapper();
        wrapper.setConnections(exportList);
        
        jakarta.xml.bind.Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(jakarta.xml.bind.Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.marshal(wrapper, result.exportFile);
    }

}
