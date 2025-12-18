package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.security.PasswordVault;
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
    private final char[] masterPassword;
    private final int topConnectionsCount;
    
    // Individual connection tab
    private ComboBox<ServerConnection> savedConnectionsCombo;
    private TextField hostField;
    private Spinner<Integer> portSpinner;
    private TextField usernameField;
    private PasswordField passwordField;
    private CheckBox saveConnectionCheck;
    private TextField connectionNameField;
    
    // Group tab
    private ListView<String> groupListView;
    
    public QuickConnectDialog(Stage owner, List<ServerConnection> savedConnections, PasswordVault passwordVault, 
                              de.kortty.core.CredentialManager credentialManager, char[] masterPassword, int topConnectionsCount) {
        this.savedConnections = savedConnections;
        this.passwordVault = passwordVault;
        this.credentialManager = credentialManager;
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
        // Get top N most used connections
        List<ServerConnection> topConnections = savedConnections.stream()
                .filter(c -> c.getUsageCount() > 0)
                .sorted((a, b) -> {
                    // Sort by usage count descending, then by last used descending
                    int usageCompare = Integer.compare(b.getUsageCount(), a.getUsageCount());
                    if (usageCompare != 0) return usageCompare;
                    return Long.compare(b.getLastUsed(), a.getLastUsed());
                })
                .limit(topConnectionsCount)
                .collect(Collectors.toList());
        
        if (topConnections.isEmpty()) {
            return null;
        }
        
        VBox box = new VBox(8);
        Label label = new Label("Häufig genutzte Verbindungen:");
        label.setStyle("-fx-font-weight: bold;");
        
        FlowPane flowPane = new FlowPane(8, 8);
        
        for (ServerConnection conn : topConnections) {
            Button btn = new Button(conn.getName());
            btn.setTooltip(new Tooltip(conn.getUsername() + "@" + conn.getHost() + ":" + conn.getPort() + 
                    "\nVerwendet: " + conn.getUsageCount() + "x"));
            btn.setOnAction(e -> {
                // Fill in the form and close dialog
                fillFormWithConnection(conn);
                setResult(new ConnectionResult(conn, 
                        getConnectionPassword(conn), 
                        false, true, null, false));
                close();
            });
            flowPane.getChildren().add(btn);
        }
        
        box.getChildren().addAll(label, flowPane);
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
        
        saveConnectionCheck = new CheckBox("Verbindung speichern");
        
        connectionNameField = new TextField();
        connectionNameField.setPromptText("Verbindungsname (optional)");
        connectionNameField.setDisable(true);
        
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
        
        grid.add(new Label("Passwort:"), 0, 2);
        grid.add(passwordField, 1, 2);
        
        grid.add(saveConnectionCheck, 1, 3);
        
        grid.add(new Label("Name:"), 0, 4);
        grid.add(connectionNameField, 1, 4);
        
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
            return new ConnectionResult(selected, passwordField.getText(), false, true, null, false);
        }
        
        ServerConnection connection = new ServerConnection();
        connection.setHost(hostField.getText().trim());
        connection.setPort(portSpinner.getValue());
        connection.setUsername(usernameField.getText().trim().isEmpty() ? "root" : usernameField.getText().trim());
        
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
