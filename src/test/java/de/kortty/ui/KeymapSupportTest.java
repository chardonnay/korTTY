package de.kortty.ui;

import com.sithtermfx.ui.TerminalActionPresentation;
import com.sithtermfx.ui.settings.DynamicFontSizeSettingsProvider;
import com.sithtermfx.ui.settings.SystemSettingsProvider;
import de.kortty.core.KeyChord;
import de.kortty.core.KeyChord.Os;
import de.kortty.core.KeymapOverrides;
import de.kortty.core.KeymapOverrides.Problem;
import de.kortty.core.KeymapOverrides.Resolution;
import de.kortty.model.ConnectionSettings;
import de.kortty.ui.KeyTypedResidueGuard.Residue;
import de.kortty.ui.actions.ActionIds;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import org.testng.annotations.Test;

import java.lang.reflect.Constructor;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.IntSupplier;
import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The JavaFX side of the user's shortcut overrides: chords and KeyCodeCombinations, the default
 * keymap read off a menu bar, the overrides applied to the menu items and the scene shortcut
 * router's {@link RoutedChord}s, and korTTY's fixed shortcuts and the terminal's own key actions,
 * which no override can take. No JavaFX toolkit is started: menu items are no nodes.
 */
class KeymapSupportTest {

    private static final KeyCombination PALETTE =
        new KeyCodeCombination(KeyCode.P, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
    private static final KeyCombination NEW_TAB = new KeyCodeCombination(KeyCode.T, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination CUT = new KeyCodeCombination(KeyCode.X, KeyCombination.SHORTCUT_DOWN);
    private static final KeyCombination ZOOM_IN = new KeyCodeCombination(KeyCode.PLUS, KeyCombination.ALT_DOWN);

    @Test
    void keyCodeCombinationsAndChordsConvertBothWays() {
        assertThat(KeymapSupport.chordOf(PALETTE, true)).isEqualTo(KeyChord.parse("Shortcut+Shift+P"));
        assertThat(KeymapSupport.chordOf(ZOOM_IN, false)).isEqualTo(KeyChord.parse("Alt+Plus"));
        assertThat(KeymapSupport.chordOf(new KeyCodeCombination(KeyCode.TAB, KeyCombination.CONTROL_DOWN), true))
            .isEqualTo(KeyChord.parse("Ctrl+Tab"));
        assertThat(KeymapSupport.chordOf(new KeyCodeCombination(KeyCode.F12), false)).isEqualTo(KeyChord.parse("F12"));
        assertWithMessage("Meta is Cmd on a Mac").that(KeymapSupport.chordOf(
            new KeyCodeCombination(KeyCode.K, KeyCombination.META_DOWN), true)).isEqualTo(KeyChord.parse("Shortcut+K"));
        assertWithMessage("and the Windows key elsewhere").that(KeymapSupport.chordOf(
            new KeyCodeCombination(KeyCode.K, KeyCombination.META_DOWN), false)).isNull();
        assertThat(KeymapSupport.chordOf(new KeyCodeCombination(KeyCode.K, KeyCombination.SHIFT_ANY), true)).isNull();
        assertThat(KeymapSupport.chordOf(new KeyCodeCombination(KeyCode.NUMPAD1, KeyCombination.SHORTCUT_DOWN), true))
            .isNull();
        assertThat(KeymapSupport.chordOf(null, true)).isNull();

        for (String raw : new String[] {"Shortcut+Shift+P", "Ctrl+Tab", "Alt+Plus", "F12", "Shortcut+Ctrl+Shift+Alt+F5",
            "Shortcut+Alt+Left", "Shortcut+Comma"}) {
            KeyChord chord = KeyChord.parse(raw);
            assertWithMessage(raw).that(KeymapSupport.chordOf(KeymapSupport.combinationOf(chord), true)).isEqualTo(chord);
        }
        assertThat(KeymapSupport.combinationOf(KeyChord.parse("Shortcut+Shift+P"))).isEqualTo(PALETTE);
        assertThat(KeymapSupport.combinationOf(null)).isNull();
    }

    @Test
    void theDefaultsAreTheTaggedItemsAcceleratorsAndSurviveAnOverride() {
        Bar bar = new Bar();
        KeymapSupport.Defaults defaults = KeymapSupport.defaults(bar.menus, Os.LINUX);

        assertThat(new ArrayList<>(defaults.rebindable().keySet())).containsExactly(
            "menu.file.newTab", "menu.file.renameTab", "menu.view.commandPalette", "menu.view.dashboard").inOrder();
        assertThat(defaults.rebindable().get("menu.file.newTab")).isEqualTo(KeyChord.parse("Shortcut+T"));
        assertThat(defaults.rebindable().get("menu.file.renameTab")).isNull();
        assertThat(defaults.fixed()).containsExactly("menu.edit.cut", KeyChord.parse("Shortcut+X"),
            "menu.view.zoomIn", KeyChord.parse("Alt+Plus"));

        Resolution keymap = KeymapOverrides.parse(List.of("menu.file.newTab=Shortcut+Alt+N",
            "menu.file.renameTab=Shortcut+Shift+R", "menu.view.commandPalette=none")).resolve(defaults.rebindable(),
            KeymapSupport.rules(Os.LINUX, defaults.fixed()));
        KeymapSupport.applyToMenus(bar.menus, keymap);

        assertThat(bar.newTab.getAccelerator()).isEqualTo(
            new KeyCodeCombination(KeyCode.N, KeyCombination.SHORTCUT_DOWN, KeyCombination.ALT_DOWN));
        assertThat(bar.renameTab.getAccelerator()).isEqualTo(
            new KeyCodeCombination(KeyCode.R, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN));
        assertThat(bar.palette.getAccelerator()).isNull();
        assertWithMessage("the default object itself, not a copy").that(bar.dashboard.getAccelerator())
            .isSameInstanceAs(bar.dashboardDefault);
        assertThat(bar.cut.getAccelerator()).isSameInstanceAs(CUT);

        // The defaults are read from what the builders set, not from the overridden accelerators.
        KeymapSupport.Defaults again = KeymapSupport.defaults(bar.menus, Os.LINUX);
        assertThat(again).isEqualTo(defaults);
        KeymapSupport.applyToMenus(bar.menus, KeymapOverrides.empty().resolve(again.rebindable(), KeymapSupport.rules(
            Os.LINUX, again.fixed())));
        assertThat(bar.newTab.getAccelerator()).isSameInstanceAs(NEW_TAB);
        assertThat(bar.palette.getAccelerator()).isSameInstanceAs(PALETTE);
        assertThat(bar.renameTab.getAccelerator()).isNull();
    }

    @Test
    void rebuiltSubmenusUntaggedItemsAndSecondCopiesAreNoActions() {
        Bar bar = new Bar();
        Map<String, MenuItem> items = KeymapSupport.actionItems(bar.menus);

        assertThat(items).doesNotContainKey("menu.file.recentlyClosed.entry");
        assertThat(items.get("menu.view.dashboard")).isSameInstanceAs(bar.dashboard);
        assertThat(items.values()).doesNotContain(bar.untagged);
        assertWithMessage("excluded from the palette, but an action of the keymap")
            .that(items).containsKey("menu.view.commandPalette");
    }

    @Test
    void anOverrideThatBreaksARuleLeavesTheMenuOnItsDefault() {
        Bar bar = new Bar();
        KeymapSupport.Defaults defaults = KeymapSupport.defaults(bar.menus, Os.MAC);
        Resolution keymap = KeymapOverrides.parse(List.of("menu.file.newTab=Shortcut+X",
            "menu.view.commandPalette=Shortcut+K", "menu.view.dashboard=Shortcut+Shift+P"))
            .resolve(defaults.rebindable(), KeymapSupport.rules(Os.MAC, defaults.fixed()));
        KeymapSupport.applyToMenus(bar.menus, keymap);

        assertThat(bar.newTab.getAccelerator()).isSameInstanceAs(NEW_TAB);
        assertThat(bar.palette.getAccelerator()).isSameInstanceAs(PALETTE);
        assertThat(bar.dashboard.getAccelerator()).isSameInstanceAs(bar.dashboardDefault);
        assertThat(keymap.rejected().stream().map(KeymapOverrides.Rejection::problem).toList())
            .containsExactly(Problem.FIXED, Problem.TERMINAL, Problem.CONFLICT).inOrder();
        assertThat(KeymapSupport.describeRejections(keymap)).containsExactly(
            "menu.file.newTab=Shortcut+X (FIXED with menu.edit.cut)",
            "menu.view.commandPalette=Shortcut+K (TERMINAL)",
            "menu.view.dashboard=Shortcut+Shift+P (CONFLICT with menu.view.commandPalette)").inOrder();
    }

    @Test
    void theRoutersOwnKeyRulesAreFixedShortcuts() {
        Map<String, KeyChord> fixedMenus = Map.of("menu.edit.cut", KeyChord.parse("Shortcut+X"));

        assertThat(fixed("Shortcut+Tab", Os.LINUX, fixedMenus)).isEqualTo(KeymapSupport.FIXED_NEXT_TAB);
        assertThat(fixed("Ctrl+Tab", Os.MAC, fixedMenus)).isEqualTo(KeymapSupport.FIXED_NEXT_TAB);
        assertThat(fixed("Ctrl+Shift+Tab", Os.MAC, fixedMenus)).isEqualTo(KeymapSupport.FIXED_PREVIOUS_TAB);
        assertThat(fixed("Shortcut+1", Os.WINDOWS, fixedMenus)).isEqualTo(KeymapSupport.FIXED_TAB_JUMP);
        assertThat(fixed("Shortcut+9", Os.MAC, fixedMenus)).isEqualTo(KeymapSupport.FIXED_TAB_JUMP);
        assertWithMessage("Shift is tolerated on a Mac").that(fixed("Shortcut+Shift+9", Os.MAC, fixedMenus))
            .isEqualTo(KeymapSupport.FIXED_TAB_JUMP);
        assertWithMessage("exactly Ctrl elsewhere").that(fixed("Shortcut+Shift+9", Os.LINUX, fixedMenus)).isNull();
        assertThat(fixed("Alt+Plus", Os.WINDOWS, fixedMenus)).isEqualTo("menu.view.zoomIn");
        assertThat(fixed("Shortcut+Equal", Os.MAC, fixedMenus)).isEqualTo("menu.view.zoomIn");
        assertThat(fixed("Shortcut+Minus", Os.LINUX, fixedMenus)).isEqualTo("menu.view.zoomOut");
        assertThat(fixed("Alt+0", Os.WINDOWS, fixedMenus)).isEqualTo("menu.view.resetZoom");
        assertThat(fixed("Shortcut+Shift+F12", Os.MAC, fixedMenus)).isEqualTo("menu.view.fullscreen");
        assertThat(fixed("Shortcut+X", Os.MAC, fixedMenus)).isEqualTo("menu.edit.cut");
        assertWithMessage("Option+Plus types a character on a Mac and does not zoom")
            .that(fixed("Alt+Plus", Os.MAC, fixedMenus)).isNull();
        assertThat(fixed("Shortcut+Alt+P", Os.LINUX, fixedMenus)).isNull();
    }

    @Test
    void theTerminalChordsCoverEveryKeyActionOfTheTerminal() throws Exception {
        Os host = Os.current();
        boolean hostMac = host.isMac();
        assertThat(com.sithtermfx.core.util.Platform.isMacOS()).isEqualTo(hostMac);
        SystemSettingsProvider provider = korttyProvider();
        List<TerminalActionPresentation> presentations = List.of(
            provider.getOpenUrlActionPresentation(), provider.getCopyActionPresentation(),
            provider.getPasteActionPresentation(), provider.getClearBufferActionPresentation(),
            provider.getPageUpActionPresentation(), provider.getPageDownActionPresentation(),
            provider.getLineUpActionPresentation(), provider.getLineDownActionPresentation(),
            provider.getFindActionPresentation(), provider.getSelectAllActionPresentation());
        for (TerminalActionPresentation presentation : presentations) {
            for (KeyCombination combination : presentation.getKeyCombinations()) {
                KeyChord chord = KeymapSupport.chordOf(combination, hostMac);
                assertWithMessage("%s %s", presentation.getName(), combination).that(chord).isNotNull();
                assertWithMessage("%s %s", presentation.getName(), combination)
                    .that(KeymapOverrides.terminalChords(host)).contains(chord.physical(host));
            }
        }
        for (boolean mac : new boolean[] {true, false}) {
            Os os = mac ? Os.MAC : Os.WINDOWS;
            List<KeyCombination> korttys = new ArrayList<>();
            korttys.addAll(TerminalView.clearBufferActionPresentation(mac).getKeyCombinations());
            korttys.addAll(TerminalView.findActionPresentation(mac).getKeyCombinations());
            for (KeyCombination combination : korttys) {
                assertWithMessage("%s %s", os, combination).that(KeymapOverrides.terminalChords(os))
                    .contains(KeymapSupport.chordOf(combination, mac).physical(os));
            }
        }
    }

    @Test
    void aRoutedChordFollowsItsBindingAndItsResidue() {
        RoutedChord chord = new RoutedChord("menu.view.commandPalette", PALETTE, PaletteKeys.RESIDUE);

        assertThat(chord.matches(press(KeyCode.P, true, false, false, true, true))).isTrue();
        assertThat(chord.residue()).isSameInstanceAs(PaletteKeys.RESIDUE);

        KeyCodeCombination rebound = KeymapSupport.combinationOf(KeyChord.parse("Shortcut+Alt+K"));
        chord.bind(rebound);
        assertThat(chord.matches(press(KeyCode.P, true, false, false, true, true))).isFalse();
        assertThat(chord.matches(press(KeyCode.K, false, false, true, true, true))).isTrue();
        assertWithMessage("a chord the user chose swallows whatever it types")
            .that(chord.residue()).isEqualTo(Residue.anyCharacter());

        chord.bind((KeyCombination) null);
        assertThat(chord.chord()).isNull();
        assertThat(chord.matches(press(KeyCode.P, true, false, false, true, true))).isFalse();

        chord.bind(KeymapSupport.combinationOf(KeyChord.parse("Shortcut+Shift+P")));
        assertThat(chord.chord()).isSameInstanceAs(PALETTE);
        assertThat(chord.residue()).isSameInstanceAs(PaletteKeys.RESIDUE);
    }

    @Test
    void aRoutedChordBindsFromTheResolutionAndFallsBackToItsDefault() {
        RoutedChord chord = new RoutedChord("menu.view.commandPalette", PALETTE, PaletteKeys.RESIDUE);
        Map<String, KeyChord> defaults = Map.of("menu.view.commandPalette", KeyChord.parse("Shortcut+Shift+P"));

        chord.bind(KeymapOverrides.parse(List.of("menu.view.commandPalette=Shortcut+Alt+P"))
            .resolve(defaults, KeymapOverrides.Rules.of(Os.LINUX)));
        assertThat(chord.chord()).isEqualTo(KeymapSupport.combinationOf(KeyChord.parse("Shortcut+Alt+P")));

        chord.bind(KeymapOverrides.empty().resolve(Map.of(), KeymapOverrides.Rules.of(Os.LINUX)));
        assertWithMessage("an action the menu bar lacks keeps its default").that(chord.chord()).isSameInstanceAs(PALETTE);
    }

    @Test
    void theRouterConsumesTheReboundChordAndSwallowsItsResidue() {
        RoutedChord chord = new RoutedChord("menu.security.credentials",
            new KeyCodeCombination(KeyCode.M, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN),
            Residue.ofLetter('M'));
        List<String> opened = new ArrayList<>();
        SceneShortcutRouter router = new SceneShortcutRouter(false)
            .consume(chord::matches, SceneShortcutRouter.ALWAYS, () -> opened.add("credentials"), chord::residue);
        chord.bind(KeymapSupport.combinationOf(KeyChord.parse("Shortcut+Shift+J")));

        KeyEvent oldChord = keyPressed(KeyCode.M, true, true, false, false);
        router.onKeyPressed(oldChord);
        assertThat(oldChord.isConsumed()).isFalse();
        assertThat(opened).isEmpty();

        KeyEvent newChord = keyPressed(KeyCode.J, true, true, false, false);
        router.onKeyPressed(newChord);
        assertThat(newChord.isConsumed()).isTrue();
        assertThat(opened).containsExactly("credentials");
        KeyEvent residue = keyTyped("\n");
        router.onKeyTyped(residue);
        assertWithMessage("Ctrl+Shift+J types a line feed on Windows: never into the shell")
            .that(residue.isConsumed()).isTrue();
        KeyEvent next = keyTyped("j");
        router.onKeyTyped(next);
        assertWithMessage("only the one KEY_TYPED after the chord").that(next.isConsumed()).isFalse();
    }

    @Test
    void thePalettesPassThroughFollowsTheChordInEffect() {
        RoutedChord chord = new RoutedChord("menu.view.commandPalette", PALETTE, PaletteKeys.RESIDUE);
        Predicate<KeyEvent> passThrough = PaletteKeys.passThrough(chord::chord, false);

        assertThat(passThrough.test(keyPressed(KeyCode.P, true, true, false, false))).isTrue();
        chord.bind(KeymapSupport.combinationOf(KeyChord.parse("Shortcut+Alt+F5")));
        assertThat(passThrough.test(keyPressed(KeyCode.P, true, true, false, false))).isFalse();
        assertThat(passThrough.test(keyPressed(KeyCode.F5, false, true, true, false))).isTrue();
        chord.bind((KeyCombination) null);
        assertThat(passThrough.test(keyPressed(KeyCode.F5, false, true, true, false))).isFalse();
    }

    @Test
    void everyPaneActionHasTheIdOfItsMenuItem() {
        assertThat(PaneShortcuts.PaneAction.SPLIT.actionId()).isEqualTo(PaneMenuSupport.SPLIT_AUTO_KEY);
        assertThat(PaneShortcuts.PaneAction.ZOOM.actionId()).isEqualTo(PaneMenuSupport.ZOOM_KEY);
        for (PaneNavigator.PaneDirection direction : PaneNavigator.PaneDirection.values()) {
            assertThat(PaneShortcuts.PaneAction.focus(direction).actionId()).isEqualTo(PaneMenuSupport.focusKey(direction));
        }
    }

    private static String fixed(String chord, Os os, Map<String, KeyChord> fixedMenus) {
        return KeymapSupport.fixedOwner(KeyChord.parse(chord), os, fixedMenus);
    }

    /** A small menu bar shaped like the main window's. */
    private static final class Bar {
        final MenuItem newTab = ActionIds.tag(new MenuItem("New Tab"), "menu.file.newTab");
        final MenuItem renameTab = ActionIds.tag(new MenuItem("Rename Tab"), "menu.file.renameTab");
        final MenuItem cut = ActionIds.tag(new MenuItem("Cut"), "menu.edit.cut");
        final MenuItem palette = ActionIds.exclude(ActionIds.tag(new MenuItem("Command Palette"),
            "menu.view.commandPalette"));
        final KeyCombination dashboardDefault =
            new KeyCodeCombination(KeyCode.D, KeyCombination.SHORTCUT_DOWN, KeyCombination.SHIFT_DOWN);
        final CheckMenuItem dashboard = ActionIds.tag(new CheckMenuItem("Dashboard"), "menu.view.dashboard");
        final MenuItem dashboardCopy = ActionIds.tag(new MenuItem("Dashboard again"), "menu.view.dashboard");
        final MenuItem zoomIn = ActionIds.tag(new MenuItem("Zoom In"), "menu.view.zoomIn");
        final MenuItem untagged = new MenuItem("Untagged");
        final List<Menu> menus;

        Bar() {
            newTab.setAccelerator(NEW_TAB);
            cut.setAccelerator(CUT);
            palette.setAccelerator(PALETTE);
            dashboard.setAccelerator(dashboardDefault);
            dashboardCopy.setAccelerator(new KeyCodeCombination(KeyCode.F9));
            zoomIn.setAccelerator(ZOOM_IN);
            Menu recentlyClosed = ActionIds.exclude(new Menu("Recently Closed"));
            recentlyClosed.getItems().add(ActionIds.tag(new MenuItem("ssh host"), "menu.file.recentlyClosed.entry"));
            Menu file = new Menu("File");
            file.getItems().addAll(newTab, renameTab, recentlyClosed, untagged);
            Menu edit = new Menu("Edit");
            edit.getItems().add(cut);
            Menu sub = new Menu("Sub");
            sub.getItems().add(dashboardCopy);
            Menu view = new Menu("View");
            view.getItems().addAll(palette, dashboard, sub, zoomIn);
            menus = List.of(file, edit, view);
        }
    }

    private static SceneShortcutRouter.KeyPress press(KeyCode code, boolean shift, boolean ctrl, boolean alt,
                                                      boolean meta, boolean macOs) {
        return new SceneShortcutRouter.KeyPress(code, "", "", shift, ctrl, alt, meta, macOs);
    }

    private static KeyEvent keyPressed(KeyCode code, boolean shift, boolean ctrl, boolean alt, boolean meta) {
        return new KeyEvent(KeyEvent.KEY_PRESSED, KeyEvent.CHAR_UNDEFINED, "", code, shift, ctrl, alt, meta);
    }

    private static KeyEvent keyTyped(String character) {
        return new KeyEvent(KeyEvent.KEY_TYPED, character, "", KeyCode.UNDEFINED, false, false, false, false);
    }

    private static SystemSettingsProvider korttyProvider() throws Exception {
        Class<?> cls = Class.forName("de.kortty.ui.TerminalView$KorTTYSettingsProvider");
        Constructor<?> constructor = cls.getDeclaredConstructor(
            ConnectionSettings.class, DynamicFontSizeSettingsProvider.class, IntSupplier.class);
        constructor.setAccessible(true);
        return (SystemSettingsProvider) constructor.newInstance(
            new ConnectionSettings(), new DynamicFontSizeSettingsProvider(14f), (IntSupplier) () -> 0);
    }
}
