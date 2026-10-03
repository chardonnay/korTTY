package de.kortty.ui.actions;

import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static com.google.common.truth.Truth.assertThat;

/**
 * Harvesting menu items into actions, toolkit-free. The test builds only Menu, MenuItem,
 * CheckMenuItem, RadioMenuItem and no-arg CustomMenuItems (the stand-in for a separator): a
 * MenuBar, SeparatorMenuItem, Label or any other Control would run Control's static initializer,
 * which needs a running toolkit and would break later tests in the same JVM. It never calls
 * KeyCombination.match or getDisplayText, which read the toolkit too.
 */
class MenuActionHarvesterTest {

    private static Menu menu(String text, MenuItem... items) {
        Menu menu = new Menu(text);
        menu.getItems().addAll(items);
        return menu;
    }

    private static AppAction only(List<AppAction> actions, String label) {
        List<AppAction> matching = actions.stream().filter(a -> a.label().equals(label)).toList();
        assertThat(matching).hasSize(1);
        return matching.get(0);
    }

    private static Optional<AppAction> byLabel(List<AppAction> actions, String label) {
        return actions.stream().filter(a -> a.label().equals(label)).findFirst();
    }

    private static List<String> labels(List<AppAction> actions) {
        return actions.stream().map(AppAction::label).toList();
    }

    @Test
    void separatorsBlankAndInvisibleItemsAreSkipped() {
        MenuItem visible = new MenuItem("New Tab");
        MenuItem hidden = new MenuItem("Hidden");
        hidden.setVisible(false);
        MenuItem blank = new MenuItem("   ");
        MenuItem noText = new MenuItem();
        CustomMenuItem slider = new CustomMenuItem();
        slider.setText("Zoom");

        List<AppAction> actions = MenuActionHarvester.harvest(List.of(
                menu("File", visible, new CustomMenuItem(), hidden, blank, noText, slider)));

        assertThat(labels(actions)).containsExactly("New Tab");
    }

    @Test
    void submenusAreWalkedButAreNotActions() {
        Menu fileBrowser = menu("File Browser", new MenuItem("Dock Left"), new MenuItem("Dock Right"));
        Menu view = menu("View", new MenuItem("Status Bar"), fileBrowser);

        List<AppAction> actions = MenuActionHarvester.harvest(List.of(view));

        assertThat(labels(actions)).containsExactly("Status Bar", "Dock Left", "Dock Right").inOrder();
        assertThat(only(actions, "Status Bar").category()).isEqualTo("View");
        assertThat(only(actions, "Dock Left").category()).isEqualTo("View \u203A File Browser");
        assertThat(only(actions, "Dock Left").keywords()).containsExactly("View \u203A File Browser");
    }

    @Test
    void invisibleSubmenuIsPruned() {
        Menu jobs = menu("Jobs", new MenuItem("Open Scheduler"));
        jobs.setVisible(false);

        List<AppAction> actions = MenuActionHarvester.harvest(List.of(menu("Tools", new MenuItem("SFTP"), jobs)));

        assertThat(labels(actions)).containsExactly("SFTP");
    }

    @Test
    void ellipsisAndMnemonicAreStrippedFromLabelAndCategory() {
        MenuItem dots = new MenuItem("Open Project...");
        MenuItem unicode = new MenuItem("Find\u2026");
        MenuItem mnemonic = new MenuItem("_Save As");
        MenuItem escaped = new MenuItem("Snake__Case");
        MenuItem literal = new MenuItem("_raw_");
        literal.setMnemonicParsing(false);

        List<AppAction> actions = MenuActionHarvester.harvest(List.of(
                menu("_File", dots, unicode, mnemonic, escaped, literal)));

        assertThat(labels(actions))
                .containsExactly("Open Project", "Find", "Save As", "Snake_Case", "_raw_").inOrder();
        assertThat(actions.get(0).category()).isEqualTo("File");
    }

