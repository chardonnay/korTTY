package de.kortty.ui;

import de.kortty.KorTTYApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.Taskbar;

/**
 * Adds a right-click menu to korTTY's macOS Dock icon via the AWT
 * {@link Taskbar} API. Items mirror common app actions and run them against the
 * focused window through {@link MainWindow#runDockAction}. No-op on platforms /
 * runtimes that don't support a Dock menu.
 */
public final class MacDockMenu {

    private static final Logger logger = LoggerFactory.getLogger(MacDockMenu.class);

    /** Utility class — not instantiable. */
    private MacDockMenu() {
    }

    /** Installs the Dock menu. Safe to call once at startup; failures are logged, not fatal. */
    public static void install() {
        if (!Taskbar.isTaskbarSupported()) {
            return;
        }
        try {
            Taskbar taskbar = Taskbar.getTaskbar();
            if (!taskbar.isSupported(Taskbar.Feature.MENU)) {
                logger.info("Dock menu is not supported on this platform");
                return;
            }
            PopupMenu menu = new PopupMenu();
            menu.add(item(I18n.get("menu.file.newWindow"), MainWindow.DockAction.NEW_WINDOW));
            menu.add(item(I18n.get("dock.newTab"), MainWindow.DockAction.NEW_TAB));
            menu.addSeparator();
            menu.add(item(I18n.get("menu.connections.manage"), MainWindow.DockAction.CONNECTION_MANAGER));
            menu.add(item(I18n.get("menu.file.openProject"), MainWindow.DockAction.OPEN_PROJECT));
            menu.addSeparator();
            menu.add(item(I18n.get("menu.help.guide"), MainWindow.DockAction.GUIDE));
            menu.add(item(I18n.get("menu.help.about") + " " + KorTTYApplication.getAppName(),
                    MainWindow.DockAction.ABOUT));
            menu.addSeparator();
            // A reliable Quit is essential here: the app keeps running in the
            // background for the JobScheduler after all windows close, and the native
            // macOS "Quit korTTY" is broken on JavaFX 21.0.2+ (JDK-8332656). This item
            // quits korTTY even when no window is open.
            menu.add(item(I18n.get("menu.file.quit"), MainWindow.DockAction.QUIT));
            taskbar.setMenu(menu);
            logger.info("Installed macOS Dock menu");
        } catch (Throwable t) {
            logger.warn("Could not install the macOS Dock menu", t);
        }
    }

    /** Builds a Dock menu item that dispatches {@code action} onto the JavaFX thread via {@link MainWindow#runDockAction}. */
    private static MenuItem item(String label, MainWindow.DockAction action) {
        MenuItem menuItem = new MenuItem(label);
        // Dock menu actions fire on the AWT event thread; runDockAction marshals
        // onto the JavaFX Application Thread.
        menuItem.addActionListener(e -> MainWindow.runDockAction(action));
        return menuItem;
    }
}
