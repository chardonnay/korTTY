package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.StoredCredential;
import de.kortty.model.GPGKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.lingala.zip4j.ZipFile;
import net.lingala.zip4j.model.ZipParameters;
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
                throw new Exception("Kein Passwort für Backup-Verschlüsselung ausgewählt!");
            }
            if (credentialManager == null || credentialManager.findCredentialById(settings.getBackupCredentialId()).isEmpty()) {
                throw new Exception("Ausgewähltes Passwort nicht gefunden!");
            }
        } else if (settings.getBackupEncryptionType() == GlobalSettings.BackupEncryptionType.GPG) {
            if (settings.getBackupGpgKeyId() == null) {
                throw new Exception("Kein GPG-Schlüssel für Backup-Verschlüsselung ausgewählt!");
            }
            GPGKey key = gpgKeyManager.getAllKeys().stream()
                .filter(k -> k.getId().equals(settings.getBackupGpgKeyId()))
                .findFirst()
                .orElseThrow(() -> new Exception("Ausgewählter GPG-Schlüssel nicht gefunden!"));
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
            throw new Exception("Passwort konnte nicht entschlüsselt werden!");
        }
        
        // Create password-protected ZIP using zip4j
        ZipFile zipFile = new ZipFile(backupFile.toFile(), password.toCharArray());
        
        ZipParameters zipParameters = new ZipParameters();
        zipParameters.setEncryptFiles(true);
        zipParameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
        
        // Add all config files
        addFileToPasswordZip(zipFile, configDir.resolve("connections.xml"), zipParameters);
        addFileToPasswordZip(zipFile, configDir.resolve("credentials.xml"), zipParameters);
        addFileToPasswordZip(zipFile, configDir.resolve("gpg-keys.xml"), zipParameters);
        addFileToPasswordZip(zipFile, configDir.resolve("global-settings.xml"), zipParameters);
        addFileToPasswordZip(zipFile, configDir.resolve("master-password-hash"), zipParameters);
        
        // Add projects directory if exists
        Path projectsDir = configDir.resolve("projects");
        if (Files.exists(projectsDir) && Files.isDirectory(projectsDir)) {
            zipFile.addFolder(projectsDir.toFile(), zipParameters);
        }
        
        logger.info("Created password-protected backup");
    }
    
    private void addFileToPasswordZip(ZipFile zipFile, Path file, ZipParameters parameters) throws Exception {
        if (Files.exists(file)) {
            zipFile.addFile(file.toFile(), parameters);
            logger.debug("Added to backup: {}", file.getFileName());
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
                addFileToZip(zos, configDir.resolve("connections.xml"), "connections.xml");
                addFileToZip(zos, configDir.resolve("credentials.xml"), "credentials.xml");
                addFileToZip(zos, configDir.resolve("gpg-keys.xml"), "gpg-keys.xml");
                addFileToZip(zos, configDir.resolve("global-settings.xml"), "global-settings.xml");
                addFileToZip(zos, configDir.resolve("master-password-hash"), "master-password-hash");
                
                Path projectsDir = configDir.resolve("projects");
                if (Files.exists(projectsDir) && Files.isDirectory(projectsDir)) {
                    addDirectoryToZip(zos, projectsDir, "projects");
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
}