    @Test
    void taggedItemKeepsItsStableId() {
        MenuItem quickConnect = ActionIds.tag(new MenuItem("Quick Connect..."), "menu.file.quickConnect");

        AppAction action = MenuActionHarvester.harvest(List.of(menu("File", quickConnect))).get(0);

        assertThat(action.id()).isEqualTo("menu.file.quickConnect");
        assertThat(action.stableId()).isTrue();
    }

    @Test
    void untaggedItemGetsAPathIdThatIsNotStable() {
        Menu journal = menu("Live Journal", new MenuItem("Dock Left"));
        Menu view = menu("View", journal, new MenuItem("\u00dcn\u00efc\u00f6d\u00e9 Str\u00f6m"));

        List<AppAction> actions = MenuActionHarvester.harvest(List.of(view));

        AppAction dockLeft = only(actions, "Dock Left");
        assertThat(dockLeft.id()).isEqualTo("menu:view/live-journal/dock-left");
        assertThat(dockLeft.stableId()).isFalse();
        assertThat(only(actions, "\u00dcn\u00efc\u00f6d\u00e9 Str\u00f6m").id()).isEqualTo("menu:view/unicode-strom");
    }

    @Test
    void duplicateIdsGetASuffixAndLoseTheStableFlag() {
        MenuItem first = ActionIds.tag(new MenuItem("Copy"), "menu.edit.copy");
        MenuItem second = ActionIds.tag(new MenuItem("Copy again"), "menu.edit.copy");
        MenuItem untaggedOne = new MenuItem("Same");
        MenuItem untaggedTwo = new MenuItem("Same");

        List<AppAction> actions = MenuActionHarvester.harvest(List.of(
                menu("Edit", first, second, untaggedOne, untaggedTwo)));

        assertThat(actions.stream().map(AppAction::id).toList())
                .containsExactly("menu.edit.copy", "menu.edit.copy#2", "menu:edit/same", "menu:edit/same#2")
                .inOrder();
        assertThat(actions.get(0).stableId()).isTrue();
        assertThat(actions.get(1).stableId()).isFalse();
    }

    @Test
    void acceleratorIsCarried() {
        KeyCombination shortcut = new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN);
        MenuItem newTab = new MenuItem("New Tab");
        newTab.setAccelerator(shortcut);

        List<AppAction> actions = MenuActionHarvester.harvest(List.of(menu("File", newTab, new MenuItem("Close All"))));

