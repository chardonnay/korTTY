package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.telemetry.Telemetry;
import de.kortty.telemetry.TelemetryEvents;
import de.kortty.telemetry.TelemetryService;
import de.kortty.ui.I18n;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.JvmLaunchProfileStore;
import de.kortty.model.JvmResourceProfile;
import de.kortty.core.DynamicLanguageGenerator;
import de.kortty.core.GuideTranslationGenerator;
import de.kortty.core.GuideLocationResolver;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.AiCliArgumentPreset;
import de.kortty.core.AiCliArgumentTemplate;
import de.kortty.core.AiCliProviderDescriptor;
import de.kortty.core.AiCliProviderRegistry;
import de.kortty.core.AiTokenUsageManager;
import de.kortty.core.AiTokenUsageSnapshot;
import de.kortty.core.AiTokenWarningLevel;
import de.kortty.core.AiInternetAccessConfiguration;
import de.kortty.core.AiReasoningDiscoveryService;
import de.kortty.core.AiReasoningSupport;
import de.kortty.core.AiRequestTimeoutSupport;
import de.kortty.core.AiServiceFactory;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.core.AiProfileSelectionSupport;
import de.kortty.core.AiModelComboSupport;
import de.kortty.core.LocalLmModelResolver;
import de.kortty.core.AiLanguageSupport;
import de.kortty.core.AiService;
import de.kortty.core.AiPromptService;
import de.kortty.core.FailingAiService;
import de.kortty.core.GoogleTranslationService;
import de.kortty.core.DeepLTranslationService;
import de.kortty.core.LibreTranslateTranslationService;
import de.kortty.core.LocalAiTranslationService;
import de.kortty.core.MicrosoftTranslationService;
import de.kortty.core.TerminalAgentCommandSupport;
import de.kortty.core.YandexTranslationService;
import de.kortty.core.LanguageManager;
import de.kortty.core.LoggingConfiguration;
import de.kortty.core.SSHKeyManager;
import de.kortty.core.ThemeManager;
import de.kortty.core.TranslationService;
import de.kortty.ai.llama.LlamaModel;
import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.mlx.MlxModel;
import de.kortty.ai.mlx.MlxModelRegistry;
import de.kortty.ai.mlx.MlxPlatform;
import de.kortty.model.AiProfile;
import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiReasoningEffort;
import de.kortty.model.AiTokenLimitUnit;
import de.kortty.model.AiTokenizerType;
import de.kortty.model.AppDesign;
import de.kortty.model.ChatColorProfile;
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
import de.kortty.security.MasterPasswordManager;
import de.kortty.security.MasterPasswordReEncryptor;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.Clipboard;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.function.Supplier;
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
    private static final double APP_DESIGN_PREVIEW_MAX_HEIGHT = 260;
    private static final String APP_DESIGN_PREVIEW_NEUTRAL_STYLE =
        "-fx-border-color: rgba(255,255,255,0.12); -fx-border-width: 1;";

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
    private final CheckBox terminalColorsEnabledCheck;
    
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
    private final CheckBox terminalRecordingAlwaysEnabledCheck;
    private final CheckBox terminalRecordingCaptureColorsCheck;

    // Appearance settings
    private final ComboBox<AppDesign> appDesignCombo;
    private ComboBox<ChatColorProfile> chatColorProfileCombo;
    private CheckBox appDesignAnimationsCheck;
    
    // Security settings
    private final CheckBox requireMasterPasswordOnStartupCheck;
    private final CheckBox skipMasterPasswordPromptCheck;
    private final CheckBox telemetryEnabledCheck;
    private final CheckBox temporarySshKeyEnabledCheck;
    
    // SSH Keep-Alive settings
    private final CheckBox sshKeepAliveCheck;
    private final CheckBox disableHostKeyCheckAllCheck;
    private final Spinner<Integer> sshKeepAliveIntervalSpinner;
    
    // Connection settings
    private final CheckBox connectionRetriesEnabledCheck;
    
    // Backup settings
    private final Spinner<Integer> maxBackupSpinner;
    private final javafx.scene.control.RadioButton passwordEncryptionRadio;
    private final javafx.scene.control.RadioButton gpgEncryptionRadio;
    private final ComboBox<StoredCredential> backupCredentialCombo;
    private final ComboBox<GPGKey> backupGpgKeyCombo;

    // Logging settings
    private final TextField logDirectoryPathField;
    private final Spinner<Integer> logRetentionDaysSpinner;
    private CheckBox pdfWatermarkEnabledCheck;
    private TextField pdfWatermarkTextField;
    private javafx.scene.control.ColorPicker pdfWatermarkColorPicker;
    private CheckBox exportFooterEnabledCheck;
    private TextField exportFooterTextField;
    private TextField sessionJournalStoragePathField;
    private CheckBox sessionJournalAiSummariesCheck;
    private Spinner<Integer> sessionJournalIntervalSpinner;
    private ComboBox<de.kortty.model.AiProfile> sessionJournalAiProfileCombo;

    // Update settings
    private final CheckBox updateChecksEnabledCheck;
    private final Slider updateCheckIntervalSlider;
    private final Label updateCheckIntervalValueLabel;
    
    // Window settings
    private final CheckBox rememberWindowGeometryCheck;
    private final CheckBox rememberDashboardStateCheck;
    private final CheckBox openToolWindowsAsTabsCheck;
    private final CheckBox useFixedGeometryCheck;
    private final Spinner<Integer> fixedWidthSpinner;
    private final Spinner<Integer> fixedHeightSpinner;
    private final Spinner<Integer> fixedXSpinner;
    private final Spinner<Integer> fixedYSpinner;
    
    // JVM resource profile (opt-in heap/GC), applied via relaunch on next start
    private ComboBox<JvmResourceProfile> jvmResourceProfileCombo;

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
    // Guide translation runs for hours on a local model, so unlike the interface-string
    // generation it needs a progress bar and a cancel button of its own.
    private javafx.scene.control.ProgressBar guideTranslationProgress;
    private Button guideTranslationCancelButton;
    private Label guideTranslationStatusLabel;
    private ListView<String> guideTranslationList;
    private ComboBox<AiProfile> guideAiProfileCombo;
    private ComboBox<AiProfile> interfaceAiProfileCombo;
    private Runnable guideJobListener;

    // AI settings
    private static final String DEFAULT_AI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String AI_MODEL_DEFAULT_LABEL = I18n.get("ai.model.default");
    private static final String AI_MODEL_AUTO_LABEL = I18n.get("ai.model.auto");
    private static final String AI_MODEL_CUSTOM_LABEL = I18n.get("settings.ai.cli.model.custom");
    private final ListView<AiProfile> aiProfileListView;
    private final TextField aiProfileNameField;
    private final ComboBox<AiConnectionMode> aiConnectionModeCombo;
    private final TextField aiApiUrlField;
    private final ComboBox<String> aiModelCombo;
    private final Label aiEmbeddedModelLabel;
    private final ComboBox<EmbeddedModelChoice> aiEmbeddedModelCombo;
    private final TextField aiCliCustomModelField;
    private final Button aiRefreshModelsButton;
    private final ComboBox<AiReasoningEffort> aiReasoningCombo;
    private final Button aiRefreshReasoningButton;
    private final ComboBox<AiInternetAccessMode> aiInternetAccessModeCombo;
    private final PasswordField aiApiKeyField;
    private final CheckBox aiClearApiKeyCheck;
    private final ComboBox<AiCliProviderDescriptor> aiCliProviderCombo;
    private final TextField aiCliExecutableField;
    private final TextArea aiCliArgumentsTemplateArea;
    private final Button aiRefreshCliStatusButton;
    private final Label aiCliStatusLabel;
    private final PasswordField aiTavilyApiKeyField;
    private final CheckBox aiClearTavilyApiKeyCheck;
    private final PasswordField aiBrightDataApiTokenField;
    private final CheckBox aiClearBrightDataApiTokenCheck;
    private final PasswordField aiBraveSearchApiKeyField;
    private final CheckBox aiClearBraveSearchApiKeyCheck;
    private final TextField aiSearxngUrlField;
    private final TextField aiTavilyMcpServerLabelField;
    private final TextField aiBrightDataMcpServerLabelField;
    private final TextField aiBraveSearchMcpPluginIdField;
    private final TextField aiSearxngMcpPluginIdField;
    private final TextField aiLmStudioToolpackMcpPluginIdField;
    private final CheckBox aiFeaturesEnabledCheck;
    private final CheckBox aiConfirmBeforeSendCheck;
    private final CheckBox aiTerminalAgentExecutionEnabledCheck;
    private final CheckBox aiTerminalAgentConfirmMutatingCommandSetsCheck;
    private final CheckBox aiPromptHookEnabledCheck;
    private final CheckBox aiShowDebugMessagesCheck;
    private final CheckBox aiShowRuntimeMessagesCheck;
    private final CheckBox aiTerminalAgentShowRunDialogCheck;
    private final TextField aiAgentCommandNameField;
    private final CheckBox aiAgentCommandNameCaseInsensitiveCheck;
    private final ComboBox<TerminalAgentExecutionTarget> aiExecutionTargetCombo;
    private final Spinner<Integer> aiMaxSelectionCharsSpinner;
    private final Spinner<Integer> aiGlobalRequestTimeoutSpinner;
    private final CheckBox aiRequestTimeoutOverrideCheck;
    private final Spinner<Integer> aiRequestTimeoutSpinner;
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
    private final ComboBox<AiProfile> aiDefaultProfileCombo;
    private final ComboBox<AiProfile> aiSecurityCheckProfileCombo;
    private final ComboBox<AiLanguageSupport.LanguageOption> aiCodeTextLanguageCombo;
    private final CheckBox aiSnippetEditorInstructionsCheck;
    private final Spinner<Integer> aiSnippetAlternativeSolutionCountSpinner;
    private final Spinner<Integer> terminalAgentInputHistorySizeSpinner;
    private AiProfile selectedAiProfile;

    private TabPane mainTabPane;

    // SFTP settings
    private final CheckBox sftpAutoCloseEnabledCheck;
    private final Spinner<Integer> sftpAutoCloseMinutesSpinner;
    private final TextField sftpDefaultZipPathField;
    private final Spinner<Integer> sftpDefaultZipCompressionSpinner;
    private final TextField jobSchedulerRsyncBinaryPathField;
    
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
        this.mainTabPane = tabPane;

        // Appearance tab
        Tab appearanceTab = new Tab(I18n.get("settings.tab.appearance"));
        appDesignCombo = new ComboBox<>();
        appDesignCombo.getItems().setAll(AppDesign.values());
        appDesignCombo.setValue(globalSettings != null ? globalSettings.getAppDesign() : AppDesign.NORMAL);
        appDesignCombo.setPrefWidth(220);
        appDesignCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(AppDesign design) {
                return appDesignLabel(design);
            }

            @Override
            public AppDesign fromString(String value) {
                return null;
            }
        });
        // The collapsed combo renders its current design through a "button cell". JavaFX does NOT
        // reliably re-render that cell when the value changes programmatically (via the prev/next
        // buttons): the value, selection and preview all update, but the cell's Text node keeps
        // showing a stale design name. Just calling setText on the existing cell doesn't fix it
        // (the property updates but the rendered Text node doesn't). What works reliably is to
        // install a FRESH button cell with the label pre-set and force the combo to lay it out, so
        // the skin rebuilds and re-renders the display node from the current value. (Selecting from
        // the open dropdown was never affected — only programmatic value changes.)
        Supplier<ListCell<AppDesign>> appDesignButtonCellFactory = () -> new ListCell<>() {
            @Override
            protected void updateItem(AppDesign design, boolean empty) {
                super.updateItem(design, empty);
                setText(empty || design == null ? null : appDesignLabel(design));
            }
        };
        appDesignCombo.setButtonCell(appDesignButtonCellFactory.get());
        appDesignCombo.valueProperty().addListener((obs, oldDesign, newDesign) -> {
            ListCell<AppDesign> fresh = appDesignButtonCellFactory.get();
            fresh.setText(newDesign == null ? null : appDesignLabel(newDesign));
            appDesignCombo.setButtonCell(fresh);
            appDesignCombo.applyCss();
            appDesignCombo.layout();
        });

        // Previous/next buttons let the user step through the designs without opening the dropdown.
        Button appDesignPrevButton = new Button("◀");
        appDesignPrevButton.setTooltip(new Tooltip(I18n.get("settings.appearance.design.previous")));
        appDesignPrevButton.setAccessibleText(I18n.get("settings.appearance.design.previous"));
        appDesignPrevButton.getStyleClass().add("settings-section-nav-button");
        // Step via setValue (not selectionModel.select): setValue drives the combo's value
        // property directly, which the skin always observes to refresh the collapsed display.
        // Going through the selection model could change the value (and the preview) while leaving
        // the shown label stuck. The index is read from the current value, so it stays correct.
        appDesignPrevButton.setOnAction(e -> appDesignCombo.setValue(appDesignCombo.getItems().get(
            AppDesignNavigation.previous(appDesignCombo.getItems().indexOf(appDesignCombo.getValue()),
                appDesignCombo.getItems().size()))));
        Button appDesignNextButton = new Button("▶");
        appDesignNextButton.setTooltip(new Tooltip(I18n.get("settings.appearance.design.next")));
        appDesignNextButton.setAccessibleText(I18n.get("settings.appearance.design.next"));
        appDesignNextButton.getStyleClass().add("settings-section-nav-button");
        appDesignNextButton.setOnAction(e -> appDesignCombo.setValue(appDesignCombo.getItems().get(
            AppDesignNavigation.next(appDesignCombo.getItems().indexOf(appDesignCombo.getValue()),
                appDesignCombo.getItems().size()))));

        HBox appDesignControls = new HBox(8,
            new Label(I18n.get("settings.appearance.appDesign")), appDesignCombo,
            appDesignPrevButton, appDesignNextButton);
        appDesignControls.setAlignment(Pos.CENTER_LEFT);

        appDesignAnimationsCheck = new CheckBox(I18n.get("settings.appearance.animations"));
        appDesignAnimationsCheck.setSelected(globalSettings == null || globalSettings.isAppDesignAnimationsEnabled());

        // Chat color profile (themes for the AI/swarm chat surfaces).
        chatColorProfileCombo = new ComboBox<>();
        chatColorProfileCombo.getItems().setAll(ChatColorProfileSupport.all());
        chatColorProfileCombo.setPrefWidth(220);
        Supplier<ListCell<ChatColorProfile>> chatProfileCellFactory = () -> new ListCell<>() {
            @Override
            protected void updateItem(ChatColorProfile profile, boolean empty) {
                super.updateItem(profile, empty);
                setText(empty || profile == null ? null : ChatColorProfileSupport.displayName(profile));
            }
        };
        chatColorProfileCombo.setCellFactory(listView -> chatProfileCellFactory.get());
        chatColorProfileCombo.setButtonCell(chatProfileCellFactory.get());
        chatColorProfileCombo.setValue(ChatColorProfileSupport.activeProfile(app));
        HBox chatColorProfileControls = new HBox(8,
            new Label(I18n.get("settings.appearance.chatColorProfile")), chatColorProfileCombo);
        chatColorProfileControls.setAlignment(Pos.CENTER_LEFT);

        Label appearanceInfo = new Label(I18n.get("settings.appearance.appDesign.info"));
        appearanceInfo.setWrapText(true);
        appearanceInfo.setMaxWidth(460);
        appearanceInfo.getStyleClass().add("settings-info-label");
        appearanceInfo.setStyle(globalSettings == null || globalSettings.getAppDesign() == AppDesign.NORMAL
            ? "-fx-font-size: 11px; -fx-text-fill: gray;"
            : "-fx-font-size: 11px;");

        Label appDesignPreviewLabel = new Label(I18n.get("settings.appearance.preview"));
        appDesignPreviewLabel.getStyleClass().add("settings-info-label");

        ImageView appDesignPreviewImage = createAppDesignPreviewImage();
        // Shown centered in place of the image for designs without a preview (e.g. Default).
        Label appDesignPreviewPlaceholder = new Label(I18n.get("settings.appearance.preview.none"));
        appDesignPreviewPlaceholder.getStyleClass().add("settings-info-label");
        appDesignPreviewPlaceholder.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        appDesignPreviewPlaceholder.setWrapText(true);

        // Fixed-size area that holds either the preview image or the "no preview" note, both
        // centered. Crucially its size is the SAME for every design, so switching designs only
        // swaps the *content* and never resizes the layout. A resize here was what made the
        // surrounding container skip a layout/repaint pass and leave the preview drawn over the
        // dropdown (or a stale image behind the placeholder when switching back to Default).
        StackPane appDesignPreviewArea = new StackPane(appDesignPreviewImage, appDesignPreviewPlaceholder);
        appDesignPreviewArea.setMinHeight(APP_DESIGN_PREVIEW_MAX_HEIGHT + 16);
        appDesignPreviewArea.setPrefHeight(APP_DESIGN_PREVIEW_MAX_HEIGHT + 16);
        appDesignPreviewArea.setMaxWidth(452);

        VBox appDesignPreviewBox = new VBox(6, appDesignPreviewLabel, appDesignPreviewArea);
        appDesignPreviewBox.setMaxWidth(460);
        appDesignPreviewBox.setPadding(new Insets(6));
        appDesignCombo.valueProperty().addListener((obs, oldDesign, newDesign) ->
            updateAppDesignPreview(appDesignPreviewImage, appDesignPreviewPlaceholder, appDesignPreviewBox, newDesign));
        updateAppDesignPreview(appDesignPreviewImage, appDesignPreviewPlaceholder, appDesignPreviewBox, appDesignCombo.getValue());

        // Plain top-to-bottom stack: controls row, the info text, then the fixed preview box below.
        VBox appearanceContent = new VBox(14, appDesignControls, appDesignAnimationsCheck,
            chatColorProfileControls, appearanceInfo, appDesignPreviewBox);
        appearanceContent.setPadding(new Insets(20));
        appearanceTab.setContent(appearanceContent);
        
        // Font tab
        Tab fontTab = new Tab(I18n.get("settings.tab.font"));
        GridPane fontGrid = new GridPane();
        fontGrid.setHgap(10);
        fontGrid.setVgap(10);
        fontGrid.setPadding(new Insets(20));
        
        fontFamilyCombo = new ComboBox<>();
        fontFamilyCombo.getItems().addAll(MonospaceFontFamilies.monospaceFirst());
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
            refreshColorProfileCombo(colorThemeManager, selectedGlobalThemeId);
        }
        cursorBlinkCheck = new CheckBox(I18n.get("settings.colors.cursorBlink"));
        cursorBlinkCheck.setSelected(isCursorBlink(settings.getCursorStyle()));
        cursorBlinkCheck.setTooltip(new Tooltip(I18n.get("settings.colors.cursorBlink.tooltip")));
        terminalColorsEnabledCheck = new CheckBox(I18n.get("settings.colors.terminalColors"));
        terminalColorsEnabledCheck.setSelected(settings.isTerminalColorsEnabled());
        terminalColorsEnabledCheck.setTooltip(new Tooltip(I18n.get("settings.colors.terminalColors.tooltip")));
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
                // Take the profile's cursor SHAPE but keep the user's blink preference: "Cursor blinks"
                // is an explicit setting of its own, and every built-in profile ships BLINK_*. Applying
                // the profile's style verbatim silently re-enabled blinking (and re-ticked the box), so
                // the "off" choice was lost on save and the cursor blinked again after a restart.
                settings.setCursorStyle(deriveCursorStyle(selected.getCursorStyle(), cursorBlinkCheck.isSelected()));
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
        colorsGrid.add(terminalColorsEnabledCheck, 0, 6, 2, 1);
        
        // ANSI Colors section
        colorsGrid.add(new Separator(), 0, 7, 2, 1);
        colorsGrid.add(new Label(I18n.get("settings.colors.ansi")), 0, 8, 2, 1);
        
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
        normalColorsBox.disableProperty().bind(terminalColorsEnabledCheck.selectedProperty().not());
        brightColorsBox.disableProperty().bind(terminalColorsEnabledCheck.selectedProperty().not());
        
        colorsGrid.add(new Label(I18n.get("settings.colors.normal")), 0, 9);
        colorsGrid.add(normalColorsBox, 1, 9);
        colorsGrid.add(new Label(I18n.get("settings.colors.bright")), 0, 10);
        colorsGrid.add(brightColorsBox, 1, 10);
        
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

        disableHostKeyCheckAllCheck = new CheckBox(I18n.get("settings.terminal.hostKeyCheck.disableAll"));
        de.kortty.policy.PolicyUiSupport.lockIfManaged(
            disableHostKeyCheckAllCheck, de.kortty.policy.ManagedSetting.HOST_KEY_CHECK);

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

        // SSH host-key verification (global, insecure opt-out)
        terminalGrid.add(new Separator(), 0, 14, 2, 1);
        terminalGrid.add(new Label(I18n.get("settings.terminal.hostKeyCheck")), 0, 15, 2, 1);
        disableHostKeyCheckAllCheck.setSelected(globalSettings != null && globalSettings.isHostKeyCheckDisabledForAllConnections());
        disableHostKeyCheckAllCheck.setTooltip(new Tooltip(I18n.get("settings.terminal.hostKeyCheck.disableAll.tooltip")));
        terminalGrid.add(disableHostKeyCheckAllCheck, 0, 16, 2, 1);
        Label hostKeyWarn = new Label(I18n.get("settings.terminal.hostKeyCheck.warning"));
        hostKeyWarn.setStyle("-fx-font-size: 10px; -fx-text-fill: #d9534f;");
        hostKeyWarn.setWrapText(true);
        terminalGrid.add(hostKeyWarn, 0, 17, 2, 1);

        // Connection section
        terminalGrid.add(new Separator(), 0, 18, 2, 1);
        Label connectionHeader = new Label(I18n.get("settings.connection.header"));
        connectionHeader.setStyle("-fx-font-weight: bold;");
        terminalGrid.add(connectionHeader, 0, 19, 2, 1);
        terminalGrid.add(connectionRetriesEnabledCheck, 0, 20, 2, 1);
        
        terminalTab.setContent(terminalGrid);

        // Video tab
        Tab videoTab = new Tab(I18n.get("settings.tab.video"));
        GridPane videoGrid = new GridPane();
        videoGrid.setHgap(10);
        videoGrid.setVgap(10);
        videoGrid.setPadding(new Insets(20));

        terminalRecordingAlwaysEnabledCheck = new CheckBox(I18n.get("settings.video.recordingAlwaysEnabled"));
        terminalRecordingAlwaysEnabledCheck.setSelected(globalSettings != null && globalSettings.isTerminalRecordingEnabled());
        terminalRecordingAlwaysEnabledCheck.setTooltip(
            new Tooltip(I18n.get("settings.video.recordingAlwaysEnabled.tooltip")));
        de.kortty.policy.PolicyUiSupport.lockIfManaged(
            terminalRecordingAlwaysEnabledCheck, de.kortty.policy.ManagedSetting.TERMINAL_RECORDING);

        terminalRecordingCaptureColorsCheck = new CheckBox(I18n.get("settings.video.captureColors"));
        terminalRecordingCaptureColorsCheck.setSelected(
            globalSettings != null && globalSettings.isTerminalRecordingCaptureColorsEnabled());
        terminalRecordingCaptureColorsCheck.setTooltip(new Tooltip(I18n.get("settings.video.captureColors.tooltip")));

        videoGrid.add(terminalRecordingAlwaysEnabledCheck, 0, 0, 2, 1);
        videoGrid.add(terminalRecordingCaptureColorsCheck, 0, 1, 2, 1);
        videoTab.setContent(videoGrid);
        
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

        // Logging tab
        Tab loggingTab = new Tab(I18n.get("settings.tab.logging"));
        GridPane loggingGrid = new GridPane();
        loggingGrid.setHgap(10);
        loggingGrid.setVgap(10);
        loggingGrid.setPadding(new Insets(20));
        int loggingRow = 0;

        Label loggingHeader = new Label(I18n.get("settings.logging.header"));
        loggingHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        loggingGrid.add(loggingHeader, 0, loggingRow++, 2, 1);

        logDirectoryPathField = new TextField();
        logDirectoryPathField.setPrefWidth(420);
        Path effectiveLogDirectory = LoggingConfiguration.resolveLogDirectory(globalSettings, KorTTYApplication.getConfigDirectory());
        logDirectoryPathField.setText(effectiveLogDirectory.toString());
        Button logDirectoryBrowseButton = new Button(I18n.get("connEdit.browse"));
        logDirectoryBrowseButton.setOnAction(event -> chooseLogDirectory());
        if (de.kortty.policy.PolicyManager.effective().logging().directory() != null) {
            logDirectoryPathField.setDisable(true);
            logDirectoryPathField.setTooltip(new Tooltip(
                de.kortty.policy.PolicyUiSupport.managedByOrganizationText()));
            logDirectoryBrowseButton.setDisable(true);
        }
        HBox logDirectoryBox = new HBox(10, logDirectoryPathField, logDirectoryBrowseButton);
        HBox.setHgrow(logDirectoryPathField, Priority.ALWAYS);
        loggingGrid.add(new Label(I18n.get("settings.logging.directory")), 0, loggingRow);
        loggingGrid.add(logDirectoryBox, 1, loggingRow++);

        Label logDirectoryInfo = new Label(I18n.get(
            "settings.logging.directory.info",
            LoggingConfiguration.defaultLogDirectory(KorTTYApplication.getConfigDirectory())));
        logDirectoryInfo.setWrapText(true);
        logDirectoryInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        loggingGrid.add(logDirectoryInfo, 1, loggingRow++);

        logRetentionDaysSpinner = new Spinner<>(
            0,
            GlobalSettings.MAX_LOG_RETENTION_DAYS,
            globalSettings != null ? globalSettings.getLogRetentionDays() : GlobalSettings.DEFAULT_LOG_RETENTION_DAYS);
        logRetentionDaysSpinner.setEditable(true);
        logRetentionDaysSpinner.setPrefWidth(120);
        logRetentionDaysSpinner.setTooltip(new Tooltip(I18n.get("settings.logging.retention.tooltip")));
        if (de.kortty.policy.PolicyManager.effective().logging().retentionDays() != null) {
            logRetentionDaysSpinner.setDisable(true);
            logRetentionDaysSpinner.setTooltip(new Tooltip(
                de.kortty.policy.PolicyUiSupport.managedByOrganizationText()));
        }
        HBox logRetentionBox = new HBox(10, logRetentionDaysSpinner, new Label(I18n.get("common.days")));
        logRetentionBox.setAlignment(Pos.CENTER_LEFT);
        loggingGrid.add(new Label(I18n.get("settings.logging.retention")), 0, loggingRow);
        loggingGrid.add(logRetentionBox, 1, loggingRow++);

        Label logRetentionInfo = new Label(I18n.get("settings.logging.retention.info"));
        logRetentionInfo.setWrapText(true);
        logRetentionInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        loggingGrid.add(logRetentionInfo, 1, loggingRow++);

        Label logCompressionInfo = new Label(I18n.get("settings.logging.compression.info"));
        logCompressionInfo.setWrapText(true);
        logCompressionInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        loggingGrid.add(logCompressionInfo, 0, loggingRow++, 2, 1);

        // Session journal section (journal capture is logging-adjacent, so it lives on this tab)
        loggingGrid.add(new Separator(), 0, loggingRow++, 2, 1);
        Label journalHeader = new Label(I18n.get("settings.journal.section"));
        journalHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        loggingGrid.add(journalHeader, 0, loggingRow++, 2, 1);

        de.kortty.policy.EffectivePolicy journalPolicy = de.kortty.policy.PolicyManager.effective();
        sessionJournalStoragePathField = new TextField();
        sessionJournalStoragePathField.setPrefWidth(420);
        sessionJournalStoragePathField.setText(globalSettings != null
            && globalSettings.getSessionJournalStoragePath() != null
            ? globalSettings.getSessionJournalStoragePath() : "");
        Button journalPathBrowseButton = new Button(I18n.get("settings.journal.browse"));
        journalPathBrowseButton.setOnAction(event -> {
            javafx.stage.DirectoryChooser chooser = new javafx.stage.DirectoryChooser();
            chooser.setTitle(I18n.get("settings.journal.storagePath"));
            java.io.File selected = chooser.showDialog(getDialogPane().getScene().getWindow());
            if (selected != null) {
                sessionJournalStoragePathField.setText(selected.getAbsolutePath());
            }
        });
        if (journalPolicy.sessionJournal().storagePath() != null) {
            sessionJournalStoragePathField.setDisable(true);
            sessionJournalStoragePathField.setTooltip(new Tooltip(
                de.kortty.policy.PolicyUiSupport.managedByOrganizationText()));
            journalPathBrowseButton.setDisable(true);
        }
        HBox journalPathBox = new HBox(10, sessionJournalStoragePathField, journalPathBrowseButton);
        HBox.setHgrow(sessionJournalStoragePathField, Priority.ALWAYS);
        loggingGrid.add(new Label(I18n.get("settings.journal.storagePath")), 0, loggingRow);
        loggingGrid.add(journalPathBox, 1, loggingRow++);

        sessionJournalAiSummariesCheck = new CheckBox(I18n.get("settings.journal.aiSummaries"));
        sessionJournalAiSummariesCheck.setSelected(globalSettings == null
            || globalSettings.isSessionJournalAiSummariesEnabled());
        if (!journalPolicy.sessionJournalAiSummariesAllowed()) {
            sessionJournalAiSummariesCheck.setSelected(false);
            sessionJournalAiSummariesCheck.setDisable(true);
            sessionJournalAiSummariesCheck.setTooltip(new Tooltip(
                de.kortty.policy.PolicyUiSupport.managedByOrganizationText()));
        }
        loggingGrid.add(sessionJournalAiSummariesCheck, 0, loggingRow++, 2, 1);

        sessionJournalIntervalSpinner = new Spinner<>(1, 240,
            globalSettings != null ? globalSettings.getSessionJournalSummarizeIntervalMinutes() : 5);
        sessionJournalIntervalSpinner.setEditable(true);
        sessionJournalIntervalSpinner.setPrefWidth(120);
        sessionJournalIntervalSpinner.disableProperty().bind(
            sessionJournalAiSummariesCheck.selectedProperty().not());
        loggingGrid.add(new Label(I18n.get("settings.journal.interval")), 0, loggingRow);
        loggingGrid.add(sessionJournalIntervalSpinner, 1, loggingRow++);

        sessionJournalAiProfileCombo = new ComboBox<>();
        de.kortty.model.AiProfile defaultProfilePlaceholder = null;
        sessionJournalAiProfileCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(de.kortty.model.AiProfile profile) {
                return profile != null && profile.getName() != null
                    ? profile.getName()
                    : I18n.get("settings.journal.defaultProfile");
            }

            @Override
            public de.kortty.model.AiProfile fromString(String value) {
                return null;
            }
        });
        sessionJournalAiProfileCombo.getItems().add(defaultProfilePlaceholder);
        String journalProfileId = globalSettings != null ? globalSettings.getSessionJournalAiProfileId() : null;
        if (globalSettings != null && globalSettings.getAiProfiles() != null) {
            for (de.kortty.model.AiProfile profile : globalSettings.getAiProfiles()) {
                if (profile != null) {
                    sessionJournalAiProfileCombo.getItems().add(profile);
                    if (journalProfileId != null && journalProfileId.equals(profile.getId())) {
                        sessionJournalAiProfileCombo.setValue(profile);
                    }
                }
            }
        }
        sessionJournalAiProfileCombo.disableProperty().bind(
            sessionJournalAiSummariesCheck.selectedProperty().not());
        loggingGrid.add(new Label(I18n.get("settings.journal.aiProfile")), 0, loggingRow);
        loggingGrid.add(sessionJournalAiProfileCombo, 1, loggingRow++);

        loggingTab.setContent(loggingGrid);

        // Export tab: watermark and footer for exported documents (journals and AI chats)
        Tab exportTab = new Tab(I18n.get("settings.tab.export"));
        GridPane exportGrid = new GridPane();
        exportGrid.setHgap(10);
        exportGrid.setVgap(10);
        exportGrid.setPadding(new Insets(20));
        int exportRow = 0;

        Label exportHeader = new Label(I18n.get("settings.export.header"));
        exportHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        exportGrid.add(exportHeader, 0, exportRow++, 2, 1);

        Label exportIntro = new Label(I18n.get("settings.export.intro"));
        exportIntro.setWrapText(true);
        exportIntro.setMaxWidth(560);
        exportIntro.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        exportGrid.add(exportIntro, 0, exportRow++, 2, 1);

        pdfWatermarkEnabledCheck = new CheckBox(I18n.get("settings.export.watermark"));
        pdfWatermarkEnabledCheck.setSelected(globalSettings != null && globalSettings.isPdfWatermarkEnabled());
        exportGrid.add(pdfWatermarkEnabledCheck, 0, exportRow++, 2, 1);

        pdfWatermarkTextField = new TextField();
        pdfWatermarkTextField.setPrefWidth(360);
        pdfWatermarkTextField.setPromptText(de.kortty.core.ExportBranding.DEFAULT_WATERMARK_TEXT);
        pdfWatermarkTextField.setText(globalSettings != null && globalSettings.getPdfWatermarkText() != null
            ? globalSettings.getPdfWatermarkText() : "");
        pdfWatermarkTextField.disableProperty().bind(pdfWatermarkEnabledCheck.selectedProperty().not());
        exportGrid.add(new Label(I18n.get("settings.export.watermarkText")), 0, exportRow);
        exportGrid.add(pdfWatermarkTextField, 1, exportRow++);

        pdfWatermarkColorPicker = new javafx.scene.control.ColorPicker(
            toFxColor(de.kortty.core.ExportBranding.parseColor(
                globalSettings != null ? globalSettings.getPdfWatermarkColor() : null)));
        pdfWatermarkColorPicker.disableProperty().bind(pdfWatermarkEnabledCheck.selectedProperty().not());
        exportGrid.add(new Label(I18n.get("settings.export.watermarkColor")), 0, exportRow);
        exportGrid.add(pdfWatermarkColorPicker, 1, exportRow++);

        Label watermarkInfo = new Label(I18n.get("settings.export.watermark.info"));
        watermarkInfo.setWrapText(true);
        watermarkInfo.setMaxWidth(360);
        watermarkInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        exportGrid.add(watermarkInfo, 1, exportRow++);

        exportGrid.add(new Separator(), 0, exportRow++, 2, 1);

        exportFooterEnabledCheck = new CheckBox(I18n.get("settings.export.footer"));
        exportFooterEnabledCheck.setSelected(globalSettings == null || globalSettings.isExportFooterEnabled());
        exportGrid.add(exportFooterEnabledCheck, 0, exportRow++, 2, 1);

        exportFooterTextField = new TextField();
        exportFooterTextField.setPrefWidth(360);
        exportFooterTextField.setPromptText(de.kortty.core.ExportBranding.defaultFooterText());
        exportFooterTextField.setText(globalSettings != null && globalSettings.getExportFooterText() != null
            ? globalSettings.getExportFooterText() : "");
        exportFooterTextField.disableProperty().bind(exportFooterEnabledCheck.selectedProperty().not());
        exportGrid.add(new Label(I18n.get("settings.export.footerText")), 0, exportRow);
        exportGrid.add(exportFooterTextField, 1, exportRow++);

        Label footerInfo = new Label(I18n.get("settings.export.footer.info"));
        footerInfo.setWrapText(true);
        footerInfo.setMaxWidth(360);
        footerInfo.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        exportGrid.add(footerInfo, 1, exportRow++);

        exportTab.setContent(exportGrid);

        // Updates tab
        Tab updatesTab = new Tab(I18n.get("settings.tab.updates"));
        GridPane updatesGrid = new GridPane();
        updatesGrid.setHgap(10);
        updatesGrid.setVgap(10);
        updatesGrid.setPadding(new Insets(20));
        int updatesRow = 0;

        Label updatesHeader = new Label(I18n.get("settings.updates.header"));
        updatesHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        updatesGrid.add(updatesHeader, 0, updatesRow++, 2, 1);

        updateChecksEnabledCheck = new CheckBox(I18n.get("settings.updates.automatic"));
        updateChecksEnabledCheck.setSelected(globalSettings == null || globalSettings.isUpdateChecksEnabled());
        updateChecksEnabledCheck.setTooltip(new Tooltip(I18n.get("settings.updates.automatic.tooltip")));
        de.kortty.policy.PolicyUiSupport.lockIfManaged(
            updateChecksEnabledCheck, de.kortty.policy.ManagedSetting.UPDATES);
        updatesGrid.add(updateChecksEnabledCheck, 0, updatesRow++, 2, 1);

        updateCheckIntervalSlider = new Slider(
            GlobalSettings.MIN_UPDATE_CHECK_INTERVAL_DAYS,
            GlobalSettings.MAX_UPDATE_CHECK_INTERVAL_DAYS,
            globalSettings != null
                ? globalSettings.getUpdateCheckIntervalDays()
                : GlobalSettings.DEFAULT_UPDATE_CHECK_INTERVAL_DAYS);
        updateCheckIntervalSlider.setMajorTickUnit(1);
        updateCheckIntervalSlider.setMinorTickCount(0);
        updateCheckIntervalSlider.setBlockIncrement(1);
        updateCheckIntervalSlider.setSnapToTicks(true);
        updateCheckIntervalSlider.setShowTickMarks(true);
        updateCheckIntervalSlider.setPrefWidth(360);
        updateCheckIntervalValueLabel = new Label();
        updateCheckIntervalValueLabel.setMinWidth(140);
        updateUpdateIntervalLabel();
        updateCheckIntervalSlider.valueProperty().addListener((obs, oldValue, newValue) -> {
            updateCheckIntervalSlider.setValue(Math.round(newValue.doubleValue()));
            updateUpdateIntervalLabel();
        });
        updateCheckIntervalSlider.disableProperty().bind(updateChecksEnabledCheck.selectedProperty().not());
        updateCheckIntervalValueLabel.disableProperty().bind(updateChecksEnabledCheck.selectedProperty().not());

        HBox updateIntervalBox = new HBox(12, updateCheckIntervalSlider, updateCheckIntervalValueLabel);
        updateIntervalBox.setAlignment(Pos.CENTER_LEFT);
        updatesGrid.add(new Label(I18n.get("settings.updates.interval")), 0, updatesRow);
        updatesGrid.add(updateIntervalBox, 1, updatesRow++);

        Label updatesInfoLabel = new Label(I18n.get("settings.updates.info"));
        updatesInfoLabel.setWrapText(true);
        updatesInfoLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        updatesGrid.add(updatesInfoLabel, 0, updatesRow++, 2, 1);

        updatesTab.setContent(updatesGrid);
        
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

        openToolWindowsAsTabsCheck = new CheckBox(I18n.get("settings.window.toolWindowTabs"));
        openToolWindowsAsTabsCheck.setSelected(globalSettings != null && globalSettings.isOpenToolWindowsAsTabs());
        openToolWindowsAsTabsCheck.setTooltip(new Tooltip(I18n.get("settings.window.toolWindowTabs.tooltip")));
        windowGrid.add(openToolWindowsAsTabsCheck, 0, windowRow++, 2, 1);

        Label toolWindowTabsInfoLabel = new Label(I18n.get("settings.window.toolWindowTabs.info"));
        toolWindowTabsInfoLabel.setStyle("-fx-font-size: 10px; -fx-text-fill: gray;");
        windowGrid.add(toolWindowTabsInfoLabel, 0, windowRow++, 2, 1);

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
        de.kortty.policy.PolicyUiSupport.lockIfManaged(
            requireMasterPasswordOnStartupCheck, de.kortty.policy.ManagedSetting.MASTER_PASSWORD);
        
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

        // Skip the startup prompt entirely and auto-unlock from a remembered password.
        // INSECURE — for throwaway/test environments (e.g. a VM) only.
        skipMasterPasswordPromptCheck = new CheckBox(I18n.get("settings.security.masterPassword.skipPrompt"));
        skipMasterPasswordPromptCheck.setSelected(globalSettings != null && globalSettings.isSkipMasterPasswordPrompt());
        skipMasterPasswordPromptCheck.setTooltip(new Tooltip(I18n.get("settings.security.masterPassword.skipPrompt.tooltip")));
        de.kortty.policy.PolicyUiSupport.lockIfManaged(
            skipMasterPasswordPromptCheck, de.kortty.policy.ManagedSetting.MASTER_PASSWORD);

        Label skipPromptWarningLabel = new Label(I18n.get("settings.security.masterPassword.skipPrompt.warning"));
        skipPromptWarningLabel.setWrapText(true);
        skipPromptWarningLabel.setMaxWidth(640);
        skipPromptWarningLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: #ff6b6b; -fx-font-weight: bold;");
        skipPromptWarningLabel.setVisible(skipMasterPasswordPromptCheck.isSelected());

        skipMasterPasswordPromptCheck.selectedProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal) {
                // Require an explicit confirmation before enabling the insecure mode.
                Alert confirm = new Alert(Alert.AlertType.WARNING);
                confirm.setTitle(I18n.get("settings.security.masterPassword.skipPrompt.confirm.title"));
                confirm.setHeaderText(I18n.get("settings.security.masterPassword.skipPrompt.confirm.header"));
                confirm.setContentText(I18n.get("settings.security.masterPassword.skipPrompt.confirm.content"));
                ButtonType enableAnyway = new ButtonType(
                    I18n.get("settings.security.masterPassword.skipPrompt.confirm.enable"), ButtonBar.ButtonData.OK_DONE);
                confirm.getButtonTypes().setAll(enableAnyway, ButtonType.CANCEL);
                if (getDialogPane().getScene() != null) {
                    confirm.initOwner(getDialogPane().getScene().getWindow());
                }
                if (confirm.showAndWait().orElse(ButtonType.CANCEL) != enableAnyway) {
                    skipMasterPasswordPromptCheck.setSelected(false);
                    return;
                }
                // "skip" and "require on startup" are mutually exclusive; skip wins.
                requireMasterPasswordOnStartupCheck.setSelected(false);
            }
            requireMasterPasswordOnStartupCheck.setDisable(newVal);
            skipPromptWarningLabel.setVisible(newVal);
            // The "must enter manually" warning is irrelevant while auto-unlock is active.
            masterPasswordWarningLabel.setVisible(!newVal && !requireMasterPasswordOnStartupCheck.isSelected());
        });
        // Match the initial enable/disable state to a persisted "skip" selection.
        if (skipMasterPasswordPromptCheck.isSelected()) {
            requireMasterPasswordOnStartupCheck.setDisable(true);
            masterPasswordWarningLabel.setVisible(false);
        }

        securityGrid.add(skipMasterPasswordPromptCheck, 0, securityRow++, 2, 1);
        securityGrid.add(skipPromptWarningLabel, 0, securityRow++, 2, 1);

        // Temporary SSH key (Connection Manager + Quick Connect)
        securityGrid.add(new Separator(), 0, securityRow++, 2, 1);
        temporarySshKeyEnabledCheck = new CheckBox(I18n.get("settings.security.temporarySshKeyEnabled"));
        temporarySshKeyEnabledCheck.setSelected(globalSettings != null && globalSettings.isTemporarySshKeyEnabled());
        temporarySshKeyEnabledCheck.setTooltip(new Tooltip(I18n.get("settings.security.temporarySshKeyEnabled.tooltip")));
        securityGrid.add(temporarySshKeyEnabledCheck, 0, securityRow++, 2, 1);
        
        securityTab.setContent(securityGrid);

        // Privacy tab (anonymous usage statistics: opt-in, EU servers, GDPR)
        Tab privacyTab = new Tab(I18n.get("settings.tab.privacy"));
        GridPane privacyGrid = new GridPane();
        privacyGrid.setHgap(10);
        privacyGrid.setVgap(10);
        privacyGrid.setPadding(new Insets(20));

        int privacyRow = 0;

        Label privacyHeader = new Label(I18n.get("settings.telemetry.header"));
        privacyHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        privacyGrid.add(privacyHeader, 0, privacyRow++, 2, 1);

        Label privacySummaryLabel = new Label(I18n.get("settings.telemetry.summary"));
        privacySummaryLabel.setWrapText(true);
        privacySummaryLabel.setMaxWidth(640);
        privacySummaryLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        privacyGrid.add(privacySummaryLabel, 0, privacyRow++, 2, 1);

        telemetryEnabledCheck = new CheckBox(I18n.get("settings.telemetry.enable"));
        telemetryEnabledCheck.setSelected(globalSettings != null && globalSettings.isTelemetryEnabled());
        de.kortty.policy.PolicyUiSupport.lockIfManaged(
            telemetryEnabledCheck, de.kortty.policy.ManagedSetting.TELEMETRY);
        telemetryEnabledCheck.setTooltip(new Tooltip(I18n.get("settings.telemetry.enable.tooltip")));
        Button telemetryLearnMoreButton = new Button("?");
        telemetryLearnMoreButton.setMinWidth(Region.USE_PREF_SIZE);
        telemetryLearnMoreButton.setTooltip(new Tooltip(I18n.get("settings.telemetry.learnMore.tooltip")));
        telemetryLearnMoreButton.setOnAction(e -> {
            try {
                GuideViewer.show(app, getDialogPane().getScene().getWindow(), de.kortty.telemetry.TelemetryEvents.GUIDE_LOCATION);
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(getClass())
                    .warn("Could not open the guide chapter on anonymous data", ex);
            }
        });
        HBox telemetryEnableRow = new HBox(8, telemetryEnabledCheck, telemetryLearnMoreButton);
        telemetryEnableRow.setAlignment(Pos.CENTER_LEFT);
        privacyGrid.add(telemetryEnableRow, 0, privacyRow++, 2, 1);

        privacyGrid.add(new Separator(), 0, privacyRow++, 2, 1);

        Label telemetryCollectedLabel = new Label(I18n.get("settings.telemetry.collected"));
        telemetryCollectedLabel.setWrapText(true);
        telemetryCollectedLabel.setMaxWidth(640);
        telemetryCollectedLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        privacyGrid.add(telemetryCollectedLabel, 0, privacyRow++, 2, 1);

        Label telemetryNotCollectedLabel = new Label(I18n.get("settings.telemetry.notCollected"));
        telemetryNotCollectedLabel.setWrapText(true);
        telemetryNotCollectedLabel.setMaxWidth(640);
        telemetryNotCollectedLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        privacyGrid.add(telemetryNotCollectedLabel, 0, privacyRow++, 2, 1);

        String telemetryConsentDate = globalSettings != null ? globalSettings.getTelemetryConsentDate() : null;
        if (telemetryConsentDate != null && globalSettings.getTelemetryConsentVersion() > 0) {
            Label telemetryDecisionLabel =
                new Label(I18n.get("settings.telemetry.decisionDate", formatConsentDate(telemetryConsentDate)));
            telemetryDecisionLabel.setWrapText(true);
            telemetryDecisionLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
            privacyGrid.add(telemetryDecisionLabel, 0, privacyRow++, 2, 1);
        }

        privacyTab.setContent(privacyGrid);

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
            TranslationApiProvider.YANDEX,
            TranslationApiProvider.LOCAL_AI_PROFILE
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
                    case LOCAL_AI_PROFILE: return I18n.get("settings.translation.provider.localAi");
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
        // Which AI profile translates the interface strings. The guide section below has the same
        // choice; without it here the interface strings were stuck on the default text profile and
        // only when that profile ran a local model — a cloud or CLI profile produced nothing but
        // "failed to generate language file".
        translationGrid.add(new Label(I18n.get("settings.translation.profile")), 0, transRow);
        interfaceAiProfileCombo = new ComboBox<>();
        interfaceAiProfileCombo.setPrefWidth(320);
        interfaceAiProfileCombo.setConverter(aiProfileChoiceConverter(
            "settings.translation.profileDefault"));
        translationGrid.add(interfaceAiProfileCombo, 1, transRow++);
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
        translationProviderCombo.valueProperty().addListener((obs, oldProvider, newProvider) -> {
            boolean localAi = newProvider == TranslationApiProvider.LOCAL_AI_PROFILE;
            translationApiKeyField.setDisable(localAi);
            translationApiUrlField.setDisable(localAi);
            // Inverse of the key/url fields: the profile choice only means anything for the AI provider.
            interfaceAiProfileCombo.setDisable(!localAi);
        });
        boolean localAiTranslation = translationProviderCombo.getValue() == TranslationApiProvider.LOCAL_AI_PROFILE;
        translationApiKeyField.setDisable(localAiTranslation);
        translationApiUrlField.setDisable(localAiTranslation);
        interfaceAiProfileCombo.setDisable(!localAiTranslation);
        refreshAiProfileCombo(interfaceAiProfileCombo);
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

        // Guide translation. Uses the provider and target language selected above, but is a
        // separate action: the interface strings take seconds, the guide takes hours on a local
        // model, so the two must not share one button.
        translationGrid.add(new javafx.scene.control.Separator(), 0, transRow, 2, 1);
        transRow++;
        Label guideSectionLabel = new Label(I18n.get("settings.translation.guide.section"));
        guideSectionLabel.setStyle("-fx-font-weight: bold;");
        translationGrid.add(guideSectionLabel, 0, transRow++, 2, 1);
        Label guideHint = new Label(I18n.get("settings.translation.guide.hint"));
        guideHint.setWrapText(true);
        // prefWidth, not maxWidth: inside a GridPane cell the label reports the full text as its
        // preferred width and is then clipped to one ellipsised line, so the warning about the
        // multi-hour runtime — the part users most need to read — never appears.
        guideHint.setPrefWidth(520);
        guideHint.setMinHeight(Region.USE_PREF_SIZE);
        guideHint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        translationGrid.add(guideHint, 1, transRow++);

        Button guideGenerateButton = new Button(I18n.get("settings.translation.guide.generate"));
        guideTranslationCancelButton = new Button(I18n.get("settings.translation.guide.cancel"));
        guideTranslationCancelButton.setVisible(false);
        guideTranslationProgress = new javafx.scene.control.ProgressBar(0);
        guideTranslationProgress.setPrefWidth(160);
        guideTranslationProgress.setVisible(false);
        guideTranslationStatusLabel = new Label();
        guideTranslationStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        // Which AI profile does the work. Without this the guide is stuck on the default text
        // profile, so a user could neither benchmark a second model nor point the run at the one
        // they actually want spending the next few hours on.
        translationGrid.add(new Label(I18n.get("settings.translation.guide.profile")), 0, transRow);
        guideAiProfileCombo = new ComboBox<>();
        guideAiProfileCombo.setPrefWidth(320);
        guideAiProfileCombo.setConverter(aiProfileChoiceConverter(
            "settings.translation.guide.profileDefault"));
        refreshAiProfileCombo(guideAiProfileCombo);
        translationGrid.add(guideAiProfileCombo, 1, transRow++);

        Button guideEstimateButton = new Button(I18n.get("settings.translation.guide.estimate"));
        guideEstimateButton.setOnAction(ev ->
            estimateGuideTranslation(guideEstimateButton, guideGenerateButton));
        guideGenerateButton.setOnAction(ev -> generateGuideTranslation(guideGenerateButton));
        HBox guideActionBox = new HBox(10, guideEstimateButton, guideGenerateButton,
            guideTranslationProgress, guideTranslationCancelButton);
        guideActionBox.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        translationGrid.add(new VBox(4, guideActionBox, guideTranslationStatusLabel), 1, transRow++);

        translationGrid.add(new Label(I18n.get("settings.translation.guide.generated")), 0, transRow);
        guideTranslationList = new ListView<>();
        guideTranslationList.setPrefHeight(90);
        guideTranslationList.setCellFactory(lv -> new ListCell<String>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null
                    : Locale.forLanguageTag(item).getDisplayLanguage() + " (" + item + ")");
            }
        });
        Button guideDeleteButton = new Button(I18n.get("settings.translation.delete"));
        guideDeleteButton.setOnAction(e -> deleteSelectedGuideTranslation(owner));
        translationGrid.add(new VBox(5, guideTranslationList, guideDeleteButton), 1, transRow++);
        refreshGuideTranslationList();

        translationTab.setContent(translationGrid);

        // AI tab
        Tab aiTab = new Tab(I18n.get("settings.tab.ai"));
        VBox aiRoot = new VBox(12);
        aiRoot.setPadding(new Insets(20));
        if (de.kortty.policy.PolicyUiSupport.anyManaged(
                de.kortty.policy.ManagedSetting.AI_FEATURES,
                de.kortty.policy.ManagedSetting.AGENT_EXECUTION,
                de.kortty.policy.ManagedSetting.AGENT_CONFIRM_MUTATING,
                de.kortty.policy.ManagedSetting.AI_PROFILES,
                de.kortty.policy.ManagedSetting.AI_RUNTIME)) {
            aiRoot.getChildren().add(de.kortty.policy.PolicyUiSupport.managedTabBanner());
        }

        aiFeaturesEnabledCheck = new CheckBox(I18n.get("settings.ai.featuresEnabled"));
        aiFeaturesEnabledCheck.setStyle("-fx-font-weight: bold;");
        aiFeaturesEnabledCheck.setSelected(globalSettings == null || globalSettings.isAiFeaturesEnabled());
        de.kortty.policy.PolicyUiSupport.lockIfManaged(
            aiFeaturesEnabledCheck, de.kortty.policy.ManagedSetting.AI_FEATURES);
        aiRoot.getChildren().add(aiFeaturesEnabledCheck);

        Label aiFeaturesHint = new Label(I18n.get("settings.ai.featuresEnabled.hint"));
        aiFeaturesHint.setWrapText(true);
        aiFeaturesHint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        aiRoot.getChildren().add(aiFeaturesHint);

        Label aiInfo = new Label(I18n.get("settings.ai.info"));
        aiInfo.setWrapText(true);
        aiInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        aiRoot.getChildren().add(aiInfo);

        aiConfirmBeforeSendCheck = new CheckBox(I18n.get("settings.ai.confirmBeforeSend"));
        aiConfirmBeforeSendCheck.setSelected(globalSettings == null || globalSettings.isAiConfirmBeforeSend());
        aiRoot.getChildren().add(aiConfirmBeforeSendCheck);

        aiTerminalAgentExecutionEnabledCheck = new CheckBox(I18n.get("settings.ai.terminalAgentExecutionEnabled"));
        aiTerminalAgentExecutionEnabledCheck.setSelected(globalSettings == null || globalSettings.isTerminalAgentExecutionEnabled());
        de.kortty.policy.PolicyUiSupport.lockIfManaged(
            aiTerminalAgentExecutionEnabledCheck, de.kortty.policy.ManagedSetting.AGENT_EXECUTION);
        aiRoot.getChildren().add(aiTerminalAgentExecutionEnabledCheck);

        Label aiTerminalAgentExecutionHint = new Label(I18n.get("settings.ai.terminalAgentExecutionEnabled.hint"));
        aiTerminalAgentExecutionHint.setWrapText(true);
        aiTerminalAgentExecutionHint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        aiRoot.getChildren().add(aiTerminalAgentExecutionHint);

        aiTerminalAgentConfirmMutatingCommandSetsCheck =
            new CheckBox(I18n.get("settings.ai.terminalAgentConfirmMutatingCommandSets"));
        aiTerminalAgentConfirmMutatingCommandSetsCheck.setSelected(
            globalSettings != null && globalSettings.isTerminalAgentConfirmMutatingCommandSets());
        de.kortty.policy.PolicyUiSupport.lockIfManaged(
            aiTerminalAgentConfirmMutatingCommandSetsCheck,
            de.kortty.policy.ManagedSetting.AGENT_CONFIRM_MUTATING);
        aiRoot.getChildren().add(aiTerminalAgentConfirmMutatingCommandSetsCheck);

        Label aiTerminalAgentConfirmMutatingHint =
            new Label(I18n.get("settings.ai.terminalAgentConfirmMutatingCommandSets.hint"));
        aiTerminalAgentConfirmMutatingHint.setWrapText(true);
        aiTerminalAgentConfirmMutatingHint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        aiRoot.getChildren().add(aiTerminalAgentConfirmMutatingHint);

        aiPromptHookEnabledCheck = new CheckBox(I18n.get("settings.ai.promptHook"));
        aiPromptHookEnabledCheck.setSelected(globalSettings == null || globalSettings.isDefaultPromptHookEnabled());
        aiRoot.getChildren().add(aiPromptHookEnabledCheck);

        aiShowDebugMessagesCheck = new CheckBox(I18n.get("settings.ai.showDebugMessages"));
        aiShowDebugMessagesCheck.setSelected(globalSettings != null && globalSettings.isTerminalAgentShowDebugMessages());
        aiRoot.getChildren().add(aiShowDebugMessagesCheck);

        aiShowRuntimeMessagesCheck = new CheckBox(I18n.get("settings.ai.showRuntimeMessages"));
        aiShowRuntimeMessagesCheck.setSelected(globalSettings != null && globalSettings.isTerminalAgentShowRuntimeMessages());
        aiRoot.getChildren().add(aiShowRuntimeMessagesCheck);

        aiTerminalAgentShowRunDialogCheck = new CheckBox(I18n.get("settings.ai.terminalAgentShowRunDialog"));
        aiTerminalAgentShowRunDialogCheck.setSelected(globalSettings == null || globalSettings.isTerminalAgentShowRunDialog());
        aiRoot.getChildren().add(aiTerminalAgentShowRunDialogCheck);

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

        aiAgentCommandNameCaseInsensitiveCheck = new CheckBox(I18n.get("settings.ai.agentCommandNameCaseInsensitive"));
        aiAgentCommandNameCaseInsensitiveCheck.setSelected(
            globalSettings != null && globalSettings.isTerminalAgentCommandNameCaseInsensitive());
        aiRoot.getChildren().add(aiAgentCommandNameCaseInsensitiveCheck);

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

        aiDefaultProfileCombo = new ComboBox<>();
        aiDefaultProfileCombo.setPrefWidth(260);
        aiDefaultProfileCombo.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : getAiProfileDisplayName(item));
            }
        });
        aiDefaultProfileCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : getAiProfileDisplayName(item));
            }
        });
        refreshDefaultAiProfileSelection(globalSettings != null ? globalSettings.getDefaultAiProfileId() : null);
        HBox aiDefaultProfileBox = new HBox(10,
            new Label(I18n.get("settings.ai.defaultProfile")),
            aiDefaultProfileCombo);
        Label aiDefaultProfileHint = new Label(I18n.get("settings.ai.defaultProfile.hint"));
        aiDefaultProfileHint.setWrapText(true);
        aiDefaultProfileHint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        aiRoot.getChildren().addAll(aiDefaultProfileBox, aiDefaultProfileHint);

        aiSecurityCheckProfileCombo = new ComboBox<>();
        aiSecurityCheckProfileCombo.setPrefWidth(260);
        aiSecurityCheckProfileCombo.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : getAiProfileDisplayName(item));
            }
        });
        aiSecurityCheckProfileCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AiProfile item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : getAiProfileDisplayName(item));
            }
        });
        aiSecurityCheckProfileCombo.setPromptText(I18n.get("settings.ai.securityProfile.default"));
        refreshSecurityCheckProfileSelection(globalSettings != null ? globalSettings.getSecurityCheckAiProfileId() : null);
        Button aiSecurityCheckProfileClear = new Button(I18n.get("settings.ai.securityProfile.clear"));
        aiSecurityCheckProfileClear.setOnAction(event -> aiSecurityCheckProfileCombo.getSelectionModel().clearSelection());
        HBox aiSecurityCheckProfileBox = new HBox(10,
            new Label(I18n.get("settings.ai.securityProfile")),
            aiSecurityCheckProfileCombo,
            aiSecurityCheckProfileClear);
        Label aiSecurityCheckProfileHint = new Label(I18n.get("settings.ai.securityProfile.hint"));
        aiSecurityCheckProfileHint.setWrapText(true);
        aiSecurityCheckProfileHint.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        aiRoot.getChildren().addAll(aiSecurityCheckProfileBox, aiSecurityCheckProfileHint);

        aiCodeTextLanguageCombo = new ComboBox<>();
        aiCodeTextLanguageCombo.getItems().setAll(
            AiLanguageSupport.buildAvailableLanguageOptions(globalSettings != null ? globalSettings.getAiCodeTextDefaultLanguage() : null));
        aiCodeTextLanguageCombo.setPrefWidth(260);
        aiCodeTextLanguageCombo.setCellFactory(listView -> new ListCell<>() {
            @Override
            protected void updateItem(AiLanguageSupport.LanguageOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        });
        aiCodeTextLanguageCombo.setButtonCell(new ListCell<>() {
            @Override
            protected void updateItem(AiLanguageSupport.LanguageOption item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? "" : item.label());
            }
        });
        AiLanguageSupport.LanguageOption selectedCodeTextLanguage = AiLanguageSupport.findOption(
            aiCodeTextLanguageCombo.getItems(),
            globalSettings != null ? globalSettings.getAiCodeTextDefaultLanguage() : null);
        if (selectedCodeTextLanguage != null && !aiCodeTextLanguageCombo.getItems().contains(selectedCodeTextLanguage)) {
            aiCodeTextLanguageCombo.getItems().add(selectedCodeTextLanguage);
        }
        aiCodeTextLanguageCombo.getSelectionModel().select(selectedCodeTextLanguage);
        HBox aiCodeTextLanguageBox = new HBox(10,
            new Label(I18n.get("settings.ai.codeTextLanguage")),
            aiCodeTextLanguageCombo);
        aiRoot.getChildren().add(aiCodeTextLanguageBox);

        aiSnippetEditorInstructionsCheck = new CheckBox(I18n.get("settings.ai.snippetInstructionsEnabled"));
        aiSnippetEditorInstructionsCheck.setSelected(globalSettings != null && globalSettings.isAiSnippetEditorAdditionalInstructionsEnabled());
        aiRoot.getChildren().add(aiSnippetEditorInstructionsCheck);

        aiSnippetAlternativeSolutionCountSpinner = new Spinner<>(1, 10,
            globalSettings != null ? globalSettings.getAiSnippetAlternativeSolutionCount() : 3);
        aiSnippetAlternativeSolutionCountSpinner.setEditable(true);
        HBox aiSnippetAlternativeCountBox = new HBox(10,
            new Label(I18n.get("settings.ai.alternativeSolutionCount")),
            aiSnippetAlternativeSolutionCountSpinner);
        aiRoot.getChildren().add(aiSnippetAlternativeCountBox);

        terminalAgentInputHistorySizeSpinner = new Spinner<>(5, 100,
            globalSettings != null ? globalSettings.getTerminalAgentInputHistorySize() : 20);
        terminalAgentInputHistorySizeSpinner.setEditable(true);
        HBox terminalAgentInputHistoryBox = new HBox(10,
            new Label(I18n.get("settings.ai.terminalAgentInputHistorySize")),
            terminalAgentInputHistorySizeSpinner);
        aiRoot.getChildren().add(terminalAgentInputHistoryBox);

        GridPane aiInternetGrid = new GridPane();
        aiInternetGrid.setHgap(10);
        aiInternetGrid.setVgap(8);
        int internetRow = 0;

        aiInternetGrid.add(new Label(I18n.get("settings.ai.internet.tavilyKey")), 0, internetRow);
        aiTavilyApiKeyField = new PasswordField();
        aiTavilyApiKeyField.setPrefWidth(280);
        aiTavilyApiKeyField.setPromptText(I18n.get("settings.ai.internet.secretUnchanged"));
        aiClearTavilyApiKeyCheck = new CheckBox(I18n.get("settings.ai.internet.clearSecret"));
        wireSecretClearToggle(aiTavilyApiKeyField, aiClearTavilyApiKeyCheck);
        aiInternetGrid.add(new HBox(8, aiTavilyApiKeyField, aiClearTavilyApiKeyCheck), 1, internetRow++);

        aiInternetGrid.add(new Label(I18n.get("settings.ai.internet.brightDataToken")), 0, internetRow);
        aiBrightDataApiTokenField = new PasswordField();
        aiBrightDataApiTokenField.setPrefWidth(280);
        aiBrightDataApiTokenField.setPromptText(I18n.get("settings.ai.internet.secretUnchanged"));
        aiClearBrightDataApiTokenCheck = new CheckBox(I18n.get("settings.ai.internet.clearSecret"));
        wireSecretClearToggle(aiBrightDataApiTokenField, aiClearBrightDataApiTokenCheck);
        aiInternetGrid.add(new HBox(8, aiBrightDataApiTokenField, aiClearBrightDataApiTokenCheck), 1, internetRow++);

        aiInternetGrid.add(new Label(I18n.get("settings.ai.internet.braveKey")), 0, internetRow);
        aiBraveSearchApiKeyField = new PasswordField();
        aiBraveSearchApiKeyField.setPrefWidth(280);
        aiBraveSearchApiKeyField.setPromptText(I18n.get("settings.ai.internet.secretUnchanged"));
        aiClearBraveSearchApiKeyCheck = new CheckBox(I18n.get("settings.ai.internet.clearSecret"));
        wireSecretClearToggle(aiBraveSearchApiKeyField, aiClearBraveSearchApiKeyCheck);
        aiInternetGrid.add(new HBox(8, aiBraveSearchApiKeyField, aiClearBraveSearchApiKeyCheck), 1, internetRow++);

        aiSearxngUrlField = new TextField(globalSettings != null && globalSettings.getAiSearxngUrl() != null ? globalSettings.getAiSearxngUrl() : "");
        aiInternetGrid.add(new Label(I18n.get("settings.ai.internet.searxngUrl")), 0, internetRow);
        aiInternetGrid.add(aiSearxngUrlField, 1, internetRow++);

        aiTavilyMcpServerLabelField = new TextField(globalSettings != null ? globalSettings.getAiTavilyMcpServerLabel() : "tavily");
        aiInternetGrid.add(new Label(I18n.get("settings.ai.internet.tavilyLabel")), 0, internetRow);
        aiInternetGrid.add(aiTavilyMcpServerLabelField, 1, internetRow++);

        aiBrightDataMcpServerLabelField = new TextField(globalSettings != null ? globalSettings.getAiBrightDataMcpServerLabel() : "bright-data");
        aiInternetGrid.add(new Label(I18n.get("settings.ai.internet.brightDataLabel")), 0, internetRow);
        aiInternetGrid.add(aiBrightDataMcpServerLabelField, 1, internetRow++);

        aiBraveSearchMcpPluginIdField = new TextField(globalSettings != null && globalSettings.getAiBraveSearchMcpPluginId() != null
            ? globalSettings.getAiBraveSearchMcpPluginId()
            : "");
        aiBraveSearchMcpPluginIdField.setPromptText("mcp/<server_label>");
        aiInternetGrid.add(new Label(I18n.get("settings.ai.internet.bravePluginId")), 0, internetRow);
        aiInternetGrid.add(aiBraveSearchMcpPluginIdField, 1, internetRow++);

        aiSearxngMcpPluginIdField = new TextField(globalSettings != null && globalSettings.getAiSearxngMcpPluginId() != null
            ? globalSettings.getAiSearxngMcpPluginId()
            : "");
        aiSearxngMcpPluginIdField.setPromptText("mcp/<server_label>");
        aiInternetGrid.add(new Label(I18n.get("settings.ai.internet.searxngPluginId")), 0, internetRow);
        aiInternetGrid.add(aiSearxngMcpPluginIdField, 1, internetRow++);

        aiLmStudioToolpackMcpPluginIdField = new TextField(globalSettings != null && globalSettings.getAiLmStudioToolpackMcpPluginId() != null
            ? globalSettings.getAiLmStudioToolpackMcpPluginId()
            : "");
        aiLmStudioToolpackMcpPluginIdField.setPromptText("mcp/<server_label>");
        aiInternetGrid.add(new Label(I18n.get("settings.ai.internet.toolpackPluginId")), 0, internetRow);
        aiInternetGrid.add(aiLmStudioToolpackMcpPluginIdField, 1, internetRow++);

        VBox aiInternetBox = new VBox(
            8,
            new Label(I18n.get("settings.ai.internet.configuration")),
            aiInternetGrid);
        aiRoot.getChildren().add(aiInternetBox);

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
                refreshDefaultAiProfileSelection(getSelectedDefaultAiProfileId());
                refreshSecurityCheckProfileSelection(getSelectedSecurityCheckAiProfileId());
            }
        });
        aiEditorGrid.add(aiProfileNameField, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.connectionMode")), 0, aiRow);
        aiConnectionModeCombo = new ComboBox<>();
        aiConnectionModeCombo.getItems().setAll(AiConnectionMode.values());
        if (!MlxPlatform.isSupported()) {
            // MLX runs exclusively on Apple-Silicon macOS; do not offer the mode elsewhere.
            // Existing EMBEDDED_MLX profiles still render via the converter's unavailable hint.
            aiConnectionModeCombo.getItems().remove(AiConnectionMode.EMBEDDED_MLX);
        }
        aiConnectionModeCombo.setPrefWidth(220);
        aiConnectionModeCombo.setConverter(createAiConnectionModeConverter());
        aiConnectionModeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null) {
                selectedAiProfile.setConnectionMode(newValue);
                ensureAiCliDefaults(selectedAiProfile);
                loadAiModelSelection(selectedAiProfile);
                refreshAiReasoningOptions(selectedAiReasoningEffort());
                updateAiConnectionModeUi();
                aiProfileListView.refresh();
            }
        });
        aiEditorGrid.add(aiConnectionModeCombo, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.apiUrl")), 0, aiRow);
        aiApiUrlField = new TextField();
        aiApiUrlField.setPrefWidth(320);
        aiEditorGrid.add(aiApiUrlField, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.cli.provider")), 0, aiRow);
        aiCliProviderCombo = new ComboBox<>();
        aiCliProviderCombo.getItems().setAll(AiCliProviderRegistry.providers());
        aiCliProviderCombo.setPrefWidth(260);
        aiCliProviderCombo.setConverter(createAiCliProviderConverter());
        aiRefreshCliStatusButton = new Button("↻");
        aiRefreshCliStatusButton.setTooltip(new Tooltip(I18n.get("settings.ai.cli.status.refresh")));
        aiRefreshCliStatusButton.setAccessibleText(I18n.get("settings.ai.cli.status.refresh"));
        aiRefreshCliStatusButton.setMinWidth(36);
        aiRefreshCliStatusButton.setOnAction(e -> refreshAiCliStatus());
        HBox aiCliProviderBox = new HBox(6, aiCliProviderCombo, aiRefreshCliStatusButton);
        HBox.setHgrow(aiCliProviderCombo, Priority.ALWAYS);
        aiEditorGrid.add(aiCliProviderBox, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.cli.status")), 0, aiRow);
        aiCliStatusLabel = new Label();
        aiCliStatusLabel.setWrapText(true);
        aiCliStatusLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        aiEditorGrid.add(aiCliStatusLabel, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.cli.executable")), 0, aiRow);
        aiCliExecutableField = new TextField();
        aiCliExecutableField.setPromptText(I18n.get("settings.ai.cli.executable.prompt"));
        aiEditorGrid.add(aiCliExecutableField, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.model")), 0, aiRow);
        aiModelCombo = new ComboBox<>();
        aiModelCombo.setEditable(true);
        ComboBoxEditorSync.install(aiModelCombo);
        aiModelCombo.setPrefWidth(220);
        aiModelCombo.getItems().addAll(AI_MODEL_DEFAULT_LABEL, AI_MODEL_AUTO_LABEL);
        aiRefreshModelsButton = new Button("↻");
        aiRefreshModelsButton.setTooltip(new Tooltip(I18n.get("ai.model.refresh.tooltip")));
        aiRefreshModelsButton.setAccessibleText(I18n.get("ai.model.refresh.accessible"));
        aiRefreshModelsButton.setMinWidth(36);
        aiRefreshModelsButton.setOnAction(e -> refreshLocalAiModels(true));
        HBox aiModelBox = new HBox(6, aiModelCombo, aiRefreshModelsButton);
        HBox.setHgrow(aiModelCombo, Priority.ALWAYS);
        aiEditorGrid.add(aiModelBox, 1, aiRow++);

        aiEmbeddedModelLabel = new Label(I18n.get("settings.ai.embeddedModel"));
        aiEditorGrid.add(aiEmbeddedModelLabel, 0, aiRow);
        aiEmbeddedModelCombo = new ComboBox<>();
        aiEmbeddedModelCombo.setPrefWidth(320);
        aiEmbeddedModelCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(EmbeddedModelChoice model) {
                return model == null ? "" : model.displayName() + " (" + model.id() + ")";
            }

            @Override
            public EmbeddedModelChoice fromString(String string) {
                return null;
            }
        });
        aiEmbeddedModelCombo.setPromptText(I18n.get("settings.ai.embeddedModel.empty"));
        aiEditorGrid.add(aiEmbeddedModelCombo, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.cli.customModel")), 0, aiRow);
        aiCliCustomModelField = new TextField();
        aiCliCustomModelField.setPromptText(I18n.get("settings.ai.cli.customModel.prompt"));
        aiCliCustomModelField.textProperty().addListener((obs, oldValue, newValue) ->
            refreshAiReasoningOptions(selectedAiReasoningEffort()));
        aiEditorGrid.add(aiCliCustomModelField, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.reasoning")), 0, aiRow);
        aiReasoningCombo = new ComboBox<>();
        aiReasoningCombo.setPrefWidth(220);
        aiReasoningCombo.setConverter(createAiReasoningConverter());
        aiRefreshReasoningButton = new Button("↻");
        aiRefreshReasoningButton.setTooltip(new Tooltip(I18n.get("settings.ai.reasoning.refresh")));
        aiRefreshReasoningButton.setAccessibleText(I18n.get("settings.ai.reasoning.refresh"));
        aiRefreshReasoningButton.setMinWidth(36);
        aiRefreshReasoningButton.setOnAction(e -> refreshAiReasoningFromConnection(aiRefreshReasoningButton));
        aiReasoningCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null) {
                selectedAiProfile.setReasoningEffort(newValue);
            }
        });
        HBox aiReasoningBox = new HBox(6, aiReasoningCombo, aiRefreshReasoningButton);
        HBox.setHgrow(aiReasoningCombo, Priority.ALWAYS);
        aiEditorGrid.add(aiReasoningBox, 1, aiRow++);

        aiApiUrlField.textProperty().addListener((obs, oldValue, newValue) -> {
            refreshAiReasoningOptions(aiReasoningCombo.getValue());
            refreshLocalAiModels(false);
        });
        aiModelCombo.getEditor().textProperty().addListener((obs, oldValue, newValue) ->
            refreshAiReasoningOptions(aiReasoningCombo.getValue()));

        aiEditorGrid.add(new Label(I18n.get("settings.ai.internet.mode")), 0, aiRow);
        aiInternetAccessModeCombo = new ComboBox<>();
        aiInternetAccessModeCombo.getItems().addAll(AiInternetAccessMode.values());
        aiInternetAccessModeCombo.setPrefWidth(260);
        aiInternetAccessModeCombo.setConverter(createAiInternetModeConverter());
        aiInternetAccessModeCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null) {
                selectedAiProfile.setInternetAccessMode(newValue);
                aiProfileListView.refresh();
            }
        });
        aiEditorGrid.add(aiInternetAccessModeCombo, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.apiKey")), 0, aiRow);
        aiApiKeyField = new PasswordField();
        aiApiKeyField.setPrefWidth(280);
        aiApiKeyField.setPromptText(I18n.get("settings.ai.apiKey"));
        aiEditorGrid.add(aiApiKeyField, 1, aiRow++);

        aiClearApiKeyCheck = new CheckBox(I18n.get("settings.ai.clearApiKey"));
        aiEditorGrid.add(aiClearApiKeyCheck, 1, aiRow++);
        aiApiKeyField.textProperty().addListener((obs, oldValue, newValue) -> {
            boolean hasReplacementKey = newValue != null && !newValue.isBlank();
            aiClearApiKeyCheck.setDisable(isAiCliModeSelected() || hasReplacementKey);
            if (hasReplacementKey) {
                aiClearApiKeyCheck.setSelected(false);
            }
        });

        aiEditorGrid.add(new Label(I18n.get("settings.ai.cli.arguments")), 0, aiRow);
        aiCliArgumentsTemplateArea = new TextArea();
        aiCliArgumentsTemplateArea.setPromptText(I18n.get("settings.ai.cli.arguments.prompt"));
        aiCliArgumentsTemplateArea.setPrefRowCount(4);
        aiCliArgumentsTemplateArea.setWrapText(false);
        aiEditorGrid.add(aiCliArgumentsTemplateArea, 1, aiRow++);
        aiCliProviderCombo.valueProperty().addListener((obs, oldValue, newValue) -> {
            if (selectedAiProfile != null && newValue != null) {
                String previousProviderId = oldValue != null ? oldValue.id() : selectedAiProfile.getCliProviderId();
                boolean replaceArgumentTemplate = shouldReplaceAiCliArgumentsTemplateOnProviderChange(
                    previousProviderId,
                    selectedAiProfile.getCliArgumentsTemplate(),
                    aiCliArgumentsTemplateArea.getText());
                selectedAiProfile.setCliProviderId(newValue.id());
                ensureAiCliDefaults(selectedAiProfile);
                if (replaceArgumentTemplate) {
                    aiCliArgumentsTemplateArea.setText(selectedAiProfile.getCliArgumentsTemplate() != null
                        ? selectedAiProfile.getCliArgumentsTemplate()
                        : "");
                }
                loadAiModelSelection(selectedAiProfile);
                refreshAiReasoningOptions(selectedAiReasoningEffort());
                refreshAiCliStatus();
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

        aiEditorGrid.add(new Label(I18n.get("settings.ai.timeout.global")), 0, aiRow);
        aiGlobalRequestTimeoutSpinner = new Spinner<>(
            0,
            AiRequestTimeoutSupport.MAX_TIMEOUT_MINUTES,
            globalSettings != null ? globalSettings.getAiRequestTimeoutMinutes() : 0);
        aiGlobalRequestTimeoutSpinner.setEditable(true);
        aiGlobalRequestTimeoutSpinner.setPrefWidth(110);
        aiGlobalRequestTimeoutSpinner.setTooltip(new Tooltip(I18n.get("settings.ai.timeout.global.tooltip")));
        Label aiGlobalTimeoutHint = new Label(I18n.get("settings.ai.timeout.hint"));
        aiGlobalTimeoutHint.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-inner-color;");
        HBox aiGlobalTimeoutBox = new HBox(6, aiGlobalRequestTimeoutSpinner, aiGlobalTimeoutHint);
        aiGlobalTimeoutBox.setAlignment(Pos.CENTER_LEFT);
        aiEditorGrid.add(aiGlobalTimeoutBox, 1, aiRow++);

        aiEditorGrid.add(new Label(I18n.get("settings.ai.timeout.profile")), 0, aiRow);
        aiRequestTimeoutOverrideCheck = new CheckBox(I18n.get("settings.ai.timeout.profile.override"));
        aiRequestTimeoutSpinner = new Spinner<>(0, AiRequestTimeoutSupport.MAX_TIMEOUT_MINUTES, 0);
        aiRequestTimeoutSpinner.setEditable(true);
        aiRequestTimeoutSpinner.setPrefWidth(110);
        // Without the override the profile follows the global value, so an enabled spinner would
        // only show a number that has no effect.
        aiRequestTimeoutSpinner.disableProperty().bind(aiRequestTimeoutOverrideCheck.selectedProperty().not());
        Tooltip aiProfileTimeoutTooltip = new Tooltip(I18n.get("settings.ai.timeout.profile.tooltip"));
        aiRequestTimeoutOverrideCheck.setTooltip(aiProfileTimeoutTooltip);
        aiRequestTimeoutSpinner.setTooltip(aiProfileTimeoutTooltip);
        Label aiProfileTimeoutHint = new Label(I18n.get("settings.ai.timeout.hint"));
        aiProfileTimeoutHint.setStyle("-fx-font-size: 11px; -fx-text-fill: -fx-text-inner-color;");
        HBox aiProfileTimeoutBox = new HBox(
            6, aiRequestTimeoutOverrideCheck, aiRequestTimeoutSpinner, aiProfileTimeoutHint);
        aiProfileTimeoutBox.setAlignment(Pos.CENTER_LEFT);
        aiEditorGrid.add(aiProfileTimeoutBox, 1, aiRow++);

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
        ScrollPane aiScrollPane = new ScrollPane(aiRoot);
        aiScrollPane.setFitToWidth(true);
        aiScrollPane.setFitToHeight(false);
        aiScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        aiScrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        aiTab.setContent(aiScrollPane);

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

        sftpGrid.add(new Separator(), 0, sftpRow++, 2, 1);

        Label rsyncTitle = new Label(I18n.get("settings.sftp.rsyncTitle"));
        rsyncTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        sftpGrid.add(rsyncTitle, 0, sftpRow++, 2, 1);

        Label rsyncBinaryLabel = new Label(I18n.get("settings.sftp.rsyncBinaryPath"));
        jobSchedulerRsyncBinaryPathField = new TextField(globalSettings.getJobSchedulerRsyncBinaryPath());
        jobSchedulerRsyncBinaryPathField.setPromptText("rsync");
        jobSchedulerRsyncBinaryPathField.setPrefWidth(320);
        Button rsyncBinaryBrowseButton = new Button(I18n.get("connEdit.browse"));
        rsyncBinaryBrowseButton.setOnAction(event -> selectRsyncBinaryPath());
        HBox rsyncBinaryBox = new HBox(10, rsyncBinaryLabel, jobSchedulerRsyncBinaryPathField, rsyncBinaryBrowseButton);
        HBox.setHgrow(jobSchedulerRsyncBinaryPathField, Priority.ALWAYS);
        sftpGrid.add(rsyncBinaryBox, 0, sftpRow++, 2, 1);

        Label rsyncInfo = new Label(I18n.get("settings.sftp.rsyncInfo"));
        rsyncInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");
        rsyncInfo.setWrapText(true);
        rsyncInfo.setMaxWidth(400);
        sftpGrid.add(rsyncInfo, 0, sftpRow++, 2, 1);
        
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
        snippetFontFamilyCombo.getItems().addAll(MonospaceFontFamilies.monospaceFirst());
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

        // Resources tab (opt-in JVM heap/GC profile)
        Tab resourcesTab = createResourcesTab();

        tabPane.getTabs().addAll(fontTab, colorsTab, themesTab, appearanceTab, terminalTab, videoTab, backupTab, loggingTab, exportTab, updatesTab, windowTab, resourcesTab, securityTab, privacyTab, sftpTab, editorTab, snippetEditorTab, languageTab, translationTab, aiTab);
        
        final double defaultContentWidth = 1000;
        final double minimumContentWidth = 860;
        final double defaultContentHeight = 920;
        final double minimumContentHeight = 720;
        final double defaultDialogHeight = 1080;
        final double minimumDialogHeight = 960;

        tabPane.setPrefSize(defaultContentWidth, defaultContentHeight);
        tabPane.setMinSize(minimumContentWidth, minimumContentHeight);
        getDialogPane().setContent(buildSectionNavigationContent(tabPane));
        getDialogPane().setPrefWidth(1120);
        getDialogPane().setMinWidth(1000);
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
                    app.applyLoggingSettings();
                    app.restartUpdateCheckService();
                    if (app.getTelemetryService() != null) {
                        // Enable starts the pipeline; disable discards the queue immediately.
                        app.getTelemetryService().applyEnabledState();
                    }
                    
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

    private String appDesignLabel(AppDesign design) {
        AppDesign resolved = design != null ? design : AppDesign.NORMAL;
        return I18n.get("settings.appearance.design." + toCamelKey(resolved.getId()));
    }

    /** Convert a kebab-case design id (e.g. "amber-crt") into a camelCase i18n key suffix ("amberCrt"). */
    private static String toCamelKey(String id) {
        StringBuilder sb = new StringBuilder(id.length());
        boolean upperNext = false;
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            if (c == '-') {
                upperNext = true;
            } else {
                sb.append(upperNext ? Character.toUpperCase(c) : c);
                upperNext = false;
            }
        }
        return sb.toString();
    }

    private ImageView createAppDesignPreviewImage() {
        ImageView imageView = new ImageView();
        imageView.setFitWidth(440);
        // Cap the height too (preserveRatio keeps it undistorted) so every preview fits inside the
        // fixed-size preview area regardless of the source image's aspect ratio.
        imageView.setFitHeight(APP_DESIGN_PREVIEW_MAX_HEIGHT);
        imageView.setPreserveRatio(true);
        imageView.setSmooth(true);
        return imageView;
    }

    private void updateAppDesignPreview(ImageView previewImage, Label previewPlaceholder,
                                        VBox previewBox, AppDesign design) {
        String previewResource = appDesignPreviewResource(design);
        var previewUrl = previewResource == null ? null : getClass().getResource(previewResource);

        // Only ever swap CONTENT and VISIBILITY here — never anything that changes the layout's
        // size. The image and the placeholder live in a fixed-size StackPane, so toggling their
        // `visible` flags leaves every dimension untouched. That is what stops the surrounding
        // container from skipping a repaint and leaving the preview drawn over the dropdown (or a
        // stale image behind the placeholder when switching back to the imageless Default design).
        if (previewUrl == null) {
            previewImage.setImage(null);
            previewImage.setVisible(false);
            previewPlaceholder.setVisible(true);
            previewBox.setStyle(APP_DESIGN_PREVIEW_NEUTRAL_STYLE);
        } else {
            previewImage.setImage(new Image(previewUrl.toExternalForm()));
            previewImage.setVisible(true);
            previewPlaceholder.setVisible(false);
            previewBox.setStyle(appDesignPreviewStyle(design));
        }
    }

    private String appDesignPreviewResource(AppDesign design) {
        return AppDesignStyleSupport.previewResource(design);
    }

    private String appDesignPreviewStyle(AppDesign design) {
        return "-fx-background-color: " + AppDesignStyleSupport.backgroundColor(design)
            + "; -fx-border-color: " + AppDesignStyleSupport.previewBorderColor(design)
            + "; -fx-border-width: 1;";
    }
    
    /** @return true if save may continue, false to abort (e.g. vault locked and translation API key cannot be encrypted) */
    private boolean applySettings() {
        // Snapshot for the "most changed settings" metric — read from the models
        // before any setter runs, diffed again on the success path.
        java.util.Map<String, Object> trackedSettingsBefore = captureTrackedSettings();

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
        settings.setTerminalColorsEnabled(terminalColorsEnabledCheck.isSelected());
        settings.setEncoding(encodingCombo.getValue());
        settings.setCommandTimestampsEnabled(commandTimestampsCheck.isSelected());
        settings.setSshKeepAliveEnabled(sshKeepAliveCheck.isSelected());
        settings.setSshKeepAliveInterval(sshKeepAliveIntervalSpinner.getValue());
        if (globalSettings != null) {
            globalSettings.setHostKeyCheckDisabledForAllConnections(disableHostKeyCheckAllCheck.isSelected());
        }
        
        // Save connection settings to GlobalSettings
        if (globalSettings != null) {
            globalSettings.setConnectionRetriesEnabled(connectionRetriesEnabledCheck.isSelected());
            globalSettings.setCommandTimestampsEnabled(commandTimestampsCheck.isSelected());
            globalSettings.setApplyThemeFonts(applyThemeFontsProperty.get());
            globalSettings.setAppDesign(appDesignCombo.getValue());
            globalSettings.setAppDesignAnimationsEnabled(appDesignAnimationsCheck.isSelected());
            globalSettings.setChatColorProfileId(
                chatColorProfileCombo.getValue() != null ? chatColorProfileCombo.getValue().id() : null);
        }
        
        // Save backup settings to GlobalSettings
        if (globalSettings != null) {
            globalSettings.setShowTerminalScrollbar(showTerminalScrollbarCheck.isSelected());
            globalSettings.setTerminalDragDropEnabled(terminalDragDropCheck.isSelected());
            globalSettings.setTerminalCopyOnSelectEnabled(terminalCopyOnSelectCheck.isSelected());
            globalSettings.setCloseActiveTerminalWindowsWithoutConfirmation(
                closeActiveTerminalWindowsWithoutConfirmationCheck.isSelected()
            );
            globalSettings.setTerminalRecordingEnabled(terminalRecordingAlwaysEnabledCheck.isSelected());
            globalSettings.setTerminalRecordingCaptureColorsEnabled(terminalRecordingCaptureColorsCheck.isSelected());
            globalSettings.setRequireMasterPasswordOnStartup(requireMasterPasswordOnStartupCheck.isSelected());
            boolean skipPrompt = skipMasterPasswordPromptCheck.isSelected();
            // Only touch the remembered-password file when the option actually changes — or when it
            // is on but no password was stored yet (the vault was still locked the last time).
            MasterPasswordManager mpmForAutoUnlock = app != null ? app.getMasterPasswordManager() : null;
            boolean autoUnlockNeedsUpdate = skipPrompt != globalSettings.isSkipMasterPasswordPrompt()
                || (skipPrompt && mpmForAutoUnlock != null && !mpmForAutoUnlock.hasAutoUnlockPassword());
            globalSettings.setSkipMasterPasswordPrompt(skipPrompt);
            if (autoUnlockNeedsUpdate) {
                applyAutoUnlockPreference(skipPrompt);
            }
            globalSettings.setTemporarySshKeyEnabled(temporarySshKeyEnabledCheck.isSelected());

            // JVM resource profile: persist in GlobalSettings and mirror to the tiny launch file
            // that JvmRelauncher reads at startup. Applied on the next launch (relaunch), not now.
            if (jvmResourceProfileCombo != null && jvmResourceProfileCombo.getValue() != null) {
                JvmResourceProfile chosenProfile = jvmResourceProfileCombo.getValue();
                globalSettings.setJvmResourceProfile(chosenProfile);
                JvmLaunchProfileStore.write(KorTTYApplication.getConfigDirectory(), chosenProfile);
            }

            // Privacy tab: persist the consent decision (date only when the user actually changed it).
            boolean telemetrySelected = telemetryEnabledCheck != null && telemetryEnabledCheck.isSelected();
            boolean telemetryChanged = telemetrySelected != globalSettings.isTelemetryEnabled();
            globalSettings.setTelemetryEnabled(telemetrySelected);
            if (telemetryChanged) {
                globalSettings.setTelemetryConsentVersion(TelemetryService.CURRENT_CONSENT_VERSION);
                globalSettings.setTelemetryConsentDate(java.time.Instant.now().toString());
            }
            if (!saveLoggingSettingsToSettings()) {
                return false;
            }
            globalSettings.setUpdateChecksEnabled(updateChecksEnabledCheck.isSelected());
            globalSettings.setUpdateCheckIntervalDays((int) Math.round(updateCheckIntervalSlider.getValue()));
            
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

            if (!shouldSkipAiValidationOnSave()) {
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
            }

            saveAiToggleFlagsToSettings(globalSettings);
            
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
            globalSettings.setOpenToolWindowsAsTabs(openToolWindowsAsTabsCheck.isSelected());
            
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
            globalSettings.setJobSchedulerRsyncBinaryPath(jobSchedulerRsyncBinaryPathField.getText());
            
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
        trackChangedSettings(trackedSettingsBefore);
        return true;
    }

    // ------------------------------------------------------------------
    // Anonymous usage statistics: "most changed settings" diff
    // ------------------------------------------------------------------

    private static final int MAX_SETTING_CHANGE_EVENTS_PER_APPLY = 25;

    /** One tracked logical setting; {@code sendValue} only for non-sensitive bool/number/enum values. */
    private record TrackedSetting(String tab, String key, java.util.function.Supplier<Object> reader, boolean sendValue) {}

    /** Explicit catalog — deterministic and PII-auditable; extend with one line per setting. */
    private java.util.List<TrackedSetting> trackedSettings() {
        java.util.List<TrackedSetting> tracked = new java.util.ArrayList<>();
        tracked.add(new TrackedSetting("font", "family", settings::getFontFamily, false));
        tracked.add(new TrackedSetting("font", "size", settings::getFontSize, true));
        tracked.add(new TrackedSetting("colors", "theme_id", settings::getThemeId, true));
        tracked.add(new TrackedSetting("colors", "foreground", settings::getForegroundColor, false));
        tracked.add(new TrackedSetting("colors", "background", settings::getBackgroundColor, false));
        tracked.add(new TrackedSetting("colors", "cursor", settings::getCursorColor, false));
        tracked.add(new TrackedSetting("colors", "selection", settings::getSelectionColor, false));
        tracked.add(new TrackedSetting("colors", "bold_as_bright", settings::isBoldAsBright, true));
        tracked.add(new TrackedSetting("colors", "terminal_colors_enabled", settings::isTerminalColorsEnabled, true));
        tracked.add(new TrackedSetting("terminal", "columns", settings::getTerminalColumns, true));
        tracked.add(new TrackedSetting("terminal", "rows", settings::getTerminalRows, true));
        tracked.add(new TrackedSetting("terminal", "scrollback_lines", settings::getScrollbackLines, true));
        tracked.add(new TrackedSetting("terminal", "encoding", settings::getEncoding, true));
        tracked.add(new TrackedSetting("terminal", "command_timestamps", settings::isCommandTimestampsEnabled, true));
        tracked.add(new TrackedSetting("terminal", "cursor_style", settings::getCursorStyle, true));
        tracked.add(new TrackedSetting("terminal", "ssh_keepalive_enabled", settings::isSshKeepAliveEnabled, true));
        tracked.add(new TrackedSetting("terminal", "ssh_keepalive_interval", settings::getSshKeepAliveInterval, true));
        GlobalSettings gs = globalSettings;
        if (gs != null) {
            tracked.add(new TrackedSetting("appearance", "app_design", gs::getAppDesign, true));
            tracked.add(new TrackedSetting("appearance", "animations_enabled", gs::isAppDesignAnimationsEnabled, true));
            tracked.add(new TrackedSetting("appearance", "apply_theme_fonts", gs::isApplyThemeFonts, true));
            tracked.add(new TrackedSetting("terminal", "connection_retries_enabled", gs::isConnectionRetriesEnabled, true));
            tracked.add(new TrackedSetting("terminal", "scrollbar_visible", gs::isShowTerminalScrollbar, true));
            tracked.add(new TrackedSetting("terminal", "drag_drop_enabled", gs::isTerminalDragDropEnabled, true));
            tracked.add(new TrackedSetting("terminal", "copy_on_select", gs::isTerminalCopyOnSelectEnabled, true));
            tracked.add(new TrackedSetting("terminal", "close_without_confirmation",
                gs::isCloseActiveTerminalWindowsWithoutConfirmation, true));
            tracked.add(new TrackedSetting("video", "recording_enabled", gs::isTerminalRecordingEnabled, true));
            tracked.add(new TrackedSetting("video", "capture_colors", gs::isTerminalRecordingCaptureColorsEnabled, true));
            tracked.add(new TrackedSetting("backup", "max_count", gs::getMaxBackupCount, true));
            tracked.add(new TrackedSetting("backup", "encryption_type", gs::getBackupEncryptionType, true));
            tracked.add(new TrackedSetting("logging", "retention_days", gs::getLogRetentionDays, true));
            tracked.add(new TrackedSetting("logging", "directory_customized",
                () -> gs.getLogDirectoryPath() != null && !gs.getLogDirectoryPath().isBlank(), true));
            tracked.add(new TrackedSetting("updates", "checks_enabled", gs::isUpdateChecksEnabled, true));
            tracked.add(new TrackedSetting("updates", "interval_days", gs::getUpdateCheckIntervalDays, true));
            tracked.add(new TrackedSetting("window", "remember_geometry", gs::isRememberWindowGeometry, true));
            tracked.add(new TrackedSetting("window", "remember_dashboard", gs::isRememberDashboardState, true));
            tracked.add(new TrackedSetting("window", "tools_as_tabs", gs::isOpenToolWindowsAsTabs, true));
            tracked.add(new TrackedSetting("window", "fixed_geometry", gs::isUseFixedWindowGeometry, true));
            tracked.add(new TrackedSetting("security", "require_master_password_on_startup",
                gs::isRequireMasterPasswordOnStartup, true));
            tracked.add(new TrackedSetting("security", "temporary_ssh_key_enabled", gs::isTemporarySshKeyEnabled, true));
            tracked.add(new TrackedSetting("language", "ui_language", gs::getLanguage, true));
            tracked.add(new TrackedSetting("translation", "provider", gs::getTranslationApiProvider, true));
            tracked.add(new TrackedSetting("translation", "api_url_set",
                () -> gs.getTranslationApiUrl() != null && !gs.getTranslationApiUrl().isBlank(), true));
            tracked.add(new TrackedSetting("ai", "features_enabled", gs::isAiFeaturesEnabled, true));
            tracked.add(new TrackedSetting("ai", "agent_execution_enabled", gs::isTerminalAgentExecutionEnabled, true));
            tracked.add(new TrackedSetting("ai", "confirm_before_send", gs::isAiConfirmBeforeSend, true));
            tracked.add(new TrackedSetting("sftp", "auto_close_minutes", gs::getSftpAutoCloseMinutes, true));
        }
        return tracked;
    }

    private java.util.Map<String, Object> captureTrackedSettings() {
        java.util.Map<String, Object> values = new java.util.LinkedHashMap<>();
        for (TrackedSetting tracked : trackedSettings()) {
            try {
                values.put(tracked.tab() + "." + tracked.key(), normalizeTrackedValue(tracked.reader().get()));
            } catch (RuntimeException e) {
                // a broken reader must never affect saving
            }
        }
        return values;
    }

    /** Emits one {@code setting_changed} event per changed catalog entry (capped per apply). */
    private void trackChangedSettings(java.util.Map<String, Object> before) {
        try {
            int emitted = 0;
            for (TrackedSetting tracked : trackedSettings()) {
                if (emitted >= MAX_SETTING_CHANGE_EVENTS_PER_APPLY) {
                    break;
                }
                String settingKey = tracked.tab() + "." + tracked.key();
                Object afterValue;
                try {
                    afterValue = normalizeTrackedValue(tracked.reader().get());
                } catch (RuntimeException e) {
                    continue;
                }
                if (java.util.Objects.equals(before.get(settingKey), afterValue)) {
                    continue;
                }
                java.util.Map<String, Object> props = new java.util.LinkedHashMap<>();
                props.put("setting", settingKey);
                props.put("tab", tracked.tab());
                if (tracked.sendValue() && afterValue != null) {
                    props.put("value", afterValue);
                }
                Telemetry.track(TelemetryEvents.SETTING_CHANGED, props);
                emitted++;
            }
        } catch (RuntimeException e) {
            // tracking must never break saving
        }
    }

    /** Booleans/numbers/strings pass; enums become lowercase names; other objects compare by toString. */
    private static Object normalizeTrackedValue(Object value) {
        if (value == null || value instanceof Boolean || value instanceof Number || value instanceof String) {
            return value;
        }
        if (value instanceof Enum<?> enumValue) {
            return enumValue.name().toLowerCase(java.util.Locale.ROOT);
        }
        return value.toString();
    }

    /** Localized display form of the stored ISO-8601 consent instant. */
    private static String formatConsentDate(String isoInstant) {
        try {
            return java.time.format.DateTimeFormatter
                .ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM)
                .withZone(java.time.ZoneId.systemDefault())
                .format(java.time.Instant.parse(isoInstant));
        } catch (RuntimeException e) {
            return isoInstant;
        }
    }

    private void chooseLogDirectory() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(I18n.get("settings.logging.directory.choose"));
        try {
            Path currentDirectory = LoggingConfiguration.resolveLogDirectory(
                logDirectoryPathField.getText(),
                KorTTYApplication.getConfigDirectory());
            if (Files.isDirectory(currentDirectory)) {
                chooser.setInitialDirectory(currentDirectory.toFile());
            }
        } catch (Exception e) {
            // Keep chooser usable even if the typed path is not currently valid.
        }
        File selected = chooser.showDialog(getDialogPane().getScene().getWindow());
        if (selected != null) {
            logDirectoryPathField.setText(selected.getAbsolutePath());
        }
    }

    private boolean saveLoggingSettingsToSettings() {
        String enteredPath = logDirectoryPathField.getText();
        Path logDirectory = LoggingConfiguration.resolveLogDirectory(enteredPath, KorTTYApplication.getConfigDirectory());
        try {
            Files.createDirectories(logDirectory);
            if (!Files.isDirectory(logDirectory) || !Files.isWritable(logDirectory)) {
                showSettingsWarning(I18n.get("settings.logging.directory.error", logDirectory));
                return false;
            }
        } catch (Exception e) {
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            showSettingsWarning(I18n.get("settings.logging.directory.error.detail", logDirectory, message));
            return false;
        }

        Path defaultLogDirectory = LoggingConfiguration.defaultLogDirectory(KorTTYApplication.getConfigDirectory());
        if (logDirectory.equals(defaultLogDirectory)) {
            globalSettings.setLogDirectoryPath(null);
        } else {
            globalSettings.setLogDirectoryPath(logDirectory.toString());
        }
        globalSettings.setLogRetentionDays(logRetentionDaysSpinner.getValue());

        if (sessionJournalStoragePathField != null && !sessionJournalStoragePathField.isDisabled()) {
            globalSettings.setSessionJournalStoragePath(sessionJournalStoragePathField.getText());
        }
        if (sessionJournalAiSummariesCheck != null && !sessionJournalAiSummariesCheck.isDisabled()) {
            globalSettings.setSessionJournalAiSummariesEnabled(sessionJournalAiSummariesCheck.isSelected());
        }
        if (sessionJournalIntervalSpinner != null) {
            globalSettings.setSessionJournalSummarizeIntervalMinutes(sessionJournalIntervalSpinner.getValue());
        }
        if (sessionJournalAiProfileCombo != null) {
            de.kortty.model.AiProfile selectedProfile = sessionJournalAiProfileCombo.getValue();
            globalSettings.setSessionJournalAiProfileId(
                selectedProfile != null ? selectedProfile.getId() : null);
        }

        if (pdfWatermarkEnabledCheck != null) {
            globalSettings.setPdfWatermarkEnabled(pdfWatermarkEnabledCheck.isSelected());
            globalSettings.setPdfWatermarkText(pdfWatermarkTextField.getText());
            globalSettings.setPdfWatermarkColor(de.kortty.core.ExportBranding.toHex(
                toAwtColor(pdfWatermarkColorPicker.getValue())));
        }
        if (exportFooterEnabledCheck != null) {
            globalSettings.setExportFooterEnabled(exportFooterEnabledCheck.isSelected());
            globalSettings.setExportFooterText(exportFooterTextField.getText());
        }
        return true;
    }

    private static javafx.scene.paint.Color toFxColor(java.awt.Color color) {
        java.awt.Color value = color != null ? color : de.kortty.core.ExportBranding.DEFAULT_WATERMARK_COLOR;
        return javafx.scene.paint.Color.rgb(value.getRed(), value.getGreen(), value.getBlue());
    }

    private static java.awt.Color toAwtColor(javafx.scene.paint.Color color) {
        if (color == null) {
            return de.kortty.core.ExportBranding.DEFAULT_WATERMARK_COLOR;
        }
        return new java.awt.Color(
            (int) Math.round(color.getRed() * 255),
            (int) Math.round(color.getGreen() * 255),
            (int) Math.round(color.getBlue() * 255));
    }

    private void showSettingsWarning(String message) {
        Alert alert = new Alert(Alert.AlertType.WARNING, message);
        alert.setHeaderText(null);
        if (getDialogPane().getScene() != null) {
            alert.initOwner(getDialogPane().getScene().getWindow());
        }
        alert.showAndWait();
    }

    private void updateUpdateIntervalLabel() {
        int days = (int) Math.round(updateCheckIntervalSlider.getValue());
        updateCheckIntervalValueLabel.setText(I18n.get("settings.updates.interval.days", days));
    }
    
    private void updatePreviewFont(Label previewLabel) {
        previewLabel.setFont(Font.font(fontFamilyCombo.getValue(), fontSizeSpinner.getValue()));
    }

    /**
     * Wraps the section {@link TabPane} with a slim top navigation bar holding previous/next arrow
     * buttons and a "current / total" position label, so the user can step through the configuration
     * sections without having to click the tab headers. The arrows disable at the first/last section
     * and the label stays in sync with the selected tab.
     */
    private static Region buildSectionNavigationContent(TabPane tabPane) {
        Button previousButton = new Button("◀");
        previousButton.setTooltip(new Tooltip(I18n.get("settings.nav.previous")));
        previousButton.setAccessibleText(I18n.get("settings.nav.previous"));
        previousButton.getStyleClass().add("settings-section-nav-button");
        Button nextButton = new Button("▶");
        nextButton.setTooltip(new Tooltip(I18n.get("settings.nav.next")));
        nextButton.setAccessibleText(I18n.get("settings.nav.next"));
        nextButton.getStyleClass().add("settings-section-nav-button");

        Label positionLabel = new Label();
        positionLabel.getStyleClass().add("settings-section-nav-label");

        Runnable update = () -> {
            int index = tabPane.getSelectionModel().getSelectedIndex();
            int count = tabPane.getTabs().size();
            Tab selected = tabPane.getSelectionModel().getSelectedItem();
            String title = selected != null ? selected.getText() : "";
            positionLabel.setText(SettingsSectionNavigation.positionLabel(title, index, count));
            previousButton.setDisable(!SettingsSectionNavigation.canGoPrevious(index));
            nextButton.setDisable(!SettingsSectionNavigation.canGoNext(index, count));
        };

        previousButton.setOnAction(e -> tabPane.getSelectionModel()
            .select(SettingsSectionNavigation.previous(tabPane.getSelectionModel().getSelectedIndex())));
        nextButton.setOnAction(e -> tabPane.getSelectionModel()
            .select(SettingsSectionNavigation.next(tabPane.getSelectionModel().getSelectedIndex(),
                tabPane.getTabs().size())));
        tabPane.getSelectionModel().selectedIndexProperty().addListener((obs, oldIndex, newIndex) -> update.run());
        tabPane.getTabs().addListener((javafx.collections.ListChangeListener<Tab>) change -> update.run());
        update.run();

        HBox navBar = new HBox(8, previousButton, positionLabel, nextButton);
        navBar.setAlignment(Pos.CENTER);
        navBar.setPadding(new Insets(2, 8, 8, 8));
        navBar.getStyleClass().add("settings-section-nav-bar");

        BorderPane content = new BorderPane(tabPane);
        content.setTop(navBar);
        return content;
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
        final boolean[] initializingThemeSelection = {true};
        VBox.setVgrow(themeList, javafx.scene.layout.Priority.ALWAYS);
        themeList.getSelectionModel().selectedItemProperty().addListener((obs, oldTheme, newTheme) -> {
            if (newTheme != null) {
                selectedGlobalThemeId = newTheme.getId();
                if (!initializingThemeSelection[0]) {
                    applyThemeCursorStyleToCurrentSettings(newTheme);
                }
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
        initializingThemeSelection[0] = false;
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
            ThemeEditDialog dlg = new ThemeEditDialog(getSettingsDialogOwner(owner), null);
            dlg.showAndWait().ifPresent(t -> {
                themeManager.addTheme(t);
                selectedGlobalThemeId = t.getId();
                refreshThemeList(themeList, themeManager, selectedGlobalThemeId);
                refreshColorProfileCombo(themeManager, selectedGlobalThemeId);
            });
        });
        
        editBtn.setOnAction(e -> {
            Theme sel = themeList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Theme copy = copyTheme(sel);
            ThemeEditDialog dlg = new ThemeEditDialog(getSettingsDialogOwner(owner), copy);
            dlg.showAndWait().ifPresent(edited -> {
                themeManager.updateTheme(edited);
                selectedGlobalThemeId = edited.getId();
                refreshThemeList(themeList, themeManager, selectedGlobalThemeId);
                refreshColorProfileCombo(themeManager, selectedGlobalThemeId);
            });
        });
        
        duplicateBtn.setOnAction(e -> {
            Theme sel = themeList.getSelectionModel().getSelectedItem();
            if (sel == null) return;
            Theme dup = copyTheme(sel);
            dup.setId(null);
            dup.setName(sel.getName() + " " + I18n.get("theme.copy"));
            dup.setBuiltIn(false);
            ThemeEditDialog dlg = new ThemeEditDialog(getSettingsDialogOwner(owner), dup);
            dlg.showAndWait().ifPresent(t -> {
                themeManager.addTheme(t);
                selectedGlobalThemeId = t.getId();
                refreshThemeList(themeList, themeManager, selectedGlobalThemeId);
                refreshColorProfileCombo(themeManager, selectedGlobalThemeId);
            });
        });
        
        deleteBtn.setOnAction(e -> {
            Theme sel = themeList.getSelectionModel().getSelectedItem();
            if (sel == null || sel.isBuiltIn()) return;
            Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
            confirm.setTitle(I18n.get("theme.deleteTitle"));
            confirm.setHeaderText(I18n.get("theme.deleteConfirm", sel.getName()));
            Window dialogOwner = getSettingsDialogOwner(owner);
            if (dialogOwner != null) {
                confirm.initOwner(dialogOwner);
            }
            if (confirm.showAndWait().orElse(ButtonType.CANCEL) == ButtonType.OK) {
                String deletedId = sel.getId();
                themeManager.removeTheme(sel.getId());
                if (deletedId != null && deletedId.equals(selectedGlobalThemeId)) {
                    selectedGlobalThemeId = themeManager.getThemes().isEmpty()
                        ? null
                        : themeManager.getThemes().get(0).getId();
                }
                refreshThemeList(themeList, themeManager, selectedGlobalThemeId);
                refreshColorProfileCombo(themeManager, selectedGlobalThemeId);
            }
        });
        
        buttons.getChildren().addAll(addBtn, editBtn, duplicateBtn, deleteBtn);
        vbox.getChildren().addAll(themeList, buttons);
        
        tab.setContent(vbox);
        return tab;
    }

    /**
     * Builds the Resources tab: an opt-in JVM heap/GC profile. The choice is persisted and applied
     * by relaunching the packaged app at the next start (see {@code de.kortty.JvmRelauncher}); the
     * default (Balanced) keeps the shipped 2 GB cap and never relaunches.
     */
    private Tab createResourcesTab() {
        Tab tab = new Tab(I18n.get("settings.tab.resources"));
        tab.setClosable(false);

        VBox vbox = new VBox(12);
        vbox.setPadding(new Insets(20));

        Label header = new Label(I18n.get("settings.resources.header"));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 14px;");
        Label desc = new Label(I18n.get("settings.resources.description"));
        desc.setWrapText(true);
        desc.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        jvmResourceProfileCombo = new ComboBox<>();
        jvmResourceProfileCombo.getItems().addAll(
            JvmResourceProfile.BALANCED, JvmResourceProfile.HIGH, JvmResourceProfile.MAXIMUM);
        jvmResourceProfileCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(JvmResourceProfile profile) {
                return profile == null ? "" : I18n.get("settings.resources.profile." + profile.i18nKey());
            }

            @Override
            public JvmResourceProfile fromString(String value) {
                return null;
            }
        });
        JvmResourceProfile current = globalSettings != null
            ? globalSettings.getJvmResourceProfile() : JvmResourceProfile.BALANCED;
        jvmResourceProfileCombo.setValue(current);

        HBox profileRow = new HBox(10, new Label(I18n.get("settings.resources.profile")), jvmResourceProfileCombo);
        profileRow.setAlignment(Pos.CENTER_LEFT);

        long ramBytes = detectedPhysicalMemoryBytes();
        Label ramLabel = new Label(ramBytes > 0
            ? I18n.get("settings.resources.detectedRam", formatGigabytes(ramBytes))
            : I18n.get("settings.resources.detectedRam.unknown"));
        ramLabel.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        // Live ceiling for the selected profile: how much memory korTTY may use at most.
        Label maxHeapLabel = new Label();
        maxHeapLabel.setWrapText(true);
        maxHeapLabel.setStyle("-fx-font-weight: bold;");

        Label profileDetail = new Label();
        profileDetail.setWrapText(true);
        profileDetail.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        Runnable updateDetail = () -> {
            JvmResourceProfile selected = jvmResourceProfileCombo.getValue();
            if (selected == null) {
                maxHeapLabel.setText("");
                profileDetail.setText("");
                return;
            }
            String heap = formatGigabytes(selected.maxHeapMegabytes(ramBytes) * 1024L * 1024L);
            maxHeapLabel.setText(ramBytes > 0
                ? I18n.get("settings.resources.maxHeap", heap, formatGigabytes(ramBytes))
                : I18n.get("settings.resources.maxHeap.noRam", heap));
            profileDetail.setText(I18n.get("settings.resources.profile." + selected.i18nKey() + ".detail"));
        };
        jvmResourceProfileCombo.valueProperty().addListener((obs, oldV, newV) -> updateDetail.run());
        updateDetail.run();

        Label warn = new Label(I18n.get("settings.resources.warning"));
        warn.setWrapText(true);
        warn.setStyle("-fx-font-size: 11px; -fx-text-fill: derive(-fx-text-inner-color, -20%);");

        Label restartInfo = new Label(I18n.get("settings.resources.restart"));
        restartInfo.setWrapText(true);
        restartInfo.setStyle("-fx-font-size: 11px; -fx-text-fill: gray;");

        vbox.getChildren().addAll(header, desc, profileRow, ramLabel, maxHeapLabel, profileDetail, warn, restartInfo);
        tab.setContent(vbox);
        return tab;
    }

    private static String formatGigabytes(long bytes) {
        double gb = bytes / (1024.0 * 1024.0 * 1024.0);
        return String.format(java.util.Locale.ROOT, "%.1f GB", gb);
    }

    private static long detectedPhysicalMemoryBytes() {
        try {
            var bean = java.lang.management.ManagementFactory.getOperatingSystemMXBean();
            if (bean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
                return sunBean.getTotalMemorySize();
            }
        } catch (Throwable ignored) {
            // fall through
        }
        return 0L;
    }

    private Window getSettingsDialogOwner(Stage fallbackOwner) {
        if (getDialogPane() != null && getDialogPane().getScene() != null) {
            Window window = getDialogPane().getScene().getWindow();
            if (window != null) {
                return window;
            }
        }
        return fallbackOwner;
    }

    private void refreshThemeList(ListView<Theme> themeList, ThemeManager themeManager, String selectedThemeId) {
        if (themeList == null || themeManager == null) {
            return;
        }
        themeList.getItems().setAll(themeManager.getThemes());
        if (selectedThemeId != null && !selectedThemeId.isBlank()) {
            for (Theme theme : themeList.getItems()) {
                if (selectedThemeId.equals(theme.getId())) {
                    themeList.getSelectionModel().select(theme);
                    return;
                }
            }
        }
        if (!themeList.getItems().isEmpty()) {
            themeList.getSelectionModel().selectFirst();
        }
    }

    private void refreshColorProfileCombo(ThemeManager themeManager, String selectedThemeId) {
        if (colorProfileCombo == null || themeManager == null) {
            return;
        }
        colorProfileCombo.getItems().setAll(themeManager.getThemes());
        if (selectedThemeId != null && !selectedThemeId.isBlank()) {
            Theme selected = themeManager.getTheme(selectedThemeId).orElse(null);
            if (selected != null) {
                colorProfileCombo.setValue(selected);
            } else {
                colorProfileCombo.getSelectionModel().clearSelection();
            }
        } else {
            colorProfileCombo.getSelectionModel().clearSelection();
        }
    }

    /**
     * Adopts the selected theme's cursor SHAPE while preserving the user's "Cursor blinks" preference
     * (see the color-profile combo handler): selecting a theme must not reset that toggle.
     */
    private void applyThemeCursorStyleToCurrentSettings(Theme theme) {
        if (theme == null) {
            return;
        }
        boolean blink = cursorBlinkCheck != null
                ? cursorBlinkCheck.isSelected()
                : isCursorBlink(settings.getCursorStyle());
        settings.setCursorStyle(deriveCursorStyle(theme.getCursorStyle(), blink));
    }

    private static Theme copyTheme(Theme source) {
        Theme copy = new Theme();
        if (source == null) {
            return copy;
        }
        copy.setId(source.getId());
        copy.setName(source.getName());
        copy.setFontFamily(source.getFontFamily());
        copy.setFontSize(source.getFontSize());
        copy.setForegroundColor(source.getForegroundColor());
        copy.setBackgroundColor(source.getBackgroundColor());
        copy.setCursorColor(source.getCursorColor());
        copy.setCursorStyle(source.getCursorStyle());
        copy.setAgentPanelBackgroundColor(source.getAgentPanelBackgroundColor());
        copy.setAgentPanelBorderColor(source.getAgentPanelBorderColor());
        copy.setAgentPanelTextColor(source.getAgentPanelTextColor());
        copy.setAgentPanelMutedTextColor(source.getAgentPanelMutedTextColor());
        copy.setAgentPanelAccentColor(source.getAgentPanelAccentColor());
        copy.setAgentPanelErrorColor(source.getAgentPanelErrorColor());
        copy.setBuiltIn(source.isBuiltIn());
        return copy;
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
        Label agentBg = new Label(I18n.get("settings.preview.aiBg"));
        agentBg.setStyle("-fx-text-fill: " + theme.getAgentPanelTextColor() + "; -fx-background-color: " + theme.getAgentPanelBackgroundColor() + "; -fx-padding: 2 8 2 8; -fx-border-color: " + theme.getAgentPanelBorderColor() + "; -fx-border-radius: 4; -fx-background-radius: 4;");
        Label agentAccent = new Label(I18n.get("settings.preview.aiRun"));
        agentAccent.setStyle("-fx-text-fill: " + theme.getAgentPanelBackgroundColor() + "; -fx-background-color: " + theme.getAgentPanelAccentColor() + "; -fx-padding: 2 8 2 8; -fx-background-radius: 4;");
        Label agentError = new Label(I18n.get("settings.preview.aiErr"));
        agentError.setStyle("-fx-text-fill: " + theme.getAgentPanelTextColor() + "; -fx-background-color: " + theme.getAgentPanelErrorColor() + "; -fx-padding: 2 8 2 8; -fx-background-radius: 4;");
        previewSwatches.getChildren().addAll(fg, bg, cursor, agentBg, agentAccent, agentError);
    }
    
    private String toHex(Color color) {
        return String.format("#%02X%02X%02X",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }

    private static boolean isCursorBlink(String cursorStyle) {
        return TerminalCursorStyleSupport.isBlinkingStyle(cursorStyle);
    }

    private static String deriveCursorStyle(String currentStyle, boolean blink) {
        // Stored preference: the saved style must always reflect the checkbox, even when the current
        // style carries a shape this build does not know (which would otherwise be returned unchanged).
        return TerminalCursorStyleSupport.withStoredBlinkingPreference(currentStyle, blink);
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
    
    /** A save operation that may fail, used by {@link #persistStore}. */
    @FunctionalInterface
    private interface StoreSave {
        void run() throws Exception;
    }

    /**
     * Saves one secret store during a master-password change, recording its name in
     * {@code failures} instead of propagating. Aborting the sequence would leave every store after
     * the failing one written with the old password while the rest already use the new one.
     */
    private void persistStore(List<String> failures, String label, StoreSave save) {
        try {
            save.run();
        } catch (Exception e) {
            failures.add(label);
            org.slf4j.LoggerFactory.getLogger(getClass())
                .error("Failed to save {} after the master-password change", label, e);
        }
    }

    /**
     * Persists or removes the remembered master password to match the "skip prompt" setting.
     * When enabling, the currently unlocked password is stored (obfuscated, owner-only); if the
     * vault happens to be locked, the startup flow remembers it after the next unlock. When
     * disabling, any remembered password is deleted so the prompt returns on the next launch.
     */
    private void applyAutoUnlockPreference(boolean skipPrompt) {
        MasterPasswordManager mpm = app != null ? app.getMasterPasswordManager() : null;
        if (mpm == null) {
            return;
        }
        try {
            if (skipPrompt) {
                char[] current = mpm.getMasterPassword();
                if (current != null && current.length > 0) {
                    mpm.saveAutoUnlockPassword(current);
                } else {
                    org.slf4j.LoggerFactory.getLogger(getClass())
                        .info("Auto-unlock enabled but vault is locked; password will be remembered on next unlock");
                }
            } else {
                mpm.clearAutoUnlockPassword();
            }
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .warn("Failed to update the remembered master password", e);
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
                MasterPasswordManager mpm = app.getMasterPasswordManager();

                // Stage the change in memory only. master.key is the authority for which password
                // unlocks the vault, so it is rewritten at the very end (the commit point) — if
                // anything below fails, the old password still matches the data that is on disk.
                MasterPasswordManager.PendingPasswordChange pending =
                    mpm.beginPasswordChange(oldPasswordChars, newPasswordChars);
                boolean committed = false;
                try {
                    // Re-encrypt EVERY master-password-derived secret old->new, then persist each store.
                    // Previously only connection + SSH-key secrets were migrated, silently breaking AI
                    // profile keys, credentials, RAG and Job Scheduler secrets after a password change.
                    MasterPasswordReEncryptor reEncryptor = new MasterPasswordReEncryptor(
                        mpm.getEncryptionService(), oldPasswordChars, newPasswordChars);

                    // In-memory model stores — mutated here, persisted below.
                    reEncryptor.reEncryptConnections(configManager.getConnections());
                    SSHKeyManager sshKeyManager = app.getSSHKeyManager();
                    if (sshKeyManager != null) {
                        reEncryptor.reEncryptSshKeys(sshKeyManager.getAllKeys());
                    }
                    if (credentialManager != null) {
                        reEncryptor.reEncryptCredentials(credentialManager.getAllCredentials());
                    }
                    if (globalSettings != null) {
                        reEncryptor.reEncryptGlobalSettings(globalSettings);
                    }

                    // Self-persisting file / JSON stores.
                    reEncryptor.reEncryptHuggingFaceTokenStore(
                        new de.kortty.ai.huggingface.HuggingFaceTokenStore(KorTTYApplication.getConfigDirectory()));
                    try {
                        reEncryptor.reEncryptRagStores(new de.kortty.rag.RagConfigurationManager());
                    } catch (Exception ragEx) {
                        org.slf4j.LoggerFactory.getLogger(getClass())
                            .warn("Could not open the RAG store registry for re-encryption", ragEx);
                    }
                    if (app.getJobSchedulerService() != null) {
                        reEncryptor.reEncryptJobScheduler(app.getJobSchedulerService().getRepository());
                    }

                    // Persist each store on its own: one failing file must not skip the ones after it,
                    // which would leave those stores encrypted with the old password.
                    List<String> failedStores = new ArrayList<>();
                    persistStore(failedStores, "connections.xml",
                        () -> configManager.save(mpm.getDerivedKey()));
                    if (sshKeyManager != null) {
                        persistStore(failedStores, "ssh-keys.xml", sshKeyManager::save);
                    }
                    if (credentialManager != null) {
                        persistStore(failedStores, "credentials.xml", credentialManager::save);
                    }
                    if (globalSettings != null) {
                        persistStore(failedStores, "global-settings.xml",
                            () -> app.getGlobalSettingsManager().save());
                    }

                    // Commit point: every store has been migrated, so the new password may take over.
                    mpm.commitPasswordChange(pending);
                    committed = true;
                    Telemetry.track(TelemetryEvents.MASTER_PASSWORD_CHANGED);

                    // Keep the remembered auto-unlock password in sync with the new master password.
                    if (globalSettings != null && globalSettings.isSkipMasterPasswordPrompt()) {
                        try {
                            mpm.saveAutoUnlockPassword(newPasswordChars);
                        } catch (Exception ex) {
                            org.slf4j.LoggerFactory.getLogger(getClass())
                                .warn("Failed to update remembered password after master-password change", ex);
                        }
                    }

                    int reEncryptedCount = reEncryptor.reEncryptedCount();
                    int problems = reEncryptor.failureCount() + failedStores.size();
                    if (problems == 0) {
                        Alert success = new Alert(Alert.AlertType.INFORMATION);
                        success.setTitle(I18n.get("settings.masterPassword.changed"));
                        success.setHeaderText(I18n.get("settings.masterPassword.changedSuccess"));
                        success.setContentText(
                            I18n.get("settings.masterPassword.changedMessage", reEncryptedCount));
                        success.showAndWait();
                    } else {
                        // Never report a clean success when some secrets stayed on the old password.
                        org.slf4j.LoggerFactory.getLogger(getClass())
                            .warn("Master password changed with {} unmigrated item(s); stores not saved: {}",
                                problems, failedStores);
                        Alert partial = new Alert(Alert.AlertType.WARNING);
                        partial.setTitle(I18n.get("settings.masterPassword.changed"));
                        partial.setHeaderText(I18n.get("settings.masterPassword.changedSuccess"));
                        partial.setContentText(
                            I18n.get("settings.masterPassword.changedPartial", reEncryptedCount, problems));
                        partial.showAndWait();
                    }
                } finally {
                    if (!committed) {
                        // master.key was never rewritten — put the old password back in memory so the
                        // running session stays consistent with what is on disk.
                        mpm.rollbackPasswordChange(pending);
                    }
                    // Clear sensitive data
                    java.util.Arrays.fill(oldPasswordChars, '\0');
                    java.util.Arrays.fill(newPasswordChars, '\0');
                }

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
            case LOCAL_AI_PROFILE:
                // A profile picked here is honoured whatever its connection mode; with nothing
                // picked this stays the local-only default path.
                return createLocalAiTranslationService(
                    interfaceAiProfileCombo != null ? interfaceAiProfileCombo.getValue() : null);
            default:
                return key != null && !key.isEmpty() ? new GoogleTranslationService(key, urlTrimmed) : null;
        }
    }

    /**
     * Why a local-AI translation service could not be built, or null when that is not the problem.
     *
     * <p>Without this the default path's one refusal — a text profile that does not run a local
     * model — surfaced as the generic "failed to generate language file", which named neither the
     * cause nor the fix (pick a profile explicitly).
     */
    private String localAiTranslationProblem() {
        if (translationProviderCombo == null
            || translationProviderCombo.getValue() != TranslationApiProvider.LOCAL_AI_PROFILE) {
            return null;
        }
        if (interfaceAiProfileCombo != null && interfaceAiProfileCombo.getValue() != null) {
            return null;
        }
        if (globalSettings == null) {
            return null;
        }
        AiProfile textProfile = AiProfileSelectionSupport.workloadProfile(
            globalSettings.getAiProfiles(),
            de.kortty.model.AiWorkload.TEXT,
            globalSettings.getTextAiProfileId(),
            globalSettings.getCodingAiProfileId(),
            globalSettings.getDefaultAiProfileId());
        if (textProfile == null) {
            return I18n.get("settings.translation.error.noTextProfile");
        }
        return textProfile.getConnectionMode().isEmbedded()
            ? null
            : I18n.get("settings.translation.error.textProfileNotLocal",
                textProfile.getName(), textProfile.getConnectionMode().toString());
    }

    /**
     * Builds a translation service from an AI profile.
     *
     * <p>With {@code explicitProfile} null this keeps the original contract: the workload's text
     * profile, and only when it runs a local model — the provider exists for users who cannot
     * reach a translation API, so silently falling back to a cloud profile would defeat it.
     *
     * <p>An explicitly chosen profile is honoured whatever its connection mode. Comparing models
     * is the reason the choice exists, and the user picking a profile by name has already made
     * the decision the default path is protecting them from. Its API key is resolved the same way
     * the rest of the application resolves one.
     */
    private TranslationService createLocalAiTranslationService(AiProfile explicitProfile) {
        if (globalSettings == null) {
            return null;
        }
        AiProfile profile = explicitProfile;
        if (profile == null) {
            profile = AiProfileSelectionSupport.workloadProfile(
                globalSettings.getAiProfiles(),
                de.kortty.model.AiWorkload.TEXT,
                globalSettings.getTextAiProfileId(),
                globalSettings.getCodingAiProfileId(),
                globalSettings.getDefaultAiProfileId());
            if (profile == null || !profile.getConnectionMode().isEmbedded()) {
                return null;
            }
        }
        // Translation never needs web search, and this call passes a disabled internet
        // configuration; keep the profile's mode from tripping the factory's Tavily-key check.
        // Disabling the mode on a copy is safe for embedded profiles (the LM-Studio-MCP
        // mis-routing trap applies only to native LM Studio endpoints).
        AiProfile translationProfile = new AiProfile(profile);
        translationProfile.setInternetAccessMode(de.kortty.model.AiInternetAccessMode.DISABLED);
        AiService service;
        try {
            service = AiServiceFactory.create(
                translationProfile,
                // Embedded models need no key; a chosen HTTP profile does.
                translationProfile.getConnectionMode().isEmbedded()
                    ? null : resolveProfileApiKey(translationProfile),
                AiInternetAccessConfiguration.disabled(),
                AiSkillPromptSupport.disabled());
        } catch (IllegalStateException e) {
            // E.g. no embedded model selected yet, or an EMBEDDED_MLX profile on a non-Apple-Silicon
            // machine: surface the generic "test failed" path instead of an uncaught exception.
            return null;
        }
        return service instanceof AiPromptService promptService
            ? new LocalAiTranslationService(promptService)
            : null;
    }

    /** Decrypts an AI profile's stored key; null when it has none or the vault is locked. */
    private String resolveProfileApiKey(AiProfile profile) {
        String policyKey = de.kortty.policy.PolicyAiProfileSupport.apiKeyOverride(profile);
        if (policyKey != null) {
            return policyKey;
        }
        String encrypted = profile.getEncryptedApiKey();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        try {
            char[] master = app != null && app.getMasterPasswordManager() != null
                ? app.getMasterPasswordManager().getMasterPassword() : null;
            return master == null ? null
                : new de.kortty.security.EncryptionService().decryptPassword(encrypted, master);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass())
                .debug("Could not decrypt the API key of profile {}", profile.getName(), e);
            return null;
        }
    }

    /**
     * The service the guide translation should use.
     *
     * <p>An AI profile picked in the guide section wins over the provider dropdown above. Picking
     * a model by name is an unambiguous instruction to use it, and requiring the provider to be
     * switched to "local AI profile" as well made the choice look ignored: with the dropdown left
     * on Google Translate and no API key, choosing a profile produced nothing but "the guide
     * could not be translated".
     */
    private TranslationService createGuideTranslationService() {
        AiProfile chosen = guideAiProfileCombo != null ? guideAiProfileCombo.getValue() : null;
        if (chosen != null) {
            return createLocalAiTranslationService(chosen);
        }
        if (translationProviderCombo.getValue() != TranslationApiProvider.LOCAL_AI_PROFILE) {
            return createTranslationService();
        }
        return createLocalAiTranslationService(null);
    }

    /**
     * Warns before letting a reasoning model translate the guide.
     *
     * <p>Such a model spends most of its output thinking rather than translating — measured here
     * at 4.4 output tokens per input token — which turns a run of about an hour into six or more.
     * Worth interrupting for once, not worth blocking: the user may have no other model.
     *
     * @return true to go ahead
     */
    private boolean confirmReasoningModel() {
        AiProfile chosen = guideAiProfileCombo != null ? guideAiProfileCombo.getValue() : null;
        if (chosen == null || !de.kortty.core.ReasoningModelHint.likelyReasoningModel(chosen)) {
            return true;
        }
        String model = chosen.getEmbeddedModelId() != null && !chosen.getEmbeddedModelId().isBlank()
            ? chosen.getEmbeddedModelId() : chosen.getName();
        Alert alert = new Alert(Alert.AlertType.WARNING);
        DialogThemeHelper.applyTheme(alert);
        alert.setTitle(I18n.get("settings.translation.guide.reasoning.title"));
        alert.setHeaderText(I18n.get("settings.translation.guide.reasoning.header", model));
        alert.setContentText(I18n.get("settings.translation.guide.reasoning.message"));
        alert.getButtonTypes().setAll(
            new ButtonType(I18n.get("settings.translation.guide.reasoning.continue"),
                ButtonBar.ButtonData.OK_DONE),
            new ButtonType(I18n.get("settings.translation.guide.cancel"),
                ButtonBar.ButtonData.CANCEL_CLOSE));
        ButtonType choice = alert.showAndWait().orElse(null);
        return choice != null && choice.getButtonData() == ButtonBar.ButtonData.OK_DONE;
    }

    /**
     * Why no service could be built, in words the user can act on. The generic failure message
     * was actively misleading here: nothing had been translated, and the cause was a missing API
     * key for a provider the user had not meant to use.
     */
    private String guideTranslationServiceProblem() {
        AiProfile chosen = guideAiProfileCombo != null ? guideAiProfileCombo.getValue() : null;
        if (chosen != null) {
            return I18n.get("settings.translation.guide.error.profile", chosen.getName());
        }
        TranslationApiProvider provider = translationProviderCombo.getValue();
        if (provider != TranslationApiProvider.LOCAL_AI_PROFILE) {
            return I18n.get("settings.translation.guide.error.provider");
        }
        return I18n.get("settings.translation.guide.error.noLocalProfile");
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
        boolean keyOptional = provider == TranslationApiProvider.LIBRETRANSLATE
            || provider == TranslationApiProvider.LOCAL_AI_PROFILE;
        String key = getTranslationApiKeyPlain();
        if (!keyOptional && (key == null || key.isEmpty())) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.translation.error.noKey")).showAndWait();
            return;
        }
        TranslationService svc = createTranslationService();
        if (svc == null) {
            String problem = localAiTranslationProblem();
            new Alert(Alert.AlertType.ERROR, problem != null ? problem
                : I18n.get("settings.translation.error.testFailed")).showAndWait();
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
        String currentDefaultProfileId = getSelectedDefaultAiProfileId();

        AiProfile profile = new AiProfile();
        profile.setId(UUID.randomUUID().toString());
        profile.setName(createDefaultAiProfileName());
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        String defaultApiUrl = globalSettings != null ? globalSettings.getAiApiUrl() : null;
        profile.setApiUrl(defaultApiUrl != null && !defaultApiUrl.isBlank() ? defaultApiUrl : DEFAULT_AI_API_URL);
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setMaxSelectionChars(AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        profile.setTokenizerType(AiTokenizerType.ESTIMATE);
        profile.setTokenLimitUnit(AiTokenLimitUnit.THOUSANDS);
        profile.setTokenResetPeriodDays(30);
        profile.setTokenResetAnchorDate(LocalDate.now().toString());
        profile.setTokenUsageCycleStartDate(LocalDate.now().toString());

        aiProfiles.add(profile);
        aiProfileListView.getItems().setAll(aiProfiles);
        refreshDefaultAiProfileSelection(currentDefaultProfileId != null ? currentDefaultProfileId : profile.getId());
        refreshSecurityCheckProfileSelection(getSelectedSecurityCheckAiProfileId());
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
        String currentDefaultProfileId = getSelectedDefaultAiProfileId();

        aiProfiles.remove(profile);
        if (profile.getId() != null) {
            aiPlainApiKeysByProfileId.remove(profile.getId());
            aiClearedApiKeysByProfileId.remove(profile.getId());
        }

        aiProfileListView.getItems().setAll(aiProfiles);
        refreshDefaultAiProfileSelection(currentDefaultProfileId);
        refreshSecurityCheckProfileSelection(getSelectedSecurityCheckAiProfileId());
        if (aiProfiles.isEmpty()) {
            selectedAiProfile = null;
            loadAiProfileIntoEditor(null);
        } else {
            aiProfileListView.getSelectionModel().selectFirst();
        }
        aiProfileListView.refresh();
    }

    private void selectRsyncBinaryPath() {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(I18n.get("settings.sftp.rsyncBinaryChoose"));
        File selected = chooser.showOpenDialog(getDialogPane().getScene().getWindow());
        if (selected != null) {
            jobSchedulerRsyncBinaryPathField.setText(selected.getAbsolutePath());
        }
    }

    private String errorMessage(Exception e) {
        if (e == null) {
            return "";
        }
        return e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
    }

    private javafx.util.StringConverter<AiReasoningEffort> createAiReasoningConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiReasoningEffort object) {
                return object == null
                    ? ""
                    : I18n.get("settings.ai.reasoning." + object.messageKeySuffix());
            }

            @Override
            public AiReasoningEffort fromString(String string) {
                return null;
            }
        };
    }

    private javafx.util.StringConverter<AiInternetAccessMode> createAiInternetModeConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiInternetAccessMode object) {
                return object == null
                    ? ""
                    : I18n.get("settings.ai.internet.mode." + object.messageKeySuffix());
            }

            @Override
            public AiInternetAccessMode fromString(String string) {
                return null;
            }
        };
    }

    private javafx.util.StringConverter<AiConnectionMode> createAiConnectionModeConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiConnectionMode object) {
                if (object == null) {
                    return "";
                }
                if (object == AiConnectionMode.EMBEDDED_MLX && !MlxPlatform.isSupported()) {
                    return I18n.get("settings.ai.connectionMode.embedded_mlx.unavailable");
                }
                return I18n.get("settings.ai.connectionMode." + object.name().toLowerCase(Locale.ROOT));
            }

            @Override
            public AiConnectionMode fromString(String string) {
                return null;
            }
        };
    }

    private javafx.util.StringConverter<AiCliProviderDescriptor> createAiCliProviderConverter() {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiCliProviderDescriptor object) {
                return object != null ? object.displayName() : "";
            }

            @Override
            public AiCliProviderDescriptor fromString(String string) {
                return null;
            }
        };
    }

    private void wireSecretClearToggle(PasswordField passwordField, CheckBox clearCheck) {
        passwordField.textProperty().addListener((obs, oldValue, newValue) -> {
            boolean hasReplacement = newValue != null && !newValue.isBlank();
            clearCheck.setDisable(hasReplacement);
            if (hasReplacement) {
                clearCheck.setSelected(false);
            }
        });
    }

    private String aiModelEditorText() {
        return aiModelCombo != null && aiModelCombo.getEditor() != null
            ? aiModelCombo.getEditor().getText()
            : null;
    }

    private AiReasoningEffort selectedAiReasoningEffort() {
        return aiReasoningCombo != null ? aiReasoningCombo.getValue() : AiReasoningEffort.DISABLED;
    }

    private String aiModelTextForReasoning() {
        if (isAiCliModeSelected()) {
            String editorText = trimToNull(aiModelEditorText());
            if (AI_MODEL_DEFAULT_LABEL.equals(editorText)) {
                return null;
            }
            if (AI_MODEL_CUSTOM_LABEL.equals(editorText)) {
                return trimToNull(aiCliCustomModelField.getText());
            }
            return editorText != null ? editorText : trimToNull(aiCliCustomModelField.getText());
        }
        String editorText = trimToNull(aiModelEditorText());
        if (AI_MODEL_DEFAULT_LABEL.equals(editorText)) {
            return null;
        }
        if (AI_MODEL_AUTO_LABEL.equals(editorText) && selectedAiProfile != null) {
            return selectedAiProfile.getModel();
        }
        return editorText;
    }

    private void loadAiModelSelection(AiProfile profile) {
        refreshEmbeddedModelSelection(profile);
        if (profile != null && profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            loadAiCliModelSelection(profile);
            return;
        }
        String apiUrl = trimToNull(profile.getApiUrl());
        String model = trimToNull(profile.getModel());
        aiModelCombo.getItems().setAll(AiModelComboSupport.buildModelItems(
            AI_MODEL_DEFAULT_LABEL, AI_MODEL_AUTO_LABEL, apiUrl, List.of(), model));
        AiModelSelectionMode mode = profile.getModelSelectionMode();
        if (mode == AiModelSelectionMode.DEFAULT) {
            aiModelCombo.getSelectionModel().select(AI_MODEL_DEFAULT_LABEL);
        } else if (mode == AiModelSelectionMode.AUTO && AiModelComboSupport.supportsAutoModel(apiUrl)) {
            aiModelCombo.getSelectionModel().select(AI_MODEL_AUTO_LABEL);
        } else if (mode == AiModelSelectionMode.AUTO) {
            // Auto cannot resolve a model for a remote/cloud endpoint: show nothing so the user
            // picks a concrete model instead of hitting a runtime error.
            aiModelCombo.getEditor().setText("");
        } else {
            aiModelCombo.getEditor().setText(model != null ? model : "");
        }
    }

    /** Mode-independent embedded model entry (llama.cpp GGUF or MLX) for the profile editor combo. */
    private record EmbeddedModelChoice(String id, String displayName) {
    }

    private void refreshEmbeddedModelSelection(AiProfile profile) {
        if (aiEmbeddedModelCombo == null) {
            return;
        }
        boolean mlx = profile != null && profile.getConnectionMode() == AiConnectionMode.EMBEDDED_MLX;
        if (aiEmbeddedModelLabel != null) {
            aiEmbeddedModelLabel.setText(I18n.get(mlx ? "settings.ai.embeddedModel.mlx" : "settings.ai.embeddedModel"));
        }
        aiEmbeddedModelCombo.setPromptText(I18n.get(mlx
            ? "settings.ai.embeddedModel.mlx.empty"
            : "settings.ai.embeddedModel.empty"));
        List<EmbeddedModelChoice> models = mlx ? listMlxEmbeddedModels() : listLlamaEmbeddedModels();
        aiEmbeddedModelCombo.getItems().setAll(models);
        String selectedId = profile != null ? profile.getEmbeddedModelId() : null;
        aiEmbeddedModelCombo.setValue(models.stream()
            .filter(model -> model.id().equals(selectedId))
            .findFirst()
            .orElse(null));
    }

    private List<EmbeddedModelChoice> listLlamaEmbeddedModels() {
        return LlamaModelRegistry
            .inDirectory(KorTTYApplication.getConfigDirectory().resolve("llm"))
            .list()
            .stream()
            .filter(model -> model.getPurpose() == de.kortty.ai.llama.LlamaModelPurpose.CHAT)
            .sorted(Comparator.comparing(LlamaModel::getDisplayName, String.CASE_INSENSITIVE_ORDER))
            .map(model -> new EmbeddedModelChoice(model.getId(), model.getDisplayName()))
            .toList();
    }

    private List<EmbeddedModelChoice> listMlxEmbeddedModels() {
        return MlxModelRegistry
            .inDirectory(KorTTYApplication.getConfigDirectory().resolve("llm"))
            .list()
            .stream()
            .sorted(Comparator.comparing(MlxModel::getDisplayName, String.CASE_INSENSITIVE_ORDER))
            .map(model -> new EmbeddedModelChoice(model.getId(), model.getDisplayName()))
            .toList();
    }

    private void snapshotAiModelSelection(AiProfile profile) {
        if (profile.getConnectionMode().isEmbedded()) {
            EmbeddedModelChoice model = aiEmbeddedModelCombo != null ? aiEmbeddedModelCombo.getValue() : null;
            profile.setEmbeddedModelId(model != null ? model.id() : null);
            profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
            profile.setModel(model != null ? model.displayName() : null);
            return;
        }
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            String editorText = trimToNull(aiModelEditorText());
            if (AI_MODEL_DEFAULT_LABEL.equals(editorText)) {
                profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
                profile.setModel(null);
                return;
            }
            String customModel = aiCliCustomModelField != null ? trimToNull(aiCliCustomModelField.getText()) : null;
            profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
            profile.setModel(AI_MODEL_CUSTOM_LABEL.equals(editorText)
                ? customModel
                : editorText != null ? editorText : customModel);
            return;
        }
        String editorText = trimToNull(aiModelEditorText());
        if (AI_MODEL_DEFAULT_LABEL.equals(editorText)) {
            profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
            profile.setModel(null);
            return;
        }
        if (AI_MODEL_AUTO_LABEL.equals(editorText)) {
            profile.setModelSelectionMode(AiModelSelectionMode.AUTO);
            return;
        }
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setModel(editorText);
    }

    private void refreshLocalAiModels(boolean showErrors) {
        if (aiModelCombo == null || aiApiUrlField == null) {
            return;
        }
        if (isAiCliModeSelected()) {
            aiRefreshModelsButton.setDisable(true);
            return;
        }
        String apiUrl = trimToNull(aiApiUrlField.getText());
        aiRefreshModelsButton.setDisable(!LocalLmModelResolver.canListModels(apiUrl));
        if (!LocalLmModelResolver.canListModels(apiUrl)) {
            preserveCurrentAiModelItems(List.of());
            return;
        }
        AiProfile profile = selectedAiProfile;
        String profileId = profile != null ? profile.getId() : null;
        String apiKey = profile != null ? getAiApiKeyPlain(profile) : null;
        aiRefreshModelsButton.setDisable(true);
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return LocalLmModelResolver.loadAvailableModelNames(apiUrl, apiKey);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            })
            .whenComplete((models, throwable) -> Platform.runLater(() -> {
                if (!java.util.Objects.equals(apiUrl, trimToNull(aiApiUrlField.getText()))) {
                    aiRefreshModelsButton.setDisable(!LocalLmModelResolver.canListModels(trimToNull(aiApiUrlField.getText())));
                    return;
                }
                aiRefreshModelsButton.setDisable(false);
                if (selectedAiProfile != profile && (selectedAiProfile == null || !java.util.Objects.equals(selectedAiProfile.getId(), profileId))) {
                    return;
                }
                if (throwable != null) {
                    preserveCurrentAiModelItems(List.of());
                    if (showErrors) {
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        Alert alert = new Alert(Alert.AlertType.ERROR,
                            I18n.get("settings.ai.error.testFailed") + ": "
                                + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()));
                        alert.setHeaderText(null);
                        alert.showAndWait();
                    }
                    return;
                }
                preserveCurrentAiModelItems(models != null ? models : List.of());
            }));
    }

    private void preserveCurrentAiModelItems(List<String> loadedModels) {
        String currentText = aiModelEditorText();
        String apiUrl = aiApiUrlField != null ? trimToNull(aiApiUrlField.getText()) : null;
        aiModelCombo.getItems().setAll(AiModelComboSupport.buildModelItems(
            AI_MODEL_DEFAULT_LABEL, AI_MODEL_AUTO_LABEL, apiUrl, loadedModels, trimToNull(currentText)));
        if (currentText != null) {
            aiModelCombo.getEditor().setText(currentText);
        }
    }

    private void refreshAiReasoningOptions(AiReasoningEffort requestedEffort) {
        AiProfile editorProfile = buildAiReasoningEditorProfile();
        List<AiReasoningEffort> options = editorProfile != null
            ? AiReasoningSupport.availableEfforts(editorProfile)
            : List.of(AiReasoningEffort.DISABLED);
        AiReasoningEffort selected = AiReasoningSupport.normalize(requestedEffort, options);
        aiReasoningCombo.getItems().setAll(options);
        aiReasoningCombo.getSelectionModel().select(selected);
        if (selectedAiProfile != null) {
            selectedAiProfile.setReasoningEffort(selected);
        }
    }

    private AiProfile buildAiReasoningEditorProfile() {
        if (selectedAiProfile == null) {
            return null;
        }
        AiProfile profile = new AiProfile(selectedAiProfile);
        profile.setConnectionMode(aiConnectionModeCombo != null ? aiConnectionModeCombo.getValue() : profile.getConnectionMode());
        profile.setApiUrl(aiApiUrlField != null ? trimToNull(aiApiUrlField.getText()) : profile.getApiUrl());
        profile.setCliProviderId(selectedAiCliProviderId());
        profile.setCliExecutablePath(aiCliExecutableField != null ? trimToNull(aiCliExecutableField.getText()) : profile.getCliExecutablePath());
        profile.setCliArgumentsTemplate(aiCliArgumentsTemplateArea != null
            ? trimToNull(aiCliArgumentsTemplateArea.getText())
            : profile.getCliArgumentsTemplate());
        applyAiModelEditorSelection(profile);
        return profile;
    }

    private void applyAiModelEditorSelection(AiProfile profile) {
        String editorText = trimToNull(aiModelEditorText());
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            if (AI_MODEL_DEFAULT_LABEL.equals(editorText)) {
                profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
                profile.setModel(null);
                return;
            }
            String customModel = aiCliCustomModelField != null ? trimToNull(aiCliCustomModelField.getText()) : null;
            profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
            profile.setModel(AI_MODEL_CUSTOM_LABEL.equals(editorText)
                ? customModel
                : editorText != null ? editorText : customModel);
            return;
        }
        if (AI_MODEL_DEFAULT_LABEL.equals(editorText)) {
            profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
            profile.setModel(null);
            return;
        }
        if (AI_MODEL_AUTO_LABEL.equals(editorText)) {
            profile.setModelSelectionMode(AiModelSelectionMode.AUTO);
            return;
        }
        profile.setModelSelectionMode(AiModelSelectionMode.MANUAL);
        profile.setModel(editorText);
    }

    private void refreshAiReasoningFromConnection(Button refreshButton) {
        snapshotSelectedAiProfileEditorState();
        if (selectedAiProfile == null) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noProfilesConfigured")).showAndWait();
            return;
        }
        if (selectedAiProfile.getConnectionMode() != AiConnectionMode.LOCAL_CLI
            && trimToNull(selectedAiProfile.getApiUrl()) == null) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noUrl")).showAndWait();
            return;
        }
        if (requiresModelForTest(selectedAiProfile)
            && trimToNull(selectedAiProfile.getModel()) == null) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noModel")).showAndWait();
            return;
        }
        if (!validateAiInternetConfigurationForTest(selectedAiProfile)) {
            return;
        }
        AiProfile profileSnapshot = new AiProfile(selectedAiProfile);
        String profileId = profileSnapshot.getId();
        String discoveryKey = AiReasoningSupport.discoveryKey(profileSnapshot);
        String apiKey = getAiApiKeyPlain(selectedAiProfile);
        AiInternetAccessConfiguration internetConfig = buildInternetAccessConfiguration(selectedAiProfile);
        AiSkillPromptSupport skillPromptSupport = AiSkillPromptSupport.fromSettings(globalSettings);
        refreshButton.setDisable(true);
        CompletableFuture
            .supplyAsync(() -> {
                try {
                    return AiReasoningDiscoveryService.discover(
                        profileSnapshot,
                        apiKey,
                        internetConfig,
                        skillPromptSupport);
                } catch (Exception ex) {
                    throw new RuntimeException(ex);
                }
            })
            .whenComplete((options, throwable) -> Platform.runLater(() -> {
                refreshButton.setDisable(false);
                if (throwable != null) {
                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                    Alert alert = new Alert(Alert.AlertType.ERROR,
                        I18n.get("settings.ai.reasoning.refresh.failed") + ": "
                            + (cause.getMessage() != null ? cause.getMessage() : cause.getClass().getSimpleName()));
                    alert.setHeaderText(null);
                    alert.showAndWait();
                    return;
                }
                if (selectedAiProfile == null
                    || (profileId != null && !profileId.equals(selectedAiProfile.getId()))
                    || !discoveryKey.equals(AiReasoningSupport.discoveryKey(selectedAiProfile))) {
                    return;
                }
                List<AiReasoningEffort> discovered = AiReasoningSupport.normalizeOptions(options);
                selectedAiProfile.setReasoningDiscoveryKey(discoveryKey);
                selectedAiProfile.setDiscoveredReasoningEfforts(discovered);
                refreshAiReasoningOptions(selectedAiReasoningEffort());
                Alert alert = new Alert(
                    Alert.AlertType.INFORMATION,
                    discovered.size() > 1
                        ? I18n.get("settings.ai.reasoning.refresh.success", formatAiReasoningOptions(discovered))
                        : I18n.get("settings.ai.reasoning.refresh.none"));
                alert.setHeaderText(null);
                alert.showAndWait();
            }));
    }

    private String formatAiReasoningOptions(List<AiReasoningEffort> options) {
        return AiReasoningSupport.normalizeOptions(options).stream()
            .map(effort -> I18n.get("settings.ai.reasoning." + effort.messageKeySuffix()))
            .collect(Collectors.joining(", "));
    }

    private void loadAiCliModelSelection(AiProfile profile) {
        List<String> items = new ArrayList<>();
        items.add(AI_MODEL_DEFAULT_LABEL);
        items.add(AI_MODEL_CUSTOM_LABEL);
        AiCliProviderRegistry.find(profile.getCliProviderId())
            .ifPresent(provider -> provider.modelPresets().forEach(preset -> {
                if (!preset.modelName().isBlank() && !items.contains(preset.modelName())) {
                    items.add(preset.modelName());
                }
            }));
        String model = trimToNull(profile.getModel());
        if (model != null && !items.contains(model)) {
            items.add(model);
        }
        aiModelCombo.getItems().setAll(items);
        if (profile.getModelSelectionMode() == AiModelSelectionMode.DEFAULT) {
            aiModelCombo.getSelectionModel().select(AI_MODEL_DEFAULT_LABEL);
            aiCliCustomModelField.clear();
        } else if (model == null) {
            aiModelCombo.getSelectionModel().select(AI_MODEL_CUSTOM_LABEL);
            aiCliCustomModelField.clear();
        } else if (AiCliProviderRegistry.find(profile.getCliProviderId())
            .map(provider -> provider.modelPresets().stream().anyMatch(preset -> model.equalsIgnoreCase(preset.modelName())))
            .orElse(false)) {
            aiModelCombo.getSelectionModel().select(model);
            aiCliCustomModelField.clear();
        } else {
            aiModelCombo.getSelectionModel().select(AI_MODEL_CUSTOM_LABEL);
            aiCliCustomModelField.setText(model);
        }
    }

    private boolean isAiCliModeSelected() {
        AiConnectionMode mode = aiConnectionModeCombo != null ? aiConnectionModeCombo.getValue() : null;
        if (mode != null) {
            return mode == AiConnectionMode.LOCAL_CLI;
        }
        return selectedAiProfile != null && selectedAiProfile.getConnectionMode() == AiConnectionMode.LOCAL_CLI;
    }

    private String selectedAiCliProviderId() {
        AiCliProviderDescriptor provider = aiCliProviderCombo != null ? aiCliProviderCombo.getValue() : null;
        if (provider != null) {
            return provider.id();
        }
        return selectedAiProfile != null ? selectedAiProfile.getCliProviderId() : null;
    }

    private void ensureAiCliDefaults(AiProfile profile) {
        if (profile == null || profile.getConnectionMode() != AiConnectionMode.LOCAL_CLI) {
            return;
        }
        if (trimToNull(profile.getCliProviderId()) == null) {
            profile.setCliProviderId(AiCliProviderRegistry.defaultProvider().id());
        }
        String currentTemplate = trimToNull(profile.getCliArgumentsTemplate());
        if (currentTemplate == null
            || AiCliProviderRegistry.isDeprecatedDefaultArgumentTemplate(profile.getCliProviderId(), currentTemplate)) {
            AiCliProviderRegistry.defaultArgumentPreset(profile.getCliProviderId())
                .ifPresent(preset -> {
                    String template = preset.argumentsTemplate();
                    if (template.isBlank()) {
                        return;
                    }
                    profile.setCliArgumentsTemplate(template);
                    if (!AiCliArgumentTemplate.requiresModel(template)) {
                        profile.setModelSelectionMode(AiModelSelectionMode.DEFAULT);
                        profile.setModel(null);
                    }
                });
        }
    }

    private boolean shouldReplaceAiCliArgumentsTemplateOnProviderChange(
        String previousProviderId,
        String previousProfileTemplate,
        String editorTemplate) {

        String currentTemplate = trimToNull(editorTemplate);
        if (currentTemplate == null) {
            return true;
        }
        String previousTemplate = trimToNull(previousProfileTemplate);
        if (previousTemplate != null && currentTemplate.equals(previousTemplate)) {
            return true;
        }
        return AiCliProviderRegistry.isKnownDefaultArgumentTemplate(previousProviderId, currentTemplate);
    }

    private void updateAiConnectionModeUi() {
        boolean cliMode = isAiCliModeSelected();
        boolean embeddedMode = aiConnectionModeCombo != null
            && aiConnectionModeCombo.getValue() != null
            && aiConnectionModeCombo.getValue().isEmbedded();
        boolean localMode = cliMode || embeddedMode;
        aiApiUrlField.setDisable(localMode);
        aiApiKeyField.setDisable(localMode);
        aiClearApiKeyCheck.setDisable(localMode || (aiApiKeyField.getText() != null && !aiApiKeyField.getText().isBlank()));
        aiInternetAccessModeCombo.setDisable(localMode);
        aiRefreshModelsButton.setDisable(localMode || !LocalLmModelResolver.canListModels(trimToNull(aiApiUrlField.getText())));
        aiRefreshReasoningButton.setDisable(selectedAiProfile == null);
        aiCliProviderCombo.setDisable(!cliMode);
        aiCliExecutableField.setDisable(!cliMode);
        aiCliArgumentsTemplateArea.setDisable(!cliMode);
        aiCliCustomModelField.setDisable(!cliMode);
        aiRefreshCliStatusButton.setDisable(!cliMode);
        aiEmbeddedModelCombo.setDisable(!embeddedMode);
        aiModelCombo.setDisable(embeddedMode);
        if (cliMode) {
            refreshAiCliStatus();
        } else {
            aiCliStatusLabel.setText("");
        }
    }

    private void refreshAiCliStatus() {
        if (!isAiCliModeSelected()) {
            aiCliStatusLabel.setText("");
            return;
        }
        String customExecutable = trimToNull(aiCliExecutableField.getText());
        if (customExecutable != null) {
            boolean executableKnown = AiCliProviderRegistry.findExecutable(customExecutable).isPresent()
                || java.nio.file.Files.isExecutable(java.nio.file.Path.of(customExecutable));
            aiCliStatusLabel.setText(executableKnown
                ? I18n.get("settings.ai.cli.status.custom", customExecutable)
                : I18n.get("settings.ai.cli.status.customUnverified", customExecutable));
            return;
        }
        AiCliProviderRegistry.findProviderExecutable(selectedAiCliProviderId())
            .ifPresentOrElse(
                path -> aiCliStatusLabel.setText(I18n.get("settings.ai.cli.status.installed", path)),
                () -> aiCliStatusLabel.setText(I18n.get("settings.ai.cli.status.notInstalled")));
    }

    private void snapshotSelectedAiProfileEditorState() {
        if (selectedAiProfile == null) {
            return;
        }
        if (selectedAiProfile.getId() == null || selectedAiProfile.getId().isBlank()) {
            selectedAiProfile.setId(UUID.randomUUID().toString());
        }

        selectedAiProfile.setName(trimToNull(aiProfileNameField.getText()));
        selectedAiProfile.setConnectionMode(aiConnectionModeCombo.getValue());
        selectedAiProfile.setApiUrl(trimToNull(aiApiUrlField.getText()));
        selectedAiProfile.setCliProviderId(selectedAiCliProviderId());
        selectedAiProfile.setCliExecutablePath(trimToNull(aiCliExecutableField.getText()));
        selectedAiProfile.setCliArgumentsTemplate(trimToNull(aiCliArgumentsTemplateArea.getText()));
        snapshotAiModelSelection(selectedAiProfile);
        selectedAiProfile.setReasoningEffort(AiReasoningSupport.normalize(
            aiReasoningCombo.getValue(),
            AiReasoningSupport.availableEfforts(selectedAiProfile)));
        selectedAiProfile.setInternetAccessMode(aiInternetAccessModeCombo.getValue());
        selectedAiProfile.setMaxSelectionChars(aiMaxSelectionCharsSpinner.getValue() != null ? aiMaxSelectionCharsSpinner.getValue() : AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        selectedAiProfile.setRequestTimeoutMinutes(
            aiRequestTimeoutOverrideCheck.isSelected() ? aiRequestTimeoutSpinner.getValue() : null);
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
            aiConnectionModeCombo.setValue(AiConnectionMode.HTTP_API);
            aiApiUrlField.clear();
            aiCliProviderCombo.getSelectionModel().select(AiCliProviderRegistry.defaultProvider());
            aiCliExecutableField.clear();
            aiCliArgumentsTemplateArea.clear();
            aiCliCustomModelField.clear();
            aiCliStatusLabel.setText("");
            aiModelCombo.getItems().setAll(AI_MODEL_DEFAULT_LABEL, AI_MODEL_AUTO_LABEL);
            aiModelCombo.getSelectionModel().select(AI_MODEL_AUTO_LABEL);
            aiEmbeddedModelCombo.getItems().clear();
            aiEmbeddedModelCombo.setValue(null);
            refreshAiReasoningOptions(AiReasoningEffort.DISABLED);
            aiInternetAccessModeCombo.setValue(AiInternetAccessMode.DISABLED);
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
            updateAiConnectionModeUi();
            return;
        }

        ensureAiCliDefaults(profile);
        aiProfileNameField.setText(profile.getName() != null ? profile.getName() : "");
        aiConnectionModeCombo.setValue(profile.getConnectionMode());
        aiApiUrlField.setText(profile.getApiUrl() != null ? profile.getApiUrl() : "");
        aiCliProviderCombo.getSelectionModel().select(
            AiCliProviderRegistry.find(profile.getCliProviderId()).orElse(AiCliProviderRegistry.defaultProvider()));
        aiCliExecutableField.setText(profile.getCliExecutablePath() != null ? profile.getCliExecutablePath() : "");
        aiCliArgumentsTemplateArea.setText(profile.getCliArgumentsTemplate() != null ? profile.getCliArgumentsTemplate() : "");
        loadAiModelSelection(profile);
        refreshAiReasoningOptions(profile.getReasoningEffort());
        refreshLocalAiModels(false);
        aiInternetAccessModeCombo.setValue(profile.getInternetAccessMode());
        aiMaxSelectionCharsSpinner.getValueFactory().setValue(
            profile.getMaxSelectionChars() != null && profile.getMaxSelectionChars() > 0
                ? profile.getMaxSelectionChars()
                : AiProfile.DEFAULT_MAX_SELECTION_CHARS);
        Integer aiProfileTimeoutMinutes = profile.getRequestTimeoutMinutes();
        aiRequestTimeoutOverrideCheck.setSelected(aiProfileTimeoutMinutes != null);
        aiRequestTimeoutSpinner.getValueFactory().setValue(
            aiProfileTimeoutMinutes != null ? aiProfileTimeoutMinutes : 0);
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
        updateAiConnectionModeUi();
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
            ensureAiCliDefaults(copy);
            copy.setApiUrl(trimToNull(copy.getApiUrl()));
            copy.setModel(trimToNull(copy.getModel()));
            copy.setCliExecutablePath(trimToNull(copy.getCliExecutablePath()));
            copy.setCliArgumentsTemplate(trimToNull(copy.getCliArgumentsTemplate()));
            copy.setReasoningEffort(AiReasoningSupport.normalizeForProfile(copy));

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

        String defaultProfileId = null;
        AiProfile defaultProfile = aiDefaultProfileCombo.getSelectionModel().getSelectedItem();
        if (defaultProfile != null) {
            if (defaultProfile.getId() == null || defaultProfile.getId().isBlank()) {
                String generatedId = UUID.randomUUID().toString();
                defaultProfile.setId(generatedId);
            }
            defaultProfileId = defaultProfile.getId();
        }

        globalSettings.setAiProfiles(profilesToSave);
        globalSettings.setDefaultAiProfileId(
            AiProfileSelectionSupport.normalizeDefaultProfileId(defaultProfileId, profilesToSave));
        // A null security-check profile is intentional ("use default profile"), so do NOT run it
        // through normalizeDefaultProfileId (which would fall back to the first profile). setter +
        // normalizeAiProfiles() drop it if the referenced profile no longer exists.
        globalSettings.setSecurityCheckAiProfileId(getSelectedSecurityCheckAiProfileId());
        globalSettings.setAiApiUrl(null);
        globalSettings.setAiModel(null);
        globalSettings.setEncryptedAiApiKey(null);
        globalSettings.setTerminalAgentCommandName(TerminalAgentCommandSupport.normalizeCommandName(aiAgentCommandNameField.getText()));
        globalSettings.setTerminalAgentExecutionTarget(aiExecutionTargetCombo.getValue());
        AiLanguageSupport.LanguageOption selectedLanguage = aiCodeTextLanguageCombo.getSelectionModel().getSelectedItem();
        globalSettings.setAiCodeTextDefaultLanguage(selectedLanguage != null ? selectedLanguage.code() : null);
        globalSettings.setAiSnippetAlternativeSolutionCount(aiSnippetAlternativeSolutionCountSpinner.getValue());
        globalSettings.setTerminalAgentInputHistorySize(terminalAgentInputHistorySizeSpinner.getValue());
        if (!saveAiInternetToolSettings(encryptionService)) {
            return false;
        }
        return true;
    }

    private boolean saveAiInternetToolSettings(de.kortty.security.EncryptionService encryptionService) {
        try {
            globalSettings.setEncryptedAiTavilyApiKey(resolveEncryptedGlobalSecret(
                encryptionService,
                globalSettings.getEncryptedAiTavilyApiKey(),
                aiTavilyApiKeyField.getText(),
                aiClearTavilyApiKeyCheck.isSelected()));
            globalSettings.setEncryptedAiBrightDataApiToken(resolveEncryptedGlobalSecret(
                encryptionService,
                globalSettings.getEncryptedAiBrightDataApiToken(),
                aiBrightDataApiTokenField.getText(),
                aiClearBrightDataApiTokenCheck.isSelected()));
            globalSettings.setEncryptedAiBraveSearchApiKey(resolveEncryptedGlobalSecret(
                encryptionService,
                globalSettings.getEncryptedAiBraveSearchApiKey(),
                aiBraveSearchApiKeyField.getText(),
                aiClearBraveSearchApiKeyCheck.isSelected()));
        } catch (Exception ex) {
            Alert alert = new Alert(Alert.AlertType.WARNING,
                I18n.get("settings.ai.error.testFailed") + ": "
                    + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
            alert.setHeaderText(null);
            alert.showAndWait();
            return false;
        }
        globalSettings.setAiRequestTimeoutMinutes(
            aiGlobalRequestTimeoutSpinner.getValue() != null ? aiGlobalRequestTimeoutSpinner.getValue() : 0);
        globalSettings.setAiSearxngUrl(trimToNull(aiSearxngUrlField.getText()));
        globalSettings.setAiTavilyMcpServerLabel(aiTavilyMcpServerLabelField.getText());
        globalSettings.setAiBrightDataMcpServerLabel(aiBrightDataMcpServerLabelField.getText());
        globalSettings.setAiBraveSearchMcpPluginId(aiBraveSearchMcpPluginIdField.getText());
        globalSettings.setAiSearxngMcpPluginId(aiSearxngMcpPluginIdField.getText());
        globalSettings.setAiLmStudioToolpackMcpPluginId(aiLmStudioToolpackMcpPluginIdField.getText());
        return true;
    }

    private String resolveEncryptedGlobalSecret(
        de.kortty.security.EncryptionService encryptionService,
        String existingEncryptedValue,
        String plainReplacement,
        boolean clearExisting) throws Exception {

        if (plainReplacement != null && !plainReplacement.isBlank()) {
            char[] masterPassword = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
            if (masterPassword == null) {
                throw new IllegalStateException(I18n.get("settings.ai.error.vaultLocked"));
            }
            return encryptionService.encryptPassword(plainReplacement, masterPassword);
        }
        return clearExisting ? null : existingEncryptedValue;
    }

    private void saveAiToggleFlagsToSettings(GlobalSettings targetSettings) {
        if (targetSettings == null) {
            return;
        }
        if (aiFeaturesEnabledCheck != null) {
            targetSettings.setAiFeaturesEnabled(aiFeaturesEnabledCheck.isSelected());
        }
        if (aiConfirmBeforeSendCheck != null) {
            targetSettings.setAiConfirmBeforeSend(aiConfirmBeforeSendCheck.isSelected());
        }
        if (aiTerminalAgentExecutionEnabledCheck != null) {
            targetSettings.setTerminalAgentExecutionEnabled(aiTerminalAgentExecutionEnabledCheck.isSelected());
        }
        if (aiTerminalAgentConfirmMutatingCommandSetsCheck != null) {
            targetSettings.setTerminalAgentConfirmMutatingCommandSets(aiTerminalAgentConfirmMutatingCommandSetsCheck.isSelected());
        }
        if (aiPromptHookEnabledCheck != null) {
            targetSettings.setDefaultPromptHookEnabled(aiPromptHookEnabledCheck.isSelected());
        }
        if (aiShowDebugMessagesCheck != null) {
            targetSettings.setTerminalAgentShowDebugMessages(aiShowDebugMessagesCheck.isSelected());
        }
        if (aiShowRuntimeMessagesCheck != null) {
            targetSettings.setTerminalAgentShowRuntimeMessages(aiShowRuntimeMessagesCheck.isSelected());
        }
        if (aiTerminalAgentShowRunDialogCheck != null) {
            targetSettings.setTerminalAgentShowRunDialog(aiTerminalAgentShowRunDialogCheck.isSelected());
        }
        if (aiAgentCommandNameCaseInsensitiveCheck != null) {
            targetSettings.setTerminalAgentCommandNameCaseInsensitive(aiAgentCommandNameCaseInsensitiveCheck.isSelected());
        }
        if (aiSnippetEditorInstructionsCheck != null) {
            targetSettings.setAiSnippetEditorAdditionalInstructionsEnabled(aiSnippetEditorInstructionsCheck.isSelected());
        }
    }

    private boolean shouldSkipAiValidationOnSave() {
        if (aiFeaturesEnabledCheck != null) {
            return !aiFeaturesEnabledCheck.isSelected();
        }
        return globalSettings == null || !globalSettings.isAiFeaturesEnabled();
    }

    private void refreshDefaultAiProfileSelection(String preferredProfileId) {
        aiDefaultProfileCombo.getItems().setAll(aiProfiles);
        AiProfile selection = findLocalAiProfileById(preferredProfileId);
        if (selection == null && globalSettings != null) {
            selection = findLocalAiProfileById(globalSettings.getDefaultAiProfileId());
        }
        if (selection == null && !aiProfiles.isEmpty()) {
            selection = aiProfiles.getFirst();
        }
        aiDefaultProfileCombo.getSelectionModel().select(selection);
    }

    private AiProfile findLocalAiProfileById(String profileId) {
        if (profileId == null || profileId.isBlank()) {
            return null;
        }
        for (AiProfile profile : aiProfiles) {
            if (profile != null && profileId.equals(profile.getId())) {
                return profile;
            }
        }
        return null;
    }

    private String getSelectedDefaultAiProfileId() {
        AiProfile selectedDefaultProfile = aiDefaultProfileCombo.getSelectionModel().getSelectedItem();
        return selectedDefaultProfile != null ? selectedDefaultProfile.getId() : null;
    }

    private void refreshSecurityCheckProfileSelection(String preferredProfileId) {
        if (aiSecurityCheckProfileCombo == null) {
            return;
        }
        aiSecurityCheckProfileCombo.getItems().setAll(aiProfiles);
        AiProfile selection = findLocalAiProfileById(preferredProfileId);
        if (selection != null) {
            aiSecurityCheckProfileCombo.getSelectionModel().select(selection);
        } else {
            // A blank selection means "use the default profile" (persisted as null).
            aiSecurityCheckProfileCombo.getSelectionModel().clearSelection();
        }
    }

    private String getSelectedSecurityCheckAiProfileId() {
        if (aiSecurityCheckProfileCombo == null) {
            return null;
        }
        AiProfile selected = aiSecurityCheckProfileCombo.getSelectionModel().getSelectedItem();
        return selected != null ? selected.getId() : null;
    }

    private AiService createAiService(AiProfile profile) {
        if (profile == null) {
            return null;
        }
        String apiKey = getAiApiKeyPlain(profile);
        if (profile.getConnectionMode() != AiConnectionMode.LOCAL_CLI
            && trimToNull(profile.getApiUrl()) == null) {
            return null;
        }
        try {
            return AiServiceFactory.create(
                profile,
                apiKey,
                buildInternetAccessConfiguration(profile),
                AiSkillPromptSupport.fromSettings(globalSettings));
        } catch (IllegalStateException e) {
            return new FailingAiService(e.getMessage());
        }
    }

    private String getAiApiKeyPlain(AiProfile profile) {
        if (profile == null) {
            return null;
        }
        String policyKey = de.kortty.policy.PolicyAiProfileSupport.apiKeyOverride(profile);
        if (policyKey != null) {
            return policyKey;
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

    private AiInternetAccessConfiguration buildInternetAccessConfiguration(AiProfile profile) {
        if (globalSettings == null || profile == null || !profile.getInternetAccessMode().isEnabled()) {
            return AiInternetAccessConfiguration.disabled();
        }
        return new AiInternetAccessConfiguration(
            profile.getInternetAccessMode(),
            readPlainInternetSecret(aiTavilyApiKeyField, aiClearTavilyApiKeyCheck, globalSettings.getEncryptedAiTavilyApiKey()),
            readPlainInternetSecret(aiBrightDataApiTokenField, aiClearBrightDataApiTokenCheck, globalSettings.getEncryptedAiBrightDataApiToken()),
            readPlainInternetSecret(aiBraveSearchApiKeyField, aiClearBraveSearchApiKeyCheck, globalSettings.getEncryptedAiBraveSearchApiKey()),
            trimToNull(aiSearxngUrlField.getText()),
            aiTavilyMcpServerLabelField.getText(),
            aiBrightDataMcpServerLabelField.getText(),
            aiBraveSearchMcpPluginIdField.getText(),
            aiSearxngMcpPluginIdField.getText(),
            aiLmStudioToolpackMcpPluginIdField.getText());
    }

    private String readPlainInternetSecret(PasswordField field, CheckBox clearCheck, String encryptedValue) {
        String replacement = field != null ? field.getText() : null;
        if (replacement != null && !replacement.isBlank()) {
            return replacement;
        }
        if (clearCheck != null && clearCheck.isSelected()) {
            return null;
        }
        if (encryptedValue == null || encryptedValue.isBlank()) {
            return null;
        }
        try {
            char[] master = app.getMasterPasswordManager() != null ? app.getMasterPasswordManager().getMasterPassword() : null;
            if (master == null) {
                return null;
            }
            de.kortty.security.EncryptionService enc = new de.kortty.security.EncryptionService();
            String decrypted = enc.decryptPassword(encryptedValue, master);
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
        if (selectedAiProfile.getConnectionMode() != AiConnectionMode.LOCAL_CLI
            && trimToNull(selectedAiProfile.getApiUrl()) == null) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noUrl")).showAndWait();
            return;
        }
        if (requiresModelForTest(selectedAiProfile)
            && trimToNull(selectedAiProfile.getModel()) == null) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.ai.error.noModel")).showAndWait();
            return;
        }
        if (!validateAiInternetConfigurationForTest(selectedAiProfile)) {
            return;
        }
        AiService svc = createAiService(selectedAiProfile);
        if (svc == null) {
            new Alert(Alert.AlertType.ERROR, I18n.get("settings.ai.error.testFailed")).showAndWait();
            return;
        }
        if (svc instanceof FailingAiService failingService) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                I18n.get("settings.ai.error.testFailed") + ": " + failingService.message());
            alert.setHeaderText(null);
            alert.showAndWait();
            return;
        }
        aiTestConnectionButton.setDisable(true);
        CompletableFuture
            .supplyAsync(svc::testConnection)
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

    private boolean validateAiInternetConfigurationForTest(AiProfile profile) {
        if (profile == null || !profile.getInternetAccessMode().isEnabled()) {
            return true;
        }
        try {
            buildInternetAccessConfiguration(profile).validate();
            return true;
        } catch (IllegalStateException ex) {
            Alert alert = new Alert(Alert.AlertType.ERROR,
                I18n.get("settings.ai.error.testFailed") + ": "
                    + (ex.getMessage() != null ? ex.getMessage() : ex.getClass().getSimpleName()));
            alert.setHeaderText(null);
            alert.showAndWait();
            return false;
        }
    }

    private boolean requiresModelForTest(AiProfile profile) {
        if (profile == null) {
            return false;
        }
        if (profile.getModelSelectionMode() == AiModelSelectionMode.DEFAULT) {
            return false;
        }
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            return AiCliArgumentTemplate.requiresModel(profile.getCliArgumentsTemplate());
        }
        return profile.getModelSelectionMode() == AiModelSelectionMode.MANUAL;
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
        boolean keyOptional = provider == TranslationApiProvider.LIBRETRANSLATE
            || provider == TranslationApiProvider.LOCAL_AI_PROFILE;
        String key = getTranslationApiKeyPlain();
        if (!keyOptional && (key == null || key.isEmpty())) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.translation.error.noKey")).showAndWait();
            return;
        }
        TranslationService svc = createTranslationService();
        if (svc == null) {
            String problem = localAiTranslationProblem();
            new Alert(Alert.AlertType.ERROR, problem != null ? problem
                : I18n.get("settings.translation.error.generationFailed")).showAndWait();
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

    /**
     * Translates the bundled guide into the selected language.
     *
     * <p>Unlike the interface strings this is a long job — hours against a local model — so it
     * reports real progress and can be cancelled. Cancelling is safe rather than wasteful: the
     * generator checkpoints its translation memory, so the next run resumes instead of
     * retranslating what was already done.
     */
    private void generateGuideTranslation(Button generateButton) {
        TranslationApiProvider provider = translationProviderCombo.getValue();
        boolean keyOptional = provider == TranslationApiProvider.LIBRETRANSLATE
            || provider == TranslationApiProvider.LOCAL_AI_PROFILE;
        String key = getTranslationApiKeyPlain();
        if (!keyOptional && (key == null || key.isEmpty())) {
            new Alert(Alert.AlertType.WARNING, I18n.get("settings.translation.error.noKey")).showAndWait();
            return;
        }
        TranslationService service = createGuideTranslationService();
        Locale target = translationTargetLanguageCombo.getValue();
        if (service == null || target == null || target.getLanguage() == null
            || target.getLanguage().isEmpty()) {
            new Alert(Alert.AlertType.ERROR, guideTranslationServiceProblem()).showAndWait();
            return;
        }
        if (!confirmReasoningModel()) {
            return;
        }
        de.kortty.core.GuideTranslationJob job = de.kortty.core.GuideTranslationJob.getInstance();
        if (!job.start(service, target.getLanguage(), KorTTYApplication.getConfigDirectory())) {
            new Alert(Alert.AlertType.INFORMATION,
                I18n.get("settings.translation.guide.alreadyRunning")).showAndWait();
            return;
        }
        generateButton.setDisable(true);
        guideTranslationProgress.setProgress(0);
        guideTranslationProgress.setVisible(true);
        guideTranslationCancelButton.setVisible(true);
        guideTranslationStatusLabel.setText(I18n.get("settings.translation.guide.progress", 0));
        guideTranslationCancelButton.setOnAction(ev -> {
            guideTranslationCancelButton.setDisable(true);
            job.cancel();
        });
        observeGuideTranslationJob(generateButton);
    }

    /**
     * Translates a small sample and projects the full run from it.
     *
     * <p>Worth a button of its own: the honest answer ranges from a minute on a cloud API to most
     * of a night on a local model, and nobody should have to start a six-hour job to find that
     * out. The sample is real translation, kept in the memory, so the estimate is a down payment
     * on the run rather than throwaway work.
     */
    private void estimateGuideTranslation(Button estimateButton, Button generateButton) {
        TranslationService service = createGuideTranslationService();
        Locale target = translationTargetLanguageCombo.getValue();
        if (service == null || target == null || target.getLanguage() == null
            || target.getLanguage().isEmpty()) {
            new Alert(Alert.AlertType.ERROR, guideTranslationServiceProblem()).showAndWait();
            return;
        }
        if (!confirmReasoningModel()) {
            return;
        }
        String targetLang = target.getLanguage();
        int sampleSize = GuideTranslationGenerator.DEFAULT_ESTIMATE_SAMPLE;

        estimateButton.setDisable(true);
        generateButton.setDisable(true);
        guideTranslationProgress.setProgress(-1);
        guideTranslationProgress.setVisible(true);
        guideTranslationCancelButton.setVisible(true);
        guideTranslationStatusLabel.setText(
            I18n.get("settings.translation.guide.estimateRunning", sampleSize));

        Task<GuideTranslationGenerator.Estimate> task = new Task<>() {
            @Override
            protected GuideTranslationGenerator.Estimate call() throws Exception {
                return new GuideTranslationGenerator(service, KorTTYApplication.getConfigDirectory())
                    .estimate(targetLang, sampleSize, this::isCancelled);
            }
        };
        guideTranslationCancelButton.setOnAction(ev -> {
            guideTranslationCancelButton.setDisable(true);
            task.cancel();
        });
        Runnable finish = () -> {
            estimateButton.setDisable(false);
            generateButton.setDisable(false);
            guideTranslationProgress.setVisible(false);
            guideTranslationProgress.setProgress(0);
            guideTranslationCancelButton.setVisible(false);
            guideTranslationCancelButton.setDisable(false);
        };
        task.setOnSucceeded(ev -> {
            finish.run();
            GuideTranslationGenerator.Estimate estimate = task.getValue();
            if (estimate.isComplete()) {
                guideTranslationStatusLabel.setText(
                    I18n.get("settings.translation.guide.estimateComplete"));
            } else if (!estimate.isUsable()) {
                guideTranslationStatusLabel.setText(
                    I18n.get("settings.translation.guide.estimateFailed"));
            } else {
                guideTranslationStatusLabel.setText(I18n.get(
                    "settings.translation.guide.estimateResult",
                    formatDuration(estimate.lowMillis()), formatDuration(estimate.highMillis()),
                    estimate.remainingSegments(), estimate.sampleSegments(),
                    formatDuration(estimate.elapsedMillis())));
            }
        });
        task.setOnCancelled(ev -> {
            finish.run();
            guideTranslationStatusLabel.setText(I18n.get("settings.translation.guide.cancelled"));
        });
        task.setOnFailed(ev -> {
            finish.run();
            Throwable error = task.getException();
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Guide estimate failed", error);
            guideTranslationStatusLabel.setText(
                I18n.get("settings.translation.guide.estimateFailed"));
        });
        Thread worker = new Thread(task, "guide-translation-estimate");
        worker.setDaemon(true);
        worker.start();
    }

    /** Coarse, readable duration — an estimate must not imply second-level precision. */
    static String formatDuration(long millis) {
        long seconds = Math.max(0, millis) / 1000;
        if (seconds < 90) {
            return seconds + " s";
        }
        long minutes = (seconds + 30) / 60;
        if (minutes < 90) {
            return minutes + " min";
        }
        long hours = minutes / 60;
        long remainder = minutes % 60;
        return remainder == 0 ? hours + " h" : hours + " h " + remainder + " min";
    }

    /**
     * Mirrors the application-wide job into this dialog for as long as it stays open.
     *
     * <p>The dialog observes the run instead of owning it. A translation lasts hours and the user
     * has to be able to close this window, keep working, watch progress in the menu bar and be
     * warned before quitting — none of which survives a task that belongs to a dialog.
     */
    private void observeGuideTranslationJob(Button generateButton) {
        de.kortty.core.GuideTranslationJob job = de.kortty.core.GuideTranslationJob.getInstance();
        if (guideJobListener != null) {
            job.removeListener(guideJobListener);
        }
        guideJobListener = () -> Platform.runLater(() -> {
            de.kortty.core.GuideTranslationJob.Snapshot snapshot = job.snapshot();
            if (snapshot.running()) {
                guideTranslationProgress.setProgress(snapshot.progress());
                guideTranslationStatusLabel.setText(
                    I18n.get("settings.translation.guide.progress", snapshot.percent()));
                return;
            }
            generateButton.setDisable(false);
            guideTranslationProgress.setVisible(false);
            guideTranslationCancelButton.setVisible(false);
            guideTranslationCancelButton.setDisable(false);
            guideTranslationStatusLabel.setText(job.isCancelRequested()
                ? I18n.get("settings.translation.guide.cancelled") : "");
            refreshGuideTranslationList();
            job.removeListener(guideJobListener);
            guideJobListener = null;
        });
        job.addListener(guideJobListener);
    }

    /** Null first: the default text profile, matching what the run uses when nothing is picked. */
    private void refreshAiProfileCombo(ComboBox<AiProfile> combo) {
        if (combo == null) {
            return;
        }
        AiProfile previous = combo.getValue();
        java.util.List<AiProfile> items = new java.util.ArrayList<>();
        items.add(null);
        if (globalSettings != null && globalSettings.getAiProfiles() != null) {
            items.addAll(globalSettings.getAiProfiles());
        }
        combo.getItems().setAll(items);
        combo.setValue(items.contains(previous) ? previous : null);
    }

    /**
     * Renders an AI-profile choice, with {@code null} shown as the supplied "default" label.
     *
     * <p>The connection mode is shown, not hidden: it is the difference between an hours-long
     * local run and sending half a megabyte to a paid endpoint.
     */
    private javafx.util.StringConverter<AiProfile> aiProfileChoiceConverter(String defaultLabelKey) {
        return new javafx.util.StringConverter<>() {
            @Override
            public String toString(AiProfile profile) {
                return profile == null
                    ? I18n.get(defaultLabelKey)
                    : profile.getName() + "  (" + profile.getConnectionMode() + ")";
            }

            @Override
            public AiProfile fromString(String value) {
                return null;
            }
        };
    }

    private void refreshGuideTranslationList() {
        if (guideTranslationList == null) {
            return;
        }
        guideTranslationList.getItems().setAll(
            GuideLocationResolver.availableGeneratedLanguages(KorTTYApplication.getConfigDirectory()));
    }

    /** Removes a translated guide, its staged assets and its translation memory. */
    private void deleteSelectedGuideTranslation(Stage owner) {
        String selected = guideTranslationList.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION);
        confirm.setTitle(I18n.get("settings.translation.guide.deleteTitle"));
        confirm.setHeaderText(I18n.get("settings.translation.guide.deleteConfirm",
            Locale.forLanguageTag(selected).getDisplayLanguage()));
        Window dialogOwner = getSettingsDialogOwner(owner);
        if (dialogOwner != null) {
            confirm.initOwner(dialogOwner);
        }
        if (confirm.showAndWait().orElse(ButtonType.CANCEL) != ButtonType.OK) {
            return;
        }
        java.nio.file.Path root = GuideLocationResolver
            .generatedRoot(KorTTYApplication.getConfigDirectory()).resolve(selected);
        try {
            deleteGeneratedGuideTree(root);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not delete {}", root, e);
            new Alert(Alert.AlertType.ERROR, I18n.get("settings.translation.guide.error")).showAndWait();
        }
        refreshGuideTranslationList();
    }

    /**
     * Deletes one generated guide language directory.
     *
     * <p>Recursive deletion of a user directory deserves a guard rather than trust: the path is
     * re-checked to be a real directory directly beneath the config directory's guide root, so a
     * malformed or symlinked language entry cannot turn this into a delete of something else.
     */
    private static void deleteGeneratedGuideTree(java.nio.file.Path root) throws java.io.IOException {
        java.nio.file.Path guideRoot = GuideLocationResolver
            .generatedRoot(KorTTYApplication.getConfigDirectory()).toAbsolutePath().normalize();
        java.nio.file.Path resolved = root.toAbsolutePath().normalize();
        if (!resolved.getParent().equals(guideRoot)
            || !java.nio.file.Files.isDirectory(resolved, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new java.io.IOException("Refusing to delete " + resolved);
        }
        try (java.util.stream.Stream<java.nio.file.Path> paths = java.nio.file.Files.walk(resolved)) {
            for (java.nio.file.Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                java.nio.file.Files.deleteIfExists(path);
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
            String problem = localAiTranslationProblem();
            new Alert(Alert.AlertType.WARNING, problem != null ? problem
                : I18n.get("settings.translation.error.noKey")).showAndWait();
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
