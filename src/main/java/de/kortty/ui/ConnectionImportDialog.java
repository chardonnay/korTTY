package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.model.StoredCredential;
import de.kortty.model.SSHKey;
import de.kortty.core.CredentialManager;
import de.kortty.core.SSHKeyManager;
import de.kortty.core.ConfigurationManager;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.io.File;
import java.util.List;
import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Dialog for importing connections with options.
 */
public class ConnectionImportDialog extends Dialog<ConnectionImportDialog.ImportResult> {
    
    private final Stage owner;
    private final CredentialManager credentialManager;
    private final SSHKeyManager sshKeyManager;
    private final ConfigurationManager configManager;
    
    private final CheckBox importUsernameCheck;
    private final CheckBox importPasswordCheck;
    private final CheckBox importTunnelsCheck;
    private final CheckBox importJumpServerCheck;
    private final CheckBox replaceCredentialsCheck;
    private final ComboBox<StoredCredential> credentialCombo;
    private final CheckBox replaceSSHKeyCheck;
    private final ComboBox<SSHKey> sshKeyCombo;
    private final CheckBox filterGroupsCheck;
    private final ListView<String> groupListView;
    private final CheckBox assignToGroupCheck;
    private final ComboBox<String> targetGroupCombo;
    private final Button newGroupButton;
    private final TextField importPathField;
    private File selectedFile;
    private List<String> availableGroupsFromFile = new ArrayList<>();
    
    public static class ImportResult {
        public final File importFile;
        public final boolean importUsername;
        public final boolean importPassword;
        public final boolean importTunnels;
        public final boolean importJumpServer;
        public final boolean replaceCredentials;
        public final StoredCredential replacementCredential;
        public final boolean replaceSSHKey;
        public final SSHKey replacementSSHKey;
        public final boolean filterGroups;
        public final List<String> selectedGroups;
        public final boolean assignToGroup;
        public final String targetGroup;
        
        public ImportResult(File importFile,
                          boolean importUsername, boolean importPassword,
                          boolean importTunnels, boolean importJumpServer,
                          boolean replaceCredentials, StoredCredential replacementCredential,
                          boolean replaceSSHKey, SSHKey replacementSSHKey,
                          boolean filterGroups, List<String> selectedGroups,
                          boolean assignToGroup, String targetGroup) {
            this.importFile = importFile;
            this.importUsername = importUsername;
            this.importPassword = importPassword;
            this.importTunnels = importTunnels;
            this.importJumpServer = importJumpServer;
            this.replaceCredentials = replaceCredentials;
            this.replacementCredential = replacementCredential;
            this.replaceSSHKey = replaceSSHKey;
            this.replacementSSHKey = replacementSSHKey;
            this.filterGroups = filterGroups;
            this.selectedGroups = selectedGroups;
            this.assignToGroup = assignToGroup;
            this.targetGroup = targetGroup;
        }
    }
    
    public ConnectionImportDialog(Stage owner, CredentialManager credentialManager, 
                                 SSHKeyManager sshKeyManager, ConfigurationManager configManager) {
        this.owner = owner;
        this.credentialManager = credentialManager;
        this.sshKeyManager = sshKeyManager;
        this.configManager = configManager;
        
        setTitle("Verbindungen importieren");
        setHeaderText("Verbindungen aus Datei importieren");
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);
        
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
        
        // Section: Group filter
        Label groupFilterHeader = new Label("Gruppen-Filter:");
        groupFilterHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        grid.add(groupFilterHeader, 0, row++, 3, 1);
        
        filterGroupsCheck = new CheckBox("Nur bestimmte Gruppen importieren");
        filterGroupsCheck.setSelected(false);
        grid.add(filterGroupsCheck, 0, row++, 3, 1);
        
        Label groupListLabel = new Label("  Gruppen aus Datei:");
        groupListView = new ListView<>();
        groupListView.getSelectionModel().setSelectionMode(SelectionMode.MULTIPLE);
        groupListView.setPrefHeight(100);
        groupListView.setPrefWidth(300);
        groupListView.setDisable(true);
        groupListView.setPlaceholder(new Label("(Datei auswählen, um Gruppen zu sehen)"));
        
