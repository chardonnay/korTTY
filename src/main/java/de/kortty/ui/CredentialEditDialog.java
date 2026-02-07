package de.kortty.ui;

import de.kortty.model.StoredCredential;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;

/**
 * Result containing credential and plaintext password
 */
class CredentialResult {
    public final StoredCredential credential;
    public final String password;  // null if not changed
    
    public CredentialResult(StoredCredential credential, String password) {
        this.credential = credential;
        this.password = password;
    }
}

/**
 * Dialog for adding/editing stored credentials.
 */
public class CredentialEditDialog extends Dialog<CredentialResult> {
    
    private final TextField nameField;
    private final TextField usernameField;
    private final PasswordField passwordField;
    private final ComboBox<StoredCredential.Environment> environmentCombo;
    private final TextField serverPatternField;
    private final TextArea descriptionField;
    
    public CredentialEditDialog(StoredCredential existingCredential) {
        setTitle(existingCredential == null ? I18n.get("credential.edit.addTitle") : I18n.get("credential.edit.editTitle"));
        setHeaderText(existingCredential == null ? I18n.get("credential.edit.addHeader") : I18n.get("credential.edit.editHeader"));
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        grid.setPrefWidth(500);
        
        nameField = new TextField();
        nameField.setPromptText(I18n.get("credential.edit.namePrompt"));
        
        usernameField = new TextField();
        usernameField.setPromptText(I18n.get("credential.edit.usernamePrompt"));
        
        passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("credential.edit.passwordPrompt"));
        
        environmentCombo = new ComboBox<>();
        environmentCombo.getItems().addAll(StoredCredential.Environment.values());
        environmentCombo.setValue(StoredCredential.Environment.PRODUCTION);
        
        serverPatternField = new TextField();
        serverPatternField.setPromptText(I18n.get("credential.edit.serverPatternPrompt"));
        
        descriptionField = new TextArea();
        descriptionField.setPromptText(I18n.get("credential.edit.descriptionPrompt"));
        descriptionField.setPrefRowCount(3);
        
        // Fill existing values
        if (existingCredential != null) {
            nameField.setText(existingCredential.getName());
            usernameField.setText(existingCredential.getUsername());
            environmentCombo.setValue(existingCredential.getEnvironment());
            if (existingCredential.getServerPattern() != null) {
                serverPatternField.setText(existingCredential.getServerPattern());
            }
            if (existingCredential.getDescription() != null) {
                descriptionField.setText(existingCredential.getDescription());
            }
            // Note: Password is not pre-filled for security reasons
        }
        
        int row = 0;
        grid.add(new Label(I18n.get("common.name") + ":"), 0, row);
        grid.add(nameField, 1, row++);
        
        grid.add(new Label(I18n.get("common.username") + ":"), 0, row);
        grid.add(usernameField, 1, row++);
        
        grid.add(new Label(I18n.get("common.password") + ":"), 0, row);
        grid.add(passwordField, 1, row++);
        
        grid.add(new Label(I18n.get("credential.edit.environment")), 0, row);
        grid.add(environmentCombo, 1, row++);
        
        grid.add(new Label(I18n.get("credential.edit.serverPattern")), 0, row);
        grid.add(serverPatternField, 1, row++);
        
        grid.add(new Label(I18n.get("common.description") + ":"), 0, row);
        grid.add(descriptionField, 1, row++);
        
        getDialogPane().setContent(grid);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        Button okButton = (Button) getDialogPane().lookupButton(ButtonType.OK);
        okButton.setDisable(true);
        
        // Validate required fields
        Runnable validator = () -> {
            boolean valid = nameField.getText() != null && !nameField.getText().trim().isEmpty() &&
                           usernameField.getText() != null && !usernameField.getText().trim().isEmpty();
            okButton.setDisable(!valid);
        };
        
        nameField.textProperty().addListener((obs, old, newVal) -> validator.run());
        usernameField.textProperty().addListener((obs, old, newVal) -> validator.run());
        
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                StoredCredential credential = existingCredential != null ? existingCredential : new StoredCredential();
                credential.setName(nameField.getText().trim());
                credential.setUsername(usernameField.getText().trim());
                credential.setEnvironment(environmentCombo.getValue());
                credential.setServerPattern(serverPatternField.getText().trim().isEmpty() ? null : serverPatternField.getText().trim());
                credential.setDescription(descriptionField.getText().trim().isEmpty() ? null : descriptionField.getText().trim());
                
                String password = passwordField.getText();
                boolean hasPassword = password != null && !password.isEmpty();
                
                return new CredentialResult(credential, hasPassword ? password : null);
            }
            return null;
        });
    }
}
