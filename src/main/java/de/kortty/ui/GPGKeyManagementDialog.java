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
public class GPGKeyManagementDialog extends Dialog<Boolean> {
    
    private static final Logger logger = LoggerFactory.getLogger(GPGKeyManagementDialog.class);
    
    private final GPGKeyManager keyManager;
    private final ListView<GPGKey> keyListView;
    
    public GPGKeyManagementDialog(GPGKeyManager keyManager) {
        this.keyManager = keyManager;
        this.keyListView = new ListView<>();
        
        setTitle("GPG-Schlüsselverwaltung");
        setHeaderText("Verwalten Sie Ihre GPG-Schlüssel für verschlüsselte Exporte");
        
        // Create UI
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setPrefWidth(700);
        content.setPrefHeight(500);
        
        // Key list
        Label listLabel = new Label("Gespeicherte GPG-Schlüssel:");
        
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
        Button addButton = new Button("Hinzufügen");
        Button editButton = new Button("Bearbeiten");
        Button removeButton = new Button("Entfernen");
        Button importButton = new Button("Aus GPG importieren");
        
        addButton.setOnAction(e -> addKey());
        editButton.setOnAction(e -> editKey());
        removeButton.setOnAction(e -> removeKey());
        importButton.setOnAction(e -> importFromGPG());
        
        editButton.disableProperty().bind(keyListView.getSelectionModel().selectedItemProperty().isNull());
        removeButton.disableProperty().bind(keyListView.getSelectionModel().selectedItemProperty().isNull());
        
        buttonBox.getChildren().addAll(addButton, editButton, removeButton, importButton);
        
        // Info text
        Label infoLabel = new Label(
            "GPG-Schlüssel werden für die Verschlüsselung von Exporten verwendet.\n" +
            "Sie können Schlüssel manuell hinzufügen oder aus Ihrer GPG-Installation importieren."
        );
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
                    showError("Fehler beim Speichern", "GPG-Schlüssel konnten nicht gespeichert werden: " + e.getMessage());
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
        dialog.showAndWait().ifPresent(key -> {
            keyManager.addKey(key);
            refreshKeyList();
        });
    }
    
    private void editKey() {
        GPGKey selected = keyListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            GPGKeyEditDialog dialog = new GPGKeyEditDialog(selected);
            dialog.showAndWait().ifPresent(key -> {
                keyManager.updateKey(key);
                refreshKeyList();
            });
        }
    }
    
    private void removeKey() {
        GPGKey selected = keyListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Löschen bestätigen");
            confirm.setHeaderText("GPG-Schlüssel löschen");
            confirm.setContentText("Möchten Sie den Schlüssel '" + selected.getName() + "' wirklich löschen?");
            
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
                showInfo("Keine Schlüssel gefunden", "Es wurden keine GPG-Schlüssel auf Ihrem System gefunden.");
            } else {
                // Show selection dialog
                ChoiceDialog<GPGKey> dialog = new ChoiceDialog<>(importedKeys.get(0), importedKeys);
                dialog.setTitle("GPG-Schlüssel importieren");
                dialog.setHeaderText("Wählen Sie einen Schlüssel zum Importieren");
                dialog.setContentText("Schlüssel:");
                
                dialog.showAndWait().ifPresent(selectedKey -> {
                    keyManager.addKey(selectedKey);
                    refreshKeyList();
                    showInfo("Import erfolgreich", "Schlüssel wurde erfolgreich importiert.");
                });
            }
            
        } catch (Exception e) {
            logger.error("Failed to import GPG keys", e);
            showError("Import fehlgeschlagen", 
                "GPG-Schlüssel konnten nicht importiert werden.\n" +
                "Stellen Sie sicher, dass GPG installiert ist.\n\n" +
                "Fehler: " + e.getMessage());
        }
    }
    
    private void showError(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
    
    private void showInfo(String title, String message) {
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
    }
}
