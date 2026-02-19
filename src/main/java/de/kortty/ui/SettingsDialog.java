package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.ui.I18n;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.DynamicLanguageGenerator;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.GoogleTranslationService;
import de.kortty.core.DeepLTranslationService;
import de.kortty.core.LibreTranslateTranslationService;
import de.kortty.core.MicrosoftTranslationService;
import de.kortty.core.YandexTranslationService;
import de.kortty.core.LanguageManager;
import de.kortty.core.SSHKeyManager;
import de.kortty.core.ThemeManager;
import de.kortty.core.TranslationService;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.Theme;
import de.kortty.model.TranslationApiProvider;
import de.kortty.model.StoredCredential;
import de.kortty.model.GPGKey;
import de.kortty.model.WindowGeometry;
import de.kortty.security.PasswordStrengthChecker;
import de.kortty.security.PasswordVault;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.Modality;
import javafx.stage.Stage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

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
    private final CheckBox commandTimestampsCheck;
    private final CheckBox terminalDragDropCheck;
    private final CheckBox terminalCopyOnSelectCheck;
    
    // Security settings
    private final CheckBox requireMasterPasswordOnStartupCheck;
    private final CheckBox temporarySshKeyEnabledCheck;
    
    // SSH Keep-Alive settings
    private final CheckBox sshKeepAliveCheck;
    private final Spinner<Integer> sshKeepAliveIntervalSpinner;
    
    // Connection settings
    private final CheckBox connectionRetriesEnabledCheck;
    
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
    
    // Translation (dynamic i18n) settings
    private final ComboBox<TranslationApiProvider> translationProviderCombo;
    private final PasswordField translationApiKeyField;
    private final TextField translationApiUrlField;
    private final ComboBox<Locale> translationTargetLanguageCombo;
    private final ListView<Locale> translationGeneratedList;
    private final ProgressIndicator translationProgressIndicator;
    private Label translationOutdatedLabelRef;
    private Button translationRegenerateOutdatedButtonRef;
    
    // SFTP settings
    private final CheckBox sftpAutoCloseEnabledCheck;
    private final Spinner<Integer> sftpAutoCloseMinutesSpinner;
    private final TextField sftpDefaultZipPathField;
    private final Spinner<Integer> sftpDefaultZipCompressionSpinner;
    
    // Editor settings
    private final ComboBox<String> editorCursorStyleCombo;
    private final ColorPicker editorCursorColorPicker;
    
    // Snippet editor settings
    private final ComboBox<String> snippetFontFamilyCombo;
    private final Spinner<Integer> snippetFontSizeSpinner;
    private final ColorPicker snippetForegroundColorPicker;
    private final ColorPicker snippetBackgroundColorPicker;
    private final ComboBox<String> snippetCursorStyleCombo;
    private final ColorPicker snippetCursorColorPicker;
    
    public SettingsDialog(Stage owner, KorTTYApplication app, ConfigurationManager configManager, 
                          GlobalSettings globalSettings, CredentialManager credentialManager, 
                          GPGKeyManager gpgKeyManager) {
        this.app = app;
        this.configManager = configManager;
        // Use persisted terminal settings from GlobalSettings (not ConfigurationManager defaults)
        this.settings = new ConnectionSettings(globalSettings.getDefaultTerminalSettings());
        this.globalSettings = globalSettings;
        this.credentialManager = credentialManager;
        this.gpgKeyManager = gpgKeyManager;
        
        setTitle(I18n.get("settings.title"));
        setHeaderText(I18n.get("settings.header"));
        initOwner(owner);
        initModality(Modality.WINDOW_MODAL);
        setResizable(true);
        
        // Create tabs
        TabPane tabPane = new TabPane();
        tabPane.setTabClosingPolicy(TabPane.TabClosingPolicy.UNAVAILABLE);
        
        // Font tab
        Tab fontTab = new Tab(I18n.get("settings.tab.font"));
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
        
        fontGrid.add(new Label(I18n.get("settings.font.family")), 0, 0);
        fontGrid.add(fontFamilyCombo, 1, 0);
        fontGrid.add(new Label(I18n.get("settings.font.size")), 0, 1);
        fontGrid.add(fontSizeSpinner, 1, 1);
        
        // Preview
        Label previewLabel = new Label("AaBbCcDdEe 0123456789");
        previewLabel.setStyle("-fx-background-color: #1e1e1e; -fx-padding: 10;");
        previewLabel.setTextFill(Color.WHITE);
        updatePreviewFont(previewLabel);
        
        fontFamilyCombo.valueProperty().addListener((obs, old, newVal) -> updatePreviewFont(previewLabel));
        fontSizeSpinner.valueProperty().addListener((obs, old, newVal) -> updatePreviewFont(previewLabel));
        
        fontGrid.add(new Label(I18n.get("settings.font.preview")), 0, 2);
        fontGrid.add(previewLabel, 1, 2);
        
        fontTab.setContent(fontGrid);
        
        // Colors tab
        Tab colorsTab = new Tab(I18n.get("settings.tab.colors"));
        GridPane colorsGrid = new GridPane();
        colorsGrid.setHgap(10);
        colorsGrid.setVgap(10);
        colorsGrid.setPadding(new Insets(20));
        
        foregroundColorPicker = new ColorPicker(Color.web(settings.getForegroundColor()));
        backgroundColorPicker = new ColorPicker(Color.web(settings.getBackgroundColor()));
        cursorColorPicker = new ColorPicker(Color.web(settings.getCursorColor()));
        selectionColorPicker = new ColorPicker(Color.web(settings.getSelectionColor()));
        
        colorsGrid.add(new Label(I18n.get("settings.colors.foreground")), 0, 0);
        colorsGrid.add(foregroundColorPicker, 1, 0);
        colorsGrid.add(new Label(I18n.get("settings.colors.background")), 0, 1);
        colorsGrid.add(backgroundColorPicker, 1, 1);
        colorsGrid.add(new Label(I18n.get("settings.colors.cursor")), 0, 2);
        colorsGrid.add(cursorColorPicker, 1, 2);
        colorsGrid.add(new Label(I18n.get("settings.colors.selection")), 0, 3);
        colorsGrid.add(selectionColorPicker, 1, 3);
        
        // ANSI Colors section
        colorsGrid.add(new Separator(), 0, 4, 2, 1);
        colorsGrid.add(new Label(I18n.get("settings.colors.ansi")), 0, 5, 2, 1);
        
        String[] colorNames = {I18n.get("color.black"), I18n.get("color.red"), I18n.get("color.green"), I18n.get("color.yellow"), I18n.get("color.blue"), I18n.get("color.magenta"), I18n.get("color.cyan"), I18n.get("color.white")};
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
            Tooltip.install(brightPicker, new Tooltip(colorNames[i] + " " + I18n.get("color.bright")));
            brightColorsBox.getChildren().add(brightPicker);
        }
        
        colorsGrid.add(new Label(I18n.get("settings.colors.normal")), 0, 6);
        colorsGrid.add(normalColorsBox, 1, 6);
        colorsGrid.add(new Label(I18n.get("settings.colors.bright")), 0, 7);
        colorsGrid.add(brightColorsBox, 1, 7);
        
        colorsTab.setContent(colorsGrid);
        
        // Terminal tab
        Tab terminalTab = new Tab(I18n.get("settings.tab.terminal"));
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
        
        boldAsBrightCheck = new CheckBox(I18n.get("settings.terminal.boldAsBright"));
        boldAsBrightCheck.setSelected(settings.isBoldAsBright());
        
        encodingCombo = new ComboBox<>();
        encodingCombo.getItems().addAll("UTF-8", "ISO-8859-1", "ISO-8859-15", "Windows-1252");
        encodingCombo.setValue(settings.getEncoding());
        
        showTerminalScrollbarCheck = new CheckBox(I18n.get("settings.terminal.scrollbar"));
        showTerminalScrollbarCheck.setSelected(globalSettings != null ? globalSettings.isShowTerminalScrollbar() : true);
        showTerminalScrollbarCheck.setTooltip(new Tooltip(I18n.get("settings.terminal.scrollbar.tooltip")));
        
        commandTimestampsCheck = new CheckBox(I18n.get("settings.terminal.commandTimestamps"));
        commandTimestampsCheck.setSelected(settings.isCommandTimestampsEnabled());
        commandTimestampsCheck.setTooltip(new Tooltip(I18n.get("settings.terminal.commandTimestamps.tooltip")));

        terminalDragDropCheck = new CheckBox(I18n.get("settings.terminal.dragDrop"));
        terminalDragDropCheck.setSelected(globalSettings != null ? globalSettings.isTerminalDragDropEnabled() : true);
        terminalDragDropCheck.setTooltip(new Tooltip(I18n.get("settings.terminal.dragDrop.tooltip")));

        terminalCopyOnSelectCheck = new CheckBox(I18n.get("settings.terminal.copyOnSelect"));
        terminalCopyOnSelectCheck.setSelected(globalSettings != null ? globalSettings.isTerminalCopyOnSelectEnabled() : true);
        terminalCopyOnSelectCheck.setTooltip(new Tooltip(I18n.get("settings.terminal.copyOnSelect.tooltip")));
        
        // SSH Keep-Alive settings
        sshKeepAliveCheck = new CheckBox(I18n.get("settings.terminal.sshKeepAlive"));
        sshKeepAliveCheck.setSelected(settings.isSshKeepAliveEnabled());
        sshKeepAliveCheck.setTooltip(new Tooltip(I18n.get("settings.terminal.sshKeepAlive.tooltip")));
        
        sshKeepAliveIntervalSpinner = new Spinner<>(5, 600, settings.getSshKeepAliveInterval(), 5);
        sshKeepAliveIntervalSpinner.setEditable(true);
        sshKeepAliveIntervalSpinner.setPrefWidth(100);
        sshKeepAliveIntervalSpinner.setDisable(!sshKeepAliveCheck.isSelected());
        sshKeepAliveIntervalSpinner.setTooltip(new Tooltip(I18n.get("settings.terminal.sshKeepAliveInterval.tooltip")));
        
        // Enable/disable spinner based on checkbox
        sshKeepAliveCheck.selectedProperty().addListener((obs, old, newVal) -> {
            sshKeepAliveIntervalSpinner.setDisable(!newVal);
        });
        
        // Connection retry settings
        connectionRetriesEnabledCheck = new CheckBox(I18n.get("settings.connection.retriesEnabled"));
        connectionRetriesEnabledCheck.setSelected(globalSettings != null ? globalSettings.isConnectionRetriesEnabled() : true);
        connectionRetriesEnabledCheck.setTooltip(new Tooltip(I18n.get("settings.connection.retriesEnabled.tooltip")));
        
        terminalGrid.add(new Label(I18n.get("settings.terminal.columns")), 0, 0);
        terminalGrid.add(columnsSpinner, 1, 0);
        terminalGrid.add(new Label(I18n.get("settings.terminal.rows")), 0, 1);
        terminalGrid.add(rowsSpinner, 1, 1);
        terminalGrid.add(new Label(I18n.get("settings.terminal.scrollback")), 0, 2);
        terminalGrid.add(scrollbackSpinner, 1, 2);
        terminalGrid.add(new Label(I18n.get("settings.terminal.encoding")), 0, 3);
        terminalGrid.add(encodingCombo, 1, 3);
        terminalGrid.add(boldAsBrightCheck, 0, 4, 2, 1);
        terminalGrid.add(showTerminalScrollbarCheck, 0, 5, 2, 1);
        terminalGrid.add(commandTimestampsCheck, 0, 6, 2, 1);
        terminalGrid.add(terminalDragDropCheck, 0, 7, 2, 1);
        terminalGrid.add(terminalCopyOnSelectCheck, 0, 8, 2, 1);
        
        // SSH Keep-Alive section
        terminalGrid.add(new Separator(), 0, 9, 2, 1);
        terminalGrid.add(new Label(I18n.get("settings.terminal.sshKeepAlive")), 0, 10, 2, 1);
        terminalGrid.add(sshKeepAliveCheck, 0, 11, 2, 1);
        terminalGrid.add(new Label(I18n.get("settings.terminal.sshKeepAliveInterval")), 0, 12);
        HBox keepAliveBox = new HBox(10);
        keepAliveBox.getChildren().addAll(sshKeepAliveIntervalSpinner, new Label(I18n.get("common.seconds")));
        terminalGrid.add(keepAliveBox, 1, 12);
        
        // Connection section
        terminalGrid.add(new Separator(), 0, 13, 2, 1);
        Label connectionHeader = new Label(I18n.get("settings.connection.header"));
        connectionHeader.setStyle("-fx-font-weight: bold;");
        terminalGrid.add(connectionHeader, 0, 14, 2, 1);
        terminalGrid.add(connectionRetriesEnabledCheck, 0, 15, 2, 1);
        
        terminalTab.setContent(terminalGrid);
        
        // Backup tab
        Tab backupTab = new Tab(I18n.get("settings.tab.backup"));
        GridPane backupGrid = new GridPane();
        backupGrid.setHgap(10);
        backupGrid.setVgap(10);
        backupGrid.setPadding(new Insets(20));
        
        int backupRow = 0;
        
        Label backupHeader = new Label(I18n.get("settings.backup.header"));
        backupHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        backupGrid.add(backupHeader, 0, backupRow++, 2, 1);
        
        // Max backup count
        Label maxBackupLabel = new Label(I18n.get("settings.backup.maxCount"));
        maxBackupLabel.setTooltip(new Tooltip(I18n.get("settings.backup.maxCount.tooltip")));
        
        maxBackupSpinner = new Spinner<>(0, 100, globalSettings != null ? globalSettings.getMaxBackupCount() : 10);
        maxBackupSpinner.setEditable(true);
        maxBackupSpinner.setPrefWidth(150);
        maxBackupSpinner.setTooltip(new Tooltip(I18n.get("settings.backup.maxCount.tooltip")));
        
        backupGrid.add(maxBackupLabel, 0, backupRow);
        backupGrid.add(maxBackupSpinner, 1, backupRow++);
        
        Label infoLabel = new Label(I18n.get("settings.backup.maxCount.info"));
        infoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        backupGrid.add(infoLabel, 0, backupRow++, 2, 1);
        
        // Encryption (REQUIRED!)
        Label encryptionHeader = new Label(I18n.get("settings.backup.encryption.header"));
        encryptionHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        backupGrid.add(encryptionHeader, 0, backupRow++, 2, 1);
        
        javafx.scene.control.ToggleGroup encryptionGroup = new javafx.scene.control.ToggleGroup();
        
        passwordEncryptionRadio = new javafx.scene.control.RadioButton(I18n.get("settings.backup.encryption.password"));
        passwordEncryptionRadio.setToggleGroup(encryptionGroup);
        passwordEncryptionRadio.setSelected(globalSettings == null || 
            globalSettings.getBackupEncryptionType() == GlobalSettings.BackupEncryptionType.PASSWORD);
        backupGrid.add(passwordEncryptionRadio, 0, backupRow++, 2, 1);
        
        // Password credential combo
        backupCredentialCombo = new ComboBox<>();
        backupCredentialCombo.setPromptText(I18n.get("settings.backup.credential.select"));
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
        backupGrid.add(new Label("  " + I18n.get("settings.backup.credential")), 0, backupRow);
        backupGrid.add(backupCredentialCombo, 1, backupRow++);
        
        // GPG encryption
        gpgEncryptionRadio = new javafx.scene.control.RadioButton(I18n.get("settings.backup.encryption.gpg"));
        gpgEncryptionRadio.setToggleGroup(encryptionGroup);
        gpgEncryptionRadio.setSelected(globalSettings != null && 
            globalSettings.getBackupEncryptionType() == GlobalSettings.BackupEncryptionType.GPG);
        backupGrid.add(gpgEncryptionRadio, 0, backupRow++, 2, 1);
        
        // GPG key combo
        backupGpgKeyCombo = new ComboBox<>();
        backupGpgKeyCombo.setPromptText(I18n.get("settings.backup.gpgKey.select"));
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
        backupGrid.add(new Label("  " + I18n.get("settings.backup.gpgKey")), 0, backupRow);
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
        Label warningLabel = new Label(I18n.get("settings.backup.warning"));
        warningLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #d97706; -fx-font-weight: bold;");
        backupGrid.add(warningLabel, 0, backupRow++, 2, 1);
        
        // Show last backup info
        if (globalSettings != null && globalSettings.getLastBackupTime() > 0) {
            Label lastBackupLabel = new Label(I18n.get("settings.backup.lastBackup"));
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
        Tab windowTab = new Tab(I18n.get("settings.tab.window"));
        GridPane windowGrid = new GridPane();
        windowGrid.setHgap(10);
        windowGrid.setVgap(10);
        windowGrid.setPadding(new Insets(20));
        
        int windowRow = 0;
        
        Label windowHeader = new Label(I18n.get("settings.window.header"));
        windowHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        windowGrid.add(windowHeader, 0, windowRow++, 2, 1);
        
        rememberWindowGeometryCheck = new CheckBox(I18n.get("settings.window.rememberGeometry"));
        rememberWindowGeometryCheck.setSelected(globalSettings != null ? globalSettings.isRememberWindowGeometry() : true);
        rememberWindowGeometryCheck.setTooltip(new Tooltip(I18n.get("settings.window.rememberGeometry.tooltip")));
        windowGrid.add(rememberWindowGeometryCheck, 0, windowRow++, 2, 1);
        
        Label windowInfoLabel = new Label(I18n.get("settings.window.rememberGeometry.info"));
        windowInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        windowGrid.add(windowInfoLabel, 0, windowRow++, 2, 1);
        
        rememberDashboardStateCheck = new CheckBox(I18n.get("settings.window.rememberDashboard"));
        rememberDashboardStateCheck.setSelected(globalSettings != null ? globalSettings.isRememberDashboardState() : true);
        rememberDashboardStateCheck.setTooltip(new Tooltip(I18n.get("settings.window.rememberDashboard.tooltip")));
        windowGrid.add(rememberDashboardStateCheck, 0, windowRow++, 2, 1);
        
        Label dashboardInfoLabel = new Label(I18n.get("settings.window.rememberDashboard.info"));
        dashboardInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        windowGrid.add(dashboardInfoLabel, 0, windowRow++, 2, 1);
        
        // Fixed geometry section
        windowGrid.add(new Separator(), 0, windowRow++, 2, 1);
        
        Label fixedGeometryHeader = new Label(I18n.get("settings.window.fixedGeometry.header"));
        fixedGeometryHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 12px;");
        windowGrid.add(fixedGeometryHeader, 0, windowRow++, 2, 1);
        
        useFixedGeometryCheck = new CheckBox(I18n.get("settings.window.fixedGeometry"));
        useFixedGeometryCheck.setSelected(globalSettings != null && globalSettings.isUseFixedWindowGeometry());
        useFixedGeometryCheck.setTooltip(new Tooltip(I18n.get("settings.window.fixedGeometry.tooltip")));
        windowGrid.add(useFixedGeometryCheck, 0, windowRow++, 2, 1);
        
        // Get current or default values
        WindowGeometry fixedGeo = globalSettings != null ? globalSettings.getFixedWindowGeometry() : null;
        int currentWidth = fixedGeo != null ? (int)fixedGeo.getWidth() : 1200;
        int currentHeight = fixedGeo != null ? (int)fixedGeo.getHeight() : 800;
        int currentX = fixedGeo != null ? (int)fixedGeo.getX() : 100;
        int currentY = fixedGeo != null ? (int)fixedGeo.getY() : 100;
        
        // Width and Height
        Label sizeLabel = new Label(I18n.get("common.size") + ":");
        fixedWidthSpinner = new Spinner<>(400, 4000, currentWidth);
        fixedWidthSpinner.setEditable(true);
        fixedWidthSpinner.setPrefWidth(100);
        fixedWidthSpinner.setDisable(!useFixedGeometryCheck.isSelected());
        
        fixedHeightSpinner = new Spinner<>(300, 3000, currentHeight);
        fixedHeightSpinner.setEditable(true);
        fixedHeightSpinner.setPrefWidth(100);
        fixedHeightSpinner.setDisable(!useFixedGeometryCheck.isSelected());
        
        HBox sizeBox = new HBox(10);
        sizeBox.getChildren().addAll(new Label(I18n.get("settings.window.fixedWidth")), fixedWidthSpinner, new Label(I18n.get("settings.window.fixedHeight")), fixedHeightSpinner);
        
        windowGrid.add(sizeLabel, 0, windowRow);
        windowGrid.add(sizeBox, 1, windowRow++);
        
        // Position
        Label posLabel = new Label(I18n.get("common.position") + ":");
        fixedXSpinner = new Spinner<>(0, 5000, currentX);
        fixedXSpinner.setEditable(true);
        fixedXSpinner.setPrefWidth(100);
        fixedXSpinner.setDisable(!useFixedGeometryCheck.isSelected());
        
        fixedYSpinner = new Spinner<>(0, 3000, currentY);
        fixedYSpinner.setEditable(true);
        fixedYSpinner.setPrefWidth(100);
        fixedYSpinner.setDisable(!useFixedGeometryCheck.isSelected());
        
        HBox posBox = new HBox(10);
        posBox.getChildren().addAll(new Label(I18n.get("settings.window.fixedX")), fixedXSpinner, new Label(I18n.get("settings.window.fixedY")), fixedYSpinner);
        
        windowGrid.add(posLabel, 0, windowRow);
        windowGrid.add(posBox, 1, windowRow++);
        
        // Enable/disable spinners based on checkbox
        useFixedGeometryCheck.selectedProperty().addListener((obs, old, newVal) -> {
            fixedWidthSpinner.setDisable(!newVal);
            fixedHeightSpinner.setDisable(!newVal);
            fixedXSpinner.setDisable(!newVal);
            fixedYSpinner.setDisable(!newVal);
        });
        
        Label fixedGeometryInfoLabel = new Label(I18n.get("settings.window.fixedGeometry.info"));
        fixedGeometryInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        windowGrid.add(fixedGeometryInfoLabel, 0, windowRow++, 2, 1);
        
        windowTab.setContent(windowGrid);
        
        // Security tab
        Tab securityTab = new Tab(I18n.get("settings.tab.security"));
        GridPane securityGrid = new GridPane();
        securityGrid.setHgap(10);
        securityGrid.setVgap(10);
        securityGrid.setPadding(new Insets(20));
        
        int securityRow = 0;
        
        Label securityHeader = new Label(I18n.get("settings.security.masterPassword"));
        securityHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        securityGrid.add(securityHeader, 0, securityRow++, 2, 1);
        
        Label passwordInfoLabel = new Label(I18n.get("settings.security.masterPassword.info"));
        passwordInfoLabel.setWrapText(true);
        passwordInfoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        securityGrid.add(passwordInfoLabel, 0, securityRow++, 2, 1);
        
        Button changePasswordButton = new Button(I18n.get("settings.security.masterPassword.change"));
        changePasswordButton.setOnAction(e -> changeMasterPassword());
        securityGrid.add(changePasswordButton, 0, securityRow++, 2, 1);
        
        // Separator
        securityGrid.add(new Separator(), 0, securityRow++, 2, 1);
        
        // Master password on startup option
        requireMasterPasswordOnStartupCheck = new CheckBox(I18n.get("settings.security.masterPassword.requireOnStartup"));
        requireMasterPasswordOnStartupCheck.setSelected(globalSettings != null ? globalSettings.isRequireMasterPasswordOnStartup() : true);
        requireMasterPasswordOnStartupCheck.setTooltip(new Tooltip(I18n.get("settings.security.masterPassword.requireOnStartup.tooltip")));
        
        Label masterPasswordWarningLabel = new Label(I18n.get("settings.security.masterPassword.warning"));
        masterPasswordWarningLabel.setWrapText(true);
        masterPasswordWarningLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #ff6b6b; -fx-font-weight: bold;");
        masterPasswordWarningLabel.setVisible(!requireMasterPasswordOnStartupCheck.isSelected());
        
        // Show/hide warning based on checkbox state
        requireMasterPasswordOnStartupCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            masterPasswordWarningLabel.setVisible(!newVal);
        });
        
        securityGrid.add(requireMasterPasswordOnStartupCheck, 0, securityRow++, 2, 1);
        securityGrid.add(masterPasswordWarningLabel, 0, securityRow++, 2, 1);
        
        // Temporary SSH key (Connection Manager + Quick Connect)
        securityGrid.add(new Separator(), 0, securityRow++, 2, 1);
        temporarySshKeyEnabledCheck = new CheckBox(I18n.get("settings.security.temporarySshKeyEnabled"));
        temporarySshKeyEnabledCheck.setSelected(globalSettings != null && globalSettings.isTemporarySshKeyEnabled());
        temporarySshKeyEnabledCheck.setTooltip(new Tooltip(I18n.get("settings.security.temporarySshKeyEnabled.tooltip")));
        securityGrid.add(temporarySshKeyEnabledCheck, 0, securityRow++, 2, 1);
        
        securityTab.setContent(securityGrid);
        
        // Language tab
        Tab languageTab = new Tab(I18n.get("settings.tab.language"));
        GridPane languageGrid = new GridPane();
        languageGrid.setHgap(10);
        languageGrid.setVgap(10);
        languageGrid.setPadding(new Insets(20));
        
        languageCombo = new ComboBox<>();
        languageCombo.getItems().add(I18n.get("settings.language.autoDetect"));
        languageCombo.getItems().add(I18n.get("settings.language.english"));
        languageCombo.getItems().add(I18n.get("settings.language.german"));
        languageCombo.getItems().add(I18n.get("settings.language.italian"));
        languageCombo.getItems().add(I18n.get("settings.language.spanish"));
        languageCombo.getItems().add(I18n.get("settings.language.portuguese"));
        languageCombo.getItems().add(I18n.get("settings.language.french"));
        languageCombo.getItems().add(I18n.get("settings.language.croatian"));
        languageCombo.getItems().add(I18n.get("settings.language.dutch"));
        // Add dynamically generated languages (not already in static list)
        java.util.Set<String> staticCodes = java.util.Set.of("en", "de", "it", "es", "pt", "fr", "hr", "nl");
        for (Locale dyn : LanguageManager.getAvailableDynamicLocales()) {
            if (dyn.getLanguage() != null && !staticCodes.contains(dyn.getLanguage())) {
                languageCombo.getItems().add(LanguageManager.getLocaleDisplayName(dyn) + " (" + dyn.getLanguage() + ")");
            }
        }
        
        // Set current language
        String currentLang = globalSettings != null ? globalSettings.getLanguage() : null;
        if (currentLang == null || currentLang.isEmpty()) {
            languageCombo.setValue(I18n.get("settings.language.autoDetect"));
        } else {
            switch (currentLang.toLowerCase()) {
                case "en": languageCombo.setValue(I18n.get("settings.language.english")); break;
                case "de": languageCombo.setValue(I18n.get("settings.language.german")); break;
                case "it": languageCombo.setValue(I18n.get("settings.language.italian")); break;
                case "es": languageCombo.setValue(I18n.get("settings.language.spanish")); break;
                case "pt": languageCombo.setValue(I18n.get("settings.language.portuguese")); break;
                case "fr": languageCombo.setValue(I18n.get("settings.language.french")); break;
                case "hr": languageCombo.setValue(I18n.get("settings.language.croatian")); break;
                case "nl": languageCombo.setValue(I18n.get("settings.language.dutch")); break;
                default:
                    String dynamicDisplay = null;
                    for (Locale dyn : LanguageManager.getAvailableDynamicLocales()) {
                        if (currentLang.equalsIgnoreCase(dyn.getLanguage())) {
                            dynamicDisplay = LanguageManager.getLocaleDisplayName(dyn) + " (" + dyn.getLanguage() + ")";
                            break;
                        }
                    }
                    languageCombo.setValue(dynamicDisplay != null ? dynamicDisplay : I18n.get("settings.language.autoDetect"));
                    break;
            }
        }
        if (languageCombo.getValue() == null) {
            languageCombo.setValue(I18n.get("settings.language.autoDetect"));
        }
        
        languageGrid.add(new Label(I18n.get("settings.language.select")), 0, 0);
        languageGrid.add(languageCombo, 1, 0);
        
        Label languageInfo = new Label(I18n.get("settings.language.info"));
        languageInfo.setWrapText(true);
        languageInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        languageGrid.add(languageInfo, 0, 1, 2, 1);
        
        languageTab.setContent(languageGrid);
        
        // Translation tab (dynamic i18n)
        Tab translationTab = new Tab(I18n.get("settings.tab.translation"));
        GridPane translationGrid = new GridPane();
        translationGrid.setHgap(10);
        translationGrid.setVgap(10);
        translationGrid.setPadding(new Insets(20));
        int transRow = 0;
        translationGrid.add(new Label(I18n.get("settings.translation.systemLanguage")), 0, transRow);
        translationGrid.add(new Label(java.util.Locale.getDefault().getDisplayLanguage()), 1, transRow++);
        translationGrid.add(new Label(I18n.get("settings.translation.provider")), 0, transRow);
        translationProviderCombo = new ComboBox<>();
        translationProviderCombo.getItems().addAll(
            TranslationApiProvider.GOOGLE_TRANSLATE,
            TranslationApiProvider.DEEPL,
            TranslationApiProvider.LIBRETRANSLATE,
            TranslationApiProvider.MICROSOFT,
            TranslationApiProvider.YANDEX
        );
        translationProviderCombo.setConverter(new javafx.util.StringConverter<TranslationApiProvider>() {
            @Override
            public String toString(TranslationApiProvider p) {
                if (p == null) return "";
                switch (p) {
                    case GOOGLE_TRANSLATE: return I18n.get("settings.translation.provider.google");
                    case DEEPL: return I18n.get("settings.translation.provider.deepl");
                    case LIBRETRANSLATE: return I18n.get("settings.translation.provider.libretranslate");
                    case MICROSOFT: return I18n.get("settings.translation.provider.microsoft");
                    case YANDEX: return I18n.get("settings.translation.provider.yandex");
                    default: return p.name();
                }
            }
            @Override
            public TranslationApiProvider fromString(String s) { return null; }
        });
        if (globalSettings != null && globalSettings.getTranslationApiProvider() != null) {
            translationProviderCombo.setValue(globalSettings.getTranslationApiProvider());
        } else {
            translationProviderCombo.setValue(TranslationApiProvider.GOOGLE_TRANSLATE);
        }
        translationProviderCombo.setPrefWidth(220);
        translationGrid.add(translationProviderCombo, 1, transRow++);
        translationGrid.add(new Label(I18n.get("settings.translation.apiKey")), 0, transRow);
        translationApiKeyField = new PasswordField();
        translationApiKeyField.setPrefWidth(280);
        translationApiKeyField.setPromptText(I18n.get("settings.translation.apiKey"));
        translationGrid.add(translationApiKeyField, 1, transRow++);
        translationGrid.add(new Label(I18n.get("settings.translation.apiUrl")), 0, transRow);
        translationApiUrlField = new TextField();
        translationApiUrlField.setPrefWidth(280);
        if (globalSettings != null && globalSettings.getTranslationApiUrl() != null) {
            translationApiUrlField.setText(globalSettings.getTranslationApiUrl());
        }
        translationGrid.add(translationApiUrlField, 1, transRow++);
        Button testConnectionButton = new Button(I18n.get("settings.translation.testConnection"));
        testConnectionButton.setOnAction(e -> testTranslationConnection());
        translationGrid.add(testConnectionButton, 1, transRow++);
        translationGrid.add(new Label(I18n.get("settings.translation.targetLanguage")), 0, transRow);
        translationTargetLanguageCombo = new ComboBox<>();
        // One entry per language code to avoid duplicates (e.g. en_US, en_GB, en all show "English")
        java.util.Set<String> seen = new java.util.LinkedHashSet<>();
        List<Locale> allLocales = Arrays.stream(Locale.getAvailableLocales())
            .filter(l -> l.getLanguage() != null && !l.getLanguage().isEmpty())
            .filter(l -> seen.add(l.getLanguage()))
            .sorted((a, b) -> a.getDisplayLanguage().compareToIgnoreCase(b.getDisplayLanguage()))
            .collect(Collectors.toList());
        translationTargetLanguageCombo.getItems().addAll(allLocales);
        translationTargetLanguageCombo.setConverter(new javafx.util.StringConverter<Locale>() {
            @Override
            public String toString(Locale l) {
                return l == null ? "" : l.getDisplayLanguage() + " (" + l.getLanguage() + ")";
            }
            @Override
            public Locale fromString(String s) { return null; }
        });
        translationTargetLanguageCombo.setPrefWidth(220);
        java.util.Locale systemLocale = java.util.Locale.getDefault();
        translationTargetLanguageCombo.setValue(systemLocale);
        translationGrid.add(translationTargetLanguageCombo, 1, transRow++);
        Button generateButton = new Button(I18n.get("settings.translation.generateFile"));
        translationProgressIndicator = new ProgressIndicator(-1);
        translationProgressIndicator.setVisible(false);
        HBox generateBox = new HBox(10, generateButton, translationProgressIndicator);
        generateBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        generateButton.setOnAction(ev -> generateTranslationFile(generateButton));
        translationGrid.add(generateBox, 1, transRow++);
        translationGrid.add(new Label(I18n.get("settings.translation.generatedLanguages")), 0, transRow);
        translationOutdatedLabelRef = new Label();
        translationOutdatedLabelRef.setWrapText(true);
        translationOutdatedLabelRef.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        translationOutdatedLabelRef.setVisible(false);
        translationRegenerateOutdatedButtonRef = new Button(I18n.get("settings.translation.regenerateOutdated"));
        translationRegenerateOutdatedButtonRef.setVisible(false);
        translationRegenerateOutdatedButtonRef.setOnAction(ev -> regenerateOutdatedTranslations(translationRegenerateOutdatedButtonRef, translationOutdatedLabelRef, translationRegenerateOutdatedButtonRef));
        VBox generatedBox = new VBox(5,
            translationOutdatedLabelRef,
            translationRegenerateOutdatedButtonRef,
            translationGeneratedList = new ListView<>(),
            new Button(I18n.get("settings.translation.delete")) {{
                setOnAction(e -> deleteSelectedGeneratedLanguage());
            }}
        );
        translationGeneratedList.setPrefHeight(120);
        translationGeneratedList.setCellFactory(lv -> new ListCell<Locale>() {
            @Override
            protected void updateItem(Locale item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getDisplayLanguage() + " (" + item.getLanguage() + ")");
            }
        });
        refreshTranslationGeneratedList(translationOutdatedLabelRef, translationRegenerateOutdatedButtonRef);
        translationGrid.add(generatedBox, 1, transRow++);
        translationTab.setContent(translationGrid);
        
        // SFTP tab
        Tab sftpTab = new Tab(I18n.get("settings.tab.sftp"));
        GridPane sftpGrid = new GridPane();
        sftpGrid.setHgap(10);
        sftpGrid.setVgap(10);
        sftpGrid.setPadding(new Insets(20));
        
        int sftpRow = 0;
        
        // SFTP Manager title
        Label sftpTitle = new Label(I18n.get("settings.sftp.title"));
        sftpTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        sftpGrid.add(sftpTitle, 0, sftpRow++, 2, 1);
        
        // Auto-close enabled checkbox
        sftpAutoCloseEnabledCheck = new CheckBox(I18n.get("settings.sftp.autoCloseEnabled"));
        boolean autoCloseEnabled = globalSettings.getSftpAutoCloseMinutes() != null && globalSettings.getSftpAutoCloseMinutes() > 0;
        sftpAutoCloseEnabledCheck.setSelected(autoCloseEnabled);
        sftpGrid.add(sftpAutoCloseEnabledCheck, 0, sftpRow++, 2, 1);
        
        // Auto-close timeout spinner
        Label autoCloseLabel = new Label(I18n.get("settings.sftp.autoCloseMinutes"));
        int currentMinutes = globalSettings.getSftpAutoCloseMinutes() != null ? globalSettings.getSftpAutoCloseMinutes() : 10;
        sftpAutoCloseMinutesSpinner = new Spinner<>(1, 120, currentMinutes);
        sftpAutoCloseMinutesSpinner.setEditable(true);
        sftpAutoCloseMinutesSpinner.setPrefWidth(80);
        sftpAutoCloseMinutesSpinner.setDisable(!autoCloseEnabled);
        
        HBox autoCloseBox = new HBox(10);
        autoCloseBox.getChildren().addAll(autoCloseLabel, sftpAutoCloseMinutesSpinner);
        sftpGrid.add(autoCloseBox, 0, sftpRow++, 2, 1);
        
        // Enable/disable spinner based on checkbox
        sftpAutoCloseEnabledCheck.selectedProperty().addListener((obs, old, newVal) -> {
            sftpAutoCloseMinutesSpinner.setDisable(!newVal);
        });
        
        // Info label
        Label sftpInfo = new Label(I18n.get("settings.sftp.info"));
        sftpInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        sftpInfo.setWrapText(true);
        sftpInfo.setMaxWidth(400);
        sftpGrid.add(sftpInfo, 0, sftpRow++, 2, 1);
        
        // Separator
        sftpGrid.add(new Separator(), 0, sftpRow++, 2, 1);
        
        // ZIP settings title
        Label zipTitle = new Label(I18n.get("settings.sftp.zipTitle"));
        zipTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        sftpGrid.add(zipTitle, 0, sftpRow++, 2, 1);
        
        // Default ZIP path
        Label zipPathLabel = new Label(I18n.get("settings.sftp.defaultZipPath"));
        sftpDefaultZipPathField = new TextField(globalSettings.getSftpDefaultZipPath());
        sftpDefaultZipPathField.setPrefWidth(250);
        HBox zipPathBox = new HBox(10);
        zipPathBox.getChildren().addAll(zipPathLabel, sftpDefaultZipPathField);
        sftpGrid.add(zipPathBox, 0, sftpRow++, 2, 1);
        
        // Default compression level
        Label compressionLabel = new Label(I18n.get("settings.sftp.defaultCompression"));
        sftpDefaultZipCompressionSpinner = new Spinner<>(0, 9, globalSettings.getSftpDefaultZipCompression());
        sftpDefaultZipCompressionSpinner.setEditable(true);
        sftpDefaultZipCompressionSpinner.setPrefWidth(80);
        HBox compressionBox = new HBox(10);
        compressionBox.getChildren().addAll(compressionLabel, sftpDefaultZipCompressionSpinner);
        sftpGrid.add(compressionBox, 0, sftpRow++, 2, 1);
        
        // ZIP info
        Label zipInfo = new Label(I18n.get("settings.sftp.zipInfo"));
        zipInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        zipInfo.setWrapText(true);
        zipInfo.setMaxWidth(400);
        sftpGrid.add(zipInfo, 0, sftpRow++, 2, 1);
        
        sftpTab.setContent(sftpGrid);
        
        // Editor tab
        Tab editorTab = new Tab(I18n.get("settings.tab.editor"));
        GridPane editorGrid = new GridPane();
        editorGrid.setHgap(10);
        editorGrid.setVgap(10);
        editorGrid.setPadding(new Insets(20));
        
        int editorRow = 0;
        
        // Editor settings title
        Label editorTitle = new Label(I18n.get("settings.editor.title"));
        editorTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        editorGrid.add(editorTitle, 0, editorRow++, 2, 1);
        
        // Info about editor using terminal settings
        Label editorInfo = new Label(I18n.get("settings.editor.info"));
        editorInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        editorInfo.setWrapText(true);
        editorInfo.setMaxWidth(400);
        editorGrid.add(editorInfo, 0, editorRow++, 2, 1);
        
        // Cursor style
        Label cursorStyleLabel = new Label(I18n.get("settings.editor.cursorStyle"));
        editorCursorStyleCombo = new ComboBox<>();
        editorCursorStyleCombo.getItems().addAll("BLOCK", "LINE", "UNDERSCORE");
        editorCursorStyleCombo.setValue(globalSettings.getEditorCursorStyle());
        editorCursorStyleCombo.setPrefWidth(150);
        
        HBox cursorStyleBox = new HBox(10);
        cursorStyleBox.getChildren().addAll(cursorStyleLabel, editorCursorStyleCombo);
        editorGrid.add(cursorStyleBox, 0, editorRow++, 2, 1);
        
        // Cursor color
        Label cursorColorLabel = new Label(I18n.get("settings.editor.cursorColor"));
        editorCursorColorPicker = new ColorPicker(Color.web(globalSettings.getEditorCursorColor()));
        editorCursorColorPicker.setPrefWidth(150);
        
        HBox cursorColorBox = new HBox(10);
        cursorColorBox.getChildren().addAll(cursorColorLabel, editorCursorColorPicker);
        editorGrid.add(cursorColorBox, 0, editorRow++, 2, 1);
        
        // Cursor info
        Label cursorInfo = new Label(I18n.get("settings.editor.cursorInfo"));
        cursorInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        cursorInfo.setWrapText(true);
        cursorInfo.setMaxWidth(400);
        editorGrid.add(cursorInfo, 0, editorRow++, 2, 1);
        
        editorTab.setContent(editorGrid);
        
        // Snippet Editor tab
        Tab snippetEditorTab = new Tab(I18n.get("settings.tab.snippetEditor"));
        GridPane snippetEditorGrid = new GridPane();
        snippetEditorGrid.setHgap(10);
        snippetEditorGrid.setVgap(10);
        snippetEditorGrid.setPadding(new Insets(20));
        
        int snippetRow = 0;
        
        Label snippetTitle = new Label(I18n.get("settings.snippetEditor.title"));
        snippetTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        snippetEditorGrid.add(snippetTitle, 0, snippetRow++, 2, 1);
        
        Label snippetInfo = new Label(I18n.get("settings.snippetEditor.info"));
        snippetInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        snippetInfo.setWrapText(true);
        snippetInfo.setMaxWidth(400);
        snippetEditorGrid.add(snippetInfo, 0, snippetRow++, 2, 1);
        
        // Font family
        snippetFontFamilyCombo = new ComboBox<>();
        snippetFontFamilyCombo.getItems().add(""); // empty = inherit from terminal
        snippetFontFamilyCombo.getItems().addAll(getMonospaceFonts());
        String savedSnippetFont = globalSettings.getSnippetFontFamily();
        snippetFontFamilyCombo.setValue(savedSnippetFont != null ? savedSnippetFont : "");
        snippetFontFamilyCombo.setPrefWidth(200);
        snippetEditorGrid.add(new Label(I18n.get("settings.snippetEditor.fontFamily")), 0, snippetRow);
        snippetEditorGrid.add(snippetFontFamilyCombo, 1, snippetRow++);
        
        // Font size
        int savedSnippetSize = globalSettings.getSnippetFontSize() != null ? globalSettings.getSnippetFontSize() : 0;
        snippetFontSizeSpinner = new Spinner<>(0, 72, savedSnippetSize);
        snippetFontSizeSpinner.setEditable(true);
        snippetFontSizeSpinner.setPrefWidth(80);
        Label snippetSizeInfo = new Label(I18n.get("settings.snippetEditor.fontSizeInfo"));
        snippetSizeInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        HBox snippetSizeBox = new HBox(10, snippetFontSizeSpinner, snippetSizeInfo);
        snippetEditorGrid.add(new Label(I18n.get("settings.snippetEditor.fontSize")), 0, snippetRow);
        snippetEditorGrid.add(snippetSizeBox, 1, snippetRow++);
        
        // Foreground color
        String snippetFg = globalSettings.getSnippetForegroundColor();
        snippetForegroundColorPicker = new ColorPicker(snippetFg != null ? Color.web(snippetFg) : Color.web("#d4d4d4"));
        snippetEditorGrid.add(new Label(I18n.get("settings.snippetEditor.foreground")), 0, snippetRow);
        snippetEditorGrid.add(snippetForegroundColorPicker, 1, snippetRow++);
        
        // Background color
        String snippetBg = globalSettings.getSnippetBackgroundColor();
        snippetBackgroundColorPicker = new ColorPicker(snippetBg != null ? Color.web(snippetBg) : Color.web("#1e1e1e"));
        snippetEditorGrid.add(new Label(I18n.get("settings.snippetEditor.background")), 0, snippetRow);
        snippetEditorGrid.add(snippetBackgroundColorPicker, 1, snippetRow++);
        
        // Cursor style
        snippetCursorStyleCombo = new ComboBox<>();
        snippetCursorStyleCombo.getItems().addAll("", "BLOCK", "LINE", "UNDERSCORE");
        String savedSnippetCursorStyle = globalSettings.getSnippetCursorStyle();
        snippetCursorStyleCombo.setValue(savedSnippetCursorStyle != null ? savedSnippetCursorStyle : "");
        snippetCursorStyleCombo.setPrefWidth(150);
        snippetEditorGrid.add(new Label(I18n.get("settings.snippetEditor.cursorStyle")), 0, snippetRow);
        snippetEditorGrid.add(snippetCursorStyleCombo, 1, snippetRow++);
        
        // Cursor color
        String snippetCursorCol = globalSettings.getSnippetCursorColor();
        snippetCursorColorPicker = new ColorPicker(snippetCursorCol != null ? Color.web(snippetCursorCol) : Color.web("#FF0000"));
        snippetEditorGrid.add(new Label(I18n.get("settings.snippetEditor.cursorColor")), 0, snippetRow);
        snippetEditorGrid.add(snippetCursorColorPicker, 1, snippetRow++);
        
        snippetEditorTab.setContent(snippetEditorGrid);
        
        // Themes tab
        Tab themesTab = createThemesTab(owner);
        
        tabPane.getTabs().addAll(fontTab, colorsTab, themesTab, terminalTab, backupTab, windowTab, securityTab, sftpTab, editorTab, snippetEditorTab, languageTab, translationTab);
        
        VBox content = new VBox(tabPane);
        content.setFillWidth(true);
        // TabPane does not report preferred height well to ScrollPane (JavaFX quirk), so set a min height
        // so the scrollable area is large enough and the vertical scrollbar appears
        content.setMinHeight(800);
        content.setPrefHeight(800);
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPrefViewportWidth(580);
        scrollPane.setPrefViewportHeight(580);
        scrollPane.setMinViewportWidth(400);
        scrollPane.setMinViewportHeight(400);
        getDialogPane().setContent(scrollPane);
        
        // Buttons
        ButtonType saveButtonType = new ButtonType(I18n.get("settings.save"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                if (!applySettings()) {
                    return null; // abort save and keep dialog open (e.g. vault locked when saving translation API key)
                }

                // Save settings to both ConnectionSettings and GlobalSettings
                configManager.setGlobalSettings(settings);
                globalSettings.setDefaultTerminalSettings(new ConnectionSettings(settings));
                
                // Save global settings
                try {
                    app.getGlobalSettingsManager().save();
                    
                    // Update language manager if language was changed
                    if (globalSettings != null && globalSettings.getLanguage() != null) {
                        de.kortty.core.LanguageManager.getInstance().setLocale(globalSettings.getLanguage());
                    } else if (globalSettings != null && globalSettings.getLanguage() == null) {
                        // Auto-detect: use system locale
                        java.util.Locale defaultLocale = java.util.Locale.getDefault();
                        de.kortty.core.LanguageManager.getInstance().setLocale(defaultLocale);
                    }
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
    
    /** @return true if save may continue, false to abort (e.g. vault locked and translation API key cannot be encrypted) */
    private boolean applySettings() {
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
        settings.setCommandTimestampsEnabled(commandTimestampsCheck.isSelected());
        settings.setSshKeepAliveEnabled(sshKeepAliveCheck.isSelected());
        settings.setSshKeepAliveInterval(sshKeepAliveIntervalSpinner.getValue());
        
        // Save connection settings to GlobalSettings
        if (globalSettings != null) {
            globalSettings.setConnectionRetriesEnabled(connectionRetriesEnabledCheck.isSelected());
            globalSettings.setCommandTimestampsEnabled(commandTimestampsCheck.isSelected());
        }
        
        // Save backup settings to GlobalSettings
        if (globalSettings != null) {
            globalSettings.setShowTerminalScrollbar(showTerminalScrollbarCheck.isSelected());
            globalSettings.setTerminalDragDropEnabled(terminalDragDropCheck.isSelected());
            globalSettings.setTerminalCopyOnSelectEnabled(terminalCopyOnSelectCheck.isSelected());
            globalSettings.setRequireMasterPasswordOnStartup(requireMasterPasswordOnStartupCheck.isSelected());
            globalSettings.setTemporarySshKeyEnabled(temporarySshKeyEnabledCheck.isSelected());
            
            // Save language setting
            String selectedLanguage = languageCombo.getValue();
            String autoDetectText = I18n.get("settings.language.autoDetect");
            if (selectedLanguage == null || selectedLanguage.equals(autoDetectText)) {
                globalSettings.setLanguage(null); // null means auto-detect
            } else {
                String langCode = null;
                if (selectedLanguage.equals(I18n.get("settings.language.english"))) langCode = "en";
                else if (selectedLanguage.equals(I18n.get("settings.language.german"))) langCode = "de";
                else if (selectedLanguage.equals(I18n.get("settings.language.italian"))) langCode = "it";
                else if (selectedLanguage.equals(I18n.get("settings.language.spanish"))) langCode = "es";
                else if (selectedLanguage.equals(I18n.get("settings.language.portuguese"))) langCode = "pt";
                else if (selectedLanguage.equals(I18n.get("settings.language.french"))) langCode = "fr";
                else if (selectedLanguage.equals(I18n.get("settings.language.croatian"))) langCode = "hr";
                else if (selectedLanguage.equals(I18n.get("settings.language.dutch"))) langCode = "nl";
                else {
                    // Dynamic language: format is "DisplayName (xx)"
                    int paren = selectedLanguage != null ? selectedLanguage.lastIndexOf('(') : -1;
                    if (paren >= 0 && selectedLanguage.endsWith(")")) {
                        langCode = selectedLanguage.substring(paren + 1, selectedLanguage.length() - 1).trim();
                    }
                }
                globalSettings.setLanguage(langCode);
            }
            
            // Translation API settings
            globalSettings.setTranslationApiProvider(translationProviderCombo.getValue());
            String apiUrl = translationApiUrlField.getText();
            globalSettings.setTranslationApiUrl(apiUrl != null && !apiUrl.trim().isEmpty() ? apiUrl.trim() : null);
            String apiKeyPlain = translationApiKeyField.getText();
            if (apiKeyPlain != null && !apiKeyPlain.isEmpty()) {
                char[] masterPassword = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
                if (masterPassword == null) {
                    Alert vaultLocked = new Alert(Alert.AlertType.WARNING, I18n.get("settings.translation.error.vaultLocked"));
                    vaultLocked.setHeaderText(null);
                    vaultLocked.showAndWait();
                    // Abort save so dialog stays open; do not call setEncryptedTranslationApiKey
                    return false;
                } else {
                    try {
                        de.kortty.security.EncryptionService enc = new de.kortty.security.EncryptionService();
                        String encrypted = enc.encryptPassword(apiKeyPlain, masterPassword);
                        globalSettings.setEncryptedTranslationApiKey(encrypted);
                    } catch (Exception ex) {
                        org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not encrypt translation API key", ex);
                        Alert err = new Alert(Alert.AlertType.WARNING, I18n.get("settings.translation.error.generationFailed") + ": " + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
                        err.setHeaderText(null);
                        err.showAndWait();
                        // Do not overwrite existing encrypted key; plain key remains in field
                    }
                }
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
            
            // Save SFTP settings
            if (sftpAutoCloseEnabledCheck.isSelected()) {
                globalSettings.setSftpAutoCloseMinutes(sftpAutoCloseMinutesSpinner.getValue());
            } else {
                globalSettings.setSftpAutoCloseMinutes(null); // Disabled
            }
            
            // Save ZIP settings
            String zipPath = sftpDefaultZipPathField.getText().trim();
            globalSettings.setSftpDefaultZipPath(zipPath.isEmpty() ? "/tmp" : zipPath);
            globalSettings.setSftpDefaultZipCompression(sftpDefaultZipCompressionSpinner.getValue());
            
            // Save Editor settings
            globalSettings.setEditorCursorStyle(editorCursorStyleCombo.getValue());
            globalSettings.setEditorCursorColor(toHex(editorCursorColorPicker.getValue()));
            
            // Save Snippet Editor settings (empty string = inherit from terminal/editor)
            String snippetFont = snippetFontFamilyCombo.getValue();
            globalSettings.setSnippetFontFamily(snippetFont != null && !snippetFont.isEmpty() ? snippetFont : null);
            
            int snippetSize = snippetFontSizeSpinner.getValue();
            globalSettings.setSnippetFontSize(snippetSize > 0 ? snippetSize : null);
            
            globalSettings.setSnippetForegroundColor(toHex(snippetForegroundColorPicker.getValue()));
            globalSettings.setSnippetBackgroundColor(toHex(snippetBackgroundColorPicker.getValue()));
            
            String snippetCursorSt = snippetCursorStyleCombo.getValue();
            globalSettings.setSnippetCursorStyle(snippetCursorSt != null && !snippetCursorSt.isEmpty() ? snippetCursorSt : null);
            
            globalSettings.setSnippetCursorColor(toHex(snippetCursorColorPicker.getValue()));
        }
        return true;
    }
    
    private void updatePreviewFont(Label previewLabel) {
        previewLabel.setFont(Font.font(fontFamilyCombo.getValue(), fontSizeSpinner.getValue()));
    }
    
    private Tab createThemesTab(Stage owner) {
        Tab tab = new Tab(I18n.get("settings.tab.themes"));
        tab.setClosable(false);
        
        ThemeManager themeManager = app != null ? app.getThemeManager() : null;
        if (themeManager == null) {
            tab.setContent(new Label(I18n.get("theme.notAvailable")));
            return tab;
        }
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        
        Label header = new Label(I18n.get("theme.header"));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        vbox.getChildren().add(header);
        
        Label desc = new Label(I18n.get("theme.description"));
        desc.setWrapText(true);
        desc.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        vbox.getChildren().add(desc);
        
        ListView<Theme> themeList = new ListView<>();
        themeList.getItems().addAll(themeManager.getThemes());
        themeList.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(Theme item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                } else {
                    setText(item.getName() + (item.isBuiltIn() ? " (" + I18n.get("theme.builtIn") + ")" : ""));
                }
            }
        });
        themeList.setPrefHeight(260);
        VBox.setVgrow(themeList, javafx.scene.layout.Priority.ALWAYS);
        
        HBox buttons = new HBox(10);
        Button addBtn = new Button(I18n.get("theme.add"));
        Button editBtn = new Button(I18n.get("theme.edit"));
        Button duplicateBtn = new Button(I18n.get("theme.duplicate"));
        Button deleteBtn = new Button(I18n.get("theme.delete"));
        
        editBtn.disableProperty().bind(themeList.getSelectionModel().selectedItemProperty().isNull());
        duplicateBtn.disableProperty().bind(themeList.getSelectionModel().selectedItemProperty().isNull());
        themeList.getSelectionModel().selectedItemProperty().addListener((o, a, b) ->
            deleteBtn.setDisable(b == null || b.isBuiltIn()));
        deleteBtn.setDisable(true);
        
        addBtn.setOnAction(e -> {
            ThemeEditDialog dlg = new ThemeEditDialog(owner, null);
            dlg.showAndWait().ifPresent(t -> {
                themeManager.addTheme(t);
                themeList.getItems().clear();
                themeList.getItems().addAll(themeManager.getThemes());
            });
        });
        
        editBtn.setOnAction(e -> {
            Theme sel = themeList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Theme copy = new Theme();
            copy.setId(sel.getId());
            copy.setName(sel.getName());
            copy.setFontFamily(sel.getFontFamily());
            copy.setFontSize(sel.getFontSize());
            copy.setForegroundColor(sel.getForegroundColor());
            copy.setBackgroundColor(sel.getBackgroundColor());
            copy.setCursorColor(sel.getCursorColor());
            copy.setCursorStyle(sel.getCursorStyle());
            copy.setBuiltIn(sel.isBuiltIn());
            ThemeEditDialog dlg = new ThemeEditDialog(owner, copy);
            dlg.showAndWait().ifPresent(edited -> {
                themeManager.updateTheme(edited);
                themeList.getItems().clear();
                themeList.getItems().addAll(themeManager.getThemes());
            });
        });
        
        duplicateBtn.setOnAction(e -> {
            Theme sel = themeList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Theme dup = new Theme();
            dup.setName(sel.getName() + " " + I18n.get("theme.copy"));
            dup.setFontFamily(sel.getFontFamily());
            dup.setFontSize(sel.getFontSize());
            dup.setForegroundColor(sel.getForegroundColor());
            dup.setBackgroundColor(sel.getBackgroundColor());
            dup.setCursorColor(sel.getCursorColor());
            dup.setCursorStyle(sel.getCursorStyle());
            dup.setBuiltIn(false);
            ThemeEditDialog dlg = new ThemeEditDialog(owner, dup);
            dlg.showAndWait().ifPresent(t -> {
                themeManager.addTheme(t);
                themeList.getItems().clear();
                themeList.getItems().addAll(themeManager.getThemes());
            });
        });
        
        deleteBtn.setOnAction(e -> {
            Theme sel = themeList.getSelectionModel().getSelectedItem();
            if (sel == null || sel.isBuiltIn()) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(I18n.get("theme.deleteTitle"));
            confirm.setHeaderText(I18n.get("theme.deleteConfirm", sel.getName()));
            confirm.initOwner(owner);
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                themeManager.removeTheme(sel.getId());
                themeList.getItems().clear();
                themeList.getItems().addAll(themeManager.getThemes());
            }
        });
        
        buttons.getChildren().addAll(addBtn, editBtn, duplicateBtn, deleteBtn);
        vbox.getChildren().addAll(themeList, buttons);
        
        tab.setContent(vbox);
        return tab;
    }
    
    /**
     * Returns all system-available font families, with common monospace fonts first.
     */
    private List<String> getMonospaceFonts() {
        java.util.Set<String> preferred = new java.util.LinkedHashSet<>();
        preferred.add("Monospaced");
        preferred.add("Courier New");
        preferred.add("Consolas");
        preferred.add("Monaco");
        preferred.add("Menlo");
        preferred.add("Source Code Pro");
        preferred.add("JetBrains Mono");
        preferred.add("Fira Code");
        preferred.add("SF Mono");
        preferred.add("DejaVu Sans Mono");
        preferred.add("Liberation Mono");
        preferred.add("Ubuntu Mono");
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
     * Updates the new master password field border color by length: red below minimum, green otherwise.
     */
    private void updateMasterPasswordFieldLengthStyle(PasswordField field, int length) {
        if (length < PasswordStrengthChecker.MIN_LENGTH) {
            field.setStyle("-fx-border-color: #e74c3c; -fx-border-width: 2px; -fx-border-radius: 3px;");
        } else {
            field.setStyle("-fx-border-color: #27ae60; -fx-border-width: 2px; -fx-border-radius: 3px;");
        }
    }
    
    /**
     * Shows dialog to change master password and re-encrypts all stored passwords.
     */
    private void changeMasterPassword() {
        Dialog<char[]> passwordDialog = new Dialog<>();
        passwordDialog.setTitle(I18n.get("settings.masterPassword.changeTitle"));
        passwordDialog.setHeaderText(I18n.get("settings.masterPassword.changeHeader"));
        passwordDialog.initModality(Modality.WINDOW_MODAL);
        passwordDialog.initOwner(getDialogPane().getScene().getWindow());
        
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        
        PasswordField oldPasswordField = new PasswordField();
        oldPasswordField.setPromptText(I18n.get("settings.masterPassword.current"));
        oldPasswordField.setPrefWidth(250);
        
        PasswordField newPasswordField = new PasswordField();
        newPasswordField.setPromptText(I18n.get("settings.masterPassword.new"));
        newPasswordField.setPrefWidth(250);
        updateMasterPasswordFieldLengthStyle(newPasswordField, 0);
        newPasswordField.textProperty().addListener((obs, old, newVal) ->
            updateMasterPasswordFieldLengthStyle(newPasswordField, newVal != null ? newVal.length() : 0));
        
        PasswordField confirmPasswordField = new PasswordField();
        confirmPasswordField.setPromptText(I18n.get("settings.masterPassword.confirmNew"));
        confirmPasswordField.setPrefWidth(250);
        
        Label errorLabel = new Label();
        errorLabel.setStyle("-fx-text-fill: red;");
        errorLabel.setVisible(false);
        
        int row = 0;
        grid.add(new Label(I18n.get("settings.masterPassword.current") + ":"), 0, row);
        grid.add(oldPasswordField, 1, row++);
        grid.add(new Label(I18n.get("settings.masterPassword.new") + ":"), 0, row);
        grid.add(newPasswordField, 1, row++);
        grid.add(new Label(I18n.get("settings.masterPassword.confirmNew") + ":"), 0, row);
        grid.add(confirmPasswordField, 1, row++);
        grid.add(errorLabel, 0, row++, 2, 1);
        
        passwordDialog.getDialogPane().setContent(grid);
        
        ButtonType changeButtonType = new ButtonType(I18n.get("settings.masterPassword.changeButton"), ButtonBar.ButtonData.OK_DONE);
        passwordDialog.getDialogPane().getButtonTypes().addAll(changeButtonType, ButtonType.CANCEL);
        
        Button changeButton = (Button) passwordDialog.getDialogPane().lookupButton(changeButtonType);
        changeButton.setDisable(true);
        
        // Validate fields
        Runnable validator = () -> {
            boolean valid = !oldPasswordField.getText().isEmpty() &&
                           !newPasswordField.getText().isEmpty() &&
                           !confirmPasswordField.getText().isEmpty() &&
                           newPasswordField.getText().length() >= PasswordStrengthChecker.MIN_LENGTH;
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
                    errorLabel.setText(I18n.get("settings.masterPassword.enterCurrent"));
                    errorLabel.setVisible(true);
                    return null;
                }
                
                if (newPassword.length() < PasswordStrengthChecker.MIN_LENGTH) {
                    errorLabel.setText(I18n.get("settings.masterPassword.tooShort"));
                    errorLabel.setVisible(true);
                    return null;
                }
                
                if (!newPassword.equals(confirmPassword)) {
                    errorLabel.setText(I18n.get("settings.masterPassword.mismatch"));
                    errorLabel.setVisible(true);
                    return null;
                }
                
                if (PasswordStrengthChecker.isWeak(newPassword)) {
                    Alert warn = new Alert(Alert.AlertType.CONFIRMATION);
                    warn.setTitle(I18n.get("masterPassword.weakWarning.title"));
                    warn.setHeaderText(I18n.get("masterPassword.weakWarning.header"));
                    warn.setContentText(I18n.get("masterPassword.weakWarning.content"));
                    ButtonType useAnyway = new ButtonType(I18n.get("masterPassword.useAnyway"), ButtonBar.ButtonData.OK_DONE);
                    warn.getButtonTypes().setAll(useAnyway, ButtonType.CANCEL);
                    warn.initOwner(passwordDialog.getDialogPane().getScene().getWindow());
                    if (warn.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.CANCEL) {
                        return null;
                    }
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
                        
                        // Re-encrypt key passphrase if exists (connection-level stored passphrase)
                        String plainPassphrase = oldVault.retrieveKeyPassphrase(connection);
                        if (plainPassphrase != null) {
                            newVault.storeKeyPassphrase(connection, plainPassphrase);
                            reEncryptedCount++;
                        }
                    } catch (Exception e) {
                        org.slf4j.LoggerFactory.getLogger(getClass())
                            .warn("Failed to re-encrypt password for connection: {}", connection.getName(), e);
                    }
                }
                
                // Re-encrypt SSH key passphrases (stored in SSH Key Manager)
                SSHKeyManager sshKeyManager = app.getSSHKeyManager();
                if (sshKeyManager != null) {
                    for (de.kortty.model.SSHKey key : sshKeyManager.getAllKeys()) {
                        if (key.getEncryptedPassphrase() != null && !key.getEncryptedPassphrase().isBlank()) {
                            try {
                                String plain = sshKeyManager.getPassphrase(key, oldPasswordChars);
                                if (plain != null) {
                                    sshKeyManager.setPassphrase(key, plain, newPasswordChars);
                                    reEncryptedCount++;
                                }
                            } catch (Exception e) {
                                org.slf4j.LoggerFactory.getLogger(getClass())
                                    .warn("Failed to re-encrypt passphrase for SSH key: {}", key.getName(), e);
                            }
                        }
                    }
                    sshKeyManager.save();
                }
                
                // Save connections with new encryption
                configManager.save(app.getMasterPasswordManager().getDerivedKey());
                
                // Clear sensitive data
                java.util.Arrays.fill(oldPasswordChars, '\0');
                java.util.Arrays.fill(newPasswordChars, '\0');
                
                Alert success = new Alert(Alert.AlertType.INFORMATION);
                success.setTitle(I18n.get("settings.masterPassword.changed"));
                success.setHeaderText(I18n.get("settings.masterPassword.changedSuccess"));
                success.setContentText(I18n.get("settings.masterPassword.changedMessage", reEncryptedCount));
                success.showAndWait();
                
            } catch (SecurityException e) {
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle(I18n.get("sftp.error.title"));
                error.setHeaderText(I18n.get("settings.masterPassword.changeFailed"));
                error.setContentText(I18n.get("settings.masterPassword.wrongPassword") + " " + e.getMessage());
                error.showAndWait();
            } catch (Exception e) {
                org.slf4j.LoggerFactory.getLogger(getClass()).error("Failed to change master password", e);
                Alert error = new Alert(Alert.AlertType.ERROR);
                error.setTitle(I18n.get("sftp.error.title"));
                error.setHeaderText(I18n.get("settings.masterPassword.changeFailed"));
                error.setContentText(I18n.get("settings.masterPassword.errorOccurred", e.getMessage()));
                error.showAndWait();
            }
        });
    }

    private TranslationService createTranslationService() {
        String key = getTranslationApiKeyPlain();
        TranslationApiProvider provider = translationProviderCombo.getValue();
        if (provider == null) provider = TranslationApiProvider.GOOGLE_TRANSLATE;
        String url = translationApiUrlField.getText();
        String urlTrimmed = (url != null && !url.trim().isEmpty()) ? url.trim() : null;
        switch (provider) {
            case GOOGLE_TRANSLATE:
                if (key == null || key.isEmpty()) return null;
                return new GoogleTranslationService(key, urlTrimmed);
            case DEEPL:
                if (key == null || key.isEmpty()) return null;
                return new DeepLTranslationService(key, urlTrimmed);
            case LIBRETRANSLATE:
                return new LibreTranslateTranslationService(key != null ? key : "", urlTrimmed);
            case MICROSOFT:
                if (key == null || key.isEmpty()) return null;
                return new MicrosoftTranslationService(key, urlTrimmed, null);
            case YANDEX:
                if (key == null || key.isEmpty()) return null;
                return new YandexTranslationService(key, urlTrimmed);
            default:
                return key != null && !key.isEmpty() ? new GoogleTranslationService(key, urlTrimmed) : null;
        }
    }

    private String getTranslationApiKeyPlain() {
        String fromField = translationApiKeyField.getText();
        if (fromField != null && !fromField.isEmpty()) return fromField;
        String encrypted = globalSettings != null ? globalSettings.getEncryptedTranslationApiKey() : null;
        if (encrypted == null || encrypted.isEmpty()) return null;
        try {
            char[] master = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
            if (master == null) return null;
            de.kortty.security.EncryptionService enc = new de.kortty.security.EncryptionService();
            return enc.decryptPassword(encrypted, master);
        } catch (Exception e) {
            return null;
        }
    }

    private void testTranslationConnection() {
        TranslationApiProvider provider = translationProviderCombo.getValue();
        boolean keyOptional = provider == TranslationApiProvider.LIBRETRANSLATE;
        String key = getTranslationApiKeyPlain();
        if (!keyOptional && (key == null || key.isEmpty())) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.translation.error.noKey")).showAndWait();
            return;
        }
        TranslationService svc = createTranslationService();
        if (svc == null) {
            new Alert(Alert.AlertType.ERROR, I18n.get("settings.translation.error.testFailed")).showAndWait();
            return;
        }
        boolean ok = svc.testConnection();
        if (ok) {
            new Alert(Alert.AlertType.INFORMATION, I18n.get("settings.translation.testSuccess")).showAndWait();
        } else {
            new Alert(Alert.AlertType.ERROR, I18n.get("settings.translation.error.testFailed")).showAndWait();
        }
    }

    private void generateTranslationFile(Button generateButton) {
        TranslationApiProvider provider = translationProviderCombo.getValue();
        boolean keyOptional = provider == TranslationApiProvider.LIBRETRANSLATE;
        String key = getTranslationApiKeyPlain();
        if (!keyOptional && (key == null || key.isEmpty())) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.translation.error.noKey")).showAndWait();
            return;
        }
        TranslationService svc = createTranslationService();
        if (svc == null) {
            new Alert(Alert.AlertType.ERROR, I18n.get("settings.translation.error.generationFailed")).showAndWait();
            return;
        }
        Locale target = translationTargetLanguageCombo.getValue();
        if (target == null || target.getLanguage() == null || target.getLanguage().isEmpty()) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.translation.error.generationFailed")).showAndWait();
            return;
        }
        String targetLang = target.getLanguage();
        generateButton.setDisable(true);
        translationProgressIndicator.setVisible(true);
        Task<java.nio.file.Path> task = new Task<>() {
            @Override
            protected java.nio.file.Path call() throws Exception {
                DynamicLanguageGenerator gen = new DynamicLanguageGenerator(svc, KorTTYApplication.getConfigDirectory());
                return gen.generate(targetLang, progress -> Platform.runLater(() -> translationProgressIndicator.setProgress(progress)));
            }
        };
        task.setOnSucceeded(ev -> {
            generateButton.setDisable(false);
            translationProgressIndicator.setVisible(false);
            refreshTranslationGeneratedList(translationOutdatedLabelRef, translationRegenerateOutdatedButtonRef);
            new Alert(Alert.AlertType.INFORMATION, I18n.get("settings.translation.success")).showAndWait();
        });
        task.setOnFailed(ev -> {
            generateButton.setDisable(false);
            translationProgressIndicator.setVisible(false);
            Throwable t = task.getException();
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Translation generation failed", t);
            new Alert(Alert.AlertType.ERROR, I18n.get("settings.translation.error.generationFailed") + ": " + (t != null ? t.getMessage() : "")).showAndWait();
        });
        new Thread(task).start();
    }

    private void refreshTranslationGeneratedList() {
        refreshTranslationGeneratedList(null, null);
    }

    private void refreshTranslationGeneratedList(Label outdatedLabel, Button regenerateOutdatedBtn) {
        translationGeneratedList.getItems().setAll(LanguageManager.getAvailableDynamicLocales());
        List<Locale> outdated = LanguageManager.getOutdatedDynamicLocales();
        if (outdatedLabel != null && regenerateOutdatedBtn != null) {
            if (outdated.isEmpty()) {
                outdatedLabel.setVisible(false);
                regenerateOutdatedBtn.setVisible(false);
            } else {
                String names = outdated.stream()
                    .map(LanguageManager::getLocaleDisplayName)
                    .collect(Collectors.joining(", "));
                outdatedLabel.setText(I18n.get("settings.translation.outdatedHint", names));
                outdatedLabel.setVisible(true);
                regenerateOutdatedBtn.setVisible(true);
            }
        }
    }

    private void deleteSelectedGeneratedLanguage() {
        Locale selected = translationGeneratedList.getSelectionModel().getSelectedItem();
        if (selected == null) return;
        java.nio.file.Path file = KorTTYApplication.getConfigDirectory().resolve("i18n").resolve("messages_" + selected.getLanguage() + ".properties");
        if (!java.nio.file.Files.isRegularFile(file)) return;
        try {
            java.nio.file.Files.delete(file);
            refreshTranslationGeneratedList(translationOutdatedLabelRef, translationRegenerateOutdatedButtonRef);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not delete {}", file, e);
        }
    }

    private void regenerateOutdatedTranslations(Button regenerateButton, Label outdatedLabel, Button regenerateOutdatedBtn) {
        List<Locale> outdated = LanguageManager.getOutdatedDynamicLocales();
        if (outdated.isEmpty()) {
            refreshTranslationGeneratedList(outdatedLabel, regenerateOutdatedBtn);
            return;
        }
        TranslationService svc = createTranslationService();
        if (svc == null) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.translation.error.noKey")).showAndWait();
            return;
        }
        regenerateButton.setDisable(true);
        translationProgressIndicator.setVisible(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                DynamicLanguageGenerator gen = new DynamicLanguageGenerator(svc, KorTTYApplication.getConfigDirectory());
                int total = outdated.size();
                for (int i = 0; i < total; i++) {
                    final int idx = i;
                    Locale loc = outdated.get(i);
                    String lang = loc.getLanguage();
                    gen.generate(lang, p -> Platform.runLater(() -> translationProgressIndicator.setProgress((idx + p) / total)));
                }
                return null;
            }
        };
        task.setOnSucceeded(ev -> {
            regenerateButton.setDisable(false);
            translationProgressIndicator.setVisible(false);
            refreshTranslationGeneratedList(outdatedLabel, regenerateOutdatedBtn);
            new Alert(Alert.AlertType.INFORMATION, I18n.get("settings.translation.regenerateOutdatedDone")).showAndWait();
        });
        task.setOnFailed(ev -> {
            regenerateButton.setDisable(false);
            translationProgressIndicator.setVisible(false);
            Throwable t = task.getException();
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Regenerate outdated failed", t);
            new Alert(Alert.AlertType.ERROR, I18n.get("settings.translation.error.generationFailed") + ": " + (t != null ? t.getMessage() : "")).showAndWait();
        });
        new Thread(task).start();
    }
}
