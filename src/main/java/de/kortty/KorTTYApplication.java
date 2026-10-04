package de.kortty;

import de.kortty.core.ActiveConnectionRegistry;
import de.kortty.core.ConfigurationManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.EnvironmentManager;
import de.kortty.core.SSHKeyManager;
import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetVariableManager;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.LegacyDiagramCacheCleanup;
import de.kortty.core.LegacyTemporaryKeyFileCleanup;
import de.kortty.core.LoggingConfiguration;
import de.kortty.core.ThemeManager;
import de.kortty.core.TerminalEffectPluginManager;
import de.kortty.core.BackupManager;
import de.kortty.codingagent.AgentRuleRepository;
import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentDetector;
import de.kortty.codingagent.CodingAgentNavigator;
import de.kortty.codingagent.CodingAgentNotificationCoordinator;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentService;
import de.kortty.codingagent.FocusOracle;
import de.kortty.codingagent.desktop.AppBadgeBackends;
import de.kortty.codingagent.desktop.AppBadgeService;
import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.codingagent.desktop.PlatformProbe;
import de.kortty.codingagent.desktop.StageIconPresenter;
import de.kortty.codingagent.desktop.TitleBadgePresenter;
import de.kortty.ui.CodingAgentUiBridge;
import de.kortty.core.AiChatManager;
import de.kortty.core.SwarmChatManager;
import de.kortty.teamwork.TeamworkSyncService;
import de.kortty.teamwork.TeamworkRecycleBinService;
import de.kortty.jobscheduler.JobSchedulerService;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.model.TeamworkSourceConfig;
import de.kortty.model.TeamworkSourceType;
import de.kortty.telemetry.Telemetry;
import de.kortty.telemetry.TelemetryEvents;
import de.kortty.telemetry.TelemetryProps;
import de.kortty.telemetry.TelemetryService;
import de.kortty.update.UpdateCheckService;
import de.kortty.jmx.SSHClientMonitor;
import de.kortty.security.MasterPasswordManager;
import de.kortty.power.PowerManagementCoordinator;
import de.kortty.ui.MainWindow;
import de.kortty.ui.MasterPasswordDialog;
import de.kortty.ui.AppDesignStyleSupport;
import java.awt.Desktop;
import java.awt.desktop.AppForegroundListener;
import java.awt.desktop.AppReopenedListener;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.control.Alert;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Main entry point for the KorTTY SSH Client application.
 */
public class KorTTYApplication extends Application {

    static {
        LoggingConfiguration.bootstrapFromPersistedSettings(getConfigDirectory());
    }

    private static final Logger logger = LoggerFactory.getLogger(KorTTYApplication.class);
    private static final String APP_NAME = "KorTTY";
    private static final String APP_VERSION = "2.18.0";
    
    private static KorTTYApplication instance;
    private AutoCloseable llamaRuntimeStatusSubscription;
    private volatile String lastNotifiedLlamaRuntimeId;
    private AutoCloseable mlxRuntimeStatusSubscription;
    private volatile String lastNotifiedMlxRuntimeId;
    
    private ConfigurationManager configManager;
    private MasterPasswordManager masterPasswordManager;
    private GPGKeyManager gpgKeyManager;
    private CredentialManager credentialManager;
    private EnvironmentManager environmentManager;
    private SSHKeyManager sshKeyManager;
    private SnippetManager snippetManager;
    private de.kortty.core.SnippetAnalysisStore snippetAnalysisStore;
    private de.kortty.core.SnippetDraftStore snippetDraftStore;
    private SnippetVariableManager snippetVariableManager;
    private GlobalSettingsManager globalSettingsManager;
    private de.kortty.core.SettingsAiUsageRecorder aiUsageRecorder;
    private ThemeManager themeManager;
    private TerminalEffectPluginManager terminalEffectPluginManager;
    private CodingAgentService codingAgentService;
    private de.kortty.core.highlight.TerminalHighlightService terminalHighlightService;
    // Coding-agent UI services (Stage 2): registry + verbs + navigation + notifications + badge.
    private CodingAgentRegistry codingAgentRegistry;
    private CodingAgentActions codingAgentActions;
    private CodingAgentNavigator codingAgentNavigator;
    private CodingAgentNotificationCoordinator codingAgentNotificationCoordinator;
    private AppBadgeService appBadgeService;
    private java.util.concurrent.ExecutorService appBadgeExecutor;
    private DesktopNotifier desktopNotifier;
    private CodingAgentUiBridge codingAgentUiBridge;
    // Control API (Stage 3): the listener plus the bridge that answers it from the windows.
    private de.kortty.control.ControlApiServer controlApiServer;
    private de.kortty.ui.ControlApiUiBridge controlApiUiBridge;
    private BackupManager backupManager;
    private AiChatManager aiChatManager;
    private SwarmChatManager swarmChatManager;
    private de.kortty.core.SessionJournalService sessionJournalService;
    private de.kortty.core.SessionJournalSummarizer sessionJournalSummarizer;
    private de.kortty.core.AutomationJournalRetention automationJournalRetention;
    private de.kortty.core.SessionJournalScreenshotAnalyzer sessionJournalScreenshotAnalyzer;
    private de.kortty.core.SessionJournalHtmlRenderer sessionJournalHtmlRenderer;
    private TeamworkSyncService teamworkSyncService;
    private TeamworkRecycleBinService teamworkRecycleBinService;
    private JobSchedulerService jobSchedulerService;
    private de.kortty.jobscheduler.WebhookSender jobWebhookSender;
    private UpdateCheckService updateCheckService;
    private TelemetryService telemetryService;
    private ScheduledExecutorService logMaintenanceExecutor;
    private PowerManagementCoordinator powerManagementCoordinator;
    private Runnable schedulerPowerStateListener;
    private boolean macDesktopHandlersRegistered = false;
    private Boolean packagedMacApp;
    private volatile boolean shuttingDown = false;
    /**
     * Set when a backup import replaced {@code master.key}: every store in memory still belongs
     * to the old master key, so the shutdown must not write them over the restored files.
     */
    private volatile boolean restoredBackupAwaitsRestart = false;
    /** Keeps ~/.kortty/session/ current while korTTY runs; null when it could not start. */
    private de.kortty.ui.SessionAutosaveCoordinator sessionAutosave;
    private de.kortty.policy.PolicyManager policyManager;
    
    public static void main(String[] args) {
        JavaFxPlatformSupport.configureRenderer();
        // Admin console mode: encrypt a sensitive policy-file value (e.g. an AI-profile API key)
        // into the kortty-enc:v1: envelope, without starting JavaFX.
        if (args.length > 0 && "--encrypt-policy-value".equals(args[0])) {
            runEncryptPolicyValue(args);
            return;
        }
        logger.info("Starting {} v{}", APP_NAME, APP_VERSION);
        if ("sw".equalsIgnoreCase(System.getProperty("prism.order", ""))) {
            logger.info("JavaFX software renderer enabled for Windows ARM x64 emulation");
        }
        launch(args);
    }

    /**
     * Console routine behind {@code korTTY --encrypt-policy-value [value]}. Reads the plaintext
     * from the argument, or interactively (echo-free where a console is available) when omitted,
     * and prints the envelope for the admin to paste into kortty-policy.toml.
     */
    private static void runEncryptPolicyValue(String[] args) {
        String plaintext;
        if (args.length > 1) {
            plaintext = args[1];
        } else if (System.console() != null) {
            char[] chars = System.console().readPassword("Value to encrypt: ");
            plaintext = chars == null ? "" : new String(chars);
        } else {
            try (java.util.Scanner scanner = new java.util.Scanner(System.in)) {
                System.out.print("Value to encrypt: ");
                plaintext = scanner.hasNextLine() ? scanner.nextLine() : "";
            }
        }
        if (plaintext.isEmpty()) {
            System.err.println("No value given — nothing to encrypt.");
            System.exit(1);
        }
        System.out.println(de.kortty.policy.PolicyValueCipher.encrypt(plaintext));
        // Explicit exit: the logging bootstrap in the static initializer may have started
        // non-daemon threads that would otherwise keep this console-only invocation alive.
        System.exit(0);
    }
    
    public static KorTTYApplication getInstance() {
        return instance;
    }
    
    @Override
    public void init() throws Exception {
        // Unusable AI answers are archived beside the log only inside the running application.
        de.kortty.core.AiAnswerArchive.enableForApplication();
        instance = this;

        // Load the enterprise policy FIRST — the settings managers constructed below must see the
        // clamp before their first load, so a policy-managed value can never leak through.
        policyManager = de.kortty.policy.PolicyManager.initialize();

        // Remove the retired diagram renderer's app-owned download cache and abandoned work
        // directories before loading persisted application data. Cleanup is deliberately
        // best-effort so a locked or read-only legacy file can never prevent korTTY from starting.
        LegacyDiagramCacheCleanup.cleanupAtStartup();
        // Delete private keys that earlier versions wrote to the temp folder for temporary SSH
        // keys and never removed. Best-effort as well, and limited to the current user's files.
        LegacyTemporaryKeyFileCleanup.cleanupAtStartup();
        // Remove the private folders of remote files edited in an external editor that a crashed
        // or killed session left behind (older than a day, own folders only, links never
        // followed). In the background: a large temp folder must not delay the start.
        Thread remoteEditSweep = new Thread(de.kortty.core.remote.edit.RemoteEditTempSweeper::sweepAtStartup,
            "kortty-remote-edit-sweep");
        remoteEditSweep.setDaemon(true);
        remoteEditSweep.start();
        
        // Install global exception handler to suppress SithTermFX bug
        installGlobalExceptionHandler();
        
        // Initialize configuration directory
        Path configDir = getConfigDirectory();
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
            logger.info("Created configuration directory: {}", configDir);
        }
        // Also an existing directory: older versions (and the logging setup, which can create
        // ~/.kortty/logs before this point) created it with the default umask.
        restrictConfigDirectoryToOwner(configDir);
        
