package de.kortty.model;

import de.kortty.ai.llama.LlamaBackend;
import jakarta.xml.bind.annotation.*;

/**
 * Global application settings.
 */
@XmlRootElement(name = "globalSettings")
@XmlAccessorType(XmlAccessType.FIELD)
public class GlobalSettings {

    private static final String DEFAULT_AI_API_URL = "https://api.openai.com/v1/chat/completions";
    public static final int DEFAULT_JOB_SCHEDULER_JOURNAL_RETENTION_DAYS = 14;
    public static final int MAX_JOB_SCHEDULER_JOURNAL_RETENTION_DAYS = 3650;
    public static final int DEFAULT_LOG_RETENTION_DAYS = 7;
    public static final int MAX_LOG_RETENTION_DAYS = 3650;
    public static final int DEFAULT_UPDATE_CHECK_INTERVAL_DAYS = 1;
    public static final int MIN_UPDATE_CHECK_INTERVAL_DAYS = 1;
    public static final int MAX_UPDATE_CHECK_INTERVAL_DAYS = 30;
    /**
     * UI font scale bounds. The upper bound is deliberately conservative: several dialogs size
     * themselves from hard-coded pixel widths, so text starts to crowd well before 200%.
     */
    public static final int UI_FONT_SCALE_DEFAULT_PERCENT = 100;
    public static final int MIN_UI_FONT_SCALE_PERCENT = 80;
    public static final int MAX_UI_FONT_SCALE_PERCENT = 160;
    /**
     * Guide (manual) text size bounds. Wider than the UI scale because the guide is a reflowing
     * document in a WebView, not a layout built from fixed pixel widths.
     */
    public static final int GUIDE_FONT_SCALE_DEFAULT_PERCENT = 100;
    public static final int MIN_GUIDE_FONT_SCALE_PERCENT = 70;
    public static final int MAX_GUIDE_FONT_SCALE_PERCENT = 250;

    @XmlElement
    private String logDirectoryPath; // Blank/null = ~/.kortty/logs

    @XmlElement
    private Integer logRetentionDays = DEFAULT_LOG_RETENTION_DAYS; // 0 = unlimited

    @XmlElement
    private boolean updateChecksEnabled = true;

    @XmlElement
    private Integer updateCheckIntervalDays = DEFAULT_UPDATE_CHECK_INTERVAL_DAYS;

    @XmlElement
    private Long lastSuccessfulUpdateCheckMillis;

    @XmlElement
    private String ignoredUpdateVersion;

    @XmlElement
    private String snoozedUpdateVersion;

    @XmlElement
    private String updateSnoozedUntilLocalDate;

    @XmlElement
    private String lastAutomaticUpdatePromptVersion;

    @XmlElement
    private String lastAutomaticUpdatePromptLocalDate;

    @XmlElement
    private boolean telemetryEnabled = false; // Anonymous usage statistics: opt-in, default OFF

    @XmlElement
    private Integer telemetryConsentVersion = 0; // 0 = never asked; >=1 = decided for that consent text version

    @XmlElement
    private String telemetryConsentDate; // ISO-8601 instant of the last consent decision (GDPR Art. 7 record)

    @XmlElement
    private int maxBackupCount = 10; // Default: 10 backups, 0 = unlimited
    
    @XmlElement
    private String lastBackupPath; // Remember last backup location
    
    @XmlElement
    private long lastBackupTime; // Timestamp of last backup
    
    @XmlElement
    private BackupEncryptionType backupEncryptionType = BackupEncryptionType.PASSWORD;
    
    @XmlElement
    private String backupCredentialId; // Selected credential for password encryption
    
    @XmlElement
    private String backupGpgKeyId; // Selected GPG key for GPG encryption
    
    @XmlElement
    private boolean rememberWindowGeometry = true; // Remember last window geometry

    // Terminal window background transparency in percent: 0 = fully opaque (default), 100 = fully
    // transparent (desktop shows through). > 0 makes the window borderless (StageStyle.TRANSPARENT)
    // with custom chrome; toggling between 0 and > 0 requires an app restart.
    @XmlElement
    private int terminalBackgroundTransparency = 0;

    @XmlElement
    private boolean useFixedWindowGeometry = false; // Use fixed geometry instead of last used
    
    @XmlElement
    private WindowGeometry fixedWindowGeometry; // Fixed window geometry (when useFixedWindowGeometry is true)
    
    @XmlElement
    private WindowGeometry lastWindowGeometry; // Last saved window geometry
    
    @XmlElement
    private boolean rememberDashboardState = true; // Remember dashboard visibility
    
    @XmlElement
    private boolean dashboardVisible = false; // Last dashboard visibility state

    @XmlElement
    private boolean showMenuBar = true; // Show the main menu bar inside the window

    @XmlElement
    private boolean openToolWindowsAsTabs = false; // Open management tool windows as tabs in the main window

    @XmlElement
    private boolean jobSchedulerMenuStatusEnabled = true; // Show JobScheduler status in the menu bar

    @XmlElement
    private boolean preventSystemSleep = false; // Manual, persistent power-management override

    // 0 means keep journal entries indefinitely.
    @XmlElement
    private Integer jobSchedulerJournalRetentionDays = DEFAULT_JOB_SCHEDULER_JOURNAL_RETENTION_DAYS;

    @XmlElement
    private Double jobSchedulerJournalDetailDividerPosition; // Vertical journal/detail split position

    @XmlElement
    private String jobSchedulerRsyncBinaryPath; // Optional rsync binary path; blank means PATH lookup
    
    @XmlElement
    private double dashboardDividerPosition = 0.2; // Last dashboard divider position (0.0-1.0)
    
    @XmlElement
    private boolean showTerminalScrollbar = true; // Show scrollbar in terminal view

    @XmlElement
    private boolean hideTerminalScrollbarsInFullscreen = false; // Hide terminal scrollbars while the window is fullscreen
    
    @XmlElement
    private boolean commandTimestampsEnabled = false; // Show timestamp gutter in terminal

    @XmlElement
    private boolean terminalRecordingEnabled = false;

    @XmlElement
    private String terminalRecordingStoragePath; // Blank/null = ~/.kortty/recordings

    @XmlElement
    private TerminalRecordingFormat terminalRecordingFormat = TerminalRecordingFormat.KORTTY_REPLAY;

    @XmlElement
    private TerminalRecordingScope terminalRecordingDefaultScope = TerminalRecordingScope.ACTIVE_SPLIT;

    @XmlElement
    private boolean terminalRecordingAutoPauseEnabled = true;

    @XmlElement
    private Integer terminalRecordingIdlePauseSeconds = 20;

    @XmlElement
    private String terminalRecordingFfmpegPath; // Blank/null = ffmpeg from PATH

    @XmlElement
    private boolean terminalRecordingCaptureColorsEnabled = false;

    @XmlElement
    private String sessionJournalStoragePath; // Blank/null = ~/.kortty/journals

    @XmlElement
    private String sessionJournalAiProfileId; // Null = TEXT-workload/default AI profile

    @XmlElement
    private boolean sessionJournalAiSummariesEnabled = true;

    @XmlElement
    private Integer sessionJournalSummarizeIntervalMinutes = 5;

    @XmlElement
    private SessionJournalLogFormat sessionJournalLogFormat = SessionJournalLogFormat.DEFAULT;

    @XmlElement
    private Integer sessionJournalAiMaxLines = 100; // 0 = fill the model context (token budget)

    @XmlElement
    private Integer sessionJournalAiTokenBudget = 130_000; // Used only when sessionJournalAiMaxLines == 0

    @XmlElement
    private boolean sessionJournalAiChunkingEnabled = false; // Process the whole backlog in multiple prompts

    @XmlElement
    private boolean sessionJournalAiTitleEnabled = false; // Let the AI title the journal on close

    @XmlElement
    private Integer sessionJournalFontScalePercent = 100; // Font size of the generated journal page

    /** Height of the journal page's live-log tail in vh (null = the page's CSS default). */
    @XmlElement
    private Integer sessionJournalLiveTailHeightVh;

    /** User-defined markers; the four built-ins are added by SessionJournalMarkers, not stored. */
    @XmlElementWrapper(name = "sessionJournalMarkers")
    @XmlElement(name = "marker")
    private java.util.List<SessionJournalMarkerDefinition> sessionJournalMarkers = new java.util.ArrayList<>();

    /** Auto-marker rules, in priority order: the first enabled match wins. */
    @XmlElementWrapper(name = "sessionJournalMarkerRules")
    @XmlElement(name = "rule")
    private java.util.List<SessionJournalMarkerRule> sessionJournalMarkerRules = new java.util.ArrayList<>();

    @XmlElement
    private boolean sessionJournalMarkerRulesEnabled = false; // Opt-in: rules touch every new entry

    @XmlElement
    private String sessionJournalPageSchemeId; // Null/"auto" = the page's own light/dark pair

    @XmlElement
    private String sessionJournalPageUiFont; // Null = the page's default sans stack

    @XmlElement
    private String sessionJournalPageMonoFont; // Null = the page's default monospace stack

    @XmlElement
    private String sessionJournalPageTheme; // auto (follow the OS) | light | dark

    // ---- PDF export branding (shared by session journal and AI chat exports) ----

    @XmlElement
    private boolean pdfWatermarkEnabled = false; // Off by default: mark documents deliberately

    @XmlElement
    private String pdfWatermarkText; // Null = the built-in korTTY watermark

    @XmlElement
    private String pdfWatermarkColor; // #rrggbb; null = default grey

    @XmlElement
    private boolean exportFooterEnabled = true;

    @XmlElement
    private String exportFooterText; // Null = the built-in brand line plus the repository link

    @XmlElement
    private boolean terminalDragDropEnabled = true; // Allow drag-and-drop file copy into terminal

    @XmlElement
    private boolean terminalCopyOnSelectEnabled = true; // Copy selected text to clipboard automatically

    @XmlElement
    private boolean closeActiveTerminalWindowsWithoutConfirmation = false; // Ask before closing active terminal windows by default

    @XmlElement
    private boolean applyThemeFonts = false; // Apply font family/size when applying themes

    @XmlElement
    private String appDesign = AppDesign.NORMAL.getId(); // App-level UI design, default: normal

    @XmlElement
    private boolean appDesignAnimationsEnabled = true; // Subtle design animations (glow pulse / blink); doubles as reduce-motion switch

    /**
     * UI chrome font size in percent (menus, dialogs, labels). Boxed so a settings file written
     * before this setting existed deserializes to null and falls back to the default.
     */
    @XmlElement
    private Integer uiFontScalePercent = UI_FONT_SCALE_DEFAULT_PERCENT;

    @XmlElement
    private boolean uiFontScaleAuto = false; // Derive the UI font size from the display resolution

    /**
     * Text size of the manual, in percent. Separate from the UI font scale: the guide is a
     * document people read at length, and its window is resizable, so it earns its own control.
     */
    @XmlElement
    private Integer guideFontScalePercent = GUIDE_FONT_SCALE_DEFAULT_PERCENT;

    /**
     * The UI font scale that was in effect when dialog geometry was last stored. Remembered sizes
     * were measured against that scale, so a different one makes them the wrong size and they are
     * discarded in favour of a fresh layout. Null means "recorded before this was tracked".
     */
    @XmlElement
    private Integer uiFontScalePercentAtGeometrySave;

    @XmlElement
    private boolean requireMasterPasswordOnStartup = true; // Require master password on startup

    /**
     * When true, korTTY skips the master-password prompt on startup and unlocks the vault
     * automatically from a remembered password (see {@code de.kortty.security.MasterPasswordManager}).
     * INSECURE — the master password is stored obfuscated on disk; intended for throwaway/test
     * environments only. Default: false.
     */
    @XmlElement
    private boolean skipMasterPasswordPrompt = false;

    /** When true, temporary SSH key option is shown in Connection Manager and Quick Connect. Default: false. */
    @XmlElement
    private boolean temporarySshKeyEnabled = false;
    
    @XmlElement
    private String language; // Language code (e.g., "en", "de", "fr") - null means auto-detect
    
    /** Translation API provider for dynamic i18n (Google Translate or DeepL). */
    @XmlElement
    private TranslationApiProvider translationApiProvider;

    /** Opt-in JVM heap/GC profile for the packaged app; applied via relaunch, needs a restart. */
    @XmlElement
    private JvmResourceProfile jvmResourceProfile = JvmResourceProfile.BALANCED;
    
    /** Encrypted API key for translation service (decrypted with master password). */
    @XmlElement
    private String encryptedTranslationApiKey;
    
    /** Optional custom API URL for translation service (null = use provider default). */
    @XmlElement
    private String translationApiUrl;

    /** OpenAI-compatible AI API URL used for terminal selection analysis. */
    @XmlElement
    private String aiApiUrl = DEFAULT_AI_API_URL;

    /** Model name for the configured OpenAI-compatible AI API. */
    @XmlElement
    private String aiModel;

    /** Encrypted API key for the configured AI service. */
    @XmlElement
    private String encryptedAiApiKey;

    /** Named AI profiles for OpenAI-compatible endpoints. */
    @XmlElementWrapper(name = "aiProfiles")
    @XmlElement(name = "profile")
    private java.util.List<AiProfile> aiProfiles = new java.util.ArrayList<>();

    /** Global switch for sending user-defined AI skills with prompts. */
    @XmlElement
    private boolean aiSkillsEnabled = true;

    /** When enabled, KorTTY sends only active AI skills that match the current request. */
    @XmlElement
    private boolean aiSkillAutoDetectionEnabled = true;

    /** User-defined AI skills appended to AI prompts. */
    @XmlElementWrapper(name = "aiSkills")
    @XmlElement(name = "skill")
    private java.util.List<AiSkill> aiSkills = new java.util.ArrayList<>();

    /** AI skill ids pre-selected for every new Quick-Connect connection. The "Save" button in the
     *  connection-skills picker persists the current selection here. */
    @XmlElementWrapper(name = "defaultConnectionAiSkillIds")
    @XmlElement(name = "skillId")
    private java.util.List<String> defaultConnectionAiSkillIds = new java.util.ArrayList<>();

    /** Stable keys of the Quick-Connect collapsible sections the user last left expanded
     *  (e.g. "terminalAppearance"); toggling a section persists immediately. Empty = all collapsed. */
    @XmlElementWrapper(name = "quickConnectExpandedSections")
    @XmlElement(name = "section")
    private java.util.List<String> quickConnectExpandedSections = new java.util.ArrayList<>();

    /** Code languages the user added to the snippet editor's built-in list, normalized and lowercased. */
    @XmlElementWrapper(name = "customSnippetCodeLanguages")
    @XmlElement(name = "language")
    private java.util.List<String> customSnippetCodeLanguages = new java.util.ArrayList<>();

    /** Preferred AI profile used when no explicit profile is selected by the user. */
    @XmlElement
    private String defaultAiProfileId;

    /** Installed local model marked as the default/start model in the Local Models manager. */
    @XmlElement
    private String defaultLocalModelId;

    /** AI profile dedicated to snippet security checks. When null the default profile is used. */
    @XmlElement
    private String securityCheckAiProfileId;

    /** Preferred AI profile for translation, summarization, and general text generation. */
    @XmlElement
    private String textAiProfileId;

    /** Preferred AI profile for programming and code-related actions. */
    @XmlElement
    private String codingAiProfileId;

    /** Local model id used to create embeddings for RAG knowledge stores. */
    @XmlElement
    private String ragEmbeddingModelId;

    /** Encrypted optional Hugging Face access token for gated/private repositories. */
    @XmlElement
    private String encryptedHuggingFaceToken;

    /** Update behavior for the separately installed llama.cpp runtime. */
    @XmlElement
    private LlamaRuntimeUpdatePolicy llamaRuntimeUpdatePolicy = LlamaRuntimeUpdatePolicy.NOTIFY;

    /** Preferred separately installed runtime backend (AUTO keeps the active backend on updates). */
    @XmlElement
    private LlamaBackend preferredLlamaRuntimeBackend = LlamaBackend.AUTO;

    /** Encrypted Tavily API key used by KorTTY's direct web-search tool and Tavily MCP. */
    @XmlElement
    private String encryptedAiTavilyApiKey;

    /** Encrypted Bright Data API token used by Bright Data Web MCP. */
    @XmlElement
    private String encryptedAiBrightDataApiToken;

    /** Encrypted Brave Search API key used by Brave Search MCP. */
    @XmlElement
    private String encryptedAiBraveSearchApiKey;

