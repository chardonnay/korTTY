package de.kortty.ui;

import de.kortty.core.CredentialManager;
import de.kortty.model.StoredCredential;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dialog for managing stored credentials.
 */
public class CredentialManagementDialog extends Dialog<Boolean> {
    
    private static final Logger logger = LoggerFactory.getLogger(CredentialManagementDialog.class);
    
    private final CredentialManager credentialManager;
    private final char[] masterPassword;
    private final ListView<StoredCredential> credentialListView;
    
    public CredentialManagementDialog(CredentialManager credentialManager, char[] masterPassword) {
        this.credentialManager = credentialManager;
        this.masterPassword = masterPassword;
        this.credentialListView = new ListView<>();
        
        setTitle("Zugangsdaten-Verwaltung");
        setHeaderText("Verwalten Sie umgebungsspezifische Zugangsdaten");
        
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setPrefWidth(750);
        content.setPrefHeight(550);
        
        Label listLabel = new Label("Gespeicherte Zugangsdaten:");
        
        credentialListView.setCellFactory(lv -> new ListCell<StoredCredential>() {
            @Override
            protected void updateItem(StoredCredential cred, boolean empty) {
                super.updateItem(cred, empty);
                if (empty || cred == null) {
                    setText(null);
                } else {
                    String serverInfo = cred.getServerPattern() != null ? 
                        " [" + cred.getServerPattern() + "]" : " [Alle Server]";
                    setText(String.format("%s - %s @ %s%s",
                        cred.getName(),
                        cred.getUsername(),
                        cred.getEnvironment().getDisplayName(),
                        serverInfo));
                }
            }
        });
        
        refreshCredentialList();
        
        HBox buttonBox = new HBox(10);
        Button addButton = new Button("Hinzufügen");
        Button editButton = new Button("Bearbeiten");
        Button removeButton = new Button("Entfernen");
        
        addButton.setOnAction(e -> addCredential());
        editButton.setOnAction(e -> editCredential());
        removeButton.setOnAction(e -> removeCredential());
        
        editButton.disableProperty().bind(credentialListView.getSelectionModel().selectedItemProperty().isNull());
        removeButton.disableProperty().bind(credentialListView.getSelectionModel().selectedItemProperty().isNull());
        
        buttonBox.getChildren().addAll(addButton, editButton, removeButton);
        
        Label infoLabel = new Label(
            "Zugangsdaten können umgebungsspezifisch (Produktion, Entwicklung, Test, Staging) und\n" +
            "server-spezifisch (über Muster wie '*.example.com' oder '10.0.0.*') hinterlegt werden.\n" +
            "Diese können später in Verbindungseinstellungen ausgewählt werden."
        );
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
                    showError("Fehler beim Speichern", "Zugangsdaten konnten nicht gespeichert werden: " + e.getMessage());
                    return false;
                }
            }
            return false;
        });
    }
    
    private void refreshCredentialList() {
        credentialListView.getItems().setAll(credentialManager.getAllCredentials());
    }
    
    private void addCredential() {
        CredentialEditDialog dialog = new CredentialEditDialog(null);
        dialog.showAndWait().ifPresent(result -> {
            credentialManager.addCredential(result.credential);
            if (result.password != null) {
                try {
                    credentialManager.setPassword(result.credential, result.password, masterPassword);
                } catch (Exception e) {
                    logger.error("Failed to encrypt password", e);
                    showError("Fehler", "Passwort konnte nicht verschlüsselt werden: " + e.getMessage());
                }
            }
            refreshCredentialList();
        });
    }
    
    private void editCredential() {
        StoredCredential selected = credentialListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            CredentialEditDialog dialog = new CredentialEditDialog(selected);
            dialog.showAndWait().ifPresent(result -> {
                credentialManager.updateCredential(result.credential);
                if (result.password != null) {
                    try {
                        credentialManager.setPassword(result.credential, result.password, masterPassword);
                    } catch (Exception e) {
                        logger.error("Failed to encrypt password", e);
                        showError("Fehler", "Passwort konnte nicht verschlüsselt werden: " + e.getMessage());
                    }
                }
                refreshCredentialList();
            });
        }
    }
    
    private void removeCredential() {
        StoredCredential selected = credentialListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Löschen bestätigen");
            confirm.setHeaderText("Zugangsdaten löschen");
            confirm.setContentText("Möchten Sie '" + selected.getName() + "' wirklich löschen?");
            
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
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
