package de.kortty.core.worker;

import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.future.DefaultOpenFuture;
import org.apache.sshd.client.future.OpenFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.channel.ChannelOutputStream;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.channel.AbstractServerChannel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.function.Supplier;

/**
 * A TCP channel the worker receives on one side and opens again on the other, copying the bytes both
 * ways; an end of file or a close on either side ends the other.
 *
 * <ul>
 *   <li>{@code direct-tcpip} on the loopback endpoint (korTTY's {@code -L} and {@code -D} tunnels)
 *       becomes a {@code direct-tcpip} channel on the upstream server, so the connection is made from
 *       the server, as with a direct session.</li>
 *   <li>{@code forwarded-tcpip} from the upstream server (a connection to the port of one of korTTY's
 *       {@code -R} tunnels) becomes a {@code forwarded-tcpip} channel to korTTY, which connects the
 *       tunnel's local target itself — the worker opens no connection of its own for it.</li>
 * </ul>
 *
 * Both open messages carry the same four fields (a host, a port, the originator's host and port), so
 * one class serves both directions.
 */
final class TcpipRelayChannel extends AbstractServerChannel {

    private static final Logger logger = LoggerFactory.getLogger(TcpipRelayChannel.class);

    /** Opens the channel on the other side for the fields of the incoming open message. */
    @FunctionalInterface
    interface Opener {
        ChannelDirectTcpip open(SshdSocketAddress target, SshdSocketAddress originator) throws IOException;
    }

    /** Relays korTTY's {@code direct-tcpip} channels to the upstream session the supplier returns. */
    static ChannelFactory directFactory(Supplier<ClientSession> upstream) {
        return factory("direct-tcpip", (target, originator) -> {
            ClientSession session = upstream.get();
            if (session == null || !session.isOpen()) {
                throw new IOException("upstream session is closed");
            }
            return session.createDirectTcpipChannel(originator, target);
        });
    }

    /** Relays the server's {@code forwarded-tcpip} channels to korTTY's session for the forwarded port. */
    static ChannelFactory forwardedFactory(RemoteForwards forwards) {
        return factory("forwarded-tcpip", forwards::openTowardsKortty);
    }

    private static ChannelFactory factory(String type, Opener opener) {
        return new ChannelFactory() {
            @Override
            public String getName() {
                return type;
            }

            @Override
            public Channel createChannel(Session session) {
                return new TcpipRelayChannel(type, opener);
            }
        };
    }

    private final String type;
    private final Opener opener;
    private volatile ChannelDirectTcpip otherChannel;
    private volatile OutputStream toOther;

    private TcpipRelayChannel(String type, Opener opener) {
        super(type, java.util.List.of(), null);
        this.type = type;
        this.opener = opener;
    }

    @Override
    protected OpenFuture doInit(Buffer buffer) {
        DefaultOpenFuture future = new DefaultOpenFuture(this, futureLock);
        try {
            String host = buffer.getString();
            int port = buffer.getInt();
            String originatorHost = buffer.getString();
            int originatorPort = buffer.getInt();
            ChannelDirectTcpip channel = opener.open(new SshdSocketAddress(host, port),
                new SshdSocketAddress(originatorHost, originatorPort));
            otherChannel = channel;
            channel.open().addListener(opened -> {
                try {
                    if (!opened.isOpened()) {
                        Throwable failure = opened.getException();
                        fail(future, failure != null ? failure : new IOException("the other side refused the channel"));
                        return;
                    }
                    toOther = channel.getInvertedIn();
                    startPump(channel.getInvertedOut());
                    // The connection service sends the open confirmation on this signal.
                    signalChannelOpenSuccess();
                    future.setOpened();
                } catch (RuntimeException e) {
                    logger.warn("Relaying {} failed: {}", type, e.toString(), e);
                    fail(future, e);
                }
            });
        } catch (Exception e) {
            fail(future, e);
        }
        return future;
    }

    private void fail(DefaultOpenFuture future, Throwable failure) {
        signalChannelOpenFailure(failure);
        future.setException(failure);
    }

    /** Copies what the other side sends to this channel until it ends. */
    private void startPump(InputStream fromOther) {
        Thread pump = new Thread(() -> {
            byte[] chunk = new byte[32 * 1024];
            try (OutputStream toThis = new ChannelOutputStream(this, getRemoteWindow(), logger,
                    SshConstants.SSH_MSG_CHANNEL_DATA, true)) {
                int count;
                while ((count = fromOther.read(chunk)) >= 0) {
                    if (count > 0) {
                        toThis.write(chunk, 0, count);
                        toThis.flush();
                    }
                }
            } catch (IOException e) {
                logger.debug("Relay of {} ended: {}", type, e.getMessage());
            } finally {
                close(false);
            }
        }, "Relay-" + type);
        pump.setDaemon(true);
        pump.start();
    }

    @Override
    protected void doWriteData(byte[] data, int off, long len) throws IOException {
        OutputStream target = toOther;
        if (target == null) {
            throw new IOException("the other channel is not open");
        }
        target.write(data, off, (int) len);
        target.flush();
        getLocalWindow().release(len);
    }

    @Override
    protected void doWriteExtendedData(byte[] data, int off, long len) throws IOException {
        throw new IOException(type + " carries no extended data");
    }

    @Override
    public void handleEof() throws IOException {
        super.handleEof();
        OutputStream target = toOther;
        if (target != null) {
            target.close();
        }
    }

    @Override
    protected void preClose() {
        ChannelDirectTcpip channel = otherChannel;
        if (channel != null) {
            channel.close(false);
        }
        super.preClose();
    }
}
