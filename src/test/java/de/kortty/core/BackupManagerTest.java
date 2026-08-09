package de.kortty.core;

import de.kortty.model.GlobalSettings;
import de.kortty.model.StoredCredential;
import de.kortty.security.MasterPasswordManager;
import org.testng.annotations.Test;

import net.lingala.zip4j.model.ZipParameters;
import net.lingala.zip4j.model.enums.EncryptionMethod;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;


public class BackupManagerTest {

    @Test
    public void importRejectsZipEntriesEscapingTheExtractionDirectory() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-zipslip-");
        Path malicious = root.resolve("backup.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(malicious))) {
            zip.putNextEntry(new ZipEntry("../escaped.txt"));
            zip.write("attacker content".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        BackupManager manager = new BackupManager(root.resolve("config"), new GlobalSettings());

        Exception failure = expectThrows(Exception.class, () ->
            manager.importBackup(malicious, null, false));

        assertThat(failure).hasMessageThat().contains("Blocked backup ZIP entry");
        // The traversal target must not exist anywhere above the extraction directory.
        try (var walk = Files.walk(root)) {
            assertThat(walk.filter(path -> path.getFileName().toString().equals("escaped.txt"))
                .toList()).isEmpty();
        }
    }

    @Test
    public void managedBackupFilesIncludeSavedAiChats() {
        assertThat(BackupManager.managedBackupFiles()).contains("ai-chats.xml");
    }

    @Test
    public void managedBackupFilesPreserveTrustedSshHostKeys() {
        assertThat(BackupManager.managedBackupFiles()).contains("ssh-host-keys.properties");
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("ssh-host-keys.properties.lock");
    }

    @Test
    public void managedBackupContentCoversSshKeyReferencesAndCopiedKeyFiles() {
        assertThat(BackupManager.managedBackupFiles()).contains("ssh-keys.xml");
        // The copied key FILES are included as a directory, like projects/.
        assertThat(BackupManager.managedBackupDirectories()).containsExactly("projects", "ssh-keys");
    }

    @Test
    public void managedBackupFilesIncludeTheMasterKeyFile() {
        // The list once carried "master-password-hash" — a name that never existed on disk —
        // so every backup silently omitted the master-password file and a restore onto a
        // fresh profile could not decrypt anything.
        assertThat(BackupManager.managedBackupFiles()).contains(MasterPasswordManager.MASTER_KEY_FILE);
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("master-password-hash");
        // The obfuscated auto-login password is deliberately kept out of backups.
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("master.autounlock");
    }

    @Test
    public void passwordBackupContainsTheMasterKeyFileAndRestoresIt() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-masterkey-");
        Path configDir = Files.createDirectories(root.resolve("config"));
        byte[] masterKeyBytes = "salt-and-hash".getBytes(StandardCharsets.UTF_8);
        Files.write(configDir.resolve(MasterPasswordManager.MASTER_KEY_FILE), masterKeyBytes);
        Files.writeString(configDir.resolve("connections.xml"), "<connections/>");
        Files.createDirectories(configDir.resolve("projects"));
        Files.writeString(configDir.resolve("projects/demo.kortty"), "<project/>");
        Files.createDirectories(configDir.resolve("ssh-keys"));
        Files.writeString(configDir.resolve("ssh-keys/id_test"), "fake key material");

        char[] masterPassword = "master-pw".toCharArray();
        CredentialManager credentialManager = new CredentialManager(configDir);
        StoredCredential credential = new StoredCredential(
            "backup", "user", StoredCredential.Environment.PRODUCTION);
        credentialManager.setPassword(credential, "backup-pw", masterPassword);
        credentialManager.addCredential(credential);

        GlobalSettings settings = new GlobalSettings();
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.PASSWORD);
        settings.setBackupCredentialId(credential.getId());

        Path backupZip = new BackupManager(configDir, settings)
            .createBackup(root.resolve("target"), credentialManager, null, masterPassword);

        try (net.lingala.zip4j.ZipFile zip =
                 new net.lingala.zip4j.ZipFile(backupZip.toFile(), "backup-pw".toCharArray())) {
            assertThat(zip.getFileHeaders().stream()
                    .map(net.lingala.zip4j.model.FileHeader::getFileName)
                    .toList())
                .containsAtLeast(
                    MasterPasswordManager.MASTER_KEY_FILE,
                    "projects/demo.kortty",
                    "ssh-keys/id_test");
            // New backups use AES-256, not legacy ZipCrypto — the archive carries raw key files.
            assertThat(zip.getFileHeader(MasterPasswordManager.MASTER_KEY_FILE)
                    .getEncryptionMethod())
                .isEqualTo(EncryptionMethod.AES);
        }

        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        int imported = new BackupManager(restoreDir, new GlobalSettings())
            .importBackup(backupZip, "backup-pw", true);