        // Initialize managers
        configManager = new ConfigurationManager(configDir);
        masterPasswordManager = new MasterPasswordManager(configDir);
        gpgKeyManager = new GPGKeyManager(configDir);
        credentialManager = new CredentialManager(configDir);
        environmentManager = new EnvironmentManager(configDir);
        environmentManager.load();
        sshKeyManager = new SSHKeyManager(configDir);
        snippetManager = new SnippetManager(configDir);
        snippetVariableManager = new SnippetVariableManager(configDir);
        globalSettingsManager = new GlobalSettingsManager(configDir);
        aiUsageRecorder = new de.kortty.core.SettingsAiUsageRecorder(globalSettingsManager);
        globalSettingsManager.setPolicyClamp(
            new de.kortty.policy.PolicyClamp(policyManager.getEffective()));
        // Stored Full-code analyses: built right after the snippet manager, outside the fragile load
        // block below, so a failed snippets load can never leave the store missing. Only saved,
        // non-policy snippets are written; drafts stay in memory until their first save.
        snippetAnalysisStore = new de.kortty.core.SnippetAnalysisStore(
            configDir.resolve(de.kortty.core.SnippetAnalysisStore.DIRECTORY_NAME),
            id -> snippetManager.findById(id).filter(snippet -> !snippet.isPolicyManaged()).isPresent()
                || snippetManager.findFolder(de.kortty.core.SnippetProjectAiSupport.folderIdOfKey(id)).isPresent(),
            () -> globalSettingsManager.getSettings().getSnippetAnalysisHistoryMaxSize());
        // One decision point for how much script text an analysis stores: the user's setting capped
        // by the enterprise policy, read live on every use.
        de.kortty.core.SnippetAnalysisContentLimit.install(() -> de.kortty.core.SnippetAnalysisContentLimit.compute(
            globalSettingsManager.getSettings().getSnippetAnalysisMaxStoredContentBytes(),
            de.kortty.policy.PolicyManager.effective().snippetAnalysisMaxStoredContentBytes()));
        snippetAnalysisStore.attachTo(snippetManager);
        snippetAnalysisStore.warnOnMutationsOffFxThread();
        de.kortty.core.SnippetAnalysisStore.installApplicationStore(snippetAnalysisStore);
        // Unsaved editor drafts (crash protection); not part of the backup, read lazily per editor.
        snippetDraftStore = new de.kortty.core.SnippetDraftStore(
            configDir.resolve(de.kortty.core.SnippetDraftStore.DIRECTORY_NAME));
        de.kortty.core.SnippetDraftStore.installApplicationStore(snippetDraftStore);
        powerManagementCoordinator = PowerManagementCoordinator.createDefault();
        themeManager = new ThemeManager(configDir);
        terminalEffectPluginManager = new TerminalEffectPluginManager(configDir);
        // The repository itself warns once per invalid override file ("Ignoring coding-agents rule file").
        AgentRuleRepository codingAgentRules = new AgentRuleRepository(configDir);
        codingAgentService = new CodingAgentService(
            new CodingAgentDetector(codingAgentRules),
            () -> {
                GlobalSettings current = globalSettingsManager.getSettings();
                return current != null && current.isCodingAgentDetectionEnabled();
            },
            Platform::runLater,
            CodingAgentService.defaultScheduler());
        // Keyword highlighting of terminal output: one shared thread; dormant while no set is active.
        terminalHighlightService = new de.kortty.core.highlight.TerminalHighlightService(
            de.kortty.core.highlight.TerminalHighlightService.defaultScheduler());
        initCodingAgentUiServices();
        initControlApi(configDir);
        aiChatManager = new AiChatManager(configDir);
        swarmChatManager = new SwarmChatManager(configDir);
        sessionJournalService = new de.kortty.core.SessionJournalService();
        sessionJournalSummarizer = new de.kortty.core.SessionJournalSummarizer(sessionJournalService);
        automationJournalRetention = new de.kortty.core.AutomationJournalRetention(
            sessionJournalService,
            () -> globalSettingsManager != null ? globalSettingsManager.getSettings() : null,
            this::automationJournalConfigOf,
            de.kortty.core.AutomationJournalPolicy::current,
            sessionJournalSummarizer::isPending,
            java.time.Clock.systemDefaultZone());
        sessionJournalScreenshotAnalyzer =
            new de.kortty.core.SessionJournalScreenshotAnalyzer(sessionJournalService);
        sessionJournalHtmlRenderer = new de.kortty.core.SessionJournalHtmlRenderer(sessionJournalService);
        sessionJournalHtmlRenderer.attachToServiceChanges();
        // A regenerated page keeps the look the user chose: the font size from the page's A-/A+
        // buttons and the scheme and fonts from the viewer's appearance popover.
        sessionJournalHtmlRenderer.setAppearanceSupplier(() -> {
            GlobalSettings journalSettings =
                globalSettingsManager != null ? globalSettingsManager.getSettings() : null;
            if (journalSettings == null) {
                return de.kortty.core.SessionJournalPageAppearance.defaults();
            }
            return new de.kortty.core.SessionJournalPageAppearance(
                journalSettings.getSessionJournalPageSchemeId(),
                journalSettings.getSessionJournalPageUiFont(),
                journalSettings.getSessionJournalPageMonoFont(),
                journalSettings.getSessionJournalFontScalePercent(),
                journalSettings.getSessionJournalPageTheme());
        });
        sessionJournalHtmlRenderer.setSchemeResolver(
            schemeId -> de.kortty.ui.SessionJournalPageSchemes.resolve(schemeId, this));
        sessionJournalHtmlRenderer.setBrandingSupplier(() -> de.kortty.core.ExportBranding.fromSettings(
            globalSettingsManager != null ? globalSettingsManager.getSettings() : null));
        telemetryService = new TelemetryService(globalSettingsManager, configDir);
        Telemetry.init(telemetryService);

