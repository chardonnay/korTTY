package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.model.SSHKey;
import de.kortty.model.Theme;
import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.StoredCredential;
import de.kortty.model.TemporarySSHKey;
import de.kortty.security.PasswordVault;
import de.kortty.core.SSHKeyManager;
import de.kortty.core.TemporarySSHKeyManager;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
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
public class QuickConnectDialog extends Dialog<QuickConnectDialog.ConnectionResult> {
    
    private final List<ServerConnection> savedConnections;
    private final PasswordVault passwordVault;
    private final de.kortty.core.CredentialManager credentialManager;
    private final SSHKeyManager sshKeyManager;
    private final char[] masterPassword;
    private final int topConnectionsCount;
    
    // Individual connection tab
    private ComboBox<ServerConnection> savedConnectionsCombo;
    private TextField hostField;
    private Spinner<Integer> portSpinner;
    private ComboBox<ConnectionProtocol> protocolCombo;
    private TextField usernameField;
    private PasswordField passwordField;
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
        individualTab.setContent(createIndividualConnectionPane());
        
        Tab groupTab = new Tab(I18n.get("quickConnect.openGroup"));
        groupTab.setContent(createGroupSelectionPane());
        
        tabPane.getTabs().addAll(individualTab, groupTab);
        
        mainContent.getChildren().add(tabPane);
        
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
        
        // Enable/disable connect button
        connectButton.setDisable(true);
        hostField.textProperty().addListener((obs, old, newVal) -> {
            connectButton.setDisable(newVal.trim().isEmpty());
        });
        
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
        // Get recently used connections (last used > 0), sorted by frequency then last used
        List<ServerConnection> recentConnections = savedConnections.stream()
                .filter(c -> c.getLastUsed() > 0)
                .sorted((a, b) -> {
                    // Sort by usage count descending first, then by last used descending
                    int usageCompare = Integer.compare(b.getUsageCount(), a.getUsageCount());
                    if (usageCompare != 0) return usageCompare;
                    return Long.compare(b.getLastUsed(), a.getLastUsed());
                })
                .limit(10)
                .collect(Collectors.toList());
        
        if (recentConnections.isEmpty()) {
            return null;
        }
        
        VBox box = new VBox(8);
        Label label = new Label(I18n.get("quickConnect.frequentlyUsed"));
        label.setStyle("-fx-font-weight: bold;");
        
        // Create horizontal scrollable container
        HBox buttonContainer = new HBox(8);
        buttonContainer.setPadding(new Insets(5));
        
