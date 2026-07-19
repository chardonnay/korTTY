package de.kortty.core;

import de.kortty.model.GlobalSettings;
import org.testng.annotations.Test;

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
    public void managedBackupFilesIncludeOnlyRegenerableLocalAiMetadata() {
        assertThat(BackupManager.managedBackupFiles()).containsAtLeast(
            "llm/models.xml",
            "rag/stores.json");
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("llm/runtime");
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("llm/models");
        assertThat(BackupManager.managedBackupFiles()).doesNotContain("rag/index");
    }
}
