package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;

/**
 * Linux notifications straight through the notification server's D-Bus interface
 * ({@link NotificationsDBusConnection}), which is what makes them clickable: the server reports the
 * click back on the same connection. The connection is opened on the first notification and kept;
 * when it cannot be opened or breaks, this notification and every one for the next
 * {@link #RETRY_MILLIS} go through the {@code notify-send} fallback instead, after which the bus is
 * tried again. Runs on the notifier executor.
 */
final class LinuxDBusNotifierBackend implements DesktopNotifierBackend {

    private static final Logger logger = LoggerFactory.getLogger(LinuxDBusNotifierBackend.class);

    /** How long a failed bus connection is left alone before the next attempt. */
    static final long RETRY_MILLIS = 60_000L;

    /** The notification server as this backend needs it; {@link NotificationsDBusConnection} in production. */
    interface NotificationServer extends AutoCloseable {

        /** Shows a notification; returns the server's id for it. */
        int notify(String appName, String icon, String title, String body, String desktopEntry, int expireMillis,
                   Runnable onActivate) throws IOException;

        /** Whether the server reports clicks (its {@code actions} capability). */
        boolean supportsActions();

        /** False once the connection broke; the backend then opens a new one. */
        boolean isOpen();

        @Override
        void close();
    }

    /** Opens a connection to the notification server; throws when there is none. */
    interface ServerFactory {
        NotificationServer open() throws IOException;
    }

    private final String icon;
    private final String desktopEntry;
    private final ServerFactory factory;
    private final DesktopNotifierBackend fallback;
    private final LongSupplier clockMillis;
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private volatile NotificationServer server;
    private volatile boolean closed;
    private long retryAtMillis = Long.MIN_VALUE;

    /**
     * @param icon the {@code app_icon} (a theme icon name or an absolute path)
     * @param desktopEntry the desktop id without {@code .desktop}, or {@code null} when korTTY has none
     * @param factory opens the bus connection
     * @param fallback the backend for when the bus cannot be used ({@code notify-send} or unsupported)
     * @param clockMillis the clock that times the retry
     */
    LinuxDBusNotifierBackend(String icon, String desktopEntry, ServerFactory factory, DesktopNotifierBackend fallback,
                             LongSupplier clockMillis) {
        this.icon = icon == null || icon.isBlank() ? NotificationCommands.FALLBACK_LINUX_ICON : icon;
        this.desktopEntry = desktopEntry;
        this.factory = Objects.requireNonNull(factory, "factory");
        this.fallback = Objects.requireNonNull(fallback, "fallback");
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    /** The fallback backend (for the selection tests). */
    DesktopNotifierBackend fallback() {
        return fallback;
    }

    @Override
    public boolean isSupported() {
        return !closed;
    }

    @Override
    public boolean supportsActivation() {
        NotificationServer current = server;
        if (current != null && current.isOpen()) {
            return current.supportsActions();
        }
        // Not connected yet (or between attempts): the bus is what korTTY tries first.
        return !closed;
    }

    @Override
    public void notify(String title, String body) throws Exception {
        notify(title, body, null);
    }

    @Override
    public void notify(String title, String body, Runnable onActivate) throws Exception {
        NotificationServer current = connection();
        if (current != null) {
            try {
                current.notify(NotificationCommands.APP_NAME, icon, title, body, desktopEntry,
                    NotificationCommands.NOTIFY_SEND_EXPIRE_MILLIS, onActivate);
                return;
            } catch (IOException e) {
                dropConnection(current, e);
            }
        }
        if (!fallback.isSupported()) {
            throw new IOException("no notification server on the session bus and no notify-send");
        }
        fallback.notify(title, body, onActivate);
    }

    @Override
    public void close() {
        closed = true;
        NotificationServer current = server;
        server = null;
        if (current != null) {
            current.close();
        }
        fallback.close();
    }

    /** The open connection, opening one when none is open and the retry pause is over. */
    private NotificationServer connection() {
        NotificationServer current = server;
        if (current != null && current.isOpen()) {
            return current;
        }
        if (current != null) {
            server = null;
            current.close();
        }
        if (closed || clockMillis.getAsLong() < retryAtMillis) {
            return null;
        }
        try {
            NotificationServer opened = factory.open();
            if (closed) {
                opened.close();
                return null;
            }
            server = opened;
            return opened;
        } catch (IOException | RuntimeException e) {
            retryAtMillis = clockMillis.getAsLong() + RETRY_MILLIS;
            logFailure("Could not reach the notification server on the session bus; using notify-send", e);
            return null;
        }
    }

    private void dropConnection(NotificationServer current, Exception cause) {
        if (server == current) {
            server = null;
        }
        current.close();
        retryAtMillis = clockMillis.getAsLong() + RETRY_MILLIS;
        logFailure("Notification over the session bus failed; using notify-send", cause);
    }

    private void logFailure(String message, Exception cause) {
        if (failureLogged.compareAndSet(false, true)) {
            logger.info("{}: {}", message, cause.toString());
        } else {
            logger.debug("{}: {}", message, cause.toString());
        }
    }
}
