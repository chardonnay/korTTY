package de.kortty.ui;

import de.kortty.core.SSHKeyManager;
import de.kortty.model.SSHKey;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.FileChooser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Dialog for managing SSH keys.
 */
public class SSHKeyManagementDialog extends Dialog<Boolean> {
    
    private static final Logger logger = LoggerFactory.getLogger(SSHKeyManagementDialog.class);
    
    private final SSHKeyManager sshKeyManager;
    private final char[] masterPassword;
    private final ListView<SSHKey> keyListView;
    private final TextField searchField;
    private final ObservableList<SSHKey> keys;
    private final FilteredList<SSHKey> filteredKeys;
    
    public SSHKeyManagementDialog(SSHKeyManager sshKeyManager, char[] masterPassword) {
        this.sshKeyManager = sshKeyManager;
        this.masterPassword = masterPassword;
        
        setTitle("SSH-Key-Verwaltung");
        setHeaderText("Verwalten Sie Ihre privaten SSH-Keys");
        
        VBox content = new VBox(15);
        content.setPadding(new Insets(20));
        content.setPrefWidth(800);
        content.setPrefHeight(600);
        
        // Search field
        HBox searchBox = new HBox(10);
        searchField = new TextField();
        searchField.setPromptText("Suchen nach Name oder Pfad... (* als Wildcard)");
        searchField.setPrefWidth(300);
        Label searchLabel = new Label("Suchen:");
        searchBox.getChildren().addAll(searchLabel, searchField);
        HBox.setHgrow(searchField, Priority.ALWAYS);
        
        keys = FXCollections.observableArrayList(sshKeyManager.getAllKeys());
        filteredKeys = new FilteredList<>(keys, p -> true);
        
        // Search filter with glob pattern support
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal == null || newVal.trim().isEmpty()) {
                filteredKeys.setPredicate(p -> true);
            } else {
                String searchText = newVal.trim();
                boolean useGlobPattern = searchText.contains("*");
                
                java.util.regex.Pattern pattern = null;
                if (useGlobPattern) {
                    String regexPattern = searchText
                        .replace("\\", "\\\\")
                        .replace(".", "\\.")
                        .replace("+", "\\+")
                        .replace("?", "\\?")
                        .replace("^", "\\^")
                        .replace("$", "\\$")
                        .replace("|", "\\|")
                        .replace("(", "\\(")
                        .replace(")", "\\)")
                        .replace("[", "\\[")
                        .replace("]", "\\]")
                        .replace("{", "\\{")
                        .replace("}", "\\}")
                        .replace("*", ".*");
                    
                    try {
                        pattern = java.util.regex.Pattern.compile(regexPattern, 
                            java.util.regex.Pattern.CASE_INSENSITIVE);
                    } catch (java.util.regex.PatternSyntaxException e) {
                        pattern = null;
                    }
                }
                
                final java.util.regex.Pattern finalPattern = pattern;
                final boolean usePattern = useGlobPattern && pattern != null;
                final String lowerSearchText = searchText.toLowerCase();
                
                filteredKeys.setPredicate(key -> {
                    if (key.getName() != null) {
                        String name = key.getName();
                        if (usePattern) {
                            if (finalPattern.matcher(name).matches()) {
                                return true;
                            }
                        } else {
                            if (name.toLowerCase().contains(lowerSearchText)) {
                                return true;
                            }
                        }
                    }
                    if (key.getKeyPath() != null) {
                        String path = key.getKeyPath();
                        if (usePattern) {
                            if (finalPattern.matcher(path).matches()) {
                                return true;
                            }
                        } else {
                            if (path.toLowerCase().contains(lowerSearchText)) {
                                return true;
                            }
                        }
                    }
                    return false;
                });
            }
        });
        
        Label listLabel = new Label("Gespeicherte SSH-Keys:");
        
        keyListView = new ListView<>(filteredKeys);
        keyListView.setCellFactory(lv -> new ListCell<SSHKey>() {
            @Override
            protected void updateItem(SSHKey key, boolean empty) {
                super.updateItem(key, empty);
                if (empty || key == null) {
                    setText(null);
                } else {
                    String pathInfo = key.isCopiedToUserDir() ? 
                        " [Kopiert]" : " [" + key.getKeyPath() + "]";
                    String desc = key.getDescription() != null && !key.getDescription().isEmpty() ?
                        " - " + key.getDescription() : "";
                    setText(key.getName() + desc + pathInfo);
                }
            }
        });
        
        HBox buttonBox = new HBox(10);
        Button addButton = new Button("Hinzufügen");
        Button editButton = new Button("Bearbeiten");
        Button removeButton = new Button("Entfernen");
        Button copyButton = new Button("Ins User-Verzeichnis kopieren");
        
        addButton.setOnAction(e -> addKey());
        editButton.setOnAction(e -> editKey());
        removeButton.setOnAction(e -> removeKey());
        copyButton.setOnAction(e -> copyKeyToUserDir());
        
        editButton.disableProperty().bind(keyListView.getSelectionModel().selectedItemProperty().isNull());
        removeButton.disableProperty().bind(keyListView.getSelectionModel().selectedItemProperty().isNull());
        copyButton.disableProperty().bind(keyListView.getSelectionModel().selectedItemProperty().isNull());
        
        buttonBox.getChildren().addAll(addButton, editButton, removeButton, copyButton);
        
        Label infoLabel = new Label(
            "Verwalten Sie Ihre privaten SSH-Keys. Sie können Keys ins KorTTY User-Verzeichnis kopieren,\n" +
            "damit sie bei einem Umzug auf einen anderen Computer mitgenommen werden können.\n" +
            "Passphrases werden verschlüsselt gespeichert und müssen nicht bei jeder Verbindung eingegeben werden."
        );
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        
        content.getChildren().addAll(searchBox, listLabel, keyListView, buttonBox, infoLabel);
        VBox.setVgrow(keyListView, Priority.ALWAYS);
        
        getDialogPane().setContent(content);
        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        
        setResultConverter(buttonType -> {
            if (buttonType == ButtonType.OK) {
                try {
                    sshKeyManager.save();
                    return true;
                } catch (Exception e) {
                    logger.error("Failed to save SSH keys", e);
                    showError("Fehler beim Speichern", "SSH-Keys konnten nicht gespeichert werden: " + e.getMessage());
                    return false;
                }
            }
            return false;
        });
    }
    
    private void refreshKeyList() {
        keys.setAll(sshKeyManager.getAllKeys());
    }
    
    private void addKey() {
        SSHKeyEditDialog dialog = new SSHKeyEditDialog(null, sshKeyManager, masterPassword);
        dialog.showAndWait().ifPresent(result -> {
            sshKeyManager.addKey(result.key);
            if (result.passphrase != null) {
                try {
                    sshKeyManager.setPassphrase(result.key, result.passphrase, masterPassword);
                } catch (Exception e) {
                    logger.error("Failed to encrypt passphrase", e);
                    showError("Fehler", "Passphrase konnte nicht verschlüsselt werden: " + e.getMessage());
                }
            }
            refreshKeyList();
        });
    }
    
    private void editKey() {
        SSHKey selected = keyListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            SSHKeyEditDialog dialog = new SSHKeyEditDialog(selected, sshKeyManager, masterPassword);
            dialog.showAndWait().ifPresent(result -> {
                sshKeyManager.updateKey(result.key);
                if (result.passphrase != null) {
                    try {
                        sshKeyManager.setPassphrase(result.key, result.passphrase, masterPassword);
                    } catch (Exception e) {
                        logger.error("Failed to encrypt passphrase", e);
                        showError("Fehler", "Passphrase konnte nicht verschlüsselt werden: " + e.getMessage());
                    }
                }
                refreshKeyList();
            });
        }
    }
    
    private void removeKey() {
        SSHKey selected = keyListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle("Löschen bestätigen");
            confirm.setHeaderText("SSH-Key löschen");
            confirm.setContentText("Möchten Sie '" + selected.getName() + "' wirklich löschen?");
            
            confirm.showAndWait().ifPresent(response -> {
                if (response == ButtonType.OK) {
                    sshKeyManager.removeKey(selected);
                    refreshKeyList();
                }
            });
        }
    }
    
    private void copyKeyToUserDir() {
        SSHKey selected = keyListView.getSelectionModel().getSelectedItem();
        if (selected != null) {
            try {
                sshKeyManager.copyKeyToUserDir(selected);
                sshKeyManager.save();
                refreshKeyList();
                showInfo("Erfolg", "SSH-Key wurde ins User-Verzeichnis kopiert.");
            } catch (Exception e) {
                logger.error("Failed to copy key to user directory", e);
                showError("Fehler", "Key konnte nicht kopiert werden: " + e.getMessage());
            }
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
    
    /**
     * Result class for SSHKeyEditDialog
     */
    public static class SSHKeyEditResult {
        public final SSHKey key;
        public final String passphrase;
        
        public SSHKeyEditResult(SSHKey key, String passphrase) {
            this.key = key;
            this.passphrase = passphrase;
        }
    }
    
    /**
     * Dialog for editing SSH keys
     */
    public static class SSHKeyEditDialog extends Dialog<SSHKeyEditResult> {
        
        private final SSHKeyManager sshKeyManager;
        private final char[] masterPassword;
        private TextField nameField;
        private TextField keyPathField;
        private PasswordField passphraseField;
        private TextArea descriptionArea;
        private Button browseButton;
        
        public SSHKeyEditDialog(SSHKey existingKey, SSHKeyManager sshKeyManager, char[] masterPassword) {
            this.sshKeyManager = sshKeyManager;
            this.masterPassword = masterPassword;
            
            setTitle(existingKey == null ? "SSH-Key hinzufügen" : "SSH-Key bearbeiten");
            setHeaderText(existingKey == null ? "Neuen SSH-Key hinzufügen" : "SSH-Key bearbeiten");
            
            GridPane grid = new GridPane();
            grid.setHgap(10);
            grid.setVgap(10);
            grid.setPadding(new Insets(20));
            grid.setPrefWidth(650);
            
            ColumnConstraints labelColumn = new ColumnConstraints();
            labelColumn.setMinWidth(130);
            labelColumn.setPrefWidth(130);
            labelColumn.setHgrow(Priority.NEVER);
            
            ColumnConstraints inputColumn = new ColumnConstraints();
            inputColumn.setHgrow(Priority.ALWAYS);
            
            grid.getColumnConstraints().addAll(labelColumn, inputColumn);
            
            int row = 0;
            
            nameField = new TextField(existingKey != null ? existingKey.getName() : "");
            nameField.setPromptText("Name für diesen Key");
            grid.add(new Label("Name:"), 0, row);
            grid.add(nameField, 1, row++);
            
            keyPathField = new TextField(existingKey != null ? existingKey.getKeyPath() : "");
            keyPathField.setPromptText("Pfad zum privaten SSH-Key");
            browseButton = new Button("...");
            browseButton.setOnAction(e -> browseForKey());
            HBox keyPathBox = new HBox(5, keyPathField, browseButton);
            HBox.setHgrow(keyPathField, Priority.ALWAYS);
            grid.add(new Label("Key-Pfad:"), 0, row);
            grid.add(keyPathBox, 1, row++);
            
            passphraseField = new PasswordField();
            passphraseField.setPromptText("Passphrase (optional)");
            if (existingKey != null && existingKey.getEncryptedPassphrase() != null) {
                try {
                    String passphrase = sshKeyManager.getPassphrase(existingKey, masterPassword);
                    if (passphrase != null) {
                        passphraseField.setText(passphrase);
                    }
                } catch (Exception e) {
                    logger.error("Failed to decrypt passphrase", e);
                }
            }
            grid.add(new Label("Passphrase:"), 0, row);
            grid.add(passphraseField, 1, row++);
            
            descriptionArea = new TextArea(existingKey != null ? existingKey.getDescription() : "");
            descriptionArea.setPromptText("Beschreibung (optional)");
            descriptionArea.setPrefRowCount(3);
            grid.add(new Label("Beschreibung:"), 0, row);
            grid.add(descriptionArea, 1, row++);
            
            getDialogPane().setContent(grid);
            getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
            
            setResultConverter(buttonType -> {
                if (buttonType == ButtonType.OK) {
                    if (nameField.getText().trim().isEmpty()) {
                        showError("Fehler", "Name ist erforderlich");
                        return null;
                    }
                    if (keyPathField.getText().trim().isEmpty()) {
                        showError("Fehler", "Key-Pfad ist erforderlich");
                        return null;
                    }
                    
                    SSHKey key = existingKey != null ? existingKey : new SSHKey();
                    key.setName(nameField.getText().trim());
                    key.setKeyPath(keyPathField.getText().trim());
                    key.setDescription(descriptionArea.getText().trim());
                    
                    String passphrase = passphraseField.getText();
                    if (passphrase.isEmpty()) {
                        passphrase = null;
                    }
                    
                    return new SSHKeyEditResult(key, passphrase);
                }
                return null;
            });
        }
        
        private void browseForKey() {
            FileChooser fileChooser = new FileChooser();
            fileChooser.setTitle("Privaten SSH-Key auswählen");
            File sshDir = new File(System.getProperty("user.home"), ".ssh");
            if (sshDir.exists()) {
                fileChooser.setInitialDirectory(sshDir);
            }
            File selected = fileChooser.showOpenDialog(getDialogPane().getScene().getWindow());
            if (selected != null) {
                keyPathField.setText(selected.getAbsolutePath());
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
}
