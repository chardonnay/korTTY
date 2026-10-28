package de.kortty.core;

import com.sithtermfx.core.util.TermSize;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.SerialSettings;
import de.kortty.model.ServerConnection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Connector for a {@link ConnectionProtocol#SERIAL} connection: a terminal on a serial port
 * (a router or switch console, a microcontroller, a modem), like PuTTY's serial sessions.
 *
 * <p>There is no process and no login: bytes typed go out on the line as they are (Enter as the
 * configured {@link SerialSettings.LineEnding}), bytes received are decoded with the connection's
 * encoding. A serial line has no window size, so {@link #resize} does nothing. The session ends
 * when the tab closes or the port disappears (adapter unplugged); the latter counts as a lost
 * connection, so the tab stays open with the reconnect offer.</p>
 */
public class SerialTtyConnector implements ObservableTtyConnector, de.kortty.isolation.IsolationAware {

    private static final Logger logger = LoggerFactory.getLogger(SerialTtyConnector.class);

    /** How long Send Break holds the line low; PuTTY and most consoles use a quarter second. */
    static final int BREAK_MILLIS = 250;

    private static final boolean WINDOWS =
            System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    private final ServerConnection connection;
    /** The line settings frozen at connect time; editing the connection later does not touch an open port. */
    private final SerialSettings settings;
    private final Charset charset;
    private final SerialLink.Opener opener;
    /** Whether the enterprise policy lets korTTY open serial ports; asked on every connect. */
    private final java.util.function.BooleanSupplier policyAllowsSerial;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicBoolean disconnectReported = new AtomicBoolean(false);
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    /** Locally echoed input, handed to the terminal ahead of the next bytes from the line. */
    private final ConcurrentLinkedQueue<byte[]> echoQueue = new ConcurrentLinkedQueue<>();

    private DisconnectListener disconnectListener;
    private final CopyOnWriteArrayList<DataListener> dataListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<InputActivityListener> inputActivityListeners = new CopyOnWriteArrayList<>();
    private volatile InputInterceptor inputInterceptor;

    private volatile SerialLink link;
    private volatile InputStreamReader reader;
    private volatile boolean connectionLost;

    public SerialTtyConnector(ServerConnection connection) {
        this(connection, JSerialCommLink::open);
    }

    SerialTtyConnector(ServerConnection connection, SerialLink.Opener opener) {
        this(connection, opener, () -> de.kortty.policy.PolicyManager.effective().serialConnectionsAllowed());
    }

    SerialTtyConnector(ServerConnection connection, SerialLink.Opener opener,
                       java.util.function.BooleanSupplier policyAllowsSerial) {
        this.policyAllowsSerial = policyAllowsSerial;
        this.connection = connection;
        this.settings = new SerialSettings(connection.getSerialSettings());
        this.charset = TerminalEncodingSupport.resolveFromSettings(connection);
        this.opener = opener;
    }

    @Override
    public ServerConnection getConnection() {
        return connection;
    }

    @Override
    public Charset getCharset() {
        return charset;
    }

    /** The settings this connector opened (or will open) the port with. */
    public SerialSettings getSerialSettings() {
        return new SerialSettings(settings);
    }

    /**
     * Opens the port.
     *
     * @throws IOException with a message for the user when the port does not open or the
     *     organization's policy denies {@code serial-connections}
     */
    public boolean connect() throws IOException {
        if (connection.getProtocol() != ConnectionProtocol.SERIAL) {
            throw new IllegalStateException(i18n("serial.protocolMismatch", i18n("protocol.serial")));
        }
        // Checked here, the one place every way of opening a serial tab passes: the server allowlist
        // cannot cover a port, as it has no host.
        if (!policyAllowsSerial.getAsBoolean()) {
            logger.info("Serial port not opened: the organization's policy denies serial connections");
            throw new IOException(i18n("serial.error.policyDenied"));
        }
        try {
            SerialLink opened = opener.open(settings);
            link = opened;
            reader = new InputStreamReader(new LinkInputStream(opened), charset);
            connectionLost = false;
            disconnectReported.set(false);
            connected.set(true);
            // No connection getter in the log line: see the CodeQL note in SshTtyConnector.connect.
            logger.info("Serial port opened ({})", settings.frameSummary());
            return true;
        } catch (SerialOpenException e) {
            logger.warn("Serial port not opened: {} (code {})", e.reason(), e.errorCode());
            close();
            throw new IOException(describe(e, settings.getPortName(), WINDOWS), e);
        }
    }

    /** The message the user sees for a port that did not open. */
    static String describe(SerialOpenException e, String portName, boolean windows) {
        String port = portName != null ? portName : "";
        return switch (e.reason()) {
            case NO_PORT -> i18n("serial.error.noPort");
            case NOT_FOUND -> i18n("serial.error.notFound", port);
            case BUSY -> i18n("serial.error.busy", port);
            case PERMISSION_DENIED -> windows
                    ? i18n("serial.error.permissionDenied", port)
                    : i18n("serial.error.permissionDenied", port) + " " + i18n("serial.error.permissionHintUnix");
            case SETTINGS_REJECTED -> i18n("serial.error.settingsRejected", port);
            case LIBRARY_UNAVAILABLE -> i18n("serial.error.libraryUnavailable");
            case OTHER -> i18n("serial.error.other", port, e.errorCode());
        };
    }

    /**
     * Holds the line in the break condition for a quarter second, which drops a Cisco console into
     * ROMMON or a Linux console into SysRq. Blocks for that long; call off the JavaFX thread.
     *
     * @return false when the port is closed or the driver does not support break
     */
    public boolean sendBreak() {
        SerialLink current = link;
        if (!connected.get() || current == null) {
            return false;
        }
        try {
            return current.sendBreak(BREAK_MILLIS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    @Override
    public void setDisconnectListener(DisconnectListener disconnectListener) {
        this.disconnectListener = disconnectListener;
    }

    @Override
    public boolean wasConnectionLost() {
        return connectionLost;
    }

    @Override
    public void setDataListener(DataListener listener) {
        dataListeners.clear();
        if (listener != null) {
            dataListeners.add(listener);
        }
    }

    @Override
    public void addDataListener(DataListener listener) {
        if (listener != null) {
            dataListeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeDataListener(DataListener listener) {
        if (listener != null) {
            dataListeners.remove(listener);
        }
    }

    @Override
    public void addInputActivityListener(InputActivityListener listener) {
        if (listener != null) {
            inputActivityListeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeInputActivityListener(InputActivityListener listener) {
        if (listener != null) {
            inputActivityListeners.remove(listener);
        }
    }

    @Override
    public void setInputInterceptor(InputInterceptor inputInterceptor) {
        this.inputInterceptor = inputInterceptor;
    }

    @Override
    public int read(char[] buf, int offset, int length) throws IOException {
        InputStreamReader localReader = reader;
        if (!connected.get() || localReader == null) {
            return -1;
        }
        int count;
        try {
            count = localReader.read(buf, offset, length);
        } catch (IOException e) {
            if (!connected.get()) {
                return -1;
            }
            throw e;
        }
        if (count < 0) {
            portGone();
            return -1;
        }
        if (count > 0) {
            notifyDataRead(new String(buf, offset, count));
        }
        return count;
    }

    /** The line ended while the tab still wanted it: the adapter was unplugged or the driver failed. */
    private void portGone() {
        if (!connected.getAndSet(false)) {
            return;
        }
        connectionLost = true;
        SerialLink current = link;
        if (current != null) {
            current.close();
        }
        closedLatch.countDown();
        logger.info("Serial port went away");
        DisconnectListener listener = disconnectListener;
        if (listener != null && disconnectReported.compareAndSet(false, true)) {
            listener.onDisconnect(i18n("serial.disconnectLost", String.valueOf(settings.getPortName())), true);
        }
    }

    private void notifyDataRead(String data) {
        for (DataListener dataListener : dataListeners) {
            try {
                dataListener.onData(data);
            } catch (Exception e) {
                logger.warn("Data listener error: {}", e.getMessage());
            }
        }
    }

    @Override
    public void write(byte[] bytes) throws IOException {
        SerialLink current = link;
        if (!connected.get() || current == null || bytes == null) {
            return;
        }
        byte[] bytesToWrite = applyInputInterceptor(bytes);
        if (bytesToWrite == null || bytesToWrite.length == 0) {
            return;
        }
        notifyInputActivity(bytesToWrite.length);
        byte[] line = translateLineEndings(bytesToWrite, settings.getLineEnding());
        if (settings.isLocalEcho()) {
            echoQueue.add(localEcho(bytesToWrite));
        }
        try {
            current.write(line);
        } catch (IOException e) {
            if (connected.get()) {
                portGone();
            }
            throw e;
        }
    }

    /** Enter arrives as CR; the device may want LF or CR LF instead. Other bytes pass unchanged. */
    static byte[] translateLineEndings(byte[] bytes, SerialSettings.LineEnding lineEnding) {
        if (lineEnding == SerialSettings.LineEnding.CR) {
            return bytes;
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 8);
        for (byte b : bytes) {
            if (b == '\r') {
                if (lineEnding == SerialSettings.LineEnding.CRLF) {
                    out.write('\r');
                }
                out.write('\n');
            } else {
                out.write(b);
            }
        }
        return out.toByteArray();
    }

    /** What local echo shows for typed bytes: Enter moves to the start of the next line. */
    static byte[] localEcho(byte[] bytes) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(bytes.length + 4);
        for (byte b : bytes) {
            out.write(b);
            if (b == '\r') {
                out.write('\n');
            }
        }
        return out.toByteArray();
    }

    @Override
    public void write(String string) throws IOException {
        if (string == null) {
            return;
        }
        write(string.getBytes(charset));
    }

    private byte[] applyInputInterceptor(byte[] bytes) throws IOException {
        InputInterceptor interceptor = inputInterceptor;
        if (interceptor == null || bytes.length == 0) {
            return bytes;
        }
        return interceptor.intercept(bytes);
    }

    private void notifyInputActivity(int byteCount) {
        for (InputActivityListener listener : inputActivityListeners) {
            try {
                listener.onInputActivity(byteCount);
            } catch (Exception e) {
                logger.warn("Input activity listener error: {}", e.getMessage());
            }
        }
    }

    @Override
    public boolean isConnected() {
        return connected.get();
    }

    @Override
    public int waitFor() throws InterruptedException {
        closedLatch.await();
        return connectionLost ? 1 : 0;
    }

    @Override
    public boolean ready() throws IOException {
        SerialLink current = link;
        return connected.get() && (!echoQueue.isEmpty() || (current != null && current.available() > 0));
    }

    @Override
    public void resize(TermSize termSize) {
        // A serial line has no window size to tell the other end.
    }

    @Override
    public String getName() {
        return connection.getDisplayName();
    }

    @Override
    public de.kortty.isolation.IsolationReport isolationReport() {
        return de.kortty.isolation.IsolationReport.NONE;
    }

    @Override
    public void close() {
        connected.set(false);
        SerialLink current = link;
        link = null;
        if (current != null) {
            current.close();
        }
        // The reader holds nothing of its own, and closing it would wait for the lock a terminal
        // thread blocked in read() holds; dropping it is enough once the link is closed.
        reader = null;
        echoQueue.clear();
        closedLatch.countDown();
    }

    /**
     * The bytes the terminal reads: locally echoed input first, then the line. Blocks until there
     * is at least one byte, the port is gone (-1) or the connector closed (-1).
     */
    private final class LinkInputStream extends InputStream {

        private final SerialLink source;
        private byte[] pendingEcho;
        private int pendingEchoOffset;

        LinkInputStream(SerialLink source) {
            this.source = source;
        }

        @Override
        public int read() throws IOException {
            byte[] one = new byte[1];
            int count = read(one, 0, 1);
            return count < 0 ? -1 : one[0] & 0xFF;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            byte[] scratch = offset == 0 ? buffer : new byte[length];
            while (true) {
                int echoed = drainEcho(buffer, offset, length);
                if (echoed > 0) {
                    return echoed;
                }
                if (!connected.get()) {
                    return -1;
                }
                int count = source.read(scratch, length);
                if (count < 0) {
                    return -1;
                }
                if (count > 0) {
                    if (scratch != buffer) {
                        System.arraycopy(scratch, 0, buffer, offset, count);
                    }
                    return count;
                }
            }
        }

        private int drainEcho(byte[] buffer, int offset, int length) {
            if (pendingEcho == null) {
                pendingEcho = echoQueue.poll();
                pendingEchoOffset = 0;
                if (pendingEcho == null) {
                    return 0;
                }
            }
            int count = Math.min(length, pendingEcho.length - pendingEchoOffset);
            System.arraycopy(pendingEcho, pendingEchoOffset, buffer, offset, count);
            pendingEchoOffset += count;
            if (pendingEchoOffset >= pendingEcho.length) {
                pendingEcho = null;
            }
            return count;
        }

        @Override
        public int available() {
            return source.available();
        }
    }

    private static String i18n(String key, Object... args) {
        try {
            LanguageManager lm = LanguageManager.getInstance();
            if (lm != null) {
                String s = args == null || args.length == 0 ? lm.getString(key) : lm.getString(key, args);
                if (s != null && !s.isEmpty() && !s.equals(key)) {
                    return s;
                }
            }
        } catch (RuntimeException ignored) {
        }
        if (args == null || args.length == 0) {
            return key;
        }
        StringBuilder sb = new StringBuilder(key);
        for (Object arg : args) {
            sb.append(' ').append(arg);
        }
        return sb.toString();
    }
}
