package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/** Which rule set a pane shows: pane choice, then connection, then global default, then none. */
class HighlightSetResolutionTest {

    private static final Predicate<String> KNOWN = Set.of(HighlightBuiltinSets.ERRORS, HighlightBuiltinSets.NETWORK,
        "user-1")::contains;

    private TerminalHighlightService service;

    @BeforeMethod
    void setUp() {
        service = new TerminalHighlightService(TerminalHighlightService.defaultScheduler());
    }

    @AfterMethod
    void tearDown() {
        service.stop();
    }

    private static String resolve(String pane, String connection, String globalDefault) {
        return TerminalHighlightService.resolveSetId(true, KNOWN, pane, connection, globalDefault);
    }

    @Test
    void thePaneChoiceWinsOverTheGlobalDefault() {
        assertThat(resolve("user-1", null, HighlightBuiltinSets.ERRORS)).isEqualTo("user-1");
        assertThat(resolve(HighlightBuiltinSets.NETWORK, null, HighlightBuiltinSets.ERRORS))
            .isEqualTo(HighlightBuiltinSets.NETWORK);
    }

    @Test
    void withoutAPaneChoiceTheGlobalDefaultApplies() {
        assertThat(resolve(null, null, HighlightBuiltinSets.ERRORS)).isEqualTo(HighlightBuiltinSets.ERRORS);
        assertThat(resolve("  ", null, HighlightBuiltinSets.ERRORS)).isEqualTo(HighlightBuiltinSets.ERRORS);
    }

    @Test
    void theConnectionSitsBetweenThePaneAndTheDefault() {
        assertThat(resolve(null, HighlightBuiltinSets.NETWORK, HighlightBuiltinSets.ERRORS))
            .isEqualTo(HighlightBuiltinSets.NETWORK);
        assertThat(resolve("user-1", HighlightBuiltinSets.NETWORK, HighlightBuiltinSets.ERRORS)).isEqualTo("user-1");
    }

    @Test
    void noneIsAnExplicitOffThatBeatsTheLevelsBelow() {
        assertThat(resolve(TerminalHighlightService.NONE_ID, null, HighlightBuiltinSets.ERRORS)).isNull();
        assertThat(resolve(null, TerminalHighlightService.NONE_ID, HighlightBuiltinSets.ERRORS)).isNull();
        assertThat(resolve(HighlightBuiltinSets.ERRORS, TerminalHighlightService.NONE_ID, null))
            .isEqualTo(HighlightBuiltinSets.ERRORS);
    }

    @Test
    void anUnknownIdFallsThroughToTheNextLevel() {
        assertThat(resolve("deleted-set", null, HighlightBuiltinSets.ERRORS)).isEqualTo(HighlightBuiltinSets.ERRORS);
        assertThat(resolve(null, null, "deleted-set")).isNull();
    }

    @Test
    void theMasterSwitchOffMeansNoneWhateverIsChosen() {
        assertThat(TerminalHighlightService.resolveSetId(false, KNOWN, "user-1", null, HighlightBuiltinSets.ERRORS))
            .isNull();
    }

    @Test
    void noChoiceAnywhereMeansNone() {
        assertThat(resolve(null, null, null)).isNull();
        assertThat(TerminalHighlightService.resolveSetId(true, KNOWN)).isNull();
    }

    @Test
    void theServiceStartsWithNoSetActive() {
        assertThat(service.resolveSetId(() -> null)).isNull();
        assertThat(service.resolve(() -> null)).isSameInstanceAs(CompiledHighlightSet.NONE);
        assertThat(new GlobalSettings().getDefaultHighlightRuleSetId()).isNull();
    }

    @Test
    void theServiceResolvesThePaneBeforeTheGlobalDefault() {
        GlobalSettings settings = new GlobalSettings();
        settings.setDefaultHighlightRuleSetId(HighlightBuiltinSets.NETWORK);
        service.reload(settings);

        assertThat(service.resolveSetId(() -> null)).isEqualTo(HighlightBuiltinSets.NETWORK);
        assertThat(service.resolveSetId(() -> HighlightBuiltinSets.ERRORS)).isEqualTo(HighlightBuiltinSets.ERRORS);
        assertThat(service.resolve(() -> HighlightBuiltinSets.ERRORS).setId()).isEqualTo(HighlightBuiltinSets.ERRORS);
        assertThat(service.resolve(() -> TerminalHighlightService.NONE_ID)).isSameInstanceAs(CompiledHighlightSet.NONE);
    }

    @Test
    void builtInsAndUserSetsResolveAnywhere() {
        HighlightRule rule = new HighlightRule("deploy", false);
        rule.setBold(true);
        GlobalSettings settings = new GlobalSettings();
        settings.setHighlightRuleSets(List.of(new HighlightRuleSet("user-7", "Deploys", List.of(rule))));
        service.reload(settings);

        for (String id : HighlightBuiltinSets.IDS) {
            assertThat(service.resolveSetId(() -> id)).isEqualTo(id);
        }
        assertThat(service.resolveSetId(() -> "user-7")).isEqualTo("user-7");
        assertThat(service.setIds()).containsAtLeastElementsIn(HighlightBuiltinSets.IDS);
        assertThat(service.setIds()).contains("user-7");
    }

    @Test
    void theServiceMasterSwitchForcesNone() {
        GlobalSettings settings = new GlobalSettings();
        settings.setDefaultHighlightRuleSetId(HighlightBuiltinSets.ERRORS);
        settings.setTerminalHighlightingEnabled(false);
        service.reload(settings);

        assertThat(service.resolveSetId(() -> HighlightBuiltinSets.NETWORK)).isNull();
        assertThat(service.resolve(() -> null)).isSameInstanceAs(CompiledHighlightSet.NONE);
    }

    @Test
    void aDeletedUserSetFallsBackToTheDefault() {
        GlobalSettings settings = new GlobalSettings();
        settings.setDefaultHighlightRuleSetId(HighlightBuiltinSets.ERRORS);
        service.reload(settings);

        assertThat(service.resolveSetId(() -> "gone")).isEqualTo(HighlightBuiltinSets.ERRORS);
    }
}
