package de.kortty.ui.actions;

import javafx.event.Event;
import javafx.event.EventHandler;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuItem;

import java.util.List;

/**
 * Brings menu items up to date the way opening their menus would. Some menus set the enabled or
 * checked state of their items only in their {@code onShowing} handler (File disables Rename Tab
 * outside a terminal tab, Configuration › Security enables Unlock Vault while the vault is locked),
 * so a reader of the items that does not open the menu, such as the command palette, runs those
 * handlers first.
 *
 * <p>Menus marked with {@link ActionIds#exclude} are skipped with everything below them: they are
 * rebuilt while they open and are no actions anyway. A submenu that an opening handler adds is
 * refreshed as well. Toolkit-free (menus only, no Control); use it on the FX thread.
 */
public final class MenuStateRefresh {

    private MenuStateRefresh() {
    }

    /** Runs the {@code onShowing} handler of every menu and submenu, outermost first. */
    public static void refresh(List<? extends Menu> menus) {
        if (menus == null) {
            return;
        }
        for (Menu menu : List.copyOf(menus)) {
            refresh(menu);
        }
    }

    private static void refresh(Menu menu) {
        if (menu == null || ActionIds.isExcluded(menu)) {
            return;
        }
        EventHandler<Event> onShowing = menu.getOnShowing();
        if (onShowing != null) {
            onShowing.handle(new Event(menu, menu, Menu.ON_SHOWING));
        }
        for (MenuItem item : List.copyOf(menu.getItems())) {
            if (item instanceof Menu submenu) {
                refresh(submenu);
            }
        }
    }
}
