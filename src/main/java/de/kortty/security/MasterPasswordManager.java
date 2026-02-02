package de.kortty.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.Properties;

/**
 * Manages the master password for the application.
 */
public class MasterPasswordManager {
    
    private static final Logger logger = LoggerFactory.getLogger(MasterPasswordManager.class);
    private static final String MASTER_KEY_FILE = "master.key";
    
    private final Path configDir;
    private final EncryptionService encryptionService;
    
    private byte[] salt;
    private String storedHash;
    private SecretKey derivedKey;
    private char[] masterPassword;
    
    public MasterPasswordManager(Path configDir) {
        this.configDir = configDir;
        this.encryptionService = new EncryptionService();
    }
    
    /**
     * Checks if a master password has been set up.
     */
    public boolean isPasswordSet() {
        Path keyFile = configDir.resolve(MASTER_KEY_FILE);
        return Files.exists(keyFile);
    }
    
    /**
     * Sets up a new master password.
     */
    public void setupPassword(char[] password) throws Exception {
        salt = encryptionService.generateSalt();
        storedHash = encryptionService.hashPassword(password, salt);
        
        // Store salt and hash
        Properties props = new Properties();
        props.setProperty("salt", Base64.getEncoder().encodeToString(salt));
        props.setProperty("hash", storedHash);
        
        Path keyFile = configDir.resolve(MASTER_KEY_FILE);
        try (OutputStream out = Files.newOutputStream(keyFile)) {
            props.store(out, "KorTTY Master Password");
        }
        
        // Derive and store the key
        derivedKey = encryptionService.deriveKey(password, salt);
        this.masterPassword = password.clone();
        
        logger.info("Master password set up successfully");
    }
    
    /**
     * Verifies the master password.
     */
    public boolean verifyPassword(char[] password) throws Exception {
        if (!isPasswordSet()) {
            return false;
        }
        
        loadStoredCredentials();
        
        boolean valid = encryptionService.verifyPassword(password, salt, storedHash);
        if (valid) {
            derivedKey = encryptionService.deriveKey(password, salt);
            this.masterPassword = password.clone();
            logger.info("Master password verified successfully");
        } else {
            logger.warn("Master password verification failed");
        }
        
        return valid;
    }
    
    /**
     * Changes the master password.
     * This requires re-encrypting all stored passwords.
     */
    public void changePassword(char[] oldPassword, char[] newPassword) throws Exception {
        if (!verifyPassword(oldPassword)) {
            throw new SecurityException("Old password is incorrect");
        }
        
        // Generate new salt and hash
        byte[] newSalt = encryptionService.generateSalt();
        String newHash = encryptionService.hashPassword(newPassword, newSalt);
        
        // Update stored credentials
        Properties props = new Properties();
        props.setProperty("salt", Base64.getEncoder().encodeToString(newSalt));
        props.setProperty("hash", newHash);
        
        Path keyFile = configDir.resolve(MASTER_KEY_FILE);
        try (OutputStream out = Files.newOutputStream(keyFile)) {
            props.store(out, "KorTTY Master Password");
        }
        
        salt = newSalt;
        storedHash = newHash;
        derivedKey = encryptionService.deriveKey(newPassword, newSalt);
        this.masterPassword = newPassword.clone();
        
        logger.info("Master password changed successfully");
    }
    
    private void loadStoredCredentials() throws Exception {
        Path keyFile = configDir.resolve(MASTER_KEY_FILE);
        Properties props = new Properties();
        
        try (InputStream in = Files.newInputStream(keyFile)) {
            props.load(in);
        }
        
        salt = Base64.getDecoder().decode(props.getProperty("salt"));
        storedHash = props.getProperty("hash");
    }
    
    /**
     * Gets the derived encryption key.
     */
    public SecretKey getDerivedKey() {
        return derivedKey;
    }
    
    /**
     * Gets the master password (for re-encryption purposes).
     */
    public char[] getMasterPassword() {
        return masterPassword;
    }
    
    /**
     * Gets the encryption service.
     */
    public EncryptionService getEncryptionService() {
        return encryptionService;
    }
    
    /**
     * Clears sensitive data from memory.
     */
    public void clear() {
        if (masterPassword != null) {
            java.util.Arrays.fill(masterPassword, '\0');
            masterPassword = null;
        }
        derivedKey = null;
    }
}
