package de.kortty.core;

import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


public class BackupManagerTest {

    @Test
    public void managedBackupFilesIncludeSavedAiChats() {
        assertThat(BackupManager.managedBackupFiles()).contains("ai-chats.xml");
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
