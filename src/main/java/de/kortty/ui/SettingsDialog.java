package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.StoredCredential;
import de.kortty.model.GPGKey;
import de.kortty.model.WindowGeometry;
import de.kortty.security.PasswordVault;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.List;
import java.util.ArrayList;

/**
 * Dialog for editing global terminal settings.
 */
public class SettingsDialog extends Dialog<ConnectionSettings> {
    
    private final KorTTYApplication app;
    private final ConfigurationManager configManager;
    private final ConnectionSettings settings;
    private final GlobalSettings globalSettings;
    private final CredentialManager credentialManager;
    private final GPGKeyManager gpgKeyManager;
    private final List<Runnable> changeListeners = new ArrayList<>();
    
    // Font settings
    private final ComboBox<String> fontFamilyCombo;
    private final Spinner<Integer> fontSizeSpinner;
    
    // Colors
    private final ColorPicker foregroundColorPicker;
    private final ColorPicker backgroundColorPicker;
    private final ColorPicker cursorColorPicker;
    private final ColorPicker selectionColorPicker;
    
    // Terminal size
    private final Spinner<Integer> columnsSpinner;
    private final Spinner<Integer> rowsSpinner;
    private final Spinner<Integer> scrollbackSpinner;
    
    // Other settings
    private final CheckBox boldAsBrightCheck;
    private final ComboBox<String> encodingCombo;
    private final CheckBox showTerminalScrollbarCheck;
    
    // Security settings
    private final CheckBox requireMasterPasswordOnStartupCheck;
    
    // SSH Keep-Alive settings
    private final CheckBox sshKeepAliveCheck;
    private final Spinner<Integer> sshKeepAliveIntervalSpinner;
    
    // Backup settings
    private final Spinner<Integer> maxBackupSpinner;
    private final javafx.scene.control.RadioButton passwordEncryptionRadio;
    private final javafx.scene.control.RadioButton gpgEncryptionRadio;
    private final ComboBox<StoredCredential> backupCredentialCombo;
    private final ComboBox<GPGKey> backupGpgKeyCombo;
    
    // Window settings
    private final CheckBox rememberWindowGeometryCheck;
    private final CheckBox rememberDashboardStateCheck;
    private final CheckBox useFixedGeometryCheck;
    private final Spinner<Integer> fixedWidthSpinner;
    private final Spinner<Integer> fixedHeightSpinner;
    private final Spinner<Integer> fixedXSpinner;
    private final Spinner<Integer> fixedYSpinner;
    
    // Language settings
    private final ComboBox<String> languageCombo;
    
    public SettingsDialog(Stage owner, KorTTYApplication app, ConfigurationManager configManager, 
                          GlobalSettings globalSettings, CredentialManager credentialManager, 
                          GPGKeyManager gpgKeyManager) {
        this.app = app;
        this.configManager = configManager;
        this.settings = new ConnectionSettings(configManager.getGlobalSettings());
        this.globalSettings = globalSettings;
        this.credentialManager = credentialManager;
        this.gpgKeyManager = gpgKeyManager;
        
        setTitle("Einstellungen");
        setHeaderText("Globale Terminal-Einstellungen");
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);
        
        // Create tabs
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        // Font tab
        Tab fontTab = new Tab("Schrift");
        GridPane fontGrid = new GridPane();
        fontGrid.setHgap(10);
        fontGrid.setVgap(10);
        fontGrid.setPadding(new Insets(20));
        
        fontFamilyCombo = new ComboBox<>();
        fontFamilyCombo.getItems().addAll(getMonospaceFonts());
        fontFamilyCombo.setValue(settings.getFontFamily());
        fontFamilyCombo.setPrefWidth(200);
        
        fontSizeSpinner = new Spinner<>(8, 72, settings.getFontSize());
        fontSizeSpinner.setEditable(true);
        fontSizeSpinner.setPrefWidth(80);
        
        fontGrid.add(new Label("Schriftart:"), 0, 0);
        fontGrid.add(fontFamilyCombo, 1, 0);
        fontGrid.add(new Label("Schriftgröße:"), 0, 1);
        fontGrid.add(fontSizeSpinner, 1, 1);
        
        // Preview
        Label previewLabel = new Label("AaBbCcDdEe 0123456789");
        previewLabel.setStyle("-fx-background-color: #1e1e1e; -fx-padding: 10;");
        previewLabel.setTextFill(Color.WHITE);
        updatePreviewFont(previewLabel);
        
        fontFamilyCombo.valueProperty().addListener((obs, old, newVal) -> updatePreviewFont(previewLabel));
        fontSizeSpinner.valueProperty().addListener((obs, old, newVal) -> updatePreviewFont(previewLabel));
        
        fontGrid.add(new Label("Vorschau:"), 0, 2);
        fontGrid.add(previewLabel, 1, 2);
        
        fontTab.setContent(fontGrid);
        
        // Colors tab
        Tab colorsTab = new Tab("Farben");
        GridPane colorsGrid = new GridPane();
        colorsGrid.setHgap(10);
        colorsGrid.setVgap(10);
        colorsGrid.setPadding(new Insets(20));
        
