package de.kortty.core;

import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.mlx.MlxModelRegistry;
import de.kortty.jobscheduler.JobSchedulerRepository;
import de.kortty.model.GlobalSettings;
import de.kortty.model.StoredCredential;
import de.kortty.model.GPGKey;
import de.kortty.persistence.XMLConnectionRepository;
import de.kortty.security.EncryptionService;
import de.kortty.security.MasterPasswordManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Manages encrypted backups of all application settings.
 */
public class BackupManager {
    
    private static final Logger logger = LoggerFactory.getLogger(BackupManager.class);
    private static final DateTimeFormatter BACKUP_DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    private static final String BACKUP_SUBDIR = "old-backups";
    /** The current password-protected backup in the target directory. */
    static final String BACKUP_FILENAME = "kortty-backup.zip";
    /**
     * The current GPG backup. It used to share {@link #BACKUP_FILENAME}, which sent korTTY's own
     * GPG backups to the ZIP-password prompt on import; the format is now read from the content,
     * so those older files still restore.
     */
    static final String GPG_BACKUP_FILENAME = BACKUP_FILENAME + ".gpg";
    /** Rotated backups in {@code old-backups/}: {@code kortty-backup_<timestamp>[-n].zip[.gpg]}. */
    static final String ROTATED_PREFIX = "kortty-backup_";
    private static final String GPG_BACKUP_SUFFIX = ".zip.gpg";
    /** A backup is written under this suffix and only moved into place once it is complete. */
    private static final String PART_SUFFIX = ".part";
    private static final String ARMORED_PGP_MESSAGE = "-----BEGIN PGP MESSAGE-----";
    /** The RAG store registry relative to the configuration directory. */
    static final String RAG_STORES_FILE = "rag/stores.json";
    /** How often a merge import asks for the backup's master password before it gives up. */
    static final int MAX_BACKUP_MASTER_PASSWORD_ATTEMPTS = 3;
    /**
     * Every store file a backup carries, built from the stores' own constants so a renamed file
     * cannot silently fall out of the backup; {@code BackupCoverageTest} fails when a new store
     * constant is neither listed here nor explicitly excluded.
     */
    private static final List<String> MANAGED_BACKUP_FILES = List.of(
        XMLConnectionRepository.CONNECTIONS_FILE,
        CredentialManager.CREDENTIALS_FILE,
        GPGKeyManager.GPG_KEYS_FILE,
        // Key references and master-password-encrypted passphrases; the copied key FILES in
        // ~/.kortty/ssh-keys/ are added as a directory alongside projects/ below.
        SSHKeyManager.SSH_KEYS_FILE,
        GlobalSettingsManager.SETTINGS_FILE,
        JobSchedulerRepository.FILE_NAME,
        // Without this file, a restore onto a fresh profile leaves every restored encrypted
        // value undecryptable until the user recreates the identical master password by hand.
        // master.autounlock is deliberately NOT backed up: it is an obfuscated copy of the
        // master password whose obfuscation key ships in the binary, so copying it into
        // backup archives (and their rotated old-backups/) would spread the weakest secret;
        // after a restore, auto-login simply re-arms on the next enable.
        MasterPasswordManager.MASTER_KEY_FILE,
        SshHostKeyTrustManager.STORE_FILE_NAME,
        SnippetManager.SNIPPETS_FILE,
        SnippetVariableManager.VARIABLES_FILE,
        AiChatManager.AI_CHATS_FILE,
        "llm/" + LlamaModelRegistry.REGISTRY_FILE_NAME,
        // RagConfigurationManager.DEFAULT_FILE is an absolute Path, not a name.
        RAG_STORES_FILE,
        ThemeManager.THEMES_FILE,
        EnvironmentManager.ENVIRONMENTS_FILE,
        SwarmChatManager.SWARM_CHATS_FILE,
        "llm/" + MlxModelRegistry.REGISTRY_FILE_NAME);
    /**
     * Directories included alongside the managed files: project workspaces and the copied SSH
     * key files. Raw private keys are why password ZIPs are written with AES-256 rather than
     * legacy ZipCrypto — see {@link #createPasswordEncryptedBackup}.
     */
    private static final List<String> MANAGED_BACKUP_DIRECTORIES = List.of(
        "projects", "ssh-keys", SnippetAnalysisStore.DIRECTORY_NAME);
    /**
     * Restored files korTTY itself writes owner-only: they name every host and user, hold the
     * scheduler's sudo secrets, or (master.key) the salt and hash an attacker could guess the
     * master password from. The other managed files keep the permissions of the file they replace.
     */
    private static final Set<String> OWNER_ONLY_RESTORED_FILES = Set.of(
        XMLConnectionRepository.CONNECTIONS_FILE,
        CredentialManager.CREDENTIALS_FILE,
        SSHKeyManager.SSH_KEYS_FILE,
        JobSchedulerRepository.FILE_NAME,
        MasterPasswordManager.MASTER_KEY_FILE);
    
    /**
     * The managed files holding values encrypted with the master password (or with the key derived
     * from it). A merge import of a backup made under another master password re-encrypts them —
     * see {@link BackupSecretReKeyer} — or does not import them.
     */
    static final List<String> SECRET_BEARING_FILES = List.of(
        XMLConnectionRepository.CONNECTIONS_FILE,
        CredentialManager.CREDENTIALS_FILE,
        SSHKeyManager.SSH_KEYS_FILE,
        GlobalSettingsManager.SETTINGS_FILE,
        JobSchedulerRepository.FILE_NAME,
        RAG_STORES_FILE);

