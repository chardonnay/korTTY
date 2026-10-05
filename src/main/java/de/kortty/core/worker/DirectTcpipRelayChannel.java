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
 * A {@code direct-tcpip} channel of the worker's loopback endpoint (korTTY's {@code -L} and
 * {@code -D} tunnels) that is really a {@code direct-tcpip} channel on the upstream server, so the
 * connection is made from the server, as with a direct session. The bytes are copied both ways; an
 * end of file or a close on either side ends the other.
 */
final class DirectTcpipRelayChannel extends AbstractServerChannel {

    private static final Logger logger = LoggerFactory.getLogger(DirectTcpipRelayChannel.class);

    /** Creates relay channels on the upstream session the supplier returns at the time of the open. */
    static ChannelFactory factory(Supplier<ClientSession> upstream) {
        return new ChannelFactory() {
            @Override
            public String getName() {
                return "direct-tcpip";
            }

            @Override
            public Channel createChannel(Session session) {
                return new DirectTcpipRelayChannel(upstream.get());
            }
        };
    }

    private final ClientSession upstream;
    private volatile ChannelDirectTcpip upstreamChannel;
    private volatile OutputStream toUpstream;

    private DirectTcpipRelayChannel(ClientSession upstream) {
        super("direct-tcpip", java.util.List.of(), null);
        this.upstream = upstream;
    }

    @Override
    protected OpenFuture doInit(Buffer buffer) {
        DefaultOpenFuture future = new DefaultOpenFuture(this, futureLock);
        try {
            String host = buffer.getString();
            int port = buffer.getInt();
            String originatorHost = buffer.getString();
            int originatorPort = buffer.getInt();
            if (upstream == null || !upstream.isOpen()) {
                throw new IOException("upstream session is closed");
            }
            ChannelDirectTcpip channel = upstream.createDirectTcpipChannel(
                new SshdSocketAddress(originatorHost, originatorPort), new SshdSocketAddress(host, port));
            upstreamChannel = channel;
            channel.open().addListener(opened -> {
                try {
                    if (!opened.isOpened()) {
                        Throwable failure = opened.getException();
                        fail(future, failure != null ? failure : new IOException("upstream refused the channel"));
                        return;
                    }
                    toUpstream = channel.getInvertedIn();
                    startPump(channel.getInvertedOut());
                    // The connection service sends the open confirmation on this signal.
                    signalChannelOpenSuccess();
                    future.setOpened();
                } catch (RuntimeException e) {
                    logger.warn("Relaying direct-tcpip failed: {}", e.toString(), e);
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

    /** Copies what the upstream end sends to korTTY's end until it ends. */
    private void startPump(InputStream fromUpstream) {
        Thread pump = new Thread(() -> {
            byte[] chunk = new byte[32 * 1024];
            try (OutputStream toDownstream = new ChannelOutputStream(this, getRemoteWindow(), logger,
                    SshConstants.SSH_MSG_CHANNEL_DATA, true)) {
                int count;
                while ((count = fromUpstream.read(chunk)) >= 0) {
                    if (count > 0) {
                        toDownstream.write(chunk, 0, count);
                        toDownstream.flush();
                    }
                }
            } catch (IOException e) {
                logger.debug("Relay to the loopback endpoint ended: {}", e.getMessage());
            } finally {
                close(false);
            }
        }, "Relay-direct-tcpip");
        pump.setDaemon(true);
        pump.start();
    }

    @Override
    protected void doWriteData(byte[] data, int off, long len) throws IOException {
        OutputStream target = toUpstream;
        if (target == null) {
            throw new IOException("upstream channel not open");
        }
        target.write(data, off, (int) len);
        target.flush();
        getLocalWindow().release(len);
    }

    @Override
    protected void doWriteExtendedData(byte[] data, int off, long len) throws IOException {
        throw new IOException("direct-tcpip carries no extended data");
    }

    @Override
    public void handleEof() throws IOException {
        super.handleEof();
        OutputStream target = toUpstream;
        if (target != null) {
            target.close();
        }
    }

    @Override
    protected void preClose() {
        ChannelDirectTcpip channel = upstreamChannel;
        if (channel != null) {
            channel.close(false);
        }
        super.preClose();
    }
}
