package de.kortty.ui;

import de.kortty.model.AuthMethod;

import de.kortty.model.ServerConnection;
import de.kortty.model.StoredCredential;
import de.kortty.core.CredentialManager;
import de.kortty.model.ConnectionSettings;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.control.Alert;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.layout.Priority;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.scene.paint.Color;

import java.io.File;

/**
 * Dialog for editing a single connection.
 */
public class ConnectionEditDialog extends Dialog<ServerConnection> {
    
    private final ServerConnection connection;
    private final CredentialManager credentialManager;
    private final char[] masterPassword;
    private ComboBox<StoredCredential> savedCredentialsCombo;

    private final TextField nameField;
    private final TextField hostField;
    private final Spinner<Integer> portSpinner;
    private final TextField usernameField;
    private final PasswordField passwordField;
    private final TextField groupField;
    private final ToggleGroup authMethodGroup;
    private final RadioButton passwordAuthRadio;
    private final RadioButton keyAuthRadio;
    private final TextField keyPathField;
    private final Button browseKeyButton;
    private final PasswordField keyPassphraseField;
    
    // Connection-specific settings
    private CheckBox useCustomSettingsCheck;
    
    // Tunnel and Jump Server
    private CheckBox enableTunnelsCheck;
    private CheckBox enableJumpCheck;
    private ComboBox<String> fontFamilyCombo;
    private Spinner<Integer> fontSizeSpinner;
    private ColorPicker foregroundColorPicker;
    private ColorPicker backgroundColorPicker;
    private CheckBox closeWithoutConfirmCheck;
    
    // Terminal Logging
    private CheckBox enableLoggingCheck;
    private TextField logFilePathField;
    private Spinner<Integer> maxFileSizeMBSpinner;
    private ComboBox<de.kortty.model.TerminalLogConfig.LogFormat> logFormatCombo;
    
    public ConnectionEditDialog(Stage owner, ServerConnection existingConnection, CredentialManager credentialManager, char[] masterPassword) {
        this.connection = existingConnection != null ? existingConnection : new ServerConnection();
        this.credentialManager = credentialManager;
        this.masterPassword = masterPassword;
        
        setTitle(existingConnection == null ? "Neue Verbindung" : "Verbindung bearbeiten");
        setHeaderText(null);
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        
        // Create form
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        // Tab 1: Connection settings
        Tab connectionTab = new Tab("Verbindung");
        connectionTab.setClosable(false);
        GridPane connectionGrid = new GridPane();
        connectionGrid.setHgap(10);
        connectionGrid.setVgap(10);
        connectionGrid.setPadding(new Insets(20));
        
        // Basic fields
        nameField = new TextField(connection.getName());
        nameField.setPromptText("Anzeigename");
        nameField.setPrefWidth(250);
        
        hostField = new TextField(connection.getHost());
        hostField.setPromptText("hostname oder IP");
        
        portSpinner = new Spinner<>(1, 65535, connection.getPort());
        portSpinner.setEditable(true);
        portSpinner.setPrefWidth(80);
        
        usernameField = new TextField(connection.getUsername());
        usernameField.setPromptText("root");
        
        passwordField = new PasswordField();
        // Don't show encrypted password
        
        // Saved credentials ComboBox
        savedCredentialsCombo = new ComboBox<>();
        savedCredentialsCombo.setPromptText("Gespeichertes Passwort auswählen...");
        savedCredentialsCombo.setPrefWidth(300);
        updateCredentialCombo(connection.getHost());
        
        // Restore previously selected credential
        if (connection.getCredentialId() != null && credentialManager != null) {
            credentialManager.findCredentialById(connection.getCredentialId()).ifPresent(cred -> {
                savedCredentialsCombo.setValue(cred);
            });
        }
        savedCredentialsCombo.setOnAction(e -> {
            StoredCredential selected = savedCredentialsCombo.getValue();
            if (selected != null) {
                try {
                    usernameField.setText(selected.getUsername());
                    if (credentialManager != null && masterPassword != null) {
                        String password = credentialManager.getPassword(selected, masterPassword);
                        if (password != null) {
                            passwordField.setText(password);
                            // Mark that password comes from credential store
                            passwordField.setPromptText("Aus Zugangsdaten: " + selected.getName());
                        }
                    }
                } catch (Exception ex) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle("Fehler");
                    alert.setHeaderText("Passwort-Entschlüsselung fehlgeschlagen");
                    alert.setContentText(ex.getMessage());
                    alert.showAndWait();
                }
            } else {
                passwordField.setPromptText("");
            }
        });
        
