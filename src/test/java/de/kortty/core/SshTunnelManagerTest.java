package de.kortty.core;

import de.kortty.core.SshTunnelManager.Endpoints;
import de.kortty.core.SshTunnelManager.Failure;
import de.kortty.core.SshTunnelManager.State;
import de.kortty.core.SshTunnelManager.TunnelStatus;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import de.kortty.model.TunnelType;
import org.apache.sshd.common.util.net.SshdSocketAddress;
import org.apache.sshd.server.forward.ForwardingFilter;
import org.apache.sshd.server.forward.TcpForwardingFilter;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static de.kortty.core.SshTunnelTestFixtures.tunnel;

/** The pure parts of {@link SshTunnelManager}: endpoint resolution, refusals, copies, the filter. */
class SshTunnelManagerTest {

    @Test
    void aLocalTunnelListensOnTheLocalHostAndTargetsTheRemoteHost() {
        Endpoints endpoints = SshTunnelManager.resolve(tunnel(TunnelType.LOCAL, " ", 8080, "db", 5432), false);

        assertThat(endpoints.bindHost()).isEqualTo("localhost");
        assertThat(endpoints.bindPort()).isEqualTo(8080);
        assertThat(endpoints.targetHost()).isEqualTo("db");
        assertThat(endpoints.targetPort()).isEqualTo(5432);
        assertThat(endpoints.refusal()).isEqualTo(Failure.NONE);
    }

    @Test
    void aRemoteTunnelBindsOnTheRemoteHostAndTargetsThisComputer() {
        Endpoints endpoints = SshTunnelManager.resolve(
            tunnel(TunnelType.REMOTE, "127.0.0.1", 3000, "10.0.0.5", 9090), false);

        assertThat(endpoints.bindHost()).isEqualTo("10.0.0.5");
        assertThat(endpoints.bindPort()).isEqualTo(9090);
        assertThat(endpoints.targetHost()).isEqualTo("127.0.0.1");
        assertThat(endpoints.targetPort()).isEqualTo(3000);
    }

    @Test
    void aRemoteTunnelBindsLoopbackByDefaultLikeOpenSsh() {
        // The model default remoteHost is "localhost", and a blank one means the same: -R never
        // publishes on the server's network unless the user asked for that address.
        SSHTunnel modelDefault = new SSHTunnel(TunnelType.REMOTE, 3000, "localhost", 9090);
        assertThat(SshTunnelManager.resolve(modelDefault, false).bindHost()).isEqualTo("localhost");
        assertThat(SshTunnelManager.resolve(tunnel(TunnelType.REMOTE, null, 3000, "", 9090), false).bindHost())
            .isEqualTo("localhost");
        assertThat(SshTunnelManager.resolve(tunnel(TunnelType.REMOTE, null, 3000, null, 9090), false).targetHost())
            .isEqualTo("localhost");
        assertThat(SshTunnelManager.plannedStatus(modelDefault, false).exposed()).isFalse();
    }

    @Test
    void aDynamicTunnelHasNoTarget() {
        Endpoints endpoints = SshTunnelManager.resolve(tunnel(TunnelType.DYNAMIC, null, 1080, "ignored", 0), false);

        assertThat(endpoints.bindHost()).isEqualTo("localhost");
        assertThat(endpoints.bindPort()).isEqualTo(1080);
        assertThat(endpoints.targetHost()).isNull();
        assertThat(endpoints.refusal()).isEqualTo(Failure.NONE);
    }

