package de.kortty.ui;

import de.kortty.model.AuthMethod;

import de.kortty.model.ServerConnection;
import de.kortty.model.StoredCredential;
import de.kortty.model.SSHKey;
import de.kortty.model.SSHTunnel;
import de.kortty.model.TunnelType;
import de.kortty.core.CredentialManager;
import de.kortty.core.SSHKeyManager;
import de.kortty.core.ThemeManager;
import de.kortty.security.EncryptionService;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Theme;
import de.kortty.model.WindowGeometry;
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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

/**
 * Dialog for editing a single connection.
 */
public class ConnectionEditDialog extends Dialog<ServerConnection> {
    
    private static final Logger logger = LoggerFactory.getLogger(ConnectionEditDialog.class);
    
    private final ServerConnection connection;
    private final CredentialManager credentialManager;
    private final SSHKeyManager sshKeyManager;
    private final char[] masterPassword;
    private ComboBox<StoredCredential> savedCredentialsCombo;
    private ComboBox<SSHKey> savedSSHKeysCombo;

    private final TextField nameField;
    private final TextField hostField;
    private final Spinner<Integer> portSpinner;
    private final TextField usernameField;
    private final PasswordField passwordField;
    private final TextField groupField;
    private final ToggleGroup authMethodGroup;
    private final RadioButton passwordAuthRadio;
    private final RadioButton keyAuthRadio;
    private final RadioButton temporaryKeyAuthRadio;
    private final TextField keyPathField;
    private final Button browseKeyButton;
    private final PasswordField keyPassphraseField;
    private final TextArea temporaryKeyArea;
    private final Spinner<Integer> temporaryKeyExpirationSpinner;
    private final CheckBox temporaryKeyPermanentCheck;
    
    // Connection-specific settings
    private CheckBox useCustomSettingsCheck;
    private ComboBox<Theme> themeCombo;
    
    // Tunnel and Jump Server
    private CheckBox enableTunnelsCheck;
    private CheckBox enableJumpCheck;
    private ComboBox<String> fontFamilyCombo;
    private Spinner<Integer> fontSizeSpinner;
    private ColorPicker foregroundColorPicker;
    private ColorPicker backgroundColorPicker;
    private CheckBox closeWithoutConfirmCheck;
    private CheckBox commandTimestampsCheck;
    
    // Terminal Logging
    private CheckBox enableLoggingCheck;
    private TextField logFilePathField;
    private Spinner<Integer> maxFileSizeMBSpinner;
    private ComboBox<de.kortty.model.TerminalLogConfig.LogFormat> logFormatCombo;
    
    // Connection timeout
    private Spinner<Integer> timeoutSpinner;
    private Spinner<Integer> retrySpinner;
    
    // Window geometry (per connection)
    private CheckBox useCustomGeometryCheck;
    private Spinner<Integer> customWidthSpinner;
    private Spinner<Integer> customHeightSpinner;
    private Spinner<Integer> customXSpinner;
    private Spinner<Integer> customYSpinner;
    private CheckBox maximizedCheck;
    
