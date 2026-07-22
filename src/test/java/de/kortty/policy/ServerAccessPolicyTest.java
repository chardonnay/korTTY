package de.kortty.policy;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

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
}
