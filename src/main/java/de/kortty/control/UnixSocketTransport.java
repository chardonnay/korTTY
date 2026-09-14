package de.kortty.control;

import java.io.IOException;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.EnumSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The POSIX {@code AF_UNIX} listener at {@code ~/.kortty/control/control.sock}.
 *
 * <p>Any thread, never the JavaFX application thread; one thread owns {@link #accept()}.
 *
 * <p>The 0700 parent directory is the credential here — the JDK's unix-domain socket API exposes no
 * {@code SO_PEERCRED} — and the bearer token is defence in depth on top of it. {@link #close()}
 * unlinks the socket only when this instance actually bound it, so a start refused because another
 * korTTY owns the path deletes nothing.
 */
public final class UnixSocketTransport implements ControlApiTransport {

    private static final Logger LOG = LoggerFactory.getLogger(UnixSocketTransport.class);

    private static final Set<PosixFilePermission> OWNER_READ_WRITE = EnumSet.of(
        PosixFilePermission.OWNER_READ,
        PosixFilePermission.OWNER_WRITE);

    private final Path socketPath;

    private volatile ServerSocketChannel server;

    private volatile boolean bound;

    /**
     * @param socketPath the socket file to create; its parent must already exist and have been
     *     verified by {@link ControlDirectory}
     */
    public UnixSocketTransport(Path socketPath) {
        this.socketPath = Objects.requireNonNull(socketPath, "socketPath");
    }

    @Override
    public String kind() {
        return EndpointDescriptor.TRANSPORT_UNIX;
    }

    /** The socket file this transport binds, whether or not it is bound yet. */
    public Path socketPath() {
        return socketPath;
    }

    @Override
    public EndpointDescriptor bind() throws IOException, ControlApiException {
        clearLeftover();
        ServerSocketChannel channel = ServerSocketChannel.open(StandardProtocolFamily.UNIX);
        try {
            channel.bind(UnixDomainSocketAddress.of(socketPath));
        } catch (IOException | RuntimeException e) {
            closeQuietly(channel);
            throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                "Cannot bind the control socket " + socketPath + ": " + e.getMessage(), e);
        }
        server = channel;
        bound = true;
        tighten();
        LOG.info("control API listening on unix:{}", socketPath);
        return new EndpointDescriptor(EndpointDescriptor.TRANSPORT_UNIX, socketPath.toString(), null, 0,
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
        if (channel != null) {
            closeQuietly(channel);
        }
        if (bound) {
            bound = false;
            try {
                Files.deleteIfExists(socketPath);
            } catch (IOException e) {
                LOG.debug("control-api: cannot unlink {}", socketPath, e);
            }
        }
    }

    private void clearLeftover() throws IOException, ControlApiException {
        StaleSocketProbe.Verdict verdict = StaleSocketProbe.classify(socketPath);
        switch (verdict) {
            case NONE -> {
                // Nothing to clear.
            }
            case STALE -> {
                LOG.info("control-api: unlinking the stale control socket {}", socketPath);
                Files.deleteIfExists(socketPath);
            }
            case LIVE -> throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                "Another korTTY instance already owns " + socketPath,
                Map.of("reason", "socket_in_use", "path", socketPath.toString()));
            case FOREIGN -> throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                socketPath + " exists and is not a korTTY control socket",
                Map.of("reason", "socket_foreign", "path", socketPath.toString()));
        }
    }

    private void tighten() {
        try {
            Files.setPosixFilePermissions(socketPath, OWNER_READ_WRITE);
        } catch (UnsupportedOperationException | IOException e) {
            LOG.debug("control-api: cannot tighten the permissions of {}", socketPath, e);
        }
    }

    private static void closeQuietly(ServerSocketChannel channel) {
        try {
            channel.close();
        } catch (IOException e) {
            LOG.debug("control-api: closing the listener failed", e);
        }
    }
}
