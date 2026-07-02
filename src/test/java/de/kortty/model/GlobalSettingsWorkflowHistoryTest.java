package de.kortty.model;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class GlobalSettingsWorkflowHistoryTest {

    @Test
    void entriesAreNewestFirstAndDeduplicated() {
        GlobalSettings settings = new GlobalSettings();
        settings.addWorkflowInstructionsHistoryEntry("use rsync");
        settings.addWorkflowInstructionsHistoryEntry("add logging");
        settings.addWorkflowInstructionsHistoryEntry("use rsync");

        assertThat(settings.getWorkflowInstructionsHistory())
            .containsExactly("use rsync", "add logging")
            .inOrder();
    }

    @Test
    void historyIsCappedAtTenEntries() {
        GlobalSettings settings = new GlobalSettings();
        for (int i = 1; i <= 12; i++) {
            settings.addWorkflowInstructionsHistoryEntry("instruction " + i);
        }
        assertThat(settings.getWorkflowInstructionsHistory()).hasSize(10);
        assertThat(settings.getWorkflowInstructionsHistory().get(0)).isEqualTo("instruction 12");
        assertThat(settings.getWorkflowInstructionsHistory()).doesNotContain("instruction 1");
        assertThat(settings.getWorkflowInstructionsHistory()).doesNotContain("instruction 2");
    }

    @Test
    void blankAndNullEntriesAreIgnoredAndValuesTrimmed() {
        GlobalSettings settings = new GlobalSettings();
        settings.addWorkflowInstructionsHistoryEntry(null);
        settings.addWorkflowInstructionsHistoryEntry("   ");
        settings.addWorkflowInstructionsHistoryEntry("  keep me  ");

        assertThat(settings.getWorkflowInstructionsHistory()).containsExactly("keep me");
    }
}
