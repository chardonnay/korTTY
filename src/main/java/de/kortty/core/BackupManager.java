package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.StoredCredential;
import de.kortty.model.GPGKey;
import de.kortty.security.MasterPasswordManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.AesKeyStrength;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.io.*;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
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
    private static final String BACKUP_FILENAME = "kortty-backup.zip";
    private static final List<String> MANAGED_BACKUP_FILES = List.of(
        "connections.xml",
        "credentials.xml",
        "gpg-keys.xml",
        // Key references and master-password-encrypted passphrases; the copied key FILES in
        // ~/.kortty/ssh-keys/ are added as a directory alongside projects/ below.
        "ssh-keys.xml",
        "global-settings.xml",
        "job-scheduler.xml",
        // Without this file, a restore onto a fresh profile leaves every restored encrypted
        // value undecryptable until the user recreates the identical master password by hand.
        // master.autounlock is deliberately NOT backed up: it is an obfuscated copy of the
        // master password whose obfuscation key ships in the binary, so copying it into
        // backup archives (and their rotated old-backups/) would spread the weakest secret;
        // after a restore, auto-login simply re-arms on the next enable.
        MasterPasswordManager.MASTER_KEY_FILE,
        SshHostKeyTrustManager.STORE_FILE_NAME,
        "snippets.xml",
        "snippet-variables.xml",
        "ai-chats.xml",
        "llm/models.xml",
        "rag/stores.json");
    /**
     * Directories included alongside the managed files: project workspaces and the copied SSH
     * key files. Raw private keys are why password ZIPs are written with AES-256 rather than
     * legacy ZipCrypto — see {@link #createPasswordEncryptedBackup}.
     */
    private static final List<String> MANAGED_BACKUP_DIRECTORIES = List.of("projects", "ssh-keys");
    
    private final Path configDir;
    private final GlobalSettings settings;
    
    public BackupManager(Path configDir, GlobalSettings settings) {
        this.configDir = configDir;
        this.settings = settings;
    }
    
    /**
     * Creates an encrypted backup of all settings to the specified directory.
     * Encryption method is determined by GlobalSettings.
     * 
     * @param targetDir Directory where backup should be saved
     * @param credentialManager For retrieving backup password (if PASSWORD encryption)
     * @param gpgKeyManager For retrieving GPG key (if GPG encryption)
     * @param masterPassword For decrypting stored credentials
     * @return Path to created backup file
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
        
        Path backupFile = targetDir.resolve(BACKUP_FILENAME);
        
        // Rotate existing backup if present
        if (Files.exists(backupFile)) {
            rotateBackup(targetDir, backupFile);
        }
        
        // Create encrypted backup based on type
        if (settings.getBackupEncryptionType() == GlobalSettings.BackupEncryptionType.PASSWORD) {
            createPasswordEncryptedBackup(backupFile, credentialManager, masterPassword);
        } else {
            createGPGEncryptedBackup(backupFile, gpgKeyManager);
        }
        
        logger.info("Backup created successfully: {} ({} bytes)", 
                   backupFile, Files.size(backupFile));
        
        // Update settings
        settings.setLastBackupPath(targetDir.toString());
        settings.setLastBackupTime(System.currentTimeMillis());
        
        return backupFile;
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
            GPGKey key = gpgKeyManager.getAllKeys().stream()
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
        
        // Create password-protected ZIP using zip4j
        ZipFile zipFile = new ZipFile(backupFile.toFile(), password.toCharArray());
        
        ZipParameters zipParameters = new ZipParameters();
        zipParameters.setEncryptFiles(true);
        // AES-256, not legacy ZipCrypto: the archive carries raw SSH private-key files, which
        // (unlike the XML payloads) have no inner AES-256-GCM layer of their own. zip4j picks
        // the decryption method per entry from the archive headers, so backups created with
        // the former ZIP_STANDARD encryption keep importing unchanged.
        zipParameters.setEncryptionMethod(EncryptionMethod.AES);
        zipParameters.setAesKeyStrength(AesKeyStrength.KEY_STRENGTH_256);

        // Add all config files
        for (String fileName : MANAGED_BACKUP_FILES) {
            addFileToPasswordZip(zipFile, configDir.resolve(fileName), fileName, zipParameters);
        }

        for (String dirName : MANAGED_BACKUP_DIRECTORIES) {
            Path dir = configDir.resolve(dirName);
            if (Files.exists(dir) && Files.isDirectory(dir)) {
                zipFile.addFolder(dir.toFile(), zipParameters);
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
     * Creates a GPG-encrypted backup.
     */
    private void createGPGEncryptedBackup(Path backupFile, GPGKeyManager gpgKeyManager) throws Exception {
        // Get GPG key
        GPGKey gpgKey = gpgKeyManager.getAllKeys().stream()
            .filter(k -> k.getId().equals(settings.getBackupGpgKeyId()))
            .findFirst()
            .orElseThrow(() -> new Exception("GPG key not found"));
        
        // Create temporary unencrypted ZIP
        Path tempZip = Files.createTempFile("kortty-backup-temp", ".zip");
        try {
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempZip))) {
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
            
            // Encrypt with GPG
            ProcessBuilder pb = new ProcessBuilder(
                "gpg", "--encrypt",
                "--recipient", gpgKey.getKeyId(),
                "--trust-model", "always",
                "--output", backupFile.toString(),
                tempZip.toString()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();
            
            // Read output
            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }
            
            int exitCode = process.waitFor();
            if (exitCode != 0) {
                throw new Exception("GPG encryption failed: " + output.toString());
            }
            
            logger.info("Created GPG-encrypted backup with key: {}", gpgKey.getKeyId());
        } finally {
            Files.deleteIfExists(tempZip);
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
                    String relativePath = basePath + "/" + dir.relativize(file).toString();
                    addFileToZip(zos, file, relativePath);
                } catch (IOException e) {
                    logger.warn("Failed to add file to backup: {}", file, e);
                }
            });
        }
    }
    
    /**
     * Rotates existing backup by moving it to old-backups subdirectory with timestamp.
     */
    private void rotateBackup(Path targetDir, Path existingBackup) throws IOException {
        Path backupSubdir = targetDir.resolve(BACKUP_SUBDIR);
        Files.createDirectories(backupSubdir);
        
        String timestamp = LocalDateTime.now().format(BACKUP_DATE_FORMAT);
        String rotatedFilename = "kortty-backup_" + timestamp + ".zip";
        Path rotatedBackup = backupSubdir.resolve(rotatedFilename);
        
        Files.move(existingBackup, rotatedBackup, StandardCopyOption.REPLACE_EXISTING);
        logger.info("Rotated old backup to: {}", rotatedBackup);
        
        cleanupOldBackups(backupSubdir);
    }
    
    /**
     * Removes oldest backups if count exceeds maximum.
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
                .filter(p -> p.getFileName().toString().startsWith("kortty-backup_"))
                .filter(p -> p.getFileName().toString().endsWith(".zip"))
                .sorted(Comparator.comparing(p -> {
                    try {
                        return Files.getLastModifiedTime(p);
                    } catch (IOException e) {
                        return java.nio.file.attribute.FileTime.fromMillis(0);
                    }
                }))
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
    
    /**
     * Imports a backup from an encrypted backup file.
     * Supports both password-encrypted ZIP files and GPG-encrypted files.
     * 
     * @param backupFile Path to the backup file to import
     * @param password Password for password-encrypted backups (null if GPG-encrypted)
     * @param overwriteExisting If true, existing files will be overwritten
     * @return Number of files imported
     * @throws Exception if import fails
     */
    public int importBackup(Path backupFile, String password, boolean overwriteExisting) throws Exception {
        logger.info("Importing backup from: {}", backupFile);
        
        if (!Files.exists(backupFile)) {
            throw new Exception("Backup file not found: " + backupFile);
        }
        
        String fileName = backupFile.getFileName().toString().toLowerCase();
        Path tempZipFile = null;
        boolean isGPGEncrypted = fileName.endsWith(".gpg");
        
        try {
            // Handle GPG-encrypted backups
            if (isGPGEncrypted) {
                tempZipFile = Files.createTempFile("kortty-backup-import-", ".zip");
                
                // Decrypt GPG file
                ProcessBuilder pb = new ProcessBuilder(
                    "gpg",
                    "--batch",
                    "--yes",
                    "--no-tty",
                    "--quiet",
                    "--decrypt",
                    "--output", tempZipFile.toString(),
                    backupFile.toString()
                );
                
                pb.redirectErrorStream(true);
                String osName = System.getProperty("os.name").toLowerCase();
                File nullFile = new File(osName.contains("win") ? "NUL" : "/dev/null");
                pb.redirectInput(ProcessBuilder.Redirect.from(nullFile));
                
                Process process = pb.start();
                
                StringBuilder output = new StringBuilder();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        output.append(line).append("\n");
                    }
                }
                
                int exitCode = process.waitFor();
                if (exitCode != 0) {
                    throw new Exception("GPG decryption failed: " + output.toString());
                }
                
                logger.info("GPG decryption successful");
            } else {
                tempZipFile = backupFile;
            }
            
            // Extract ZIP file
            Path extractDir = Files.createTempDirectory("kortty-backup-extract-");
            try {
                if (isGPGEncrypted || password != null) {
                    // Password-protected ZIP
                    if (password == null) {
                        throw new Exception("Password required for password-encrypted backup");
                    }
                    
                    ZipFile zipFile = new ZipFile(tempZipFile.toFile(), password.toCharArray());
                    zipFile.extractAll(extractDir.toString());
                    logger.info("Extracted password-protected ZIP");
                } else {
                    // Unencrypted ZIP (shouldn't happen for backups, but handle it)
                    try (java.util.zip.ZipInputStream zis = new java.util.zip.ZipInputStream(
                            Files.newInputStream(tempZipFile))) {
                        java.util.zip.ZipEntry entry;
                        while ((entry = zis.getNextEntry()) != null) {
                            // Zip-slip guard: a crafted entry name ("../..") must never escape
                            // the extraction directory. zip4j's extractAll above validates this
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
                    logger.info("Extracted unencrypted ZIP");
                }
                
                // Copy files to config directory
                int filesImported = copyBackupFiles(extractDir, overwriteExisting);
                
                logger.info("Backup imported successfully: {} files", filesImported);
                return filesImported;
                
            } finally {
                // Cleanup extract directory
                if (Files.exists(extractDir)) {
                    try (var stream = Files.walk(extractDir)) {
                        stream.sorted(Comparator.reverseOrder())
                            .forEach(path -> {
                                try {
                                    Files.delete(path);
                                } catch (IOException e) {
                                    logger.warn("Failed to delete temp file: {}", path, e);
                                }
                            });
                    }
                }
            }
            
        } finally {
            // Cleanup temp GPG decrypted file
            if (isGPGEncrypted && tempZipFile != null && Files.exists(tempZipFile)) {
                Files.deleteIfExists(tempZipFile);
            }
        }
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
                Files.copy(sourceFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
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

    static List<String> managedBackupFiles() {
        return MANAGED_BACKUP_FILES;
    }

    static List<String> managedBackupDirectories() {
        return MANAGED_BACKUP_DIRECTORIES;
    }
}
