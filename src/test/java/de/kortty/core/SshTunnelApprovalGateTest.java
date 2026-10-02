package de.kortty.core;

import de.kortty.model.ConnectionSource;
import de.kortty.model.SSHTunnel;
import de.kortty.model.ServerConnection;
import de.kortty.model.TunnelType;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;
import static de.kortty.core.SshTunnelTestFixtures.tunnel;

/**
 * The one-time confirmation in front of a tab's SSH tunnels: tunnels that used to be stored but
 * never opened must not start listening after the update until the user has seen them once per
 * connection and tunnel set, and a shared (Teamwork) file must never be able to skip that question.
 */
class SshTunnelApprovalGateTest {

    private Path store;
    private final List<List<SSHTunnel>> questions = new ArrayList<>();

    @BeforeMethod
    void freshStore() throws Exception {
        store = Files.createTempDirectory("kortty-tunnel-gate-").resolve("ssh-tunnel-approvals.properties");
        questions.clear();
    }

    /** A new tab: its own answers, the persisted approvals shared with every other tab. */
    private SshTunnelApprovalGate newTab() {
        return new SshTunnelApprovalGate(() -> new SshTunnelApprovals(store));
    }

    private Predicate<List<SSHTunnel>> answer(boolean allow) {
        return startable -> {
            questions.add(startable);
            return allow;
        };
    }

    private static ServerConnection connection(String id, ConnectionSource source) {
        ServerConnection connection = new ServerConnection("db", "db.example.com", 22, "u");
        connection.setId(id);
        connection.setConnectionSource(source);
        return connection;
    }

    private static final SSHTunnel LOCAL = tunnel(TunnelType.LOCAL, "localhost", 15432, "db", 5432);
    private static final SSHTunnel REMOTE = tunnel(TunnelType.REMOTE, "localhost", 3000, "localhost", 9090);

    @Test
    void anUnconfirmedSetIsAskedOnceAndTheApprovalHoldsForLaterTabsAndRestarts() {
        ServerConnection own = connection("conn-1", ConnectionSource.LOCAL);
        SshTunnelApprovalGate tab = newTab();

        assertThat(tab.allows(own, List.of(LOCAL), answer(true))).isTrue();
        assertThat(questions).hasSize(1);
        assertThat(questions.get(0)).containsExactly(LOCAL);

        assertWithMessage("a reconnect of the same tab must not ask again")
            .that(tab.allows(own, List.of(LOCAL), answer(false))).isTrue();
        assertWithMessage("another tab, or the next start (a fresh store), knows the approval")
            .that(newTab().allows(own, List.of(LOCAL), answer(false))).isTrue();
        assertThat(questions).hasSize(1);
    }

    @Test
    void notNowHoldsForTheTabOnlyAndIsNeverStored() {
        ServerConnection own = connection("conn-1", ConnectionSource.LOCAL);
        SshTunnelApprovalGate tab = newTab();

        assertThat(tab.allows(own, List.of(LOCAL), answer(false))).isFalse();
        assertWithMessage("the tab's reconnects keep the answer without asking")
            .that(tab.allows(own, List.of(LOCAL), answer(true))).isFalse();
        assertThat(questions).hasSize(1);
        assertThat(Files.exists(store)).isFalse();

        assertWithMessage("a new tab asks again").that(newTab().allows(own, List.of(LOCAL), answer(true))).isTrue();
        assertThat(questions).hasSize(2);
    }

    @Test
    void aChangedTunnelSetIsAskedAgain() {
        ServerConnection own = connection("conn-1", ConnectionSource.LOCAL);
        newTab().allows(own, List.of(LOCAL), answer(true));

        assertThat(newTab().allows(own, List.of(LOCAL, REMOTE), answer(true))).isTrue();
        own.setHost("other.example.com");
        assertThat(newTab().allows(own, List.of(LOCAL, REMOTE), answer(false))).isFalse();

        assertThat(questions).hasSize(3);
    }

    @Test
    void aSharedConnectionIsAskedOnlyAboutTheTunnelsThatWouldOpen() {
        ServerConnection shared = connection("team-1", ConnectionSource.TEAMWORK);

        assertThat(newTab().allows(shared, List.of(REMOTE, LOCAL), answer(true))).isTrue();

        assertThat(questions).hasSize(1);
        assertWithMessage("a shared remote tunnel is refused anyway and is not offered")
            .that(questions.get(0)).containsExactly(LOCAL);
    }

    @Test
    void aSetInWhichNothingWouldOpenNeedsNoQuestion() {
        ServerConnection shared = connection("team-1", ConnectionSource.TEAMWORK);

        assertThat(newTab().allows(shared, List.of(REMOTE), answer(false))).isTrue();
        assertThat(newTab().allows(connection("conn-1", ConnectionSource.LOCAL),
            List.of(tunnel(TunnelType.LOCAL, "localhost", 0, "db", 5432)), answer(false))).isTrue();

        assertThat(questions).isEmpty();
        assertThat(Files.exists(store)).isFalse();
    }

    @Test
    void anApprovalOfTheUsersOwnConnectionNeverCoversASharedOneWithTheSameId() {
        // A shared file can carry any connection id; the question for it must still say that its
        // tunnels come from the shared file.
        newTab().allows(connection("same-id", ConnectionSource.LOCAL), List.of(LOCAL), answer(true));

        assertThat(newTab().allows(connection("same-id", ConnectionSource.TEAMWORK), List.of(LOCAL), answer(false)))
            .isFalse();
        assertThat(questions).hasSize(2);
    }

    @Test
    void withoutAConnectionIdTheTabStillRemembersItsAnswer() {
        ServerConnection quick = connection(null, ConnectionSource.LOCAL);
        SshTunnelApprovalGate tab = newTab();

        assertThat(tab.allows(quick, List.of(LOCAL), answer(true))).isTrue();
        assertThat(tab.allows(quick, List.of(LOCAL), answer(false))).isTrue();
        assertThat(questions).hasSize(1);
        assertWithMessage("nothing to store the approval under, so a new tab asks again")
            .that(newTab().allows(quick, List.of(LOCAL), answer(false))).isFalse();
    }
}
