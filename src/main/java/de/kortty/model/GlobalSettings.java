package de.kortty.model;

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
    private boolean jobSchedulerMenuStatusEnabled = true; // Show JobScheduler status in the menu bar

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
    private boolean requireMasterPasswordOnStartup = true; // Require master password on startup
    
    /** When true, temporary SSH key option is shown in Connection Manager and Quick Connect. Default: false. */
    @XmlElement
    private boolean temporarySshKeyEnabled = false;
    
    @XmlElement
    private String language; // Language code (e.g., "en", "de", "fr") - null means auto-detect
    
    /** Translation API provider for dynamic i18n (Google Translate or DeepL). */
    @XmlElement
    private TranslationApiProvider translationApiProvider;
    
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

    /** Preferred AI profile used when no explicit profile is selected by the user. */
    @XmlElement
    private String defaultAiProfileId;

    /** Encrypted Tavily API key used by KorTTY's direct web-search tool and Tavily MCP. */
    @XmlElement
    private String encryptedAiTavilyApiKey;

    /** Encrypted Bright Data API token used by Bright Data Web MCP. */
    @XmlElement
    private String encryptedAiBrightDataApiToken;

    /** Encrypted Brave Search API key used by Brave Search MCP. */
    @XmlElement
    private String encryptedAiBraveSearchApiKey;

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

    /** Font size used in temporary AI result tabs. */
    @XmlElement
    private Integer aiResultFontSize = 13;

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

    @XmlElementWrapper(name = "aiPromptHistory")
    @XmlElement(name = "prompt")
    private java.util.List<String> aiPromptHistory;

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

    /** Last window geometry of the alternative snippet solutions dialog. */
    @XmlElement
    private WindowGeometry alternativeSnippetSolutionsDialogGeometry;

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

    public boolean isJobSchedulerMenuStatusEnabled() {
        return jobSchedulerMenuStatusEnabled;
    }

    public void setJobSchedulerMenuStatusEnabled(boolean jobSchedulerMenuStatusEnabled) {
        this.jobSchedulerMenuStatusEnabled = jobSchedulerMenuStatusEnabled;
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

    public boolean isRequireMasterPasswordOnStartup() {
        return requireMasterPasswordOnStartup;
    }
    
    public void setRequireMasterPasswordOnStartup(boolean requireMasterPasswordOnStartup) {
        this.requireMasterPasswordOnStartup = requireMasterPasswordOnStartup;
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

    public String getDefaultAiProfileId() {
        return defaultAiProfileId;
    }

    public void setDefaultAiProfileId(String defaultAiProfileId) {
        this.defaultAiProfileId = defaultAiProfileId != null && !defaultAiProfileId.isBlank()
            ? defaultAiProfileId.trim()
            : null;
        normalizeAiProfiles();
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
            normalized.add(skill);
        }
        aiSkills = normalized;
    }

    private void normalizeAiInternetConfiguration() {
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

    public WindowGeometry getAlternativeSnippetSolutionsDialogGeometry() { return alternativeSnippetSolutionsDialogGeometry; }
    public void setAlternativeSnippetSolutionsDialogGeometry(WindowGeometry alternativeSnippetSolutionsDialogGeometry) {
        this.alternativeSnippetSolutionsDialogGeometry = alternativeSnippetSolutionsDialogGeometry;
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
