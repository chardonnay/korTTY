package de.kortty.core;

import de.kortty.core.SshTunnelManager.State;
import de.kortty.core.SshTunnelTestFixtures.CapturingForwardingFilter;
import de.kortty.core.SshTunnelTestFixtures.EchoServer;
import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import de.kortty.model.TunnelType;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.SshServer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static de.kortty.core.SshTunnelTestFixtures.freePort;
import static de.kortty.core.SshTunnelTestFixtures.isListening;
import static de.kortty.core.SshTunnelTestFixtures.roundTrip;
import static de.kortty.core.SshTunnelTestFixtures.tunnel;

/**
 * Connects a real {@link SshTtyConnector} to a loopback server and pins the split of duties: the
 * connector never opens the connection's tunnels itself (every split pane and reconnect builds a
 * connector of its own, so it would collide on the ports), but it installs the client forwarding
 * filter that the tab's {@link SshTunnelManager} needs for remote tunnels on its session.
 */
class SshTtyConnectorTunnelIntegrationTest {

    private SshServer server;
    private EchoServer echo;
    private Path tmp;
    private SshTtyConnector connector;
    private SshTunnelManager manager;

    @BeforeMethod
    void start() throws Exception {
        tmp = Files.createTempDirectory("kortty-tty-tunnel-it-");
        server = SshTunnelTestFixtures.startServer(tmp.resolve("host.ser"), new CapturingForwardingFilter(),
            channel -> new TemporaryKeyTestFixtures.EchoShell());
        echo = new EchoServer();
    }

    @AfterMethod(alwaysRun = true)
    void stop() throws IOException {
        if (manager != null) {
            manager.close();
        }
        if (connector != null) {
            connector.close();
        }
        if (server != null) {
            server.stop(true);
        }
        if (echo != null) {
            echo.close();
        }
    }

    private ServerConnection connectionWithLocalTunnel(int localPort) {
        ServerConnection connection = new ServerConnection("tunnels", "127.0.0.1", server.getPort(), "tester");
        connection.setAuthMethod(AuthMethod.PASSWORD);
        connection.getSshTunnels().add(tunnel(TunnelType.LOCAL, "localhost", localPort, "127.0.0.1", echo.port()));
        return connection;
    }

    @Test
    void connectingNeverOpensTheConnectionsTunnels() throws Exception {
        int localPort = freePort();
        connector = new SshTtyConnector(connectionWithLocalTunnel(localPort), SshTunnelTestFixtures.PASSWORD,
            TemporaryKeyTestFixtures.acceptingTrustManager(tmp.resolve("hostkeys.properties")));

        assertThat(connector.connect()).isTrue();

        assertWithMessage("a connector (and so every split pane) must leave the tunnels to the tab")
            .that(isListening(localPort)).isFalse();
    }

    @Test
    void theConnectorsSessionCarriesARemoteTunnelBackToThisComputer() throws Exception {
        int serverPort = freePort();
        connector = new SshTtyConnector(connectionWithLocalTunnel(freePort()), SshTunnelTestFixtures.PASSWORD,
            TemporaryKeyTestFixtures.acceptingTrustManager(tmp.resolve("hostkeys.properties")));
        assertThat(connector.connect()).isTrue();
        ClientSession session = connector.getSession();
        assertThat(session).isNotNull();
        manager = new SshTunnelManager(() -> true);

        manager.attach(session, List.of(tunnel(TunnelType.REMOTE, "127.0.0.1", echo.port(), "localhost", serverPort)),
            false);

        assertThat(manager.snapshot().get(0).state()).isEqualTo(State.ACTIVE);
        try (Socket socket = new Socket("localhost", serverPort)) {
            assertWithMessage("without the connector's forwarding filter MINA refuses the forwarded channel")
                .that(roundTrip(socket, "back home")).isEqualTo("back home");
        }
    }
}
