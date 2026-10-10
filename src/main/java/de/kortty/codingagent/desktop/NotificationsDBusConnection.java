package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A session-bus connection to the desktop's notification server ({@code org.freedesktop.Notifications},
 * Desktop Notifications Specification 1.2) that, unlike {@code notify-send}, stays around to hear
 * which notification the user clicked.
 *
 * <p>Each notification is sent with a {@code default} action — the one notification servers run when
 * the notification itself is clicked — and the {@code desktop-entry} hint, so GNOME and KDE show
 * korTTY's name and icon and group the notifications under it. A reader thread
 * ({@code kortty-notification-bus}) waits for the method replies and the {@code ActionInvoked} and
 * {@code NotificationClosed} signals; the click action of a notification runs on that thread, once,
 * when its {@code default} action is invoked, and is forgotten when the notification closes. Only
 * signals whose sender is the unique bus name that answered korTTY's own calls count, so another
 * program on the bus cannot fake a click.
 */
final class NotificationsDBusConnection implements LinuxDBusNotifierBackend.NotificationServer {

    private static final Logger logger = LoggerFactory.getLogger(NotificationsDBusConnection.class);

    static final String SERVICE = "org.freedesktop.Notifications";
    static final String OBJECT_PATH = "/org/freedesktop/Notifications";
    static final String DEFAULT_ACTION = "default";
    /** The label of the default action; servers that list actions as buttons show it. */
    static final String DEFAULT_ACTION_LABEL = "Open";
    static final String MATCH_RULE = "type='signal',sender='" + SERVICE + "',path='" + OBJECT_PATH
        + "',interface='" + SERVICE + "'";
    static final String NOTIFY_SIGNATURE = "susssasa{sv}i";
    /** At most this many notifications keep their click action; older ones lose it. */
    static final int MAX_TRACKED_NOTIFICATIONS = 64;
    /** Urgency hint value {@code normal} (0 low, 1 normal, 2 critical). */
    static final byte URGENCY_NORMAL = 1;

    private static final long READ_WAIT_MILLIS = 1_000L;
    private static final String READER_THREAD_NAME = "kortty-notification-bus";

    private final DBusSessionSocket socket;
    private final long timeoutMillis;
    private final Map<Integer, CompletableFuture<DBusWire.Message>> replies = new ConcurrentHashMap<>();
    private final Activations activations = new Activations(MAX_TRACKED_NOTIFICATIONS);
    private volatile boolean open = true;
    private volatile boolean actions;
    private Thread reader;

