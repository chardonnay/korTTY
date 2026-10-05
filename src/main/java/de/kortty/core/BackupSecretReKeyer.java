package de.kortty.core;

import de.kortty.jobscheduler.JobSchedulerRepository;
import de.kortty.model.ServerConnection;
import de.kortty.persistence.XMLConnectionRepository;
import de.kortty.rag.RagConfigurationManager;
import de.kortty.security.EncryptionService;
import de.kortty.security.MasterPasswordReEncryptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Re-encrypts the secret-bearing store files of an extracted backup from the backup's master
 * password to the current one, in the private extraction directory and before anything is copied
 * into the configuration directory. A merge import of a backup made under another master
 * password would otherwise put ciphertext into the local stores that the current master password
 * can never decrypt.
 *
 * <p>Each store is loaded with its own manager from the extraction directory, re-encrypted with
 * {@link MasterPasswordReEncryptor#forImport} (which clears, never keeps, a value it cannot
 * decrypt) and saved back there. A file whose re-encryption fails as a whole is deleted from the
 * extraction directory, so it is not imported at all — see {@link Result#skippedFiles()}.
 */
final class BackupSecretReKeyer {

    private static final Logger logger = LoggerFactory.getLogger(BackupSecretReKeyer.class);

    /** The outcome: secrets re-encrypted, secrets cleared as undecryptable, files not imported. */
    record Result(int reEncrypted, int cleared, List<String> skippedFiles) {
        Result {
            skippedFiles = List.copyOf(skippedFiles);
        }
    }

    private final EncryptionService enc;
    private final char[] backupPassword;
    private final byte[] backupSalt;
    private final char[] currentPassword;
    private final byte[] currentSalt;

    /**
     * @param backupPassword  the master password the backup was made with (verified by the caller)
     * @param backupSalt      the salt of the backup's {@code master.key}
     * @param currentPassword the unlocked master password of this installation
     * @param currentSalt     the salt of the local {@code master.key}
     */
    BackupSecretReKeyer(EncryptionService enc, char[] backupPassword, byte[] backupSalt,
                        char[] currentPassword, byte[] currentSalt) {
        this.enc = Objects.requireNonNull(enc, "enc");
        this.backupPassword = Objects.requireNonNull(backupPassword, "backupPassword");
        this.backupSalt = Objects.requireNonNull(backupSalt, "backupSalt");
        this.currentPassword = Objects.requireNonNull(currentPassword, "currentPassword");
        this.currentSalt = Objects.requireNonNull(currentSalt, "currentSalt");
    }

    /** Re-encrypts {@code files} (managed backup names) inside {@code extractDir} in place. */
    Result reKey(Path extractDir, List<String> files) {
        MasterPasswordReEncryptor reEncryptor = MasterPasswordReEncryptor.forImport(enc, backupPassword, currentPassword);
        List<String> skipped = new ArrayList<>();
        for (String name : files) {
            Path file = extractDir.resolve(name);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                reKeyFile(extractDir, name, reEncryptor);
            } catch (Exception e) {
                logger.warn("Could not re-encrypt {} from the backup; it is not imported ({})",
                    name, e.getClass().getSimpleName());
                deleteQuietly(file);
                skipped.add(name);
            }
        }
        return new Result(reEncryptor.reEncryptedCount(), reEncryptor.clearedCount(), skipped);
    }

    private void reKeyFile(Path extractDir, String name, MasterPasswordReEncryptor reEncryptor) throws Exception {
        switch (name) {
            case XMLConnectionRepository.CONNECTIONS_FILE -> reKeyConnections(extractDir.resolve(name), reEncryptor);
            case CredentialManager.CREDENTIALS_FILE -> {
                CredentialManager credentials = new CredentialManager(extractDir);
                credentials.load();
                requireLoaded(credentials.getLoadFailureBackup().isEmpty(), name);
                List<de.kortty.model.StoredCredential> all = credentials.getAllCredentials();
                reEncryptor.reEncryptCredentials(all);
                credentials.save();
            }
            case SSHKeyManager.SSH_KEYS_FILE -> {
                SSHKeyManager keys = new SSHKeyManager(extractDir);
                keys.load();
                requireLoaded(keys.getLoadFailureBackup().isEmpty(), name);
                reEncryptor.reEncryptSshKeys(keys.getAllKeys());
                keys.save();
            }
            case GlobalSettingsManager.SETTINGS_FILE -> {
                GlobalSettingsManager settings = new GlobalSettingsManager(extractDir);
                settings.load();
                requireLoaded(settings.getLoadRecovery().isEmpty(), name);
                reEncryptor.reEncryptGlobalSettings(settings.getSettings());
                settings.save();
            }
            case JobSchedulerRepository.FILE_NAME -> {
                JobSchedulerRepository repo = new JobSchedulerRepository(extractDir);
                repo.load();
                requireLoaded(repo.getLoadFailureBackup().isEmpty(), name);
                reEncryptor.reEncryptJobScheduler(repo);
                // reEncryptJobScheduler only saves when something changed.
                repo.save();
            }
            case BackupManager.RAG_STORES_FILE -> {
                RagConfigurationManager rag = new RagConfigurationManager(extractDir.resolve(name));
                reEncryptor.reEncryptRagStores(rag);
                deleteQuietly(rag.file().resolveSibling(rag.file().getFileName() + ".lock"));
            }
            default -> throw new IllegalArgumentException("Not a secret-bearing store: " + name);
        }
    }

    /**
     * {@code connections.xml}: the per-value secrets with the master password, the temporary SSH
     * keys with the key derived from the master password and the {@code master.key} salt.
     */
    private void reKeyConnections(Path file, MasterPasswordReEncryptor reEncryptor) throws Exception {
        SecretKey backupKey = enc.deriveKey(backupPassword, backupSalt);
        SecretKey currentKey = enc.deriveKey(currentPassword, currentSalt);
        // A temporary key the backup key cannot decrypt is cleared by the read, never kept.
        List<ServerConnection> connections = XMLConnectionRepository.readConnections(file, backupKey);
        reEncryptor.reEncryptConnections(connections);
        ByteArrayOutputStream xml = new ByteArrayOutputStream();
        XMLConnectionRepository.writeConnections(connections, xml, currentKey);
        AtomicFileWriter.writeStoreAtomically(file, xml.toString(java.nio.charset.StandardCharsets.UTF_8),
            AtomicFileWriter.FileMode.OWNER_ONLY);
    }

    private static void requireLoaded(boolean loaded, String name) {
        if (!loaded) {
            throw new IllegalStateException(name + " in the backup is unreadable");
        }
    }

    private static void deleteQuietly(Path file) {
        try {
            Files.deleteIfExists(file);
        } catch (Exception e) {
            logger.debug("Could not delete {}", file, e);
        }
    }
}
