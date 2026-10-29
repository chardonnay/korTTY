package de.kortty.core;

import de.kortty.isolation.IsolationLevel;
import de.kortty.isolation.IsolationRequest;
import de.kortty.isolation.IsolationState;
import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import org.apache.sshd.client.channel.ChannelDirectTcpip;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

/**
 * Runs SSH sessions through a real session worker process against a loopback server: the shell, an
 * exec channel (the AI agent's path), SFTP on the terminal's session, a {@code direct-tcpip} channel
 * (an {@code -L} tunnel), a remote tunnel ({@code -R}) with and without the sandbox, key authentication signed by korTTY for the worker, and a killed worker
 * reported as a lost connection with the crash reason.
 */
class SessionWorkerIntegrationTest {

    private static final String PASSWORD = "worker-test";
    private static final long TIMEOUT_MS = 20_000;

    private SshServer server;
    private Path tmp;
    private SshTtyConnector connector;
    private ServerSocket echoServer;
    /** The shell the server runs; the echo shell unless a test sets another before {@link #startServer}. */
    private java.util.function.Supplier<Command> shell = EchoShell::new;

    @AfterMethod(alwaysRun = true)
    void cleanup() throws IOException {
        if (connector != null) {
            connector.close();
            connector = null;
        }
        if (server != null) {
            server.stop(true);
            server = null;
        }
        if (echoServer != null) {
            echoServer.close();
            echoServer = null;
        }
        shell = EchoShell::new;
        if (tmp != null) {
            try (Stream<Path> entries = Files.walk(tmp)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
            tmp = null;
        }
    }

    private void startServer(KeyPair acceptedKey) throws IOException {
        if (de.kortty.core.worker.SessionWorkerProcess.defaultCommand() == null) {
            throw new SkipException("session workers cannot be started from this test JVM");
        }
        tmp = Files.createTempDirectory("kortty-worker-it-");
        Files.createDirectories(tmp.resolve("sftp-root"));
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("host.ser")));
        server.setPasswordAuthenticator((user, password, session) -> PASSWORD.equals(password));
        server.setKeyboardInteractiveAuthenticator(null);
        server.setPublickeyAuthenticator((user, key, session) ->
            acceptedKey != null && KeyUtils.compareKeys(acceptedKey.getPublic(), key));
        java.util.function.Supplier<Command> serverShell = shell;
        server.setShellFactory(channel -> serverShell.get());
        server.setCommandFactory((channel, command) -> new ExecCommand(command));
        server.setSubsystemFactories(java.util.List.of(new SftpSubsystemFactory()));
        server.setFileSystemFactory(new VirtualFileSystemFactory(tmp.resolve("sftp-root")));
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        server.start();
    }

    private SshTtyConnector isolatedConnector(ServerConnection connection, String password) {
        SshTtyConnector c = new SshTtyConnector(connection, password,
            TemporaryKeyTestFixtures.acceptingTrustManager(tmp.resolve("hostkeys.properties")));
        c.setIsolationRequest(new IsolationRequest(IsolationLevel.PROCESS, null));
        return c;
    }

    @Test
    void theShellRunsThroughAWorkerProcess() throws Exception {
        startServer(null);
        connector = isolatedConnector(new ServerConnection("w", "127.0.0.1", server.getPort(), "tester"), PASSWORD);

        assertThat(connector.connect()).isTrue();
        assertThat(connector.isolationReport().state()).isEqualTo(IsolationState.PROCESS);
        assertThat(connector.getWorker()).isNotNull();
        assertThat(connector.getWorker().pid()).isNotEqualTo(ProcessHandle.current().pid());
        assertThat(readUntil(connector, "echo-ready")).contains("echo-ready");

        connector.write("ping\r");
        assertThat(readUntil(connector, "ping")).contains("ping");
    }

    /**
     * OSC 5522 crosses the worker both ways unchanged: the server's clipboard commands reach the emulator
     * in korTTY's process, where the handler runs, and the emulator's answers reach the server.
     */
    @Test
    void theClipboardProtocolRoundTripsThroughAWorkerProcess() throws Exception {
        ClipboardShell clipboardShell = new ClipboardShell();
        shell = () -> clipboardShell;
        startServer(null);
        connector = isolatedConnector(new ServerConnection("w", "127.0.0.1", server.getPort(), "tester"), PASSWORD);
        assertThat(connector.connect()).isTrue();
        assertThat(connector.isolationReport().state()).isEqualTo(IsolationState.PROCESS);

        com.sithtermfx.core.model.StyleState style = new com.sithtermfx.core.model.StyleState();
        com.sithtermfx.core.model.TerminalTextBuffer buffer =
            new com.sithtermfx.core.model.TerminalTextBuffer(40, 4, style);
        com.sithtermfx.core.model.SithTerminal terminal = new com.sithtermfx.core.model.SithTerminal(
            silentDisplay(), buffer, style);
        java.util.List<String> written = new java.util.concurrent.CopyOnWriteArrayList<>();
        terminal.setClipboardHandler(new com.sithtermfx.core.clipboard.TerminalClipboardHandler() {
            @Override
            public java.util.concurrent.CompletionStage<java.util.List<String>> getMimeTypes(
                    com.sithtermfx.core.clipboard.ClipboardLocation location) {
                return java.util.concurrent.CompletableFuture.completedFuture(java.util.List.of("text/plain"));
            }

            @Override
            public java.util.concurrent.CompletionStage<java.util.Map<String, byte[]>> read(
                    com.sithtermfx.core.clipboard.ClipboardReadRequest request) {
                return java.util.concurrent.CompletableFuture.completedFuture(
                    java.util.Map.of("text/plain", "from korTTY".getBytes(StandardCharsets.UTF_8)));
            }

            @Override
            public java.util.concurrent.CompletionStage<Void> write(
                    com.sithtermfx.core.clipboard.ClipboardWriteRequest request) {
                written.add(new String(request.getData().get("text/plain"), StandardCharsets.UTF_8));
                return java.util.concurrent.CompletableFuture.completedFuture(null);
            }
        });
        terminal.setTerminalOutput(new com.sithtermfx.core.TerminalOutputStream() {
            @Override
            public void sendBytes(byte[] response, boolean userInput) {
                sendString(new String(response, StandardCharsets.UTF_8), userInput);
            }

            @Override
            public void sendString(String string, boolean userInput) {
                try {
                    connector.write(string);
                } catch (IOException e) {
                    throw new java.io.UncheckedIOException(e);
                }
            }
        });
        SshTtyConnector pane = connector;
        Thread emulatorThread = new Thread(() -> {
            com.sithtermfx.core.emulator.SithEmulator emulator = new com.sithtermfx.core.emulator.SithEmulator(
                new com.sithtermfx.core.TtyBasedArrayDataStream(pane), terminal);
            try {
                while (emulator.hasNext()) {
                    emulator.next();
                }
            } catch (IOException ended) {
                // the server closed the channel
            }
        }, "emulator");
        emulatorThread.setDaemon(true);
        emulatorThread.start();

        assertThat(clipboardShell.answered.await(TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)).isTrue();
        String received = clipboardShell.received.toString();
        assertThat(received).contains(ClipboardShell.ESC + "[?5522;2$y");
        assertThat(received).contains("]5522;type=write:status=DONE");
        assertThat(received).contains("]5522;type=read:status=DATA:mime="
            + ClipboardShell.b64("text/plain") + ";" + ClipboardShell.b64("from korTTY"));
        assertThat(received).contains("]5522;type=read:status=DONE");
        assertThat(written).containsExactly("from the server");
    }

    @Test
    void aSandboxedWorkerConnectsAndReportsItsSandbox() throws Exception {
        if (!de.kortty.isolation.sandbox.SandboxSupport.availability().available()) {
            throw new SkipException("no working sandbox on this computer");
        }
        startServer(null);
        connector = new SshTtyConnector(new ServerConnection("w", "127.0.0.1", server.getPort(), "tester"), PASSWORD,
            TemporaryKeyTestFixtures.acceptingTrustManager(tmp.resolve("hostkeys.properties")));
        connector.setIsolationRequest(new IsolationRequest(IsolationLevel.SANDBOX, null));

        assertThat(connector.connect()).isTrue();
        assertThat(connector.isolationReport().state()).isEqualTo(IsolationState.SANDBOXED);
        assertThat(readUntil(connector, "echo-ready")).contains("echo-ready");
        connector.write("sandboxed\r");
        assertThat(readUntil(connector, "sandboxed")).contains("sandboxed");
    }

    @Test
    void execSftpAndDirectTcpipUseTheTerminalsSession() throws Exception {
        startServer(null);
        connector = isolatedConnector(new ServerConnection("w", "127.0.0.1", server.getPort(), "tester"), PASSWORD);
        assertThat(connector.connect()).isTrue();
        ClientSession session = connector.getSession();

        try (ChannelExec exec = session.createExecChannel("hello")) {
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            exec.setOut(out);
            exec.open().verify(Duration.ofSeconds(10));
            exec.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TIMEOUT_MS);
            assertThat(out.toString(StandardCharsets.UTF_8)).isEqualTo("exec:hello");
            assertThat(exec.getExitStatus()).isEqualTo(0);
        }

        try (SftpClient sftp = SftpClientFactory.instance().createSftpClient(session)) {
            try (OutputStream file = sftp.write("note.txt")) {
                file.write("through the worker".getBytes(StandardCharsets.UTF_8));
            }
        }
        assertThat(Files.readString(tmp.resolve("sftp-root").resolve("note.txt"))).isEqualTo("through the worker");

        int echoPort = startEchoServer();
        try (ChannelDirectTcpip tunnel = session.createDirectTcpipChannel(
                new SshdSocketAddress("127.0.0.1", 0), new SshdSocketAddress("127.0.0.1", echoPort))) {
            tunnel.open().verify(Duration.ofSeconds(10));
            OutputStream toTarget = tunnel.getInvertedIn();
            toTarget.write("tunnel\n".getBytes(StandardCharsets.UTF_8));
            toTarget.flush();
            byte[] reply = tunnel.getInvertedOut().readNBytes(7);
            assertThat(new String(reply, StandardCharsets.UTF_8)).isEqualTo("tunnel\n");
        }
    }

    @Test
    void aRemoteTunnelDeliversTheServersConnectionsToKortty() throws Exception {
        startServer(null);
        connector = isolatedConnector(new ServerConnection("w", "127.0.0.1", server.getPort(), "tester"), PASSWORD);
        assertThat(connector.connect()).isTrue();
        assertRemoteTunnelWorks(connector.getSession());
    }

    @Test
    void aRemoteTunnelWorksFromASandboxedWorker() throws Exception {
        if (!de.kortty.isolation.sandbox.SandboxSupport.availability().available()) {
            throw new SkipException("no working sandbox on this computer");
        }
        startServer(null);
        connector = new SshTtyConnector(new ServerConnection("w", "127.0.0.1", server.getPort(), "tester"), PASSWORD,
            TemporaryKeyTestFixtures.acceptingTrustManager(tmp.resolve("hostkeys.properties")));
        connector.setIsolationRequest(new IsolationRequest(IsolationLevel.SANDBOX, null));
        assertThat(connector.connect()).isTrue();
        assertThat(connector.isolationReport().state()).isEqualTo(IsolationState.SANDBOXED);
        assertRemoteTunnelWorks(connector.getSession());
    }

    /**
     * Opens {@code -R 0:127.0.0.1:<echo>} on korTTY's session, connects to the port the server bound and
     * expects the echo back through server, worker and korTTY; after a cancel the port is gone.
     */
    private void assertRemoteTunnelWorks(ClientSession session) throws Exception {
        int echoPort = startEchoServer();
        SshdSocketAddress bound = session.startRemotePortForwarding(
            new SshdSocketAddress("127.0.0.1", 0), new SshdSocketAddress("127.0.0.1", echoPort));
        assertThat(bound.getPort()).isGreaterThan(0);

        try (Socket client = new Socket(InetAddress.getLoopbackAddress(), bound.getPort())) {
            client.setSoTimeout((int) TIMEOUT_MS);
            client.getOutputStream().write("reverse\n".getBytes(StandardCharsets.UTF_8));
            client.getOutputStream().flush();
            byte[] reply = client.getInputStream().readNBytes(8);
            assertThat(new String(reply, StandardCharsets.UTF_8)).isEqualTo("reverse\n");
        }

        session.stopRemotePortForwarding(bound);
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        boolean closed = false;
        while (!closed && System.currentTimeMillis() < deadline) {
            try (Socket ignored = new Socket(InetAddress.getLoopbackAddress(), bound.getPort())) {
                Thread.sleep(50);
            } catch (IOException refused) {
                closed = true;
            }
        }
        assertThat(closed).isTrue();
    }

    @Test
    void keyAuthenticationIsSignedByKortty() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(256);
        KeyPair key = generator.generateKeyPair();
        startServer(key);
        Path keyFile = tmp.resolve("id_test");
        try (OutputStream out = Files.newOutputStream(keyFile)) {
            OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(key, "test", null, out);
        }
        ServerConnection connection = new ServerConnection("w", "127.0.0.1", server.getPort(), "tester");
        connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
        connection.setPrivateKeyPath(keyFile.toString());
        connector = isolatedConnector(connection, null);

        assertThat(connector.connect()).isTrue();
        assertThat(readUntil(connector, "echo-ready")).contains("echo-ready");
    }

    @Test
    void aKilledWorkerIsReportedAsALostConnection() throws Exception {
        startServer(null);
        connector = isolatedConnector(new ServerConnection("w", "127.0.0.1", server.getPort(), "tester"), PASSWORD);
        assertThat(connector.connect()).isTrue();
        assertThat(readUntil(connector, "echo-ready")).contains("echo-ready");

        connector.getWorker().kill();

        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!connector.wasConnectionLost() && System.currentTimeMillis() < deadline) {
            Thread.sleep(50);
        }
        assertThat(connector.wasConnectionLost()).isTrue();
        assertThat(connector.getWorker().handle().onExit().get(10, java.util.concurrent.TimeUnit.SECONDS).isAlive())
            .isFalse();
        assertThat(connector.getWorker().crashed()).isTrue();
    }

    private int startEchoServer() throws IOException {
        echoServer = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread thread = new Thread(() -> {
            try (Socket socket = echoServer.accept()) {
                socket.getInputStream().transferTo(socket.getOutputStream());
            } catch (IOException ignored) {
                // The test ended.
            }
        }, "echo-server");
        thread.setDaemon(true);
        thread.start();
        return echoServer.getLocalPort();
    }

    private static String readUntil(SshTtyConnector c, String expected) throws Exception {
        StringBuilder decoded = new StringBuilder();
        char[] buffer = new char[256];
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!decoded.toString().contains(expected) && System.currentTimeMillis() < deadline) {
            if (!c.ready()) {
                Thread.sleep(10);
                continue;
            }
            int count = c.read(buffer, 0, buffer.length);
            if (count < 0) {
                break;
            }
            decoded.append(buffer, 0, count);
        }
        return decoded.toString();
    }

    /** Prints a banner and echoes what it receives until the channel closes. */
    private static final class EchoShell implements Command {
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
            Thread thread = new Thread(() -> {
                try {
                    out.write("echo-ready\r\n".getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    byte[] buffer = new byte[256];
                    int count;
                    while ((count = in.read(buffer)) >= 0) {
                        out.write(buffer, 0, count);
                        out.flush();
                    }
                    exit.onExit(0);
                } catch (IOException e) {
                    exit.onExit(1);
                }
            }, "echo-shell");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
        }
    }

    /** A display that shows nothing: the emulator only needs one to talk to. */
    private static com.sithtermfx.core.TerminalDisplay silentDisplay() {
        return (com.sithtermfx.core.TerminalDisplay) java.lang.reflect.Proxy.newProxyInstance(
            SessionWorkerIntegrationTest.class.getClassLoader(), new Class<?>[] {com.sithtermfx.core.TerminalDisplay.class},
            (proxy, method, args) -> {
                if (method.isDefault()) {
                    return java.lang.reflect.InvocationHandler.invokeDefault(proxy, method, args);
                }
                Class<?> type = method.getReturnType();
                if (type == boolean.class) {
                    return false;
                }
                if (type == int.class || type == long.class || type == float.class || type == double.class) {
                    return java.lang.reflect.Array.get(java.lang.reflect.Array.newInstance(type, 1), 0);
                }
                return null;
            });
    }

    /**
     * Sends a program's OSC 5522 session (DECRQM, a write, a read), then records what comes back until the
     * read's last answer, and exits.
     */
    private static final class ClipboardShell implements Command {
        static final String ESC = "\u001B";
        final StringBuffer received = new StringBuffer();
        final java.util.concurrent.CountDownLatch answered = new java.util.concurrent.CountDownLatch(1);
        private InputStream in;
        private OutputStream out;
        private ExitCallback exit;

        static String b64(String text) {
            return java.util.Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
        }

        private static String osc(String metadata, String payload) {
            return ESC + "]5522;" + metadata + (payload.isEmpty() ? "" : ";" + payload) + ESC + "\\";
        }

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
            Thread thread = new Thread(() -> {
                try {
                    String session = ESC + "[?5522$p"
                        + osc("type=write", "") + osc("type=wdata:mime=" + b64("text/plain"), b64("from the server"))
                        + osc("type=wdata", "")
                        + osc("type=read", b64("text/plain"));
                    out.write(session.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    byte[] buffer = new byte[256];
                    int count;
                    while (!received.toString().contains("]5522;type=read:status=DONE") && (count = in.read(buffer)) >= 0) {
                        received.append(new String(buffer, 0, count, StandardCharsets.UTF_8));
                    }
                    answered.countDown();
                    exit.onExit(0);
                } catch (IOException e) {
                    exit.onExit(1);
                }
            }, "clipboard-shell");
            thread.setDaemon(true);
            thread.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
        }
    }

    /** Prints {@code exec:<command>} and exits with 0. */
    private static final class ExecCommand implements Command {
        private final String command;
        private OutputStream out;
        private ExitCallback exit;

        ExecCommand(String command) {
            this.command = command;
        }

        @Override
        public void setInputStream(InputStream in) {
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
        public void start(ChannelSession channel, Environment env) throws IOException {
            out.write(("exec:" + command).getBytes(StandardCharsets.UTF_8));
            out.flush();
            exit.onExit(0);
        }

        @Override
        public void destroy(ChannelSession channel) {
        }
    }
}
