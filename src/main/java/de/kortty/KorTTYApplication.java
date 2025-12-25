package de.kortty;

import de.kortty.core.ConfigurationManager;
import de.kortty.core.SessionManager;
import de.kortty.core.GPGKeyManager;
import de.kortty.core.CredentialManager;
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
    private static final String APP_VERSION = "1.0.0";
    
    private static KorTTYApplication instance;
    
    private ConfigurationManager configManager;
    private SessionManager sessionManager;
    private MasterPasswordManager masterPasswordManager;
    private GPGKeyManager gpgKeyManager;
    private CredentialManager credentialManager;
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
            // Check if master password needs to be set up or verified
            if (!handleMasterPassword(primaryStage)) {
                Platform.exit();
                return;
            }
            
            // Load configuration
            configManager.load(masterPasswordManager.getDerivedKey());
            
            // Load GPG keys and credentials
            try {
                gpgKeyManager.load();
                credentialManager.load();
                globalSettingsManager.load();
                
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
            showErrorAndExit("Fehler beim Starten der Anwendung: " + e.getMessage());
        }
    }
    
    @Override
    public void stop() throws Exception {
        logger.info("Shutting down {}...", APP_NAME);
        
        // Close all SSH sessions
        if (sessionManager != null) {
            sessionManager.closeAllSessions();
        }
        
        // Save configuration
        if (configManager != null && masterPasswordManager != null && masterPasswordManager.getDerivedKey() != null) {
            configManager.save(masterPasswordManager.getDerivedKey());
        }
        
        // Save GPG keys and credentials
        try {
            if (gpgKeyManager != null) {
                gpgKeyManager.save();
            }
            if (credentialManager != null) {
                credentialManager.save();
            }
            if (globalSettingsManager != null) {
                globalSettingsManager.save();
            }
        } catch (Exception e) {
            logger.error("Failed to save GPG keys or credentials", e);
        }
        
        logger.info("{} shutdown complete", APP_NAME);
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
        alert.setTitle("Fehler");
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
    
    public GlobalSettingsManager getGlobalSettingsManager() {
        return globalSettingsManager;
    }
    
    public BackupManager getBackupManager() {
        return backupManager;
    }
}
