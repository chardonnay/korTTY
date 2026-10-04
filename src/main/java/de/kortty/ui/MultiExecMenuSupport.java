package de.kortty.ui;

import de.kortty.ui.actions.ActionIds;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds <i>View → Multi-exec</i>, which MainWindow puts into both menu bars (the in-window bar and
 * the macOS system bar): <b>Include This Pane</b> lets the focused pane of the active terminal tab
 * take part in {@link MultiExecCoordinator multi-exec} or no longer, <b>Include All Panes of This
 * Tab</b> does so for every pane of that tab, <b>Include All Terminals of This Window</b> lets every
 * pane of every terminal tab of the window take part, and <b>Stop Multi-exec</b> takes every pane of
 * every window out.
 *
 * <p>The items are synced with {@link #sync}: the include items need a terminal tab, the window item
 * a terminal tab in the window, and Stop a pane that takes part; the check marks show whether the
 * focused pane, and every pane of the tab, take part. JavaFX flips a {@link CheckMenuItem} before
 * its action runs, so neither action reads {@code isSelected()}: the command decides from the
 * members, and the items are re-synced afterwards. The labels never change and the items are built
 * once per menu bar, so the action registry can harvest them; they are tagged with their i18n keys.
 *
 * <p>The include items decide where typed keys go, so the menu bar of a closed macOS window does
 * nothing with them ({@link ClosedWindowMenuRouter#ownWindowOnly}); Stop needs no window and acts
 * from any menu bar ({@link ClosedWindowMenuRouter#noWindowNeeded}).
 *
 * <p>Menu items are not nodes, so this runs in a unit test without the JavaFX toolkit, except
 * {@link SeparatorMenuItem}, which holds a {@code Separator} control; the package-private builder
 * therefore takes the separator factory.
 */
final class MultiExecMenuSupport {

    static final String MENU_KEY = "menu.view.multiExec";
    static final String INCLUDE_PANE_KEY = "menu.view.multiExec.includePane";
    static final String INCLUDE_TAB_KEY = "menu.view.multiExec.includeTab";
    static final String INCLUDE_WINDOW_KEY = "menu.view.multiExec.includeWindow";
    static final String STOP_KEY = "menu.view.multiExec.stop";

    /** Every label key of the submenu, in menu order. */
    static final List<String> KEYS = List.of(MENU_KEY, INCLUDE_PANE_KEY, INCLUDE_TAB_KEY, INCLUDE_WINDOW_KEY,
        STOP_KEY);

    private static final Supplier<MenuItem> SEPARATORS = SeparatorMenuItem::new;

    private MultiExecMenuSupport() {
    }

    /** What the commands act on. */
    interface Commands {

        /** Lets the focused pane of the active terminal tab take part, or no longer, deciding from the members. */
        void togglePane();

        /** Lets every pane of the active terminal tab take part when not all do, else none of them. */
        void toggleTab();

        /** Lets every pane of every terminal tab of the window take part. */
        void includeWindow();

        /** Takes every pane of every window out. */
        void stop();
    }

    /**
     * What the menu shows.
     *
     * @param terminal     whether a terminal tab is active
     * @param paneIncluded whether its focused pane takes part
     * @param tabIncluded  whether every one of its panes takes part
     * @param windowTerminals whether the window has a terminal tab
     * @param members      how many panes of all windows take part
     */
    record State(boolean terminal, boolean paneIncluded, boolean tabIncluded, boolean windowTerminals,
                 int members) {

        /** No terminal tab in the window and no member anywhere: every item is disabled. */
        static final State NONE = new State(false, false, false, false, 0);

        boolean canIncludePane() {
            return terminal;
        }

        boolean canIncludeTab() {
            return terminal;
        }

        boolean canIncludeWindow() {
            return windowTerminals;
        }

        boolean canStop() {
            return members > 0;
        }
    }

    /** One menu bar's <i>View → Multi-exec</i> submenu and the items {@link #sync} updates. */
    record MultiExecMenu(@NotNull Menu menu, @NotNull CheckMenuItem includePane, @NotNull CheckMenuItem includeTab,
                         @NotNull MenuItem includeWindow, @NotNull MenuItem stop) {
    }

    /** The submenu of one menu bar, its items disabled until the first {@link #sync}. */
    static @NotNull MultiExecMenu create(@NotNull Commands commands) {
        return create(commands, SEPARATORS);
    }

    static @NotNull MultiExecMenu create(@NotNull Commands commands, @NotNull Supplier<? extends MenuItem> separators) {
        Objects.requireNonNull(commands, "commands");
        Menu menu = new Menu(I18n.get(MENU_KEY));
        CheckMenuItem includePane = ActionIds.tag(new CheckMenuItem(I18n.get(INCLUDE_PANE_KEY)), INCLUDE_PANE_KEY);
        // Never read isSelected() here: JavaFX already flipped it, the members decide.
        includePane.setOnAction(event -> commands.togglePane());
        ClosedWindowMenuRouter.ownWindowOnly(includePane);
        CheckMenuItem includeTab = ActionIds.tag(new CheckMenuItem(I18n.get(INCLUDE_TAB_KEY)), INCLUDE_TAB_KEY);
        includeTab.setOnAction(event -> commands.toggleTab());
        ClosedWindowMenuRouter.ownWindowOnly(includeTab);
        MenuItem includeWindow = ActionIds.tag(new MenuItem(I18n.get(INCLUDE_WINDOW_KEY)), INCLUDE_WINDOW_KEY);
        includeWindow.setOnAction(event -> commands.includeWindow());
        ClosedWindowMenuRouter.ownWindowOnly(includeWindow);
        MenuItem stop = ActionIds.tag(new MenuItem(I18n.get(STOP_KEY)), STOP_KEY);
        stop.setOnAction(event -> commands.stop());
        // Stops it in every window: from the menu bar of a closed window too, without opening one.
        ClosedWindowMenuRouter.noWindowNeeded(stop);
        menu.getItems().addAll(includePane, includeTab, includeWindow, separators.get(), stop);
        MultiExecMenu multiExecMenu = new MultiExecMenu(menu, includePane, includeTab, includeWindow, stop);
        sync(multiExecMenu, State.NONE);
        return multiExecMenu;
    }

    /** Shows {@code state} on the items: what can act now, and the check marks of the pane and the tab. */
    static void sync(@Nullable MultiExecMenu multiExecMenu, @NotNull State state) {
        if (multiExecMenu == null) {
            return;
        }
        multiExecMenu.includePane().setDisable(!state.canIncludePane());
        multiExecMenu.includePane().setSelected(state.terminal() && state.paneIncluded());
        multiExecMenu.includeTab().setDisable(!state.canIncludeTab());
        multiExecMenu.includeTab().setSelected(state.terminal() && state.tabIncluded());
        multiExecMenu.includeWindow().setDisable(!state.canIncludeWindow());
        multiExecMenu.stop().setDisable(!state.canStop());
    }
}
