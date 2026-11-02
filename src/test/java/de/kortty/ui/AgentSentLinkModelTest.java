package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.core.AiSentContext;
import org.testng.annotations.Test;

import java.util.List;

/** The "Mitgesendet" line of the designs: a turn replaces the earlier copy, and a new run clears it. */
class AgentSentLinkModelTest {

    private static AiSentContext withSkill(String name, int tokens) {
        return new AiSentContext(null, List.of(new AiSentContext.Skill("id-" + name, name, true, tokens, 1)));
    }

    @Test
    void nothingIsShownBeforeATurnSentSomething() {
        AgentSentLinkModel model = new AgentSentLinkModel();

        assertThat(model.context()).isNull();
    }

    @Test
    void aTurnReplacesTheEarlierCopyOfTheRun() {
        AgentSentLinkModel model = new AgentSentLinkModel();
        model.publish(withSkill("Shell Style", 30));
        AiSentContext latest = withSkill("Shell Style", 55);

        model.publish(latest);

        assertThat(model.context()).isSameInstanceAs(latest);
    }

    @Test
    void aTurnThatSentNothingKeepsTheEarlierCopy() {
        AgentSentLinkModel model = new AgentSentLinkModel();
        AiSentContext earlier = withSkill("Shell Style", 30);
        model.publish(earlier);

        model.publish(AiSentContext.empty());
        model.publish(null);

        assertThat(model.context()).isSameInstanceAs(earlier);
    }

    @Test
    void aNewRunClearsTheContextOfTheEarlierRun() {
        AgentSentLinkModel model = new AgentSentLinkModel();
        model.publish(withSkill("Shell Style", 30));

        model.reset();

        assertThat(model.context()).isNull();
    }

    @Test
    void theContextStaysAfterTheRunEnded() {
        AgentSentLinkModel model = new AgentSentLinkModel();
        AiSentContext sent = withSkill("Shell Style", 30);
        model.publish(sent);

        // Nothing touches the model when the run ends; only the next run's start resets it.
        assertThat(model.context()).isSameInstanceAs(sent);
    }
}
