package de.kortty.core.worker;

import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.SshConstants;
import org.apache.sshd.common.channel.RequestHandler;
import org.apache.sshd.common.session.ConnectionService;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.helpers.AbstractConnectionServiceRequestHandler;
import org.apache.sshd.common.util.buffer.Buffer;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * korTTY's remote tunnels ({@code -R}) through a session worker. korTTY asks the worker's loopback
 * endpoint for {@code tcpip-forward} as it would ask a server; the worker passes the request on to the
 * real server unchanged and answers with the server's reply, so the bound port korTTY learns is the
 * server's. When someone connects to that port, the server opens a {@code forwarded-tcpip} channel to
 * the worker, which opens one to the korTTY session that asked for the port
 * ({@link TcpipRelayChannel#forwardedFactory}); korTTY connects the tunnel's local target itself, with
 * its own confirmation, Teamwork and policy rules, exactly as for a direct session. The worker needs no
 * network of its own for it, so its sandbox stays as narrow as before.
 *
 * <p>Requests are answered in order, as global requests must be: each one waits for the server's reply
 * before the next request of the same session is read.
 */
final class RemoteForwards extends AbstractConnectionServiceRequestHandler {

    private static final Logger logger = LoggerFactory.getLogger(RemoteForwards.class);

    static final String FORWARD = "tcpip-forward";
    static final String CANCEL = "cancel-tcpip-forward";
    private static final Duration REPLY_TIMEOUT = Duration.ofSeconds(30);

    private final Supplier<ClientSession> upstream;
    /** The korTTY session on the endpoint that asked for each port bound on the server. */
    private final Map<Integer, Session> askedBy = new ConcurrentHashMap<>();

    RemoteForwards(Supplier<ClientSession> upstream) {
        this.upstream = upstream;
    }

    @Override
    public RequestHandler.Result process(ConnectionService service, String request, boolean wantReply,
            Buffer buffer) throws Exception {
        if (!FORWARD.equals(request) && !CANCEL.equals(request)) {
            return super.process(service, request, wantReply, buffer);
        }
        String host = buffer.getString();
        int port = buffer.getInt();
        Session downstream = service.getSession();
        Buffer reply = passOn(request, host, port);
        if (reply == null) {
            return wantReply ? RequestHandler.Result.ReplyFailure : RequestHandler.Result.Replied;
        }
        if (CANCEL.equals(request)) {
            askedBy.remove(port);
            return wantReply ? RequestHandler.Result.ReplySuccess : RequestHandler.Result.Replied;
        }
        // The server names the port it chose only when port 0 was asked for.
        int bound = port != 0 ? port : reply.available() >= Integer.BYTES ? reply.getInt() : 0;
        if (bound <= 0) {
            logger.warn("The server bound a remote tunnel without naming its port");
            return wantReply ? RequestHandler.Result.ReplyFailure : RequestHandler.Result.Replied;
        }
        askedBy.put(bound, downstream);
        logger.info("Remote tunnel on server port {} opened", bound);
        if (wantReply) {
            Buffer success = downstream.createBuffer(SshConstants.SSH_MSG_REQUEST_SUCCESS, Integer.BYTES);
            if (port == 0) {
                success.putUInt(bound);
            }
            downstream.writePacket(success);
        }
        return RequestHandler.Result.Replied;
    }

    /** Sends the request to the server and waits for its reply: the reply's payload, or null if refused. */
    private Buffer passOn(String request, String host, int port) {
        ClientSession session = upstream.get();
        if (session == null || !session.isOpen()) {
            return null;
        }
        try {
            Buffer buffer = session.createBuffer(SshConstants.SSH_MSG_GLOBAL_REQUEST);
            buffer.putString(request);
            buffer.putBoolean(true);
            buffer.putString(host);
            buffer.putUInt(port);
            return session.request(request, buffer, REPLY_TIMEOUT);
        } catch (IOException | RuntimeException e) {
            logger.warn("The server did not answer {}: {}", request, e.toString());
            return null;
        }
    }

    /**
     * The channel to korTTY for a connection the server delivered to {@code target}'s port, registered on
     * the session that asked for the port and not yet opened.
     */
    ChannelDirectTcpip openTowardsKortty(SshdSocketAddress target, SshdSocketAddress originator) throws IOException {
        Session downstream = askedBy.get(target.getPort());
        if (downstream == null || !downstream.isOpen()) {
            throw new IOException("no remote tunnel on port " + target.getPort());
        }
        ConnectionService service = downstream.getService(ConnectionService.class);
        if (service == null) {
            throw new IOException("korTTY's session has no connection service");
        }
        ForwardedChannel channel = new ForwardedChannel(originator, target);
        service.registerChannel(channel);
        return channel;
    }

    /**
     * A {@code forwarded-tcpip} channel opened towards korTTY. Its open message has the layout of
     * {@code direct-tcpip} (connected host and port, originator host and port), so only the type differs.
     */
    private static final class ForwardedChannel extends ChannelDirectTcpip {
        ForwardedChannel(SshdSocketAddress originator, SshdSocketAddress connected) {
            super(originator, connected);
        }

        @Override
        public String getChannelType() {
            return "forwarded-tcpip";
        }
    }
}
