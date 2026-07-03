package de.kortty.ui;

import de.kortty.jobscheduler.JobActionType;
import de.kortty.jobscheduler.ScheduledJob;
import de.kortty.model.SavedSwarmMessage;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

class SwarmScheduleDraftSupportTest {

    private static ServerConnection ssh(String name) {
        ServerConnection connection = new ServerConnection(name, name + ".example.com", 22, "root");
        return connection;
    }

    @Test
    void buildDraftFillsTypePromptProfileTargetsAndSafeDefaults() {
        ScheduledJob draft = SwarmScheduleDraftSupport.buildDraft(
            "AI Swarm", "how much RAM?", "prof-1", List.of(ssh("a"), ssh("b")), true);

        assertThat(draft).isNotNull();
        assertThat(draft.getName()).isEqualTo("AI Swarm");
        assertThat(draft.isEnabled()).isFalse();
        assertThat(draft.getAction().getType()).isEqualTo(JobActionType.AI_SWARM);
        assertThat(draft.getAction().getAiPrompt()).isEqualTo("how much RAM?");
        assertThat(draft.getAction().getAiProfileId()).isEqualTo("prof-1");
        assertThat(draft.getAction().isSwarmReadOnly()).isTrue();
        assertThat(draft.getAction().effectiveSwarmParallelism()).isEqualTo(4);
        assertThat(draft.getTargetConnectionIds()).hasSize(2);
    }

    @Test
    void localShellConnectionsAreFilteredOut() {
        ServerConnection local = ssh("local");
        local.setProtocol(de.kortty.model.ConnectionProtocol.LOCAL_SHELL);
        assertThat(SwarmScheduleDraftSupport.sshConnectionIds(List.of(local, ssh("a")))).hasSize(1);
        // only local-shell targets -> no schedulable job
        assertThat(SwarmScheduleDraftSupport.buildDraft("t", "p", "prof", List.of(local), true)).isNull();
    }

    @Test
    void draftRequiresAPrompt() {
        assertThat(SwarmScheduleDraftSupport.buildDraft("t", "  ", "prof", List.of(ssh("a")), true)).isNull();
        assertThat(SwarmScheduleDraftSupport.buildDraft("t", null, "prof", List.of(ssh("a")), true)).isNull();
    }

    @Test
    void promptResolutionPrefersComposerTextOverLastUserMessage() {
        SavedSwarmMessage user = new SavedSwarmMessage();
        user.setRole(SavedSwarmMessage.ROLE_USER);
        user.setContent("old prompt");
        SavedSwarmMessage assistant = new SavedSwarmMessage();
        assistant.setRole(SavedSwarmMessage.ROLE_ASSISTANT);
        assistant.setContent("answer");
        List<SavedSwarmMessage> messages = List.of(user, assistant);

        assertThat(SwarmScheduleDraftSupport.resolvePromptForDraft(messages, "typed now"))
            .isEqualTo("typed now");
        assertThat(SwarmScheduleDraftSupport.resolvePromptForDraft(messages, "  "))
            .isEqualTo("old prompt");
        assertThat(SwarmScheduleDraftSupport.resolvePromptForDraft(List.of(), null)).isNull();
    }
}
