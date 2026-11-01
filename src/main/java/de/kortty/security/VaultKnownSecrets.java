package de.kortty.security;

import de.kortty.model.AiProfile;
import de.kortty.model.AiSkillProvider;
import de.kortty.model.GlobalSettings;
import de.kortty.model.JumpServer;
import de.kortty.model.SSHKey;
import de.kortty.model.ServerConnection;
import de.kortty.model.StoredCredential;
import de.kortty.model.TranslationProfile;

import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/**
 * The known secrets {@link de.kortty.core.AiSecretGuard} refuses in AI skills and the memory: every value the vault
 * encrypts with the master password. That is the connection passwords, jump-server passwords and private-key
 * passphrases, the stored credentials and their external commands, the SSH key passphrases, the AI, translation and
 * skill-provider keys, and the global API keys. They are available only while the vault is open. While it is locked
 * nothing is decrypted and the result is empty.
 */
public final class VaultKnownSecrets {

    private VaultKnownSecrets() {
    }

    /**
     * The decrypted secrets of {@code connections}, or an empty list when {@code passwords} is null or its vault is
     * locked. Only the connections; {@link #ofOwners} covers every other vault secret.
     */
    public static Collection<String> of(MasterPasswordManager passwords, Supplier<List<ServerConnection>> connections) {
        if (connections == null) {
            return List.of();
        }
        return ofOwners(passwords, () -> connections.get());
    }

    /**
     * The decrypted secrets of every object that {@code owners} returns: connections, stored credentials, SSH keys, AI and
     * translation profiles, AI skill providers, jump servers and the global settings. An empty list when {@code passwords}
     * is null, its vault is locked or {@code owners} is null. A value that cannot be decrypted is skipped. Never logs a
     * value. The check runs on every skill check, so decryption goes through the encryption service directly:
     * {@link PasswordVault} would log each failure.
     */
    public static Collection<String> ofOwners(MasterPasswordManager passwords,
                                              Supplier<? extends Collection<?>> owners) {
        if (passwords == null || !passwords.isUnlocked() || owners == null) {
            return List.of();
        }
        Collection<?> objects = owners.get();
        Set<String> values = new LinkedHashSet<>();
        if (objects == null) {
            return values;
        }
        EncryptionService encryption = passwords.getEncryptionService();
        char[] masterPassword = passwords.getMasterPassword();
        for (Object owner : objects) {
            for (String encrypted : encryptedValues(owner)) {
                addDecrypted(values, encryption, masterPassword, encrypted);
            }
        }
        return values;
    }

    /**
     * The vault-encrypted strings one object holds, as stored; {@code null} entries are skipped by the caller. An object
     * of a type not listed here holds no vault secret. A new vault-encrypted field has to be listed here, and
     * {@code VaultKnownSecretsTest} fails until it is.
     */
    static List<String> encryptedValues(Object owner) {
        if (owner instanceof ServerConnection connection) {
            JumpServer jump = connection.getJumpServer();
            return Arrays.asList(connection.getEncryptedPassword(), connection.getPrivateKeyPassphrase(),
                jump != null ? jump.getEncryptedPassword() : null);
        }
        if (owner instanceof JumpServer jump) {
            return Arrays.asList(jump.getEncryptedPassword());
        }
        if (owner instanceof StoredCredential credential) {
            return Arrays.asList(credential.getEncryptedPassword(), credential.getEncryptedExternalCommand());
        }
        if (owner instanceof SSHKey key) {
            return Arrays.asList(key.getEncryptedPassphrase());
        }
        if (owner instanceof AiProfile profile) {
            return Arrays.asList(profile.getEncryptedApiKey());
        }
        if (owner instanceof TranslationProfile profile) {
            return Arrays.asList(profile.getEncryptedApiKey());
        }
        if (owner instanceof AiSkillProvider provider) {
            return Arrays.asList(provider.getEncryptedSecret());
        }
        if (owner instanceof GlobalSettings settings) {
            return Arrays.asList(settings.getEncryptedTranslationApiKey(), settings.getEncryptedAiApiKey(),
                settings.getEncryptedHuggingFaceToken(), settings.getEncryptedAiTavilyApiKey(),
                settings.getEncryptedAiBrightDataApiToken(), settings.getEncryptedAiBraveSearchApiKey());
        }
        return List.of();
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
