package de.kortty.core;

import de.kortty.model.JumpServer;
import de.kortty.model.ServerConnection;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.common.config.keys.writer.openssh.OpenSSHKeyPairResourceWriter;
import org.apache.sshd.common.keyprovider.KeyPairProvider;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.forward.AcceptAllForwardingFilter;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
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
            bastion = null;
        }
        if (target != null) {
            target.stop(true);
            target = null;
        }
        // TestNG reuses one instance for all tests of the class.
        passwordsSeenByTarget.clear();
        passwordsSeenByBastion.clear();
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

    @Test
    void passwordTargetGetsTheJumpPasswordThroughConfigureVault() throws Exception {
        // The SFTP tab used to hand the vault only to a non-temporary PUBLIC_KEY target, so a
        // password target failed on the jump password with "vault is locked".
        Path tmp = Files.createTempDirectory("kortty-sftp-jump-vault-");
        bastion = startServer(tmp.resolve("bastion.ser"), false, passwordsSeenByBastion);
        target = startServer(tmp.resolve("target.ser"), true, passwordsSeenByTarget);

        char[] master = "it-master".toCharArray();
        ServerConnection connection = new ServerConnection("t", "127.0.0.1", target.getPort(), "targetuser");
        connection.setAuthMethod(de.kortty.model.AuthMethod.PASSWORD);
        connection.setJumpServer(passwordJump(master));

        SFTPSession sftp = new SFTPSession(connection, "TARGET-secret", acceptingTrust(tmp));
        sftp.configureVault(new SSHKeyManager(tmp), master, false);
        try {
            sftp.connect();
            assertThat(sftp.listFiles(".")).isNotNull();
        } finally {
            sftp.close();
        }

        assertThat(passwordsSeenByTarget).containsExactly("TARGET-secret");
        assertThat(passwordsSeenByBastion).containsExactly("JUMP-secret");
    }

    @Test
    void temporaryKeyTargetGetsTheJumpPasswordThroughConfigureVault() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-sftp-jump-tempkey-");
        bastion = startServer(tmp.resolve("bastion.ser"), false, passwordsSeenByBastion);
        KeyPair temporaryKey = KeyUtils.generateKeyPair(KeyPairProvider.ECDSA_SHA2_NISTP256, 256);
        // The target accepts exactly the temporary key: no password or keyboard-interactive login
        // that could let the session in without it.
        target = SshServer.setUpDefaultServer();
        target.setHost("127.0.0.1");
        target.setPort(0);
        target.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("target.ser")));
        target.setPasswordAuthenticator(null);
        target.setKeyboardInteractiveAuthenticator(null);
        target.setPublickeyAuthenticator(
            (username, key, session) -> KeyUtils.compareKeys(key, temporaryKey.getPublic()));
        target.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory.Builder().build()));
        target.start();

        char[] master = "it-master".toCharArray();
        // What the SFTP tab builds for a Quick Connect temporary key (SftpConnectionSupport).
        ServerConnection connection = new ServerConnection("t", "127.0.0.1", target.getPort(), "targetuser");
        connection.setAuthMethod(de.kortty.model.AuthMethod.PUBLIC_KEY);
        connection.setPrivateKeyPath("TEMPORARY:" + openSshPrivateKey(temporaryKey));
        connection.setJumpServer(passwordJump(master));
        // The connection still references a managed key the target does not accept. With the
        // temporary key in use the key manager must be left out, or that key would be offered
        // instead and the login would fail.
        SSHKeyManager keyManager = new SSHKeyManager(tmp);
        Path managedKeyFile = tmp.resolve("managed.key");
        Files.writeString(managedKeyFile,
            openSshPrivateKey(KeyUtils.generateKeyPair(KeyPairProvider.ECDSA_SHA2_NISTP256, 256)),
            StandardCharsets.UTF_8);
        de.kortty.model.SSHKey managedKey = new de.kortty.model.SSHKey("managed", managedKeyFile.toString());
        keyManager.addKey(managedKey);
        connection.setSshKeyId(managedKey.getId());

        SFTPSession sftp = new SFTPSession(connection, null, acceptingTrust(tmp));
        sftp.configureVault(keyManager, master, true);
        try {
            sftp.connect();
            assertThat(sftp.listFiles(".")).isNotNull();
        } finally {
            sftp.close();
        }

        assertThat(passwordsSeenByBastion).containsExactly("JUMP-secret");
    }

    /** A password jump through {@link #bastion} whose stored password is encrypted with {@code master}. */
    private JumpServer passwordJump(char[] master) throws Exception {
        JumpServer jump = new JumpServer();
        jump.setEnabled(true);
        jump.setHost("127.0.0.1");
        jump.setPort(bastion.getPort());
        jump.setUsername("jumpuser");
        jump.setAuthMethod(de.kortty.model.AuthMethod.PASSWORD);
        jump.setEncryptedPassword(
            new de.kortty.security.EncryptionService().encryptPassword("JUMP-secret", master));
        return jump;
    }

    private static SshHostKeyTrustManager acceptingTrust(Path tmp) {
        return new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), new AcceptingPrompt());
    }

    private static String openSshPrivateKey(KeyPair keyPair) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        OpenSSHKeyPairResourceWriter.INSTANCE.writePrivateKey(keyPair, "kortty-test", null, out);
        return out.toString(StandardCharsets.UTF_8);
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
