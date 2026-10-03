package de.kortty.policy;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * {@link ServerAccessPolicy} evaluated through the real locator/loader/manager chain (dev-override
 * property), covering the target host, the jump host and the local-shell / null exemptions.
 */
class ServerAccessPolicyTest {

    private Path policyFile;

    @AfterMethod
    void reset() throws IOException {
        System.clearProperty(PolicyLocator.OVERRIDE_PROPERTY);
        PolicyManager.resetForTests();
        if (policyFile != null) {
            Files.deleteIfExists(policyFile);
        }
    }

    private void activateDenyList(String... hosts) throws IOException {
        StringBuilder toml = new StringBuilder("""
            [meta]
            schema-version = 1

            [[rule]]
            [rule.servers]
            mode = "deny"
            hosts = [""");
        for (int i = 0; i < hosts.length; i++) {
            toml.append('"').append(hosts[i]).append('"');
            if (i < hosts.length - 1) {
                toml.append(", ");
            }
        }
        toml.append("]\n");
        policyFile = Files.createTempFile("kortty-policy", ".toml");
        Files.writeString(policyFile, toml.toString());
        System.clearProperty("jpackage.app-path");
        System.setProperty(PolicyLocator.OVERRIDE_PROPERTY, policyFile.toString());
        PolicyManager.initialize();
    }

    private static ServerConnection connection(String host, int port) {
        ServerConnection connection = new ServerConnection();
        connection.setHost(host);
        connection.setPort(port);
        connection.setProtocol(ConnectionProtocol.SSH_TCP);
        return connection;
    }

    @Test
    void blocksAndAllowsTargetHostPerPolicy() throws IOException {
        activateDenyList("vault.acme.com");
        assertThat(ServerAccessPolicy.isAllowed(connection("vault.acme.com", 22))).isFalse();
        assertThat(ServerAccessPolicy.firstBlockedTarget(connection("vault.acme.com", 22)))
            .hasValue("vault.acme.com:22");
        assertThat(ServerAccessPolicy.isAllowed(connection("web.acme.com", 22))).isTrue();
    }

    @Test
    void checksTheJumpHostOnlyWhenItIsActuallyUsed() throws IOException {
        activateDenyList("bastion.blocked.com");

        ServerConnection viaEnabledJump = connection("web.acme.com", 22);
        JumpServer enabled = new JumpServer("bastion.blocked.com", 22, "ops");
        enabled.setEnabled(true);
        viaEnabledJump.setJumpServer(enabled);
        // Target is allowed, but the enabled jump host is denied → blocked on the jump host.
        assertThat(ServerAccessPolicy.firstBlockedTarget(viaEnabledJump))
            .hasValue("bastion.blocked.com:22");

        // Same jump host but disabled: korTTY connects directly, so it must not block.
        ServerConnection viaDisabledJump = connection("web.acme.com", 22);
        JumpServer disabled = new JumpServer("bastion.blocked.com", 22, "ops");
        disabled.setEnabled(false);
        viaDisabledJump.setJumpServer(disabled);
        assertThat(ServerAccessPolicy.isAllowed(viaDisabledJump)).isTrue();

        // A jump server that is enabled but has no host is never contacted either.
        ServerConnection viaBlankJump = connection("web.acme.com", 22);
        JumpServer blank = new JumpServer("", 22, "ops");
        blank.setEnabled(true);
        viaBlankJump.setJumpServer(blank);
        assertThat(ServerAccessPolicy.isAllowed(viaBlankJump)).isTrue();
    }

    @Test
    void localShellAndNullConnectionsAreNeverBlocked() throws IOException {
        activateDenyList("*");
        ServerConnection localShell = new ServerConnection();
        localShell.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        localShell.setHost("anything");
        assertThat(ServerAccessPolicy.isAllowed(localShell)).isTrue();
        assertThat(ServerAccessPolicy.isAllowed(null)).isTrue();
    }

    @Test
    void withoutAServerPolicyEverythingIsAllowed() {
        PolicyManager.resetForTests();
        assertThat(ServerAccessPolicy.isAllowed(connection("vault.acme.com", 22))).isTrue();
    }

    @Test
    void partitionKeepsTheAllowedMembersAndNamesEachBlockedTargetOnce() throws IOException {
        activateDenyList("vault.acme.com", "bastion.blocked.com");

        ServerConnection web = connection("web.acme.com", 22);
        ServerConnection vault = connection("vault.acme.com", 22);
        ServerConnection viaBlockedJump = connection("app.acme.com", 22);
        JumpServer jump = new JumpServer("bastion.blocked.com", 22, "ops");
        jump.setEnabled(true);
        viaBlockedJump.setJumpServer(jump);
        ServerConnection vaultAgain = connection("vault.acme.com", 22);
        ServerConnection localShell = new ServerConnection();
        localShell.setProtocol(ConnectionProtocol.LOCAL_SHELL);
        localShell.setHost("vault.acme.com");

        ServerAccessPolicy.Partition partition = ServerAccessPolicy.partition(
            List.of(web, vault, viaBlockedJump, vaultAgain, localShell));

        // The allowed members keep their order; a blocked jump host blocks its member even though
        // the target itself is allowed, and a target blocked twice is named once.
        assertThat(partition.allowed()).containsExactly(web, localShell).inOrder();
        assertThat(partition.blockedTargets())
            .containsExactly("vault.acme.com:22", "bastion.blocked.com:22").inOrder();
        assertThat(partition.blockedTargetList()).isEqualTo("vault.acme.com:22, bastion.blocked.com:22");
    }

    @Test
    void partitionAgainstAnExplicitPolicy() {
        ServerConnection web = connection("web.acme.com", 22);
        ServerConnection db = connection("db.acme.com", 5022);

        ServerAccessPolicy.Partition locked =
            ServerAccessPolicy.partition(Arrays.asList(web, null, db), EffectivePolicy.lockdown());
        assertThat(locked.allowed()).isEmpty();
        assertThat(locked.blockedTargetList()).isEqualTo("web.acme.com:22, db.acme.com:5022");

        ServerAccessPolicy.Partition open =
            ServerAccessPolicy.partition(List.of(web, db), EffectivePolicy.unrestricted());
        assertThat(open.allowed()).containsExactly(web, db).inOrder();
        assertThat(open.blockedTargets()).isEmpty();
        assertThat(open.blockedTargetList()).isEmpty();
    }

    @Test
    void firstBlockedTargetAgainstAnExplicitPolicyIgnoresTheActiveOne() throws IOException {
        activateDenyList("*");
        ServerConnection web = connection("web.acme.com", 22);
        assertThat(ServerAccessPolicy.firstBlockedTarget(web)).hasValue("web.acme.com:22");
        assertThat(ServerAccessPolicy.firstBlockedTarget(web, EffectivePolicy.unrestricted())).isEmpty();
    }
}