        foregroundColorPicker = new ColorPicker(Color.web(settings.getForegroundColor()));
        backgroundColorPicker = new ColorPicker(Color.web(settings.getBackgroundColor()));
        cursorColorPicker = new ColorPicker(Color.web(settings.getCursorColor()));
        selectionColorPicker = new ColorPicker(Color.web(settings.getSelectionColor()));
        
        colorsGrid.add(new Label("Textfarbe:"), 0, 0);
        colorsGrid.add(foregroundColorPicker, 1, 0);
        colorsGrid.add(new Label("Hintergrund:"), 0, 1);
        colorsGrid.add(backgroundColorPicker, 1, 1);
        colorsGrid.add(new Label("Cursor:"), 0, 2);
        colorsGrid.add(cursorColorPicker, 1, 2);
        colorsGrid.add(new Label("Auswahl:"), 0, 3);
        colorsGrid.add(selectionColorPicker, 1, 3);
        
        // ANSI Colors section
        colorsGrid.add(new Separator(), 0, 4, 2, 1);
        colorsGrid.add(new Label("ANSI-Farben:"), 0, 5, 2, 1);
        
        String[] colorNames = {"Schwarz", "Rot", "Grün", "Gelb", "Blau", "Magenta", "Cyan", "Weiß"};
        HBox normalColorsBox = new HBox(5);
        HBox brightColorsBox = new HBox(5);
        
        for (int i = 0; i < 8; i++) {
            ColorPicker normalPicker = new ColorPicker(Color.web(settings.getAnsiColor(i, false)));
            normalPicker.setPrefWidth(40);
            normalPicker.setStyle("-fx-color-label-visible: false;");
            Tooltip.install(normalPicker, new Tooltip(colorNames[i]));
            normalColorsBox.getChildren().add(normalPicker);
            
            ColorPicker brightPicker = new ColorPicker(Color.web(settings.getAnsiColor(i, true)));
            brightPicker.setPrefWidth(40);
            brightPicker.setStyle("-fx-color-label-visible: false;");
            Tooltip.install(brightPicker, new Tooltip(colorNames[i] + " (hell)"));
            brightColorsBox.getChildren().add(brightPicker);
        }
        
        colorsGrid.add(new Label("Normal:"), 0, 6);
        colorsGrid.add(normalColorsBox, 1, 6);
        colorsGrid.add(new Label("Hell:"), 0, 7);
        colorsGrid.add(brightColorsBox, 1, 7);
        
        colorsTab.setContent(colorsGrid);
        
        // Terminal tab
        Tab terminalTab = new Tab("Terminal");
        GridPane terminalGrid = new GridPane();
        terminalGrid.setHgap(10);
        terminalGrid.setVgap(10);
        terminalGrid.setPadding(new Insets(20));
        
        columnsSpinner = new Spinner<>(40, 500, settings.getTerminalColumns());
        columnsSpinner.setEditable(true);
        columnsSpinner.setPrefWidth(80);
        
        rowsSpinner = new Spinner<>(10, 200, settings.getTerminalRows());
        rowsSpinner.setEditable(true);
        rowsSpinner.setPrefWidth(80);
        
        scrollbackSpinner = new Spinner<>(100, 100000, settings.getScrollbackLines(), 1000);
        scrollbackSpinner.setEditable(true);
        scrollbackSpinner.setPrefWidth(100);
        
        boldAsBrightCheck = new CheckBox("Fett als helle Farbe anzeigen");
        boldAsBrightCheck.setSelected(settings.isBoldAsBright());
        
        encodingCombo = new ComboBox<>();
        encodingCombo.getItems().addAll("UTF-8", "ISO-8859-1", "ISO-8859-15", "Windows-1252");
        encodingCombo.setValue(settings.getEncoding());
        
        showTerminalScrollbarCheck = new CheckBox("Scroll-Leiste im Terminal anzeigen");
        showTerminalScrollbarCheck.setSelected(globalSettings != null ? globalSettings.isShowTerminalScrollbar() : true);
        showTerminalScrollbarCheck.setTooltip(new Tooltip("Zeigt eine Scroll-Leiste auf der rechten Seite des Terminal-Fensters an"));
        
        // SSH Keep-Alive settings
        sshKeepAliveCheck = new CheckBox("SSH Keep-Alive aktivieren");
        sshKeepAliveCheck.setSelected(settings.isSshKeepAliveEnabled());
        sshKeepAliveCheck.setTooltip(new Tooltip("Verhindert Timeouts bei inaktiven Verbindungen durch regelmäßige Keep-Alive-Pakete"));
        
        sshKeepAliveIntervalSpinner = new Spinner<>(5, 600, settings.getSshKeepAliveInterval(), 5);
        sshKeepAliveIntervalSpinner.setEditable(true);
        sshKeepAliveIntervalSpinner.setPrefWidth(100);
        sshKeepAliveIntervalSpinner.setDisable(!sshKeepAliveCheck.isSelected());
        sshKeepAliveIntervalSpinner.setTooltip(new Tooltip("Intervall in Sekunden zwischen Keep-Alive-Paketen"));
        
