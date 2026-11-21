package de.kortty.core;

import com.sithtermfx.core.util.TermSize;
import de.kortty.isolation.IsolationAware;
import de.kortty.isolation.IsolationReport;
import de.kortty.model.ServerConnection;
import de.kortty.policy.EffectivePolicy;
import de.kortty.policy.PolicyManager;
import de.kortty.policy.PolicyRestrictionException;
import org.apache.commons.net.telnet.EchoOptionHandler;
import org.apache.commons.net.telnet.InvalidTelnetOptionException;
import org.apache.commons.net.telnet.SimpleOptionHandler;
import org.apache.commons.net.telnet.SuppressGAOptionHandler;
import org.apache.commons.net.telnet.TelnetClient;
import org.apache.commons.net.telnet.TelnetOption;
import org.apache.commons.net.telnet.TelnetOptionHandler;
import org.apache.commons.net.telnet.TerminalTypeOptionHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Interactive Telnet transport. Credentials entered in the terminal are application data. */
public final class TelnetTtyConnector implements ObservableTtyConnector, IsolationAware {

    private static final Logger logger = LoggerFactory.getLogger(TelnetTtyConnector.class);

    private enum State { NEW, CONNECTING, CONNECTED, LOST, CLOSED }

    private final ServerConnection connection;
    private final Charset charset;
    private final Supplier<EffectivePolicy> policy;
    private final Client client = new Client();
    private final WindowSizeOption windowSize = new WindowSizeOption();
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    private final CountDownLatch closed = new CountDownLatch(1);
    private final CopyOnWriteArrayList<DataListener> dataListeners = new CopyOnWriteArrayList<>();
    private final CopyOnWriteArrayList<InputActivityListener> inputListeners = new CopyOnWriteArrayList<>();
    private volatile DisconnectListener disconnectListener;
    private volatile InputInterceptor inputInterceptor;
    private InputStreamReader reader;
    private OutputStream output;

    public TelnetTtyConnector(ServerConnection connection) {
        this(connection, PolicyManager::effective);
    }

    TelnetTtyConnector(ServerConnection connection, Supplier<EffectivePolicy> policy) {
        this.connection = connection;
        this.policy = policy;
        charset = TerminalEncodingSupport.resolveFromSettings(connection);
    }

    public boolean connect() throws IOException {
        if (!connection.isTelnet()) {
            throw new IllegalArgumentException("Telnet profile required");
        }
        String host = connection.getHost();
        int port = connection.getPort();
        if (host == null || host.isBlank() || port < 1 || port > 65535) {
            throw new IllegalStateException(message("telnet.endpointRequired"));
        }
        EffectivePolicy effectivePolicy = policy.get();
        if (!effectivePolicy.telnetConnectionsAllowed()) {
            throw new PolicyRestrictionException(message("telnet.policyDenied"));
        }
        if (!effectivePolicy.isServerAllowed(host, port)) {
            throw new PolicyRestrictionException(message("policy.server.blocked.message", host + ":" + port));
        }
        if (!state.compareAndSet(State.NEW, State.CONNECTING)) {
            return false;
        }
        try {
            client.setConnectTimeout(Math.clamp(connection.getConnectionTimeoutSeconds(), 1, 300) * 1000);
            configureOptions();
            client.connect(host, port);
            reader = new InputStreamReader(client.getInputStream(), charset);
            output = client.getOutputStream();
            if (state.compareAndSet(State.CONNECTING, State.CONNECTED)) {
                return true;
            }
            disconnectClient();
            return false;
        } catch (IOException | RuntimeException e) {
            state.compareAndSet(State.CONNECTING, State.CLOSED);
            disconnectClient();
            closed.countDown();
            throw e;
        }
    }

    private void configureOptions() {
        try {
            client.addOptionHandler(new TerminalTypeOptionHandler(
                TerminalEmulationSupport.termName(connection).toUpperCase(Locale.ROOT), false, false, true, false));
            client.addOptionHandler(new EchoOptionHandler(false, true, false, true));
            client.addOptionHandler(new SuppressGAOptionHandler(true, true, true, true));
            // Commons Net uses the requested mode before the peer acknowledges it. Keep NVT
            // line handling active for peers that ignore options, and accept BINARY when offered.
            client.addOptionHandler(new SimpleOptionHandler(TelnetOption.BINARY, false, false, true, true));
            client.addOptionHandler(windowSize);
        } catch (InvalidTelnetOptionException | IOException e) {
            throw new IllegalStateException("Could not configure Telnet options", e);
        }
    }

    @Override
    public int read(char[] buffer, int offset, int length) throws IOException {
        if (!isConnected()) {
            return -1;
        }
        int count;
        try {
            count = reader.read(buffer, offset, length);
        } catch (IOException e) {
            connectionEnded();
            return -1;
        }
        if (count == -1) {
            connectionEnded();
        } else if (count > 0) {
            String data = new String(buffer, offset, count);
            notifyListeners(dataListeners, listener -> listener.onData(data));
        }
        return count;
    }