        for (ServerConnection conn : recentConnections) {
            Button btn = new Button(conn.getName());
            btn.setPrefWidth(150);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setTooltip(new Tooltip(conn.getUsername() + "@" + conn.getHost() + ":" + conn.getPort() + 
                    "\n" + I18n.get("quickConnect.usageCount") + ": " + conn.getUsageCount() + "x" +
                    "\n" + I18n.get("quickConnect.lastUsed") + ": " + new java.util.Date(conn.getLastUsed())));
            btn.setOnAction(e -> {
                // Fill in the form and close dialog
                fillFormWithConnection(conn);
                // Look up temporary SSH key from manager if connection uses one
                TemporarySSHKey tempKeyForBtn = null;
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
                    conn.setPrivateKeyPath("TEMPORARY:" + conn.getTemporaryKeyContent());
                    conn.setAuthMethod(AuthMethod.PUBLIC_KEY);
                }
                setResult(new ConnectionResult(conn, 
                        getConnectionPassword(conn), 
                        false, true, null, false, tempKeyForBtn));
                close();
            });
            buttonContainer.getChildren().add(btn);
        }
        
        // Wrap in ScrollPane for horizontal scrolling
        ScrollPane scrollPane = new ScrollPane(buttonContainer);
        scrollPane.setFitToHeight(true);
        scrollPane.setFitToWidth(false);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setPrefHeight(60);
        scrollPane.setMinHeight(60);
        scrollPane.setMaxHeight(60);
        
        box.getChildren().addAll(label, scrollPane);
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
        protocolCombo.getItems().addAll(ConnectionProtocol.SSH_TCP, ConnectionProtocol.MOSH);
        protocolCombo.setValue(ConnectionProtocol.SSH_TCP);
        protocolCombo.setPrefWidth(180);
        protocolCombo.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(ConnectionProtocol item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else if (item == ConnectionProtocol.MOSH) {
                    setText(I18n.get("protocol.mosh"));
                } else {
                    setText(I18n.get("protocol.sshTcp"));
                }
            }
        });
        protocolCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(ConnectionProtocol item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(I18n.get("protocol.sshTcp"));
                } else if (item == ConnectionProtocol.MOSH) {
                    setText(I18n.get("protocol.mosh"));
                } else {
                    setText(I18n.get("protocol.sshTcp"));
                }
            }
        });
        
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
        authMethodGroup.selectedToggleProperty().addListener((obs, old, newVal) -> {
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
        });
        
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
        
        timeoutSpinner = new Spinner<>(1, 300, 15);
        timeoutSpinner.setEditable(true);
        timeoutSpinner.setPrefWidth(80);
        
        retrySpinner = new Spinner<>(1, 20, 4);
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
        
        // When a saved connection is selected, fill in the fields
        savedConnectionsCombo.setOnAction(e -> {
            ServerConnection selected = savedConnectionsCombo.getValue();
            if (selected != null) {
                fillFormWithConnection(selected);
            }
        });
        
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
        
        grid.add(new Label(I18n.get("quickConnect.authentication")), 0, 3);
        VBox authBox = new VBox(5);
        authBox.getChildren().addAll(passwordAuthRadio, keyAuthRadio);
        if (temporaryKeyAuthRadio != null) {
            authBox.getChildren().add(temporaryKeyAuthRadio);
        }
        grid.add(authBox, 1, 3);
        
        grid.add(new Label(I18n.get("quickConnect.password")), 0, 4);
        grid.add(passwordField, 1, 4);
        
        grid.add(new Label(I18n.get("connEdit.savedCredentials")), 0, 5);
        grid.add(savedCredentialsCombo, 1, 5);
        
        grid.add(new Label(I18n.get("quickConnect.sshKey")), 0, 6);
        grid.add(savedSSHKeysCombo, 1, 6);
        
        int row = 7;
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
        
        grid.add(saveConnectionCheck, 1, row++);
        
        grid.add(new Label(I18n.get("quickConnect.connectionName")), 0, row);
        grid.add(connectionNameField, 1, row++);
        
        grid.add(new Separator(), 0, row, 2, 1);
        row++;
        
        grid.add(new Label(I18n.get("quickConnect.connectionTimeout")), 0, row);
        HBox timeoutBox = new HBox(10);
        timeoutBox.getChildren().addAll(timeoutSpinner, new Label(I18n.get("common.seconds")));
        grid.add(timeoutBox, 1, row++);
        
        grid.add(new Label(I18n.get("quickConnect.retries")), 0, row);
        HBox retryBox = new HBox(10);
        retryBox.getChildren().addAll(retrySpinner, new Label("attempts"));
        grid.add(retryBox, 1, row++);
        
        grid.add(new Separator(), 0, row, 2, 1);
        row++;
        
        Label appearanceLabel = new Label(I18n.get("quickConnect.terminalAppearance"));
        appearanceLabel.setStyle("-fx-font-weight: bold;");
        grid.add(appearanceLabel, 0, row, 2, 1);
        row++;
        
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
        
        grid.add(new Label(I18n.get("quickConnect.theme")), 0, row);
        grid.add(themeCombo, 1, row++);
        
        fontFamilyCombo = new ComboBox<>();
        fontFamilyCombo.getItems().addAll(getMonospaceFonts());
        fontFamilyCombo.setValue("Monospaced");
        fontFamilyCombo.setPrefWidth(200);
        
        fontSizeSpinner = new Spinner<>(8, 72, 14);
        fontSizeSpinner.setEditable(true);
        fontSizeSpinner.setPrefWidth(80);
        
        grid.add(new Label(I18n.get("quickConnect.font")), 0, row);
        HBox fontBox = new HBox(10);
        fontBox.getChildren().addAll(fontFamilyCombo, new Label(I18n.get("quickConnect.fontSize")), fontSizeSpinner);
        grid.add(fontBox, 1, row++);
        
        foregroundColorPicker = new ColorPicker(javafx.scene.paint.Color.web("#FFFFFF"));
        backgroundColorPicker = new ColorPicker(javafx.scene.paint.Color.web("#1E1E1E"));
        
        grid.add(new Label(I18n.get("quickConnect.textColor")), 0, row);
        grid.add(foregroundColorPicker, 1, row++);
        
        grid.add(new Label(I18n.get("quickConnect.background")), 0, row);
        grid.add(backgroundColorPicker, 1, row++);
        
        Button resetButton = new Button(I18n.get("quickConnect.resetToDefaults"));
        resetButton.setOnAction(e -> resetToDefaultSettings());
        HBox buttonBox = new HBox(10);
        buttonBox.getChildren().add(resetButton);
        grid.add(buttonBox, 1, row);
        
        // Load last used settings or default settings from GlobalSettings
        loadTerminalSettings();
        
        // Add to pane
        if (savedConnections != null && !savedConnections.isEmpty()) {
            Label savedLabel = new Label(I18n.get("quickConnect.savedConnections"));
            pane.getChildren().addAll(savedLabel, savedConnectionsCombo, new Separator());
        }
        pane.getChildren().add(grid);
        
        return pane;
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
        updateCredentialCombo(conn.getHost());
        if (conn.getCredentialId() != null && credentialManager != null) {
            credentialManager.findCredentialById(conn.getCredentialId()).ifPresent(savedCredentialsCombo::setValue);
        } else {
            savedCredentialsCombo.setValue(null);
        }
        
        // Set timeout and retry from connection
        timeoutSpinner.getValueFactory().setValue(conn.getConnectionTimeoutSeconds());
        retrySpinner.getValueFactory().setValue(conn.getRetryCount());
        
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
                modified.setSettings(selected.getSettings());
                modified.setConnectionTimeoutSeconds(timeoutSpinner.getValue());
                modified.setRetryCount(retrySpinner.getValue());
                modified.setAuthMethod(AuthMethod.PUBLIC_KEY);
                modified.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : selected.getProtocol());
                
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
                modified.setSettings(selected.getSettings());
                modified.setConnectionTimeoutSeconds(timeoutSpinner.getValue());
                modified.setRetryCount(retrySpinner.getValue());
                modified.setAuthMethod(AuthMethod.PASSWORD);
                modified.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : selected.getProtocol());
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
                modified.setSettings(selected.getSettings());
                // Use values from spinners, not from saved connection
                modified.setConnectionTimeoutSeconds(timeoutSpinner.getValue());
                modified.setRetryCount(retrySpinner.getValue());
                modified.setAuthMethod(AuthMethod.PUBLIC_KEY);
                modified.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : selected.getProtocol());
                if (savedSSHKeysCombo.getValue() != null) {
                    modified.setSshKeyId(savedSSHKeysCombo.getValue().getId());
                    modified.setPrivateKeyPath(sshKeyManager != null ? 
                        sshKeyManager.getEffectiveKeyPath(savedSSHKeysCombo.getValue()) : 
                        savedSSHKeysCombo.getValue().getKeyPath());
                }
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
                modified.setSettings(selected.getSettings());
                // Use values from spinners, not from saved connection
                modified.setConnectionTimeoutSeconds(timeoutSpinner.getValue());
                modified.setRetryCount(retrySpinner.getValue());
                modified.setAuthMethod(AuthMethod.PASSWORD);
                modified.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : selected.getProtocol());
                modified.setSshKeyId(null);
                modified.setPrivateKeyPath(null);
                modified.setTemporaryKeyContent(null);
                modified.setTemporaryKeyExpirationMinutes(null);
                modified.setTemporaryKeyPermanent(false);
                if (savedCredentialsCombo.getValue() != null) {
                    modified.setCredentialId(savedCredentialsCombo.getValue().getId());
                }
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
            modified.setSettings(selected.getSettings());
            modified.setAuthMethod(selected.getAuthMethod());
            modified.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : selected.getProtocol());
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
            if (savedCredentialsCombo.getValue() != null) {
                modified.setCredentialId(savedCredentialsCombo.getValue().getId());
            }
            return new ConnectionResult(modified, resolvedPassword, false, true, null, false, existingTempKey);
        }
        
        ServerConnection connection = new ServerConnection();
        connection.setHost(hostField.getText().trim());
        connection.setPort(portSpinner.getValue());
        connection.setUsername(usernameField.getText().trim().isEmpty() ? "root" : usernameField.getText().trim());
        connection.setProtocol(protocolCombo.getValue() != null ? protocolCombo.getValue() : ConnectionProtocol.SSH_TCP);
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
        if (savedCredentialsCombo.getValue() != null) {
            connection.setCredentialId(savedCredentialsCombo.getValue().getId());
        }
        
        if (saveConnectionCheck.isSelected()) {
            String name = connectionNameField.getText().trim();
            connection.setName(name.isEmpty() ? connection.getUsername() + "@" + connection.getHost() : name);
        }
        
        // Apply terminal settings from the dialog
        applyTerminalSettings(connection);
        
        return new ConnectionResult(connection, resolvedPassword, saveConnectionCheck.isSelected(), false, null, false, tempKey);
    }

    private void updateCredentialCombo(String hostname) {
        if (savedCredentialsCombo == null || credentialManager == null) return;
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
    }

    private String resolveCredentialPassword(StoredCredential credential) {
        if (credential == null || credentialManager == null || masterPassword == null) return null;
        try {
            return credentialManager.getPassword(credential, masterPassword);
        } catch (Exception e) {
            return null;
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
        try {
            de.kortty.core.GlobalSettingsManager gsm = 
                de.kortty.KorTTYApplication.getInstance().getGlobalSettingsManager();
            de.kortty.model.GlobalSettings globalSettings = gsm.getSettings();
            
            if (globalSettings != null) {
                // Load timeout
                if (globalSettings.getLastQuickConnectTimeout() != null) {
                    timeoutSpinner.getValueFactory().setValue(globalSettings.getLastQuickConnectTimeout());
                }
                
                // Load retries
                if (globalSettings.getLastQuickConnectRetries() != null) {
                    retrySpinner.getValueFactory().setValue(globalSettings.getLastQuickConnectRetries());
                }
            }
        } catch (Exception e) {
            // Ignore, use default values
        }
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
                    selTheme.applyTo(settings);
                    settings.setThemeId(selTheme.getId());
                } else {
                    settings.setFontFamily(fontFamilyCombo.getValue());
                    settings.setFontSize(fontSizeSpinner.getValue());
                    settings.setForegroundColor(toHex(foregroundColorPicker.getValue()));
                    settings.setBackgroundColor(toHex(backgroundColorPicker.getValue()));
                }
                globalSettings.setLastQuickConnectTerminalSettings(settings);
                
                // Save connection settings (timeout and retries)
                globalSettings.setLastQuickConnectTimeout(timeoutSpinner.getValue());
                globalSettings.setLastQuickConnectRetries(retrySpinner.getValue());
                
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
    private void applyTerminalSettings(ServerConnection connection) {
        if (connection.getSettings() == null) {
            connection.setSettings(new de.kortty.model.ConnectionSettings());
        }
        
        connection.getSettings().setFontFamily(fontFamilyCombo.getValue());
        connection.getSettings().setFontSize(fontSizeSpinner.getValue());
        connection.getSettings().setForegroundColor(toHex(foregroundColorPicker.getValue()));
        connection.getSettings().setBackgroundColor(toHex(backgroundColorPicker.getValue()));
        Theme selTheme = themeCombo != null ? themeCombo.getValue() : null;
        if (selTheme != null) {
            selTheme.applyTo(connection.getSettings());
            connection.getSettings().setThemeId(selTheme.getId());
        } else {
            connection.getSettings().setThemeId(null);
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
