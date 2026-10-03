package de.kortty.ui;

import de.kortty.ui.PaneNavigator.PaneDirection;
import de.kortty.ui.actions.ActionIds;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds <i>View → Panes</i>, which MainWindow puts into both menu bars (the in-window bar and the
 * macOS system bar): <b>Focus Pane Left/Right/Up/Down</b>, <b>Next Pane</b> and <b>Previous Pane</b>
 * move the keyboard focus between the split panes of the active terminal tab, and <b>Broadcast to
 * All Panes of This Tab</b> switches that tab's broadcast mode, the same mode as <i>Extras →
 * Broadcast Mode</i> in the terminal context menu.
 *
 * <p>The items are synced from the active tab with {@link #sync}: the focus items need two or more
 * panes, and the broadcast item shows the tab's real mode. JavaFX flips a {@link CheckMenuItem}
 * before its action runs (an accelerator and {@code MenuItemActivation} do too), so the broadcast
 * action never reads {@code isSelected()}: the command decides from the tab's mode and the item is
 * re-synced afterwards. The broadcast item acts on its own window only
 * ({@link ClosedWindowMenuRouter#ownWindowOnly}): it decides where typed keys go, so the menu bar of
 * a closed macOS window must not switch it in another window. The focus items are routed like
 * <i>Edit → Find</i>.
 *
 * <p>Menu items are not nodes, so this runs in a unit test without the JavaFX toolkit, except
 * {@link SeparatorMenuItem}, which holds a {@code Separator} control; the package-private builder
 * therefore takes the separator factory.
 */
final class PaneMenuSupport {

    static final String MENU_KEY = "menu.view.panes";
    static final String FOCUS_LEFT_KEY = "menu.view.panes.focusLeft";
    static final String FOCUS_RIGHT_KEY = "menu.view.panes.focusRight";
    static final String FOCUS_UP_KEY = "menu.view.panes.focusUp";
    static final String FOCUS_DOWN_KEY = "menu.view.panes.focusDown";
    static final String NEXT_KEY = "menu.view.panes.next";
    static final String PREVIOUS_KEY = "menu.view.panes.previous";
    static final String BROADCAST_KEY = "menu.view.panes.broadcast";

    /** Every label key of the submenu, in menu order. */
    static final List<String> KEYS = List.of(MENU_KEY, FOCUS_LEFT_KEY, FOCUS_RIGHT_KEY, FOCUS_UP_KEY,
        FOCUS_DOWN_KEY, NEXT_KEY, PREVIOUS_KEY, BROADCAST_KEY);

    private static final Supplier<MenuItem> SEPARATORS = SeparatorMenuItem::new;

    private PaneMenuSupport() {
    }

    /** What the commands act on: the panes of the active terminal tab. */
    interface Commands {

        /** Moves the focus to the pane on that side of the focused one. */
        void focus(@NotNull PaneDirection direction);

        /** Moves the focus to the next ({@code true}) or the previous pane, wrapping around. */
        void cycle(boolean forward);

        /** Switches the tab's broadcast mode, deciding from the tab's mode, never from the item. */
        void toggleBroadcast();
    }

    /**
     * The active tab as the menu shows it.
     *
     * @param terminal  whether a terminal tab is active
     * @param paneCount its number of panes
     * @param broadcast whether its broadcast mode is on
     */
    record State(boolean terminal, int paneCount, boolean broadcast) {

        /** No terminal tab is active: every item is disabled. */
        static final State NO_TERMINAL = new State(false, 0, false);

        /** Moving the focus needs a second pane. */
        boolean canMoveFocus() {
            return terminal && paneCount >= 2;
        }

        /** Broadcast needs a second pane; once on, it can always be switched off again. */
        boolean canToggleBroadcast() {
            return terminal && (paneCount >= 2 || broadcast);
        }
    }

    /** One menu bar's <i>View → Panes</i> submenu and the items {@link #sync} updates. */
    record PaneMenu(@NotNull Menu menu, @NotNull Map<PaneDirection, MenuItem> focusItems,
                    @NotNull MenuItem next, @NotNull MenuItem previous, @NotNull CheckMenuItem broadcast) {

        PaneMenu {
            focusItems = Map.copyOf(focusItems);
        }

        /** The <b>Focus Pane …</b> item of {@code direction}, where MainWindow sets its accelerator. */
        @NotNull MenuItem focusItem(@NotNull PaneDirection direction) {
            return focusItems.get(direction);
        }
    }

    /** The submenu of one menu bar, its items disabled until the first {@link #sync}. */
    static @NotNull PaneMenu create(@NotNull Commands commands) {
        return create(commands, SEPARATORS);
    }

    static @NotNull PaneMenu create(@NotNull Commands commands, @NotNull Supplier<? extends MenuItem> separators) {
        Objects.requireNonNull(commands, "commands");
        Menu menu = new Menu(I18n.get(MENU_KEY));
        Map<PaneDirection, MenuItem> focusItems = new EnumMap<>(PaneDirection.class);
        for (PaneDirection direction : PaneDirection.values()) {
            String key = focusKey(direction);
            MenuItem item = ActionIds.tag(new MenuItem(I18n.get(key)), key);
            item.setOnAction(event -> commands.focus(direction));
            focusItems.put(direction, item);
            menu.getItems().add(item);
        }
        MenuItem next = ActionIds.tag(new MenuItem(I18n.get(NEXT_KEY)), NEXT_KEY);
        next.setOnAction(event -> commands.cycle(true));
        MenuItem previous = ActionIds.tag(new MenuItem(I18n.get(PREVIOUS_KEY)), PREVIOUS_KEY);
        previous.setOnAction(event -> commands.cycle(false));
        CheckMenuItem broadcast = ActionIds.tag(new CheckMenuItem(I18n.get(BROADCAST_KEY)), BROADCAST_KEY);
        // Never read broadcast.isSelected() here: JavaFX already flipped it, the tab's mode decides.
        broadcast.setOnAction(event -> commands.toggleBroadcast());
        ClosedWindowMenuRouter.ownWindowOnly(broadcast);
        menu.getItems().addAll(separators.get(), next, previous, separators.get(), broadcast);
        PaneMenu paneMenu = new PaneMenu(menu, focusItems, next, previous, broadcast);
        sync(paneMenu, State.NO_TERMINAL);
        return paneMenu;
    }

    /** The label key of the focus item for {@code direction}. */
    static @NotNull String focusKey(@NotNull PaneDirection direction) {
        return switch (direction) {
            case LEFT -> FOCUS_LEFT_KEY;
            case RIGHT -> FOCUS_RIGHT_KEY;
            case UP -> FOCUS_UP_KEY;
            case DOWN -> FOCUS_DOWN_KEY;
        };
    }

    /** Shows the active tab's state on the items: what can act now, and the tab's broadcast mode. */
    static void sync(@Nullable PaneMenu paneMenu, @NotNull State state) {
        if (paneMenu == null) {
            return;
        }
        boolean focusDisabled = !state.canMoveFocus();
        for (MenuItem item : paneMenu.focusItems().values()) {
            item.setDisable(focusDisabled);
        }
        paneMenu.next().setDisable(focusDisabled);
        paneMenu.previous().setDisable(focusDisabled);
        paneMenu.broadcast().setDisable(!state.canToggleBroadcast());
        paneMenu.broadcast().setSelected(state.terminal() && state.broadcast());
    }
}
