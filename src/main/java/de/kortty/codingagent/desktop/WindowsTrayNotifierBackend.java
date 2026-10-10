package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.EventQueue;
import java.awt.GraphicsEnvironment;
import java.awt.Image;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.InputStream;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;

/**
 * Windows balloon notifications through {@link TrayIcon#displayMessage}. The tray icon is created
 * lazily on the AWT event-dispatch thread inside {@link EventQueue#invokeLater} with headless and
 * {@link SystemTray#isSupported()} guards; the JavaFX thread never touches AWT. Removal on
 * {@link #close()} is fire-and-forget (the {@code MacMenuBarIcon.removeAsync} idiom).
 *
 * <p>A click on the balloon (on Windows 10 and 11 shown as a toast and kept in the notification
 * centre) reaches the tray icon's {@link java.awt.event.ActionListener}. Windows does not say which
 * balloon was clicked, and only the latest one can still be clicked through the icon, so the
 * backend keeps the click action of the latest notification only and runs it once. A double-click on
 * the tray icon fires the same event and therefore jumps to that notification too, which is the
 * pane the user was told about last.
 *
 * <p>Real WinRT toasts ({@code ToastNotificationManager}) were weighed and left out: they need an
 * AppUserModelID registered on a Start-menu shortcut by every installer (MSI; the portable zip has
 * none), a COM activator for clicks on toasts that outlive the process, and WinRT interop through
 * JNA, while the balloon already appears as a toast with korTTY's executable name and icon and its
 * click now reaches the pane.
 */
final class WindowsTrayNotifierBackend implements DesktopNotifierBackend {

    private static final Logger logger = LoggerFactory.getLogger(WindowsTrayNotifierBackend.class);
    private static final String ICON_RESOURCE = "/icon/kortty_icon.png";

    private final AtomicBoolean failureLogged = new AtomicBoolean();
    // Accessed on the AWT event-dispatch thread only.
    private TrayIcon trayIcon;
    private boolean trayUnavailable;
    private Runnable latestActivation;
    private volatile boolean supported = true;

    WindowsTrayNotifierBackend() {
        // SystemTray is probed lazily on the AWT thread.
    }

    @Override
    public boolean isSupported() {
        return supported;
    }

    @Override
    public boolean supportsActivation() {
        return supported;
    }

    @Override
    public void notify(String title, String body) {
        notify(title, body, null);
    }

    @Override
    public void notify(String title, String body, Runnable onActivate) {
        EventQueue.invokeLater(() -> {
            try {
                TrayIcon icon = ensureTrayIcon();
                if (icon != null) {
                    latestActivation = onActivate;
                    icon.displayMessage(title, body, TrayIcon.MessageType.INFO);
                }
            } catch (Throwable t) {
                logOnce("Could not show a tray notification", t);
            }
        });
    }

    @Override
    public void close() {
        try {
            EventQueue.invokeLater(() -> {
                try {
                    TrayIcon icon = trayIcon;
                    trayIcon = null;
                    latestActivation = null;
                    if (icon != null) {
                        SystemTray.getSystemTray().remove(icon);
                    }
                } catch (Throwable t) {
                    logger.debug("Could not remove the tray icon", t);
                }
            });
        } catch (Throwable t) {
            logger.debug("Could not schedule tray icon removal", t);
        }
    }

    private TrayIcon ensureTrayIcon() throws Exception {
        if (trayIcon != null || trayUnavailable) {
            return trayIcon;
        }
        if (GraphicsEnvironment.isHeadless() || !SystemTray.isSupported()) {
            trayUnavailable = true;
            supported = false;
            logger.debug("System tray is not available; tray notifications disabled");
            return null;
        }
        Image image = loadIcon();
        if (image == null) {
            trayUnavailable = true;
            supported = false;
            logger.debug("Tray icon image missing ({}); tray notifications disabled", ICON_RESOURCE);
            return null;
        }
        TrayIcon icon = new TrayIcon(image, NotificationCommands.APP_NAME);
        icon.setImageAutoSize(true);
        icon.addActionListener(event -> activateLatest());
        SystemTray.getSystemTray().add(icon);
        trayIcon = icon;
        return icon;
    }

    /** Runs the click action of the latest balloon once; AWT event-dispatch thread. */
    private void activateLatest() {
        Runnable activation = latestActivation;
        latestActivation = null;
        if (activation != null) {
            try {
                activation.run();
            } catch (Throwable t) {
                logger.debug("Tray notification click action failed", t);
            }
        }
    }

    private static Image loadIcon() throws Exception {
        try (InputStream in = WindowsTrayNotifierBackend.class.getResourceAsStream(ICON_RESOURCE)) {
            return in == null ? null : ImageIO.read(in);
        }
    }

    private void logOnce(String message, Throwable t) {
        if (failureLogged.compareAndSet(false, true)) {
            logger.warn(message, t);
        } else {
            logger.debug(message, t);
        }
    }
}