        groupField = new TextField(connection.getGroup());
        groupField.setPromptText("Optional - zur Gruppierung");
        
        // Authentication method
        authMethodGroup = new ToggleGroup();
        passwordAuthRadio = new RadioButton("Passwort");
        passwordAuthRadio.setToggleGroup(authMethodGroup);
        
        keyAuthRadio = new RadioButton("Privater Schlüssel");
        keyAuthRadio.setToggleGroup(authMethodGroup);
        
        if (connection.getAuthMethod() == AuthMethod.PUBLIC_KEY) {
            keyAuthRadio.setSelected(true);
        } else {
            passwordAuthRadio.setSelected(true);
        }
        
        // Key authentication fields
        keyPathField = new TextField(connection.getPrivateKeyPath());
        keyPathField.setPromptText("Pfad zum privaten Schlüssel");
        
        browseKeyButton = new Button("...");
        browseKeyButton.setOnAction(e -> browseForKey());
        
        keyPassphraseField = new PasswordField();
        keyPassphraseField.setPromptText("Passphrase (falls erforderlich)");
        
        HBox keyPathBox = new HBox(5, keyPathField, browseKeyButton);
        
        // Update field states based on auth method
        updateAuthFields();
        authMethodGroup.selectedToggleProperty().addListener((obs, old, newVal) -> updateAuthFields());
        
        // Layout
        int row = 0;
        connectionGrid.add(new Label("Name:"), 0, row);
        connectionGrid.add(nameField, 1, row++);
        
        connectionGrid.add(new Label("Host:"), 0, row);
        HBox hostBox = new HBox(10, hostField, new Label("Port:"), portSpinner);
        connectionGrid.add(hostBox, 1, row++);
        
        connectionGrid.add(new Label("Benutzer:"), 0, row);
        connectionGrid.add(usernameField, 1, row++);
        
        connectionGrid.add(new Label("Gruppe:"), 0, row);
        connectionGrid.add(groupField, 1, row++);
        
        connectionGrid.add(new Separator(), 0, row++, 2, 1);
        
        connectionGrid.add(new Label("Authentifizierung:"), 0, row);
        HBox authBox = new HBox(15, passwordAuthRadio, keyAuthRadio);
        connectionGrid.add(authBox, 1, row++);
        
        
        // Saved credentials
        connectionGrid.add(new Label("Gespeicherte Zugangsdaten:"), 0, row);
        connectionGrid.add(savedCredentialsCombo, 1, row++);
        
        connectionGrid.add(new Label("Passwort:"), 0, row);
        connectionGrid.add(passwordField, 1, row++);
        
        connectionGrid.add(new Label("Schlüsseldatei:"), 0, row);
        connectionGrid.add(keyPathBox, 1, row++);
        
        connectionGrid.add(new Label("Passphrase:"), 0, row);
        connectionGrid.add(keyPassphraseField, 1, row++);
        
        connectionTab.setContent(connectionGrid);
        
        // Tab 2: Terminal settings
        Tab settingsTab = createSettingsTab();
        
        // Tab 3: SSH Tunnels
        Tab tunnelsTab = createTunnelsTab();
        
        // Tab 4: Jump Server
        Tab jumpServerTab = createJumpServerTab();
        
        // Tab 5: Terminal Logging
        Tab loggingTab = createLoggingTab();
        
        tabPane.getTabs().addAll(connectionTab, settingsTab, tunnelsTab, jumpServerTab, loggingTab);
        getDialogPane().setContent(tabPane);
        
