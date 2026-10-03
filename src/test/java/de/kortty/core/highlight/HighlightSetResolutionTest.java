package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import de.kortty.model.ServerConnection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * Which rule set a pane shows: pane choice, then connection, then global default, then none — and how
 * a saved default, master switch and deleted set reach the panes that are already open.
 */
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

    // ---- The global default, as Settings -> Terminal saves it (reload runs on every save) ----

    private TerminalOutputHighlighter attach(String paneChoice) {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        return service.attach(session.buffer, () -> paneChoice, () -> { }, () -> { }, () -> false);
    }

    @Test
    void aSavedDefaultMovesEveryPaneWithoutAChoiceOfItsOwn() {
        TerminalOutputHighlighter inheriting = attach(null);
        TerminalOutputHighlighter chosen = attach(HighlightBuiltinSets.NETWORK);
        TerminalOutputHighlighter switchedOff = attach(TerminalHighlightService.NONE_ID);
        GlobalSettings settings = new GlobalSettings();

        settings.setDefaultHighlightRuleSetId(HighlightBuiltinSets.ERRORS);
        service.reload(settings);

        assertThat(inheriting.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.ERRORS);
        assertThat(chosen.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.NETWORK);
        assertThat(switchedOff.ruleSet()).isSameInstanceAs(CompiledHighlightSet.NONE);

        settings.setDefaultHighlightRuleSetId(HighlightBuiltinSets.NETWORK_DEVICES);
        service.reload(settings);
        assertThat(inheriting.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.NETWORK_DEVICES);
        assertThat(chosen.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.NETWORK);

        settings.setDefaultHighlightRuleSetId(null);
        service.reload(settings);
        assertThat(inheriting.ruleSet()).isSameInstanceAs(CompiledHighlightSet.NONE);
        assertThat(chosen.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.NETWORK);
    }

    @Test
    void aNewPaneStartsWithTheSavedDefault() {
        GlobalSettings settings = new GlobalSettings();
        settings.setDefaultHighlightRuleSetId(HighlightBuiltinSets.NETWORK);
        service.reload(settings);

        assertThat(attach(null).ruleSet().setId()).isEqualTo(HighlightBuiltinSets.NETWORK);
    }

    @Test
    void aDefaultStoredAsNoneOrBlankMeansNone() {
        GlobalSettings settings = new GlobalSettings();
        for (String stored : new String[] {TerminalHighlightService.NONE_ID, " none ", "", "  "}) {
            settings.setDefaultHighlightRuleSetId(stored);
            service.reload(settings);
            assertThat(service.resolveSetId(() -> null)).isNull();
        }
    }

    @Test
    void theDefaultCanBeAUserSet() {
        HighlightRule rule = new HighlightRule("deploy", false);
        rule.setBold(true);
        GlobalSettings settings = new GlobalSettings();
        settings.setHighlightRuleSets(List.of(new HighlightRuleSet("user-7", "Deploys", List.of(rule))));
        settings.setDefaultHighlightRuleSetId("user-7");
        service.reload(settings);

        assertThat(service.resolveSetId(() -> null)).isEqualTo("user-7");

        // Deleting the set leaves the stored id behind; it then means none.
        settings.setHighlightRuleSets(List.of());
        service.reload(settings);
        assertThat(service.resolveSetId(() -> null)).isNull();
    }

    @Test
    void theMasterSwitchOffHidesTheDefaultAndOnBringsItBack() {
        TerminalOutputHighlighter pane = attach(null);
        GlobalSettings settings = new GlobalSettings();
        settings.setDefaultHighlightRuleSetId(HighlightBuiltinSets.ERRORS);

        settings.setTerminalHighlightingEnabled(false);
        service.reload(settings);
        assertThat(pane.ruleSet()).isSameInstanceAs(CompiledHighlightSet.NONE);
        assertThat(service.isEnabled()).isFalse();

        settings.setTerminalHighlightingEnabled(true);
        service.reload(settings);
        assertThat(pane.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.ERRORS);
        assertThat(service.isEnabled()).isTrue();
    }

    // ---- The connection level: ServerConnection.highlightRuleSetId between the pane and the default ----

    /** A pane whose runtime choice is {@code pane} and whose connection stores {@code connection}. */
    private static TerminalHighlightService.PaneSelection selection(String pane, String connection) {
        return new TerminalHighlightService.PaneSelection() {
            @Override
            public String paneOverride() {
                return pane;
            }

            @Override
            public String connectionSetId() {
                return connection;
            }
        };
    }

    private TerminalOutputHighlighter attachPane(TerminalHighlightService.PaneSelection selection) {
        HeadlessTerminalSession session = new HeadlessTerminalSession(80, 5);
        return service.attach(session.buffer, selection, () -> { }, () -> { }, () -> false);
    }

    private GlobalSettings settingsWithDefault(String defaultSetId) {
        GlobalSettings settings = new GlobalSettings();
        settings.setDefaultHighlightRuleSetId(defaultSetId);
        service.reload(settings);
        return settings;
    }

    @Test
    void theServiceResolvesTheConnectionBetweenThePaneAndTheDefault() {
        settingsWithDefault(HighlightBuiltinSets.ERRORS);

        assertThat(service.resolveSetId(selection(null, HighlightBuiltinSets.NETWORK)))
            .isEqualTo(HighlightBuiltinSets.NETWORK);
        assertThat(service.resolveSetId(selection(HighlightBuiltinSets.NETWORK_DEVICES, HighlightBuiltinSets.NETWORK)))
            .isEqualTo(HighlightBuiltinSets.NETWORK_DEVICES);
        assertThat(service.resolveSetId(selection(null, null))).isEqualTo(HighlightBuiltinSets.ERRORS);
        assertThat(service.resolve(selection(null, HighlightBuiltinSets.NETWORK)).setId())
            .isEqualTo(HighlightBuiltinSets.NETWORK);
    }

    @Test
    void aConnectionSetToNoneKeepsItsPanesPlainWhateverTheDefault() {
        settingsWithDefault(HighlightBuiltinSets.ERRORS);

        assertThat(service.resolveSetId(selection(null, TerminalHighlightService.NONE_ID))).isNull();
        assertThat(service.resolve(selection(null, TerminalHighlightService.NONE_ID)))
            .isSameInstanceAs(CompiledHighlightSet.NONE);
        // The pane's own choice still wins over the connection's None.
        assertThat(service.resolveSetId(selection(HighlightBuiltinSets.NETWORK, TerminalHighlightService.NONE_ID)))
            .isEqualTo(HighlightBuiltinSets.NETWORK);
    }

    @Test
    void aConnectionNamingAnUnknownSetFallsThroughToTheDefault() {
        settingsWithDefault(HighlightBuiltinSets.ERRORS);

        // A user set deleted since, or one a shared teamwork file brought from another machine.
        assertThat(service.resolveSetId(selection(null, "set-from-another-machine")))
            .isEqualTo(HighlightBuiltinSets.ERRORS);
    }

    @Test
    void theMasterSwitchOffAlsoOverridesTheConnection() {
        GlobalSettings settings = settingsWithDefault(null);
        settings.setTerminalHighlightingEnabled(false);
        service.reload(settings);

        assertThat(service.resolveSetId(selection(null, HighlightBuiltinSets.NETWORK))).isNull();
        assertThat(service.decidingLevel(selection(null, HighlightBuiltinSets.NETWORK)))
            .isEqualTo(TerminalHighlightService.Level.NONE);
    }

    @Test
    void theDecidingLevelNamesWhereThePanesSetComesFrom() {
        settingsWithDefault(HighlightBuiltinSets.ERRORS);

        assertThat(service.decidingLevel(selection(HighlightBuiltinSets.NETWORK, HighlightBuiltinSets.NETWORK_DEVICES)))
            .isEqualTo(TerminalHighlightService.Level.PANE);
        assertThat(service.decidingLevel(selection(null, HighlightBuiltinSets.NETWORK_DEVICES)))
            .isEqualTo(TerminalHighlightService.Level.CONNECTION);
        assertThat(service.decidingLevel(selection(null, TerminalHighlightService.NONE_ID)))
            .isEqualTo(TerminalHighlightService.Level.CONNECTION);
        assertThat(service.decidingLevel(selection(null, "unknown")))
            .isEqualTo(TerminalHighlightService.Level.DEFAULT);
        assertThat(service.decidingLevel(selection(null, null))).isEqualTo(TerminalHighlightService.Level.DEFAULT);
        assertThat(service.decidingLevel(null)).isEqualTo(TerminalHighlightService.Level.DEFAULT);

        settingsWithDefault(null);
        assertThat(service.decidingLevel(selection(null, null))).isEqualTo(TerminalHighlightService.Level.NONE);
    }

    @Test
    void aConnectionSetMovesItsPanesWhenTheConnectionsAreSaved() {
        settingsWithDefault(HighlightBuiltinSets.ERRORS);
        String[] stored = {null};
        TerminalOutputHighlighter pane = attachPane(new TerminalHighlightService.PaneSelection() {
            @Override
            public String paneOverride() {
                return null;
            }

            @Override
            public String connectionSetId() {
                return stored[0];
            }
        });
        TerminalOutputHighlighter chosen = attachPane(selection(HighlightBuiltinSets.NETWORK_DEVICES, null));
        assertThat(pane.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.ERRORS);

        // The Connection Manager saved a set on the connection: refreshAll re-reads every pane's levels.
        stored[0] = HighlightBuiltinSets.NETWORK;
        service.refreshAll();
        assertThat(pane.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.NETWORK);
        assertThat(chosen.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.NETWORK_DEVICES);

        stored[0] = TerminalHighlightService.NONE_ID;
        service.refreshAll();
        assertThat(pane.ruleSet()).isSameInstanceAs(CompiledHighlightSet.NONE);

        stored[0] = null;
        service.refreshAll();
        assertThat(pane.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.ERRORS);
    }

    @Test
    void aSavedDefaultLeavesAPaneWithAConnectionSetAlone() {
        TerminalOutputHighlighter pane = attachPane(selection(null, HighlightBuiltinSets.NETWORK));
        assertThat(pane.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.NETWORK);

        settingsWithDefault(HighlightBuiltinSets.ERRORS);
        assertThat(pane.ruleSet().setId()).isEqualTo(HighlightBuiltinSets.NETWORK);
    }

    @Test
    void theConnectionLevelPrefersTheSavedConnectionWithTheSameId() {
        ServerConnection saved = new ServerConnection("prod", "db.example.com", 22, "root");
        saved.setHighlightRuleSetId(HighlightBuiltinSets.NETWORK);
        // A tab opened through Quick Connect or a teamwork default login holds a copy with the same id.
        ServerConnection copyInTab = ServerConnection.copyForAuth(saved);
        copyInTab.setHighlightRuleSetId(null);
        Map<String, ServerConnection> savedById = Map.of(saved.getId(), saved);

        assertThat(TerminalHighlightService.connectionSetId(copyInTab, savedById::get))
            .isEqualTo(HighlightBuiltinSets.NETWORK);

        // A choice removed in the Connection Manager reaches the copy too.
        saved.setHighlightRuleSetId(null);
        copyInTab.setHighlightRuleSetId(HighlightBuiltinSets.ERRORS);
        assertThat(TerminalHighlightService.connectionSetId(copyInTab, savedById::get)).isNull();
    }

    @Test
    void aConnectionThatIsNotSavedHereUsesItsOwnSet() {
        ServerConnection teamwork = new ServerConnection("shared", "switch.example.com", 22, "admin");
        teamwork.setHighlightRuleSetId(HighlightBuiltinSets.NETWORK_DEVICES);

        assertThat(TerminalHighlightService.connectionSetId(teamwork, id -> null))
            .isEqualTo(HighlightBuiltinSets.NETWORK_DEVICES);
        assertThat(TerminalHighlightService.connectionSetId(teamwork, null))
            .isEqualTo(HighlightBuiltinSets.NETWORK_DEVICES);
        assertThat(TerminalHighlightService.connectionSetId(null, id -> teamwork)).isNull();
    }
}
