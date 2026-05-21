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
import de.kortty.core.LoggingConfiguration;
import de.kortty.core.ThemeManager;
import de.kortty.core.TerminalEffectPluginManager;
import de.kortty.core.BackupManager;
import de.kortty.core.AiChatManager;
import de.kortty.teamwork.TeamworkSyncService;
import de.kortty.teamwork.TeamworkRecycleBinService;
import de.kortty.jobscheduler.JobSchedulerService;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.GlobalSettings;
import de.kortty.update.UpdateCheckService;
import de.kortty.jmx.SSHClientMonitor;
import de.kortty.security.MasterPasswordManager;
import de.kortty.ui.MainWindow;
import de.kortty.ui.MasterPasswordDialog;
import java.awt.Desktop;
import java.awt.desktop.AppForegroundListener;
import java.awt.desktop.AppReopenedListener;
import java.awt.desktop.QuitHandler;
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
    private static final String APP_VERSION = "2.2.0";
    
    private static KorTTYApplication instance;
    
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
    private TeamworkSyncService teamworkSyncService;
    private TeamworkRecycleBinService teamworkRecycleBinService;
    private JobSchedulerService jobSchedulerService;
    private UpdateCheckService updateCheckService;
    private ScheduledExecutorService logMaintenanceExecutor;
    private boolean macDesktopHandlersRegistered = false;
    private Boolean packagedMacApp;
    
    public static void main(String[] args) {
        logger.info("Starting {} v{}", APP_NAME, APP_VERSION);
        launch(args);
    }
    
    public static KorTTYApplication getInstance() {
        return instance;
    }
    
    @Override
    public void init() throws Exception {
        instance = this;
        
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
        themeManager = new ThemeManager(configDir);
        terminalEffectPluginManager = new TerminalEffectPluginManager(configDir);
        aiChatManager = new AiChatManager(configDir);
        
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
            
            if (passwordNotSet || requirePasswordOnStartup) {
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
                jobSchedulerService.start();
            } catch (Exception e) {
                logger.warn("Failed to load GPG keys or credentials", e);
            }

            try {
                terminalEffectPluginManager.load();
            } catch (Exception e) {
                logger.warn("Failed to load terminal effect plugins", e);
            }
            
            // Start teamwork sync and recycle bin (separate try-catch for accurate error context)
            try {
                teamworkSyncService = new TeamworkSyncService(getConfigDirectory(), globalSettingsManager);
                teamworkSyncService.start();
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
            
            // Create and show main window
            MainWindow mainWindow = new MainWindow(primaryStage);
            mainWindow.show();
            registerMacDesktopHandlers();
            startUpdateCheckService();
            
            logger.info("{} started successfully", APP_NAME);
            
        } catch (Exception e) {
            logger.error("Failed to start application", e);
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            showErrorAndExit(msg);
        }
    }
    
    @Override
    public void stop() throws Exception {
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
        
        // Save GPG keys and credentials
        try {
            if (gpgKeyManager != null) {
                gpgKeyManager.save();
            }
            if (credentialManager != null) {
                credentialManager.save();
            }
            if (sshKeyManager != null) {
                sshKeyManager.save();
            }
            if (snippetManager != null) {
                snippetManager.save();
            }
            if (snippetVariableManager != null) {
                snippetVariableManager.save();
            }
            if (aiChatManager != null) {
                aiChatManager.save();
            }
            if (globalSettingsManager != null) {
                globalSettingsManager.save();
            }
            if (teamworkRecycleBinService != null) {
                teamworkRecycleBinService.save();
            }
            if (teamworkSyncService != null) {
                teamworkSyncService.stop();
            }
            if (jobSchedulerService != null) {
                jobSchedulerService.shutdownSchedulerThreads();
            }
            if (updateCheckService != null) {
                updateCheckService.stop();
                updateCheckService = null;
            }
            if (logMaintenanceExecutor != null) {
                logMaintenanceExecutor.shutdownNow();
                logMaintenanceExecutor = null;
            }
        } catch (Exception e) {
            logger.error("Failed to save GPG keys or credentials", e);
        }
        
        logger.info("{} shutdown complete", APP_NAME);
        
        // Force exit to ensure all threads (including non-daemon threads from Apache SSHD) terminate
        // This prevents the application from hanging after Platform.exit()
        System.exit(0);
    }

    public boolean shouldKeepRunningAfterLastWindowClosed() {
        return isMacOs() && isPackagedMacApplication();
    }
    
    private boolean handleMasterPassword(Stage ownerStage) {
        MasterPasswordDialog dialog = new MasterPasswordDialog(ownerStage, masterPasswordManager);
        return dialog.showAndWait();
    }

    public void applyLoggingSettings() {
        if (globalSettingsManager == null) {
            return;
        }
        GlobalSettings settings = globalSettingsManager.getSettings();
        try {
            LoggingConfiguration.applyRuntimeSettings(settings, getConfigDirectory());
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

    private void prepareMacApplicationLifecycle() {
        if (!shouldKeepRunningAfterLastWindowClosed()) {
            return;
        }

        Platform.setImplicitExit(false);
        logger.info("Configured JavaFX implicit exit to keep the packaged macOS app alive after the last window closes");
    }

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
            desktop.setQuitHandler((QuitHandler) (event, response) -> {
                logger.info("Received macOS Desktop quit request");
                boolean hasOpenWindows = MainWindow.hasOpenWindows();
                if (hasOpenWindows) {
                    Platform.runLater(MainWindow::requestApplicationQuit);
                    response.cancelQuit();
                    return;
                }

                Platform.runLater(Platform::exit);
                response.performQuit();
            });
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
    
    public ThemeManager getThemeManager() {
        return themeManager;
    }

    public TerminalEffectPluginManager getTerminalEffectPluginManager() {
        return terminalEffectPluginManager;
    }

    public AiChatManager getAiChatManager() {
        return aiChatManager;
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

    public UpdateCheckService getUpdateCheckService() {
        return updateCheckService;
    }
}
