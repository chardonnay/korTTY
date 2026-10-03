package de.kortty.core;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

class ActiveConnectionRegistryTest {

    private static final Clock FIXED_CLOCK =
        Clock.fixed(Instant.parse("2026-10-02T08:15:30.750Z"), ZoneOffset.UTC);

    /** Two keys that are {@code equals} but not the same object: identity must keep them apart. */
    private record ConnectorKey(String host) {
    }

    @Test
    void connectAndDisconnectAreSymmetric() {
        ActiveConnectionRegistry registry = new ActiveConnectionRegistry(FIXED_CLOCK);
        Object primary = new Object();
        Object split = new Object();

        assertThat(registry.connected(primary, "prod-db", ActiveConnectionRegistry.PROTOCOL_SSH)).isTrue();
        assertThat(registry.connected(split, "prod-db", ActiveConnectionRegistry.PROTOCOL_SSH)).isTrue();
        assertThat(registry.connectionCount()).isEqualTo(2);

        assertThat(registry.disconnected(primary)).isTrue();
        assertThat(registry.connectionCount()).isEqualTo(1);
        assertThat(registry.disconnected(split)).isTrue();
        assertThat(registry.connectionCount()).isEqualTo(0);
        assertThat(registry.snapshot()).isEmpty();
    }

    @Test
    void entriesAreKeyedByIdentityNotEquality() {
        ActiveConnectionRegistry registry = new ActiveConnectionRegistry(FIXED_CLOCK);
        ConnectorKey first = new ConnectorKey("db.example");
        ConnectorKey second = new ConnectorKey("db.example");
        assertThat(first).isEqualTo(second);

        registry.connected(first, "db", ActiveConnectionRegistry.PROTOCOL_SSH);
        registry.connected(second, "db", ActiveConnectionRegistry.PROTOCOL_SSH);
        assertThat(registry.connectionCount()).isEqualTo(2);

        registry.disconnected(second);
        assertThat(registry.connectionCount()).isEqualTo(1);
        // A third equal key was never registered, so it removes nothing.
        assertThat(registry.disconnected(new ConnectorKey("db.example"))).isFalse();
        assertThat(registry.connectionCount()).isEqualTo(1);
    }

    @Test
    void reportingTheSameConnectorTwiceKeepsTheFirstEntry() {
        ActiveConnectionRegistry registry = new ActiveConnectionRegistry(FIXED_CLOCK);
        Object connector = new Object();

        assertThat(registry.connected(connector, "first", ActiveConnectionRegistry.PROTOCOL_SSH)).isTrue();
        ActiveConnectionRegistry.Entry original = registry.snapshot().get(0);
        assertThat(registry.connected(connector, "second", ActiveConnectionRegistry.PROTOCOL_MOSH)).isFalse();

        assertThat(registry.snapshot()).containsExactly(original);
        // The hooks fire more than once on teardown (pane close after a transport disconnect).
        assertThat(registry.disconnected(connector)).isTrue();
        assertThat(registry.disconnected(connector)).isFalse();
        assertThat(registry.disconnected(null)).isFalse();
    }

    @Test
    void localShellsAreNotNetworkConnections() {
        ActiveConnectionRegistry registry = new ActiveConnectionRegistry(FIXED_CLOCK);
        ServerConnection local = new ServerConnection("zsh", "localhost", 0, "me");
        local.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        LocalShellTtyConnector shell = new LocalShellTtyConnector(local);

        assertThat(ActiveConnectionRegistry.protocolOf(shell)).isNull();
        assertThat(ActiveConnectionRegistry.protocolOf(null)).isNull();
        assertThat(registry.terminalConnected(shell)).isFalse();
        assertThat(registry.connectionCount()).isEqualTo(0);
        assertThat(registry.terminalDisconnected(shell)).isFalse();
    }

    @Test
    void terminalConnectorsAreLabelledByTransport() {
        ActiveConnectionRegistry registry = new ActiveConnectionRegistry(FIXED_CLOCK);
        // Never connected, so the trust store is never read; this keeps the test off ~/.kortty.
        SshHostKeyTrustManager unusedTrust =
            new SshHostKeyTrustManager(Path.of("build", "unused-hostkeys.properties"), new RefusingPrompt());
        SshTtyConnector ssh = new SshTtyConnector(
            connection("Prod web", ConnectionProtocol.SSH_TCP), "pw", unusedTrust);
        Mosh4jTtyConnector mosh = new Mosh4jTtyConnector(connection("Laptop", ConnectionProtocol.MOSH), "pw");
        NativeMoshTtyConnector nativeMosh =
            new NativeMoshTtyConnector(connection("", ConnectionProtocol.MOSH_CLIENT), "pw");

        assertThat(registry.terminalConnected(ssh)).isTrue();
        assertThat(registry.terminalConnected(mosh)).isTrue();
        assertThat(registry.terminalConnected(nativeMosh)).isTrue();

        List<ActiveConnectionRegistry.Entry> entries = registry.snapshot();
        assertThat(entries.stream().map(ActiveConnectionRegistry.Entry::protocol).toList())
            .containsExactly("SSH", "MOSH", "MOSH_CLIENT").inOrder();
        // A blank connection name falls back to the connection's own user@host display name.
        assertThat(registry.connectionNames())
            .containsExactly("Prod web", "Laptop", "daniel@host.example").inOrder();

        assertThat(registry.terminalDisconnected(mosh)).isTrue();
        assertThat(registry.connectionNames()).containsExactly("Prod web", "daniel@host.example").inOrder();
    }

