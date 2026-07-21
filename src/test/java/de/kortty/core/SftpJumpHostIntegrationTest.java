package de.kortty.core;

import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import static com.google.common.truth.Truth.assertThat;

/**
 * Proves that an SFTP session reaches its target through an enabled jump server, and that the
 * bastion never sees the target's password — the same guarantees as the terminal path, exercised
 * against two real loopback SSHD servers where only the target runs the SFTP subsystem.
 */
class SftpJumpHostIntegrationTest {

    private SshServer bastion;
    private SshServer target;
    private final Set<String> passwordsSeenByTarget = ConcurrentHashMap.newKeySet();
    private final Set<String> passwordsSeenByBastion = ConcurrentHashMap.newKeySet();

    private SshServer startServer(Path hostKey, boolean withSftp, Set<String> passwordSink) throws IOException {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        server.setPasswordAuthenticator((username, password, session) -> {
            passwordSink.add(password);
            return true;
        });
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        if (withSftp) {
            server.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory.Builder().build()));
        }
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
    void sftpReachesTheTargetThroughTheBastionAndKeepsCredentialsSeparate() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-sftp-jump-it-");
        // Only the target runs the SFTP subsystem — if the session terminated on the bastion, the
        // SFTP handshake would fail rather than silently succeeding against the wrong host.
        bastion = startServer(tmp.resolve("bastion.ser"), false, passwordsSeenByBastion);
        target = startServer(tmp.resolve("target.ser"), true, passwordsSeenByTarget);

        ServerConnection connection = new ServerConnection("t", "127.0.0.1", target.getPort(), "targetuser");
        JumpServer jump = new JumpServer();
        jump.setEnabled(true);
        jump.setHost("127.0.0.1");
        jump.setPort(bastion.getPort());
        jump.setUsername("jumpuser");
        char[] master = "it-master".toCharArray();
        jump.setEncryptedPassword(
            new de.kortty.security.EncryptionService().encryptPassword("JUMP-secret", master));
        connection.setJumpServer(jump);

        SshHostKeyTrustManager trust = new SshHostKeyTrustManager(
            tmp.resolve("hostkeys.properties"), new AcceptingPrompt());

        SFTPSession sftp = new SFTPSession(connection, "TARGET-secret", trust);
        sftp.setSSHKeyManager(null, master);
        try {
            sftp.connect();
            assertThat(sftp.isConnected()).isTrue();
            // A real SFTP round trip proves the subsystem responded — i.e. we reached the target.
            assertThat(sftp.listFiles(".")).isNotNull();
        } finally {
            sftp.close();
        }

        assertThat(passwordsSeenByTarget).contains("TARGET-secret");
        assertThat(passwordsSeenByBastion).contains("JUMP-secret");
        assertThat(passwordsSeenByTarget).doesNotContain("JUMP-secret");
        assertThat(passwordsSeenByBastion).doesNotContain("TARGET-secret");
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
