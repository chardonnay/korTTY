package de.kortty.core;

import org.testng.annotations.Test;
import static com.google.common.truth.Truth.assertThat;


public class BackupManagerTest {

    @Test
    public void managedBackupFilesIncludeSavedAiChats() {
        assertThat(BackupManager.managedBackupFiles()).contains("ai-chats.xml");
    }
}
