package de.kortty.control;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Objects;
import java.util.function.IntSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The Windows fallback: a TCP listener on {@code 127.0.0.1} whose only credential is the bearer
 * token in {@code endpoint.json}.
 *
 * <p>Any thread, never the JavaFX application thread; one thread owns {@link #accept()}.
 *
 * <p>The bind host is a constant and there is no knob that can widen it. The port is bound
 * <strong>directly</strong> — normally port 0, then the actually-bound port is read back — rather
 * than probed-and-released the way the AI sidecar launchers allocate a port for a child process:
 * probe-then-release leaves a TOCTOU window in which an attacker can squat the port before the
 * server binds it.
 */
public final class LoopbackTokenTransport implements ControlApiTransport {

    private static final Logger LOG = LoggerFactory.getLogger(LoopbackTokenTransport.class);

    /** The only address this transport ever binds. */
    public static final String LOOPBACK_HOST = "127.0.0.1";

    private final IntSupplier requestedPort;

    private volatile ServerSocketChannel server;

    private volatile int port;

    /**
     * @param requestedPort the port to bind; {@code () -> 0} asks the OS to assign one, which is what
     *     production uses. Injected so a test can pin a port.
     */
    public LoopbackTokenTransport(IntSupplier requestedPort) {
        this.requestedPort = Objects.requireNonNull(requestedPort, "requestedPort");
    }

    @Override
    public String kind() {
        return EndpointDescriptor.TRANSPORT_LOOPBACK;
    }

    /** The bound port, or 0 before {@link #bind()} succeeded. */
    public int port() {
        return port;
    }

    @Override
    public EndpointDescriptor bind() throws IOException, ControlApiException {
        int wanted = requestedPort.getAsInt();
        if (wanted < 0 || wanted > 65_535) {
            throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                "A control-API port must be between 0 and 65535, not " + wanted);
        }
        ServerSocketChannel channel = ServerSocketChannel.open();
        try {
            channel.bind(new InetSocketAddress(LOOPBACK_HOST, wanted));
            port = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        } catch (IOException | RuntimeException e) {
            closeQuietly(channel);
            throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                "Cannot bind the control listener on " + LOOPBACK_HOST + ":" + wanted + ": " + e.getMessage(), e);
        }
        server = channel;
        LOG.info("control API listening on loopback:{}:{}", LOOPBACK_HOST, port);
        return new EndpointDescriptor(EndpointDescriptor.TRANSPORT_LOOPBACK, null, LOOPBACK_HOST, port,
            null, 0L, null, ControlApiProtocol.PROTOCOL_VERSION, null, 0L);
    }

    @Override
    public SocketChannel accept() throws IOException {
        ServerSocketChannel channel = server;
        if (channel == null) {
            throw new ClosedChannelException();
        }
        return channel.accept();
    }

    @Override
    public void close() {
        ServerSocketChannel channel = server;
        server = null;
        port = 0;
        if (channel != null) {
            closeQuietly(channel);
        }
    }

    private static void closeQuietly(ServerSocketChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            LOG.debug("control-api: closing the loopback listener failed", e);
        }
    }
}
