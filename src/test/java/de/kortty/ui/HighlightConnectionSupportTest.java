package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.core.highlight.HighlightBuiltinSets;
import de.kortty.core.highlight.TerminalHighlightService;
import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The rule-set dropdown of the connection editor's Terminal behavior section: Use the default (naming the
 * default's current set) and None first, then the sets in menu order, the stored value selected, a stored
 * id that names no set kept rather than dropped, and the round trip to what the connection stores. No
 * JavaFX toolkit needed.
 */
class HighlightConnectionSupportTest {

    private TerminalHighlightService service;

    @BeforeMethod
    void setUp() {
        service = new TerminalHighlightService(TerminalHighlightService.defaultScheduler());
    }

    @AfterMethod
    void tearDown() {
        service.stop();
    }

    private void loadUserSet(String id, String name) {
        HighlightRule rule = new HighlightRule("deploy", false);
        rule.setId(id + ".rule");
        rule.setBold(true);
        GlobalSettings settings = new GlobalSettings();
        settings.setHighlightRuleSets(new ArrayList<>(List.of(new HighlightRuleSet(id, name, new ArrayList<>(List.of(rule))))));
        service.reload(settings);
    }

    private List<HighlightConnectionSupport.Choice> choices(String defaultSetId, String stored) {
        return HighlightConnectionSupport.choices(HighlightSettingsSupport.selectableSetIds(service),
            service::userSetName, defaultSetId, stored);
    }

    private static List<String> storedValues(List<HighlightConnectionSupport.Choice> choices) {
        List<String> values = new ArrayList<>();
        for (HighlightConnectionSupport.Choice choice : choices) {
            values.add(choice.storedValue());
        }
        return values;
    }

    @Test
    void useTheDefaultAndNoneComeFirstThenTheBuiltInsThenTheUserSets() {
        loadUserSet("user-1", "Production");

        List<HighlightConnectionSupport.Choice> choices = choices(null, null);

        assertThat(storedValues(choices)).containsExactly(null, TerminalHighlightService.NONE_ID,
            HighlightBuiltinSets.ERRORS, HighlightBuiltinSets.NETWORK, HighlightBuiltinSets.NETWORK_DEVICES, "user-1")
            .inOrder();
        assertThat(choices.get(1).label()).isEqualTo(I18n.get(HighlightConnectionSupport.NONE_KEY));
        assertThat(choices.get(2).label()).isEqualTo(I18n.get(HighlightBuiltinSets.nameKey(HighlightBuiltinSets.ERRORS)));
        assertThat(choices.get(5).label()).isEqualTo("Production");
        assertThat(choices.get(5).toString()).isEqualTo("Production");
    }

    @Test
    void useTheDefaultNamesTheDefaultsCurrentSet() {
        loadUserSet("user-1", "Production");

        assertThat(choices(HighlightBuiltinSets.NETWORK, null).getFirst().label()).isEqualTo(
            I18n.get(HighlightConnectionSupport.DEFAULT_KEY, I18n.get(HighlightBuiltinSets.nameKey(HighlightBuiltinSets.NETWORK))));
        assertThat(choices("user-1", null).getFirst().label())
            .isEqualTo(I18n.get(HighlightConnectionSupport.DEFAULT_KEY, "Production"));
        assertThat(choices(null, null).getFirst().label())
            .isEqualTo(I18n.get(HighlightConnectionSupport.DEFAULT_KEY, I18n.get(HighlightMenuSupport.NONE_KEY)));
    }

    @Test
    void theStoredValueIsSelected() {
        loadUserSet("user-1", "Production");

        assertThat(HighlightConnectionSupport.selected(choices(null, null), null).storedValue()).isNull();
        assertThat(HighlightConnectionSupport.selected(choices(null, " none "), " none ").storedValue())
            .isEqualTo(TerminalHighlightService.NONE_ID);
        assertThat(HighlightConnectionSupport.selected(choices(null, "user-1"), "user-1").storedValue())
            .isEqualTo("user-1");
        assertThat(HighlightConnectionSupport.selected(choices(null, HighlightBuiltinSets.NETWORK),
            HighlightBuiltinSets.NETWORK).storedValue()).isEqualTo(HighlightBuiltinSets.NETWORK);
    }

