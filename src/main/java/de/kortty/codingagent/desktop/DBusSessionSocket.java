package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * One authenticated session-bus connection over the {@code unix:path} socket: connect,
 * {@code EXTERNAL} authentication and {@code Hello}, then bounded writes and — for a connection that
 * listens — reading whole messages. The socket is non-blocking; every write and the handshake are
 * bounded by the timeout, and {@link #readMessage} waits in a {@link Selector}, never in a busy loop.
 * Every failure is an {@link IOException}.
 *
 * <p>Writing is safe from any thread; reading is meant for one thread at a time (the listening
 * connection's reader).
 */
final class DBusSessionSocket implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(DBusSessionSocket.class);

    private static final int POLL_MILLIS = 2;
    private static final int MAX_DISCARD_ROUNDS = 64;

    private final SocketChannel channel;
    private final long timeoutMillis;
    private final Object writeLock = new Object();
    private int serial = 1;
    private String uniqueName = "";
    // Reader state, touched only by the reading thread.
    private volatile Selector selector;
    private ByteBuffer pending = ByteBuffer.allocate(4096);

    private DBusSessionSocket(SocketChannel channel, long timeoutMillis) {
        this.channel = channel;
        this.timeoutMillis = timeoutMillis;
    }

    /**
     * The session-bus socket to connect to: the {@code path=} of the first {@code unix:} entry in
     * {@code DBUS_SESSION_BUS_ADDRESS}, otherwise {@code $XDG_RUNTIME_DIR/bus}.
     *
     * <p>An {@code abstract=} address (the Linux abstract namespace, used by {@code dbus-launch}
     * sessions) is skipped: {@link UnixDomainSocketAddress} cannot express it.
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
    static DBusSessionSocket open(String socketPath, long timeoutMillis) throws IOException {
        Objects.requireNonNull(socketPath, "socketPath");
        SocketChannel channel = SocketChannel.open(StandardProtocolFamily.UNIX);
        DBusSessionSocket connection = new DBusSessionSocket(channel, timeoutMillis);
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

    /** The unique bus name the bus assigned in its reply to {@code Hello}, for example {@code :1.42}. */
    String uniqueName() {
        return uniqueName;
    }

    /** The next message serial (never 0, wrapping before overflow). */
    int nextSerial() {
        synchronized (writeLock) {
            serial = serial == Integer.MAX_VALUE ? 1 : serial + 1;
            return serial;
        }
    }

    /** Writes one whole message, bounded by the timeout. */
    void write(byte[] message) throws IOException {
        synchronized (writeLock) {
            writeFully(message);
        }
    }

    /**
     * Drops whatever the bus sent (the {@code NameAcquired} signal) for a connection that never
     * listens: the bytes are read only to notice a closed connection and to keep the receive buffer
     * empty. The loop is bounded so a chatty bus can never hold the caller.
     */
    void discardIncoming() throws IOException {
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

    /**
     * Waits up to {@code waitMillis} for the next whole message and returns its bytes; empty when
     * none arrived in time. One reading thread only.
     *
     * @throws IOException when the bus closed the connection or sent a message korTTY cannot frame
     */
    Optional<byte[]> readMessage(long waitMillis) throws IOException {
        Optional<byte[]> buffered = takeBufferedMessage();
        if (buffered.isPresent()) {
            return buffered;
        }
        if (selector == null) {
            selector = Selector.open();
            channel.register(selector, SelectionKey.OP_READ);
        }
        selector.select(Math.max(1L, waitMillis));
        selector.selectedKeys().clear();
        while (true) {
            if (!pending.hasRemaining()) {
                ByteBuffer grown = ByteBuffer.allocate(pending.capacity() * 2);
                pending.flip();
                grown.put(pending);
                pending = grown;
            }
            int read = channel.read(pending);
            if (read < 0) {
                throw new IOException("session bus closed the connection");
            }
            if (read == 0) {
                break;
            }
            if (pending.position() > DBusWire.MAX_MESSAGE_BYTES * 2) {
                throw new IOException("session bus sent more than korTTY reads");
            }
        }
        return takeBufferedMessage();
    }

    /** Closes the connection; the bus then forgets the unique name. */
    @Override
    public void close() {
        try {
            if (selector != null) {
                selector.close();
            }
        } catch (IOException e) {
            logger.debug("Could not close the session-bus selector: {}", e.toString());
        }
        try {
            channel.close();
        } catch (IOException e) {
            logger.debug("Could not close the session-bus connection: {}", e.toString());
        }
    }

    private Optional<byte[]> takeBufferedMessage() throws IOException {
        if (pending.position() < DBusWire.FIXED_HEADER_BYTES) {
            return Optional.empty();
        }
        byte[] header = new byte[DBusWire.FIXED_HEADER_BYTES];
        System.arraycopy(pending.array(), 0, header, 0, header.length);
        int length;
        try {
            length = DBusWire.messageLength(header);
        } catch (IllegalArgumentException e) {
            throw new IOException("session bus sent an unreadable message: " + e.getMessage(), e);
        }
        if (pending.position() < length) {
            return Optional.empty();
        }
        byte[] message = new byte[length];
        pending.flip();
        pending.get(message);
        pending.compact();
        return Optional.of(message);
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
        writeFully(DBusWire.helloMessage(nextSerial()));
        byte[] header = readFully(DBusWire.FIXED_HEADER_BYTES);
        byte type = header[1];
        int bodyLength = DBusWire.readUInt32(header, 4);
        int fieldsLength = DBusWire.readUInt32(header, 12);
        byte[] rest = readFully(fieldsLength + DBusWire.padding(fieldsLength, 8) + bodyLength);
        if (type == DBusWire.TYPE_ERROR) {
            throw new IOException("session bus rejected Hello");
        }
        if (type != DBusWire.TYPE_METHOD_RETURN) {
            throw new IOException("unexpected reply to Hello: message type " + type);
        }
        try {
            byte[] whole = new byte[header.length + rest.length];
            System.arraycopy(header, 0, whole, 0, header.length);
            System.arraycopy(rest, 0, whole, header.length, rest.length);
            DBusWire.Message reply = DBusWire.parse(whole);
            if ("s".equals(reply.signature())) {
                uniqueName = new DBusWire.Unmarshaller(reply.body()).readString();
            }
        } catch (IllegalArgumentException e) {
            logger.debug("Could not read the unique name from the Hello reply: {}", e.toString());
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
}
