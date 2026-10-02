package de.kortty.core;

import de.kortty.model.SSHTunnel;
import de.kortty.model.TunnelType;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.session.Session;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.StaticDecisionForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Loopback fixtures for the SSH tunnel tests: an embedded SSHD server (same harness as
 * {@code JumpHostIntegrationTest}) whose forwarding filter records the remote binds it is asked
 * for, a TCP echo server to forward to, and helpers to prove a byte round trip through a tunnel.
 */
final class SshTunnelTestFixtures {

    static final String PASSWORD = "tunnel-test-pw";

    private SshTunnelTestFixtures() {
    }

    /**
     * Allows every forward like a permissive OpenSSH server, records each {@code tcpip-forward}
     * bind address, and can hold remote-forward requests until the test releases them.
     */
    static final class CapturingForwardingFilter extends StaticDecisionForwardingFilter {
        final List<SshdSocketAddress> listenRequests = new CopyOnWriteArrayList<>();
        volatile CountDownLatch holdListen;
        final CountDownLatch listenEntered = new CountDownLatch(1);

        CapturingForwardingFilter() {
            super(true);
        }

        @Override
        public boolean canListen(SshdSocketAddress address, Session session) {
            listenRequests.add(address);
            listenEntered.countDown();
            CountDownLatch hold = holdListen;
            if (hold != null) {
                try {
                    hold.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return true;
        }
    }

    /** A loopback server with password login and the given filter, already started. */
    static SshServer startServer(Path hostKey, CapturingForwardingFilter filter) throws IOException {
        return startServer(hostKey, filter, null);
    }

    static SshServer startServer(Path hostKey, CapturingForwardingFilter filter,
                                 org.apache.sshd.server.shell.ShellFactory shellFactory) throws IOException {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        server.setPasswordAuthenticator((username, password, session) -> PASSWORD.equals(password));
        server.setForwardingFilter(filter);
        if (shellFactory != null) {
            server.setShellFactory(shellFactory);
        }
        server.start();
        return server;
    }

    /** A plain client with the korTTY client forwarding filter (or MINA's default when false). */
    static SshClient startClient(boolean korttyFilter) {
        SshClient client = SshClient.setUpDefaultClient();
        if (korttyFilter) {
            client.setForwardingFilter(SshTunnelManager.clientForwardingFilter());
        }
        client.setServerKeyVerifier((session, address, key) -> true);
        client.start();
        return client;
    }

    static ClientSession login(SshClient client, SshServer server) throws IOException {
        ClientSession session = client.connect("tester", "127.0.0.1", server.getPort())
            .verify(Duration.ofSeconds(10)).getSession();
        session.addPasswordIdentity(PASSWORD);
        session.auth().verify(Duration.ofSeconds(10));
        return session;
    }

    /** A port that was free a moment ago on {@code localhost}. */
    static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0, 50, InetAddress.getByName("localhost"))) {
            return socket.getLocalPort();
        }
    }

    /** Whether something listens on {@code localhost:port} (a bind there fails). */
    static boolean isListening(int port) {
        try (ServerSocket socket = new ServerSocket()) {
            socket.bind(new InetSocketAddress(InetAddress.getByName("localhost"), port));
            return false;
        } catch (IOException e) {
            return true;
        }
    }

    /** Polls until {@code localhost:port} is free again, or the timeout passes. */
    static boolean awaitReleased(int port, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (!isListening(port)) {
                return true;
            }
            Thread.sleep(50);
        }
        return !isListening(port);
    }

    static SSHTunnel tunnel(TunnelType type, String localHost, int localPort, String remoteHost, int remotePort) {
        SSHTunnel tunnel = new SSHTunnel();
        tunnel.setEnabled(true);
        tunnel.setType(type);
        tunnel.setLocalHost(localHost);
        tunnel.setLocalPort(localPort);
        tunnel.setRemoteHost(remoteHost);
        tunnel.setRemotePort(remotePort);
        return tunnel;
    }

    /** Writes a line through {@code socket} and returns what came back, or null on EOF. */
    static String roundTrip(Socket socket, String text) throws IOException {
        socket.setSoTimeout(10_000);
        OutputStream out = socket.getOutputStream();
        out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
        out.flush();
        InputStream in = socket.getInputStream();
        StringBuilder received = new StringBuilder();
        int read;
        while ((read = in.read()) != -1) {
            if (read == '\n') {
                return received.toString();
            }
            received.append((char) read);
        }
        return received.isEmpty() ? null : received.toString();
    }

    /** Echoes every line back to each client; listens on 127.0.0.1. */
    static final class EchoServer implements AutoCloseable {
        private final ServerSocket serverSocket;
        private final List<Socket> clients = new CopyOnWriteArrayList<>();

        EchoServer() throws IOException {
            serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
            Thread acceptor = new Thread(this::acceptLoop, "tunnel-test-echo");
            acceptor.setDaemon(true);
            acceptor.start();
        }

        int port() {
            return serverSocket.getLocalPort();
        }

        private void acceptLoop() {
            while (!serverSocket.isClosed()) {
                try {
                    Socket client = serverSocket.accept();
                    clients.add(client);
                    Thread worker = new Thread(() -> echo(client), "tunnel-test-echo-client");
                    worker.setDaemon(true);
                    worker.start();
                } catch (SocketException closed) {
                    return;
                } catch (IOException e) {
                    return;
                }
            }
        }

        private static void echo(Socket client) {
            try (client) {
                InputStream in = client.getInputStream();
                OutputStream out = client.getOutputStream();
                byte[] buffer = new byte[1024];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    out.flush();
                }
            } catch (IOException ignored) {
                // The test side closed the connection.
            }
        }

        @Override
        public void close() throws IOException {
            serverSocket.close();
            for (Socket client : clients) {
                client.close();
            }
        }
    }
}
