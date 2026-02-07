package de.kortty.ui;

import de.kortty.model.StoredCredential;
import de.kortty.core.CredentialManager;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

/**
 * Result containing credential, plaintext password, and external command
 */
class CredentialResult {
    public final StoredCredential credential;
    public final String password;           // null if not changed or if external command mode
    public final String externalCommand;    // null if not changed or if stored password mode
    
    public CredentialResult(StoredCredential credential, String password, String externalCommand) {
        this.credential = credential;
        this.password = password;
        this.externalCommand = externalCommand;
    }
}

/**
 * Dialog for adding/editing stored credentials.
 * Supports two password modes: stored (encrypted) password or external command.
 */
public class CredentialEditDialog extends Dialog<CredentialResult> {
    
    private final TextField nameField;
    private final TextField usernameField;
    private final PasswordField passwordField;
    private final TextField externalCommandField;
    private final RadioButton storedPasswordRadio;
    private final RadioButton externalCommandRadio;
    private final ComboBox<StoredCredential.Environment> environmentCombo;
    private final TextField serverPatternField;
    private final TextArea descriptionField;
    private final Button testCommandButton;
    private final Label testResultLabel;
    
    public CredentialEditDialog(StoredCredential existingCredential) {
        this(existingCredential, null, null);
    }
    
    public CredentialEditDialog(StoredCredential existingCredential, 
                                CredentialManager credentialManager, 
                                char[] masterPassword) {
        setTitle(existingCredential == null ? I18n.get("credential.edit.addTitle") : I18n.get("credential.edit.editTitle"));
        setHeaderText(existingCredential == null ? I18n.get("credential.edit.addHeader") : I18n.get("credential.edit.editHeader"));
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        grid.setPrefWidth(600);
        
        // Name field
        nameField = new TextField();
        nameField.setPromptText(I18n.get("credential.edit.namePrompt"));
        
        // Username field
        usernameField = new TextField();
        usernameField.setPromptText(I18n.get("credential.edit.usernamePrompt"));
        
        // Password type selection (RadioButtons)
        ToggleGroup passwordTypeGroup = new ToggleGroup();
        storedPasswordRadio = new RadioButton(I18n.get("credential.passwordType.stored"));
        storedPasswordRadio.setToggleGroup(passwordTypeGroup);
        storedPasswordRadio.setSelected(true);
        
        externalCommandRadio = new RadioButton(I18n.get("credential.passwordType.external"));
        externalCommandRadio.setToggleGroup(passwordTypeGroup);
        
        HBox radioBox = new HBox(15, storedPasswordRadio, externalCommandRadio);
        
        // Stored password field
        passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("credential.edit.passwordPrompt"));
        
        // External command field + test button
        externalCommandField = new TextField();
        externalCommandField.setPromptText(I18n.get("credential.externalCommand.prompt"));
        HBox.setHgrow(externalCommandField, Priority.ALWAYS);
        
        testCommandButton = new Button(I18n.get("credential.externalCommand.test"));
        testCommandButton.setOnAction(e -> testExternalCommand());
        
        HBox commandBox = new HBox(8, externalCommandField, testCommandButton);
        HBox.setHgrow(externalCommandField, Priority.ALWAYS);
        
        // Test result label
        testResultLabel = new Label();
        testResultLabel.setWrapText(true);
        testResultLabel.setMaxWidth(Double.MAX_VALUE);
        testResultLabel.setStyle("-fx-font-size: 11px;");
        
        // External command hint
        Label commandHint = new Label(I18n.get("credential.externalCommand.hint"));
        commandHint.setWrapText(true);
        commandHint.setMaxWidth(Double.MAX_VALUE);
        commandHint.setStyle("-fx-font-size: 10px; -fx-text-fill: #888888;");
        
        VBox externalCommandBox = new VBox(5, commandBox, commandHint, testResultLabel);
        
        // Initially hide external command fields
        externalCommandBox.setVisible(false);
        externalCommandBox.setManaged(false);
        