    private NotificationsDBusConnection(DBusSessionSocket socket, long timeoutMillis) {
        this.socket = socket;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * Connects to the session bus, starts the reader, subscribes to the notification server's signals
     * and asks it whether it supports actions.
     *
     * @throws IOException when the bus or the notification server cannot be reached
     */
    static NotificationsDBusConnection open(String socketPath, long timeoutMillis) throws IOException {
        NotificationsDBusConnection connection =
            new NotificationsDBusConnection(DBusSessionSocket.open(socketPath, timeoutMillis), timeoutMillis);
        try {
            connection.startReader();
            connection.call(addMatchCall(connection.socket.nextSerial()));
            DBusWire.Message capabilities = connection.call(DBusWire.methodCall(connection.socket.nextSerial(),
                SERVICE, OBJECT_PATH, SERVICE, "GetCapabilities", "", new byte[0]));
            connection.actions = capabilitiesOf(capabilities).contains("actions");
            connection.activations.serverName(capabilities.sender());
            return connection;
        } catch (IOException | RuntimeException e) {
            connection.close();
            throw e instanceof IOException io ? io : new IOException(e);
        }
    }

    @Override
    public boolean isOpen() {
        return open;
    }

    @Override
    public boolean supportsActions() {
        return actions;
    }

    @Override
    public int notify(String appName, String icon, String title, String body, String desktopEntry,
                      int expireMillis, Runnable onActivate) throws IOException {
        boolean withAction = onActivate != null && actions;
        DBusWire.Message reply = call(notifyCall(socket.nextSerial(), appName, icon, title, body,
            withAction, desktopEntry, expireMillis));
        if (!"u".equals(reply.signature())) {
            throw new IOException("unexpected Notify reply signature " + reply.signature());
        }
        int id;
        try {
            id = new DBusWire.Unmarshaller(reply.body()).readUInt32();
        } catch (IllegalArgumentException e) {
            throw new IOException("unreadable Notify reply", e);
        }
        activations.serverName(reply.sender());
        if (withAction) {
            activations.register(id, onActivate);
        }
        return id;
    }

    @Override
    public void close() {
        open = false;
        socket.close();
        Thread thread = reader;
        if (thread != null) {
            thread.interrupt();
        }
        failPendingReplies(new IOException("notification bus connection closed"));
    }

    /** The marshalled {@code AddMatch} call that subscribes to the notification server's signals. */
    static byte[] addMatchCall(int serial) {
        DBusWire.Marshaller body = new DBusWire.Marshaller();
        body.putString(MATCH_RULE);
        return DBusWire.methodCall(serial, DBusWire.BUS_NAME, DBusWire.BUS_PATH, DBusWire.BUS_NAME, "AddMatch",
            "s", body.toArray());
    }

    /**
     * The marshalled {@code Notify} call: no replaced notification, the {@code default} action when
     * {@code withDefaultAction}, the {@code urgency} hint and — when known — the {@code desktop-entry}
     * hint (the desktop id without {@code .desktop}). The body is escaped for markup, as for
     * {@code notify-send}; the summary is never markup.
     */
    static byte[] notifyCall(int serial, String appName, String icon, String title, String body,
                             boolean withDefaultAction, String desktopEntry, int expireMillis) {
        DBusWire.Marshaller out = new DBusWire.Marshaller();
        out.putString(nullToEmpty(appName));
        out.putUInt32(0);
        out.putString(nullToEmpty(icon));
        out.putString(nullToEmpty(title));
        out.putString(NotificationCommands.escapeMarkup(body));

        DBusWire.Marshaller actionList = new DBusWire.Marshaller();
        if (withDefaultAction) {
            actionList.putString(DEFAULT_ACTION);
            actionList.putString(DEFAULT_ACTION_LABEL);
        }
        out.putUInt32(actionList.size());
        out.putBytes(actionList.toArray());

        DBusWire.Marshaller hints = new DBusWire.Marshaller();
        hints.align(8);
        hints.putString("urgency");
        hints.putSignature("y");
        hints.putByte(URGENCY_NORMAL);
        if (desktopEntry != null && !desktopEntry.isBlank()) {
            hints.align(8);
            hints.putString("desktop-entry");
            hints.putSignature("s");
            hints.putString(desktopEntry);
        }
        out.putUInt32(hints.size());
        out.align(8);
        out.putBytes(hints.toArray());

        out.putUInt32(expireMillis);
        return DBusWire.methodCall(serial, SERVICE, OBJECT_PATH, SERVICE, "Notify", NOTIFY_SIGNATURE,
            out.toArray());
    }

    /** The capability names of a {@code GetCapabilities} reply; empty when it carries none. */
    static List<String> capabilitiesOf(DBusWire.Message reply) {
        if (reply == null || !"as".equals(reply.signature())) {
            return List.of();
        }
        try {
            return new DBusWire.Unmarshaller(reply.body()).readStringArray();
        } catch (IllegalArgumentException e) {
            return List.of();
        }
    }

    private DBusWire.Message call(byte[] message) throws IOException {
        int serial = DBusWire.readUInt32(message, 8);
        CompletableFuture<DBusWire.Message> reply = new CompletableFuture<>();
        replies.put(serial, reply);
        try {
            if (!open) {
                throw new IOException("notification bus connection closed");
            }
            socket.write(message);
            DBusWire.Message answer = reply.get(timeoutMillis, TimeUnit.MILLISECONDS);
            if (answer.type() == DBusWire.TYPE_ERROR) {
                throw new IOException("D-Bus error " + answer.errorName());
            }
            return answer;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while waiting for the notification server", e);
        } catch (ExecutionException e) {
            throw e.getCause() instanceof IOException io ? io : new IOException(e.getCause());
        } catch (TimeoutException e) {
            throw new IOException("notification server did not answer within " + timeoutMillis + " ms", e);
        } finally {
            replies.remove(serial);
        }
    }

    private void startReader() {
        Thread thread = new Thread(this::readLoop, READER_THREAD_NAME);
        thread.setDaemon(true);
        reader = thread;
        thread.start();
    }

    private void readLoop() {
        try {
            while (open) {
                Optional<byte[]> next = socket.readMessage(READ_WAIT_MILLIS);
                if (next.isPresent()) {
                    dispatch(next.get());
                }
            }
        } catch (IOException | RuntimeException e) {
            if (open) {
                logger.debug("Notification bus connection lost: {}", e.toString());
            }
        } finally {
            open = false;
            socket.close();
            failPendingReplies(new IOException("notification bus connection lost"));
        }
    }

    private void dispatch(byte[] bytes) {
        DBusWire.Message message;
        try {
            message = DBusWire.parse(bytes);
        } catch (IllegalArgumentException e) {
            logger.debug("Ignoring an unreadable message from the session bus: {}", e.toString());
            return;
        }
        if (message.type() == DBusWire.TYPE_METHOD_RETURN || message.type() == DBusWire.TYPE_ERROR) {
            CompletableFuture<DBusWire.Message> waiting = replies.get(message.replySerial());
            if (waiting != null) {
                waiting.complete(message);
            }
            return;
        }
        Runnable activation = activations.onSignal(message);
        if (activation != null) {
            try {
                activation.run();
            } catch (RuntimeException e) {
                logger.debug("Notification click action failed", e);
            }
        }
    }

    private void failPendingReplies(IOException cause) {
        for (CompletableFuture<DBusWire.Message> waiting : replies.values()) {
            waiting.completeExceptionally(cause);
        }
    }

    private static String nullToEmpty(String text) {
        return text == null ? "" : text;
    }

    /**
     * The click actions of the notifications still on screen, by notification id, and what a signal
     * from the notification server does to them. Pure and thread-safe.
     */
    static final class Activations {

        private final int capacity;
        private final LinkedHashMap<Integer, Runnable> byId = new LinkedHashMap<>();
        private String serverName = "";

        Activations(int capacity) {
            this.capacity = Math.max(1, capacity);
        }

        /** Records the unique bus name of the notification server, from a reply it sent. */
        synchronized void serverName(String uniqueName) {
            if (uniqueName != null && !uniqueName.isBlank()) {
                serverName = uniqueName;
            }
        }

        /** Remembers {@code onActivate} for notification {@code id}, dropping the oldest beyond capacity. */
        synchronized void register(int id, Runnable onActivate) {
            byId.remove(id);
            byId.put(id, Objects.requireNonNull(onActivate, "onActivate"));
            Iterator<Integer> oldest = byId.keySet().iterator();
            while (byId.size() > capacity && oldest.hasNext()) {
                oldest.next();
                oldest.remove();
            }
        }

        /** How many notifications still have a click action. */
        synchronized int size() {
            return byId.size();
        }

        /**
         * Applies one signal: {@code ActionInvoked(id, "default")} from the notification server hands
         * back that notification's action (and forgets it), {@code NotificationClosed(id, reason)}
         * forgets it; every other message, and every signal from another sender, changes nothing.
         *
         * @return the click action to run, or {@code null}
         */
        synchronized Runnable onSignal(DBusWire.Message message) {
            if (message == null || message.type() != DBusWire.TYPE_SIGNAL || serverName.isEmpty()
                    || !serverName.equals(message.sender()) || !SERVICE.equals(message.iface())
                    || !OBJECT_PATH.equals(message.path())) {
                return null;
            }
            try {
                if ("ActionInvoked".equals(message.member()) && "us".equals(message.signature())) {
                    DBusWire.Unmarshaller body = new DBusWire.Unmarshaller(message.body());
                    int id = body.readUInt32();
                    String action = body.readString();
                    return DEFAULT_ACTION.equals(action) ? byId.remove(id) : null;
                }
                if ("NotificationClosed".equals(message.member()) && "uu".equals(message.signature())) {
                    byId.remove(new DBusWire.Unmarshaller(message.body()).readUInt32());
                }
            } catch (IllegalArgumentException e) {
                logger.debug("Ignoring a malformed notification signal: {}", e.toString());
            }
            return null;
        }
    }
}
