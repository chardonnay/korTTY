package de.kortty.ui;

import de.kortty.model.ServerConnection;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;

/**
 * Dialog for editing a single connection.
 */
public class ConnectionEditDialog extends Dialog<ServerConnection> {
    
    private final ServerConnection connection;
    
    private final TextField nameField;
    private final TextField hostField;
    private final Spinner<Integer> portSpinner;
    private final TextField usernameField;
    private final PasswordField passwordField;
    private final TextField groupField;
    private final ToggleGroup authMethodGroup;
    private final RadioButton passwordAuthRadio;
    private final RadioButton keyAuthRadio;
    private final TextField keyPathField;
    private final Button browseKeyButton;
    private final PasswordField keyPassphraseField;
    
    public ConnectionEditDialog(Stage owner, ServerConnection existingConnection) {
        this.connection = existingConnection != null ? existingConnection : new ServerConnection();
        
        setTitle(existingConnection == null ? "Neue Verbindung" : "Verbindung bearbeiten");
        setHeaderText(null);
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        
        // Create form
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        // Basic fields
        nameField = new TextField(connection.getName());
        nameField.setPromptText("Anzeigename");
        nameField.setPrefWidth(250);
        
        hostField = new TextField(connection.getHost());
        hostField.setPromptText("hostname oder IP");
        
        portSpinner = new Spinner<>(1, 65535, connection.getPort());
        portSpinner.setEditable(true);
        portSpinner.setPrefWidth(80);
        
        usernameField = new TextField(connection.getUsername());
        usernameField.setPromptText("root");
        
        passwordField = new PasswordField();
        // Don't show encrypted password
        
        groupField = new TextField(connection.getGroup());
        groupField.setPromptText("Optional - zur Gruppierung");
        
        // Authentication method
        authMethodGroup = new ToggleGroup();
        passwordAuthRadio = new RadioButton("Passwort");
        passwordAuthRadio.setToggleGroup(authMethodGroup);
        
        keyAuthRadio = new RadioButton("Privater Schlüssel");
        keyAuthRadio.setToggleGroup(authMethodGroup);
        
        if (connection.getAuthMethod() == ServerConnection.AuthMethod.PUBLIC_KEY) {
            keyAuthRadio.setSelected(true);
        } else {
            passwordAuthRadio.setSelected(true);
        }
        
        // Key authentication fields
        keyPathField = new TextField(connection.getPrivateKeyPath());
        keyPathField.setPromptText("Pfad zum privaten Schlüssel");
        
        browseKeyButton = new Button("...");
        browseKeyButton.setOnAction(e -> browseForKey());
        
        keyPassphraseField = new PasswordField();
        keyPassphraseField.setPromptText("Passphrase (falls erforderlich)");
        
        HBox keyPathBox = new HBox(5, keyPathField, browseKeyButton);
        
        // Update field states based on auth method
        updateAuthFields();
        authMethodGroup.selectedToggleProperty().addListener((obs, old, newVal) -> updateAuthFields());
        
        // Layout
        int row = 0;
        grid.add(new Label("Name:"), 0, row);
        grid.add(nameField, 1, row++);
        
        grid.add(new Label("Host:"), 0, row);
        HBox hostBox = new HBox(10, hostField, new Label("Port:"), portSpinner);
        grid.add(hostBox, 1, row++);
        
        grid.add(new Label("Benutzer:"), 0, row);
        grid.add(usernameField, 1, row++);
        
        grid.add(new Label("Gruppe:"), 0, row);
        grid.add(groupField, 1, row++);
        
        grid.add(new Separator(), 0, row++, 2, 1);
        
        grid.add(new Label("Authentifizierung:"), 0, row);
        HBox authBox = new HBox(15, passwordAuthRadio, keyAuthRadio);
        grid.add(authBox, 1, row++);
        
        grid.add(new Label("Passwort:"), 0, row);
        grid.add(passwordField, 1, row++);
        
        grid.add(new Label("Schlüsseldatei:"), 0, row);
        grid.add(keyPathBox, 1, row++);
        
        grid.add(new Label("Passphrase:"), 0, row);
        grid.add(keyPassphraseField, 1, row++);
        
        getDialogPane().setContent(grid);
        
        // Buttons
        ButtonType saveButtonType = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        
        // Validation
        Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);
        saveButton.setDisable(true);
        
        hostField.textProperty().addListener((obs, old, newVal) -> validateForm(saveButton));
        validateForm(saveButton);
        
        // Result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                connection.setName(nameField.getText().trim());
                connection.setHost(hostField.getText().trim());
                connection.setPort(portSpinner.getValue());
                connection.setUsername(usernameField.getText().trim().isEmpty() ? "root" : usernameField.getText().trim());
                connection.setGroup(groupField.getText().trim().isEmpty() ? null : groupField.getText().trim());
                
                if (keyAuthRadio.isSelected()) {
                    connection.setAuthMethod(ServerConnection.AuthMethod.PUBLIC_KEY);
                    connection.setPrivateKeyPath(keyPathField.getText().trim());
                    // Note: Key passphrase should be encrypted before saving
                } else {
                    connection.setAuthMethod(ServerConnection.AuthMethod.PASSWORD);
                    // Note: Password should be encrypted before saving
                }
                
                return connection;
            }
            return null;
        });
    }
    
    private void updateAuthFields() {
        boolean useKey = keyAuthRadio.isSelected();
        passwordField.setDisable(useKey);
        keyPathField.setDisable(!useKey);
        browseKeyButton.setDisable(!useKey);
        keyPassphraseField.setDisable(!useKey);
    }
    
    private void validateForm(Button saveButton) {
        boolean valid = !hostField.getText().trim().isEmpty();
        saveButton.setDisable(!valid);
    }
    
    private void browseForKey() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Privaten Schlüssel auswählen");
        
        // Start in .ssh directory if it exists
        File sshDir = new File(System.getProperty("user.home"), ".ssh");
        if (sshDir.exists()) {
            fileChooser.setInitialDirectory(sshDir);
        }
        
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Alle Dateien", "*.*"),
                new FileChooser.ExtensionFilter("PEM Dateien", "*.pem"),
                new FileChooser.ExtensionFilter("Private Keys", "id_rsa", "id_ed25519", "id_ecdsa")
        );
        
        File file = fileChooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (file != null) {
            keyPathField.setText(file.getAbsolutePath());
        }
    }
}