    @Test
    void aStoredSetThatNoLongerExistsStaysSelectableUnderAMissingLabel() {
        List<HighlightConnectionSupport.Choice> choices = choices(null, "set-from-another-machine");

        HighlightConnectionSupport.Choice last = choices.getLast();
        assertThat(last.storedValue()).isEqualTo("set-from-another-machine");
        assertThat(last.label()).isEqualTo(I18n.get(HighlightConnectionSupport.UNKNOWN_KEY, "set-from-another-machine"));
        HighlightConnectionSupport.Choice selected =
            HighlightConnectionSupport.selected(choices, "set-from-another-machine");
        assertThat(HighlightConnectionSupport.storedValue(selected)).isEqualTo("set-from-another-machine");
    }

    @Test
    void aKnownStoredSetIsNotListedTwice() {
        List<HighlightConnectionSupport.Choice> choices = choices(null, HighlightBuiltinSets.ERRORS);

        assertThat(storedValues(choices)).containsNoDuplicates();
        assertThat(storedValues(choices)).hasSize(2 + HighlightBuiltinSets.IDS.size());
    }

    @Test
    void theChoiceRoundTripsToWhatTheConnectionStores() {
        List<HighlightConnectionSupport.Choice> choices = choices(null, null);

        assertThat(HighlightConnectionSupport.storedValue(choices.get(0))).isNull();
        assertThat(HighlightConnectionSupport.storedValue(choices.get(1))).isEqualTo(TerminalHighlightService.NONE_ID);
        assertThat(HighlightConnectionSupport.storedValue(choices.get(2))).isEqualTo(HighlightBuiltinSets.ERRORS);
        assertThat(HighlightConnectionSupport.storedValue(null)).isNull();
    }

    @Test
    void theDefaultLabelOnlyNamesASetThePanesCanShow() {
        loadUserSet("user-1", "Production");

        assertThat(HighlightConnectionSupport.defaultSetId("user-1", HighlightConnectionSupport.knownSets(service)))
            .isEqualTo("user-1");
        assertThat(HighlightConnectionSupport.defaultSetId(" builtin.errors ", HighlightConnectionSupport.knownSets(service)))
            .isEqualTo(HighlightBuiltinSets.ERRORS);
        assertThat(HighlightConnectionSupport.defaultSetId("deleted", HighlightConnectionSupport.knownSets(service))).isNull();
        assertThat(HighlightConnectionSupport.defaultSetId(TerminalHighlightService.NONE_ID,
            HighlightConnectionSupport.knownSets(service))).isNull();
        assertThat(HighlightConnectionSupport.defaultSetId(null, HighlightConnectionSupport.knownSets(service))).isNull();
    }

    @Test
    void withoutARunningServiceTheBuiltInsAreTheKnownSets() {
        service.stop();

        assertThat(HighlightConnectionSupport.knownSets(null).test(HighlightBuiltinSets.NETWORK)).isTrue();
        assertThat(HighlightConnectionSupport.knownSets(null).test("user-1")).isFalse();
        assertThat(HighlightConnectionSupport.knownSets(service).test(HighlightBuiltinSets.ERRORS)).isTrue();
        assertThat(storedValues(HighlightConnectionSupport.choices(HighlightSettingsSupport.selectableSetIds(null),
            null, null, null))).containsExactly(null, TerminalHighlightService.NONE_ID, HighlightBuiltinSets.ERRORS,
            HighlightBuiltinSets.NETWORK, HighlightBuiltinSets.NETWORK_DEVICES).inOrder();
    }
}