        // Enable/disable spinner based on checkbox
        sshKeepAliveCheck.selectedProperty().addListener((obs, old, newVal) -> {
            sshKeepAliveIntervalSpinner.setDisable(!newVal);
        });
        
        terminalGrid.add(new Label("Spalten:"), 0, 0);
        terminalGrid.add(columnsSpinner, 1, 0);
        terminalGrid.add(new Label("Zeilen:"), 0, 1);
        terminalGrid.add(rowsSpinner, 1, 1);
        terminalGrid.add(new Label("Scrollback:"), 0, 2);
        terminalGrid.add(scrollbackSpinner, 1, 2);
        terminalGrid.add(new Label("Kodierung:"), 0, 3);
        terminalGrid.add(encodingCombo, 1, 3);
        terminalGrid.add(boldAsBrightCheck, 0, 4, 2, 1);
        terminalGrid.add(showTerminalScrollbarCheck, 0, 5, 2, 1);
        
        // SSH Keep-Alive section
        terminalGrid.add(new Separator(), 0, 6, 2, 1);
        terminalGrid.add(new Label("SSH Keep-Alive:"), 0, 7, 2, 1);
        terminalGrid.add(sshKeepAliveCheck, 0, 8, 2, 1);
        terminalGrid.add(new Label("Intervall (Sekunden):"), 0, 9);
        HBox keepAliveBox = new HBox(10);
        keepAliveBox.getChildren().addAll(sshKeepAliveIntervalSpinner, new Label("Sekunden"));
        terminalGrid.add(keepAliveBox, 1, 9);
        
        terminalTab.setContent(terminalGrid);
        
        // Backup tab
        Tab backupTab = new Tab("Backup");
        GridPane backupGrid = new GridPane();
        backupGrid.setHgap(10);
        backupGrid.setVgap(10);
        backupGrid.setPadding(new Insets(20));
        
        int backupRow = 0;
        
        Label backupHeader = new Label("Backup-Einstellungen");
        backupHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        backupGrid.add(backupHeader, 0, backupRow++, 2, 1);
        
        // Max backup count
        Label maxBackupLabel = new Label("Maximale Anzahl Backups:");
        maxBackupLabel.setTooltip(new Tooltip("0 = unbegrenzt, sonst werden älteste Backups gelöscht"));
        
        maxBackupSpinner = new Spinner<>(0, 100, globalSettings != null ? globalSettings.getMaxBackupCount() : 10);
        maxBackupSpinner.setEditable(true);
        maxBackupSpinner.setPrefWidth(150);
        maxBackupSpinner.setTooltip(new Tooltip("0 = unbegrenzt"));
        
        backupGrid.add(maxBackupLabel, 0, backupRow);
        backupGrid.add(maxBackupSpinner, 1, backupRow++);
        
        Label infoLabel = new Label("(0 = unbegrenzt, älteste Backups werden automatisch gelöscht)");
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        backupGrid.add(infoLabel, 0, backupRow++, 2, 1);
        
        // Verschlüsselung (PFLICHT!)
        Label encryptionHeader = new Label("Verschlüsselung (Pflicht)");
        encryptionHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        backupGrid.add(encryptionHeader, 0, backupRow++, 2, 1);
        
        javafx.scene.control.ToggleGroup encryptionGroup = new javafx.scene.control.ToggleGroup();
        
        passwordEncryptionRadio = new javafx.scene.control.RadioButton("ZIP mit Passwort");
        passwordEncryptionRadio.setToggleGroup(encryptionGroup);
        passwordEncryptionRadio.setSelected(globalSettings == null || 
            globalSettings.getBackupEncryptionType() == GlobalSettings.BackupEncryptionType.PASSWORD);
        backupGrid.add(passwordEncryptionRadio, 0, backupRow++, 2, 1);
        