        // Buttons
        ButtonType saveButtonType = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        
        // Validation
        Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);
        saveButton.setDisable(true);
        
        hostField.textProperty().addListener((obs, old, newVal) -> { validateForm(saveButton); updateCredentialCombo(newVal); });
        validateForm(saveButton);
        
        // Result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                connection.setName(nameField.getText().trim());
                connection.setHost(hostField.getText().trim());
                connection.setPort(portSpinner.getValue());
                connection.setUsername(usernameField.getText().trim().isEmpty() ? "root" : usernameField.getText().trim());
                connection.setGroup(groupField.getText().trim().isEmpty() ? null : groupField.getText().trim());
                
                // Save credential reference
                if (savedCredentialsCombo.getValue() != null) {
                    connection.setCredentialId(savedCredentialsCombo.getValue().getId());
                } else {
                    connection.setCredentialId(null);
                }
                
                if (keyAuthRadio.isSelected()) {
                    connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
                    connection.setPrivateKeyPath(keyPathField.getText().trim());
                    // Note: Key passphrase should be encrypted before saving
                } else {
                    connection.setAuthMethod(AuthMethod.PASSWORD);
                    // Note: Password should be encrypted before saving
                }
                
                // Save connection-specific settings if enabled
                if (useCustomSettingsCheck.isSelected()) {
                    ConnectionSettings customSettings = new ConnectionSettings();
                    customSettings.setFontFamily(fontFamilyCombo.getValue());
                    customSettings.setFontSize(fontSizeSpinner.getValue());
                    customSettings.setForegroundColor(toHex(foregroundColorPicker.getValue()));
                    customSettings.setBackgroundColor(toHex(backgroundColorPicker.getValue()));
                    customSettings.setCloseWithoutConfirmation(closeWithoutConfirmCheck.isSelected());
                    connection.setSettings(customSettings);
                } else {
                    connection.setSettings(null); // Use global settings
                }
                
                // Save tunnel settings (checkboxes control enabled state in models)
                // Tunnels are managed through add/edit/remove buttons
                // The enabled state is already reflected in the tunnel objects
                
                // Save jump server settings
                if (enableJumpCheck != null && enableJumpCheck.isSelected()) {
                    // Jump server configuration is saved when checkbox is enabled
                    // Full implementation TODO: extract values from UI fields
                    if (connection.getJumpServer() == null) {
                        connection.setJumpServer(new de.kortty.model.JumpServer());
                    }
                    connection.getJumpServer().setEnabled(true);
                    // TODO: Set host, port, username, password, autoCommand from UI fields
                } else if (connection.getJumpServer() != null) {
                    connection.getJumpServer().setEnabled(false);
                }
                
                // Save logging settings
                de.kortty.model.TerminalLogConfig logConfig = connection.getLogConfig();
                logConfig.setEnabled(enableLoggingCheck.isSelected());
                if (enableLoggingCheck.isSelected()) {
                    logConfig.setLogFilePath(logFilePathField.getText().trim());
                    logConfig.setMaxFileSizeMB(maxFileSizeMBSpinner.getValue());
                    logConfig.setFormat(logFormatCombo.getValue());
                }
                
                return connection;
            }
            return null;
        });
    }
    
    private void updateAuthFields() {
        boolean useKey = keyAuthRadio.isSelected();
        passwordField.setDisable(useKey);
        keyPathField.setDisable(!useKey);
        browseKeyButton.setDisable(!useKey);
        keyPassphraseField.setDisable(!useKey);
    }
    
    private void validateForm(Button saveButton) {
        boolean valid = !hostField.getText().trim().isEmpty();
        saveButton.setDisable(!valid);
    }
    
    private Tab createSettingsTab() {
        Tab tab = new Tab("Terminal-Einstellungen");
        tab.setClosable(false);
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        
        // Use custom settings checkbox
        useCustomSettingsCheck = new CheckBox("Spezifische Einstellungen für diese Verbindung verwenden");
        ConnectionSettings connSettings = connection.getSettings();
        useCustomSettingsCheck.setSelected(connSettings != null);
        
        // Settings grid
        GridPane settingsGrid = new GridPane();
        settingsGrid.setHgap(10);
        settingsGrid.setVgap(10);
        settingsGrid.setPadding(new Insets(10));
        settingsGrid.setDisable(connSettings == null);
        
        // Font settings
        fontFamilyCombo = new ComboBox<>();
        fontFamilyCombo.getItems().addAll("Monospaced", "Courier New", "Consolas", "Monaco", "DejaVu Sans Mono");
        fontFamilyCombo.setValue(connSettings != null ? connSettings.getFontFamily() : "Monospaced");
        fontFamilyCombo.setPrefWidth(200);
        
        fontSizeSpinner = new Spinner<>(8, 72, connSettings != null ? connSettings.getFontSize() : 14);
        fontSizeSpinner.setEditable(true);
        fontSizeSpinner.setPrefWidth(80);
        
        // Colors
        foregroundColorPicker = new ColorPicker(connSettings != null ? 
                Color.web(connSettings.getForegroundColor()) : Color.WHITE);
        backgroundColorPicker = new ColorPicker(connSettings != null ? 
                Color.web(connSettings.getBackgroundColor()) : Color.BLACK);
        
        // Close without confirmation
        closeWithoutConfirmCheck = new CheckBox("Tab ohne Nachfrage schließen");
        closeWithoutConfirmCheck.setSelected(connSettings != null && connSettings.isCloseWithoutConfirmation());
        
        // Layout
        int row = 0;
        settingsGrid.add(new Label("Schriftart:"), 0, row);
        settingsGrid.add(fontFamilyCombo, 1, row++);
        
        settingsGrid.add(new Label("Schriftgröße:"), 0, row);
        settingsGrid.add(fontSizeSpinner, 1, row++);
        
        settingsGrid.add(new Separator(), 0, row++, 2, 1);
        
        settingsGrid.add(new Label("Textfarbe:"), 0, row);
        settingsGrid.add(foregroundColorPicker, 1, row++);
        
        settingsGrid.add(new Label("Hintergrundfarbe:"), 0, row);
        settingsGrid.add(backgroundColorPicker, 1, row++);
        
        settingsGrid.add(new Separator(), 0, row++, 2, 1);
        
        settingsGrid.add(closeWithoutConfirmCheck, 0, row++, 2, 1);
        
        // Enable/disable settings grid based on checkbox
        useCustomSettingsCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settingsGrid.setDisable(!newVal);
        });
        
        vbox.getChildren().addAll(
                useCustomSettingsCheck,
                new Label("Diese Einstellungen überschreiben die globalen Einstellungen nur für diese Verbindung."),
                settingsGrid
        );
        
        tab.setContent(vbox);
        return tab;
    }
    
    private void browseForKey() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Privaten Schlüssel auswählen");
        
        // Start in .ssh directory if it exists
        File sshDir = new File(System.getProperty("user.home"), ".ssh");
        if (sshDir.exists()) {
            fileChooser.setInitialDirectory(sshDir);
        }
        
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Alle Dateien", "*.*"),
                new FileChooser.ExtensionFilter("PEM Dateien", "*.pem"),
                new FileChooser.ExtensionFilter("Private Keys", "id_rsa", "id_ed25519", "id_ecdsa")
        );
        
        File file = fileChooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (file != null) {
            keyPathField.setText(file.getAbsolutePath());
        }
    }

    
    private String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }

    
    private Tab createTunnelsTab() {
        Tab tab = new Tab("SSH-Tunnel");
        tab.setClosable(false);
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        
        // Enable tunnel checkbox
        enableTunnelsCheck = new CheckBox("SSH-Tunnel aktivieren");
        enableTunnelsCheck.setSelected(!connection.getSshTunnels().isEmpty());
        
        // Tunnel list
        Label label = new Label("Konfigurierte Tunnel:");
        ListView<String> tunnelList = new ListView<>();
        
        // Simple display of existing tunnels
        for (de.kortty.model.SSHTunnel tunnel : connection.getSshTunnels()) {
            tunnelList.getItems().add(tunnel.toString());
        }
        
        // Buttons for add/edit/remove
        HBox buttonBox = new HBox(10);
        Button addButton = new Button("Hinzufügen");
        Button editButton = new Button("Bearbeiten");
        Button removeButton = new Button("Entfernen");
        
        addButton.setOnAction(e -> {
            // TODO: Show tunnel edit dialog
            Alert alert = new Alert(Alert.AlertType.INFORMATION);
            alert.setTitle("SSH-Tunnel");
            alert.setHeaderText("Funktion in Entwicklung");
            alert.setContentText("Die SSH-Tunnel-Konfiguration wird in einer späteren Version vollständig implementiert.");
            alert.showAndWait();
        });
        
        editButton.setDisable(true);
        removeButton.setDisable(true);
        
        tunnelList.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            boolean selected = newVal != null;
            editButton.setDisable(!selected);
            removeButton.setDisable(!selected);
        });
        
        buttonBox.getChildren().addAll(addButton, editButton, removeButton);
        
        Label infoLabel = new Label("SSH-Tunnel ermöglichen Port-Forwarding durch den SSH-Server.\n" +
                "Local: -L localPort:remoteHost:remotePort\n" +
                "Remote: -R remotePort:localHost:localPort\n" +
                "Dynamic (SOCKS): -D localPort");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        infoLabel.setWrapText(true);
        
        vbox.getChildren().addAll(enableTunnelsCheck, new Separator(), label, tunnelList, buttonBox, infoLabel);
        VBox.setVgrow(tunnelList, Priority.ALWAYS);
        
        tab.setContent(vbox);
        return tab;
    }
    
    private Tab createJumpServerTab() {
        Tab tab = new Tab("Jump Server");
        tab.setClosable(false);
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        
        // Enable jump server checkbox
        enableJumpCheck = new CheckBox("Jump Server / Auto-Hop aktivieren");
        de.kortty.model.JumpServer jumpServer = connection.getJumpServer();
        enableJumpCheck.setSelected(jumpServer != null && jumpServer.isEnabled());
        
        // Jump server configuration
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setDisable(jumpServer == null || !jumpServer.isEnabled());
        
        TextField jumpHostField = new TextField();
        jumpHostField.setPromptText("Jump-Server Hostname");
        if (jumpServer != null) jumpHostField.setText(jumpServer.getHost());
        
        Spinner<Integer> jumpPortSpinner = new Spinner<>(1, 65535, jumpServer != null ? jumpServer.getPort() : 22);
        jumpPortSpinner.setEditable(true);
        jumpPortSpinner.setPrefWidth(80);
        
        TextField jumpUserField = new TextField();
        jumpUserField.setPromptText("Benutzername");
        if (jumpServer != null) jumpUserField.setText(jumpServer.getUsername());
        
        PasswordField jumpPasswordField = new PasswordField();
        jumpPasswordField.setPromptText("Passwort (optional)");
        
        TextField autoCommandField = new TextField();
        autoCommandField.setPromptText("z.B. ssh user@final-host");
        if (jumpServer != null) autoCommandField.setText(jumpServer.getAutoCommand());
        
        int row = 0;
        grid.add(new Label("Jump-Server Host:"), 0, row);
        HBox hostBox = new HBox(10);
        hostBox.getChildren().addAll(jumpHostField, new Label("Port:"), jumpPortSpinner);
        grid.add(hostBox, 1, row++);
        
        grid.add(new Label("Benutzer:"), 0, row);
        grid.add(jumpUserField, 1, row++);
        
        grid.add(new Label("Passwort:"), 0, row);
        grid.add(jumpPasswordField, 1, row++);
        
        grid.add(new Label("Auto-Befehl:"), 0, row);
        grid.add(autoCommandField, 1, row++);
        
        enableJumpCheck.selectedProperty().addListener((obs, old, newVal) -> {
            grid.setDisable(!newVal);
        });
        
        Label infoLabel = new Label("Jump Server ermöglicht das automatische Hopping über einen Bastion-Host.\n" +
                "Der Auto-Befehl wird nach dem Login auf dem Jump-Server ausgeführt.");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        infoLabel.setWrapText(true);
        
        vbox.getChildren().addAll(enableJumpCheck, new Separator(), grid, infoLabel);
        
        tab.setContent(vbox);
        return tab;
    }
    
    private Tab createLoggingTab() {
        Tab tab = new Tab("Terminal-Logging");
        tab.setClosable(false);
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        
        // Enable logging checkbox
        enableLoggingCheck = new CheckBox("Terminal-Ausgabe in Datei protokollieren");
        de.kortty.model.TerminalLogConfig logConfig = connection.getLogConfig();
        enableLoggingCheck.setSelected(logConfig != null && logConfig.isEnabled());
        
        // Logging configuration
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setDisable(logConfig == null || !logConfig.isEnabled());
        
        // Log file path
        logFilePathField = new TextField();
        logFilePathField.setPromptText("/path/to/terminal.log");
        logFilePathField.setPrefWidth(300);
        if (logConfig != null) {
            logFilePathField.setText(logConfig.getLogFilePath());
        }
        
        Button browseLogButton = new Button("Durchsuchen...");
        browseLogButton.setOnAction(e -> browseForLogFile());
        
        // Max file size
        maxFileSizeMBSpinner = new Spinner<>(1, 1000, logConfig != null ? logConfig.getMaxFileSizeMB() : 10);
        maxFileSizeMBSpinner.setEditable(true);
        maxFileSizeMBSpinner.setPrefWidth(100);
        
        // Log format
        logFormatCombo = new ComboBox<>();
        logFormatCombo.getItems().addAll(de.kortty.model.TerminalLogConfig.LogFormat.values());
        logFormatCombo.setValue(logConfig != null ? logConfig.getFormat() : de.kortty.model.TerminalLogConfig.LogFormat.PLAIN_TEXT);
        logFormatCombo.setPrefWidth(200);
        
        // Layout
        int row = 0;
        grid.add(new Label("Log-Datei:"), 0, row);
        HBox logPathBox = new HBox(10);
        logPathBox.getChildren().addAll(logFilePathField, browseLogButton);
        grid.add(logPathBox, 1, row++);
        
        grid.add(new Label("Max. Dateigröße:"), 0, row);
        HBox sizeBox = new HBox(10);
        sizeBox.getChildren().addAll(maxFileSizeMBSpinner, new Label("MB"));
        grid.add(sizeBox, 1, row++);
        
        grid.add(new Label("Format:"), 0, row);
        grid.add(logFormatCombo, 1, row++);
        
        // Enable/disable grid based on checkbox
        enableLoggingCheck.selectedProperty().addListener((obs, old, newVal) -> {
            grid.setDisable(!newVal);
        });
        
        Label infoLabel = new Label(
                "Terminal-Logging protokolliert die komplette Terminal-Ausgabe in eine Datei.\n\n" +
                "• Plain Text: Einfaches Textformat mit Zeitstempeln\n" +
                "• XML: Strukturiertes XML-Format\n" +
                "• JSON: Maschinenlesbares JSON-Format\n\n" +
                "Wenn die maximale Dateigröße erreicht ist, wird die Datei gelöscht und neu begonnen.\n" +
                "Alle ANSI-Escape-Sequenzen und Nicht-ASCII-Zeichen werden entfernt.");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        infoLabel.setWrapText(true);
        
        vbox.getChildren().addAll(enableLoggingCheck, new Separator(), grid, infoLabel);
        
        tab.setContent(vbox);
        return tab;
    }
    
    private void browseForLogFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle("Log-Datei auswählen");
        
        // Start in user home
        File homeDir = new File(System.getProperty("user.home"));
        fileChooser.setInitialDirectory(homeDir);
        
        // Suggest file name based on connection
        String suggestedName = connection.getName() != null ? 
                connection.getName().replaceAll("[^a-zA-Z0-9-_]", "_") + ".log" : 
                "terminal.log";
        fileChooser.setInitialFileName(suggestedName);
        
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Log Dateien", "*.log", "*.txt", "*.xml", "*.json"),
                new FileChooser.ExtensionFilter("Alle Dateien", "*.*")
        );
        
        File file = fileChooser.showSaveDialog(getDialogPane().getScene().getWindow());
        if (file != null) {
            logFilePathField.setText(file.getAbsolutePath());
        }
    }
    
    
    private void updateCredentialCombo(String hostname) {
        if (savedCredentialsCombo == null || credentialManager == null) return;
        
        // Remember current selection
        StoredCredential currentSelection = savedCredentialsCombo.getValue();
        
        savedCredentialsCombo.getItems().clear();
        if (hostname != null && !hostname.trim().isEmpty()) {
            java.util.List<StoredCredential> matchingCredentials = credentialManager.getAllCredentials().stream()
                .filter(c -> c.matchesServer(hostname)).collect(java.util.stream.Collectors.toList());
            savedCredentialsCombo.getItems().addAll(matchingCredentials);
            
            // Restore selection if it still matches
            if (currentSelection != null && matchingCredentials.contains(currentSelection)) {
                savedCredentialsCombo.setValue(currentSelection);
            }
        }
    }

}
