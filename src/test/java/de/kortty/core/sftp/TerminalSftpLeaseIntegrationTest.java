package de.kortty.core.sftp;

import de.kortty.ui.I18n;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.common.channel.Channel;
import org.apache.sshd.common.channel.ChannelListener;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * {@link TerminalSftpLease} against a loopback MINA SSH server with the SFTP subsystem: every lease
 * releases its server channel, the terminal's session stays open, and a missing or closed session
 * fails with the readable message instead of a NullPointerException.
 */
class TerminalSftpLeaseIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private Path tempDir;
    private SshServer server;
    private SshClient client;
    private ClientSession session;
    private final AtomicInteger openServerChannels = new AtomicInteger();

    @BeforeMethod
    void setUp() throws IOException {
        tempDir = Files.createTempDirectory("kortty-sftp-lease-test");
        Path remoteRoot = Files.createDirectory(tempDir.resolve("remote"));
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tempDir.resolve("host.ser")));
        server.setPasswordAuthenticator((user, password, serverSession) -> true);
        server.setFileSystemFactory(new VirtualFileSystemFactory(remoteRoot));
        server.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory.Builder().build()));
        server.addChannelListener(new ChannelListener() {
            @Override
            public void channelOpenSuccess(Channel channel) {
                openServerChannels.incrementAndGet();
            }

            @Override
            public void channelClosed(Channel channel, Throwable reason) {
                openServerChannels.decrementAndGet();
            }
        });
        server.start();

        client = SshClient.setUpDefaultClient();
        client.start();
        session = client.connect("tester", "127.0.0.1", server.getPort()).verify(TIMEOUT).getSession();
        session.addPasswordIdentity("secret");
        session.auth().verify(TIMEOUT);
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (session != null) {
            session.close(true);
        }
        if (client != null) {
            client.stop();
        }
        if (server != null) {
            server.stop(true);
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        }
    }

    @Test
    void twentyLeasesLeaveNoServerChannelOpen() throws Exception {
        for (int i = 0; i < 20; i++) {
            try (TerminalSftpLease lease = TerminalSftpLease.open(session)) {
                assertThat(lease.client().canonicalPath(".")).isNotEmpty();
            }
        }
        awaitOpenServerChannels(0);
        assertThat(session.isOpen()).isTrue();
    }

    @Test
    void theFixtureSeesChannelsThatAreNotClosed() throws Exception {
        // Control for the test above: the old drag-and-drop code opened clients like this and never closed them.
        List<SftpClient> leaked = new ArrayList<>();
        try {
            for (int i = 0; i < 3; i++) {
                SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
                sftp.canonicalPath(".");
                leaked.add(sftp);
            }
            awaitOpenServerChannels(3);
        } finally {
            for (SftpClient sftp : leaked) {
                sftp.close();
            }
        }
        awaitOpenServerChannels(0);
    }

    @Test
    void closingTwiceIsHarmlessAndKeepsTheSession() throws Exception {
        TerminalSftpLease lease = TerminalSftpLease.open(session);
        lease.close();
        lease.close();
        assertThat(lease.client().isOpen()).isFalse();
        assertThat(session.isOpen()).isTrue();
        awaitOpenServerChannels(0);
    }

    @Test
    void aClosedSessionGivesAReadableIOException() throws Exception {
        session.close(true);
        IOException closed = expectThrows(IOException.class, () -> TerminalSftpLease.open(session));
        assertThat(closed).hasMessageThat().isEqualTo(I18n.get("terminal.sftp.sessionUnavailable"));
    }

    @Test
    void aMissingSessionGivesAReadableIOException() {
        IOException missing = expectThrows(IOException.class, () -> TerminalSftpLease.open(null));
        assertThat(missing).hasMessageThat().isEqualTo(I18n.get("terminal.sftp.sessionUnavailable"));
        assertThat(missing).hasMessageThat().doesNotContain("terminal.sftp");
    }

    private void awaitOpenServerChannels(int expected) throws InterruptedException {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (openServerChannels.get() != expected && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(openServerChannels.get()).isEqualTo(expected);
    }
}
