package de.kortty.ui;

import de.kortty.core.LanguageManager;
import de.kortty.core.SshTunnelManager;
import de.kortty.core.SshTunnelManager.Failure;
import de.kortty.core.SshTunnelManager.State;
import de.kortty.core.SshTunnelManager.TunnelStatus;
import de.kortty.model.SSHTunnel;
import de.kortty.model.TunnelType;
import org.testng.annotations.AfterClass;
import org.testng.annotations.BeforeClass;
import org.testng.annotations.Test;

import java.util.List;
import java.util.Locale;

import static com.google.common.truth.Truth.assertThat;

class TunnelStatusSupportTest {

    private Locale previous;

    @BeforeClass
    void english() {
        previous = LanguageManager.getInstance().getCurrentLocale();
        LanguageManager.getInstance().setLocale(Locale.ENGLISH);
    }

    @AfterClass(alwaysRun = true)
    void restoreLocale() {
        // The locale is global: leave it as found so later tests see their own expectations.
        if (previous != null) {
            LanguageManager.getInstance().setLocale(previous);
        }
    }

    private static SSHTunnel tunnel(TunnelType type, String localHost, int localPort, String remoteHost, int remotePort) {
        SSHTunnel tunnel = new SSHTunnel();
        tunnel.setEnabled(true);
        tunnel.setType(type);
        tunnel.setLocalHost(localHost);
        tunnel.setLocalPort(localPort);
        tunnel.setRemoteHost(remoteHost);
        tunnel.setRemotePort(remotePort);
        return tunnel;
    }

    private static TunnelStatus status(SSHTunnel tunnel, State state, Failure failure, String detail) {
        TunnelStatus planned = SshTunnelManager.plannedStatus(tunnel, false);
        return new TunnelStatus(planned.type(), planned.bindHost(), planned.bindPort(), planned.targetHost(),
            planned.targetPort(), planned.description(), state,
            state == State.ACTIVE ? planned.bindHost() + ":" + planned.bindPort() : null, failure, detail);
    }

    private static final SSHTunnel LOCAL = tunnel(TunnelType.LOCAL, "127.0.0.1", 8080, "db", 5432);
    private static final SSHTunnel REMOTE = tunnel(TunnelType.REMOTE, "localhost", 3000, "localhost", 9090);
    private static final SSHTunnel DYNAMIC = tunnel(TunnelType.DYNAMIC, "localhost", 1080, null, 0);

    @Test
    void describeReadsListenerFirstForEveryType() {
        assertThat(TunnelStatusSupport.describe(LOCAL)).isEqualTo("L 127.0.0.1:8080 -> db:5432");
        // REMOTE: the server listens on remoteHost:remotePort, this computer is the target.
        assertThat(TunnelStatusSupport.describe(REMOTE)).isEqualTo("R localhost:9090 -> localhost:3000");
        assertThat(TunnelStatusSupport.describe(DYNAMIC)).isEqualTo("D localhost:1080 (SOCKS)");
        assertThat(TunnelStatusSupport.describe(tunnel(TunnelType.LOCAL, "::1", 8080, "db", 5432)))
            .isEqualTo("L [::1]:8080 -> db:5432");
        assertThat(TunnelStatusSupport.describe(tunnel(TunnelType.LOCAL, "", 8080, " ", 5432)))
            .isEqualTo("L localhost:8080 -> localhost:5432");
    }

    @Test
    void noTunnelsMeansNoStatusBarSegmentAndNoTooltip() {
        assertThat(TunnelStatusSupport.statusBarSummary(List.of())).isNull();
        assertThat(TunnelStatusSupport.statusBarSummary(null)).isNull();
        assertThat(TunnelStatusSupport.tooltip(List.of())).isNull();
    }

    @Test
    void theSummaryCountsActiveTunnels() {
        String summary = TunnelStatusSupport.statusBarSummary(List.of(
            status(LOCAL, State.ACTIVE, Failure.NONE, null),
            status(REMOTE, State.ACTIVE, Failure.NONE, null),
            status(DYNAMIC, State.STARTING, Failure.NONE, null)));

        assertThat(summary).isEqualTo("Tunnels: 2/3 active");
    }

    @Test
    void theFirstFailureIsAppendedWithItsDetail() {
        String summary = TunnelStatusSupport.statusBarSummary(List.of(
            status(LOCAL, State.FAILED, Failure.BIND_FAILED, "Address already in use"),
            status(REMOTE, State.FAILED, Failure.SERVER_REJECTED, "denied"),
            status(DYNAMIC, State.ACTIVE, Failure.NONE, null)));

        assertThat(summary).isEqualTo(
            "Tunnels: 1/3 active | Tunnel L 127.0.0.1:8080 -> db:5432 failed: Address already in use");
    }

