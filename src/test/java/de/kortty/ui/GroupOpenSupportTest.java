package de.kortty.ui;

import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

import static com.google.common.truth.Truth.assertThat;

/**
 * The rule deciding which members of a group Quick Connect's Open Group opens: local shells and
 * SSH key auth open without a password, as in Duplicate; only a password login without a stored
 * password is skipped.
 */
class GroupOpenSupportTest {

    @Test
    void aKeyAuthMemberOpensWithoutAPasswordAndWithoutLookingOneUp() {
        ServerConnection key = connection(ConnectionProtocol.SSH_TCP, AuthMethod.PUBLIC_KEY);
        List<ServerConnection> lookedUp = new ArrayList<>();

        GroupOpenSupport.Decision decision = GroupOpenSupport.decide(key, recording(lookedUp, null));

        assertThat(decision.open()).isTrue();
        assertThat(decision.password()).isNull();
        assertThat(lookedUp).isEmpty();
    }

    @Test
    void aLocalShellOpensWithoutAPasswordWhateverItsAuthMethodSays() {
        // The editor disables authentication for a local shell, so its stored auth method is
        // whatever it was before; it must not decide anything.
        for (AuthMethod method : AuthMethod.values()) {
            ServerConnection shell = connection(ConnectionProtocol.LOCAL_SHELL, method);
            List<ServerConnection> lookedUp = new ArrayList<>();

            GroupOpenSupport.Decision decision = GroupOpenSupport.decide(shell, recording(lookedUp, null));

            assertThat(decision.open()).isTrue();
            assertThat(decision.password()).isNull();
            assertThat(lookedUp).isEmpty();
        }
    }

    @Test
    void aPasswordMemberOpensWithItsStoredPassword() {
        for (AuthMethod method : List.of(AuthMethod.PASSWORD, AuthMethod.KEYBOARD_INTERACTIVE)) {
            ServerConnection member = connection(ConnectionProtocol.SSH_TCP, method);
            List<ServerConnection> lookedUp = new ArrayList<>();

            GroupOpenSupport.Decision decision = GroupOpenSupport.decide(member, recording(lookedUp, "s3cret"));

            assertThat(decision.open()).isTrue();
            assertThat(decision.password()).isEqualTo("s3cret");
            assertThat(lookedUp).containsExactly(member);
        }
    }

    @Test
    void aPasswordMemberWithoutAStoredPasswordIsSkipped() {
        for (AuthMethod method : List.of(AuthMethod.PASSWORD, AuthMethod.KEYBOARD_INTERACTIVE)) {
            for (String stored : new String[] {null, ""}) {
                ServerConnection member = connection(ConnectionProtocol.SSH_TCP, method);

                GroupOpenSupport.Decision decision = GroupOpenSupport.decide(member, c -> stored);

                assertThat(decision.open()).isFalse();
                assertThat(decision.password()).isNull();
            }
        }
    }

    @Test
    void aMoshMemberFollowsItsAuthMethodLikeSsh() {
        assertThat(GroupOpenSupport.opensWithoutPassword(
            connection(ConnectionProtocol.MOSH, AuthMethod.PUBLIC_KEY))).isTrue();
        assertThat(GroupOpenSupport.opensWithoutPassword(
            connection(ConnectionProtocol.MOSH, AuthMethod.PASSWORD))).isFalse();
    }

    @Test
    void theDecisionNeverPrintsThePassword() {
        GroupOpenSupport.Decision decision = new GroupOpenSupport.Decision(true, "s3cret");

        assertThat(decision.toString()).doesNotContain("s3cret");
    }

    private static ServerConnection connection(ConnectionProtocol protocol, AuthMethod method) {
        ServerConnection connection = new ServerConnection("member", "host.example", 22, "user");
        connection.setProtocol(protocol);
        connection.setAuthMethod(method);
        return connection;
    }

    private static Function<ServerConnection, String> recording(List<ServerConnection> calls, String password) {
        return connection -> {
            calls.add(connection);
            return password;
        };
    }
}