    /**
     * Global AI request timeout in minutes. {@code 0} — the default — lets every AI request run to
     * completion; long analyses such as the snippet editor's full code analysis must not be cut
     * off unless the user asked for a limit. Individual profiles may override this.
     */
    @XmlElement
    private Integer aiRequestTimeoutMinutes = 0;

    /** Optional SearXNG instance URL used by SearXNG MCP setups. */
    @XmlElement
    private String aiSearxngUrl;

    /** LM Studio server label for the ephemeral Tavily MCP integration. */
    @XmlElement
    private String aiTavilyMcpServerLabel = "tavily";

    /** LM Studio server label for the ephemeral Bright Data MCP integration. */
    @XmlElement
    private String aiBrightDataMcpServerLabel = "bright-data";

    /** LM Studio plugin id for Brave Search MCP. */
    @XmlElement
    private String aiBraveSearchMcpPluginId;

    /** LM Studio plugin id for SearXNG MCP. */
    @XmlElement
    private String aiSearxngMcpPluginId;

    /** LM Studio plugin id for the community LM Studio Toolpack web-search server. */
    @XmlElement
    private String aiLmStudioToolpackMcpPluginId;

    /** Preferred natural language for AI-generated text inside program code comments and descriptions. */
    @XmlElement
    private String aiCodeTextDefaultLanguage;

    /**
     * Last target language chosen for "Translate selection" in the snippet editor. Holds either a
     * language code from the dropdown or a name the user typed, because the value is handed to the
     * model as prompt text rather than parsed as a locale.
     */
    @XmlElement
    private String snippetTranslationTargetLanguage;

    /** Selected color profile id for the AI chat surfaces; null/blank = follow the terminal theme. */
    @XmlElement
    private String chatColorProfileId;

    /** Font size used in temporary AI result tabs. */
    @XmlElement
    private Integer aiResultFontSize = 13;

    /** Font size used in the snippet security-check findings window. */
    @XmlElement
    private Integer securityReportFontSize = 13;

    /** Font size used in the AI diff / "review changes" windows. */
    @XmlElement
    private Integer aiDiffFontSize = 14;

    /** Font size used in the AI code-review / syntax-check findings window. */
    @XmlElement
    private Integer aiReviewFontSize = 14;

    /** Font size used in the AI technical-description window. */
    @XmlElement
    private Integer aiDescribeFontSize = 14;

    /** Font size used in the AI alternative-solutions previews. */
    @XmlElement
    private Integer aiAlternativesFontSize = 14;

    /** Font size used in the AI code-analysis window (left analysis pane). */
    @XmlElement
    private Integer codeAnalysisFontSize = 14;

    /** Whether the "Hardening options" panel in the AI code-analysis window is expanded. Default: collapsed. */
    @XmlElement
    private Boolean codeAnalysisHardeningExpanded = false;

    /** Whether the AI code-analysis window generates the flow diagram automatically on open. Default: enabled. */
    @XmlElement
    private Boolean codeAnalysisDiagramAutoGenerate = true;

    /** Font size used in the Workflow script-generation window's editors. */
    @XmlElement
    private Integer workflowScriptFontSize = 14;

    /** When false, AI menu entries and terminal AI context actions are disabled. Default: enabled. */
    @XmlElement
    private boolean aiFeaturesEnabled = true;

    /** Show a confirmation dialog before sending selected terminal text to AI. */
    @XmlElement
    private boolean aiConfirmBeforeSend = true;

    /** When false, executable terminal-agent runs are disabled while AI planning and agent-ask remain available. */
    @XmlElement
    private boolean terminalAgentExecutionEnabled = true;

    /** When true, mutating terminal-agent command sets require confirmation before they run. */
    @XmlElement
    private boolean terminalAgentConfirmMutatingCommandSets = false;

    /** Prefer OSC 133 prompt markers when the shell emits them. */
    @XmlElement
    private boolean defaultPromptHookEnabled = true;

    /** Show verbose AI agent debug messages by default. */
    @XmlElement
    private boolean terminalAgentShowDebugMessages = false;

    /** Show AI agent runtime notices by default. */
    @XmlElement
    private boolean terminalAgentShowRuntimeMessages = false;

    /** Also print the terminal agent's final answer into the terminal (not only the AI agent panel). */
    @XmlElement
    private boolean terminalAgentMirrorFinalAnswerToTerminal = true;

    /** Show the terminal-agent setup dialog before starting prompt-based terminal commands. */
    @XmlElement
    private boolean terminalAgentShowRunDialog = true;

    /** Base command name for terminal-agent shortcuts like agent, agent-ask and agent-plan. */
    @XmlElement
    private String terminalAgentCommandName = "agent";

    /** When true, terminal-agent shortcut names are matched without case sensitivity. */
    @XmlElement
    private boolean terminalAgentCommandNameCaseInsensitive = false;

    /** Preferred presentation target for AI agent runs. */
    @XmlElement
    private TerminalAgentExecutionTarget terminalAgentExecutionTarget = TerminalAgentExecutionTarget.TERMINAL_WINDOW;

    /** Remember the inline terminal-agent panel height and font size across application restarts. */
    @XmlElement
    private boolean terminalAgentRememberPanelLayout = false;

    /** Last saved inline terminal-agent panel height, used only when layout remembering is enabled. */
    @XmlElement
    private Double terminalAgentPanelHeight;

    /** Last saved inline terminal-agent panel font size, used only when layout remembering is enabled. */
    @XmlElement
    private Double terminalAgentPanelFontSize;

    /** Last saved AI planning tab font size. */
    @XmlElement
    private Double terminalAgentPlanFontSize;

    /** When true, inline terminal-agent panels start collapsed and stay collapsed unless manually expanded. */
    @XmlElement
    private boolean terminalAgentPanelKeepCollapsed = false;

    /** Where the AI-agent activity panel is shown: BOTTOM (default), LEFT or RIGHT (docked side panel). */
    @XmlElement
    private String aiAgentPanelPlacement = "BOTTOM";

    /** Persisted width of the docked AI-agent side panel (null = default). */
    @XmlElement
    private Double aiAgentPanelSideWidth;

    /** Where the live session-journal panel is docked: HIDDEN (default), LEFT or RIGHT. */
    @XmlElement
    private String journalLivePanelPlacement = "HIDDEN";

    /** Persisted width of the docked live session-journal panel (null = default). */
    @XmlElement
    private Double journalLivePanelWidth;

    /** Where the file-browser sidebar is docked: HIDDEN (default), LEFT or RIGHT. */
    @XmlElement
    private String fileBrowserPosition = "HIDDEN";

    /** Persisted width of the docked file-browser sidebar (null = default). */
    @XmlElement
    private Double fileBrowserWidth;

    /** Whether the file browser shows dotfiles/hidden entries. */
    @XmlElement
    private boolean fileBrowserShowHidden = false;

    /** Last directory the file-browser tree was rooted at (null/blank = home). */
    @XmlElement
    private String fileBrowserLastRoot;

    /** Persisted state of the inline terminal-agent "Expand all" activity detail option. */
    @XmlElement
    private boolean terminalAgentPanelExpandAll = false;

    /** Show optional additional instruction text area for snippet-editor AI actions. */
    @XmlElement
    private boolean aiSnippetEditorAdditionalInstructionsEnabled = false;

    /** Maximum number of alternative snippet solutions generated per request. */
    @XmlElement
    private Integer aiSnippetAlternativeSolutionCount = 3;
    
    // Default terminal settings for new connections
    @XmlElement
    private ConnectionSettings defaultTerminalSettings;
    
    // Last terminal settings used in QuickConnect dialog
    @XmlElement
    private ConnectionSettings lastQuickConnectTerminalSettings;

    // Last terminal effect animation speed used in QuickConnect dialog. Null = default.
    @XmlElement
    private Double lastQuickConnectTerminalEffectAnimationSpeed;

    // Master switch for terminal effect plugin functionality.
    @XmlElement
    private boolean terminalEffectsEnabled = true;
    
    // Last connection timeout and retries used in QuickConnect dialog
    @XmlElement
    private Integer lastQuickConnectTimeout;
    
    @XmlElement
    private Integer lastQuickConnectRetries;
    
    // Enable/disable connection retries globally
    @XmlElement
    private boolean connectionRetriesEnabled = true;
    
    // History of access reasons for CyberArk (max 5 entries)
    @XmlElementWrapper(name = "accessReasonHistory")
    @XmlElement(name = "reason")
    private java.util.List<String> accessReasonHistory;

    /** When true, SSH host-key verification relaxes to accept-new for every connection that inherits
     * (i.e. does not set its own or its group's override). Insecure; off by default. */
    @XmlElement
    private boolean hostKeyCheckDisabledForAllConnections = false;

    /** Names of connection groups whose SSH host-key verification is relaxed to accept-new. */
    @XmlElementWrapper(name = "hostKeyCheckDisabledGroups")
    @XmlElement(name = "group")
    private java.util.List<String> hostKeyCheckDisabledGroups;

    @XmlElementWrapper(name = "aiPromptHistory")
    @XmlElement(name = "prompt")
    private java.util.List<String> aiPromptHistory;

    /** Recent questions asked in the guide's AI docs search (max 10, newest first). */
    @XmlElementWrapper(name = "guideAskHistory")
    @XmlElement(name = "question")
    private java.util.List<String> guideAskHistory;

    /** Recent extra instructions from the workflow-script generator (max 10, newest first). */
    @XmlElementWrapper(name = "workflowInstructionsHistory")
    @XmlElement(name = "entry")
    private java.util.List<String> workflowInstructionsHistory;

    /** Recent terminal AI-agent prompts, offered via TAB after the agent command + space. */
    @XmlElementWrapper(name = "terminalAgentInputHistory")
    @XmlElement(name = "input")
    private java.util.List<TerminalAgentInputHistoryEntry> terminalAgentInputHistory;

    /** How many terminal agent prompts to remember (configurable in AI settings). */
    @XmlElement
    private Integer terminalAgentInputHistorySize = 20;

    /** User-adjusted size of the terminal agent TAB history popup (persisted across restarts). */
    @XmlElement
    private Integer terminalAgentHistoryPopupWidth;
    @XmlElement
    private Integer terminalAgentHistoryPopupHeight;
    
    // SFTP Manager settings
    @XmlElement
    private Integer sftpAutoCloseMinutes; // Auto-close SFTP tab after N minutes (null/0 = disabled)
    
    @XmlElement
    private String sftpDefaultZipPath = "/tmp"; // Default path for remote ZIP creation
    
    @XmlElement
    private Integer sftpDefaultZipCompression = 6; // Default compression level (0-9)
    
    // Editor defaults (FileEditor)
    @XmlElement
    private String editorForegroundColor = "#000000"; // black text
    
    @XmlElement
    private String editorBackgroundColor = "#FFFFFF"; // white background
    
    @XmlElement
    private String editorCursorStyle = "BLOCK"; // BLOCK, LINE, UNDERSCORE
    
    @XmlElement
    private String editorCursorColor = "#FF0000"; // red cursor for visibility
    
    // Snippet Editor dedicated settings
    @XmlElement
    private String snippetFontFamily; // null = use terminal default
    
    @XmlElement
    private Integer snippetFontSize; // null = use terminal default
    
    @XmlElement
    private String snippetForegroundColor; // null = use terminal default
    
    @XmlElement
    private String snippetBackgroundColor; // null = use terminal default
    
    @XmlElement
    private String snippetCursorStyle; // null = use editor default
    
    @XmlElement
    private String snippetCursorColor; // null = use editor default

    @XmlElement
    private String snippetDiagramBackgroundColor = "#FFFFFF";

    /** Diagram light/dark appearance: "auto" (follow OS), "light" or "dark". */
    @XmlElement
    private String snippetDiagramColorMode = "auto";

    /** Persisted hardening-option selection (comma-separated enum names); null = never saved → use defaults. */
    @XmlElement
    private String snippetHardeningOptions;

    /** Whether the per-run "Input hardening" master toggle starts ticked. Null/absent = off (strictly opt-in). */
    @XmlElement
    private Boolean snippetInputHardeningEnabled;

    /** Persisted input-hardening sub-option selection (comma-separated enum names); null = never saved → use defaults. */
    @XmlElement
    private String snippetInputHardeningOptions;

    /** Default for the generated MAX_FILE_SIZE variable, in MB. Null/absent = 10; 0 = unlimited (clamped 0..1024). */
    @XmlElement
    private Integer snippetInputHardeningMaxFileSizeMb;

    @XmlElement
    private String selectedSnippetEditorProfileId; // null = use explicit snippet editor colors

    @XmlElementWrapper(name = "snippetEditorProfiles")
    @XmlElement(name = "profile")
    private java.util.List<SnippetEditorProfile> snippetEditorProfiles = new java.util.ArrayList<>();
    
    @XmlElement
    private boolean snippetWordWrap = false; // Word wrap in snippet preview & editor (default: off)
    
    @XmlElement
    private boolean snippetLineNumbers = false; // line-number gutter in snippet editor & manager preview (default: off)

    @XmlElement
    private Double snippetManagerPreviewDividerPosition; // Vertical table/preview divider position

    @XmlElement
    private Integer snippetHistoryMaxSize = 30; // Max number of history entries per snippet (default: 30, max: 99)

    // Snippet dialog geometries
    @XmlElement
    private WindowGeometry snippetManagerGeometry;
    
    @XmlElement
    private WindowGeometry snippetEditGeometry;
    
    /** Last window geometry of the ASCII Art Banner dialog. */
    @XmlElement
    private WindowGeometry asciiArtDialogGeometry;

    /** Last preview zoom level of the ASCII Art dialog, in px. */
    @XmlElement
    private double asciiArtPreviewFontSize = 12.0;

    /** Last window geometry of the alternative snippet solutions dialog. */
    @XmlElement
    private WindowGeometry alternativeSnippetSolutionsDialogGeometry;

    /** Last window geometry of the snippet "AI code analysis" dialog. */
    @XmlElement
    private WindowGeometry snippetCodeAnalysisDialogGeometry;

    /** Last window geometry of the AI change-review (diff) window. */
    @XmlElement
    private WindowGeometry aiDiffDialogGeometry;

    /**
     * Divider between the summary and the diff in the AI change-review window, stored only once the
     * reviewer moved it themselves. While it is unset, the divider follows the summary's own height.
     */
    @XmlElement
    private Double aiDiffDialogSummaryDividerPosition;

    /** Last window geometry of the Generate Workflow Script dialog. */
    @XmlElement
    private WindowGeometry workflowScriptDialogGeometry;

    /** Last window geometry of the snippet editor's "Custom AI improvement" instruction dialog. */
    @XmlElement
    private WindowGeometry customAiImprovementDialogGeometry;

    /** Per-language default "Script-Header" snippet for the Generate Workflow Script dialog. */
    @XmlElementWrapper(name = "workflowHeaderDefaults")
    @XmlElement(name = "headerDefault")
    private java.util.List<WorkflowHeaderDefault> workflowHeaderDefaults = new java.util.ArrayList<>();

    /** Last window geometry of the JobScheduler dialog. */
    @XmlElement
    private WindowGeometry jobSchedulerDialogGeometry;

    /** Last window geometry of the in-app guide viewer ("Anleitung"). */
    @XmlElement
    private WindowGeometry guideViewerGeometry;

    /** Last window geometry of the AI manager dialog ("AI-Manager"). */
    @XmlElement
    private WindowGeometry aiManagerDialogGeometry;

    /** Last window geometry of the saved chats dialog (saved AI and swarm chats). */
    @XmlElement
    private WindowGeometry savedChatsDialogGeometry;

    /** Last window geometry of the session journal manager dialog. */
    @XmlElement
    private WindowGeometry sessionJournalManagerGeometry;

    /** Last window geometry of the session journal viewer dialog. */
    @XmlElement
    private WindowGeometry sessionJournalViewerGeometry;

    @XmlElement
    private WindowGeometry sessionJournalScreenshotEditorGeometry;

    /** Persisted divider position of the session journal viewer's edit split pane. */
    @XmlElement
    private Double sessionJournalViewerEditDividerPosition;

    // Teamwork: shared connection sources (Git or shared file)
    @XmlElementWrapper(name = "teamworkSources")
    @XmlElement(name = "source")
    private java.util.List<TeamworkSourceConfig> teamworkSources;
    
    /** Default check interval in minutes for teamwork source updates (used when adding new source). */
    @XmlElement
    private int teamworkDefaultCheckIntervalMinutes = 15;
    
