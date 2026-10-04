package de.kortty.ui;

import de.kortty.core.KeyChord;
import de.kortty.core.KeyChord.Os;
import de.kortty.core.KeymapOverrides;
import de.kortty.core.KeymapOverrides.Problem;
import de.kortty.ui.KeyboardSettingsModel.Edit;
import de.kortty.ui.KeyboardSettingsModel.Row;
import de.kortty.ui.KeyboardSettingsModel.Status;
import de.kortty.ui.actions.ActionIds;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import org.testng.annotations.Test;

import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.assertThrows;

/**
 * The Settings → Keyboard page's model, which needs no JavaFX toolkit: the catalog read off a menu
 * bar, a chord another action uses being taken but blocking saving until each chord is used once,
 * chords that break a rule being refused with the reason, the menu bar toggle keeping a shortcut,
 * stored overrides that do not apply on this computer or name an unknown action being shown and
 * kept, and the effective keymap matching what the windows apply.
 */
class KeyboardSettingsModelTest {

    private static final String NEW_TAB = "menu.file.newTab";
    private static final String RENAME_TAB = "menu.file.renameTab";
    private static final String PALETTE = "menu.view.commandPalette";
    private static final String MENU_BAR = "menu.view.menuBar";
    private static final String DASHBOARD = "menu.view.dashboard";
    private static final String CUT = "menu.edit.cut";

    @Test
    void theCatalogListsTheMenuActionsInMenuOrderAndTheFixedShortcutsAfterThem() {
        KeyboardSettingsModel.Catalog catalog = KeymapSupport.catalog(new Bar().menus, Os.LINUX);

        assertThat(catalog.actions().stream().map(KeyboardSettingsModel.Action::id).toList()).containsExactly(
            NEW_TAB, RENAME_TAB, CUT, PALETTE, MENU_BAR, DASHBOARD, "menu.view.zoomIn", "menu.view.blank",
            KeymapSupport.FIXED_NEXT_TAB, KeymapSupport.FIXED_PREVIOUS_TAB, KeymapSupport.FIXED_TAB_JUMP).inOrder();
        KeyboardSettingsModel.Action palette = catalog.action(PALETTE);
        assertWithMessage("no mnemonic marker, no ellipsis").that(palette.label()).isEqualTo("Command Palette");
        assertThat(palette.category()).isEqualTo("View");
        assertThat(palette.defaultChord()).isEqualTo(KeyChord.parse("Shortcut+Shift+P"));
        assertThat(catalog.action(DASHBOARD).category()).isEqualTo("View › Panels");
        assertThat(catalog.action(CUT).fixed()).isTrue();
        assertThat(catalog.action("menu.view.zoomIn").fixed()).isTrue();
        assertWithMessage("an item without text is named by its i18n key's text")
            .that(catalog.action("menu.view.blank").label()).isEqualTo(I18n.get("menu.view.blank"));
        assertThat(catalog.defaults()).doesNotContainKey(CUT);
        assertThat(catalog.defaults()).containsKey(RENAME_TAB);
        assertThat(catalog.defaults().get(RENAME_TAB)).isNull();

        KeyboardSettingsModel.Action tabJump = catalog.action(KeymapSupport.FIXED_TAB_JUMP);
        assertThat(tabJump.label()).isEqualTo(I18n.get(KeyboardSettingsModel.FIXED_TAB_JUMP_KEY));
        assertThat(tabJump.keysText()).isEqualTo("Ctrl+1 … Ctrl+9");
        assertThat(KeymapSupport.catalog(new Bar().menus, Os.MAC).action(KeymapSupport.FIXED_TAB_JUMP).keysText())
            .isEqualTo("Cmd+1 … Cmd+9");
        assertThat(catalog.action(KeymapSupport.FIXED_NEXT_TAB).defaultChord()).isEqualTo(KeyChord.parse("Ctrl+Tab"));
    }