    /**
     * Asks for the master password a backup was made with, when a merge import would bring
     * secrets encrypted under it. Called on the import's worker thread; the implementation shows a
     * masked prompt and blocks until it is answered. The returned array belongs to the caller,
     * which wipes it after use.
     */
    @FunctionalInterface
    public interface BackupMasterPasswordPrompt {
        /**
         * @param attempt     1 for the first prompt, higher after a wrong password
         * @param secretFiles the backup files that hold secrets and would be imported
         * @return the password, or {@code null} when the user cancels
         */
        char[] requestBackupMasterPassword(int attempt, List<String> secretFiles);
    }

    /** What a backup file holds, read from its first bytes rather than from its name. */
    public enum BackupFormat {
        /** A ZIP archive: a password-protected backup (or an unencrypted ZIP). */
        ZIP,
        /** OpenPGP-encrypted data, binary or ASCII-armored: a GPG backup. */
        OPENPGP,
        /** Neither — not a korTTY backup. */
        UNKNOWN
    }

    /**
     * The outcome of {@link #restoreBackup}. {@code secretsReEncrypted} and {@code secretsCleared}
     * count the secrets of a merge import that came from a backup made under another master
     * password: re-encrypted with the current one, or cleared because the backup's password did not
     * decrypt them. {@code skippedSecretFiles} are the files with secrets that were not imported
     * because the backup's master password was not given (cancelled, wrong, or the vault is locked).
     */
    public record ImportResult(int filesImported, boolean masterKeyReplaced, int secretsReEncrypted,
                               int secretsCleared, List<String> skippedSecretFiles) {
        public ImportResult {
            skippedSecretFiles = skippedSecretFiles == null ? List.of() : List.copyOf(skippedSecretFiles);
        }

        public ImportResult(int filesImported, boolean masterKeyReplaced) {
            this(filesImported, masterKeyReplaced, 0, 0, List.of());
        }
    }

    /** Decrypts {@code input} into {@code output}; the seam tests replace. */
    @FunctionalInterface
    interface GpgDecryptor {
        void decrypt(Path input, Path output) throws IOException;
    }

    /** The system's {@code gpg}, which picks the matching private key from the keyring. */
    static final GpgDecryptor SYSTEM_GPG_DECRYPTOR = BackupManager::decryptWithSystemGpg;

    private final Path configDir;
    private final GlobalSettings settings;
    private final SessionJournalExportProtection.GpgEncryptor gpgEncryptor;
    private final GpgDecryptor gpgDecryptor;
    
    public BackupManager(Path configDir, GlobalSettings settings) {
        this(configDir, settings, SessionJournalExportProtection.SYSTEM_GPG, SYSTEM_GPG_DECRYPTOR);
    }

    BackupManager(Path configDir, GlobalSettings settings,
                  SessionJournalExportProtection.GpgEncryptor gpgEncryptor, GpgDecryptor gpgDecryptor) {
        this.configDir = configDir;
        this.settings = settings;
        this.gpgEncryptor = Objects.requireNonNull(gpgEncryptor, "gpgEncryptor");
        this.gpgDecryptor = Objects.requireNonNull(gpgDecryptor, "gpgDecryptor");
    }

    /**
     * Reads the format of a backup from its first bytes: a ZIP local, end-of-central-directory
     * or spanning signature; an OpenPGP message whose first packet is a public-key or symmetric
     * session key or a marker packet (old or new packet format); or an ASCII-armored message.
     * An empty file is {@link BackupFormat#UNKNOWN}.
     */
    public static BackupFormat detectBackupFormat(Path file) throws IOException {
        byte[] head;
        try (InputStream in = Files.newInputStream(file)) {
            head = in.readNBytes(64);
        }
        return detectBackupFormat(head);
    }

    static BackupFormat detectBackupFormat(byte[] head) {
        if (head.length >= 4 && head[0] == 'P' && head[1] == 'K'
                && ((head[2] == 3 && head[3] == 4) || (head[2] == 5 && head[3] == 6)
                    || (head[2] == 7 && head[3] == 8))) {
            return BackupFormat.ZIP;
        }
        if (head.length >= 1 && (head[0] & 0x80) != 0) {
            int b = head[0] & 0xFF;
            // New packet format (bit 6 set): tag in bits 5-0; old format: tag in bits 5-2.
            int tag = (b & 0x40) != 0 ? (b & 0x3F) : ((b >> 2) & 0x0F);
            if (tag == 1 || tag == 3 || tag == 10) {
                return BackupFormat.OPENPGP;
            }
        }
        String text = new String(head, StandardCharsets.US_ASCII);
        if (text.startsWith("ï»¿")) {
            text = text.substring(3); // UTF-8 byte-order mark, decoded byte by byte
        }
        if (text.stripLeading().startsWith(ARMORED_PGP_MESSAGE)) {
            return BackupFormat.OPENPGP;
        }
        return BackupFormat.UNKNOWN;
    }

    /**
     * The format an import treats a file as: its detected content, except that an unrecognised
     * file named {@code *.gpg} is handed to gpg, which knows more OpenPGP variants than the
     * first-packet check.
     */
    public static BackupFormat importFormat(Path file) throws IOException {
        BackupFormat format = detectBackupFormat(file);
        if (format == BackupFormat.UNKNOWN && hasGpgName(file)) {
            return BackupFormat.OPENPGP;
        }
        return format;
    }