        // Password credential combo
        backupCredentialCombo = new ComboBox<>();
        backupCredentialCombo.setPromptText("Passwort aus Verwaltung wählen...");
        backupCredentialCombo.setPrefWidth(300);
        backupCredentialCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<StoredCredential>() {
            @Override
            protected void updateItem(StoredCredential item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getUsername() + ")");
            }
        });
        backupCredentialCombo.setButtonCell(new javafx.scene.control.ListCell<StoredCredential>() {
            @Override
            protected void updateItem(StoredCredential item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getUsername() + ")");
            }
        });
        if (credentialManager != null) {
            backupCredentialCombo.getItems().addAll(credentialManager.getAllCredentials());
            // Restore selection
            if (globalSettings != null && globalSettings.getBackupCredentialId() != null) {
                credentialManager.findCredentialById(globalSettings.getBackupCredentialId())
                    .ifPresent(backupCredentialCombo::setValue);
            }
        }
        backupGrid.add(new Label("  Passwort:"), 0, backupRow);
        backupGrid.add(backupCredentialCombo, 1, backupRow++);
        
        // GPG encryption
        gpgEncryptionRadio = new javafx.scene.control.RadioButton("GPG-Verschlüsselung");
        gpgEncryptionRadio.setToggleGroup(encryptionGroup);
        gpgEncryptionRadio.setSelected(globalSettings != null && 
            globalSettings.getBackupEncryptionType() == GlobalSettings.BackupEncryptionType.GPG);
        backupGrid.add(gpgEncryptionRadio, 0, backupRow++, 2, 1);
        
        // GPG key combo
        backupGpgKeyCombo = new ComboBox<>();
        backupGpgKeyCombo.setPromptText("GPG-Schlüssel wählen...");
        backupGpgKeyCombo.setPrefWidth(300);
        backupGpgKeyCombo.setCellFactory(lv -> new javafx.scene.control.ListCell<GPGKey>() {
            @Override
            protected void updateItem(GPGKey item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getKeyId() + ")");
            }
        });
        backupGpgKeyCombo.setButtonCell(new javafx.scene.control.ListCell<GPGKey>() {
            @Override
            protected void updateItem(GPGKey item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getName() + " (" + item.getKeyId() + ")");
            }
        });
        if (gpgKeyManager != null) {
            backupGpgKeyCombo.getItems().addAll(gpgKeyManager.getAllKeys());
            // Restore selection
            if (globalSettings != null && globalSettings.getBackupGpgKeyId() != null) {
                gpgKeyManager.getAllKeys().stream()
                    .filter(k -> k.getId().equals(globalSettings.getBackupGpgKeyId()))
                    .findFirst()
                    .ifPresent(backupGpgKeyCombo::setValue);
            }
        }
        backupGrid.add(new Label("  GPG-Schlüssel:"), 0, backupRow);
        backupGrid.add(backupGpgKeyCombo, 1, backupRow++);
        
        // Dynamic enable/disable based on radio selection
        backupCredentialCombo.setDisable(!passwordEncryptionRadio.isSelected());
        backupGpgKeyCombo.setDisable(!gpgEncryptionRadio.isSelected());
        
        passwordEncryptionRadio.selectedProperty().addListener((obs, old, selected) -> {
            backupCredentialCombo.setDisable(!selected);
        });
        
        gpgEncryptionRadio.selectedProperty().addListener((obs, old, selected) -> {
            backupGpgKeyCombo.setDisable(!selected);
        });
        
        // Warning message
        Label warningLabel = new Label("⚠ Backups sind IMMER verschlüsselt!");
        warningLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #d97706; -fx-font-weight: bold;");
        backupGrid.add(warningLabel, 0, backupRow++, 2, 1);
        
        // Show last backup info
        if (globalSettings != null && globalSettings.getLastBackupTime() > 0) {
            Label lastBackupLabel = new Label("Letztes Backup:");
            java.time.LocalDateTime lastBackup = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(globalSettings.getLastBackupTime()),
                java.time.ZoneId.systemDefault()
            );
            String lastBackupText = lastBackup.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));
            if (globalSettings.getLastBackupPath() != null) {
                lastBackupText += "\n→ " + globalSettings.getLastBackupPath();
            }
            Label lastBackupValue = new Label(lastBackupText);
            lastBackupValue.setStyle("-fx-font-size: 10px;");
            lastBackupValue.setWrapText(true);
            lastBackupValue.setMaxWidth(350);
            
            backupGrid.add(lastBackupLabel, 0, backupRow);
            backupGrid.add(lastBackupValue, 1, backupRow++);
        }
        
        backupTab.setContent(backupGrid);
        
        // Window tab
        Tab windowTab = new Tab("Fenster");
        GridPane windowGrid = new GridPane();
        windowGrid.setHgap(10);
        windowGrid.setVgap(10);
        windowGrid.setPadding(new Insets(20));
        
        int windowRow = 0;
        
        Label windowHeader = new Label("Fenster-Einstellungen");
        windowHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        windowGrid.add(windowHeader, 0, windowRow++, 2, 1);
        
        rememberWindowGeometryCheck = new CheckBox("Fenstergeometrie merken");
        rememberWindowGeometryCheck.setSelected(globalSettings != null ? globalSettings.isRememberWindowGeometry() : true);
        rememberWindowGeometryCheck.setTooltip(new Tooltip("Speichert Position und Größe des Hauptfensters beim Schließen"));
        windowGrid.add(rememberWindowGeometryCheck, 0, windowRow++, 2, 1);
        
        Label windowInfoLabel = new Label("(Beim nächsten Start wird das Fenster an der letzten Position geöffnet)");
        windowInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        windowGrid.add(windowInfoLabel, 0, windowRow++, 2, 1);
        
        rememberDashboardStateCheck = new CheckBox("Dashboard-Status merken");
        rememberDashboardStateCheck.setSelected(globalSettings != null ? globalSettings.isRememberDashboardState() : true);
        rememberDashboardStateCheck.setTooltip(new Tooltip("Speichert, ob das Dashboard beim Schließen geöffnet war"));
        windowGrid.add(rememberDashboardStateCheck, 0, windowRow++, 2, 1);
        
        Label dashboardInfoLabel = new Label("(Beim nächsten Start wird das Dashboard wieder angezeigt, wenn es zuvor geöffnet war)");
        dashboardInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        windowGrid.add(dashboardInfoLabel, 0, windowRow++, 2, 1);
        
        // Fixed geometry section
        windowGrid.add(new Separator(), 0, windowRow++, 2, 1);
        
        Label fixedGeometryHeader = new Label("Feste Fenstergeometrie");
        fixedGeometryHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        windowGrid.add(fixedGeometryHeader, 0, windowRow++, 2, 1);
        
        useFixedGeometryCheck = new CheckBox("Feste Fenstergeometrie verwenden");
        useFixedGeometryCheck.setSelected(globalSettings != null && globalSettings.isUseFixedWindowGeometry());
        useFixedGeometryCheck.setTooltip(new Tooltip("Fenster wird immer mit der angegebenen Größe und Position geöffnet"));
        windowGrid.add(useFixedGeometryCheck, 0, windowRow++, 2, 1);
        
        // Get current or default values
        WindowGeometry fixedGeo = globalSettings != null ? globalSettings.getFixedWindowGeometry() : null;
        int currentWidth = fixedGeo != null ? (int)fixedGeo.getWidth() : 1200;
        int currentHeight = fixedGeo != null ? (int)fixedGeo.getHeight() : 800;
        int currentX = fixedGeo != null ? (int)fixedGeo.getX() : 100;
        int currentY = fixedGeo != null ? (int)fixedGeo.getY() : 100;
        
        // Width and Height
        Label sizeLabel = new Label("Größe:");
        fixedWidthSpinner = new Spinner<>(400, 4000, currentWidth);
        fixedWidthSpinner.setEditable(true);
        fixedWidthSpinner.setPrefWidth(100);
        fixedWidthSpinner.setDisable(!useFixedGeometryCheck.isSelected());
        
        fixedHeightSpinner = new Spinner<>(300, 3000, currentHeight);
        fixedHeightSpinner.setEditable(true);
        fixedHeightSpinner.setPrefWidth(100);
        fixedHeightSpinner.setDisable(!useFixedGeometryCheck.isSelected());
        
        HBox sizeBox = new HBox(10);
        sizeBox.getChildren().addAll(new Label("Breite:"), fixedWidthSpinner, new Label("Höhe:"), fixedHeightSpinner);
        
        windowGrid.add(sizeLabel, 0, windowRow);
        windowGrid.add(sizeBox, 1, windowRow++);
        
        // Position
        Label posLabel = new Label("Position:");
        fixedXSpinner = new Spinner<>(0, 5000, currentX);
        fixedXSpinner.setEditable(true);
        fixedXSpinner.setPrefWidth(100);
        fixedXSpinner.setDisable(!useFixedGeometryCheck.isSelected());
        
        fixedYSpinner = new Spinner<>(0, 3000, currentY);
        fixedYSpinner.setEditable(true);
        fixedYSpinner.setPrefWidth(100);
        fixedYSpinner.setDisable(!useFixedGeometryCheck.isSelected());
        
        HBox posBox = new HBox(10);
        posBox.getChildren().addAll(new Label("X:"), fixedXSpinner, new Label("Y:"), fixedYSpinner);
        
        windowGrid.add(posLabel, 0, windowRow);
        windowGrid.add(posBox, 1, windowRow++);
        
        // Enable/disable spinners based on checkbox
        useFixedGeometryCheck.selectedProperty().addListener((obs, old, newVal) -> {
            fixedWidthSpinner.setDisable(!newVal);
            fixedHeightSpinner.setDisable(!newVal);
            fixedXSpinner.setDisable(!newVal);
            fixedYSpinner.setDisable(!newVal);
        });
        
        Label fixedGeometryInfoLabel = new Label("(Hat Vorrang vor 'Fenstergeometrie merken' - Fenster öffnet immer an angegebener Position)");
        fixedGeometryInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        windowGrid.add(fixedGeometryInfoLabel, 0, windowRow++, 2, 1);
        
        windowTab.setContent(windowGrid);
        
        // Security tab
        Tab securityTab = new Tab("Sicherheit");
        GridPane securityGrid = new GridPane();
        securityGrid.setHgap(10);
        securityGrid.setVgap(10);
        securityGrid.setPadding(new Insets(20));
        
        int securityRow = 0;
        
        Label securityHeader = new Label("Master-Passwort");
        securityHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        securityGrid.add(securityHeader, 0, securityRow++, 2, 1);
        
        Label passwordInfoLabel = new Label(
            "Das Master-Passwort wird verwendet, um alle gespeicherten Verbindungspasswörter zu verschlüsseln.\n" +
            "Wenn Sie das Master-Passwort ändern, werden alle gespeicherten Passwörter automatisch neu verschlüsselt."
        );
        passwordInfoLabel.setWrapText(true);
        passwordInfoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        securityGrid.add(passwordInfoLabel, 0, securityRow++, 2, 1);
        
        Button changePasswordButton = new Button("Master-Passwort ändern...");
        changePasswordButton.setOnAction(e -> changeMasterPassword());
        securityGrid.add(changePasswordButton, 0, securityRow++, 2, 1);
        
        // Separator
        securityGrid.add(new Separator(), 0, securityRow++, 2, 1);
        
        // Master password on startup option
        requireMasterPasswordOnStartupCheck = new CheckBox("Master-Passwort beim Programmstart anfordern");
        requireMasterPasswordOnStartupCheck.setSelected(globalSettings != null ? globalSettings.isRequireMasterPasswordOnStartup() : true);
        requireMasterPasswordOnStartupCheck.setTooltip(new Tooltip(
            "Wenn deaktiviert, wird beim Programmstart kein Master-Passwort-Dialog angezeigt.\n" +
            "⚠️ WARNUNG: Dies ist unsicher, da verschlüsselte Daten ohne Passwort-Eingabe nicht entschlüsselt werden können."
        ));
        
        Label masterPasswordWarningLabel = new Label(
            "⚠️ WARNUNG: Wenn diese Option deaktiviert ist, können verschlüsselte Passwörter und Schlüssel " +
            "beim Programmstart nicht automatisch geladen werden. Sie müssen das Master-Passwort manuell " +
            "eingeben, wenn Sie auf verschlüsselte Daten zugreifen möchten."
        );
        masterPasswordWarningLabel.setWrapText(true);
        masterPasswordWarningLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #ff6b6b; -fx-font-weight: bold;");
        masterPasswordWarningLabel.setVisible(!requireMasterPasswordOnStartupCheck.isSelected());
        
        // Show/hide warning based on checkbox state
        requireMasterPasswordOnStartupCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            masterPasswordWarningLabel.setVisible(!newVal);
        });
        
        securityGrid.add(requireMasterPasswordOnStartupCheck, 0, securityRow++, 2, 1);
        securityGrid.add(masterPasswordWarningLabel, 0, securityRow++, 2, 1);
        
        securityTab.setContent(securityGrid);
        
        // Language tab
        Tab languageTab = new Tab("Sprache");
        GridPane languageGrid = new GridPane();
        languageGrid.setHgap(10);
        languageGrid.setVgap(10);
        languageGrid.setPadding(new Insets(20));
        
        languageCombo = new ComboBox<>();
        languageCombo.getItems().add("Auto (System)");
        languageCombo.getItems().add("English");
        languageCombo.getItems().add("Deutsch");
        languageCombo.getItems().add("Italiano");
        languageCombo.getItems().add("Español");
        languageCombo.getItems().add("Português");
        languageCombo.getItems().add("Français");
        languageCombo.getItems().add("Hrvatski");
        languageCombo.getItems().add("Nederlands");
        
        // Set current language
        String currentLang = globalSettings != null ? globalSettings.getLanguage() : null;
        if (currentLang == null || currentLang.isEmpty()) {
            languageCombo.setValue("Auto (System)");
        } else {
            // Map language codes to display names
            switch (currentLang.toLowerCase()) {
                case "en": languageCombo.setValue("English"); break;
                case "de": languageCombo.setValue("Deutsch"); break;
                case "it": languageCombo.setValue("Italiano"); break;
                case "es": languageCombo.setValue("Español"); break;
                case "pt": languageCombo.setValue("Português"); break;
                case "fr": languageCombo.setValue("Français"); break;
                case "hr": languageCombo.setValue("Hrvatski"); break;
                case "nl": languageCombo.setValue("Nederlands"); break;
                default: languageCombo.setValue("Auto (System)");
            }
        }
        
        languageGrid.add(new Label("Sprache auswählen:"), 0, 0);
        languageGrid.add(languageCombo, 1, 0);
        
        Label languageInfo = new Label(
            "Die Spracheinstellung wird nach dem Neustart der Anwendung wirksam."
        );
        languageInfo.setWrapText(true);
        languageInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        languageGrid.add(languageInfo, 0, 1, 2, 1);
        
        languageTab.setContent(languageGrid);
        
        tabPane.getTabs().addAll(fontTab, colorsTab, terminalTab, backupTab, windowTab, securityTab, languageTab);
        
        VBox content = new VBox(tabPane);
        content.setPrefSize(500, 400);
        getDialogPane().setContent(content);
        
        // Buttons
        ButtonType saveButtonType = new ButtonType("Speichern", ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                applySettings();
                
                // Save settings to both ConnectionSettings and GlobalSettings
                configManager.setGlobalSettings(settings);
                globalSettings.setDefaultTerminalSettings(new ConnectionSettings(settings));
                
                // Save global settings
                try {
                    app.getGlobalSettingsManager().save();
                } catch (Exception e) {
                    org.slf4j.LoggerFactory.getLogger(getClass())
                        .error("Failed to save global settings", e);
                }
                
                // Notify listeners about settings change
                notifyListeners();
                
                return settings;
            }
            return null;
        });
    }
    
    private void applySettings() {
        settings.setFontFamily(fontFamilyCombo.getValue());
        settings.setFontSize(fontSizeSpinner.getValue());
        settings.setForegroundColor(toHex(foregroundColorPicker.getValue()));
        settings.setBackgroundColor(toHex(backgroundColorPicker.getValue()));
        settings.setCursorColor(toHex(cursorColorPicker.getValue()));
        settings.setSelectionColor(toHex(selectionColorPicker.getValue()));
        settings.setTerminalColumns(columnsSpinner.getValue());
        settings.setTerminalRows(rowsSpinner.getValue());
        settings.setScrollbackLines(scrollbackSpinner.getValue());
        settings.setBoldAsBright(boldAsBrightCheck.isSelected());
        settings.setEncoding(encodingCombo.getValue());
        settings.setSshKeepAliveEnabled(sshKeepAliveCheck.isSelected());
        settings.setSshKeepAliveInterval(sshKeepAliveIntervalSpinner.getValue());
        
        // Save backup settings to GlobalSettings
        if (globalSettings != null) {
            globalSettings.setShowTerminalScrollbar(showTerminalScrollbarCheck.isSelected());
            globalSettings.setRequireMasterPasswordOnStartup(requireMasterPasswordOnStartupCheck.isSelected());
            
            // Save language setting
            String selectedLanguage = languageCombo.getValue();
            if (selectedLanguage == null || selectedLanguage.equals("Auto (System)")) {
                globalSettings.setLanguage(null); // null means auto-detect
            } else {
                // Map display names to language codes
                String langCode = null;
                switch (selectedLanguage) {
                    case "English": langCode = "en"; break;
                    case "Deutsch": langCode = "de"; break;
                    case "Italiano": langCode = "it"; break;
                    case "Español": langCode = "es"; break;
                    case "Português": langCode = "pt"; break;
                    case "Français": langCode = "fr"; break;
                    case "Hrvatski": langCode = "hr"; break;
                    case "Nederlands": langCode = "nl"; break;
                }
                globalSettings.setLanguage(langCode);
            }
            
            globalSettings.setMaxBackupCount(maxBackupSpinner.getValue());
            
            // Save encryption settings
            if (passwordEncryptionRadio.isSelected()) {
                globalSettings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.PASSWORD);
                if (backupCredentialCombo.getValue() != null) {
                    globalSettings.setBackupCredentialId(backupCredentialCombo.getValue().getId());
                } else {
                    globalSettings.setBackupCredentialId(null);
                }
                globalSettings.setBackupGpgKeyId(null);
            } else if (gpgEncryptionRadio.isSelected()) {
                globalSettings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.GPG);
                if (backupGpgKeyCombo.getValue() != null) {
                    globalSettings.setBackupGpgKeyId(backupGpgKeyCombo.getValue().getId());
                } else {
                    globalSettings.setBackupGpgKeyId(null);
                }
                globalSettings.setBackupCredentialId(null);
            }
            
            // Save window settings
            globalSettings.setRememberWindowGeometry(rememberWindowGeometryCheck.isSelected());
            globalSettings.setRememberDashboardState(rememberDashboardStateCheck.isSelected());
            
            // Save fixed geometry settings
            globalSettings.setUseFixedWindowGeometry(useFixedGeometryCheck.isSelected());
            if (useFixedGeometryCheck.isSelected()) {
                WindowGeometry fixedGeo = new WindowGeometry();
                fixedGeo.setWidth(fixedWidthSpinner.getValue());
                fixedGeo.setHeight(fixedHeightSpinner.getValue());
                fixedGeo.setX(fixedXSpinner.getValue());
                fixedGeo.setY(fixedYSpinner.getValue());
                globalSettings.setFixedWindowGeometry(fixedGeo);
            }
        }
    }
    
    private void updatePreviewFont(Label previewLabel) {
        previewLabel.setFont(Font.font(fontFamilyCombo.getValue(), fontSizeSpinner.getValue()));
    }
    
    private List<String> getMonospaceFonts() {
        return List.of(
                "Monospaced",
                "Courier New",
                "Consolas",
                "Monaco",
                "Menlo",
                "Source Code Pro",
                "JetBrains Mono",
                "Fira Code",
                "SF Mono"
        );
    }
    
    private String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }

    
    public void addChangeListener(Runnable listener) {
        changeListeners.add(listener);
    }
    
    private void notifyListeners() {
        for (Runnable listener : changeListeners) {
            listener.run();
        }
    }
    
    /**
     * Shows dialog to change master password and re-encrypts all stored passwords.
     */
    private void changeMasterPassword() {
        Dialog<char[]> passwordDialog = new Dialog<>();
        passwordDialog.setTitle("Master-Passwort ändern");
        passwordDialog.setHeaderText("Geben Sie das aktuelle und das neue Master-Passwort ein");
        passwordDialog.initModality(Modality.WINDOW_MODAL);
        passwordDialog.initOwner(getDialogPane().getScene().getWindow());
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        PasswordField oldPasswordField = new PasswordField();
        oldPasswordField.setPromptText("Aktuelles Passwort");
        oldPasswordField.setPrefWidth(250);
        
        PasswordField newPasswordField = new PasswordField();
        newPasswordField.setPromptText("Neues Passwort (mindestens 6 Zeichen)");
        newPasswordField.setPrefWidth(250);
        
        PasswordField confirmPasswordField = new PasswordField();
        confirmPasswordField.setPromptText("Neues Passwort bestätigen");
        confirmPasswordField.setPrefWidth(250);
        
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel.setVisible(false);
        
        int row = 0;
        grid.add(new Label("Aktuelles Passwort:"), 0, row);
        grid.add(oldPasswordField, 1, row++);
        grid.add(new Label("Neues Passwort:"), 0, row);
        grid.add(newPasswordField, 1, row++);
        grid.add(new Label("Passwort bestätigen:"), 0, row);
        grid.add(confirmPasswordField, 1, row++);
        grid.add(errorLabel, 0, row++, 2, 1);
        
        passwordDialog.getDialogPane().setContent(grid);
        
        ButtonType changeButtonType = new ButtonType("Ändern", ButtonBar.ButtonData.OK_DONE);
        passwordDialog.getDialogPane().getButtonTypes().addAll(changeButtonType, ButtonType.CANCEL);
        
        Button changeButton = (Button) passwordDialog.getDialogPane().lookupButton(changeButtonType);
        changeButton.setDisable(true);
        
        // Validate fields
        Runnable validator = () -> {
            boolean valid = !oldPasswordField.getText().isEmpty() &&
                           !newPasswordField.getText().isEmpty() &&
                           !confirmPasswordField.getText().isEmpty() &&
                           newPasswordField.getText().length() >= 6;
            changeButton.setDisable(!valid);
        };
        
        oldPasswordField.textProperty().addListener((obs, old, newVal) -> validator.run());
        newPasswordField.textProperty().addListener((obs, old, newVal) -> validator.run());
        confirmPasswordField.textProperty().addListener((obs, old, newVal) -> validator.run());
        
        passwordDialog.setResultConverter(buttonType -> {
            if (buttonType == changeButtonType) {
                String oldPassword = oldPasswordField.getText();
                String newPassword = newPasswordField.getText();
                String confirmPassword = confirmPasswordField.getText();
                
                // Validate
                if (oldPassword.isEmpty()) {
                    errorLabel.setText("Bitte aktuelles Passwort eingeben");
                    errorLabel.setVisible(true);
                    return null;
                }
                
                if (newPassword.length() < 6) {
                    errorLabel.setText("Neues Passwort muss mindestens 6 Zeichen haben");
                    errorLabel.setVisible(true);
                    return null;
                }
                
                if (!newPassword.equals(confirmPassword)) {
                    errorLabel.setText("Neue Passwörter stimmen nicht überein");
                    errorLabel.setVisible(true);
                    return null;
                }
                
                return newPassword.toCharArray();
            }
            return null;
        });
        
        passwordDialog.showAndWait().ifPresent(newPasswordChars -> {
            try {
                // Verify old password and change to new password
                char[] oldPasswordChars = oldPasswordField.getText().toCharArray();
                
                // Change master password
                app.getMasterPasswordManager().changePassword(oldPasswordChars, newPasswordChars);
                
                // Re-encrypt all connection passwords
                PasswordVault oldVault = new PasswordVault(
                    app.getMasterPasswordManager().getEncryptionService(),
                    oldPasswordChars
                );
                
                PasswordVault newVault = new PasswordVault(
                    app.getMasterPasswordManager().getEncryptionService(),
                    newPasswordChars
                );
                
                List<ServerConnection> allConnections = configManager.getConnections();
                int reEncryptedCount = 0;
                
                for (ServerConnection connection : allConnections) {
                    try {
                        // Re-encrypt password if exists
                        String plainPassword = oldVault.retrievePassword(connection);
                        if (plainPassword != null) {
                            newVault.storePassword(connection, plainPassword);
                            reEncryptedCount++;
                        }
                        
                        // Re-encrypt key passphrase if exists
                        String plainPassphrase = oldVault.retrieveKeyPassphrase(connection);
                        if (plainPassphrase != null) {
                            newVault.storeKeyPassphrase(connection, plainPassphrase);
                        }
                    } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger(getClass())
                            .warn("Failed to re-encrypt password for connection: {}", connection.getName(), e);
                    }
                }
                
                // Save connections with new encryption
                configManager.save(app.getMasterPasswordManager().getDerivedKey());
                
                // Clear sensitive data
                java.util.Arrays.fill(oldPasswordChars, '\0');
                java.util.Arrays.fill(newPasswordChars, '\0');
                
                Alert success = new Alert(Alert.AlertType.INFORMATION);
                success.setTitle("Passwort geändert");
                success.setHeaderText("Master-Passwort erfolgreich geändert");
                success.setContentText(String.format(
                    "Das Master-Passwort wurde geändert und %d Verbindungspasswörter wurden neu verschlüsselt.",
                    reEncryptedCount
                ));
                success.showAndWait();
                
            } catch (SecurityException e) {
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle("Fehler");
                error.setHeaderText("Passwort-Änderung fehlgeschlagen");
                error.setContentText("Das aktuelle Passwort ist falsch: " + e.getMessage());
                error.showAndWait();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Failed to change master password", e);
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle("Fehler");
                error.setHeaderText("Passwort-Änderung fehlgeschlagen");
                error.setContentText("Ein Fehler ist aufgetreten: " + e.getMessage());
                error.showAndWait();
            }
        });
    }
}
