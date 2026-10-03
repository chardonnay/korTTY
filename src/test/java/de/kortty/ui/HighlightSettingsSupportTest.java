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
 * The default-set dropdown of Settings → Terminal → Keyword highlighting: None first, then the sets in
 * menu order, the stored default selected, a stored id that names no set kept rather than dropped, and
 * only the set's class reported to the anonymous statistics. No JavaFX toolkit needed.
 */
class HighlightSettingsSupportTest {

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

    private List<HighlightSettingsSupport.DefaultSetChoice> choices(String storedDefault) {
        return HighlightSettingsSupport.defaultSetChoices(HighlightSettingsSupport.selectableSetIds(service),
            service::userSetName, storedDefault);
    }

    private static List<String> ids(List<HighlightSettingsSupport.DefaultSetChoice> choices) {
        List<String> ids = new ArrayList<>();
        for (HighlightSettingsSupport.DefaultSetChoice choice : choices) {
            ids.add(choice.setId());
        }
        return ids;
    }

    @Test
    void noneComesFirstThenTheBuiltInsThenTheUserSets() {
        loadUserSet("user-1", "Production");

        List<HighlightSettingsSupport.DefaultSetChoice> choices = choices(null);

        assertThat(ids(choices)).containsExactly(null, HighlightBuiltinSets.ERRORS, HighlightBuiltinSets.NETWORK,
            HighlightBuiltinSets.NETWORK_DEVICES, "user-1").inOrder();
        assertThat(choices.get(0).label()).isEqualTo(I18n.get(HighlightSettingsSupport.DEFAULT_SET_NONE_KEY));
        assertThat(choices.get(1).label()).isEqualTo(I18n.get(HighlightBuiltinSets.nameKey(HighlightBuiltinSets.ERRORS)));
        assertThat(choices.get(4).label()).isEqualTo("Production");
        assertThat(choices.get(4).toString()).isEqualTo("Production");
    }

    @Test
    void withoutARunningServiceTheBuiltInsAreOffered() {
        service.stop();

        assertThat(HighlightSettingsSupport.selectableSetIds(service)).isEqualTo(HighlightBuiltinSets.IDS);
        assertThat(HighlightSettingsSupport.selectableSetIds(null)).isEqualTo(HighlightBuiltinSets.IDS);
        assertThat(ids(HighlightSettingsSupport.defaultSetChoices(HighlightSettingsSupport.selectableSetIds(null), null,
            null))).containsExactly(null, HighlightBuiltinSets.ERRORS, HighlightBuiltinSets.NETWORK,
            HighlightBuiltinSets.NETWORK_DEVICES).inOrder();
    }

    @Test
    void theStoredDefaultIsSelected() {
        loadUserSet("user-1", "Production");

        assertThat(HighlightSettingsSupport.selected(choices(null), null).setId()).isNull();
        assertThat(HighlightSettingsSupport.selected(choices(HighlightBuiltinSets.NETWORK), HighlightBuiltinSets.NETWORK)
            .setId()).isEqualTo(HighlightBuiltinSets.NETWORK);
        assertThat(HighlightSettingsSupport.selected(choices(" user-1 "), " user-1 ").setId()).isEqualTo("user-1");
    }

    @Test
    void aStoredNoneOrBlankDefaultSelectsNone() {
        for (String stored : new String[] {TerminalHighlightService.NONE_ID, " none ", "", "   "}) {
            List<HighlightSettingsSupport.DefaultSetChoice> choices = choices(stored);
            assertThat(choices).hasSize(1 + HighlightBuiltinSets.IDS.size());
            assertThat(HighlightSettingsSupport.selected(choices, stored).setId()).isNull();
        }
    }

    @Test
    void aStoredIdThatNamesNoSetIsKeptUnderTheMissingLabel() {
        List<HighlightSettingsSupport.DefaultSetChoice> choices = choices("deleted-set");

        HighlightSettingsSupport.DefaultSetChoice last = choices.getLast();
        assertThat(last.setId()).isEqualTo("deleted-set");
        assertThat(last.label()).isEqualTo(I18n.get(HighlightSettingsSupport.DEFAULT_SET_UNKNOWN_KEY, "deleted-set"));
        assertThat(last.label()).contains("deleted-set");
        HighlightSettingsSupport.DefaultSetChoice selected = HighlightSettingsSupport.selected(choices, "deleted-set");
        assertThat(selected).isSameInstanceAs(last);
        // Saving the page for another change writes the id back unchanged.
        assertThat(HighlightSettingsSupport.storedValue(selected)).isEqualTo("deleted-set");
    }

    @Test
    void aKnownStoredIdIsNotListedTwice() {
        loadUserSet("user-1", "Production");

        assertThat(choices("user-1")).hasSize(5);
        assertThat(choices(HighlightBuiltinSets.ERRORS)).hasSize(5);
    }

    @Test
    void noneIsStoredAsNoDefault() {
        List<HighlightSettingsSupport.DefaultSetChoice> choices = choices(null);

        assertThat(HighlightSettingsSupport.storedValue(choices.get(0))).isNull();
        assertThat(HighlightSettingsSupport.storedValue(null)).isNull();
        assertThat(HighlightSettingsSupport.storedValue(choices.get(1))).isEqualTo(HighlightBuiltinSets.ERRORS);

        GlobalSettings settings = new GlobalSettings();
        settings.setDefaultHighlightRuleSetId(HighlightSettingsSupport.storedValue(choices.get(2)));
        assertThat(settings.getDefaultHighlightRuleSetId()).isEqualTo(HighlightBuiltinSets.NETWORK);
        settings.setDefaultHighlightRuleSetId(HighlightSettingsSupport.storedValue(choices.get(0)));
        assertThat(settings.getDefaultHighlightRuleSetId()).isNull();
    }

    @Test
    void theStatisticsSeeTheSetsClassNeverItsName() {
        assertThat(HighlightSettingsSupport.telemetryValue(null)).isEqualTo("none");
        assertThat(HighlightSettingsSupport.telemetryValue(TerminalHighlightService.NONE_ID)).isEqualTo("none");
        assertThat(HighlightSettingsSupport.telemetryValue(HighlightBuiltinSets.NETWORK_DEVICES))
            .isEqualTo(HighlightBuiltinSets.NETWORK_DEVICES);
        assertThat(HighlightSettingsSupport.telemetryValue("3f2c9a1e-my-production-set")).isEqualTo("custom");
    }
}
