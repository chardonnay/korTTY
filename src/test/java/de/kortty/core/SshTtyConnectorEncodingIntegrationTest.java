package de.kortty.core;

import de.kortty.model.ServerConnection;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * Connects {@link SshTtyConnector} to a loopback server whose shell prints fixed bytes and records
 * what it receives, and pins that the connection's encoding decides both directions: a Latin-1
 * connection shows {@code E4 F6 FC DF} as "äöüß" and sends "é" as {@code E9}, while a connection
 * without an override stays UTF-8 exactly as before.
 */
class SshTtyConnectorEncodingIntegrationTest {

    private static final String PASSWORD = "encoding-test";
    private static final long TIMEOUT_MS = 10_000;

    private SshServer server;
    private Path tmp;

    @AfterMethod(alwaysRun = true)
    void stopServer() throws IOException {
        if (server != null) {
            server.stop(true);
            server = null;
        }
        if (tmp != null) {
            // Host key and trust store: nothing of this test stays in the temp folder.
            try (Stream<Path> entries = Files.walk(tmp)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
            tmp = null;
        }
    }

    @Test
    void aLatin1ConnectionDecodesAndEncodesLatin1() throws Exception {
        RecordingShell shell = startServer(new byte[] {(byte) 0xE4, (byte) 0xF6, (byte) 0xFC, (byte) 0xDF});
        ServerConnection connection = connection();
        connection.setEncoding("ISO-8859-1");

        SshTtyConnector connector = connector(connection);
        assertThat(connector.getCharset()).isEqualTo(StandardCharsets.ISO_8859_1);
        try {
            assertThat(connector.connect()).isTrue();
            assertThat(readUntil(connector, "äöüß")).contains("äöüß");

            connector.write("é");

            assertThat(shell.awaitReceived(1)).isEqualTo(new byte[] {(byte) 0xE9});
        } finally {
            connector.close();
        }
    }

    @Test
    void withoutAnOverrideTheSessionStaysUtf8() throws Exception {
        RecordingShell shell = startServer("äöüß".getBytes(StandardCharsets.UTF_8));

        SshTtyConnector connector = connector(connection());
        assertThat(connector.getCharset()).isEqualTo(StandardCharsets.UTF_8);
        try {
            assertThat(connector.connect()).isTrue();
            assertThat(readUntil(connector, "äöüß")).contains("äöüß");

            connector.write("é");

            assertThat(shell.awaitReceived(2)).isEqualTo("é".getBytes(StandardCharsets.UTF_8));
        } finally {
            connector.close();
        }
    }

    @Test
    void aMoshBootstrapConnectionStaysUtf8EvenWithAnOverride() throws Exception {
        tmp = Files.createTempDirectory("kortty-tty-encoding-mosh-");
        ServerConnection connection = connection();
        connection.setProtocol(de.kortty.model.ConnectionProtocol.MOSH);
        connection.setEncoding("ISO-8859-1");

        // The Mosh connectors bootstrap mosh-server through an SshTtyConnector on this connection.
        assertThat(connector(connection).getCharset()).isEqualTo(StandardCharsets.UTF_8);
    }

    private RecordingShell startServer(byte[] greeting) throws Exception {
        tmp = Files.createTempDirectory("kortty-tty-encoding-");
        RecordingShell shell = new RecordingShell(greeting);
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("host.ser")));
        server.setPasswordAuthenticator((username, password, session) -> PASSWORD.equals(password));
        server.setKeyboardInteractiveAuthenticator(null);
        server.setShellFactory(channel -> shell);
        server.start();
        return shell;
    }

    private ServerConnection connection() {
        int port = server != null ? server.getPort() : 22;
        return new ServerConnection("encoding", "127.0.0.1", port, "tester");
    }

    private SshTtyConnector connector(ServerConnection connection) {
        return new SshTtyConnector(connection, PASSWORD,
            TemporaryKeyTestFixtures.acceptingTrustManager(tmp.resolve("hostkeys.properties")));
    }

    /** Reads decoded output until it contains {@code expected} or the timeout passes. */
    private static String readUntil(SshTtyConnector connector, String expected) throws Exception {
        StringBuilder decoded = new StringBuilder();
        char[] buffer = new char[256];
        long deadline = System.currentTimeMillis() + TIMEOUT_MS;
        while (!decoded.toString().contains(expected) && System.currentTimeMillis() < deadline) {
            if (!connector.ready()) {
                Thread.onSpinWait();
                Thread.sleep(10);
                continue;
            }
            int count = connector.read(buffer, 0, buffer.length);
            if (count < 0) {
                break;
            }
            decoded.append(buffer, 0, count);
        }
        return decoded.toString();
    }

    /** A shell that prints {@code greeting} and records every byte it receives. */
    private static final class RecordingShell implements Command {
        private final byte[] greeting;
        private final ByteArrayOutputStream received = new ByteArrayOutputStream();
        private InputStream in;
        private OutputStream out;
        private ExitCallback exit;
        private Thread worker;

        RecordingShell(byte[] greeting) {
            this.greeting = greeting.clone();
        }

        /** The first {@code count} bytes the shell received, waiting up to the timeout for them. */
        byte[] awaitReceived(int count) throws InterruptedException {
            long deadline = System.currentTimeMillis() + TIMEOUT_MS;
            synchronized (received) {
                while (received.size() < count && System.currentTimeMillis() < deadline) {
                    received.wait(50);
                }
                byte[] bytes = received.toByteArray();
                assertWithMessage("bytes received by the shell: %s", HexFormat.of().formatHex(bytes))
                    .that(bytes.length).isAtLeast(count);
                return Arrays.copyOf(bytes, count);
            }
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
            worker = new Thread(() -> {
                try {
                    out.write(greeting);
                    out.flush();
                    byte[] buffer = new byte[256];
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        synchronized (received) {
                            received.write(buffer, 0, read);
                            received.notifyAll();
                        }
                    }
                    exit.onExit(0);
                } catch (IOException e) {
                    exit.onExit(1);
                }
            }, "terminal-encoding-test-shell");
            worker.setDaemon(true);
            worker.start();
        }

        @Override
        public void destroy(ChannelSession channel) {
            if (worker != null) {
                worker.interrupt();
            }
        }
    }
}