    @Test
    void portsOutsideTheValidRangeAreRefused() {
        assertThat(SshTunnelManager.resolve(tunnel(TunnelType.LOCAL, "localhost", 0, "db", 5432), false).refusal())
            .isEqualTo(Failure.INVALID_PORT);
        assertThat(SshTunnelManager.resolve(tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 70000), false).refusal())
            .isEqualTo(Failure.INVALID_PORT);
        assertThat(SshTunnelManager.resolve(tunnel(TunnelType.REMOTE, "localhost", 3000, "localhost", 0), false)
            .refusal()).isEqualTo(Failure.INVALID_PORT);
        assertThat(SshTunnelManager.resolve(tunnel(TunnelType.DYNAMIC, "localhost", 70000, null, 0), false).refusal())
            .isEqualTo(Failure.INVALID_PORT);
        assertThat(SshTunnelManager.plannedStatus(tunnel(TunnelType.LOCAL, "localhost", 0, "db", 1), false).state())
            .isEqualTo(State.FAILED);
    }

    @Test
    void loopbackBindHostsAreRecognized() {
        for (String loopback : new String[] {null, "", " ", "localhost", "LOCALHOST", "127.0.0.1", "127.1.2.3",
                "::1", "[::1]"}) {
            assertWithMessage("loopback: " + loopback).that(SshTunnelManager.isLoopbackBindHost(loopback)).isTrue();
        }
        for (String exposed : new String[] {"0.0.0.0", "*", "::", "192.168.1.5", "server.example.com"}) {
            assertWithMessage("not loopback: " + exposed).that(SshTunnelManager.isLoopbackBindHost(exposed)).isFalse();
        }
    }

    @Test
    void aSharedConnectionRefusesEveryRemoteTunnelEvenOnLoopback() {
        // Loopback on a multi-user SSH server is still reachable by every account on it.
        assertThat(SshTunnelManager.resolve(tunnel(TunnelType.REMOTE, "localhost", 5432, "localhost", 9999), true)
            .refusal()).isEqualTo(Failure.SHARED_REMOTE);
        assertThat(SshTunnelManager.resolve(tunnel(TunnelType.REMOTE, "localhost", 5432, "0.0.0.0", 9999), true)
            .refusal()).isEqualTo(Failure.SHARED_REMOTE);
        assertThat(SshTunnelManager.wouldStart(tunnel(TunnelType.REMOTE, "localhost", 5432, "localhost", 9999), true))
            .isFalse();
        assertThat(SshTunnelManager.wouldStart(tunnel(TunnelType.REMOTE, "localhost", 5432, "localhost", 9999), false))
            .isTrue();
    }

    @Test
    void aSharedConnectionMayOnlyListenOnLoopbackHere() {
        assertThat(SshTunnelManager.resolve(tunnel(TunnelType.LOCAL, "0.0.0.0", 8080, "db", 5432), true).refusal())
            .isEqualTo(Failure.SHARED_NON_LOOPBACK);
        assertThat(SshTunnelManager.resolve(tunnel(TunnelType.DYNAMIC, "192.168.1.5", 1080, null, 0), true).refusal())
            .isEqualTo(Failure.SHARED_NON_LOOPBACK);
        assertThat(SshTunnelManager.resolve(tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432), true).refusal())
            .isEqualTo(Failure.NONE);
        assertWithMessage("the user's own connection may bind wherever it asks")
            .that(SshTunnelManager.resolve(tunnel(TunnelType.LOCAL, "0.0.0.0", 8080, "db", 5432), false).refusal())
            .isEqualTo(Failure.NONE);
    }

    @Test
    void enabledTunnelsSkipsDisabledEntriesAndToleratesANullList() {
        ServerConnection connection = new ServerConnection("c", "h", 22, "u");
        SSHTunnel enabled = tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432);
        SSHTunnel disabled = tunnel(TunnelType.DYNAMIC, "localhost", 1080, null, 0);
        disabled.setEnabled(false);
        List<SSHTunnel> configured = new ArrayList<>();
        configured.add(enabled);
        configured.add(null);
        configured.add(disabled);
        connection.setSshTunnels(configured);

        List<SSHTunnel> copies = SshTunnelManager.enabledTunnels(connection);

        assertThat(copies).hasSize(1);
        assertThat(copies.get(0).getLocalPort()).isEqualTo(8080);
        connection.setSshTunnels(null);
        assertThat(SshTunnelManager.enabledTunnels(connection)).isEmpty();
        assertThat(SshTunnelManager.enabledTunnels(null)).isEmpty();
    }

    @Test
    void enabledTunnelsAreDeepCopiesSoLaterEditsCannotReachTheAttachThread() {
        // The tab shares the tunnel list with the stored connection, which the editor changes in
        // place; the attach thread must only ever see its own copies.
        ServerConnection connection = new ServerConnection("c", "h", 22, "u");
        SSHTunnel original = tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432);
        original.setDescription("db");
        connection.getSshTunnels().add(original);

        List<SSHTunnel> copies = SshTunnelManager.enabledTunnels(connection);
        original.setLocalPort(9999);
        original.setRemoteHost("elsewhere");
        connection.getSshTunnels().clear();

        assertThat(copies).hasSize(1);
        assertThat(copies.get(0)).isNotSameInstanceAs(original);
        assertThat(copies.get(0).getLocalPort()).isEqualTo(8080);
        assertThat(copies.get(0).getRemoteHost()).isEqualTo("db");
        assertThat(copies.get(0).getDescription()).isEqualTo("db");
        assertThat(copies.get(0).isEnabled()).isTrue();
    }

    @Test
    void theClientFilterAdmitsOnlyForwardedChannels() {
        ForwardingFilter filter = SshTunnelManager.clientForwardingFilter();
        SshdSocketAddress address = new SshdSocketAddress("localhost", 3000);

        assertThat(filter.canConnect(TcpForwardingFilter.Type.Forwarded, address, null)).isTrue();
        assertThat(filter.canConnect(TcpForwardingFilter.Type.Direct, address, null)).isFalse();
        assertThat(filter.canListen(address, null)).isFalse();
        assertThat(filter.canForwardAgent(null, "auth-agent-req@openssh.com")).isFalse();
        assertThat(filter.canForwardX11(null, "x11-req")).isFalse();
    }

    @Test
    void markNotStartedRecordsTheReasonForEveryTunnel() {
        SshTunnelManager manager = new SshTunnelManager(() -> true);
        List<SSHTunnel> tunnels = List.of(
            tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432),
            tunnel(TunnelType.DYNAMIC, "localhost", 1080, null, 0));

        manager.markNotStarted(tunnels, Failure.UNSUPPORTED_PROTOCOL);

        assertThat(manager.snapshot()).hasSize(2);
        for (TunnelStatus status : manager.snapshot()) {
            assertThat(status.state()).isEqualTo(State.NOT_STARTED);
            assertThat(status.failure()).isEqualTo(Failure.UNSUPPORTED_PROTOCOL);
        }
        assertThat(manager.activeCount()).isEqualTo(0);
    }

    @Test
    void stopKeepsTheReasonWhyNothingStartedAndClearForgetsIt() {
        SshTunnelManager manager = new SshTunnelManager(() -> true);
        manager.markNotStarted(List.of(tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432)), Failure.NOT_CONFIRMED);

        manager.stop();
        assertThat(manager.snapshot().get(0).failure()).isEqualTo(Failure.NOT_CONFIRMED);

        manager.clear();
        assertThat(manager.snapshot()).isEmpty();
    }

    @Test
    void closeIsIdempotentAndForgetsTheStatuses() {
        SshTunnelManager manager = new SshTunnelManager(() -> true);
        manager.markNotStarted(List.of(tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432)), Failure.NOT_CONFIRMED);

        manager.close();
        manager.close();
        manager.markNotStarted(List.of(tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432)), Failure.NOT_CONFIRMED);

        assertThat(manager.isClosed()).isTrue();
        assertThat(manager.snapshot()).isEmpty();
        assertThat(manager.ownerSession()).isNull();
    }

    @Test
    void aNonLoopbackBindIsReportedAsExposed() {
        assertThat(SshTunnelManager.plannedStatus(tunnel(TunnelType.REMOTE, "localhost", 3000, "0.0.0.0", 9090), false)
            .exposed()).isTrue();
        assertThat(SshTunnelManager.plannedStatus(tunnel(TunnelType.LOCAL, "localhost", 8080, "0.0.0.0", 1), false)
            .exposed()).isFalse();
    }

    @Test
    void sameForwardsComparesWhatWouldListenAndWhereItGoes() {
        SSHTunnel local = tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432);
        SSHTunnel relabelled = tunnel(TunnelType.LOCAL, null, 8080, "DB", 5432);
        relabelled.setDescription("database");

        // Defaults and case are applied, the description does not count: no restart for a label.
        assertThat(SshTunnelManager.sameForwards(List.of(local), List.of(relabelled))).isTrue();
        assertThat(SshTunnelManager.sameForwards(null, List.of())).isTrue();

        assertThat(SshTunnelManager.sameForwards(List.of(local),
            List.of(tunnel(TunnelType.LOCAL, "localhost", 8081, "db", 5432)))).isFalse();
        assertThat(SshTunnelManager.sameForwards(List.of(local),
            List.of(tunnel(TunnelType.LOCAL, "0.0.0.0", 8080, "db", 5432)))).isFalse();
        assertThat(SshTunnelManager.sameForwards(List.of(local),
            List.of(tunnel(TunnelType.REMOTE, "localhost", 8080, "db", 5432)))).isFalse();
        assertThat(SshTunnelManager.sameForwards(List.of(local), List.of())).isFalse();
        SSHTunnel dynamic = tunnel(TunnelType.DYNAMIC, "localhost", 1080, null, 0);
        assertThat(SshTunnelManager.sameForwards(List.of(local, dynamic), List.of(dynamic, local))).isFalse();
    }
}
