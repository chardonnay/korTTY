package de.kortty.security;

import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages encrypted storage and retrieval of connection passwords.
 */
public class PasswordVault {
    
    private static final Logger logger = LoggerFactory.getLogger(PasswordVault.class);
    
    private final EncryptionService encryptionService;
    private final char[] masterPassword;
    
    public PasswordVault(EncryptionService encryptionService, char[] masterPassword) {
        this.encryptionService = encryptionService;
        this.masterPassword = masterPassword;
    }
    
    /**
     * Encrypts and stores a password in the connection.
     */
    public void storePassword(ServerConnection connection, String plainPassword) {
        try {
            String encrypted = encryptionService.encryptPassword(plainPassword, masterPassword);
            connection.setEncryptedPassword(encrypted);
            logger.debug("Password encrypted for connection: {}", connection.getName());
        } catch (Exception e) {
            logger.error("Failed to encrypt password", e);
            throw new RuntimeException("Failed to encrypt password", e);
        }
    }
    
    /**
     * Retrieves and decrypts the password from a connection.
     */
    public String retrievePassword(ServerConnection connection) {
        String encrypted = connection.getEncryptedPassword();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        
        try {
            return encryptionService.decryptPassword(encrypted, masterPassword);
        } catch (Exception e) {
            logger.error("Failed to decrypt password", e);
            throw new RuntimeException("Failed to decrypt password", e);
        }
    }
    
    /**
     * Encrypts a private key passphrase.
     */
    public void storeKeyPassphrase(ServerConnection connection, String passphrase) {
        try {
            String encrypted = encryptionService.encryptPassword(passphrase, masterPassword);
            connection.setPrivateKeyPassphrase(encrypted);
            logger.debug("Key passphrase encrypted for connection: {}", connection.getName());
        } catch (Exception e) {
            logger.error("Failed to encrypt key passphrase", e);
            throw new RuntimeException("Failed to encrypt key passphrase", e);
        }
    }
    
    /**
     * Retrieves and decrypts the private key passphrase.
     */
    public String retrieveKeyPassphrase(ServerConnection connection) {
        String encrypted = connection.getPrivateKeyPassphrase();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        
        try {
            return encryptionService.decryptPassword(encrypted, masterPassword);
        } catch (Exception e) {
            logger.error("Failed to decrypt key passphrase", e);
            throw new RuntimeException("Failed to decrypt key passphrase", e);
        }
    }
    
    /**
     * Re-encrypts all passwords with a new master password.
     */
    public void reEncryptPassword(ServerConnection connection, char[] newMasterPassword) {
        try {
            // Decrypt with old password
            String plainPassword = retrievePassword(connection);
            if (plainPassword != null) {
                // Re-encrypt with new password
                String encrypted = encryptionService.encryptPassword(plainPassword, newMasterPassword);
                connection.setEncryptedPassword(encrypted);
            }
            
            // Same for key passphrase
            String plainPassphrase = retrieveKeyPassphrase(connection);
            if (plainPassphrase != null) {
                String encrypted = encryptionService.encryptPassword(plainPassphrase, newMasterPassword);
                connection.setPrivateKeyPassphrase(encrypted);
            }
            
            logger.debug("Passwords re-encrypted for connection: {}", connection.getName());
        } catch (Exception e) {
            logger.error("Failed to re-encrypt passwords", e);
            throw new RuntimeException("Failed to re-encrypt passwords", e);
        }
    }
}
