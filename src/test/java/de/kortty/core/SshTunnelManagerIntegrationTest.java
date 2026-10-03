package de.kortty.core;

import de.kortty.core.SshTunnelManager.Failure;
import de.kortty.core.SshTunnelManager.State;
import de.kortty.core.SshTunnelManager.TunnelStatus;
import de.kortty.core.SshTunnelTestFixtures.CapturingForwardingFilter;
import de.kortty.core.SshTunnelTestFixtures.EchoServer;
import de.kortty.model.SSHTunnel;
import de.kortty.model.TunnelType;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.SshServer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static de.kortty.core.SshTunnelTestFixtures.awaitReleased;
import static de.kortty.core.SshTunnelTestFixtures.freePort;
import static de.kortty.core.SshTunnelTestFixtures.isListening;
import static de.kortty.core.SshTunnelTestFixtures.login;
import static de.kortty.core.SshTunnelTestFixtures.roundTrip;
import static de.kortty.core.SshTunnelTestFixtures.tunnel;

/**
 * Drives {@link SshTunnelManager} against a real, loopback-only Apache SSHD server: every tunnel
 * type carries bytes end to end, failures stay per tunnel, and the stop/attach handover that a
 * reconnect or a re-home relies on frees the ports before they are bound again.
 */
class SshTunnelManagerIntegrationTest {

    private SshServer server;
    private SshClient client;
    private EchoServer echo;
    private CapturingForwardingFilter filter;
    private final List<SshTunnelManager> managers = new ArrayList<>();