    @Test
    void aListenerOtherComputersMayReachIsFlagged() {
        SSHTunnel exposedRemote = tunnel(TunnelType.REMOTE, "localhost", 3000, "0.0.0.0", 9090);

        String summary = TunnelStatusSupport.statusBarSummary(List.of(status(exposedRemote, State.ACTIVE, Failure.NONE, null)));
        String tooltip = TunnelStatusSupport.tooltip(List.of(status(exposedRemote, State.ACTIVE, Failure.NONE, null)));

        assertThat(summary).isEqualTo(
            "Tunnels: 1/1 active | Tunnel R 0.0.0.0:9090 -> localhost:3000 may be reachable from other computers");
        assertThat(tooltip).contains("may be reachable from other computers");
    }

    @Test
    void aSetThatWasNotStartedGetsOneSentence() {
        assertThat(TunnelStatusSupport.statusBarSummary(List.of(
            status(LOCAL, State.NOT_STARTED, Failure.UNSUPPORTED_PROTOCOL, null),
            status(DYNAMIC, State.NOT_STARTED, Failure.UNSUPPORTED_PROTOCOL, null))))
            .isEqualTo("SSH tunnels need the SSH protocol and were not started");
        assertThat(TunnelStatusSupport.statusBarSummary(List.of(
            status(LOCAL, State.NOT_STARTED, Failure.POLICY_DENIED, null))))
            .isEqualTo("SSH tunnels are disabled by your organization and were not started");
        assertThat(TunnelStatusSupport.statusBarSummary(List.of(
            status(LOCAL, State.NOT_STARTED, Failure.NOT_CONFIRMED, null))))
            .isEqualTo("SSH tunnels were not started: not allowed for this tab");
    }

    @Test
    void sharedConnectionRefusalsExplainTheWayOut() {
        String summary = TunnelStatusSupport.statusBarSummary(List.of(
            status(REMOTE, State.FAILED, Failure.SHARED_REMOTE, null)));

        assertThat(summary).startsWith("Tunnels: 0/1 active | Tunnel R localhost:9090 -> localhost:3000 failed: ");
        assertThat(summary).contains("duplicate the connection");
    }

    @Test
    void theTooltipHasOneLinePerTunnel() {
        SSHTunnel described = tunnel(TunnelType.LOCAL, "localhost", 5433, "db", 5432);
        described.setDescription("reporting db");

        String tooltip = TunnelStatusSupport.tooltip(List.of(
            status(LOCAL, State.ACTIVE, Failure.NONE, null),
            status(described, State.FAILED, Failure.BIND_FAILED, "Address already in use"),
            status(DYNAMIC, State.STOPPED, Failure.NONE, null)));

        assertThat(tooltip.split("\n")).asList().containsExactly(
            "L 127.0.0.1:8080 -> db:5432: active on 127.0.0.1:8080",
            "L localhost:5433 -> db:5432 (reporting db): failed: Address already in use",
            "D localhost:1080 (SOCKS): stopped").inOrder();
    }

    @Test
    void aDeclinedSetTellsHowToBeAskedAgain() {
        String tooltip = TunnelStatusSupport.tooltip(List.of(status(LOCAL, State.NOT_STARTED, Failure.NOT_CONFIRMED, null)));

        assertThat(tooltip).isEqualTo("L 127.0.0.1:8080 -> db:5432: not started: not allowed for this tab\n"
            + "Open the connection in a new tab to be asked again.");
    }

    @Test
    void theApprovalQuestionListsTheTunnelsAndFlagsSharedConnections() {
        SSHTunnel exposed = tunnel(TunnelType.DYNAMIC, "0.0.0.0", 1080, null, 0);
        exposed.setDescription("browser");

        String own = TunnelStatusSupport.approvalText("prod-db", List.of(LOCAL, exposed), false);
        String shared = TunnelStatusSupport.approvalText("prod-db", List.of(LOCAL), true);

        assertThat(own).contains("for prod-db");
        assertThat(own).contains("  L 127.0.0.1:8080 -> db:5432\n");
        assertThat(own).contains("  D 0.0.0.0:1080 (SOCKS) (browser) - may be reachable from other computers");
        assertThat(own).doesNotContain("Teamwork");
        assertThat(own).contains("asked once per connection");
        assertThat(shared).contains("shared (Teamwork) connection");
    }
}
