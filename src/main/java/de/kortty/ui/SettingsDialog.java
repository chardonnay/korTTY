package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.ui.I18n;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.DynamicLanguageGenerator;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.AiTokenUsageManager;
import de.kortty.core.AiTokenUsageSnapshot;
import de.kortty.core.AiTokenWarningLevel;
import de.kortty.core.AiService;
import de.kortty.core.GoogleTranslationService;
import de.kortty.core.DeepLTranslationService;
import de.kortty.core.LibreTranslateTranslationService;
import de.kortty.core.OpenAiCompatibleAiService;
import de.kortty.core.MicrosoftTranslationService;
import de.kortty.core.TerminalAgentCommandSupport;
import de.kortty.core.YandexTranslationService;
import de.kortty.core.LanguageManager;
import de.kortty.core.SSHKeyManager;
import de.kortty.core.ThemeManager;
import de.kortty.core.TranslationService;
import de.kortty.model.AiProfile;
import de.kortty.model.AiTokenLimitUnit;
import de.kortty.model.AiTokenizerType;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.Theme;
import de.kortty.model.TranslationApiProvider;
import de.kortty.model.StoredCredential;
import de.kortty.model.GPGKey;
import de.kortty.model.WindowGeometry;
import de.kortty.security.PasswordStrengthChecker;
import de.kortty.security.PasswordVault;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * Dialog for editing global terminal settings.
 */
public class SettingsDialog extends ThemeAwareDialog<ConnectionSettings> {
    
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
    private final CheckBox cursorBlinkCheck;
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
    private final CheckBox closeActiveTerminalWindowsWithoutConfirmationCheck;
    
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
    private final CheckBox showMenuBarCheck;
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

    // AI settings
    private static final String DEFAULT_AI_API_URL = "https://api.openai.com/v1/chat/completions";
    private final ListView<AiProfile> aiProfileListView;
    private final TextField aiProfileNameField;
    private final TextField aiApiUrlField;
    private final TextField aiModelField;
    private final PasswordField aiApiKeyField;
    private final CheckBox aiClearApiKeyCheck;
    private final CheckBox aiConfirmBeforeSendCheck;
    private final CheckBox aiPromptHookEnabledCheck;
    private final CheckBox aiShowDebugMessagesCheck;
    private final CheckBox aiShowRuntimeMessagesCheck;
    private final TextField aiAgentCommandNameField;
    private final ComboBox<TerminalAgentExecutionTarget> aiExecutionTargetCombo;
    private final Spinner<Integer> aiMaxSelectionCharsSpinner;
    private final ComboBox<AiTokenizerType> aiTokenizerCombo;
    private final Spinner<Integer> aiTokenLimitAmountSpinner;
    private final ComboBox<AiTokenLimitUnit> aiTokenLimitUnitCombo;
    private final Spinner<Integer> aiTokenWarningYellowSpinner;
    private final Spinner<Integer> aiTokenWarningRedSpinner;
    private final Spinner<Integer> aiTokenResetDaysSpinner;
    private final DatePicker aiTokenResetAnchorPicker;
    private final AiQuotaBar aiTokenUsageBar;
    private final Label aiTokenUsageLabel;
    private final List<AiProfile> aiProfiles = new ArrayList<>();
    private final Map<String, String> aiPlainApiKeysByProfileId = new HashMap<>();
    private final Set<String> aiClearedApiKeysByProfileId = new HashSet<>();
    private AiProfile selectedAiProfile;
    
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
    private String selectedGlobalThemeId;
    private ComboBox<Theme> colorProfileCombo;
    private final BooleanProperty applyThemeFontsProperty;
    
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
        this.selectedGlobalThemeId = this.settings.getThemeId();
        this.applyThemeFontsProperty = new SimpleBooleanProperty(globalSettings != null && globalSettings.isApplyThemeFonts());
        
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
        
