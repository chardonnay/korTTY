package de.kortty.core;

import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.session.SessionListener;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Loopback-only Apache SSHD servers for connector integration tests: every password logs in, the
 * offered passwords are recorded, TCP forwarding is allowed (so a server can act as a bastion) and
 * an optional shell prints {@link #BANNER} and then waits for its input to end.
 */
final class LoopbackSshServers {

    /** What the scripted shell prints first, proving the session reached that server's shell. */
    static final String BANNER = "korTTY loopback shell ready";

    private LoopbackSshServers() {
    }

    /**
     * Starts a server on an ephemeral loopback port.
     *
     * @param passwordSink receives every password offered to the server
     * @param sessions counts every session the server accepted a TCP connection for
     * @param withShell whether a shell channel gets the scripted banner shell
     */
    static SshServer start(Path hostKey, Set<String> passwordSink, AtomicInteger sessions, boolean withShell)
            throws IOException {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        server.setPasswordAuthenticator((username, password, session) -> {
            passwordSink.add(password);
            return true; // any credential authenticates; tests assert on what was offered
        });
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        server.addSessionListener(new SessionListener() {
            @Override
            public void sessionCreated(Session session) {
                sessions.incrementAndGet();
            }
        });
        if (withShell) {
            server.setShellFactory(channel -> new BannerShell());
        }
        server.start();
        return server;
    }

    /** A loopback port nothing listens on (bound once and released again). */
    static int closedPort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    /** Accepts every first-use host key without a UI and counts how often it was asked. */
    static final class AcceptingPrompt implements SshHostKeyTrustManager.HostKeyPrompt {
        final AtomicInteger firstUsePrompts = new AtomicInteger();

        @Override
        public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
            firstUsePrompts.incrementAndGet();
            return true;
        }

        @Override
        public void warnMismatch(SshHostKeyTrustManager.HostKeyMismatch mismatch) {
        }

        @Override
        public void warnVerificationFailure(SshHostKeyTrustManager.HostKeyVerificationFailure failure) {
        }
    }

    /** Prints {@link #BANNER} and keeps the shell open until the client closes its input. */
    private static final class BannerShell implements Command {
        private InputStream in;
        private OutputStream out;
        private ExitCallback exit;

        @Override
        public void setInputStream(InputStream in) {
            this.in = in;
        }

        @Override
        public void setOutputStream(OutputStream out) {
            this.out = out;
        }

        @Override
        public void setErrorStream(OutputStream err) {
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.exit = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
            Thread worker = new Thread(() -> {
                try {
                    out.write((BANNER + "\r\n").getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    while (in.read() != -1) {
                        // discard input; the shell only has to stay open
                    }
                    exit.onExit(0);
                } catch (IOException e) {
                    exit.onExit(1);
                }
            }, "loopback-banner-shell");
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
        }
    }
}
