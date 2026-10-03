package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.core.highlight.HighlightBuiltinSets;
import de.kortty.core.highlight.HighlightToggle;
import de.kortty.core.highlight.TerminalHighlightService;
import de.kortty.model.GlobalSettings;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import de.kortty.ui.actions.ActionIds;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import java.util.function.Supplier;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

/**
 * The highlighting submenus of the View menu and the pane context menu: None, the built-in sets, the
 * user's sets, the pane's set checked, and a check item that follows the pane rather than its own
 * click. Menu items are not nodes, so no JavaFX toolkit is started; a SeparatorMenuItem holds a
 * Separator control, whose static initializer needs the toolkit and would break later tests in the same
 * JVM, so the builders get plain marker items as separators here.
 */
class HighlightMenuSupportTest {

    private static final String SEPARATOR_MARK = "test.separator";

    private static final Supplier<MenuItem> SEPARATORS = () -> {
        MenuItem separator = new MenuItem();
        separator.getProperties().put(SEPARATOR_MARK, Boolean.TRUE);
        return separator;
    };

    private static boolean isSeparator(MenuItem item) {
        return Boolean.TRUE.equals(item.getProperties().get(SEPARATOR_MARK));
    }

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

    private static List<String> ids(List<HighlightMenuSupport.Entry> entries) {
        return entries.stream().map(HighlightMenuSupport.Entry::setId).toList();
    }

    private static List<RadioMenuItem> radios(Menu menu) {
        return menu.getItems().stream().filter(RadioMenuItem.class::isInstance).map(RadioMenuItem.class::cast).toList();
    }

    @Test
    void entriesListNoneThenTheBuiltInsThenTheUserSetsInGroups() {
        loadUserSet("user-1", "Production");

        List<HighlightMenuSupport.Entry> entries = HighlightMenuSupport.state(service, true, null).entries();

        assertThat(ids(entries)).containsExactly(TerminalHighlightService.NONE_ID, HighlightBuiltinSets.ERRORS,
            HighlightBuiltinSets.NETWORK, HighlightBuiltinSets.NETWORK_DEVICES, "user-1").inOrder();
        assertThat(entries.stream().map(HighlightMenuSupport.Entry::separatorBefore).toList())
            .containsExactly(false, true, false, false, true).inOrder();
        assertThat(entries.get(0).label()).isEqualTo(I18n.get(HighlightMenuSupport.NONE_KEY));
        assertThat(entries.get(4).label()).isEqualTo("Production");
    }

    @Test
    void builtInSetsShowTheirTranslatedName() {
        for (String id : HighlightBuiltinSets.IDS) {
            String label = HighlightMenuSupport.label(id, ignored -> "user name must not win");
            assertThat(label).isEqualTo(I18n.get(HighlightBuiltinSets.nameKey(id)));
            assertThat(label).isNotEqualTo(HighlightBuiltinSets.nameKey(id));
        }
    }

    @Test
    void anUnnamedUserSetFallsBackToItsId() {
        assertThat(HighlightMenuSupport.label("user-2", ignored -> null)).isEqualTo("user-2");
        assertThat(HighlightMenuSupport.label("user-2", ignored -> "  ")).isEqualTo("user-2");
    }

    @Test
    void withoutAServiceTheMenuIsDisabledButStillListsTheBuiltIns() {
        HighlightMenuSupport.State state = HighlightMenuSupport.state(null, true, HighlightBuiltinSets.ERRORS);

        assertThat(state.actionable()).isFalse();
        assertThat(state.shownSetId()).isNull();
        assertThat(ids(state.entries())).containsExactly(TerminalHighlightService.NONE_ID, HighlightBuiltinSets.ERRORS,
            HighlightBuiltinSets.NETWORK, HighlightBuiltinSets.NETWORK_DEVICES).inOrder();
    }

    @Test
    void thePanesSetIsCheckedAndPickingASetReportsItsId() {
        List<String> chosen = new ArrayList<>();
        Menu menu = HighlightMenuSupport.createPaneMenu(
            HighlightMenuSupport.state(service, true, HighlightBuiltinSets.NETWORK), () -> { }, chosen::add, SEPARATORS);

        List<RadioMenuItem> radios = radios(menu);
        assertThat(radios.stream().filter(RadioMenuItem::isSelected).map(MenuItem::getText).toList())
            .containsExactly(I18n.get(HighlightBuiltinSets.nameKey(HighlightBuiltinSets.NETWORK)));
        radios.get(0).fire();
        radios.get(3).fire();
        assertThat(chosen).containsExactly(TerminalHighlightService.NONE_ID, HighlightBuiltinSets.NETWORK_DEVICES)
            .inOrder();
    }

    @Test
    void aPaneWithoutASetHasNoneChecked() {
        Menu menu = HighlightMenuSupport.createPaneMenu(HighlightMenuSupport.state(service, true, null), () -> { },
            id -> { }, SEPARATORS);

        assertThat(radios(menu).get(0).isSelected()).isTrue();
        assertThat(radios(menu).stream().filter(RadioMenuItem::isSelected).count()).isEqualTo(1);
        CheckMenuItem toggle = (CheckMenuItem) menu.getItems().get(0);
        assertThat(toggle.isSelected()).isFalse();
        assertThat(toggle.getAccelerator()).isNull();
    }

