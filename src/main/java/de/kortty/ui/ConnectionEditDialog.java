package de.kortty.ui;

import de.kortty.model.AiProfile;
import de.kortty.model.AiSkill;
import de.kortty.model.AiSkillTarget;
import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;

import de.kortty.model.ServerConnection;
import de.kortty.model.StoredCredential;
import de.kortty.model.SSHKey;
import de.kortty.core.CredentialManager;
import de.kortty.core.SSHKeyManager;
import de.kortty.core.ThemeManager;
import de.kortty.security.EncryptionService;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.Theme;
import de.kortty.model.WindowGeometry;
import de.kortty.plugin.terminaleffects.TerminalEffectAnimationSpeed;
import com.sithtermfx.core.emulator.EmulationType;
import de.kortty.core.TerminalEmulationSupport;
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
public class ConnectionEditDialog extends ThemeAwareDialog<ServerConnection> {
    
    private static final Logger logger = LoggerFactory.getLogger(ConnectionEditDialog.class);
    
    private final ServerConnection connection;
    private final CredentialManager credentialManager;
    private final SSHKeyManager sshKeyManager;
    private final char[] masterPassword;
    private ComboBox<StoredCredential> savedCredentialsCombo;
    private ComboBox<SSHKey> savedSSHKeysCombo;
    private ComboBox<AiProfileOption> aiProfileCombo;
    private java.util.Map<String, javafx.beans.property.BooleanProperty> aiSkillChecksById;
    private java.util.List<String> unavailableAssignedAiSkillIds = java.util.List.of();

    private final TextField nameField;
    private final TextField hostField;
    private final Spinner<Integer> portSpinner;
    private final ComboBox<ConnectionProtocol> protocolCombo;
    private final ComboBox<EmulationType> terminalEmulationCombo;
    private final TextField usernameField;
    private final PasswordField passwordField;
    private final TextField groupField;
    private final ToggleGroup authMethodGroup;
    private final RadioButton passwordAuthRadio;
    private final RadioButton keyAuthRadio;
    private RadioButton temporaryKeyAuthRadio; // null when global setting "temporary SSH key" is disabled
    private final TextField keyPathField;
    private final Button browseKeyButton;
    private final PasswordField keyPassphraseField;
    private TextArea temporaryKeyArea;
    private Spinner<Integer> temporaryKeyExpirationSpinner;
    private CheckBox temporaryKeyPermanentCheck;

    // Local shell (LOCAL_SHELL protocol) controls — preset ids live in LocalShellPresetSupport.
    /** Resolved launch commands for optional Windows shells, or null when not on Windows / not installed. */
    private final String gitBashCommand = de.kortty.core.LocalShellTtyConnector.findWindowsGitBashCommand();
    private final String cygwinCommand = de.kortty.core.LocalShellTtyConnector.findWindowsCygwinCommand();
    private final String wslCommand = de.kortty.core.LocalShellTtyConnector.findWindowsWslCommand();
    private ComboBox<String> shellPresetCombo;
    private TextField customShellCommandField;
    private TextField shellWorkingDirField;
    
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
    private CheckBox terminalColorsEnabledCheck;
    private CheckBox closeWithoutConfirmCheck;
    private CheckBox commandTimestampsCheck;
    private ComboBox<TerminalEffectUiSupport.Option> terminalEffectCombo;
    private TerminalEffectUiSupport.AnimationSpeedControls terminalEffectSpeedControls;
    
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
        
        boolean temporarySshKeyEnabled = false;
        try {
            de.kortty.core.GlobalSettingsManager gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            if (gsm != null && gsm.getSettings() != null) {
                temporarySshKeyEnabled = gsm.getSettings().isTemporarySshKeyEnabled();
            }
        } catch (Exception e) {
            // use default false
        }
        
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