        assertThat(imported).isAtLeast(4);
        assertThat(Files.readAllBytes(restoreDir.resolve(MasterPasswordManager.MASTER_KEY_FILE)))
            .isEqualTo(masterKeyBytes);
        // Directly under projects/ — not nested into projects/projects/ (former restore bug).
        assertThat(Files.readString(restoreDir.resolve("projects/demo.kortty")))
            .isEqualTo("<project/>");
        assertThat(Files.exists(restoreDir.resolve("projects/projects"))).isFalse();
        assertThat(Files.readString(restoreDir.resolve("ssh-keys/id_test")))
            .isEqualTo("fake key material");
    }

    @Test
    public void restoreMergesSshKeysWithoutDeletingOrOverwriting() throws Exception {
        Path root = Files.createTempDirectory("kortty-backup-keymerge-");
        Path configDir = Files.createDirectories(root.resolve("config"));
        Files.createDirectories(configDir.resolve("ssh-keys"));
        Files.writeString(configDir.resolve("ssh-keys/id_backup"), "from backup");
        Files.writeString(configDir.resolve("ssh-keys/id_shared"), "backup version");

        char[] masterPassword = "master-pw".toCharArray();
        CredentialManager credentialManager = new CredentialManager(configDir);
        StoredCredential credential = new StoredCredential(
            "backup", "user", StoredCredential.Environment.PRODUCTION);
        credentialManager.setPassword(credential, "backup-pw", masterPassword);
        credentialManager.addCredential(credential);
        GlobalSettings settings = new GlobalSettings();
        settings.setBackupEncryptionType(GlobalSettings.BackupEncryptionType.PASSWORD);
        settings.setBackupCredentialId(credential.getId());
        Path backupZip = new BackupManager(configDir, settings)
            .createBackup(root.resolve("target"), credentialManager, null, masterPassword);

        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        Files.createDirectories(restoreDir.resolve("ssh-keys"));
        Files.writeString(restoreDir.resolve("ssh-keys/id_local_only"), "must survive");
        Files.writeString(restoreDir.resolve("ssh-keys/id_shared"), "local version");

        new BackupManager(restoreDir, new GlobalSettings())
            .importBackup(backupZip, "backup-pw", false);

        // Merge semantics: local-only keys survive, existing files are not overwritten
        // without the overwrite flag, and new keys from the backup arrive.
        assertThat(Files.readString(restoreDir.resolve("ssh-keys/id_local_only")))
            .isEqualTo("must survive");
        assertThat(Files.readString(restoreDir.resolve("ssh-keys/id_shared")))
            .isEqualTo("local version");
        assertThat(Files.readString(restoreDir.resolve("ssh-keys/id_backup")))
            .isEqualTo("from backup");
    }

    @Test
    public void importToleratesLegacyBackupsWithoutTheMasterKeyFile() throws Exception {
        // Backups created while the list named the non-existent file contain no master-key
        // entry at all; restoring them must keep working and simply not create the file.
        Path root = Files.createTempDirectory("kortty-backup-legacy-");
        Path legacyZip = root.resolve("kortty-backup.zip");
        try (net.lingala.zip4j.ZipFile zip =
                 new net.lingala.zip4j.ZipFile(legacyZip.toFile(), "backup-pw".toCharArray())) {
            Path connections = root.resolve("connections.xml");
            Files.writeString(connections, "<connections/>");
            ZipParameters parameters = new ZipParameters();
            parameters.setEncryptFiles(true);
            parameters.setEncryptionMethod(EncryptionMethod.ZIP_STANDARD);
            zip.addFile(connections.toFile(), parameters);
        }

        Path restoreDir = Files.createDirectories(root.resolve("restore"));
        int imported = new BackupManager(restoreDir, new GlobalSettings())
            .importBackup(legacyZip, "backup-pw", true);

        assertThat(imported).isEqualTo(1);
        assertThat(Files.exists(restoreDir.resolve("connections.xml"))).isTrue();
        assertThat(Files.exists(restoreDir.resolve(MasterPasswordManager.MASTER_KEY_FILE))).isFalse();
    }

    @Test
    public void managedBackupFilesIncludeOnlyRegenerableLocalAiMetadata() {
        assertThat(BackupManager.managedBackupFiles()).containsAtLeast(
            "llm/models.xml",
            "rag/stores.json");
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("llm/runtime");
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("llm/models");
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("rag/index");
    }
}