        grid.add(groupListLabel, 0, row);
        grid.add(groupListView, 1, row++, 2, 1);
        
        Label groupFilterInfo = new Label("(Mehrfachauswahl möglich, leer = keine Gruppe)");
        groupFilterInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        grid.add(groupFilterInfo, 0, row++, 3, 1);
        
        filterGroupsCheck.selectedProperty().addListener((obs, old, selected) -> {
            groupListView.setDisable(!selected);
        });
        
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
        
        replaceCredentialsCheck.selectedProperty().addListener((obs, old, selected) -> {
            credentialCombo.setDisable(!selected);
        });
        
        // SSH Key replacement
        replaceSSHKeyCheck = new CheckBox("SSH-Key durch gespeicherten SSH-Key ersetzen");
        replaceSSHKeyCheck.setSelected(false);
        grid.add(replaceSSHKeyCheck, 0, row++, 3, 1);
        
        Label sshKeyLabel = new Label("  Gespeicherte SSH-Keys:");
        sshKeyCombo = new ComboBox<>();
        sshKeyCombo.setPromptText("SSH-Key auswählen...");
        sshKeyCombo.setPrefWidth(300);
        sshKeyCombo.setDisable(true);
        
        sshKeyCombo.setCellFactory(lv -> new ListCell<SSHKey>() {
            @Override
            protected void updateItem(SSHKey item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });
        sshKeyCombo.setButtonCell(new ListCell<SSHKey>() {
            @Override
            protected void updateItem(SSHKey item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName());
            }
        });
        
        if (sshKeyManager != null) {
            sshKeyCombo.getItems().addAll(sshKeyManager.getAllKeys());
        }
        
        grid.add(sshKeyLabel, 0, row);
        grid.add(sshKeyCombo, 1, row++, 2, 1);
        
        replaceSSHKeyCheck.selectedProperty().addListener((obs, old, selected) -> {
            sshKeyCombo.setDisable(!selected);
        });
        
        // Section: Target group assignment
        Label targetGroupHeader = new Label("Ziel-Gruppe:");
        targetGroupHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        grid.add(targetGroupHeader, 0, row++, 3, 1);
        
        assignToGroupCheck = new CheckBox("Importierte Verbindungen in Gruppe verschieben");
        assignToGroupCheck.setSelected(false);
        grid.add(assignToGroupCheck, 0, row++, 3, 1);
        
        Label targetGroupLabel = new Label("  Ziel-Gruppe:");
        targetGroupCombo = new ComboBox<>();
        targetGroupCombo.setPromptText("Gruppe auswählen...");
        targetGroupCombo.setPrefWidth(200);
        targetGroupCombo.setEditable(false);
        targetGroupCombo.setDisable(true);
        
        // Populate with existing groups
        updateTargetGroupCombo();
        
        newGroupButton = new Button("Neue Gruppe...");
        newGroupButton.setDisable(true);
        newGroupButton.setOnAction(e -> createNewGroup());
        
        HBox targetGroupBox = new HBox(10, targetGroupCombo, newGroupButton);
        
        grid.add(targetGroupLabel, 0, row);
        grid.add(targetGroupBox, 1, row++, 2, 1);
        
        Label targetGroupInfo = new Label("(Überschreibt Gruppe aller importierten Verbindungen)");
        targetGroupInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        grid.add(targetGroupInfo, 0, row++, 3, 1);
        
