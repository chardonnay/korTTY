package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.awt.EventQueue;
import java.awt.Taskbar;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * macOS Dock badge through {@link Taskbar#setIconBadge}. Constructed only for the packaged
 * application (a {@code ./gradlew run} JVM has no Dock tile and initialising AWT there would pin a
 * non-daemon thread). All AWT calls are posted with {@link EventQueue#invokeLater} inside a
 * {@code Throwable} guard — never {@code invokeAndWait}, never from a shutdown hook.
 */
final class MacDockBadgeBackend implements AppBadgeBackend {

    private static final Logger logger = LoggerFactory.getLogger(MacDockBadgeBackend.class);

    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private volatile Boolean supported;

    MacDockBadgeBackend() {
        // The Taskbar probe is deferred to the first isSupported() call.
    }

    @Override
    public boolean isSupported() {
        Boolean known = supported;
        if (known == null) {
            known = probeSupport(Taskbar.Feature.ICON_BADGE_TEXT);
            supported = known;
        }
        return known;
    }

    @Override
    public void showCount(int blockedCount) {
        String badge = blockedCount > 0 ? Integer.toString(blockedCount) : null;
        EventQueue.invokeLater(() -> {
            try {
                Taskbar.getTaskbar().setIconBadge(badge);
            } catch (Throwable t) {
                logOnce("Could not update the macOS Dock badge", t);
            }
        });
    }

    @Override
    public void requestAttention() {
        EventQueue.invokeLater(() -> {
            try {
                Taskbar taskbar = Taskbar.getTaskbar();
                if (taskbar.isSupported(Taskbar.Feature.USER_ATTENTION)) {
                    taskbar.requestUserAttention(true, false);
                }
            } catch (Throwable t) {
                logOnce("Could not request attention through the macOS Dock", t);
            }
        });
    }

    @Override
    public void close() {
        try {
            EventQueue.invokeLater(() -> {
                try {
                    Taskbar.getTaskbar().setIconBadge(null);
                } catch (Throwable t) {
                    logger.debug("Could not clear the macOS Dock badge", t);
                }
            });
        } catch (Throwable t) {
            logger.debug("Could not schedule clearing the macOS Dock badge", t);
        }
    }

    private static boolean probeSupport(Taskbar.Feature feature) {
        try {
            return Taskbar.isTaskbarSupported() && Taskbar.getTaskbar().isSupported(feature);
        } catch (Throwable t) {
            logger.debug("macOS Taskbar probe failed", t);
            return false;
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
