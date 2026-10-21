package de.kortty.ui;

import de.kortty.KorTTYApplication;
import de.kortty.telemetry.Telemetry;
import de.kortty.telemetry.TelemetryEvents;
import de.kortty.telemetry.TelemetryService;
import de.kortty.ui.I18n;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.SnippetCompletionShortcut;
import de.kortty.core.CredentialManager;
import de.kortty.core.JvmLaunchProfileStore;
import de.kortty.model.JvmResourceProfile;
import de.kortty.core.DynamicLanguageGenerator;
import de.kortty.core.GuideTranslationGenerator;
import de.kortty.core.GuideLocationResolver;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.QuickSelectLabels;
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
import de.kortty.core.OpenAiCompatibleAiService;
import de.kortty.core.AiPromptService;
import de.kortty.core.FailingAiService;
import de.kortty.core.GoogleTranslationService;
import de.kortty.core.DeepLTranslationService;
import de.kortty.core.LibreTranslateTranslationService;
import de.kortty.core.LocalAiTranslationService;
import de.kortty.core.MicrosoftTranslationService;
import de.kortty.core.TerminalAgentCommandSupport;
import de.kortty.core.TerminalEncodingSupport;
import de.kortty.core.TerminalPaletteSupport;
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
import de.kortty.model.SessionRestoreMode;
import de.kortty.model.TerminalAgentExecutionTarget;
import de.kortty.model.Theme;
import de.kortty.model.TranslationApiProvider;
import de.kortty.model.StoredCredential;
import de.kortty.model.GPGKey;
import de.kortty.model.WindowGeometry;
import de.kortty.paste.PastePacer;
import de.kortty.paste.PasteProtectionSettings;
import de.kortty.paste.PasteWarningMode;
import de.kortty.shellintegration.PaneActivityMonitor;
import de.kortty.shellintegration.TerminalNotificationPolicy;
import de.kortty.security.PasswordStrengthChecker;
import de.kortty.security.MasterPasswordManager;
import de.kortty.security.MasterPasswordReEncryptor;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.geometry.VPos;
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
import javafx.util.Duration;

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
    // The 16 ANSI colours, index 0-7 = black, red, green, yellow, blue, magenta, cyan, white.
    private final ColorPicker[] ansiNormalPickers = new ColorPicker[ConnectionSettings.ANSI_COLOR_COUNT];
    private final ColorPicker[] ansiBrightPickers = new ColorPicker[ConnectionSettings.ANSI_COLOR_COUNT];

    // Terminal size
    private final Spinner<Integer> columnsSpinner;
    private final Spinner<Integer> rowsSpinner;
    private final Spinner<Integer> scrollbackSpinner;
    
    // Other settings
    private final ComboBox<com.sithtermfx.ui.settings.BoldTextMode> boldTextModeCombo;
    private final ComboBox<String> encodingCombo;
    /** The Terminal page; built on first selection, so a still-pending build means it was not shown. */
    private final Tab terminalTab;
    private final CheckBox showTerminalScrollbarCheck;
    private final CheckBox commandTimestampsCheck;
    private final CheckBox terminalDragDropCheck;
    private final CheckBox terminalCopyOnSelectCheck;
    private final CheckBox osc52ClipboardWriteCheck;
    private final CheckBox terminalLinkDetectionCheck;
    /** Quick select's label letters and the user's own patterns (Links section); see QuickSelectSettingsSupport. */
    private final TextField quickSelectAlphabetField;
    private final TextArea quickSelectPatternsArea;
    private final Label quickSelectAlphabetError;
    private final Label quickSelectPatternsError;
    private final CheckBox closeActiveTerminalWindowsWithoutConfirmationCheck;
    private final ComboBox<PasteWarningMode> pasteWarningModeCombo;
    private final Spinner<Integer> pasteLargeWarningSpinner;
    private final Spinner<Integer> pasteLineDelaySpinner;
    private final CheckBox shellIntegrationCheck;
    private final ComboBox<de.kortty.model.TerminalRemoteSidebarPosition> remoteSidebarPositionCombo;
    private final CheckBox terminalBellNotificationsCheck;
    private final CheckBox commandFinishedNotificationsCheck;
    private final Spinner<Integer> commandFinishedSecondsSpinner;
    private final CheckBox remoteTerminalNotificationsCheck;
    private final CheckBox aiRunToastsCheck;
    private final Spinner<Integer> terminalSilenceSecondsSpinner;
    private final CheckBox terminalRecordingAlwaysEnabledCheck;
    private final CheckBox terminalRecordingCaptureColorsCheck;
    private final CheckBox codingAgentDetectionCheck;
    private final CheckBox codingAgentNotificationsCheck;
    private final CheckBox codingAgentAppBadgeCheck;
    private final CheckBox codingAgentPanelAutoVisibilityCheck;
    private final CheckBox controlApiEnabledCheck;
    private final Label controlApiStatusLabel;
    private final CheckBox mcpServerEnabledCheck;
    private final CheckBox mcpWriteToolsCheck;
    private final Label mcpHintLabel;
    // Keyword highlighting: master switch, full-screen programs, default rule set
    private final CheckBox terminalHighlightingEnabledCheck;
    private final CheckBox terminalHighlightAlternateScreenCheck;
    private final ComboBox<HighlightSettingsSupport.DefaultSetChoice> defaultHighlightSetCombo;
    private final CheckBox terminalTriggersEnabledCheck;

    // Appearance settings
    private final ComboBox<AppDesign> appDesignCombo;
    private ComboBox<ChatColorProfile> chatColorProfileCombo;
    private CheckBox appDesignAnimationsCheck;
    private Spinner<Integer> uiFontScaleSpinner;
    private CheckBox uiFontScaleAutoCheck;
    
    // Security settings
    private final CheckBox requireMasterPasswordOnStartupCheck;
    private final CheckBox skipMasterPasswordPromptCheck;
    private final CheckBox telemetryEnabledCheck;
    private final CheckBox temporarySshKeyEnabledCheck;
    /** Security › Session isolation: the level of every connection that sets none itself. */
    private ComboBox<de.kortty.isolation.IsolationLevel> isolationDefaultCombo;
    
    // SSH Keep-Alive settings
    private final CheckBox sshKeepAliveCheck;
    private final CheckBox disableHostKeyCheckAllCheck;
    private final Spinner<Integer> sshKeepAliveIntervalSpinner;
    
    // Connection settings
    private final CheckBox connectionRetriesEnabledCheck;
    private final CheckBox autoReconnectEnabledCheck;
    
    // Backup settings
    private final Spinner<Integer> maxBackupSpinner;
    private ComboBox<GlobalSettings.AutoBackupInterval> autoBackupIntervalCombo;
    private TextField autoBackupDirectoryField;
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
    private CheckBox sessionJournalClickableLinksCheck;
    private Spinner<Integer> sessionJournalIntervalSpinner;
    private ComboBox<de.kortty.model.AiProfile> sessionJournalAiProfileCombo;
    private ComboBox<de.kortty.model.AiProfile> automationJournalAiProfileCombo;

    // Update settings
    private final CheckBox updateChecksEnabledCheck;
    private final Slider updateCheckIntervalSlider;
    private final Label updateCheckIntervalValueLabel;
    
    // Window settings
    private final CheckBox rememberWindowGeometryCheck;
    private final Slider toolbarIconSizeSlider;
    private final CheckBox rememberDashboardStateCheck;
    private final CheckBox openToolWindowsAsTabsCheck;
    private final CheckBox connectionColorBorderCheck;
    private final CheckBox tabTitleFromShellCheck;
    private final CheckBox tabSwitchMostRecentFirstCheck;
    private final ComboBox<SessionRestoreMode> sessionRestoreModeCombo;
    private final CheckBox sessionRestoreScrollbackCheck;
    private final Spinner<Integer> sessionRestoreScrollbackLinesSpinner;
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
    private final ListView<Locale> translationGeneratedList;
    private Label translationOutdatedLabelRef;
    private Button translationRegenerateOutdatedButtonRef;
    // Guide translation runs for hours on a local model, so unlike the interface-string
    // generation it needs a progress bar and a cancel button of its own.
    private ListView<String> guideTranslationList;
    private TranslationProfilesPane translationProfilesPane;
    private Label translationRunIndicator;
    private Runnable translationRunListener;

    // AI settings
    private static final String DEFAULT_AI_API_URL = "https://api.openai.com/v1/chat/completions";
    private static final String AI_MODEL_DEFAULT_LABEL = I18n.get("ai.model.default");
    private static final String AI_MODEL_AUTO_LABEL = I18n.get("ai.model.auto");
    private static final String AI_MODEL_CUSTOM_LABEL = I18n.get("settings.ai.cli.model.custom");
    /** Opt-out from masking secrets; enabled only for an HTTP profile with a loopback URL. */
    /** Set while the AI editor form is being populated; suppresses form-driven writes into the profile. */
    private boolean loadingAiProfile;
    /** Discovery key of the automatic metadata lookup that is scheduled or running, or {@code null}. */
    private String pendingAiReasoningDiscoveryKey;
    /** Keys already looked up automatically, so an endpoint that cannot answer is asked only once. */
    private final java.util.Set<String> attemptedAiReasoningDiscoveryKeys = new java.util.HashSet<>();
    /**
     * Holds the automatic lookup back until the editor settles. The model field rebuilds the picker
     * on every keystroke, and each half-typed model name is a key of its own — without this, typing
     * one name would send a request per character.
     */
    /** Set while {@link #refreshAiReasoningOptions} repopulates the picker; its own select() is not a user pick. */
    private boolean rebuildingAiReasoningOptions;
    /** Shown below the internet combo while a native Anthropic Messages URL locks it to Disabled. */
    private AiProfile selectedAiProfile;

    private TabPane mainTabPane;
    private SettingsSearchBar settingsSearch;

    // Keyboard page: built on first selection from the actions of the window that opened the dialog.
    private Tab keyboardTab;
    private KeyboardSettingsPage keyboardPage;
    private Supplier<KeyboardSettingsModel.Catalog> keymapCatalogSource = () -> null;

    // SFTP settings
    private final CheckBox sftpAutoCloseEnabledCheck;
    private final Spinner<Integer> sftpAutoCloseMinutesSpinner;
    private final TextField sftpDefaultZipPathField;
    private final Spinner<Integer> sftpDefaultZipCompressionSpinner;
    private final TextField jobSchedulerRsyncBinaryPathField;
    private final Spinner<Integer> sftpParallelTransfersSpinner;
    private final ComboBox<de.kortty.model.SftpConflictDefault> sftpConflictDefaultCombo;
    private final CheckBox sftpResumePartialTransfersCheck;
    private final CheckBox sftpKeepPartialOnCancelCheck;
    private final TextField sftpExternalEditorCommandField;
    
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
    private final TextField snippetCompletionShortcutField;
    private final CheckBox snippetPrewarmCheck;
    private final Spinner<Integer> snippetAnalysisHistorySpinner;
    private final SnippetAnalysisContentLimitControl snippetAnalysisContentLimit;
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
        Tab appearanceTab = sectionTab(SettingsSection.APPEARANCE);
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

        // UI font size. Not editable on purpose — unlike the terminal font spinner, this one resizes
        // the dialog the user is standing in, so arrows-only rules out both a typed value that never
        // commits and an accidental extreme that makes Settings hard to navigate back out of.
        uiFontScaleSpinner = new Spinner<>(GlobalSettings.MIN_UI_FONT_SCALE_PERCENT,
            GlobalSettings.MAX_UI_FONT_SCALE_PERCENT,
            globalSettings != null
                ? globalSettings.getUiFontScalePercent()
                : GlobalSettings.UI_FONT_SCALE_DEFAULT_PERCENT,
            5);
        uiFontScaleSpinner.setEditable(false);
        uiFontScaleSpinner.setPrefWidth(90);
        uiFontScaleSpinner.setTooltip(new Tooltip(I18n.get("settings.appearance.uiFontScale.tooltip")));

        uiFontScaleAutoCheck = new CheckBox(I18n.get("settings.appearance.uiFontScale.auto"));
        uiFontScaleAutoCheck.setSelected(globalSettings != null && globalSettings.isUiFontScaleAuto());
        uiFontScaleAutoCheck.setTooltip(new Tooltip(I18n.get("settings.appearance.uiFontScale.auto.tooltip")));
        // With auto on the stored percent is ignored, so show the value the screen actually yields
        // rather than leaving a stale manual number under a disabled control.
        uiFontScaleSpinner.disableProperty().bind(uiFontScaleAutoCheck.selectedProperty());
        uiFontScaleAutoCheck.selectedProperty().addListener((obs, wasAuto, isAuto) -> {
            if (Boolean.TRUE.equals(isAuto)) {
                uiFontScaleSpinner.getValueFactory().setValue(UiFontScaleSupport.autoPercentForPrimaryScreen());
            } else if (globalSettings != null) {
                uiFontScaleSpinner.getValueFactory().setValue(globalSettings.getUiFontScalePercent());
            }
        });
        if (uiFontScaleAutoCheck.isSelected()) {
            uiFontScaleSpinner.getValueFactory().setValue(UiFontScaleSupport.autoPercentForPrimaryScreen());
        }

        // Without the unit the number reads as a font size in points rather than a scale factor.
        Label uiFontScaleUnit = new Label("%");
        HBox uiFontScaleValue = new HBox(4, uiFontScaleSpinner, uiFontScaleUnit);
        uiFontScaleValue.setAlignment(Pos.CENTER_LEFT);

        HBox uiFontScaleControls = new HBox(8,
            new Label(I18n.get("settings.appearance.uiFontScale")), uiFontScaleValue, uiFontScaleAutoCheck);
        uiFontScaleControls.setAlignment(Pos.CENTER_LEFT);

        Label uiFontScaleInfo = new Label(I18n.get("settings.appearance.uiFontScale.info"));
        uiFontScaleInfo.setWrapText(true);
        uiFontScaleInfo.setMaxWidth(460);
        uiFontScaleInfo.getStyleClass().add("settings-info-label");
        uiFontScaleInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");

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
            ? "-fx-font-size: 0.8462em; -fx-text-fill: gray;"
            : "-fx-font-size: 0.8462em;");

        Label appDesignPreviewLabel = new Label(I18n.get("settings.appearance.preview"));
        appDesignPreviewLabel.getStyleClass().add("settings-info-label");

        ImageView appDesignPreviewImage = createAppDesignPreviewImage();
        // Shown centered in place of the image for designs without a preview (e.g. Default).
        Label appDesignPreviewPlaceholder = new Label(I18n.get("settings.appearance.preview.none"));
        appDesignPreviewPlaceholder.getStyleClass().add("settings-info-label");
        appDesignPreviewPlaceholder.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
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
            uiFontScaleControls, uiFontScaleInfo, chatColorProfileControls, appearanceInfo, appDesignPreviewBox);
        appearanceContent.setPadding(new Insets(20));
        // This tab holds a fixed-height preview box below its controls, so it is the one tab with no
        // slack left: at a large UI font size the stack outgrows the dialog. Scrolling keeps every
        // control reachable instead of pushing the preview off the bottom edge.
        ScrollPane appearanceScroll = new ScrollPane(appearanceContent);
        appearanceScroll.setFitToWidth(true);
        appearanceScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        appearanceScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        // Tab contents are attached on first selection (LazyTabContent): TabPaneSkin keeps every
        // tab's content in the scene graph, so with 20 eager tabs the dialog's show() styled and laid
        // out ~700 controls of which one tab's worth is visible. The controls themselves are still
        // built here, so applySettings() reads every field exactly as before.
        LazyTabContent.defer(appearanceTab, appearanceScroll);
        
        // Font tab
        Tab fontTab = sectionTab(SettingsSection.FONT);
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
        
        LazyTabContent.defer(fontTab, fontGrid);
        
        // Colors tab
        Tab colorsTab = sectionTab(SettingsSection.COLORS);
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
        // The choice has a stored field of its own; the cursor style is only how the terminal
        // consumes it, and color profiles own that string (see GlobalSettings.terminalCursorBlink).
        cursorBlinkCheck.setSelected(globalSettings != null
                ? globalSettings.isTerminalCursorBlink()
                : isCursorBlink(settings.getCursorStyle()));
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
        
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            // Show what the terminal draws: the stored colours once customised, the built-in palette before.
            ColorPicker normalPicker = new ColorPicker(Color.web(TerminalPaletteSupport.effectiveHex(settings, i, false)));
            normalPicker.setPrefWidth(40);
            normalPicker.setStyle("-fx-color-label-visible: false;");
            Tooltip.install(normalPicker, new Tooltip(colorNames[i]));
            normalColorsBox.getChildren().add(normalPicker);
            ansiNormalPickers[i] = normalPicker;

            ColorPicker brightPicker = new ColorPicker(Color.web(TerminalPaletteSupport.effectiveHex(settings, i, true)));
            brightPicker.setPrefWidth(40);
            brightPicker.setStyle("-fx-color-label-visible: false;");
            Tooltip.install(brightPicker, new Tooltip(colorNames[i] + " " + I18n.get("color.bright")));
            brightColorsBox.getChildren().add(brightPicker);
            ansiBrightPickers[i] = brightPicker;
        }
        normalColorsBox.disableProperty().bind(terminalColorsEnabledCheck.selectedProperty().not());
        brightColorsBox.disableProperty().bind(terminalColorsEnabledCheck.selectedProperty().not());
        
        colorsGrid.add(new Label(I18n.get("settings.colors.normal")), 0, 9);
        colorsGrid.add(normalColorsBox, 1, 9);
        colorsGrid.add(new Label(I18n.get("settings.colors.bright")), 0, 10);
        colorsGrid.add(brightColorsBox, 1, 10);
        
        LazyTabContent.defer(colorsTab, colorsGrid);
        
        // Terminal tab
        terminalTab = sectionTab(SettingsSection.TERMINAL);
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
        
        boldTextModeCombo = new ComboBox<>();
        boldTextModeCombo.getItems().setAll(com.sithtermfx.ui.settings.BoldTextMode.values());
        boldTextModeCombo.setValue(TerminalPaletteSupport.boldTextMode(settings));
        boldTextModeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(com.sithtermfx.ui.settings.BoldTextMode mode) {
                return mode != null ? I18n.get(boldTextModeKey(mode)) : "";
            }

            @Override
            public com.sithtermfx.ui.settings.BoldTextMode fromString(String text) {
                return null;
            }
        });
        boldTextModeCombo.setTooltip(new Tooltip(I18n.get("settings.terminal.boldText.tooltip")));
        
        encodingCombo = new ComboBox<>();
        encodingCombo.getItems().addAll(TerminalEncodingSupport.offeredEncodings(settings.getEncoding()));
        String storedEncoding = TerminalEncodingSupport.displayName(settings.getEncoding());
        encodingCombo.setValue(storedEncoding != null ? storedEncoding : TerminalEncodingSupport.SUPPORTED_ENCODINGS.get(0));
        encodingCombo.setTooltip(new Tooltip(I18n.get("settings.terminal.encoding.tooltip")));
        // A value stored while the setting still had no effect applies only once this page is saved.
        Label encodingPendingHint = new Label(I18n.get("settings.terminal.encoding.pendingHint"));
        encodingPendingHint.setWrapText(true);
        encodingPendingHint.setPrefWidth(UiFontScaleSupport.scaleDimension(420, true));
        encodingPendingHint.setMinHeight(Region.USE_PREF_SIZE); // wrap instead of ellipsizing
        encodingPendingHint.setStyle(MutedTextStyle.HINT);
        boolean encodingPending = TerminalEncodingSupport.isGlobalEncodingPending(globalSettings);
        encodingPendingHint.setVisible(encodingPending);
        encodingPendingHint.setManaged(encodingPending);
        VBox encodingBox = new VBox(4, encodingCombo, encodingPendingHint);
        
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

        // Programs copying to the clipboard with OSC 52 (vim, tmux on a server): off unless allowed.
        osc52ClipboardWriteCheck = new CheckBox(I18n.get("settings.terminal.osc52.enabled"));
        osc52ClipboardWriteCheck.setSelected(globalSettings != null && globalSettings.isOsc52ClipboardWriteEnabled());
        osc52ClipboardWriteCheck.setTooltip(new Tooltip(I18n.get("settings.terminal.osc52.enabled.tooltip")));
        // After setTooltip: the lock replaces it with the managed-by-your-organization hint. Only a policy
        // that forbids OSC 52 locks the box (off, as PolicyClamp keeps it); allowing it leaves the choice.
        de.kortty.policy.PolicyUiSupport.lockIf(osc52ClipboardWriteCheck,
            !de.kortty.policy.PolicyManager.effective().osc52ClipboardWriteAllowed());

        terminalLinkDetectionCheck = new CheckBox(I18n.get("settings.terminal.linkDetection"));
        terminalLinkDetectionCheck.setSelected(globalSettings == null || globalSettings.isTerminalLinkDetectionEnabled());
        terminalLinkDetectionCheck.setTooltip(new Tooltip(I18n.get("settings.terminal.linkDetection.tooltip")));

        // Quick select: the label letters and the user's own patterns. Checked as they are typed; a
        // problem shows under the field and keeps Save from closing the dialog.
        quickSelectAlphabetField = new TextField(globalSettings != null && globalSettings.getTerminalQuickSelectAlphabet() != null
            ? globalSettings.getTerminalQuickSelectAlphabet() : "");
        quickSelectAlphabetField.setPromptText(QuickSelectLabels.DEFAULT_ALPHABET);
        quickSelectAlphabetField.setPrefColumnCount(26);
        quickSelectAlphabetField.setMaxWidth(Region.USE_PREF_SIZE);
        quickSelectAlphabetField.setTooltip(new Tooltip(I18n.get(QuickSelectSettingsSupport.ALPHABET_TOOLTIP_KEY)));
        quickSelectPatternsArea = new TextArea(QuickSelectSettingsSupport.patternsText(
            globalSettings != null ? globalSettings.getTerminalQuickSelectPatterns() : List.of()));
        quickSelectPatternsArea.setPromptText(I18n.get(QuickSelectSettingsSupport.PATTERNS_PROMPT_KEY));
        quickSelectPatternsArea.setPrefRowCount(4);
        quickSelectPatternsArea.setPrefColumnCount(40);
        quickSelectPatternsArea.setMaxWidth(UiFontScaleSupport.scaleDimension(560, true));
        quickSelectPatternsArea.setWrapText(false);
        quickSelectPatternsArea.setStyle("-fx-font-family: monospace;");
        quickSelectPatternsArea.setTooltip(new Tooltip(I18n.get(QuickSelectSettingsSupport.PATTERNS_TOOLTIP_KEY)));
        quickSelectAlphabetError = quickSelectErrorLabel();
        quickSelectPatternsError = quickSelectErrorLabel();
        quickSelectAlphabetField.textProperty().addListener((obs, was, now) -> validateQuickSelectFields());
        quickSelectPatternsArea.textProperty().addListener((obs, was, now) -> validateQuickSelectFields());
        validateQuickSelectFields();

        closeActiveTerminalWindowsWithoutConfirmationCheck = new CheckBox(I18n.get("settings.terminal.closeActiveWithoutConfirmation"));
        closeActiveTerminalWindowsWithoutConfirmationCheck.setSelected(globalSettings != null
            && globalSettings.isCloseActiveTerminalWindowsWithoutConfirmation());
        closeActiveTerminalWindowsWithoutConfirmationCheck.setTooltip(
            new Tooltip(I18n.get("settings.terminal.closeActiveWithoutConfirmation.tooltip"))
        );

        // Paste protection: when a terminal paste asks before it reaches the pane.
        // [rule.terminal] paste-warning is a floor: only the modes that ask at least as often are offered.
        PasteWarningMode pasteWarningFloor = de.kortty.policy.PolicyManager.effective().pasteWarningFloor();
        pasteWarningModeCombo = new ComboBox<>();
        pasteWarningModeCombo.getItems().setAll(PasteWarningMode.atLeast(pasteWarningFloor));
        pasteWarningModeCombo.setValue(PasteWarningMode.mostRestrictive(globalSettings != null
            ? globalSettings.getPasteWarningMode() : PasteWarningMode.DEFAULT, pasteWarningFloor));
        pasteWarningModeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(PasteWarningMode mode) {
                return mode != null ? I18n.get(pasteWarningModeKey(mode)) : "";
            }

            @Override
            public PasteWarningMode fromString(String text) {
                return null;
            }
        });
        pasteWarningModeCombo.setTooltip(new Tooltip(I18n.get("settings.terminal.paste.warningMode.tooltip")));
        // After setTooltip: under [rule.terminal] paste-warning the combo carries the managed hint and
        // offers only the floor and stricter modes; it is locked when nothing stricter is left (always).
        if (de.kortty.policy.PolicyManager.effective().isManaged(de.kortty.policy.ManagedSetting.PASTE_WARNING)) {
            if (!de.kortty.policy.PolicyUiSupport.lockIf(pasteWarningModeCombo,
                    pasteWarningModeCombo.getItems().size() <= 1)) {
                pasteWarningModeCombo.setTooltip(
                    new Tooltip(de.kortty.policy.PolicyUiSupport.managedByOrganizationText()));
            }
        }
        pasteLargeWarningSpinner = new Spinner<>(0, PasteProtectionSettings.MAX_LARGE_WARNING_KIB,
            globalSettings != null ? globalSettings.getPasteLargeWarningKiB()
                : PasteProtectionSettings.DEFAULT_LARGE_WARNING_KIB);
        pasteLargeWarningSpinner.setEditable(true);
        pasteLargeWarningSpinner.setPrefWidth(100);
        pasteLargeWarningSpinner.setTooltip(new Tooltip(I18n.get("settings.terminal.paste.largeWarning.tooltip")));
        pasteLineDelaySpinner = new Spinner<>(0, PastePacer.MAX_LINE_DELAY_MS,
            globalSettings != null ? globalSettings.getPasteLineDelayMs() : 0, 10);
        pasteLineDelaySpinner.setEditable(true);
        pasteLineDelaySpinner.setPrefWidth(100);
        pasteLineDelaySpinner.setTooltip(new Tooltip(I18n.get("settings.terminal.paste.lineDelay.tooltip")));

        // Shell integration: OSC 133 command marks and prompt navigation.
        shellIntegrationCheck = new CheckBox(I18n.get("settings.terminal.shellIntegration.enabled"));
        shellIntegrationCheck.setSelected(globalSettings == null || globalSettings.isShellIntegrationEnabled());
        shellIntegrationCheck.setTooltip(new Tooltip(I18n.get("settings.terminal.shellIntegration.enabled.tooltip")));

        // Remote files sidebar (D10): hidden by default; follows the shell of the focused SSH pane.
        remoteSidebarPositionCombo = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
            de.kortty.model.TerminalRemoteSidebarPosition.values()));
        remoteSidebarPositionCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(de.kortty.model.TerminalRemoteSidebarPosition position) {
                if (position == null) {
                    return "";
                }
                return I18n.get("settings.terminal.remoteSidebar." + position.name().toLowerCase(java.util.Locale.ROOT));
            }

            @Override
            public de.kortty.model.TerminalRemoteSidebarPosition fromString(String text) {
                return null;
            }
        });
        remoteSidebarPositionCombo.setValue(globalSettings != null
            ? globalSettings.getTerminalRemoteSidebarPosition() : de.kortty.model.TerminalRemoteSidebarPosition.HIDDEN);
        remoteSidebarPositionCombo.setTooltip(new Tooltip(I18n.get("settings.terminal.remoteSidebar.tooltip")));

        // Notifications: a bell in a tab the user is not looking at. An enterprise policy that denies
        // desktop notifications locks every notification switch off; an allow leaves them with the user.
        boolean desktopNotificationsDenied =
            !de.kortty.policy.PolicyManager.effective().desktopNotificationsAllowed();
        terminalBellNotificationsCheck = new CheckBox(I18n.get("settings.messaging.bell"));
        terminalBellNotificationsCheck.setSelected(globalSettings != null
            && globalSettings.isTerminalBellNotificationsEnabled());
        terminalBellNotificationsCheck.setTooltip(new Tooltip(I18n.get("settings.messaging.bell.tooltip")));
        // ... and a long command the shell marked finishing there; needs shell integration.
        commandFinishedNotificationsCheck = new CheckBox(I18n.get("settings.messaging.commandFinished"));
        commandFinishedNotificationsCheck.setSelected(globalSettings == null
            || globalSettings.isCommandFinishedNotificationsEnabled());
        commandFinishedNotificationsCheck.setTooltip(
            new Tooltip(I18n.get("settings.messaging.commandFinished.tooltip")));
        commandFinishedSecondsSpinner = new Spinner<>(TerminalNotificationPolicy.MIN_COMMAND_FINISHED_SECONDS,
            TerminalNotificationPolicy.MAX_COMMAND_FINISHED_SECONDS,
            globalSettings != null ? globalSettings.getCommandFinishedNotificationSeconds()
                : TerminalNotificationPolicy.DEFAULT_COMMAND_FINISHED_SECONDS);
        commandFinishedSecondsSpinner.setEditable(true);
        commandFinishedSecondsSpinner.setPrefWidth(100);
        commandFinishedSecondsSpinner.setTooltip(
            new Tooltip(I18n.get("settings.messaging.commandFinishedSeconds.tooltip")));
        // Without shell integration no command is ever marked finished: show that the two do nothing.
        Runnable syncCommandFinishedControls = () -> {
            boolean marksRead = shellIntegrationCheck.isSelected();
            commandFinishedNotificationsCheck.setDisable(!marksRead || desktopNotificationsDenied);
            commandFinishedSecondsSpinner.setDisable(!marksRead || desktopNotificationsDenied);
        };
        shellIntegrationCheck.selectedProperty().addListener((obs, was, now) -> syncCommandFinishedControls.run());
        syncCommandFinishedControls.run();
        // ... and a program there asking for a notification (OSC 9, OSC 777); needs no shell integration.
        remoteTerminalNotificationsCheck = new CheckBox(I18n.get("settings.messaging.remote"));
        remoteTerminalNotificationsCheck.setSelected(globalSettings == null
            || globalSettings.isRemoteTerminalNotificationsEnabled());
        remoteTerminalNotificationsCheck.setTooltip(new Tooltip(I18n.get("settings.messaging.remote.tooltip")));
        // ... and korTTY's own AI runs (terminal agent, swarm) finishing or waiting for an approval.
        aiRunToastsCheck = new CheckBox(I18n.get("settings.messaging.aiRun"));
        aiRunToastsCheck.setSelected(globalSettings == null || globalSettings.isAiRunToastsEnabled());
        aiRunToastsCheck.setTooltip(new Tooltip(I18n.get("settings.messaging.aiRun.tooltip")));
        de.kortty.policy.PolicyUiSupport.lockIf(terminalBellNotificationsCheck, desktopNotificationsDenied);
        de.kortty.policy.PolicyUiSupport.lockIf(commandFinishedNotificationsCheck, desktopNotificationsDenied);
        de.kortty.policy.PolicyUiSupport.lockIf(remoteTerminalNotificationsCheck, desktopNotificationsDenied);
        de.kortty.policy.PolicyUiSupport.lockIf(aiRunToastsCheck, desktopNotificationsDenied);
        // ... and how long a tab watched for silence (its right-click menu) has to stay silent.
        terminalSilenceSecondsSpinner = new Spinner<>(PaneActivityMonitor.MIN_SILENCE_SECONDS,
            PaneActivityMonitor.MAX_SILENCE_SECONDS,
            globalSettings != null ? globalSettings.getTerminalSilenceSeconds()
                : PaneActivityMonitor.DEFAULT_SILENCE_SECONDS);
        terminalSilenceSecondsSpinner.setEditable(true);
        terminalSilenceSecondsSpinner.setPrefWidth(100);
        terminalSilenceSecondsSpinner.setTooltip(
            new Tooltip(I18n.get("settings.messaging.silenceSeconds.tooltip")));
        
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

        autoReconnectEnabledCheck = new CheckBox(I18n.get("settings.connection.autoReconnect"));
        autoReconnectEnabledCheck.setSelected(globalSettings != null ? globalSettings.isAutoReconnectEnabled() : true);
        autoReconnectEnabledCheck.setTooltip(new Tooltip(I18n.get("settings.connection.autoReconnect.tooltip")));

        // Coding-agent detection (local shell tabs)
        codingAgentDetectionCheck = new CheckBox(I18n.get("settings.codingAgent.detectionEnabled"));
        codingAgentDetectionCheck.setSelected(globalSettings == null || globalSettings.isCodingAgentDetectionEnabled());
        codingAgentDetectionCheck.setTooltip(new Tooltip(I18n.get("settings.codingAgent.detectionEnabled.tooltip")));
        codingAgentPanelAutoVisibilityCheck = new CheckBox(I18n.get("settings.codingAgent.panelAutoVisibility"));
        codingAgentPanelAutoVisibilityCheck.setSelected(
            globalSettings == null || globalSettings.isCodingAgentPanelAutoVisibility());
        codingAgentPanelAutoVisibilityCheck.setTooltip(
            new Tooltip(I18n.get("settings.codingAgent.panelAutoVisibility.tooltip")));
        codingAgentNotificationsCheck = new CheckBox(I18n.get("settings.messaging.codingAgent"));
        codingAgentNotificationsCheck.setSelected(
            globalSettings == null || globalSettings.isCodingAgentNotificationsEnabled());
        codingAgentNotificationsCheck.setTooltip(
            new Tooltip(I18n.get("settings.messaging.codingAgent.tooltip")));
        de.kortty.policy.PolicyUiSupport.lockIf(codingAgentNotificationsCheck,
            !de.kortty.policy.PolicyManager.effective().desktopNotificationsAllowed());
        codingAgentAppBadgeCheck = new CheckBox(I18n.get("settings.codingAgent.appBadgeEnabled"));
        codingAgentAppBadgeCheck.setSelected(globalSettings == null || globalSettings.isCodingAgentAppBadgeEnabled());
        codingAgentAppBadgeCheck.setTooltip(new Tooltip(I18n.get("settings.codingAgent.appBadgeEnabled.tooltip")));

        controlApiEnabledCheck = new CheckBox(I18n.get("settings.controlApi.enabled"));
        controlApiEnabledCheck.setSelected(globalSettings != null && globalSettings.isControlApiEnabled());
        controlApiEnabledCheck.setTooltip(new Tooltip(I18n.get("settings.controlApi.enabled.tooltip")));
        // After setTooltip, never before: lockIfManaged replaces the tooltip with the managed-by-your-
        // organization hint, and the user needs to see the reason the box cannot be ticked.
        de.kortty.policy.PolicyUiSupport.lockIfManaged(
            controlApiEnabledCheck, de.kortty.policy.ManagedSetting.CONTROL_API);
        controlApiStatusLabel = new Label(controlApiStatusText());
        controlApiStatusLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        controlApiStatusLabel.setWrapText(true);

        // The MCP server (kortty-cli mcp) rides on the Control API; both switches are off by default.
        mcpServerEnabledCheck = new CheckBox(I18n.get("settings.controlApi.mcpServer"));
        mcpServerEnabledCheck.setSelected(globalSettings != null && globalSettings.isMcpServerEnabled());
        mcpServerEnabledCheck.setTooltip(new Tooltip(I18n.get("settings.controlApi.mcpServer.tooltip")));
        mcpWriteToolsCheck = new CheckBox(I18n.get("settings.controlApi.mcpWriteTools"));
        mcpWriteToolsCheck.setSelected(globalSettings != null && globalSettings.isMcpWriteToolsEnabled());
        mcpWriteToolsCheck.setTooltip(new Tooltip(I18n.get("settings.controlApi.mcpWriteTools.tooltip")));
        // Locked only when the policy denies the MCP server; an allow leaves both switches with the
        // user, so a merely managed setting does not grey them out.
        boolean mcpPolicyAllowed = de.kortty.policy.PolicyManager.effective().mcpServerAllowed();
        de.kortty.policy.PolicyUiSupport.lockIf(mcpServerEnabledCheck, !mcpPolicyAllowed);
        de.kortty.policy.PolicyUiSupport.lockIf(mcpWriteToolsCheck, !mcpPolicyAllowed);
        mcpHintLabel = new Label();
        mcpHintLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        mcpHintLabel.setWrapText(true);
        controlApiEnabledCheck.selectedProperty().addListener((obs, was, now) -> refreshMcpControls());
        mcpServerEnabledCheck.selectedProperty().addListener((obs, was, now) -> refreshMcpControls());
        refreshMcpControls();
        
        // Keyword highlighting. The defaults (on, not in full-screen programs, no default set) mean
        // nothing is highlighted until a set is chosen here, in a menu or with the shortcut.
        terminalHighlightingEnabledCheck = new CheckBox(I18n.get(HighlightSettingsSupport.ENABLED_KEY));
        terminalHighlightingEnabledCheck.setSelected(globalSettings == null || globalSettings.isTerminalHighlightingEnabled());
        terminalHighlightingEnabledCheck.setTooltip(new Tooltip(I18n.get(HighlightSettingsSupport.ENABLED_TOOLTIP_KEY)));
        terminalHighlightAlternateScreenCheck = new CheckBox(I18n.get(HighlightSettingsSupport.ALTERNATE_SCREEN_KEY));
        terminalHighlightAlternateScreenCheck.setSelected(
            globalSettings != null && globalSettings.isTerminalHighlightAlternateScreen());
        terminalHighlightAlternateScreenCheck.setTooltip(
            new Tooltip(I18n.get(HighlightSettingsSupport.ALTERNATE_SCREEN_TOOLTIP_KEY)));
        de.kortty.core.highlight.TerminalHighlightService highlightService =
            app != null ? app.getTerminalHighlightService() : null;
        String storedDefaultHighlightSet = globalSettings != null ? globalSettings.getDefaultHighlightRuleSetId() : null;
        java.util.List<HighlightSettingsSupport.DefaultSetChoice> defaultHighlightSetChoices =
            HighlightSettingsSupport.defaultSetChoices(HighlightSettingsSupport.selectableSetIds(highlightService),
                highlightService != null ? highlightService::userSetName : null, storedDefaultHighlightSet);
        defaultHighlightSetCombo = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(defaultHighlightSetChoices));
        defaultHighlightSetCombo.setValue(
            HighlightSettingsSupport.selected(defaultHighlightSetChoices, storedDefaultHighlightSet));
        defaultHighlightSetCombo.setTooltip(new Tooltip(I18n.get(HighlightSettingsSupport.DEFAULT_SET_TOOLTIP_KEY)));
        // With the master switch off, neither of the other two has any effect.
        terminalHighlightAlternateScreenCheck.disableProperty().bind(terminalHighlightingEnabledCheck.selectedProperty().not());
        defaultHighlightSetCombo.disableProperty().bind(terminalHighlightingEnabledCheck.selectedProperty().not());
        // Triggers: what a highlight rule may do besides coloring (a desktop notification, a snippet). The policy
        // key terminal-triggers locks the box; otherwise it follows the master switch, as rules only run
        // while highlighting is on.
        terminalTriggersEnabledCheck = new CheckBox(I18n.get(HighlightSettingsSupport.TRIGGERS_KEY));
        terminalTriggersEnabledCheck.setSelected(globalSettings == null || globalSettings.isTerminalTriggersEnabled());
        terminalTriggersEnabledCheck.setTooltip(new Tooltip(I18n.get(HighlightSettingsSupport.TRIGGERS_TOOLTIP_KEY)));
        // After setTooltip: lockIfManaged replaces it with the managed-by-your-organization hint. A locked
        // box must not be bound as well, or setDisable(true) would fail on the bound property.
        if (!de.kortty.policy.PolicyUiSupport.lockIfManaged(
                terminalTriggersEnabledCheck, de.kortty.policy.ManagedSetting.TERMINAL_TRIGGERS)) {
            terminalTriggersEnabledCheck.disableProperty().bind(terminalHighlightingEnabledCheck.selectedProperty().not());
        }

        // Rows are numbered by a counter, as on the Window tab, so a section can be inserted
        // anywhere without renumbering every row below it.
        int terminalRow = 0;

        terminalGrid.add(new Label(I18n.get("settings.terminal.columns")), 0, terminalRow);
        terminalGrid.add(columnsSpinner, 1, terminalRow++);
        terminalGrid.add(new Label(I18n.get("settings.terminal.rows")), 0, terminalRow);
        terminalGrid.add(rowsSpinner, 1, terminalRow++);
        terminalGrid.add(new Label(I18n.get("settings.terminal.scrollback")), 0, terminalRow);
        terminalGrid.add(scrollbackSpinner, 1, terminalRow++);
        Label encodingLabel = new Label(I18n.get("settings.terminal.encoding"));
        // Line the label up with the dropdown, not with the middle of dropdown and hint.
        GridPane.setValignment(encodingLabel, VPos.BASELINE);
        GridPane.setValignment(encodingBox, VPos.BASELINE);
        terminalGrid.add(encodingLabel, 0, terminalRow);
        terminalGrid.add(encodingBox, 1, terminalRow++);
        terminalGrid.add(new Label(I18n.get("settings.terminal.boldText")), 0, terminalRow);
        terminalGrid.add(boldTextModeCombo, 1, terminalRow++);
        terminalGrid.add(showTerminalScrollbarCheck, 0, terminalRow++, 2, 1);
        terminalGrid.add(commandTimestampsCheck, 0, terminalRow++, 2, 1);
        terminalGrid.add(terminalDragDropCheck, 0, terminalRow++, 2, 1);
        terminalGrid.add(terminalCopyOnSelectCheck, 0, terminalRow++, 2, 1);
        terminalGrid.add(osc52ClipboardWriteCheck, 0, terminalRow++, 2, 1);
        terminalGrid.add(closeActiveTerminalWindowsWithoutConfirmationCheck, 0, terminalRow++, 2, 1);

        // Keyword highlighting section
        terminalGrid.add(new Separator(), 0, terminalRow++, 2, 1);
        Label highlightingHeader = new Label(I18n.get(HighlightSettingsSupport.HEADER_KEY));
        highlightingHeader.setStyle("-fx-font-weight: bold;");
        terminalGrid.add(highlightingHeader, 0, terminalRow++, 2, 1);
        terminalGrid.add(terminalHighlightingEnabledCheck, 0, terminalRow++, 2, 1);
        terminalGrid.add(terminalHighlightAlternateScreenCheck, 0, terminalRow++, 2, 1);
        terminalGrid.add(new Label(I18n.get(HighlightSettingsSupport.DEFAULT_SET_KEY)), 0, terminalRow);
        // The editor stays reachable with the master switch off: rule sets can be prepared at any time.
        Button editHighlightRulesButton = new Button(I18n.get(HighlightSettingsSupport.EDIT_RULES_KEY));
        editHighlightRulesButton.setTooltip(new Tooltip(I18n.get(HighlightSettingsSupport.EDIT_RULES_TOOLTIP_KEY)));
        editHighlightRulesButton.setOnAction(event -> editHighlightRules());
        editHighlightRulesButton.setMinWidth(Region.USE_PREF_SIZE);
        HBox defaultHighlightSetRow = new HBox(8, defaultHighlightSetCombo, editHighlightRulesButton);
        defaultHighlightSetRow.setAlignment(javafx.geometry.Pos.CENTER_LEFT);
        terminalGrid.add(defaultHighlightSetRow, 1, terminalRow++);
        terminalGrid.add(terminalTriggersEnabledCheck, 0, terminalRow++, 2, 1);
        Label highlightingInfo = new Label(I18n.get(HighlightSettingsSupport.INFO_KEY));
        highlightingInfo.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        highlightingInfo.setWrapText(true);
        terminalGrid.add(highlightingInfo, 0, terminalRow++, 2, 1);

        // Links section
        terminalGrid.add(new Separator(), 0, terminalRow++, 2, 1);
        Label linksHeader = new Label(I18n.get("settings.terminal.links.header"));
        linksHeader.setStyle("-fx-font-weight: bold;");
        terminalGrid.add(linksHeader, 0, terminalRow++, 2, 1);
        terminalGrid.add(terminalLinkDetectionCheck, 0, terminalRow++, 2, 1);
        Label quickSelectAlphabetLabel = new Label(I18n.get(QuickSelectSettingsSupport.ALPHABET_KEY));
        GridPane.setValignment(quickSelectAlphabetLabel, VPos.TOP);
        quickSelectAlphabetLabel.setPadding(new Insets(4, 0, 0, 0));
        terminalGrid.add(quickSelectAlphabetLabel, 0, terminalRow);
        terminalGrid.add(new VBox(4, quickSelectAlphabetField, quickSelectAlphabetError), 1, terminalRow++);
        Label quickSelectPatternsLabel = new Label(I18n.get(QuickSelectSettingsSupport.PATTERNS_KEY));
        GridPane.setValignment(quickSelectPatternsLabel, VPos.TOP);
        quickSelectPatternsLabel.setPadding(new Insets(4, 0, 0, 0));
        terminalGrid.add(quickSelectPatternsLabel, 0, terminalRow);
        terminalGrid.add(new VBox(4, quickSelectPatternsArea, quickSelectPatternsError), 1, terminalRow++);
        Label quickSelectInfo = new Label(I18n.get(QuickSelectSettingsSupport.INFO_KEY));
        quickSelectInfo.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        quickSelectInfo.setWrapText(true);
        terminalGrid.add(quickSelectInfo, 0, terminalRow++, 2, 1);

        // Paste protection section
        terminalGrid.add(new Separator(), 0, terminalRow++, 2, 1);
        Label pasteProtectionHeader = new Label(I18n.get("settings.terminal.paste.header"));
        pasteProtectionHeader.setStyle("-fx-font-weight: bold;");
        terminalGrid.add(pasteProtectionHeader, 0, terminalRow++, 2, 1);
        terminalGrid.add(new Label(I18n.get("settings.terminal.paste.warningMode")), 0, terminalRow);
        terminalGrid.add(pasteWarningModeCombo, 1, terminalRow++);
        terminalGrid.add(new Label(I18n.get("settings.terminal.paste.largeWarning")), 0, terminalRow);
        HBox pasteLargeWarningBox = new HBox(10, pasteLargeWarningSpinner,
            new Label(I18n.get("settings.terminal.paste.largeWarning.unit")));
        pasteLargeWarningBox.setAlignment(Pos.CENTER_LEFT);
        terminalGrid.add(pasteLargeWarningBox, 1, terminalRow++);
        terminalGrid.add(new Label(I18n.get("settings.terminal.paste.lineDelay")), 0, terminalRow);
        HBox pasteLineDelayBox = new HBox(10, pasteLineDelaySpinner,
            new Label(I18n.get("settings.terminal.paste.lineDelay.unit")));
        pasteLineDelayBox.setAlignment(Pos.CENTER_LEFT);
        terminalGrid.add(pasteLineDelayBox, 1, terminalRow++);
        Label pasteProtectionInfo = new Label(I18n.get("settings.terminal.paste.info"));
        pasteProtectionInfo.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        pasteProtectionInfo.setWrapText(true);
        terminalGrid.add(pasteProtectionInfo, 0, terminalRow++, 2, 1);

        // Shell integration section
        terminalGrid.add(new Separator(), 0, terminalRow++, 2, 1);
        Label shellIntegrationHeader = new Label(I18n.get("settings.terminal.shellIntegration.header"));
        shellIntegrationHeader.setStyle("-fx-font-weight: bold;");
        terminalGrid.add(shellIntegrationHeader, 0, terminalRow++, 2, 1);
        terminalGrid.add(shellIntegrationCheck, 0, terminalRow++, 2, 1);
        // Reachable with the checkbox off too: the snippets can be put on the servers first.
        Button shellIntegrationSetupButton = new Button(I18n.get("settings.terminal.shellIntegration.setup"));
        shellIntegrationSetupButton.setTooltip(new Tooltip(I18n.get("settings.terminal.shellIntegration.setup.tooltip")));
        shellIntegrationSetupButton.setOnAction(event -> openShellIntegrationSetup());
        shellIntegrationSetupButton.setMinWidth(Region.USE_PREF_SIZE);
        terminalGrid.add(shellIntegrationSetupButton, 0, terminalRow++, 2, 1);
        Label shellIntegrationInfo = new Label(I18n.get("settings.terminal.shellIntegration.info"));
        shellIntegrationInfo.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        shellIntegrationInfo.setWrapText(true);
        terminalGrid.add(shellIntegrationInfo, 0, terminalRow++, 2, 1);
        Label remoteSidebarLabel = new Label(I18n.get("settings.terminal.remoteSidebar"));
        terminalGrid.add(remoteSidebarLabel, 0, terminalRow);
        terminalGrid.add(remoteSidebarPositionCombo, 1, terminalRow++);

        // SSH Keep-Alive section
        terminalGrid.add(new Separator(), 0, terminalRow++, 2, 1);
        terminalGrid.add(new Label(I18n.get("settings.terminal.sshKeepAlive")), 0, terminalRow++, 2, 1);
        terminalGrid.add(sshKeepAliveCheck, 0, terminalRow++, 2, 1);
        terminalGrid.add(new Label(I18n.get("settings.terminal.sshKeepAliveInterval")), 0, terminalRow);
        HBox keepAliveBox = new HBox(10);
        keepAliveBox.getChildren().addAll(sshKeepAliveIntervalSpinner, new Label(I18n.get("common.seconds")));
        terminalGrid.add(keepAliveBox, 1, terminalRow++);

        // SSH host-key verification (global, insecure opt-out)
        terminalGrid.add(new Separator(), 0, terminalRow++, 2, 1);
        terminalGrid.add(new Label(I18n.get("settings.terminal.hostKeyCheck")), 0, terminalRow++, 2, 1);
        disableHostKeyCheckAllCheck.setSelected(globalSettings != null && globalSettings.isHostKeyCheckDisabledForAllConnections());
        disableHostKeyCheckAllCheck.setTooltip(new Tooltip(I18n.get("settings.terminal.hostKeyCheck.disableAll.tooltip")));
        terminalGrid.add(disableHostKeyCheckAllCheck, 0, terminalRow++, 2, 1);
        Label hostKeyWarn = new Label(I18n.get("settings.terminal.hostKeyCheck.warning"));
        hostKeyWarn.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: #d9534f;");
        hostKeyWarn.setWrapText(true);
        terminalGrid.add(hostKeyWarn, 0, terminalRow++, 2, 1);

        // Connection section
        terminalGrid.add(new Separator(), 0, terminalRow++, 2, 1);
        Label connectionHeader = new Label(I18n.get("settings.connection.header"));
        connectionHeader.setStyle("-fx-font-weight: bold;");
        terminalGrid.add(connectionHeader, 0, terminalRow++, 2, 1);
        terminalGrid.add(connectionRetriesEnabledCheck, 0, terminalRow++, 2, 1);
        terminalGrid.add(autoReconnectEnabledCheck, 0, terminalRow++, 2, 1);

        // Coding agents section
        terminalGrid.add(new Separator(), 0, terminalRow++, 2, 1);
        Label codingAgentHeader = new Label(I18n.get("settings.codingAgent.header"));
        codingAgentHeader.setStyle("-fx-font-weight: bold;");
        terminalGrid.add(codingAgentHeader, 0, terminalRow++, 2, 1);
        terminalGrid.add(codingAgentDetectionCheck, 0, terminalRow++, 2, 1);
        Label codingAgentInfo = new Label(I18n.get("settings.codingAgent.detectionEnabled.info"));
        codingAgentInfo.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        codingAgentInfo.setWrapText(true);
        terminalGrid.add(codingAgentInfo, 0, terminalRow++, 2, 1);
        terminalGrid.add(codingAgentPanelAutoVisibilityCheck, 0, terminalRow++, 2, 1);
        terminalGrid.add(codingAgentAppBadgeCheck, 0, terminalRow++, 2, 1);

        // Control API section
        terminalGrid.add(new Separator(), 0, terminalRow++, 2, 1);
        Label controlApiHeader = new Label(I18n.get("settings.controlApi.header"));
        controlApiHeader.setStyle("-fx-font-weight: bold;");
        terminalGrid.add(controlApiHeader, 0, terminalRow++, 2, 1);
        terminalGrid.add(controlApiEnabledCheck, 0, terminalRow++, 2, 1);
        Label controlApiInfo = new Label(I18n.get("settings.controlApi.enabled.info"));
        controlApiInfo.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        controlApiInfo.setWrapText(true);
        terminalGrid.add(controlApiInfo, 0, terminalRow++, 2, 1);
        terminalGrid.add(controlApiStatusLabel, 0, terminalRow++, 2, 1);
        mcpServerEnabledCheck.setPadding(new Insets(0, 0, 0, 20));
        terminalGrid.add(mcpServerEnabledCheck, 0, terminalRow++, 2, 1);
        mcpWriteToolsCheck.setPadding(new Insets(0, 0, 0, 40));
        terminalGrid.add(mcpWriteToolsCheck, 0, terminalRow++, 2, 1);
        Label mcpInfo = new Label(I18n.get("settings.controlApi.mcpServer.info"));
        mcpInfo.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        mcpInfo.setWrapText(true);
        mcpInfo.setPadding(new Insets(0, 0, 0, 20));
        terminalGrid.add(mcpInfo, 0, terminalRow++, 2, 1);
        mcpHintLabel.setPadding(new Insets(0, 0, 0, 20));
        terminalGrid.add(mcpHintLabel, 0, terminalRow++, 2, 1);

        LazyTabContent.defer(terminalTab, terminalGrid);
        Tab messagingTab = createMessagingTab();

        // Video tab
        Tab videoTab = sectionTab(SettingsSection.VIDEO);
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
        LazyTabContent.defer(videoTab, videoGrid);
        
        // Backup tab
        Tab backupTab = sectionTab(SettingsSection.BACKUP);
        GridPane backupGrid = new GridPane();
        backupGrid.setHgap(10);
        backupGrid.setVgap(10);
        backupGrid.setPadding(new Insets(20));
        
        int backupRow = 0;
        
        Label backupHeader = new Label(I18n.get("settings.backup.header"));
        backupHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
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
        infoLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        backupGrid.add(infoLabel, 0, backupRow++, 2, 1);
        
        // Encryption (REQUIRED!)
        Label encryptionHeader = new Label(I18n.get("settings.backup.encryption.header"));
        encryptionHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 0.9231em;");
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
        warningLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: #d97706; -fx-font-weight: bold;");
        backupGrid.add(warningLabel, 0, backupRow++, 2, 1);

        backupRow = addAutoBackupSection(backupGrid, backupRow);
        
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
            lastBackupValue.setStyle("-fx-font-size: 0.7692em;");
            lastBackupValue.setWrapText(true);
            lastBackupValue.setMaxWidth(350);
            
            backupGrid.add(lastBackupLabel, 0, backupRow);
            backupGrid.add(lastBackupValue, 1, backupRow++);
        }
        
        LazyTabContent.defer(backupTab, backupGrid);

        // Logging tab
        Tab loggingTab = sectionTab(SettingsSection.LOGGING);
        GridPane loggingGrid = new GridPane();
        loggingGrid.setHgap(10);
        loggingGrid.setVgap(10);
        loggingGrid.setPadding(new Insets(20));
        int loggingRow = 0;

        loggingGrid.add(sectionHeader("settings.logging.header", "reference/settings/logging.html#application-log"),
            0, loggingRow++, 2, 1);
        Label loggingScopeLabel = new Label(I18n.get("settings.logging.scope.info"));
        loggingScopeLabel.setWrapText(true);
        loggingScopeLabel.setMaxWidth(640);
        loggingScopeLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        Hyperlink terminalLoggingLink = docLink("features/terminal.html#terminal-logging");
        terminalLoggingLink.setText(I18n.get("settings.logging.scope.terminalLink"));
        VBox loggingScopeBox = new VBox(2, loggingScopeLabel, terminalLoggingLink);
        loggingGrid.add(loggingScopeBox, 0, loggingRow++, 2, 1);

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
        logDirectoryInfo.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
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
        logRetentionInfo.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        loggingGrid.add(logRetentionInfo, 1, loggingRow++);

        Label logCompressionInfo = new Label(I18n.get("settings.logging.compression.info"));
        logCompressionInfo.setWrapText(true);
        logCompressionInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        loggingGrid.add(logCompressionInfo, 0, loggingRow++, 2, 1);

        // Session journal section (journal capture is logging-adjacent, so it lives on this tab)
        loggingGrid.add(new Separator(), 0, loggingRow++, 2, 1);
        Label journalHeader = new Label(I18n.get("settings.journal.section"));
        journalHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
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

        sessionJournalClickableLinksCheck = new CheckBox(I18n.get("settings.journal.clickableLinks"));
        sessionJournalClickableLinksCheck.setSelected(globalSettings == null
            || globalSettings.isSessionJournalClickableLinks());
        sessionJournalClickableLinksCheck.setTooltip(new Tooltip(I18n.get("settings.journal.clickableLinks.tooltip")));
        if (!journalPolicy.sessionJournalClickableLinksAllowed()) {
            sessionJournalClickableLinksCheck.setSelected(false);
            sessionJournalClickableLinksCheck.setDisable(true);
            sessionJournalClickableLinksCheck.setTooltip(new Tooltip(
                de.kortty.policy.PolicyUiSupport.managedByOrganizationText()));
        }
        loggingGrid.add(sessionJournalClickableLinksCheck, 0, loggingRow++, 2, 1);

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

        // Automation journals (JobScheduler / AI Swarm runs) can use a cheaper or local profile.
        automationJournalAiProfileCombo = new ComboBox<>();
        automationJournalAiProfileCombo.setMaxWidth(Double.MAX_VALUE);
        automationJournalAiProfileCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(de.kortty.model.AiProfile profile) {
                if (profile == null || profile.getId() == null) {
                    return I18n.get("settings.journal.automationProfile.same");
                }
                return AutomationJournalConfigPane.profileLabel(profile);
            }

            @Override
            public de.kortty.model.AiProfile fromString(String value) {
                return null;
            }
        });
        de.kortty.model.AiProfile sameAsJournalPlaceholder = new de.kortty.model.AiProfile();
        automationJournalAiProfileCombo.getItems().add(sameAsJournalPlaceholder);
        automationJournalAiProfileCombo.setValue(sameAsJournalPlaceholder);
        String automationProfileId = globalSettings != null ? globalSettings.getAutomationJournalAiProfileId() : null;
        if (globalSettings != null && globalSettings.getAiProfiles() != null) {
            for (de.kortty.model.AiProfile profile : globalSettings.getAiProfiles()) {
                if (profile != null) {
                    automationJournalAiProfileCombo.getItems().add(profile);
                    if (automationProfileId != null && automationProfileId.equals(profile.getId())) {
                        automationJournalAiProfileCombo.setValue(profile);
                    }
                }
            }
        }
        automationJournalAiProfileCombo.disableProperty().bind(
            sessionJournalAiSummariesCheck.selectedProperty().not());
        loggingGrid.add(new Label(I18n.get("settings.journal.automationProfile")), 0, loggingRow);
        loggingGrid.add(automationJournalAiProfileCombo, 1, loggingRow++);
        Label automationProfileHint = new Label(I18n.get("settings.journal.automationProfile.hint"));
        automationProfileHint.setWrapText(true);
        automationProfileHint.setStyle(MutedTextStyle.HINT);
        loggingGrid.add(automationProfileHint, 1, loggingRow++);

        LazyTabContent.defer(loggingTab, loggingGrid);

        // Export tab: watermark and footer for exported documents (journals and AI chats)
        Tab exportTab = sectionTab(SettingsSection.EXPORT);
        GridPane exportGrid = new GridPane();
        exportGrid.setHgap(10);
        exportGrid.setVgap(10);
        exportGrid.setPadding(new Insets(20));
        int exportRow = 0;

        Label exportHeader = new Label(I18n.get("settings.export.header"));
        exportHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
        exportGrid.add(exportHeader, 0, exportRow++, 2, 1);

        Label exportIntro = new Label(I18n.get("settings.export.intro"));
        exportIntro.setWrapText(true);
        exportIntro.setMaxWidth(560);
        exportIntro.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
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
        watermarkInfo.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
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
        footerInfo.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        exportGrid.add(footerInfo, 1, exportRow++);

        LazyTabContent.defer(exportTab, exportGrid);

        // Updates tab
        Tab updatesTab = sectionTab(SettingsSection.UPDATES);
        GridPane updatesGrid = new GridPane();
        updatesGrid.setHgap(10);
        updatesGrid.setVgap(10);
        updatesGrid.setPadding(new Insets(20));
        int updatesRow = 0;

        Label updatesHeader = new Label(I18n.get("settings.updates.header"));
        updatesHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
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
        updatesInfoLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        updatesGrid.add(updatesInfoLabel, 0, updatesRow++, 2, 1);

        LazyTabContent.defer(updatesTab, updatesGrid);
        
        // Window tab
        Tab windowTab = sectionTab(SettingsSection.WINDOW);
        GridPane windowGrid = new GridPane();
        windowGrid.setHgap(10);
        windowGrid.setVgap(10);
        windowGrid.setPadding(new Insets(20));
        
        int windowRow = 0;
        
        Label windowHeader = new Label(I18n.get("settings.window.header"));
        windowHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
        windowGrid.add(windowHeader, 0, windowRow++, 2, 1);

        toolbarIconSizeSlider = new Slider(GlobalSettings.MIN_TOOLBAR_ICON_SIZE,
            GlobalSettings.MAX_TOOLBAR_ICON_SIZE,
            globalSettings != null ? globalSettings.getToolbarIconSize() : GlobalSettings.DEFAULT_TOOLBAR_ICON_SIZE);
        toolbarIconSizeSlider.setMajorTickUnit(8);
        toolbarIconSizeSlider.setMinorTickCount(7);
        toolbarIconSizeSlider.setSnapToTicks(true);
        toolbarIconSizeSlider.setShowTickLabels(true);
        toolbarIconSizeSlider.setShowTickMarks(true);
        toolbarIconSizeSlider.setBlockIncrement(1);
        toolbarIconSizeSlider.setPrefWidth(260);
        toolbarIconSizeSlider.setTooltip(new Tooltip(I18n.get("settings.window.toolbarIconSize.tooltip")));
        Label iconSizeLabel = new Label(I18n.get("settings.window.toolbarIconSize"));
        iconSizeLabel.setLabelFor(toolbarIconSizeSlider);
        Label iconSizeValue = new Label();
        iconSizeValue.textProperty().bind(javafx.beans.binding.Bindings.format("%.0f px", toolbarIconSizeSlider.valueProperty()));
        iconSizeValue.setMinWidth(48);
        var iconPreview = ToolbarIcons.create("gear", iconSizeLabel.textFillProperty(), toolbarIconSizeSlider.valueProperty());
        iconPreview.getStyleClass().add("toolbar-icon-size-preview");
        StackPane previewFrame = new StackPane(iconPreview);
        previewFrame.setMinSize(80, 80);
        previewFrame.setPrefSize(80, 80);
        previewFrame.setMaxSize(80, 80);
        HBox iconSizeControls = new HBox(16, toolbarIconSizeSlider, iconSizeValue, previewFrame);
        iconSizeControls.setAlignment(Pos.CENTER_LEFT);
        windowGrid.add(iconSizeLabel, 0, windowRow);
        windowGrid.add(iconSizeControls, 1, windowRow++);
        
        rememberWindowGeometryCheck = new CheckBox(I18n.get("settings.window.rememberGeometry"));
        rememberWindowGeometryCheck.setSelected(globalSettings != null ? globalSettings.isRememberWindowGeometry() : true);
        rememberWindowGeometryCheck.setTooltip(new Tooltip(I18n.get("settings.window.rememberGeometry.tooltip")));
        windowGrid.add(rememberWindowGeometryCheck, 0, windowRow++, 2, 1);
        
        Label windowInfoLabel = new Label(I18n.get("settings.window.rememberGeometry.info"));
        windowInfoLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        windowGrid.add(windowInfoLabel, 0, windowRow++, 2, 1);
        
        rememberDashboardStateCheck = new CheckBox(I18n.get("settings.window.rememberDashboard"));
        rememberDashboardStateCheck.setSelected(globalSettings != null ? globalSettings.isRememberDashboardState() : true);
        rememberDashboardStateCheck.setTooltip(new Tooltip(I18n.get("settings.window.rememberDashboard.tooltip")));
        windowGrid.add(rememberDashboardStateCheck, 0, windowRow++, 2, 1);
        
        Label dashboardInfoLabel = new Label(I18n.get("settings.window.rememberDashboard.info"));
        dashboardInfoLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        windowGrid.add(dashboardInfoLabel, 0, windowRow++, 2, 1);

        openToolWindowsAsTabsCheck = new CheckBox(I18n.get("settings.window.toolWindowTabs"));
        openToolWindowsAsTabsCheck.setSelected(globalSettings != null && globalSettings.isOpenToolWindowsAsTabs());
        openToolWindowsAsTabsCheck.setTooltip(new Tooltip(I18n.get("settings.window.toolWindowTabs.tooltip")));
        windowGrid.add(openToolWindowsAsTabsCheck, 0, windowRow++, 2, 1);

        Label toolWindowTabsInfoLabel = new Label(I18n.get("settings.window.toolWindowTabs.info"));
        toolWindowTabsInfoLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        windowGrid.add(toolWindowTabsInfoLabel, 0, windowRow++, 2, 1);

        // Tabs section
        windowGrid.add(new Separator(), 0, windowRow++, 2, 1);

        Label tabsHeader = new Label(I18n.get("settings.window.tabs.header"));
        tabsHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 0.9231em;");
        windowGrid.add(tabsHeader, 0, windowRow++, 2, 1);

        connectionColorBorderCheck = new CheckBox(I18n.get("settings.window.connectionColorBorder"));
        connectionColorBorderCheck.setSelected(globalSettings == null || globalSettings.isConnectionColorBorderEnabled());
        connectionColorBorderCheck.setTooltip(new Tooltip(I18n.get("settings.window.connectionColorBorder.tooltip")));
        windowGrid.add(connectionColorBorderCheck, 0, windowRow++, 2, 1);

        Label connectionColorBorderInfoLabel = new Label(I18n.get("settings.window.connectionColorBorder.info"));
        connectionColorBorderInfoLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        windowGrid.add(connectionColorBorderInfoLabel, 0, windowRow++, 2, 1);

        tabTitleFromShellCheck = new CheckBox(I18n.get("settings.window.tabTitleFromShell"));
        tabTitleFromShellCheck.setSelected(globalSettings == null || globalSettings.isTabTitleFromShellEnabled());
        tabTitleFromShellCheck.setTooltip(new Tooltip(I18n.get("settings.window.tabTitleFromShell.tooltip")));
        windowGrid.add(tabTitleFromShellCheck, 0, windowRow++, 2, 1);

        Label tabTitleFromShellInfoLabel = new Label(I18n.get("settings.window.tabTitleFromShell.info"));
        tabTitleFromShellInfoLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        windowGrid.add(tabTitleFromShellInfoLabel, 0, windowRow++, 2, 1);

        tabSwitchMostRecentFirstCheck = new CheckBox(I18n.get("settings.window.tabSwitchMostRecentFirst"));
        tabSwitchMostRecentFirstCheck.setSelected(globalSettings != null && globalSettings.isTabSwitchMostRecentFirst());
        tabSwitchMostRecentFirstCheck.setTooltip(new Tooltip(I18n.get("settings.window.tabSwitchMostRecentFirst.tooltip")));
        windowGrid.add(tabSwitchMostRecentFirstCheck, 0, windowRow++, 2, 1);

        Label tabSwitchMostRecentFirstInfoLabel = new Label(I18n.get("settings.window.tabSwitchMostRecentFirst.info"));
        tabSwitchMostRecentFirstInfoLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        windowGrid.add(tabSwitchMostRecentFirstInfoLabel, 0, windowRow++, 2, 1);

        // Session restore section: what korTTY does at startup with the session before this start.
        windowGrid.add(new Separator(), 0, windowRow++, 2, 1);

        Label sessionRestoreHeader = new Label(I18n.get("settings.window.restore.header"));
        sessionRestoreHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 0.9231em;");
        windowGrid.add(sessionRestoreHeader, 0, windowRow++, 2, 1);

        sessionRestoreModeCombo = new ComboBox<>();
        sessionRestoreModeCombo.getItems().setAll(SessionRestoreMode.values());
        sessionRestoreModeCombo.setValue(globalSettings != null
            ? globalSettings.getSessionRestoreMode() : SessionRestoreMode.DEFAULT);
        sessionRestoreModeCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(SessionRestoreMode mode) {
                return mode != null ? I18n.get(sessionRestoreModeKey(mode)) : "";
            }

            @Override
            public SessionRestoreMode fromString(String text) {
                return null;
            }
        });
        sessionRestoreModeCombo.setTooltip(new Tooltip(I18n.get("settings.window.restore.mode.tooltip")));
        // After setTooltip: [rule.terminal] session-restore sets the mode (PolicyClamp) and locks the combo.
        de.kortty.policy.PolicyUiSupport.lockIfManaged(
            sessionRestoreModeCombo, de.kortty.policy.ManagedSetting.SESSION_RESTORE);
        windowGrid.add(new Label(I18n.get("settings.window.restore.mode")), 0, windowRow);
        windowGrid.add(sessionRestoreModeCombo, 1, windowRow++);

        Label sessionRestoreInfoLabel = new Label(I18n.get("settings.window.restore.info"));
        sessionRestoreInfoLabel.setWrapText(true);
        sessionRestoreInfoLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        windowGrid.add(sessionRestoreInfoLabel, 0, windowRow++, 2, 1);

        // Optional, off by default: the output of each pane, saved only encrypted with the vault key.
        sessionRestoreScrollbackCheck = new CheckBox(I18n.get("settings.window.restore.scrollback"));
        sessionRestoreScrollbackCheck.setSelected(globalSettings != null && globalSettings.isSessionRestoreScrollback());
        sessionRestoreScrollbackCheck.setTooltip(new Tooltip(I18n.get("settings.window.restore.scrollback.tooltip")));
        windowGrid.add(sessionRestoreScrollbackCheck, 0, windowRow++, 2, 1);

        sessionRestoreScrollbackLinesSpinner = new Spinner<>(de.kortty.core.ScrollbackSnapshotCodec.MIN_LINES,
            de.kortty.core.ScrollbackSnapshotCodec.MAX_LINES,
            globalSettings != null ? globalSettings.getSessionRestoreScrollbackLines()
                : de.kortty.core.ScrollbackSnapshotCodec.DEFAULT_LINES, 100);
        sessionRestoreScrollbackLinesSpinner.setEditable(true);
        sessionRestoreScrollbackLinesSpinner.setPrefWidth(110);
        sessionRestoreScrollbackLinesSpinner.setTooltip(
            new Tooltip(I18n.get("settings.window.restore.scrollbackLines.tooltip")));
        // [rule.terminal] session-restore-output sets the switch (PolicyClamp) and locks it with the lines
        // spinner; otherwise the spinner only follows the switch.
        if (de.kortty.policy.PolicyUiSupport.lockIfManaged(
                sessionRestoreScrollbackCheck, de.kortty.policy.ManagedSetting.SESSION_RESTORE_OUTPUT)) {
            de.kortty.policy.PolicyUiSupport.lockIf(sessionRestoreScrollbackLinesSpinner, true);
        } else {
            sessionRestoreScrollbackLinesSpinner.disableProperty().bind(sessionRestoreScrollbackCheck.selectedProperty().not());
        }
        windowGrid.add(new Label(I18n.get("settings.window.restore.scrollbackLines")), 0, windowRow);
        windowGrid.add(sessionRestoreScrollbackLinesSpinner, 1, windowRow++);

        Label sessionRestoreScrollbackInfoLabel = new Label(I18n.get("settings.window.restore.scrollback.info"));
        sessionRestoreScrollbackInfoLabel.setWrapText(true);
        sessionRestoreScrollbackInfoLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        windowGrid.add(sessionRestoreScrollbackInfoLabel, 0, windowRow++, 2, 1);

        // Fixed geometry section
        windowGrid.add(new Separator(), 0, windowRow++, 2, 1);
        
        Label fixedGeometryHeader = new Label(I18n.get("settings.window.fixedGeometry.header"));
        fixedGeometryHeader.setStyle("-fx-font-weight: bold; -fx-font-size: 0.9231em;");
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
        fixedGeometryInfoLabel.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        windowGrid.add(fixedGeometryInfoLabel, 0, windowRow++, 2, 1);
        
        // Four sections of checkboxes with info lines outgrow the dialog height, so scroll them.
        ScrollPane windowScroll = new ScrollPane(windowGrid);
        windowScroll.setFitToWidth(true);
        windowScroll.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        windowScroll.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        LazyTabContent.defer(windowTab, windowScroll);
        
        // Security tab
        Tab securityTab = sectionTab(SettingsSection.SECURITY);
        GridPane securityGrid = new GridPane();
        securityGrid.setHgap(10);
        securityGrid.setVgap(10);
        securityGrid.setPadding(new Insets(20));
        
        int securityRow = 0;
        
        securityGrid.add(sectionHeader("settings.security.masterPassword", "reference/settings/security.html#master-password"),
            0, securityRow++, 2, 1);
        
        Label passwordInfoLabel = new Label(I18n.get("settings.security.masterPassword.info"));
        passwordInfoLabel.setWrapText(true);
        passwordInfoLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
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
        masterPasswordWarningLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: #ff6b6b; -fx-font-weight: bold;");
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
        skipPromptWarningLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: #ff6b6b; -fx-font-weight: bold;");
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

        // Temporary SSH key (Connection Manager + Quick Connect): its own section, not a master-password option
        securityGrid.add(new Separator(), 0, securityRow++, 2, 1);
        securityGrid.add(sectionHeader("settings.security.temporarySshKey.header",
            "reference/settings/security.html#temporary-ssh-keys"), 0, securityRow++, 2, 1);
        Label temporarySshKeyInfoLabel = new Label(I18n.get("settings.security.temporarySshKey.info"));
        temporarySshKeyInfoLabel.setWrapText(true);
        temporarySshKeyInfoLabel.setMaxWidth(640);
        temporarySshKeyInfoLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        securityGrid.add(temporarySshKeyInfoLabel, 0, securityRow++, 2, 1);
        temporarySshKeyEnabledCheck = new CheckBox(I18n.get("settings.security.temporarySshKeyEnabled"));
        temporarySshKeyEnabledCheck.setSelected(globalSettings != null && globalSettings.isTemporarySshKeyEnabled());
        temporarySshKeyEnabledCheck.setTooltip(new Tooltip(I18n.get("settings.security.temporarySshKeyEnabled.tooltip")));
        securityGrid.add(temporarySshKeyEnabledCheck, 0, securityRow++, 2, 1);

        // Session isolation section
        securityGrid.add(new Separator(), 0, securityRow++, 2, 1);
        securityRow = addIsolationSection(securityGrid, securityRow);
        
        LazyTabContent.defer(securityTab, securityGrid);

        // Privacy tab (anonymous usage statistics: opt-in, EU servers, GDPR)
        Tab privacyTab = sectionTab(SettingsSection.PRIVACY);
        GridPane privacyGrid = new GridPane();
        privacyGrid.setHgap(10);
        privacyGrid.setVgap(10);
        privacyGrid.setPadding(new Insets(20));

        int privacyRow = 0;

        privacyGrid.add(sectionHeader("settings.telemetry.header", de.kortty.telemetry.TelemetryEvents.GUIDE_LOCATION),
            0, privacyRow++, 2, 1);

        Label privacySummaryLabel = new Label(I18n.get("settings.telemetry.summary"));
        privacySummaryLabel.setWrapText(true);
        privacySummaryLabel.setMaxWidth(640);
        privacySummaryLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        privacyGrid.add(privacySummaryLabel, 0, privacyRow++, 2, 1);

        telemetryEnabledCheck = new CheckBox(I18n.get("settings.telemetry.enable"));
        telemetryEnabledCheck.setSelected(globalSettings != null && globalSettings.isTelemetryEnabled());
        de.kortty.policy.PolicyUiSupport.lockIfManaged(
            telemetryEnabledCheck, de.kortty.policy.ManagedSetting.TELEMETRY);
        telemetryEnabledCheck.setTooltip(new Tooltip(I18n.get("settings.telemetry.enable.tooltip")));
        privacyGrid.add(telemetryEnabledCheck, 0, privacyRow++, 2, 1);

        privacyGrid.add(new Separator(), 0, privacyRow++, 2, 1);

        Label telemetryCollectedLabel = new Label(I18n.get("settings.telemetry.collected"));
        telemetryCollectedLabel.setWrapText(true);
        telemetryCollectedLabel.setMaxWidth(640);
        telemetryCollectedLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        privacyGrid.add(telemetryCollectedLabel, 0, privacyRow++, 2, 1);

        Label telemetryNotCollectedLabel = new Label(I18n.get("settings.telemetry.notCollected"));
        telemetryNotCollectedLabel.setWrapText(true);
        telemetryNotCollectedLabel.setMaxWidth(640);
        telemetryNotCollectedLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        privacyGrid.add(telemetryNotCollectedLabel, 0, privacyRow++, 2, 1);

        String telemetryConsentDate = globalSettings != null ? globalSettings.getTelemetryConsentDate() : null;
        if (telemetryConsentDate != null && globalSettings.getTelemetryConsentVersion() > 0) {
            Label telemetryDecisionLabel =
                new Label(I18n.get("settings.telemetry.decisionDate", formatConsentDate(telemetryConsentDate)));
            telemetryDecisionLabel.setWrapText(true);
            telemetryDecisionLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
            privacyGrid.add(telemetryDecisionLabel, 0, privacyRow++, 2, 1);
        }

        LazyTabContent.defer(privacyTab, privacyGrid);

        // Language tab
        Tab languageTab = sectionTab(SettingsSection.LANGUAGE);
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
        languageInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        languageGrid.add(languageInfo, 0, 1, 2, 1);
        
        LazyTabContent.defer(languageTab, languageGrid);
        
        // Translation tab: translation profiles (one default), "Translate now…", generated files
        Tab translationTab = sectionTab(SettingsSection.TRANSLATION);
        VBox translationRoot = new VBox(14);
        translationRoot.setPadding(new Insets(20));
        HBox systemLanguageRow = new HBox(10, new Label(I18n.get("settings.translation.systemLanguage")),
            new Label(java.util.Locale.getDefault().getDisplayLanguage()));
        systemLanguageRow.setAlignment(Pos.CENTER_LEFT);
        translationRoot.getChildren().add(systemLanguageRow);

        translationRoot.getChildren().add(sectionHeader("settings.translation.profiles.header",
            "reference/settings/translation.html#translation-profiles"));
        Label profilesHint = new Label(I18n.get("settings.translation.profiles.hint"));
        profilesHint.setWrapText(true);
        profilesHint.setPrefWidth(760);
        profilesHint.setMaxWidth(760);
        profilesHint.setMinHeight(Region.USE_PREF_SIZE);
        profilesHint.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        translationRoot.getChildren().add(profilesHint);
        if (globalSettings != null) {
            translationProfilesPane = new TranslationProfilesPane(globalSettings, translationSecrets(),
                () -> getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
                app != null ? this::openAiManagerForTranslation : null);
            translationRoot.getChildren().add(translationProfilesPane);
        }

        translationRoot.getChildren().add(new javafx.scene.control.Separator());
        translationRoot.getChildren().add(sectionHeader("settings.translation.run.header",
            "reference/settings/translation.html#translate-now"));
        Label runHint = new Label(I18n.get("settings.translation.run.hint"));
        runHint.setWrapText(true);
        runHint.setPrefWidth(760);
        runHint.setMaxWidth(760);
        runHint.setMinHeight(Region.USE_PREF_SIZE);
        runHint.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        Button translateNowButton = new Button(I18n.get("settings.translation.run.open"));
        translateNowButton.setOnAction(e -> openTranslationRunDialog());
        translationRunIndicator = new Label();
        translationRunIndicator.setStyle("-fx-font-size: 0.8462em;");
        HBox runRow = new HBox(12, translateNowButton, translationRunIndicator);
        runRow.setAlignment(Pos.CENTER_LEFT);
        translationRoot.getChildren().addAll(runHint, runRow);
        installTranslationRunIndicator();

        translationRoot.getChildren().add(new javafx.scene.control.Separator());
        GridPane generatedGrid = new GridPane();
        generatedGrid.setHgap(10);
        generatedGrid.setVgap(10);
        int transRow = 0;
        generatedGrid.add(new Label(I18n.get("settings.translation.generatedLanguages")), 0, transRow);
        translationOutdatedLabelRef = new Label();
        translationOutdatedLabelRef.setWrapText(true);
        translationOutdatedLabelRef.setMaxWidth(520);
        translationOutdatedLabelRef.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        translationOutdatedLabelRef.setVisible(false);
        translationRegenerateOutdatedButtonRef = new Button(I18n.get("settings.translation.regenerateOutdated"));
        translationRegenerateOutdatedButtonRef.setVisible(false);
        translationRegenerateOutdatedButtonRef.setOnAction(ev -> regenerateOutdatedTranslations(
            translationRegenerateOutdatedButtonRef, translationOutdatedLabelRef, translationRegenerateOutdatedButtonRef));
        translationGeneratedList = new ListView<>();
        translationGeneratedList.setPrefHeight(110);
        translationGeneratedList.setMinHeight(110);
        translationGeneratedList.setPrefWidth(420);
        translationGeneratedList.setCellFactory(lv -> new ListCell<Locale>() {
            @Override
            protected void updateItem(Locale item, boolean empty) {
                super.updateItem(item, empty);
                setText(empty || item == null ? null : item.getDisplayLanguage() + " (" + item.getLanguage() + ")");
            }
        });
        Button deleteGeneratedButton = new Button(I18n.get("settings.translation.delete"));
        deleteGeneratedButton.setOnAction(e -> deleteSelectedGeneratedLanguage());
        VBox generatedBox = new VBox(5, translationOutdatedLabelRef, translationRegenerateOutdatedButtonRef,
            translationGeneratedList, deleteGeneratedButton);
        refreshTranslationGeneratedList(translationOutdatedLabelRef, translationRegenerateOutdatedButtonRef);
        generatedGrid.add(generatedBox, 1, transRow++);

        generatedGrid.add(new Label(I18n.get("settings.translation.guide.generated")), 0, transRow);
        guideTranslationList = new ListView<>();
        guideTranslationList.setPrefHeight(90);
        guideTranslationList.setMinHeight(90);
        guideTranslationList.setPrefWidth(420);
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
        generatedGrid.add(new VBox(5, guideTranslationList, guideDeleteButton), 1, transRow++);
        refreshGuideTranslationList();
        translationRoot.getChildren().add(generatedGrid);

        ScrollPane translationScroll = new ScrollPane(translationRoot);
        translationScroll.setFitToWidth(true);
        LazyTabContent.defer(translationTab, translationScroll);


        // SFTP tab
        Tab sftpTab = sectionTab(SettingsSection.SFTP);
        GridPane sftpGrid = new GridPane();
        sftpGrid.setHgap(10);
        sftpGrid.setVgap(10);
        sftpGrid.setPadding(new Insets(20));
        
        int sftpRow = 0;
        
        // SFTP Manager title
        Label sftpTitle = new Label(I18n.get("settings.sftp.title"));
        sftpTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
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
        sftpInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        sftpInfo.setWrapText(true);
        sftpInfo.setMaxWidth(400);
        sftpGrid.add(sftpInfo, 0, sftpRow++, 2, 1);

        // Transfers: parallel channels, conflict default, partial files ([rule.sftp] caps and pins them)
        sftpGrid.add(new Separator(), 0, sftpRow++, 2, 1);
        Label transfersTitle = new Label(I18n.get("settings.sftp.transfersTitle"));
        transfersTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
        sftpGrid.add(transfersTitle, 0, sftpRow++, 2, 1);
        GlobalSettings sftpValues = globalSettings != null ? globalSettings : new GlobalSettings();
        de.kortty.policy.EffectivePolicy sftpPolicy = de.kortty.policy.PolicyManager.effective();
        Integer parallelCap = sftpPolicy.sftpMaxParallelTransfers();
        int parallelMax = parallelCap != null
            ? Math.max(GlobalSettings.MIN_SFTP_PARALLEL_TRANSFERS,
                Math.min(GlobalSettings.MAX_SFTP_PARALLEL_TRANSFERS, parallelCap))
            : GlobalSettings.MAX_SFTP_PARALLEL_TRANSFERS;
        sftpParallelTransfersSpinner = new Spinner<>(GlobalSettings.MIN_SFTP_PARALLEL_TRANSFERS, parallelMax,
            Math.min(parallelMax, sftpValues.getSftpParallelTransfers()));
        sftpParallelTransfersSpinner.setEditable(true);
        sftpParallelTransfersSpinner.setPrefWidth(80);
        sftpParallelTransfersSpinner.setTooltip(new Tooltip(I18n.get("settings.sftp.parallelTransfers.tooltip")));
        if (parallelCap != null) {
            // A cap: the user may still choose fewer, never more.
            sftpParallelTransfersSpinner.setTooltip(new Tooltip(
                de.kortty.policy.PolicyUiSupport.managedByOrganizationText()));
            de.kortty.policy.PolicyUiSupport.lockIf(sftpParallelTransfersSpinner, parallelMax <= 1);
        }
        HBox parallelBox = new HBox(10, new Label(I18n.get("settings.sftp.parallelTransfers")),
            sftpParallelTransfersSpinner);
        parallelBox.setAlignment(Pos.CENTER_LEFT);
        sftpGrid.add(parallelBox, 0, sftpRow++, 2, 1);

        sftpConflictDefaultCombo = new ComboBox<>(javafx.collections.FXCollections.observableArrayList(
            de.kortty.model.SftpConflictDefault.values()));
        sftpConflictDefaultCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(de.kortty.model.SftpConflictDefault value) {
                return value == null ? "" : I18n.get("settings.sftp.conflictDefault." + value.id());
            }

            @Override
            public de.kortty.model.SftpConflictDefault fromString(String text) {
                return null;
            }
        });
        sftpConflictDefaultCombo.setValue(sftpPolicy.sftpConflictDefault() != null
            ? sftpPolicy.sftpConflictDefault() : sftpValues.getSftpConflictDefault());
        sftpConflictDefaultCombo.setTooltip(new Tooltip(I18n.get("settings.sftp.conflictDefault.tooltip")));
        de.kortty.policy.PolicyUiSupport.lockIf(sftpConflictDefaultCombo, sftpPolicy.sftpConflictDefault() != null);
        HBox conflictBox = new HBox(10, new Label(I18n.get("settings.sftp.conflictDefault")), sftpConflictDefaultCombo);
        conflictBox.setAlignment(Pos.CENTER_LEFT);
        sftpGrid.add(conflictBox, 0, sftpRow++, 2, 1);

        sftpResumePartialTransfersCheck = new CheckBox(I18n.get("settings.sftp.resumePartialTransfers"));
        sftpResumePartialTransfersCheck.setSelected(sftpValues.isSftpResumePartialTransfers());
        sftpResumePartialTransfersCheck.setTooltip(new Tooltip(I18n.get("settings.sftp.resumePartialTransfers.tooltip")));
        sftpGrid.add(sftpResumePartialTransfersCheck, 0, sftpRow++, 2, 1);

        sftpKeepPartialOnCancelCheck = new CheckBox(I18n.get("settings.sftp.keepPartialOnCancel"));
        sftpKeepPartialOnCancelCheck.setSelected(sftpValues.isSftpKeepPartialOnCancel());
        sftpKeepPartialOnCancelCheck.setTooltip(new Tooltip(I18n.get("settings.sftp.keepPartialOnCancel.tooltip")));
        // A kept partial file is only useful when something resumes it.
        sftpKeepPartialOnCancelCheck.disableProperty().bind(sftpResumePartialTransfersCheck.selectedProperty().not());
        sftpGrid.add(sftpKeepPartialOnCancelCheck, 0, sftpRow++, 2, 1);

        if (sftpPolicy.isManaged(de.kortty.policy.ManagedSetting.SFTP_TRANSFERS)
                || !sftpPolicy.fileTransferAllowed()) {
            Label transfersPolicy = new Label(sftpPolicy.fileTransferAllowed()
                ? de.kortty.policy.PolicyUiSupport.managedByOrganizationText()
                : de.kortty.policy.FileTransferGate.check(sftpPolicy,
                    de.kortty.policy.FileTransferGate.Route.SFTP_UPLOAD).reason());
            transfersPolicy.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
            transfersPolicy.setWrapText(true);
            transfersPolicy.setMaxWidth(400);
            sftpGrid.add(transfersPolicy, 0, sftpRow++, 2, 1);
        }

        Label transfersInfo = new Label(I18n.get("settings.sftp.transfersInfo"));
        transfersInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        transfersInfo.setWrapText(true);
        transfersInfo.setMaxWidth(400);
        sftpGrid.add(transfersInfo, 0, sftpRow++, 2, 1);

        sftpGrid.add(new Separator(), 0, sftpRow++, 2, 1);

        // External editor for remote files ("Edit in External Editor").
        Label externalEditorTitle = new Label(I18n.get("settings.sftp.externalEditorTitle"));
        externalEditorTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
        sftpGrid.add(externalEditorTitle, 0, sftpRow++, 2, 1);

        Label externalEditorLabel = new Label(I18n.get("settings.sftp.externalEditorCommand"));
        sftpExternalEditorCommandField = new TextField(globalSettings.getSftpExternalEditorCommand());
        sftpExternalEditorCommandField.setPromptText(I18n.get("settings.sftp.externalEditorCommand.prompt"));
        sftpExternalEditorCommandField.setPrefWidth(320);
        sftpExternalEditorCommandField.setTooltip(new Tooltip(I18n.get("settings.sftp.externalEditorCommand.tooltip")));
        // A command that cannot be parsed (an unclosed quote) is marked red and not saved.
        sftpExternalEditorCommandField.textProperty().addListener((obs, old, value) ->
            sftpExternalEditorCommandField.setStyle(
                de.kortty.core.remote.edit.ExternalEditorLauncher.isValidTemplate(value) ? "" : "-fx-border-color: #e06c75;"));
        HBox externalEditorBox = new HBox(10, externalEditorLabel, sftpExternalEditorCommandField);
        HBox.setHgrow(sftpExternalEditorCommandField, Priority.ALWAYS);
        sftpGrid.add(externalEditorBox, 0, sftpRow++, 2, 1);

        Label externalEditorInfo = new Label(I18n.get("settings.sftp.externalEditorInfo"));
        externalEditorInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        externalEditorInfo.setWrapText(true);
        externalEditorInfo.setMaxWidth(400);
        sftpGrid.add(externalEditorInfo, 0, sftpRow++, 2, 1);

        // Separator
        sftpGrid.add(new Separator(), 0, sftpRow++, 2, 1);
        
        // ZIP settings title
        Label zipTitle = new Label(I18n.get("settings.sftp.zipTitle"));
        zipTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
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
        zipInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        zipInfo.setWrapText(true);
        zipInfo.setMaxWidth(400);
        sftpGrid.add(zipInfo, 0, sftpRow++, 2, 1);

        sftpGrid.add(new Separator(), 0, sftpRow++, 2, 1);

        Label rsyncTitle = new Label(I18n.get("settings.sftp.rsyncTitle"));
        rsyncTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
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
        rsyncInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        rsyncInfo.setWrapText(true);
        rsyncInfo.setMaxWidth(400);
        sftpGrid.add(rsyncInfo, 0, sftpRow++, 2, 1);
        
        LazyTabContent.defer(sftpTab, sftpGrid);
        
        // Editor tab
        Tab editorTab = sectionTab(SettingsSection.EDITOR);
        GridPane editorGrid = new GridPane();
        editorGrid.setHgap(10);
        editorGrid.setVgap(10);
        editorGrid.setPadding(new Insets(20));
        
        int editorRow = 0;
        
        // Editor settings title
        Label editorTitle = new Label(I18n.get("settings.editor.title"));
        editorTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
        editorGrid.add(editorTitle, 0, editorRow++, 2, 1);
        
        // Info about editor using terminal settings
        Label editorInfo = new Label(I18n.get("settings.editor.info"));
        editorInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
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
        cursorInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        cursorInfo.setWrapText(true);
        cursorInfo.setMaxWidth(400);
        editorGrid.add(cursorInfo, 0, editorRow++, 2, 1);
        
        LazyTabContent.defer(editorTab, editorGrid);
        
        // Snippet Editor tab
        Tab snippetEditorTab = sectionTab(SettingsSection.SNIPPET_EDITOR);
        GridPane snippetEditorGrid = new GridPane();
        snippetEditorGrid.setHgap(10);
        snippetEditorGrid.setVgap(10);
        snippetEditorGrid.setPadding(new Insets(20));
        
        int snippetRow = 0;
        
        Label snippetTitle = new Label(I18n.get("settings.snippetEditor.title"));
        snippetTitle.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
        snippetEditorGrid.add(snippetTitle, 0, snippetRow++, 2, 1);
        
        Label snippetInfo = new Label(I18n.get("settings.snippetEditor.info"));
        snippetInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
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
        snippetSizeInfo.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
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

        // AI completion shortcut: a recorder field. It stores the chord itself (one main key plus up
        // to three modifiers), so the Monaco page can register it directly — see
        // SnippetCompletionShortcut, which owns the key table for both sides.
        snippetCompletionShortcutField = new TextField();
        snippetCompletionShortcutField.setEditable(false);
        snippetCompletionShortcutField.setFocusTraversable(true);
        snippetCompletionShortcutField.setPrefWidth(160);
        snippetCompletionShortcutField.setPromptText(I18n.get("settings.snippetEditor.completionShortcut.prompt"));
        snippetCompletionShortcutField.setUserData(
            SnippetCompletionShortcut.normalizeOrDefault(globalSettings.getSnippetCompletionShortcut()));
        snippetCompletionShortcutField.setText(SnippetCompletionShortcut.displayLabel(
            (String) snippetCompletionShortcutField.getUserData(), SnippetCompletionShortcut.isMacOs()));
        // A filter, not a handler: TAB and the arrow keys would otherwise move the focus before the
        // recorder ever sees them, and Shift+Tab — the default chord — is exactly such a key.
        snippetCompletionShortcutField.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            KeyCode code = event.getCode();
            if (code == KeyCode.ESCAPE) {
                return; // let Escape close the settings dialog as usual
            }
            event.consume();
            String chord = SnippetCompletionShortcut.fromKeyPress(
                code.name(), event.isShortcutDown(), event.isShiftDown(), event.isAltDown());
            if (chord == null) {
                return; // a modifier on its own, or a key Monaco cannot bind
            }
            snippetCompletionShortcutField.setUserData(chord);
            snippetCompletionShortcutField.setText(
                SnippetCompletionShortcut.displayLabel(chord, SnippetCompletionShortcut.isMacOs()));
        });
        Button snippetCompletionShortcutReset = new Button(I18n.get("settings.snippetEditor.completionShortcut.reset"));
        snippetCompletionShortcutReset.setOnAction(e -> {
            snippetCompletionShortcutField.setUserData(SnippetCompletionShortcut.DEFAULT);
            snippetCompletionShortcutField.setText(SnippetCompletionShortcut.displayLabel(
                SnippetCompletionShortcut.DEFAULT, SnippetCompletionShortcut.isMacOs()));
        });
        HBox snippetCompletionShortcutBox =
            new HBox(10, snippetCompletionShortcutField, snippetCompletionShortcutReset);
        snippetEditorGrid.add(new Label(I18n.get("settings.snippetEditor.completionShortcut")), 0, snippetRow);
        snippetEditorGrid.add(snippetCompletionShortcutBox, 1, snippetRow++);

        Label snippetCompletionShortcutHint = new Label(I18n.get("settings.snippetEditor.completionShortcut.hint"));
        snippetCompletionShortcutHint.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        snippetCompletionShortcutHint.setWrapText(true);
        snippetCompletionShortcutHint.setMaxWidth(400);
        snippetEditorGrid.add(snippetCompletionShortcutHint, 0, snippetRow++, 2, 1);

        snippetPrewarmCheck = new CheckBox(I18n.get("settings.snippetEditor.prewarm"));
        snippetPrewarmCheck.setSelected(globalSettings == null || globalSettings.isSnippetEditorPrewarmEnabled());
        snippetEditorGrid.add(snippetPrewarmCheck, 0, snippetRow++, 2, 1);
        Label snippetPrewarmHint = new Label(I18n.get("settings.snippetEditor.prewarm.hint"));
        snippetPrewarmHint.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        snippetPrewarmHint.setWrapText(true);
        snippetPrewarmHint.setMaxWidth(400);
        snippetEditorGrid.add(snippetPrewarmHint, 0, snippetRow++, 2, 1);

        // Full-code analyses kept per snippet. Lowering the limit never deletes anything here: the
        // store trims only when the next analysis of a snippet arrives, and never pinned or pending work.
        int savedAnalysisHistory = globalSettings != null ? globalSettings.getSnippetAnalysisHistoryMaxSize() : 5;
        snippetAnalysisHistorySpinner = new Spinner<>(1, 20, savedAnalysisHistory);
        snippetAnalysisHistorySpinner.setId("settings-snippet-analysis-history");
        snippetAnalysisHistorySpinner.setEditable(true);
        snippetAnalysisHistorySpinner.setPrefWidth(90);
        snippetAnalysisHistorySpinner.setTooltip(new Tooltip(I18n.get("settings.snippetEditor.analysisHistory.tooltip")));
        snippetEditorGrid.add(new Label(I18n.get("settings.snippetEditor.analysisHistory")), 0, snippetRow);
        snippetEditorGrid.add(snippetAnalysisHistorySpinner, 1, snippetRow++);
        Label snippetAnalysisHistoryHint = new Label(I18n.get("settings.snippetEditor.analysisHistory.hint"));
        snippetAnalysisHistoryHint.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        snippetAnalysisHistoryHint.setWrapText(true);
        snippetAnalysisHistoryHint.setMaxWidth(400);
        snippetEditorGrid.add(snippetAnalysisHistoryHint, 0, snippetRow++, 2, 1);
        Label snippetAnalysisHistoryLowered = new Label();
        snippetAnalysisHistoryLowered.setId("settings-snippet-analysis-history-lowered");
        snippetAnalysisHistoryLowered.setWrapText(true);
        snippetAnalysisHistoryLowered.setMaxWidth(400);
        snippetAnalysisHistoryLowered.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: #d97706;");
        snippetAnalysisHistoryLowered.setVisible(false);
        snippetAnalysisHistoryLowered.setManaged(false);
        snippetEditorGrid.add(snippetAnalysisHistoryLowered, 0, snippetRow++, 2, 1);
        snippetAnalysisHistorySpinner.valueProperty().addListener((obs, was, isNow) ->
            updateAnalysisHistoryLoweredHint(snippetAnalysisHistoryLowered, savedAnalysisHistory, isNow));

        // How much script text each stored analysis keeps (user value, capped by the enterprise policy).
        // Lowering it never deletes stored text; only new analyses are affected.
        snippetAnalysisContentLimit = new SnippetAnalysisContentLimitControl(
            globalSettings != null
                ? globalSettings.getSnippetAnalysisMaxStoredContentBytes()
                : de.kortty.core.SnippetAnalysisContentLimit.DEFAULT_BYTES,
            de.kortty.policy.PolicyManager.effective().snippetAnalysisMaxStoredContentBytes());
        snippetRow = snippetAnalysisContentLimit.addTo(snippetEditorGrid,
            new Label(I18n.get("settings.snippetEditor.analysisContentLimit")), snippetRow);

        LazyTabContent.defer(snippetEditorTab, snippetEditorGrid);

        // Themes tab
        Tab themesTab = createThemesTab(owner);

        // Resources tab (opt-in JVM heap/GC profile)
        Tab resourcesTab = createResourcesTab();

        // Keyboard tab (rebind korTTY's shortcuts)
        keyboardTab = createKeyboardTab();

        tabPane.getTabs().addAll(fontTab, colorsTab, themesTab, appearanceTab, terminalTab, messagingTab, videoTab, backupTab, loggingTab, exportTab, updatesTab, windowTab, keyboardTab, resourcesTab, securityTab, privacyTab, sftpTab, editorTab, snippetEditorTab, languageTab, translationTab);
        
        // These are fixed pixel sizes rather than content-derived ones, so they have to grow with
        // the UI font scale or the tabs would crowd inside an unchanged frame. scaleDimension caps
        // each against the screen — the minimums especially: an unclamped 960 minimum height at a
        // large scale would exceed a 1080p panel and leave the dialog unusable.
        final double defaultContentWidth = UiFontScaleSupport.scaleDimension(1000, true);
        final double minimumContentWidth = UiFontScaleSupport.scaleDimension(860, true);
        final double defaultContentHeight = UiFontScaleSupport.scaleDimension(920, false);
        final double minimumContentHeight = UiFontScaleSupport.scaleDimension(720, false);
        final double defaultDialogHeight = UiFontScaleSupport.scaleDimension(1080, false);
        final double minimumDialogHeight = UiFontScaleSupport.scaleDimension(960, false);

        tabPane.setPrefSize(defaultContentWidth, defaultContentHeight);
        tabPane.setMinSize(minimumContentWidth, minimumContentHeight);
        getDialogPane().setContent(buildSectionNavigationContent(tabPane));
        getDialogPane().setPrefWidth(UiFontScaleSupport.scaleDimension(1120, true));
        getDialogPane().setMinWidth(UiFontScaleSupport.scaleDimension(1000, true));
        getDialogPane().setPrefHeight(defaultDialogHeight);
        getDialogPane().setMinHeight(minimumDialogHeight);
        
        // Buttons
        ButtonType saveButtonType = new ButtonType(I18n.get("settings.save"), ButtonBar.ButtonData.OK_DONE);
        getDialogPane().getButtonTypes().addAll(saveButtonType, ButtonType.CANCEL);
        // Two actions on one shortcut cannot be saved: Save stays in the dialog and shows the
        // Keyboard page, whose tab carries a red badge while a conflict lasts.
        if (getDialogPane().lookupButton(saveButtonType) instanceof Button saveButton) {
            saveButton.addEventFilter(javafx.event.ActionEvent.ACTION, event -> {
                if (keyboardPage != null && !keyboardPage.canSave()) {
                    event.consume();
                    mainTabPane.getSelectionModel().select(keyboardTab);
                    keyboardPage.revealConflicts();
                } else if (!QuickSelectSettingsSupport.canSave(quickSelectAlphabetField.getText(),
                        quickSelectPatternsArea.getText())) {
                    // A label letter or pattern quick select cannot use: show it instead of saving it.
                    event.consume();
                    revealQuickSelectProblems();
                }
            });
        }
        
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
                // Migration guard: the stored global encoding takes effect once the Terminal page was
                // shown and saved. Only here, with the new value in place: applySettings() can still
                // abort after reading the dropdown, and an aborted save must not apply the old value.
                TerminalEncodingSupport.confirmOnSave(globalSettings, !LazyTabContent.isPending(terminalTab));

                // Save global settings
                try {
                    app.getGlobalSettingsManager().save();
                    if (app.getCodingAgentService() != null) {
                        // Re-read the toggle for every open local shell pane right away.
                        app.getCodingAgentService().evaluateAll();
                    }
                    for (MainWindow window : MainWindow.getOpenWindows()) {
                        window.onCodingAgentsChanged();
                    }
                    reloadTerminalHighlighting();
                    if (app.getAppBadgeService() != null) {
                        // Re-reads the badge toggle: applies the current count or clears the badge.
                        // The notification toggle is read live by the coordinator on every decision.
                        app.getAppBadgeService().refresh();
                    }
                    if (app.getControlApiServer() != null) {
                        // Ticking the box opens the listener and unticking it unlinks the socket;
                        // the server re-reads the gate rather than being told what the user chose.
                        app.getControlApiServer().applyEnabledState();
                        controlApiStatusLabel.setText(controlApiStatusText());
                        if (de.kortty.control.McpGate.verdict(app.getGlobalSettingsManager().getSettings(),
                                de.kortty.policy.PolicyManager.effective())
                                != de.kortty.control.McpGate.Verdict.READ_WRITE) {
                            // With the write tools or the MCP server off, an earlier "allow for this
                            // pane in this session" must not come back when they are switched on again.
                            de.kortty.control.ControlApiWiring.revokeMcpGrants(app.getControlApiServer());
                        }
                    }
                    app.applyLoggingSettings();
                    app.restartUpdateCheckService();
                    app.restartAutoBackupService();
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
    
    /** A red line under a quick-select field that says what is wrong with it; hidden while nothing is. */
    private static Label quickSelectErrorLabel() {
        Label error = new Label();
        error.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: #d9534f;");
        error.setWrapText(true);
        error.setMinHeight(Region.USE_PREF_SIZE); // wrap instead of ellipsizing
        error.setMaxWidth(UiFontScaleSupport.scaleDimension(560, true));
        error.setVisible(false);
        error.setManaged(false);
        return error;
    }

    /**
     * Shows under the quick-select fields what keeps them from being saved and marks a field with a
     * problem red; called on every change of either field.
     */
    private void validateQuickSelectFields() {
        String alphabetMessage = QuickSelectSettingsSupport.alphabetMessage(quickSelectAlphabetField.getText(), I18n::get);
        List<String> patternMessages = QuickSelectSettingsSupport.patternMessages(quickSelectPatternsArea.getText(), I18n::get);
        showQuickSelectProblem(quickSelectAlphabetField, quickSelectAlphabetError, alphabetMessage);
        showQuickSelectProblem(quickSelectPatternsArea, quickSelectPatternsError,
            patternMessages.isEmpty() ? null : String.join("\n", patternMessages));
    }

    private static void showQuickSelectProblem(TextInputControl field, Label error, String message) {
        boolean problem = message != null;
        error.setText(problem ? message : "");
        error.setVisible(problem);
        error.setManaged(problem);
        String base = field instanceof TextArea ? "-fx-font-family: monospace;" : "";
        field.setStyle(problem ? base + " -fx-border-color: #e74c3c; -fx-border-width: 2px; -fx-border-radius: 3px;" : base);
    }

    private static Tab sectionTab(SettingsSection section) {
        Tab tab = new Tab(I18n.get(section.labelKey()));
        tab.setUserData(section);
        return tab;
    }

    /** Selects by stable identity, independent of translation and tab ordering. */
    public void selectSection(SettingsSection section) {
        if (section == null) return;
        mainTabPane.getTabs().stream().filter(tab -> tab.getUserData() == section).findFirst()
            .ifPresent(tab -> mainTabPane.getSelectionModel().select(tab));
    }

    /** Shows the Terminal page with the first quick-select field that cannot be saved focused. */
    private void revealQuickSelectProblems() {
        validateQuickSelectFields();
        if (mainTabPane != null) {
            mainTabPane.getSelectionModel().select(terminalTab);
        }
        TextInputControl first = quickSelectAlphabetError.isVisible() ? quickSelectAlphabetField : quickSelectPatternsArea;
        // After the page is built and laid out: it is built on first selection.
        Platform.runLater(first::requestFocus);
    }

    /** @return true if save may continue, false to abort (e.g. vault locked and translation API key cannot be encrypted) */
    private boolean applySettings() {
        // The Save button's filter already stops here; this keeps any other way in from storing
        // label letters or patterns quick select cannot use. Before any setter runs, so nothing is half saved.
        if (!QuickSelectSettingsSupport.canSave(quickSelectAlphabetField.getText(), quickSelectPatternsArea.getText())) {
            revealQuickSelectProblems();
            return false;
        }
        // Snapshot for the "most changed settings" metric — read from the models
        // before any setter runs, diffed again on the success path.
        java.util.Map<String, Object> trackedSettingsBefore = captureTrackedSettings();

        settings.setFontFamily(fontFamilyCombo.getValue());
        settings.setFontSize(fontSizeSpinner.getValue());
        settings.setForegroundColor(toHex(foregroundColorPicker.getValue()));
        settings.setBackgroundColor(toHex(backgroundColorPicker.getValue()));
        settings.setCursorColor(toHex(cursorColorPicker.getValue()));
        // Both: the dedicated preference (which nothing but this checkbox writes) and the style the
        // terminal reads, kept in step so a profile copy can no longer decide whether the cursor blinks.
        settings.setCursorStyle(deriveCursorStyle(settings.getCursorStyle(), cursorBlinkCheck.isSelected()));
        if (globalSettings != null) {
            globalSettings.setTerminalCursorBlink(cursorBlinkCheck.isSelected());
        }
        String[] ansiNormalHex = new String[ConnectionSettings.ANSI_COLOR_COUNT];
        String[] ansiBrightHex = new String[ConnectionSettings.ANSI_COLOR_COUNT];
        for (int i = 0; i < ConnectionSettings.ANSI_COLOR_COUNT; i++) {
            ansiNormalHex[i] = toHex(ansiNormalPickers[i].getValue());
            ansiBrightHex[i] = toHex(ansiBrightPickers[i].getValue());
        }
        // Also marks the palette customised exactly when the colours differ from the built-in look.
        TerminalPaletteSupport.storeColorsTab(settings, ansiNormalHex, ansiBrightHex,
                toHex(selectionColorPicker.getValue()));
        settings.setThemeId(selectedGlobalThemeId);
        settings.setTerminalColumns(columnsSpinner.getValue());
        settings.setTerminalRows(rowsSpinner.getValue());
        settings.setScrollbackLines(scrollbackSpinner.getValue());
        com.sithtermfx.ui.settings.BoldTextMode boldTextMode = boldTextModeCombo.getValue();
        settings.setBoldTextMode((boldTextMode != null ? boldTextMode
                : com.sithtermfx.ui.settings.BoldTextMode.BOLD_FONT).name());
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
            globalSettings.setAutoReconnectEnabled(autoReconnectEnabledCheck.isSelected());
            globalSettings.setCommandTimestampsEnabled(commandTimestampsCheck.isSelected());
            globalSettings.setApplyThemeFonts(applyThemeFontsProperty.get());
            globalSettings.setAppDesign(appDesignCombo.getValue());
            globalSettings.setAppDesignAnimationsEnabled(appDesignAnimationsCheck.isSelected());
            globalSettings.setUiFontScaleAuto(uiFontScaleAutoCheck.isSelected());
            // Only store the spinner while it is the live control. Under auto it shows the derived
            // value, and writing that back would overwrite the manual choice the user returns to.
            if (!uiFontScaleAutoCheck.isSelected()) {
                globalSettings.setUiFontScalePercent(uiFontScaleSpinner.getValue());
            }
            globalSettings.setChatColorProfileId(
                chatColorProfileCombo.getValue() != null ? chatColorProfileCombo.getValue().id() : null);
        }
        
        // Save backup settings to GlobalSettings
        if (globalSettings != null) {
            globalSettings.setShowTerminalScrollbar(showTerminalScrollbarCheck.isSelected());
            globalSettings.setTerminalDragDropEnabled(terminalDragDropCheck.isSelected());
            globalSettings.setTerminalCopyOnSelectEnabled(terminalCopyOnSelectCheck.isSelected());
            globalSettings.setOsc52ClipboardWriteEnabled(osc52ClipboardWriteCheck.isSelected());
            globalSettings.setTerminalLinkDetectionEnabled(terminalLinkDetectionCheck.isSelected());
            // Save only gets here with both fields valid (the Save button's filter checks them).
            globalSettings.setTerminalQuickSelectAlphabet(
                QuickSelectSettingsSupport.alphabet(quickSelectAlphabetField.getText()));
            globalSettings.setTerminalQuickSelectPatterns(
                QuickSelectSettingsSupport.patterns(quickSelectPatternsArea.getText()));
            globalSettings.setPasteWarningMode(pasteWarningModeCombo.getValue());
            globalSettings.setPasteLargeWarningKiB(pasteLargeWarningSpinner.getValue() != null
                ? pasteLargeWarningSpinner.getValue() : PasteProtectionSettings.DEFAULT_LARGE_WARNING_KIB);
            globalSettings.setPasteLineDelayMs(pasteLineDelaySpinner.getValue() != null
                ? pasteLineDelaySpinner.getValue() : 0);
            globalSettings.setShellIntegrationEnabled(shellIntegrationCheck.isSelected());
            globalSettings.setTerminalRemoteSidebarPosition(remoteSidebarPositionCombo.getValue());
            globalSettings.setTerminalBellNotificationsEnabled(terminalBellNotificationsCheck.isSelected());
            globalSettings.setCommandFinishedNotificationsEnabled(commandFinishedNotificationsCheck.isSelected());
            globalSettings.setCommandFinishedNotificationSeconds(commandFinishedSecondsSpinner.getValue() != null
                ? commandFinishedSecondsSpinner.getValue() : TerminalNotificationPolicy.DEFAULT_COMMAND_FINISHED_SECONDS);
            globalSettings.setRemoteTerminalNotificationsEnabled(remoteTerminalNotificationsCheck.isSelected());
            globalSettings.setAiRunToastsEnabled(aiRunToastsCheck.isSelected());
            globalSettings.setTerminalSilenceSeconds(terminalSilenceSecondsSpinner.getValue() != null
                ? terminalSilenceSecondsSpinner.getValue() : PaneActivityMonitor.DEFAULT_SILENCE_SECONDS);
            globalSettings.setCloseActiveTerminalWindowsWithoutConfirmation(
                closeActiveTerminalWindowsWithoutConfirmationCheck.isSelected()
            );
            globalSettings.setTerminalRecordingEnabled(terminalRecordingAlwaysEnabledCheck.isSelected());
            globalSettings.setTerminalRecordingCaptureColorsEnabled(terminalRecordingCaptureColorsCheck.isSelected());
            globalSettings.setCodingAgentDetectionEnabled(codingAgentDetectionCheck.isSelected());
            globalSettings.setCodingAgentPanelAutoVisibility(codingAgentPanelAutoVisibilityCheck.isSelected());
            globalSettings.setCodingAgentNotificationsEnabled(codingAgentNotificationsCheck.isSelected());
            globalSettings.setCodingAgentAppBadgeEnabled(codingAgentAppBadgeCheck.isSelected());
            globalSettings.setControlApiEnabled(controlApiEnabledCheck.isSelected());
            boolean mcpAllowed = de.kortty.policy.PolicyManager.effective().mcpServerAllowed();
            globalSettings.setMcpServerEnabled(mcpAllowed && mcpServerEnabledCheck.isSelected());
            // Kept while the MCP server is off: the gate ignores it then, and ticking the server again
            // restores the earlier choice instead of silently dropping it.
            globalSettings.setMcpWriteToolsEnabled(mcpAllowed && mcpWriteToolsCheck.isSelected());
            globalSettings.setTerminalHighlightingEnabled(terminalHighlightingEnabledCheck.isSelected());
            globalSettings.setTerminalHighlightAlternateScreen(terminalHighlightAlternateScreenCheck.isSelected());
            globalSettings.setDefaultHighlightRuleSetId(
                HighlightSettingsSupport.storedValue(defaultHighlightSetCombo.getValue()));
            globalSettings.setTerminalTriggersEnabled(terminalTriggersEnabledCheck.isSelected());
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
            if (isolationDefaultCombo != null && isolationDefaultCombo.getValue() != null) {
                globalSettings.setConnectionIsolationDefault(isolationDefaultCombo.getValue());
            }

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
            
            // Translation profiles: typed API keys need the master password to be stored encrypted.
            if (translationProfilesPane != null) {
                char[] translationMaster = null;
                if (translationProfilesPane.needsMasterPassword()) {
                    translationMaster = masterPasswordOrOfferUnlock(I18n.get("settings.translation.error.vaultLocked"));
                    if (translationMaster == null) {
                        return false;
                    }
                }
                if (!translationProfilesPane.commitTo(globalSettings, translationMaster)) {
                    Alert err = new Alert(Alert.AlertType.WARNING, I18n.get("settings.translation.error.generationFailed"));
                    err.setHeaderText(null);
                    err.showAndWait();
                    return false;
                }
            }

            
            globalSettings.setMaxBackupCount(maxBackupSpinner.getValue());
            if (autoBackupIntervalCombo != null) {
                globalSettings.setAutoBackupInterval(autoBackupIntervalCombo.getValue());
                globalSettings.setAutoBackupDirectory(autoBackupDirectoryField.getText());
            }

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
            globalSettings.setToolbarIconSize((int) Math.round(toolbarIconSizeSlider.getValue()));
            globalSettings.setRememberWindowGeometry(rememberWindowGeometryCheck.isSelected());
            globalSettings.setRememberDashboardState(rememberDashboardStateCheck.isSelected());
            globalSettings.setOpenToolWindowsAsTabs(openToolWindowsAsTabsCheck.isSelected());
            globalSettings.setConnectionColorBorderEnabled(connectionColorBorderCheck.isSelected());
            globalSettings.setTabTitleFromShellEnabled(tabTitleFromShellCheck.isSelected());
            globalSettings.setTabSwitchMostRecentFirst(tabSwitchMostRecentFirstCheck.isSelected());
            globalSettings.setSessionRestoreMode(sessionRestoreModeCombo.getValue());
            boolean scrollbackWasOn = globalSettings.isSessionRestoreScrollback();
            globalSettings.setSessionRestoreScrollback(sessionRestoreScrollbackCheck.isSelected());
            globalSettings.setSessionRestoreScrollbackLines(sessionRestoreScrollbackLinesSpinner.getValue() != null
                ? sessionRestoreScrollbackLinesSpinner.getValue() : de.kortty.core.ScrollbackSnapshotCodec.DEFAULT_LINES);
            if (scrollbackWasOn != sessionRestoreScrollbackCheck.isSelected()) {
                // Off deletes every saved output file at once; on starts saving with the next round.
                MainWindow.sessionRestoreScrollbackChanged(sessionRestoreScrollbackCheck.isSelected());
            }

            // Save the shortcut overrides, only when the Keyboard page changed them: stored entries
            // it does not show (another platform's, a newer version's) are kept as they are.
            if (keyboardPage != null && keyboardPage.hasChanges()) {
                globalSettings.setKeyBindingOverrides(keyboardPage.overrides().toEntries());
            }
            
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
            globalSettings.setSftpParallelTransfers(sftpParallelTransfersSpinner.getValue());
            if (sftpConflictDefaultCombo.getValue() != null) {
                globalSettings.setSftpConflictDefault(sftpConflictDefaultCombo.getValue());
            }
            globalSettings.setSftpResumePartialTransfers(sftpResumePartialTransfersCheck.isSelected());
            globalSettings.setSftpKeepPartialOnCancel(sftpKeepPartialOnCancelCheck.isSelected());
            String editorCommand = sftpExternalEditorCommandField.getText();
            if (de.kortty.core.remote.edit.ExternalEditorLauncher.isValidTemplate(editorCommand)) {
                globalSettings.setSftpExternalEditorCommand(editorCommand);
            }
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

            Object recordedShortcut = snippetCompletionShortcutField.getUserData();
            globalSettings.setSnippetCompletionShortcut(SnippetCompletionShortcut.normalizeOrDefault(
                recordedShortcut instanceof String chord ? chord : null));

            globalSettings.setSnippetEditorPrewarmEnabled(snippetPrewarmCheck.isSelected());
            MonacoEditorWarmup.applyEnabled(snippetPrewarmCheck.isSelected());
            Integer analysisHistory = snippetAnalysisHistorySpinner.getValue();
            globalSettings.setSnippetAnalysisHistoryMaxSize(analysisHistory);
            Long analysisContentLimit = snippetAnalysisContentLimit.valueToStore();
            if (analysisContentLimit != null) {
                globalSettings.setSnippetAnalysisMaxStoredContentBytes(analysisContentLimit);
            }
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
        // The mode's enum name only (BOLD_FONT, BRIGHT_COLOR, BOLD_FONT_AND_BRIGHT_COLOR).
        tracked.add(new TrackedSetting("terminal", "bold_text_mode",
            () -> TerminalPaletteSupport.boldTextMode(settings).name(), true));
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
            tracked.add(new TrackedSetting("colors", "cursor_blink", gs::isTerminalCursorBlink, true));
            tracked.add(new TrackedSetting("appearance", "app_design", gs::getAppDesign, true));
            tracked.add(new TrackedSetting("appearance", "animations_enabled", gs::isAppDesignAnimationsEnabled, true));
            tracked.add(new TrackedSetting("appearance", "apply_theme_fonts", gs::isApplyThemeFonts, true));
            tracked.add(new TrackedSetting("appearance", "ui_font_scale_percent", gs::getUiFontScalePercent, true));
            tracked.add(new TrackedSetting("appearance", "ui_font_scale_auto", gs::isUiFontScaleAuto, true));
            tracked.add(new TrackedSetting("terminal", "connection_retries_enabled", gs::isConnectionRetriesEnabled, true));
            tracked.add(new TrackedSetting("terminal", "scrollbar_visible", gs::isShowTerminalScrollbar, true));
            tracked.add(new TrackedSetting("terminal", "drag_drop_enabled", gs::isTerminalDragDropEnabled, true));
            tracked.add(new TrackedSetting("terminal", "copy_on_select", gs::isTerminalCopyOnSelectEnabled, true));
            tracked.add(new TrackedSetting("terminal", "osc52_clipboard_write", gs::isOsc52ClipboardWriteEnabled, true));
            tracked.add(new TrackedSetting("terminal", "link_detection", gs::isTerminalLinkDetectionEnabled, true));
            // Whether the user set label letters or patterns of their own, never the letters or patterns.
            tracked.add(new TrackedSetting("terminal", "quick_select_alphabet_customized",
                () -> gs.getTerminalQuickSelectAlphabet() != null, true));
            tracked.add(new TrackedSetting("terminal", "quick_select_patterns_customized",
                () -> !gs.getTerminalQuickSelectPatterns().isEmpty(), true));
            tracked.add(new TrackedSetting("terminal", "close_without_confirmation",
                gs::isCloseActiveTerminalWindowsWithoutConfirmation, true));
            tracked.add(new TrackedSetting("terminal", "paste_warning_mode", () -> gs.getPasteWarningMode().id(), true));
            tracked.add(new TrackedSetting("terminal", "paste_large_warning_kib", gs::getPasteLargeWarningKiB, true));
            tracked.add(new TrackedSetting("terminal", "paste_line_delay_ms", gs::getPasteLineDelayMs, true));
            tracked.add(new TrackedSetting("terminal", "shell_integration", gs::isShellIntegrationEnabled, true));
            tracked.add(new TrackedSetting("terminal", "bell_notifications", gs::isTerminalBellNotificationsEnabled, true));
            tracked.add(new TrackedSetting("terminal", "command_finished_notifications",
                gs::isCommandFinishedNotificationsEnabled, true));
            tracked.add(new TrackedSetting("terminal", "command_finished_notification_seconds",
                gs::getCommandFinishedNotificationSeconds, true));
            tracked.add(new TrackedSetting("terminal", "remote_notifications",
                gs::isRemoteTerminalNotificationsEnabled, true));
            tracked.add(new TrackedSetting("terminal", "ai_run_notifications", gs::isAiRunToastsEnabled, true));
            tracked.add(new TrackedSetting("terminal", "silence_seconds", gs::getTerminalSilenceSeconds, true));
            tracked.add(new TrackedSetting("terminal", "coding_agent_detection", gs::isCodingAgentDetectionEnabled, true));
            tracked.add(new TrackedSetting("terminal", "coding_agent_notifications",
                gs::isCodingAgentNotificationsEnabled, true));
            tracked.add(new TrackedSetting("terminal", "coding_agent_app_badge", gs::isCodingAgentAppBadgeEnabled, true));
            tracked.add(new TrackedSetting("terminal", "control_api_enabled", gs::isControlApiEnabled, true));
            tracked.add(new TrackedSetting("terminal", "mcp_server_enabled", gs::isMcpServerEnabled, true));
            tracked.add(new TrackedSetting("terminal", "mcp_write_tools_enabled", gs::isMcpWriteToolsEnabled, true));
            tracked.add(new TrackedSetting("terminal", "highlighting_enabled", gs::isTerminalHighlightingEnabled, true));
            tracked.add(new TrackedSetting("terminal", "highlighting_full_screen",
                gs::isTerminalHighlightAlternateScreen, true));
            // The set's class only (a built-in id, "custom" or "none"), never the name of a user's set.
            tracked.add(new TrackedSetting("terminal", "highlighting_default_set",
                () -> HighlightSettingsSupport.telemetryValue(gs.getDefaultHighlightRuleSetId()), true));
            tracked.add(new TrackedSetting("terminal", "highlighting_triggers", gs::isTerminalTriggersEnabled, true));
            tracked.add(new TrackedSetting("video", "recording_enabled", gs::isTerminalRecordingEnabled, true));
            tracked.add(new TrackedSetting("video", "capture_colors", gs::isTerminalRecordingCaptureColorsEnabled, true));
            tracked.add(new TrackedSetting("backup", "max_count", gs::getMaxBackupCount, true));
            tracked.add(new TrackedSetting("backup", "encryption_type", gs::getBackupEncryptionType, true));
            tracked.add(new TrackedSetting("backup", "auto_interval", gs::getAutoBackupInterval, true));
            tracked.add(new TrackedSetting("backup", "auto_directory_set",
                () -> gs.getAutoBackupDirectory() != null, true));
            tracked.add(new TrackedSetting("logging", "retention_days", gs::getLogRetentionDays, true));
            tracked.add(new TrackedSetting("logging", "directory_customized",
                () -> gs.getLogDirectoryPath() != null && !gs.getLogDirectoryPath().isBlank(), true));
            tracked.add(new TrackedSetting("updates", "checks_enabled", gs::isUpdateChecksEnabled, true));
            tracked.add(new TrackedSetting("updates", "interval_days", gs::getUpdateCheckIntervalDays, true));
            tracked.add(new TrackedSetting("window", "remember_geometry", gs::isRememberWindowGeometry, true));
            tracked.add(new TrackedSetting("window", "remember_dashboard", gs::isRememberDashboardState, true));
            tracked.add(new TrackedSetting("window", "tools_as_tabs", gs::isOpenToolWindowsAsTabs, true));
            tracked.add(new TrackedSetting("window", "fixed_geometry", gs::isUseFixedWindowGeometry, true));
            tracked.add(new TrackedSetting("window", "session_restore_mode", () -> gs.getSessionRestoreMode().id(), true));
            tracked.add(new TrackedSetting("window", "session_restore_output", gs::isSessionRestoreScrollback, true));
            tracked.add(new TrackedSetting("window", "session_restore_output_lines",
                gs::getSessionRestoreScrollbackLines, true));
            tracked.add(new TrackedSetting("window", "tab_title_from_shell", gs::isTabTitleFromShellEnabled, true));
            tracked.add(new TrackedSetting("window", "tab_switch_most_recent_first", gs::isTabSwitchMostRecentFirst, true));
            // Whether any shortcut was rebound, never which action or which keys.
            tracked.add(new TrackedSetting("keyboard", "shortcuts_customized",
                () -> !gs.getKeyBindingOverrides().isEmpty(), true));
            tracked.add(new TrackedSetting("security", "require_master_password_on_startup",
                gs::isRequireMasterPasswordOnStartup, true));
            tracked.add(new TrackedSetting("security", "temporary_ssh_key_enabled", gs::isTemporarySshKeyEnabled, true));
            tracked.add(new TrackedSetting("language", "ui_language", gs::getLanguage, true));
            tracked.add(new TrackedSetting("translation", "provider", gs::getTranslationApiProvider, true));
            tracked.add(new TrackedSetting("translation", "api_url_set",
                () -> gs.getTranslationApiUrl() != null && !gs.getTranslationApiUrl().isBlank(), true));
            tracked.add(new TrackedSetting("translation", "api_region_customized",
                () -> gs.getTranslationApiRegion() != null && !gs.getTranslationApiRegion().isBlank(), true));
            tracked.add(new TrackedSetting("sftp", "auto_close_minutes", gs::getSftpAutoCloseMinutes, true));
            tracked.add(new TrackedSetting("sftp", "parallel_transfers", gs::getSftpParallelTransfers, true));
            tracked.add(new TrackedSetting("sftp", "conflict_default", () -> gs.getSftpConflictDefault().id(), true));
            tracked.add(new TrackedSetting("sftp", "resume_partial_transfers", gs::isSftpResumePartialTransfers, true));
            tracked.add(new TrackedSetting("sftp", "keep_partial_on_cancel", gs::isSftpKeepPartialOnCancel, true));
            tracked.add(new TrackedSetting("sftp", "external_editor_command_set",
                () -> !gs.getSftpExternalEditorCommand().isBlank(), true));
            tracked.add(new TrackedSetting("snippet_editor", "analysis_history_max",
                gs::getSnippetAnalysisHistoryMaxSize, true));
            tracked.add(new TrackedSetting("snippet_editor", "analysis_content_limit_bytes",
                gs::getSnippetAnalysisMaxStoredContentBytes, true));
        }
        return tracked;
    }

    /** The label key of a choice of Settings › AI › AI chat code blocks in the terminal. */
    static String aiChatTerminalActionsKey(de.kortty.model.AiChatTerminalActions value) {
        return switch (value) {
            case OFF -> "settings.ai.chatTerminalActions.off";
            case INSERT_ONLY -> "settings.ai.chatTerminalActions.insertOnly";
            case INSERT_AND_RUN -> "settings.ai.chatTerminalActions.insertAndRun";
        };
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
        if (sessionJournalClickableLinksCheck != null && !sessionJournalClickableLinksCheck.isDisabled()) {
            globalSettings.setSessionJournalClickableLinks(sessionJournalClickableLinksCheck.isSelected());
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
        if (automationJournalAiProfileCombo != null) {
            de.kortty.model.AiProfile selectedProfile = automationJournalAiProfileCombo.getValue();
            globalSettings.setAutomationJournalAiProfileId(
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

    /**
     * Below the saved limit: says that nothing is deleted now, and (read on the analysis store's
     * thread) how many stored analyses the next analysis of each snippet would remove.
     */
    private void updateAnalysisHistoryLoweredHint(Label hint, int savedLimit, Integer value) {
        int limit = value != null ? value : savedLimit;
        if (limit >= savedLimit) {
            hint.setVisible(false);
            hint.setManaged(false);
            return;
        }
        String base = I18n.get("settings.snippetEditor.analysisHistory.lowered", limit);
        hint.setText(base);
        hint.setVisible(true);
        hint.setManaged(true);
        de.kortty.core.SnippetAnalysisStore.shared().allOverviews().whenComplete((overviews, error) -> {
            if (error != null || overviews == null) {
                return;
            }
            int analyses = 0;
            int snippets = 0;
            for (de.kortty.core.SnippetAnalysisOverview overview : overviews.values()) {
                int trimmed = overview.trimmableAt(limit);
                if (trimmed > 0) {
                    analyses += trimmed;
                    snippets++;
                }
            }
            int count = analyses;
            int affected = snippets;
            javafx.application.Platform.runLater(() -> {
                Integer current = snippetAnalysisHistorySpinner.getValue();
                if (current == null || current != limit || !hint.isVisible()) {
                    return; // the value moved on meanwhile
                }
                hint.setText(count > 0
                    ? base + " " + I18n.get("settings.snippetEditor.analysisHistory.loweredCount", count, affected)
                    : base);
            });
        });
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
    private Region buildSectionNavigationContent(TabPane tabPane) {
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
            // The tab at the selected index, not the selected item: selecting a tab sets the index
            // first, and this runs from the index listener while the item is still the old tab.
            Tab selected = index >= 0 && index < count ? tabPane.getTabs().get(index) : null;
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

        HBox arrows = new HBox(8, previousButton, positionLabel, nextButton);
        arrows.setAlignment(Pos.CENTER);
        arrows.setPickOnBounds(false);

        Hyperlink guideLink = buildSectionGuideLink(tabPane);
        StackPane navBar = new StackPane(arrows, guideLink);
        StackPane.setAlignment(guideLink, Pos.CENTER_RIGHT);
        navBar.setPadding(new Insets(2, 8, 8, 8));
        navBar.getStyleClass().add("settings-section-nav-bar");

        BorderPane body = new BorderPane(tabPane);
        body.setTop(navBar);

        settingsSearch = new SettingsSearchBar(tabPane, body);
        HBox searchRow = new HBox(settingsSearch.node());
        HBox.setHgrow(settingsSearch.node(), Priority.ALWAYS);
        searchRow.setAlignment(Pos.CENTER);
        searchRow.setPadding(new Insets(4, 8, 6, 8));
        searchRow.getStyleClass().add("settings-search-row");

        BorderPane content = new BorderPane(settingsSearch.host());
        content.setTop(searchRow);
        return content;
    }

    /** The link to the manual's page for whichever section is shown, at the right of the navigation bar. */
    private Hyperlink buildSectionGuideLink(TabPane tabPane) {
        Hyperlink guideLink = new Hyperlink(I18n.get("settings.nav.guide"));
        guideLink.setId("settings-guide-link");
        guideLink.getStyleClass().add("settings-section-guide-link");
        Tooltip guideTooltip = new Tooltip();
        guideLink.setTooltip(guideTooltip);
        Runnable updateGuide = () -> {
            Tab selected = tabPane.getSelectionModel().getSelectedItem();
            String title = selected != null ? selected.getText() : "";
            guideTooltip.setText(I18n.get("settings.nav.guide.tooltip", title));
            guideLink.setAccessibleText(I18n.get("settings.nav.guide.tooltip", title));
        };
        tabPane.getSelectionModel().selectedItemProperty().addListener((obs, oldTab, newTab) -> updateGuide.run());
        updateGuide.run();
        guideLink.setOnAction(e -> {
            Tab selected = tabPane.getSelectionModel().getSelectedItem();
            SettingsSection section = selected != null && selected.getUserData() instanceof SettingsSection s ? s : null;
            GuideViewer.show(app, getDialogPane().getScene().getWindow(), SettingsGuideLinks.location(section));
            guideLink.setVisited(false);
        });
        return guideLink;
    }

    /**
     * A hyperlink that opens the manual at {@code guideLocation}, placed next to a section header. Carries
     * {@link SettingsSearchBar#DOC_LINK_STYLE} so the settings search does not list it as an option.
     */
    private Hyperlink docLink(String guideLocation) {
        Hyperlink link = new Hyperlink(I18n.get("docLink.open"));
        link.getStyleClass().add(SettingsSearchBar.DOC_LINK_STYLE);
        link.setTooltip(new Tooltip(I18n.get("docLink.open.tooltip")));
        link.setOnAction(e -> {
            try {
                GuideViewer.show(app, getDialogPane().getScene().getWindow(), guideLocation);
            } catch (RuntimeException ex) {
                org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not open the guide at {}", guideLocation, ex);
            }
            link.setVisited(false);
        });
        return link;
    }

    /**
     * The "Automatic backup" section of the Backup tab: interval, folder and the status line that
     * says when the last automatic backup ran or why none can run right now.
     *
     * @return the next free row
     */
    private int addAutoBackupSection(GridPane grid, int row) {
        grid.add(new Separator(), 0, row++, 2, 1);
        boolean scheduleManaged = de.kortty.policy.PolicyManager.effective()
            .isManaged(de.kortty.policy.ManagedSetting.AUTO_BACKUP_SCHEDULE);
        boolean directoryManaged = de.kortty.policy.PolicyManager.effective()
            .isManaged(de.kortty.policy.ManagedSetting.AUTO_BACKUP_DIRECTORY);
        if (scheduleManaged || directoryManaged) {
            grid.add(de.kortty.policy.PolicyUiSupport.managedTabBanner(), 0, row++, 2, 1);
        }
        grid.add(sectionHeader("settings.backup.auto.header", "reference/settings/backup.html#automatic-backup"),
            0, row++, 2, 1);
        Label info = new Label(I18n.get("settings.backup.auto.info"));
        info.setWrapText(true);
        info.setMaxWidth(640);
        info.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        grid.add(info, 0, row++, 2, 1);

        autoBackupIntervalCombo = new ComboBox<>();
        autoBackupIntervalCombo.getItems().addAll(GlobalSettings.AutoBackupInterval.values());
        autoBackupIntervalCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(GlobalSettings.AutoBackupInterval interval) {
                return interval == null ? "" : I18n.get("settings.backup.auto.interval." + interval.id());
            }

            @Override
            public GlobalSettings.AutoBackupInterval fromString(String text) {
                return null;
            }
        });
        autoBackupIntervalCombo.setValue(globalSettings != null
            ? globalSettings.getAutoBackupInterval() : GlobalSettings.AutoBackupInterval.WEEKLY);
        autoBackupIntervalCombo.setTooltip(new Tooltip(I18n.get("settings.backup.auto.interval.tooltip")));
        de.kortty.policy.PolicyUiSupport.lockIfManaged(autoBackupIntervalCombo,
            de.kortty.policy.ManagedSetting.AUTO_BACKUP_SCHEDULE);
        grid.add(new Label(I18n.get("settings.backup.auto.interval")), 0, row);
        grid.add(autoBackupIntervalCombo, 1, row++);

        autoBackupDirectoryField = new TextField(globalSettings != null && globalSettings.getAutoBackupDirectory() != null
            ? globalSettings.getAutoBackupDirectory() : "");
        autoBackupDirectoryField.setPromptText(I18n.get("settings.backup.auto.directory.prompt"));
        autoBackupDirectoryField.setPrefWidth(420);
        HBox.setHgrow(autoBackupDirectoryField, Priority.ALWAYS);
        Button browse = new Button(I18n.get("connEdit.browse"));
        browse.setOnAction(event -> {
            DirectoryChooser chooser = new DirectoryChooser();
            chooser.setTitle(I18n.get("settings.backup.auto.directory"));
            java.io.File current = autoBackupDirectoryField.getText().isBlank()
                ? null : new java.io.File(autoBackupDirectoryField.getText());
            if (current != null && current.isDirectory()) {
                chooser.setInitialDirectory(current);
            }
            java.io.File chosen = chooser.showDialog(getDialogPane().getScene().getWindow());
            if (chosen != null) {
                autoBackupDirectoryField.setText(chosen.getAbsolutePath());
            }
        });
        de.kortty.policy.PolicyUiSupport.lockIfManaged(autoBackupDirectoryField,
            de.kortty.policy.ManagedSetting.AUTO_BACKUP_DIRECTORY);
        de.kortty.policy.PolicyUiSupport.lockIfManaged(browse, de.kortty.policy.ManagedSetting.AUTO_BACKUP_DIRECTORY);
        HBox directoryRow = new HBox(8, autoBackupDirectoryField, browse);
        directoryRow.setAlignment(Pos.CENTER_LEFT);
        grid.add(new Label(I18n.get("settings.backup.auto.directory")), 0, row);
        grid.add(directoryRow, 1, row++);

        Label status = new Label();
        status.setWrapText(true);
        status.setMaxWidth(640);
        status.setStyle("-fx-font-size: 0.8462em;");
        Runnable refreshStatus = () -> status.setText(autoBackupStatusText());
        refreshStatus.run();
        autoBackupIntervalCombo.valueProperty().addListener((obs, old, value) -> refreshStatus.run());
        autoBackupDirectoryField.textProperty().addListener((obs, old, value) -> refreshStatus.run());
        grid.add(status, 0, row++, 2, 1);
        return row;
    }

    /**
     * The status line under the automatic-backup fields: the service's last outcome while the
     * stored values are shown, else a preview computed from what the fields say right now.
     */
    private String autoBackupStatusText() {
        if (globalSettings == null) {
            return "";
        }
        GlobalSettings.AutoBackupInterval interval = autoBackupIntervalCombo.getValue();
        String directory = autoBackupDirectoryField.getText();
        boolean unchanged = interval == globalSettings.getAutoBackupInterval()
            && java.util.Objects.equals(directory.isBlank() ? null : directory.trim(), globalSettings.getAutoBackupDirectory());
        de.kortty.core.AutoBackupService service = app != null ? app.getAutoBackupService() : null;
        if (unchanged && service != null) {
            de.kortty.core.AutoBackupService.Outcome outcome = service.lastOutcome();
            return AutoBackupStatusText.describe(outcome != null ? outcome : service.preflight(), globalSettings);
        }
        if (interval == GlobalSettings.AutoBackupInterval.OFF) {
            return I18n.get("settings.backup.auto.status.disabled");
        }
        if (directory.isBlank()) {
            return I18n.get("settings.backup.auto.status.noDirectory");
        }
        return I18n.get("settings.backup.auto.status.pendingSave");
    }

    /** A bold section header followed by a {@link #docLink(String)} to the manual section that explains it. */
    private HBox sectionHeader(String labelKey, String guideLocation) {
        Label header = new Label(I18n.get(labelKey));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
        HBox row = new HBox(10, header, docLink(guideLocation));
        row.setAlignment(Pos.CENTER_LEFT);
        return row;
    }

    /** The search line at the top of the dialog; for tests and smokes. */
    SettingsSearchBar settingsSearch() {
        return settingsSearch;
    }

    private Tab createThemesTab(Stage owner) {
        Tab tab = sectionTab(SettingsSection.THEMES);
        tab.setClosable(false);
        
        ThemeManager themeManager = app != null ? app.getThemeManager() : null;
        if (themeManager == null) {
            tab.setContent(new Label(I18n.get("theme.notAvailable")));
            return tab;
        }
        
        VBox vbox = new VBox(15);
        vbox.setPadding(new Insets(20));
        
        Label header = new Label(I18n.get("theme.header"));
        header.setStyle("-fx-font-weight: bold; -fx-font-size: 1.0769em;");
        vbox.getChildren().add(header);
        
        Label desc = new Label(I18n.get("theme.description"));
        desc.setWrapText(true);
        desc.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        vbox.getChildren().add(desc);

        CheckBox applyThemeFontsCheck = new CheckBox(I18n.get("theme.applyFontOption"));
        applyThemeFontsCheck.selectedProperty().bindBidirectional(applyThemeFontsProperty);
        vbox.getChildren().add(applyThemeFontsCheck);

        Label previewHeader = new Label(I18n.get("theme.preview"));
        previewHeader.setStyle("-fx-font-weight: bold;");
        // The mock terminal: the theme's colours, font and cursor on a sample session, with the ANSI
        // colours as the Colors tab currently shows them (unsaved picker changes included).
        TerminalThemePreview preview = new TerminalThemePreview(this::previewPalette);
        HBox previewSwatches = new HBox(6);
        previewSwatches.setAlignment(Pos.CENTER_LEFT);
        VBox previewColumn = new VBox(8, previewHeader, preview, previewSwatches);
        VBox.setVgrow(preview, javafx.scene.layout.Priority.ALWAYS);
        HBox.setHgrow(previewColumn, javafx.scene.layout.Priority.ALWAYS);
        Runnable repaintPreview = () -> {
            Theme current = selectedThemeForPreview;
            preview.setTheme(current, previewFont(current));
            updateAgentSwatches(current, previewSwatches);
        };
        themePreviewRefresher = repaintPreview;
        applyThemeFontsProperty.addListener((obs, was, is) -> repaintPreview.run());
        tab.selectedProperty().addListener((obs, was, shown) -> {
            if (shown) {
                repaintPreview.run();
            }
        });

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
        themeList.setPrefWidth(240);
        themeList.setMinWidth(220);
        themeList.setMaxWidth(260);
        final boolean[] initializingThemeSelection = {true};
        VBox.setVgrow(themeList, javafx.scene.layout.Priority.ALWAYS);
        themeList.getSelectionModel().selectedItemProperty().addListener((obs, oldTheme, newTheme) -> {
            if (newTheme != null) {
                selectedGlobalThemeId = newTheme.getId();
                if (!initializingThemeSelection[0]) {
                    applyThemeCursorStyleToCurrentSettings(newTheme);
                }
            }
            selectedThemeForPreview = newTheme;
            repaintPreview.run();
        });

        if (selectedGlobalThemeId != null && !selectedGlobalThemeId.isEmpty()) {
            themeManager.getTheme(selectedGlobalThemeId).ifPresent(theme ->
                themeList.getSelectionModel().select(theme));
        }
        if (themeList.getSelectionModel().getSelectedItem() == null && !themeList.getItems().isEmpty()) {
            themeList.getSelectionModel().selectFirst();
        }
        initializingThemeSelection[0] = false;
        selectedThemeForPreview = themeList.getSelectionModel().getSelectedItem();
        repaintPreview.run();

        VBox buttons = new VBox(6);
        buttons.setFillWidth(true);
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
        
        for (Button button : List.of(addBtn, editBtn, duplicateBtn, deleteBtn)) {
            button.setMaxWidth(Double.MAX_VALUE);
        }
        buttons.getChildren().addAll(addBtn, editBtn, duplicateBtn, deleteBtn);
        VBox listColumn = new VBox(8, themeList, buttons);
        listColumn.setPrefWidth(240);
        listColumn.setMinWidth(220);
        listColumn.setMaxWidth(260);
        HBox columns = new HBox(16, listColumn, previewColumn);
        VBox.setVgrow(columns, javafx.scene.layout.Priority.ALWAYS);
        vbox.getChildren().add(columns);

        LazyTabContent.defer(tab, vbox);
        return tab;
    }

    /** The theme the Themes tab shows in its preview; kept so edits and tab switches can repaint it. */
    private Theme selectedThemeForPreview;
    /** Repaints the Themes tab's preview; set once the tab is built, called after list refreshes. */
    private Runnable themePreviewRefresher;

    /**
     * The ANSI colours for the theme preview: the Colors tab's pickers when they exist (so an unsaved
     * change shows up immediately), else the stored default terminal settings.
     */
    private TerminalPreviewModel.Palette previewPalette() {
        ConnectionSettings stored = globalSettings != null ? globalSettings.getDefaultTerminalSettings() : null;
        TerminalPreviewModel.Palette fromSettings = TerminalPreviewModel.palette(stored);
        return (index, bright) -> {
            ColorPicker[] pickers = bright ? ansiBrightPickers : ansiNormalPickers;
            if (index >= 0 && index < pickers.length && pickers[index] != null && pickers[index].getValue() != null) {
                return toHex(pickers[index].getValue());
            }
            return fromSettings.apply(index, bright);
        };
    }

    /** The font the terminal would use with {@code theme}: the theme's own when themes carry fonts, else the Font tab's. */
    private Font previewFont(Theme theme) {
        if (theme != null && applyThemeFontsProperty.get()) {
            return Font.font(theme.getFontFamily(), Math.max(8, theme.getFontSize()));
        }
        String family = fontFamilyCombo != null && fontFamilyCombo.getValue() != null ? fontFamilyCombo.getValue() : "Monospaced";
        int size = fontSizeSpinner != null && fontSizeSpinner.getValue() != null ? fontSizeSpinner.getValue() : 13;
        return Font.font(family, Math.max(8, size));
    }

    /**
     * Where the Keyboard page takes its actions from: the menu bar of the window that opens the
     * dialog ({@link KeymapSupport#catalog}). Read when the page is first shown, so it is set right
     * after construction; without one the page says that shortcuts cannot be changed here.
     */
    void setKeymapCatalogSource(Supplier<KeyboardSettingsModel.Catalog> source) {
        this.keymapCatalogSource = source != null ? source : () -> null;
    }

    /** Shares the existing notification controls and their persisted switches on a dedicated page. */
    private Tab createMessagingTab() {
        Tab tab = sectionTab(SettingsSection.MESSAGING);
        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(10);
        grid.setPadding(new Insets(20));
        int row = 0;

        Label description = new Label(I18n.get("settings.messaging.description"));
        description.setWrapText(true);
        grid.add(description, 0, row++, 2, 1);
        Label codingAgentHeader = new Label(I18n.get("settings.codingAgent.header"));
        codingAgentHeader.setStyle("-fx-font-weight: bold;");
        grid.add(codingAgentHeader, 0, row++, 2, 1);
        grid.add(codingAgentNotificationsCheck, 0, row++, 2, 1);
        grid.add(new Separator(), 0, row++, 2, 1);
        Label notificationsHeader = new Label(I18n.get("settings.messaging.header"));
        notificationsHeader.setStyle("-fx-font-weight: bold;");
        grid.add(notificationsHeader, 0, row++, 2, 1);
        grid.add(terminalBellNotificationsCheck, 0, row++, 2, 1);
        grid.add(commandFinishedNotificationsCheck, 0, row++, 2, 1);
        grid.add(new Label(I18n.get("settings.messaging.commandFinishedSeconds")), 0, row);
        HBox commandFinishedSecondsBox = new HBox(10, commandFinishedSecondsSpinner,
            new Label(I18n.get("settings.messaging.commandFinishedSeconds.unit")));
        commandFinishedSecondsBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(commandFinishedSecondsBox, 1, row++);
        grid.add(remoteTerminalNotificationsCheck, 0, row++, 2, 1);
        grid.add(aiRunToastsCheck, 0, row++, 2, 1);
        grid.add(new Label(I18n.get("settings.messaging.silenceSeconds")), 0, row);
        HBox terminalSilenceSecondsBox = new HBox(10, terminalSilenceSecondsSpinner,
            new Label(I18n.get("settings.messaging.silenceSeconds.unit")));
        terminalSilenceSecondsBox.setAlignment(Pos.CENTER_LEFT);
        grid.add(terminalSilenceSecondsBox, 1, row++);
        Label info = new Label(I18n.get("settings.messaging.info"));
        info.setStyle("-fx-font-size: 0.7692em; -fx-text-fill: gray;");
        info.setWrapText(true);
        grid.add(info, 0, row, 2, 1);
        for (CheckBox notification : List.of(codingAgentNotificationsCheck, terminalBellNotificationsCheck,
                commandFinishedNotificationsCheck, remoteTerminalNotificationsCheck, aiRunToastsCheck)) {
            notification.setWrapText(true);
            notification.setMaxWidth(Double.MAX_VALUE);
            GridPane.setHgrow(notification, Priority.ALWAYS);
        }
        LazyTabContent.defer(tab, grid);
        return tab;
    }

    /**
     * Builds the Keyboard tab: korTTY's actions with their shortcuts, a recorder to rebind one,
     * Remove, Reset and Reset All. The overrides are stored in the global settings on saving and
     * applied to every open window at once (MainWindow.refreshKeymapInAllWindows).
     */
    private Tab createKeyboardTab() {
        Tab tab = sectionTab(SettingsSection.KEYBOARD);
        tab.setClosable(false);
        LazyTabContent.defer(tab, () -> {
            KeyboardSettingsModel.Catalog catalog = keymapCatalogSource.get();
            if (catalog == null) {
                return KeyboardSettingsPage.unavailable();
            }
            de.kortty.core.KeymapOverrides stored = de.kortty.core.KeymapOverrides.parse(
                globalSettings != null ? globalSettings.getKeyBindingOverrides() : List.of());
            keyboardPage = new KeyboardSettingsPage(new KeyboardSettingsModel(catalog, stored));
            tab.setGraphic(keyboardPage.badge());
            return keyboardPage.node();
        });
        return tab;
    }

    /**
     * Builds the Resources tab: an opt-in JVM heap/GC profile. The choice is persisted and applied
     * by relaunching the packaged app at the next start (see {@code de.kortty.JvmRelauncher}); the
     * default (Balanced) keeps the shipped 2 GB cap and never relaunches.
     */
    private Tab createResourcesTab() {
        Tab tab = sectionTab(SettingsSection.RESOURCES);
        tab.setClosable(false);

        VBox vbox = new VBox(12);
        vbox.setPadding(new Insets(20));

        HBox header = sectionHeader("settings.resources.header", "reference/settings/resources.html");
        Label desc = new Label(I18n.get("settings.resources.description"));
        desc.setWrapText(true);
        desc.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");

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
        ramLabel.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");

        // Live ceiling for the selected profile: how much memory korTTY may use at most.
        Label maxHeapLabel = new Label();
        maxHeapLabel.setWrapText(true);
        maxHeapLabel.setStyle("-fx-font-weight: bold;");

        Label profileDetail = new Label();
        profileDetail.setWrapText(true);
        profileDetail.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");

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
        warn.setStyle(MutedTextStyle.HINT);

        Label restartInfo = new Label(I18n.get("settings.resources.restart"));
        restartInfo.setWrapText(true);
        restartInfo.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");

        vbox.getChildren().addAll(header, desc, profileRow, ramLabel, maxHeapLabel, profileDetail, warn, restartInfo);
        LazyTabContent.defer(tab, vbox);
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
        Theme selected = null;
        if (selectedThemeId != null && !selectedThemeId.isBlank()) {
            for (Theme theme : themeList.getItems()) {
                if (selectedThemeId.equals(theme.getId())) {
                    selected = theme;
                    break;
                }
            }
        }
        if (selected == null && !themeList.getItems().isEmpty()) {
            selected = themeList.getItems().getFirst();
        }
        themeList.getSelectionModel().select(selected);
        // An edited theme keeps its identity, so the selection listener does not fire: repaint explicitly.
        selectedThemeForPreview = selected;
        if (themePreviewRefresher != null) {
            themePreviewRefresher.run();
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
     * The legend under the mock terminal: the AI agent panel's colours, which the sample session
     * cannot show. Foreground, background and cursor are visible in the mock itself.
     */
    private void updateAgentSwatches(Theme theme, HBox previewSwatches) {
        previewSwatches.getChildren().clear();
        if (theme == null) {
            return;
        }
        Label agentBg = new Label(I18n.get("settings.preview.aiBg"));
        agentBg.setStyle("-fx-text-fill: " + theme.getAgentPanelTextColor() + "; -fx-background-color: " + theme.getAgentPanelBackgroundColor() + "; -fx-padding: 2 8 2 8; -fx-border-color: " + theme.getAgentPanelBorderColor() + "; -fx-border-radius: 4; -fx-background-radius: 4;");
        Label agentAccent = new Label(I18n.get("settings.preview.aiRun"));
        agentAccent.setStyle("-fx-text-fill: " + theme.getAgentPanelBackgroundColor() + "; -fx-background-color: " + theme.getAgentPanelAccentColor() + "; -fx-padding: 2 8 2 8; -fx-background-radius: 4;");
        Label agentError = new Label(I18n.get("settings.preview.aiErr"));
        agentError.setStyle("-fx-text-fill: " + theme.getAgentPanelTextColor() + "; -fx-background-color: " + theme.getAgentPanelErrorColor() + "; -fx-padding: 2 8 2 8; -fx-background-radius: 4;");
        previewSwatches.getChildren().addAll(agentBg, agentAccent, agentError);
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

    /** The label key of a choice in the Session Restore dropdown. */
    private static String sessionRestoreModeKey(SessionRestoreMode mode) {
        return switch (mode) {
            case ASK -> "settings.window.restore.mode.ask";
            case AUTO -> "settings.window.restore.mode.auto";
            case OFF -> "settings.window.restore.mode.off";
        };
    }

    /** The label key of a choice in the paste protection dropdown. */
    private static String pasteWarningModeKey(PasteWarningMode mode) {
        return switch (mode) {
            case OFF -> "settings.terminal.paste.mode.off";
            case UNLESS_BRACKETED -> "settings.terminal.paste.mode.unlessBracketed";
            case ALWAYS -> "settings.terminal.paste.mode.always";
        };
    }

    /** The label key of a choice in the Bold text dropdown. */
    static String boldTextModeKey(com.sithtermfx.ui.settings.BoldTextMode mode) {
        return switch (mode) {
            case BOLD_FONT -> "settings.terminal.boldText.boldFont";
            case BRIGHT_COLOR -> "settings.terminal.boldText.brightColor";
            case BOLD_FONT_AND_BRIGHT_COLOR -> "settings.terminal.boldText.boldFontAndBrightColor";
        };
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
    /**
     * Security › Session isolation: the level every connection gets that sets none itself and whose folders
     * set none, limited by the organization's minimum, and whether this computer has a sandbox.
     *
     * @return the next free row
     */
    private int addIsolationSection(GridPane grid, int row) {
        grid.add(sectionHeader("settings.security.isolation", "features/session-isolation.html"), 0, row++, 2, 1);
        Label info = new Label(I18n.get("settings.security.isolation.info"));
        info.setWrapText(true);
        info.setMaxWidth(640);
        info.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        grid.add(info, 0, row++, 2, 1);

        de.kortty.isolation.IsolationLevel floor = de.kortty.policy.PolicyManager.effective().isolationFloor();
        isolationDefaultCombo = new ComboBox<>();
        isolationDefaultCombo.getItems().setAll(de.kortty.isolation.IsolationLevel.atLeastLevels(floor));
        isolationDefaultCombo.setConverter(new javafx.util.StringConverter<>() {
            @Override
            public String toString(de.kortty.isolation.IsolationLevel level) {
                return level != null ? IsolationConnectionSupport.levelName(level) : "";
            }

            @Override
            public de.kortty.isolation.IsolationLevel fromString(String text) {
                return null;
            }
        });
        de.kortty.isolation.IsolationLevel current = globalSettings != null
            ? globalSettings.getConnectionIsolationDefault() : de.kortty.isolation.IsolationLevel.DEFAULT;
        isolationDefaultCombo.setValue(de.kortty.isolation.IsolationLevel.mostRestrictive(current, floor));
        isolationDefaultCombo.setTooltip(new Tooltip(I18n.get("settings.security.isolation.default.tooltip")));
        de.kortty.policy.PolicyUiSupport.lockIf(isolationDefaultCombo, isolationDefaultCombo.getItems().size() <= 1);
        Label label = new Label(I18n.get("settings.security.isolation.default"));
        label.setLabelFor(isolationDefaultCombo);
        grid.add(label, 0, row);
        grid.add(isolationDefaultCombo, 1, row++);
        if (floor != null) {
            Label managed = new Label(I18n.get("settings.security.isolation.minimum",
                IsolationConnectionSupport.levelName(floor)));
            managed.setWrapText(true);
            managed.setMaxWidth(640);
            managed.setStyle("-fx-font-size: 0.8462em;");
            grid.add(managed, 0, row++, 2, 1);
        }

        Label status = new Label(I18n.get("settings.security.isolation.sandbox.checking"));
        status.setWrapText(true);
        status.setMaxWidth(640);
        status.setStyle("-fx-font-size: 0.8462em;");
        grid.add(status, 0, row++, 2, 1);
        Thread probe = new Thread(() -> {
            de.kortty.isolation.sandbox.SandboxSupport.Availability availability =
                de.kortty.isolation.sandbox.SandboxSupport.availability();
            String text = availability.available()
                ? I18n.get("settings.security.isolation.sandbox.available", availability.backendId())
                : I18n.get("settings.security.isolation.sandbox.unavailable",
                    de.kortty.isolation.sandbox.LocalProcessSandbox.reason(availability));
            Platform.runLater(() -> status.setText(text));
        }, "Sandbox-SelfTest");
        probe.setDaemon(true);
        probe.start();

        Label protocols = new Label(I18n.get("settings.security.isolation.protocols"));
        protocols.setWrapText(true);
        protocols.setMaxWidth(640);
        protocols.setStyle("-fx-font-size: 0.8462em; -fx-text-fill: gray;");
        grid.add(protocols, 0, row++, 2, 1);
        return row;
    }

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

                // A session started with the vault locked still holds its temporary SSH keys only
                // encrypted with the current password. Unlock first so they are restored in memory
                // and re-encrypted with the new password instead of being dropped by the save below.
                if (mpm.isLocked() && mpm.verifyPassword(oldPasswordChars)) {
                    app.onVaultUnlocked();
                }

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
                        () -> configManager.saveOrThrow(mpm.getDerivedKey()));
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


    /** Mode-independent embedded model entry (llama.cpp GGUF or MLX) for the profile editor combo. */
    private record EmbeddedModelChoice(String id, String displayName) {
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

    private String formatAiReasoningOptions(List<AiReasoningEffort> options) {
        return AiReasoningSupport.normalizeOptions(options).stream()
            .map(effort -> I18n.get("settings.ai.reasoning." + effort.messageKeySuffix()))
            .collect(Collectors.joining(", "));
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


    /**
     * The master password, offering Unlock Vault… while the vault is locked. {@code null} when it
     * stays locked — the user has then seen {@code lockedMessage}, so no further alert is due.
     */
    private char[] masterPasswordOrOfferUnlock(String lockedMessage) {
        return VaultUnlockSupport.masterPasswordOrOfferUnlock(
            getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null,
            app != null ? app.getMasterPasswordManager() : null,
            lockedMessage);
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

    /** The failed-test text: the generic line, the provider's own reason when known, and the endpoint hint. */
    private static String aiTestFailureText(AiService svc, AiProfile profile) {
        StringBuilder text = new StringBuilder(I18n.get("settings.ai.error.testFailed"));
        String detail = svc != null ? svc.lastTestFailure() : null;
        if (detail != null && !detail.isBlank()) {
            text.append("\n\n").append(detail);
        }
        if (profile != null && OpenAiCompatibleAiService.isMiniMaxNativeEndpoint(profile.getApiUrl())) {
            text.append("\n\n").append(I18n.get("settings.ai.hint.minimaxEndpoint"));
        }
        return text.toString();
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


    /** Decrypts stored translation keys with the unlocked vault; nothing while it is locked. */
    private de.kortty.core.TranslationServiceFactory.SecretResolver translationSecrets() {
        return encrypted -> {
            try {
                char[] master = app != null && app.getMasterPasswordManager() != null
                    ? app.getMasterPasswordManager().getMasterPassword() : null;
                return master == null ? null : new de.kortty.security.EncryptionService().decryptPassword(encrypted, master);
            } catch (Exception e) {
                return null;
            }
        };
    }

    /** Opens the AI Manager from the translation tab, so an AI translation profile can be set up without leaving Settings. */
    private void openAiManagerForTranslation() {
        List<MainWindow> windows = MainWindow.getOpenWindows();
        MainWindow window = windows.isEmpty() ? null : windows.get(0);
        if (window == null) {
            return;
        }
        AiManagerDialog manager = new AiManagerDialog(window);
        if (getDialogPane().getScene() != null) {
            manager.initOwner(getDialogPane().getScene().getWindow());
        }
        manager.showAndWait();
        if (translationProfilesPane != null) {
            translationProfilesPane.refreshAiProfiles();
        }
    }

    private void openTranslationRunDialog() {
        if (globalSettings == null) {
            return;
        }
        TranslationRunDialog dialog = new TranslationRunDialog(globalSettings, translationSecrets(),
            KorTTYApplication.getConfigDirectory(), () -> {
                refreshTranslationGeneratedList(translationOutdatedLabelRef, translationRegenerateOutdatedButtonRef);
                refreshGuideTranslationList();
            });
        if (getDialogPane().getScene() != null) {
            dialog.initOwner(getDialogPane().getScene().getWindow());
        }
        dialog.showAndWait();
        refreshTranslationGeneratedList(translationOutdatedLabelRef, translationRegenerateOutdatedButtonRef);
        refreshGuideTranslationList();
    }

    /** The "translating … 42%" note next to the Translate-now button while a job runs, from either job. */
    private void installTranslationRunIndicator() {
        de.kortty.core.DynamicTranslationJob uiJob = de.kortty.core.DynamicTranslationJob.getInstance();
        de.kortty.core.GuideTranslationJob guideJob = de.kortty.core.GuideTranslationJob.getInstance();
        translationRunListener = () -> Platform.runLater(() -> {
            if (translationRunIndicator == null) {
                return;
            }
            if (uiJob.isRunning()) {
                de.kortty.core.DynamicTranslationJob.Snapshot s = uiJob.snapshot();
                translationRunIndicator.setText(I18n.get(s.paused() ? "settings.translation.run.indicator.paused"
                    : "settings.translation.run.indicator.ui", s.percent()));
            } else if (guideJob.isRunning()) {
                de.kortty.core.GuideTranslationJob.Snapshot s = guideJob.snapshot();
                translationRunIndicator.setText(I18n.get(s.paused() ? "settings.translation.run.indicator.paused"
                    : "settings.translation.run.indicator.guide", s.percent()));
            } else {
                translationRunIndicator.setText("");
                refreshTranslationGeneratedList(translationOutdatedLabelRef, translationRegenerateOutdatedButtonRef);
                refreshGuideTranslationList();
            }
        });
        uiJob.addListener(translationRunListener);
        guideJob.addListener(translationRunListener);
        translationRunListener.run();
        addEventHandler(javafx.scene.control.DialogEvent.DIALOG_HIDDEN, event -> {
            uiJob.removeListener(translationRunListener);
            guideJob.removeListener(translationRunListener);
        });
    }

    private void refreshTranslationGeneratedList() {
        refreshTranslationGeneratedList(null, null);
    }

    private void refreshTranslationGeneratedList(Label outdatedLabel, Button regenerateOutdatedBtn) {
        if (translationGeneratedList == null) {
            return;
        }
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
        de.kortty.core.TranslationServiceFactory.Problem problem = de.kortty.core.TranslationServiceFactory.problem(
            globalSettings != null ? globalSettings.getDefaultTranslationProfile() : null, globalSettings, translationSecrets(), null);
        TranslationService svc = problem == null
            ? de.kortty.core.TranslationServiceFactory.createDefault(globalSettings, translationSecrets()) : null;
        if (svc == null) {
            String text = TranslationUiSupport.problemText(problem);
            new Alert(Alert.AlertType.WARNING, text != null ? text
                : I18n.get("settings.translation.error.testFailed")).showAndWait();
            return;
        }
        regenerateButton.setDisable(true);
        Task<Void> task = new Task<>() {
            @Override
            protected Void call() throws Exception {
                DynamicLanguageGenerator gen = new DynamicLanguageGenerator(svc, KorTTYApplication.getConfigDirectory());
                int total = outdated.size();
                for (int i = 0; i < total; i++) {
                    final int idx = i;
                    Locale loc = outdated.get(i);
                    String lang = loc.getLanguage();
                    gen.generate(lang, p -> { });
                }
                return null;
            }
        };
        task.setOnSucceeded(ev -> {
            regenerateButton.setDisable(false);
            refreshTranslationGeneratedList(outdatedLabel, regenerateOutdatedBtn);
            new Alert(Alert.AlertType.INFORMATION, I18n.get("settings.translation.regenerateOutdatedDone")).showAndWait();
        });
        task.setOnFailed(ev -> {
            regenerateButton.setDisable(false);
            Throwable t = task.getException();
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Regenerate outdated failed", t);
            new Alert(Alert.AlertType.ERROR, I18n.get("settings.translation.error.generationFailed") + ": " + (t != null ? t.getMessage() : "")).showAndWait();
        });
        new Thread(task).start();
    }

    /** Set Up Shell Integration…: the window with the shell snippets, see {@link ShellIntegrationSetupDialog}. */
    private void openShellIntegrationSetup() {
        javafx.stage.Window owner = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        ShellIntegrationSetupDialog.open(owner, null);
    }

    /**
     * Edit Rules…: the rule-set editor. It saves on its own OK — the sets are not part of this dialog's
     * OK or Cancel — and writes into the same settings object this dialog applies its fields to, so
     * neither side loses the other's changes. Afterwards the default-set dropdown lists the sets as they
     * are now and keeps its selection unless the editor deleted that set.
     */
    private void editHighlightRules() {
        de.kortty.core.highlight.TerminalHighlightService service =
            app != null ? app.getTerminalHighlightService() : null;
        de.kortty.core.GlobalSettingsManager manager = app != null ? app.getGlobalSettingsManager() : null;
        String selection = HighlightSettingsSupport.storedValue(defaultHighlightSetCombo.getValue());
        java.util.List<String> idsBefore = HighlightSettingsSupport.selectableSetIds(service);
        javafx.stage.Window owner = getDialogPane().getScene() != null ? getDialogPane().getScene().getWindow() : null;
        if (!HighlightRulesDialog.showAndSave(owner, globalSettings, manager, service, selection)) {
            return;
        }
        java.util.List<String> idsAfter = HighlightSettingsSupport.selectableSetIds(service);
        String kept = HighlightSettingsSupport.selectionAfterRuleEdit(selection, idsBefore, idsAfter);
        java.util.List<HighlightSettingsSupport.DefaultSetChoice> choices = HighlightSettingsSupport.defaultSetChoices(
            idsAfter, service != null ? service::userSetName : null, kept);
        defaultHighlightSetCombo.getItems().setAll(choices);
        defaultHighlightSetCombo.setValue(HighlightSettingsSupport.selected(choices, kept));
    }

    /**
     * Moves every open pane to the highlight rule set it resolves to under the saved master switch,
     * full-screen option and default set. A pane's own choice from a menu or the shortcut stays on top.
     * Never breaks saving.
     */
    private void reloadTerminalHighlighting() {
        de.kortty.core.highlight.TerminalHighlightService service =
            app != null ? app.getTerminalHighlightService() : null;
        if (service == null || globalSettings == null) {
            return;
        }
        try {
            service.reload(globalSettings);
        } catch (RuntimeException e) {
            org.slf4j.LoggerFactory.getLogger(getClass()).warn("Could not apply the keyword highlighting settings: {}",
                e.toString());
        }
    }

    /**
     * Greys out the MCP switches that cannot take effect and says why; reads the boxes as shown, so
     * unticking the Control API greys them out before anything is saved.
     */
    private void refreshMcpControls() {
        McpSettingsSupport.State state = McpSettingsSupport.state(controlApiEnabledCheck.isSelected(),
            de.kortty.policy.PolicyManager.effective().mcpServerAllowed(), mcpServerEnabledCheck.isSelected());
        if (state.forceOff()) {
            mcpServerEnabledCheck.setSelected(false);
            mcpWriteToolsCheck.setSelected(false);
        }
        mcpServerEnabledCheck.setDisable(!state.serverEditable());
        mcpWriteToolsCheck.setDisable(!state.writeToolsEditable());
        String hint = state.hintKey() == null ? "" : I18n.get(state.hintKey());
        // Only the text changes: toggling managed inside the settings ScrollPane skips the relayout.
        mcpHintLabel.setText(hint);
    }

    /**
     * The Control API status line, rendered from the server's own state rather than from the
     * checkbox: the two can legitimately disagree — policy can deny the feature, and a start can fail
     * because another korTTY already owns the socket — and the user needs to see which it is.
     */
    private String controlApiStatusText() {
        de.kortty.KorTTYApplication application = de.kortty.KorTTYApplication.getInstance();
        de.kortty.control.ControlApiServer server =
            application == null ? null : application.getControlApiServer();
        if (server == null) {
            return I18n.get("settings.controlApi.status.disabled");
        }
        return switch (server.status()) {
            case DISABLED -> I18n.get("settings.controlApi.status.disabled");
            case BLOCKED_BY_POLICY -> I18n.get("settings.controlApi.status.blockedByPolicy");
            case RUNNING -> I18n.get("settings.controlApi.status.running",
                server.endpoint().map(de.kortty.control.EndpointDescriptor::displayText).orElse(""));
            case FAILED -> I18n.get("settings.controlApi.status.failed", server.statusDetail());
        };
    }
}