        assignToGroupCheck.selectedProperty().addListener((obs, old, selected) -> {
            targetGroupCombo.setDisable(!selected);
            newGroupButton.setDisable(!selected);
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
                // Validate credentials replacement
                if (replaceCredentialsCheck.isSelected() && credentialCombo.getValue() == null) {
                    showWarning("Keine Zugangsdaten ausgewählt", 
                               "Bitte wählen Sie Zugangsdaten aus oder deaktivieren Sie die Option.");
                    return null;
                }
                
                if (replaceSSHKeyCheck.isSelected() && sshKeyCombo.getValue() == null) {
                    showWarning("Kein SSH-Key ausgewählt", 
                               "Bitte wählen Sie einen SSH-Key aus oder deaktivieren Sie die Option.");
                    return null;
                }
                
                // Validate group assignment
                if (assignToGroupCheck.isSelected() && 
                    (targetGroupCombo.getValue() == null || targetGroupCombo.getValue().trim().isEmpty())) {
                    showWarning("Keine Gruppe ausgewählt", 
                               "Bitte wählen Sie eine Gruppe aus oder deaktivieren Sie die Option.");
                    return null;
                }
                
                // Get selected groups for filtering
                List<String> selectedGroups = new ArrayList<>(groupListView.getSelectionModel().getSelectedItems());
                
                return new ImportResult(
                    selectedFile,
                    importUsernameCheck.isSelected(),
                    importPasswordCheck.isSelected(),
                    importTunnelsCheck.isSelected(),
                    importJumpServerCheck.isSelected(),
                    replaceCredentialsCheck.isSelected(),
                    credentialCombo.getValue(),
                    replaceSSHKeyCheck.isSelected(),
                    sshKeyCombo.getValue(),
                    filterGroupsCheck.isSelected(),
                    selectedGroups,
                    assignToGroupCheck.isSelected(),
                    targetGroupCombo.getValue()
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
            loadGroupsFromFile();
        }
    }
    
    private void loadGroupsFromFile() {
        try {
            // Read XML and extract groups
            jakarta.xml.bind.JAXBContext context = jakarta.xml.bind.JAXBContext.newInstance(
                de.kortty.persistence.XMLConnectionRepository.ConnectionsWrapper.class,
                ServerConnection.class,
                de.kortty.model.SSHTunnel.class,
                de.kortty.model.JumpServer.class,
                de.kortty.model.AuthMethod.class,
                de.kortty.model.TunnelType.class,
                de.kortty.model.TerminalLogConfig.class,
                de.kortty.model.TerminalLogConfig.LogFormat.class
            );
            
            jakarta.xml.bind.Unmarshaller unmarshaller = context.createUnmarshaller();
            de.kortty.persistence.XMLConnectionRepository.ConnectionsWrapper wrapper = 
                (de.kortty.persistence.XMLConnectionRepository.ConnectionsWrapper) 
                unmarshaller.unmarshal(selectedFile);
            
            // Extract unique groups
            availableGroupsFromFile = wrapper.getConnections().stream()
                .map(ServerConnection::getGroup)
                .filter(g -> g != null && !g.trim().isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
            
            // Add "(keine Gruppe)" for connections without group
            boolean hasConnectionsWithoutGroup = wrapper.getConnections().stream()
                .anyMatch(c -> c.getGroup() == null || c.getGroup().trim().isEmpty());
            
            if (hasConnectionsWithoutGroup) {
                availableGroupsFromFile.add(0, "(keine Gruppe)");
            }
            
            groupListView.getItems().clear();
            groupListView.getItems().addAll(availableGroupsFromFile);
            
        } catch (Exception e) {
            showWarning("Fehler beim Laden der Gruppen", 
                       "Die Gruppen konnten nicht aus der Datei gelesen werden: " + e.getMessage());
        }
    }
    
    private void updateTargetGroupCombo() {
        targetGroupCombo.getItems().clear();
        
        // Get all existing groups from configuration
        if (configManager != null) {
            List<String> existingGroups = configManager.getConnections().stream()
                .map(ServerConnection::getGroup)
                .filter(g -> g != null && !g.trim().isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());
            
            targetGroupCombo.getItems().addAll(existingGroups);
        }
    }
    
    private void createNewGroup() {
        TextInputDialog dialog = new TextInputDialog();
        dialog.setTitle("Neue Gruppe");
        dialog.setHeaderText("Neue Gruppe erstellen");
        dialog.setContentText("Gruppenname:");
        
        dialog.showAndWait().ifPresent(groupName -> {
            if (groupName != null && !groupName.trim().isEmpty()) {
                targetGroupCombo.getItems().add(groupName);
                targetGroupCombo.setValue(groupName);
            }
        });
    }
    
    private void showWarning(String header, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle("Warnung");
        alert.setHeaderText(header);
        alert.setContentText(content);
        alert.showAndWait();
    }
}
