package de.kortty;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.SessionManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.CredentialManager;
import de.kortty.core.SSHKeyManager;
import de.kortty.core.GlobalSettingsManager;
import de.kortty.core.BackupManager;
import de.kortty.jmx.SSHClientMonitor;
import de.kortty.security.MasterPasswordManager;
import de.kortty.ui.MainWindow;
import de.kortty.ui.MasterPasswordDialog;
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

/**
 * Main entry point for the KorTTY SSH Client application.
 */
public class KorTTYApplication extends Application {
    
    private static final Logger logger = LoggerFactory.getLogger(KorTTYApplication.class);
    private static final String APP_NAME = "KorTTY";
    private static final String APP_VERSION = "1.1.0";
    
    private static KorTTYApplication instance;
    
    private ConfigurationManager configManager;
    private SessionManager sessionManager;
    private MasterPasswordManager masterPasswordManager;
    private GPGKeyManager gpgKeyManager;
    private CredentialManager credentialManager;
    private SSHKeyManager sshKeyManager;
    private GlobalSettingsManager globalSettingsManager;
    private BackupManager backupManager;
    
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
        
        // Install global exception handler to suppress JediTermFX bug
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
        sshKeyManager = new SSHKeyManager(configDir);
        globalSettingsManager = new GlobalSettingsManager(configDir);
        
        // Register JMX MBean
        registerJMXBean();
    }
    
    /**
     * Installs a global exception handler to suppress known harmless exceptions.
     */
    private void installGlobalExceptionHandler() {
        // Set default uncaught exception handler for all threads
        Thread.setDefaultUncaughtExceptionHandler((thread, throwable) -> {
            // Suppress known JediTermFX ClassCastException bug
            if (throwable instanceof ClassCastException) {
                String message = throwable.getMessage();
                // JediTermFX bug can have null message or the specific KeyFrame/Timeline message
                if (message == null || 
                    (message.contains("javafx.animation.KeyFrame") && message.contains("javafx.animation.Timeline"))) {
                    // This is the known JediTermFX WeakRedrawTimer bug - silently ignore it
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
            // Load global settings first (they are not encrypted) to check if master password is required
            try {
                globalSettingsManager.load();
                
                // Initialize language manager EARLY with settings, before any UI is created
                // This ensures the correct language is used from the start
                de.kortty.core.LanguageManager.getInstance().initialize(globalSettingsManager.getSettings());
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
                // Reload global settings to ensure we have the latest version
                globalSettingsManager.load();
                
                // Re-initialize language manager in case settings changed during reload
                de.kortty.core.LanguageManager.getInstance().initialize(globalSettingsManager.getSettings());
                
                // Initialize BackupManager after settings are loaded
                backupManager = new BackupManager(getConfigDirectory(), globalSettingsManager.getSettings());
            } catch (Exception e) {
                logger.warn("Failed to load GPG keys or credentials", e);
            }
            
            // Create and show main window
            MainWindow mainWindow = new MainWindow(primaryStage);
            mainWindow.show();
            
            logger.info("{} started successfully", APP_NAME);
            
        } catch (Exception e) {
            logger.error("Failed to start application", e);
            showErrorAndExit(de.kortty.ui.I18n.get("error.title") + ": " + e.getMessage());
        }
    }
    
    @Override
    public void stop() throws Exception {
        logger.info("Shutting down {}...", APP_NAME);
        
        // Close all SSH sessions first (this may take a moment)
        if (sessionManager != null) {
            try {
                sessionManager.closeAllSessions();
                // Give sessions a moment to close gracefully
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                logger.warn("Interrupted while closing sessions");
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
            if (globalSettingsManager != null) {
                globalSettingsManager.save();
            }
        } catch (Exception e) {
            logger.error("Failed to save GPG keys or credentials", e);
        }
        
        logger.info("{} shutdown complete", APP_NAME);
        
        // Force exit to ensure all threads (including non-daemon threads from Apache SSHD) terminate
        // This prevents the application from hanging after Platform.exit()
        System.exit(0);
    }
    
    private boolean handleMasterPassword(Stage ownerStage) {
        MasterPasswordDialog dialog = new MasterPasswordDialog(ownerStage, masterPasswordManager);
        return dialog.showAndWait();
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
        Alert alert = new Alert(Alert.AlertType.ERROR);
        alert.setTitle(de.kortty.ui.I18n.get("error.title"));
        alert.setHeaderText(null);
        alert.setContentText(message);
        alert.showAndWait();
        Platform.exit();
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
    
    public SSHKeyManager getSSHKeyManager() {
        return sshKeyManager;
    }
    
    public GlobalSettingsManager getGlobalSettingsManager() {
        return globalSettingsManager;
    }
    
    public BackupManager getBackupManager() {
        return backupManager;
    }
}