    public ConnectionEditDialog(Stage owner, ServerConnection existingConnection, CredentialManager credentialManager, 
                               SSHKeyManager sshKeyManager, char[] masterPassword) {
        // For new connections, apply default terminal settings
        if (existingConnection == null) {
            ServerConnection newConnection = new ServerConnection();
            // Try to load default settings from global settings
            try {
                de.kortty.core.GlobalSettingsManager gsm = 
                    de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
                GlobalSettings globalSettings = gsm.getSettings();
                if (globalSettings != null && globalSettings.getDefaultTerminalSettings() != null) {
                    newConnection.setSettings(new ConnectionSettings(globalSettings.getDefaultTerminalSettings()));
                }
            } catch (Exception e) {
                // Ignore, use default settings
            }
            this.connection = newConnection;
        } else {
            this.connection = existingConnection;
        }
        
        this.credentialManager = credentialManager;
        this.sshKeyManager = sshKeyManager;
        this.masterPassword = masterPassword;
        
        setTitle(existingConnection == null ? I18n.get("connEdit.newTitle") : I18n.get("connEdit.editTitle"));
        setHeaderText(null);
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        
        // Create form
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        // Tab 1: Connection settings
        Tab connectionTab = new Tab(I18n.get("connEdit.tab.connection"));
        connectionTab.setClosable(false);
        GridPane connectionGrid = new GridPane();
        connectionGrid.setHgap(10);
        connectionGrid.setVgap(10);
        connectionGrid.setPadding(new Insets(20));
        
        // Basic fields
        nameField = new TextField(connection.getName());
        nameField.setPromptText(I18n.get("connEdit.displayName"));
        nameField.setPrefWidth(250);
        
        hostField = new TextField(connection.getHost());
        hostField.setPromptText(I18n.get("connEdit.hostPrompt"));
        
        portSpinner = new Spinner<>(1, 65535, connection.getPort());
        portSpinner.setEditable(true);
        portSpinner.setPrefWidth(80);
        
        usernameField = new TextField(connection.getUsername());
        usernameField.setPromptText("root");
        
        passwordField = new PasswordField();
        // Don't show encrypted password
        
        // Saved credentials ComboBox
        savedCredentialsCombo = new ComboBox<>();
        savedCredentialsCombo.setPromptText(I18n.get("connEdit.selectCredential"));
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
                            passwordField.setPromptText(I18n.get("connEdit.fromCredential") + ": " + selected.getName());
                        }
                    }
                } catch (Exception ex) {
                    Alert alert = new Alert(Alert.AlertType.ERROR);
                    alert.setTitle(I18n.get("error.title"));
                    alert.setHeaderText(I18n.get("connEdit.decryptFailed"));
                    alert.setContentText(ex.getMessage());
                    alert.showAndWait();
                }
            } else {
                passwordField.setPromptText("");
            }
        });
        
        groupField = new TextField(connection.getGroup());
        groupField.setPromptText(I18n.get("connEdit.groupPrompt"));
        
        // Connection timeout and retry
        timeoutSpinner = new Spinner<>(1, 300, connection.getConnectionTimeoutSeconds());
        timeoutSpinner.setEditable(true);
        timeoutSpinner.setPrefWidth(80);
        
        retrySpinner = new Spinner<>(1, 20, connection.getRetryCount());
        retrySpinner.setEditable(true);
        retrySpinner.setPrefWidth(80);
        
        // Authentication method
        authMethodGroup = new ToggleGroup();
        passwordAuthRadio = new RadioButton(I18n.get("common.password"));
        passwordAuthRadio.setToggleGroup(authMethodGroup);
        
        keyAuthRadio = new RadioButton(I18n.get("connEdit.authKey"));
        keyAuthRadio.setToggleGroup(authMethodGroup);
        
        temporaryKeyAuthRadio = new RadioButton(I18n.get("connEdit.authTempKey"));
        temporaryKeyAuthRadio.setToggleGroup(authMethodGroup);
        
        // Determine initial selection
        if (connection.getTemporaryKeyContent() != null && !connection.getTemporaryKeyContent().trim().isEmpty()) {
            temporaryKeyAuthRadio.setSelected(true);
        } else if (connection.getAuthMethod() == AuthMethod.PUBLIC_KEY) {
            keyAuthRadio.setSelected(true);
        } else {
            passwordAuthRadio.setSelected(true);
        }
        
        // Key authentication fields
        keyPathField = new TextField(connection.getPrivateKeyPath());
        keyPathField.setPromptText(I18n.get("connEdit.keyPathPrompt"));
        
        browseKeyButton = new Button("...");
        browseKeyButton.setOnAction(e -> browseForKey());
        
        keyPassphraseField = new PasswordField();
        keyPassphraseField.setPromptText(I18n.get("connEdit.passphrasePrompt"));
        
        savedSSHKeysCombo = new ComboBox<>();
        savedSSHKeysCombo.setPromptText(I18n.get("connEdit.selectSSHKey"));
        savedSSHKeysCombo.setPrefWidth(300);
        if (sshKeyManager != null) {
            savedSSHKeysCombo.getItems().addAll(sshKeyManager.getAllKeys());
        }
        
        // Restore previously selected SSH key and load its passphrase into the field
        if (connection.getSshKeyId() != null && sshKeyManager != null) {
            sshKeyManager.findKeyById(connection.getSshKeyId()).ifPresent(key -> {
                savedSSHKeysCombo.setValue(key);
                keyPathField.setText(sshKeyManager.getEffectiveKeyPath(key));
                loadPassphraseForSelectedKey();
            });
        }
        
        savedSSHKeysCombo.setOnAction(e -> {
            SSHKey selected = savedSSHKeysCombo.getValue();
            if (selected != null && sshKeyManager != null) {
                keyPathField.setText(sshKeyManager.getEffectiveKeyPath(selected));
                loadPassphraseForSelectedKey();
            } else {
                keyPathField.clear();
                keyPassphraseField.clear();
                keyPassphraseField.setPromptText(I18n.get("connEdit.passphrasePrompt"));
            }
        });
        
        // Temporary SSH Key fields
        temporaryKeyArea = new TextArea();
        temporaryKeyArea.setPromptText(I18n.get("connEdit.tempKeyPrompt"));
        temporaryKeyArea.setPrefRowCount(5);
        temporaryKeyArea.setWrapText(true);
        
        // Load existing temporary key if present
        if (connection.getTemporaryKeyContent() != null && !connection.getTemporaryKeyContent().trim().isEmpty()) {
            temporaryKeyArea.setText(connection.getTemporaryKeyContent());
        }
        
        temporaryKeyExpirationSpinner = new Spinner<>(1, 1440, 
            connection.getTemporaryKeyExpirationMinutes() != null ? 
                connection.getTemporaryKeyExpirationMinutes().intValue() : 60);
        temporaryKeyExpirationSpinner.setEditable(true);
        temporaryKeyExpirationSpinner.setPrefWidth(100);
        
        temporaryKeyPermanentCheck = new CheckBox(I18n.get("connEdit.tempKeyPermanent"));
        temporaryKeyPermanentCheck.setSelected(connection.isTemporaryKeyPermanent());
        temporaryKeyPermanentCheck.setTooltip(new Tooltip(
            I18n.get("connEdit.tempKeyPermanentTooltip")
        ));
        
        HBox keyPathBox = new HBox(5, keyPathField, browseKeyButton);
        
        // Update field states based on auth method
        updateAuthFields();
        authMethodGroup.selectedToggleProperty().addListener((obs, old, newVal) -> updateAuthFields());
        
        // Layout
        int row = 0;
        connectionGrid.add(new Label(I18n.get("common.name") + ":"), 0, row);
        connectionGrid.add(nameField, 1, row++);
        
        connectionGrid.add(new Label(I18n.get("common.host") + ":"), 0, row);
        HBox hostBox = new HBox(10, hostField, new Label(I18n.get("common.port") + ":"), portSpinner);
        connectionGrid.add(hostBox, 1, row++);
        
        connectionGrid.add(new Label(I18n.get("common.username") + ":"), 0, row);
        connectionGrid.add(usernameField, 1, row++);
        
        connectionGrid.add(new Label(I18n.get("connEdit.group")), 0, row);
        connectionGrid.add(groupField, 1, row++);
        
        connectionGrid.add(new Separator(), 0, row++, 2, 1);
        
        connectionGrid.add(new Label(I18n.get("connEdit.timeout")), 0, row);
        HBox timeoutBox = new HBox(10);
        timeoutBox.getChildren().addAll(timeoutSpinner, new Label(I18n.get("common.seconds")));
        connectionGrid.add(timeoutBox, 1, row++);
        
        connectionGrid.add(new Label(I18n.get("connEdit.retries")), 0, row);
        HBox retryBox = new HBox(10);
        retryBox.getChildren().addAll(retrySpinner, new Label(I18n.get("connEdit.attempts")));
        connectionGrid.add(retryBox, 1, row++);
        
        connectionGrid.add(new Separator(), 0, row++, 2, 1);
        
        connectionGrid.add(new Label(I18n.get("connEdit.authentication")), 0, row);
        HBox authBox = new HBox(15, passwordAuthRadio, keyAuthRadio, temporaryKeyAuthRadio);
        connectionGrid.add(authBox, 1, row++);
        
        
        // Saved credentials
        connectionGrid.add(new Label(I18n.get("connEdit.savedCredentials")), 0, row);
        connectionGrid.add(savedCredentialsCombo, 1, row++);
        
        connectionGrid.add(new Label(I18n.get("common.password") + ":"), 0, row);
        connectionGrid.add(passwordField, 1, row++);
        
        connectionGrid.add(new Label(I18n.get("connEdit.savedSSHKeys")), 0, row);
        connectionGrid.add(savedSSHKeysCombo, 1, row++);
        
        connectionGrid.add(new Label(I18n.get("connEdit.keyFile")), 0, row);
        connectionGrid.add(keyPathBox, 1, row++);
        
        connectionGrid.add(new Label(I18n.get("connEdit.passphrase")), 0, row);
        connectionGrid.add(keyPassphraseField, 1, row++);
        
        // Temporary SSH Key section
        connectionGrid.add(new Separator(), 0, row++, 2, 1);
        connectionGrid.add(new Label(I18n.get("connEdit.tempKey")), 0, row);
        VBox tempKeyBox = new VBox(5);
        tempKeyBox.getChildren().add(temporaryKeyArea);
        Button updateTempKeyButton = new Button(I18n.get("quickConnect.updateTempKey"));
        updateTempKeyButton.setTooltip(new Tooltip(I18n.get("quickConnect.updateTempKey.tooltip")));
        updateTempKeyButton.setOnAction(e -> {
            if (temporaryKeyAuthRadio.isSelected() && temporaryKeyArea.getText() != null && !temporaryKeyArea.getText().trim().isEmpty()) {
                long expirationMinutes = temporaryKeyExpirationSpinner.getValue();
                de.kortty.core.TemporarySSHKeyManager.getInstance().storeTemporaryKey(
                    temporaryKeyArea.getText().trim(), expirationMinutes);
            }
        });
        tempKeyBox.getChildren().add(updateTempKeyButton);
        connectionGrid.add(tempKeyBox, 1, row++);
        
        connectionGrid.add(new Label(I18n.get("connEdit.expiration")), 0, row);
        HBox expirationBox = new HBox(10);
        expirationBox.getChildren().addAll(temporaryKeyExpirationSpinner, new Label(I18n.get("quickConnect.expirationMinutes")));
        connectionGrid.add(expirationBox, 1, row++);
        
        connectionGrid.add(new Label(""), 0, row);
        connectionGrid.add(temporaryKeyPermanentCheck, 1, row++);
        
        connectionTab.setContent(connectionGrid);
        
        // Tab 2: Terminal settings
        Tab settingsTab = createSettingsTab();
        
        // Tab 3: SSH Tunnels
        Tab tunnelsTab = createTunnelsTab();
        
        // Tab 4: Jump Server
        Tab jumpServerTab = createJumpServerTab();
        
        // Tab 5: Terminal Logging
        Tab loggingTab = createLoggingTab();
        
        // Tab 6: Window Geometry
        Tab geometryTab = createGeometryTab();
        
        tabPane.getTabs().addAll(connectionTab, settingsTab, tunnelsTab, jumpServerTab, loggingTab, geometryTab);
        getDialogPane().setContent(tabPane);
        
        // Buttons
        ButtonType saveButtonType = new ButtonType(I18n.get("dialog.save"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        
        // Validation
        Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);
        saveButton.setDisable(true);
        
        hostField.textProperty().addListener((obs, old, newVal) -> { validateForm(saveButton); updateCredentialCombo(newVal); });
        if (savedCredentialsCombo != null) {
            savedCredentialsCombo.valueProperty().addListener((obs, oldVal, newVal) -> validateForm(saveButton));
        }
        if (savedSSHKeysCombo != null) {
            savedSSHKeysCombo.valueProperty().addListener((obs, oldVal, newVal) -> validateForm(saveButton));
        }
        if (passwordAuthRadio != null) passwordAuthRadio.selectedProperty().addListener((obs, oldVal, newVal) -> validateForm(saveButton));
        if (keyAuthRadio != null) keyAuthRadio.selectedProperty().addListener((obs, oldVal, newVal) -> validateForm(saveButton));
        if (temporaryKeyAuthRadio != null) temporaryKeyAuthRadio.selectedProperty().addListener((obs, oldVal, newVal) -> validateForm(saveButton));
        if (temporaryKeyArea != null) temporaryKeyArea.textProperty().addListener((obs, oldVal, newVal) -> validateForm(saveButton));
        validateForm(saveButton);
        
        // Result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                // Helper method to safely get text from TextField
                String getNameText = nameField != null && nameField.getText() != null ? nameField.getText().trim() : "";
                String getHostText = hostField != null && hostField.getText() != null ? hostField.getText().trim() : "";
                String getUsernameText = usernameField != null && usernameField.getText() != null ? usernameField.getText().trim() : "";
                String getGroupText = groupField != null && groupField.getText() != null ? groupField.getText().trim() : "";
                
                connection.setName(getNameText);
                connection.setHost(getHostText);
                connection.setPort(portSpinner.getValue());
                connection.setUsername(getUsernameText.isEmpty() ? "root" : getUsernameText);
                connection.setGroup(getGroupText.isEmpty() ? null : getGroupText);
                connection.setConnectionTimeoutSeconds(timeoutSpinner.getValue());
                connection.setRetryCount(retrySpinner.getValue());
                
                // Save credential reference
                if (savedCredentialsCombo.getValue() != null) {
                    connection.setCredentialId(savedCredentialsCombo.getValue().getId());
                } else {
                    connection.setCredentialId(null);
                }
                
                if (temporaryKeyAuthRadio != null && temporaryKeyAuthRadio.isSelected()) {
                    // Temporary SSH Key authentication
                    connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
                    connection.setSshKeyId(null);
                    
                    String tempKeyContent = temporaryKeyArea != null && temporaryKeyArea.getText() != null ? 
                        temporaryKeyArea.getText().trim() : "";
                    
                    if (!tempKeyContent.isEmpty()) {
                        connection.setTemporaryKeyContent(tempKeyContent);
                        connection.setTemporaryKeyExpirationMinutes(
                            temporaryKeyExpirationSpinner != null ? 
                                (long) temporaryKeyExpirationSpinner.getValue() : 60L);
                        connection.setTemporaryKeyPermanent(
                            temporaryKeyPermanentCheck != null && temporaryKeyPermanentCheck.isSelected());
                        
                        // Set privateKeyPath to TEMPORARY: prefix for compatibility
                        connection.setPrivateKeyPath("TEMPORARY:" + tempKeyContent);
                    } else {
                        // Clear temporary key if empty
                        connection.setTemporaryKeyContent(null);
                        connection.setTemporaryKeyExpirationMinutes(null);
                        connection.setTemporaryKeyPermanent(false);
                        connection.setPrivateKeyPath(null);
                    }
                } else if (keyAuthRadio != null && keyAuthRadio.isSelected()) {
                    connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
                    // Clear temporary key fields
                    connection.setTemporaryKeyContent(null);
                    connection.setTemporaryKeyExpirationMinutes(null);
                    connection.setTemporaryKeyPermanent(false);
                    
                    // Use key from combo if selected, otherwise use path field
                    if (savedSSHKeysCombo != null && savedSSHKeysCombo.getValue() != null) {
                        connection.setSshKeyId(savedSSHKeysCombo.getValue().getId());
                        connection.setPrivateKeyPath(sshKeyManager != null ? 
                            sshKeyManager.getEffectiveKeyPath(savedSSHKeysCombo.getValue()) : 
                            (keyPathField != null && keyPathField.getText() != null ? keyPathField.getText().trim() : ""));
                    } else {
                        connection.setSshKeyId(null);
                        connection.setPrivateKeyPath(keyPathField != null && keyPathField.getText() != null ? keyPathField.getText().trim() : "");
                    }
                    // Persist key passphrase only when encrypted (never store plain text)
                    String passphraseToSave = keyPassphraseField != null ? keyPassphraseField.getText() : null;
                    if (passphraseToSave != null && !passphraseToSave.trim().isEmpty() && masterPassword != null) {
                        try {
                            EncryptionService encryptionService = new EncryptionService();
                            connection.setPrivateKeyPassphrase(encryptionService.encryptPassword(passphraseToSave.trim(), masterPassword));
                        } catch (Exception ex) {
                            logger.error("Failed to encrypt key passphrase for saving", ex);
                            connection.setPrivateKeyPassphrase(null);
                        }
                    } else {
                        connection.setPrivateKeyPassphrase(null);
                    }
                } else {
                    connection.setAuthMethod(AuthMethod.PASSWORD);
                    connection.setSshKeyId(null);
                    // Clear temporary key fields
                    connection.setTemporaryKeyContent(null);
                    connection.setTemporaryKeyExpirationMinutes(null);
                    connection.setTemporaryKeyPermanent(false);
                    // Note: Password should be encrypted before saving
                }
                
                // Save connection-specific settings if enabled
                if (useCustomSettingsCheck != null && useCustomSettingsCheck.isSelected()) {
                    ConnectionSettings customSettings;
                    Theme selTheme = themeCombo != null ? themeCombo.getValue() : null;
                    if (selTheme != null) {
                        customSettings = selTheme.toConnectionSettings();
                        customSettings.setThemeId(selTheme.getId());
                    } else {
                        customSettings = new ConnectionSettings();
                    }
                    if (fontFamilyCombo != null) customSettings.setFontFamily(fontFamilyCombo.getValue());
                    if (fontSizeSpinner != null) customSettings.setFontSize(fontSizeSpinner.getValue());
                    if (foregroundColorPicker != null) customSettings.setForegroundColor(toHex(foregroundColorPicker.getValue()));
                    if (backgroundColorPicker != null) customSettings.setBackgroundColor(toHex(backgroundColorPicker.getValue()));
                    if (closeWithoutConfirmCheck != null) customSettings.setCloseWithoutConfirmation(closeWithoutConfirmCheck.isSelected());
                    if (commandTimestampsCheck != null) customSettings.setCommandTimestampsEnabled(commandTimestampsCheck.isSelected());
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
                if (enableLoggingCheck != null) {
                    logConfig.setEnabled(enableLoggingCheck.isSelected());
                    if (enableLoggingCheck.isSelected()) {
                        logConfig.setLogFilePath(logFilePathField != null && logFilePathField.getText() != null ? logFilePathField.getText().trim() : "");
                        if (maxFileSizeMBSpinner != null) {
                            logConfig.setMaxFileSizeMB(maxFileSizeMBSpinner.getValue());
                        }
                        if (logFormatCombo != null) {
                            logConfig.setFormat(logFormatCombo.getValue());
                        }
                    }
                }
                
                // Save window geometry settings
                if (useCustomGeometryCheck != null && useCustomGeometryCheck.isSelected()) {
                    WindowGeometry customGeo = new WindowGeometry();
                    customGeo.setWidth(customWidthSpinner.getValue());
                    customGeo.setHeight(customHeightSpinner.getValue());
                    customGeo.setX(customXSpinner.getValue());
                    customGeo.setY(customYSpinner.getValue());
                    customGeo.setMaximized(maximizedCheck.isSelected());
                    connection.setWindowGeometry(customGeo);
                } else {
                    connection.setWindowGeometry(null); // Use global settings
                }
                
                // Teamwork connections must not persist inline secrets; only credentialId/sshKeyId
                if (connection.isTeamworkConnection()) {
                    connection.setEncryptedPassword(null);
                    connection.setPrivateKeyPath(null);
                    connection.setPrivateKeyPassphrase(null);
                    connection.setTemporaryKeyContent(null);
                    connection.setTemporaryKeyExpirationMinutes(null);
                    connection.setTemporaryKeyPermanent(false);
                }
                
                return connection;
            }
            return null;
        });
    }
    
    /**
     * Loads the passphrase for the currently selected SSH key into the passphrase field.
     * Tries key manager first, then connection's stored (encrypted) passphrase.
     */
    private void loadPassphraseForSelectedKey() {
        SSHKey selected = savedSSHKeysCombo.getValue();
        if (selected == null || sshKeyManager == null) {
            keyPassphraseField.clear();
            keyPassphraseField.setPromptText(I18n.get("connEdit.passphrasePrompt"));
            return;
        }
        try {
            String passphrase = sshKeyManager.getPassphrase(selected, masterPassword);
            if (passphrase != null) {
                keyPassphraseField.setText(passphrase);
                keyPassphraseField.setPromptText(I18n.get("connEdit.fromSSHKey") + ": " + selected.getName());
                return;
            }
        } catch (Exception ex) {
            logger.error("Could not decrypt stored key passphrase for key '{}': {}", selected.getName(), ex.getMessage(), ex);
            showPassphraseDecryptFailedAlert();
            keyPassphraseField.clear();
            keyPassphraseField.setPromptText(I18n.get("connEdit.passphrasePrompt"));
            return;
        }
        // Fallback: connection may have stored encrypted passphrase (e.g. from previous save)
        String stored = connection.getPrivateKeyPassphrase();
        if (stored != null && !stored.isBlank() && masterPassword != null) {
            try {
                EncryptionService encryptionService = new EncryptionService();
                String decrypted = encryptionService.decryptPassword(stored, masterPassword);
                if (decrypted != null) {
                    keyPassphraseField.setText(decrypted);
                    keyPassphraseField.setPromptText(I18n.get("connEdit.fromSSHKey") + ": " + selected.getName());
                    return;
                }
            } catch (Exception ex) {
                logger.error("Could not decrypt stored connection passphrase: {}", ex.getMessage(), ex);
                showPassphraseDecryptFailedAlert();
            }
        }
        keyPassphraseField.clear();
        keyPassphraseField.setPromptText(I18n.get("connEdit.passphrasePrompt"));
    }
    
    private void showPassphraseDecryptFailedAlert() {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(I18n.get("error.title"));
        alert.setHeaderText(I18n.get("error.passphraseDecryptFailed"));
        if (getDialogPane().getScene() != null && getDialogPane().getScene().getWindow() != null) {
            alert.initOwner(getDialogPane().getScene().getWindow());
        }
        alert.showAndWait();
    }
    
    private void updateAuthFields() {
        boolean useKey = keyAuthRadio.isSelected();
        boolean useTemporaryKey = temporaryKeyAuthRadio.isSelected();
        boolean usePassword = passwordAuthRadio.isSelected();
        
        passwordField.setDisable(useKey || useTemporaryKey);
        savedCredentialsCombo.setDisable(useKey || useTemporaryKey);
        savedSSHKeysCombo.setDisable(!useKey || useTemporaryKey);
        keyPathField.setDisable(!useKey || useTemporaryKey);
        browseKeyButton.setDisable(!useKey || useTemporaryKey);
        keyPassphraseField.setDisable(!useKey || useTemporaryKey);
        
        temporaryKeyArea.setDisable(!useTemporaryKey);
        temporaryKeyExpirationSpinner.setDisable(!useTemporaryKey);
        temporaryKeyPermanentCheck.setDisable(!useTemporaryKey);
    }
    
    private void validateForm(Button saveButton) {
        String hostText = hostField.getText();
        boolean valid = hostText != null && !hostText.trim().isEmpty();
        if (valid && connection.isTeamworkConnection()) {
            boolean hasCred = passwordAuthRadio != null && passwordAuthRadio.isSelected()
                && savedCredentialsCombo != null && savedCredentialsCombo.getValue() != null;
            boolean hasKey = keyAuthRadio != null && keyAuthRadio.isSelected() && savedSSHKeysCombo != null && savedSSHKeysCombo.getValue() != null;
            boolean hasTempKey = temporaryKeyAuthRadio != null && temporaryKeyAuthRadio.isSelected()
                && temporaryKeyArea != null && temporaryKeyArea.getText() != null && !temporaryKeyArea.getText().trim().isEmpty();
            valid = hasCred || hasKey || hasTempKey;
        }
        saveButton.setDisable(!valid);
        if (saveButton.isDisabled() && connection.isTeamworkConnection()) {
            saveButton.setTooltip(new Tooltip(I18n.get("connEdit.teamworkAuthRequired")));
        } else {
            saveButton.setTooltip(null);
        }
    }
    
    private Tab createSettingsTab() {
        Tab tab = new Tab(I18n.get("connEdit.tab.settings"));
        tab.setClosable(false);
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        
        // Use custom settings checkbox
        useCustomSettingsCheck = new CheckBox(I18n.get("connEdit.useCustomSettings"));
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
        closeWithoutConfirmCheck = new CheckBox(I18n.get("connEdit.closeWithoutConfirm"));
        closeWithoutConfirmCheck.setSelected(connSettings != null && connSettings.isCloseWithoutConfirmation());
        
        // Command timestamps
        commandTimestampsCheck = new CheckBox(I18n.get("settings.terminal.commandTimestamps"));
        commandTimestampsCheck.setSelected(connSettings != null && connSettings.isCommandTimestampsEnabled());
        commandTimestampsCheck.setTooltip(new javafx.scene.control.Tooltip(I18n.get("settings.terminal.commandTimestamps.tooltip")));
        
        // Theme selector
        themeCombo = new ComboBox<>();
        themeCombo.setPromptText(I18n.get("connEdit.theme"));
        themeCombo.setPrefWidth(200);
        try {
            ThemeManager tm = de.kortty.KorTTYApplication.getInstance().getThemeManager();
            if (tm != null) {
                themeCombo.getItems().add(null); // Custom
                themeCombo.getItems().addAll(tm.getThemes());
                if (connSettings != null && connSettings.getThemeId() != null) {
                    tm.getTheme(connSettings.getThemeId()).ifPresent(themeCombo::setValue);
                } else {
                    themeCombo.setValue(null);
                }
            }
        } catch (Exception e) {
            // Theme manager not available
        }
        themeCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Theme item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? I18n.get("theme.custom") : item.getName());
            }
        });
        themeCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(Theme item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? I18n.get("theme.custom") : item.getName());
            }
        });
        themeCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                fontFamilyCombo.setValue(newVal.getFontFamily());
                fontSizeSpinner.getValueFactory().setValue(newVal.getFontSize());
                foregroundColorPicker.setValue(Color.web(newVal.getForegroundColor()));
                backgroundColorPicker.setValue(Color.web(newVal.getBackgroundColor()));
            }
        });
        
        // Layout
        int row = 0;
        settingsGrid.add(new Label(I18n.get("connEdit.theme")), 0, row);
        settingsGrid.add(themeCombo, 1, row++);
        
        settingsGrid.add(new Label(I18n.get("settings.font.family")), 0, row);
        settingsGrid.add(fontFamilyCombo, 1, row++);
        
        settingsGrid.add(new Label(I18n.get("settings.font.size")), 0, row);
        settingsGrid.add(fontSizeSpinner, 1, row++);
        
        settingsGrid.add(new Separator(), 0, row++, 2, 1);
        
        settingsGrid.add(new Label(I18n.get("settings.colors.foreground")), 0, row);
        settingsGrid.add(foregroundColorPicker, 1, row++);
        
        settingsGrid.add(new Label(I18n.get("connEdit.backgroundColor")), 0, row);
        settingsGrid.add(backgroundColorPicker, 1, row++);
        
        settingsGrid.add(new Separator(), 0, row++, 2, 1);
        
        settingsGrid.add(closeWithoutConfirmCheck, 0, row++, 2, 1);
        settingsGrid.add(commandTimestampsCheck, 0, row++, 2, 1);
        
        // Enable/disable settings grid based on checkbox
        useCustomSettingsCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settingsGrid.setDisable(!newVal);
        });
        
        vbox.getChildren().addAll(
                useCustomSettingsCheck,
                new Label(I18n.get("connEdit.customSettingsInfo")),
                settingsGrid
        );
        
        tab.setContent(vbox);
        return tab;
    }
    
    private void browseForKey() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("connEdit.selectPrivateKey"));
        
        // Start in .ssh directory if it exists
        File sshDir = new File(System.getProperty("user.home"), ".ssh");
        if (sshDir.exists()) {
            fileChooser.setInitialDirectory(sshDir);
        }
        
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.get("connEdit.allFiles"), "*.*"),
                new FileChooser.ExtensionFilter(I18n.get("connEdit.pemFiles"), "*.pem"),
                new FileChooser.ExtensionFilter(I18n.get("connEdit.privateKeys"), "id_rsa", "id_ed25519", "id_ecdsa")
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
        Tab tab = new Tab(I18n.get("connEdit.tab.tunnels"));
        tab.setClosable(false);
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        
        // Enable tunnel checkbox
        enableTunnelsCheck = new CheckBox(I18n.get("connEdit.enableTunnels"));
        enableTunnelsCheck.setSelected(!connection.getSshTunnels().isEmpty());
        
        // Tunnel list with better display
        Label label = new Label(I18n.get("connEdit.configuredTunnels"));
        ListView<de.kortty.model.SSHTunnel> tunnelList = new ListView<>();
        tunnelList.setCellFactory(lv -> new ListCell<de.kortty.model.SSHTunnel>() {
            @Override
            protected void updateItem(de.kortty.model.SSHTunnel tunnel, boolean empty) {
                super.updateItem(tunnel, empty);
                if (empty || tunnel == null) {
                    setText(null);
                } else {
                    String status = tunnel.isEnabled() ? "✓" : "○";
                    String desc = tunnel.getDescription() != null && !tunnel.getDescription().trim().isEmpty() 
                        ? " - " + tunnel.getDescription() 
                        : "";
                    
                    if (tunnel.getType() == de.kortty.model.TunnelType.DYNAMIC) {
                        setText(String.format("%s %s: SOCKS-Proxy auf %s:%d%s", 
                            status, tunnel.getType(), tunnel.getLocalHost(), tunnel.getLocalPort(), desc));
                    } else {
                        setText(String.format("%s %s: %s:%d -> %s:%d%s", 
                            status, tunnel.getType(), tunnel.getLocalHost(), tunnel.getLocalPort(),
                            tunnel.getRemoteHost(), tunnel.getRemotePort(), desc));
                    }
                }
            }
        });
        
        // Load existing tunnels
        tunnelList.getItems().addAll(connection.getSshTunnels());
        
        // Buttons for add/edit/remove
        HBox buttonBox = new HBox(10);
        Button addButton = new Button(I18n.get("dialog.add"));
        Button editButton = new Button(I18n.get("dialog.edit"));
        Button removeButton = new Button(I18n.get("connEdit.remove"));
        
        addButton.setOnAction(e -> {
            TunnelEditDialog dialog = new TunnelEditDialog((Stage) getDialogPane().getScene().getWindow(), null);
            dialog.showAndWait().ifPresent(newTunnel -> {
                connection.getSshTunnels().add(newTunnel);
                tunnelList.getItems().add(newTunnel);
                enableTunnelsCheck.setSelected(true);
            });
        });
        
        editButton.setDisable(true);
        removeButton.setDisable(true);
        
        tunnelList.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            boolean selected = newVal != null;
            editButton.setDisable(!selected);
            removeButton.setDisable(!selected);
        });
        
        editButton.setOnAction(e -> {
            de.kortty.model.SSHTunnel selected = tunnelList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                // Create a copy for editing
                de.kortty.model.SSHTunnel copy = new de.kortty.model.SSHTunnel();
                copy.setEnabled(selected.isEnabled());
                copy.setType(selected.getType());
                copy.setLocalHost(selected.getLocalHost());
                copy.setLocalPort(selected.getLocalPort());
                copy.setRemoteHost(selected.getRemoteHost());
                copy.setRemotePort(selected.getRemotePort());
                copy.setDescription(selected.getDescription());
                
                TunnelEditDialog dialog = new TunnelEditDialog((Stage) getDialogPane().getScene().getWindow(), copy);
                dialog.showAndWait().ifPresent(editedTunnel -> {
                    int index = tunnelList.getSelectionModel().getSelectedIndex();
                    connection.getSshTunnels().set(index, editedTunnel);
                    tunnelList.getItems().set(index, editedTunnel);
                });
            }
        });
        
        removeButton.setOnAction(e -> {
            de.kortty.model.SSHTunnel selected = tunnelList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
                confirm.setTitle(I18n.get("connEdit.removeTunnel.title"));
                confirm.setHeaderText(I18n.get("connEdit.removeTunnel.header"));
                confirm.setContentText(I18n.get("connEdit.removeTunnel.content"));
                
                confirm.showAndWait().ifPresent(buttonType -> {
                    if (buttonType == ButtonType.OK) {
                        int index = tunnelList.getSelectionModel().getSelectedIndex();
                        connection.getSshTunnels().remove(index);
                        tunnelList.getItems().remove(index);
                        
                        if (connection.getSshTunnels().isEmpty()) {
                            enableTunnelsCheck.setSelected(false);
                        }
                    }
                });
            }
        });
        
        buttonBox.getChildren().addAll(addButton, editButton, removeButton);
        
        Label infoLabel = new Label(I18n.get("connEdit.tunnelInfo"));
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        infoLabel.setWrapText(true);
        
        vbox.getChildren().addAll(enableTunnelsCheck, new Separator(), label, tunnelList, buttonBox, infoLabel);
        VBox.setVgrow(tunnelList, Priority.ALWAYS);
        
        tab.setContent(vbox);
        return tab;
    }
    
    private Tab createJumpServerTab() {
        Tab tab = new Tab(I18n.get("connEdit.tab.jumpServer"));
        tab.setClosable(false);
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        
        // Enable jump server checkbox
        enableJumpCheck = new CheckBox(I18n.get("connEdit.enableJump"));
        de.kortty.model.JumpServer jumpServer = connection.getJumpServer();
        enableJumpCheck.setSelected(jumpServer != null && jumpServer.isEnabled());
        
        // Jump server configuration
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setDisable(jumpServer == null || !jumpServer.isEnabled());
        
        TextField jumpHostField = new TextField();
        jumpHostField.setPromptText(I18n.get("connEdit.jumpHostPrompt"));
        if (jumpServer != null) jumpHostField.setText(jumpServer.getHost());
        
        Spinner<Integer> jumpPortSpinner = new Spinner<>(1, 65535, jumpServer != null ? jumpServer.getPort() : 22);
        jumpPortSpinner.setEditable(true);
        jumpPortSpinner.setPrefWidth(80);
        
        TextField jumpUserField = new TextField();
        jumpUserField.setPromptText(I18n.get("common.username"));
        if (jumpServer != null) jumpUserField.setText(jumpServer.getUsername());
        
        PasswordField jumpPasswordField = new PasswordField();
        jumpPasswordField.setPromptText(I18n.get("connEdit.passwordOptional"));
        
        TextField autoCommandField = new TextField();
        autoCommandField.setPromptText(I18n.get("connEdit.autoCommandPrompt"));
        if (jumpServer != null) autoCommandField.setText(jumpServer.getAutoCommand());
        
        int row = 0;
        grid.add(new Label(I18n.get("connEdit.jumpHost")), 0, row);
        HBox hostBox = new HBox(10);
        hostBox.getChildren().addAll(jumpHostField, new Label(I18n.get("common.port") + ":"), jumpPortSpinner);
        grid.add(hostBox, 1, row++);
        
        grid.add(new Label(I18n.get("common.username") + ":"), 0, row);
        grid.add(jumpUserField, 1, row++);
        
        grid.add(new Label(I18n.get("common.password") + ":"), 0, row);
        grid.add(jumpPasswordField, 1, row++);
        
        grid.add(new Label(I18n.get("connEdit.autoCommand")), 0, row);
        grid.add(autoCommandField, 1, row++);
        
        enableJumpCheck.selectedProperty().addListener((obs, old, newVal) -> {
            grid.setDisable(!newVal);
        });
        
        Label infoLabel = new Label(I18n.get("connEdit.jumpInfo"));
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        infoLabel.setWrapText(true);
        
        vbox.getChildren().addAll(enableJumpCheck, new Separator(), grid, infoLabel);
        
        tab.setContent(vbox);
        return tab;
    }
    
    private Tab createLoggingTab() {
        Tab tab = new Tab(I18n.get("connEdit.tab.logging"));
        tab.setClosable(false);
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        
        // Enable logging checkbox
        enableLoggingCheck = new CheckBox(I18n.get("connEdit.enableLogging"));
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
        
        Button browseLogButton = new Button(I18n.get("connEdit.browse"));
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
        grid.add(new Label(I18n.get("connEdit.logFile")), 0, row);
        HBox logPathBox = new HBox(10);
        logPathBox.getChildren().addAll(logFilePathField, browseLogButton);
        grid.add(logPathBox, 1, row++);
        
        grid.add(new Label(I18n.get("connEdit.maxFileSize")), 0, row);
        HBox sizeBox = new HBox(10);
        sizeBox.getChildren().addAll(maxFileSizeMBSpinner, new Label("MB"));
        grid.add(sizeBox, 1, row++);
        
        grid.add(new Label(I18n.get("connEdit.format")), 0, row);
        grid.add(logFormatCombo, 1, row++);
        
        // Enable/disable grid based on checkbox
        enableLoggingCheck.selectedProperty().addListener((obs, old, newVal) -> {
            grid.setDisable(!newVal);
        });
        
        Label infoLabel = new Label(I18n.get("connEdit.loggingInfo"));
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        infoLabel.setWrapText(true);
        
        vbox.getChildren().addAll(enableLoggingCheck, new Separator(), grid, infoLabel);
        
        tab.setContent(vbox);
        return tab;
    }
    
    private void browseForLogFile() {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(I18n.get("connEdit.selectLogFile"));
        
        // Start in user home
        File homeDir = new File(System.getProperty("user.home"));
        fileChooser.setInitialDirectory(homeDir);
        
        // Suggest file name based on connection
        String suggestedName = connection.getName() != null ? 
                connection.getName().replaceAll("[^a-zA-Z0-9-_]", "_") + ".log" : 
                "terminal.log";
        fileChooser.setInitialFileName(suggestedName);
        
        fileChooser.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter(I18n.get("connEdit.logFiles"), "*.log", "*.txt", "*.xml", "*.json"),
                new FileChooser.ExtensionFilter(I18n.get("connEdit.allFiles"), "*.*")
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
    
    private Tab createGeometryTab() {
        Tab tab = new Tab(I18n.get("connEdit.tab.geometry"));
        tab.setClosable(false);
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        
        // Use custom geometry checkbox
        useCustomGeometryCheck = new CheckBox(I18n.get("connEdit.useCustomGeometry"));
        WindowGeometry connGeo = connection.getWindowGeometry();
        useCustomGeometryCheck.setSelected(connGeo != null);
        
        Label infoLabel = new Label(
            I18n.get("connEdit.geometryInfo")
        );
        infoLabel.setWrapText(true);
        infoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        
        // Get current or default values
        int currentWidth = connGeo != null ? (int)connGeo.getWidth() : 1200;
        int currentHeight = connGeo != null ? (int)connGeo.getHeight() : 800;
        int currentX = connGeo != null ? (int)connGeo.getX() : 100;
        int currentY = connGeo != null ? (int)connGeo.getY() : 100;
        boolean isMaximized = connGeo != null && connGeo.isMaximized();
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(10));
        
        int row = 0;
        
        // Width and Height
        Label sizeLabel = new Label(I18n.get("connEdit.windowSize"));
        customWidthSpinner = new Spinner<>(400, 4000, currentWidth);
        customWidthSpinner.setEditable(true);
        customWidthSpinner.setPrefWidth(100);
        customWidthSpinner.setDisable(!useCustomGeometryCheck.isSelected());
        
        customHeightSpinner = new Spinner<>(300, 3000, currentHeight);
        customHeightSpinner.setEditable(true);
        customHeightSpinner.setPrefWidth(100);
        customHeightSpinner.setDisable(!useCustomGeometryCheck.isSelected());
        
        HBox sizeBox = new HBox(10);
        sizeBox.getChildren().addAll(new Label(I18n.get("settings.window.fixedWidth")), customWidthSpinner, new Label(I18n.get("settings.window.fixedHeight")), customHeightSpinner, new Label("px"));
        
        grid.add(sizeLabel, 0, row);
        grid.add(sizeBox, 1, row++);
        
        // Position
        Label posLabel = new Label(I18n.get("connEdit.windowPosition"));
        customXSpinner = new Spinner<>(0, 5000, currentX);
        customXSpinner.setEditable(true);
        customXSpinner.setPrefWidth(100);
        customXSpinner.setDisable(!useCustomGeometryCheck.isSelected());
        
        customYSpinner = new Spinner<>(0, 3000, currentY);
        customYSpinner.setEditable(true);
        customYSpinner.setPrefWidth(100);
        customYSpinner.setDisable(!useCustomGeometryCheck.isSelected());
        
        HBox posBox = new HBox(10);
        posBox.getChildren().addAll(new Label("X:"), customXSpinner, new Label("Y:"), customYSpinner, new Label("px"));
        
        grid.add(posLabel, 0, row);
        grid.add(posBox, 1, row++);
        
        // Maximized checkbox
        maximizedCheck = new CheckBox(I18n.get("connEdit.openMaximized"));
        maximizedCheck.setSelected(isMaximized);
        maximizedCheck.setDisable(!useCustomGeometryCheck.isSelected());
        grid.add(maximizedCheck, 1, row++);
        
        // Enable/disable fields based on checkbox
        useCustomGeometryCheck.selectedProperty().addListener((obs, old, newVal) -> {
            customWidthSpinner.setDisable(!newVal);
            customHeightSpinner.setDisable(!newVal);
            customXSpinner.setDisable(!newVal);
            customYSpinner.setDisable(!newVal);
            maximizedCheck.setDisable(!newVal);
        });
        
        vbox.getChildren().addAll(useCustomGeometryCheck, infoLabel, new Separator(), grid);
        
        tab.setContent(vbox);
        return tab;
    }

}