    @Test
    void aChordAnotherActionUsesIsTakenButBlocksSavingUntilEachChordIsUsedOnce() {
        KeyboardSettingsModel model = model(Os.LINUX, KeymapOverrides.empty());

        Edit taken = model.assign(DASHBOARD, KeyChord.parse("Shortcut+Shift+P"));

        assertThat(taken.applied()).isTrue();
        assertThat(taken.problem()).isEqualTo(Problem.CONFLICT);
        assertThat(taken.message()).isEqualTo(I18n.get("settings.keyboard.problem.conflict", "Ctrl+Shift+P",
            "Command Palette"));
        Row dashboard = model.row(DASHBOARD);
        Row palette = model.row(PALETTE);
        assertThat(dashboard.status()).isEqualTo(Status.CONFLICT);
        assertThat(dashboard.otherAction()).isEqualTo(PALETTE);
        assertThat(palette.status()).isEqualTo(Status.CONFLICT);
        assertThat(palette.otherAction()).isEqualTo(DASHBOARD);
        assertThat(model.statusText(palette)).isEqualTo(I18n.get("settings.keyboard.status.conflict", "Dashboard"));
        assertThat(model.canSave()).isFalse();
        assertThat(model.conflictCount()).isEqualTo(1);
        assertThat(model.conflictsText()).isEqualTo(I18n.get("settings.keyboard.conflicts", "Ctrl+Shift+P"));

        // The second half of a swap clears the conflict.
        Edit second = model.assign(PALETTE, KeyChord.parse("Shortcut+Shift+D"));

        assertThat(second.problem()).isNull();
        assertThat(model.canSave()).isTrue();
        assertThat(model.conflictsText()).isEmpty();
        assertThat(model.row(PALETTE).status()).isEqualTo(Status.CHANGED);
        assertThat(model.row(DASHBOARD).status()).isEqualTo(Status.CHANGED);
        assertThat(model.overrides().toEntries()).containsExactly(
            "menu.view.commandPalette=Shortcut+Shift+D", "menu.view.dashboard=Shortcut+Shift+P").inOrder();
        assertThat(model.hasChanges()).isTrue();
    }

    @Test
    void theSameKeysOnAMacAreTwoChordsWhereCmdAndCtrlDiffer() {
        KeyboardSettingsModel mac = model(Os.MAC, KeymapOverrides.empty());
        mac.assign(DASHBOARD, KeyChord.parse("Ctrl+Shift+P"));
        assertWithMessage("Ctrl+Shift+P is not Cmd+Shift+P").that(mac.canSave()).isTrue();

        KeyboardSettingsModel linux = model(Os.LINUX, KeymapOverrides.empty());
        linux.assign(DASHBOARD, KeyChord.parse("Ctrl+Shift+P"));
        assertWithMessage("but the same key press on Linux").that(linux.canSave()).isFalse();
    }

    @Test
    void aChordThatBreaksARuleIsRefusedWithItsReasonAndNeverStored() {
        assertRefused(Os.LINUX, "Shortcut+L", Problem.RESERVED_SHELL, "Ctrl+L", "");
        assertRefused(Os.WINDOWS, "Shortcut+Shift+6", Problem.RESERVED_SHELL, "Ctrl+Shift+6", "");
        assertRefused(Os.WINDOWS, "Shortcut+Alt+Q", Problem.ALTGR_RANGE, "Ctrl+Alt+Q", "");
        assertRefused(Os.MAC, "Alt+L", Problem.ALTGR_RANGE, "Option+L", "");
        assertRefused(Os.MAC, "Shortcut+K", Problem.TERMINAL, "Cmd+K", "");
        assertRefused(Os.LINUX, "Shortcut+Shift+V", Problem.TERMINAL, "Ctrl+Shift+V", "");
        assertRefused(Os.WINDOWS, "Alt+F4", Problem.SYSTEM, "Alt+F4", "");
        assertRefused(Os.LINUX, "Shift+K", Problem.NEEDS_MODIFIER, "Shift+K", "");
        assertRefused(Os.LINUX, "Ctrl+Tab", Problem.FIXED, "Ctrl+Tab", I18n.get(KeyboardSettingsModel.FIXED_NEXT_TAB_KEY));
        assertRefused(Os.MAC, "Shortcut+3", Problem.FIXED, "Cmd+3", I18n.get(KeyboardSettingsModel.FIXED_TAB_JUMP_KEY));
        assertRefused(Os.MAC, "Shortcut+X", Problem.FIXED, "Cmd+X", "Cut");
        assertRefused(Os.MAC, "Shortcut+Equal", Problem.FIXED, "Cmd+Equal", "Zoom In");
        assertWithMessage("Ctrl with a letter is the shell's before it is Cut's")
            .that(model(Os.LINUX, KeymapOverrides.empty()).assign(DASHBOARD, KeyChord.parse("Shortcut+X")).problem())
            .isEqualTo(Problem.RESERVED_SHELL);
    }

