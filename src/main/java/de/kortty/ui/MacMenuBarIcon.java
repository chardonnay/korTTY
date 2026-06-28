package de.kortty.ui;

import de.kortty.KorTTYApplication;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.imageio.ImageIO;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.InputStream;

/**
 * Adds a macOS menu-bar (AWT {@link SystemTray}) icon so the user can open a
 * window or quit korTTY even while it runs headless in the background for the
 * JobScheduler.
 *
 * <p>This is the reliable control surface for the packaged app: the native macOS
 * Quit (Cmd+Q / the app-menu "Quit korTTY") is broken on JavaFX 21.0.2+ once AWT
 * is loaded (<a href="https://bugs.openjdk.org/browse/JDK-8332656">JDK-8332656</a>:
 * Glass owns the {@code NSApplication} delegate and never forwards the quit), and
 * with {@code Platform.setImplicitExit(false)} (needed to keep the JobScheduler
 * running) there is no other native hook. The tray menu sidesteps all of that by
 * calling straight into korTTY via {@link MainWindow#runDockAction}, which ends in
 * {@code Runtime.getRuntime().halt(0)} (see {@code shutdownAndExit()} — halt, not
 * System.exit, to skip the AWT/JavaFX shutdown hooks that hang the macOS quit).
 * {@code SystemTray} is a different AWT subsystem than the
 * broken eawt Desktop events, so it works regardless. No-op where unsupported.
 */
public final class MacMenuBarIcon {

    private static final Logger logger = LoggerFactory.getLogger(MacMenuBarIcon.class);
    private static TrayIcon installed;

    /** Utility class — not instantiable. */
    private MacMenuBarIcon() {
    }

    /** Installs the menu-bar icon. Safe to call once at startup; failures are logged, not fatal. */
    public static void install() {
        if (installed != null) {
            return;
        }
        if (!SystemTray.isSupported()) {
            logger.info("System tray is not supported on this platform");
            return;
        }
        try {
            Image image = loadIcon();
            if (image == null) {
                logger.warn("Menu-bar icon image missing (/icon/kortty_icon.png); skipping system tray icon");
                return;
            }
            PopupMenu menu = new PopupMenu();
            menu.add(item(I18n.get("menu.file.newWindow"), MainWindow.DockAction.NEW_WINDOW));
            menu.addSeparator();
            menu.add(item(I18n.get("menu.file.quit"), MainWindow.DockAction.QUIT));

            TrayIcon trayIcon = new TrayIcon(image, KorTTYApplication.getAppName(), menu);
            trayIcon.setImageAutoSize(true);
            // Clicking the icon opens a window — the common menu-bar-app behaviour and
            // the fastest way back to the UI when running headless.
            trayIcon.addActionListener(e -> MainWindow.runDockAction(MainWindow.DockAction.NEW_WINDOW));
            SystemTray.getSystemTray().add(trayIcon);
            installed = trayIcon;
            logger.info("Installed macOS menu-bar (system tray) icon");
        } catch (Throwable t) {
            logger.warn("Could not install the macOS menu-bar icon", t);
        }
    }

    /** Removes the menu-bar icon, if present. */
    public static void remove() {
        if (installed != null) {
            try {
                SystemTray.getSystemTray().remove(installed);
            } catch (Throwable ignored) {
                // Best-effort cleanup; the JVM is exiting anyway.
            }
            installed = null;
        }
    }

    /** Loads the menu-bar icon image from the classpath, or {@code null} if missing/unreadable. */
    private static Image loadIcon() {
        try (InputStream in = MacMenuBarIcon.class.getResourceAsStream("/icon/kortty_icon.png")) {
            return in != null ? ImageIO.read(in) : null;
        } catch (Exception e) {
            logger.warn("Could not read the menu-bar icon image", e);
            return null;
        }
    }

    /** Builds a tray menu item that dispatches {@code action} onto the JavaFX thread via {@link MainWindow#runDockAction}. */
    private static MenuItem item(String label, MainWindow.DockAction action) {
        MenuItem menuItem = new MenuItem(label);
        // Fires on the AWT event thread; runDockAction marshals onto the JavaFX thread.
        menuItem.addActionListener(e -> MainWindow.runDockAction(action));
        return menuItem;
    }
}
