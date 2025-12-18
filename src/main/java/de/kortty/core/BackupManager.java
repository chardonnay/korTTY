package de.kortty.core;

import de.kortty.model.GlobalSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.CipherOutputStream;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
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
     * 
     * @param targetDir Directory where backup should be saved
     * @param masterPassword Master password for encryption
     * @return Path to created backup file
     * @throws Exception if backup creation fails
     */
    public Path createBackup(Path targetDir, char[] masterPassword, SecretKey encryptionKey) throws Exception {
        logger.info("Creating backup to: {}", targetDir);
        
        // Ensure target directory exists
        Files.createDirectories(targetDir);
        
        Path backupFile = targetDir.resolve(BACKUP_FILENAME);
        
        // Rotate existing backup if present
        if (Files.exists(backupFile)) {
            rotateBackup(targetDir, backupFile);
        }
        
        // Create temporary unencrypted ZIP
        Path tempZip = Files.createTempFile("kortty-backup", ".zip");
        try {
            // Collect all config files
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(tempZip))) {
                addFileToZip(zos, configDir.resolve("connections.xml"), "connections.xml");
                addFileToZip(zos, configDir.resolve("credentials.xml"), "credentials.xml");
                addFileToZip(zos, configDir.resolve("gpg-keys.xml"), "gpg-keys.xml");
                addFileToZip(zos, configDir.resolve("global-settings.xml"), "global-settings.xml");
                addFileToZip(zos, configDir.resolve("master-password-hash"), "master-password-hash");
                
                // Include project files if they exist
                Path projectsDir = configDir.resolve("projects");
                if (Files.exists(projectsDir) && Files.isDirectory(projectsDir)) {
                    addDirectoryToZip(zos, projectsDir, "projects");
                }
            }
            
            // Encrypt the ZIP file
            encryptFile(tempZip, backupFile, encryptionKey);
            
            logger.info("Backup created successfully: {} ({} bytes)", 
                       backupFile, Files.size(backupFile));
            
            // Update settings
            settings.setLastBackupPath(targetDir.toString());
            settings.setLastBackupTime(System.currentTimeMillis());
            
            return backupFile;
            
        } finally {
            // Clean up temp file
            Files.deleteIfExists(tempZip);
        }
    }
    
    /**
     * Rotates existing backup by moving it to old-backups subdirectory with timestamp.
     */
    private void rotateBackup(Path targetDir, Path existingBackup) throws IOException {
        Path backupSubdir = targetDir.resolve(BACKUP_SUBDIR);
        Files.createDirectories(backupSubdir);
        
        // Generate timestamped filename
        String timestamp = LocalDateTime.now().format(BACKUP_DATE_FORMAT);
        String rotatedFilename = "kortty-backup_" + timestamp + ".zip";
        Path rotatedBackup = backupSubdir.resolve(rotatedFilename);
        
        // Move existing backup
        Files.move(existingBackup, rotatedBackup, StandardCopyOption.REPLACE_EXISTING);
        logger.info("Rotated old backup to: {}", rotatedBackup);
        
        // Clean up old backups according to max count
        cleanupOldBackups(backupSubdir);
    }
    
    /**
     * Removes oldest backups if count exceeds maximum.
     */
    private void cleanupOldBackups(Path backupSubdir) throws IOException {
        int maxBackups = settings.getMaxBackupCount();
        
        // 0 = unlimited
        if (maxBackups == 0) {
            logger.debug("Unlimited backups enabled, skipping cleanup");
            return;
        }
        
        // List all backup files
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
        
        // Delete oldest backups if over limit
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
     * Adds a single file to ZIP archive.
     */
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
    
    /**
     * Recursively adds directory to ZIP archive.
     */
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
     * Encrypts a file using AES-256-GCM.
     */
    private void encryptFile(Path input, Path output, SecretKey key) throws Exception {
        // Generate random IV
        byte[] iv = new byte[12];
        java.security.SecureRandom random = new java.security.SecureRandom();
        random.nextBytes(iv);
        
        // Initialize cipher
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, key, spec);
        
        // Write IV first, then encrypted data
        try (OutputStream os = Files.newOutputStream(output);
             CipherOutputStream cos = new CipherOutputStream(os, cipher);
             InputStream is = Files.newInputStream(input)) {
            
            // Write IV
            os.write(iv);
            
            // Encrypt and write data
            byte[] buffer = new byte[8192];
            int bytesRead;
            while ((bytesRead = is.read(buffer)) != -1) {
                cos.write(buffer, 0, bytesRead);
            }
        }
    }
}
