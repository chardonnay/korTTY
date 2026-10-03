package de.kortty.core;

import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import de.kortty.model.TunnelType;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static de.kortty.core.SshTunnelTestFixtures.tunnel;

/** The one-time confirmation store for tunnel sets: what counts as the same set, and persistence. */
class SshTunnelApprovalsTest {

    private static final ServerConnection SERVER = new ServerConnection("c", "db.example.com", 22, "u");

    @Test
    void theHashIgnoresOrderDescriptionsAndDefaultSpellings() {
        SSHTunnel local = tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432);
        SSHTunnel dynamic = tunnel(TunnelType.DYNAMIC, "", 1080, null, 0);
        SSHTunnel dynamicSpelledOut = tunnel(TunnelType.DYNAMIC, "LOCALHOST", 1080, "ignored-for-socks", 0);
        dynamicSpelledOut.setDescription("socks for the browser");

        assertThat(SshTunnelApprovals.tunnelSetHash(SERVER, List.of(local, dynamic)))
            .isEqualTo(SshTunnelApprovals.tunnelSetHash(SERVER, List.of(dynamicSpelledOut, local)));
    }

    @Test
    void aChangedTunnelAnAddedTunnelOrAnotherServerIsANewSet() {
        SSHTunnel local = tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432);
        String approved = SshTunnelApprovals.tunnelSetHash(SERVER, List.of(local));

        assertThat(SshTunnelApprovals.tunnelSetHash(SERVER,
            List.of(tunnel(TunnelType.LOCAL, "0.0.0.0", 8080, "db", 5432)))).isNotEqualTo(approved);
        assertThat(SshTunnelApprovals.tunnelSetHash(SERVER,
            List.of(local, tunnel(TunnelType.DYNAMIC, "localhost", 1080, null, 0)))).isNotEqualTo(approved);
        assertThat(SshTunnelApprovals.tunnelSetHash(new ServerConnection("c", "other.example.com", 22, "u"),
            List.of(local))).isNotEqualTo(approved);
        assertThat(SshTunnelApprovals.tunnelSetHash(SERVER,
            List.of(tunnel(TunnelType.REMOTE, "localhost", 8080, "db", 5432)))).isNotEqualTo(approved);
    }

    @Test
    void anApprovalHoldsForItsConnectionAndSetOnlyAndSurvivesARestart() throws Exception {
        Path store = Files.createTempDirectory("kortty-tunnel-approvals-").resolve("approvals.properties");
        String hash = SshTunnelApprovals.tunnelSetHash(SERVER, List.of(tunnel(TunnelType.LOCAL, "localhost", 8080, "db", 5432)));
        SshTunnelApprovals approvals = new SshTunnelApprovals(store);
        assertThat(approvals.isApproved("conn-1", hash)).isFalse();

        approvals.approve("conn-1", hash);

        assertThat(approvals.isApproved("conn-1", hash)).isTrue();
        assertThat(approvals.isApproved("conn-2", hash)).isFalse();
        assertThat(approvals.isApproved("conn-1", "other")).isFalse();
        SshTunnelApprovals reloaded = new SshTunnelApprovals(store);
        assertThat(reloaded.isApproved("conn-1", hash)).isTrue();
    }

    @Test
    void aNewApprovalReplacesTheOldOneForTheSameConnection() throws Exception {
        Path store = Files.createTempDirectory("kortty-tunnel-approvals-").resolve("approvals.properties");
        SshTunnelApprovals approvals = new SshTunnelApprovals(store);

        approvals.approve("conn-1", "first");
        approvals.approve("conn-1", "second");

        SshTunnelApprovals reloaded = new SshTunnelApprovals(store);
        assertThat(reloaded.isApproved("conn-1", "second")).isTrue();
        assertThat(reloaded.isApproved("conn-1", "first")).isFalse();
    }

    @Test
    void missingIdsAreNeverApprovedAndAnUnreadableFileOnlyMeansAskingAgain() throws Exception {
        Path store = Files.createTempDirectory("kortty-tunnel-approvals-").resolve("approvals.properties");
        Files.writeString(store, "conn-1=\\uZZZZ broken escape\n");
        SshTunnelApprovals approvals = new SshTunnelApprovals(store);

        assertThat(approvals.isApproved("conn-1", "anything")).isFalse();
        approvals.approve(null, "hash");
        approvals.approve(" ", "hash");
        assertThat(approvals.isApproved(null, "hash")).isFalse();
        assertThat(approvals.isApproved(" ", "hash")).isFalse();
    }
}
