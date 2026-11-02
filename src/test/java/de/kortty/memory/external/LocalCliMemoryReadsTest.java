package de.kortty.memory.external;

import de.kortty.codingagent.CodingAgentKind;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class LocalCliMemoryReadsTest {

    @Test
    void claudeCodeReadsItsOwnFilesAndNoOthers() {
        assertThat(LocalCliMemoryReads.readBy(CodingAgentKind.CLAUDE_CODE))
            .containsExactly(ExternalMemoryTool.CLAUDE_CODE);
    }

    @Test
    void codexReadsItsAgentsMdAndTheProjectAgentsMd() {
        assertThat(LocalCliMemoryReads.readBy(CodingAgentKind.CODEX))
            .containsExactly(ExternalMemoryTool.CODEX, ExternalMemoryTool.AGENTS_MD);
    }

    @Test
    void aProviderIdMapsToItsKind() {
        assertThat(LocalCliMemoryReads.readByProvider("codex-cli"))
            .containsExactly(ExternalMemoryTool.CODEX, ExternalMemoryTool.AGENTS_MD);
        assertThat(LocalCliMemoryReads.readByProvider("lm-studio-cli")).isEmpty();
        assertThat(LocalCliMemoryReads.readByProvider(null)).isEmpty();
    }

    @Test
    void copilotIsNotInTheTableBecauseItsFilesAreNotVerified() {
        assertThat(LocalCliMemoryReads.readBy(CodingAgentKind.COPILOT)).isEmpty();
    }

    @Test
    void onlyALocalCliProfileExcludesFiles() {
        AiProfile cli = new AiProfile();
        cli.setConnectionMode(AiConnectionMode.LOCAL_CLI);
        cli.setCliProviderId("claude-code");
        AiProfile api = new AiProfile();
        api.setCliProviderId("claude-code");

        assertThat(LocalCliMemoryReads.excludedFor(cli)).containsExactly(ExternalMemoryTool.CLAUDE_CODE);
        assertThat(LocalCliMemoryReads.excludedFor(api)).isEmpty();
        assertThat(LocalCliMemoryReads.excludedFor(null)).isEmpty();
    }
}