    @Test
    void theMenuBarToggleMustKeepAShortcut() {
        KeyboardSettingsModel model = model(Os.LINUX, KeymapOverrides.empty());

        Edit removal = model.remove(MENU_BAR);

        assertThat(removal.applied()).isFalse();
        assertThat(removal.problem()).isEqualTo(Problem.REQUIRED);
        assertThat(removal.message()).isEqualTo(I18n.get("settings.keyboard.problem.required", "Ctrl+Shift+L",
            "Show Menu Bar"));
        assertThat(model.overrides().isEmpty()).isTrue();
        assertWithMessage("another chord is fine").that(model.assign(MENU_BAR, KeyChord.parse("Shortcut+Alt+M"))
            .applied()).isTrue();
    }

    @Test
    void removeResetAndAssigningTheDefaultAgain() {
        KeyboardSettingsModel model = model(Os.LINUX, KeymapOverrides.empty());

        Edit removed = model.remove(DASHBOARD);
        Row row = model.row(DASHBOARD);
        assertThat(removed.message()).isEqualTo(I18n.get("settings.keyboard.removed", "Dashboard"));
        assertThat(row.status()).isEqualTo(Status.CHANGED);
        assertThat(row.chord()).isNull();
        assertThat(model.shortcutText(row)).isEqualTo(I18n.get("settings.keyboard.none"));
        assertThat(model.statusText(row)).isEqualTo(I18n.get("settings.keyboard.status.removed"));
        assertThat(model.overrides().toEntries()).containsExactly("menu.view.dashboard=none");

        model.reset(DASHBOARD);
        assertThat(model.row(DASHBOARD).status()).isEqualTo(Status.DEFAULT);
        assertThat(model.overrides().isEmpty()).isTrue();
        assertThat(model.hasChanges()).isFalse();

        model.assign(NEW_TAB, KeyChord.parse("Shortcut+Alt+T"));
        Edit back = model.assign(NEW_TAB, KeyChord.parse("Shortcut+T"));
        assertWithMessage("the default chord only removes the override").that(model.overrides().isEmpty()).isTrue();
        assertThat(back.message()).isEqualTo(I18n.get("settings.keyboard.restored", "New Tab"));

        Edit assigned = model.assign(RENAME_TAB, KeyChord.parse("Shortcut+Shift+F2"));
        assertThat(assigned.message()).isEqualTo(I18n.get("settings.keyboard.assigned", "Ctrl+Shift+F2", "Rename Tab"));
        assertWithMessage("an action without a default goes back to none")
            .that(model.remove(RENAME_TAB).applied()).isTrue();
        assertThat(model.overrides().isEmpty()).isTrue();
        assertThat(model.row(RENAME_TAB).status()).isEqualTo(Status.DEFAULT);
    }

    @Test
    void aStoredOverrideThatBreaksARuleHereIsShownAsNotInEffectAndKept() {
        // Cmd+L on the Mac it was chosen on; Ctrl+L, the shell's, on Linux.
        KeymapOverrides stored = KeymapOverrides.parse(List.of("menu.file.newTab=Shortcut+L"));
        KeyboardSettingsModel linux = model(Os.LINUX, stored);

        Row row = linux.row(NEW_TAB);
        assertThat(row.status()).isEqualTo(Status.NOT_IN_EFFECT);
        assertThat(row.problem()).isEqualTo(Problem.RESERVED_SHELL);
        assertWithMessage("the default stays in effect").that(row.chord()).isEqualTo(KeyChord.parse("Shortcut+T"));
        assertThat(linux.statusText(row)).isEqualTo(I18n.get("settings.keyboard.status.notInEffect", "Ctrl+L"));
        assertThat(linux.detailText(row)).isEqualTo(I18n.get("settings.keyboard.problem.reservedShell", "Ctrl+L",
            "New Tab"));
        assertThat(linux.canSave()).isTrue();
        assertThat(linux.hasChanges()).isFalse();
        assertThat(linux.overrides().toEntries()).containsExactly("menu.file.newTab=Shortcut+L");

        assertThat(model(Os.MAC, stored).row(NEW_TAB).status()).isEqualTo(Status.CHANGED);
    }

    @Test
    void storedOverridesThatShareAChordAreAConflictToResolve() {
        KeyboardSettingsModel model = model(Os.LINUX, KeymapOverrides.parse(List.of(
            "menu.file.renameTab=Shortcut+Alt+R", "menu.view.dashboard=Shortcut+Alt+R")));

        assertThat(model.row(RENAME_TAB).status()).isEqualTo(Status.CONFLICT);
        assertThat(model.row(DASHBOARD).status()).isEqualTo(Status.CONFLICT);
        assertThat(model.canSave()).isFalse();
        assertThat(model.rows(null, true).stream().map(Row::id).toList()).containsExactly(RENAME_TAB, DASHBOARD);

        model.reset(DASHBOARD);
        assertThat(model.canSave()).isTrue();
    }