        protocolCombo = new ComboBox<>();
        protocolCombo.getItems().addAll(ConnectionProtocol.SSH_TCP, ConnectionProtocol.MOSH, ConnectionProtocol.MOSH_CLIENT, ConnectionProtocol.LOCAL_SHELL);
        protocolCombo.setValue(connection.getProtocol());
        protocolCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ConnectionProtocol item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(protocolDisplayName(item));
                }
            }
        });
        protocolCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ConnectionProtocol item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(I18n.get("protocol.sshTcp"));
                } else {
                    setText(protocolDisplayName(item));
                }
            }
        });

        terminalEmulationCombo = new ComboBox<>();
        TerminalEmulationComboBoxSupport.configureComboBox(terminalEmulationCombo);
        TerminalEmulationComboBoxSupport.select(terminalEmulationCombo, connection.getTerminalEmulationType());
        terminalEmulationCombo.setPrefWidth(300);
        
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
        
        if (temporarySshKeyEnabled) {
            temporaryKeyAuthRadio = new RadioButton(I18n.get("connEdit.authTempKey"));
            temporaryKeyAuthRadio.setToggleGroup(authMethodGroup);
        } else {
            temporaryKeyAuthRadio = null;
        }
        
        // Determine initial selection
        if (temporaryKeyAuthRadio != null && connection.getTemporaryKeyContent() != null && !connection.getTemporaryKeyContent().trim().isEmpty()) {
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
        
        // Temporary SSH Key fields (only when global setting is enabled)
        if (temporarySshKeyEnabled) {
            temporaryKeyArea = new TextArea();
            temporaryKeyArea.setPromptText(I18n.get("connEdit.tempKeyPrompt"));
            temporaryKeyArea.setPrefRowCount(5);
            temporaryKeyArea.setWrapText(true);
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
            temporaryKeyPermanentCheck.setTooltip(new Tooltip(I18n.get("connEdit.tempKeyPermanentTooltip")));
        } else {
            temporaryKeyArea = null;
            temporaryKeyExpirationSpinner = null;
            temporaryKeyPermanentCheck = null;
        }
        
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

        connectionGrid.add(new Label(I18n.get("connEdit.protocol")), 0, row);
        connectionGrid.add(protocolCombo, 1, row++);

        // Local shell controls (only relevant for LOCAL_SHELL protocol) — OS-appropriate shells only.
        shellPresetCombo = new ComboBox<>();
        LocalShellPresetSupport.configure(shellPresetCombo, gitBashCommand, cygwinCommand, wslCommand);
        customShellCommandField = new TextField();
        customShellCommandField.setPromptText(I18n.get("connEdit.shellCommandPrompt"));
        shellWorkingDirField = new TextField();
        shellWorkingDirField.setPromptText(I18n.get("connEdit.shellWorkingDirPrompt"));
        loadLocalShellSelection();
        shellPresetCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateLocalShellFields());

        connectionGrid.add(new Label(I18n.get("connEdit.shell")), 0, row);
        connectionGrid.add(shellPresetCombo, 1, row++);
        connectionGrid.add(new Label(I18n.get("connEdit.shellCommand")), 0, row);
        connectionGrid.add(customShellCommandField, 1, row++);
        connectionGrid.add(new Label(I18n.get("connEdit.shellWorkingDir")), 0, row);
        connectionGrid.add(shellWorkingDirField, 1, row++);

        connectionGrid.add(new Label(I18n.get("connEdit.terminalEmulation")), 0, row);
        connectionGrid.add(terminalEmulationCombo, 1, row++);
        
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
        HBox authBox = new HBox(15);
        authBox.getChildren().addAll(passwordAuthRadio, keyAuthRadio);
        if (temporaryKeyAuthRadio != null) {
            authBox.getChildren().add(temporaryKeyAuthRadio);
        }
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
        
        // Temporary SSH Key section (only when global setting is enabled)
        if (temporaryKeyAuthRadio != null && temporaryKeyArea != null) {
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
        }
        
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

        // Tab 7: AI profile and connection-scoped skills
        Tab aiTab = createAiTab();

        tabPane.getTabs().addAll(connectionTab, settingsTab, tunnelsTab, jumpServerTab, loggingTab, geometryTab, aiTab);
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
        protocolCombo.valueProperty().addListener((obs, oldVal, newVal) -> {
            updateLocalShellFields();
            validateForm(saveButton);
        });
        updateLocalShellFields();
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
                connection.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : ConnectionProtocol.SSH_TCP);
                if (connection.getProtocol() == ConnectionProtocol.LOCAL_SHELL) {
                    connection.setLocalShellCommand(effectiveShellCommand());
                    String workingDir = shellWorkingDirField.getText() != null ? shellWorkingDirField.getText().trim() : "";
                    connection.setLocalShellWorkingDirectory(workingDir.isEmpty() ? null : workingDir);
                }
                connection.setTerminalEmulationType(TerminalEmulationSupport.storedValue(
                    TerminalEmulationComboBoxSupport.selectedEmulation(terminalEmulationCombo)));
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
                        customSettings = new ConnectionSettings();
                        selTheme.applyTo(customSettings, isThemeFontApplyEnabled());
                        customSettings.setThemeId(selTheme.getId());
                    } else {
                        customSettings = new ConnectionSettings();
                    }
                    if (fontFamilyCombo != null) customSettings.setFontFamily(fontFamilyCombo.getValue());
                    if (fontSizeSpinner != null) customSettings.setFontSize(fontSizeSpinner.getValue());
                    if (foregroundColorPicker != null) customSettings.setForegroundColor(toHex(foregroundColorPicker.getValue()));
                    if (backgroundColorPicker != null) customSettings.setBackgroundColor(toHex(backgroundColorPicker.getValue()));
                    if (terminalColorsEnabledCheck != null) customSettings.setTerminalColorsEnabled(terminalColorsEnabledCheck.isSelected());
                    if (closeWithoutConfirmCheck != null) customSettings.setCloseWithoutConfirmation(closeWithoutConfirmCheck.isSelected());
                    if (commandTimestampsCheck != null) customSettings.setCommandTimestampsEnabled(commandTimestampsCheck.isSelected());
                    connection.setSettings(customSettings);
                } else {
                    connection.setSettings(null); // Use global settings
                }
                saveTerminalEffectSettings();
                
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
                
                // Save AI profile and connection-scoped skills
                applyAiSelections();

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
    
    /** Combo entry for the per-connection AI profile; profileId == null means "use default". */
    private record AiProfileOption(String profileId, String label) {
    }

    private Tab createAiTab() {
        Tab tab = new Tab(I18n.get("connEdit.tab.ai"));
        tab.setClosable(false);

        GlobalSettings globalSettings = null;
        try {
            de.kortty.core.GlobalSettingsManager gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            globalSettings = gsm != null ? gsm.getSettings() : null;
        } catch (Exception e) {
            logger.debug("Could not load global settings for AI tab: {}", e.getMessage());
        }

        aiProfileCombo = new ComboBox<>();
        aiProfileCombo.setPrefWidth(340);
        aiProfileCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiProfileOption option) {
                return option != null ? option.label() : "";
            }

            @Override
            public AiProfileOption fromString(String string) {
                return null;
            }
        });
        AiProfileOption defaultOption = new AiProfileOption(null, I18n.get("connEdit.ai.profile.default"));
        aiProfileCombo.getItems().add(defaultOption);
        if (globalSettings != null && globalSettings.getAiProfiles() != null) {
            globalSettings.getAiProfiles().stream()
                .filter(profile -> profile != null && profile.getId() != null && !profile.getId().isBlank())
                .sorted((left, right) -> aiProfileLabel(left).compareToIgnoreCase(aiProfileLabel(right)))
                .forEach(profile -> aiProfileCombo.getItems().add(
                    new AiProfileOption(profile.getId(), aiProfileLabel(profile))));
        }
        String storedProfileId = connection.getAiProfileId();
        AiProfileOption selection = defaultOption;
        if (storedProfileId != null && !storedProfileId.isBlank()) {
            selection = aiProfileCombo.getItems().stream()
                .filter(option -> storedProfileId.equals(option.profileId()))
                .findFirst()
                .orElse(null);
            if (selection == null) {
                // Keep the stored id so the fixed profile is used again once it is available;
                // until then the default profile acts as fallback at runtime.
                selection = new AiProfileOption(storedProfileId, I18n.get("connEdit.ai.profile.missing"));
                aiProfileCombo.getItems().add(selection);
            }
        }
        aiProfileCombo.getSelectionModel().select(selection);

        Label profileHint = new Label(I18n.get("connEdit.ai.profile.hint"));
        profileHint.setWrapText(true);
        profileHint.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-inner-color;");

        java.util.Set<String> assignedSkillIds = new java.util.LinkedHashSet<>(connection.getAiSkillIds());
        java.util.List<AiSkill> connectionSkills = new java.util.ArrayList<>();
        if (globalSettings != null && globalSettings.getAiSkills() != null) {
            for (AiSkill skill : globalSettings.getAiSkills()) {
                if (skill != null && skill.getId() != null) {
                    connectionSkills.add(skill);
                }
            }
        }
        connectionSkills.sort(java.util.Comparator
            .comparing((AiSkill skill) -> skill.getTarget() != AiSkillTarget.CONNECTION)
            .thenComparing(ConnectionEditDialog::aiSkillLabel, String.CASE_INSENSITIVE_ORDER));

        aiSkillChecksById = new java.util.LinkedHashMap<>();
        for (AiSkill skill : connectionSkills) {
            aiSkillChecksById.put(skill.getId(),
                new javafx.beans.property.SimpleBooleanProperty(assignedSkillIds.contains(skill.getId())));
        }
        java.util.List<String> unavailableIds = new java.util.ArrayList<>(assignedSkillIds);
        unavailableIds.removeAll(aiSkillChecksById.keySet());
        unavailableAssignedAiSkillIds = unavailableIds;

        Label skillsHint = new Label(I18n.get("connEdit.ai.skills.hint"));
        skillsHint.setWrapText(true);
        skillsHint.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-inner-color;");

        VBox content = new VBox(10);
        content.setPadding(new Insets(20));
        content.getChildren().addAll(
            new Label(I18n.get("connEdit.ai.profile")),
            aiProfileCombo,
            profileHint,
            new Label(I18n.get("connEdit.ai.skills")),
            skillsHint);
        if (connectionSkills.isEmpty()) {
            Label noSkillsLabel = new Label(I18n.get("connEdit.ai.skills.none"));
            noSkillsLabel.setWrapText(true);
            content.getChildren().add(noSkillsLabel);
        } else {
            ListView<AiSkill> skillsListView = new ListView<>(
                javafx.collections.FXCollections.observableArrayList(connectionSkills));
            skillsListView.setPrefHeight(200);
            skillsListView.setCellFactory(javafx.scene.control.cell.CheckBoxListCell.forListView(
                skill -> aiSkillChecksById.get(skill.getId()),
                new javafx.util.StringConverter<>() {
                    @Override
                    public String toString(AiSkill skill) {
                        return aiSkillListLabel(skill);
                    }

                    @Override
                    public AiSkill fromString(String string) {
                        return null;
                    }
                }));
            VBox.setVgrow(skillsListView, Priority.ALWAYS);
            content.getChildren().add(skillsListView);
        }

        tab.setContent(content);
        return tab;
    }

    private static String aiProfileLabel(AiProfile profile) {
        if (profile == null) {
            return "";
        }
        String name = profile.getName();
        return name != null && !name.isBlank() ? name.trim() : profile.getId();
    }

    private static String aiSkillLabel(AiSkill skill) {
        if (skill == null) {
            return "";
        }
        String name = skill.getName();
        return name != null && !name.isBlank() ? name.trim() : I18n.get("settings.aiSkills.defaultName");
    }

    static String aiSkillListLabel(AiSkill skill) {
        if (skill == null) {
            return "";
        }
        AiSkillTarget target = skill.getTarget() != null ? skill.getTarget() : AiSkillTarget.BOTH;
        String targetLabel = I18n.get("settings.aiSkills.target." + target.name().toLowerCase(java.util.Locale.ROOT));
        return aiSkillLabel(skill) + " (" + targetLabel + ")";
    }

    private void applyAiSelections() {
        AiProfileOption selectedOption = aiProfileCombo != null ? aiProfileCombo.getValue() : null;
        connection.setAiProfileId(selectedOption != null ? selectedOption.profileId() : null);
        if (aiSkillChecksById == null) {
            return;
        }
        java.util.List<String> selectedSkillIds = new java.util.ArrayList<>();
        aiSkillChecksById.forEach((skillId, checked) -> {
            if (checked != null && checked.get()) {
                selectedSkillIds.add(skillId);
            }
        });
        // Assignments to skills that are currently not stored (e.g. deleted and re-imported later)
        // are preserved instead of being dropped silently.
        selectedSkillIds.addAll(unavailableAssignedAiSkillIds);
        connection.setAiSkillIds(selectedSkillIds);
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
        boolean useTemporaryKey = temporaryKeyAuthRadio != null && temporaryKeyAuthRadio.isSelected();
        
        passwordField.setDisable(useKey || useTemporaryKey);
        savedCredentialsCombo.setDisable(useKey || useTemporaryKey);
        savedSSHKeysCombo.setDisable(!useKey || useTemporaryKey);
        keyPathField.setDisable(!useKey || useTemporaryKey);
        browseKeyButton.setDisable(!useKey || useTemporaryKey);
        keyPassphraseField.setDisable(!useKey || useTemporaryKey);
        
        if (temporaryKeyArea != null) {
            temporaryKeyArea.setDisable(!useTemporaryKey);
            temporaryKeyExpirationSpinner.setDisable(!useTemporaryKey);
            temporaryKeyPermanentCheck.setDisable(!useTemporaryKey);
        }
    }
    
    private void validateForm(Button saveButton) {
        boolean localShell = protocolCombo.getValue() == ConnectionProtocol.LOCAL_SHELL;
        String hostText = hostField.getText();
        // Local shells have no host; everything else requires a non-empty host.
        boolean valid = localShell || (hostText != null && !hostText.trim().isEmpty());
        if (valid && !localShell && connection.isTeamworkConnection()) {
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

        terminalColorsEnabledCheck = new CheckBox(I18n.get("settings.colors.terminalColors"));
        terminalColorsEnabledCheck.setSelected(connSettings == null || connSettings.isTerminalColorsEnabled());
        terminalColorsEnabledCheck.setTooltip(new javafx.scene.control.Tooltip(I18n.get("settings.colors.terminalColors.tooltip")));
        
        // Close without confirmation
        closeWithoutConfirmCheck = new CheckBox(I18n.get("connEdit.closeWithoutConfirm"));
        closeWithoutConfirmCheck.setSelected(connSettings != null && connSettings.isCloseWithoutConfirmation());
        
        // Command timestamps
        commandTimestampsCheck = new CheckBox(I18n.get("settings.terminal.commandTimestamps"));
        commandTimestampsCheck.setSelected(connSettings != null && connSettings.isCommandTimestampsEnabled());
        commandTimestampsCheck.setTooltip(new javafx.scene.control.Tooltip(I18n.get("settings.terminal.commandTimestamps.tooltip")));

        terminalEffectCombo = new ComboBox<>();
        terminalEffectCombo.setPrefWidth(220);
        TerminalEffectUiSupport.configureComboBox(terminalEffectCombo);
        TerminalEffectUiSupport.selectPlugin(terminalEffectCombo, connection.getTerminalEffectPluginId());

        terminalEffectSpeedControls = TerminalEffectUiSupport.createAnimationSpeedControls(
                connection.getTerminalEffectAnimationSpeed() != null
                        ? connection.getTerminalEffectAnimationSpeed()
                        : TerminalEffectAnimationSpeed.DEFAULT);
        terminalEffectCombo.valueProperty().addListener((obs, oldValue, newValue) -> updateTerminalEffectSpeedState());
        updateTerminalEffectSpeedState();
        
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
        settingsGrid.add(terminalColorsEnabledCheck, 0, row++, 2, 1);
        
        settingsGrid.add(new Separator(), 0, row++, 2, 1);
        
        settingsGrid.add(closeWithoutConfirmCheck, 0, row++, 2, 1);
        settingsGrid.add(commandTimestampsCheck, 0, row++, 2, 1);
        
        // Enable/disable settings grid based on checkbox
        useCustomSettingsCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            settingsGrid.setDisable(!newVal);
        });

        GridPane terminalEffectGrid = new GridPane();
        terminalEffectGrid.setHgap(10);
        terminalEffectGrid.setVgap(10);
        terminalEffectGrid.setPadding(new Insets(10));
        int effectRow = 0;
        terminalEffectGrid.add(new Label(I18n.get("connection.terminalEffect")), 0, effectRow);
        terminalEffectGrid.add(terminalEffectCombo, 1, effectRow++);
        terminalEffectGrid.add(new Label(I18n.get("connection.animationSpeed")), 0, effectRow);
        terminalEffectGrid.add(terminalEffectSpeedControls.root(), 1, effectRow);

        Label terminalEffectLabel = new Label(I18n.get("connection.terminalEffect"));
        terminalEffectLabel.setStyle("-fx-font-weight: bold;");
        
        vbox.getChildren().addAll(
                useCustomSettingsCheck,
                new Label(I18n.get("connEdit.customSettingsInfo")),
                settingsGrid
        );
        if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            vbox.getChildren().addAll(
                    new Separator(),
                    terminalEffectLabel,
                    terminalEffectGrid
            );
        }
        
        tab.setContent(vbox);
        return tab;
    }

    private void updateTerminalEffectSpeedState() {
        String pluginId = TerminalEffectUiSupport.selectedPluginId(terminalEffectCombo);
        boolean enabled = pluginId != null;
        if (terminalEffectSpeedControls != null) {
            terminalEffectSpeedControls.setDisable(!enabled);
        }
    }

    private void saveTerminalEffectSettings() {
        if (!TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            return;
        }
        String pluginId = TerminalEffectUiSupport.selectedPluginId(terminalEffectCombo);
        connection.setTerminalEffectPluginId(pluginId);
        connection.setTerminalEffectAnimationSpeed(TerminalEffectUiSupport.animationSpeedForStorage(
                pluginId,
                terminalEffectSpeedControls != null
                        ? terminalEffectSpeedControls.getValue()
                        : TerminalEffectAnimationSpeed.DEFAULT));
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

    private boolean isThemeFontApplyEnabled() {
        try {
            var gs = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return gs != null && gs.isApplyThemeFonts();
        } catch (Exception e) {
            return false;
        }
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

    private String protocolDisplayName(ConnectionProtocol protocol) {
        if (protocol == ConnectionProtocol.MOSH) {
            return I18n.get("protocol.mosh");
        }
        if (protocol == ConnectionProtocol.MOSH_CLIENT) {
            return I18n.get("protocol.moshClient");
        }
        if (protocol == ConnectionProtocol.LOCAL_SHELL) {
            return I18n.get("protocol.localShell");
        }
        return I18n.get("protocol.sshTcp");
    }

    /** Initializes the shell controls from the connection's stored localShellCommand. */
    private void loadLocalShellSelection() {
        String command = connection.getLocalShellCommand();
        String preset = LocalShellPresetSupport.presetForCommand(command, gitBashCommand, cygwinCommand, wslCommand);
        shellPresetCombo.setValue(preset);
        if (LocalShellPresetSupport.CUSTOM.equals(preset) && command != null && !command.isBlank()) {
            customShellCommandField.setText(command);
        }
        if (connection.getLocalShellWorkingDirectory() != null) {
            shellWorkingDirField.setText(connection.getLocalShellWorkingDirectory());
        }
    }

    /** The shell command to persist: the selected preset, or the custom field when "custom". */
    private String effectiveShellCommand() {
        return LocalShellPresetSupport.commandFor(
            shellPresetCombo.getValue(),
            customShellCommandField.getText(),
            gitBashCommand, cygwinCommand, wslCommand);
    }

    /** Enables shell fields and disables SSH fields for LOCAL_SHELL, and vice-versa. */
    private void updateLocalShellFields() {
        boolean local = protocolCombo.getValue() == ConnectionProtocol.LOCAL_SHELL;

        shellPresetCombo.setDisable(!local);
        shellWorkingDirField.setDisable(!local);
        customShellCommandField.setDisable(!local || !LocalShellPresetSupport.CUSTOM.equals(shellPresetCombo.getValue()));

        hostField.setDisable(local);
        portSpinner.setDisable(local);
        usernameField.setDisable(local);
        passwordAuthRadio.setDisable(local);
        keyAuthRadio.setDisable(local);
        if (temporaryKeyAuthRadio != null) {
            temporaryKeyAuthRadio.setDisable(local);
        }
        if (local) {
            passwordField.setDisable(true);
            savedCredentialsCombo.setDisable(true);
            savedSSHKeysCombo.setDisable(true);
            keyPathField.setDisable(true);
            browseKeyButton.setDisable(true);
            keyPassphraseField.setDisable(true);
            if (temporaryKeyArea != null) {
                temporaryKeyArea.setDisable(true);
                temporaryKeyExpirationSpinner.setDisable(true);
                temporaryKeyPermanentCheck.setDisable(true);
            }
        } else {
            // Restore auth-driven enablement of the credential/key fields.
            updateAuthFields();
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
