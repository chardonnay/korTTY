package de.kortty.core.worker;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.core.PtySizeRecordingServer;
import de.kortty.core.SshPtyPixels;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.keyverifier.AcceptAllServerKeyVerifier;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

/**
 * The session worker relays korTTY's shell to the upstream server. Apache SSHD's own server
 * channel keeps only columns and lines of the {@code pty-req} and {@code window-change}, so the
 * endpoint uses {@link PixelSizeChannelSession}; this pins that the text area's pixels reach the
 * upstream server on both requests, through the worker's real channel factory and relay.
 */
class RelayCommandPixelSizeTest {

    private static final String PASSWORD = "relay-pixels";
    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private Path tmp;

    @AfterMethod(alwaysRun = true)
    void deleteTemp() throws IOException {
        if (tmp != null) {
            try (Stream<Path> entries = Files.walk(tmp)) {
                for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
                    Files.deleteIfExists(entry);
                }
            }
            tmp = null;
        }
    }

    @Test(timeOut = 30_000)
    void thePixelSizeIsRelayedUpstream() throws Exception {
        tmp = Files.createTempDirectory("kortty-relay-pixels-");
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(AcceptAllServerKeyVerifier.INSTANCE);
        client.start();
        SshServer endpoint = null;
        try (PtySizeRecordingServer upstreamServer = new PtySizeRecordingServer(tmp.resolve("upstream.ser"), PASSWORD)) {
            ClientSession upstream = login(client, upstreamServer.port());

            endpoint = SshServer.setUpDefaultServer();
            endpoint.setHost("127.0.0.1");
            endpoint.setPort(0);
            endpoint.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("endpoint.ser")));
            endpoint.setPasswordAuthenticator((username, given, session) -> PASSWORD.equals(given));
            endpoint.setShellFactory(channel -> new RelayCommand(upstream, RelayCommand.Kind.SHELL, null));
            endpoint.setChannelFactories(List.of(PixelSizeChannelSession.FACTORY));
            endpoint.start();

            try (ClientSession korTTY = login(client, endpoint.getPort());
                 ChannelShell shell = korTTY.createShellChannel()) {
                shell.setPtyType("xterm-256color");
                shell.setPtyColumns(120);
                shell.setPtyLines(40);
                SshPtyPixels.setPtyPixels(shell, 1080, 720);
                shell.open().verify(TIMEOUT);

                assertThat(upstreamServer.awaitPtyRequest()).isEqualTo("120x40@1080x720px");

                SshPtyPixels.sendWindowChange(shell, 100, 30, 900, 540);
                assertThat(upstreamServer.awaitWindowChange()).isEqualTo("100x30@900x540px");
            }
        } finally {
            if (endpoint != null) {
                endpoint.stop(true);
            }
            client.stop();
        }
    }

    private static ClientSession login(SshClient client, int port) throws IOException {
        ClientSession session = client.connect("tester", "127.0.0.1", port).verify(TIMEOUT).getSession();
        session.addPasswordIdentity(PASSWORD);
        session.auth().verify(TIMEOUT);
        return session;
    }
}