    /** Default credential for all teamwork connections (ID from credential management). Passwords must not be in teamwork file. */
    @XmlElement
    private String teamworkDefaultCredentialId;
    
    /** Default SSH key for all teamwork connections (ID from SSH key management). Keys must not be in teamwork file. */
    @XmlElement
    private String teamworkDefaultSshKeyId;
    
    /** Default username when using default SSH key for teamwork (optional; otherwise use connection's username from file). */
    @XmlElement
    private String teamworkDefaultUsername;
    
    /** When true, use temporary SSH key flow for teamwork connections that have no credential/key. */
    @XmlElement
    private boolean teamworkUseTemporaryKey;
    
    @XmlEnum
    public enum BackupEncryptionType {
        @XmlEnumValue("PASSWORD") PASSWORD,
        @XmlEnumValue("GPG") GPG
    }
    
    public GlobalSettings() {}

    public String getLogDirectoryPath() {
        return logDirectoryPath;
    }

    public void setLogDirectoryPath(String logDirectoryPath) {
        String normalized = logDirectoryPath != null ? logDirectoryPath.trim() : "";
        this.logDirectoryPath = normalized.isBlank() ? null : normalized;
    }

    public int getLogRetentionDays() {
        if (logRetentionDays == null) {
            return DEFAULT_LOG_RETENTION_DAYS;
        }
        return Math.max(0, Math.min(logRetentionDays, MAX_LOG_RETENTION_DAYS));
    }

    public void setLogRetentionDays(Integer logRetentionDays) {
        if (logRetentionDays == null) {
            this.logRetentionDays = DEFAULT_LOG_RETENTION_DAYS;
        } else {
            this.logRetentionDays = Math.max(0, Math.min(logRetentionDays, MAX_LOG_RETENTION_DAYS));
        }
    }

    public boolean isUpdateChecksEnabled() {
        return updateChecksEnabled;
    }

    public void setUpdateChecksEnabled(boolean updateChecksEnabled) {
        this.updateChecksEnabled = updateChecksEnabled;
    }

    public boolean isTelemetryEnabled() {
        return telemetryEnabled;
    }

    public void setTelemetryEnabled(boolean telemetryEnabled) {
        this.telemetryEnabled = telemetryEnabled;
    }

    public int getTelemetryConsentVersion() {
        return telemetryConsentVersion != null ? Math.max(0, telemetryConsentVersion) : 0;
    }

    public void setTelemetryConsentVersion(Integer telemetryConsentVersion) {
        this.telemetryConsentVersion = telemetryConsentVersion != null ? Math.max(0, telemetryConsentVersion) : 0;
    }

    public String getTelemetryConsentDate() {
        return telemetryConsentDate;
    }

    public void setTelemetryConsentDate(String telemetryConsentDate) {
        this.telemetryConsentDate = telemetryConsentDate != null && !telemetryConsentDate.isBlank()
            ? telemetryConsentDate
            : null;
    }

    public int getUpdateCheckIntervalDays() {
        if (updateCheckIntervalDays == null) {
            return DEFAULT_UPDATE_CHECK_INTERVAL_DAYS;
        }
        return Math.max(
            MIN_UPDATE_CHECK_INTERVAL_DAYS,
            Math.min(updateCheckIntervalDays, MAX_UPDATE_CHECK_INTERVAL_DAYS)
        );
    }

    public void setUpdateCheckIntervalDays(Integer updateCheckIntervalDays) {
        if (updateCheckIntervalDays == null) {
            this.updateCheckIntervalDays = DEFAULT_UPDATE_CHECK_INTERVAL_DAYS;
            return;
        }
        this.updateCheckIntervalDays = Math.max(
            MIN_UPDATE_CHECK_INTERVAL_DAYS,
            Math.min(updateCheckIntervalDays, MAX_UPDATE_CHECK_INTERVAL_DAYS)
        );
    }

    public long getLastSuccessfulUpdateCheckMillis() {
        return lastSuccessfulUpdateCheckMillis != null ? Math.max(0L, lastSuccessfulUpdateCheckMillis) : 0L;
    }

    public void setLastSuccessfulUpdateCheckMillis(Long lastSuccessfulUpdateCheckMillis) {
        this.lastSuccessfulUpdateCheckMillis = lastSuccessfulUpdateCheckMillis != null
            ? Math.max(0L, lastSuccessfulUpdateCheckMillis)
            : null;
    }

    public String getIgnoredUpdateVersion() {
        return ignoredUpdateVersion;
    }

    public void setIgnoredUpdateVersion(String ignoredUpdateVersion) {
        this.ignoredUpdateVersion = trimToNull(ignoredUpdateVersion);
    }

    public String getSnoozedUpdateVersion() {
        return snoozedUpdateVersion;
    }

    public void setSnoozedUpdateVersion(String snoozedUpdateVersion) {
        this.snoozedUpdateVersion = trimToNull(snoozedUpdateVersion);
    }

    public String getUpdateSnoozedUntilLocalDate() {
        return updateSnoozedUntilLocalDate;
    }

    public void setUpdateSnoozedUntilLocalDate(String updateSnoozedUntilLocalDate) {
        this.updateSnoozedUntilLocalDate = trimToNull(updateSnoozedUntilLocalDate);
    }

    public String getLastAutomaticUpdatePromptVersion() {
        return lastAutomaticUpdatePromptVersion;
    }

    public void setLastAutomaticUpdatePromptVersion(String lastAutomaticUpdatePromptVersion) {
        this.lastAutomaticUpdatePromptVersion = trimToNull(lastAutomaticUpdatePromptVersion);
    }

    public String getLastAutomaticUpdatePromptLocalDate() {
        return lastAutomaticUpdatePromptLocalDate;
    }

    public void setLastAutomaticUpdatePromptLocalDate(String lastAutomaticUpdatePromptLocalDate) {
        this.lastAutomaticUpdatePromptLocalDate = trimToNull(lastAutomaticUpdatePromptLocalDate);
    }
    
    public int getMaxBackupCount() {
        return maxBackupCount;
    }
    
    public void setMaxBackupCount(int maxBackupCount) {
        this.maxBackupCount = maxBackupCount;
    }
    
    public String getLastBackupPath() {
        return lastBackupPath;
    }
    
    public void setLastBackupPath(String lastBackupPath) {
        this.lastBackupPath = lastBackupPath;
    }
    
    public long getLastBackupTime() {
        return lastBackupTime;
    }
    
    public void setLastBackupTime(long lastBackupTime) {
        this.lastBackupTime = lastBackupTime;
    }
    
    public BackupEncryptionType getBackupEncryptionType() {
        return backupEncryptionType;
    }
    
    public void setBackupEncryptionType(BackupEncryptionType backupEncryptionType) {
        this.backupEncryptionType = backupEncryptionType;
    }
    
    public String getBackupCredentialId() {
        return backupCredentialId;
    }
    
    public void setBackupCredentialId(String backupCredentialId) {
        this.backupCredentialId = backupCredentialId;
    }
    
    public String getBackupGpgKeyId() {
        return backupGpgKeyId;
    }
    
    public void setBackupGpgKeyId(String backupGpgKeyId) {
        this.backupGpgKeyId = backupGpgKeyId;
    }
    
    public boolean isRememberWindowGeometry() {
        return rememberWindowGeometry;
    }

    public void setRememberWindowGeometry(boolean rememberWindowGeometry) {
        this.rememberWindowGeometry = rememberWindowGeometry;
    }

    /** Terminal background transparency in percent (0 = opaque, 100 = fully transparent). */
    public int getTerminalBackgroundTransparency() {
        return terminalBackgroundTransparency;
    }

    public void setTerminalBackgroundTransparency(int terminalBackgroundTransparency) {
        this.terminalBackgroundTransparency = Math.max(0, Math.min(100, terminalBackgroundTransparency));
    }
    
    public boolean isUseFixedWindowGeometry() {
        return useFixedWindowGeometry;
    }
    
    public void setUseFixedWindowGeometry(boolean useFixedWindowGeometry) {
        this.useFixedWindowGeometry = useFixedWindowGeometry;
    }
    
    public WindowGeometry getFixedWindowGeometry() {
        return fixedWindowGeometry;
    }
    
    public void setFixedWindowGeometry(WindowGeometry fixedWindowGeometry) {
        this.fixedWindowGeometry = fixedWindowGeometry;
    }
    
    public WindowGeometry getLastWindowGeometry() {
        return lastWindowGeometry;
    }
    
    public void setLastWindowGeometry(WindowGeometry lastWindowGeometry) {
        this.lastWindowGeometry = lastWindowGeometry;
    }
    
    public boolean isRememberDashboardState() {
        return rememberDashboardState;
    }
    
    public void setRememberDashboardState(boolean rememberDashboardState) {
        this.rememberDashboardState = rememberDashboardState;
    }
    
    public boolean isDashboardVisible() {
        return dashboardVisible;
    }
    
    public void setDashboardVisible(boolean dashboardVisible) {
        this.dashboardVisible = dashboardVisible;
    }

    public boolean isShowMenuBar() {
        return showMenuBar;
    }

    public void setShowMenuBar(boolean showMenuBar) {
        this.showMenuBar = showMenuBar;
    }

    public boolean isOpenToolWindowsAsTabs() {
        return openToolWindowsAsTabs;
    }

    public void setOpenToolWindowsAsTabs(boolean openToolWindowsAsTabs) {
        this.openToolWindowsAsTabs = openToolWindowsAsTabs;
    }

    public boolean isJobSchedulerMenuStatusEnabled() {
        return jobSchedulerMenuStatusEnabled;
    }

    public void setJobSchedulerMenuStatusEnabled(boolean jobSchedulerMenuStatusEnabled) {
        this.jobSchedulerMenuStatusEnabled = jobSchedulerMenuStatusEnabled;
    }

    public boolean isPreventSystemSleep() {
        return preventSystemSleep;
    }

    public void setPreventSystemSleep(boolean preventSystemSleep) {
        this.preventSystemSleep = preventSystemSleep;
    }

    public int getJobSchedulerJournalRetentionDays() {
        if (jobSchedulerJournalRetentionDays == null) {
            return DEFAULT_JOB_SCHEDULER_JOURNAL_RETENTION_DAYS;
        }
        return Math.max(
            0,
            Math.min(jobSchedulerJournalRetentionDays, MAX_JOB_SCHEDULER_JOURNAL_RETENTION_DAYS)
        );
    }

    public void setJobSchedulerJournalRetentionDays(Integer jobSchedulerJournalRetentionDays) {
        if (jobSchedulerJournalRetentionDays == null) {
            this.jobSchedulerJournalRetentionDays = DEFAULT_JOB_SCHEDULER_JOURNAL_RETENTION_DAYS;
        } else {
            this.jobSchedulerJournalRetentionDays = Math.max(
                0,
                Math.min(jobSchedulerJournalRetentionDays, MAX_JOB_SCHEDULER_JOURNAL_RETENTION_DAYS)
            );
        }
    }

    public double getJobSchedulerJournalDetailDividerPosition() {
        if (jobSchedulerJournalDetailDividerPosition == null
            || jobSchedulerJournalDetailDividerPosition <= 0.0
            || jobSchedulerJournalDetailDividerPosition >= 1.0) {
            return 0.72;
        }
        return jobSchedulerJournalDetailDividerPosition;
    }

    public void setJobSchedulerJournalDetailDividerPosition(Double jobSchedulerJournalDetailDividerPosition) {
        if (jobSchedulerJournalDetailDividerPosition == null) {
            this.jobSchedulerJournalDetailDividerPosition = null;
            return;
        }
        this.jobSchedulerJournalDetailDividerPosition = Math.max(0.2, Math.min(0.9, jobSchedulerJournalDetailDividerPosition));
    }

    public String getJobSchedulerRsyncBinaryPath() {
        return jobSchedulerRsyncBinaryPath;
    }

    public void setJobSchedulerRsyncBinaryPath(String jobSchedulerRsyncBinaryPath) {
        String trimmed = jobSchedulerRsyncBinaryPath != null ? jobSchedulerRsyncBinaryPath.trim() : "";
        this.jobSchedulerRsyncBinaryPath = trimmed.isEmpty() ? null : trimmed;
    }
    
    public double getDashboardDividerPosition() {
        return dashboardDividerPosition;
    }
    
    public void setDashboardDividerPosition(double dashboardDividerPosition) {
        this.dashboardDividerPosition = dashboardDividerPosition;
    }
    
    public boolean isShowTerminalScrollbar() {
        return showTerminalScrollbar;
    }
    
    public void setShowTerminalScrollbar(boolean showTerminalScrollbar) {
        this.showTerminalScrollbar = showTerminalScrollbar;
    }

    public boolean isHideTerminalScrollbarsInFullscreen() {
        return hideTerminalScrollbarsInFullscreen;
    }

    public void setHideTerminalScrollbarsInFullscreen(boolean hideTerminalScrollbarsInFullscreen) {
        this.hideTerminalScrollbarsInFullscreen = hideTerminalScrollbarsInFullscreen;
    }
    
    public boolean isCommandTimestampsEnabled() {
        return commandTimestampsEnabled;
    }
    
    public void setCommandTimestampsEnabled(boolean commandTimestampsEnabled) {
        this.commandTimestampsEnabled = commandTimestampsEnabled;
    }

    public boolean isTerminalRecordingEnabled() {
        return terminalRecordingEnabled;
    }

    public void setTerminalRecordingEnabled(boolean terminalRecordingEnabled) {
        this.terminalRecordingEnabled = terminalRecordingEnabled;
    }

    public String getTerminalRecordingStoragePath() {
        return terminalRecordingStoragePath;
    }

    public void setTerminalRecordingStoragePath(String terminalRecordingStoragePath) {
        String trimmed = terminalRecordingStoragePath != null ? terminalRecordingStoragePath.trim() : "";
        this.terminalRecordingStoragePath = trimmed.isEmpty() ? null : trimmed;
    }

    public TerminalRecordingFormat getTerminalRecordingFormat() {
        return terminalRecordingFormat != null
            ? terminalRecordingFormat
            : TerminalRecordingFormat.KORTTY_REPLAY;
    }

    public void setTerminalRecordingFormat(TerminalRecordingFormat terminalRecordingFormat) {
        this.terminalRecordingFormat = terminalRecordingFormat != null
            ? terminalRecordingFormat
            : TerminalRecordingFormat.KORTTY_REPLAY;
    }

    public TerminalRecordingScope getTerminalRecordingDefaultScope() {
        return terminalRecordingDefaultScope != null
            ? terminalRecordingDefaultScope
            : TerminalRecordingScope.ACTIVE_SPLIT;
    }

    public void setTerminalRecordingDefaultScope(TerminalRecordingScope terminalRecordingDefaultScope) {
        this.terminalRecordingDefaultScope = terminalRecordingDefaultScope != null
            ? terminalRecordingDefaultScope
            : TerminalRecordingScope.ACTIVE_SPLIT;
    }

    public boolean isTerminalRecordingAutoPauseEnabled() {
        return terminalRecordingAutoPauseEnabled;
    }

    public void setTerminalRecordingAutoPauseEnabled(boolean terminalRecordingAutoPauseEnabled) {
        this.terminalRecordingAutoPauseEnabled = terminalRecordingAutoPauseEnabled;
    }

    public int getTerminalRecordingIdlePauseSeconds() {
        if (terminalRecordingIdlePauseSeconds == null) {
            return 20;
        }
        return Math.max(1, Math.min(terminalRecordingIdlePauseSeconds, 3600));
    }

    public void setTerminalRecordingIdlePauseSeconds(Integer terminalRecordingIdlePauseSeconds) {
        if (terminalRecordingIdlePauseSeconds == null) {
            this.terminalRecordingIdlePauseSeconds = 20;
        } else {
            this.terminalRecordingIdlePauseSeconds = Math.max(1, Math.min(terminalRecordingIdlePauseSeconds, 3600));
        }
    }

    public String getTerminalRecordingFfmpegPath() {
        return terminalRecordingFfmpegPath;
    }

    public void setTerminalRecordingFfmpegPath(String terminalRecordingFfmpegPath) {
        String trimmed = terminalRecordingFfmpegPath != null ? terminalRecordingFfmpegPath.trim() : "";
        this.terminalRecordingFfmpegPath = trimmed.isEmpty() ? null : trimmed;
    }

    public boolean isTerminalRecordingCaptureColorsEnabled() {
        return terminalRecordingCaptureColorsEnabled;
    }

