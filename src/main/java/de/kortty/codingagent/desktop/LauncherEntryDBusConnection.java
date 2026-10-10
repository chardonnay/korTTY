package de.kortty.codingagent.desktop;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A session-bus connection that stays open for the lifetime of the application and emits the
 * {@code com.canonical.Unity.LauncherEntry.Update} signal on demand.
 *
 * <p>The connection has to be persistent: every receiver of the counter — KDE Plasma's
 * {@code SmartLauncherBackend}, Dash to Dock and Ubuntu Dock — remembers the sender's unique bus
 * name and drops the launcher entry again as soon as that name unregisters. A one-shot
 * {@code gdbus emit} process therefore shows the count for about a millisecond and then loses it,
 * which is why korTTY speaks the wire protocol itself instead: one connection, one unique name, one
 * emit per count change.
 *
 * <p>Only the fraction of D-Bus that this needs is implemented: connect to the {@code unix:path}
 * socket of {@code DBUS_SESSION_BUS_ADDRESS}, authenticate with {@code EXTERNAL}, say {@code Hello}
 * ({@link DBusSessionSocket}) and marshal one signal ({@link DBusWire}). Everything is
 * little-endian, every wait is bounded and every failure is an {@link IOException} — the caller then
 * degrades to the window-title fallback. Nothing is ever read back except the {@code Hello} reply,
 * so no message from the bus can influence korTTY.
 */
final class LauncherEntryDBusConnection implements AutoCloseable {

    private static final String UPDATE_SIGNATURE = "sa{sv}";

    private final DBusSessionSocket socket;

    private LauncherEntryDBusConnection(DBusSessionSocket socket) {
        this.socket = socket;
    }

    /**
     * The session-bus socket to connect to: the {@code path=} of the first {@code unix:} entry in
     * {@code DBUS_SESSION_BUS_ADDRESS}, otherwise {@code $XDG_RUNTIME_DIR/bus}.
     *
     * <p>An {@code abstract=} address (the Linux abstract namespace, used by {@code dbus-launch}
     * sessions) is skipped: {@link java.net.UnixDomainSocketAddress} cannot express it. Such a
     * session falls back to the window title.
     */
    static Optional<String> sessionBusSocketPath(Map<String, String> env) {
        return DBusSessionSocket.sessionBusSocketPath(env);
    }

    /**
     * Connects, authenticates and registers on the bus.
     *
     * @param socketPath the session-bus socket
     * @param timeoutMillis the bound for the handshake and for every later write
     * @return the open connection
     * @throws IOException when the socket, the authentication or {@code Hello} failed
     */
    static LauncherEntryDBusConnection open(String socketPath, long timeoutMillis) throws IOException {
        return new LauncherEntryDBusConnection(DBusSessionSocket.open(socketPath, timeoutMillis));
    }

    /**
     * Emits one counter update.
     *
     * @param objectPath the LauncherEntry object path ({@link LinuxDesktopId#objectPath})
     * @param appUri {@code application://<desktop id>}
     * @param count the number of agents waiting for a decision ({@code 0} hides the counter)
     * @param urgent whether the launcher entry should be marked urgent
     * @throws IOException when the signal could not be written
     */
    void emitUpdate(String objectPath, String appUri, int count, boolean urgent) throws IOException {
        Objects.requireNonNull(objectPath, "objectPath");
        Objects.requireNonNull(appUri, "appUri");
        socket.discardIncoming();
        socket.write(updateSignal(socket.nextSerial(), objectPath, appUri, count, urgent));
    }

    /** Closes the connection; the bus then forgets the unique name and the receivers the entry. */
    @Override
    public void close() {
        socket.close();
    }

    /** The marshalled {@code org.freedesktop.DBus.Hello} method call. */
    static byte[] helloMessage(int serial) {
        return DBusWire.helloMessage(serial);
    }

    /**
     * The marshalled {@code com.canonical.Unity.LauncherEntry.Update} signal: the application URI
     * and the {@code count} / {@code count-visible} / {@code urgent} properties, exactly the
     * dictionary {@code gdbus emit} used to build as text.
     */
    static byte[] updateSignal(int serial, String objectPath, String appUri, int count, boolean urgent) {
        int visibleCount = Math.max(0, count);
        DBusWire.Marshaller properties = new DBusWire.Marshaller();
        putInt64Property(properties, "count", visibleCount);
        putBooleanProperty(properties, "count-visible", visibleCount > 0);
        putBooleanProperty(properties, "urgent", urgent);

        DBusWire.Marshaller body = new DBusWire.Marshaller();
        body.putString(appUri);
        body.putUInt32(properties.size());
        body.align(8);
        body.putBytes(properties.toArray());

        List<DBusWire.HeaderField> fields = List.of(
            new DBusWire.HeaderField(DBusWire.FIELD_PATH, "o", objectPath),
            new DBusWire.HeaderField(DBusWire.FIELD_INTERFACE, "s", LinuxDesktopId.LAUNCHER_ENTRY_INTERFACE),
            new DBusWire.HeaderField(DBusWire.FIELD_MEMBER, "s", LinuxDesktopId.LAUNCHER_ENTRY_MEMBER),
            new DBusWire.HeaderField(DBusWire.FIELD_SIGNATURE, "g", UPDATE_SIGNATURE));
        return DBusWire.message(DBusWire.TYPE_SIGNAL, DBusWire.FLAG_NO_REPLY_EXPECTED, serial, fields, body.toArray());
    }

    private static void putInt64Property(DBusWire.Marshaller properties, String name, long value) {
        properties.align(8);
        properties.putString(name);
        properties.putSignature("x");
        properties.putInt64(value);
    }

    private static void putBooleanProperty(DBusWire.Marshaller properties, String name, boolean value) {
        properties.align(8);
        properties.putString(name);
        properties.putSignature("b");
        properties.putUInt32(value ? 1 : 0);
    }
}
