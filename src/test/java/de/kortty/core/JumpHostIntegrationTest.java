package de.kortty.core;

import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.command.CommandFactory;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.server.shell.ProcessShellCommandFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.truth.Truth.assertThat;

/**
 * Drives {@link JumpHostSupport} against two real, loopback-only Apache SSHD servers — a bastion
 * and a target — and asserts the two properties the feature exists to provide: the tunnel reaches
 * the target through the bastion, and the bastion's password is never presented to the target.
 */
class JumpHostIntegrationTest {

    private SshServer bastion;
    private SshServer target;
    private final Set<String> passwordsSeenByTarget = ConcurrentHashMap.newKeySet();

    private SshServer startServer(Path hostKey, java.util.function.BiConsumer<String, String> onPassword)
            throws IOException {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        server.setPasswordAuthenticator((username, password, session) -> {
            if (onPassword != null) {
                onPassword.accept(username, password);
            }
            return true; // any credential authenticates; the test asserts on what was offered
        });
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        server.setCommandFactory((CommandFactory) ProcessShellCommandFactory.INSTANCE);
        server.start();
        return server;
    }

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (bastion != null) {
            bastion.stop(true);
        }
        if (target != null) {
            target.stop(true);
        }
    }

    @Test
    void tunnelReachesTheTargetThroughTheBastionWithSeparateCredentials() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-jump-it-");
        bastion = startServer(tmp.resolve("bastion.ser"), null);
        target = startServer(tmp.resolve("target.ser"),
            (user, pw) -> passwordsSeenByTarget.add(pw));

        ServerConnection connection = new ServerConnection("t", "127.0.0.1", target.getPort(), "targetuser");
        JumpServer jump = new JumpServer();
        jump.setEnabled(true);
        jump.setHost("127.0.0.1");
        jump.setPort(bastion.getPort());
        jump.setUsername("jumpuser");
        connection.setJumpServer(jump);

        // A trust manager that accepts every first-use key without a UI prompt.
        Path store = tmp.resolve("hostkeys.properties");
        SshHostKeyTrustManager trust = new SshHostKeyTrustManager(store, new AcceptingPrompt());

        // The stored jump password is set directly (encryption is covered by its own round-trip test).
        jump.setEncryptedPassword(null);
        jump = connection.getJumpServer();

        try (JumpHostSupport.JumpTunnel tunnel = openWithPlainPassword(
                connection, trust, "JUMP-secret", Duration.ofSeconds(10))) {

            assertThat(tunnel.localHost()).isEqualTo("127.0.0.1");
            assertThat(tunnel.localPort()).isGreaterThan(0);
            assertThat(tunnel.localPort()).isNotEqualTo(target.getPort());
            assertThat(tunnel.localPort()).isNotEqualTo(bastion.getPort());

            // Connect the real session through the tunnel with the TARGET's own password.
            org.apache.sshd.client.SshClient client = org.apache.sshd.client.SshClient.setUpDefaultClient();
            client.setServerKeyVerifier((s, addr, key) -> true);
            client.start();
            try {
                var session = client.connect("targetuser", tunnel.localHost(), tunnel.localPort())
                    .verify(Duration.ofSeconds(10)).getSession();
                session.addPasswordIdentity("TARGET-secret");
                session.auth().verify(Duration.ofSeconds(10));
                assertThat(session.isAuthenticated()).isTrue();
                session.close(false);
            } finally {
                client.stop();
            }
        }

        // The target authenticated the real session — and only ever saw the target password.
        assertThat(passwordsSeenByTarget).contains("TARGET-secret");
        assertThat(passwordsSeenByTarget).doesNotContain("JUMP-secret");
    }

    /** Opens the tunnel using a plaintext jump password, bypassing the encrypted-store path. */
    private JumpHostSupport.JumpTunnel openWithPlainPassword(
            ServerConnection connection, SshHostKeyTrustManager trust, String plainPassword, Duration timeout)
            throws Exception {
        // JumpHostSupport decrypts from the model; for the integration test we encrypt the password
        // with a throwaway master password so the real decrypt path is exercised end to end.
        char[] master = "it-master".toCharArray();
        connection.getJumpServer().setEncryptedPassword(
            new de.kortty.security.EncryptionService().encryptPassword(plainPassword, master));
        return JumpHostSupport.open(connection, trust, master, timeout);
    }

    private static final class AcceptingPrompt implements SshHostKeyTrustManager.HostKeyPrompt {
        @Override
        public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
            return true;
        }

        @Override
        public void warnMismatch(SshHostKeyTrustManager.HostKeyMismatch mismatch) {
        }

        @Override
        public void warnVerificationFailure(SshHostKeyTrustManager.HostKeyVerificationFailure failure) {
        }
    }
}
