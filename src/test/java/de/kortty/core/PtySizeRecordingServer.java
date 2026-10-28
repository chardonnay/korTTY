package de.kortty.core;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelFactory;
import org.apache.sshd.common.channel.PtyMode;
import org.apache.sshd.common.channel.RequestHandler;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

/**
 * A loopback SSH server whose shell does nothing but record the terminal sizes it is told: the
 * {@code pty-req} and every {@code window-change}, each as {@code "COLSxROWS@WIDTHxHEIGHTpx"}.
 */
public final class PtySizeRecordingServer implements AutoCloseable {

    private final BlockingQueue<String> ptyRequests = new LinkedBlockingQueue<>();
    private final BlockingQueue<String> windowChanges = new LinkedBlockingQueue<>();
    private final SshServer server;

    public PtySizeRecordingServer(Path hostKey, String password) throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        server.setPasswordAuthenticator((username, given, session) -> password.equals(given));
        server.setKeyboardInteractiveAuthenticator(null);
        server.setShellFactory(channel -> new IdleShell());
        server.setChannelFactories(List.of(new ChannelFactory() {
            @Override
            public String getName() {
                return "session";
            }

            @Override
            public Channel createChannel(Session session) {
                return new RecordingChannelSession();
            }
        }));
        server.start();
    }

    public int port() {
        return server.getPort();
    }

    public String awaitPtyRequest() throws InterruptedException {
        return ptyRequests.poll(10, TimeUnit.SECONDS);
    }

    public String awaitWindowChange() throws InterruptedException {
        return windowChanges.poll(10, TimeUnit.SECONDS);
    }

    @Override
    public void close() throws IOException {
        server.stop(true);
    }

    private static String size(int columns, int rows, int width, int height) {
        return columns + "x" + rows + "@" + width + "x" + height + "px";
    }

    private final class RecordingChannelSession extends ChannelSession {
        @Override
        protected RequestHandler.Result handlePtyReqParsed(String term, int columns, int rows, int width, int height,
                Map<PtyMode, Integer> modes) throws IOException {
            ptyRequests.add(size(columns, rows, width, height));
            return super.handlePtyReqParsed(term, columns, rows, width, height, modes);
        }

        @Override
        protected RequestHandler.Result handleWindowChangeParsed(int columns, int rows, int width, int height)
                throws IOException {
            windowChanges.add(size(columns, rows, width, height));
            return super.handleWindowChangeParsed(columns, rows, width, height);
        }
    }

    /** A shell that stays open until the channel closes. */
    private static final class IdleShell implements Command {
        private ExitCallback exitCallback;

        @Override
        public void setInputStream(InputStream in) {
        }

        @Override
        public void setOutputStream(OutputStream out) {
        }

        @Override
        public void setErrorStream(OutputStream err) {
        }

        @Override
        public void setExitCallback(ExitCallback callback) {
            this.exitCallback = callback;
        }

        @Override
        public void start(ChannelSession channel, Environment env) {
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (exitCallback != null) {
                exitCallback.onExit(0);
            }
        }
    }
}