    public void setTerminalRecordingCaptureColorsEnabled(boolean terminalRecordingCaptureColorsEnabled) {
        this.terminalRecordingCaptureColorsEnabled = terminalRecordingCaptureColorsEnabled;
    }

    public String getSessionJournalStoragePath() {
        return sessionJournalStoragePath;
    }

    public void setSessionJournalStoragePath(String sessionJournalStoragePath) {
        String trimmed = sessionJournalStoragePath != null ? sessionJournalStoragePath.trim() : "";
        this.sessionJournalStoragePath = trimmed.isEmpty() ? null : trimmed;
    }

    public String getSessionJournalAiProfileId() {
        return sessionJournalAiProfileId;
    }

    public void setSessionJournalAiProfileId(String sessionJournalAiProfileId) {
        String trimmed = sessionJournalAiProfileId != null ? sessionJournalAiProfileId.trim() : "";
        this.sessionJournalAiProfileId = trimmed.isEmpty() ? null : trimmed;
    }

    public boolean isSessionJournalAiSummariesEnabled() {
        return sessionJournalAiSummariesEnabled;
    }

    public void setSessionJournalAiSummariesEnabled(boolean sessionJournalAiSummariesEnabled) {
        this.sessionJournalAiSummariesEnabled = sessionJournalAiSummariesEnabled;
    }

    public int getSessionJournalSummarizeIntervalMinutes() {
        if (sessionJournalSummarizeIntervalMinutes == null) {
            return 5;
        }
        return Math.max(1, Math.min(sessionJournalSummarizeIntervalMinutes, 240));
    }

    public void setSessionJournalSummarizeIntervalMinutes(Integer sessionJournalSummarizeIntervalMinutes) {
        if (sessionJournalSummarizeIntervalMinutes == null) {
            this.sessionJournalSummarizeIntervalMinutes = 5;
        } else {
            this.sessionJournalSummarizeIntervalMinutes = Math.max(1, Math.min(sessionJournalSummarizeIntervalMinutes, 240));
        }
    }

    public SessionJournalLogFormat getSessionJournalLogFormat() {
        return sessionJournalLogFormat != null ? sessionJournalLogFormat : SessionJournalLogFormat.DEFAULT;
    }

    public void setSessionJournalLogFormat(SessionJournalLogFormat sessionJournalLogFormat) {
        this.sessionJournalLogFormat = sessionJournalLogFormat != null ? sessionJournalLogFormat : SessionJournalLogFormat.DEFAULT;
    }

    /** Max terminal lines per AI evaluation window; 0 = fill the model context using the token budget. */
    public int getSessionJournalAiMaxLines() {
        if (sessionJournalAiMaxLines == null) {
            return 100;
        }
        return Math.max(0, sessionJournalAiMaxLines);
    }

    public void setSessionJournalAiMaxLines(Integer sessionJournalAiMaxLines) {
        this.sessionJournalAiMaxLines = sessionJournalAiMaxLines == null ? 100 : Math.max(0, sessionJournalAiMaxLines);
    }

    public int getSessionJournalAiTokenBudget() {
        if (sessionJournalAiTokenBudget == null) {
            return 130_000;
        }
        return Math.max(1_000, sessionJournalAiTokenBudget);
    }

    public void setSessionJournalAiTokenBudget(Integer sessionJournalAiTokenBudget) {
        this.sessionJournalAiTokenBudget = sessionJournalAiTokenBudget == null
            ? 130_000
            : Math.max(1_000, sessionJournalAiTokenBudget);
    }

    public boolean isSessionJournalAiChunkingEnabled() {
        return sessionJournalAiChunkingEnabled;
    }

    public void setSessionJournalAiChunkingEnabled(boolean sessionJournalAiChunkingEnabled) {
        this.sessionJournalAiChunkingEnabled = sessionJournalAiChunkingEnabled;
    }

    public boolean isSessionJournalAiTitleEnabled() {
        return sessionJournalAiTitleEnabled;
    }

    public void setSessionJournalAiTitleEnabled(boolean sessionJournalAiTitleEnabled) {
        this.sessionJournalAiTitleEnabled = sessionJournalAiTitleEnabled;
    }

    /** Font size of the generated journal page in percent; the page's A-/A+ buttons write it back. */
    public int getSessionJournalFontScalePercent() {
        if (sessionJournalFontScalePercent == null) {
            return 100;
        }
        return Math.max(70, Math.min(sessionJournalFontScalePercent, 250));
    }

    public void setSessionJournalFontScalePercent(Integer sessionJournalFontScalePercent) {
        this.sessionJournalFontScalePercent = sessionJournalFontScalePercent == null
            ? 100
            : Math.max(70, Math.min(sessionJournalFontScalePercent, 250));
    }

    /** Live-log tail height in vh (15–85), or null for the page's CSS default. */
    public Integer getSessionJournalLiveTailHeightVh() {
        return sessionJournalLiveTailHeightVh == null
            ? null
            : Math.max(15, Math.min(sessionJournalLiveTailHeightVh, 85));
    }

    public void setSessionJournalLiveTailHeightVh(Integer sessionJournalLiveTailHeightVh) {
        this.sessionJournalLiveTailHeightVh = sessionJournalLiveTailHeightVh == null
            ? null
            : Math.max(15, Math.min(sessionJournalLiveTailHeightVh, 85));
    }

    /** User-defined markers only; {@code SessionJournalMarkers.registry} adds the built-ins. */
    public java.util.List<SessionJournalMarkerDefinition> getSessionJournalMarkers() {
        if (sessionJournalMarkers == null) {
            sessionJournalMarkers = new java.util.ArrayList<>();
        }
        return sessionJournalMarkers;
    }

    public void setSessionJournalMarkers(java.util.List<SessionJournalMarkerDefinition> markers) {
        this.sessionJournalMarkers = markers != null
            ? new java.util.ArrayList<>(markers) : new java.util.ArrayList<>();
    }

    /** Auto-marker rules in priority order; the first enabled match wins. */
    public java.util.List<SessionJournalMarkerRule> getSessionJournalMarkerRules() {
        if (sessionJournalMarkerRules == null) {
            sessionJournalMarkerRules = new java.util.ArrayList<>();
        }
        return sessionJournalMarkerRules;
    }

    public void setSessionJournalMarkerRules(java.util.List<SessionJournalMarkerRule> rules) {
        this.sessionJournalMarkerRules = rules != null
            ? new java.util.ArrayList<>(rules) : new java.util.ArrayList<>();
    }

    public boolean isSessionJournalMarkerRulesEnabled() {
        return sessionJournalMarkerRulesEnabled;
    }

    public void setSessionJournalMarkerRulesEnabled(boolean sessionJournalMarkerRulesEnabled) {
        this.sessionJournalMarkerRulesEnabled = sessionJournalMarkerRulesEnabled;
    }

    /** Colour scheme of the generated journal page; "auto" follows the operating system. */
    public String getSessionJournalPageSchemeId() {
        return sessionJournalPageSchemeId != null ? sessionJournalPageSchemeId : "auto";
    }

    public void setSessionJournalPageSchemeId(String sessionJournalPageSchemeId) {
        String trimmed = sessionJournalPageSchemeId != null ? sessionJournalPageSchemeId.trim() : "";
        this.sessionJournalPageSchemeId = trimmed.isEmpty() ? null : trimmed;
    }

    public String getSessionJournalPageUiFont() {
        return sessionJournalPageUiFont;
    }

    public void setSessionJournalPageUiFont(String sessionJournalPageUiFont) {
        String trimmed = sessionJournalPageUiFont != null ? sessionJournalPageUiFont.trim() : "";
        this.sessionJournalPageUiFont = trimmed.isEmpty() ? null : trimmed;
    }

    /** The journal page's light/dark choice; "auto" follows the operating system. */
    public String getSessionJournalPageTheme() {
        return sessionJournalPageTheme != null ? sessionJournalPageTheme : "auto";
    }

    public void setSessionJournalPageTheme(String sessionJournalPageTheme) {
        String trimmed = sessionJournalPageTheme != null ? sessionJournalPageTheme.trim() : "";
        this.sessionJournalPageTheme = trimmed.isEmpty() ? null : trimmed;
    }

    public String getSessionJournalPageMonoFont() {
        return sessionJournalPageMonoFont;
    }

    public void setSessionJournalPageMonoFont(String sessionJournalPageMonoFont) {
        String trimmed = sessionJournalPageMonoFont != null ? sessionJournalPageMonoFont.trim() : "";
        this.sessionJournalPageMonoFont = trimmed.isEmpty() ? null : trimmed;
    }

    public boolean isPdfWatermarkEnabled() {
        return pdfWatermarkEnabled;
    }

    public void setPdfWatermarkEnabled(boolean pdfWatermarkEnabled) {
        this.pdfWatermarkEnabled = pdfWatermarkEnabled;
    }

    /** Custom watermark text; null means the built-in korTTY watermark. */
    public String getPdfWatermarkText() {
        return pdfWatermarkText;
    }

    public void setPdfWatermarkText(String pdfWatermarkText) {
        String trimmed = pdfWatermarkText != null ? pdfWatermarkText.trim() : "";
        this.pdfWatermarkText = trimmed.isEmpty() ? null : trimmed;
    }

    /** Watermark colour as {@code #rrggbb}; null means the default grey. */
    public String getPdfWatermarkColor() {
        return pdfWatermarkColor;
    }

    public void setPdfWatermarkColor(String pdfWatermarkColor) {
        String trimmed = pdfWatermarkColor != null ? pdfWatermarkColor.trim() : "";
        this.pdfWatermarkColor = trimmed.isEmpty() ? null : trimmed;
    }

    public boolean isExportFooterEnabled() {
        return exportFooterEnabled;
    }

    public void setExportFooterEnabled(boolean exportFooterEnabled) {
        this.exportFooterEnabled = exportFooterEnabled;
    }

    /** Custom footer text; null means the built-in brand line plus the repository link. */
    public String getExportFooterText() {
        return exportFooterText;
    }

    public void setExportFooterText(String exportFooterText) {
        String trimmed = exportFooterText != null ? exportFooterText.trim() : "";
        this.exportFooterText = trimmed.isEmpty() ? null : trimmed;
    }

    public boolean isTerminalDragDropEnabled() {
        return terminalDragDropEnabled;
    }

    public void setTerminalDragDropEnabled(boolean terminalDragDropEnabled) {
        this.terminalDragDropEnabled = terminalDragDropEnabled;
    }

    public boolean isTerminalCopyOnSelectEnabled() {
        return terminalCopyOnSelectEnabled;
    }

    public void setTerminalCopyOnSelectEnabled(boolean terminalCopyOnSelectEnabled) {
        this.terminalCopyOnSelectEnabled = terminalCopyOnSelectEnabled;
    }

    public boolean isCloseActiveTerminalWindowsWithoutConfirmation() {
        return closeActiveTerminalWindowsWithoutConfirmation;
    }

    public void setCloseActiveTerminalWindowsWithoutConfirmation(boolean closeActiveTerminalWindowsWithoutConfirmation) {
        this.closeActiveTerminalWindowsWithoutConfirmation = closeActiveTerminalWindowsWithoutConfirmation;
    }

    public boolean isApplyThemeFonts() {
        return applyThemeFonts;
    }

    public void setApplyThemeFonts(boolean applyThemeFonts) {
        this.applyThemeFonts = applyThemeFonts;
    }

    public AppDesign getAppDesign() {
        return AppDesign.fromId(appDesign);
    }

    public void setAppDesign(AppDesign appDesign) {
        this.appDesign = (appDesign != null ? appDesign : AppDesign.NORMAL).getId();
    }

    public boolean isAppDesignAnimationsEnabled() {
        return appDesignAnimationsEnabled;
    }

    public void setAppDesignAnimationsEnabled(boolean appDesignAnimationsEnabled) {
        this.appDesignAnimationsEnabled = appDesignAnimationsEnabled;
    }

    /** @return the UI chrome font size in percent, clamped to the supported range (100 = built-in size). */
    public int getUiFontScalePercent() {
        return uiFontScalePercent != null
            ? clampUiFontScalePercent(uiFontScalePercent)
            : UI_FONT_SCALE_DEFAULT_PERCENT;
    }

    public void setUiFontScalePercent(Integer uiFontScalePercent) {
        this.uiFontScalePercent = uiFontScalePercent != null
            ? clampUiFontScalePercent(uiFontScalePercent)
            : UI_FONT_SCALE_DEFAULT_PERCENT;
    }

    /** Clamps a UI font scale to the supported range. */
    public static int clampUiFontScalePercent(int percent) {
        return Math.max(MIN_UI_FONT_SCALE_PERCENT, Math.min(MAX_UI_FONT_SCALE_PERCENT, percent));
    }

    /** @return whether the UI font size follows the display resolution instead of the stored percent. */
    public boolean isUiFontScaleAuto() {
        return uiFontScaleAuto;
    }

    public void setUiFontScaleAuto(boolean uiFontScaleAuto) {
        this.uiFontScaleAuto = uiFontScaleAuto;
    }

    /** @return the manual's text size in percent, clamped to the supported range. */
    public int getGuideFontScalePercent() {
        return guideFontScalePercent != null
            ? clampGuideFontScalePercent(guideFontScalePercent)
            : GUIDE_FONT_SCALE_DEFAULT_PERCENT;
    }

    public void setGuideFontScalePercent(Integer guideFontScalePercent) {
        this.guideFontScalePercent = guideFontScalePercent != null
            ? clampGuideFontScalePercent(guideFontScalePercent)
            : GUIDE_FONT_SCALE_DEFAULT_PERCENT;
    }

    /** Clamps a guide text size to the supported range. */
    public static int clampGuideFontScalePercent(int percent) {
        return Math.max(MIN_GUIDE_FONT_SCALE_PERCENT, Math.min(MAX_GUIDE_FONT_SCALE_PERCENT, percent));
    }

    /** @return the UI font scale stored dialog sizes were measured at, or null if never recorded. */
    public Integer getUiFontScalePercentAtGeometrySave() {
        return uiFontScalePercentAtGeometrySave;
    }

    public void setUiFontScalePercentAtGeometrySave(Integer uiFontScalePercentAtGeometrySave) {
        this.uiFontScalePercentAtGeometrySave = uiFontScalePercentAtGeometrySave;
    }

    public boolean isRequireMasterPasswordOnStartup() {
        return requireMasterPasswordOnStartup;
    }
    
    public void setRequireMasterPasswordOnStartup(boolean requireMasterPasswordOnStartup) {
        this.requireMasterPasswordOnStartup = requireMasterPasswordOnStartup;
    }

    public boolean isSkipMasterPasswordPrompt() {
        return skipMasterPasswordPrompt;
    }

    public void setSkipMasterPasswordPrompt(boolean skipMasterPasswordPrompt) {
        this.skipMasterPasswordPrompt = skipMasterPasswordPrompt;
    }

    public boolean isTemporarySshKeyEnabled() {
        return temporarySshKeyEnabled;
    }
    
    public void setTemporarySshKeyEnabled(boolean temporarySshKeyEnabled) {
        this.temporarySshKeyEnabled = temporarySshKeyEnabled;
    }
    
    public String getLanguage() {
        return language;
    }
    
    public void setLanguage(String language) {
        this.language = language;
    }
    
    public TranslationApiProvider getTranslationApiProvider() {
        return translationApiProvider;
    }
    
    public void setTranslationApiProvider(TranslationApiProvider translationApiProvider) {
        this.translationApiProvider = translationApiProvider;
    }
    
    public String getEncryptedTranslationApiKey() {
        return encryptedTranslationApiKey;
    }
    
    public void setEncryptedTranslationApiKey(String encryptedTranslationApiKey) {
        this.encryptedTranslationApiKey = encryptedTranslationApiKey;
    }
    
    public String getTranslationApiUrl() {
        return translationApiUrl;
    }
    
    public void setTranslationApiUrl(String translationApiUrl) {
        this.translationApiUrl = translationApiUrl;
    }

    public String getAiApiUrl() {
        return aiApiUrl;
    }

    public void setAiApiUrl(String aiApiUrl) {
        this.aiApiUrl = aiApiUrl;
    }

    public String getAiModel() {
        return aiModel;
    }

    public void setAiModel(String aiModel) {
        this.aiModel = aiModel;
    }

    public String getEncryptedAiApiKey() {
        return encryptedAiApiKey;
    }

    public void setEncryptedAiApiKey(String encryptedAiApiKey) {
        this.encryptedAiApiKey = encryptedAiApiKey;
    }

