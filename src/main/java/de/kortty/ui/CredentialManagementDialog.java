package de.kortty.ui;

import de.kortty.core.CredentialManager;
import de.kortty.core.EnvironmentManager;
import de.kortty.model.StoredCredential;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dialog for managing stored credentials.
 */
public class CredentialManagementDialog extends ThemeAwareDialog<Boolean> {
    
    private static final Logger logger = LoggerFactory.getLogger(CredentialManagementDialog.class);
    
    private final CredentialManager credentialManager;
    private final EnvironmentManager environmentManager;
    private final char[] masterPassword;
    private final ListView<StoredCredential> credentialListView;

    public CredentialManagementDialog(CredentialManager credentialManager, char[] masterPassword) {
        this(credentialManager, null, masterPassword);
    }

    public CredentialManagementDialog(CredentialManager credentialManager,
                                     EnvironmentManager environmentManager,
                                     char[] masterPassword) {
        this.credentialManager = credentialManager;
        this.environmentManager = environmentManager;
        this.masterPassword = masterPassword;
        this.credentialListView = new ListView<>();
        
        setTitle(I18n.get("menu.management.credentials"));
        setHeaderText(I18n.get("credential.header"));
        
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setPrefWidth(750);
        content.setPrefHeight(550);
        
        Label listLabel = new Label(I18n.get("credential.storedCredentials"));
        
        credentialListView.setCellFactory(lv -> new ListCell<StoredCredential>() {
            @Override
            protected void updateItem(StoredCredential cred, boolean empty) {
                super.updateItem(cred, empty);
                if (empty || cred == null) {
                    setText(null);
                } else {
                    String serverInfo = cred.getServerPattern() != null ? 
                        " [" + cred.getServerPattern() + "]" : " [" + I18n.get("credential.allServers") + "]";
                    String envName = environmentManager != null
                        ? environmentManager.getDisplayName(cred.getEnvironmentId())
                        : (cred.getEnvironment() != null ? cred.getEnvironment().getDisplayName() : cred.getEnvironmentId());
                    setText(String.format("%s - %s @ %s%s",
                        cred.getName(),
                        cred.getUsername(),
                        envName,
                        serverInfo));
                }
            }
        });
        
        refreshCredentialList();
        
        HBox buttonBox = new HBox(10);
        Button addButton = new Button(I18n.get("dialog.add"));
        Button editButton = new Button(I18n.get("dialog.edit"));
        Button removeButton = new Button(I18n.get("dialog.delete"));
        Button environmentsButton = new Button(I18n.get("credential.environments.button"));
        
        addButton.setOnAction(e -> addCredential());
        editButton.setOnAction(e -> editCredential());
        removeButton.setOnAction(e -> removeCredential());
        environmentsButton.setOnAction(e -> showEnvironments());
        
        editButton.disableProperty().bind(credentialListView.getSelectionModel().selectedItemProperty().isNull());
        removeButton.disableProperty().bind(credentialListView.getSelectionModel().selectedItemProperty().isNull());
        
        buttonBox.getChildren().addAll(addButton, editButton, removeButton, environmentsButton);
        
        Label infoLabel = new Label(I18n.get("credential.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        
        content.getChildren().addAll(listLabel, credentialListView, buttonBox, infoLabel);
        VBox.setVgrow(credentialListView, Priority.ALWAYS);
        
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                try {
                    credentialManager.save();
                    return true;
                } catch (Exception e) {
                    logger.error("Failed to save credentials", e);
                    showError(I18n.get("error.saveFailed"), I18n.get("error.title") + ": " + e.getMessage());
                    return false;
                }
            }
            return false;
        });
    }
    
    private void refreshCredentialList() {
        credentialListView.getItems().setAll(credentialManager.getAllCredentials());
    }
    
    private void showEnvironments() {
        if (environmentManager == null) return;
        EnvironmentManagementDialog dialog = new EnvironmentManagementDialog(environmentManager, credentialManager);
        dialog.initOwner(getDialogPane().getScene().getWindow());
        dialog.showAndWait();
    }

    private void addCredential() {
        CredentialEditDialog dialog = new CredentialEditDialog(null, credentialManager, environmentManager, masterPassword);
        dialog.initOwner(getDialogPane().getScene().getWindow());
        dialog.showAndWait().ifPresent(result -> {
            credentialManager.addCredential(result.credential);
            saveCredentialSecrets(result);
            refreshCredentialList();
        });
    }
    
    private void editCredential() {
        StoredCredential selected = credentialListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            CredentialEditDialog dialog = new CredentialEditDialog(selected, credentialManager, environmentManager, masterPassword);
            dialog.initOwner(getDialogPane().getScene().getWindow());
            dialog.showAndWait().ifPresent(result -> {
                credentialManager.updateCredential(result.credential);
                saveCredentialSecrets(result);
                refreshCredentialList();
            });
        }
    }
    
    /**
     * Encrypts and stores password or external command based on credential result.
     */
    private void saveCredentialSecrets(CredentialResult result) {
        try {
            if (result.password != null) {
                credentialManager.setPassword(result.credential, result.password, masterPassword);
            }
            if (result.externalCommand != null) {
                credentialManager.setExternalCommand(result.credential, result.externalCommand, masterPassword);
            }
        } catch (Exception e) {
            logger.error("Failed to encrypt credential secrets", e);
            showError(I18n.get("error.title"), I18n.get("credential.passwordEncryptFailed", e.getMessage()));
        }
    }
    
    private void removeCredential() {
        StoredCredential selected = credentialListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(getDialogPane().getScene().getWindow());
            confirm.setTitle(I18n.get("credential.deleteConfirm.title"));
            confirm.setHeaderText(I18n.get("credential.deleteConfirm.header"));
            confirm.setContentText(I18n.get("credential.deleteConfirm.content", selected.getName()));
            
            confirm.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    credentialManager.removeCredential(selected);
                    refreshCredentialList();
                }
            });
        }
    }
    
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
