package de.kortty.ui;

import de.kortty.model.ServerConnection;
import de.kortty.model.ConnectionSettings;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
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
    private ComboBox<String> fontFamilyCombo;
    private Spinner<Integer> fontSizeSpinner;
    private ColorPicker foregroundColorPicker;
    private ColorPicker backgroundColorPicker;
    private CheckBox closeWithoutConfirmCheck;
    
    public ConnectionEditDialog(Stage owner, ServerConnection existingConnection) {
        this.connection = existingConnection != null ? existingConnection : new ServerConnection();
        
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
        
        groupField = new TextField(connection.getGroup());
        groupField.setPromptText("Optional - zur Gruppierung");
        
        // Authentication method
        authMethodGroup = new ToggleGroup();
        passwordAuthRadio = new RadioButton("Passwort");
        passwordAuthRadio.setToggleGroup(authMethodGroup);
        
        keyAuthRadio = new RadioButton("Privater Schlüssel");
        keyAuthRadio.setToggleGroup(authMethodGroup);
        
        if (connection.getAuthMethod() == ServerConnection.AuthMethod.PUBLIC_KEY) {
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
        
        connectionGrid.add(new Label("Passwort:"), 0, row);
        connectionGrid.add(passwordField, 1, row++);
        
        connectionGrid.add(new Label("Schlüsseldatei:"), 0, row);
        connectionGrid.add(keyPathBox, 1, row++);
        
        connectionGrid.add(new Label("Passphrase:"), 0, row);
        connectionGrid.add(keyPassphraseField, 1, row++);
        
        connectionTab.setContent(connectionGrid);
        
        // Tab 2: Terminal settings
        Tab settingsTab = createSettingsTab();
        
        tabPane.getTabs().addAll(connectionTab, settingsTab);
        getDialogPane().setContent(tabPane);
        
        // Buttons
        ButtonType saveButtonType = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        
        // Validation
        Button saveButton = (Button) getDialogPane().lookupButton(saveButtonType);
        saveButton.setDisable(true);
        
        hostField.textProperty().addListener((obs, old, newVal) -> validateForm(saveButton));
        validateForm(saveButton);
        
        // Result converter
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                connection.setName(nameField.getText().trim());
                connection.setHost(hostField.getText().trim());
                connection.setPort(portSpinner.getValue());
                connection.setUsername(usernameField.getText().trim().isEmpty() ? "root" : usernameField.getText().trim());
                connection.setGroup(groupField.getText().trim().isEmpty() ? null : groupField.getText().trim());
                
                if (keyAuthRadio.isSelected()) {
                    connection.setAuthMethod(ServerConnection.AuthMethod.PUBLIC_KEY);
                    connection.setPrivateKeyPath(keyPathField.getText().trim());
                    // Note: Key passphrase should be encrypted before saving
                } else {
                    connection.setAuthMethod(ServerConnection.AuthMethod.PASSWORD);
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
}