    private static boolean hasGpgName(Path file) {
        return file.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".gpg");
    }
    
    /**
     * Creates an encrypted backup of all settings to the specified directory.
     * Encryption method is determined by GlobalSettings. The new backup is written next to the
     * current one first; only once it is complete are the current backups rotated into
     * {@code old-backups/} and the new one moved into place, so a failed backup never pushes
     * out the last good one.
     * 
     * @param targetDir Directory where backup should be saved
     * @param credentialManager For retrieving backup password (if PASSWORD encryption)
     * @param gpgKeyManager For retrieving GPG key (if GPG encryption)
     * @param masterPassword For decrypting stored credentials
     * @return Path to created backup file: {@code kortty-backup.zip} or {@code kortty-backup.zip.gpg}
     * @throws Exception if backup creation fails
     */
    public Path createBackup(Path targetDir, CredentialManager credentialManager, 
                            GPGKeyManager gpgKeyManager, char[] masterPassword) throws Exception {
        logger.info("Creating backup to: {} with encryption type: {}", 
                   targetDir, settings.getBackupEncryptionType());
        
        // Validate encryption settings
        validateEncryptionSettings(credentialManager, gpgKeyManager);
        
        // Ensure target directory exists
        Files.createDirectories(targetDir);
        
        boolean passwordMode =
            settings.getBackupEncryptionType() == GlobalSettings.BackupEncryptionType.PASSWORD;
        Path backupFile = targetDir.resolve(passwordMode ? BACKUP_FILENAME : GPG_BACKUP_FILENAME);
        Path partFile = targetDir.resolve(backupFile.getFileName() + PART_SUFFIX);
        // A .part left behind by a crash must not be extended: zip4j appends to an existing file.
        Files.deleteIfExists(partFile);
        
        boolean placed = false;
        try {
            if (passwordMode) {
                createPasswordEncryptedBackup(partFile, credentialManager, masterPassword);
            } else {
                createGPGEncryptedBackup(partFile, gpgKeyManager);
            }
            if (!Files.isRegularFile(partFile) || Files.size(partFile) == 0) {
                throw new IOException("The backup file was not written: " + partFile.getFileName());
            }
            rotateCurrentBackups(targetDir);
            moveIntoPlace(partFile, backupFile);
            placed = true;
        } finally {
            if (!placed) {
                try {
                    Files.deleteIfExists(partFile);
                } catch (IOException e) {
                    logger.warn("Could not delete the incomplete backup {}", partFile, e);
                }
            }
        }
        
        logger.info("Backup created successfully: {} ({} bytes)", 
                   backupFile, Files.size(backupFile));
        
        // Update settings
        settings.setLastBackupPath(targetDir.toString());
        settings.setLastBackupTime(System.currentTimeMillis());
        
        return backupFile;
    }

    private static void moveIntoPlace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
    
    private void validateEncryptionSettings(CredentialManager credentialManager, GPGKeyManager gpgKeyManager) throws Exception {
        if (settings.getBackupEncryptionType() == GlobalSettings.BackupEncryptionType.PASSWORD) {
            if (settings.getBackupCredentialId() == null) {
                throw new Exception("No password selected for backup encryption!");
            }
            if (credentialManager == null || credentialManager.findCredentialById(settings.getBackupCredentialId()).isEmpty()) {
                throw new Exception("Selected password not found!");
            }
        } else if (settings.getBackupEncryptionType() == GlobalSettings.BackupEncryptionType.GPG) {
            if (settings.getBackupGpgKeyId() == null) {
                throw new Exception("No GPG key selected for backup encryption!");
            }
            if (gpgKeyManager == null) {
                throw new Exception("Selected GPG key not found!");
            }
            gpgKeyManager.getAllKeys().stream()
                .filter(k -> k.getId().equals(settings.getBackupGpgKeyId()))
                .findFirst()
                .orElseThrow(() -> new Exception("Selected GPG key not found!"));
        }
    }
    
    /**
     * Creates a password-protected ZIP file.
     */
    private void createPasswordEncryptedBackup(Path backupFile, CredentialManager credentialManager, 
                                              char[] masterPassword) throws Exception {
        // Get password from credential
        StoredCredential credential = credentialManager.findCredentialById(settings.getBackupCredentialId())
            .orElseThrow(() -> new Exception("Credential not found"));
        String password = credentialManager.getPassword(credential, masterPassword);
        if (password == null || password.isEmpty()) {
            throw new Exception("Password could not be decrypted!");
        }
        
        ZipParameters zipParameters = new ZipParameters();
        zipParameters.setEncryptFiles(true);
        // AES-256, not legacy ZipCrypto: the archive carries raw SSH private-key files, which
        // (unlike the XML payloads) have no inner AES-256-GCM layer of their own. zip4j picks
        // the decryption method per entry from the archive headers, so backups created with
        // the former ZIP_STANDARD encryption keep importing unchanged.
        zipParameters.setEncryptionMethod(EncryptionMethod.AES);
        zipParameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);

        // Closed before the file is moved into place: Windows cannot move a file that is open.
        try (ZipFile zipFile = new ZipFile(backupFile.toFile(), password.toCharArray())) {
            for (String fileName : MANAGED_BACKUP_FILES) {
                addFileToPasswordZip(zipFile, configDir.resolve(fileName), fileName, zipParameters);
            }

            for (String dirName : MANAGED_BACKUP_DIRECTORIES) {
                Path dir = configDir.resolve(dirName);
                if (Files.exists(dir) && Files.isDirectory(dir)) {
                    zipFile.addFolder(dir.toFile(), zipParameters);
                }
            }
        }

        logger.info("Created password-protected backup");
    }
    
    private void addFileToPasswordZip(
        ZipFile zipFile,
        Path file,
        String entryName,
        ZipParameters parameters
    ) throws Exception {
        if (Files.exists(file)) {
            ZipParameters fileParameters = new ZipParameters(parameters);
            fileParameters.setFileNameInZip(entryName.replace('\\', '/'));
            zipFile.addFile(file.toFile(), fileParameters);
            logger.debug("Added to backup: {}", entryName);
        }
    }
    
    /**
     * Creates a GPG-encrypted backup: an unencrypted ZIP built in an owner-only temporary folder,
     * encrypted for the selected key's public key and removed again with its folder.
     */
    private void createGPGEncryptedBackup(Path backupFile, GPGKeyManager gpgKeyManager) throws Exception {
        // Get GPG key
        GPGKey gpgKey = gpgKeyManager.getAllKeys().stream()
            .filter(k -> k.getId().equals(settings.getBackupGpgKeyId()))
            .findFirst()
            .orElseThrow(() -> new Exception("GPG key not found"));
        
        Path work = SessionJournalExportProtection.privateTempDir("kortty-backup");
        try {
            Path plainZip = work.resolve(BACKUP_FILENAME);
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(plainZip))) {
                for (String fileName : MANAGED_BACKUP_FILES) {
                    addFileToZip(zos, configDir.resolve(fileName), fileName);
                }

                for (String dirName : MANAGED_BACKUP_DIRECTORIES) {
                    Path dir = configDir.resolve(dirName);
                    if (Files.exists(dir) && Files.isDirectory(dir)) {
                        addDirectoryToZip(zos, dir, dirName);
                    }
                }
            }
            
            try {
                gpgEncryptor.encrypt(plainZip, backupFile, gpgKey.getKeyId(), gpgKey.getPublicKeyPath());
            } catch (IOException e) {
                throw new Exception("GPG encryption failed: " + e.getMessage(), e);
            }
            
            logger.info("Created GPG-encrypted backup with key: {}", gpgKey.getKeyId());
        } finally {
            SessionJournalExportProtection.deleteRecursively(work);
        }
    }
    
    private void addFileToZip(ZipOutputStream zos, Path file, String entryName) throws IOException {
        if (!Files.exists(file)) {
            logger.debug("Skipping non-existent file: {}", file);
            return;
        }
        
        zos.putNextEntry(new ZipEntry(entryName));
        Files.copy(file, zos);
        zos.closeEntry();
        logger.debug("Added to backup: {}", entryName);
    }
    
    private void addDirectoryToZip(ZipOutputStream zos, Path dir, String basePath) throws IOException {
        try (var stream = Files.walk(dir)) {
            stream.filter(Files::isRegularFile).forEach(file -> {
                try {
                    String relativePath = basePath + "/" + dir.relativize(file).toString().replace('\\', '/');
                    addFileToZip(zos, file, relativePath);
                } catch (IOException e) {
                    logger.warn("Failed to add file to backup: {}", file, e);
                }
            });
        }
    }
    
    /**
     * Moves every current backup in {@code targetDir} — {@code kortty-backup.zip} and
     * {@code kortty-backup.zip.gpg}, so switching the encryption type never leaves two "current"
     * backups — into {@code old-backups/} with a timestamp. The rotated name ends in what the
     * file holds ({@code .zip.gpg} for GPG data, even a GPG backup that an older version saved as
     * {@code .zip}), and never replaces a backup rotated in the same second.
     */
    private void rotateCurrentBackups(Path targetDir) throws IOException {
        List<Path> current = new ArrayList<>();
        for (String name : List.of(BACKUP_FILENAME, GPG_BACKUP_FILENAME)) {
            Path candidate = targetDir.resolve(name);
            if (Files.isRegularFile(candidate)) {
                current.add(candidate);
            }
        }
        if (current.isEmpty()) {
            return;
        }
        Path backupSubdir = targetDir.resolve(BACKUP_SUBDIR);
        Files.createDirectories(backupSubdir);
        
        String timestamp = LocalDateTime.now().format(BACKUP_DATE_FORMAT);
        for (Path existing : current) {
            String suffix = importFormat(existing) == BackupFormat.OPENPGP ? GPG_BACKUP_SUFFIX : ".zip";
            Path rotatedBackup = freeRotatedName(backupSubdir, ROTATED_PREFIX + timestamp, suffix);
            Files.move(existing, rotatedBackup);
            logger.info("Rotated old backup to: {}", rotatedBackup);
        }
        
        cleanupOldBackups(backupSubdir);
    }
    
    private static Path freeRotatedName(Path dir, String base, String suffix) {
        Path candidate = dir.resolve(base + suffix);
        for (int i = 1; Files.exists(candidate); i++) {
            candidate = dir.resolve(base + "-" + i + suffix);
        }
        return candidate;
    }

    /**
     * Removes oldest backups if count exceeds maximum. ZIP and GPG backups count together.
     */
    private void cleanupOldBackups(Path backupSubdir) throws IOException {
        int maxBackups = settings.getMaxBackupCount();
        
        if (maxBackups == 0) {
            logger.debug("Unlimited backups enabled, skipping cleanup");
            return;
        }
        
        List<Path> backups;
        try (var stream = Files.list(backupSubdir)) {
            backups = stream
                .filter(p -> isRotatedBackupName(p.getFileName().toString()))
                .filter(Files::isRegularFile)
                .sorted(Comparator.comparing((Path p) -> {
                    try {
                        return Files.getLastModifiedTime(p);
                    } catch (IOException e) {
                        return java.nio.file.attribute.FileTime.fromMillis(0);
                    }
                }).thenComparing(p -> p.getFileName().toString()))
                .collect(Collectors.toList());
        }
        
        int toDelete = backups.size() - maxBackups;
        if (toDelete > 0) {
            logger.info("Deleting {} old backup(s) (max: {})", toDelete, maxBackups);
            for (int i = 0; i < toDelete; i++) {
                Path oldBackup = backups.get(i);
                Files.deleteIfExists(oldBackup);
                logger.debug("Deleted old backup: {}", oldBackup);
            }
        }
    }
    
    static boolean isRotatedBackupName(String name) {
        return name.startsWith(ROTATED_PREFIX) && (name.endsWith(".zip") || name.endsWith(".gpg"));
    }

    /**
     * Imports a backup from an encrypted backup file.
     * Supports both password-encrypted ZIP files and GPG-encrypted files.
     * 
     * @param backupFile Path to the backup file to import
     * @param password Password for password-encrypted backups (null if GPG-encrypted)
     * @param overwriteExisting If true, existing files will be overwritten
     * @return Number of files imported
     * @throws Exception if import fails
     * @see #restoreBackup
     */
    public int importBackup(Path backupFile, String password, boolean overwriteExisting) throws Exception {
        return restoreBackup(backupFile, password, overwriteExisting).filesImported();
    }

    /**
     * Imports a backup. The format is read from the file's content, not its name: a ZIP is
     * opened with {@code password} (when given), GPG data is decrypted with gpg — which needs the
     * matching private key — and its unencrypted ZIP payload extracted without a password. GPG
     * backups that older versions saved as {@code kortty-backup.zip} therefore restore too.
     *
     * <p>{@link ImportResult#masterKeyReplaced()} reports that the import wrote a
     * {@code master.key} different from the one on disk before (another master password): the
     * running application still holds the old key, so it must not reload or save its stores —
     * they would be read or re-encrypted with the wrong key — and has to restart.</p>
     *
     * @param password Password for password-encrypted ZIP backups (null for GPG backups)
     * @param overwriteExisting If true, existing files will be overwritten
     */
    public ImportResult restoreBackup(Path backupFile, String password, boolean overwriteExisting) throws Exception {
        return restoreBackup(backupFile, password, overwriteExisting, null, null);
    }

    /**
     * Imports a backup like {@link #restoreBackup(Path, String, boolean)}. When a merge import
     * would copy files with secrets from a backup whose {@code master.key} differs from the local
     * one (which stays), {@code prompt} is asked for the backup's master password (verified against
     * the backup's {@code master.key}); those files are then re-encrypted with
     * {@code currentMasterPassword} before they are copied. Without a correct password — cancelled,
     * wrong {@value #MAX_BACKUP_MASTER_PASSWORD_ATTEMPTS} times, no prompt, or no unlocked current
     * password — the files with secrets are not imported and are reported in
     * {@link ImportResult#skippedSecretFiles()}: ciphertext under a foreign key is never written.
     *
     * @param currentMasterPassword the unlocked master password of this installation (not wiped)
     */
    public ImportResult restoreBackup(Path backupFile, String password, boolean overwriteExisting,
                                      char[] currentMasterPassword, BackupMasterPasswordPrompt prompt)
            throws Exception {
        logger.info("Importing backup from: {}", backupFile);
        
        if (!Files.exists(backupFile)) {
            throw new Exception("Backup file not found: " + backupFile);
        }
        
        BackupFormat format = importFormat(backupFile);
        if (format == BackupFormat.UNKNOWN) {
            throw new Exception("Not a korTTY backup: " + backupFile.getFileName()
                + " is neither a ZIP archive nor GPG-encrypted");
        }
        
        Path work = SessionJournalExportProtection.privateTempDir("kortty-backup-restore");
        try {
            Path zip = backupFile;
            if (format == BackupFormat.OPENPGP) {
                zip = work.resolve(BACKUP_FILENAME);
                try {
                    gpgDecryptor.decrypt(backupFile, zip);
                } catch (IOException e) {
                    String message = String.valueOf(e.getMessage());
                    throw new Exception(message.startsWith("GPG decryption")
                        ? message : "GPG decryption failed: " + message, e);
                }
                if (!Files.isRegularFile(zip) || detectBackupFormat(zip) != BackupFormat.ZIP) {
                    throw new Exception("The decrypted GPG backup " + backupFile.getFileName()
                        + " is not a ZIP archive");
                }
                logger.info("GPG decryption successful");
            }
            
            Path extractDir = Files.createDirectories(work.resolve("extract"));
            if (format == BackupFormat.ZIP && password != null) {
                try (ZipFile zipFile = new ZipFile(zip.toFile(), password.toCharArray())) {
                    zipFile.extractAll(extractDir.toString());
                }
                logger.info("Extracted password-protected ZIP");
            } else {
                if (isEncryptedZip(zip)) {
                    throw new Exception("Password required for password-encrypted backup");
                }
                // An unencrypted ZIP: the payload of a GPG backup (or a hand-made archive).
                extractUnencryptedZip(zip, extractDir);
                logger.info("Extracted unencrypted ZIP");
            }
            
            boolean masterKeyReplaced = masterKeyWouldBeReplaced(extractDir, overwriteExisting);
            if (masterKeyReplaced) {
                logger.warn("The backup carries a different master key; korTTY must restart after the import");
            }

            ForeignSecrets foreign = masterKeyReplaced
                ? ForeignSecrets.NONE
                : reKeyForeignSecrets(extractDir, overwriteExisting, currentMasterPassword, prompt);

            // Copy files to config directory
            int filesImported = copyBackupFiles(extractDir, overwriteExisting);
            // The saved terminal output belongs to the session before the restore, and may be encrypted
            // with a master key the restored files no longer use.
            SessionScrollbackStore.purge(configDir);

            logger.info("Backup imported successfully: {} files", filesImported);
            return new ImportResult(filesImported, masterKeyReplaced, foreign.reEncrypted(), foreign.cleared(),
                foreign.skippedFiles());
        } finally {
            SessionJournalExportProtection.deleteRecursively(work);
        }
    }

    private static boolean isEncryptedZip(Path zip) {
        try (ZipFile zipFile = new ZipFile(zip.toFile())) {
            return zipFile.isEncrypted();
        } catch (IOException e) {
            // Unreadable central directory: let the streaming extraction report the problem.
            return false;
        }
    }

    private static void extractUnencryptedZip(Path zip, Path extractDir) throws IOException {
        try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                Files.newInputStream(zip))) {
            java.util.zip.ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                // Zip-slip guard: a crafted entry name ("../..") must never escape
                // the extraction directory. zip4j's extractAll validates this
                // itself; this manual ZipInputStream path has to do it explicitly.
                Path entryPath = extractDir.resolve(entry.getName()).normalize();
                if (!entryPath.startsWith(extractDir)) {
                    throw new IOException(
                        "Blocked backup ZIP entry outside the extraction directory: "
                            + entry.getName());
                }
                if (entry.isDirectory()) {
                    Files.createDirectories(entryPath);
                } else {
                    Files.createDirectories(entryPath.getParent());
                    Files.copy(zis, entryPath, StandardCopyOption.REPLACE_EXISTING);
                }
                zis.closeEntry();
            }
        }
    }

    /**
     * Whether copying the extracted backup will put a {@code master.key} on disk that differs
     * from the current one (or where there was none): the file is only copied when it is missing
     * locally or the import overwrites.
     */
    private boolean masterKeyWouldBeReplaced(Path extractDir, boolean overwriteExisting) throws IOException {
        Path restored = extractDir.resolve(MasterPasswordManager.MASTER_KEY_FILE);
        if (!Files.isRegularFile(restored)) {
            return false;
        }
        Path local = configDir.resolve(MasterPasswordManager.MASTER_KEY_FILE);
        if (!Files.exists(local)) {
            return true;
        }
        return overwriteExisting && !sameMasterKey(local, restored);
    }

    private record ForeignSecrets(int reEncrypted, int cleared, List<String> skippedFiles) {
        static final ForeignSecrets NONE = new ForeignSecrets(0, 0, List.of());
    }

    /**
     * The files with secrets this import would copy although they were encrypted under another
     * master password: the backup's {@code master.key} differs from the local one and the local
     * {@code master.key} stays (a merge import, where only files missing locally are copied).
     */
    List<String> foreignSecretFilesToImport(Path extractDir, boolean overwriteExisting) throws IOException {
        Path restored = extractDir.resolve(MasterPasswordManager.MASTER_KEY_FILE);
        Path local = configDir.resolve(MasterPasswordManager.MASTER_KEY_FILE);
        if (!Files.isRegularFile(restored) || !Files.isRegularFile(local) || sameMasterKey(local, restored)) {
            return List.of();
        }
        List<String> files = new java.util.ArrayList<>();
        for (String name : SECRET_BEARING_FILES) {
            if (Files.isRegularFile(extractDir.resolve(name))
                    && (overwriteExisting || !Files.exists(configDir.resolve(name)))) {
                files.add(name);
            }
        }
        return files;
    }

    /**
     * Re-encrypts the files with secrets that a merge import would bring from a backup made under
     * another master password, or — without that password — removes them from the extraction so
     * they are not imported. The passwords are never logged; the backup's is wiped after use.
     */
    private ForeignSecrets reKeyForeignSecrets(Path extractDir, boolean overwriteExisting,
                                               char[] currentMasterPassword, BackupMasterPasswordPrompt prompt)
            throws IOException {
        List<String> files = foreignSecretFilesToImport(extractDir, overwriteExisting);
        if (files.isEmpty()) {
            return ForeignSecrets.NONE;
        }
        logger.info("The backup was made with another master password; {} file(s) with secrets need re-encryption",
            files.size());
        Properties backupKey = readMasterKey(extractDir.resolve(MasterPasswordManager.MASTER_KEY_FILE));
        Properties localKey = readMasterKey(configDir.resolve(MasterPasswordManager.MASTER_KEY_FILE));
        byte[] backupSalt = decodeSalt(backupKey);
        byte[] localSalt = decodeSalt(localKey);
        String backupHash = backupKey != null ? backupKey.getProperty("hash") : null;
        boolean usable = currentMasterPassword != null && currentMasterPassword.length > 0 && prompt != null
            && backupSalt != null && localSalt != null && backupHash != null && !backupHash.isBlank();
        char[] backupPassword = usable ? askBackupMasterPassword(prompt, files, backupSalt, backupHash.trim()) : null;
        if (backupPassword == null) {
            return skipSecretFiles(extractDir, files);
        }
        try {
            BackupSecretReKeyer.Result result = new BackupSecretReKeyer(new EncryptionService(),
                backupPassword, backupSalt, currentMasterPassword, localSalt).reKey(extractDir, files);
            logger.info("Re-encrypted {} secret(s) from the backup with the current master password ({} cleared, "
                + "{} file(s) not imported)", result.reEncrypted(), result.cleared(), result.skippedFiles().size());
            return new ForeignSecrets(result.reEncrypted(), result.cleared(), result.skippedFiles());
        } finally {
            Arrays.fill(backupPassword, '\0');
        }
    }

    /** Prompts until the password matches the backup's {@code master.key}; null when it never does. */
    private static char[] askBackupMasterPassword(BackupMasterPasswordPrompt prompt, List<String> files,
                                                  byte[] backupSalt, String backupHash) {
        EncryptionService enc = new EncryptionService();
        for (int attempt = 1; attempt <= MAX_BACKUP_MASTER_PASSWORD_ATTEMPTS; attempt++) {
            char[] candidate = prompt.requestBackupMasterPassword(attempt, List.copyOf(files));
            if (candidate == null) {
                logger.info("The backup's master password was not given; files with secrets are not imported");
                return null;
            }
            try {
                if (candidate.length > 0 && enc.verifyPassword(candidate, backupSalt, backupHash)) {
                    return candidate;
                }
            } catch (Exception e) {
                logger.warn("Could not verify the backup's master password ({})", e.getClass().getSimpleName());
            }
            Arrays.fill(candidate, '\0');
            logger.info("Wrong master password for the backup (attempt {} of {})",
                attempt, MAX_BACKUP_MASTER_PASSWORD_ATTEMPTS);
        }
        return null;
    }

    private static ForeignSecrets skipSecretFiles(Path extractDir, List<String> files) throws IOException {
        for (String name : files) {
            Files.deleteIfExists(extractDir.resolve(name));
        }
        logger.warn("Not importing {} file(s) with secrets encrypted under another master password: {}",
            files.size(), files);
        return new ForeignSecrets(0, 0, files);
    }

    private static byte[] decodeSalt(Properties masterKey) {
        String salt = masterKey != null ? masterKey.getProperty("salt") : null;
        if (salt == null || salt.isBlank()) {
            return null;
        }
        try {
            return java.util.Base64.getDecoder().decode(salt.trim());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Compares two {@code master.key} files by salt and verification hash; the timestamp comment
     * {@link Properties#store} writes is not part of the key.
     */
    static boolean sameMasterKey(Path a, Path b) throws IOException {
        Properties left = readMasterKey(a);
        Properties right = readMasterKey(b);
        if (left != null && right != null
                && left.getProperty("salt") != null && left.getProperty("hash") != null) {
            return left.getProperty("salt").equals(right.getProperty("salt"))
                && left.getProperty("hash").equals(right.getProperty("hash"));
        }
        return Arrays.equals(Files.readAllBytes(a), Files.readAllBytes(b));
    }

    private static Properties readMasterKey(Path file) {
        Properties properties = new Properties();
        try (InputStream in = Files.newInputStream(file)) {
            properties.load(in);
            return properties;
        } catch (IOException | IllegalArgumentException e) {
            return null;
        }
    }

    private static void decryptWithSystemGpg(Path input, Path output) throws IOException {
        String gpg = SessionJournalExportProtection.resolveGpg()
            .orElseThrow(() -> new IOException("GPG decryption failed: gpg is not installed or not found"));
        ProcessBuilder pb = new ProcessBuilder(
            gpg,
            "--batch",
            "--yes",
            "--no-tty",
            "--quiet",
            "--decrypt",
            "--output", output.toString(),
            input.toString()
        );
        pb.redirectErrorStream(true);
        String osName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        pb.redirectInput(ProcessBuilder.Redirect.from(new File(osName.contains("win") ? "NUL" : "/dev/null")));
        Process process;
        try {
            process = pb.start();
        } catch (IOException e) {
            throw new IOException("GPG decryption failed: gpg could not be started", e);
        }
        // No hard timeout: gpg may be waiting for the private key's passphrase in a pinentry.
        String log = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip();
        int exitCode;
        try {
            exitCode = process.waitFor();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            Files.deleteIfExists(output);
            throw new IOException("GPG decryption was interrupted", e);
        }
        if (exitCode != 0) {
            Files.deleteIfExists(output);
            throw new IOException("GPG decryption failed (exit " + exitCode + ")" + (log.isEmpty() ? "" : ": " + log));
        }
    }
    
    private static AtomicFileWriter.FileMode restoredFileMode(String fileName) {
        return OWNER_ONLY_RESTORED_FILES.contains(fileName)
            ? AtomicFileWriter.FileMode.OWNER_ONLY
            : AtomicFileWriter.FileMode.PRESERVE;
    }

    /**
     * Copies backup files from extract directory to config directory.
     */
    private int copyBackupFiles(Path extractDir, boolean overwriteExisting) throws IOException {
        final int[] filesImported = {0}; // Use array to allow modification in lambda
        
        // List of files to import
        String[] filesToImport = MANAGED_BACKUP_FILES.toArray(String[]::new);
        
        // Copy individual files
        for (String fileName : filesToImport) {
            Path sourceFile = extractDir.resolve(fileName);
            if (Files.exists(sourceFile)) {
                Path targetFile = configDir.resolve(fileName);
                
                if (Files.exists(targetFile) && !overwriteExisting) {
                    logger.debug("Skipping existing file: {}", fileName);
                    continue;
                }
                
                Files.createDirectories(targetFile.getParent());
                // Never truncate a store in place: a failed copy keeps the local file intact.
                AtomicFileWriter.copyStoreAtomically(sourceFile, targetFile, restoredFileMode(fileName));
                filesImported[0]++;
                logger.debug("Imported: {}", fileName);
            }
        }
        
        // Copy projects directory if it exists
        Path sourceProjectsDir = extractDir.resolve("projects");
        if (Files.exists(sourceProjectsDir) && Files.isDirectory(sourceProjectsDir)) {
            Path targetProjectsDir = configDir.resolve("projects");
            
            if (Files.exists(targetProjectsDir) && !overwriteExisting) {
                logger.debug("Skipping existing projects directory");
            } else {
                if (Files.exists(targetProjectsDir)) {
                    // Delete existing projects directory
                    try (var stream = Files.walk(targetProjectsDir)) {
                        stream.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    logger.warn("Failed to delete existing project file: {}", path, e);
                                }
                            });
                    }
                }
                
                // Copy projects directory. Relativize against the projects directory itself, not
                // extractDir: the walk starts at extract/projects, so relativizing against
                // extractDir kept the leading "projects/" and restored everything into the
                // invisible ~/.kortty/projects/projects/.
                try (var stream = Files.walk(sourceProjectsDir)) {
                    stream.forEach(source -> {
                        try {
                            Path target = targetProjectsDir.resolve(sourceProjectsDir.relativize(source));
                            if (Files.isDirectory(source)) {
                                Files.createDirectories(target);
                            } else {
                                Files.createDirectories(target.getParent());
                                Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                                filesImported[0]++;
                            }
                        } catch (IOException e) {
                            logger.warn("Failed to copy project file: {}", source, e);
                        }
                    });
                }
                logger.debug("Imported projects directory");
            }
        }

        filesImported[0] += mergeSshKeysDirectory(extractDir, overwriteExisting);
        filesImported[0] += mergeSnippetAnalysesDirectory(extractDir, overwriteExisting);

        return filesImported[0];
    }

    /**
     * Restores the copied SSH key files by merging, never deleting: unlike projects/, keys that
     * exist locally but not in the backup must survive an import. Restored key files get
     * owner-only permissions where the filesystem supports them — OpenSSH refuses keys that are
     * readable by others.
     */
    private int mergeSshKeysDirectory(Path extractDir, boolean overwriteExisting) throws IOException {
        Path sourceKeysDir = extractDir.resolve("ssh-keys");
        if (!Files.exists(sourceKeysDir) || !Files.isDirectory(sourceKeysDir)) {
            return 0;
        }
        Path targetKeysDir = configDir.resolve("ssh-keys");
        final int[] imported = {0};
        try (var stream = Files.walk(sourceKeysDir)) {
            stream.forEach(source -> {
                try {
                    Path target = targetKeysDir.resolve(sourceKeysDir.relativize(source));
                    if (Files.isDirectory(source)) {
                        Files.createDirectories(target);
                        return;
                    }
                    if (Files.exists(target) && !overwriteExisting) {
                        logger.debug("Skipping existing SSH key file: {}", target.getFileName());
                        return;
                    }
                    Files.createDirectories(target.getParent());
                    Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
                    try {
                        Files.setPosixFilePermissions(target, java.util.Set.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE));
                    } catch (UnsupportedOperationException e) {
                        // Non-POSIX filesystem (Windows): nothing to tighten.
                    }
                    imported[0]++;
                } catch (IOException e) {
                    logger.warn("Failed to copy SSH key file: {}", source, e);
                }
            });
        }
        logger.debug("Imported ssh-keys directory");
        return imported[0];
    }

    /**
     * Restores stored snippet analyses by merging, never deleting: an analysis file that exists only
     * locally survives, a file missing locally is added, and an existing file is only replaced
     * (with the overwrite flag) when the backup carries a newer {@code revision}. Only the
     * {@code <id>.json} files are restored, never quarantined copies or temp files.
     */
    private int mergeSnippetAnalysesDirectory(Path extractDir, boolean overwriteExisting) throws IOException {
        Path sourceDir = extractDir.resolve(SnippetAnalysisStore.DIRECTORY_NAME);
        if (!Files.isDirectory(sourceDir)) {
            return 0;
        }
        Path targetDir = configDir.resolve(SnippetAnalysisStore.DIRECTORY_NAME);
        int imported = 0;
        try (var stream = Files.list(sourceDir)) {
            for (Path source : stream.sorted().toList()) {
                String name = source.getFileName().toString();
                if (!Files.isRegularFile(source) || !name.endsWith(SnippetAnalysisStore.FILE_SUFFIX)) {
                    continue;
                }
                Path target = targetDir.resolve(name).normalize();
                if (!target.startsWith(targetDir)) {
                    continue;
                }
                try {
                    if (Files.exists(target)) {
                        if (!overwriteExisting) {
                            logger.debug("Skipping existing snippet analyses: {}", name);
                            continue;
                        }
                        long backupRevision = SnippetAnalysisStore.readRevision(source).orElse(-1L);
                        java.util.OptionalLong localRevision = SnippetAnalysisStore.readRevision(target);
                        if (backupRevision < 0
                                || (localRevision.isPresent() && localRevision.getAsLong() >= backupRevision)) {
                            logger.debug("Keeping local snippet analyses {} (not older than the backup)", name);
                            continue;
                        }
                        if (localRevision.isEmpty()) {
                            // An unreadable local file is kept aside rather than overwritten.
                            CorruptFileQuarantine.moveAside(target);
                        }
                    }
                    Files.createDirectories(targetDir);
                    AtomicFileWriter.writeStringAtomically(target,
                        Files.readString(source, java.nio.charset.StandardCharsets.UTF_8));
                    imported++;
                } catch (IOException e) {
                    logger.warn("Failed to restore snippet analyses file: {}", source, e);
                }
            }
        }
        logger.debug("Imported snippet-analyses directory");
        return imported;
    }

    static List<String> managedBackupFiles() {
        return MANAGED_BACKUP_FILES;
    }

    static List<String> managedBackupDirectories() {
        return MANAGED_BACKUP_DIRECTORIES;
    }
}