        assertThat(only(actions, "New Tab").accelerator()).isSameInstanceAs(shortcut);
        assertThat(only(actions, "Close All").accelerator()).isNull();
    }

    @Test
    void enabledStateIsReadLazilyFromTheItemAndEveryMenuAboveIt() {
        MenuItem item = new MenuItem("Matrix");
        Menu effects = menu("Effects", item);
        Menu view = menu("View", effects);

        AppAction action = MenuActionHarvester.harvest(List.of(view)).get(0);
        assertThat(action.isEnabled()).isTrue();

        effects.setDisable(true);
        assertThat(action.isEnabled()).isFalse();
        effects.setDisable(false);
        view.setDisable(true);
        assertThat(action.isEnabled()).isFalse();
        view.setDisable(false);
        item.setDisable(true);
        assertThat(action.isEnabled()).isFalse();
        item.setDisable(false);
        assertThat(action.isEnabled()).isTrue();
    }

    @Test
    void excludeOnAMenuPrunesItsWholeSubtree() {
        Menu recent = ActionIds.exclude(menu("Open Recent",
                new MenuItem("project-a.kortty"),
                menu("Older", new MenuItem("project-b.kortty"))));
        MenuItem excludedItem = ActionIds.exclude(new MenuItem("Rebuilt Item"));

        List<AppAction> actions = MenuActionHarvester.harvest(List.of(
                menu("File", new MenuItem("Quit"), recent, excludedItem)));

        assertThat(labels(actions)).containsExactly("Quit");
    }

    @Test
    void policyLockIsCarriedFromTheItemOrAMenuAboveIt() {
        MenuItem lockedItem = ActionIds.markPolicyLocked(new MenuItem("Teamwork Sync"));
        lockedItem.setDisable(true);
        Menu lockedMenu = ActionIds.markPolicyLocked(menu("AI", new MenuItem("Ask"), menu("Agents", new MenuItem("Run"))));

        List<AppAction> actions = MenuActionHarvester.harvest(List.of(
                menu("Tools", new MenuItem("SFTP"), lockedItem), lockedMenu));

        assertThat(only(actions, "SFTP").policyLocked()).isFalse();
        assertThat(only(actions, "Teamwork Sync").policyLocked()).isTrue();
        assertThat(only(actions, "Teamwork Sync").isEnabled()).isFalse();
        assertThat(only(actions, "Ask").policyLocked()).isTrue();
        assertThat(only(actions, "Run").policyLocked()).isTrue();
    }

    @Test
    void toggleItemsReportTheirCheckedStateAndPlainItemsDoNot() {
        CheckMenuItem statusBar = new CheckMenuItem("Status Bar");
        RadioMenuItem dark = new RadioMenuItem("Dark");
        MenuItem plain = new MenuItem("New Window");

        List<AppAction> actions = MenuActionHarvester.harvest(List.of(menu("View", statusBar, dark, plain)));

        AppAction status = only(actions, "Status Bar");
        assertThat(status.isCheckable()).isTrue();
        assertThat(status.isChecked()).isFalse();
        statusBar.setSelected(true);
        assertThat(status.isChecked()).isTrue();
        assertThat(only(actions, "Dark").isCheckable()).isTrue();
        assertThat(only(actions, "New Window").isCheckable()).isFalse();
        assertThat(only(actions, "New Window").checked()).isNull();
    }

    @Test
    void runningAHarvestedActionActivatesTheItemLikeItsAccelerator() {
        CheckMenuItem statusBar = new CheckMenuItem("Status Bar");
        List<Boolean> seen = new ArrayList<>();
        statusBar.setOnAction(e -> seen.add(statusBar.isSelected()));
        ActionRegistry registry = new ActionRegistry();
        Menu view = menu("View", ActionIds.tag(statusBar, "menu.view.statusBar"));
        registry.addContributor(() -> MenuActionHarvester.harvest(List.of(view)));

        assertThat(registry.run("menu.view.statusBar")).isTrue();
        view.setDisable(true);
        assertThat(registry.run("menu.view.statusBar")).isFalse();

        assertThat(seen).containsExactly(true);
    }

    @Test
    void nullAndEmptyMenuListsHarvestNothing() {
        assertThat(MenuActionHarvester.harvest(null)).isEmpty();
        assertThat(MenuActionHarvester.harvest(List.of())).isEmpty();
        assertThat(MenuActionHarvester.harvest(List.of(new Menu("Empty")))).isEmpty();
        assertThat(byLabel(MenuActionHarvester.harvest(List.of(menu("A", new MenuItem("B")))), "A")).isEmpty();
    }

    @Test
    void untaggedIdOfAnUntitledMenuSkipsThatSegment() {
        Menu untitled = menu("", new MenuItem("Scheduler"));

        AppAction action = MenuActionHarvester.harvest(List.of(untitled)).get(0);

        assertThat(action.category()).isEmpty();
        assertThat(action.keywords()).isEmpty();
        assertThat(action.id()).isEqualTo("menu:scheduler");
    }

    @Test
    void slugFallsBackForTextWithoutLettersOrDigits() {
        assertThat(MenuActionHarvester.slug("\u2026 !!")).isEqualTo("_");
        assertThat(MenuActionHarvester.slug("Show/Hide")).isEqualTo("show-hide");
    }
}