    @Test
    void overridesOfActionsThisVersionDoesNotKnowAreKeptAndNamed() {
        KeyboardSettingsModel model = model(Os.LINUX, KeymapOverrides.parse(List.of(
            "menu.future.thing=Shortcut+Alt+F", "menu.edit.cut=Shortcut+Alt+X", "menu.view.dashboard=Shortcut+Alt+D")));

        assertThat(model.unknownActionIds()).containsExactly("menu.edit.cut", "menu.future.thing").inOrder();
        assertThat(model.unknownActionsText()).isEqualTo(I18n.get("settings.keyboard.problem.unknownAction",
            "menu.edit.cut, menu.future.thing"));

        model.resetAll();
        assertThat(model.overrides().toEntries())
            .containsExactly("menu.edit.cut=Shortcut+Alt+X", "menu.future.thing=Shortcut+Alt+F").inOrder();
        assertThat(model.row(DASHBOARD).status()).isEqualTo(Status.DEFAULT);
        assertThat(model(Os.LINUX, KeymapOverrides.empty()).unknownActionsText()).isEmpty();
    }

    @Test
    void theKeymapTheModelSavesIsTheOneTheWindowsApply() {
        KeyboardSettingsModel model = model(Os.WINDOWS, KeymapOverrides.parse(List.of(
            "menu.file.newTab=Shortcut+L", "menu.future.thing=Shortcut+Alt+F")));
        assertThat(model.assign(DASHBOARD, KeyChord.parse("Shortcut+Shift+F5")).applied()).isTrue();
        assertThat(model.assign(RENAME_TAB, KeyChord.parse("F2")).applied()).isTrue();
        model.remove(PALETTE);
        assertThat(model.canSave()).isTrue();

        KeyboardSettingsModel.Catalog catalog = model.catalog();
        KeymapOverrides.Resolution applied = KeymapOverrides.parse(model.overrides().toEntries())
            .resolve(catalog.defaults(), catalog.rules());

        assertThat(model.effective()).isEqualTo(applied.effective());
        assertThat(applied.chord(PALETTE)).isNull();
        assertThat(applied.chord(RENAME_TAB)).isEqualTo(KeyChord.parse("F2"));
    }

    @Test
    void fixedShortcutsAreListedButCannotBeEdited() {
        KeyboardSettingsModel model = model(Os.LINUX, KeymapOverrides.empty());

        Row cut = model.row(CUT);
        assertThat(cut.status()).isEqualTo(Status.FIXED);
        assertThat(cut.editable()).isFalse();
        assertThat(model.shortcutText(cut)).isEqualTo("Ctrl+X");
        assertThat(model.statusText(cut)).isEqualTo(I18n.get("settings.keyboard.status.fixed"));
        assertThat(model.detailText(cut)).isEqualTo(I18n.get("settings.keyboard.fixedRow"));
        assertThat(model.shortcutText(model.row(KeymapSupport.FIXED_TAB_JUMP))).isEqualTo("Ctrl+1 … Ctrl+9");
        assertThat(model.shortcutText(model.row(KeymapSupport.FIXED_PREVIOUS_TAB))).isEqualTo("Ctrl+Shift+Tab");
        assertThrows(IllegalArgumentException.class, () -> model.assign(CUT, KeyChord.parse("Shortcut+Alt+X")));
        assertThrows(IllegalArgumentException.class, () -> model.remove(KeymapSupport.FIXED_NEXT_TAB));
    }

    @Test
    void theFilterMatchesTheActionTheMenuAndTheShortcut() {
        KeyboardSettingsModel model = model(Os.LINUX, KeymapOverrides.empty());

        assertThat(ids(model.rows("palette", false))).containsExactly(PALETTE);
        assertThat(ids(model.rows("  CTRL+SHIFT+D ", false))).containsExactly(DASHBOARD);
        assertThat(ids(model.rows("panels", false))).containsExactly(DASHBOARD);
        assertThat(model.rows("", false)).hasSize(model.catalog().actions().size());
        assertThat(model.rows(null, true)).isEmpty();

        model.assign(NEW_TAB, KeyChord.parse("Shortcut+Alt+N"));
        assertThat(ids(model.rows(null, true))).containsExactly(NEW_TAB);
        assertThat(ids(model.rows(I18n.get("settings.keyboard.status.changed"), false))).containsExactly(NEW_TAB);
    }