        ThemeManager colorThemeManager = app != null ? app.getThemeManager() : null;
        ConnectionSettings displaySettings = settings;
        if (selectedGlobalThemeId != null && !selectedGlobalThemeId.isEmpty() && colorThemeManager != null) {
            displaySettings = colorThemeManager.resolveSettings(settings, selectedGlobalThemeId);
        }
        foregroundColorPicker = new ColorPicker(Color.web(displaySettings.getForegroundColor()));
        backgroundColorPicker = new ColorPicker(Color.web(displaySettings.getBackgroundColor()));
        cursorColorPicker = new ColorPicker(Color.web(displaySettings.getCursorColor()));
        selectionColorPicker = new ColorPicker(Color.web(settings.getSelectionColor()));
        colorProfileCombo = new ComboBox<>();
        colorProfileCombo.setPrefWidth(240);
        colorProfileCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(Theme t) { return t != null ? t.getName() : ""; }
            @Override public Theme fromString(String s) { return null; }
        });
        if (colorThemeManager != null) {
            colorProfileCombo.getItems().addAll(colorThemeManager.getThemes());
            if (selectedGlobalThemeId != null && !selectedGlobalThemeId.isEmpty()) {
                colorThemeManager.getTheme(selectedGlobalThemeId).ifPresent(colorProfileCombo::setValue);
            }
        }
        cursorBlinkCheck = new CheckBox(I18n.get("settings.colors.cursorBlink"));
        cursorBlinkCheck.setSelected(isCursorBlink(settings.getCursorStyle()));
        cursorBlinkCheck.setTooltip(new Tooltip(I18n.get("settings.colors.cursorBlink.tooltip")));
        colorProfileCombo.setOnAction(e -> {
            try {
                Theme selected = colorProfileCombo.getValue();
                if (selected == null) return;
                selectedGlobalThemeId = selected.getId();
                if (applyThemeFontsProperty.get()) {
                    fontFamilyCombo.setValue(selected.getFontFamily());
                    fontSizeSpinner.getValueFactory().setValue(selected.getFontSize());
                }
                foregroundColorPicker.setValue(Color.web(selected.getForegroundColor()));
                backgroundColorPicker.setValue(Color.web(selected.getBackgroundColor()));
                cursorColorPicker.setValue(Color.web(selected.getCursorColor()));
                cursorBlinkCheck.setSelected(isCursorBlink(selected.getCursorStyle()));
                settings.setCursorStyle(selected.getCursorStyle());
            } catch (Exception ex) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                    .error("Error applying theme from combo selection", ex);
            }
        });
        HBox profileBox = new HBox(8, colorProfileCombo);
        
        colorsGrid.add(new Label(I18n.get("settings.colors.profile")), 0, 0);
        colorsGrid.add(profileBox, 1, 0);
        colorsGrid.add(new Label(I18n.get("settings.colors.foreground")), 0, 1);
        colorsGrid.add(foregroundColorPicker, 1, 1);
        colorsGrid.add(new Label(I18n.get("settings.colors.background")), 0, 2);
        colorsGrid.add(backgroundColorPicker, 1, 2);
        colorsGrid.add(new Label(I18n.get("settings.colors.cursor")), 0, 3);
        colorsGrid.add(cursorColorPicker, 1, 3);
        colorsGrid.add(cursorBlinkCheck, 0, 4, 2, 1);
        colorsGrid.add(new Label(I18n.get("settings.colors.selection")), 0, 5);
        colorsGrid.add(selectionColorPicker, 1, 5);
        
        // ANSI Colors section
        colorsGrid.add(new Separator(), 0, 6, 2, 1);
        colorsGrid.add(new Label(I18n.get("settings.colors.ansi")), 0, 7, 2, 1);
        
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
        
        colorsGrid.add(new Label(I18n.get("settings.colors.normal")), 0, 8);
        colorsGrid.add(normalColorsBox, 1, 8);
        colorsGrid.add(new Label(I18n.get("settings.colors.bright")), 0, 9);
        colorsGrid.add(brightColorsBox, 1, 9);
        
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

        closeActiveTerminalWindowsWithoutConfirmationCheck = new CheckBox(I18n.get("settings.terminal.closeActiveWithoutConfirmation"));
        closeActiveTerminalWindowsWithoutConfirmationCheck.setSelected(globalSettings != null
            && globalSettings.isCloseActiveTerminalWindowsWithoutConfirmation());
        closeActiveTerminalWindowsWithoutConfirmationCheck.setTooltip(
            new Tooltip(I18n.get("settings.terminal.closeActiveWithoutConfirmation.tooltip"))
        );
        
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
        terminalGrid.add(closeActiveTerminalWindowsWithoutConfirmationCheck, 0, 9, 2, 1);
        
        // SSH Keep-Alive section
        terminalGrid.add(new Separator(), 0, 10, 2, 1);
        terminalGrid.add(new Label(I18n.get("settings.terminal.sshKeepAlive")), 0, 11, 2, 1);
        terminalGrid.add(sshKeepAliveCheck, 0, 12, 2, 1);
        terminalGrid.add(new Label(I18n.get("settings.terminal.sshKeepAliveInterval")), 0, 13);
        HBox keepAliveBox = new HBox(10);
        keepAliveBox.getChildren().addAll(sshKeepAliveIntervalSpinner, new Label(I18n.get("common.seconds")));
        terminalGrid.add(keepAliveBox, 1, 13);
        
        // Connection section
        terminalGrid.add(new Separator(), 0, 14, 2, 1);
        Label connectionHeader = new Label(I18n.get("settings.connection.header"));
        connectionHeader.setStyle("-fx-font-weight: bold;");
        terminalGrid.add(connectionHeader, 0, 15, 2, 1);
        terminalGrid.add(connectionRetriesEnabledCheck, 0, 16, 2, 1);
        
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

        showMenuBarCheck = new CheckBox(I18n.get("settings.window.showMenuBar"));
        showMenuBarCheck.setSelected(globalSettings == null || globalSettings.isShowMenuBar());
        showMenuBarCheck.setTooltip(new Tooltip(I18n.get("settings.window.showMenuBar.tooltip")));
        windowGrid.add(showMenuBarCheck, 0, windowRow++, 2, 1);

        Label showMenuBarInfoLabel = new Label(I18n.get("settings.window.showMenuBar.info"));
        showMenuBarInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        windowGrid.add(showMenuBarInfoLabel, 0, windowRow++, 2, 1);
        
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
        List<Locale> allLocales = buildTranslationTargetLocales();
        translationTargetLanguageCombo.getItems().addAll(allLocales);
        translationTargetLanguageCombo.setConverter(new javafx.util.StringConverter<Locale>() {
            @Override
            public String toString(Locale l) {
                return l == null ? "" : getLocaleDisplayNameFallback(l) + " (" + l.getLanguage() + ")";
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

        // AI tab
        Tab aiTab = new Tab(I18n.get("settings.tab.ai"));
        VBox aiRoot = new VBox(12);
        aiRoot.setPadding(new Insets(20));

        Label aiInfo = new Label(I18n.get("settings.ai.info"));
        aiInfo.setWrapText(true);
        aiInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        aiRoot.getChildren().add(aiInfo);

        aiConfirmBeforeSendCheck = new CheckBox(I18n.get("settings.ai.confirmBeforeSend"));
        aiConfirmBeforeSendCheck.setSelected(globalSettings == null || globalSettings.isAiConfirmBeforeSend());
        aiRoot.getChildren().add(aiConfirmBeforeSendCheck);

        aiPromptHookEnabledCheck = new CheckBox(I18n.get("settings.ai.promptHook"));
        aiPromptHookEnabledCheck.setSelected(globalSettings == null || globalSettings.isDefaultPromptHookEnabled());
        aiRoot.getChildren().add(aiPromptHookEnabledCheck);

        aiShowDebugMessagesCheck = new CheckBox(I18n.get("settings.ai.showDebugMessages"));
        aiShowDebugMessagesCheck.setSelected(globalSettings != null && globalSettings.isTerminalAgentShowDebugMessages());
        aiRoot.getChildren().add(aiShowDebugMessagesCheck);

        aiShowRuntimeMessagesCheck = new CheckBox(I18n.get("settings.ai.showRuntimeMessages"));
        aiShowRuntimeMessagesCheck.setSelected(globalSettings != null && globalSettings.isTerminalAgentShowRuntimeMessages());
        aiRoot.getChildren().add(aiShowRuntimeMessagesCheck);

        aiAgentCommandNameField = new TextField(globalSettings != null ? globalSettings.getTerminalAgentCommandName() : "");
        aiAgentCommandNameField.setPrefWidth(220);
        Label aiAgentCommandInfoLabel = new Label();
        aiAgentCommandInfoLabel.setWrapText(true);
        aiAgentCommandInfoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        Runnable updateAgentCommandInfo = () -> {
            String configured = aiAgentCommandNameField.getText();
            String normalized = TerminalAgentCommandSupport.normalizeCommandName(configured);
            String validationMessage = TerminalAgentCommandSupport.validateCommandName(configured);
            if (validationMessage != null) {
                aiAgentCommandInfoLabel.setText(validationMessage);
                aiAgentCommandInfoLabel.setTextFill(Color.web("#c53030"));
            } else {
                aiAgentCommandInfoLabel.setText(I18n.get(
                    "settings.ai.agentCommandInfo",
                    normalized,
                    TerminalAgentCommandSupport.getAskCommandName(normalized),
                    TerminalAgentCommandSupport.getPlanCommandName(normalized)));
                aiAgentCommandInfoLabel.setTextFill(Color.GRAY);
            }
        };
        aiAgentCommandNameField.textProperty().addListener((obs, oldValue, newValue) -> updateAgentCommandInfo.run());
        updateAgentCommandInfo.run();

        HBox aiAgentCommandBox = new HBox(10,
            new Label(I18n.get("settings.ai.agentCommandName")),
            aiAgentCommandNameField);
        aiRoot.getChildren().addAll(aiAgentCommandBox, aiAgentCommandInfoLabel);

        aiExecutionTargetCombo = new ComboBox<>();
        aiExecutionTargetCombo.getItems().addAll(TerminalAgentExecutionTarget.values());
        aiExecutionTargetCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(TerminalAgentExecutionTarget object) {
                if (object == null) {
                    return "";
                }
                return I18n.get(object == TerminalAgentExecutionTarget.CHAT_WINDOW
                    ? "settings.ai.executionTarget.chatWindow"
                    : "settings.ai.executionTarget.terminalWindow");
            }

            @Override
            public TerminalAgentExecutionTarget fromString(String string) {
                return null;
            }
        });
        aiExecutionTargetCombo.setValue(
            globalSettings != null ? globalSettings.getTerminalAgentExecutionTarget() : TerminalAgentExecutionTarget.TERMINAL_WINDOW);
        HBox aiExecutionTargetBox = new HBox(10,
            new Label(I18n.get("settings.ai.executionTarget")),
            aiExecutionTargetCombo);
        aiRoot.getChildren().add(aiExecutionTargetBox);

        aiProfiles.addAll(globalSettings.getAiProfiles().stream().map(AiProfile::new).collect(Collectors.toList()));

        aiProfileListView = new ListView<>();
        aiProfileListView.getItems().addAll(aiProfiles);
        aiProfileListView.setPrefWidth(220);
        aiProfileListView.setPrefHeight(220);
        aiProfileListView.setCellFactory(lv -> new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    setStyle("");
                    return;
                }
                setText(getAiProfileDisplayName(item) + "\n" + buildAiProfileUsageInline(item));
                AiTokenWarningLevel warningLevel = AiTokenUsageManager.refreshUsage(item).warningLevel();
                setStyle(switch (warningLevel) {
                    case YELLOW -> "-fx-text-fill: #b7791f;";
                    case RED -> "-fx-text-fill: #c53030;";
                    case NONE -> "";
                });
            }
        });

        Button aiAddProfileButton = new Button(I18n.get("settings.ai.profile.add"));
        aiAddProfileButton.setMinWidth(140);
        aiAddProfileButton.setPrefWidth(140);
        aiAddProfileButton.setOnAction(e -> addAiProfile());
        Button aiRemoveProfileButton = new Button(I18n.get("settings.ai.profile.remove"));
        aiRemoveProfileButton.setMinWidth(140);
        aiRemoveProfileButton.setPrefWidth(140);
        aiRemoveProfileButton.disableProperty().bind(aiProfileListView.getSelectionModel().selectedItemProperty().isNull());
        aiRemoveProfileButton.setOnAction(e -> removeSelectedAiProfile());

        VBox aiProfilesBox = new VBox(8,
            new Label(I18n.get("settings.ai.profiles")),
            aiProfileListView,
            new HBox(8, aiAddProfileButton, aiRemoveProfileButton)
        );

        GridPane aiEditorGrid = new GridPane();
        aiEditorGrid.setHgap(10);
        aiEditorGrid.setVgap(10);
        int aiRow = 0;

        aiEditorGrid.add(new Label(I18n.get("settings.ai.profile.name")), 0, aiRow);
        aiProfileNameField = new TextField();
        aiProfileNameField.setPrefWidth(220);
        aiProfileNameField.textProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null) {
                selectedAiProfile.setName(newValue);
                aiProfileListView.refresh();
            }
        });
        aiEditorGrid.add(aiProfileNameField, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.apiUrl")), 0, aiRow);
        aiApiUrlField = new TextField();
        aiApiUrlField.setPrefWidth(320);
        aiEditorGrid.add(aiApiUrlField, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.model")), 0, aiRow);
        aiModelField = new TextField();
        aiModelField.setPrefWidth(220);
        aiEditorGrid.add(aiModelField, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.apiKey")), 0, aiRow);
        aiApiKeyField = new PasswordField();
        aiApiKeyField.setPrefWidth(280);
        aiApiKeyField.setPromptText(I18n.get("settings.ai.apiKey"));
        aiEditorGrid.add(aiApiKeyField, 1, aiRow++);

        aiClearApiKeyCheck = new CheckBox(I18n.get("settings.ai.clearApiKey"));
        aiEditorGrid.add(aiClearApiKeyCheck, 1, aiRow++);
        aiApiKeyField.textProperty().addListener((obs, oldValue, newValue) -> {
            boolean hasReplacementKey = newValue != null && !newValue.isBlank();
            aiClearApiKeyCheck.setDisable(hasReplacementKey);
            if (hasReplacementKey) {
                aiClearApiKeyCheck.setSelected(false);
            }
        });

        aiEditorGrid.add(new Label(I18n.get("settings.ai.maxChars")), 0, aiRow);
        aiMaxSelectionCharsSpinner = new Spinner<>(1, 50_000_000, AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        aiMaxSelectionCharsSpinner.setEditable(true);
        aiMaxSelectionCharsSpinner.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null) {
                selectedAiProfile.setMaxSelectionChars(newValue != null ? newValue : AiProfile.DEFAULT_MAX_SELECTION_CHARS);
            }
        });
        aiEditorGrid.add(aiMaxSelectionCharsSpinner, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.tokenizer")), 0, aiRow);
        aiTokenizerCombo = new ComboBox<>();
        aiTokenizerCombo.getItems().addAll(AiTokenizerType.values());
        aiTokenizerCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiTokenizerType object) {
                return object == null ? "" : I18n.get("settings.ai.tokenizer." + object.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public AiTokenizerType fromString(String string) {
                return null;
            }
        });
        aiTokenizerCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null) {
                selectedAiProfile.setTokenizerType(newValue);
                updateAiTokenUsagePreview();
            }
        });
        aiEditorGrid.add(aiTokenizerCombo, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.token.limit")), 0, aiRow);
        aiTokenLimitAmountSpinner = new Spinner<>(0, 1_000_000, 0);
        aiTokenLimitAmountSpinner.setEditable(true);
        aiTokenLimitAmountSpinner.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null) {
                selectedAiProfile.setTokenLimitAmount(newValue != null ? newValue.longValue() : 0L);
                updateAiTokenUsagePreview();
            }
        });
        aiTokenLimitUnitCombo = new ComboBox<>();
        aiTokenLimitUnitCombo.getItems().addAll(AiTokenLimitUnit.values());
        aiTokenLimitUnitCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiTokenLimitUnit object) {
                return object == null ? "" : I18n.get("settings.ai.token.unit." + object.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public AiTokenLimitUnit fromString(String string) {
                return null;
            }
        });
        aiTokenLimitUnitCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null) {
                selectedAiProfile.setTokenLimitUnit(newValue);
                updateAiTokenUsagePreview();
            }
        });
        aiEditorGrid.add(new HBox(8, aiTokenLimitAmountSpinner, aiTokenLimitUnitCombo), 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.token.warn")), 0, aiRow);
        aiTokenWarningYellowSpinner = new Spinner<>(0, 100, 75);
        aiTokenWarningYellowSpinner.setEditable(true);
        aiTokenWarningYellowSpinner.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null) {
                selectedAiProfile.setTokenWarningYellowPercent(newValue);
                updateAiTokenUsagePreview();
            }
        });
        aiTokenWarningRedSpinner = new Spinner<>(0, 100, 90);
        aiTokenWarningRedSpinner.setEditable(true);
        aiTokenWarningRedSpinner.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null) {
                selectedAiProfile.setTokenWarningRedPercent(newValue);
                updateAiTokenUsagePreview();
            }
        });
        aiEditorGrid.add(new HBox(8,
            new Label(I18n.get("settings.ai.token.warn.yellow")),
            aiTokenWarningYellowSpinner,
            new Label(I18n.get("settings.ai.token.warn.red")),
            aiTokenWarningRedSpinner), 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.token.reset")), 0, aiRow);
        aiTokenResetDaysSpinner = new Spinner<>(1, 3650, 30);
        aiTokenResetDaysSpinner.setEditable(true);
        aiTokenResetDaysSpinner.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null) {
                selectedAiProfile.setTokenResetPeriodDays(newValue);
                updateAiTokenUsagePreview();
            }
        });
        aiTokenResetAnchorPicker = new DatePicker();
        aiTokenResetAnchorPicker.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null) {
                selectedAiProfile.setTokenResetAnchorDate(newValue != null ? newValue.toString() : null);
                updateAiTokenUsagePreview();
            }
        });
        aiEditorGrid.add(new HBox(8,
            aiTokenResetDaysSpinner,
            new Label(I18n.get("settings.ai.token.reset.days")),
            new Label(I18n.get("settings.ai.token.reset.anchor")),
            aiTokenResetAnchorPicker), 1, aiRow++);

        aiTokenUsageBar = new AiQuotaBar();
        aiTokenUsageBar.setPrefWidth(320);
        aiEditorGrid.add(aiTokenUsageBar, 1, aiRow++);

        aiTokenUsageLabel = new Label();
        aiTokenUsageLabel.setWrapText(true);
        aiTokenUsageLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        aiEditorGrid.add(aiTokenUsageLabel, 1, aiRow++);

        Button aiTestConnectionButton = new Button(I18n.get("settings.ai.testConnection"));
        aiTestConnectionButton.setOnAction(e -> testAiConnection(aiTestConnectionButton));
        aiEditorGrid.add(aiTestConnectionButton, 1, aiRow++);

        HBox aiContent = new HBox(16, aiProfilesBox, aiEditorGrid);
        aiRoot.getChildren().add(aiContent);
        aiTab.setContent(aiRoot);

        aiProfileListView.getSelectionModel().selectedItemProperty().addListener((obs, oldValue, newValue) -> {
            snapshotSelectedAiProfileEditorState();
            selectedAiProfile = newValue;
            loadAiProfileIntoEditor(newValue);
        });
        if (!aiProfiles.isEmpty()) {
            aiProfileListView.getSelectionModel().selectFirst();
        } else {
            addAiProfile();
        }
        
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
        
        tabPane.getTabs().addAll(fontTab, colorsTab, themesTab, terminalTab, backupTab, windowTab, securityTab, sftpTab, editorTab, snippetEditorTab, languageTab, translationTab, aiTab);
        
        final double defaultContentHeight = 900;
        final double defaultViewportHeight = 700;
        final double minimumViewportHeight = 520;
        final double defaultDialogHeight = 860;
        final double minimumDialogHeight = 720;

        VBox content = new VBox(tabPane);
        content.setFillWidth(true);
        // TabPane does not report preferred height well to ScrollPane (JavaFX quirk), so set a min height
        // so the scrollable area is large enough and the vertical scrollbar appears
        content.setMinHeight(defaultContentHeight);
        content.setPrefHeight(defaultContentHeight);
        ScrollPane scrollPane = new ScrollPane(content);
        scrollPane.setFitToWidth(true);
        scrollPane.setFitToHeight(false);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setPrefViewportWidth(860);
        scrollPane.setPrefViewportHeight(defaultViewportHeight);
        scrollPane.setMinViewportWidth(720);
        scrollPane.setMinViewportHeight(minimumViewportHeight);
        getDialogPane().setContent(scrollPane);
        getDialogPane().setPrefWidth(980);
        getDialogPane().setMinWidth(860);
        getDialogPane().setPrefHeight(defaultDialogHeight);
        getDialogPane().setMinHeight(minimumDialogHeight);
        
        // Buttons
        ButtonType saveButtonType = new ButtonType(I18n.get("settings.save"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        
        setResultConverter(dialogButton -> {
            if (dialogButton == saveButtonType) {
                if (!applySettings()) {
                    return null; // abort save and keep dialog open (e.g. vault locked when saving translation API key)
                }

                // Save settings to both ConnectionSettings and GlobalSettings
                org.slf4j.LoggerFactory.getLogger(getClass()).info(
                        "Saving settings: themeId='{}', bg='{}', fg='{}', cursorStyle='{}'",
                        settings.getThemeId(), settings.getBackgroundColor(),
                        settings.getForegroundColor(), settings.getCursorStyle());
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
        settings.setCursorStyle(deriveCursorStyle(settings.getCursorStyle(), cursorBlinkCheck.isSelected()));
        settings.setSelectionColor(toHex(selectionColorPicker.getValue()));
        settings.setThemeId(selectedGlobalThemeId);
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
            globalSettings.setApplyThemeFonts(applyThemeFontsProperty.get());
        }
        
        // Save backup settings to GlobalSettings
        if (globalSettings != null) {
            globalSettings.setShowTerminalScrollbar(showTerminalScrollbarCheck.isSelected());
            globalSettings.setTerminalDragDropEnabled(terminalDragDropCheck.isSelected());
            globalSettings.setTerminalCopyOnSelectEnabled(terminalCopyOnSelectCheck.isSelected());
            globalSettings.setCloseActiveTerminalWindowsWithoutConfirmation(
                closeActiveTerminalWindowsWithoutConfirmationCheck.isSelected()
            );
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

            String commandNameValidationMessage = TerminalAgentCommandSupport.validateCommandName(aiAgentCommandNameField.getText());
            if (commandNameValidationMessage != null) {
                Alert alert = new Alert(Alert.AlertType.WARNING, commandNameValidationMessage);
                alert.setHeaderText(null);
                alert.showAndWait();
                return false;
            }

            if (!saveAiProfilesToSettings()) {
                return false;
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
            globalSettings.setShowMenuBar(showMenuBarCheck.isSelected());
            
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

        CheckBox applyThemeFontsCheck = new CheckBox(I18n.get("theme.applyFontOption"));
        applyThemeFontsCheck.selectedProperty().bindBidirectional(applyThemeFontsProperty);
        vbox.getChildren().add(applyThemeFontsCheck);

        Label previewHeader = new Label(I18n.get("theme.preview"));
        previewHeader.setStyle("-fx-font-weight: bold;");
        Label previewSample = new Label(I18n.get("theme.previewSample"));
        HBox previewSwatches = new HBox(6);
        VBox previewBox = new VBox(8, previewSample, previewSwatches);
        previewBox.setPadding(new Insets(10));
        previewBox.setStyle("-fx-background-color: #1E1E1E; -fx-background-radius: 6;");
        vbox.getChildren().addAll(previewHeader, previewBox);
        
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
        themeList.getSelectionModel().selectedItemProperty().addListener((obs, oldTheme, newTheme) -> {
            if (newTheme != null) {
                selectedGlobalThemeId = newTheme.getId();
            }
            updateThemePreview(newTheme, previewBox, previewSample, previewSwatches);
        });

        if (selectedGlobalThemeId != null && !selectedGlobalThemeId.isEmpty()) {
            themeManager.getTheme(selectedGlobalThemeId).ifPresent(theme ->
                themeList.getSelectionModel().select(theme));
        }
        if (themeList.getSelectionModel().getSelectedItem() == null && !themeList.getItems().isEmpty()) {
            themeList.getSelectionModel().selectFirst();
        }
        updateThemePreview(themeList.getSelectionModel().getSelectedItem(), previewBox, previewSample, previewSwatches);
        
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
                themeList.getSelectionModel().select(t);
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
                themeList.getSelectionModel().select(edited);
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
                themeList.getSelectionModel().select(t);
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
                String deletedId = sel.getId();
                themeManager.removeTheme(sel.getId());
                themeList.getItems().clear();
                themeList.getItems().addAll(themeManager.getThemes());
                if (deletedId != null && deletedId.equals(selectedGlobalThemeId)) {
                    selectedGlobalThemeId = null;
                    if (!themeList.getItems().isEmpty()) {
                        themeList.getSelectionModel().selectFirst();
                    }
                }
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

    /**
     * Builds the target-language list for dynamic translations.
     * Uses ISO language codes as base so packaged runtime images still show many entries,
     * then enriches with any locales available in the current JVM.
     */
    private List<Locale> buildTranslationTargetLocales() {
        java.util.Map<String, Locale> byLanguage = new java.util.LinkedHashMap<>();

        for (String code : Locale.getISOLanguages()) {
            if (code != null && !code.isBlank()) {
                byLanguage.putIfAbsent(code, Locale.forLanguageTag(code));
            }
        }
        for (Locale l : Locale.getAvailableLocales()) {
            if (l.getLanguage() != null && !l.getLanguage().isBlank()) {
                byLanguage.putIfAbsent(l.getLanguage(), l);
            }
        }
        return byLanguage.values().stream()
                .sorted((a, b) -> getLocaleDisplayNameFallback(a).compareToIgnoreCase(getLocaleDisplayNameFallback(b)))
                .collect(Collectors.toList());
    }

    private String getLocaleDisplayNameFallback(Locale locale) {
        if (locale == null) return "";
        String display = locale.getDisplayLanguage();
        if (display == null || display.isBlank()) {
            display = locale.getDisplayLanguage(Locale.ENGLISH);
        }
        if (display == null || display.isBlank()) {
            display = locale.getLanguage();
        }
        return display;
    }

    private void updateThemePreview(Theme theme, VBox previewBox, Label previewSample, HBox previewSwatches) {
        if (theme == null) {
            previewSample.setText(I18n.get("theme.previewSample"));
            previewSample.setStyle("-fx-text-fill: #CCCCCC;");
            previewBox.setStyle("-fx-background-color: #1E1E1E; -fx-background-radius: 6;");
            previewSwatches.getChildren().clear();
            return;
        }

        previewSample.setText("AaBbCcDdEe 0123456789  " + theme.getName());
        previewSample.setFont(Font.font(theme.getFontFamily(), Math.max(10, theme.getFontSize())));
        previewSample.setStyle("-fx-text-fill: " + theme.getForegroundColor() + ";");
        previewBox.setStyle("-fx-background-color: " + theme.getBackgroundColor() + "; -fx-background-radius: 6;");

        previewSwatches.getChildren().clear();
        Label fg = new Label("FG");
        fg.setStyle("-fx-text-fill: " + theme.getBackgroundColor() + "; -fx-background-color: " + theme.getForegroundColor() + "; -fx-padding: 2 8 2 8; -fx-background-radius: 4;");
        Label bg = new Label("BG");
        bg.setStyle("-fx-text-fill: " + theme.getForegroundColor() + "; -fx-background-color: " + theme.getBackgroundColor() + "; -fx-padding: 2 8 2 8; -fx-border-color: " + theme.getForegroundColor() + "; -fx-border-radius: 4; -fx-background-radius: 4;");
        Label cursor = new Label("CURSOR");
        cursor.setStyle("-fx-text-fill: " + theme.getBackgroundColor() + "; -fx-background-color: " + theme.getCursorColor() + "; -fx-padding: 2 8 2 8; -fx-background-radius: 4;");
        previewSwatches.getChildren().addAll(fg, bg, cursor);
    }
    
    private String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }

    private static boolean isCursorBlink(String cursorStyle) {
        return cursorStyle != null && cursorStyle.toUpperCase().startsWith("BLINK");
    }

    private static String deriveCursorStyle(String currentStyle, boolean blink) {
        String s = (currentStyle != null && !currentStyle.isEmpty()) ? currentStyle : "BLINK_BLOCK";
        String suffix = s.contains("_") ? s.substring(s.indexOf('_') + 1) : "BLOCK";
        return blink ? "BLINK_" + suffix : "STEADY_" + suffix;
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

    private String getAiProfileDisplayName(AiProfile profile) {
        if (profile == null) {
            return "";
        }
        String name = profile.getName();
        if (name != null && !name.isBlank()) {
            return name.trim();
        }
        return I18n.get("settings.ai.profile.unnamed");
    }

    private void addAiProfile() {
        snapshotSelectedAiProfileEditorState();

        AiProfile profile = new AiProfile();
        profile.setId(UUID.randomUUID().toString());
        profile.setName(createDefaultAiProfileName());
        String defaultApiUrl = globalSettings != null ? globalSettings.getAiApiUrl() : null;
        profile.setApiUrl(defaultApiUrl != null && !defaultApiUrl.isBlank() ? defaultApiUrl : DEFAULT_AI_API_URL);
        profile.setMaxSelectionChars(AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        profile.setTokenizerType(AiTokenizerType.ESTIMATE);
        profile.setTokenLimitUnit(AiTokenLimitUnit.THOUSANDS);
        profile.setTokenResetPeriodDays(30);
        profile.setTokenResetAnchorDate(LocalDate.now().toString());
        profile.setTokenUsageCycleStartDate(LocalDate.now().toString());

        aiProfiles.add(profile);
        aiProfileListView.getItems().setAll(aiProfiles);
        aiProfileListView.getSelectionModel().select(profile);
        aiProfileListView.refresh();
    }

    private String createDefaultAiProfileName() {
        String baseName = I18n.get("settings.ai.profile.newDefault");
        int suffix = 1;
        String candidate = baseName;
        while (containsAiProfileName(candidate)) {
            suffix++;
            candidate = baseName + " " + suffix;
        }
        return candidate;
    }

    private boolean containsAiProfileName(String candidate) {
        for (AiProfile profile : aiProfiles) {
            if (candidate.equalsIgnoreCase(getAiProfileDisplayName(profile))) {
                return true;
            }
        }
        return false;
    }

    private void removeSelectedAiProfile() {
        AiProfile profile = aiProfileListView.getSelectionModel().getSelectedItem();
        if (profile == null) {
            return;
        }

        aiProfiles.remove(profile);
        if (profile.getId() != null) {
            aiPlainApiKeysByProfileId.remove(profile.getId());
            aiClearedApiKeysByProfileId.remove(profile.getId());
        }

        aiProfileListView.getItems().setAll(aiProfiles);
        if (aiProfiles.isEmpty()) {
            selectedAiProfile = null;
            loadAiProfileIntoEditor(null);
        } else {
            aiProfileListView.getSelectionModel().selectFirst();
        }
        aiProfileListView.refresh();
    }

    private void snapshotSelectedAiProfileEditorState() {
        if (selectedAiProfile == null) {
            return;
        }
        if (selectedAiProfile.getId() == null || selectedAiProfile.getId().isBlank()) {
            selectedAiProfile.setId(UUID.randomUUID().toString());
        }

        selectedAiProfile.setName(trimToNull(aiProfileNameField.getText()));
        selectedAiProfile.setApiUrl(trimToNull(aiApiUrlField.getText()));
        selectedAiProfile.setModel(trimToNull(aiModelField.getText()));
        selectedAiProfile.setMaxSelectionChars(aiMaxSelectionCharsSpinner.getValue() != null ? aiMaxSelectionCharsSpinner.getValue() : AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        selectedAiProfile.setTokenizerType(aiTokenizerCombo.getValue());
        selectedAiProfile.setTokenLimitAmount(aiTokenLimitAmountSpinner.getValue() != null ? aiTokenLimitAmountSpinner.getValue().longValue() : 0L);
        selectedAiProfile.setTokenLimitUnit(aiTokenLimitUnitCombo.getValue());
        selectedAiProfile.setTokenWarningYellowPercent(aiTokenWarningYellowSpinner.getValue());
        selectedAiProfile.setTokenWarningRedPercent(aiTokenWarningRedSpinner.getValue());
        selectedAiProfile.setTokenResetPeriodDays(aiTokenResetDaysSpinner.getValue());
        selectedAiProfile.setTokenResetAnchorDate(aiTokenResetAnchorPicker.getValue() != null ? aiTokenResetAnchorPicker.getValue().toString() : null);

        String profileId = selectedAiProfile.getId();
        String plainApiKey = aiApiKeyField.getText();
        if (plainApiKey != null && !plainApiKey.isBlank()) {
            aiPlainApiKeysByProfileId.put(profileId, plainApiKey);
            aiClearedApiKeysByProfileId.remove(profileId);
        } else {
            aiPlainApiKeysByProfileId.remove(profileId);
            if (aiClearApiKeyCheck.isSelected()) {
                aiClearedApiKeysByProfileId.add(profileId);
            } else {
                aiClearedApiKeysByProfileId.remove(profileId);
            }
        }
        aiProfileListView.refresh();
        updateAiTokenUsagePreview();
    }

    private void loadAiProfileIntoEditor(AiProfile profile) {
        if (profile == null) {
            aiProfileNameField.clear();
            aiApiUrlField.clear();
            aiModelField.clear();
            aiApiKeyField.clear();
            aiClearApiKeyCheck.setDisable(false);
            aiClearApiKeyCheck.setSelected(false);
            aiMaxSelectionCharsSpinner.getValueFactory().setValue(AiProfile.DEFAULT_MAX_SELECTION_CHARS);
            aiTokenizerCombo.setValue(AiTokenizerType.ESTIMATE);
            aiTokenLimitAmountSpinner.getValueFactory().setValue(0);
            aiTokenLimitUnitCombo.setValue(AiTokenLimitUnit.THOUSANDS);
            aiTokenWarningYellowSpinner.getValueFactory().setValue(75);
            aiTokenWarningRedSpinner.getValueFactory().setValue(90);
            aiTokenResetDaysSpinner.getValueFactory().setValue(30);
            aiTokenResetAnchorPicker.setValue(LocalDate.now());
            aiTokenUsageBar.update(0.0, 75, 90, AiTokenWarningLevel.NONE, true);
            aiTokenUsageLabel.setText("");
            return;
        }

        aiProfileNameField.setText(profile.getName() != null ? profile.getName() : "");
        aiApiUrlField.setText(profile.getApiUrl() != null ? profile.getApiUrl() : "");
        aiModelField.setText(profile.getModel() != null ? profile.getModel() : "");
        aiMaxSelectionCharsSpinner.getValueFactory().setValue(
            profile.getMaxSelectionChars() != null && profile.getMaxSelectionChars() > 0
                ? profile.getMaxSelectionChars()
                : AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        aiTokenizerCombo.setValue(profile.getTokenizerType() != null ? profile.getTokenizerType() : AiTokenizerType.ESTIMATE);
        aiTokenLimitAmountSpinner.getValueFactory().setValue(profile.getTokenLimitAmount() != null ? profile.getTokenLimitAmount().intValue() : 0);
        aiTokenLimitUnitCombo.setValue(profile.getTokenLimitUnit() != null ? profile.getTokenLimitUnit() : AiTokenLimitUnit.THOUSANDS);
        aiTokenWarningYellowSpinner.getValueFactory().setValue(profile.getTokenWarningYellowPercent() != null ? profile.getTokenWarningYellowPercent() : 75);
        aiTokenWarningRedSpinner.getValueFactory().setValue(profile.getTokenWarningRedPercent() != null ? profile.getTokenWarningRedPercent() : 90);
        aiTokenResetDaysSpinner.getValueFactory().setValue(profile.getTokenResetPeriodDays() != null ? profile.getTokenResetPeriodDays() : 30);
        aiTokenResetAnchorPicker.setValue(parseLocalDate(profile.getTokenResetAnchorDate(), LocalDate.now()));

        String profileId = profile.getId();
        String plainApiKey = profileId != null ? aiPlainApiKeysByProfileId.get(profileId) : null;
        aiApiKeyField.setText(plainApiKey != null ? plainApiKey : "");
        boolean cleared = profileId != null && aiClearedApiKeysByProfileId.contains(profileId);
        aiClearApiKeyCheck.setSelected(cleared);
        aiClearApiKeyCheck.setDisable(plainApiKey != null && !plainApiKey.isBlank());
        updateAiTokenUsagePreview();
    }

    private boolean saveAiProfilesToSettings() {
        if (globalSettings == null) {
            return true;
        }

        snapshotSelectedAiProfileEditorState();

        List<AiProfile> profilesToSave = new ArrayList<>();
        de.kortty.security.EncryptionService encryptionService = new de.kortty.security.EncryptionService();

        for (AiProfile profile : aiProfiles) {
            if (profile == null) {
                continue;
            }
            AiProfile copy = new AiProfile(profile);
            if (copy.getId() == null || copy.getId().isBlank()) {
                String generatedId = UUID.randomUUID().toString();
                copy.setId(generatedId);
                profile.setId(generatedId);
            }
            String profileName = trimToNull(copy.getName());
            if (profileName == null) {
                Alert alert = new Alert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noProfileName"));
                alert.setHeaderText(null);
                alert.showAndWait();
                return false;
            }
            copy.setName(profileName);
            copy.setApiUrl(trimToNull(copy.getApiUrl()));
            copy.setModel(trimToNull(copy.getModel()));

            String plainApiKey = aiPlainApiKeysByProfileId.get(copy.getId());
            if (plainApiKey != null && !plainApiKey.isBlank()) {
                char[] masterPassword = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
                if (masterPassword == null) {
                    Alert vaultLocked = new Alert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.vaultLocked"));
                    vaultLocked.setHeaderText(null);
                    vaultLocked.showAndWait();
                    return false;
                }
                try {
                    copy.setEncryptedApiKey(encryptionService.encryptPassword(plainApiKey, masterPassword));
                } catch (Exception ex) {
                    org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not encrypt AI API key", ex);
                    Alert alert = new Alert(Alert.AlertType.WARNING,
                        I18n.get("settings.ai.error.testFailed") + ": "
                            + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
                    alert.setHeaderText(null);
                    alert.showAndWait();
                    return false;
                }
            } else if (aiClearedApiKeysByProfileId.contains(copy.getId())) {
                copy.setEncryptedApiKey(null);
            }

            profilesToSave.add(copy);
        }

        globalSettings.setAiProfiles(profilesToSave);
        globalSettings.setAiApiUrl(null);
        globalSettings.setAiModel(null);
        globalSettings.setEncryptedAiApiKey(null);
        globalSettings.setAiConfirmBeforeSend(aiConfirmBeforeSendCheck.isSelected());
        globalSettings.setDefaultPromptHookEnabled(aiPromptHookEnabledCheck.isSelected());
        globalSettings.setTerminalAgentShowDebugMessages(aiShowDebugMessagesCheck.isSelected());
        globalSettings.setTerminalAgentShowRuntimeMessages(aiShowRuntimeMessagesCheck.isSelected());
        globalSettings.setTerminalAgentCommandName(TerminalAgentCommandSupport.normalizeCommandName(aiAgentCommandNameField.getText()));
        globalSettings.setTerminalAgentExecutionTarget(aiExecutionTargetCombo.getValue());
        return true;
    }

    private AiService createAiService(AiProfile profile) {
        if (profile == null) {
            return null;
        }
        String apiUrl = trimToNull(profile.getApiUrl());
        String model = trimToNull(profile.getModel());
        String apiKey = getAiApiKeyPlain(profile);
        if (apiUrl == null) {
            return null;
        }
        return new OpenAiCompatibleAiService(
            apiUrl,
            model != null ? model : "",
            apiKey != null ? apiKey : "");
    }

    private String getAiApiKeyPlain(AiProfile profile) {
        if (profile == null) {
            return null;
        }
        String profileId = profile.getId();
        if (profileId != null) {
            String plainApiKey = aiPlainApiKeysByProfileId.get(profileId);
            if (plainApiKey != null && !plainApiKey.isBlank()) {
                return plainApiKey;
            }
            if (aiClearedApiKeysByProfileId.contains(profileId)) {
                return null;
            }
        }
        String encrypted = profile.getEncryptedApiKey();
        if (encrypted == null || encrypted.isEmpty()) return null;
        try {
            char[] master = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
            if (master == null) return null;
            de.kortty.security.EncryptionService enc = new de.kortty.security.EncryptionService();
            String decrypted = enc.decryptPassword(encrypted, master);
            return decrypted != null && !decrypted.isBlank() ? decrypted : null;
        } catch (Exception e) {
            return null;
        }
    }

    private void testAiConnection(Button aiTestConnectionButton) {
        snapshotSelectedAiProfileEditorState();
        if (selectedAiProfile == null) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noProfilesConfigured")).showAndWait();
            return;
        }
        if (trimToNull(selectedAiProfile.getApiUrl()) == null) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noUrl")).showAndWait();
            return;
        }
        AiService svc = createAiService(selectedAiProfile);
        if (svc == null) {
            new Alert(Alert.AlertType.ERROR, I18n.get("settings.ai.error.testFailed")).showAndWait();
            return;
        }
        aiTestConnectionButton.setDisable(true);
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return svc.testConnection();
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            })
            .whenComplete((ok, throwable) -> Platform.runLater(() -> {
                aiTestConnectionButton.setDisable(false);
                if (throwable != null) {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    Alert alert = new Alert(Alert.AlertType.ERROR,
                        I18n.get("settings.ai.error.testFailed") + ": "
                            + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()));
                    alert.setHeaderText(null);
                    alert.showAndWait();
                    return;
                }
                Alert alert = ok != null && ok
                    ? new Alert(Alert.AlertType.INFORMATION, I18n.get("settings.ai.testSuccess"))
                    : new Alert(Alert.AlertType.ERROR, I18n.get("settings.ai.error.testFailed"));
                alert.setHeaderText(null);
                alert.showAndWait();
            }));
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void updateAiTokenUsagePreview() {
        if (selectedAiProfile == null) {
            aiTokenUsageLabel.setText("");
            return;
        }
        AiTokenUsageSnapshot snapshot = AiTokenUsageManager.refreshUsage(selectedAiProfile);
        String limitText = snapshot.unlimited()
            ? I18n.get("settings.ai.token.unlimited")
            : AiTokenUsageManager.formatCompact(snapshot.maxTokens());
        String remainingText = snapshot.unlimited()
            ? I18n.get("settings.ai.token.unlimited")
            : AiTokenUsageManager.formatCompact(snapshot.remainingTokens());
        String warningText = I18n.get("settings.ai.token.warning." + snapshot.warningLevel().name().toLowerCase(Locale.ROOT));
        double usedFraction = snapshot.unlimited() || snapshot.maxTokens() <= 0
            ? 0.0
            : Math.min(1.0, snapshot.usedTotalTokens() / (double) snapshot.maxTokens());
        aiTokenUsageBar.update(
            usedFraction,
            selectedAiProfile.getTokenWarningYellowPercent() != null ? selectedAiProfile.getTokenWarningYellowPercent() : 75,
            selectedAiProfile.getTokenWarningRedPercent() != null ? selectedAiProfile.getTokenWarningRedPercent() : 90,
            snapshot.warningLevel(),
            snapshot.unlimited());
        aiTokenUsageLabel.setText(I18n.get(
            "settings.ai.token.usage.summary",
            AiTokenUsageManager.formatCompact(snapshot.usedTotalTokens()),
            limitText,
            remainingText,
            snapshot.nextResetDate(),
            warningText));
        aiProfileListView.refresh();
    }

    private String buildAiProfileUsageInline(AiProfile profile) {
        AiTokenUsageSnapshot snapshot = AiTokenUsageManager.refreshUsage(profile);
        String limitText = snapshot.unlimited()
            ? I18n.get("settings.ai.token.unlimited")
            : AiTokenUsageManager.formatCompact(snapshot.maxTokens());
        return I18n.get("settings.ai.token.usage.inline",
            AiTokenUsageManager.formatCompact(snapshot.usedTotalTokens()),
            limitText,
            snapshot.nextResetDate());
    }

    private LocalDate parseLocalDate(String value, LocalDate fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (Exception ignored) {
            return fallback;
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
