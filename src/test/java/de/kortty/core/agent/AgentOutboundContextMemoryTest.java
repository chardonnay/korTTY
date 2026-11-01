package de.kortty.core.agent;

import de.kortty.core.SessionJournalRedactor;
import de.kortty.memory.MemoryBlock;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The memory block a run carries: kept as given, and masked with the run's secrets like any other prompt text. */
class AgentOutboundContextMemoryTest {

    private static final String SECRET = "Memo-Passw0rd-4711";

    @Test
    void aRunWithoutMemoryCarriesAnEmptyBlock() {
        AgentOutboundContext context = AgentOutboundContext.forRun(cloud(), null);

        assertThat(context.memory().isEmpty()).isTrue();
    }

    @Test
    void theBlockOfTheRunIsKeptAsGiven() {
        MemoryBlock block = new MemoryBlock("<kortty_user_memory>\nA rule.\n</kortty_user_memory>", 7, List.of(), 0);

        AgentOutboundContext context = AgentOutboundContext.forRun(cloud(), block, null);

        assertThat(context.memory()).isSameInstanceAs(block);
    }

    @Test
    void theBlockIsMaskedWithTheRunsSecretsLikeAnyPrompt() {
        SessionJournalRedactor secrets = new SessionJournalRedactor();
        secrets.addSecret(SECRET);
        MemoryBlock block = new MemoryBlock("<kortty_user_memory>\nThe vault is " + SECRET + ".\n</kortty_user_memory>",
            9, List.of(), 0);
        AgentOutboundContext context = AgentOutboundContext.forRun(cloud(), block, null, secrets);

        String masked = context.mask(context.memory().text());

        assertThat(masked).doesNotContain(SECRET);
        assertThat(masked).contains("The vault is");
    }

    private static AiProfile cloud() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("https://api.example.com/v1");
        return profile;
    }
}
