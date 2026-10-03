package de.kortty.ui;

import de.kortty.ui.actions.MenuItemActivation;
import javafx.event.ActionEvent;
import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.control.CheckMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Sends what a closed window's menu bar is asked to do to a window that is open. On macOS korTTY
 * keeps running after the last window closed, and the menu bar of a closed window stays the
 * application's menu bar: after the last window closed, and while no korTTY window has the focus,
 * macOS shows the menu bar of the window opened last, even when that one is closed. Its items still
 * call into that closed window, so New Tab, the Connection Manager or Open Project would act in a
 * window nobody can see.
 *
 * <p>{@link #install} wraps every item of a window's menus. While the window is open an item runs as
 * built. Once it closed, the item at the same place in another window's menus runs instead, in the
 * window {@link #targetWindow} picks: the frontmost open one, which comes to the front, or a new one
 * when none is open. Finding the item by its place needs no list of actions and covers items added
 * later; a menu that fills itself while it opens does so first, and an item labelled differently
 * is not run. What an item needs is marked where it is built ({@link #ownWindowOnly},
 * {@link #noWindowNeeded}); unmarked items need a window.
 *
 * <p>Toolkit-free: only menus and menu items, no Control. Use it on the FX thread.
 */
final class ClosedWindowMenuRouter {

    private static final Logger logger = LoggerFactory.getLogger(ClosedWindowMenuRouter.class);

    /** What a menu item needs once the window whose menu bar it is in has closed. */
    enum WindowNeed {
        /** The default: it acts in a window, the frontmost open one, else a new one. */
        ANY_WINDOW,
        /**
         * It acts on what is in its own window (closing tabs or the window, the selection for the
         * clipboard), so it does nothing once that window closed and never acts elsewhere.
         */
        OWN_WINDOW,
        /** It needs no window and runs as built (New Window, Quit, ...). */
        NO_WINDOW
    }

    /** What the router asks about the windows; {@code W} is the window type. */
    interface Windows<W> {

        boolean isOpen(W window);

        /** The frontmost open window, or {@code null} when none is open. */
        W frontmostOpen();

        /** Opens a new window and returns it, or {@code null} when that failed. */
        W openNew();

        /** Brings an open window to the front before one of its items runs. */
        void bringToFront(W window);
    }

    static final String NEED_PROPERTY = "kortty.menu.closedWindowNeed";
    private static final String ROUTED_PROPERTY = "kortty.menu.closedWindowRouted";

    private ClosedWindowMenuRouter() {
    }

    /** Marks {@code item} as acting on what is in its own window only; returns the item. */
    static <T extends MenuItem> T ownWindowOnly(T item) {
        item.getProperties().put(NEED_PROPERTY, WindowNeed.OWN_WINDOW);
        return item;
    }

    /** Marks {@code item} as needing no window; returns the item. */
    static <T extends MenuItem> T noWindowNeeded(T item) {
        item.getProperties().put(NEED_PROPERTY, WindowNeed.NO_WINDOW);
        return item;
    }

    static WindowNeed needOf(MenuItem item) {
        return item.getProperties().get(NEED_PROPERTY) instanceof WindowNeed need ? need : WindowNeed.ANY_WINDOW;
    }

    /**
     * The window an item of {@code owner}'s menu bar acts in: {@code owner} while it is open, and
     * always for an item that needs no window. Once {@code owner} closed, the frontmost open window
     * takes over, or a new window when none is open; an item that acts on its own window's content
     * then does nothing ({@code null}). The closed window itself is never used for an item that
     * needs a window, since what it did there would never be seen.
     *
     * @param frontmostOpen the frontmost open window, or {@code null} when none is open
     * @param newWindow     opens a new window and returns it, or {@code null} when that failed; only
     *                      called when no window is open and the item needs one
     * @return the window to act in, or {@code null} for none
     */
    static <W> W targetWindow(W owner, WindowNeed need, Predicate<W> isOpen, Supplier<W> frontmostOpen,
            Supplier<W> newWindow) {
        if (need == WindowNeed.NO_WINDOW || isOpen.test(owner)) {
            return owner;
        }
        if (need == WindowNeed.OWN_WINDOW) {
            return null;
        }
        W open = frontmostOpen.get();
        return open != null ? open : newWindow.get();
    }

    /**
     * The frontmost of the open windows: the focused one, else the one that had the focus last,
     * else the one opened last; {@code null} when none is open. A window's place on screen cannot be
     * asked, so the last focus stands for it.
     *
     * @param openWindows the open windows, oldest first
     * @param lastFocused the window that had the focus last, open or not, or {@code null}
     */
    static <W> W frontmost(List<W> openWindows, Predicate<W> isFocused, W lastFocused) {
        for (W window : openWindows) {
            if (isFocused.test(window)) {
                return window;
            }
        }
        if (lastFocused != null && openWindows.contains(lastFocused)) {
            return lastFocused;
        }
        return openWindows.isEmpty() ? null : openWindows.get(openWindows.size() - 1);
    }

    /**
     * Routes every item of {@code owner}'s menus, including the ones a menu adds while it opens.
     *
     * @param menusOf a window's menus; for {@code owner} the ones to route, for another window the
     *                ones of the same menu bar (the in-window bar, or the macOS system bar), which
     *                are built by the same code and so hold the same items at the same places
     */
    static <W> void install(W owner, Function<W, List<Menu>> menusOf, Windows<W> windows) {
        for (Menu menu : menusOf.apply(owner)) {
            route(menu, owner, menusOf, windows);
        }
    }

    private static <W> void route(MenuItem item, W owner, Function<W, List<Menu>> menusOf, Windows<W> windows) {
        if (item instanceof Menu menu) {
            if (markRouted(menu)) {
                // Menus such as the job status or Terminal Effect menu fill themselves while opening.
                EventHandler<Event> filling = menu.getOnShowing();
                menu.setOnShowing(event -> {
                    if (filling != null) {
                        filling.handle(event);
                    }
                    routeItems(menu, owner, menusOf, windows);
                });
            }
            routeItems(menu, owner, menusOf, windows);
            return;
        }
        EventHandler<ActionEvent> own = item.getOnAction();
        if (own == null || !markRouted(item)) {
            return;
        }
        item.setOnAction(event -> {
            W target = targetWindow(owner, needOf(item), windows::isOpen, windows::frontmostOpen,
                windows::openNew);
            if (target == owner) {
                own.handle(event);
            } else if (target != null) {
                runIn(target, item, owner, menusOf, windows);
            }
        });
    }

    private static <W> void routeItems(Menu menu, W owner, Function<W, List<Menu>> menusOf, Windows<W> windows) {
        for (MenuItem child : List.copyOf(menu.getItems())) {
            route(child, owner, menusOf, windows);
        }
    }

    /** Marks an item as routed; {@code false} when it already was. */
    private static boolean markRouted(MenuItem item) {
        return !Boolean.TRUE.equals(item.getProperties().put(ROUTED_PROPERTY, Boolean.TRUE));
    }

    /** Runs the item at {@code item}'s place in {@code target}'s menus, as a click there would. */
    private static <W> void runIn(W target, MenuItem item, W owner, Function<W, List<Menu>> menusOf,
            Windows<W> windows) {
        MenuItem counterpart = counterpart(item, menusOf.apply(owner), menusOf.apply(target));
        if (counterpart == null) {
            logger.debug("No counterpart in an open window for menu item '{}' of a closed window", item.getText());
            return;
        }
        windows.bringToFront(target);
        // Toggles a check item from the target's own state, so View toggles follow that window.
        MenuItemActivation.activate(counterpart);
        if (item instanceof CheckMenuItem check && counterpart instanceof CheckMenuItem shown) {
            // The click toggled the closed window's check mark; show what the target has now.
            check.setSelected(shown.isSelected());
        }
    }

    /**
     * The item at {@code item}'s place in {@code otherMenus}, or {@code null} when there is none or it
     * is of another kind or labelled differently. Every menu on the way runs its {@code onShowing}
     * first, so one that fills itself while opening holds its items.
     */
    static MenuItem counterpart(MenuItem item, List<Menu> ownMenus, List<Menu> otherMenus) {
        List<Integer> path = pathOf(item, ownMenus);
        if (path.isEmpty()) {
            return null;
        }
        List<? extends MenuItem> level = otherMenus;
        MenuItem found = null;
        for (int index : path) {
            if (found != null) {
                if (!(found instanceof Menu menu)) {
                    return null;
                }
                fill(menu);
                level = menu.getItems();
            }
            if (index >= level.size()) {
                return null;
            }
            found = level.get(index);
        }
        boolean same = found != null && found.getClass() == item.getClass()
            && Objects.equals(found.getText(), item.getText());
        return same ? found : null;
    }

    /** The indices from the menu bar down to {@code item}; empty when it is not in {@code menus}. */
    static List<Integer> pathOf(MenuItem item, List<Menu> menus) {
        List<Integer> path = new ArrayList<>();
        MenuItem current = item;
        for (Menu parent = current.getParentMenu(); parent != null; parent = current.getParentMenu()) {
            int index = parent.getItems().indexOf(current);
            if (index < 0) {
                return List.of();
            }
            path.add(0, index);
            current = parent;
        }
        int top = menus.indexOf(current);
        if (top < 0) {
            return List.of();
        }
        path.add(0, top);
        return path;
    }

    private static void fill(Menu menu) {
        EventHandler<Event> onShowing = menu.getOnShowing();
        if (onShowing != null) {
            onShowing.handle(new Event(Menu.ON_SHOWING));
        }
    }
}
