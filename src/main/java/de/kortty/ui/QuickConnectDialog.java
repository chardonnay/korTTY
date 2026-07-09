package de.kortty.ui;

import com.sithtermfx.core.emulator.EmulationType;
import de.kortty.core.TerminalEmulationSupport;
import de.kortty.model.ServerConnection;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.SSHKey;
import de.kortty.model.Theme;
import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.StoredCredential;
import de.kortty.model.TemporarySSHKey;
import de.kortty.security.PasswordVault;
import de.kortty.core.SSHKeyManager;
import de.kortty.core.TemporarySSHKeyManager;
import de.kortty.plugin.terminaleffects.TerminalEffectAnimationSpeed;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.util.Duration;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Quick connect dialog with support for:
 * - Top N most frequently used connections
 * - Individual connection
 * - Group selection (open all tabs in a group)
 */
public class QuickConnectDialog extends ThemeAwareDialog<QuickConnectDialog.ConnectionResult> {
    
    private final List<ServerConnection> savedConnections;
    private final PasswordVault passwordVault;
    private final de.kortty.core.CredentialManager credentialManager;
    private final SSHKeyManager sshKeyManager;
    private final char[] masterPassword;
    private final int topConnectionsCount;

    // Scroll wrapper around the form; its viewport is fitted to min(content, screen cap) so the
    // dialog is compact when short and scrolls (rather than clipping or leaving dead space) when the
    // form + expanded sections exceed the screen.
    private ScrollPane contentScroll;
    private double viewportHeightCap;

    private boolean ignoreSavedCredentialsEvents;
    
    // Individual connection tab
    private ComboBox<ServerConnection> savedConnectionsCombo;
    private TextField savedConnectionsSearchField;
    private ComboBox<AiProfileOption> aiProfileCombo;
    private AiProfileOption aiProfileDefaultOption;
    private AiProfileOption aiProfileMissingOption;
    private java.util.Map<String, javafx.beans.property.BooleanProperty> aiSkillChecksById;
    private java.util.List<String> unavailableAssignedAiSkillIds = java.util.List.of();

    /** Combo entry for the per-connection AI profile; profileId == null means "use default". */
    private record AiProfileOption(String profileId, String label) {
    }
    private TextField hostField;
    private Spinner<Integer> portSpinner;
    private ComboBox<ConnectionProtocol> protocolCombo;
    private ComboBox<EmulationType> terminalEmulationCombo;
    private TextField usernameField;
    private PasswordField passwordField;

    // Local shell (LOCAL_SHELL protocol) controls — preset ids live in LocalShellPresetSupport.
    /** Resolved launch commands for optional Windows shells, or null when not on Windows / not installed. */
    private final String gitBashCommand = de.kortty.core.LocalShellTtyConnector.findWindowsGitBashCommand();
    private final String cygwinCommand = de.kortty.core.LocalShellTtyConnector.findWindowsCygwinCommand();
    private final String wslCommand = de.kortty.core.LocalShellTtyConnector.findWindowsWslCommand();
    private ComboBox<String> shellPresetCombo;
    private TextField customShellCommandField;
    private TextField shellWorkingDirField;
    private ComboBox<StoredCredential> savedCredentialsCombo;
    private ToggleGroup authMethodGroup;
    private RadioButton passwordAuthRadio;
    private RadioButton keyAuthRadio;
    private RadioButton temporaryKeyAuthRadio;
    private ComboBox<SSHKey> savedSSHKeysCombo;
    private TextArea temporaryKeyArea;
    private Spinner<Integer> expirationMinutesSpinner;
    private Label remainingTimeLabel;
    private Timeline expirationTimer;
    private TemporarySSHKey currentTemporaryKey;
    private CheckBox saveConnectionCheck;
    private TextField connectionNameField;
    private Spinner<Integer> timeoutSpinner;
    private Spinner<Integer> retrySpinner;
    
    // Terminal appearance
    private ComboBox<Theme> themeCombo;
    private ComboBox<String> fontFamilyCombo;
    private Spinner<Integer> fontSizeSpinner;
    private ColorPicker foregroundColorPicker;
    private ColorPicker backgroundColorPicker;
    private CheckBox terminalColorsEnabledCheck;
    private ComboBox<TerminalEffectUiSupport.Option> terminalEffectCombo;
    private TerminalEffectUiSupport.AnimationSpeedControls terminalEffectSpeedControls;
    
    // Group tab
    private ListView<String> groupListView;
    
    public QuickConnectDialog(Stage owner, List<ServerConnection> savedConnections, PasswordVault passwordVault, 
                              de.kortty.core.CredentialManager credentialManager, SSHKeyManager sshKeyManager,
                              char[] masterPassword, int topConnectionsCount) {
        this.savedConnections = savedConnections;
        this.passwordVault = passwordVault;
        this.credentialManager = credentialManager;
        this.sshKeyManager = sshKeyManager;
        this.masterPassword = masterPassword;
        this.topConnectionsCount = topConnectionsCount;
        
        setTitle(I18n.get("quickConnect.title"));
        setHeaderText(null);
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);
        
        VBox mainContent = new VBox(10);
        mainContent.setPadding(new Insets(15));
        mainContent.setPrefWidth(750);
        mainContent.setMinWidth(700);
        
        // Top N most used connections
        if (savedConnections != null && !savedConnections.isEmpty()) {
            VBox topConnections = createTopConnectionsSection();
            if (topConnections != null) {
                mainContent.getChildren().add(topConnections);
                mainContent.getChildren().add(new Separator());
            }
        }
        
