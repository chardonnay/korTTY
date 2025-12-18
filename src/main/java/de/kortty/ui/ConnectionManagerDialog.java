package de.kortty.ui;

import de.kortty.model.AuthMethod;

import de.kortty.core.ConfigurationManager;
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
    
    private final ConfigurationManager configManager;
    private final TableView<ServerConnection> table;
    private final ObservableList<ServerConnection> connections;
    private final Stage owner;
    
    public ConnectionManagerDialog(Stage owner, ConfigurationManager configManager) {
        this.configManager = configManager;
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
        
        editButton.setDisable(true);
        deleteButton.setDisable(true);
        duplicateButton.setDisable(true);
        
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> {
            boolean hasSelection = selected != null;
            editButton.setDisable(!hasSelection);
            deleteButton.setDisable(!hasSelection);
            duplicateButton.setDisable(!hasSelection);
        });
        
        addButton.setOnAction(e -> addConnection());
        editButton.setOnAction(e -> editConnection());
        deleteButton.setOnAction(e -> deleteConnection());
        duplicateButton.setOnAction(e -> duplicateConnection());
        
        VBox buttonBox = new VBox(10, addButton, editButton, deleteButton, duplicateButton);
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
        ConnectionEditDialog dialog = new ConnectionEditDialog(owner, null);
        dialog.showAndWait().ifPresent(connection -> {
            connections.add(connection);
            configManager.addConnection(connection);
            table.getSelectionModel().select(connection);
        });
    }
    
    private void editConnection() {
        ServerConnection selected = table.getSelectionModel().getSelectedItem();
        if (selected != null) {
            ConnectionEditDialog dialog = new ConnectionEditDialog(owner, selected);
            dialog.showAndWait().ifPresent(connection -> {
                int index = connections.indexOf(selected);
                connections.set(index, connection);
                configManager.updateConnection(connection);
                table.getSelectionModel().select(connection);
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
        }
    }
}