    @Test
    void everyProblemAndEveryFixedShortcutWithoutAMenuItemHasItsText() {
        for (Problem problem : Problem.values()) {
            assertWithMessage(problem.name()).that(KeyboardSettingsModel.PROBLEM_KEYS.get(problem))
                .startsWith("settings.keyboard.problem.");
        }
        assertThat(KeyboardSettingsModel.FIXED_OWNER_KEYS.keySet()).containsExactly(KeymapSupport.FIXED_TAB_JUMP,
            KeymapSupport.FIXED_NEXT_TAB, KeymapSupport.FIXED_PREVIOUS_TAB);

        KeyboardSettingsModel model = model(Os.LINUX, KeymapOverrides.empty());
        assertThat(model.actionLabel(KeymapSupport.FIXED_TAB_JUMP)).isEqualTo(I18n.get("settings.keyboard.fixed.tabJump"));
        assertWithMessage("a menu key the catalog lacks is named by its i18n text without the ellipsis")
            .that(model.actionLabel("menu.settings.global")).isEqualTo(
                I18n.get("menu.settings.global").replace("...", "").replace("…", "").strip());
        assertThat(model.actionLabel("no.such.key")).isEqualTo("no.such.key");
    }

    private static void assertRefused(Os os, String chord, Problem problem, String shown, String other) {
        KeyboardSettingsModel model = model(os, KeymapOverrides.empty());

        Edit edit = model.assign(DASHBOARD, KeyChord.parse(chord));

        assertWithMessage("%s on %s", chord, os).that(edit.applied()).isFalse();
        assertWithMessage("%s on %s", chord, os).that(edit.problem()).isEqualTo(problem);
        String expected = problem == Problem.FIXED
            ? I18n.get("settings.keyboard.problem.fixed", shown, other)
            : I18n.get(KeyboardSettingsModel.PROBLEM_KEYS.get(problem), shown, "Dashboard");
        assertWithMessage("%s on %s", chord, os).that(edit.message()).isEqualTo(expected);
        assertWithMessage("%s on %s", chord, os).that(model.overrides().isEmpty()).isTrue();
        assertThat(model.row(DASHBOARD).status()).isEqualTo(Status.DEFAULT);
    }

    private static List<String> ids(List<Row> rows) {
        return rows.stream().map(Row::id).toList();
    }

    private static KeyboardSettingsModel model(Os os, KeymapOverrides stored) {
        return new KeyboardSettingsModel(KeymapSupport.catalog(new Bar().menus, os), stored);
    }

    /** A small menu bar shaped like the main window's. */
    private static final class Bar {
        final List<Menu> menus;

        Bar() {
            MenuItem newTab = item("_New Tab", NEW_TAB, new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN));
            MenuItem renameTab = item("Rename Tab...", RENAME_TAB, null);
            MenuItem cut = item("Cut", CUT, new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN));
            MenuItem palette = ActionIds.exclude(item("Command _Palette…", PALETTE,
                new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN)));
            MenuItem menuBar = item("Show Menu Bar", MENU_BAR,
                new KeyCodeCombination(KeyCode.L, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
            CheckMenuItem dashboard = ActionIds.tag(new CheckMenuItem("Dashboard"), DASHBOARD);
            dashboard.setAccelerator(new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN,
                KeyCombination.SHIFT_DOWN));
            MenuItem zoomIn = item("Zoom In", "menu.view.zoomIn", new KeyCodeCombination(KeyCode.PLUS,
                KeyCombination.ALT_DOWN));
            MenuItem blank = item("", "menu.view.blank", null);
            Menu file = new Menu("_File");
            file.getItems().addAll(newTab, renameTab);
            Menu edit = new Menu("Edit");
            edit.getItems().add(cut);
            Menu panels = new Menu("Panels");
            panels.getItems().add(dashboard);
            Menu view = new Menu("View");
            view.getItems().addAll(palette, menuBar, panels, zoomIn, blank);
            menus = List.of(file, edit, view);
        }

        private static MenuItem item(String text, String id, KeyCombination accelerator) {
            MenuItem item = ActionIds.tag(new MenuItem(text), id);
            item.setAccelerator(accelerator);
            return item;
        }
    }
}
