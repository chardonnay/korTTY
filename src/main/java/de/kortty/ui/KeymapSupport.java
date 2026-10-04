package de.kortty.ui;

import de.kortty.core.KeyChord;
import de.kortty.core.KeyChord.Os;
import de.kortty.core.KeyChord.Physical;
import de.kortty.core.KeymapOverrides;
import de.kortty.core.KeymapOverrides.Resolution;
import de.kortty.core.KeymapOverrides.Rules;
import de.kortty.ui.SceneShortcutRouter.KeyPress;
import de.kortty.ui.actions.ActionIds;
import de.kortty.ui.actions.MenuActionHarvester;
import de.kortty.ui.actions.MenuItemActivation;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCodeCombination;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyCombination.Modifier;
import javafx.scene.input.KeyCombination.ModifierValue;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Applies the user's shortcut overrides ({@link KeymapOverrides}, stored in the global settings) to
 * the main window: the keymap's actions are the tagged items of the in-window menu bar
 * ({@link ActionIds#tag}, the id being the item's i18n key), each with the accelerator its menu
 * builder gave it as the default. The scene shortcut router's chords follow the same keymap
 * ({@link RoutedChord}). Pure functions over menu items and key facts with an explicit platform, so
 * they run in plain unit tests: a {@link MenuItem} needs no JavaFX toolkit.
 *
 * <p>A few actions keep their keys ({@link #FIXED_ACTION_IDS}), because more than the menu item and
 * the router handle them: Cut, Copy and Paste are also the terminal's and every text field's
 * clipboard keys, Previous and Next Prompt are key actions of every terminal pane, and the zoom keys
 * and F12 are matched by layout-aware rules ({@link SceneShortcutKeys}). Ctrl+Tab, Ctrl+Shift+Tab
 * and Cmd/Ctrl+1..9 ({@link TabKeyboardShortcuts}) have no menu item and are fixed as well. A chord
 * of a fixed shortcut cannot be given to another action ({@link KeymapOverrides.Problem#FIXED}).
 */
final class KeymapSupport {

    /** Menu actions that keep their default keys; see the class comment. */
    static final Set<String> FIXED_ACTION_IDS = Set.of(
        "menu.edit.cut", "menu.edit.copy", "menu.edit.paste",
        "menu.edit.previousPrompt", "menu.edit.nextPrompt",
        "menu.view.zoomIn", "menu.view.zoomOut", "menu.view.resetZoom", "menu.view.fullscreen");

    /**
     * Actions that must keep a shortcut: with the menu bar hidden, its toggle is the keyboard's only
     * way back to the menus.
     */
    static final Set<String> REQUIRED_ACTION_IDS = Set.of("menu.view.menuBar");

    /** The fixed owner id of Cmd/Ctrl+1..9, which jump to a tab. */
    static final String FIXED_TAB_JUMP = "keymap.fixed.tabJump";
    /** The fixed owner id of Ctrl+Tab, the palette's Next Tab action. */
    static final String FIXED_NEXT_TAB = "palette.action.nextTab";
    /** The fixed owner id of Ctrl+Shift+Tab, the palette's Previous Tab action. */
    static final String FIXED_PREVIOUS_TAB = "palette.action.previousTab";

    /** Item property that keeps the accelerator its menu builder set, before any override replaced it. */
    static final String DEFAULT_ACCELERATOR_PROPERTY = "kortty.keymap.defaultAccelerator";

    /** {@link #DEFAULT_ACCELERATOR_PROPERTY} of an item that had no accelerator. */
    private static final Object NO_DEFAULT = new Object();

    private KeymapSupport() {
    }

    /**
     * The default keymap of a menu bar, split into the actions the user can rebind and the fixed
     * ones, each in menu order with its default chord ({@code null} for none).
     */
    record Defaults(@NotNull Map<String, KeyChord> rebindable, @NotNull Map<String, KeyChord> fixed) {
    }

    /**
     * The chord of {@code combination}, or {@code null} when it names none a keymap can hold: no
     * KeyCodeCombination, a key without a chord name, a modifier marked ANY, or Meta outside macOS
     * (where it is the Windows or Super key). On macOS Meta is Cmd, the same key as Shortcut.
     */
    static @Nullable KeyChord chordOf(@Nullable KeyCombination combination, boolean macOs) {
        if (!(combination instanceof KeyCodeCombination keyCode)) {
            return null;
        }
        String key = KeyChord.keyForJavaFxName(keyCode.getCode().name());
        if (key == null || anyIsAny(keyCode)) {
            return null;
        }
        boolean meta = keyCode.getMeta() == ModifierValue.DOWN;
        if (meta && !macOs) {
            return null;
        }
        return new KeyChord(keyCode.getShortcut() == ModifierValue.DOWN || meta,
            keyCode.getControl() == ModifierValue.DOWN, keyCode.getShift() == ModifierValue.DOWN,
            keyCode.getAlt() == ModifierValue.DOWN, key);
    }

    /** The KeyCodeCombination of {@code chord}, as menu items and the router take it; {@code null} for none. */
    static @Nullable KeyCodeCombination combinationOf(@Nullable KeyChord chord) {
        if (chord == null) {
            return null;
        }
        List<Modifier> modifiers = new ArrayList<>(4);
        if (chord.shortcut()) {
            modifiers.add(KeyCombination.SHORTCUT_DOWN);
        }
        if (chord.control()) {
            modifiers.add(KeyCombination.CONTROL_DOWN);
        }
        if (chord.shift()) {
            modifiers.add(KeyCombination.SHIFT_DOWN);
        }
        if (chord.alt()) {
            modifiers.add(KeyCombination.ALT_DOWN);
        }
        return new KeyCodeCombination(KeyCode.valueOf(chord.javafxKeyCodeName()), modifiers.toArray(Modifier[]::new));
    }

    /**
     * The keymap's items of a menu bar: every tagged leaf item, the first one only where two share an
     * id, skipping the submenus that are rebuilt while they open ({@link ActionIds#exclude}) and the
     * custom items (sliders, separators). A tagged item that is itself excluded from the palette,
     * such as View → Command Palette, is still an action of the keymap.
     */
    static @NotNull Map<String, MenuItem> actionItems(@NotNull List<Menu> menus) {
        Map<String, MenuItem> items = new LinkedHashMap<>();
        for (Map.Entry<String, Located> entry : locate(menus).entrySet()) {
            items.put(entry.getKey(), entry.getValue().item());
        }
        return items;
    }

    /**
     * What the Settings → Keyboard page lists for a menu bar: every action item in menu order, named
     * by its menu text and its menu path as the command palette names it, the fixed ones marked;
     * then the fixed shortcuts without a menu item (Ctrl+Tab, Ctrl+Shift+Tab, Cmd/Ctrl+1..9); and
     * the rules on {@code os}. An item without text is named by its i18n key's text.
     */
    static @NotNull KeyboardSettingsModel.Catalog catalog(@NotNull List<Menu> menus, @NotNull Os os) {
        Defaults defaults = defaults(menus, os);
        List<KeyboardSettingsModel.Action> actions = new ArrayList<>();
        for (Map.Entry<String, Located> entry : locate(menus).entrySet()) {
            String id = entry.getKey();
            String label = MenuActionHarvester.displayLabel(entry.getValue().item());
            if (label.isEmpty()) {
                label = MenuActionHarvester.displayLabel(new MenuItem(I18n.get(id)));
            }
            String category = entry.getValue().category();
            actions.add(FIXED_ACTION_IDS.contains(id)
                ? KeyboardSettingsModel.Action.fixed(id, label, category, defaults.fixed().get(id), null)
                : KeyboardSettingsModel.Action.rebindable(id, label, category, defaults.rebindable().get(id)));
        }
        String tabs = I18n.get(KeyboardSettingsModel.CATEGORY_TABS_KEY);
        actions.add(KeyboardSettingsModel.Action.fixed(FIXED_NEXT_TAB, I18n.get(KeyboardSettingsModel.FIXED_NEXT_TAB_KEY),
            tabs, KeyChord.parse("Ctrl+Tab"), null));
        actions.add(KeyboardSettingsModel.Action.fixed(FIXED_PREVIOUS_TAB,
            I18n.get(KeyboardSettingsModel.FIXED_PREVIOUS_TAB_KEY), tabs, KeyChord.parse("Ctrl+Shift+Tab"), null));
        KeyChord firstTab = KeyChord.parse("Shortcut+1");
        KeyChord lastTab = KeyChord.parse("Shortcut+9");
        actions.add(KeyboardSettingsModel.Action.fixed(FIXED_TAB_JUMP, I18n.get(KeyboardSettingsModel.FIXED_TAB_JUMP_KEY),
            tabs, firstTab, firstTab.displayLabel(os) + " \u2026 " + lastTab.displayLabel(os)));
        return new KeyboardSettingsModel.Catalog(os, actions, rules(os, defaults.fixed()));
    }

    /**
     * The default keymap of a menu bar: each action item's accelerator as its menu builder set it.
     * The first call records that accelerator on the item, so later calls, after overrides replaced
     * it, still return the default.
     */
    static @NotNull Defaults defaults(@NotNull List<Menu> menus, @NotNull Os os) {
        Map<String, KeyChord> rebindable = new LinkedHashMap<>();
        Map<String, KeyChord> fixed = new LinkedHashMap<>();
        for (Map.Entry<String, MenuItem> entry : actionItems(menus).entrySet()) {
            KeyChord chord = chordOf(defaultAccelerator(entry.getValue()), os.isMac());
            (FIXED_ACTION_IDS.contains(entry.getKey()) ? fixed : rebindable).put(entry.getKey(), chord);
        }
        return new Defaults(rebindable, fixed);
    }

    /** The accelerator {@code item} had before the keymap first touched it; recorded on the first call. */
    static @Nullable KeyCombination defaultAccelerator(@NotNull MenuItem item) {
        Object recorded = item.getProperties().get(DEFAULT_ACCELERATOR_PROPERTY);
        if (recorded == null) {
            KeyCombination accelerator = item.getAccelerator();
            item.getProperties().put(DEFAULT_ACCELERATOR_PROPERTY, accelerator != null ? accelerator : NO_DEFAULT);
            return accelerator;
        }
        return recorded instanceof KeyCombination accelerator ? accelerator : null;
    }

    /** The rules overrides are checked against on {@code os}, with korTTY's fixed shortcuts. */
    static @NotNull Rules rules(@NotNull Os os, @NotNull Map<String, KeyChord> fixedMenuChords) {
        Map<String, KeyChord> fixed = new LinkedHashMap<>(fixedMenuChords);
        return new Rules(os, chord -> fixedOwner(chord, os, fixed), REQUIRED_ACTION_IDS);
    }

    /**
     * The id of the fixed korTTY shortcut that takes {@code chord} on {@code os}, or {@code null}:
     * the router's own key rules (tab jump, Ctrl+Tab, the zoom keys, F12) judged on the key press
     * the chord makes, then the default chords of the fixed menu actions.
     */
    static @Nullable String fixedOwner(@NotNull KeyChord chord, @NotNull Os os,
                                       @NotNull Map<String, KeyChord> fixedMenuChords) {
        KeyPress press = pressOf(chord, os);
        if (TabKeyboardShortcuts.slotOf(press) != TabKeyboardShortcuts.NOT_A_JUMP) {
            return FIXED_TAB_JUMP;
        }
        if (SceneShortcutKeys.isNextTab(press)) {
            return FIXED_NEXT_TAB;
        }
        if (SceneShortcutKeys.isPreviousTab(press)) {
            return FIXED_PREVIOUS_TAB;
        }
        if (SceneShortcutKeys.isZoomIn(press)) {
            return "menu.view.zoomIn";
        }
        if (SceneShortcutKeys.isZoomOut(press)) {
            return "menu.view.zoomOut";
        }
        if (SceneShortcutKeys.isZoomReset(press)) {
            return "menu.view.resetZoom";
        }
        if (SceneShortcutKeys.isFullscreenToggle(press)) {
            return "menu.view.fullscreen";
        }
        Physical keys = chord.physical(os);
        for (Map.Entry<String, KeyChord> entry : fixedMenuChords.entrySet()) {
            if (entry.getValue() != null && entry.getValue().physical(os).equals(keys)) {
                return entry.getKey();
            }
        }
        return null;
    }

    /** The key press {@code chord} makes on {@code os}, with no text, for the router's key rules. */
    static @NotNull KeyPress pressOf(@NotNull KeyChord chord, @NotNull Os os) {
        Physical keys = chord.physical(os);
        return new KeyPress(KeyCode.valueOf(chord.javafxKeyCodeName()), "", "", keys.shift(), keys.ctrl(),
            keys.alt(), keys.meta(), os.isMac());
    }

    /**
     * Sets each action item of a menu bar to its chord in {@code resolution}; an item the resolution
     * does not know (a fixed action) gets its default back. Returns the items whose accelerator
     * changed, in menu order, for {@link #reinstallAccelerators}.
     */
    static @NotNull List<MenuItem> applyToMenus(@NotNull List<Menu> menus, @NotNull Resolution resolution) {
        List<MenuItem> changed = new ArrayList<>();
        for (Map.Entry<String, MenuItem> entry : actionItems(menus).entrySet()) {
            MenuItem item = entry.getValue();
            KeyCombination defaultAccelerator = defaultAccelerator(item);
            KeyCombination before = item.getAccelerator();
            if (!resolution.knows(entry.getKey())) {
                item.setAccelerator(defaultAccelerator);
            } else {
                KeyCombination accelerator = combinationOf(resolution.chord(entry.getKey()));
                item.setAccelerator(Objects.equals(accelerator, defaultAccelerator) ? defaultAccelerator : accelerator);
            }
            if (!Objects.equals(before, item.getAccelerator())) {
                changed.add(item);
            }
        }
        return changed;
    }

    /**
     * Puts the action of each of {@code changedItems} back under its accelerator in
     * {@code sceneAccelerators}, the accelerators of the scene the menu bar is in, after
     * {@link #applyToMenus} changed them in a window that is already shown.
     *
     * <p>JavaFX keeps a menu item's scene accelerator in step with its {@code accelerator} property
     * by moving the action from the old chord to the new one. That loses actions: an item that had
     * no chord gets the new one without an action (so does an item whose removed shortcut is given
     * back), and when two items trade chords, the first move overwrites the other item's entry, so
     * one chord runs the wrong item and the other none. Each changed item's chord is therefore set
     * again to run that item the way its accelerator does ({@link MenuItemActivation}). JavaFX has
     * already removed the old chords, and a chord in effect belongs to one item only, so no other
     * entry is touched. FX thread only.
     */
    static void reinstallAccelerators(@NotNull Map<KeyCombination, Runnable> sceneAccelerators,
                                      @NotNull List<MenuItem> changedItems) {
        for (MenuItem item : changedItems) {
            KeyCombination accelerator = item.getAccelerator();
            if (accelerator != null) {
                sceneAccelerators.put(accelerator, () -> MenuItemActivation.activate(item));
            }
        }
    }

    /**
     * The action items of a menu bar that run on a chord the user chose, by that chord: every item
     * whose accelerator, after {@link #applyToMenus}, is not the one its menu builder set, except
     * {@code routedIds}, the actions the scene shortcut router has entries of its own for
     * ({@link RoutedChord}). The router runs them while the keyboard is in a terminal, which would
     * otherwise encode the key for the shell before the menu accelerator sees it.
     */
    static @NotNull Map<KeyCombination, MenuItem> reboundItems(@NotNull List<Menu> menus,
                                                               @NotNull Set<String> routedIds) {
        Map<KeyCombination, MenuItem> rebound = new LinkedHashMap<>();
        for (Map.Entry<String, MenuItem> entry : actionItems(menus).entrySet()) {
            MenuItem item = entry.getValue();
            KeyCombination accelerator = item.getAccelerator();
            if (accelerator != null && !routedIds.contains(entry.getKey())
                && !accelerator.equals(defaultAccelerator(item))) {
                rebound.put(accelerator, item);
            }
        }
        return Map.copyOf(rebound);
    }

    /** The item of {@code rebound} whose chord {@code press} is, or {@code null}. */
    static @Nullable MenuItem reboundItemFor(@NotNull Map<KeyCombination, MenuItem> rebound, @NotNull KeyPress press) {
        for (Map.Entry<KeyCombination, MenuItem> entry : rebound.entrySet()) {
            if (press.matches(entry.getKey())) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** One line per override that is not in effect, for the log: the action, the chord and why. */
    static @NotNull List<String> describeRejections(@NotNull Resolution resolution) {
        Set<String> lines = new LinkedHashSet<>();
        for (KeymapOverrides.Rejection rejection : resolution.rejected()) {
            lines.add(rejection.actionId() + "=" + (rejection.chord() != null ? rejection.chord().canonical()
                : KeymapOverrides.NONE) + " (" + rejection.problem()
                + (rejection.conflictWith() != null ? " with " + rejection.conflictWith() : "") + ")");
        }
        return List.copyOf(lines);
    }

    /** An action item and its menu path, as the command palette shows it. */
    private record Located(MenuItem item, String category) {
    }

    /** The action items of a menu bar by id, see {@link #actionItems}, each with its menu path. */
    private static Map<String, Located> locate(List<Menu> menus) {
        Map<String, Located> items = new LinkedHashMap<>();
        for (Menu menu : menus) {
            collect(menu, List.of(), items);
        }
        return items;
    }

    private static void collect(Menu menu, List<String> above, Map<String, Located> items) {
        if (ActionIds.isExcluded(menu)) {
            return;
        }
        List<String> path = new ArrayList<>(above);
        String menuLabel = MenuActionHarvester.displayLabel(menu);
        if (!menuLabel.isEmpty()) {
            path.add(menuLabel);
        }
        for (MenuItem item : menu.getItems()) {
            if (item instanceof Menu submenu) {
                collect(submenu, path, items);
            } else if (item != null && !(item instanceof CustomMenuItem)) {
                String id = ActionIds.idOf(item);
                if (id != null) {
                    items.putIfAbsent(id, new Located(item,
                        String.join(MenuActionHarvester.CATEGORY_SEPARATOR, path)));
                }
            }
        }
    }

    private static boolean anyIsAny(KeyCodeCombination keyCode) {
        return keyCode.getShortcut() == ModifierValue.ANY || keyCode.getControl() == ModifierValue.ANY
            || keyCode.getShift() == ModifierValue.ANY || keyCode.getAlt() == ModifierValue.ANY
            || keyCode.getMeta() == ModifierValue.ANY;
    }
}
