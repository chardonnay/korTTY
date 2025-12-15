package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.security.PasswordVault;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;

/**
 * Quick connect dialog for entering connection details.
 */
public class QuickConnectDialog extends Dialog<QuickConnectDialog.ConnectionResult> {
    
    private final ComboBox<ServerConnection> savedConnectionsCombo;
    private final TextField hostField;
    private final Spinner<Integer> portSpinner;
    private final TextField usernameField;
    private final PasswordField passwordField;
    private final CheckBox saveConnectionCheck;
    private final TextField connectionNameField;
    
    private final List<ServerConnection> savedConnections;
    private final PasswordVault passwordVault;
    
    public QuickConnectDialog(Stage owner, List<ServerConnection> savedConnections, PasswordVault passwordVault) {
        this.savedConnections = savedConnections;
        this.passwordVault = passwordVault;
        
        setTitle("Schnellverbindung");
        setHeaderText("SSH-Verbindung herstellen");
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        
        VBox mainContent = new VBox(15);
        mainContent.setPadding(new Insets(10, 20, 10, 20));
        
        // Create form fields first (before they are referenced in event handlers)
        hostField = new TextField();
        hostField.setPromptText("hostname oder IP");
        hostField.setPrefWidth(250);
        
        portSpinner = new Spinner<>(1, 65535, 22);
        portSpinner.setEditable(true);
        portSpinner.setPrefWidth(80);
        
        usernameField = new TextField();
        usernameField.setPromptText("root");
        usernameField.setText("root");
        
        passwordField = new PasswordField();
        passwordField.setPromptText("Passwort");
        
        saveConnectionCheck = new CheckBox("Verbindung speichern");
        
        connectionNameField = new TextField();
        connectionNameField.setPromptText("Verbindungsname (optional)");
        connectionNameField.setDisable(true);
        
        saveConnectionCheck.selectedProperty().addListener((obs, old, newVal) -> {
            connectionNameField.setDisable(!newVal);
        });
        
        // Saved connections dropdown (created after fields are initialized)
        savedConnectionsCombo = new ComboBox<>();
        savedConnectionsCombo.setPromptText("-- Gespeicherte Verbindung auswählen --");
        savedConnectionsCombo.setPrefWidth(400);
        savedConnectionsCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ServerConnection item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getName() + " (" + item.getUsername() + "@" + item.getHost() + ":" + item.getPort() + ")");
                }
            }
        });
        savedConnectionsCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ServerConnection item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText("-- Gespeicherte Verbindung auswählen --");
                } else {
                    setText(item.getName() + " (" + item.getUsername() + "@" + item.getHost() + ")");
                }
            }
        });
        
        if (savedConnections != null && !savedConnections.isEmpty()) {
            savedConnectionsCombo.getItems().addAll(savedConnections);
        }
        
        // When a saved connection is selected, fill in the fields including password
        savedConnectionsCombo.setOnAction(e -> {
            ServerConnection selected = savedConnectionsCombo.getValue();
            if (selected != null) {
                hostField.setText(selected.getHost());
                portSpinner.getValueFactory().setValue(selected.getPort());
                usernameField.setText(selected.getUsername());
                
                // Try to retrieve stored password from vault
                if (passwordVault != null) {
                    String storedPassword = passwordVault.retrievePassword(selected);
                    if (storedPassword != null && !storedPassword.isEmpty()) {
                        passwordField.setText(storedPassword);
                    } else {
                        passwordField.clear();
                        passwordField.requestFocus();
                    }
                } else {
                    passwordField.clear();
                    passwordField.requestFocus();
                }
                saveConnectionCheck.setSelected(false);
            }
        });
        
        // Separator
        Separator separator = new Separator();
        separator.setPadding(new Insets(5, 0, 5, 0));
        
        // Layout
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        
        grid.add(new Label("Host:"), 0, 0);
        
        HBox hostBox = new HBox(10);
        hostBox.getChildren().addAll(hostField, new Label("Port:"), portSpinner);
        grid.add(hostBox, 1, 0);
        
        grid.add(new Label("Benutzer:"), 0, 1);
        grid.add(usernameField, 1, 1);
        
        grid.add(new Label("Passwort:"), 0, 2);
        grid.add(passwordField, 1, 2);
        
        grid.add(saveConnectionCheck, 1, 3);
        
        grid.add(new Label("Name:"), 0, 4);
        grid.add(connectionNameField, 1, 4);
        
        // Add all to main content
        if (savedConnections != null && !savedConnections.isEmpty()) {
            Label savedLabel = new Label("Gespeicherte Verbindungen:");
            mainContent.getChildren().addAll(savedLabel, savedConnectionsCombo, separator);
        }
        mainContent.getChildren().add(grid);
        
        getDialogPane().setContent(mainContent);
        
        // Buttons
        ButtonType connectButtonType = new ButtonType("Verbinden", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(connectButtonType, ButtonType.CANCEL);
        
        // Enable/disable connect button
        Button connectButton = (Button) getDialogPane().lookupButton(connectButtonType);
        connectButton.setDisable(true);
        
        hostField.textProperty().addListener((obs, old, newVal) -> {
            connectButton.setDisable(newVal.trim().isEmpty());
        });
        
        // Result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == connectButtonType) {
                // Check if using a saved connection
                ServerConnection selected = savedConnectionsCombo.getValue();
                if (selected != null && 
                    selected.getHost().equals(hostField.getText().trim()) &&
                    selected.getPort() == portSpinner.getValue() &&
                    selected.getUsername().equals(usernameField.getText().trim())) {
                    // Using an existing saved connection
                    return new ConnectionResult(selected, passwordField.getText(), false, true);
                }
                
                ServerConnection connection = new ServerConnection();
                connection.setHost(hostField.getText().trim());
                connection.setPort(portSpinner.getValue());
                connection.setUsername(usernameField.getText().trim().isEmpty() ? "root" : usernameField.getText().trim());
                
                if (saveConnectionCheck.isSelected()) {
                    String name = connectionNameField.getText().trim();
                    connection.setName(name.isEmpty() ? connection.getUsername() + "@" + connection.getHost() : name);
                }
                
                return new ConnectionResult(connection, passwordField.getText(), saveConnectionCheck.isSelected(), false);
            }
            return null;
        });
        
        // Focus host field or saved connections
        if (savedConnections != null && !savedConnections.isEmpty()) {
            savedConnectionsCombo.requestFocus();
        } else {
            hostField.requestFocus();
        }
    }
    
    /**
     * Result record containing connection details.
     * @param connection The server connection details
     * @param password The password entered
     * @param save Whether to save this as a new connection
     * @param existingSaved Whether this is an existing saved connection (password needs to be fetched from vault)
     */
    public record ConnectionResult(ServerConnection connection, String password, boolean save, boolean existingSaved) {}
}
