package de.kortty.ui;

import de.kortty.model.SavedAiChatMessage;
import de.kortty.model.SavedSwarmMessage;
import de.kortty.model.SavedSwarmServerSummary;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SwarmChatExportSupportTest {

    @Test
    void mapsRolesFieldsAndOrderOneToOne() {
        SavedSwarmMessage user = new SavedSwarmMessage();
        user.setRole(SavedSwarmMessage.ROLE_USER);
        user.setContent("how much RAM?");
        user.setCreatedAt(1000L);

        SavedSwarmMessage assistant = new SavedSwarmMessage();
        assistant.setRole(SavedSwarmMessage.ROLE_ASSISTANT);
        assistant.setContent("| Server | RAM |");
        assistant.setCreatedAt(2000L);
        assistant.setAiProfileId("prof-1");
        assistant.setAiProfileName("Claude");
        SavedSwarmServerSummary summary = new SavedSwarmServerSummary();
        summary.setServerDisplayName("srv-1");
        assistant.setServerSummaries(new ArrayList<>(List.of(summary)));

        List<SavedAiChatMessage> converted =
            SwarmChatExportSupport.toChatMessages(List.of(user, assistant));

        assertThat(converted).hasSize(2);
        assertThat(converted.get(0).getRole()).isEqualTo(SavedAiChatMessage.ROLE_USER);
        assertThat(converted.get(0).getContent()).isEqualTo("how much RAM?");
        assertThat(converted.get(0).getCreatedAt()).isEqualTo(1000L);
        assertThat(converted.get(1).getRole()).isEqualTo(SavedAiChatMessage.ROLE_ASSISTANT);
        assertThat(converted.get(1).getContent()).isEqualTo("| Server | RAM |");
        assertThat(converted.get(1).getAiProfileId()).isEqualTo("prof-1");
        assertThat(converted.get(1).getAiProfileName()).isEqualTo("Claude");
    }

    @Test
    void toleratesNullListAndNullEntries() {
        assertThat(SwarmChatExportSupport.toChatMessages(null)).isEmpty();
        SavedSwarmMessage message = new SavedSwarmMessage();
        message.setRole(SavedSwarmMessage.ROLE_USER);
        message.setContent("hi");
        assertThat(SwarmChatExportSupport.toChatMessages(Arrays.asList(null, message))).hasSize(1);
    }
}
