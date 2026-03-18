package de.kortty.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

class BackupManagerTest {

    @Test
    void managedBackupFilesIncludeSavedAiChats() {
        assertTrue(BackupManager.managedBackupFiles().contains("ai-chats.xml"));
    }
}