    public java.util.List<AiProfile> getAiProfiles() {
        return aiProfiles;
    }

    public void setAiProfiles(java.util.List<AiProfile> aiProfiles) {
        this.aiProfiles = aiProfiles != null ? aiProfiles : new java.util.ArrayList<>();
        normalizeAiProfiles();
    }

    /**
     * Swaps the AI-profile list reference and returns the previous one, <b>without</b> running
     * {@link #normalizeAiProfiles()}. Used only by the enterprise-policy marshal path
     * ({@code PolicyClamp}) to persist a filtered list — policy-managed profiles excluded — while
     * leaving the live list untouched for concurrent readers, and without letting normalization
     * null out default-profile ids that point at a (temporarily absent) policy-managed profile.
     */
    public java.util.List<AiProfile> exchangeAiProfilesForMarshal(java.util.List<AiProfile> replacement) {
        java.util.List<AiProfile> previous = this.aiProfiles;
        this.aiProfiles = replacement;
        return previous;
    }

    public boolean isAiSkillsEnabled() {
        return aiSkillsEnabled;
    }

    public void setAiSkillsEnabled(boolean aiSkillsEnabled) {
        this.aiSkillsEnabled = aiSkillsEnabled;
    }

    public boolean isAiSkillAutoDetectionEnabled() {
        return aiSkillAutoDetectionEnabled;
    }

    public void setAiSkillAutoDetectionEnabled(boolean aiSkillAutoDetectionEnabled) {
        this.aiSkillAutoDetectionEnabled = aiSkillAutoDetectionEnabled;
    }

    public java.util.List<AiSkill> getAiSkills() {
        if (aiSkills == null) {
            aiSkills = new java.util.ArrayList<>();
        }
        normalizeAiSkills();
        return aiSkills;
    }

    public void setAiSkills(java.util.List<AiSkill> aiSkills) {
        this.aiSkills = aiSkills != null ? aiSkills : new java.util.ArrayList<>();
        normalizeAiSkills();
    }

    /** AI skill ids pre-selected for every new Quick-Connect connection (never null). */
    public java.util.List<String> getDefaultConnectionAiSkillIds() {
        if (defaultConnectionAiSkillIds == null) {
            defaultConnectionAiSkillIds = new java.util.ArrayList<>();
        }
        return defaultConnectionAiSkillIds;
    }

    public void setDefaultConnectionAiSkillIds(java.util.List<String> defaultConnectionAiSkillIds) {
        this.defaultConnectionAiSkillIds = defaultConnectionAiSkillIds != null
            ? new java.util.ArrayList<>(defaultConnectionAiSkillIds)
            : new java.util.ArrayList<>();
    }

    /** Quick-Connect collapsible sections last left expanded, by stable key (never null). */
    public java.util.List<String> getQuickConnectExpandedSections() {
        if (quickConnectExpandedSections == null) {
            quickConnectExpandedSections = new java.util.ArrayList<>();
        }
        return quickConnectExpandedSections;
    }

    public void setQuickConnectExpandedSections(java.util.List<String> quickConnectExpandedSections) {
        this.quickConnectExpandedSections = quickConnectExpandedSections != null
            ? new java.util.ArrayList<>(quickConnectExpandedSections)
            : new java.util.ArrayList<>();
    }

    /** User-added snippet code languages, in the order they were added (never null). */
    public java.util.List<String> getCustomSnippetCodeLanguages() {
        if (customSnippetCodeLanguages == null) {
            customSnippetCodeLanguages = new java.util.ArrayList<>();
        }
        return customSnippetCodeLanguages;
    }

    public void setCustomSnippetCodeLanguages(java.util.List<String> customSnippetCodeLanguages) {
        this.customSnippetCodeLanguages = customSnippetCodeLanguages != null
            ? new java.util.ArrayList<>(customSnippetCodeLanguages)
            : new java.util.ArrayList<>();
    }

    public String getDefaultAiProfileId() {
        return defaultAiProfileId;
    }

    public void setDefaultAiProfileId(String defaultAiProfileId) {
        this.defaultAiProfileId = defaultAiProfileId != null && !defaultAiProfileId.isBlank()
            ? defaultAiProfileId.trim()
            : null;
        normalizeAiProfiles();
    }

    public String getDefaultLocalModelId() {
        return defaultLocalModelId;
    }

    public void setDefaultLocalModelId(String defaultLocalModelId) {
        this.defaultLocalModelId = defaultLocalModelId != null && !defaultLocalModelId.isBlank()
            ? defaultLocalModelId.trim()
            : null;
    }

    public String getSecurityCheckAiProfileId() {
        return securityCheckAiProfileId;
    }

    public void setSecurityCheckAiProfileId(String securityCheckAiProfileId) {
        this.securityCheckAiProfileId = securityCheckAiProfileId != null && !securityCheckAiProfileId.isBlank()
            ? securityCheckAiProfileId.trim()
            : null;
        normalizeAiProfiles();
    }

    public String getTextAiProfileId() {
        return textAiProfileId;
    }

    public void setTextAiProfileId(String textAiProfileId) {
        this.textAiProfileId = normalizeOptionalString(textAiProfileId);
        normalizeAiProfiles();
    }

    public String getCodingAiProfileId() {
        return codingAiProfileId;
    }

    public void setCodingAiProfileId(String codingAiProfileId) {
        this.codingAiProfileId = normalizeOptionalString(codingAiProfileId);
        normalizeAiProfiles();
    }

    public String getRagEmbeddingModelId() {
        return ragEmbeddingModelId;
    }

    public void setRagEmbeddingModelId(String ragEmbeddingModelId) {
        this.ragEmbeddingModelId = normalizeOptionalString(ragEmbeddingModelId);
    }

    public String getEncryptedHuggingFaceToken() {
        return encryptedHuggingFaceToken;
    }

    public void setEncryptedHuggingFaceToken(String encryptedHuggingFaceToken) {
        this.encryptedHuggingFaceToken = normalizeOptionalString(encryptedHuggingFaceToken);
    }

    public LlamaRuntimeUpdatePolicy getLlamaRuntimeUpdatePolicy() {
        return llamaRuntimeUpdatePolicy != null ? llamaRuntimeUpdatePolicy : LlamaRuntimeUpdatePolicy.NOTIFY;
    }

    public void setLlamaRuntimeUpdatePolicy(LlamaRuntimeUpdatePolicy llamaRuntimeUpdatePolicy) {
        this.llamaRuntimeUpdatePolicy = llamaRuntimeUpdatePolicy != null
            ? llamaRuntimeUpdatePolicy
            : LlamaRuntimeUpdatePolicy.NOTIFY;
    }

    public LlamaBackend getPreferredLlamaRuntimeBackend() {
        return preferredLlamaRuntimeBackend != null ? preferredLlamaRuntimeBackend : LlamaBackend.AUTO;
    }

    public void setPreferredLlamaRuntimeBackend(LlamaBackend preferredLlamaRuntimeBackend) {
        this.preferredLlamaRuntimeBackend = preferredLlamaRuntimeBackend != null
            ? preferredLlamaRuntimeBackend
            : LlamaBackend.AUTO;
    }

    public String getEncryptedAiTavilyApiKey() {
        return encryptedAiTavilyApiKey;
    }

    public void setEncryptedAiTavilyApiKey(String encryptedAiTavilyApiKey) {
        this.encryptedAiTavilyApiKey = normalizeOptionalString(encryptedAiTavilyApiKey);
    }

    public String getEncryptedAiBrightDataApiToken() {
        return encryptedAiBrightDataApiToken;
    }

    public void setEncryptedAiBrightDataApiToken(String encryptedAiBrightDataApiToken) {
        this.encryptedAiBrightDataApiToken = normalizeOptionalString(encryptedAiBrightDataApiToken);
    }

    public String getEncryptedAiBraveSearchApiKey() {
        return encryptedAiBraveSearchApiKey;
    }

    public void setEncryptedAiBraveSearchApiKey(String encryptedAiBraveSearchApiKey) {
        this.encryptedAiBraveSearchApiKey = normalizeOptionalString(encryptedAiBraveSearchApiKey);
    }

    /** @return the global AI request timeout in minutes; {@code 0} means requests never time out. */
    public int getAiRequestTimeoutMinutes() {
        return aiRequestTimeoutMinutes != null && aiRequestTimeoutMinutes > 0 ? aiRequestTimeoutMinutes : 0;
    }

    public void setAiRequestTimeoutMinutes(int aiRequestTimeoutMinutes) {
        this.aiRequestTimeoutMinutes = Math.max(0, aiRequestTimeoutMinutes);
    }

    public String getAiSearxngUrl() {
        return aiSearxngUrl;
    }

    public void setAiSearxngUrl(String aiSearxngUrl) {
        this.aiSearxngUrl = normalizeOptionalString(aiSearxngUrl);
    }

    public String getAiTavilyMcpServerLabel() {
        return nonBlank(aiTavilyMcpServerLabel, "tavily");
    }

    public void setAiTavilyMcpServerLabel(String aiTavilyMcpServerLabel) {
        this.aiTavilyMcpServerLabel = nonBlank(aiTavilyMcpServerLabel, "tavily");
    }

    public String getAiBrightDataMcpServerLabel() {
        return nonBlank(aiBrightDataMcpServerLabel, "bright-data");
    }

    public void setAiBrightDataMcpServerLabel(String aiBrightDataMcpServerLabel) {
        this.aiBrightDataMcpServerLabel = nonBlank(aiBrightDataMcpServerLabel, "bright-data");
    }

    public String getAiBraveSearchMcpPluginId() {
        return aiBraveSearchMcpPluginId;
    }

    public void setAiBraveSearchMcpPluginId(String aiBraveSearchMcpPluginId) {
        this.aiBraveSearchMcpPluginId = normalizeOptionalString(aiBraveSearchMcpPluginId);
    }

    public String getAiSearxngMcpPluginId() {
        return aiSearxngMcpPluginId;
    }

    public void setAiSearxngMcpPluginId(String aiSearxngMcpPluginId) {
        this.aiSearxngMcpPluginId = normalizeOptionalString(aiSearxngMcpPluginId);
    }

    public String getAiLmStudioToolpackMcpPluginId() {
        return aiLmStudioToolpackMcpPluginId;
    }

    public void setAiLmStudioToolpackMcpPluginId(String aiLmStudioToolpackMcpPluginId) {
        this.aiLmStudioToolpackMcpPluginId = normalizeOptionalString(aiLmStudioToolpackMcpPluginId);
    }

    public Integer getAiResultFontSize() {
        return aiResultFontSize;
    }

    public void setAiResultFontSize(Integer aiResultFontSize) {
        this.aiResultFontSize = aiResultFontSize;
    }

    public JvmResourceProfile getJvmResourceProfile() {
        return jvmResourceProfile != null ? jvmResourceProfile : JvmResourceProfile.BALANCED;
    }

    public void setJvmResourceProfile(JvmResourceProfile jvmResourceProfile) {
        this.jvmResourceProfile = jvmResourceProfile != null ? jvmResourceProfile : JvmResourceProfile.BALANCED;
    }

    public Integer getSecurityReportFontSize() {
        return securityReportFontSize;
    }

    public void setSecurityReportFontSize(Integer securityReportFontSize) {
        this.securityReportFontSize = securityReportFontSize;
    }

    public Integer getAiDiffFontSize() {
        return aiDiffFontSize;
    }

    public void setAiDiffFontSize(Integer aiDiffFontSize) {
        this.aiDiffFontSize = aiDiffFontSize;
    }

    public Integer getAiReviewFontSize() {
        return aiReviewFontSize;
    }

    public void setAiReviewFontSize(Integer aiReviewFontSize) {
        this.aiReviewFontSize = aiReviewFontSize;
    }

    public Integer getAiDescribeFontSize() {
        return aiDescribeFontSize;
    }

    public void setAiDescribeFontSize(Integer aiDescribeFontSize) {
        this.aiDescribeFontSize = aiDescribeFontSize;
    }

    public Integer getAiAlternativesFontSize() {
        return aiAlternativesFontSize;
    }

    public void setAiAlternativesFontSize(Integer aiAlternativesFontSize) {
        this.aiAlternativesFontSize = aiAlternativesFontSize;
    }

    public Integer getCodeAnalysisFontSize() {
        return codeAnalysisFontSize;
    }

    public void setCodeAnalysisFontSize(Integer codeAnalysisFontSize) {
        this.codeAnalysisFontSize = codeAnalysisFontSize;
    }

    public Boolean getCodeAnalysisHardeningExpanded() {
        return codeAnalysisHardeningExpanded;
    }

    public void setCodeAnalysisHardeningExpanded(Boolean codeAnalysisHardeningExpanded) {
        this.codeAnalysisHardeningExpanded = codeAnalysisHardeningExpanded;
    }

    public Boolean getCodeAnalysisDiagramAutoGenerate() {
        return codeAnalysisDiagramAutoGenerate;
    }

    public void setCodeAnalysisDiagramAutoGenerate(Boolean codeAnalysisDiagramAutoGenerate) {
        this.codeAnalysisDiagramAutoGenerate = codeAnalysisDiagramAutoGenerate;
    }

    public Integer getWorkflowScriptFontSize() {
        return workflowScriptFontSize;
    }

    public void setWorkflowScriptFontSize(Integer workflowScriptFontSize) {
        this.workflowScriptFontSize = workflowScriptFontSize;
    }

    public String getAiCodeTextDefaultLanguage() {
        return aiCodeTextDefaultLanguage;
    }

    public void setAiCodeTextDefaultLanguage(String aiCodeTextDefaultLanguage) {
        this.aiCodeTextDefaultLanguage =
            aiCodeTextDefaultLanguage != null && !aiCodeTextDefaultLanguage.isBlank()
                ? aiCodeTextDefaultLanguage.trim()
                : null;
    }

    public String getSnippetTranslationTargetLanguage() {
        return snippetTranslationTargetLanguage;
    }

    public void setSnippetTranslationTargetLanguage(String snippetTranslationTargetLanguage) {
        this.snippetTranslationTargetLanguage =
            snippetTranslationTargetLanguage != null && !snippetTranslationTargetLanguage.isBlank()
                ? snippetTranslationTargetLanguage.trim()
                : null;
    }

    public String getChatColorProfileId() {
        return chatColorProfileId;
    }

    public void setChatColorProfileId(String chatColorProfileId) {
        this.chatColorProfileId =
            chatColorProfileId != null && !chatColorProfileId.isBlank()
                ? chatColorProfileId.trim()
                : null;
    }

    public boolean isAiConfirmBeforeSend() {
        return aiConfirmBeforeSend;
    }

    public void setAiConfirmBeforeSend(boolean aiConfirmBeforeSend) {
        this.aiConfirmBeforeSend = aiConfirmBeforeSend;
    }

    public boolean isAiFeaturesEnabled() {
        return aiFeaturesEnabled;
    }

    public void setAiFeaturesEnabled(boolean aiFeaturesEnabled) {
        this.aiFeaturesEnabled = aiFeaturesEnabled;
    }

    public boolean isTerminalAgentExecutionEnabled() {
        return terminalAgentExecutionEnabled;
    }

    public void setTerminalAgentExecutionEnabled(boolean terminalAgentExecutionEnabled) {
        this.terminalAgentExecutionEnabled = terminalAgentExecutionEnabled;
    }

    public boolean isTerminalAgentConfirmMutatingCommandSets() {
        return terminalAgentConfirmMutatingCommandSets;
    }

    public void setTerminalAgentConfirmMutatingCommandSets(boolean terminalAgentConfirmMutatingCommandSets) {
        this.terminalAgentConfirmMutatingCommandSets = terminalAgentConfirmMutatingCommandSets;
    }

    public boolean isDefaultPromptHookEnabled() {
        return defaultPromptHookEnabled;
    }

    public void setDefaultPromptHookEnabled(boolean defaultPromptHookEnabled) {
        this.defaultPromptHookEnabled = defaultPromptHookEnabled;
    }

    public boolean isTerminalAgentShowDebugMessages() {
        return terminalAgentShowDebugMessages;
    }

    public void setTerminalAgentShowDebugMessages(boolean terminalAgentShowDebugMessages) {
        this.terminalAgentShowDebugMessages = terminalAgentShowDebugMessages;
    }

    public boolean isTerminalAgentShowRuntimeMessages() {
        return terminalAgentShowRuntimeMessages;
    }

