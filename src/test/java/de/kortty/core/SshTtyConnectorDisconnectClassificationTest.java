package de.kortty.core;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

/**
 * Pins how a closed SSH shell channel is reported to the disconnect listener. The critical case:
 * a channel that closed without any remote exit status or signal means the transport died
 * (network drop, server gone) and must be reported as an error, so the tab stays open and offers
 * a reconnect instead of silently closing like a normal logout.
 */
class SshTtyConnectorDisconnectClassificationTest {

    @Test
    void remoteExitWithStatusZeroIsANormalExit() {
        SshTtyConnector.ChannelCloseClassification classification =
            SshTtyConnector.classifyChannelClose(0, null);

        assertThat(classification.wasError()).isFalse();
        assertThat(classification.reason()).isEqualTo("Normal exit");
    }

    @Test
    void remoteExitWithNonZeroStatusIsAnError() {
        SshTtyConnector.ChannelCloseClassification classification =
            SshTtyConnector.classifyChannelClose(1, null);

        assertThat(classification.wasError()).isTrue();
        assertThat(classification.reason()).contains("exit code: 1");
    }

    @Test
    void remoteExitSignalIsAnError() {
        SshTtyConnector.ChannelCloseClassification classification =
            SshTtyConnector.classifyChannelClose(null, "KILL");

        assertThat(classification.wasError()).isTrue();
        assertThat(classification.reason()).contains("KILL");
    }

    @Test
    void exitSignalWinsOverExitStatus() {
        SshTtyConnector.ChannelCloseClassification classification =
            SshTtyConnector.classifyChannelClose(0, "TERM");

        assertThat(classification.wasError()).isTrue();
        assertThat(classification.reason()).contains("TERM");
    }

    @Test
    void closeWithoutExitStatusOrSignalIsAConnectionLoss() {
        SshTtyConnector.ChannelCloseClassification classification =
            SshTtyConnector.classifyChannelClose(null, null);

        assertThat(classification.wasError()).isTrue();
        assertThat(classification.reason()).isEqualTo("Connection lost");
    }

    @Test
    void emptyExitSignalCountsAsAbsent() {
        SshTtyConnector.ChannelCloseClassification classification =
            SshTtyConnector.classifyChannelClose(null, "");

        assertThat(classification.wasError()).isTrue();
        assertThat(classification.reason()).isEqualTo("Connection lost");
    }

    @Test
    void connectorWithoutChannelNeverReportsAConnectionLoss() {
        SshTtyConnector connector = new SshTtyConnector(
            new de.kortty.model.ServerConnection(), null);

        assertThat(connector.wasConnectionLost()).isFalse();
    }

    @Test
    void livenessProbeDetectsADeadTransportWithinTenSeconds() {
        // Worst case: the transport dies right after a successful probe. The next probe starts
        // after one interval and the death is confirmed by a second timed-out probe.
        long worstCaseMs = SshTtyConnector.LIVENESS_PROBE_INTERVAL_MS
            + 2 * SshTtyConnector.LIVENESS_PROBE_TIMEOUT_MS;

        assertThat(worstCaseMs).isAtMost(10_000L);
    }
}
