package de.kortty.core.agent;

import de.kortty.core.AiSentContext;
import de.kortty.core.SessionJournalRedactor;
import de.kortty.memory.MemoryBlock;
import de.kortty.memory.MemoryBlock.Origin;
import de.kortty.memory.MemoryBlock.SentPart;
import de.kortty.memory.MemoryBlock.SentPiece;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The memory of an agent run as its prompts send it, and the ledger the run records its turns in. */
class AgentOutboundContextSentMemoryTest {

    private static final String SECRET = "Memo-Passw0rd-4711";

    private static MemoryBlock memoryWithSecret() {
        String text = "<kortty_user_memory>\nThe vault is " + SECRET + ".\n</kortty_user_memory>";
        return new MemoryBlock(text, 12, List.of(), List.of(new SentPart(false, 0, null)), 1,
            List.of(new SentPiece(Origin.GLOBAL, 0, null, null, "The vault is " + SECRET + ".", 9, false)));
    }

    @Test
    void theSentMemoryMasksTheRunsSecretsInTheTextAndInTheParts() {
        SessionJournalRedactor secrets = new SessionJournalRedactor();
        secrets.addSecret(SECRET);
        AgentOutboundContext context = AgentOutboundContext.forRun(cloud(), memoryWithSecret(), null, secrets);

        MemoryBlock sent = context.sentMemory();

        assertThat(sent.text()).doesNotContain(SECRET);
        assertThat(sent.sentPieces()).hasSize(1);
        assertThat(sent.sentPieces().get(0).text()).doesNotContain(SECRET);
        assertThat(sent.sentPieces().get(0).text()).startsWith("The vault is");
        assertThat(sent.sentParts()).containsExactly(new SentPart(false, 0, null));
        assertThat(sent.omittedCount()).isEqualTo(1);
    }

    @Test
    void aRunWithoutMemoryHasNothingSentAndNothingRecorded() {
        AgentOutboundContext context = AgentOutboundContext.forRun(cloud(), null);

        assertThat(context.sentMemory().isEmpty()).isTrue();
        assertThat(context.sentLedger().snapshot().isEmpty()).isTrue();
    }

    @Test
    void theRunsLedgerKeepsTheMaskedMemoryAndTheSkillsOfEveryTurn() {
        SessionJournalRedactor secrets = new SessionJournalRedactor();
        secrets.addSecret(SECRET);
        AgentOutboundContext context = AgentOutboundContext.forRun(cloud(), memoryWithSecret(), null, secrets);

        context.sentLedger().recordTurn(new AiSentContext(context.sentMemory(),
            List.of(new AiSentContext.Skill("s1", "Shell", true, 8, 1))));
        context.sentLedger().recordTurn(new AiSentContext(context.sentMemory(),
            List.of(new AiSentContext.Skill("s1", "Shell", true, 8, 1))));

        AiSentContext snapshot = context.sentLedger().snapshot();
        assertThat(snapshot.memory().text()).doesNotContain(SECRET);
        assertThat(snapshot.skills()).containsExactly(new AiSentContext.Skill("s1", "Shell", true, 8, 2));
    }

    private static AiProfile cloud() {
        AiProfile profile = new AiProfile();
        profile.setConnectionMode(AiConnectionMode.HTTP_API);
        profile.setApiUrl("https://api.example.com/v1");
        return profile;
    }
}
