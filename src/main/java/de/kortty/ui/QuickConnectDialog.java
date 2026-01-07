package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.model.SSHKey;
import de.kortty.model.AuthMethod;
import de.kortty.security.PasswordVault;
import de.kortty.core.SSHKeyManager;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Modality;
import javafx.stage.Stage;

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
    private TextField usernameField;
    private PasswordField passwordField;
    private ToggleGroup authMethodGroup;
    private RadioButton passwordAuthRadio;
    private RadioButton keyAuthRadio;
    private ComboBox<SSHKey> savedSSHKeysCombo;
    private CheckBox saveConnectionCheck;
    private TextField connectionNameField;
    private Spinner<Integer> timeoutSpinner;
    private Spinner<Integer> retrySpinner;
    
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
        
        setTitle("Schnellverbindung");
        setHeaderText(null);
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);
        
        VBox mainContent = new VBox(10);
        mainContent.setPadding(new Insets(15));
        mainContent.setPrefWidth(500);
        
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
        
        Tab individualTab = new Tab("Einzelverbindung");
        individualTab.setContent(createIndividualConnectionPane());
        
        Tab groupTab = new Tab("Gruppe öffnen");
        groupTab.setContent(createGroupSelectionPane());
        
        tabPane.getTabs().addAll(individualTab, groupTab);
        
        mainContent.getChildren().add(tabPane);
        
        getDialogPane().setContent(mainContent);
        
        // Buttons
        ButtonType connectButtonType = new ButtonType("Verbinden", ButtonBar.ButtonData.OK_DONE);
        ButtonType openGroupButtonType = new ButtonType("Gruppe öffnen", ButtonBar.ButtonData.OK_DONE);
        ButtonType loadProjectButtonType = new ButtonType("Projekt laden", ButtonBar.ButtonData.OTHER);
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
            if (dialogButton == connectButtonType) {
                return createIndividualResult();
            } else if (dialogButton == openGroupButtonType) {
                String selectedGroup = groupListView.getSelectionModel().getSelectedItem();
                if (selectedGroup != null) {
                    return new ConnectionResult(null, null, false, false, selectedGroup, false);
                }
            } else if (dialogButton == loadProjectButtonType) {
                // Special result to signal "load project"
                return new ConnectionResult(null, null, false, false, null, true);
            }
            return null;
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
        Label label = new Label("Häufig genutzte Verbindungen:");
        label.setStyle("-fx-font-weight: bold;");
        
        // Create horizontal scrollable container
        HBox buttonContainer = new HBox(8);
        buttonContainer.setPadding(new Insets(5));
        
        for (ServerConnection conn : recentConnections) {
            Button btn = new Button(conn.getName());
            btn.setPrefWidth(150);
            btn.setMaxWidth(Double.MAX_VALUE);
            btn.setTooltip(new Tooltip(conn.getUsername() + "@" + conn.getHost() + ":" + conn.getPort() + 
                    "\nVerwendet: " + conn.getUsageCount() + "x" +
                    "\nZuletzt: " + new java.util.Date(conn.getLastUsed())));
            btn.setOnAction(e -> {
                // Fill in the form and close dialog
                fillFormWithConnection(conn);
                setResult(new ConnectionResult(conn, 
                        getConnectionPassword(conn), 
                        false, true, null, false));
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
        
        // Create form fields
        hostField = new TextField();
        hostField.setPromptText("hostname oder IP");
        hostField.setPrefWidth(250);
        
        portSpinner = new Spinner<>(1, 65535, 22);
        portSpinner.setEditable(true);
        portSpinner.setPrefWidth(80);
        
        usernameField = new TextField();
        usernameField.setPromptText("root");
        usernameField.setText("root");
        
        passwordField = new PasswordField();
        passwordField.setPromptText("Passwort");
        
        // Authentication method
        authMethodGroup = new ToggleGroup();
        passwordAuthRadio = new RadioButton("Passwort");
        passwordAuthRadio.setToggleGroup(authMethodGroup);
        passwordAuthRadio.setSelected(true);
        
        keyAuthRadio = new RadioButton("Privater Schlüssel");
        keyAuthRadio.setToggleGroup(authMethodGroup);
        
        // SSH Key selection
        savedSSHKeysCombo = new ComboBox<>();
        savedSSHKeysCombo.setPromptText("Gespeicherten SSH-Key auswählen...");
        savedSSHKeysCombo.setPrefWidth(300);
        savedSSHKeysCombo.setDisable(true);
        if (sshKeyManager != null) {
            savedSSHKeysCombo.getItems().addAll(sshKeyManager.getAllKeys());
        }
        
        // Update field states based on auth method
        authMethodGroup.selectedToggleProperty().addListener((obs, old, newVal) -> {
            boolean useKey = keyAuthRadio.isSelected();
            passwordField.setDisable(useKey);
            savedSSHKeysCombo.setDisable(!useKey);
        });
        
        saveConnectionCheck = new CheckBox("Verbindung speichern");
        
        connectionNameField = new TextField();
        connectionNameField.setPromptText("Verbindungsname (optional)");
        connectionNameField.setDisable(true);
        
        timeoutSpinner = new Spinner<>(1, 300, 15);
        timeoutSpinner.setEditable(true);
        timeoutSpinner.setPrefWidth(80);
        
        retrySpinner = new Spinner<>(1, 20, 4);
        retrySpinner.setEditable(true);
        retrySpinner.setPrefWidth(80);
        
        saveConnectionCheck.selectedProperty().addListener((obs, old, newVal) -> {
            connectionNameField.setDisable(!newVal);
        });
        
        // Saved connections dropdown
        savedConnectionsCombo = new ComboBox<>();
        savedConnectionsCombo.setPromptText("-- Gespeicherte Verbindung auswählen --");
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
                    setText("-- Gespeicherte Verbindung auswählen --");
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
        
        grid.add(new Label("Host:"), 0, 0);
        HBox hostBox = new HBox(10);
        hostBox.getChildren().addAll(hostField, new Label("Port:"), portSpinner);
        grid.add(hostBox, 1, 0);
        
        grid.add(new Label("Benutzer:"), 0, 1);
        grid.add(usernameField, 1, 1);
        
        grid.add(new Label("Authentifizierung:"), 0, 2);
        HBox authBox = new HBox(15, passwordAuthRadio, keyAuthRadio);
        grid.add(authBox, 1, 2);
        
        grid.add(new Label("Passwort:"), 0, 3);
        grid.add(passwordField, 1, 3);
        
        grid.add(new Label("SSH-Key:"), 0, 4);
        grid.add(savedSSHKeysCombo, 1, 4);
        
        grid.add(saveConnectionCheck, 1, 5);
        
        grid.add(new Label("Name:"), 0, 6);
        grid.add(connectionNameField, 1, 6);
        
        grid.add(new Separator(), 0, 7, 2, 1);
        
        grid.add(new Label("Verbindungstimeout:"), 0, 8);
        HBox timeoutBox = new HBox(10);
        timeoutBox.getChildren().addAll(timeoutSpinner, new Label("Sekunden"));
        grid.add(timeoutBox, 1, 8);
        
        grid.add(new Label("Wiederholungsversuche:"), 0, 9);
        HBox retryBox = new HBox(10);
        retryBox.getChildren().addAll(retrySpinner, new Label("Versuche"));
        grid.add(retryBox, 1, 9);
        
        // Add to pane
        if (savedConnections != null && !savedConnections.isEmpty()) {
            Label savedLabel = new Label("Gespeicherte Verbindungen:");
            pane.getChildren().addAll(savedLabel, savedConnectionsCombo, new Separator());
        }
        pane.getChildren().add(grid);
        
        return pane;
    }
    
    private VBox createGroupSelectionPane() {
        VBox pane = new VBox(10);
        pane.setPadding(new Insets(15));
        
        Label label = new Label("Wählen Sie eine Gruppe, um alle Verbindungen dieser Gruppe zu öffnen:");
        
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
                    setText(item + " (" + count + " Verbindung" + (count != 1 ? "en" : "") + ")");
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
        
        // Set timeout and retry from connection
        timeoutSpinner.getValueFactory().setValue(conn.getConnectionTimeoutSeconds());
        retrySpinner.getValueFactory().setValue(conn.getRetryCount());
        
        // Set authentication method
        if (conn.getAuthMethod() == AuthMethod.PUBLIC_KEY) {
            keyAuthRadio.setSelected(true);
            // Try to find and select SSH key
            if (conn.getSshKeyId() != null && sshKeyManager != null) {
                sshKeyManager.findKeyById(conn.getSshKeyId()).ifPresent(key -> {
                    savedSSHKeysCombo.setValue(key);
                });
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
        // Check if using a saved connection
        ServerConnection selected = savedConnectionsCombo.getValue();
        if (selected != null && 
            selected.getHost().equals(hostField.getText().trim()) &&
            selected.getPort() == portSpinner.getValue() &&
            selected.getUsername().equals(usernameField.getText().trim())) {
            // Using an existing saved connection
            // But if auth method changed, create a modified copy
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
                modified.setConnectionTimeoutSeconds(selected.getConnectionTimeoutSeconds());
                modified.setRetryCount(selected.getRetryCount());
                modified.setAuthMethod(AuthMethod.PUBLIC_KEY);
                if (savedSSHKeysCombo.getValue() != null) {
                    modified.setSshKeyId(savedSSHKeysCombo.getValue().getId());
                    modified.setPrivateKeyPath(sshKeyManager != null ? 
                        sshKeyManager.getEffectiveKeyPath(savedSSHKeysCombo.getValue()) : 
                        savedSSHKeysCombo.getValue().getKeyPath());
                }
                return new ConnectionResult(modified, null, false, true, null, false);
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
                modified.setConnectionTimeoutSeconds(selected.getConnectionTimeoutSeconds());
                modified.setRetryCount(selected.getRetryCount());
                modified.setAuthMethod(AuthMethod.PASSWORD);
                modified.setSshKeyId(null);
                return new ConnectionResult(modified, passwordField.getText(), false, true, null, false);
            }
            // Using an existing saved connection as-is
            return new ConnectionResult(selected, passwordField.getText(), false, true, null, false);
        }
        
        ServerConnection connection = new ServerConnection();
        connection.setHost(hostField.getText().trim());
        connection.setPort(portSpinner.getValue());
        connection.setUsername(usernameField.getText().trim().isEmpty() ? "root" : usernameField.getText().trim());
        connection.setConnectionTimeoutSeconds(timeoutSpinner.getValue());
        connection.setRetryCount(retrySpinner.getValue());
        
        // Set authentication method
        if (keyAuthRadio.isSelected()) {
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
        
        if (saveConnectionCheck.isSelected()) {
            String name = connectionNameField.getText().trim();
            connection.setName(name.isEmpty() ? connection.getUsername() + "@" + connection.getHost() : name);
        }
        
        return new ConnectionResult(connection, passwordField.getText(), saveConnectionCheck.isSelected(), false, null, false);
    }
    
    /**
     * Result containing connection details or group name or load project flag.
     * @param connection The server connection details (null for group or project load)
     * @param password The password entered
     * @param save Whether to save this as a new connection
     * @param existingSaved Whether this is an existing saved connection
     * @param groupName The group name to open (null for individual connection or project load)
     * @param loadProject Whether to load a project instead of connecting
     */
    public record ConnectionResult(ServerConnection connection, String password, boolean save, boolean existingSaved, String groupName, boolean loadProject) {
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

}