        // Register JMX MBean
        registerJMXBean();
    }
    
    /**
     * Installs a global exception handler to suppress known harmless exceptions.
     */
    private void installGlobalExceptionHandler() {
        // Set default uncaught exception handler for all threads
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // Suppress known SithTermFX ClassCastException bug
            if (throwable instanceof ClassCastException) {
                String message = throwable.getMessage();
                // SithTermFX bug can have null message or the specific KeyFrame/Timeline message
                if (message == null || 
                    (message.contains("javafx.animation.KeyFrame") && message.contains("javafx.animation.Timeline"))) {
                    // This is the known SithTermFX WeakRedrawTimer bug - silently ignore it
                    return;
                }
            }
            
            // Log all other exceptions
            logger.error("Uncaught exception in thread {}: {}", thread.getName(), throwable.getMessage(), throwable);
        });
        
    }
    
    @Override
    public void start(Stage primaryStage) {
        try {
            prepareMacApplicationLifecycle();

            // Load global settings first (they are not encrypted) to check if master password is required
            try {
                globalSettingsManager.load();
                AppDesignStyleSupport.initializeGlobalStyling(
                    globalSettingsManager.getSettings().getAppDesign());
                themeManager.load();
                
                // Initialize language manager EARLY with settings, before any UI is created
                // This ensures the correct language is used from the start
                de.kortty.core.LanguageManager.getInstance().initialize(globalSettingsManager.getSettings());
                applyLoggingSettings();
            } catch (Exception e) {
                logger.warn("Failed to load global settings, using defaults", e);
            }
            // The fallback also makes the startup order explicit when loading failed: Modena is
            // selected and the global Window listener is installed before any password UI exists.
            AppDesignStyleSupport.initializeGlobalStyling(
                globalSettingsManager.getSettings().getAppDesign());
            
            // Check if master password needs to be set up or verified
            // Always show dialog if password is not set (first time setup)
            // Otherwise, check the setting
            boolean passwordNotSet = !masterPasswordManager.isPasswordSet();
            boolean requirePasswordOnStartup = globalSettingsManager.getSettings().isRequireMasterPasswordOnStartup();
            boolean skipPasswordPrompt = globalSettingsManager.getSettings().isSkipMasterPasswordPrompt();
            // Developer/test launch: TEST_MODE_KORTTY=1 starts without the master-password gate.
            // Honored ONLY in non-packaged dev launches (e.g. `./gradlew run`); jpackage sets
            // jpackage.app-path on every platform, so the bypass can never apply to a release binary.
            String testModeFlag = System.getenv("TEST_MODE_KORTTY");
            boolean testModeRequested = "1".equals(testModeFlag) || "true".equalsIgnoreCase(testModeFlag);
            String jpackageAppPath = System.getProperty("jpackage.app-path");
            boolean packagedBuild = jpackageAppPath != null && !jpackageAppPath.isBlank();
            boolean testMode = testModeRequested && !packagedBuild;
            if (testMode) {
                logger.warn("TEST_MODE_KORTTY enabled — skipping the master-password dialog (dev launch only)");
            } else if (testModeRequested && packagedBuild) {
                logger.warn("TEST_MODE_KORTTY ignored in a packaged build — the master-password gate stays active.");
            }

            if (!testMode && skipPasswordPrompt) {
                // Auto-unlock: the user disabled the startup prompt. Unlock the vault from the
                // remembered password so encrypted secrets (AI profiles, SSH passwords, credentials)
                // stay usable — unlike requireMasterPasswordOnStartup=false, which leaves them locked.
                logger.warn("Master-password prompt disabled — unlocking the vault automatically (insecure)");
                if (!masterPasswordManager.tryAutoUnlock()) {
                    // No usable remembered password yet (or it went stale): prompt once, then remember it.
                    if (!handleMasterPassword(primaryStage)) {
                        Platform.exit();
                        return;
                    }
                    try {
                        char[] entered = masterPasswordManager.getMasterPassword();
                        if (entered != null) {
                            masterPasswordManager.saveAutoUnlockPassword(entered);
                        }
                    } catch (Exception e) {
                        logger.warn("Could not remember the master password for automatic unlock", e);
                    }
                }
            } else if (!testMode && (passwordNotSet || requirePasswordOnStartup)) {
                // Show master password dialog
                if (!handleMasterPassword(primaryStage)) {
                    Platform.exit();
                    return;
                }
            } else {
                // Password is set but not required on startup: start with the vault locked. Stored
                // secrets stay encrypted until the user unlocks it through Configuration > Security >
                // Unlock Vault... or the Unlock Vault... button of a "vault locked" message
                // (VaultUnlockSupport); onVaultUnlocked() then catches up on what this start skipped.
                logger.info("Master password required on startup is disabled, starting with the vault locked");
            }
            
            // Load configuration
            configManager.load(masterPasswordManager.getDerivedKey());
            
            // Load the per-feature stores one by one: a corrupt file in one of them must not skip
            // the unrelated managers behind it (the shared try block used to do exactly that).
            try {
                gpgKeyManager.load();
            } catch (Exception e) {
                logger.warn("Failed to load GPG keys", e);
            }
            try {
                credentialManager.load();
            } catch (Exception e) {
                logger.warn("Failed to load credentials", e);
            }
            try {
                sshKeyManager.load();
            } catch (Exception e) {
                logger.warn("Failed to load SSH keys", e);
            }
            try {
                snippetManager.load();
            } catch (Exception e) {
                logger.warn("Failed to load snippets", e);
            }
            try {
                snippetVariableManager.load();
            } catch (Exception e) {
                logger.warn("Failed to load snippet variables", e);
            }
            try {
                aiChatManager.load();
            } catch (Exception e) {
                logger.warn("Failed to load AI chats", e);
            }
            try {
                swarmChatManager.load();
            } catch (Exception e) {
                logger.warn("Failed to load AI swarm chats", e);
            }
            // Reload global settings to ensure we have the latest version
            // Note: This reload should preserve the language setting from the file
            try {
                globalSettingsManager.load();
                AppDesignStyleSupport.initializeGlobalStyling(
                    globalSettingsManager.getSettings().getAppDesign());
            } catch (Exception e) {
                logger.warn("Failed to reload global settings", e);
            }
            if (terminalHighlightService != null) {
                // Rule sets and the default set come from the settings loaded just above.
                try {
                    terminalHighlightService.reload(globalSettingsManager.getSettings());
                } catch (RuntimeException e) {
                    logger.warn("Failed to load the keyword highlighting rule sets: {}", e.toString());
                }
            }
            try {
                themeManager.load();
            } catch (Exception e) {
                logger.warn("Failed to load themes", e);
            }

            // getSettings() never returns null: a failed reload keeps the settings loaded earlier.
            GlobalSettings loadedSettings = globalSettingsManager.getSettings();
            try {
                // Re-initialize language manager with the loaded settings
                // This ensures the language from the saved settings is applied
                logger.info("Re-initializing language manager with language: '{}'", loadedSettings.getLanguage());
                de.kortty.core.LanguageManager.getInstance().initialize(loadedSettings);
                applyLoggingSettings();
                applyPersistedPowerManagementSetting(loadedSettings);
            } catch (Exception e) {
                logger.warn("Failed to apply the loaded global settings (language, logging, power management)", e);
            }

            // Sync the bundled AI skill catalog into the settings (add new, auto-update
            // unmodified built-ins). Must never prevent startup.
            try {
                de.kortty.core.BuiltinAiSkillProvisioner.provision(globalSettingsManager);
            } catch (Exception e) {
                logger.warn("Failed to provision built-in AI skills", e);
            }

            // Sync ConfigurationManager with persisted terminal settings
            // so that all components reading from configManager see the saved values
            try {
                ConnectionSettings savedTermSettings = loadedSettings.getDefaultTerminalSettings();
                if (savedTermSettings != null) {
                    configManager.setGlobalSettings(new ConnectionSettings(savedTermSettings));
                }
            } catch (Exception e) {
                logger.warn("Failed to apply the persisted terminal settings", e);
            }

            // Initialize BackupManager after settings are loaded
            try {
                backupManager = new BackupManager(getConfigDirectory(), globalSettingsManager.getSettings());
            } catch (Exception e) {
                logger.warn("Failed to initialize the backup manager", e);
            }
            try {
                jobSchedulerService = new JobSchedulerService(this, getConfigDirectory());
                jobSchedulerService.load();
                schedulerPowerStateListener = this::syncSchedulerPowerState;
                jobSchedulerService.addListener(schedulerPowerStateListener);
                JobSchedulerService scheduler = jobSchedulerService;
                jobWebhookSender = new de.kortty.jobscheduler.WebhookSender();
                de.kortty.jobscheduler.JobWebhookNotifier webhooks = new de.kortty.jobscheduler.JobWebhookNotifier(
                    targetId -> scheduler.getRepository().findWebhookTarget(targetId),
                    new de.kortty.jobscheduler.WebhookTargetSecrets(masterPasswordManager.getEncryptionService()),
                    () -> getMasterPasswordManager() != null ? getMasterPasswordManager().getMasterPassword() : null,
                    new de.kortty.jobscheduler.WebhookPayloadFormatter(),
                    jobWebhookSender,
                    scheduler::appendNotificationJournal,
                    de.kortty.policy.PolicyManager::effective);
                jobSchedulerService.addRunEventListener(new de.kortty.jobscheduler.JobNotificationDispatcher(
                    this::getDesktopNotifier, de.kortty.policy.PolicyManager::effective, java.time.Clock.systemUTC(),
                    jobId -> scheduler.findJob(jobId)
                        .map(de.kortty.jobscheduler.ScheduledJob::effectiveNotificationConfig)
                        .orElse(null),
                    webhooks));
                syncSchedulerPowerState();
                jobSchedulerService.start();
            } catch (Exception e) {
                logger.warn("Failed to start the job scheduler", e);
            }
            if (automationJournalRetention != null) {
                automationJournalRetention.start();
            }

            // RAG startup reconciliation is independent of credentials, snippets, and scheduler
            // initialization. A failure in one of those subsystems must not disable automatic
            // knowledge-source synchronization for this application session.
            de.kortty.rag.RagCoordinator.startDefault();

            if (de.kortty.policy.PolicyManager.effective().pluginsAllowed()) {
                try {
                    terminalEffectPluginManager.load();
                } catch (Exception e) {
                    logger.warn("Failed to load terminal effect plugins", e);
                }
            } else {
                logger.info("Plugins disabled by enterprise policy — skipping plugin load");
            }

            // Start teamwork sync and recycle bin (separate try-catch for accurate error context)
            try {
                if (de.kortty.policy.PolicyManager.effective().teamworkAllowed()) {
                    teamworkSyncService = new TeamworkSyncService(getConfigDirectory(), globalSettingsManager);
                    teamworkSyncService.start();
                } else {
                    logger.info("Teamwork disabled by enterprise policy — sync service not started");
                }
            } catch (Exception e) {
                logger.warn("TeamworkSyncService failed to start (configDirectory={}, globalSettingsManager={})",
                    getConfigDirectory(), globalSettingsManager, e);
            }
            try {
                teamworkRecycleBinService = new TeamworkRecycleBinService(getConfigDirectory());
                teamworkRecycleBinService.load();
            } catch (Exception e) {
                logger.warn("TeamworkRecycleBinService failed to load (configDirectory={})",
                    getConfigDirectory(), e);
            }
            
            // Start telemetry before the main window: consent from the setup dialog is
            // already persisted here, and the error appender is live during window construction.
            telemetryService.start();

            // Internal-clipboard mode: redirect copy/cut/paste shortcuts of native text controls
            // and WebViews on every window (no-op in system clipboard mode).
            de.kortty.policy.PolicyClipboardGuard.install();

            // An installed but invalid policy file has put the app into fail-safe lockdown —
            // tell the user before the (restricted) main window appears.
            if (policyManager != null && policyManager.hasLoadFailure()) {
                policyManager.loadResult().ifPresent(
                    de.kortty.policy.PolicyUiSupport::showMalformedPolicyDialog);
            }

            // The session snapshot: makes the last run's session the previous one and keeps the
            // Recently Closed list, before the first window can change either.
            try {
                sessionAutosave = MainWindow.startSessionAutosave(
                    de.kortty.core.SessionSnapshotStore.open(getConfigDirectory()),
                    () -> !restoredBackupAwaitsRestart);
            } catch (RuntimeException e) {
                logger.warn("The session snapshot could not start; the session will not be saved", e);
            }

            // Create and show main window
            MainWindow mainWindow = new MainWindow(primaryStage);
            mainWindow.show();
            try {
                showStoreLoadFailures(mainWindow);
            } catch (RuntimeException e) {
                logger.warn("Could not show the notice about unreadable data files", e);
            }
            startCodingAgentUi();
            startControlApi();
            startLlamaRuntimeUpdateCoordinator();
            // Register/download admin-provisioned local AI models in the background.
            new de.kortty.policy.PolicyRuntimeProvisioner(getConfigDirectory()).provisionAsync();
            registerMacDesktopHandlers();
            // The AWT Taskbar Dock menu only attaches to a real .app bundle's Dock
            // tile (not a `./gradlew run` JVM), and initializing AWT there would also
            // keep a non-daemon thread alive — so restrict it to the packaged app.
            if (isMacOs() && isPackagedMacApplication()) {
                de.kortty.ui.MacDockMenu.install();
                // Always-available control surface for the background (JobScheduler)
                // app: open a window or quit even when no window is showing and the
                // native macOS Quit is broken (JDK-8332656).
                de.kortty.ui.MacMenuBarIcon.install();
            }
            startUpdateCheckService();

            // One-time consent prompt for existing installations (first-run installs
            // decide in the password-setup dialog; testMode never prompts).
            if (!testMode) {
                Platform.runLater(() -> de.kortty.ui.TelemetryConsentDialog.maybeShow(this, primaryStage));
            }

            // The session before this start: offered in a bar, or restored once no dialog is open.
            // Posted after the consent prompt, so its connections never ask on top of that dialog.
            de.kortty.model.SessionRestoreMode sessionRestoreMode =
                globalSettingsManager.getSettings().getSessionRestoreMode();
            Platform.runLater(() -> {
                try {
                    mainWindow.startSessionRestore(sessionRestoreMode);
                } catch (RuntimeException e) {
                    logger.warn("The previous session could not be offered at startup", e);
                }
            });

            trackUsageSnapshot("startup");
            trackAiProfileSnapshots();

            logger.info("{} started successfully", APP_NAME);
            
        } catch (Exception e) {
            logger.error("Failed to start application", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            showErrorAndExit(msg);
        }
    }
    
    /** JavaFX lifecycle stop hook; routes to {@link #shutdownAndExit()}. */
    @Override
    public void stop() throws Exception {
        shutdownAndExit();
    }

    /**
     * Runs the shutdown cleanup and force-terminates the JVM. Called both from
     * JavaFX's {@link #stop()} and directly from the quit paths — with
     * {@code Platform.setImplicitExit(false)} (the packaged macOS keep-alive),
     * {@code Platform.exit()} does not reliably reach {@code stop()}/the JVM exit,
     * so the quit handlers call this directly to guarantee the app actually quits.
     * Idempotent via {@link #shuttingDown}.
     */
    public void shutdownAndExit() {
        startShutdownWatchdog();
        performShutdown();
        // Hard-halt instead of System.exit(0). Once AWT is loaded (the Dock menu &
        // menu-bar icon pull in the lwawt toolkit), the normal JVM exit sequence runs
        // the JavaFX + AWT shutdown hooks, which dispose native peers via
        // LWCToolkit.invokeAndWait on the AppKit *main* thread. JavaFX Glass owns that
        // thread and never pumps AWT's invocation, so the quit thread blocks forever —
        // this is the 10-15s "hang" macOS reports when quitting from the Dock/tray menu
        // (and why the menu-bar Quit appeared to do nothing). performShutdown() has
        // already flushed all state synchronously, so it is safe to skip the hooks and
        // terminate the process immediately.
        Runtime.getRuntime().halt(0);
    }

    /**
     * Guarantees the process dies once a quit is committed: if any shutdown step
     * wedges (a stuck SSH close, an AWT main/EDT deadlock, …) before the final
     * {@code halt(0)}, this daemon hard-halts after a bounded grace period. Without
     * it a single blocked step turns "quit" into a process that must be killed.
     */
    private void startShutdownWatchdog() {
        Thread watchdog = new Thread(() -> {
            try {
                Thread.sleep(SHUTDOWN_WATCHDOG_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            logger.error("Shutdown did not complete within {} ms — forcing process termination",
                SHUTDOWN_WATCHDOG_MILLIS);
            Runtime.getRuntime().halt(1);
        }, "kortty-shutdown-watchdog");
        watchdog.setDaemon(true);
        watchdog.start();
    }

    /** Grace period for a clean shutdown before the watchdog force-halts the process. */
    private static final long SHUTDOWN_WATCHDOG_MILLIS = 25_000;

    /** Bounded wait for the badge executor to run the clearing emit queued by {@code close()}. */
    private static final long APP_BADGE_SHUTDOWN_MILLIS = 400L;

    /**
     * Flushes all persistent state and stops background services, each step guarded
     * independently so one failure cannot skip the rest. Idempotent via {@link #shuttingDown}.
     */
    private synchronized void performShutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        logger.info("Shutting down {}...", APP_NAME);
        // After an import that replaced master.key the in-memory stores belong to the old key:
        // writing them now would overwrite the restored files (and re-encrypt the restored
        // connections with the wrong key), so the restored files are left as they are.
        boolean saveStores = !restoredBackupAwaitsRestart;
        if (!saveStores) {
            logger.warn("Skipping the store saves: a restored backup with a different master key awaits the restart");
        }

        // A geometry save scheduled by a dialog that closed just now must land before halt(0).
        if (globalSettingsManager != null && saveStores) {
            globalSettingsManager.flushPendingSave();
        }

        // Quit and the last window sealed the session snapshot already; every other way out (the
        // force quit while scheduled jobs drain, the Dock) writes it here, before halt(0). The
        // coordinator writes nothing while a restored backup awaits the restart.
        if (sessionAutosave != null) {
            shutdownStep("seal session snapshot", sessionAutosave::sealOnShutdown);
        }

        // Save configuration
        try {
            if (saveStores && configManager != null && masterPasswordManager != null
                    && masterPasswordManager.getDerivedKey() != null) {
                configManager.save(masterPasswordManager.getDerivedKey());
            }
        } catch (Exception e) {
            logger.error("Failed to save configuration", e);
        }
        
        // Save remaining state and stop background services. Each step is
        // independent and individually guarded: Runtime.halt(0) (in shutdownAndExit)
        // skips the JVM shutdown hooks, so this is the only chance to flush state —
        // one manager failing must not skip the remaining saves/stops.
        if (controlApiServer != null) {
            // Runtime.halt(0) below skips every JVM shutdown hook, so this registered step is the
            // only place the socket and endpoint.json are ever unlinked. close() is bounded so the
            // shutdown watchdog cannot be tripped by a connection that refuses to die.
            shutdownStep("stop control API server", controlApiServer::close);
        }
        if (codingAgentUiBridge != null) {
            shutdownStep("stop coding agent UI bridge", codingAgentUiBridge::stop);
        }
        if (desktopNotifier != null) {
            shutdownStep("stop desktop notifier", desktopNotifier::close);
        }
        if (appBadgeService != null) {
            shutdownStep("stop app badge", () -> {
                appBadgeService.close();
                if (appBadgeExecutor != null) {
                    // close() only queues the clearing emit on this executor; shutdownNow() would
                    // throw it away or interrupt it mid-write, leaving the launcher counter behind.
                    // shutdown() lets the queue drain, bounded so the exit path never stalls.
                    appBadgeExecutor.shutdown();
                    try {
                        if (!appBadgeExecutor.awaitTermination(APP_BADGE_SHUTDOWN_MILLIS,
                                java.util.concurrent.TimeUnit.MILLISECONDS)) {
                            appBadgeExecutor.shutdownNow();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        appBadgeExecutor.shutdownNow();
                    }
                }
            });
        }
        if (codingAgentService != null) {
            shutdownStep("stop coding agent detection", codingAgentService::stop);
        }
        if (terminalHighlightService != null) {
            shutdownStep("stop keyword highlighting", terminalHighlightService::stop);
        }
        if (sessionJournalSummarizer != null) {
            shutdownStep("stop session journal summarizer", sessionJournalSummarizer::stop);
        }
        if (automationJournalRetention != null) {
            shutdownStep("stop automation journal retention", automationJournalRetention::stop);
        }
        if (sessionJournalScreenshotAnalyzer != null) {
            shutdownStep("stop session journal screenshot analyzer", sessionJournalScreenshotAnalyzer::stop);
        }
        if (sessionJournalHtmlRenderer != null) {
            shutdownStep("stop session journal HTML renderer", sessionJournalHtmlRenderer::stop);
        }
        if (gpgKeyManager != null && saveStores) {
            shutdownStep("save GPG keys", gpgKeyManager::save);
        }
        if (credentialManager != null && saveStores) {
            shutdownStep("save credentials", credentialManager::save);
        }
        if (sshKeyManager != null && saveStores) {
            shutdownStep("save SSH keys", sshKeyManager::save);
        }
        if (snippetManager != null && saveStores) {
            shutdownStep("save snippets", snippetManager::save);
        }
        if (snippetAnalysisStore != null && saveStores) {
            // After the snippets save (which can make a pending draft analysis persistable);
            // halt(0) skips shutdown hooks, so the queued writes must land here.
            shutdownStep("flush snippet analyses",
                () -> snippetAnalysisStore.flush(Duration.ofSeconds(2)));
        }
        if (snippetDraftStore != null) {
            shutdownStep("flush snippet drafts", () -> snippetDraftStore.flush(2_000));
        }
        if (snippetVariableManager != null && saveStores) {
            shutdownStep("save snippet variables", snippetVariableManager::save);
        }
        if (aiChatManager != null && saveStores) {
            shutdownStep("save AI chats", aiChatManager::save);
        }
        if (swarmChatManager != null && saveStores) {
            shutdownStep("save swarm chats", swarmChatManager::save);
        }
        if (globalSettingsManager != null && saveStores) {
            shutdownStep("save global settings", globalSettingsManager::save);
        }
        if (teamworkRecycleBinService != null) {
            shutdownStep("save teamwork recycle bin", teamworkRecycleBinService::save);
        }
        if (teamworkSyncService != null) {
            shutdownStep("stop teamwork sync", teamworkSyncService::stop);
        }
        if (jobWebhookSender != null) {
            // Before the scheduler's final save, so a failed delivery's journal entry is kept.
            shutdownStep("stop webhook sender", jobWebhookSender::shutdown);
        }
        if (jobSchedulerService != null) {
            shutdownStep("stop job scheduler", () -> jobSchedulerService.shutdownSchedulerThreads(saveStores));
        }
        // Runtime.halt(0) skips deleteOnExit: delete the local copies of remote files still open
        // in an external editor here.
        shutdownStep("delete remote-edit folders", de.kortty.core.remote.edit.RemoteEditTempDirs::deleteAllLive);
        shutdownStep("stop local knowledge-store coordination",
            de.kortty.rag.RagCoordinator::shutdownDefault);
        if (llamaRuntimeStatusSubscription != null) {
            shutdownStep("remove llama.cpp runtime update listener", llamaRuntimeStatusSubscription::close);
            llamaRuntimeStatusSubscription = null;
        }
        if (mlxRuntimeStatusSubscription != null) {
            shutdownStep("remove MLX runtime update listener", mlxRuntimeStatusSubscription::close);
            mlxRuntimeStatusSubscription = null;
        }
        shutdownStep("stop llama.cpp runtime update coordination",
            de.kortty.ai.runtimeupdate.LlamaRuntimeUpdateCoordinator::shutdownDefault);
        shutdownStep("stop MLX runtime update coordination",
            de.kortty.ai.mlx.MlxRuntimeUpdateCoordinator::shutdownDefault);
        // The application terminates with Runtime.halt(), so sidecar cleanup cannot rely on JVM
        // shutdown hooks. Stop every embedded llama.cpp and mlx-lm process explicitly before the
        // hard halt.
        shutdownStep("stop embedded llama.cpp runtimes",
            de.kortty.ai.llama.LlamaRuntimeManager::shutdownDefault);
        shutdownStep("stop embedded MLX runtimes",
            de.kortty.ai.mlx.MlxRuntimeManager::shutdownDefault);
        if (powerManagementCoordinator != null) {
            shutdownStep("release power-management assertions", powerManagementCoordinator::close);
            powerManagementCoordinator = null;
        }
        if (updateCheckService != null) {
            shutdownStep("stop update check", updateCheckService::stop);
            updateCheckService = null;
        }
        if (telemetryService != null) {
            trackUsageSnapshot("shutdown");
            shutdownStep("flush telemetry", () -> telemetryService.shutdown(Duration.ofSeconds(3)));
        }
        if (logMaintenanceExecutor != null) {
            shutdownStep("stop log maintenance", logMaintenanceExecutor::shutdownNow);
            logMaintenanceExecutor = null;
        }
        // LAST and fire-and-forget: a synchronous SystemTray removal from the FX thread
        // can deadlock against the AWT EDT (see MacMenuBarIcon.removeAsync) — and it
        // used to run FIRST, before any state was saved. Purely cosmetic before halt().
        shutdownStep("remove macOS menu-bar icon", de.kortty.ui.MacMenuBarIcon::removeAsync);

        logger.info("{} shutdown complete", APP_NAME);
    }

    /**
     * Runs one independent shutdown step, swallowing and logging any failure so the
     * remaining steps still run before {@link #shutdownAndExit()} hard-halts the JVM.
     */
    private void shutdownStep(String description, ShutdownAction action) {
        try {
            action.run();
        } catch (Exception e) {
            logger.error("Shutdown step failed: {}", description, e);
        }
    }

    /** A single, independent shutdown action that may throw; executed via {@link #shutdownStep}. */
    @FunctionalInterface
    private interface ShutdownAction {
        /** Performs the shutdown action. */
        void run() throws Exception;
    }

    /**
     * Set when the native-quit hook could not be installed: the keep-alive mode is
     * then disabled so a native quit (close-all-windows + implicit exit) still
     * terminates the app instead of stranding a headless process.
     */
    private volatile boolean macKeepAliveDisabled = false;

    /** True only for the packaged macOS app, where korTTY stays alive after the last window closes. */
    public boolean shouldKeepRunningAfterLastWindowClosed() {
        return !macKeepAliveDisabled && isMacOs() && isPackagedMacApplication();
    }
    
    /**
     * Called on the FX thread after the vault was unlocked mid-session (see
     * {@link de.kortty.ui.VaultUnlockSupport}). Restores the temporary SSH keys the locked start
     * could not decrypt, then lets every open window refresh what depends on the vault.
     */
    public void onVaultUnlocked() {
        logger.info("Master-password vault unlocked after startup");
        if (configManager != null && masterPasswordManager != null && masterPasswordManager.getDerivedKey() != null) {
            configManager.onVaultUnlocked(masterPasswordManager.getDerivedKey());
        }
        for (MainWindow window : new ArrayList<>(MainWindow.getOpenWindows())) {
            try {
                window.onVaultUnlocked();
            } catch (RuntimeException e) {
                logger.warn("A window could not refresh after the vault was unlocked", e);
            }
        }
    }

    private boolean handleMasterPassword(Stage ownerStage) {
        MasterPasswordDialog dialog = new MasterPasswordDialog(ownerStage, masterPasswordManager);
        boolean confirmed = dialog.showAndWait();
        if (confirmed && telemetryService != null) {
            // First-run setup: persist the consent decision made next to the password fields.
            dialog.getTelemetryConsentChoice().ifPresent(telemetryService::recordConsent);
        }
        return confirmed;
    }

    /**
     * Consolidated inventory snapshot for the anonymous usage statistics,
     * sent twice per session (startup + shutdown). Counts only — no content.
     */
    private void trackUsageSnapshot(String phase) {
        try {
            GlobalSettings settings = globalSettingsManager != null ? globalSettingsManager.getSettings() : null;
            if (settings == null) {
                return;
            }
            Map<String, Object> props = new LinkedHashMap<>();
            props.put("phase", phase);
            if (credentialManager != null) {
                props.put("credential_count", credentialManager.getAllCredentials().size());
            }
            if (gpgKeyManager != null) {
                props.put("gpg_key_count", gpgKeyManager.getAllKeys().size());
            }
            if (sshKeyManager != null) {
                props.put("ssh_key_count", sshKeyManager.getAllKeys().size());
            }
            if (snippetManager != null) {
                props.put("snippet_category_count", snippetManager.getAllCategories().size());
            }
            props.put("ai_enabled", settings.isAiFeaturesEnabled());
            props.put("ai_profile_count", settings.getAiProfiles().size());
            List<TeamworkSourceConfig> teamworkSources = settings.getTeamworkSources();
            int gitCount = 0;
            for (TeamworkSourceConfig source : teamworkSources) {
                if (source.getType() == TeamworkSourceType.GIT) {
                    gitCount++;
                }
            }
            props.put("teamwork_source_count", teamworkSources.size());
            props.put("teamwork_git_count", gitCount);
            props.put("teamwork_shared_file_count", teamworkSources.size() - gitCount);
            if (!teamworkSources.isEmpty()) {
                // Same effective-interval formula as TeamworkSyncService.scheduleSync().
                int intervalMinutes = teamworkSources.stream()
                    .filter(TeamworkSourceConfig::isEnabled)
                    .mapToInt(TeamworkSourceConfig::getCheckIntervalMinutes)
                    .filter(interval -> interval >= 1)
                    .min()
                    .orElse(settings.getTeamworkDefaultCheckIntervalMinutes());
                if (intervalMinutes < 1) {
                    intervalMinutes = 15;
                }
                props.put("teamwork_interval_min", intervalMinutes);
            }
            props.put("window_count", MainWindow.getOpenWindowCount());
            props.put("terminal_tab_count", MainWindow.getOpenTerminalTabCount());
            props.put("coding_agent_detection_enabled", settings.isCodingAgentDetectionEnabled());
            props.put("coding_agent_notifications_enabled", settings.isCodingAgentNotificationsEnabled());
            props.put("coding_agent_app_badge_enabled", settings.isCodingAgentAppBadgeEnabled());
            props.put("control_api_enabled", settings.isControlApiEnabled());
            if (codingAgentRegistry != null && Platform.isFxApplicationThread()) {
                props.put("coding_agent_count", codingAgentRegistry.entries().size());
            }
            de.kortty.telemetry.CodingAgentUsage.get().putSnapshotProps(props);
            Telemetry.track(TelemetryEvents.USAGE_SNAPSHOT, props);
        } catch (Exception e) {
            logger.debug("Usage snapshot failed: {}", e.toString());
        }
    }

    /** One event per configured AI profile, once per session (metric: which model is used). */
    private void trackAiProfileSnapshots() {
        try {
            GlobalSettings settings = globalSettingsManager != null ? globalSettingsManager.getSettings() : null;
            if (settings == null) {
                return;
            }
            List<de.kortty.model.AiProfile> profiles = settings.getAiProfiles();
            for (int i = 0; i < profiles.size(); i++) {
                Map<String, Object> props = new LinkedHashMap<>(TelemetryProps.aiProfileProps(profiles.get(i)));
                props.put("index", i);
                Telemetry.track(TelemetryEvents.AI_PROFILE_SNAPSHOT, props);
            }
        } catch (Exception e) {
            logger.debug("AI profile snapshot failed: {}", e.toString());
        }
    }

    public void applyLoggingSettings() {
        if (globalSettingsManager == null) {
            return;
        }
        GlobalSettings settings = globalSettingsManager.getSettings();
        try {
            LoggingConfiguration.applyRuntimeSettings(settings, getConfigDirectory());
            // applyRuntimeSettings resets the Logback context, which detaches every
            // programmatic appender — the telemetry error appender must be re-attached.
            if (telemetryService != null) {
                telemetryService.onLoggingReconfigured();
            }
            restartLogMaintenance(settings);
            logger.info(
                "Logging configured: directory={}, retentionDays={}",
                LoggingConfiguration.resolveLogDirectory(settings, getConfigDirectory()),
                settings.getLogRetentionDays());
        } catch (Exception e) {
            logger.warn("Failed to apply logging settings", e);
        }
    }

    private void restartLogMaintenance(GlobalSettings settings) {
        if (logMaintenanceExecutor != null) {
            logMaintenanceExecutor.shutdownNow();
        }
        Path logDirectory = LoggingConfiguration.resolveLogDirectory(settings, getConfigDirectory());
        int retentionDays = settings != null ? settings.getLogRetentionDays() : GlobalSettings.DEFAULT_LOG_RETENTION_DAYS;
        logMaintenanceExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "kortty-log-maintenance");
            thread.setDaemon(true);
            return thread;
        });
        logMaintenanceExecutor.scheduleWithFixedDelay(() -> {
            try {
                LoggingConfiguration.maintainLogDirectory(logDirectory, retentionDays);
            } catch (Exception e) {
                logger.debug("Log maintenance failed", e);
            }
        }, 1, 1, TimeUnit.HOURS);
    }

    private void startUpdateCheckService() {
        if (globalSettingsManager == null) {
            return;
        }
        if (updateCheckService != null) {
            updateCheckService.stop();
        }
        updateCheckService = new UpdateCheckService(
            globalSettingsManager,
            update -> Platform.runLater(() -> MainWindow.showAutomaticUpdateAvailable(update)));
        updateCheckService.start();
    }

    private void startLlamaRuntimeUpdateCoordinator() {
        if (globalSettingsManager == null || globalSettingsManager.getSettings() == null) {
            return;
        }
        if (!de.kortty.policy.PolicyManager.effective().runtimeDownloadsAllowed()) {
            logger.info("AI runtime downloads disabled by enterprise policy — update coordinators not started");
            return;
        }
        de.kortty.ai.runtimeupdate.LlamaRuntimeUpdateCoordinator coordinator =
            de.kortty.ai.runtimeupdate.LlamaRuntimeUpdateCoordinator.getDefault();
        if (llamaRuntimeStatusSubscription != null) {
            try {
                llamaRuntimeStatusSubscription.close();
            } catch (Exception e) {
                logger.debug("Could not replace llama.cpp runtime update listener", e);
            }
        }
        llamaRuntimeStatusSubscription = coordinator.addListener(update -> {
            if (update.state() == de.kortty.ai.runtimeupdate.LlamaRuntimeUpdateCoordinator.State.REVOKED
                && update.revokedRuntimeId() != null) {
                String notificationId = "revoked:" + update.revokedRuntimeId();
                if (notificationId.equals(lastNotifiedLlamaRuntimeId)) {
                    return;
                }
                lastNotifiedLlamaRuntimeId = notificationId;
                String replacementId = update.availablePackage() != null
                    ? update.availablePackage().runtimeId() : null;
                Platform.runLater(() -> MainWindow.showRuntimeRevoked(
                    update.revokedRuntimeId(), replacementId));
                return;
            }
            if (update.state() != de.kortty.ai.runtimeupdate.LlamaRuntimeUpdateCoordinator.State.UPDATE_AVAILABLE
                || update.availablePackage() == null) {
                return;
            }
            // Notify only users who actually run the local runtime: without an installed (and
            // not removed) llama.cpp installation the popup would advertise a feature that was
            // never opted into. The AI Manager still lists the available runtime for install.
            if (update.activeInstallation() == null) {
                return;
            }
            String runtimeId = update.availablePackage().runtimeId();
            if (runtimeId.equals(lastNotifiedLlamaRuntimeId)) {
                return;
            }
            lastNotifiedLlamaRuntimeId = runtimeId;
            Platform.runLater(() -> MainWindow.showRuntimeUpdateAvailable(runtimeId));
        });
        de.kortty.model.LlamaRuntimeUpdatePolicy policy =
            globalSettingsManager.getSettings().getLlamaRuntimeUpdatePolicy();
        coordinator.start(
            policy,
            globalSettingsManager.getSettings().getPreferredLlamaRuntimeBackend());
        startMlxRuntimeUpdateCoordinator(policy);
    }

    /** Applies the same update policy to the embedded MLX runtime (Apple Silicon only). */
    private void startMlxRuntimeUpdateCoordinator(de.kortty.model.LlamaRuntimeUpdatePolicy policy) {
        if (!de.kortty.ai.mlx.MlxPlatform.isSupported()) {
            return;
        }
        de.kortty.ai.mlx.MlxRuntimeUpdateCoordinator coordinator =
            de.kortty.ai.mlx.MlxRuntimeUpdateCoordinator.getDefault();
        if (mlxRuntimeStatusSubscription != null) {
            try {
                mlxRuntimeStatusSubscription.close();
            } catch (Exception e) {
                logger.debug("Could not replace MLX runtime update listener", e);
            }
        }
        mlxRuntimeStatusSubscription = coordinator.addListener(update -> {
            if (update.state() == de.kortty.ai.mlx.MlxRuntimeUpdateCoordinator.State.REVOKED
                && update.revokedRuntimeId() != null) {
                String notificationId = "revoked:" + update.revokedRuntimeId();
                if (notificationId.equals(lastNotifiedMlxRuntimeId)) {
                    return;
                }
                lastNotifiedMlxRuntimeId = notificationId;
                String replacementId = update.availablePackage() != null
                    ? update.availablePackage().runtimeId() : null;
                Platform.runLater(() -> MainWindow.showRuntimeRevoked(
                    update.revokedRuntimeId(), replacementId));
                return;
            }
            if (update.state() != de.kortty.ai.mlx.MlxRuntimeUpdateCoordinator.State.UPDATE_AVAILABLE
                || update.availablePackage() == null
                || update.activeInstallation() == null) {
                return;
            }
            String runtimeId = update.availablePackage().runtimeId();
            if (runtimeId.equals(lastNotifiedMlxRuntimeId)) {
                return;
            }
            lastNotifiedMlxRuntimeId = runtimeId;
            Platform.runLater(() -> MainWindow.showRuntimeUpdateAvailable(runtimeId));
        });
        coordinator.start(policy);
    }

    public void restartUpdateCheckService() {
        if (updateCheckService == null) {
            startUpdateCheckService();
            return;
        }
        updateCheckService.restart();
    }
    
    private void registerJMXBean() {
        try {
            MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName("de.kortty:type=SSHClient");
            SSHClientMonitor monitor = new SSHClientMonitor(ActiveConnectionRegistry.shared());
            mbs.registerMBean(monitor, name);
            logger.info("JMX MBean registered: {}", name);
        } catch (Exception e) {
            logger.warn("Failed to register JMX MBean", e);
        }
    }
    
    private void showErrorAndExit(String message) {
        try {
            String title = de.kortty.ui.I18n.get("error.title");
            Alert alert = new Alert(Alert.AlertType.ERROR);
            alert.setTitle(title);
            alert.setHeaderText(null);
            alert.setContentText(message != null && message.length() > 2000 ? message.substring(0, 2000) + "…" : message);
            alert.showAndWait();
        } catch (Exception e) {
            logger.error("Could not show error dialog", e);
        } finally {
            Platform.exit();
        }
    }

    /** On the packaged macOS app, disables JavaFX implicit exit so korTTY keeps running (JobScheduler) after the last window closes. */
    private void prepareMacApplicationLifecycle() {
        if (!shouldKeepRunningAfterLastWindowClosed()) {
            return;
        }

        // The keep-alive design requires intercepting the NATIVE macOS quit first:
        // Glass owns the NSApplication delegate and translates every native quit
        // (Cmd+Q via the apple menu, app menu "Quit korTTY", the system Dock Quit,
        // logout) into mere per-window close requests — which the keep-alive branch
        // swallows (windows closed, process lingering headless; once headless the
        // native quit is a complete no-op). MacGlassQuitHook reroutes Glass's
        // handleQuitAction into MainWindow.requestApplicationQuit(), the same path
        // as File->Quit, ending in Runtime.getRuntime().halt(0) — see
        // shutdownAndExit(). halt() (not System.exit) is deliberate: it skips the
        // AWT/JavaFX shutdown hooks that otherwise hang the macOS Dock-stuck quit.
        // The AWT Desktop quit handler is NOT an alternative: on JavaFX 21.0.2+
        // (JDK-8332656) Glass's delegate never forwards the quit to AWT.
        if (!de.kortty.ui.MacGlassQuitHook.install()) {
            // Without the hook a native quit must keep working: leave implicit exit
            // ON so closing the windows exits the toolkit -> stop() -> shutdownAndExit().
            // Quittability wins over the background keep-alive.
            macKeepAliveDisabled = true;
            logger.warn("macOS keep-alive disabled: native-quit hook unavailable, keeping JavaFX implicit exit");
            return;
        }

        // Keep the packaged macOS app alive after the last window is closed so the
        // JobScheduler keeps running scheduled background jobs.
        Platform.setImplicitExit(false);
        logger.info("Configured JavaFX implicit exit to keep the packaged macOS app alive after the last window closes (JobScheduler keeps running)");
    }

    /** Registers the macOS Desktop reopen handler (re-show a window when the Dock icon is clicked); deliberately registers no quit handler. */
    private void registerMacDesktopHandlers() {
        if (!shouldKeepRunningAfterLastWindowClosed() || macDesktopHandlersRegistered) {
            return;
        }

        if (!Desktop.isDesktopSupported()) {
            logger.info("AWT Desktop integration is not supported on this platform");
            macDesktopHandlersRegistered = true;
            return;
        }

        try {
            Desktop desktop = Desktop.getDesktop();
            logger.info("Registering macOS Desktop handlers");
            desktop.addAppEventListener(new AppForegroundListener() {
                @Override
                public void appRaisedToForeground(java.awt.desktop.AppForegroundEvent event) {
                    logger.info("Received macOS Desktop foreground event");
                    Platform.runLater(KorTTYApplication.this::reopenWindowIfNeeded);
                }

                @Override
                public void appMovedToBackground(java.awt.desktop.AppForegroundEvent event) {
                    // No-op.
                }
            });
            desktop.addAppEventListener((AppReopenedListener) event ->
                Platform.runLater(() -> {
                    logger.info("Received macOS Desktop reopen event");
                    reopenWindowIfNeeded();
                })
            );
            // NOTE: we deliberately do NOT register an AWT Desktop quit handler.
            // On macOS, JavaFX Glass owns the NSApplication delegate; if an eawt
            // quit handler is also registered, Glass *defers* the system Quit to it,
            // but macOS still calls Glass's delegate — so the Quit falls through the
            // gap and the app cannot be quit (the v2.2.2 Dock-stuck bug). With NO eawt
            // quit handler, Glass handles Cmd+Q / "Quit korTTY" itself: it fires each
            // window's close request (so confirmClose() still runs). Implicit exit is
            // disabled for the packaged app (see prepareMacApplicationLifecycle()), so
            // the quit paths reach shutdownAndExit() directly (-> Runtime.halt(0))
            // rather than relying on Platform.exit() reaching stop().
            macDesktopHandlersRegistered = true;
        } catch (UnsupportedOperationException | SecurityException e) {
            logger.warn("Could not configure macOS application lifecycle integration", e);
        }
    }

    private void reopenWindowIfNeeded() {
        if (MainWindow.hasOpenWindows()) {
            return;
        }

        logger.info("macOS app reactivated without open windows, opening a new main window");
        MainWindow.reopenOrCreateWindow();
    }

    private boolean isMacOs() {
        return System.getProperty("os.name", "").toLowerCase().contains("mac");
    }

    private boolean isPackagedMacApplication() {
        if (packagedMacApp != null) {
            return packagedMacApp;
        }

        String jpackageAppPath = System.getProperty("jpackage.app-path");
        packagedMacApp = jpackageAppPath != null && !jpackageAppPath.isBlank();

        if (isMacOs()) {
            logger.info("macOS packaged app launcher detected: {}", packagedMacApp);
        }

        return packagedMacApp;
    }
    
    public static Path getConfigDirectory() {
        String userHome = System.getProperty("user.home");
        return Path.of(userHome, ".kortty");
    }

    /**
     * Keeps {@code ~/.kortty} at {@code rwx------}: it holds the connection list, the credential
     * store and {@code master.key}. Only a directory the current user owns is changed, and a
     * failure never stops the startup.
     */
    private static void restrictConfigDirectoryToOwner(Path configDir) {
        try {
            if (de.kortty.core.AtomicFileWriter.restrictToOwner(configDir)) {
                logger.info("Restricted the configuration directory {} to its owner (rwx------)", configDir);
            }
        } catch (Exception e) {
            logger.warn("Could not restrict the configuration directory {} to its owner", configDir, e);
        }
    }

    /**
     * Tells the user which data files could not be read at startup: the ones moved aside as
     * {@code *.corrupt-<timestamp>} and the ones korTTY left in place and will not save over.
     */
    private void showStoreLoadFailures(MainWindow mainWindow) {
        Path configDir = getConfigDirectory();
        List<Path> movedAside = new java.util.ArrayList<>();
        List<Path> blocked = new java.util.ArrayList<>();
        java.util.Optional<GlobalSettingsManager.LoadRecovery> settingsRecovery = java.util.Optional.empty();
        if (globalSettingsManager != null) {
            settingsRecovery = globalSettingsManager.getLoadRecovery();
            settingsRecovery.filter(recovery -> recovery.backup() == null
                    && recovery.outcome() == GlobalSettingsManager.LoadRecovery.Outcome.RESET)
                .ifPresent(recovery -> blocked.add(configDir.resolve(GlobalSettingsManager.SETTINGS_FILE)));
        }
        if (configManager != null) {
            collectStoreLoadFailure(configManager.getLoadFailureBackup(), configManager.isSaveBlocked(),
                configDir.resolve(de.kortty.persistence.XMLConnectionRepository.CONNECTIONS_FILE), movedAside, blocked);
        }
        if (credentialManager != null) {
            collectStoreLoadFailure(credentialManager.getLoadFailureBackup(), credentialManager.isSaveBlocked(),
                configDir.resolve(CredentialManager.CREDENTIALS_FILE), movedAside, blocked);
        }
        if (sshKeyManager != null) {
            collectStoreLoadFailure(sshKeyManager.getLoadFailureBackup(), sshKeyManager.isSaveBlocked(),
                configDir.resolve(SSHKeyManager.SSH_KEYS_FILE), movedAside, blocked);
        }
        if (gpgKeyManager != null) {
            collectStoreLoadFailure(gpgKeyManager.getLoadFailureBackup(), gpgKeyManager.isSaveBlocked(),
                configDir.resolve(GPGKeyManager.GPG_KEYS_FILE), movedAside, blocked);
        }
        if (environmentManager != null) {
            collectStoreLoadFailure(environmentManager.getLoadFailureBackup(), environmentManager.isSaveBlocked(),
                configDir.resolve(EnvironmentManager.ENVIRONMENTS_FILE), movedAside, blocked);
        }
        if (themeManager != null) {
            collectStoreLoadFailure(themeManager.getLoadFailureBackup(), themeManager.isSaveBlocked(),
                configDir.resolve(ThemeManager.THEMES_FILE), movedAside, blocked);
        }
        if (jobSchedulerService != null) {
            de.kortty.jobscheduler.JobSchedulerRepository schedulerRepository = jobSchedulerService.getRepository();
            collectStoreLoadFailure(schedulerRepository.getLoadFailureBackup(), schedulerRepository.isSaveBlocked(),
                configDir.resolve(de.kortty.jobscheduler.JobSchedulerRepository.FILE_NAME), movedAside, blocked);
        }
        GlobalSettingsManager.LoadRecovery settingsNotice = settingsRecovery
            .filter(recovery -> recovery.backup() != null).orElse(null);
        if (!movedAside.isEmpty() || !blocked.isEmpty() || settingsNotice != null) {
            mainWindow.showStoreLoadFailureNotice(movedAside, blocked, settingsNotice);
        }
    }

    private static void collectStoreLoadFailure(java.util.Optional<Path> backup, boolean saveBlocked, Path file,
                                                List<Path> movedAside, List<Path> blocked) {
        backup.ifPresent(movedAside::add);
        if (saveBlocked) {
            blocked.add(file);
        }
    }
    
    public ConfigurationManager getConfigManager() {
        return configManager;
    }
    
    public MasterPasswordManager getMasterPasswordManager() {
        return masterPasswordManager;
    }
    
    public static String getAppName() {
        return APP_NAME;
    }
    
    public static String getAppVersion() {
        return APP_VERSION;
    }
    
    public GPGKeyManager getGpgKeyManager() {
        return gpgKeyManager;
    }
    
    public CredentialManager getCredentialManager() {
        return credentialManager;
    }

    public EnvironmentManager getEnvironmentManager() {
        return environmentManager;
    }
    
    public SSHKeyManager getSSHKeyManager() {
        return sshKeyManager;
    }
    
    /** The store of persisted snippet analyses; see {@link de.kortty.core.SnippetAnalysisStore#shared()}. */
    public de.kortty.core.SnippetAnalysisStore getSnippetAnalysisStore() {
        return snippetAnalysisStore;
    }

    public SnippetManager getSnippetManager() {
        return snippetManager;
    }
    
    public GlobalSettingsManager getGlobalSettingsManager() {
        return globalSettingsManager;
    }

    public de.kortty.policy.PolicyManager getPolicyManager() {
        return policyManager;
    }
    
    public ThemeManager getThemeManager() {
        return themeManager;
    }

    public TerminalEffectPluginManager getTerminalEffectPluginManager() {
        return terminalEffectPluginManager;
    }

    /** Coding-agent detection for local shell panes; null before {@code init()} (e.g. in unit tests). */
    public CodingAgentService getCodingAgentService() {
        return codingAgentService;
    }

    /** Keyword highlighting of terminal output; null before {@code init()} (e.g. in unit tests). */
    public de.kortty.core.highlight.TerminalHighlightService getTerminalHighlightService() {
        return terminalHighlightService;
    }

    /**
     * Builds the Stage-2 coding-agent UI services on top of the detection service: the FX-thread
     * registry, the desktop badge and notifier with their bridge to the windows, the notification
     * policy, the verbs and the cross-window navigator. The bridge implements every port the
     * services need but is constructed last, so the badge and the coordinator reach it through
     * forwarding adapters over a holder.
     */
    private void initCodingAgentUiServices() {
        try {
            codingAgentRegistry = new CodingAgentRegistry(FocusOracle.NEVER, System::currentTimeMillis,
                Platform::isFxApplicationThread);
            codingAgentService.addListener(codingAgentRegistry::onEvent);
            codingAgentRegistry.addListener(de.kortty.telemetry.CodingAgentUsage.get());
            java.util.concurrent.atomic.AtomicReference<CodingAgentUiBridge> bridgeRef =
                new java.util.concurrent.atomic.AtomicReference<>();
            PlatformProbe probe = PlatformProbe.fromSystem();
            desktopNotifier = DesktopNotifier.createDefault(probe);
            appBadgeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor(runnable -> {
                Thread thread = new Thread(runnable, "kortty-app-badge");
                thread.setDaemon(true);
                return thread;
            });
            StageIconPresenter stageIcons = new StageIconPresenter() {
                @Override
                public void applyBadgedIcon(javafx.scene.image.Image icon) {
                    CodingAgentUiBridge bridge = bridgeRef.get();
                    if (bridge != null) {
                        bridge.applyBadgedIcon(icon);
                    }
                }

                @Override
                public void restorePlainIcon() {
                    CodingAgentUiBridge bridge = bridgeRef.get();
                    if (bridge != null) {
                        bridge.restorePlainIcon();
                    }
                }
            };
            TitleBadgePresenter titles = count -> {
                CodingAgentUiBridge bridge = bridgeRef.get();
                if (bridge != null) {
                    bridge.applyTitleCount(count);
                }
            };
            appBadgeService = new AppBadgeService(
                AppBadgeBackends.createDefault(probe, stageIcons, appBadgeExecutor),
                titles,
                () -> {
                    GlobalSettings current = globalSettingsManager.getSettings();
                    return current == null || current.isCodingAgentAppBadgeEnabled();
                });
            FocusOracle focus = new FocusOracle() {
                @Override
                public boolean isSeen(de.kortty.codingagent.PaneRef pane) {
                    CodingAgentUiBridge bridge = bridgeRef.get();
                    return bridge != null && bridge.isSeen(pane);
                }

                @Override
                public boolean isAnyWindowFocused() {
                    CodingAgentUiBridge bridge = bridgeRef.get();
                    return bridge != null && bridge.isAnyWindowFocused();
                }
            };
            CodingAgentNotificationCoordinator.Sink sink = (entry, state, anyWindowFocused) -> {
                CodingAgentUiBridge bridge = bridgeRef.get();
                if (bridge != null) {
                    bridge.notificationSink().notify(entry, state, anyWindowFocused);
                }
            };
            codingAgentNotificationCoordinator = new CodingAgentNotificationCoordinator(
                codingAgentRegistry,
                focus,
                () -> {
                    GlobalSettings current = globalSettingsManager.getSettings();
                    return current == null || current.isCodingAgentNotificationsEnabled();
                },
                sink,
                System::currentTimeMillis);
            codingAgentUiBridge = new CodingAgentUiBridge(codingAgentRegistry, appBadgeService,
                codingAgentNotificationCoordinator, desktopNotifier, MainWindow::getOpenWindows,
                globalSettingsManager::getSettings);
            bridgeRef.set(codingAgentUiBridge);
            codingAgentActions = new CodingAgentActions(codingAgentRegistry, codingAgentUiBridge,
                CodingAgentActions.AuditSink.LOGGING);
            codingAgentNavigator = new CodingAgentNavigator(codingAgentRegistry, codingAgentUiBridge,
                codingAgentUiBridge);
            codingAgentRegistry.setFocusOracle(codingAgentUiBridge);
        } catch (RuntimeException e) {
            logger.warn("Coding-agent UI services could not be initialised: {}", e.toString());
        }
    }

    /**
     * Builds the local control API on top of the coding-agent services: the window bridge, a second
     * {@code CodingAgentActions} and the server.
     *
     * <p>Nothing listens yet. {@link de.kortty.control.ControlApiServer#applyEnabledState()} opens the
     * socket, and only once both gate legs say yes — the default-off setting and the
     * {@code control-api} policy feature.
     */
    private void initControlApi(Path configDir) {
        if (codingAgentRegistry == null || codingAgentUiBridge == null) {
            logger.debug("Control API not wired: the coding-agent UI services are unavailable");
            return;
        }
        try {
            controlApiUiBridge = new de.kortty.ui.ControlApiUiBridge(MainWindow::getOpenWindows,
                codingAgentRegistry, codingAgentUiBridge, System::currentTimeMillis);
            // The wiring builds a SECOND CodingAgentActions over this same registry and UI bridge,
            // differing only in its audit sink: without it every action the Coding Agents panel
            // performs would be logged as if a script had made it, and no agent.* write would raise
            // the takeover notification.
            controlApiServer = de.kortty.control.ControlApiWiring.create(configDir,
                PlatformProbe.fromSystem(), controlApiUiBridge, controlApiUiBridge, codingAgentRegistry,
                codingAgentUiBridge, desktopNotifier,
                () -> de.kortty.control.ControlApiGate.verdict(
                    globalSettingsManager == null ? null : globalSettingsManager.getSettings(),
                    de.kortty.policy.PolicyManager.effective()),
                () -> de.kortty.control.McpGate.verdict(
                    globalSettingsManager == null ? null : globalSettingsManager.getSettings(),
                    de.kortty.policy.PolicyManager.effective()),
                new de.kortty.ui.McpWriteConsentDialog(),
                APP_VERSION);
        } catch (RuntimeException e) {
            logger.warn("Control API could not be initialised: {}", e.toString());
            controlApiServer = null;
            controlApiUiBridge = null;
        }
    }

    /**
     * Opens the control-API listener when the gate allows it, and subscribes its event bus to the
     * coding-agent registry; FX thread, once the first window exists.
     */
    private void startControlApi() {
        if (controlApiServer == null) {
            return;
        }
        try {
            de.kortty.control.ControlEventBus events =
                de.kortty.control.ControlApiWiring.eventBus(controlApiServer);
            if (events != null && codingAgentRegistry != null && controlApiUiBridge != null) {
                codingAgentRegistry.addListener(events.registryListener(controlApiUiBridge));
            }
            controlApiServer.applyEnabledState();
        } catch (RuntimeException e) {
            logger.warn("Control API could not be started: {}", e.toString());
        }
    }

    /** Starts the coding-agent UI bridge once the first window exists; FX thread. */
    private void startCodingAgentUi() {
        if (codingAgentUiBridge == null) {
            return;
        }
        try {
            codingAgentUiBridge.start();
            if (codingAgentService != null && codingAgentRegistry != null) {
                codingAgentRegistry.seed(codingAgentService.snapshot());
            }
            if (appBadgeService != null) {
                // Clear a badge left over by a previous session that ended without a clean exit.
                appBadgeService.update(0, false);
            }
        } catch (RuntimeException e) {
            logger.warn("Coding-agent UI could not be started: {}", e.toString());
        }
    }

    /** The FX-thread registry of detected coding agents; null before {@code init()}. */
    public CodingAgentRegistry getCodingAgentRegistry() {
        return codingAgentRegistry;
    }

    /** The verbs (send keys, prompt, explain, rename) against a coding agent; null before {@code init()}. */
    public CodingAgentActions getCodingAgentActions() {
        return codingAgentActions;
    }

    /** Cross-window "next blocked agent" navigation; null before {@code init()}. */
    public CodingAgentNavigator getCodingAgentNavigator() {
        return codingAgentNavigator;
    }

    /** The app-icon badge service (FX thread); null before {@code init()}. */
    public AppBadgeService getAppBadgeService() {
        return appBadgeService;
    }

    /** The desktop notifier; null before {@code init()}. */
    public DesktopNotifier getDesktopNotifier() {
        return desktopNotifier;
    }

    /** The bridge between the coding-agent services and the open windows; null before {@code init()}. */
    public CodingAgentUiBridge getCodingAgentUiBridge() {
        return codingAgentUiBridge;
    }

    /**
     * The local control-API listener; null before {@code init()} and whenever the coding-agent
     * services it stands on could not be built. Built but not listening until the gate says yes.
     */
    public de.kortty.control.ControlApiServer getControlApiServer() {
        return controlApiServer;
    }

    /** The control API's view of the windows; null before {@code init()}. */
    public de.kortty.ui.ControlApiUiBridge getControlApiUiBridge() {
        return controlApiUiBridge;
    }

    public AiChatManager getAiChatManager() {
        return aiChatManager;
    }

    public SwarmChatManager getSwarmChatManager() {
        return swarmChatManager;
    }

    /** Books AI token usage against profile quotas; see {@link de.kortty.core.AiUsageRecorder}. */
    public de.kortty.core.SettingsAiUsageRecorder getAiUsageRecorder() {
        return aiUsageRecorder;
    }

    /** Deletes expired automation run journals; see {@link de.kortty.core.AutomationJournalRetention}. */
    public de.kortty.core.AutomationJournalRetention getAutomationJournalRetention() {
        return automationJournalRetention;
    }

    /** The current "session journal per run" settings of an automation source, or null when it is gone. */
    private de.kortty.model.AutomationJournalConfig automationJournalConfigOf(
            de.kortty.model.SessionJournalSourceKind kind, String sourceId) {
        if (kind == de.kortty.model.SessionJournalSourceKind.SWARM) {
            GlobalSettings settings = globalSettingsManager != null ? globalSettingsManager.getSettings() : null;
            return settings != null ? settings.getSwarmSessionJournal() : null;
        }
        if (kind == de.kortty.model.SessionJournalSourceKind.JOB && jobSchedulerService != null && sourceId != null) {
            return jobSchedulerService.findJob(sourceId)
                .map(de.kortty.jobscheduler.ScheduledJob::getSessionJournal)
                .orElse(null);
        }
        return null;
    }

    public de.kortty.core.SessionJournalService getSessionJournalService() {
        return sessionJournalService;
    }

    public de.kortty.core.SessionJournalSummarizer getSessionJournalSummarizer() {
        return sessionJournalSummarizer;
    }

    public de.kortty.core.SessionJournalScreenshotAnalyzer getSessionJournalScreenshotAnalyzer() {
        return sessionJournalScreenshotAnalyzer;
    }

    public de.kortty.core.SessionJournalHtmlRenderer getSessionJournalHtmlRenderer() {
        return sessionJournalHtmlRenderer;
    }
    
    public BackupManager getBackupManager() {
        return backupManager;
    }
    
    /**
     * Marks that a backup import wrote a different {@code master.key}. The connections,
     * credentials and other stores in memory were loaded with the old key; saving them at
     * shutdown would re-encrypt the restored connections with that key and overwrite the other
     * restored files, so {@link #performShutdown()} skips those saves and the next start loads
     * the restored files with the backup's master password.
     */
    public void markRestoredBackupAwaitsRestart() {
        restoredBackupAwaitsRestart = true;
        logger.warn("A restored backup replaced the master key; korTTY will not save its stores until it restarts");
    }

    /** Whether {@link #markRestoredBackupAwaitsRestart()} was called in this run. */
    public boolean isRestoredBackupAwaitingRestart() {
        return restoredBackupAwaitsRestart;
    }
    
    public TeamworkSyncService getTeamworkSyncService() {
        return teamworkSyncService;
    }
    
    public TeamworkRecycleBinService getTeamworkRecycleBinService() {
        return teamworkRecycleBinService;
    }
    
    public SnippetVariableManager getSnippetVariableManager() {
        return snippetVariableManager;
    }

    /** Delivers job-run webhooks and the target manager's test sends; {@code null} without a scheduler. */
    public de.kortty.jobscheduler.WebhookSender getJobWebhookSender() {
        return jobWebhookSender;
    }

    public JobSchedulerService getJobSchedulerService() {
        return jobSchedulerService;
    }

    public PowerManagementCoordinator getPowerManagementCoordinator() {
        return powerManagementCoordinator;
    }

    private void applyPersistedPowerManagementSetting(GlobalSettings settings) {
        if (settings == null || powerManagementCoordinator == null || !settings.isPreventSystemSleep()) {
            return;
        }
        if (!powerManagementCoordinator.setManualSleepPrevention(true)
                && powerManagementCoordinator.supportsSystemSleepPrevention()) {
            settings.setPreventSystemSleep(false);
            logger.warn("Persisted system-sleep prevention could not be activated; resetting the setting");
            try {
                globalSettingsManager.save();
            } catch (Exception e) {
                logger.warn("Could not persist reset power-management setting", e);
            }
        }
    }

    private void syncSchedulerPowerState() {
        if (powerManagementCoordinator == null || jobSchedulerService == null) {
            return;
        }
        powerManagementCoordinator.updateSchedulerState(
            jobSchedulerService.hasEnabledScheduledJobs(),
            jobSchedulerService.hasActiveJobs());
    }

    public UpdateCheckService getUpdateCheckService() {
        return updateCheckService;
    }

    public TelemetryService getTelemetryService() {
        return telemetryService;
    }
}
