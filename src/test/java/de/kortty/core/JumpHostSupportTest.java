package de.kortty.core;

import de.kortty.model.AuthMethod;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class JumpHostSupportTest {

    private static ServerConnection withJump(java.util.function.Consumer<JumpServer> configure) {
        ServerConnection c = new ServerConnection("t", "target.example", 22, "user");
        JumpServer jump = new JumpServer();
        configure.accept(jump);
        c.setJumpServer(jump);
        return c;
    }

    @Test
    void isActiveRequiresEnabledFlagAndHost() {
        assertThat(JumpHostSupport.isActive(new ServerConnection("t", "h", 22, "u"))).isFalse();
        assertThat(JumpHostSupport.isActive(withJump(j -> { j.setEnabled(false); j.setHost("bastion"); }))).isFalse();
        assertThat(JumpHostSupport.isActive(withJump(j -> { j.setEnabled(true); j.setHost(""); }))).isFalse();
        assertThat(JumpHostSupport.isActive(withJump(j -> { j.setEnabled(true); j.setHost("bastion"); }))).isTrue();
    }

    @Test
    void isActiveToleratesANullJumpServer() {
        ServerConnection c = new ServerConnection("t", "h", 22, "u");
        c.setJumpServer(null);
        assertThat(JumpHostSupport.isActive(c)).isFalse();
    }

    @Test
    void openRejectsAConnectionWithoutAnEnabledJump() {
        java.io.IOException e = expectThrows(java.io.IOException.class, () ->
            JumpHostSupport.open(new ServerConnection("t", "h", 22, "u"),
                SshHostKeyTrustManager.shared(), null, java.time.Duration.ofSeconds(1)));
        assertThat(e).hasMessageThat().contains("not configured");
    }

    @Test
    void openRejectsAJumpWithoutAUsername() {
        ServerConnection c = withJump(j -> { j.setEnabled(true); j.setHost("bastion"); j.setUsername(null); });
        java.io.IOException e = expectThrows(java.io.IOException.class, () ->
            JumpHostSupport.open(c, SshHostKeyTrustManager.shared(), null, java.time.Duration.ofSeconds(1)));
        assertThat(e).hasMessageThat().contains("username");
    }

    @Test
    void aKeyAuthJumpWithNoKeyFileFailsBeforeAnyNetwork() {
        // port 1 has nothing listening: if the check did not run before connect, this would throw a
        // ConnectException instead of the config message. It must fail on configuration, not network.
        ServerConnection c = withJump(j -> {
            j.setEnabled(true);
            j.setHost("127.0.0.1");
            j.setPort(1);
            j.setUsername("u");
            j.setAuthMethod(AuthMethod.PUBLIC_KEY);
            j.setPrivateKeyPath(null);
        });
        java.io.IOException e = expectThrows(java.io.IOException.class, () ->
            JumpHostSupport.open(c, SshHostKeyTrustManager.shared(), null, java.time.Duration.ofSeconds(2)));
        assertThat(e).hasMessageThat().contains("no key file is configured");
    }
}
