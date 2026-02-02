package de.kortty.ui;

import de.kortty.model.GPGKey;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.FileChooser;

import java.io.File;

/**
 * Dialog for adding/editing a GPG key.
 */
public class GPGKeyEditDialog extends Dialog<GPGKey> {
    
    private final TextField nameField;
    private final TextField keyIdField;
    private final TextField fingerprintField;
    private final TextField emailField;
    private final TextField publicKeyPathField;
    
    public GPGKeyEditDialog(GPGKey existingKey) {
        setTitle(existingKey == null ? "GPG-Schlüssel hinzufügen" : "GPG-Schlüssel bearbeiten");
        setHeaderText(existingKey == null ? "Neuen GPG-Schlüssel hinzufügen" : "GPG-Schlüssel bearbeiten");
        
        // Create form
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        grid.setPrefWidth(500);
        
        nameField = new TextField();
        nameField.setPromptText("z.B. 'Mein Produktionsschlüssel'");
        
        keyIdField = new TextField();
        keyIdField.setPromptText("z.B. '1234ABCD'");
        
        fingerprintField = new TextField();
        fingerprintField.setPromptText("z.B. '1234 5678 90AB CDEF...'");
        
        emailField = new TextField();
        emailField.setPromptText("z.B. 'user@example.com'");
        
        publicKeyPathField = new TextField();
        publicKeyPathField.setPromptText("Pfad zur öffentlichen Schlüsseldatei (optional)");
        
        Button browseButton = new Button("Durchsuchen...");
        browseButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Öffentlichen Schlüssel auswählen");
            fileChooser.getExtensionFilters().add(
                new FileChooser.ExtensionFilter("GPG Public Key", "*.asc", "*.gpg", "*.pub")
            );
            File file = fileChooser.showOpenDialog(getDialogPane().getScene().getWindow());
            if (file != null) {
                publicKeyPathField.setText(file.getAbsolutePath());
            }
        });
        
        // Fill existing values
        if (existingKey != null) {
            nameField.setText(existingKey.getName());
            keyIdField.setText(existingKey.getKeyId());
            if (existingKey.getFingerprint() != null) {
                fingerprintField.setText(existingKey.getFingerprint());
            }
            if (existingKey.getEmail() != null) {
                emailField.setText(existingKey.getEmail());
            }
            if (existingKey.getPublicKeyPath() != null) {
                publicKeyPathField.setText(existingKey.getPublicKeyPath());
            }
        }
        
        // Layout
        int row = 0;
        grid.add(new Label("Name:"), 0, row);
        grid.add(nameField, 1, row++);
        
        grid.add(new Label("Key-ID:"), 0, row);
        grid.add(keyIdField, 1, row++);
        
        grid.add(new Label("Fingerprint:"), 0, row);
        grid.add(fingerprintField, 1, row++);
        
        grid.add(new Label("E-Mail:"), 0, row);
        grid.add(emailField, 1, row++);
        
        grid.add(new Label("Öffentlicher Schlüssel:"), 0, row);
        grid.add(publicKeyPathField, 1, row);
        grid.add(browseButton, 2, row++);
        
        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        // Enable/disable OK button based on required fields
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        
        nameField.textProperty().addListener((obs, old, newVal) -> 
            okButton.setDisable(newVal == null || newVal.trim().isEmpty() || 
                               keyIdField.getText() == null || keyIdField.getText().trim().isEmpty()));
        keyIdField.textProperty().addListener((obs, old, newVal) -> 
            okButton.setDisable(newVal == null || newVal.trim().isEmpty() || 
                               nameField.getText() == null || nameField.getText().trim().isEmpty()));
        
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                GPGKey key = existingKey != null ? existingKey : new GPGKey();
                key.setName(nameField.getText().trim());
                key.setKeyId(keyIdField.getText().trim());
                key.setFingerprint(fingerprintField.getText().trim().isEmpty() ? null : fingerprintField.getText().trim());
                key.setEmail(emailField.getText().trim().isEmpty() ? null : emailField.getText().trim());
                key.setPublicKeyPath(publicKeyPathField.getText().trim().isEmpty() ? null : publicKeyPathField.getText().trim());
                return key;
            }
            return null;
        });
    }
}