    @BeforeMethod
    void start() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-tunnel-it-");
        filter = new CapturingForwardingFilter();
        server = SshTunnelTestFixtures.startServer(tmp.resolve("host.ser"), filter);
        client = SshTunnelTestFixtures.startClient(true);
        echo = new EchoServer();
    }

    @AfterMethod(alwaysRun = true)
    void stop() throws IOException {
        managers.forEach(SshTunnelManager::close);
        managers.clear();
        if (client != null) {
            client.stop();
        }
        if (server != null) {
            server.stop(true);
        }
        if (echo != null) {
            echo.close();
        }
    }

    private SshTunnelManager manager() {
        SshTunnelManager manager = new SshTunnelManager(() -> true);
        managers.add(manager);
        return manager;
    }

    @Test
    void aLocalTunnelCarriesBytesToTheTarget() throws Exception {
        int port = freePort();
        SshTunnelManager manager = manager();
        ClientSession session = login(client, server);

        assertThat(manager.attach(session,
            List.of(tunnel(TunnelType.LOCAL, "localhost", port, "127.0.0.1", echo.port())), false)).isTrue();

        TunnelStatus status = manager.snapshot().get(0);
        assertThat(status.state()).isEqualTo(State.ACTIVE);
        assertThat(status.boundAddress()).endsWith(":" + port);
        try (Socket socket = new Socket("localhost", port)) {
            assertThat(roundTrip(socket, "through -L")).isEqualTo("through -L");
        }
    }

    @Test
    void aRemoteTunnelDeliversServerSideConnectionsAndBindsLoopbackByDefault() throws Exception {
        int serverPort = freePort();
        SshTunnelManager manager = manager();
        ClientSession session = login(client, server);
        // The model's default remoteHost is "localhost": the server must be asked for loopback only.
        SSHTunnel remote = tunnel(TunnelType.REMOTE, "127.0.0.1", echo.port(), "", serverPort);

        assertThat(manager.attach(session, List.of(remote), false)).isTrue();

        assertThat(manager.snapshot().get(0).state()).isEqualTo(State.ACTIVE);
        assertThat(filter.listenRequests).hasSize(1);
        assertThat(filter.listenRequests.get(0).getHostName()).isEqualTo("localhost");
        assertThat(filter.listenRequests.get(0).getPort()).isEqualTo(serverPort);
        // The "server" runs on this machine, so its listener is reachable here.
        try (Socket socket = new Socket("localhost", serverPort)) {
            assertThat(roundTrip(socket, "through -R")).isEqualTo("through -R");
        }
    }

    @Test
    void withoutTheClientFilterARemoteTunnelBindsButRefusesEveryConnection() throws Exception {
        // Why SshTtyConnector installs clientForwardingFilter(): MINA's client default rejects the
        // forwarded-tcpip channel of each incoming connection, so -R looked open but never worked.
        SshClient defaultClient = SshTunnelTestFixtures.startClient(false);
        try {
            int serverPort = freePort();
            SshTunnelManager manager = manager();
            ClientSession session = login(defaultClient, server);
            assertThat(manager.attach(session,
                List.of(tunnel(TunnelType.REMOTE, "127.0.0.1", echo.port(), "localhost", serverPort)), false)).isTrue();
            assertThat(manager.snapshot().get(0).state()).isEqualTo(State.ACTIVE);

            try (Socket socket = new Socket("localhost", serverPort)) {
                String answer;
                try {
                    answer = roundTrip(socket, "refused");
                } catch (IOException reset) {
                    answer = null;
                }
                assertThat(answer).isNull();
            }
        } finally {
            defaultClient.stop();
        }
    }

    @Test
    void aDynamicTunnelIsASocksProxyResolvedOnTheServer() throws Exception {
        int socksPort = freePort();
        SshTunnelManager manager = manager();
        ClientSession session = login(client, server);

        assertThat(manager.attach(session, List.of(tunnel(TunnelType.DYNAMIC, "localhost", socksPort, null, 0)),
            false)).isTrue();

        assertThat(manager.snapshot().get(0).state()).isEqualTo(State.ACTIVE);
        Proxy socks = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress("localhost", socksPort));
        try (Socket socket = new Socket(socks)) {
            socket.connect(new InetSocketAddress("127.0.0.1", echo.port()), 10_000);
            assertThat(roundTrip(socket, "through -D")).isEqualTo("through -D");
        }
    }

    @Test
    void aPortInUseFailsOnlyThatTunnelAndTheFirstOwnerKeepsIt() throws Exception {
        // Two tabs on the same connection: the second one's tunnel cannot bind, the first goes on.
        int port = freePort();
        int otherPort = freePort();
        SshTunnelManager first = manager();
        SshTunnelManager second = manager();
        ClientSession firstSession = login(client, server);
        ClientSession secondSession = login(client, server);
        SSHTunnel shared = tunnel(TunnelType.LOCAL, "localhost", port, "127.0.0.1", echo.port());

        first.attach(firstSession, List.of(shared), false);
        second.attach(secondSession, List.of(shared,
            tunnel(TunnelType.LOCAL, "localhost", otherPort, "127.0.0.1", echo.port())), false);

        assertThat(first.snapshot().get(0).state()).isEqualTo(State.ACTIVE);
        TunnelStatus conflict = second.snapshot().get(0);
        assertThat(conflict.state()).isEqualTo(State.FAILED);
        assertThat(conflict.failure()).isEqualTo(Failure.BIND_FAILED);
        assertThat(conflict.detail()).isNotEmpty();
        assertWithMessage("a failed tunnel must not keep the others from starting")
            .that(second.snapshot().get(1).state()).isEqualTo(State.ACTIVE);
        try (Socket socket = new Socket("localhost", port)) {
            assertThat(roundTrip(socket, "first owner")).isEqualTo("first owner");
        }
    }

    @Test
    void stopThenAttachOnAnotherSessionRebindsTheSamePorts() throws Exception {
        // The reconnect and re-home handover: the same LOCAL and REMOTE ports, a new session.
        int localPort = freePort();
        int serverPort = freePort();
        List<SSHTunnel> tunnels = List.of(
            tunnel(TunnelType.LOCAL, "localhost", localPort, "127.0.0.1", echo.port()),
            tunnel(TunnelType.REMOTE, "127.0.0.1", echo.port(), "localhost", serverPort));
        SshTunnelManager manager = manager();
        ClientSession firstSession = login(client, server);
        ClientSession secondSession = login(client, server);

        manager.attach(firstSession, tunnels, false);
        assertThat(manager.activeCount()).isEqualTo(2);

        manager.stop();
        assertThat(manager.snapshot()).hasSize(2);
        assertThat(manager.snapshot().stream().map(TunnelStatus::state).toList())
            .containsExactly(State.STOPPED, State.STOPPED);

        assertThat(manager.attach(secondSession, tunnels, false)).isTrue();
        assertThat(manager.snapshot().stream().map(TunnelStatus::state).toList())
            .containsExactly(State.ACTIVE, State.ACTIVE);
        assertThat(manager.ownerSession()).isSameInstanceAs(secondSession);
        assertThat(firstSession.isOpen()).isTrue();
        try (Socket socket = new Socket("localhost", localPort)) {
            assertThat(roundTrip(socket, "local again")).isEqualTo("local again");
        }
        try (Socket socket = new Socket("localhost", serverPort)) {
            assertThat(roundTrip(socket, "remote again")).isEqualTo("remote again");
        }
    }

    @Test
    void attachingToAnotherSessionWithoutStopMovesTheTunnelsThere() throws Exception {
        // A re-home while the old owner session is still open: attach releases its ports first.
        int localPort = freePort();
        List<SSHTunnel> tunnels = List.of(tunnel(TunnelType.LOCAL, "localhost", localPort, "127.0.0.1", echo.port()));
        SshTunnelManager manager = manager();
        ClientSession firstSession = login(client, server);
        ClientSession secondSession = login(client, server);

        manager.attach(firstSession, tunnels, false);
        manager.attach(secondSession, tunnels, false);

        assertThat(manager.snapshot().get(0).state()).isEqualTo(State.ACTIVE);
        firstSession.close();
        try (Socket socket = new Socket("localhost", localPort)) {
            assertWithMessage("closing the former owner must not take the moved tunnel down")
                .that(roundTrip(socket, "moved")).isEqualTo("moved");
        }
    }

    @Test
    void closingTheOwnerSessionStopsTheTunnelsFiresTheListenerAndReleasesThePort() throws Exception {
        int port = freePort();
        SshTunnelManager manager = manager();
        CountDownLatch ownerClosed = new CountDownLatch(1);
        AtomicReference<ClientSession> reported = new AtomicReference<>();
        manager.setOwnerClosedListener(session -> {
            reported.set(session);
            ownerClosed.countDown();
        });
        ClientSession session = login(client, server);
        manager.attach(session, List.of(tunnel(TunnelType.LOCAL, "localhost", port, "127.0.0.1", echo.port())), false);
        assertThat(isListening(port)).isTrue();

        session.close();

        assertThat(ownerClosed.await(10, TimeUnit.SECONDS)).isTrue();
        assertThat(reported.get()).isSameInstanceAs(session);
        assertThat(manager.snapshot().get(0).state()).isEqualTo(State.STOPPED);
        assertThat(manager.ownerSession()).isNull();
        assertThat(awaitReleased(port, Duration.ofSeconds(5))).isTrue();
    }

    @Test
    void aSessionClosedAfterStopDoesNotReportTheOwnerAsGone() throws Exception {
        // The reconnect order: stop() first, then the old session closes. That close must not
        // trigger a re-home.
        SshTunnelManager manager = manager();
        CountDownLatch ownerClosed = new CountDownLatch(1);
        manager.setOwnerClosedListener(session -> ownerClosed.countDown());
        ClientSession session = login(client, server);
        manager.attach(session, List.of(tunnel(TunnelType.LOCAL, "localhost", freePort(), "127.0.0.1", echo.port())),
            false);

        manager.stop();
        session.close();

        assertThat(ownerClosed.await(500, TimeUnit.MILLISECONDS)).isFalse();
    }

    @Test
    void attachAfterCloseIsRefusedAndBindsNothing() throws Exception {
        int port = freePort();
        SshTunnelManager manager = manager();
        ClientSession session = login(client, server);
        manager.close();

        assertThat(manager.attach(session,
            List.of(tunnel(TunnelType.LOCAL, "localhost", port, "127.0.0.1", echo.port())), false)).isFalse();

        assertThat(manager.isClosed()).isTrue();
        assertThat(manager.snapshot()).isEmpty();
        assertThat(isListening(port)).isFalse();
    }

    @Test
    void attachToAClosedSessionIsRefused() throws Exception {
        SshTunnelManager manager = manager();
        ClientSession session = login(client, server);
        session.close();

        assertThat(manager.attach(session,
            List.of(tunnel(TunnelType.LOCAL, "localhost", freePort(), "127.0.0.1", echo.port())), false)).isFalse();
        assertThat(manager.snapshot()).isEmpty();
    }

    @Test
    void closeReleasesTheTunnelsOfAStillOpenSession() throws Exception {
        int port = freePort();
        SshTunnelManager manager = manager();
        ClientSession session = login(client, server);
        manager.attach(session, List.of(tunnel(TunnelType.LOCAL, "localhost", port, "127.0.0.1", echo.port())), false);
        assertThat(isListening(port)).isTrue();

        manager.close();

        assertThat(awaitReleased(port, Duration.ofSeconds(5))).isTrue();
        assertThat(session.isOpen()).isTrue();
    }

    @Test
    void stopDoesNotBlockWhileAnAttachWaitsForTheServer() throws Exception {
        int serverPort = freePort();
        SshTunnelManager manager = manager();
        ClientSession session = login(client, server);
        CountDownLatch release = new CountDownLatch(1);
        filter.holdListen = release;
        Thread attach = new Thread(() -> manager.attach(session,
            List.of(tunnel(TunnelType.REMOTE, "127.0.0.1", echo.port(), "localhost", serverPort)), false));
        attach.setDaemon(true);
        attach.start();
        assertThat(filter.listenEntered.await(10, TimeUnit.SECONDS)).isTrue();

        long started = System.nanoTime();
        manager.stop();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        release.countDown();
        attach.join(10_000);
        assertWithMessage("stop() runs on the JavaFX thread and must never wait for the server")
            .that(elapsedMillis).isLessThan(500L);
        assertThat(attach.isAlive()).isFalse();
        assertWithMessage("a forward that arrives after stop() is closed again, not reported active")
            .that(manager.snapshot().get(0).state()).isEqualTo(State.STOPPED);
        assertThat(manager.activeCount()).isEqualTo(0);
    }

    @Test
    void aPolicyThatForbidsPortForwardingOpensNothing() throws Exception {
        int port = freePort();
        SshTunnelManager manager = new SshTunnelManager(() -> false);
        managers.add(manager);
        ClientSession session = login(client, server);

        assertThat(manager.attach(session, List.of(
            tunnel(TunnelType.LOCAL, "localhost", port, "127.0.0.1", echo.port()),
            tunnel(TunnelType.REMOTE, "127.0.0.1", echo.port(), "localhost", freePort())), false)).isTrue();

        assertThat(manager.snapshot().stream().map(TunnelStatus::state).toList())
            .containsExactly(State.NOT_STARTED, State.NOT_STARTED);
        assertThat(manager.snapshot().stream().map(TunnelStatus::failure).toList())
            .containsExactly(Failure.POLICY_DENIED, Failure.POLICY_DENIED);
        assertThat(isListening(port)).isFalse();
        assertThat(filter.listenRequests).isEmpty();
        assertWithMessage("no tunnel runs on the session, so it is no owner").that(manager.ownerSession()).isNull();
    }

    @Test
    void aSharedConnectionNeverAsksTheServerForARemoteTunnelEvenOnLoopback() throws Exception {
        // Loopback on a multi-user SSH server is reachable by every account there, so a shared
        // file must not be able to publish this computer's services through -R at all.
        int localPort = freePort();
        SshTunnelManager manager = manager();
        ClientSession session = login(client, server);

        manager.attach(session, List.of(
            tunnel(TunnelType.REMOTE, "127.0.0.1", echo.port(), "localhost", freePort()),
            tunnel(TunnelType.LOCAL, "localhost", localPort, "127.0.0.1", echo.port())), true);

        TunnelStatus remote = manager.snapshot().get(0);
        assertThat(remote.state()).isEqualTo(State.FAILED);
        assertThat(remote.failure()).isEqualTo(Failure.SHARED_REMOTE);
        assertThat(filter.listenRequests).isEmpty();
        assertWithMessage("a loopback LOCAL tunnel of a shared connection still opens")
            .that(manager.snapshot().get(1).state()).isEqualTo(State.ACTIVE);
    }

    @Test
    void aSharedConnectionCannotOpenALanFacingListener() throws Exception {
        int port = freePort();
        SshTunnelManager manager = manager();
        ClientSession session = login(client, server);

        manager.attach(session, List.of(tunnel(TunnelType.DYNAMIC, "0.0.0.0", port, null, 0)), true);

        TunnelStatus status = manager.snapshot().get(0);
        assertThat(status.state()).isEqualTo(State.FAILED);
        assertThat(status.failure()).isEqualTo(Failure.SHARED_NON_LOOPBACK);
        assertThat(isListening(port)).isFalse();
    }
}
