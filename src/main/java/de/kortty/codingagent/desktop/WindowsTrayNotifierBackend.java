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
 */
final class WindowsTrayNotifierBackend implements DesktopNotifierBackend {

    private static final Logger logger = LoggerFactory.getLogger(WindowsTrayNotifierBackend.class);
    private static final String ICON_RESOURCE = "/icon/kortty_icon.png";

    private final AtomicBoolean failureLogged = new AtomicBoolean();
    // Accessed on the AWT event-dispatch thread only.
    private TrayIcon trayIcon;
    private boolean trayUnavailable;
    private volatile boolean supported = true;

    WindowsTrayNotifierBackend() {
        // SystemTray is probed lazily on the AWT thread.
    }

    @Override
    public boolean isSupported() {
        return supported;
    }

    @Override
    public void notify(String title, String body) {
        EventQueue.invokeLater(() -> {
            try {
                TrayIcon icon = ensureTrayIcon();
                if (icon != null) {
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
        SystemTray.getSystemTray().add(icon);
        trayIcon = icon;
        return icon;
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
