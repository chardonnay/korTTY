package de.kortty.ui;

import de.kortty.core.GPGKeyManager;
import de.kortty.model.GPGKey;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * Dialog for managing GPG keys.
 */
public class GPGKeyManagementDialog extends ThemeAwareDialog<Boolean> {
    
    private static final Logger logger = LoggerFactory.getLogger(GPGKeyManagementDialog.class);
    
    private final GPGKeyManager keyManager;
    private final ListView<GPGKey> keyListView;
    
    public GPGKeyManagementDialog(GPGKeyManager keyManager) {
        this.keyManager = keyManager;
        this.keyListView = new ListView<>();
        
        setTitle(I18n.get("gpg.title"));
        setHeaderText(I18n.get("gpg.header"));
        
        // Create UI
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setPrefWidth(700);
        content.setPrefHeight(500);
        
        // Key list
        Label listLabel = new Label(I18n.get("gpg.storedKeys"));
        
        keyListView.setCellFactory(lv -> new ListCell<GPGKey>() {
            @Override
            protected void updateItem(GPGKey key, boolean empty) {
                super.updateItem(key, empty);
                if (empty || key == null) {
                    setText(null);
                } else {
                    setText(String.format("%s - Key-ID: %s%s",
                        key.getName(),
                        key.getKeyId(),
                        key.getEmail() != null ? " (" + key.getEmail() + ")" : ""));
                }
            }
        });
        
        refreshKeyList();
        
        // Buttons
        HBox buttonBox = new HBox(10);
        Button addButton = new Button(I18n.get("gpg.add"));
        Button editButton = new Button(I18n.get("dialog.edit"));
        Button removeButton = new Button(I18n.get("dialog.delete"));
        Button importButton = new Button(I18n.get("gpg.import"));
        
        addButton.setOnAction(e -> addKey());
        editButton.setOnAction(e -> editKey());
        removeButton.setOnAction(e -> removeKey());
        importButton.setOnAction(e -> importFromGPG());
        
        editButton.disableProperty().bind(keyListView.getSelectionModel().selectedItemProperty().isNull());
        removeButton.disableProperty().bind(keyListView.getSelectionModel().selectedItemProperty().isNull());
        
        buttonBox.getChildren().addAll(addButton, editButton, removeButton, importButton);
        
        // Info text
        Label infoLabel = new Label(I18n.get("gpg.info"));
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        
        content.getChildren().addAll(listLabel, keyListView, buttonBox, infoLabel);
        VBox.setVgrow(keyListView, Priority.ALWAYS);
        
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                try {
                    keyManager.save();
                    return true;
                } catch (Exception e) {
                    logger.error("Failed to save GPG keys", e);
                    showError(I18n.get("error.saveFailed"), "GPG keys could not be saved: " + e.getMessage());
                    return false;
                }
            }
            return false;
        });
    }
    
    private void refreshKeyList() {
        keyListView.getItems().setAll(keyManager.getAllKeys());
    }
    
    private void addKey() {
        GPGKeyEditDialog dialog = new GPGKeyEditDialog(null);
        dialog.initOwner(getDialogPane().getScene().getWindow());
        dialog.showAndWait().ifPresent(key -> {
            keyManager.addKey(key);
            refreshKeyList();
            de.kortty.telemetry.Telemetry.track(de.kortty.telemetry.TelemetryEvents.SECURITY_ENTRY_CHANGED,
                java.util.Map.of("manager", "gpg_keys", "op", "add", "via", "manual"));
        });
    }
    
    private void editKey() {
        GPGKey selected = keyListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            GPGKeyEditDialog dialog = new GPGKeyEditDialog(selected);
            dialog.initOwner(getDialogPane().getScene().getWindow());
            dialog.showAndWait().ifPresent(key -> {
                keyManager.updateKey(key);
                refreshKeyList();
                de.kortty.telemetry.Telemetry.track(de.kortty.telemetry.TelemetryEvents.SECURITY_ENTRY_CHANGED,
                    java.util.Map.of("manager", "gpg_keys", "op", "edit", "via", "manual"));
            });
        }
    }
    
    private void removeKey() {
        GPGKey selected = keyListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.initOwner(getDialogPane().getScene().getWindow());
            confirm.setTitle(I18n.get("gpg.deleteConfirm.title"));
            confirm.setHeaderText(I18n.get("gpg.deleteConfirm.header"));
            confirm.setContentText(I18n.get("gpg.deleteConfirm.content", selected.getName()));
            
            confirm.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    keyManager.removeKey(selected);
                    refreshKeyList();
                }
            });
        }
    }
    
    private void importFromGPG() {
        try {
            // Try to list GPG keys from system
            Process process = Runtime.getRuntime().exec(new String[]{"gpg", "--list-keys", "--with-colons"});
            BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
            
            List<GPGKey> importedKeys = new ArrayList<>();
            String line;
            String currentKeyId = null;
            String currentEmail = null;
            String currentFingerprint = null;
            
            while ((line = reader.readLine()) != null) {
                String[] parts = line.split(":");
                if (parts[0].equals("pub")) {
                    currentKeyId = parts[4];
                } else if (parts[0].equals("fpr")) {
                    currentFingerprint = parts[9];
                } else if (parts[0].equals("uid") && currentKeyId != null) {
                    currentEmail = parts[9];
                    // Extract email from UID
                    if (currentEmail.contains("<") && currentEmail.contains(">")) {
                        int start = currentEmail.indexOf("<") + 1;
                        int end = currentEmail.indexOf(">");
                        currentEmail = currentEmail.substring(start, end);
                    }
                    
                    // Create key
                    GPGKey key = new GPGKey("GPG Key " + currentKeyId.substring(0, Math.min(8, currentKeyId.length())), currentKeyId);
                    key.setEmail(currentEmail);
                    key.setFingerprint(currentFingerprint);
                    importedKeys.add(key);
                    
                    currentKeyId = null;
                    currentEmail = null;
                    currentFingerprint = null;
                }
            }
            
            process.waitFor();
            
            if (importedKeys.isEmpty()) {
                showInfo(I18n.get("gpg.importNoKeys"), I18n.get("gpg.importNoKeysMessage"));
            } else {
                // Show selection dialog
                ChoiceDialog<GPGKey> dialog = new ChoiceDialog<>(importedKeys.get(0), importedKeys);
                dialog.initOwner(getDialogPane().getScene().getWindow());
                dialog.setTitle(I18n.get("gpg.import.title"));
                dialog.setHeaderText(I18n.get("gpg.import.header"));
                dialog.setContentText(I18n.get("gpg.import.content"));
                
                dialog.showAndWait().ifPresent(selectedKey -> {
                    keyManager.addKey(selectedKey);
                    refreshKeyList();
                    showInfo(I18n.get("gpg.import.successful"), I18n.get("gpg.import.successfulMessage"));
                    de.kortty.telemetry.Telemetry.track(de.kortty.telemetry.TelemetryEvents.SECURITY_ENTRY_CHANGED,
                        java.util.Map.of("manager", "gpg_keys", "op", "add", "via", "gpg_import"));
                });
            }
            
        } catch (Exception e) {
            logger.error("Failed to import GPG keys", e);
            showError(I18n.get("error.importFailed"), 
                I18n.get("gpg.import.failed", 
                "Please ensure GPG is installed.\n\n" +
                "Error: " + e.getMessage()));
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
    
    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.initOwner(getDialogPane().getScene().getWindow());
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
