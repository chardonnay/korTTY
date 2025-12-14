package de.kortty.ui;

import de.kortty.model.ServerConnection;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.Optional;

/**
 * Quick connect dialog for entering connection details.
 */
public class QuickConnectDialog extends Dialog<QuickConnectDialog.ConnectionResult> {
    
    private final TextField hostField;
    private final Spinner<Integer> portSpinner;
    private final TextField usernameField;
    private final PasswordField passwordField;
    private final CheckBox saveConnectionCheck;
    private final TextField connectionNameField;
    
    public QuickConnectDialog(Stage owner) {
        setTitle("Schnellverbindung");
        setHeaderText("SSH-Verbindung herstellen");
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        
        // Create form
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20, 20, 10, 20));
        
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
        
        // Layout
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
        
        getDialogPane().setContent(grid);
        
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
                ServerConnection connection = new ServerConnection();
                connection.setHost(hostField.getText().trim());
                connection.setPort(portSpinner.getValue());
                connection.setUsername(usernameField.getText().trim().isEmpty() ? "root" : usernameField.getText().trim());
                
                if (saveConnectionCheck.isSelected()) {
                    String name = connectionNameField.getText().trim();
                    connection.setName(name.isEmpty() ? connection.getUsername() + "@" + connection.getHost() : name);
                }
                
                return new ConnectionResult(connection, passwordField.getText(), saveConnectionCheck.isSelected());
            }
            return null;
        });
        
        // Focus host field
        hostField.requestFocus();
    }
    
    /**
     * Result record containing connection details.
     */
    public record ConnectionResult(ServerConnection connection, String password, boolean save) {}
}
