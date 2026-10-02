package de.kortty.core;

import de.kortty.model.AuthMethod;
import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import de.kortty.security.EncryptionService;
import org.apache.sshd.server.SshServer;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.DataProvider;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * Drives the terminal connector through a password jump server to a target that does not log in
 * with a key, against two real loopback SSHD servers. Before the fix only a {@code PUBLIC_KEY}
 * target was handed the master password, so every other target failed on the jump password with
 * "vault is locked" — and that permanent failure was retried like a network error.
 */
class SshTtyConnectorJumpHostIntegrationTest {

    private static final char[] MASTER = "it-master".toCharArray();

    private SshServer bastion;
    private SshServer target;
    private final Set<String> passwordsSeenByBastion = ConcurrentHashMap.newKeySet();
    private final Set<String> passwordsSeenByTarget = ConcurrentHashMap.newKeySet();
    private final AtomicInteger bastionSessions = new AtomicInteger();
    private final AtomicInteger targetSessions = new AtomicInteger();

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (bastion != null) {
            bastion.stop(true);
            bastion = null;
        }
        if (target != null) {
            target.stop(true);
            target = null;
        }
        passwordsSeenByBastion.clear();
        passwordsSeenByTarget.clear();
        bastionSessions.set(0);
        targetSessions.set(0);
    }

    @DataProvider
    Object[][] targetsWithoutAKey() {
        return new Object[][] {{AuthMethod.PASSWORD}, {AuthMethod.KEYBOARD_INTERACTIVE}};
    }

    @Test(dataProvider = "targetsWithoutAKey", timeOut = 60_000)
    void aTargetWithoutAKeyReachesItsShellThroughAPasswordJump(AuthMethod targetAuth) throws Exception {
        Path tmp = Files.createTempDirectory("kortty-ssh-jump-it-");
        startServers(tmp);
        ServerConnection connection = targetBehindPasswordJump(targetAuth, bastion.getPort());
        LoopbackSshServers.AcceptingPrompt prompt = new LoopbackSshServers.AcceptingPrompt();

        SshTtyConnector connector = new SshTtyConnector(
            connection, "TARGET-secret", new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), prompt));
        connector.configureVault(null, MASTER);
        try {
            assertThat(connector.connect()).isTrue();
            assertThat(readUntil(connector, LoopbackSshServers.BANNER)).contains(LoopbackSshServers.BANNER);
        } finally {
            connector.close();
        }

        // Credential separation: each hop only ever saw its own password.
        assertThat(passwordsSeenByTarget).containsExactly("TARGET-secret");
        assertThat(passwordsSeenByBastion).containsExactly("JUMP-secret");
        // Both host keys were pinned on first use: the bastion's and the target's.
        assertThat(prompt.firstUsePrompts.get()).isEqualTo(2);
    }

    @Test(timeOut = 60_000)
    void aLockedVaultFailsOnceWithoutEverContactingTheBastion() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-ssh-jump-locked-");
        startServers(tmp);
        ServerConnection connection = targetBehindPasswordJump(AuthMethod.PASSWORD, bastion.getPort());
        LoopbackSshServers.AcceptingPrompt prompt = new LoopbackSshServers.AcceptingPrompt();

        SshTtyConnector connector = new SshTtyConnector(
            connection, "TARGET-secret", new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), prompt));
        connector.configureVault(null, null); // the vault is locked

        SshTtyConnector.ConnectionConfigurationException e =
            expectThrows(SshTtyConnector.ConnectionConfigurationException.class, connector::connect);

        assertThat(e).hasMessageThat().contains("vault is locked");
        assertThat(e).isNotInstanceOf(SshTtyConnector.HostKeyVerificationException.class);
        // The password was resolved before any socket: no session, no host-key prompt, no login.
        assertThat(bastionSessions.get()).isEqualTo(0);
        assertThat(targetSessions.get()).isEqualTo(0);
        assertThat(prompt.firstUsePrompts.get()).isEqualTo(0);
        assertThat(passwordsSeenByBastion).isEmpty();
        assertThat(connector.isConnected()).isFalse();
    }

    @Test(timeOut = 60_000)
    void anUnreachableJumpServerStaysARetriableFailure() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-ssh-jump-down-");
        target = LoopbackSshServers.start(tmp.resolve("target.ser"), passwordsSeenByTarget, targetSessions, true);
        ServerConnection connection = targetBehindPasswordJump(AuthMethod.PASSWORD, LoopbackSshServers.closedPort());

        SshTtyConnector connector = new SshTtyConnector(
            connection, "TARGET-secret",
            new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), new LoopbackSshServers.AcceptingPrompt()));
        connector.configureVault(null, MASTER);

        // A network failure on the hop returns false — the caller retries it — instead of throwing.
        assertThat(connector.connect()).isFalse();
        assertThat(connector.getLastFailureMessage()).isPresent();
        assertThat(targetSessions.get()).isEqualTo(0);
    }

    @Test
    void configureVaultAlwaysKeepsTheMasterPasswordButTheKeyManagerOnlyForKeyTargets() throws Exception {
        ServerConnection passwordTarget = new ServerConnection("t", "127.0.0.1", 22, "u");
        passwordTarget.setAuthMethod(AuthMethod.PASSWORD);
        SshTtyConnector passwordTargetConnector = new SshTtyConnector(passwordTarget, "pw");
        SSHKeyManager keyManager = new SSHKeyManager(Files.createTempDirectory("kortty-keys-"));
        passwordTargetConnector.configureVault(keyManager, MASTER);
        assertThat(field(passwordTargetConnector, "masterPassword")).isSameInstanceAs(MASTER);
        assertThat(field(passwordTargetConnector, "sshKeyManager")).isNull();

        ServerConnection keyTarget = new ServerConnection("t", "127.0.0.1", 22, "u");
        keyTarget.setAuthMethod(AuthMethod.PUBLIC_KEY);
        SshTtyConnector forKey = new SshTtyConnector(keyTarget, null);
        forKey.configureVault(keyManager, MASTER);
        assertThat(field(forKey, "masterPassword")).isSameInstanceAs(MASTER);
        assertThat(field(forKey, "sshKeyManager")).isSameInstanceAs(keyManager);
    }

    private void startServers(Path tmp) throws IOException {
        bastion = LoopbackSshServers.start(tmp.resolve("bastion.ser"), passwordsSeenByBastion, bastionSessions, false);
        target = LoopbackSshServers.start(tmp.resolve("target.ser"), passwordsSeenByTarget, targetSessions, true);
    }

    private ServerConnection targetBehindPasswordJump(AuthMethod targetAuth, int jumpPort) throws Exception {
        ServerConnection connection = new ServerConnection("t", "127.0.0.1", target.getPort(), "targetuser");
        connection.setAuthMethod(targetAuth);
        connection.setConnectionTimeoutSeconds(10);
        JumpServer jump = new JumpServer();
        jump.setEnabled(true);
        jump.setHost("127.0.0.1");
        jump.setPort(jumpPort);
        jump.setUsername("jumpuser");
        jump.setAuthMethod(AuthMethod.PASSWORD);
        jump.setEncryptedPassword(new EncryptionService().encryptPassword("JUMP-secret", MASTER));
        connection.setJumpServer(jump);
        return connection;
    }

    /** Reads the shell output until it contains {@code expected} or the stream ends. */
    private static String readUntil(SshTtyConnector connector, String expected) throws IOException {
        StringBuilder output = new StringBuilder();
        char[] buffer = new char[1024];
        while (!output.toString().contains(expected)) {
            int count = connector.read(buffer, 0, buffer.length);
            if (count < 0) {
                break;
            }
            output.append(buffer, 0, count);
        }
        return output.toString();
    }

    private static Object field(Object owner, String name) throws ReflectiveOperationException {
        java.lang.reflect.Field field = owner.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return field.get(owner);
    }
}