    public boolean isTerminalAgentMirrorFinalAnswerToTerminal() {
        return terminalAgentMirrorFinalAnswerToTerminal;
    }

    public void setTerminalAgentMirrorFinalAnswerToTerminal(boolean terminalAgentMirrorFinalAnswerToTerminal) {
        this.terminalAgentMirrorFinalAnswerToTerminal = terminalAgentMirrorFinalAnswerToTerminal;
    }

    public void setTerminalAgentShowRuntimeMessages(boolean terminalAgentShowRuntimeMessages) {
        this.terminalAgentShowRuntimeMessages = terminalAgentShowRuntimeMessages;
    }

    public boolean isTerminalAgentShowRunDialog() {
        return terminalAgentShowRunDialog;
    }

    public void setTerminalAgentShowRunDialog(boolean terminalAgentShowRunDialog) {
        this.terminalAgentShowRunDialog = terminalAgentShowRunDialog;
    }

    public String getTerminalAgentCommandName() {
        return terminalAgentCommandName;
    }

    public void setTerminalAgentCommandName(String terminalAgentCommandName) {
        this.terminalAgentCommandName =
            terminalAgentCommandName != null && !terminalAgentCommandName.isBlank()
                ? terminalAgentCommandName.trim()
                : "agent";
    }

    public boolean isTerminalAgentCommandNameCaseInsensitive() {
        return terminalAgentCommandNameCaseInsensitive;
    }

    public void setTerminalAgentCommandNameCaseInsensitive(boolean terminalAgentCommandNameCaseInsensitive) {
        this.terminalAgentCommandNameCaseInsensitive = terminalAgentCommandNameCaseInsensitive;
    }

    public TerminalAgentExecutionTarget getTerminalAgentExecutionTarget() {
        return terminalAgentExecutionTarget != null
            ? terminalAgentExecutionTarget
            : TerminalAgentExecutionTarget.TERMINAL_WINDOW;
    }

    public void setTerminalAgentExecutionTarget(TerminalAgentExecutionTarget terminalAgentExecutionTarget) {
        this.terminalAgentExecutionTarget = terminalAgentExecutionTarget != null
            ? terminalAgentExecutionTarget
            : TerminalAgentExecutionTarget.TERMINAL_WINDOW;
    }

    public boolean isTerminalAgentRememberPanelLayout() {
        return terminalAgentRememberPanelLayout;
    }

    public void setTerminalAgentRememberPanelLayout(boolean terminalAgentRememberPanelLayout) {
        this.terminalAgentRememberPanelLayout = terminalAgentRememberPanelLayout;
    }

    public Double getTerminalAgentPanelHeight() {
        return terminalAgentPanelHeight;
    }

    public void setTerminalAgentPanelHeight(Double terminalAgentPanelHeight) {
        this.terminalAgentPanelHeight = isPositiveFinite(terminalAgentPanelHeight)
            ? terminalAgentPanelHeight
            : null;
    }

    public Double getTerminalAgentPanelFontSize() {
        return terminalAgentPanelFontSize;
    }

    public void setTerminalAgentPanelFontSize(Double terminalAgentPanelFontSize) {
        this.terminalAgentPanelFontSize = isPositiveFinite(terminalAgentPanelFontSize)
            ? terminalAgentPanelFontSize
            : null;
    }

    public Double getTerminalAgentPlanFontSize() {
        return terminalAgentPlanFontSize;
    }

    public void setTerminalAgentPlanFontSize(Double terminalAgentPlanFontSize) {
        this.terminalAgentPlanFontSize = isPositiveFinite(terminalAgentPlanFontSize)
            ? terminalAgentPlanFontSize
            : null;
    }

    public boolean isTerminalAgentPanelKeepCollapsed() {
        return terminalAgentPanelKeepCollapsed;
    }

    public void setTerminalAgentPanelKeepCollapsed(boolean terminalAgentPanelKeepCollapsed) {
        this.terminalAgentPanelKeepCollapsed = terminalAgentPanelKeepCollapsed;
    }

    /** Placement of the AI-agent activity panel: "BOTTOM" (default), "LEFT" or "RIGHT". */
    public String getAiAgentPanelPlacement() {
        return aiAgentPanelPlacement != null ? aiAgentPanelPlacement : "BOTTOM";
    }

    public void setAiAgentPanelPlacement(String aiAgentPanelPlacement) {
        this.aiAgentPanelPlacement = aiAgentPanelPlacement;
    }

    /**
     * Allowed range and default for the docked AI-agent side-panel width. This is the single source
     * of truth for the bounds; {@code AiAgentPanelDockManager} derives its constants from these so the
     * two cannot drift apart.
     */
    public static final double AI_AGENT_PANEL_MIN_WIDTH = 200.0;
    public static final double AI_AGENT_PANEL_MAX_WIDTH = 600.0;
    public static final double AI_AGENT_PANEL_DEFAULT_WIDTH = 320.0;

    /** Docked AI-agent side-panel width, clamped to the allowed range (default 320). */
    public double getAiAgentPanelSideWidth() {
        double value = aiAgentPanelSideWidth != null ? aiAgentPanelSideWidth : AI_AGENT_PANEL_DEFAULT_WIDTH;
        return Math.max(AI_AGENT_PANEL_MIN_WIDTH, Math.min(value, AI_AGENT_PANEL_MAX_WIDTH));
    }

    public void setAiAgentPanelSideWidth(double width) {
        this.aiAgentPanelSideWidth = Math.max(AI_AGENT_PANEL_MIN_WIDTH, Math.min(width, AI_AGENT_PANEL_MAX_WIDTH));
    }

    /**
     * Allowed range and default for the docked live session-journal panel width. Single source of
     * truth; {@code SessionJournalLivePanelDockManager} derives its constants from these.
     * These are plain String/Double fields on this class, so no JAXBContext change in
     * {@code GlobalSettingsManager} is needed.
     */
    public static final double JOURNAL_LIVE_PANEL_MIN_WIDTH = 240.0;
    // Generous: the panel shows a full journal page with timeline cards and log excerpts, and on
    // a wide display a reader may well want it larger than the terminal beside it.
    public static final double JOURNAL_LIVE_PANEL_MAX_WIDTH = 1600.0;
    public static final double JOURNAL_LIVE_PANEL_DEFAULT_WIDTH = 380.0;

    /** Live session-journal panel placement: "HIDDEN" (default), "LEFT" or "RIGHT". */
    public String getJournalLivePanelPlacement() {
        return journalLivePanelPlacement != null ? journalLivePanelPlacement : "HIDDEN";
    }

    public void setJournalLivePanelPlacement(String journalLivePanelPlacement) {
        this.journalLivePanelPlacement = journalLivePanelPlacement;
    }

    /** Docked live session-journal panel width, clamped to the allowed range (default 380). */
    public double getJournalLivePanelWidth() {
        double value = journalLivePanelWidth != null ? journalLivePanelWidth : JOURNAL_LIVE_PANEL_DEFAULT_WIDTH;
        return Math.max(JOURNAL_LIVE_PANEL_MIN_WIDTH, Math.min(value, JOURNAL_LIVE_PANEL_MAX_WIDTH));
    }

    public void setJournalLivePanelWidth(double width) {
        this.journalLivePanelWidth = Math.max(JOURNAL_LIVE_PANEL_MIN_WIDTH, Math.min(width, JOURNAL_LIVE_PANEL_MAX_WIDTH));
    }

    /**
     * Allowed range and default for the docked file-browser sidebar width. Kept in sync with
     * {@code LocalFileBrowserManager}'s clamp and {@code MainWindow}'s FILE_BROWSER_* constants
     * so the persisted value never diverges from what the UI enforces.
     */
    public static final double FILE_BROWSER_MIN_WIDTH = 160.0;
    public static final double FILE_BROWSER_MAX_WIDTH = 420.0;
    public static final double FILE_BROWSER_DEFAULT_WIDTH = 220.0;

    /** File-browser sidebar placement: "HIDDEN" (default), "LEFT" or "RIGHT". */
    public String getFileBrowserPosition() {
        return fileBrowserPosition != null ? fileBrowserPosition : "HIDDEN";
    }

    public void setFileBrowserPosition(String fileBrowserPosition) {
        this.fileBrowserPosition = fileBrowserPosition;
    }

    /** Docked file-browser sidebar width, clamped to the allowed range (default 220). */
    public double getFileBrowserWidth() {
        double value = fileBrowserWidth != null ? fileBrowserWidth : FILE_BROWSER_DEFAULT_WIDTH;
        return Math.max(FILE_BROWSER_MIN_WIDTH, Math.min(value, FILE_BROWSER_MAX_WIDTH));
    }

    public void setFileBrowserWidth(double width) {
        this.fileBrowserWidth = Math.max(FILE_BROWSER_MIN_WIDTH, Math.min(width, FILE_BROWSER_MAX_WIDTH));
    }

    public boolean isFileBrowserShowHidden() {
        return fileBrowserShowHidden;
    }

    public void setFileBrowserShowHidden(boolean fileBrowserShowHidden) {
        this.fileBrowserShowHidden = fileBrowserShowHidden;
    }

    /** Last directory the file browser was rooted at; null or blank means the user's home. */
    public String getFileBrowserLastRoot() {
        return fileBrowserLastRoot;
    }

    public void setFileBrowserLastRoot(String fileBrowserLastRoot) {
        this.fileBrowserLastRoot = fileBrowserLastRoot;
    }

    public boolean isTerminalAgentPanelExpandAll() {
        return terminalAgentPanelExpandAll;
    }

    public void setTerminalAgentPanelExpandAll(boolean terminalAgentPanelExpandAll) {
        this.terminalAgentPanelExpandAll = terminalAgentPanelExpandAll;
    }

    public boolean isAiSnippetEditorAdditionalInstructionsEnabled() {
        return aiSnippetEditorAdditionalInstructionsEnabled;
    }

    public void setAiSnippetEditorAdditionalInstructionsEnabled(boolean aiSnippetEditorAdditionalInstructionsEnabled) {
        this.aiSnippetEditorAdditionalInstructionsEnabled = aiSnippetEditorAdditionalInstructionsEnabled;
    }

    public int getAiSnippetAlternativeSolutionCount() {
        return aiSnippetAlternativeSolutionCount != null && aiSnippetAlternativeSolutionCount > 0
            ? Math.min(aiSnippetAlternativeSolutionCount, 10)
            : 3;
    }

    public void setAiSnippetAlternativeSolutionCount(Integer aiSnippetAlternativeSolutionCount) {
        if (aiSnippetAlternativeSolutionCount == null) {
            this.aiSnippetAlternativeSolutionCount = 3;
            return;
        }
        this.aiSnippetAlternativeSolutionCount = Math.max(1, Math.min(aiSnippetAlternativeSolutionCount, 10));
    }

    public void initializeAiConfiguration() {
        if (aiProfiles == null) {
            aiProfiles = new java.util.ArrayList<>();
        }
        if (aiSkills == null) {
            aiSkills = new java.util.ArrayList<>();
        }
        migrateFromLegacyAiConfiguration();
        normalizeAiProfiles();
        normalizeAiSkills();
        normalizeAiInternetConfiguration();
        if (terminalAgentCommandName == null || terminalAgentCommandName.isBlank()) {
            terminalAgentCommandName = "agent";
        }
        if (terminalAgentExecutionTarget == null) {
            terminalAgentExecutionTarget = TerminalAgentExecutionTarget.TERMINAL_WINDOW;
        }
        if (!isPositiveFinite(terminalAgentPanelHeight)) {
            terminalAgentPanelHeight = null;
        }
        if (!isPositiveFinite(terminalAgentPanelFontSize)) {
            terminalAgentPanelFontSize = null;
        }
        if (!isPositiveFinite(terminalAgentPlanFontSize)) {
            terminalAgentPlanFontSize = null;
        }
        if (aiSnippetAlternativeSolutionCount == null || aiSnippetAlternativeSolutionCount <= 0) {
            aiSnippetAlternativeSolutionCount = 3;
        } else if (aiSnippetAlternativeSolutionCount > 10) {
            aiSnippetAlternativeSolutionCount = 10;
        }
    }

    public void migrateFromLegacyAiConfiguration() {
        if ((aiProfiles == null || aiProfiles.isEmpty()) && hasLegacyAiConfiguration()) {
            AiProfile legacyProfile = new AiProfile();
            legacyProfile.setId("legacy-default");
            legacyProfile.setName("Default");
            legacyProfile.setApiUrl(aiApiUrl);
            legacyProfile.setModel(aiModel);
            legacyProfile.setEncryptedApiKey(encryptedAiApiKey);
            legacyProfile.setMaxSelectionChars(AiProfile.DEFAULT_MAX_SELECTION_CHARS);
            aiProfiles = new java.util.ArrayList<>();
            aiProfiles.add(legacyProfile);
            aiApiUrl = null;
            aiModel = null;
            encryptedAiApiKey = null;
        }
    }

    private void normalizeAiProfiles() {
        if (aiProfiles == null) {
            defaultAiProfileId = null;
            return;
        }
        for (AiProfile profile : aiProfiles) {
            if (profile != null && (profile.getMaxSelectionChars() == null || profile.getMaxSelectionChars() <= 0)) {
                profile.setMaxSelectionChars(AiProfile.DEFAULT_MAX_SELECTION_CHARS);
            }
            if (profile != null && profile.getInternetAccessMode() == null) {
                profile.setInternetAccessMode(AiInternetAccessMode.DISABLED);
            }
            if (profile != null) {
                profile.setModelSelectionMode(profile.getModelSelectionMode());
                profile.setDiscoveredReasoningEfforts(profile.getDiscoveredReasoningEfforts());
            }
        }
        if (defaultAiProfileId != null && aiProfiles.stream()
            .filter(profile -> profile != null && profile.getId() != null && !profile.getId().isBlank())
            .noneMatch(profile -> defaultAiProfileId.equals(profile.getId()))) {
            defaultAiProfileId = null;
        }
        if (securityCheckAiProfileId != null && aiProfiles.stream()
            .filter(profile -> profile != null && profile.getId() != null && !profile.getId().isBlank())
            .noneMatch(profile -> securityCheckAiProfileId.equals(profile.getId()))) {
            securityCheckAiProfileId = null;
        }
        if (textAiProfileId != null && aiProfiles.stream()
            .filter(profile -> profile != null && profile.getId() != null && !profile.getId().isBlank())
            .noneMatch(profile -> textAiProfileId.equals(profile.getId()))) {
            textAiProfileId = null;
        }
        if (codingAiProfileId != null && aiProfiles.stream()
            .filter(profile -> profile != null && profile.getId() != null && !profile.getId().isBlank())
            .noneMatch(profile -> codingAiProfileId.equals(profile.getId()))) {
            codingAiProfileId = null;
        }
        ragEmbeddingModelId = normalizeOptionalString(ragEmbeddingModelId);
        encryptedHuggingFaceToken = normalizeOptionalString(encryptedHuggingFaceToken);
        if (llamaRuntimeUpdatePolicy == null) {
            llamaRuntimeUpdatePolicy = LlamaRuntimeUpdatePolicy.NOTIFY;
        }
        if (preferredLlamaRuntimeBackend == null) {
            preferredLlamaRuntimeBackend = LlamaBackend.AUTO;
        }
    }

    private void normalizeAiSkills() {
        if (aiSkills == null) {
            aiSkills = new java.util.ArrayList<>();
            return;
        }
        java.util.List<AiSkill> normalized = new java.util.ArrayList<>();
        for (AiSkill skill : aiSkills) {
            if (skill == null) {
                continue;
            }
            skill.ensureId();
            AiSkillTarget target = skill.getTarget();
            skill.setTarget(target != null ? target : AiSkillTarget.BOTH);
            skill.setTags(skill.getTags());
            skill.setBuiltinId(skill.getBuiltinId());
            if (!skill.isBuiltin()) {
                // User skills can never be hidden or carry delivery metadata; repairs hand-edited XML.
                skill.setHidden(false);
                skill.setBuiltinBaseline(null);
                skill.setBuiltinTopics(java.util.List.of());
            } else if (skill.getBuiltinBaseline() != null) {
                AiSkillBuiltinBaseline baseline = skill.getBuiltinBaseline();
                baseline.setTarget(baseline.getTarget());
                baseline.setVersion(baseline.getVersion());
            }
            normalized.add(skill);
        }
        aiSkills = normalized;
    }