        // Toggle visibility based on radio selection
        passwordTypeGroup.selectedToggleProperty().addListener((obs, oldVal, newVal) -> {
            boolean isStored = newVal == storedPasswordRadio;
            passwordField.setVisible(isStored);
            passwordField.setManaged(isStored);
            externalCommandBox.setVisible(!isStored);
            externalCommandBox.setManaged(!isStored);
            testResultLabel.setText("");
        });
        
        // Environment, server pattern, description
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
            
            // Set password type
            if (existingCredential.getPasswordType() == StoredCredential.PasswordType.EXTERNAL_COMMAND) {
                externalCommandRadio.setSelected(true);
                // Decrypt and show existing external command
                if (credentialManager != null && masterPassword != null) {
                    try {
                        String cmd = credentialManager.getExternalCommand(existingCredential, masterPassword);
                        if (cmd != null) {
                            externalCommandField.setText(cmd);
                        }
                    } catch (Exception e) {
                        // Ignore - user can re-enter the command
                    }
                }
            }
            // Note: Password is not pre-filled for security reasons
        }
        
        // Build layout
        int row = 0;
        grid.add(new Label(I18n.get("common.name") + ":"), 0, row);
        grid.add(nameField, 1, row++);
        
        grid.add(new Label(I18n.get("common.username") + ":"), 0, row);
        grid.add(usernameField, 1, row++);
        
        grid.add(new Label(I18n.get("credential.passwordType") + ":"), 0, row);
        grid.add(radioBox, 1, row++);
        
        grid.add(new Label(I18n.get("common.password") + ":"), 0, row);
        grid.add(passwordField, 1, row);
        grid.add(externalCommandBox, 1, row++);
        
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
            boolean nameValid = nameField.getText() != null && !nameField.getText().trim().isEmpty();
            boolean usernameValid = usernameField.getText() != null && !usernameField.getText().trim().isEmpty();
            okButton.setDisable(!(nameValid && usernameValid));
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
                
                if (storedPasswordRadio.isSelected()) {
                    credential.setPasswordType(StoredCredential.PasswordType.STORED);
                    String password = passwordField.getText();
                    boolean hasPassword = password != null && !password.isEmpty();
                    return new CredentialResult(credential, hasPassword ? password : null, null);
                } else {
                    credential.setPasswordType(StoredCredential.PasswordType.EXTERNAL_COMMAND);
                    String command = externalCommandField.getText();
                    boolean hasCommand = command != null && !command.trim().isEmpty();
                    return new CredentialResult(credential, null, hasCommand ? command.trim() : null);
                }
            }
            return null;
        });
    }
    
    /**
     * Tests the external command by executing it and showing the result.
     */
    private void testExternalCommand() {
        String command = externalCommandField.getText();
        if (command == null || command.trim().isEmpty()) {
            testResultLabel.setText(I18n.get("credential.externalCommand.testEmpty"));
            testResultLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #cc6600;");
            return;
        }
        
        testCommandButton.setDisable(true);
        testResultLabel.setText(I18n.get("credential.externalCommand.testing"));
        testResultLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #888888;");
        
        // Run in background thread to not block UI
        Thread testThread = new Thread(() -> {
            try {
                String result = CredentialManager.executeExternalCommand(command.trim());
                // Mask the password (show length only)
                String maskedResult = "*".repeat(Math.min(result.length(), 20));
                javafx.application.Platform.runLater(() -> {
                    testResultLabel.setText(I18n.get("credential.externalCommand.testSuccess", 
                        result.length(), maskedResult));
                    testResultLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #00cc00;");
                    testCommandButton.setDisable(false);
                });
            } catch (Exception e) {
                javafx.application.Platform.runLater(() -> {
                    testResultLabel.setText(I18n.get("credential.externalCommand.testFailed", e.getMessage()));
                    testResultLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #cc0000;");
                    testCommandButton.setDisable(false);
                });
            }
        }, "ExternalCommand-Test");
        testThread.setDaemon(true);
        testThread.start();
    }
}