    @Test
    void theToggleRunsFromThePanesStateNotFromItsOwnCheckMark() {
        AtomicInteger toggles = new AtomicInteger();
        CheckMenuItem toggle = HighlightMenuSupport.createToggleItem(toggles::incrementAndGet);

        // JavaFX flips the check mark before onAction runs, for a click and for the accelerator alike.
        toggle.setSelected(true);
        toggle.fire();
        toggle.setSelected(false);
        toggle.fire();
        assertThat(toggles.get()).isEqualTo(2);

        // Afterwards the pane's real state decides what the item shows.
        HighlightMenuSupport.syncToggle(toggle, HighlightMenuSupport.state(service, true, HighlightBuiltinSets.ERRORS));
        assertThat(toggle.isSelected()).isTrue();
        HighlightMenuSupport.syncToggle(toggle, HighlightMenuSupport.state(service, true, null));
        assertThat(toggle.isSelected()).isFalse();
        assertThat(toggle.isDisable()).isFalse();
    }

    @Test
    void withNoPaneOrTheMasterSwitchOffEverythingIsDisabledAndUnchecked() {
        GlobalSettings off = new GlobalSettings();
        off.setTerminalHighlightingEnabled(false);
        service.reload(off);

        for (HighlightMenuSupport.State state : List.of(HighlightMenuSupport.state(service, true, null),
                HighlightMenuSupport.state(service, false, null))) {
            Menu menu = HighlightMenuSupport.createPaneMenu(state, () -> { }, id -> { }, SEPARATORS);
            CheckMenuItem toggle = (CheckMenuItem) menu.getItems().get(0);
            assertThat(toggle.isDisable()).isTrue();
            assertThat(toggle.isSelected()).isFalse();
            assertThat(radios(menu).stream().allMatch(MenuItem::isDisable)).isTrue();
            assertThat(radios(menu).stream().noneMatch(RadioMenuItem::isSelected)).isTrue();
        }
    }

    @Test
    void reopeningTheViewMenuKeepsTheToggleAndReplacesOnlyTheList() {
        CheckMenuItem toggle = HighlightMenuSupport.createToggleItem(() -> { });
        Menu menu = HighlightMenuSupport.createViewMenu(toggle, HighlightMenuSupport.state(service, false, null), id -> { },
            SEPARATORS);
        int builtInsOnly = menu.getItems().size();

        loadUserSet("user-1", "Production");
        HighlightMenuSupport.refresh(menu, toggle, HighlightMenuSupport.state(service, true, "user-1"), id -> { },
            SEPARATORS);
        HighlightMenuSupport.refresh(menu, toggle, HighlightMenuSupport.state(service, true, "user-1"), id -> { },
            SEPARATORS);

        assertThat(menu.getItems().get(0)).isSameInstanceAs(toggle);
        assertThat(isSeparator(menu.getItems().get(1))).isTrue();
        // One more separator and one more set than before, not a second copy of the list.
        assertThat(menu.getItems()).hasSize(builtInsOnly + 2);
        assertThat(toggle.isSelected()).isTrue();
        assertThat(radios(menu).stream().filter(RadioMenuItem::isSelected).map(MenuItem::getText).toList())
            .containsExactly("Production");
    }

    @Test
    void theRebuiltListIsKeptOutOfTheActionHarvestButTheToggleIsNot() {
        CheckMenuItem toggle = HighlightMenuSupport.createToggleItem(() -> { });
        ActionIds.tag(toggle, HighlightMenuSupport.TOGGLE_KEY);
        Menu menu = HighlightMenuSupport.createViewMenu(toggle, HighlightMenuSupport.state(service, true, null), id -> { },
            SEPARATORS);

        assertThat(ActionIds.isExcluded(toggle)).isFalse();
        assertThat(ActionIds.idOf(toggle)).isEqualTo(HighlightMenuSupport.TOGGLE_KEY);
        for (MenuItem item : menu.getItems().subList(HighlightMenuSupport.LEADING_ITEMS, menu.getItems().size())) {
            assertThat(ActionIds.isExcluded(item)).isTrue();
        }
    }

    @Test
    void theStatusLineNamesTheSetOrSaysOff() {
        String on = HighlightMenuSupport.statusMessage(
            new HighlightToggle.Choice(null, HighlightBuiltinSets.ERRORS), id -> null);
        String off = HighlightMenuSupport.statusMessage(
            new HighlightToggle.Choice(TerminalHighlightService.NONE_ID, null), id -> null);

        assertThat(on).contains(I18n.get(HighlightBuiltinSets.nameKey(HighlightBuiltinSets.ERRORS)));
        assertThat(on).doesNotContain("{0}");
        assertThat(off).isEqualTo(I18n.get(HighlightMenuSupport.STATUS_OFF_KEY));
    }
}