    @Override
    public void write(byte[] data) throws IOException {
        if (!isConnected() || data == null || data.length == 0) {
            return;
        }
        InputInterceptor interceptor = inputInterceptor;
        byte[] bytes = interceptor != null ? interceptor.intercept(data) : data;
        if (bytes == null || bytes.length == 0) {
            return;
        }
        notifyListeners(inputListeners, listener -> listener.onInputActivity(bytes.length));
        try {
            synchronized (client) {
                // The widget sends CR for Enter. Supply its LF in the same write so the NVT
                // stream does not defer it until another key is pressed.
                for (int i = 0; i < bytes.length; i++) {
                    int value = bytes[i] & 0xff;
                    output.write(value);
                    if (value == '\r' && (i + 1 == bytes.length || bytes[i + 1] != '\n')) {
                        output.write('\n');
                    }
                }
                output.flush();
            }
        } catch (IOException e) {
            connectionEnded();
            throw e;
        }
    }

    @Override
    public void write(String text) throws IOException {
        if (text != null) {
            write(text.getBytes(charset));
        }
    }

    @Override
    public void resize(TermSize size) {
        try {
            synchronized (client) {
                if (windowSize.update(size) && isConnected() && client.getLocalOptionState(TelnetOption.WINDOW_SIZE)) {
                    client.sendSubnegotiation(windowSize.startSubnegotiationLocal());
                }
            }
        } catch (IOException e) {
            connectionEnded();
        }
    }

    private void connectionEnded() {
        if (state.compareAndSet(State.CONNECTED, State.LOST)) {
            disconnectClient();
            closed.countDown();
            DisconnectListener listener = disconnectListener;
            if (listener != null) {
                listener.onDisconnect(message("telnet.disconnected"), true);
            }
        }
    }

    @Override
    public void close() {
        State previous = state.getAndUpdate(current -> current == State.LOST ? State.LOST : State.CLOSED);
        if (previous == State.CONNECTING) {
            client.closeSocket();
        } else if (previous == State.CONNECTED) {
            disconnectClient();
        }
        closed.countDown();
    }

    private void disconnectClient() {
        client.closeSocket();
        try {
            client.disconnect();
        } catch (IOException e) {
            logger.debug("Closing Telnet session failed", e);
        }
    }

    @Override
    public boolean isConnected() {
        return state.get() == State.CONNECTED;
    }

    @Override
    public boolean wasConnectionLost() {
        return state.get() == State.LOST;
    }

    @Override
    public boolean ready() throws IOException {
        return isConnected() && reader.ready();
    }

    @Override
    public int waitFor() throws InterruptedException {
        closed.await();
        return wasConnectionLost() ? 1 : 0;
    }

    @Override
    public ServerConnection getConnection() {
        return connection;
    }

    @Override
    public Charset getCharset() {
        return charset;
    }

    @Override
    public String getName() {
        return connection.getDisplayName();
    }

    @Override
    public IsolationReport isolationReport() {
        return IsolationReport.NONE;
    }

    @Override
    public void setDisconnectListener(DisconnectListener listener) {
        disconnectListener = listener;
    }

    @Override
    public void setDataListener(DataListener listener) {
        dataListeners.clear();
        addDataListener(listener);
    }

    @Override
    public void addDataListener(DataListener listener) {
        if (listener != null) {
            dataListeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeDataListener(DataListener listener) {
        dataListeners.remove(listener);
    }

    @Override
    public void addInputActivityListener(InputActivityListener listener) {
        if (listener != null) {
            inputListeners.addIfAbsent(listener);
        }
    }

    @Override
    public void removeInputActivityListener(InputActivityListener listener) {
        inputListeners.remove(listener);
    }

    @Override
    public void setInputInterceptor(InputInterceptor interceptor) {
        inputInterceptor = interceptor;
    }

    private static <T> void notifyListeners(Iterable<T> listeners, Consumer<T> event) {
        for (T listener : listeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException e) {
                logger.warn("Telnet terminal listener failed", e);
            }
        }
    }

    private static String message(String key, Object... args) {
        return LanguageManager.getInstance().getString(key, args);
    }

    /** Keep the latest viewport for negotiation and send changes only while NAWS is enabled. */
    private static final class WindowSizeOption extends TelnetOptionHandler {
        private int columns = 80;
        private int rows = 24;

        private WindowSizeOption() {
            super(TelnetOption.WINDOW_SIZE, true, false, true, false);
        }

        boolean update(TermSize size) {
            int width = Math.clamp(size.getColumns(), 0, 65535);
            int height = Math.clamp(size.getRows(), 0, 65535);
            boolean changed = width != columns || height != rows;
            columns = width;
            rows = height;
            return changed;
        }

        @Override
        public int[] startSubnegotiationLocal() {
            // Commons Net doubles IAC bytes when framing subnegotiation.
            return new int[] {TelnetOption.WINDOW_SIZE, columns >>> 8, columns & 0xff, rows >>> 8, rows & 0xff};
        }
    }

    private static final class Client extends TelnetClient {
        void closeSocket() {
            Socket socket = _socket_;
            if (socket != null) {
                try {
                    socket.close();
                } catch (IOException e) {
                    logger.debug("Closing Telnet socket failed", e);
                }
            }
        }
    }
}
