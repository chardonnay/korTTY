package de.kortty.core;

import de.kortty.memory.MemoryBlock;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/** The learned rules of a sent skill stay with its row, so the sent-context view can show what the model received. */
class AiSentSkillLearnedRulesTest {

    @Test
    void theLearnedRulesOfASkillStayWithItsRowInTheRun() {
        AiSentRunLedger ledger = new AiSentRunLedger();
        ledger.recordTurn(new AiSentContext(MemoryBlock.empty(), List.of(
            new AiSentContext.Skill("perl", "Perl", true, 10, 1, "- Use strict and warnings"))));
        ledger.recordTurn(new AiSentContext(MemoryBlock.empty(), List.of(
            new AiSentContext.Skill("perl", "Perl", true, 10, 1, "- Use strict and warnings"))));

        assertThat(ledger.snapshot().skills()).hasSize(1);
        assertThat(ledger.snapshot().skills().get(0).learnedRules()).isEqualTo("- Use strict and warnings");
        assertThat(ledger.snapshot().skills().get(0).turns()).isEqualTo(2);
    }

    @Test
    void aSkillWithoutRulesHasNoRuleText() {
        assertThat(new AiSentContext.Skill("perl", "Perl", true, 10, 1).learnedRules()).isEmpty();
        assertThat(new AiSentContext.Skill("perl", "Perl", true, 10, 1, null).learnedRules()).isEmpty();
    }
}