    @Test
    void statisticsDescribeEachConnectionInConnectOrder() {
        ActiveConnectionRegistry registry = new ActiveConnectionRegistry(FIXED_CLOCK);
        Object first = new Object();
        Object second = new Object();
        registry.connected(first, "web", ActiveConnectionRegistry.PROTOCOL_SSH);
        registry.connected(second, "laptop", ActiveConnectionRegistry.PROTOCOL_MOSH);
        registry.connected(new Object(), null, ActiveConnectionRegistry.PROTOCOL_MOSH_CLIENT);

        List<ActiveConnectionRegistry.Entry> entries = registry.snapshot();
        assertThat(entries.stream().map(ActiveConnectionRegistry.Entry::id).distinct().count()).isEqualTo(3);
        assertThat(registry.statistics().keySet())
            .containsExactlyElementsIn(entries.stream().map(ActiveConnectionRegistry.Entry::id).toList())
            .inOrder();
        assertThat(new ArrayList<>(registry.statistics().values())).containsExactly(
            "Connection: web, Protocol: SSH, Connected At: 2026-10-02T08:15:30",
            "Connection: laptop, Protocol: MOSH, Connected At: 2026-10-02T08:15:30",
            "Connection: , Protocol: MOSH_CLIENT, Connected At: 2026-10-02T08:15:30").inOrder();
    }

    @Test
    void connectTimeAlwaysCarriesSeconds() {
        // LocalDateTime.toString() would print 08:15 here, not 08:15:00.
        Clock onTheMinute = Clock.fixed(Instant.parse("2026-10-02T08:15:00.250Z"), ZoneOffset.UTC);
        ActiveConnectionRegistry registry = new ActiveConnectionRegistry(onTheMinute);
        registry.connected(new Object(), "web", ActiveConnectionRegistry.PROTOCOL_SSH);

        assertThat(registry.statistics().values())
            .containsExactly("Connection: web, Protocol: SSH, Connected At: 2026-10-02T08:15:00");
    }

    @Test
    void rejectsAMissingKeyOrProtocol() {
        ActiveConnectionRegistry registry = new ActiveConnectionRegistry(FIXED_CLOCK);

        assertThrows(NullPointerException.class,
            () -> registry.connected(null, "x", ActiveConnectionRegistry.PROTOCOL_SSH));
        assertThrows(NullPointerException.class, () -> registry.connected(new Object(), "x", null));
        assertThat(registry.connectionCount()).isEqualTo(0);
    }

    @Test
    void concurrentConnectsAndDisconnectsBalanceOut() throws Exception {
        ActiveConnectionRegistry registry = new ActiveConnectionRegistry();
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    List<Object> mine = new ArrayList<>();
                    for (int i = 0; i < perThread; i++) {
                        Object key = new Object();
                        mine.add(key);
                        registry.connected(key, "host-" + i, ActiveConnectionRegistry.PROTOCOL_SSH);
                        registry.connectionNames();
                    }
                    for (Object key : mine) {
                        registry.disconnected(key);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(registry.connectionCount()).isEqualTo(0);
    }

    @Test
    void sharedIsOneProcessWideInstance() {
        assertThat(ActiveConnectionRegistry.shared()).isSameInstanceAs(ActiveConnectionRegistry.shared());
    }

    private static final class RefusingPrompt implements SshHostKeyTrustManager.HostKeyPrompt {
        @Override
        public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
            return false;
        }

        @Override
        public void warnMismatch(SshHostKeyTrustManager.HostKeyMismatch mismatch) {
        }

        @Override
        public void warnVerificationFailure(SshHostKeyTrustManager.HostKeyVerificationFailure failure) {
        }
    }

    private static ServerConnection connection(String name, ConnectionProtocol protocol) {
        ServerConnection connection = new ServerConnection(name, "host.example", 22, "daniel");
        connection.setProtocol(protocol);
        return connection;
    }
}