        // TabPane for Individual vs Group connection
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);

        Tab individualTab = new Tab(I18n.get("quickConnect.individualConnection"));
        // Scroll the individual-connection FORM only, directly inside its tab: the ScrollPane must be
        // the immediate parent of the growing VBox. An earlier attempt wrapped the whole dialog
        // content (header + TabPane + form) in one ScrollPane, but the TabPane's content region
        // between the ScrollPane and the form swallowed the height growth when a collapsible section
        // was expanded — the scroll range never grew and the expanded content was clipped. With the
        // ScrollPane directly around the form, expanding a section grows the VBox's preferred height
        // and the scroll range follows; header and tab strip stay fixed above.
        contentScroll = new ScrollPane(createIndividualConnectionPane());
        contentScroll.setFitToWidth(true);
        contentScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        contentScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // Blend the viewport into the dialog (no grey frame on the dark theme).
        contentScroll.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        // Fixed, compact preferred viewport height, bounded well below any screen (the window chrome —
        // header, tab strip, buttons — adds ~350px on top). The form is taller than this even fully
        // collapsed, so there is never dead space and the scroll bar engages whenever needed. The
        // dialog stays resizable; enlarging the window grows the viewport (VGROW below).
        viewportHeightCap = Math.min(Screen.getPrimary().getVisualBounds().getHeight() * 0.55, 620);
        contentScroll.setPrefViewportHeight(viewportHeightCap);
        individualTab.setContent(contentScroll);

        Tab groupTab = new Tab(I18n.get("quickConnect.openGroup"));
        groupTab.setContent(createGroupSelectionPane());

        tabPane.getTabs().addAll(individualTab, groupTab);

        mainContent.getChildren().add(tabPane);
        VBox.setVgrow(tabPane, Priority.ALWAYS);

        getDialogPane().setContent(mainContent);
        getDialogPane().setMinWidth(700);
        getDialogPane().setPrefWidth(750);
        
        // Buttons
        ButtonType connectButtonType = new ButtonType(I18n.get("quickConnect.connect"), ButtonBar.ButtonData.OK_DONE);
        ButtonType openGroupButtonType = new ButtonType(I18n.get("quickConnect.openGroup"), ButtonBar.ButtonData.OK_DONE);
        ButtonType loadProjectButtonType = new ButtonType(I18n.get("quickConnect.loadProject"), ButtonBar.ButtonData.OTHER);
        getDialogPane().getButtonTypes().addAll(connectButtonType, openGroupButtonType, loadProjectButtonType, ButtonType.CANCEL);
        
        // Show/hide buttons based on selected tab
        Button connectButton = (Button) getDialogPane().lookupButton(connectButtonType);
        Button openGroupButton = (Button) getDialogPane().lookupButton(openGroupButtonType);
        Button loadProjectButton = (Button) getDialogPane().lookupButton(loadProjectButtonType);
        
        // Style the "Projekt laden" button to make it stand out
        loadProjectButton.setStyle("-fx-font-weight: normal;");
        
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> {
            if (newTab == individualTab) {
                connectButton.setVisible(true);
                connectButton.setManaged(true);
                openGroupButton.setVisible(false);
                openGroupButton.setManaged(false);
            } else {
                connectButton.setVisible(false);
                connectButton.setManaged(false);
                openGroupButton.setVisible(true);
                openGroupButton.setManaged(true);
            }
            // "Projekt laden" button is always visible
        });
        
        // Initially show only connect button (and load project button)
        openGroupButton.setVisible(false);
        openGroupButton.setManaged(false);
        
        // Enable/disable connect button: needs a host, EXCEPT for local shells which have none.
        connectButton.setDisable(true);
        Runnable updateConnectButton = () -> {
            boolean local = protocolCombo.getValue() == ConnectionProtocol.LOCAL_SHELL;
            connectButton.setDisable(!local && hostField.getText().trim().isEmpty());
        };
        hostField.textProperty().addListener((obs, old, newVal) -> updateConnectButton.run());
        protocolCombo.valueProperty().addListener((obs, old, newVal) -> {
            updateLocalShellFields();
            updateConnectButton.run();
        });
        updateLocalShellFields();
        updateConnectButton.run();
        
        // Enable/disable group button
        openGroupButton.setDisable(true);
        groupListView.getSelectionModel().selectedItemProperty().addListener((obs, old, newVal) -> {
            openGroupButton.setDisable(newVal == null);
        });
        
        // Result converter
        setResultConverter(dialogButton -> {
            QuickConnectDialog.ConnectionResult result = null;
            
            if (dialogButton == connectButtonType) {
                result = createIndividualResult();
                if (result != null && result.connection() != null) {
                    applyAiAssignments(result.connection());
                }
            } else if (dialogButton == openGroupButtonType) {
                String selectedGroup = groupListView.getSelectionModel().getSelectedItem();
                if (selectedGroup != null) {
                    result = new ConnectionResult(null, null, false, false, selectedGroup, false, null);
                }
            } else if (dialogButton == loadProjectButtonType) {
                // Special result to signal "load project"
                result = new ConnectionResult(null, null, false, false, null, true, null);
            }
            
            // Save terminal settings when dialog is closed (except cancel)
            if (result != null && dialogButton != ButtonType.CANCEL) {
                saveTerminalSettings();
            }
            
            return result;
        });
    }
    
    private VBox createTopConnectionsSection() {
        // Show the N last used connections, ordered by last used (most recent first).
        int maxCount = Math.max(1, topConnectionsCount);
        List<ServerConnection> recentConnections = savedConnections.stream()
                .filter(c -> c.getLastUsed() > 0)
                .sorted((a, b) -> Long.compare(b.getLastUsed(), a.getLastUsed()))
                .limit(maxCount)
                .collect(Collectors.toList());
        
        if (recentConnections.isEmpty()) {
            return null;
        }
        
        VBox box = new VBox(8);
        Label label = new Label(I18n.get("quickConnect.frequentlyUsed"));
        label.setStyle("-fx-font-weight: bold;");
        
        javafx.scene.layout.TilePane buttonContainer = new javafx.scene.layout.TilePane();
        buttonContainer.setHgap(8);
        buttonContainer.setVgap(8);
        buttonContainer.setPadding(new Insets(5));
        buttonContainer.setPrefColumns(Math.min(5, maxCount));
        
        for (ServerConnection conn : recentConnections) {
            Button btn = new Button(conn.getName());
            btn.setPrefWidth(140);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setTooltip(new Tooltip(conn.getUsername() + "@" + conn.getHost() + ":" + conn.getPort() + 
                    "\n" + I18n.get("quickConnect.usageCount") + ": " + conn.getUsageCount() + "x" +
                    "\n" + I18n.get("quickConnect.lastUsed") + ": " + new java.util.Date(conn.getLastUsed())));
            btn.setOnAction(e -> {
                // Fill in the form and close dialog
                fillFormWithConnection(conn);
                // Look up temporary SSH key from manager if connection uses one
                TemporarySSHKey tempKeyForBtn = null;
                ServerConnection selectedConn = ServerConnection.copyForAuth(conn);
                if (!TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
                    selectedConn.setTerminalEffectPluginId(null);
                    selectedConn.setTerminalEffectAnimationSpeed(null);
                }
                if (conn.getTemporaryKeyContent() != null && !conn.getTemporaryKeyContent().trim().isEmpty()) {
                    tempKeyForBtn = TemporarySSHKeyManager.getInstance().getTemporaryKey(conn.getTemporaryKeyContent());
                    if (tempKeyForBtn == null || !tempKeyForBtn.isValid()) {
                        // Key expired or not found - store fresh from persisted content
                        Long expMin = conn.getTemporaryKeyExpirationMinutes();
                        tempKeyForBtn = TemporarySSHKeyManager.getInstance().storeTemporaryKey(
                                conn.getTemporaryKeyContent(), (expMin != null && expMin > 0) ? expMin : 15L);
                        // storeTemporaryKey may return null if key content is incomplete - that's OK
                    }
                    // Ensure privateKeyPath is set for the connector (use stored content directly)
                    selectedConn.setPrivateKeyPath("TEMPORARY:" + conn.getTemporaryKeyContent());
                    selectedConn.setAuthMethod(AuthMethod.PUBLIC_KEY);
                }
                setResult(new ConnectionResult(selectedConn, 
                        getConnectionPassword(conn), 
                        false, true, null, false, tempKeyForBtn));
                close();
            });
            buttonContainer.getChildren().add(btn);
        }

        box.getChildren().addAll(label, buttonContainer);
        return box;
    }
    
    private VBox createIndividualConnectionPane() {
        VBox pane = new VBox(15);
        pane.setPadding(new Insets(15));
        
        boolean temporarySshKeyEnabled = false;
        try {
            de.kortty.core.GlobalSettingsManager gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            if (gsm != null && gsm.getSettings() != null) {
                temporarySshKeyEnabled = gsm.getSettings().isTemporarySshKeyEnabled();
            }
        } catch (Exception e) {
            // use default false
        }
        
        // Create form fields
        hostField = new TextField();
        hostField.setPromptText("hostname or IP");
        hostField.setPrefWidth(250);
        
        portSpinner = new Spinner<>(1, 65535, 22);
        portSpinner.setEditable(true);
        portSpinner.setPrefWidth(80);

        protocolCombo = new ComboBox<>();
        protocolCombo.getItems().addAll(ConnectionProtocol.SSH_TCP, ConnectionProtocol.MOSH, ConnectionProtocol.MOSH_CLIENT, ConnectionProtocol.LOCAL_SHELL);
        protocolCombo.setValue(ConnectionProtocol.SSH_TCP);
        protocolCombo.setPrefWidth(180);
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

        // Local shell controls (only relevant for LOCAL_SHELL protocol) — OS-appropriate shells only.
        shellPresetCombo = new ComboBox<>();
        LocalShellPresetSupport.configure(shellPresetCombo, gitBashCommand, cygwinCommand, wslCommand);
        customShellCommandField = new TextField();
        customShellCommandField.setPromptText(I18n.get("connEdit.shellCommandPrompt"));
        shellWorkingDirField = new TextField();
        shellWorkingDirField.setPromptText(I18n.get("connEdit.shellWorkingDirPrompt"));
        shellPresetCombo.valueProperty().addListener((obs, oldVal, newVal) -> updateLocalShellFields());

        terminalEmulationCombo = new ComboBox<>();
        TerminalEmulationComboBoxSupport.configureComboBox(terminalEmulationCombo);
        TerminalEmulationComboBoxSupport.select(
                terminalEmulationCombo,
                TerminalEmulationSupport.defaultStoredValue());
        terminalEmulationCombo.setPrefWidth(300);
        
        usernameField = new TextField();
        usernameField.setPromptText("root");
        usernameField.setText("root");
        
        passwordField = new PasswordField();
        passwordField.setPromptText(I18n.get("quickConnect.password"));

        savedCredentialsCombo = new ComboBox<>();
        savedCredentialsCombo.setPromptText(I18n.get("connEdit.selectCredential"));
        savedCredentialsCombo.setPrefWidth(300);
        savedCredentialsCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(StoredCredential item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getName() + " (" + item.getUsername() + ")");
                }
            }
        });
        savedCredentialsCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(StoredCredential item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(I18n.get("connEdit.selectCredential"));
                } else {
                    setText(item.getName() + " (" + item.getUsername() + ")");
                }
            }
        });
        savedCredentialsCombo.setOnAction(e -> {
            if (ignoreSavedCredentialsEvents) return;
            StoredCredential cred = savedCredentialsCombo.getValue();
            if (cred != null) {
                usernameField.setText(cred.getUsername());
                String pw = resolveCredentialPassword(cred);
                if (pw != null) {
                    passwordField.setText(pw);
                }
                passwordAuthRadio.setSelected(true);
            }
        });
        
        // Authentication method
        authMethodGroup = new ToggleGroup();
        passwordAuthRadio = new RadioButton(I18n.get("quickConnect.authPassword"));
        passwordAuthRadio.setToggleGroup(authMethodGroup);
        passwordAuthRadio.setSelected(true);
        
        keyAuthRadio = new RadioButton(I18n.get("quickConnect.authKey"));
        keyAuthRadio.setToggleGroup(authMethodGroup);
        
        if (temporarySshKeyEnabled) {
            temporaryKeyAuthRadio = new RadioButton(I18n.get("quickConnect.authTemporaryKey"));
            temporaryKeyAuthRadio.setToggleGroup(authMethodGroup);
        } else {
            temporaryKeyAuthRadio = null;
        }
        
        // SSH Key selection
        savedSSHKeysCombo = new ComboBox<>();
        savedSSHKeysCombo.setPromptText(I18n.get("quickConnect.sshKey") + " " + I18n.get("quickConnect.selectSaved"));
        savedSSHKeysCombo.setPrefWidth(300);
        savedSSHKeysCombo.setDisable(true);
        if (sshKeyManager != null) {
            savedSSHKeysCombo.getItems().addAll(sshKeyManager.getAllKeys());
        }
        
        // Temporary SSH Key fields (only when global setting is enabled)
        if (temporarySshKeyEnabled) {
            temporaryKeyArea = new TextArea();
            temporaryKeyArea.setPromptText(I18n.get("quickConnect.temporarySSHKeyPrompt"));
            temporaryKeyArea.setPrefRowCount(5);
            temporaryKeyArea.setWrapText(true);
            temporaryKeyArea.setDisable(true);
            expirationMinutesSpinner = new Spinner<>(1, 1440, 15);
            expirationMinutesSpinner.setEditable(true);
            expirationMinutesSpinner.setPrefWidth(80);
            expirationMinutesSpinner.setDisable(true);
            remainingTimeLabel = new Label();
            remainingTimeLabel.setStyle("-fx-text-fill: #00ff00; -fx-font-weight: bold;");
            remainingTimeLabel.setDisable(true);
            TemporarySSHKey existingKey = TemporarySSHKeyManager.getInstance().getCurrentTemporaryKey();
            if (existingKey != null && existingKey.isValid()) {
                temporaryKeyArea.setText(existingKey.getKeyContent());
                long remainingMinutes = existingKey.getRemainingSeconds() / 60;
                expirationMinutesSpinner.getValueFactory().setValue(Math.max(1, (int) remainingMinutes));
                currentTemporaryKey = existingKey;
                temporaryKeyAuthRadio.setSelected(true);
                startExpirationTimer();
            } else if (existingKey != null) {
                TemporarySSHKeyManager.getInstance().removeTemporaryKey(existingKey.getKeyContent());
            }
        } else {
            temporaryKeyArea = null;
            expirationMinutesSpinner = null;
            remainingTimeLabel = null;
        }
        
        // Update field states based on auth method
        authMethodGroup.selectedToggleProperty().addListener((obs, old, newVal) -> applyAuthFieldStates());
        
        if (temporaryKeyArea != null) {
            temporaryKeyArea.textProperty().addListener((obs, old, newVal) -> {
                if (temporaryKeyAuthRadio != null && temporaryKeyAuthRadio.isSelected() && newVal != null && !newVal.trim().isEmpty()) {
                    long expirationMinutes = expirationMinutesSpinner.getValue();
                    currentTemporaryKey = TemporarySSHKeyManager.getInstance().storeTemporaryKey(
                        newVal.trim(), expirationMinutes);
                    startExpirationTimer();
                } else if (newVal == null || newVal.trim().isEmpty()) {
                    stopExpirationTimer();
                    currentTemporaryKey = null;
                }
            });
        }
        
        // Update expiration time when spinner changes - only update if user explicitly changes it
        // Note: We don't auto-update the key expiration when spinner changes because
        // storeTemporaryKey() preserves existing expiration for the same key.
        // User needs to click "Update Key" button to explicitly reset the expiration time.
        
        saveConnectionCheck = new CheckBox(I18n.get("quickConnect.saveConnection"));
        
        connectionNameField = new TextField();
        connectionNameField.setPromptText(I18n.get("quickConnect.connectionNamePrompt"));
        connectionNameField.setDisable(true);
        
        timeoutSpinner = new Spinner<>(1, 300, 10);
        timeoutSpinner.setEditable(true);
        timeoutSpinner.setPrefWidth(80);

        retrySpinner = new Spinner<>(0, 20, 0);
        retrySpinner.setEditable(true);
        retrySpinner.setPrefWidth(80);
        
        // Load last used timeout and retries values
        loadConnectionSettings();
        updateCredentialCombo(hostField.getText());
        hostField.textProperty().addListener((obs, oldVal, newVal) -> updateCredentialCombo(newVal));
        
        saveConnectionCheck.selectedProperty().addListener((obs, old, newVal) -> {
            connectionNameField.setDisable(!newVal);
        });
        
        // Saved connections dropdown
        savedConnectionsCombo = new ComboBox<>();
        savedConnectionsCombo.setPromptText(I18n.get("quickConnect.selectSaved"));
        savedConnectionsCombo.setPrefWidth(400);
        savedConnectionsCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ServerConnection item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getName() + " (" + item.getUsername() + "@" + item.getHost() + ":" + item.getPort() + ")");
                }
            }
        });
        savedConnectionsCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ServerConnection item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(I18n.get("quickConnect.selectSaved"));
                } else {
                    setText(item.getName() + " (" + item.getUsername() + "@" + item.getHost() + ")");
                }
            }
        });
        
        if (savedConnections != null && !savedConnections.isEmpty()) {
            savedConnectionsCombo.getItems().addAll(savedConnections);
        }

        // Search field to filter the saved connections (supports '*' wildcards).
        savedConnectionsSearchField = new TextField();
        savedConnectionsSearchField.setPromptText(I18n.get("quickConnect.searchSaved"));
        savedConnectionsSearchField.setPrefWidth(400);
        savedConnectionsSearchField.textProperty().addListener((obs, oldVal, newVal) -> filterSavedConnections(newVal));
        // ENTER in the search field loads the first (top) match immediately.
        savedConnectionsSearchField.setOnAction(e -> {
            java.util.List<ServerConnection> matches = savedConnectionsCombo.getItems();
            if (!matches.isEmpty()) {
                ServerConnection first = matches.get(0);
                savedConnectionsCombo.setValue(first);
                applySavedConnection(first);
                savedConnectionsCombo.hide();
            }
        });

        // When a saved connection is selected, fill in the fields
        savedConnectionsCombo.setOnAction(e -> applySavedConnection(savedConnectionsCombo.getValue()));
        
        // Layout
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        
        // Set column constraints to ensure labels are fully visible
        ColumnConstraints labelColumn = new ColumnConstraints();
        labelColumn.setMinWidth(200);
        labelColumn.setPrefWidth(220);
        labelColumn.setHgrow(Priority.NEVER);
        
        ColumnConstraints inputColumn = new ColumnConstraints();
        inputColumn.setHgrow(Priority.ALWAYS);
        inputColumn.setMinWidth(400);
        
        grid.getColumnConstraints().addAll(labelColumn, inputColumn);
        
        grid.add(new Label(I18n.get("quickConnect.host")), 0, 0);
        HBox hostBox = new HBox(10);
        hostBox.getChildren().addAll(hostField, new Label(I18n.get("quickConnect.port")), portSpinner);
        grid.add(hostBox, 1, 0);
        
        grid.add(new Label(I18n.get("quickConnect.username")), 0, 1);
        grid.add(usernameField, 1, 1);

        grid.add(new Label(I18n.get("quickConnect.protocol")), 0, 2);
        grid.add(protocolCombo, 1, 2);

        grid.add(new Label(I18n.get("quickConnect.terminalEmulation")), 0, 3);
        grid.add(terminalEmulationCombo, 1, 3);

        grid.add(new Label(I18n.get("quickConnect.authentication")), 0, 4);
        VBox authBox = new VBox(5);
        authBox.getChildren().addAll(passwordAuthRadio, keyAuthRadio);
        if (temporaryKeyAuthRadio != null) {
            authBox.getChildren().add(temporaryKeyAuthRadio);
        }
        grid.add(authBox, 1, 4);
        
        grid.add(new Label(I18n.get("quickConnect.password")), 0, 5);
        grid.add(passwordField, 1, 5);
        
        grid.add(new Label(I18n.get("connEdit.savedCredentials")), 0, 6);
        grid.add(savedCredentialsCombo, 1, 6);
        
        grid.add(new Label(I18n.get("quickConnect.sshKey")), 0, 7);
        grid.add(savedSSHKeysCombo, 1, 7);
        
        int row = 8;
        if (temporaryKeyAuthRadio != null && temporaryKeyArea != null) {
            grid.add(new Label(I18n.get("quickConnect.temporarySSHKey")), 0, row);
            VBox tempKeyBox = new VBox(5);
            tempKeyBox.getChildren().add(temporaryKeyArea);
            HBox expirationBox = new HBox(10);
            expirationBox.getChildren().addAll(
                new Label(I18n.get("quickConnect.expirationTime")),
                expirationMinutesSpinner,
                new Label(I18n.get("quickConnect.expirationMinutes"))
            );
            tempKeyBox.getChildren().add(expirationBox);
            HBox updateBox = new HBox(10);
            updateBox.getChildren().add(remainingTimeLabel);
            Button updateTempKeyButton = new Button(I18n.get("quickConnect.updateTempKey"));
            updateTempKeyButton.setTooltip(new Tooltip(I18n.get("quickConnect.updateTempKey.tooltip")));
            updateTempKeyButton.setOnAction(e -> {
                if (temporaryKeyAuthRadio.isSelected() && temporaryKeyArea.getText() != null && !temporaryKeyArea.getText().trim().isEmpty()) {
                    long expirationMinutes = expirationMinutesSpinner.getValue();
                    currentTemporaryKey = TemporarySSHKeyManager.getInstance().updateKeyExpiration(
                        temporaryKeyArea.getText().trim(), expirationMinutes);
                    if (currentTemporaryKey != null) {
                        startExpirationTimer();
                        if (remainingTimeLabel != null) remainingTimeLabel.setStyle("-fx-text-fill: #00ff00; -fx-font-weight: bold;");
                    }
                }
            });
            Button replaceTempKeyButton = new Button(I18n.get("quickConnect.replaceTempKey"));
            replaceTempKeyButton.setTooltip(new Tooltip(I18n.get("quickConnect.replaceTempKey.tooltip")));
            replaceTempKeyButton.setOnAction(e -> {
                if (temporaryKeyAuthRadio.isSelected()) {
                    if (currentTemporaryKey != null) {
                        TemporarySSHKeyManager.getInstance().removeTemporaryKey(currentTemporaryKey.getKeyContent());
                        currentTemporaryKey = null;
                    }
                    stopExpirationTimer();
                    temporaryKeyArea.clear();
                    temporaryKeyArea.setPromptText(I18n.get("quickConnect.temporarySSHKeyPrompt"));
                    if (remainingTimeLabel != null) remainingTimeLabel.setText("");
                    javafx.application.Platform.runLater(() -> temporaryKeyArea.requestFocus());
                }
            });
            updateBox.getChildren().addAll(updateTempKeyButton, replaceTempKeyButton);
            tempKeyBox.getChildren().add(updateBox);
            grid.add(tempKeyBox, 1, row);
            row++;
        }

        // Local shell controls (only relevant for LOCAL_SHELL protocol)
        grid.add(new Label(I18n.get("connEdit.shell")), 0, row);
        grid.add(shellPresetCombo, 1, row++);
        grid.add(new Label(I18n.get("connEdit.shellCommand")), 0, row);
        grid.add(customShellCommandField, 1, row++);
        grid.add(new Label(I18n.get("connEdit.shellWorkingDir")), 0, row);
        grid.add(shellWorkingDirField, 1, row++);

        grid.add(saveConnectionCheck, 1, row++);
        
        grid.add(new Label(I18n.get("quickConnect.connectionName")), 0, row);
        grid.add(connectionNameField, 1, row++);
        
        // ---- Optional settings grouped into collapsible sections. They start COLLAPSED so the
        // ---- dialog opens compact; expanding one grows the window to fit (see collapsibleSection).
        VBox collapsibleSections = new VBox(8);

        // ===== Connection Timeout =====
        GridPane timeoutGrid = sectionGrid();
        timeoutGrid.add(new Label(I18n.get("quickConnect.connectionTimeout")), 0, 0);
        HBox timeoutBox = new HBox(10);
        timeoutBox.getChildren().addAll(timeoutSpinner, new Label(I18n.get("common.seconds")));
        timeoutGrid.add(timeoutBox, 1, 0);
        timeoutGrid.add(new Label(I18n.get("quickConnect.retries")), 0, 1);
        HBox retryBox = new HBox(10);
        retryBox.getChildren().addAll(retrySpinner, new Label(I18n.get("quickConnect.attempts")));
        timeoutGrid.add(retryBox, 1, 1);
        collapsibleSections.getChildren().add(
            collapsibleSection("connectionTimeout", I18n.get("quickConnect.section.connectionTimeout"), timeoutGrid));

        // ===== Terminal Appearance =====
        GridPane appearanceGrid = sectionGrid();
        int arow = 0;
        // Theme selector
        themeCombo = new ComboBox<>();
        themeCombo.setPromptText(I18n.get("quickConnect.theme"));
        themeCombo.setPrefWidth(200);
        try {
            var tm = de.kortty.KorTTYApplication.getInstance().getThemeManager();
            if (tm != null) {
                themeCombo.getItems().add(null);
                themeCombo.getItems().addAll(tm.getThemes());
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
                        foregroundColorPicker.setValue(javafx.scene.paint.Color.web(newVal.getForegroundColor()));
                        backgroundColorPicker.setValue(javafx.scene.paint.Color.web(newVal.getBackgroundColor()));
                    }
                });
            }
        } catch (Exception e) {
            // Theme manager not available
        }
        appearanceGrid.add(new Label(I18n.get("quickConnect.theme")), 0, arow);
        appearanceGrid.add(themeCombo, 1, arow++);

        fontFamilyCombo = new ComboBox<>();
        fontFamilyCombo.getItems().addAll(getMonospaceFonts());
        fontFamilyCombo.setValue("Monospaced");
        fontFamilyCombo.setPrefWidth(200);

        fontSizeSpinner = new Spinner<>(8, 72, 14);
        fontSizeSpinner.setEditable(true);
        fontSizeSpinner.setPrefWidth(80);

        appearanceGrid.add(new Label(I18n.get("quickConnect.font")), 0, arow);
        HBox fontBox = new HBox(10);
        fontBox.getChildren().addAll(fontFamilyCombo, new Label(I18n.get("quickConnect.fontSize")), fontSizeSpinner);
        appearanceGrid.add(fontBox, 1, arow++);

        foregroundColorPicker = new ColorPicker(javafx.scene.paint.Color.web("#FFFFFF"));
        backgroundColorPicker = new ColorPicker(javafx.scene.paint.Color.web("#1E1E1E"));
        terminalColorsEnabledCheck = new CheckBox(I18n.get("settings.colors.terminalColors"));
        terminalColorsEnabledCheck.setSelected(true);
        terminalColorsEnabledCheck.setTooltip(new Tooltip(I18n.get("settings.colors.terminalColors.tooltip")));
        terminalEffectCombo = new ComboBox<>();
        terminalEffectCombo.setPrefWidth(220);
        TerminalEffectUiSupport.configureComboBox(terminalEffectCombo);

        terminalEffectSpeedControls = TerminalEffectUiSupport.createAnimationSpeedControls(
                TerminalEffectAnimationSpeed.DEFAULT);
        terminalEffectCombo.valueProperty().addListener((obs, oldValue, newValue) -> updateTerminalEffectSpeedState());
        updateTerminalEffectSpeedState();

        appearanceGrid.add(new Label(I18n.get("quickConnect.textColor")), 0, arow);
        appearanceGrid.add(foregroundColorPicker, 1, arow++);
        appearanceGrid.add(new Label(I18n.get("quickConnect.background")), 0, arow);
        appearanceGrid.add(backgroundColorPicker, 1, arow++);
        appearanceGrid.add(terminalColorsEnabledCheck, 0, arow++, 2, 1);
        collapsibleSections.getChildren().add(
            collapsibleSection("terminalAppearance", I18n.get("quickConnect.section.terminalAppearance"), appearanceGrid));

        // ===== Terminal Effect (optional) =====
        if (TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            GridPane effectGrid = sectionGrid();
            effectGrid.add(new Label(I18n.get("connection.terminalEffect")), 0, 0);
            effectGrid.add(terminalEffectCombo, 1, 0);
            effectGrid.add(new Label(I18n.get("connection.animationSpeed")), 0, 1);
            effectGrid.add(terminalEffectSpeedControls.root(), 1, 1);
            collapsibleSections.getChildren().add(
                collapsibleSection("terminalEffect", I18n.get("connection.terminalEffect"), effectGrid));
        }

        // ===== AI (profile + connection skills) =====
        java.util.List<de.kortty.model.AiProfile> aiProfiles = loadAiProfilesSorted();
        java.util.List<de.kortty.model.AiSkill> connectionAiSkills = loadConnectionTargetAiSkills();
        aiSkillChecksById = new java.util.LinkedHashMap<>();
        if (!aiProfiles.isEmpty() || !connectionAiSkills.isEmpty()) {
            VBox aiContent = new VBox(10);
            if (!aiProfiles.isEmpty()) {
                aiProfileCombo = new ComboBox<>();
                aiProfileCombo.setPrefWidth(300);
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
                aiProfileDefaultOption = new AiProfileOption(null, I18n.get("connEdit.ai.profile.default"));
                aiProfileCombo.getItems().add(aiProfileDefaultOption);
                for (de.kortty.model.AiProfile profile : aiProfiles) {
                    String name = profile.getName() != null && !profile.getName().isBlank()
                        ? profile.getName().trim()
                        : profile.getId();
                    aiProfileCombo.getItems().add(new AiProfileOption(profile.getId(), name));
                }
                aiProfileCombo.getSelectionModel().select(aiProfileDefaultOption);
                GridPane profileGrid = sectionGrid();
                profileGrid.add(new Label(I18n.get("connEdit.ai.profile")), 0, 0);
                profileGrid.add(aiProfileCombo, 1, 0);
                aiContent.getChildren().add(profileGrid);
            }
            if (!connectionAiSkills.isEmpty()) {
                aiContent.getChildren().add(buildConnectionSkillsUi(connectionAiSkills));
            }
            collapsibleSections.getChildren().add(
                collapsibleSection("ai", I18n.get("connEdit.tab.ai"), aiContent));
        }

        Button resetButton = new Button(I18n.get("quickConnect.resetToDefaults"));
        resetButton.setOnAction(e -> resetToDefaultSettings());
        HBox buttonBox = new HBox(10);
        buttonBox.getChildren().add(resetButton);

        // Load last used settings or default settings from GlobalSettings
        loadTerminalSettings();
        
        // Add to pane
        if (savedConnections != null && !savedConnections.isEmpty()) {
            Label savedLabel = new Label(I18n.get("quickConnect.savedConnections"));
            pane.getChildren().addAll(savedLabel, savedConnectionsSearchField, savedConnectionsCombo, new Separator());
        }
        pane.getChildren().addAll(grid, collapsibleSections, buttonBox);

        return pane;
    }

    /** A two-column grid (label | input) matching the main form, used for a collapsible section body. */
    private GridPane sectionGrid() {
        GridPane g = new GridPane();
        g.setHgap(10);
        g.setVgap(10);
        g.setPadding(new Insets(8, 6, 6, 6));
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(200);
        labelCol.setPrefWidth(220);
        labelCol.setHgrow(Priority.NEVER);
        ColumnConstraints inputCol = new ColumnConstraints();
        inputCol.setHgrow(Priority.ALWAYS);
        inputCol.setMinWidth(400);
        g.getColumnConstraints().addAll(labelCol, inputCol);
        return g;
    }

    /**
     * Wraps a section body in a collapsible {@link javafx.scene.control.TitledPane} whose title is the
     * section name and whose disclosure arrow toggles visibility. The expanded/collapsed state is
     * restored from and persisted to {@link de.kortty.model.GlobalSettings#getQuickConnectExpandedSections()}
     * under the given stable {@code key} (locale-independent), so the dialog reopens the way the user
     * left it. Expanding simply makes the surrounding scroll pane show more content.
     */
    private javafx.scene.control.TitledPane collapsibleSection(String key, String title, javafx.scene.Node content) {
        javafx.scene.control.TitledPane titled = new javafx.scene.control.TitledPane(title, content);
        titled.setExpanded(isSectionExpandedInSettings(key));
        titled.setAnimated(false);
        titled.expandedProperty().addListener((obs, was, now) -> persistSectionExpanded(key, now));
        return titled;
    }

    /** Whether the section was left expanded last time; false when no settings are available. */
    private boolean isSectionExpandedInSettings(String key) {
        try {
            de.kortty.core.GlobalSettingsManager gsm =
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
            return settings != null && settings.getQuickConnectExpandedSections().contains(key);
        } catch (Exception e) {
            return false; // headless/test construction without app context
        }
    }

    /** Persists a section toggle immediately; silently a no-op without app context. */
    private void persistSectionExpanded(String key, boolean expanded) {
        try {
            de.kortty.core.GlobalSettingsManager gsm =
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
            if (settings == null) {
                return;
            }
            java.util.List<String> expandedSections = settings.getQuickConnectExpandedSections();
            boolean changed = expanded ? !expandedSections.contains(key) && expandedSections.add(key)
                                       : expandedSections.remove(key);
            if (changed) {
                gsm.save();
            }
        } catch (Exception e) {
            // No global settings available (e.g. headless smoke); state simply is not remembered.
        }
    }

    /**
     * Builds the connection-skills picker: a glob-aware search field (supports {@code *} wildcards), a
     * scrollable checkbox list, and All / Clear / Save buttons. "Save" persists the current selection as
     * the default skill set pre-selected for every new connection
     * ({@link de.kortty.model.GlobalSettings#getDefaultConnectionAiSkillIds()}).
     */
    private javafx.scene.Node buildConnectionSkillsUi(java.util.List<de.kortty.model.AiSkill> skills) {
        for (de.kortty.model.AiSkill skill : skills) {
            aiSkillChecksById.put(skill.getId(), new javafx.beans.property.SimpleBooleanProperty(false));
        }
        // Pre-select the saved default skills so new connections inherit them.
        applyDefaultConnectionSkillSelection();

        javafx.collections.ObservableList<de.kortty.model.AiSkill> allSkills =
            javafx.collections.FXCollections.observableArrayList(skills);
        ListView<de.kortty.model.AiSkill> listView = new ListView<>(
            javafx.collections.FXCollections.observableArrayList(skills));
        listView.setPrefHeight(180);
        listView.setCellFactory(javafx.scene.control.cell.CheckBoxListCell.forListView(
            skill -> aiSkillChecksById.get(skill.getId()),
            new javafx.util.StringConverter<>() {
                @Override
                public String toString(de.kortty.model.AiSkill skill) {
                    return ConnectionEditDialog.aiSkillListLabel(skill);
                }

                @Override
                public de.kortty.model.AiSkill fromString(String string) {
                    return null;
                }
            }));

        // Glob-aware search ('*' wildcards), reusing the saved-connection matcher.
        TextField searchField = new TextField();
        searchField.setPromptText(I18n.get("quickConnect.skills.searchPrompt"));
        searchField.textProperty().addListener((obs, oldVal, newVal) -> {
            String text = newVal != null ? newVal.trim() : "";
            if (text.isEmpty()) {
                listView.getItems().setAll(allSkills);
                return;
            }
            java.util.function.Predicate<String> matcher = buildSavedConnectionMatcher(text);
            listView.getItems().setAll(allSkills.stream()
                .filter(skill -> matcher.test(ConnectionEditDialog.aiSkillListLabel(skill))
                    || matcher.test(skill.getName() != null ? skill.getName() : "")
                    || matcher.test(skill.getId() != null ? skill.getId() : ""))
                .collect(java.util.stream.Collectors.toList()));
        });

        Button allButton = new Button(I18n.get("quickConnect.skills.all"));
        allButton.setOnAction(e -> aiSkillChecksById.values().forEach(prop -> prop.set(true)));
        Button clearButton = new Button(I18n.get("quickConnect.skills.clear"));
        clearButton.setOnAction(e -> aiSkillChecksById.values().forEach(prop -> prop.set(false)));
        Button saveButton = new Button(I18n.get("quickConnect.skills.save"));
        saveButton.setTooltip(new Tooltip(I18n.get("quickConnect.skills.save.tooltip")));
        saveButton.setOnAction(e -> {
            saveDefaultConnectionSkills();
            // Brief inline confirmation so the user knows the default set was persisted.
            String original = I18n.get("quickConnect.skills.save");
            saveButton.setText(I18n.get("quickConnect.skills.saved"));
            saveButton.setDisable(true);
            javafx.animation.PauseTransition pause =
                new javafx.animation.PauseTransition(javafx.util.Duration.seconds(1.2));
            pause.setOnFinished(ev -> {
                saveButton.setText(original);
                saveButton.setDisable(false);
            });
            pause.play();
        });
        HBox skillButtons = new HBox(8, allButton, clearButton, saveButton);

        return new VBox(6,
            new Label(I18n.get("connEdit.ai.skills")),
            searchField,
            listView,
            skillButtons);
    }

    /** Pre-checks the skills saved as the connection default (GlobalSettings.defaultConnectionAiSkillIds). */
    private void applyDefaultConnectionSkillSelection() {
        try {
            de.kortty.core.GlobalSettingsManager gsm =
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
            if (settings == null) {
                return;
            }
            for (String skillId : settings.getDefaultConnectionAiSkillIds()) {
                javafx.beans.property.BooleanProperty prop = aiSkillChecksById.get(skillId);
                if (prop != null) {
                    prop.set(true);
                }
            }
        } catch (Exception ignored) {
            // Best-effort pre-selection.
        }
    }

    /** Persists the currently checked skills as the default set pre-selected for new connections. */
    private void saveDefaultConnectionSkills() {
        try {
            de.kortty.core.GlobalSettingsManager gsm =
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
            if (settings == null) {
                return;
            }
            java.util.List<String> selected = new java.util.ArrayList<>();
            aiSkillChecksById.forEach((skillId, checked) -> {
                if (checked != null && checked.get()) {
                    selected.add(skillId);
                }
            });
            settings.setDefaultConnectionAiSkillIds(selected);
            gsm.save();
        } catch (Exception ignored) {
            // Best-effort persistence.
        }
    }

    private java.util.List<de.kortty.model.AiSkill> loadConnectionTargetAiSkills() {
        java.util.List<de.kortty.model.AiSkill> connectionSkills = new java.util.ArrayList<>();
        try {
            de.kortty.core.GlobalSettingsManager gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
            if (settings != null && settings.getAiSkills() != null) {
                for (de.kortty.model.AiSkill skill : settings.getAiSkills()) {
                    if (skill != null && skill.getId() != null) {
                        connectionSkills.add(skill);
                    }
                }
            }
        } catch (Exception e) {
            // No global settings available; skill assignment stays hidden.
        }
        connectionSkills.sort(java.util.Comparator
            .comparing((de.kortty.model.AiSkill skill) ->
                skill.getTarget() != de.kortty.model.AiSkillTarget.CONNECTION)
            .thenComparing(skill -> skill.getName() != null ? skill.getName() : "", String.CASE_INSENSITIVE_ORDER));
        return connectionSkills;
    }

    private java.util.List<de.kortty.model.AiProfile> loadAiProfilesSorted() {
        java.util.List<de.kortty.model.AiProfile> profiles = new java.util.ArrayList<>();
        try {
            de.kortty.core.GlobalSettingsManager gsm = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings settings = gsm != null ? gsm.getSettings() : null;
            if (settings != null && settings.getAiProfiles() != null) {
                for (de.kortty.model.AiProfile profile : settings.getAiProfiles()) {
                    if (profile != null && profile.getId() != null && !profile.getId().isBlank()) {
                        profiles.add(profile);
                    }
                }
            }
        } catch (Exception e) {
            // No global settings available; profile selection stays hidden.
        }
        profiles.sort((left, right) -> {
            String leftName = left.getName() != null ? left.getName() : "";
            String rightName = right.getName() != null ? right.getName() : "";
            return leftName.compareToIgnoreCase(rightName);
        });
        return profiles;
    }

    /** Mirrors the fixed AI profile of a saved connection in the quick-connect combo. */
    private void syncAiProfileSelection(ServerConnection source) {
        if (aiProfileCombo == null) {
            return;
        }
        if (aiProfileMissingOption != null) {
            aiProfileCombo.getItems().remove(aiProfileMissingOption);
            aiProfileMissingOption = null;
        }
        String storedProfileId = source != null ? source.getAiProfileId() : null;
        if (storedProfileId == null || storedProfileId.isBlank()) {
            aiProfileCombo.getSelectionModel().select(aiProfileDefaultOption);
            return;
        }
        for (AiProfileOption option : aiProfileCombo.getItems()) {
            if (storedProfileId.equals(option.profileId())) {
                aiProfileCombo.getSelectionModel().select(option);
                return;
            }
        }
        // Keep the stored id so the fixed profile is used again once it is available;
        // until then the default profile acts as fallback at runtime.
        aiProfileMissingOption = new AiProfileOption(storedProfileId, I18n.get("connEdit.ai.profile.missing"));
        aiProfileCombo.getItems().add(aiProfileMissingOption);
        aiProfileCombo.getSelectionModel().select(aiProfileMissingOption);
    }

    /** Mirrors the assigned skills of a saved connection in the quick-connect checkboxes. */
    private void syncAiSkillChecks(ServerConnection source) {
        if (aiSkillChecksById == null) {
            return;
        }
        java.util.Set<String> assigned = source != null
            ? new java.util.LinkedHashSet<>(source.getAiSkillIds())
            : java.util.Set.of();
        aiSkillChecksById.forEach((skillId, checked) -> checked.set(assigned.contains(skillId)));
        java.util.List<String> unavailable = new java.util.ArrayList<>(assigned);
        unavailable.removeAll(aiSkillChecksById.keySet());
        unavailableAssignedAiSkillIds = unavailable;
    }

    /**
     * Applies AI profile and skill assignments to the connection the dialog returns. Only the
     * runtime copy is touched: stored connections are never modified (and therefore never saved)
     * by quick connect; persisting happens solely through the explicit "save connection" choice.
     */
    private void applyAiAssignments(ServerConnection target) {
        ServerConnection selected = savedConnectionsCombo != null ? savedConnectionsCombo.getValue() : null;
        if (target == selected) {
            // Never mutate the stored connection object from quick connect.
            return;
        }
        if (aiProfileCombo != null) {
            AiProfileOption option = aiProfileCombo.getValue();
            target.setAiProfileId(option != null ? option.profileId() : null);
        } else if (selected != null && target.getAiProfileId() == null) {
            // No profile selection UI (no profiles configured): inherit from the saved connection.
            target.setAiProfileId(selected.getAiProfileId());
        }
        if (aiSkillChecksById == null || (aiSkillChecksById.isEmpty() && unavailableAssignedAiSkillIds.isEmpty())) {
            return;
        }
        java.util.List<String> selectedSkillIds = new java.util.ArrayList<>();
        aiSkillChecksById.forEach((skillId, checked) -> {
            if (checked != null && checked.get()) {
                selectedSkillIds.add(skillId);
            }
        });
        selectedSkillIds.addAll(unavailableAssignedAiSkillIds);
        target.setAiSkillIds(selectedSkillIds);
    }

    /**
     * Filters the saved-connections dropdown by name, host, username or {@code user@host}. When the
     * query contains '*' it is treated as an anchored glob (e.g. {@code prod*}, {@code *db*});
     * otherwise it is a case-insensitive substring match. Matching mirrors the Connection Manager.
     */
    private void filterSavedConnections(String query) {
        if (savedConnectionsCombo == null || savedConnections == null) {
            return;
        }
        ServerConnection selected = savedConnectionsCombo.getValue();
        String text = query != null ? query.trim() : "";
        List<ServerConnection> filtered;
        if (text.isEmpty()) {
            filtered = new java.util.ArrayList<>(savedConnections);
        } else {
            java.util.function.Predicate<String> matcher = buildSavedConnectionMatcher(text);
            filtered = savedConnections.stream()
                .filter(connection -> matcher.test(connection.getName())
                    || matcher.test(connection.getHost())
                    || matcher.test(connection.getUsername())
                    || matcher.test(savedConnectionLabel(connection)))
                .collect(java.util.stream.Collectors.toList());
        }
        savedConnectionsCombo.getItems().setAll(filtered);
        if (selected != null && filtered.contains(selected)) {
            savedConnectionsCombo.setValue(selected);
        }
        // Live feedback: reveal the matching entries in the dropdown as the user types.
        // Open it once when matches first appear; the popup's list is bound to the items,
        // so it keeps updating live without re-opening (which would flicker on each keystroke).
        if (!text.isEmpty() && !filtered.isEmpty()) {
            if (!savedConnectionsCombo.isShowing()) {
                savedConnectionsCombo.show();
            }
        } else {
            savedConnectionsCombo.hide();
        }
    }

    /** Loads the given saved connection into the form (shared by the dropdown and the search field). */
    private void applySavedConnection(ServerConnection selected) {
        if (selected != null) {
            fillFormWithConnection(selected);
            syncAiSkillChecks(selected);
            syncAiProfileSelection(selected);
        }
    }

    private static String savedConnectionLabel(ServerConnection connection) {
        return (connection.getUsername() != null ? connection.getUsername() : "")
            + "@" + (connection.getHost() != null ? connection.getHost() : "");
    }

    // Package-private for unit testing of the glob / case-insensitive matching behaviour.
    static java.util.function.Predicate<String> buildSavedConnectionMatcher(String query) {
        if (query.contains("*")) {
            String regex = query
                .replace("\\", "\\\\").replace(".", "\\.").replace("+", "\\+").replace("?", "\\?")
                .replace("^", "\\^").replace("$", "\\$").replace("|", "\\|")
                .replace("(", "\\(").replace(")", "\\)").replace("[", "\\[").replace("]", "\\]")
                .replace("{", "\\{").replace("}", "\\}").replace("*", ".*");
            try {
                java.util.regex.Pattern pattern =
                    java.util.regex.Pattern.compile(regex, java.util.regex.Pattern.CASE_INSENSITIVE);
                return value -> value != null && pattern.matcher(value).matches();
            } catch (java.util.regex.PatternSyntaxException ignored) {
                // fall through to substring matching
            }
        }
        String lower = query.toLowerCase(java.util.Locale.ROOT);
        return value -> value != null && value.toLowerCase(java.util.Locale.ROOT).contains(lower);
    }

    private VBox createGroupSelectionPane() {
        VBox pane = new VBox(10);
        pane.setPadding(new Insets(15));
        
        Label label = new Label(I18n.get("quickConnect.selectGroup"));
        
        groupListView = new ListView<>();
        
        // Get all unique groups
        if (savedConnections != null && !savedConnections.isEmpty()) {
            Set<String> groups = savedConnections.stream()
                    .map(ServerConnection::getGroup)
                    .filter(Objects::nonNull)
                    .filter(g -> !g.trim().isEmpty())
                    .collect(Collectors.toCollection(TreeSet::new));
            
            groupListView.getItems().addAll(groups);
        }
        
        groupListView.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    long count = savedConnections.stream()
                            .filter(c -> item.equals(c.getGroup()))
                            .count();
                    setText(item + " (" + count + " " + I18n.get("quickConnect.connections") + ")");
                }
            }
        });
        
        pane.getChildren().addAll(label, groupListView);
        VBox.setVgrow(groupListView, Priority.ALWAYS);
        
        return pane;
    }
    
    private void fillFormWithConnection(ServerConnection conn) {
        hostField.setText(conn.getHost());
        portSpinner.getValueFactory().setValue(conn.getPort());
        usernameField.setText(conn.getUsername());
        protocolCombo.setValue(conn.getProtocol());
        TerminalEmulationComboBoxSupport.select(terminalEmulationCombo, conn.getTerminalEmulationType());
        updateCredentialCombo(conn.getHost());
        if (conn.getCredentialId() != null && credentialManager != null) {
            credentialManager.findCredentialById(conn.getCredentialId()).ifPresent(cred -> {
                if (savedCredentialsCombo.getItems().contains(cred)) {
                    savedCredentialsCombo.setValue(cred);
                } else {
                    savedCredentialsCombo.getItems().add(cred);
                    savedCredentialsCombo.setValue(cred);
                }
            });
        } else {
            savedCredentialsCombo.setValue(null);
        }
        
        // Set timeout and retry from connection
        timeoutSpinner.getValueFactory().setValue(conn.getConnectionTimeoutSeconds());
        retrySpinner.getValueFactory().setValue(conn.getRetryCount());
        TerminalEffectUiSupport.selectPlugin(terminalEffectCombo, conn.getTerminalEffectPluginId());
        terminalEffectSpeedControls.setValue(conn.getTerminalEffectAnimationSpeed() != null
                ? conn.getTerminalEffectAnimationSpeed()
                : TerminalEffectAnimationSpeed.DEFAULT);
        updateTerminalEffectSpeedState();
        loadTerminalSettingsIntoControls(conn.getSettings());
        
        // Set authentication method
        if (temporaryKeyAuthRadio != null && temporaryKeyArea != null && conn.getTemporaryKeyContent() != null && !conn.getTemporaryKeyContent().trim().isEmpty()) {
            TemporarySSHKey existingKey = TemporarySSHKeyManager.getInstance().getTemporaryKey(conn.getTemporaryKeyContent());
            if (existingKey != null && existingKey.isValid()) {
                temporaryKeyAuthRadio.setSelected(true);
                temporaryKeyArea.setText(existingKey.getKeyContent());
                long remainingMinutes = existingKey.getRemainingSeconds() / 60;
                expirationMinutesSpinner.getValueFactory().setValue(Math.max(1, (int) remainingMinutes));
                currentTemporaryKey = existingKey;
                startExpirationTimer();
            } else {
                temporaryKeyAuthRadio.setSelected(true);
                String savedContent = conn.getTemporaryKeyContent().trim();
                Long expMin = conn.getTemporaryKeyExpirationMinutes();
                long expirationMinutes = (expMin != null && expMin > 0) ? expMin : 15L;
                TemporarySSHKey restoredKey = TemporarySSHKeyManager.getInstance().storeTemporaryKey(
                        savedContent, expirationMinutes);
                if (restoredKey != null && restoredKey.isValid()) {
                    temporaryKeyArea.setText(restoredKey.getKeyContent());
                    long remainingMinutes = restoredKey.getRemainingSeconds() / 60;
                    expirationMinutesSpinner.getValueFactory().setValue(Math.max(1, (int) remainingMinutes));
                    currentTemporaryKey = restoredKey;
                    startExpirationTimer();
                } else {
                    temporaryKeyArea.setText(savedContent);
                    expirationMinutesSpinner.getValueFactory().setValue((int) expirationMinutes);
                    currentTemporaryKey = null;
                }
            }
        } else if (conn.getAuthMethod() == AuthMethod.PUBLIC_KEY) {
            boolean connectionUsesTempKey = conn.getTemporaryKeyContent() != null && !conn.getTemporaryKeyContent().trim().isEmpty();
            if (connectionUsesTempKey && temporaryKeyAuthRadio == null) {
                passwordAuthRadio.setSelected(true);
            } else {
                keyAuthRadio.setSelected(true);
                if (conn.getSshKeyId() != null && sshKeyManager != null) {
                    sshKeyManager.findKeyById(conn.getSshKeyId()).ifPresent(key -> savedSSHKeysCombo.setValue(key));
                }
            }
        } else {
            passwordAuthRadio.setSelected(true);
            // Try to retrieve stored password
            if (passwordVault != null) {
                String storedPassword = getConnectionPassword(conn);
                if (storedPassword != null && !storedPassword.isEmpty()) {
                    passwordField.setText(storedPassword);
                } else {
                    passwordField.clear();
                    passwordField.requestFocus();
                }
            } else {
                passwordField.clear();
                passwordField.requestFocus();
            }
        }
        saveConnectionCheck.setSelected(false);
    }

    private void updateTerminalEffectSpeedState() {
        String selectedPluginId = TerminalEffectUiSupport.selectedPluginId(terminalEffectCombo);
        boolean enabled = selectedPluginId != null;
        if (terminalEffectSpeedControls != null) {
            terminalEffectSpeedControls.setDisable(!enabled);
        }
    }

    private void loadTerminalSettingsIntoControls(ConnectionSettings settings) {
        if (settings == null) {
            return;
        }
        String themeId = settings.getThemeId();
        if (themeId != null && themeCombo != null) {
            try {
                var tm = de.kortty.KorTTYApplication.getInstance().getThemeManager();
                if (tm != null) {
                    tm.getTheme(themeId).ifPresent(themeCombo::setValue);
                }
            } catch (Exception ignored) {}
        } else if (themeCombo != null) {
            themeCombo.setValue(null);
        }
        if (themeCombo == null || themeCombo.getValue() == null) {
            if (settings.getFontFamily() != null) {
                fontFamilyCombo.setValue(settings.getFontFamily());
            }
            fontSizeSpinner.getValueFactory().setValue(settings.getFontSize());
            if (settings.getForegroundColor() != null) {
                foregroundColorPicker.setValue(javafx.scene.paint.Color.web(settings.getForegroundColor()));
            }
            if (settings.getBackgroundColor() != null) {
                backgroundColorPicker.setValue(javafx.scene.paint.Color.web(settings.getBackgroundColor()));
            }
        }
        terminalColorsEnabledCheck.setSelected(settings.isTerminalColorsEnabled());
    }

    private void applyTerminalEffectSettings(ServerConnection connection) {
        if (!TerminalEffectUiSupport.isTerminalEffectsEnabled()) {
            connection.setTerminalEffectPluginId(null);
            connection.setTerminalEffectAnimationSpeed(null);
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
    
    private ConnectionResult createIndividualResult() {
        String resolvedPassword = passwordField.getText();
        // Check if using a saved connection
        ServerConnection selected = savedConnectionsCombo.getValue();

        // Resolve password robustly for password-auth flows:
        // 1) direct input field, 2) selected credential, 3) selected saved connection fallback.
        if (passwordAuthRadio.isSelected() && (resolvedPassword == null || resolvedPassword.isBlank())) {
            if (savedCredentialsCombo.getValue() != null) {
                String credentialPassword = resolveCredentialPassword(savedCredentialsCombo.getValue());
                if (credentialPassword != null && !credentialPassword.isBlank()) {
                    resolvedPassword = credentialPassword;
                }
            }
            if ((resolvedPassword == null || resolvedPassword.isBlank()) && selected != null) {
                String savedConnectionPassword = getConnectionPassword(selected);
                if (savedConnectionPassword != null && !savedConnectionPassword.isBlank()) {
                    resolvedPassword = savedConnectionPassword;
                }
            }
        }

        if (selected != null && 
            selected.getHost().equals(hostField.getText().trim()) &&
            selected.getPort() == portSpinner.getValue() &&
            selected.getUsername().equals(usernameField.getText().trim())) {
            // Using an existing saved connection
            // But if auth method changed OR using temporary key, create a modified copy
            // Also update timeout and retries from spinner values
            
            if (temporaryKeyAuthRadio != null && temporaryKeyAuthRadio.isSelected()) {
                ServerConnection modified = new ServerConnection();
                modified.setId(selected.getId());
                modified.setName(selected.getName());
                modified.setHost(selected.getHost());
                modified.setPort(selected.getPort());
                modified.setUsername(selected.getUsername());
                modified.setGroup(selected.getGroup());
                modified.setSettings(copyTerminalSettings(selected));
                modified.setConnectionTimeoutSeconds(timeoutSpinner.getValue());
                modified.setRetryCount(retrySpinner.getValue());
                modified.setAuthMethod(AuthMethod.PUBLIC_KEY);
                modified.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : selected.getProtocol());
                applySelectedTerminalEmulation(modified, selected);
                
                TemporarySSHKey tempKey = null;
                String keyText = temporaryKeyArea != null ? temporaryKeyArea.getText() : null;
                if (keyText != null && !keyText.trim().isEmpty()) {
                    // Always store and use the current text area content
                    long expirationMinutes = expirationMinutesSpinner != null ? expirationMinutesSpinner.getValue() : 15L;
                    tempKey = TemporarySSHKeyManager.getInstance().storeTemporaryKey(
                        keyText.trim(), expirationMinutes);
                    if (tempKey != null) {
                        // Store key content in connection for persistence
                        modified.setTemporaryKeyContent(tempKey.getKeyContent());
                        modified.setTemporaryKeyExpirationMinutes(expirationMinutes);
                        modified.setTemporaryKeyPermanent(selected.isTemporaryKeyPermanent()); // Preserve permanent setting
                        modified.setPrivateKeyPath("TEMPORARY:" + tempKey.getKeyContent());
                    } else {
                        // storeTemporaryKey returned null (key content incomplete) - use raw text as fallback
                        modified.setTemporaryKeyContent(keyText.trim());
                        modified.setTemporaryKeyExpirationMinutes(expirationMinutes);
                        modified.setTemporaryKeyPermanent(selected.isTemporaryKeyPermanent());
                        modified.setPrivateKeyPath("TEMPORARY:" + keyText.trim());
                    }
                } else if (selected.getTemporaryKeyContent() != null && !selected.getTemporaryKeyContent().trim().isEmpty()) {
                    // Text area is empty but saved connection has key content - use it as fallback
                    tempKey = TemporarySSHKeyManager.getInstance().getTemporaryKey(selected.getTemporaryKeyContent());
                    if (tempKey == null || !tempKey.isValid()) {
                        Long expMin = selected.getTemporaryKeyExpirationMinutes();
                        tempKey = TemporarySSHKeyManager.getInstance().storeTemporaryKey(
                            selected.getTemporaryKeyContent(), (expMin != null && expMin > 0) ? expMin : 15L);
                    }
                    String keyContent = (tempKey != null) ? tempKey.getKeyContent() : selected.getTemporaryKeyContent();
                    modified.setTemporaryKeyContent(keyContent);
                    modified.setTemporaryKeyExpirationMinutes(selected.getTemporaryKeyExpirationMinutes());
                    modified.setTemporaryKeyPermanent(selected.isTemporaryKeyPermanent());
                    modified.setPrivateKeyPath("TEMPORARY:" + keyContent);
                }
                applyTerminalSettings(modified);
                applyTerminalEffectSettings(modified);
                return new ConnectionResult(modified, null, false, true, null, false, tempKey);
            }

            if (passwordAuthRadio.isSelected()) {
                ServerConnection modified = new ServerConnection();
                modified.setId(selected.getId());
                modified.setName(selected.getName());
                modified.setHost(selected.getHost());
                modified.setPort(selected.getPort());
                modified.setUsername(selected.getUsername());
                modified.setGroup(selected.getGroup());
                modified.setSettings(copyTerminalSettings(selected));
                modified.setConnectionTimeoutSeconds(timeoutSpinner.getValue());
                modified.setRetryCount(retrySpinner.getValue());
                modified.setAuthMethod(AuthMethod.PASSWORD);
                modified.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : selected.getProtocol());
                applySelectedTerminalEmulation(modified, selected);
                modified.setSshKeyId(null);
                modified.setPrivateKeyPath(null);
                modified.setTemporaryKeyContent(null);
                modified.setTemporaryKeyExpirationMinutes(null);
                modified.setTemporaryKeyPermanent(false);
                if (savedCredentialsCombo.getValue() != null) {
                    modified.setCredentialId(savedCredentialsCombo.getValue().getId());
                } else {
                    modified.setCredentialId(selected.getCredentialId());
                }
                applyTerminalSettings(modified);
                applyTerminalEffectSettings(modified);
                return new ConnectionResult(modified, resolvedPassword, false, true, null, false, null);
            }
            
            if (keyAuthRadio.isSelected() && selected.getAuthMethod() != AuthMethod.PUBLIC_KEY) {
                // User switched to key auth, need to update connection
                ServerConnection modified = new ServerConnection();
                modified.setId(selected.getId());
                modified.setName(selected.getName());
                modified.setHost(selected.getHost());
                modified.setPort(selected.getPort());
                modified.setUsername(selected.getUsername());
                modified.setGroup(selected.getGroup());
                modified.setSettings(copyTerminalSettings(selected));
                // Use values from spinners, not from saved connection
                modified.setConnectionTimeoutSeconds(timeoutSpinner.getValue());
                modified.setRetryCount(retrySpinner.getValue());
                modified.setAuthMethod(AuthMethod.PUBLIC_KEY);
                modified.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : selected.getProtocol());
                applySelectedTerminalEmulation(modified, selected);
                if (savedSSHKeysCombo.getValue() != null) {
                    modified.setSshKeyId(savedSSHKeysCombo.getValue().getId());
                    modified.setPrivateKeyPath(sshKeyManager != null ? 
                        sshKeyManager.getEffectiveKeyPath(savedSSHKeysCombo.getValue()) : 
                        savedSSHKeysCombo.getValue().getKeyPath());
                }
                applyTerminalSettings(modified);
                applyTerminalEffectSettings(modified);
                return new ConnectionResult(modified, null, false, true, null, false, null);
            } else if (passwordAuthRadio.isSelected() && selected.getAuthMethod() == AuthMethod.PUBLIC_KEY) {
                // User switched to password auth
                ServerConnection modified = new ServerConnection();
                modified.setId(selected.getId());
                modified.setName(selected.getName());
                modified.setHost(selected.getHost());
                modified.setPort(selected.getPort());
                modified.setUsername(selected.getUsername());
                modified.setGroup(selected.getGroup());
                modified.setSettings(copyTerminalSettings(selected));
                // Use values from spinners, not from saved connection
                modified.setConnectionTimeoutSeconds(timeoutSpinner.getValue());
                modified.setRetryCount(retrySpinner.getValue());
                modified.setAuthMethod(AuthMethod.PASSWORD);
                modified.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : selected.getProtocol());
                applySelectedTerminalEmulation(modified, selected);
                modified.setSshKeyId(null);
                modified.setPrivateKeyPath(null);
                modified.setTemporaryKeyContent(null);
                modified.setTemporaryKeyExpirationMinutes(null);
                modified.setTemporaryKeyPermanent(false);
                if (savedCredentialsCombo.getValue() != null) {
                    modified.setCredentialId(savedCredentialsCombo.getValue().getId());
                }
                applyTerminalSettings(modified);
                applyTerminalEffectSettings(modified);
                return new ConnectionResult(modified, resolvedPassword, false, true, null, false, null);
            }
            // Using an existing saved connection, but update timeout and retries from spinners
            ServerConnection modified = new ServerConnection();
            modified.setId(selected.getId());
            modified.setName(selected.getName());
            modified.setHost(selected.getHost());
            modified.setPort(selected.getPort());
            modified.setUsername(selected.getUsername());
            modified.setGroup(selected.getGroup());
            modified.setSettings(copyTerminalSettings(selected));
            modified.setAuthMethod(selected.getAuthMethod());
            modified.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : selected.getProtocol());
            applySelectedTerminalEmulation(modified, selected);
            modified.setSshKeyId(selected.getSshKeyId());
            modified.setPrivateKeyPath(selected.getPrivateKeyPath());
            // Preserve temporary SSH key fields for reconnection
            modified.setTemporaryKeyContent(selected.getTemporaryKeyContent());
            modified.setTemporaryKeyExpirationMinutes(selected.getTemporaryKeyExpirationMinutes());
            modified.setTemporaryKeyPermanent(selected.isTemporaryKeyPermanent());
            // Use values from spinners, not from saved connection
            modified.setConnectionTimeoutSeconds(timeoutSpinner.getValue());
            modified.setRetryCount(retrySpinner.getValue());
            // Look up temporary SSH key from manager if connection uses one
            TemporarySSHKey existingTempKey = null;
            if (selected.getTemporaryKeyContent() != null && !selected.getTemporaryKeyContent().trim().isEmpty()) {
                existingTempKey = TemporarySSHKeyManager.getInstance().getTemporaryKey(selected.getTemporaryKeyContent());
                if (existingTempKey == null || !existingTempKey.isValid()) {
                    Long expMin = selected.getTemporaryKeyExpirationMinutes();
                    existingTempKey = TemporarySSHKeyManager.getInstance().storeTemporaryKey(
                            selected.getTemporaryKeyContent(), (expMin != null && expMin > 0) ? expMin : 15L);
                    // storeTemporaryKey may return null if key content is incomplete - that's OK,
                    // openConnectionAndReturnTab() will handle resolution via temporaryKeyContent
                }
                modified.setPrivateKeyPath("TEMPORARY:" + selected.getTemporaryKeyContent());
                modified.setAuthMethod(AuthMethod.PUBLIC_KEY);
            }
            if (savedCredentialsCombo.getValue() != null && modified.getAuthMethod() != AuthMethod.PUBLIC_KEY) {
                modified.setCredentialId(savedCredentialsCombo.getValue().getId());
            }
            applyTerminalSettings(modified);
            applyTerminalEffectSettings(modified);
            return new ConnectionResult(modified, resolvedPassword, false, true, null, false, existingTempKey);
        }
        
        ServerConnection connection = new ServerConnection();
        connection.setHost(hostField.getText().trim());
        connection.setPort(portSpinner.getValue());
        connection.setUsername(usernameField.getText().trim().isEmpty() ? "root" : usernameField.getText().trim());
        connection.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : ConnectionProtocol.SSH_TCP);
        if (connection.getProtocol() == ConnectionProtocol.LOCAL_SHELL) {
            connection.setLocalShellCommand(effectiveShellCommand());
            String workingDir = shellWorkingDirField.getText() != null ? shellWorkingDirField.getText().trim() : "";
            connection.setLocalShellWorkingDirectory(workingDir.isEmpty() ? null : workingDir);
        }
        applySelectedTerminalEmulation(connection, null);
        connection.setConnectionTimeoutSeconds(timeoutSpinner.getValue());
        connection.setRetryCount(retrySpinner.getValue());
        
        TemporarySSHKey tempKey = null;
        if (temporaryKeyAuthRadio != null && temporaryKeyAuthRadio.isSelected()) {
            connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
            if (currentTemporaryKey != null && currentTemporaryKey.isValid()) {
                tempKey = currentTemporaryKey;
                connection.setTemporaryKeyContent(tempKey.getKeyContent());
                connection.setTemporaryKeyExpirationMinutes(tempKey.getExpirationMinutes());
                connection.setTemporaryKeyPermanent(false);
                connection.setPrivateKeyPath("TEMPORARY:" + tempKey.getKeyContent());
            } else if (temporaryKeyArea != null && temporaryKeyArea.getText() != null && !temporaryKeyArea.getText().trim().isEmpty()) {
                String keyText = temporaryKeyArea.getText().trim();
                long expirationMinutes = expirationMinutesSpinner != null ? expirationMinutesSpinner.getValue() : 15L;
                tempKey = TemporarySSHKeyManager.getInstance().storeTemporaryKey(keyText, expirationMinutes);
                String keyContent = (tempKey != null) ? tempKey.getKeyContent() : keyText;
                connection.setTemporaryKeyContent(keyContent);
                connection.setTemporaryKeyExpirationMinutes(expirationMinutes);
                connection.setTemporaryKeyPermanent(false);
                connection.setPrivateKeyPath("TEMPORARY:" + keyContent);
            }
        } else if (keyAuthRadio.isSelected()) {
            connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
            // Set SSH key if selected
            if (savedSSHKeysCombo.getValue() != null) {
                connection.setSshKeyId(savedSSHKeysCombo.getValue().getId());
                connection.setPrivateKeyPath(sshKeyManager != null ? 
                    sshKeyManager.getEffectiveKeyPath(savedSSHKeysCombo.getValue()) : 
                    savedSSHKeysCombo.getValue().getKeyPath());
            }
        } else {
            connection.setAuthMethod(AuthMethod.PASSWORD);
        }
        if (savedCredentialsCombo.getValue() != null && connection.getAuthMethod() != AuthMethod.PUBLIC_KEY) {
            connection.setCredentialId(savedCredentialsCombo.getValue().getId());
        }
        
        if (saveConnectionCheck.isSelected()) {
            String name = connectionNameField.getText().trim();
            if (!name.isEmpty()) {
                connection.setName(name);
            } else if (connection.getProtocol() == ConnectionProtocol.LOCAL_SHELL) {
                connection.setName(connection.localShellDisplayLabel());
            } else {
                connection.setName(connection.getUsername() + "@" + connection.getHost());
            }
        }
        
        // Apply terminal settings from the dialog
        applyTerminalSettings(connection);
        applyTerminalEffectSettings(connection);
        
        return new ConnectionResult(connection, resolvedPassword, saveConnectionCheck.isSelected(), false, null, false, tempKey);
    }

    private void updateCredentialCombo(String hostname) {
        if (savedCredentialsCombo == null || credentialManager == null) return;
        ignoreSavedCredentialsEvents = true;
        try {
            StoredCredential current = savedCredentialsCombo.getValue();
            savedCredentialsCombo.getItems().clear();
            if (hostname != null && !hostname.trim().isEmpty()) {
                List<StoredCredential> matching = credentialManager.getAllCredentials().stream()
                    .filter(c -> c.matchesServer(hostname))
                    .collect(Collectors.toList());
                savedCredentialsCombo.getItems().addAll(matching);
                if (current != null && matching.contains(current)) {
                    savedCredentialsCombo.setValue(current);
                }
            }
        } finally {
            ignoreSavedCredentialsEvents = false;
        }
    }

    private String resolveCredentialPassword(StoredCredential credential) {
        if (credential == null || credentialManager == null || masterPassword == null) return null;
        try {
            return credentialManager.getPassword(credential, masterPassword);
        } catch (Exception e) {
            return null;
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

    /** The shell command for the result: the selected preset, or the custom field when "custom". */
    private String effectiveShellCommand() {
        return LocalShellPresetSupport.commandFor(
            shellPresetCombo.getValue(),
            customShellCommandField.getText(),
            gitBashCommand, cygwinCommand, wslCommand);
    }

    /** Applies the credential/key field enablement implied by the selected auth method. */
    private void applyAuthFieldStates() {
        boolean useKey = keyAuthRadio.isSelected();
        boolean useTemporaryKey = temporaryKeyAuthRadio != null && temporaryKeyAuthRadio.isSelected();
        passwordField.setDisable(useKey || useTemporaryKey);
        savedCredentialsCombo.setDisable(useKey || useTemporaryKey);
        savedSSHKeysCombo.setDisable(!useKey || useTemporaryKey);
        if (temporaryKeyArea != null) {
            temporaryKeyArea.setDisable(!useTemporaryKey);
            expirationMinutesSpinner.setDisable(!useTemporaryKey);
            remainingTimeLabel.setDisable(!useTemporaryKey);
            if (useTemporaryKey && temporaryKeyArea.getText().isEmpty() && currentTemporaryKey == null) {
                temporaryKeyArea.requestFocus();
            }
        }
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
        if (passwordAuthRadio != null) passwordAuthRadio.setDisable(local);
        if (keyAuthRadio != null) keyAuthRadio.setDisable(local);
        if (temporaryKeyAuthRadio != null) temporaryKeyAuthRadio.setDisable(local);
        if (local) {
            passwordField.setDisable(true);
            if (savedCredentialsCombo != null) savedCredentialsCombo.setDisable(true);
            if (savedSSHKeysCombo != null) savedSSHKeysCombo.setDisable(true);
            if (temporaryKeyArea != null) {
                temporaryKeyArea.setDisable(true);
                expirationMinutesSpinner.setDisable(true);
            }
        } else {
            // Restore auth-driven enablement of the credential/key fields.
            applyAuthFieldStates();
        }
    }

    private void applySelectedTerminalEmulation(ServerConnection target, ServerConnection fallback) {
        if (target == null) {
            return;
        }
        EmulationType selected = TerminalEmulationComboBoxSupport.selectedEmulation(terminalEmulationCombo);
        if (selected != null) {
            target.setTerminalEmulationType(TerminalEmulationSupport.storedValue(selected));
        } else if (fallback != null) {
            target.setTerminalEmulationType(fallback.getTerminalEmulationType());
        } else {
            target.setTerminalEmulationType(TerminalEmulationSupport.defaultStoredValue());
        }
    }
    
    /**
     * Result containing connection details or group name or load project flag.
     * @param connection The server connection details (null for group or project load)
     * @param password The password entered
     * @param save Whether to save this as a new connection
     * @param existingSaved Whether this is an existing saved connection
     * @param groupName The group name to open (null for individual connection or project load)
     * @param loadProject Whether to load a project instead of connecting
     * @param temporarySSHKey The temporary SSH key if used (null otherwise)
     */
    public record ConnectionResult(ServerConnection connection, String password, boolean save, boolean existingSaved, String groupName, boolean loadProject, TemporarySSHKey temporarySSHKey) {
        public boolean isGroupConnection() {
            return groupName != null;
        }
        
        public boolean isLoadProject() {
            return loadProject;
        }
    }
    
    /**
     * Retrieves password for a connection, prioritizing credential store.
     */
    private String getConnectionPassword(ServerConnection conn) {
        // Try credential store first
        if (conn.getCredentialId() != null && credentialManager != null) {
            try {
                java.util.Optional<de.kortty.model.StoredCredential> credential = 
                    credentialManager.findCredentialById(conn.getCredentialId());
                
                if (credential.isPresent() && masterPassword != null) {
                    String password = credentialManager.getPassword(
                        credential.get(),
                        masterPassword
                    );
                    if (password != null) return password;
                }
            } catch (Exception e) {
                // Fall back to stored password
            }
        }
        
        // Fall back to stored password
        return passwordVault != null ? passwordVault.retrievePassword(conn) : "";
    }
    
    /**
     * Loads connection settings (timeout and retries) from GlobalSettings.
     */
    private void loadConnectionSettings() {
        // Connection timeout and retries intentionally use FIXED defaults every time the dialog
        // opens (10 seconds / 0 retries — see the timeoutSpinner/retrySpinner construction); they
        // are deliberately NOT restored from the previous session. Picking a saved connection still
        // fills in that connection's own timeout/retries (see fillFormWithConnection).
    }
    
    /**
     * Loads terminal settings from GlobalSettings.
     * First tries to load last QuickConnect settings, otherwise uses default terminal settings.
     */
    private void loadTerminalSettings() {
        try {
            de.kortty.core.GlobalSettingsManager gsm = 
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings globalSettings = gsm.getSettings();
            
            de.kortty.model.ConnectionSettings settings = null;
            
            // Try to load last QuickConnect settings first
            if (globalSettings != null && globalSettings.getLastQuickConnectTerminalSettings() != null) {
                settings = globalSettings.getLastQuickConnectTerminalSettings();
            }
            // Fall back to default terminal settings
            else if (globalSettings != null && globalSettings.getDefaultTerminalSettings() != null) {
                settings = globalSettings.getDefaultTerminalSettings();
            }
            
            if (settings != null) {
                String themeId = settings.getThemeId();
                if (themeId != null && themeCombo != null) {
                    try {
                        var tm = de.kortty.KorTTYApplication.getInstance().getThemeManager();
                        if (tm != null) tm.getTheme(themeId).ifPresent(themeCombo::setValue);
                    } catch (Exception ignored) {}
                } else if (themeCombo != null) {
                    themeCombo.setValue(null);
                }
                if (themeCombo == null || themeCombo.getValue() == null) {
                    if (settings.getFontFamily() != null) fontFamilyCombo.setValue(settings.getFontFamily());
                    fontSizeSpinner.getValueFactory().setValue(settings.getFontSize());
                    if (settings.getForegroundColor() != null) foregroundColorPicker.setValue(javafx.scene.paint.Color.web(settings.getForegroundColor()));
                    if (settings.getBackgroundColor() != null) backgroundColorPicker.setValue(javafx.scene.paint.Color.web(settings.getBackgroundColor()));
                }
                terminalColorsEnabledCheck.setSelected(settings.isTerminalColorsEnabled());
            }

            if (terminalEffectSpeedControls != null && globalSettings != null) {
                Double lastSpeed = globalSettings.getLastQuickConnectTerminalEffectAnimationSpeed();
                terminalEffectSpeedControls.setValue(lastSpeed != null
                        ? lastSpeed
                        : TerminalEffectAnimationSpeed.DEFAULT);
            }
        } catch (Exception e) {
            // Ignore, use default values
        }
    }
    
    /**
     * Resets terminal settings to global default settings.
     */
    private void resetToDefaultSettings() {
        try {
            de.kortty.core.GlobalSettingsManager gsm = 
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings globalSettings = gsm.getSettings();
            
            if (globalSettings != null && globalSettings.getDefaultTerminalSettings() != null) {
                de.kortty.model.ConnectionSettings settings = globalSettings.getDefaultTerminalSettings();
                if (themeCombo != null) {
                    String themeId = settings.getThemeId();
                    if (themeId != null) {
                        try {
                            var tm = de.kortty.KorTTYApplication.getInstance().getThemeManager();
                            if (tm != null) tm.getTheme(themeId).ifPresent(themeCombo::setValue);
                        } catch (Exception ignored) {}
                    } else {
                        themeCombo.setValue(null);
                    }
                }
                if (themeCombo == null || themeCombo.getValue() == null) {
                    if (settings.getFontFamily() != null) fontFamilyCombo.setValue(settings.getFontFamily());
                    fontSizeSpinner.getValueFactory().setValue(settings.getFontSize());
                    if (settings.getForegroundColor() != null) foregroundColorPicker.setValue(javafx.scene.paint.Color.web(settings.getForegroundColor()));
                    if (settings.getBackgroundColor() != null) backgroundColorPicker.setValue(javafx.scene.paint.Color.web(settings.getBackgroundColor()));
                }
                terminalColorsEnabledCheck.setSelected(settings.isTerminalColorsEnabled());
            }
        } catch (Exception e) {
            // Ignore
        }
    }
    
    /**
     * Saves current terminal settings and connection settings as last QuickConnect settings.
     */
    private void saveTerminalSettings() {
        try {
            de.kortty.core.GlobalSettingsManager gsm = 
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings globalSettings = gsm.getSettings();
            
            if (globalSettings != null) {
                // Save terminal settings
                de.kortty.model.ConnectionSettings settings = new de.kortty.model.ConnectionSettings();
                Theme selTheme = themeCombo != null ? themeCombo.getValue() : null;
                if (selTheme != null) {
                    selTheme.applyTo(settings, isThemeFontApplyEnabled());
                    settings.setThemeId(selTheme.getId());
                } else {
                    settings.setFontFamily(fontFamilyCombo.getValue());
                    settings.setFontSize(fontSizeSpinner.getValue());
                    settings.setForegroundColor(toHex(foregroundColorPicker.getValue()));
                    settings.setBackgroundColor(toHex(backgroundColorPicker.getValue()));
                }
                settings.setTerminalColorsEnabled(terminalColorsEnabledCheck.isSelected());
                globalSettings.setLastQuickConnectTerminalSettings(settings);

                String pluginId = TerminalEffectUiSupport.selectedPluginId(terminalEffectCombo);
                globalSettings.setLastQuickConnectTerminalEffectAnimationSpeed(
                        TerminalEffectUiSupport.animationSpeedForStorage(
                                pluginId,
                                terminalEffectSpeedControls != null
                                        ? terminalEffectSpeedControls.getValue()
                                        : TerminalEffectAnimationSpeed.DEFAULT));
                
                // Connection timeout/retries are intentionally NOT persisted here — the dialog
                // always opens with fixed defaults (10s / 0 retries). See loadConnectionSettings().

                gsm.save();
            }
        } catch (Exception e) {
            // Ignore, settings will be saved next time
        }
    }
    
    /**
     * Returns a list of monospace fonts available on the system.
     */
    /**
     * Returns all system-available font families, with common monospace fonts first.
     */
    private List<String> getMonospaceFonts() {
        java.util.Set<String> preferred = new java.util.LinkedHashSet<>();
        preferred.add("Monospaced");
        preferred.add("Courier New");
        preferred.add("Monaco");
        preferred.add("Menlo");
        preferred.add("Consolas");
        preferred.add("DejaVu Sans Mono");
        preferred.add("Liberation Mono");
        preferred.add("Ubuntu Mono");
        preferred.add("Fira Code");
        preferred.add("JetBrains Mono");
        preferred.add("Source Code Pro");
        preferred.add("SF Mono");
        java.util.List<String> system = javafx.scene.text.Font.getFamilies();
        java.util.List<String> result = new java.util.ArrayList<>(preferred.size() + system.size());
        for (String f : preferred) {
            if (system.contains(f)) result.add(f);
        }
        for (String f : system) {
            if (!preferred.contains(f)) result.add(f);
        }
        return result;
    }
    
    /**
     * Applies terminal settings to a connection.
     */
    private ConnectionSettings copyTerminalSettings(ServerConnection connection) {
        ConnectionSettings settings = connection != null ? connection.getSettings() : null;
        return settings != null ? new ConnectionSettings(settings) : new ConnectionSettings();
    }

    private void applyTerminalSettings(ServerConnection connection) {
        if (connection.getSettings() == null) {
            connection.setSettings(new de.kortty.model.ConnectionSettings());
        }
        
        connection.getSettings().setFontFamily(fontFamilyCombo.getValue());
        connection.getSettings().setFontSize(fontSizeSpinner.getValue());
        connection.getSettings().setForegroundColor(toHex(foregroundColorPicker.getValue()));
        connection.getSettings().setBackgroundColor(toHex(backgroundColorPicker.getValue()));
        connection.getSettings().setTerminalColorsEnabled(terminalColorsEnabledCheck.isSelected());
        Theme selTheme = themeCombo != null ? themeCombo.getValue() : null;
        if (selTheme != null) {
            selTheme.applyTo(connection.getSettings(), isThemeFontApplyEnabled());
            connection.getSettings().setThemeId(selTheme.getId());
        } else {
            connection.getSettings().setThemeId(null);
        }
    }

    private boolean isThemeFontApplyEnabled() {
        try {
            var gs = de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager().getSettings();
            return gs != null && gs.isApplyThemeFonts();
        } catch (Exception e) {
            return false;
        }
    }
    
    /**
     * Converts Color to hex string.
     */
    private String toHex(javafx.scene.paint.Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }
    
    /**
     * Starts the expiration timer to update the remaining time label.
     */
    private void startExpirationTimer() {
        stopExpirationTimer();
        
        if (currentTemporaryKey == null) {
            return;
        }
        
        expirationTimer = new Timeline(new KeyFrame(Duration.seconds(1), e -> {
            if (currentTemporaryKey != null && currentTemporaryKey.isValid()) {
                long remainingSeconds = currentTemporaryKey.getRemainingSeconds();
                long minutes = remainingSeconds / 60;
                long seconds = remainingSeconds % 60;
                if (remainingTimeLabel != null) {
                    remainingTimeLabel.setText(I18n.get("quickConnect.remainingTime", String.format("%02d:%02d", minutes, seconds)));
                    if (remainingSeconds < 60) {
                        remainingTimeLabel.setStyle("-fx-text-fill: #ff0000; -fx-font-weight: bold;");
                    } else if (remainingSeconds < 300) {
                        remainingTimeLabel.setStyle("-fx-text-fill: #ffaa00; -fx-font-weight: bold;");
                    } else {
                        remainingTimeLabel.setStyle("-fx-text-fill: #00ff00; -fx-font-weight: bold;");
                    }
                }
            } else {
                if (remainingTimeLabel != null) {
                    remainingTimeLabel.setText(I18n.get("quickConnect.keyExpired"));
                    remainingTimeLabel.setStyle("-fx-text-fill: #ff0000; -fx-font-weight: bold;");
                }
                if (temporaryKeyArea != null) temporaryKeyArea.clear();
                currentTemporaryKey = null;
                stopExpirationTimer();
            }
        }));
        expirationTimer.setCycleCount(Timeline.INDEFINITE);
        expirationTimer.play();
    }
    
    /**
     * Stops the expiration timer.
     */
    private void stopExpirationTimer() {
        if (expirationTimer != null) {
            expirationTimer.stop();
            expirationTimer = null;
        }
        if (remainingTimeLabel != null) {
            remainingTimeLabel.setText("");
        }
    }

}
