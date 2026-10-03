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
        assertThat(kindOf(e)).isEqualTo(JumpHostSupport.PermanentJumpFailure.Kind.CONFIGURATION);
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
        assertThat(kindOf(e)).isEqualTo(JumpHostSupport.PermanentJumpFailure.Kind.CONFIGURATION);
    }

    @Test
    void aPasswordJumpWithALockedVaultFailsBeforeAnyNetwork() throws Exception {
        // port 1 has nothing listening: a decrypt after connect would surface as a ConnectException.
        // A locked vault must fail on its own, before the bastion is contacted or its key prompted.
        String encrypted = new de.kortty.security.EncryptionService()
            .encryptPassword("JUMP-secret", "unit-master".toCharArray());
        ServerConnection c = withJump(j -> {
            j.setEnabled(true);
            j.setHost("127.0.0.1");
            j.setPort(1);
            j.setUsername("u");
            j.setAuthMethod(AuthMethod.PASSWORD);
            j.setEncryptedPassword(encrypted);
        });
        java.io.IOException e = expectThrows(java.io.IOException.class, () ->
            JumpHostSupport.open(c, SshHostKeyTrustManager.shared(), null, java.time.Duration.ofSeconds(2)));
        assertThat(e).hasMessageThat().contains("vault is locked");
        assertThat(kindOf(e)).isEqualTo(JumpHostSupport.PermanentJumpFailure.Kind.CREDENTIALS);
    }

    @Test
    void aPasswordJumpWithoutAStoredPasswordFailsBeforeAnyNetwork() {
        ServerConnection c = withJump(j -> {
            j.setEnabled(true);
            j.setHost("127.0.0.1");
            j.setPort(1);
            j.setUsername("u");
            j.setAuthMethod(AuthMethod.PASSWORD);
            j.setEncryptedPassword(null);
        });
        java.io.IOException e = expectThrows(java.io.IOException.class, () ->
            JumpHostSupport.open(c, SshHostKeyTrustManager.shared(), "unit-master".toCharArray(),
                java.time.Duration.ofSeconds(2)));
        assertThat(e).hasMessageThat().contains("not available");
        assertThat(kindOf(e)).isEqualTo(JumpHostSupport.PermanentJumpFailure.Kind.CREDENTIALS);
    }

    @Test
    void aJumpPasswordThatCannotBeDecryptedFailsBeforeAnyNetwork() throws Exception {
        // Encrypted under another master password: the stored value is unusable, not the network.
        String encrypted = new de.kortty.security.EncryptionService()
            .encryptPassword("JUMP-secret", "the-real-master".toCharArray());
        ServerConnection c = withJump(j -> {
            j.setEnabled(true);
            j.setHost("127.0.0.1");
            j.setPort(1);
            j.setUsername("u");
            j.setAuthMethod(AuthMethod.PASSWORD);
            j.setEncryptedPassword(encrypted);
        });
        java.io.IOException e = expectThrows(java.io.IOException.class, () ->
            JumpHostSupport.open(c, SshHostKeyTrustManager.shared(), "a-wrong-master".toCharArray(),
                java.time.Duration.ofSeconds(2)));
        assertThat(e).hasMessageThat().contains("could not be decrypted");
        assertThat(kindOf(e)).isEqualTo(JumpHostSupport.PermanentJumpFailure.Kind.CREDENTIALS);
    }

    @Test
    void anUnreachableBastionIsNotAPermanentFailure() throws Exception {
        // With usable credentials the hop gets as far as the network; a refused connection there is
        // transient and must stay a plain IOException so the caller retries it.
        char[] master = "unit-master".toCharArray();
        String encrypted = new de.kortty.security.EncryptionService().encryptPassword("JUMP-secret", master);
        int closedPort = LoopbackSshServers.closedPort();
        ServerConnection c = withJump(j -> {
            j.setEnabled(true);
            j.setHost("127.0.0.1");
            j.setPort(closedPort);
            j.setUsername("u");
            j.setAuthMethod(AuthMethod.PASSWORD);
            j.setEncryptedPassword(encrypted);
        });
        java.io.IOException e = expectThrows(java.io.IOException.class, () ->
            JumpHostSupport.open(c, SshHostKeyTrustManager.shared(), master, java.time.Duration.ofSeconds(5)));
        assertThat(e).isNotInstanceOf(JumpHostSupport.PermanentJumpFailure.class);
    }

    private static JumpHostSupport.PermanentJumpFailure.Kind kindOf(java.io.IOException e) {
        assertThat(e).isInstanceOf(JumpHostSupport.PermanentJumpFailure.class);
        return ((JumpHostSupport.PermanentJumpFailure) e).kind();
    }
}
