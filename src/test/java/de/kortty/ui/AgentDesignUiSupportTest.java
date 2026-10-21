package de.kortty.ui;

import de.kortty.agentdesign.AgentDesignCombination;
import de.kortty.model.GlobalSettings;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * The connection editor lists "use the default" first (naming the default), then every combination,
 * and keeps a stored combination that no longer exists so saving the connection does not drop it.
 */
class AgentDesignUiSupportTest {

    @Test
    void theDefaultEntryComesFirstAndNamesTheDefault() {
        GlobalSettings settings = new GlobalSettings();
        settings.setAiAgentDesignCombinations(List.of(new AgentDesignCombination(
            "custom-1", "Only the bar", false, "classic", "d08", "history", "fixed")));
        settings.setAiAgentDesignDefaultId("custom-1");

        List<AgentDesignUiSupport.Choice> choices = AgentDesignUiSupport.connectionChoices(settings, null);

        assertThat(choices.get(0).storedId()).isNull();
        assertThat(choices.get(0).label()).contains("Only the bar");
        assertThat(choices.stream().map(AgentDesignUiSupport.Choice::storedId).toList())
            .containsAtLeast("standard", "classic", "custom-1").inOrder();
        assertThat(AgentDesignUiSupport.selectedChoice(choices, null)).isEqualTo(choices.get(0));
        assertThat(AgentDesignUiSupport.selectedChoice(choices, " classic ").storedId()).isEqualTo("classic");
    }

    @Test
    void aStoredCombinationThatIsGoneIsKept() {
        List<AgentDesignUiSupport.Choice> choices =
            AgentDesignUiSupport.connectionChoices(new GlobalSettings(), "custom-deleted");

        AgentDesignUiSupport.Choice selected = AgentDesignUiSupport.selectedChoice(choices, "custom-deleted");
        assertThat(selected.storedId()).isEqualTo("custom-deleted");
        assertThat(selected.unavailable()).isTrue();
    }

    @Test
    void ownCombinationsWithoutANameGetOne() {
        AgentDesignCombination unnamed = new AgentDesignCombination("custom-2", " ", false, "d03", "none", "history", "fixed");
        assertThat(AgentDesignUiSupport.displayName(unnamed)).isNotEmpty();
        assertThat(AgentDesignUiSupport.newCustomId()).startsWith("custom-");
    }
}