    private void normalizeAiInternetConfiguration() {
        aiRequestTimeoutMinutes = aiRequestTimeoutMinutes != null && aiRequestTimeoutMinutes > 0
            ? aiRequestTimeoutMinutes
            : 0;
        aiSearxngUrl = normalizeOptionalString(aiSearxngUrl);
        aiTavilyMcpServerLabel = nonBlank(aiTavilyMcpServerLabel, "tavily");
        aiBrightDataMcpServerLabel = nonBlank(aiBrightDataMcpServerLabel, "bright-data");
        aiBraveSearchMcpPluginId = normalizeOptionalString(aiBraveSearchMcpPluginId);
        aiSearxngMcpPluginId = normalizeOptionalString(aiSearxngMcpPluginId);
        aiLmStudioToolpackMcpPluginId = normalizeOptionalString(aiLmStudioToolpackMcpPluginId);
    }

    private boolean hasLegacyAiConfiguration() {
        return (aiApiUrl != null && !aiApiUrl.isBlank() && !DEFAULT_AI_API_URL.equals(aiApiUrl.trim()))
            || (aiModel != null && !aiModel.isBlank())
            || (encryptedAiApiKey != null && !encryptedAiApiKey.isBlank());
    }

    private boolean isPositiveFinite(Double value) {
        return value != null && Double.isFinite(value) && value > 0.0;
    }

