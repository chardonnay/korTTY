package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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
 * and marshal one signal. Everything is little-endian, every wait is bounded and every failure is an
 * {@link IOException} — the caller then degrades to the window-title fallback. Nothing is ever read
 * back except the {@code Hello} reply, so no message from the bus can influence korTTY.
 */
final class LauncherEntryDBusConnection implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LauncherEntryDBusConnection.class);

    private static final byte LITTLE_ENDIAN = 'l';
    private static final byte PROTOCOL_VERSION = 1;
    private static final byte TYPE_METHOD_CALL = 1;
    private static final byte TYPE_METHOD_RETURN = 2;
    private static final byte TYPE_ERROR = 3;
    private static final byte TYPE_SIGNAL = 4;
    private static final byte FLAG_NO_REPLY_EXPECTED = 1;

    private static final byte FIELD_PATH = 1;
    private static final byte FIELD_INTERFACE = 2;
    private static final byte FIELD_MEMBER = 3;
    private static final byte FIELD_DESTINATION = 6;
    private static final byte FIELD_SIGNATURE = 8;

    private static final String BUS_PATH = "/org/freedesktop/DBus";
    private static final String BUS_NAME = "org.freedesktop.DBus";
    private static final String UPDATE_SIGNATURE = "sa{sv}";
    private static final int POLL_MILLIS = 2;
    private static final int MAX_DISCARD_ROUNDS = 64;

    private final SocketChannel channel;
    private final long timeoutMillis;
    private int serial = 1;

    private LauncherEntryDBusConnection(SocketChannel channel, long timeoutMillis) {
        this.channel = channel;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * The session-bus socket to connect to: the {@code path=} of the first {@code unix:} entry in
     * {@code DBUS_SESSION_BUS_ADDRESS}, otherwise {@code $XDG_RUNTIME_DIR/bus}.
     *
     * <p>An {@code abstract=} address (the Linux abstract namespace, used by {@code dbus-launch}
     * sessions) is skipped: {@link UnixDomainSocketAddress} cannot express it. Such a session falls
     * back to the window title.
     */
    static Optional<String> sessionBusSocketPath(Map<String, String> env) {
        Map<String, String> environment = env == null ? Map.of() : env;
        String address = environment.get("DBUS_SESSION_BUS_ADDRESS");
        if (address != null && !address.isBlank()) {
            for (String candidate : address.split(";")) {
                String path = unixPathOf(candidate);
                if (path != null && !path.isBlank()) {
                    return Optional.of(path);
                }
            }
        }
        String runtimeDir = environment.get("XDG_RUNTIME_DIR");
        if (runtimeDir != null && !runtimeDir.isBlank()) {
            // Joined with "/" rather than through Path: a D-Bus address is a POSIX path defined by the
            // protocol, not a path on whatever filesystem happens to be running this JVM, so it must
            // not pick up a platform separator.
            String base = runtimeDir.endsWith("/") ? runtimeDir.substring(0, runtimeDir.length() - 1)
                : runtimeDir;
            return Optional.of(base + "/bus");
        }
        return Optional.empty();
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
        Objects.requireNonNull(socketPath, "socketPath");
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        LauncherEntryDBusConnection connection = new LauncherEntryDBusConnection(channel, timeoutMillis);
        try {
            channel.configureBlocking(false);
            if (!channel.connect(UnixDomainSocketAddress.of(socketPath))) {
                long deadline = deadline(timeoutMillis);
                while (!channel.finishConnect()) {
                    connection.awaitPollInterval(deadline, "connect");
                }
            }
            connection.authenticate();
            connection.hello();
            return connection;
        } catch (IOException | RuntimeException e) {
            connection.close();
            throw e instanceof IOException io ? io : new IOException(e);
        }
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
        discardIncoming();
        writeFully(updateSignal(nextSerial(), objectPath, appUri, count, urgent));
    }

    /** Closes the connection; the bus then forgets the unique name and the receivers the entry. */
    @Override
    public void close() {
        try {
            channel.close();
        } catch (IOException e) {
            logger.debug("Could not close the session-bus connection: {}", e.toString());
        }
    }

    /** The marshalled {@code org.freedesktop.DBus.Hello} method call. */
    static byte[] helloMessage(int serial) {
        List<HeaderField> fields = List.of(
            new HeaderField(FIELD_PATH, "o", BUS_PATH),
            new HeaderField(FIELD_DESTINATION, "s", BUS_NAME),
            new HeaderField(FIELD_INTERFACE, "s", BUS_NAME),
            new HeaderField(FIELD_MEMBER, "s", "Hello"));
        return message(TYPE_METHOD_CALL, (byte) 0, serial, fields, new byte[0]);
    }

    /**
     * The marshalled {@code com.canonical.Unity.LauncherEntry.Update} signal: the application URI
     * and the {@code count} / {@code count-visible} / {@code urgent} properties, exactly the
     * dictionary {@code gdbus emit} used to build as text.
     */
    static byte[] updateSignal(int serial, String objectPath, String appUri, int count, boolean urgent) {
        int visibleCount = Math.max(0, count);
        Marshaller properties = new Marshaller();
        putInt64Property(properties, "count", visibleCount);
        putBooleanProperty(properties, "count-visible", visibleCount > 0);
        putBooleanProperty(properties, "urgent", urgent);

        Marshaller body = new Marshaller();
        body.putString(appUri);
        body.putUInt32(properties.size());
        body.align(8);
        body.putBytes(properties.toArray());

        List<HeaderField> fields = List.of(
            new HeaderField(FIELD_PATH, "o", objectPath),
            new HeaderField(FIELD_INTERFACE, "s", LinuxDesktopId.LAUNCHER_ENTRY_INTERFACE),
            new HeaderField(FIELD_MEMBER, "s", LinuxDesktopId.LAUNCHER_ENTRY_MEMBER),
            new HeaderField(FIELD_SIGNATURE, "g", UPDATE_SIGNATURE));
        return message(TYPE_SIGNAL, FLAG_NO_REPLY_EXPECTED, serial, fields, body.toArray());
    }

    private void authenticate() throws IOException {
        writeFully(new byte[] {0});
        writeAscii("AUTH EXTERNAL " + hexEncode(currentUid()) + "\r\n");
        String reply = readLine();
        if (reply.startsWith("REJECTED")) {
            // Retry with empty credentials: the bus then uses the peer credentials of the socket.
            writeAscii("AUTH EXTERNAL\r\n");
            reply = readLine();
            if (reply.startsWith("DATA")) {
                writeAscii("DATA\r\n");
                reply = readLine();
            }
        }
        if (!reply.startsWith("OK")) {
            throw new IOException("session bus refused the EXTERNAL authentication: " + reply);
        }
        writeAscii("BEGIN\r\n");
    }

    private void hello() throws IOException {
        writeFully(helloMessage(nextSerial()));
        byte[] header = readFully(16);
        byte type = header[1];
        int bodyLength = readUInt32(header, 4);
        int fieldsLength = readUInt32(header, 12);
        readFully(fieldsLength + padding(fieldsLength, 8) + bodyLength);
        if (type == TYPE_ERROR) {
            throw new IOException("session bus rejected Hello");
        }
        if (type != TYPE_METHOD_RETURN) {
            throw new IOException("unexpected reply to Hello: message type " + type);
        }
    }

    private int nextSerial() {
        serial = serial == Integer.MAX_VALUE ? 1 : serial + 1;
        return serial;
    }

    /**
     * Drops whatever the bus sent us (the {@code NameAcquired} signal); korTTY never listens, so the
     * bytes are read only to notice a closed connection and to keep the receive buffer empty. The
     * loop is bounded so a chatty bus can never hold the badge executor.
     */
    private void discardIncoming() throws IOException {
        ByteBuffer scratch = ByteBuffer.allocate(4096);
        for (int round = 0; round < MAX_DISCARD_ROUNDS; round++) {
            scratch.clear();
            int read = channel.read(scratch);
            if (read < 0) {
                throw new IOException("session bus closed the connection");
            }
            if (read == 0) {
                return;
            }
        }
    }

    private void writeAscii(String text) throws IOException {
        writeFully(text.getBytes(StandardCharsets.US_ASCII));
    }

    private void writeFully(byte[] bytes) throws IOException {
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        long deadline = deadline(timeoutMillis);
        while (buffer.hasRemaining()) {
            if (channel.write(buffer) == 0) {
                awaitPollInterval(deadline, "write");
            }
        }
    }

    private byte[] readFully(int length) throws IOException {
        ByteBuffer buffer = ByteBuffer.allocate(Math.max(0, length));
        long deadline = deadline(timeoutMillis);
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer);
            if (read < 0) {
                throw new IOException("session bus closed the connection");
            }
            if (read == 0) {
                awaitPollInterval(deadline, "read");
            }
        }
        return buffer.array();
    }

    private String readLine() throws IOException {
        StringBuilder line = new StringBuilder(64);
        while (line.indexOf("\r\n") < 0) {
            if (line.length() > 1024) {
                throw new IOException("session bus sent an overlong authentication line");
            }
            line.append(new String(readFully(1), StandardCharsets.US_ASCII));
        }
        return line.substring(0, line.length() - 2);
    }

    private void awaitPollInterval(long deadline, String stage) throws IOException {
        if (System.nanoTime() - deadline >= 0) {
            throw new IOException("session bus " + stage + " timed out after " + timeoutMillis + " ms");
        }
        try {
            Thread.sleep(POLL_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while talking to the session bus", e);
        }
    }

    private static long deadline(long timeoutMillis) {
        return System.nanoTime() + timeoutMillis * 1_000_000L;
    }

    private static String currentUid() {
        try {
            return String.valueOf(Files.getAttribute(Path.of("/proc/self"), "unix:uid"));
        } catch (IOException | RuntimeException e) {
            logger.debug("Could not read the process uid for the D-Bus handshake: {}", e.toString());
            return "";
        }
    }

    private static String hexEncode(String text) {
        StringBuilder hex = new StringBuilder(text.length() * 2);
        for (byte b : text.getBytes(StandardCharsets.US_ASCII)) {
            hex.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return hex.toString();
    }

    private static String unixPathOf(String address) {
        String candidate = address.trim();
        if (!candidate.startsWith("unix:")) {
            return null;
        }
        for (String pair : candidate.substring("unix:".length()).split(",")) {
            int separator = pair.indexOf('=');
            if (separator > 0 && "path".equals(pair.substring(0, separator))) {
                return unescape(pair.substring(separator + 1));
            }
        }
        return null;
    }

    private static String unescape(String value) {
        if (value.indexOf('%') < 0) {
            return value;
        }
        StringBuilder out = new StringBuilder(value.length());
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c == '%' && i + 2 < value.length()) {
                int high = Character.digit(value.charAt(i + 1), 16);
                int low = Character.digit(value.charAt(i + 2), 16);
                if (high >= 0 && low >= 0) {
                    out.append((char) ((high << 4) | low));
                    i += 2;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    private static void putInt64Property(Marshaller properties, String name, long value) {
        properties.align(8);
        properties.putString(name);
        properties.putSignature("x");
        properties.putInt64(value);
    }

    private static void putBooleanProperty(Marshaller properties, String name, boolean value) {
        properties.align(8);
        properties.putString(name);
        properties.putSignature("b");
        properties.putUInt32(value ? 1 : 0);
    }

    private static byte[] message(byte type, byte flags, int serial, List<HeaderField> fields, byte[] body) {
        Marshaller headerFields = new Marshaller();
        for (HeaderField field : fields) {
            headerFields.align(8);
            headerFields.putByte(field.code());
            headerFields.putSignature(field.signature());
            if ("g".equals(field.signature())) {
                headerFields.putSignature(field.value());
            } else {
                headerFields.putString(field.value());
            }
        }
        Marshaller out = new Marshaller();
        out.putByte(LITTLE_ENDIAN);
        out.putByte(type);
        out.putByte(flags);
        out.putByte(PROTOCOL_VERSION);
        out.putUInt32(body.length);
        out.putUInt32(serial);
        out.putUInt32(headerFields.size());
        out.putBytes(headerFields.toArray());
        out.align(8);
        out.putBytes(body);
        return out.toArray();
    }

    private static int readUInt32(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff)
            | ((bytes[offset + 1] & 0xff) << 8)
            | ((bytes[offset + 2] & 0xff) << 16)
            | ((bytes[offset + 3] & 0xff) << 24);
    }

    private static int padding(int size, int alignment) {
        int remainder = size % alignment;
        return remainder == 0 ? 0 : alignment - remainder;
    }

    /** One {@code a(yv)} header field: the field code and its variant. */
    private record HeaderField(byte code, String signature, String value) {
    }

    /**
     * Little-endian D-Bus marshaller. Every buffer this class fills starts at an eight-byte aligned
     * position of the message, so padding computed from the buffer offset matches the wire position.
     */
    private static final class Marshaller {

        private byte[] data = new byte[128];
        private int size;

        void align(int alignment) {
            while (size % alignment != 0) {
                putByte((byte) 0);
            }
        }

        void putByte(byte value) {
            ensure(1);
            data[size++] = value;
        }

        void putBytes(byte[] values) {
            ensure(values.length);
            System.arraycopy(values, 0, data, size, values.length);
            size += values.length;
        }

        void putUInt32(int value) {
            align(4);
            ensure(4);
            data[size++] = (byte) value;
            data[size++] = (byte) (value >>> 8);
            data[size++] = (byte) (value >>> 16);
            data[size++] = (byte) (value >>> 24);
        }

        void putInt64(long value) {
            align(8);
            ensure(8);
            for (int i = 0; i < 8; i++) {
                data[size++] = (byte) (value >>> (8 * i));
            }
        }

        void putString(String text) {
            byte[] utf8 = text.getBytes(StandardCharsets.UTF_8);
            putUInt32(utf8.length);
            putBytes(utf8);
            putByte((byte) 0);
        }

        void putSignature(String signature) {
            byte[] ascii = signature.getBytes(StandardCharsets.US_ASCII);
            putByte((byte) ascii.length);
            putBytes(ascii);
            putByte((byte) 0);
        }

        int size() {
            return size;
        }

        byte[] toArray() {
            byte[] copy = new byte[size];
            System.arraycopy(data, 0, copy, 0, size);
            return copy;
        }

        private void ensure(int additional) {
            if (size + additional <= data.length) {
                return;
            }
            int capacity = data.length;
            while (capacity < size + additional) {
                capacity *= 2;
            }
            byte[] grown = new byte[capacity];
            System.arraycopy(data, 0, grown, 0, size);
            data = grown;
        }
    }
}
