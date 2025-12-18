package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.model.StoredCredential;
import de.kortty.core.CredentialManager;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;

/**
 * Dialog for importing connections with options.
 */
public class ConnectionImportDialog extends Dialog<ConnectionImportDialog.ImportResult> {
    
    private final Stage owner;
    private final CredentialManager credentialManager;
    
    private final CheckBox importUsernameCheck;
    private final CheckBox importPasswordCheck;
    private final CheckBox importTunnelsCheck;
    private final CheckBox importJumpServerCheck;
    private final CheckBox replaceCredentialsCheck;
    private final ComboBox<StoredCredential> credentialCombo;
    private final TextField importPathField;
    private File selectedFile;
    
    public static class ImportResult {
        public final File importFile;
        public final boolean importUsername;
        public final boolean importPassword;
        public final boolean importTunnels;
        public final boolean importJumpServer;
        public final boolean replaceCredentials;
        public final StoredCredential replacementCredential;
        
        public ImportResult(File importFile,
                          boolean importUsername, boolean importPassword,
                          boolean importTunnels, boolean importJumpServer,
                          boolean replaceCredentials, StoredCredential replacementCredential) {
            this.importFile = importFile;
            this.importUsername = importUsername;
            this.importPassword = importPassword;
            this.importTunnels = importTunnels;
            this.importJumpServer = importJumpServer;
            this.replaceCredentials = replaceCredentials;
            this.replacementCredential = replacementCredential;
        }
    }
    
    public ConnectionImportDialog(Stage owner, CredentialManager credentialManager) {
        this.owner = owner;
        this.credentialManager = credentialManager;
        
        setTitle("Verbindungen importieren");
        setHeaderText("Verbindungen aus Datei importieren");
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(false);
        
        // Create form
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        int row = 0;
        
        // Import path
        Label pathLabel = new Label("Import-Datei:");
        importPathField = new TextField();
        importPathField.setEditable(false);
        importPathField.setPrefWidth(300);
        importPathField.setPromptText("Datei auswählen...");
        
        Button browseButton = new Button("Durchsuchen...");
        browseButton.setOnAction(e -> selectImportFile());
        
        grid.add(pathLabel, 0, row);
        grid.add(importPathField, 1, row);
        grid.add(browseButton, 2, row++);
        
        // Section: Import options
        Label importHeader = new Label("Import-Optionen:");
        importHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        grid.add(importHeader, 0, row++, 3, 1);
        
        importUsernameCheck = new CheckBox("Username importieren");
        importUsernameCheck.setSelected(true);
        grid.add(importUsernameCheck, 0, row++, 3, 1);
        
        importPasswordCheck = new CheckBox("Passwort importieren");
        importPasswordCheck.setSelected(true);
        grid.add(importPasswordCheck, 0, row++, 3, 1);
        
        importTunnelsCheck = new CheckBox("SSH-Tunnel importieren");
        importTunnelsCheck.setSelected(true);
        grid.add(importTunnelsCheck, 0, row++, 3, 1);
        
        importJumpServerCheck = new CheckBox("Jump Server importieren");
        importJumpServerCheck.setSelected(true);
        grid.add(importJumpServerCheck, 0, row++, 3, 1);
        
        // Section: Credential replacement
        Label credentialHeader = new Label("Zugangsdaten ersetzen:");
        credentialHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        grid.add(credentialHeader, 0, row++, 3, 1);
        
        replaceCredentialsCheck = new CheckBox("Username & Passwort durch gespeicherte Zugangsdaten ersetzen");
        replaceCredentialsCheck.setSelected(false);
        grid.add(replaceCredentialsCheck, 0, row++, 3, 1);
        
        Label credentialLabel = new Label("  Gespeicherte Zugangsdaten:");
        credentialCombo = new ComboBox<>();
        credentialCombo.setPromptText("Zugangsdaten auswählen...");
        credentialCombo.setPrefWidth(300);
        credentialCombo.setDisable(true);
        
        credentialCombo.setCellFactory(lv -> new ListCell<StoredCredential>() {
            @Override
            protected void updateItem(StoredCredential item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getUsername() + ")");
            }
        });
        credentialCombo.setButtonCell(new ListCell<StoredCredential>() {
            @Override
            protected void updateItem(StoredCredential item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getUsername() + ")");
            }
        });
        
        if (credentialManager != null) {
            credentialCombo.getItems().addAll(credentialManager.getAllCredentials());
        }
        
        grid.add(credentialLabel, 0, row);
        grid.add(credentialCombo, 1, row++, 2, 1);
        
        Label replaceInfo = new Label("(Überschreibt Username & Passwort aller importierten Verbindungen)");
        replaceInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        grid.add(replaceInfo, 0, row++, 3, 1);
        
        // Dynamic enable/disable
        replaceCredentialsCheck.selectedProperty().addListener((obs, old, selected) -> {
            credentialCombo.setDisable(!selected);
        });
        
        VBox content = new VBox(grid);
        getDialogPane().setContent(content);
        
        // Buttons
        ButtonType importButtonType = new ButtonType("Importieren", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(importButtonType, ButtonType.CANCEL);
        
        Button importButton = (Button) getDialogPane().lookupButton(importButtonType);
        importButton.setDisable(true);
        
        // Enable import button only when file is selected
        importPathField.textProperty().addListener((obs, old, newVal) -> {
            importButton.setDisable(newVal == null || newVal.trim().isEmpty());
        });
        
        // Result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == importButtonType && selectedFile != null) {
                // Validate if replace credentials is selected
                if (replaceCredentialsCheck.isSelected() && credentialCombo.getValue() == null) {
                    Alert alert = new Alert(Alert.AlertType.WARNING);
                    alert.setTitle("Warnung");
                    alert.setHeaderText("Keine Zugangsdaten ausgewählt");
                    alert.setContentText("Bitte wählen Sie Zugangsdaten aus oder deaktivieren Sie die Option.");
                    alert.showAndWait();
                    return null;
                }
                
                return new ImportResult(
                    selectedFile,
                    importUsernameCheck.isSelected(),
                    importPasswordCheck.isSelected(),
                    importTunnelsCheck.isSelected(),
                    importJumpServerCheck.isSelected(),
                    replaceCredentialsCheck.isSelected(),
                    credentialCombo.getValue()
                );
            }
            return null;
        });
    }
    
    private void selectImportFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Import-Datei auswählen");
        fileChooser.getExtensionFilters().addAll(
            new FileChooser.ExtensionFilter("XML-Dateien", "*.xml"),
            new FileChooser.ExtensionFilter("Alle Dateien", "*.*")
        );
        
        selectedFile = fileChooser.showOpenDialog(owner);
        if (selectedFile != null) {
            importPathField.setText(selectedFile.getAbsolutePath());
        }
    }
}
