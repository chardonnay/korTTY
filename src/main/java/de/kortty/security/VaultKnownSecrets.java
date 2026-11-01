package de.kortty.security;

import de.kortty.model.ServerConnection;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The known secrets {@link de.kortty.core.AiSecretGuard} refuses in AI skills and the memory: the stored
 * passwords, jump-server passwords and private-key passphrases of every connection. They are available only while the vault is open.
 * While it is locked nothing is decrypted and the result is empty.
 */
public final class VaultKnownSecrets {

    private VaultKnownSecrets() {
    }

    /**
     * The decrypted secrets of {@code connections}, or an empty list when {@code passwords} is null or its vault is
     * locked. A value that cannot be decrypted is skipped. Never logs a value. The check runs on every skill check,
     * so decryption goes through the encryption service directly: {@link PasswordVault} would log each failure.
     */
    public static Collection<String> of(MasterPasswordManager passwords, Supplier<List<ServerConnection>> connections) {
        if (passwords == null || !passwords.isUnlocked() || connections == null) {
            return List.of();
        }
        EncryptionService encryption = passwords.getEncryptionService();
        char[] masterPassword = passwords.getMasterPassword();
        Set<String> values = new LinkedHashSet<>();
        for (ServerConnection connection : connections.get()) {
            if (connection == null) {
                continue;
            }
            addDecrypted(values, encryption, masterPassword, connection.getEncryptedPassword());
            addDecrypted(values, encryption, masterPassword, connection.getPrivateKeyPassphrase());
            addDecrypted(values, encryption, masterPassword,
                connection.getJumpServer() != null ? connection.getJumpServer().getEncryptedPassword() : null);
        }
        return values;
    }

    private static void addDecrypted(Set<String> values, EncryptionService encryption, char[] masterPassword,
                                     String encrypted) {
        if (encrypted == null || encrypted.isBlank()) {
            return;
        }
        try {
            String value = encryption.decryptPassword(encrypted, masterPassword);
            if (value != null && !value.isBlank()) {
                values.add(value);
            }
        } catch (Exception e) {
            // Skipped without a log line: the value cannot be used, and a broken entry must not flood the log.
        }
    }
}
