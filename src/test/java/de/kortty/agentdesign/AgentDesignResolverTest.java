package de.kortty.agentdesign;

import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * A pane shows the connection's combination, else the global default, else the standard; ids that name
 * nothing are skipped, and modules this version cannot show are replaced by the standard's.
 */
class AgentDesignResolverTest {

    private static final AgentDesignCombination OWN = new AgentDesignCombination(
        "custom-1", "Mine", false, "classic", "d08", "history", "fixed");

    @Test
    void withoutAnyChoiceTheStandardIsUsed() {
        AgentDesignResolver.Resolution resolution = AgentDesignResolver.resolve(null, null, List.of());

        assertThat(resolution.combination()).isEqualTo(AgentDesignPresets.STANDARD);
        assertThat(resolution.source()).isEqualTo(AgentDesignResolver.Source.BUILT_IN);
        assertThat(resolution.substituted()).isEmpty();
    }

    @Test
    void theConnectionWinsOverTheGlobalDefault() {
        AgentDesignResolver.Resolution resolution = AgentDesignResolver.resolve("custom-1", "classic", List.of(OWN));

        assertThat(resolution.combination()).isEqualTo(OWN);
        assertThat(resolution.source()).isEqualTo(AgentDesignResolver.Source.CONNECTION);
    }

    @Test
    void theGlobalDefaultAppliesWhenTheConnectionPicksNone() {
        AgentDesignResolver.Resolution resolution = AgentDesignResolver.resolve(" ", "classic", List.of(OWN));

        assertThat(resolution.combination()).isEqualTo(AgentDesignPresets.CLASSIC);
        assertThat(resolution.source()).isEqualTo(AgentDesignResolver.Source.GLOBAL_DEFAULT);
    }

    @Test
    void anIdThatNamesNothingIsSkipped() {
        AgentDesignResolver.Resolution deletedConnectionChoice =
            AgentDesignResolver.resolve("custom-gone", "custom-1", List.of(OWN));
        assertThat(deletedConnectionChoice.combination()).isEqualTo(OWN);
        assertThat(deletedConnectionChoice.source()).isEqualTo(AgentDesignResolver.Source.GLOBAL_DEFAULT);

        AgentDesignResolver.Resolution deletedDefault = AgentDesignResolver.resolve(null, "custom-gone", null);
        assertThat(deletedDefault.combination()).isEqualTo(AgentDesignPresets.STANDARD);
    }

    @Test
    void modulesThisVersionCannotShowAreReplacedByTheStandards() {
        AgentDesignCombination future = new AgentDesignCombination(
            "custom-2", "Future", false, "d02", "d99", "d14", "fixed");

        AgentDesignResolver.Resolution resolution = AgentDesignResolver.resolve("custom-2", null, List.of(future));

        assertThat(resolution.combination().historyId()).isEqualTo("d03");
        assertThat(resolution.combination().statusId()).isEqualTo("d08");
        assertThat(resolution.combination().approvalId()).isEqualTo("history");
        assertThat(resolution.combination().arrangementId()).isEqualTo("fixed");
        assertThat(resolution.combination().id()).isEqualTo("custom-2");
        assertThat(resolution.substituted())
            .containsExactly(AgentRole.HISTORY, AgentRole.STATUS, AgentRole.APPROVAL).inOrder();
    }

    @Test
    void builtInIdsCannotBeShadowedByAnOwnCombination() {
        AgentDesignCombination impostor = new AgentDesignCombination(
            "standard", "Impostor", false, "classic", "none", "history", "fixed");

        assertThat(AgentDesignResolver.find("standard", List.of(impostor)).orElseThrow())
            .isEqualTo(AgentDesignPresets.STANDARD);
    }
}
