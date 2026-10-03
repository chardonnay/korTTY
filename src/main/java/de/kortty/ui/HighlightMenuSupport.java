package de.kortty.ui;

import de.kortty.core.highlight.HighlightBuiltinSets;
import de.kortty.core.highlight.HighlightToggle;
import de.kortty.core.highlight.TerminalHighlightService;
import de.kortty.ui.actions.ActionIds;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.RadioMenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.ToggleGroup;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Builds the keyword-highlighting submenus: <i>View → Highlighting</i> in the main window and
 * <i>Highlighting</i> in a terminal pane's context menu. Both hold a <b>Highlighting On</b> check
 * item and a radio list — <b>None</b>, the built-in sets, then the user's sets — and both act on one
 * pane only, for the running session: nothing is saved to the connection or the settings.
 *
 * <p>Below the list, <b>Manage Rule Sets…</b> opens the rule-set editor. The radio list is rebuilt every
 * time the menu opens, because the pane, its set and the list of sets change underneath it; its items
 * are therefore {@link ActionIds#exclude excluded} from the action harvest, where a copy would go
 * stale, and marked so a rebuild replaces exactly them and keeps the items around them. The check item
 * and the Manage item are created once per menu bar; the check item is
 * driven by the pane's state: JavaFX flips a {@link CheckMenuItem} before its action runs, so its
 * action never reads {@code isSelected()} and the item is re-synced afterwards (see
 * {@link HighlightToggle}).
 *
 * <p>Menu items are not nodes, so everything here runs in a unit test without the JavaFX toolkit —
 * except {@link SeparatorMenuItem}, which holds a {@code Separator} control. The package-private
 * builders therefore take the separator factory, and a test passes a plain {@link MenuItem}.
 */
public final class HighlightMenuSupport {

    /** The submenu's label, in the View menu and the pane context menu. */
    public static final String MENU_KEY = "menu.view.highlighting";
    /** The check item that switches the pane's highlighting on or off. */
    public static final String TOGGLE_KEY = "menu.view.highlighting.toggle";
    /** The radio item for "no set". */
    public static final String NONE_KEY = "menu.view.highlighting.none";
    /** Status-bar text after the toggle switched a pane on; {0} is the set's name. */
    public static final String STATUS_ON_KEY = "menu.view.highlighting.statusOn";
    /** Status-bar text after the toggle switched a pane off. */
    public static final String STATUS_OFF_KEY = "menu.view.highlighting.statusOff";
    /** Status-bar text when the toggle cannot act because the master switch is off. */
    public static final String DISABLED_KEY = "menu.view.highlighting.disabled";
    /** The item below the list that opens the rule-set editor. */
    public static final String MANAGE_KEY = "menu.view.highlighting.manage";

    /** Marks the items of the rebuilt radio list, so a rebuild replaces exactly them. */
    static final String LIST_ITEM_PROPERTY = "kortty.highlight.listItem";

    /** The check item and the separator below it, which a rebuild keeps. */
    static final int LEADING_ITEMS = 2;

    /** Separators for the real menus; creating one needs the JavaFX toolkit. */
    private static final Supplier<MenuItem> SEPARATORS = SeparatorMenuItem::new;

    private HighlightMenuSupport() {
    }

    /**
     * One radio entry.
     *
     * @param setId a set id, or {@value TerminalHighlightService#NONE_ID} for "no set"
     * @param separatorBefore whether a separator opens a new group above this entry
     */
    record Entry(@NotNull String setId, @NotNull String label, boolean separatorBefore) {
    }

    /**
     * What the menus show for one pane.
     *
     * @param enabled the master switch
     * @param paneAvailable whether there is a pane with a highlighter to act on
     * @param shownSetId the set the pane shows now, {@code null} for none
     */
    record State(boolean enabled, boolean paneAvailable, @Nullable String shownSetId, @NotNull List<Entry> entries) {

        State {
            entries = List.copyOf(entries);
        }

        /** Whether the items can act: a pane is there and the master switch is on. */
        boolean actionable() {
            return enabled && paneAvailable;
        }
    }

    /**
     * The state for a pane. A {@code null} service (it failed to start) gives a disabled menu that
     * still lists the built-in sets.
     */
    static State state(@Nullable TerminalHighlightService service, boolean paneAvailable, @Nullable String shownSetId) {
        if (service == null || service.isClosed()) {
            return new State(false, false, null, entries(HighlightBuiltinSets.IDS, id -> null));
        }
        return new State(service.isEnabled(), paneAvailable, shownSetId, entries(service.setIds(), service::userSetName));
    }

    /**
     * The radio entries: None, then the built-in sets, then the user's sets, each group after a
     * separator. {@code setIds} lists the built-ins first, as {@link TerminalHighlightService#setIds()} does.
     */
    static List<Entry> entries(List<String> setIds, Function<String, String> userSetName) {
        List<Entry> entries = new ArrayList<>();
        entries.add(new Entry(TerminalHighlightService.NONE_ID, I18n.get(NONE_KEY), false));
        boolean firstBuiltin = true;
        boolean firstUser = true;
        for (String id : setIds) {
            if (id == null || id.isBlank() || TerminalHighlightService.NONE_ID.equals(id)) {
                continue;
            }
            boolean builtin = HighlightBuiltinSets.isBuiltin(id);
            boolean separator = builtin ? firstBuiltin : firstUser;
            if (builtin) {
                firstBuiltin = false;
            } else {
                firstUser = false;
            }
            entries.add(new Entry(id, label(id, userSetName), separator));
        }
        return entries;
    }

    /** A set's display name: the built-in's translated name, the user set's stored name, else its id. */
    static String label(String setId, Function<String, String> userSetName) {
        if (HighlightBuiltinSets.isBuiltin(setId)) {
            return I18n.get(HighlightBuiltinSets.nameKey(setId));
        }
        String name = userSetName != null ? userSetName.apply(setId) : null;
        return name != null && !name.isBlank() ? name : setId;
    }

    /** The status-bar text after a toggle. */
    static String statusMessage(HighlightToggle.Choice choice, Function<String, String> userSetName) {
        return choice.on()
            ? I18n.get(STATUS_ON_KEY, label(choice.shownSetId(), userSetName))
            : I18n.get(STATUS_OFF_KEY);
    }

    /**
     * A new <b>Highlighting On</b> item that runs {@code onToggle}. The caller sets the accelerator
     * (the View menu only; a context menu shows none) and re-syncs the item afterwards.
     */
    static CheckMenuItem createToggleItem(@NotNull Runnable onToggle) {
        Objects.requireNonNull(onToggle, "onToggle");
        CheckMenuItem item = new CheckMenuItem(I18n.get(TOGGLE_KEY));
        // Never read item.isSelected() here: JavaFX already flipped it, the pane's state decides.
        item.setOnAction(event -> onToggle.run());
        return item;
    }

    /** Shows the pane's real state on the check item. */
    static void syncToggle(@Nullable CheckMenuItem toggle, @NotNull State state) {
        if (toggle == null) {
            return;
        }
        toggle.setSelected(state.actionable() && state.shownSetId() != null);
        toggle.setDisable(!state.actionable());
    }

    /**
     * Brings an opened View → Highlighting menu up to date: syncs its check item and replaces the
     * radio list below the first {@link #LEADING_ITEMS} items.
     */
    static void refresh(@NotNull Menu menu, @Nullable CheckMenuItem toggle, @NotNull State state,
                        @NotNull Consumer<String> onChoose) {
        refresh(menu, toggle, state, onChoose, SEPARATORS);
    }

    static void refresh(@NotNull Menu menu, @Nullable CheckMenuItem toggle, @NotNull State state,
                        @NotNull Consumer<String> onChoose, @NotNull Supplier<? extends MenuItem> separators) {
        syncToggle(toggle, state);
        rebuildSetItems(menu, LEADING_ITEMS, state, onChoose, separators);
    }

    /**
     * A new <b>Manage Rule Sets…</b> item that runs {@code onManage}. It stays enabled while the master
     * switch is off or no pane is focused: rule sets can be prepared at any time.
     */
    static MenuItem createManageItem(@NotNull Runnable onManage) {
        Objects.requireNonNull(onManage, "onManage");
        MenuItem item = new MenuItem(I18n.get(MANAGE_KEY));
        item.setOnAction(event -> onManage.run());
        return item;
    }

    /**
     * The View menu's submenu around an already created check item; the radio list is filled now and
     * again by the caller's {@code onShowing}.
     */
    static Menu createViewMenu(@NotNull CheckMenuItem toggle, @NotNull State state, @NotNull Consumer<String> onChoose) {
        return createViewMenu(toggle, null, state, onChoose, SEPARATORS);
    }

    /** As above, with {@code manage} (when not {@code null}) below the list, after a separator. */
    static Menu createViewMenu(@NotNull CheckMenuItem toggle, @Nullable MenuItem manage, @NotNull State state,
                               @NotNull Consumer<String> onChoose) {
        return createViewMenu(toggle, manage, state, onChoose, SEPARATORS);
    }

    static Menu createViewMenu(@NotNull CheckMenuItem toggle, @NotNull State state, @NotNull Consumer<String> onChoose,
                               @NotNull Supplier<? extends MenuItem> separators) {
        return createViewMenu(toggle, null, state, onChoose, separators);
    }

    static Menu createViewMenu(@NotNull CheckMenuItem toggle, @Nullable MenuItem manage, @NotNull State state,
                               @NotNull Consumer<String> onChoose, @NotNull Supplier<? extends MenuItem> separators) {
        Menu menu = new Menu(I18n.get(MENU_KEY));
        menu.getItems().addAll(toggle, separators.get());
        if (manage != null) {
            menu.getItems().addAll(separators.get(), manage);
        }
        refresh(menu, toggle, state, onChoose, separators);
        return menu;
    }

    /** The pane context menu's submenu, built for one opening: the check item without accelerator, then the list. */
    static Menu createPaneMenu(@NotNull State state, @NotNull Runnable onToggle, @NotNull Consumer<String> onChoose) {
        return createPaneMenu(state, onToggle, onChoose, null, SEPARATORS);
    }

    /** As above, with <b>Manage Rule Sets…</b> below the list when {@code onManage} is not {@code null}. */
    static Menu createPaneMenu(@NotNull State state, @NotNull Runnable onToggle, @NotNull Consumer<String> onChoose,
                               @Nullable Runnable onManage) {
        return createPaneMenu(state, onToggle, onChoose, onManage, SEPARATORS);
    }

    static Menu createPaneMenu(@NotNull State state, @NotNull Runnable onToggle, @NotNull Consumer<String> onChoose,
                               @NotNull Supplier<? extends MenuItem> separators) {
        return createPaneMenu(state, onToggle, onChoose, null, separators);
    }

    static Menu createPaneMenu(@NotNull State state, @NotNull Runnable onToggle, @NotNull Consumer<String> onChoose,
                               @Nullable Runnable onManage, @NotNull Supplier<? extends MenuItem> separators) {
        Menu menu = new Menu(I18n.get(MENU_KEY));
        CheckMenuItem toggle = createToggleItem(onToggle);
        menu.getItems().addAll(toggle, separators.get());
        if (onManage != null) {
            menu.getItems().addAll(separators.get(), createManageItem(onManage));
        }
        refresh(menu, toggle, state, onChoose, separators);
        return menu;
    }

    /**
     * Replaces the radio list of {@code menu} — the items an earlier rebuild added, or nothing the first
     * time — with a fresh one, right after the first {@code keep} items. Items around the list, such as
     * the Manage item below it, stay where they are.
     */
    static void rebuildSetItems(@NotNull Menu menu, int keep, @NotNull State state, @NotNull Consumer<String> onChoose,
                                @NotNull Supplier<? extends MenuItem> separators) {
        List<MenuItem> items = menu.getItems();
        items.removeIf(HighlightMenuSupport::isListItem);
        List<MenuItem> list = new ArrayList<>();
        ToggleGroup group = new ToggleGroup();
        String shown = state.shownSetId() != null ? state.shownSetId() : TerminalHighlightService.NONE_ID;
        boolean disable = !state.actionable();
        for (Entry entry : state.entries()) {
            if (entry.separatorBefore()) {
                list.add(listItem(separators.get()));
            }
            RadioMenuItem item = new RadioMenuItem(entry.label());
            item.setToggleGroup(group);
            item.setSelected(state.actionable() && entry.setId().equals(shown));
            item.setDisable(disable);
            String setId = entry.setId();
            item.setOnAction(event -> onChoose.accept(setId));
            list.add(listItem(item));
        }
        items.addAll(Math.min(keep, items.size()), list);
    }

    /** Whether {@code item} belongs to the rebuilt radio list. */
    static boolean isListItem(@Nullable MenuItem item) {
        return item != null && Boolean.TRUE.equals(item.getProperties().get(LIST_ITEM_PROPERTY));
    }

    private static <T extends MenuItem> T listItem(T item) {
        item.getProperties().put(LIST_ITEM_PROPERTY, Boolean.TRUE);
        return ActionIds.exclude(item);
    }
}
