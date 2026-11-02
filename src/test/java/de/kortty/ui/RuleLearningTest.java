package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.memory.RuleProposal;
import de.kortty.memory.RuleSource;
import org.testng.annotations.Test;

/** The parts of the rule learning that need no FX toolkit and no application settings. */
class RuleLearningTest {

    @Test
    void aManualRuleKeepsItsTextAndHasNoTopicHint() {
        RuleProposal proposal = RuleLearning.manualProposal("Antworte kurz.", null);

        assertThat(proposal.source()).isEqualTo(RuleSource.MANUAL);
        assertThat(proposal.text()).isEqualTo("Antworte kurz.");
        assertThat(proposal.connectionId()).isNull();
        assertThat(proposal.topicHint()).isNull();
    }

    @Test
    void aRuleIsNotAllowedWithoutSettings() {
        // Without the application's settings no place for a rule is known, so the entry stays disabled.
        assertThat(RuleLearning.manualRuleAllowed()).isFalse();
    }
}