    private String normalizeOptionalString(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private String nonBlank(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }
    
    public ConnectionSettings getDefaultTerminalSettings() {
        if (defaultTerminalSettings == null) {
            defaultTerminalSettings = new ConnectionSettings();
        }
        return defaultTerminalSettings;
    }
    
    public void setDefaultTerminalSettings(ConnectionSettings defaultTerminalSettings) {
        this.defaultTerminalSettings = defaultTerminalSettings;
    }
    
    public ConnectionSettings getLastQuickConnectTerminalSettings() {
        return lastQuickConnectTerminalSettings;
    }
    
    public void setLastQuickConnectTerminalSettings(ConnectionSettings lastQuickConnectTerminalSettings) {
        this.lastQuickConnectTerminalSettings = lastQuickConnectTerminalSettings;
    }

    public Double getLastQuickConnectTerminalEffectAnimationSpeed() {
        return lastQuickConnectTerminalEffectAnimationSpeed;
    }

    public void setLastQuickConnectTerminalEffectAnimationSpeed(Double lastQuickConnectTerminalEffectAnimationSpeed) {
        this.lastQuickConnectTerminalEffectAnimationSpeed = lastQuickConnectTerminalEffectAnimationSpeed;
    }

    public boolean isTerminalEffectsEnabled() {
        return terminalEffectsEnabled;
    }

    public void setTerminalEffectsEnabled(boolean terminalEffectsEnabled) {
        this.terminalEffectsEnabled = terminalEffectsEnabled;
    }
    
    public Integer getLastQuickConnectTimeout() {
        return lastQuickConnectTimeout;
    }
    
    public void setLastQuickConnectTimeout(Integer lastQuickConnectTimeout) {
        this.lastQuickConnectTimeout = lastQuickConnectTimeout;
    }
    
    public Integer getLastQuickConnectRetries() {
        return lastQuickConnectRetries;
    }
    
    public void setLastQuickConnectRetries(Integer lastQuickConnectRetries) {
        this.lastQuickConnectRetries = lastQuickConnectRetries;
    }
    
    public boolean isConnectionRetriesEnabled() {
        return connectionRetriesEnabled;
    }
    
    public void setConnectionRetriesEnabled(boolean connectionRetriesEnabled) {
        this.connectionRetriesEnabled = connectionRetriesEnabled;
    }
    
    public boolean isHostKeyCheckDisabledForAllConnections() {
        return hostKeyCheckDisabledForAllConnections;
    }

    public void setHostKeyCheckDisabledForAllConnections(boolean value) {
        this.hostKeyCheckDisabledForAllConnections = value;
    }

    public java.util.List<String> getHostKeyCheckDisabledGroups() {
        if (hostKeyCheckDisabledGroups == null) {
            hostKeyCheckDisabledGroups = new java.util.ArrayList<>();
        }
        return hostKeyCheckDisabledGroups;
    }

    public void setHostKeyCheckDisabledGroups(java.util.List<String> groups) {
        this.hostKeyCheckDisabledGroups = groups;
    }

    /** Whether {@code group}'s host-key verification is relaxed (exact-name match). */
    public boolean isHostKeyCheckDisabledForGroup(String group) {
        return group != null && !group.isBlank() && getHostKeyCheckDisabledGroups().contains(group);
    }

    public void setHostKeyCheckDisabledForGroup(String group, boolean disabled) {
        if (group == null || group.isBlank()) {
            return;
        }
        java.util.List<String> groups = getHostKeyCheckDisabledGroups();
        if (disabled) {
            if (!groups.contains(group)) {
                groups.add(group);
            }
        } else {
            groups.remove(group);
        }
    }

    public java.util.List<String> getAccessReasonHistory() {
        if (accessReasonHistory == null) {
            accessReasonHistory = new java.util.ArrayList<>();
        }
        return accessReasonHistory;
    }
    
    public void setAccessReasonHistory(java.util.List<String> accessReasonHistory) {
        this.accessReasonHistory = accessReasonHistory;
    }
    
    /**
     * Adds a new access reason to the history (keeps max 5 entries).
     */
    public void addAccessReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            return;
        }
        java.util.List<String> history = getAccessReasonHistory();
        // Remove if already exists (to move to front)
        history.remove(reason);
        // Add at front
        history.add(0, reason);
        // Keep only last 5
        while (history.size() > 5) {
            history.remove(history.size() - 1);
        }
    }

    public java.util.List<String> getAiPromptHistory() {
        if (aiPromptHistory == null) {
            aiPromptHistory = new java.util.ArrayList<>();
        }
        return aiPromptHistory;
    }

    public void setAiPromptHistory(java.util.List<String> aiPromptHistory) {
        this.aiPromptHistory = aiPromptHistory;
    }

    public void addAiPromptHistoryEntry(String prompt) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return;
        }
        java.util.List<String> history = getAiPromptHistory();
        String normalized = prompt.trim();
        history.remove(normalized);
        history.add(0, normalized);
        while (history.size() > 10) {
            history.remove(history.size() - 1);
        }
    }

    public void clearAiPromptHistory() {
        getAiPromptHistory().clear();
    }

    public java.util.List<String> getGuideAskHistory() {
        if (guideAskHistory == null) {
            guideAskHistory = new java.util.ArrayList<>();
        }
        return guideAskHistory;
    }

    public void setGuideAskHistory(java.util.List<String> guideAskHistory) {
        this.guideAskHistory = guideAskHistory;
    }

    /** Records a guide AI-search question: deduplicated, newest first, capped at 10 entries. */
    public void addGuideAskHistoryEntry(String question) {
        if (question == null || question.trim().isEmpty()) {
            return;
        }
        java.util.List<String> history = getGuideAskHistory();
        String normalized = question.trim();
        history.remove(normalized);
        history.add(0, normalized);
        while (history.size() > 10) {
            history.remove(history.size() - 1);
        }
    }

    public java.util.List<String> getWorkflowInstructionsHistory() {
        if (workflowInstructionsHistory == null) {
            workflowInstructionsHistory = new java.util.ArrayList<>();
        }
        return workflowInstructionsHistory;
    }

    public void setWorkflowInstructionsHistory(java.util.List<String> workflowInstructionsHistory) {
        this.workflowInstructionsHistory = workflowInstructionsHistory;
    }

    /** Records a workflow-generator instruction: deduplicated, newest first, capped at 10 entries. */
    public void addWorkflowInstructionsHistoryEntry(String instructions) {
        if (instructions == null || instructions.trim().isEmpty()) {
            return;
        }
        java.util.List<String> history = getWorkflowInstructionsHistory();
        String normalized = instructions.trim();
        history.remove(normalized);
        history.add(0, normalized);
        while (history.size() > 10) {
            history.remove(history.size() - 1);
        }
    }

    public int getTerminalAgentInputHistorySize() {
        return terminalAgentInputHistorySize != null && terminalAgentInputHistorySize > 0
            ? Math.max(5, Math.min(terminalAgentInputHistorySize, 100))
            : 20;
    }

    public void setTerminalAgentInputHistorySize(Integer terminalAgentInputHistorySize) {
        if (terminalAgentInputHistorySize == null) {
            this.terminalAgentInputHistorySize = 20;
            return;
        }
        this.terminalAgentInputHistorySize = Math.max(5, Math.min(terminalAgentInputHistorySize, 100));
    }

    /** Returns the remembered prompts, newest first (without timestamps). */
    public java.util.List<String> getTerminalAgentInputHistory() {
        java.util.List<String> prompts = new java.util.ArrayList<>();
        for (TerminalAgentInputHistoryEntry entry : getTerminalAgentInputHistoryEntries()) {
            if (entry != null && entry.getPrompt() != null) {
                prompts.add(entry.getPrompt());
            }
        }
        return prompts;
    }

    /** Returns the remembered prompt entries (prompt + last-used timestamp), newest first. */
    public java.util.List<TerminalAgentInputHistoryEntry> getTerminalAgentInputHistoryEntries() {
        if (terminalAgentInputHistory == null) {
            terminalAgentInputHistory = new java.util.ArrayList<>();
        }
        return terminalAgentInputHistory;
    }

    public void setTerminalAgentInputHistory(java.util.List<TerminalAgentInputHistoryEntry> terminalAgentInputHistory) {
        this.terminalAgentInputHistory = terminalAgentInputHistory;
    }

    /** Adds a terminal agent prompt to the front of the history (deduped), capped at the configured size. */
    public void addTerminalAgentInput(String prompt) {
        addTerminalAgentInput(prompt, System.currentTimeMillis());
    }

    /**
     * Adds a terminal agent prompt to the front of the history, recording {@code whenMillis} as its
     * last-used time. Entries are de-duplicated by prompt text only: running the same prompt again
     * moves it to the front and refreshes its timestamp instead of creating a duplicate. The list is
     * capped at the configured size.
     */
    public void addTerminalAgentInput(String prompt, long whenMillis) {
        if (prompt == null || prompt.trim().isEmpty()) {
            return;
        }
        String normalized = prompt.trim();
        java.util.List<TerminalAgentInputHistoryEntry> history = getTerminalAgentInputHistoryEntries();
        history.removeIf(entry -> entry == null || normalized.equals(promptKey(entry)));
        history.add(0, new TerminalAgentInputHistoryEntry(normalized, whenMillis));
        int max = getTerminalAgentInputHistorySize();
        while (history.size() > max) {
            history.remove(history.size() - 1);
        }
    }

    /**
     * Removes the terminal agent prompt-history entry whose prompt text matches {@code prompt}
     * (compared trimmed). Returns {@code true} if an entry was removed.
     */
    public boolean removeTerminalAgentInput(String prompt) {
        if (prompt == null) {
            return false;
        }
        String normalized = prompt.trim();
        if (normalized.isEmpty()) {
            return false;
        }
        return getTerminalAgentInputHistoryEntries()
            .removeIf(entry -> entry == null || normalized.equals(promptKey(entry)));
    }

    /** Removes every remembered terminal agent prompt from the input history. */
    public void clearTerminalAgentInputHistory() {
        getTerminalAgentInputHistoryEntries().clear();
    }

    /** Persisted width of the TAB history popup, clamped to a sane range (default 460). */
    public int getTerminalAgentHistoryPopupWidth() {
        int v = terminalAgentHistoryPopupWidth != null ? terminalAgentHistoryPopupWidth : 460;
        return Math.max(280, Math.min(v, 1400));
    }

    public void setTerminalAgentHistoryPopupWidth(Integer width) {
        this.terminalAgentHistoryPopupWidth = width == null ? null : Math.max(280, Math.min(width, 1400));
    }

    /** Persisted height of the TAB history popup, clamped to a sane range (default 260). */
    public int getTerminalAgentHistoryPopupHeight() {
        int v = terminalAgentHistoryPopupHeight != null ? terminalAgentHistoryPopupHeight : 260;
        return Math.max(120, Math.min(v, 900));
    }

    public void setTerminalAgentHistoryPopupHeight(Integer height) {
        this.terminalAgentHistoryPopupHeight = height == null ? null : Math.max(120, Math.min(height, 900));
    }

    /**
     * Dedup/match key for a history entry: the trimmed prompt text (or {@code null}). Comparing on the
     * trimmed value keeps add and remove symmetric even for legacy/externally-edited entries whose
     * stored prompt carries surrounding whitespace.
     */
    private static String promptKey(TerminalAgentInputHistoryEntry entry) {
        if (entry == null || entry.getPrompt() == null) {
            return null;
        }
        return entry.getPrompt().trim();
    }

    /**
     * Gets the SFTP Manager auto-close timeout in minutes.
     * @return Timeout in minutes, or null/0 if disabled
     */
    public Integer getSftpAutoCloseMinutes() {
        return sftpAutoCloseMinutes;
    }
    
    /**
     * Sets the SFTP Manager auto-close timeout in minutes.
     * @param sftpAutoCloseMinutes Timeout in minutes, or null/0 to disable
     */
    public void setSftpAutoCloseMinutes(Integer sftpAutoCloseMinutes) {
        this.sftpAutoCloseMinutes = sftpAutoCloseMinutes;
    }
    
    /**
     * Gets the default path for remote ZIP creation.
     * @return Default path (default: /tmp)
     */
    public String getSftpDefaultZipPath() {
        return sftpDefaultZipPath != null ? sftpDefaultZipPath : "/tmp";
    }
    
    /**
     * Sets the default path for remote ZIP creation.
     * @param sftpDefaultZipPath Default path for ZIP files
     */
    public void setSftpDefaultZipPath(String sftpDefaultZipPath) {
        this.sftpDefaultZipPath = sftpDefaultZipPath;
    }
    
    /**
     * Gets the default compression level for ZIP creation (0-9).
     * @return Compression level (default: 6)
     */
    public Integer getSftpDefaultZipCompression() {
        return sftpDefaultZipCompression != null ? sftpDefaultZipCompression : 6;
    }
    
    /**
     * Sets the default compression level for ZIP creation.
     * @param sftpDefaultZipCompression Compression level (0-9)
     */
    public void setSftpDefaultZipCompression(Integer sftpDefaultZipCompression) {
        this.sftpDefaultZipCompression = sftpDefaultZipCompression;
    }
    
    /**
     * Gets the default foreground color for the embedded editor.
     * @return Hex color string (default: #000000)
     */
    public String getEditorForegroundColor() {
        return editorForegroundColor != null ? editorForegroundColor : "#000000";
    }
    
    /**
     * Sets the default foreground color for the embedded editor.
     * @param editorForegroundColor Hex color string
     */
    public void setEditorForegroundColor(String editorForegroundColor) {
        this.editorForegroundColor = editorForegroundColor;
    }
    
    /**
     * Gets the default background color for the embedded editor.
     * @return Hex color string (default: #FFFFFF)
     */
    public String getEditorBackgroundColor() {
        return editorBackgroundColor != null ? editorBackgroundColor : "#FFFFFF";
    }
    
    /**
     * Sets the default background color for the embedded editor.
     * @param editorBackgroundColor Hex color string
     */
    public void setEditorBackgroundColor(String editorBackgroundColor) {
        this.editorBackgroundColor = editorBackgroundColor;
    }
    
    /**
     * Gets the cursor style for the embedded editor.
     * @return Cursor style (BLOCK, LINE, UNDERSCORE)
     */
    public String getEditorCursorStyle() {
        if (editorCursorStyle == null || editorCursorStyle.isEmpty()) {
            return "BLOCK";
        }
        return editorCursorStyle;
    }
    
    /**
     * Sets the cursor style for the embedded editor.
     * @param editorCursorStyle Cursor style (BLOCK, LINE, UNDERSCORE)
     */
    public void setEditorCursorStyle(String editorCursorStyle) {
        this.editorCursorStyle = editorCursorStyle;
    }
    
    /**
     * Gets the cursor color for the embedded editor.
     * @return Hex color string
     */
    public String getEditorCursorColor() {
        if (editorCursorColor == null || editorCursorColor.isEmpty()) {
            return "#FF0000";
        }
        return editorCursorColor;
    }
    
    /**
     * Sets the cursor color for the embedded editor.
     * @param editorCursorColor Hex color string
     */
    public void setEditorCursorColor(String editorCursorColor) {
        this.editorCursorColor = editorCursorColor;
    }
    
    // ---- Snippet Editor Settings ----
    
    public String getSnippetFontFamily() { return snippetFontFamily; }
    public void setSnippetFontFamily(String snippetFontFamily) { this.snippetFontFamily = snippetFontFamily; }
    
    public Integer getSnippetFontSize() { return snippetFontSize; }
    public void setSnippetFontSize(Integer snippetFontSize) { this.snippetFontSize = snippetFontSize; }
    
    public String getSnippetForegroundColor() { return snippetForegroundColor; }
    public void setSnippetForegroundColor(String snippetForegroundColor) { this.snippetForegroundColor = snippetForegroundColor; }
    
    public String getSnippetBackgroundColor() { return snippetBackgroundColor; }
    public void setSnippetBackgroundColor(String snippetBackgroundColor) { this.snippetBackgroundColor = snippetBackgroundColor; }
    
    public String getSnippetCursorStyle() { return snippetCursorStyle; }
    public void setSnippetCursorStyle(String snippetCursorStyle) { this.snippetCursorStyle = snippetCursorStyle; }
    
    public String getSnippetCursorColor() { return snippetCursorColor; }
    public void setSnippetCursorColor(String snippetCursorColor) { this.snippetCursorColor = snippetCursorColor; }

    public String getSnippetDiagramBackgroundColor() {
        return snippetDiagramBackgroundColor != null && !snippetDiagramBackgroundColor.isBlank()
            ? snippetDiagramBackgroundColor
            : "#FFFFFF";
    }

    public void setSnippetDiagramBackgroundColor(String snippetDiagramBackgroundColor) {
        this.snippetDiagramBackgroundColor = snippetDiagramBackgroundColor != null && !snippetDiagramBackgroundColor.isBlank()
            ? snippetDiagramBackgroundColor.trim()
            : "#FFFFFF";
    }

    public String getSnippetDiagramColorMode() {
        return snippetDiagramColorMode != null && !snippetDiagramColorMode.isBlank()
            ? snippetDiagramColorMode
            : "auto";
    }

    public void setSnippetDiagramColorMode(String snippetDiagramColorMode) {
        this.snippetDiagramColorMode = snippetDiagramColorMode != null && !snippetDiagramColorMode.isBlank()
            ? snippetDiagramColorMode.trim().toLowerCase(java.util.Locale.ROOT)
            : "auto";
    }

    /** Raw persisted hardening selection (may be null = never saved, or "" = saved empty). */
    public String getSnippetHardeningOptions() {
        return snippetHardeningOptions;
    }

    public void setSnippetHardeningOptions(String snippetHardeningOptions) {
        this.snippetHardeningOptions = snippetHardeningOptions;
    }

    /** Whether the per-run "Input hardening" master toggle starts ticked (default: off). */
    public boolean isSnippetInputHardeningEnabled() {
        return Boolean.TRUE.equals(snippetInputHardeningEnabled);
    }

    public void setSnippetInputHardeningEnabled(Boolean snippetInputHardeningEnabled) {
        this.snippetInputHardeningEnabled = snippetInputHardeningEnabled;
    }

    /** Raw persisted input-hardening sub-option selection (may be null = never saved, or "" = saved empty). */
    public String getSnippetInputHardeningOptions() {
        return snippetInputHardeningOptions;
    }

    public void setSnippetInputHardeningOptions(String snippetInputHardeningOptions) {
        this.snippetInputHardeningOptions = snippetInputHardeningOptions;
    }

    /** Default for the generated MAX_FILE_SIZE variable, in MB (10 when unset; 0 = unlimited; clamped 0..1024). */
    public int getSnippetInputHardeningMaxFileSizeMb() {
        if (snippetInputHardeningMaxFileSizeMb == null || snippetInputHardeningMaxFileSizeMb < 0) {
            return 10;
        }
        return Math.min(1024, snippetInputHardeningMaxFileSizeMb);
    }

    public void setSnippetInputHardeningMaxFileSizeMb(Integer snippetInputHardeningMaxFileSizeMb) {
        if (snippetInputHardeningMaxFileSizeMb == null) {
            this.snippetInputHardeningMaxFileSizeMb = 10;
            return;
        }
        this.snippetInputHardeningMaxFileSizeMb = Math.max(0, Math.min(1024, snippetInputHardeningMaxFileSizeMb));
    }

    public String getSelectedSnippetEditorProfileId() { return selectedSnippetEditorProfileId; }
    public void setSelectedSnippetEditorProfileId(String selectedSnippetEditorProfileId) {
        this.selectedSnippetEditorProfileId = selectedSnippetEditorProfileId != null && !selectedSnippetEditorProfileId.isBlank()
            ? selectedSnippetEditorProfileId.trim()
            : null;
    }

    public java.util.List<SnippetEditorProfile> getSnippetEditorProfiles() {
        if (snippetEditorProfiles == null) {
            snippetEditorProfiles = new java.util.ArrayList<>();
        }
        return snippetEditorProfiles;
    }

    public void setSnippetEditorProfiles(java.util.List<SnippetEditorProfile> snippetEditorProfiles) {
        this.snippetEditorProfiles = snippetEditorProfiles != null
            ? new java.util.ArrayList<>(snippetEditorProfiles)
            : new java.util.ArrayList<>();
    }
    
    public boolean isSnippetWordWrap() { return snippetWordWrap; }
    public void setSnippetWordWrap(boolean snippetWordWrap) { this.snippetWordWrap = snippetWordWrap; }
    
    public boolean isSnippetLineNumbers() { return snippetLineNumbers; }
    public void setSnippetLineNumbers(boolean snippetLineNumbers) { this.snippetLineNumbers = snippetLineNumbers; }

    public double getSnippetManagerPreviewDividerPosition() {
        if (snippetManagerPreviewDividerPosition == null
            || snippetManagerPreviewDividerPosition <= 0.0
            || snippetManagerPreviewDividerPosition >= 1.0) {
            return 0.68;
        }
        return snippetManagerPreviewDividerPosition;
    }

    public void setSnippetManagerPreviewDividerPosition(Double snippetManagerPreviewDividerPosition) {
        if (snippetManagerPreviewDividerPosition == null) {
            this.snippetManagerPreviewDividerPosition = null;
            return;
        }
        this.snippetManagerPreviewDividerPosition = Math.max(0.35, Math.min(0.9, snippetManagerPreviewDividerPosition));
    }

    public int getSnippetHistoryMaxSize() {
        if (snippetHistoryMaxSize == null || snippetHistoryMaxSize <= 0) {
            return 30;
        }
        return Math.min(99, snippetHistoryMaxSize);
    }

    public void setSnippetHistoryMaxSize(Integer snippetHistoryMaxSize) {
        if (snippetHistoryMaxSize == null) {
            this.snippetHistoryMaxSize = 30;
            return;
        }
        this.snippetHistoryMaxSize = Math.max(1, Math.min(99, snippetHistoryMaxSize));
    }

    // ---- Snippet Dialog Geometries ----
    
    public WindowGeometry getSnippetManagerGeometry() { return snippetManagerGeometry; }
    public void setSnippetManagerGeometry(WindowGeometry snippetManagerGeometry) { this.snippetManagerGeometry = snippetManagerGeometry; }
    
    public WindowGeometry getSnippetEditGeometry() { return snippetEditGeometry; }
    public void setSnippetEditGeometry(WindowGeometry snippetEditGeometry) { this.snippetEditGeometry = snippetEditGeometry; }
    
    public WindowGeometry getAsciiArtDialogGeometry() { return asciiArtDialogGeometry; }
    public void setAsciiArtDialogGeometry(WindowGeometry asciiArtDialogGeometry) { this.asciiArtDialogGeometry = asciiArtDialogGeometry; }

    public double getAsciiArtPreviewFontSize() { return asciiArtPreviewFontSize; }
    public void setAsciiArtPreviewFontSize(double asciiArtPreviewFontSize) { this.asciiArtPreviewFontSize = asciiArtPreviewFontSize; }

    public WindowGeometry getAlternativeSnippetSolutionsDialogGeometry() { return alternativeSnippetSolutionsDialogGeometry; }
    public void setAlternativeSnippetSolutionsDialogGeometry(WindowGeometry alternativeSnippetSolutionsDialogGeometry) {
        this.alternativeSnippetSolutionsDialogGeometry = alternativeSnippetSolutionsDialogGeometry;
    }

    public WindowGeometry getSnippetCodeAnalysisDialogGeometry() { return snippetCodeAnalysisDialogGeometry; }
    public void setSnippetCodeAnalysisDialogGeometry(WindowGeometry snippetCodeAnalysisDialogGeometry) {
        this.snippetCodeAnalysisDialogGeometry = snippetCodeAnalysisDialogGeometry;
    }

    public WindowGeometry getAiDiffDialogGeometry() { return aiDiffDialogGeometry; }
    public void setAiDiffDialogGeometry(WindowGeometry aiDiffDialogGeometry) {
        this.aiDiffDialogGeometry = aiDiffDialogGeometry;
    }

    public Double getAiDiffDialogSummaryDividerPosition() { return aiDiffDialogSummaryDividerPosition; }
    public void setAiDiffDialogSummaryDividerPosition(Double aiDiffDialogSummaryDividerPosition) {
        this.aiDiffDialogSummaryDividerPosition = aiDiffDialogSummaryDividerPosition;
    }

    public WindowGeometry getWorkflowScriptDialogGeometry() { return workflowScriptDialogGeometry; }
    public void setWorkflowScriptDialogGeometry(WindowGeometry workflowScriptDialogGeometry) {
        this.workflowScriptDialogGeometry = workflowScriptDialogGeometry;
    }

    public WindowGeometry getCustomAiImprovementDialogGeometry() { return customAiImprovementDialogGeometry; }
    public void setCustomAiImprovementDialogGeometry(WindowGeometry customAiImprovementDialogGeometry) {
        this.customAiImprovementDialogGeometry = customAiImprovementDialogGeometry;
    }

    public java.util.List<WorkflowHeaderDefault> getWorkflowHeaderDefaults() {
        if (workflowHeaderDefaults == null) {
            workflowHeaderDefaults = new java.util.ArrayList<>();
        }
        return workflowHeaderDefaults;
    }

    public void setWorkflowHeaderDefaults(java.util.List<WorkflowHeaderDefault> workflowHeaderDefaults) {
        this.workflowHeaderDefaults = workflowHeaderDefaults != null ? workflowHeaderDefaults : new java.util.ArrayList<>();
    }

    /** Header-snippet id configured as default for the given language ({@code ScriptLanguage.name()}), or null. */
    public String getWorkflowHeaderDefault(String language) {
        if (language == null) {
            return null;
        }
        for (WorkflowHeaderDefault entry : getWorkflowHeaderDefaults()) {
            if (language.equals(entry.getLanguage())) {
                return entry.getHeaderSnippetId();
            }
        }
        return null;
    }

    /** Sets (or, when headerSnippetId is null, clears) the default header snippet for a language. */
    public void setWorkflowHeaderDefault(String language, String headerSnippetId) {
        if (language == null) {
            return;
        }
        java.util.List<WorkflowHeaderDefault> list = getWorkflowHeaderDefaults();
        list.removeIf(entry -> language.equals(entry.getLanguage()));
        if (headerSnippetId != null && !headerSnippetId.isBlank()) {
            list.add(new WorkflowHeaderDefault(language, headerSnippetId));
        }
    }

    public WindowGeometry getJobSchedulerDialogGeometry() { return jobSchedulerDialogGeometry; }
    public void setJobSchedulerDialogGeometry(WindowGeometry jobSchedulerDialogGeometry) {
        this.jobSchedulerDialogGeometry = jobSchedulerDialogGeometry;
    }

    /** Saved position/size of the in-app guide viewer window, or {@code null} if never stored. */
    public WindowGeometry getGuideViewerGeometry() { return guideViewerGeometry; }

    /** Stores the in-app guide viewer window position/size. */
    public void setGuideViewerGeometry(WindowGeometry guideViewerGeometry) {
        this.guideViewerGeometry = guideViewerGeometry;
    }

    public WindowGeometry getAiManagerDialogGeometry() { return aiManagerDialogGeometry; }

    /** Stores the AI manager dialog window position/size. */
    public void setAiManagerDialogGeometry(WindowGeometry aiManagerDialogGeometry) {
        this.aiManagerDialogGeometry = aiManagerDialogGeometry;
    }

    public WindowGeometry getSavedChatsDialogGeometry() { return savedChatsDialogGeometry; }

    /** Stores the saved chats dialog window position/size. */
    public void setSavedChatsDialogGeometry(WindowGeometry savedChatsDialogGeometry) {
        this.savedChatsDialogGeometry = savedChatsDialogGeometry;
    }

    public WindowGeometry getSessionJournalManagerGeometry() { return sessionJournalManagerGeometry; }

    /** Stores the session journal manager dialog window position/size. */
    public void setSessionJournalManagerGeometry(WindowGeometry sessionJournalManagerGeometry) {
        this.sessionJournalManagerGeometry = sessionJournalManagerGeometry;
    }

    public WindowGeometry getSessionJournalViewerGeometry() { return sessionJournalViewerGeometry; }

    /** Stores the session journal viewer dialog window position/size. */
    public void setSessionJournalViewerGeometry(WindowGeometry sessionJournalViewerGeometry) {
        this.sessionJournalViewerGeometry = sessionJournalViewerGeometry;
    }

    public WindowGeometry getSessionJournalScreenshotEditorGeometry() {
        return sessionJournalScreenshotEditorGeometry;
    }

    /** Stores the screenshot edit window position/size. */
    public void setSessionJournalScreenshotEditorGeometry(WindowGeometry geometry) {
        this.sessionJournalScreenshotEditorGeometry = geometry;
    }

    public Double getSessionJournalViewerEditDividerPosition() { return sessionJournalViewerEditDividerPosition; }

    public void setSessionJournalViewerEditDividerPosition(Double sessionJournalViewerEditDividerPosition) {
        this.sessionJournalViewerEditDividerPosition = sessionJournalViewerEditDividerPosition;
    }

    // ---- Teamwork ----
    
    public java.util.List<TeamworkSourceConfig> getTeamworkSources() {
        if (teamworkSources == null) {
            teamworkSources = new java.util.ArrayList<>();
        }
        return teamworkSources;
    }
    
    public void setTeamworkSources(java.util.List<TeamworkSourceConfig> teamworkSources) {
        this.teamworkSources = teamworkSources;
    }

    /**
     * Swaps the teamwork-source list reference and returns the previous one. Counterpart of
     * {@link #exchangeAiProfilesForMarshal} for the enterprise-policy marshal path.
     */
    public java.util.List<TeamworkSourceConfig> exchangeTeamworkSourcesForMarshal(
            java.util.List<TeamworkSourceConfig> replacement) {
        java.util.List<TeamworkSourceConfig> previous = this.teamworkSources;
        this.teamworkSources = replacement;
        return previous;
    }
    
    public int getTeamworkDefaultCheckIntervalMinutes() {
        return teamworkDefaultCheckIntervalMinutes;
    }
    
    public void setTeamworkDefaultCheckIntervalMinutes(int teamworkDefaultCheckIntervalMinutes) {
        this.teamworkDefaultCheckIntervalMinutes = teamworkDefaultCheckIntervalMinutes;
    }
    
    public String getTeamworkDefaultCredentialId() {
        return teamworkDefaultCredentialId;
    }
    
    public void setTeamworkDefaultCredentialId(String teamworkDefaultCredentialId) {
        this.teamworkDefaultCredentialId = teamworkDefaultCredentialId;
    }
    
    public String getTeamworkDefaultSshKeyId() {
        return teamworkDefaultSshKeyId;
    }
    
    public void setTeamworkDefaultSshKeyId(String teamworkDefaultSshKeyId) {
        this.teamworkDefaultSshKeyId = teamworkDefaultSshKeyId;
    }
    
    public String getTeamworkDefaultUsername() {
        return teamworkDefaultUsername;
    }
    
    public void setTeamworkDefaultUsername(String teamworkDefaultUsername) {
        this.teamworkDefaultUsername = teamworkDefaultUsername;
    }
    
    public boolean getTeamworkUseTemporaryKey() {
        return teamworkUseTemporaryKey;
    }
    
    public void setTeamworkUseTemporaryKey(boolean teamworkUseTemporaryKey) {
        this.teamworkUseTemporaryKey = teamworkUseTemporaryKey;
    }

    private static String trimToNull(String value) {
        String trimmed = value != null ? value.trim() : "";
        return trimmed.isBlank() ? null : trimmed;
    }
}
