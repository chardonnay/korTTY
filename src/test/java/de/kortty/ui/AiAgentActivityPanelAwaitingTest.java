package de.kortty.ui;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class AiAgentActivityPanelAwaitingTest {

    @Test
    void everyKindOfPromptCountsAsWaitingForTheUser() {
        assertThat(AiAgentActivityPanel.awaitsInput(false, false, false)).isFalse();
        assertThat(AiAgentActivityPanel.awaitsInput(true, false, false)).isTrue();
        assertThat(AiAgentActivityPanel.awaitsInput(false, true, false)).isTrue();
        // The solution card blocks the run like an approval does: the panel grows for it and Pause is off.
        assertThat(AiAgentActivityPanel.awaitsInput(false, false, true)).isTrue();
    }

    @Test
    void ctrlCAndEscGoToTheCardOnlyWhileTheCardWaitsAndTheControlIsInIt() {
        assertThat(AiAgentActivityPanel.keyGoesToSolutionCard(true, true, true)).isTrue();
        assertThat(AiAgentActivityPanel.keyGoesToSolutionCard(false, true, true)).isFalse();
        assertThat(AiAgentActivityPanel.keyGoesToSolutionCard(true, false, true)).isFalse();
        assertThat(AiAgentActivityPanel.keyGoesToSolutionCard(true, true, false)).isFalse();
    }
}
