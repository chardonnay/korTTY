package de.kortty.security;

import de.kortty.policy.PolicyValueCipher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;

/**
 * Manages the master password for the application.
 */
public class MasterPasswordManager {
    
    private static final Logger logger = LoggerFactory.getLogger(MasterPasswordManager.class);
    /**
     * Salt + PBKDF2 verification hash. Public because BackupManager includes this file in
     * backups by name — a literal there once drifted to a name that never existed on disk,
     * silently dropping the file from every backup.
     */
    public static final String MASTER_KEY_FILE = "master.key";
    /** Obfuscated copy of the master password for the "skip master-password prompt" setting. */
    private static final String AUTO_UNLOCK_FILE = "master.autounlock";
    private static final Set<PosixFilePermission> OWNER_ONLY = Set.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE);
    /**
     * Password used to bootstrap the vault on a brand-new profile when auto-unlock is enabled
     * (e.g. a fresh VM). It is deliberately NOT a secret — the point of auto-unlock is to run
     * without one. Documented so the user can still unlock manually if they disable the option.
     */
    private static final char[] AUTO_UNLOCK_DEFAULT_PASSWORD = "kortty-auto".toCharArray();

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
     * A master-password change that has been staged in memory but not yet written to
     * {@code master.key}. Created by {@link #beginPasswordChange}, finished with
     * {@link #commitPasswordChange} or undone with {@link #rollbackPasswordChange}.
     */
    public static final class PendingPasswordChange {
        private final byte[] previousSalt;
        private final String previousHash;
        private final SecretKey previousDerivedKey;
        private final char[] previousMasterPassword;
        private final byte[] newSalt;
        private final String newHash;

        private PendingPasswordChange(byte[] previousSalt, String previousHash,
                                      SecretKey previousDerivedKey, char[] previousMasterPassword,
                                      byte[] newSalt, String newHash) {
            this.previousSalt = previousSalt;
            this.previousHash = previousHash;
            this.previousDerivedKey = previousDerivedKey;
            this.previousMasterPassword = previousMasterPassword;
            this.newSalt = newSalt;
            this.newHash = newHash;
        }
    }

    /**
     * Changes the master password in one step (verify, rewrite {@code master.key}, swap the
     * in-memory key material). Callers that own encrypted data should prefer the staged
     * {@link #beginPasswordChange}/{@link #commitPasswordChange} pair so the on-disk password is
     * only replaced once every secret store has been migrated successfully.
     */
    public void changePassword(char[] oldPassword, char[] newPassword) throws Exception {
        commitPasswordChange(beginPasswordChange(oldPassword, newPassword));
    }

    /**
     * Stages a master-password change: verifies {@code oldPassword} and switches the in-memory key
     * material to {@code newPassword} so secrets can be re-encrypted and persisted — but leaves
     * {@code master.key} on disk untouched.
     *
     * <p>{@code master.key} is the authority for which password unlocks the vault, so rewriting it
     * before the secret stores have been migrated would leave the vault keyed to a password the
     * stored data is not yet encrypted with. Finish with {@link #commitPasswordChange} once every
     * store is persisted, or {@link #rollbackPasswordChange} to restore the previous state.
     *
     * @throws SecurityException if the old password is wrong (nothing is staged)
     */
    public PendingPasswordChange beginPasswordChange(char[] oldPassword, char[] newPassword) throws Exception {
        if (!verifyPassword(oldPassword)) {
            throw new SecurityException("Old password is incorrect");
        }

        byte[] newSalt = encryptionService.generateSalt();
        String newHash = encryptionService.hashPassword(newPassword, newSalt);
        // Capture the current (old) state so the change can be undone without touching the disk.
        PendingPasswordChange staged = new PendingPasswordChange(
            salt, storedHash, derivedKey, masterPassword, newSalt, newHash);

        // In-memory only — the caller now re-encrypts every store with the new password.
        salt = newSalt;
        storedHash = newHash;
        derivedKey = encryptionService.deriveKey(newPassword, newSalt);
        this.masterPassword = newPassword.clone();

        logger.info("Master password change staged (master.key not written yet)");
        return staged;
    }

    /** Writes the staged password to {@code master.key}, making the change permanent. */
    public void commitPasswordChange(PendingPasswordChange pending) throws Exception {
        Objects.requireNonNull(pending, "pending");
        Properties props = new Properties();
        props.setProperty("salt", Base64.getEncoder().encodeToString(pending.newSalt));
        props.setProperty("hash", pending.newHash);

        Path keyFile = configDir.resolve(MASTER_KEY_FILE);
        try (OutputStream out = Files.newOutputStream(keyFile)) {
            props.store(out, "KorTTY Master Password");
        }

        logger.info("Master password changed successfully");
    }

    /**
     * Undoes a staged change, restoring the in-memory key material to the old password. Safe to
     * call after a failed migration: {@code master.key} was never rewritten, so the old password
     * remains the one that unlocks the vault.
     */
    public void rollbackPasswordChange(PendingPasswordChange pending) {
        if (pending == null) {
            return;
        }
        salt = pending.previousSalt;
        storedHash = pending.previousHash;
        derivedKey = pending.previousDerivedKey;
        this.masterPassword = pending.previousMasterPassword;
        logger.warn("Master password change rolled back — the previous password is still in effect");
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

    // --- Auto-unlock (the "skip master-password prompt" setting) -------------------------------
    // The master password is stored obfuscated on disk so the vault can be unlocked at startup
    // without prompting. This is INSECURE by design: the obfuscation key is embedded in the binary
    // (see PolicyValueCipher), so the owner-only file permissions are the real security boundary.
    // Intended for throwaway/test environments only.

    /** Whether a remembered auto-unlock password is stored on disk. */
    public boolean hasAutoUnlockPassword() {
        return Files.exists(configDir.resolve(AUTO_UNLOCK_FILE));
    }

    /**
     * Remembers {@code password} for automatic unlock by writing an obfuscated copy to
     * {@code ~/.kortty/master.autounlock} with owner-only permissions.
     */
    public void saveAutoUnlockPassword(char[] password) throws IOException {
        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Cannot remember an empty master password.");
        }
        String envelope = PolicyValueCipher.encrypt(new String(password));
        Files.createDirectories(configDir);
        Path file = configDir.resolve(AUTO_UNLOCK_FILE);
        Path partial = file.resolveSibling(AUTO_UNLOCK_FILE + ".part");
        try {
            Files.writeString(partial, envelope, StandardCharsets.UTF_8);
            restrictPermissions(partial);
            try {
                Files.move(partial, file,
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(partial, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrictPermissions(file);
        } finally {
            Files.deleteIfExists(partial);
        }
        logger.info("Master password remembered for automatic unlock");
    }

    /** Loads the remembered password, or {@code null} if none is stored or it cannot be read. */
    public char[] loadAutoUnlockPassword() {
        Path file = configDir.resolve(AUTO_UNLOCK_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            String envelope = Files.readString(file, StandardCharsets.UTF_8).trim();
            return PolicyValueCipher.decrypt(envelope).toCharArray();
        } catch (Exception e) {
            logger.warn("Could not read the remembered master password (corrupt or tampered)");
            return null;
        }
    }

    /** Removes any remembered auto-unlock password. Idempotent. */
    public void clearAutoUnlockPassword() {
        try {
            Files.deleteIfExists(configDir.resolve(AUTO_UNLOCK_FILE));
            Files.deleteIfExists(configDir.resolve(AUTO_UNLOCK_FILE + ".part"));
        } catch (IOException e) {
            logger.warn("Could not remove the remembered master password file", e);
        }
    }

    /**
     * Attempts to unlock the vault without prompting, for the "skip master-password prompt"
     * setting. Returns {@code true} only when the manager ended up unlocked (derived key and
     * master password available for decryption).
     *
     * <ol>
     *   <li>A remembered password exists → verify it; on success the vault is unlocked.</li>
     *   <li>No remembered password and no master password set yet (fresh profile) → bootstrap a
     *       non-secret default password and remember it, so a brand-new VM starts with zero input.</li>
     *   <li>A master password is set but not remembered → return {@code false} so the caller can
     *       prompt once and then remember it (self-healing).</li>
     * </ol>
     */
    public boolean tryAutoUnlock() {
        if (hasAutoUnlockPassword()) {
            char[] remembered = loadAutoUnlockPassword();
            if (remembered != null) {
                try {
                    if (verifyPassword(remembered)) {
                        return true;
                    }
                    logger.warn("Remembered master password no longer matches — discarding it");
                } catch (Exception e) {
                    logger.warn("Auto-unlock with the remembered master password failed", e);
                } finally {
                    Arrays.fill(remembered, '\0');
                }
            }
            // Stored password missing/corrupt/stale → drop it and fall back to prompting.
            clearAutoUnlockPassword();
            return false;
        }
        if (!isPasswordSet()) {
            // Fresh profile (e.g. a new VM): bootstrap a non-secret default so startup needs no input.
            try {
                Files.createDirectories(configDir);
                setupPassword(AUTO_UNLOCK_DEFAULT_PASSWORD.clone());
                saveAutoUnlockPassword(AUTO_UNLOCK_DEFAULT_PASSWORD.clone());
                return true;
            } catch (Exception e) {
                logger.error("Failed to bootstrap the default auto-unlock password", e);
                return false;
            }
        }
        // Password is set but we don't know it yet — caller prompts once, then remembers it.
        return false;
    }

    private static void restrictPermissions(Path path) throws IOException {
        try {
            Files.setPosixFilePermissions(path, OWNER_ONLY);
        } catch (UnsupportedOperationException ignored) {
            // Non-POSIX (Windows): protection inherited from the config directory ACL.
        }
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
