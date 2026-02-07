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
        setTitle(existingKey == null ? I18n.get("gpg.edit.addTitle") : I18n.get("gpg.edit.editTitle"));
        setHeaderText(existingKey == null ? I18n.get("gpg.edit.addHeader") : I18n.get("gpg.edit.editHeader"));
        
        // Create form
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        grid.setPrefWidth(500);
        
        nameField = new TextField();
        nameField.setPromptText(I18n.get("gpg.edit.namePrompt"));
        
        keyIdField = new TextField();
        keyIdField.setPromptText(I18n.get("gpg.edit.keyIdPrompt"));
        
        fingerprintField = new TextField();
        fingerprintField.setPromptText(I18n.get("gpg.edit.fingerprintPrompt"));
        
        emailField = new TextField();
        emailField.setPromptText(I18n.get("gpg.edit.emailPrompt"));
        
        publicKeyPathField = new TextField();
        publicKeyPathField.setPromptText(I18n.get("gpg.edit.publicKeyPathPrompt"));
        
        Button browseButton = new Button(I18n.get("gpg.edit.browse"));
        browseButton.setOnAction(e -> {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle(I18n.get("gpg.edit.selectPublicKey"));
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
        grid.add(new Label(I18n.get("common.name") + ":"), 0, row);
        grid.add(nameField, 1, row++);
        
        grid.add(new Label(I18n.get("gpg.edit.keyId")), 0, row);
        grid.add(keyIdField, 1, row++);
        
        grid.add(new Label(I18n.get("gpg.edit.fingerprint")), 0, row);
        grid.add(fingerprintField, 1, row++);
        
        grid.add(new Label(I18n.get("gpg.edit.email")), 0, row);
        grid.add(emailField, 1, row++);
        
        grid.add(new Label(I18n.get("gpg.edit.publicKey")), 0, row);
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
