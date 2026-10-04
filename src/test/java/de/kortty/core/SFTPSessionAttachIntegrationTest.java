package de.kortty.core;

import de.kortty.core.sftp.SftpLoopbackFixture;
import de.kortty.core.sftp.SftpSubsystemUnavailableException;
import de.kortty.model.ServerConnection;
import de.kortty.ui.I18n;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static org.testng.Assert.expectThrows;

/**
 * {@link SFTPSession#attach} on a terminal's SSH session, against a loopback MINA server with the
 * SFTP subsystem and a tiny shell: SFTP works on the borrowed session, closing it never closes the
 * terminal's session, a lost session is reported once, a reconnect is picked up through the
 * supplier, extra channels stay within the borrowed budget, and a server without SFTP gives
 * {@link SftpSubsystemUnavailableException} so callers can fall back to their own login.
 */
class SFTPSessionAttachIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);
    private static final String SHELL_GREETING = "shell-ready";

    private Path tempDir;
    private SftpLoopbackFixture fixture;
    private ServerConnection connection;

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-sftp-attach-test");
        fixture = SftpLoopbackFixture.builder(tempDir).start();
        fixture.server().setShellFactory(channel -> new GreetingCommand(SHELL_GREETING));
        fixture.server().setCommandFactory((channel, command) -> new GreetingCommand("ran:" + command));
        connection = new ServerConnection("it", "127.0.0.1", fixture.port(), SftpLoopbackFixture.USER);
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (fixture != null) {
            fixture.close();
        }
        deleteTree(tempDir);
    }

    @Test
    void attachListsAndClosingLeavesTheTerminalSessionAndItsShellUsable() throws Exception {
        Files.writeString(fixture.root().resolve("hello.txt"), "hi");
        ClientSession terminal = fixture.connect();

        SFTPSession sftp = SFTPSession.attach(() -> terminal, connection, "it-label");
        List<String> names = new ArrayList<>();
        for (SftpClient.DirEntry entry : sftp.listFiles("/")) {
            names.add(entry.getFilename());
        }
        assertThat(names).contains("hello.txt");
        assertThat(sftp.ownsSession()).isFalse();
        assertThat(sftp.isOpen()).isTrue();
        assertThat(sftp.describe()).isEqualTo("it-label");

        sftp.close();
        await(() -> fixture.stats().openChannels() == 0, "the SFTP channel closes on the server");
        assertThat(sftp.isOpen()).isFalse();
        assertThat(terminal.isOpen()).isTrue();
        assertThat(readShellGreeting(terminal)).contains(SHELL_GREETING);
    }

    @Test
    void closingTheTerminalSessionReportsTheDisconnectOnce() throws Exception {
        ClientSession terminal = fixture.connect();
        SFTPSession sftp = SFTPSession.attach(() -> terminal, connection, "it");
        AtomicInteger reports = new AtomicInteger();
        sftp.setDisconnectListener(reports::incrementAndGet);

        terminal.close(true);

        await(() -> reports.get() >= 1, "the disconnect is reported");
        Thread.sleep(200);
        assertThat(reports.get()).isEqualTo(1);
        sftp.close();
    }

    @Test
    void aDeliberateCloseIsNotReportedAndLeavesNoListenerOnTheSession() throws Exception {
        ClientSession terminal = fixture.connect();
        SFTPSession sftp = SFTPSession.attach(() -> terminal, connection, "it");
        AtomicInteger reports = new AtomicInteger();
        sftp.setDisconnectListener(reports::incrementAndGet);

        sftp.close();
        terminal.close(true);
        Thread.sleep(200);

        assertThat(reports.get()).isEqualTo(0);
    }

    @Test
    void afterAReconnectTheSupplierGivesTheNewSessionAndOpenChannelUsesIt() throws Exception {
        ClientSession first = fixture.connect();
        AtomicReference<ClientSession> current = new AtomicReference<>(first);
        SFTPSession sftp = SFTPSession.attach(current::get, connection, "it");

        ClientSession second = fixture.connect();
        current.set(second);
        first.close(true);

        try (SftpClient channel = sftp.openChannel()) {
            assertThat(channel.getClientSession()).isSameInstanceAs(second);
            assertThat(channel.canonicalPath(".")).isNotEmpty();
        }
        assertThat(second.isOpen()).isTrue();
        sftp.close();
        assertThat(second.isOpen()).isTrue();
    }

    @Test
    void extraChannelsAndCommandsShareTheBorrowedBudget() throws Exception {
        ClientSession terminal = fixture.connect();
        SFTPSession sftp = SFTPSession.attach(() -> terminal, connection, "it");
        assertThat(sftp.executeCommand("whoami")).contains("ran:whoami");

        SftpClient one = sftp.openChannel();
        SftpClient two = sftp.openChannel();
        assertThat(sftp.openExtraChannelCount()).isEqualTo(SFTPSession.BORROWED_CHANNEL_BUDGET);

        IOException overBudget = expectThrows(IOException.class, sftp::openChannel);
        assertThat(overBudget).hasMessageThat()
            .isEqualTo(I18n.get("sftp.error.channelBudget", SFTPSession.BORROWED_CHANNEL_BUDGET));
        Exception commandOverBudget = expectThrows(Exception.class, () -> sftp.executeCommand("id"));
        assertThat(commandOverBudget).hasMessageThat().contains(overBudget.getMessage());

        one.close();
        await(() -> sftp.openExtraChannelCount() == 1, "a closed channel frees its place");
        assertThat(sftp.executeCommand("id")).contains("ran:id");
        await(() -> sftp.openExtraChannelCount() == 1, "a finished command frees its place");

        sftp.close();
        assertThat(two.isOpen()).isFalse();
        assertThat(sftp.openExtraChannelCount()).isEqualTo(0);
        await(() -> fixture.stats().openChannels() == 0, "every SFTP channel closes on the server");
        assertThat(terminal.isOpen()).isTrue();
    }

    @Test
    void aMissingSessionIsAReadableIOExceptionNotASubsystemFailure() {
        IOException missing = expectThrows(IOException.class,
            () -> SFTPSession.attach(() -> null, connection, "it"));
        assertThat(missing).isNotInstanceOf(SftpSubsystemUnavailableException.class);
        assertThat(missing).hasMessageThat().isEqualTo(I18n.get("terminal.sftp.sessionUnavailable"));
    }

    @Test
    void aBorrowedSessionCannotConnectItself() throws Exception {
        ClientSession terminal = fixture.connect();
        SFTPSession sftp = SFTPSession.attach(() -> terminal, connection, "it");
        expectThrows(IllegalStateException.class, sftp::connect);
        sftp.close();
    }

    @Test
    void aServerWithoutTheSftpSubsystemGivesSftpSubsystemUnavailable() throws Exception {
        SshServer bare = SshServer.setUpDefaultServer();
        bare.setHost("127.0.0.1");
        bare.setPort(0);
        bare.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("bare-host.ser")));
        bare.setPasswordAuthenticator((user, password, serverSession) -> true);
        bare.start();
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
        try {
            ClientSession terminal = client.connect("tester", "127.0.0.1", bare.getPort())
                .verify(TIMEOUT).getSession();
            terminal.addPasswordIdentity("secret");
            terminal.auth().verify(TIMEOUT);
            ServerConnection bareConnection = new ServerConnection("bare", "127.0.0.1", bare.getPort(), "tester");

            SftpSubsystemUnavailableException refused = expectThrows(SftpSubsystemUnavailableException.class,
                () -> SFTPSession.attach(() -> terminal, bareConnection, "bare"));

            assertThat(refused).hasMessageThat().isNotEmpty();
            assertThat(refused).hasMessageThat().doesNotContain("sftp.error");
            assertThat(terminal.isOpen()).isTrue();
        } finally {
            client.stop();
            bare.stop(true);
        }
    }

    private static String readShellGreeting(ClientSession session) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ChannelShell shell = session.createShellChannel()) {
            shell.setOut(out);
            shell.setErr(new ByteArrayOutputStream());
            shell.open().verify(TIMEOUT);
            shell.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), TIMEOUT.toMillis());
        }
        return out.toString(StandardCharsets.UTF_8);
    }

    private static void await(BooleanSupplier condition, String what) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertWithMessage(what).that(condition.getAsBoolean()).isTrue();
    }

    private static void deleteTree(Path root) throws IOException {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    /** Writes one line, then exits 0: enough of a shell or command to prove the session works. */
    private static final class GreetingCommand implements Command {
        private final String text;
        private OutputStream out;
        private ExitCallback exit;

        GreetingCommand(String text) {
            this.text = text;
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
            out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            exit.onExit(0);
        }

        @Override
        public void destroy(ChannelSession channel) {
        }
    }
}
