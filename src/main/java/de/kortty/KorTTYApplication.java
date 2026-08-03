package de.kortty;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.SessionManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.EnvironmentManager;
import de.kortty.core.SSHKeyManager;
import de.kortty.core.SnippetManager;
import de.kortty.core.SnippetVariableManager;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.LegacyDiagramCacheCleanup;
import de.kortty.core.LoggingConfiguration;
import de.kortty.core.ThemeManager;
import de.kortty.core.TerminalEffectPluginManager;
import de.kortty.core.BackupManager;
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
    private static final String APP_VERSION = "2.8.0";
    
    private static KorTTYApplication instance;
    private AutoCloseable llamaRuntimeStatusSubscription;
    private volatile String lastNotifiedLlamaRuntimeId;
    private AutoCloseable mlxRuntimeStatusSubscription;
    private volatile String lastNotifiedMlxRuntimeId;
    
    private ConfigurationManager configManager;
    private SessionManager sessionManager;
    private MasterPasswordManager masterPasswordManager;
    private GPGKeyManager gpgKeyManager;
    private CredentialManager credentialManager;
    private EnvironmentManager environmentManager;
    private SSHKeyManager sshKeyManager;
    private SnippetManager snippetManager;
    private SnippetVariableManager snippetVariableManager;
    private GlobalSettingsManager globalSettingsManager;
    private ThemeManager themeManager;
    private TerminalEffectPluginManager terminalEffectPluginManager;
    private BackupManager backupManager;
    private AiChatManager aiChatManager;
    private SwarmChatManager swarmChatManager;
    private de.kortty.core.SessionJournalService sessionJournalService;
    private de.kortty.core.SessionJournalSummarizer sessionJournalSummarizer;
    private de.kortty.core.SessionJournalHtmlRenderer sessionJournalHtmlRenderer;
    private TeamworkSyncService teamworkSyncService;
    private TeamworkRecycleBinService teamworkRecycleBinService;
    private JobSchedulerService jobSchedulerService;
    private UpdateCheckService updateCheckService;
    private TelemetryService telemetryService;
    private ScheduledExecutorService logMaintenanceExecutor;
    private PowerManagementCoordinator powerManagementCoordinator;
    private Runnable schedulerPowerStateListener;
    private boolean macDesktopHandlersRegistered = false;
    private Boolean packagedMacApp;
    private volatile boolean shuttingDown = false;
    private de.kortty.policy.PolicyManager policyManager;
    
    public static void main(String[] args) {
        // Admin console mode: encrypt a sensitive policy-file value (e.g. an AI-profile API key)
        // into the kortty-enc:v1: envelope, without starting JavaFX.
        if (args.length > 0 && "--encrypt-policy-value".equals(args[0])) {
            runEncryptPolicyValue(args);
            return;
        }
        logger.info("Starting {} v{}", APP_NAME, APP_VERSION);
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
        instance = this;

        // Load the enterprise policy FIRST — the settings managers constructed below must see the
        // clamp before their first load, so a policy-managed value can never leak through.
        policyManager = de.kortty.policy.PolicyManager.initialize();

        // Remove the retired diagram renderer's app-owned download cache and abandoned work
        // directories before loading persisted application data. Cleanup is deliberately
        // best-effort so a locked or read-only legacy file can never prevent korTTY from starting.
        LegacyDiagramCacheCleanup.cleanupAtStartup();
        
        // Install global exception handler to suppress SithTermFX bug
        installGlobalExceptionHandler();
        
        // Initialize configuration directory
        Path configDir = getConfigDirectory();
        if (!Files.exists(configDir)) {
            Files.createDirectories(configDir);
            logger.info("Created configuration directory: {}", configDir);
        }
        
        // Initialize managers
        configManager = new ConfigurationManager(configDir);
        sessionManager = new SessionManager();
        masterPasswordManager = new MasterPasswordManager(configDir);
        gpgKeyManager = new GPGKeyManager(configDir);
        credentialManager = new CredentialManager(configDir);
        environmentManager = new EnvironmentManager(configDir);
        environmentManager.load();
        sshKeyManager = new SSHKeyManager(configDir);
        snippetManager = new SnippetManager(configDir);
        snippetVariableManager = new SnippetVariableManager(configDir);
        globalSettingsManager = new GlobalSettingsManager(configDir);
        globalSettingsManager.setPolicyClamp(
            new de.kortty.policy.PolicyClamp(policyManager.getEffective()));
        powerManagementCoordinator = PowerManagementCoordinator.createDefault();
        themeManager = new ThemeManager(configDir);
        terminalEffectPluginManager = new TerminalEffectPluginManager(configDir);
        aiChatManager = new AiChatManager(configDir);
        swarmChatManager = new SwarmChatManager(configDir);
        sessionJournalService = new de.kortty.core.SessionJournalService();
        sessionJournalSummarizer = new de.kortty.core.SessionJournalSummarizer(sessionJournalService);
        sessionJournalHtmlRenderer = new de.kortty.core.SessionJournalHtmlRenderer(sessionJournalService);
        sessionJournalHtmlRenderer.attachToServiceChanges();
        // A regenerated page keeps the font size the user set with the page's A-/A+ buttons.
        sessionJournalHtmlRenderer.setFontScaleSupplier(() -> {
            GlobalSettings journalSettings =
                globalSettingsManager != null ? globalSettingsManager.getSettings() : null;
            return journalSettings != null ? journalSettings.getSessionJournalFontScalePercent() : 100;
        });
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
                themeManager.load();
                
                // Initialize language manager EARLY with settings, before any UI is created
                // This ensures the correct language is used from the start
                de.kortty.core.LanguageManager.getInstance().initialize(globalSettingsManager.getSettings());
                applyLoggingSettings();
            } catch (Exception e) {
                logger.warn("Failed to load global settings, using defaults", e);
            }
            
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
                // Password is set but not required on startup
                // We still need the derived key for decryption, but we can't get it without the password
                // So we'll skip the dialog and try to proceed - if decryption fails later,
                // the user will need to enter the password when needed
                logger.info("Master password required on startup is disabled, skipping dialog");
                // Note: We can't decrypt credentials/keys without the password, so those features
                // will require password entry when first used
            }
            
            // Load configuration
            configManager.load(masterPasswordManager.getDerivedKey());
            
            // Load GPG keys, credentials, and SSH keys
            try {
                gpgKeyManager.load();
                credentialManager.load();
                sshKeyManager.load();
                snippetManager.load();
                snippetVariableManager.load();
                aiChatManager.load();
                swarmChatManager.load();
                // Reload global settings to ensure we have the latest version
                // Note: This reload should preserve the language setting from the file
                globalSettingsManager.load();
                themeManager.load();
                
                // Re-initialize language manager with the loaded settings
                // This ensures the language from the saved settings is applied
                GlobalSettings loadedSettings = globalSettingsManager.getSettings();
                logger.info("Re-initializing language manager with language: '{}'", loadedSettings.getLanguage());
                de.kortty.core.LanguageManager.getInstance().initialize(loadedSettings);
                applyLoggingSettings();
                applyPersistedPowerManagementSetting(loadedSettings);

                // Sync the bundled AI skill catalog into the settings (add new, auto-update
                // unmodified built-ins). Must never prevent startup.
                try {
                    de.kortty.core.BuiltinAiSkillProvisioner.provision(globalSettingsManager);
                } catch (Exception e) {
                    logger.warn("Failed to provision built-in AI skills", e);
                }


                // Sync ConfigurationManager with persisted terminal settings
                // so that all components reading from configManager see the saved values
                ConnectionSettings savedTermSettings = loadedSettings.getDefaultTerminalSettings();
                if (savedTermSettings != null) {
                    configManager.setGlobalSettings(new ConnectionSettings(savedTermSettings));
                }
                
                // Initialize BackupManager after settings are loaded
                backupManager = new BackupManager(getConfigDirectory(), globalSettingsManager.getSettings());
                jobSchedulerService = new JobSchedulerService(this, getConfigDirectory());
                jobSchedulerService.load();
                schedulerPowerStateListener = this::syncSchedulerPowerState;
                jobSchedulerService.addListener(schedulerPowerStateListener);
                syncSchedulerPowerState();
                jobSchedulerService.start();
            } catch (Exception e) {
                logger.warn("Failed to load GPG keys or credentials", e);
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

            // Create and show main window
            MainWindow mainWindow = new MainWindow(primaryStage);
            mainWindow.show();
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

        // Close all SSH sessions first.
        if (sessionManager != null) {
            try {
                sessionManager.closeAllSessions();
            } catch (Exception e) {
                logger.error("Error closing sessions", e);
            }
        }
        
        // Save configuration
        try {
            if (configManager != null && masterPasswordManager != null && masterPasswordManager.getDerivedKey() != null) {
                configManager.save(masterPasswordManager.getDerivedKey());
            }
        } catch (Exception e) {
            logger.error("Failed to save configuration", e);
        }
        
        // Save remaining state and stop background services. Each step is
        // independent and individually guarded: Runtime.halt(0) (in shutdownAndExit)
        // skips the JVM shutdown hooks, so this is the only chance to flush state —
        // one manager failing must not skip the remaining saves/stops.
        if (sessionJournalSummarizer != null) {
            shutdownStep("stop session journal summarizer", sessionJournalSummarizer::stop);
        }
        if (sessionJournalHtmlRenderer != null) {
            shutdownStep("stop session journal HTML renderer", sessionJournalHtmlRenderer::stop);
        }
        if (gpgKeyManager != null) {
            shutdownStep("save GPG keys", gpgKeyManager::save);
        }
        if (credentialManager != null) {
            shutdownStep("save credentials", credentialManager::save);
        }
        if (sshKeyManager != null) {
            shutdownStep("save SSH keys", sshKeyManager::save);
        }
        if (snippetManager != null) {
            shutdownStep("save snippets", snippetManager::save);
        }
        if (snippetVariableManager != null) {
            shutdownStep("save snippet variables", snippetVariableManager::save);
        }
        if (aiChatManager != null) {
            shutdownStep("save AI chats", aiChatManager::save);
        }
        if (swarmChatManager != null) {
            shutdownStep("save swarm chats", swarmChatManager::save);
        }
        if (globalSettingsManager != null) {
            shutdownStep("save global settings", globalSettingsManager::save);
        }
        if (teamworkRecycleBinService != null) {
            shutdownStep("save teamwork recycle bin", teamworkRecycleBinService::save);
        }
        if (teamworkSyncService != null) {
            shutdownStep("stop teamwork sync", teamworkSyncService::stop);
        }
        if (jobSchedulerService != null) {
            shutdownStep("stop job scheduler", jobSchedulerService::shutdownSchedulerThreads);
        }
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
            SSHClientMonitor monitor = new SSHClientMonitor(sessionManager);
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
    
    public ConfigurationManager getConfigManager() {
        return configManager;
    }
    
    public SessionManager getSessionManager() {
        return sessionManager;
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

    public AiChatManager getAiChatManager() {
        return aiChatManager;
    }

    public SwarmChatManager getSwarmChatManager() {
        return swarmChatManager;
    }

    public de.kortty.core.SessionJournalService getSessionJournalService() {
        return sessionJournalService;
    }

    public de.kortty.core.SessionJournalSummarizer getSessionJournalSummarizer() {
        return sessionJournalSummarizer;
    }

    public de.kortty.core.SessionJournalHtmlRenderer getSessionJournalHtmlRenderer() {
        return sessionJournalHtmlRenderer;
    }
    
    public BackupManager getBackupManager() {
        return backupManager;
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
