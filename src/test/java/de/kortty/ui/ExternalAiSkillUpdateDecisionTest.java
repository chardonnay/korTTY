package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import org.testng.annotations.Test;

/** The outcomes the update dialog offers: a changed skill is never offered the update. */
class ExternalAiSkillUpdateDecisionTest {

    @Test
    void anUnchangedSkillIsOfferedOnlyTheUpdate() {
        assertThat(ExternalAiSkillUpdateDecision.offeredFor(false))
            .containsExactly(ExternalAiSkillUpdateDecision.APPLY);
    }

    @Test
    void aChangedSkillIsOfferedACopyOrASkipButNeverTheUpdate() {
        assertThat(ExternalAiSkillUpdateDecision.offeredFor(true))
            .containsExactly(ExternalAiSkillUpdateDecision.COPY, ExternalAiSkillUpdateDecision.SKIP).inOrder();
        assertThat(ExternalAiSkillUpdateDecision.offeredFor(true)).doesNotContain(ExternalAiSkillUpdateDecision.APPLY);
    }
}
