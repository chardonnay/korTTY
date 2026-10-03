package de.kortty.ui.actions;

import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Turns the menus of a menu bar into {@link AppAction}s, one per leaf item.
 *
 * <p>It takes {@code menuBar.getMenus()} rather than the {@code MenuBar}: a {@code MenuBar} is a
 * {@code Control}, which cannot be created without a running JavaFX toolkit, so a list of menus
 * keeps this unit-testable.
 *
 * <p>Skipped: {@link CustomMenuItem}s (which includes separators and embedded sliders), invisible
 * items, items without text, and everything at or below an item marked with
 * {@link ActionIds#exclude}. Submenus are walked but are not actions themselves.
 *
 * <p>For each item: the label is its text without the mnemonic marker and a trailing ellipsis; the
 * category is the path of menu labels above it, joined with {@link #CATEGORY_SEPARATOR}; the id is
 * the {@link ActionIds#tag tagged} id, or else {@code menu:} plus a slug of that path, which
 * follows the UI language and is therefore not stable. An item is enabled while neither it nor any
 * menu above it is disabled, read when asked. Running an action goes through
 * {@link MenuItemActivation}. Harvest, and read or run the actions, on the FX thread only.
 */
public final class MenuActionHarvester {

    /** Joins the menu labels of an action's category path. */
    public static final String CATEGORY_SEPARATOR = " \u203A ";
    /** Prefix of the language-dependent id of an untagged item. */
    public static final String UNTAGGED_ID_PREFIX = "menu:";

    private MenuActionHarvester() {
    }

    /** The actions of these menus, in menu order. */
    public static List<AppAction> harvest(List<? extends Menu> menus) {
        if (menus == null || menus.isEmpty()) {
            return List.of();
        }
        List<AppAction> actions = new ArrayList<>();
        Map<String, Integer> seenIds = new HashMap<>();
        Path root = new Path(List.of(), List.of(), false);
        for (Menu menu : menus) {
            walk(menu, root, actions, seenIds);
        }
        return List.copyOf(actions);
    }

    /** The menus above an item, their non-blank labels, and whether one of them is policy-locked. */
    private record Path(List<Menu> menus, List<String> labels, boolean policyLocked) {

        Path into(Menu menu) {
            List<Menu> childMenus = new ArrayList<>(menus);
            childMenus.add(menu);
            List<String> childLabels = new ArrayList<>(labels);
            String label = displayLabel(menu);
            if (!label.isEmpty()) {
                childLabels.add(label);
            }
            return new Path(List.copyOf(childMenus), List.copyOf(childLabels),
                    policyLocked || ActionIds.isPolicyLocked(menu));
        }
    }

    private static void walk(Menu menu, Path above, List<AppAction> actions, Map<String, Integer> seenIds) {
        if (menu == null || !menu.isVisible() || ActionIds.isExcluded(menu)) {
            return;
        }
        Path path = above.into(menu);
        for (MenuItem item : menu.getItems()) {
            if (item instanceof Menu submenu) {
                walk(submenu, path, actions, seenIds);
            } else {
                AppAction action = toAction(item, path, seenIds);
                if (action != null) {
                    actions.add(action);
                }
            }
        }
    }

    private static AppAction toAction(MenuItem item, Path path, Map<String, Integer> seenIds) {
        if (item == null || item instanceof CustomMenuItem || !item.isVisible() || ActionIds.isExcluded(item)) {
            return null;
        }
        String label = displayLabel(item);
        if (label.isEmpty()) {
            return null;
        }
        String category = String.join(CATEGORY_SEPARATOR, path.labels());

        String taggedId = ActionIds.idOf(item);
        String baseId = taggedId != null ? taggedId : untaggedId(path.labels(), label);
        // Two items with the same id (the same tag, or the same label under the same path) would
        // shadow each other in the registry; later ones get a suffix and lose the stable flag.
        int occurrence = seenIds.merge(baseId, 1, Integer::sum);
        String id = occurrence == 1 ? baseId : baseId + "#" + occurrence;

        BooleanSupplier checked = null;
        if (item instanceof CheckMenuItem check) {
            checked = check::isSelected;
        } else if (item instanceof RadioMenuItem radio) {
            checked = radio::isSelected;
        }
        List<String> keywords = category.isEmpty() ? List.of() : List.of(category);
        List<Menu> menus = path.menus();
        return new AppAction(
                id,
                label,
                category,
                item.getAccelerator(),
                keywords,
                () -> isEnabled(item, menus),
                checked,
                () -> MenuItemActivation.activate(item),
                taggedId != null && occurrence == 1,
                path.policyLocked() || ActionIds.isPolicyLocked(item));
    }

    private static boolean isEnabled(MenuItem item, List<Menu> menus) {
        if (item.isDisable()) {
            return false;
        }
        for (Menu menu : menus) {
            if (menu.isDisable()) {
                return false;
            }
        }
        return true;
    }

    /**
     * The text a menu shows for {@code item}: without the mnemonic marker (when the item parses
     * mnemonics, see {@link #stripMnemonic}), without a trailing "..." or "\u2026", trimmed.
     */
    static String displayLabel(MenuItem item) {
        String text = item.getText();
        if (text == null) {
            return "";
        }
        String label = item.isMnemonicParsing() ? stripMnemonic(text) : text;
        label = label.strip();
        if (label.endsWith("...")) {
            label = label.substring(0, label.length() - 3);
        } else if (label.endsWith("\u2026")) {
            label = label.substring(0, label.length() - 1);
        }
        return label.strip();
    }

    /**
     * Removes the mnemonic marker the way JavaFX's {@code MnemonicInfo} does (checked against the
     * 21.0.12 bytecode): before the first mnemonic, "__" stands for one underscore; the first "_x",
     * where x is neither an underscore nor whitespace, loses its underscore, and an extended "_(x)"
     * is dropped whole; everything after that mnemonic is kept as it is.
     */
    static String stripMnemonic(String text) {
        int length = text.length();
        StringBuilder out = new StringBuilder(length);
        int i = 0;
        while (i < length) {
            char c = text.charAt(i);
            char next = i + 1 < length ? text.charAt(i + 1) : 0;
            if (c == '_' && next == '_') {
                out.append('_');
                i += 2;
            } else if (c == '_' && next == '(' && i + 3 < length
                    && !Character.isWhitespace(text.charAt(i + 2)) && text.charAt(i + 3) == ')') {
                i += 4;
                break;
            } else if (c == '_' && i + 1 < length && !Character.isWhitespace(next)) {
                i += 1;
                break;
            } else {
                out.append(c);
                i++;
            }
        }
        return out.append(text, i, length).toString();
    }

    private static String untaggedId(List<String> categoryLabels, String label) {
        StringBuilder id = new StringBuilder(UNTAGGED_ID_PREFIX);
        for (String segment : categoryLabels) {
            id.append(slug(segment)).append('/');
        }
        return id.append(slug(label)).toString();
    }

    /** Lower case, accents removed, every run of other characters than letters and digits as "-". */
    static String slug(String text) {
        String folded = Normalizer.normalize(text, Normalizer.Form.NFKD)
                .replaceAll("\\p{M}+", "")
                .toLowerCase(Locale.ROOT);
        String slug = folded.replaceAll("[^\\p{L}\\p{N}]+", "-").replaceAll("^-+|-+$", "");
        return slug.isEmpty() ? "_" : slug;
    }
}
