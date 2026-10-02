package de.kortty.core;

import de.kortty.core.SshHostKeyTrustManager.HostKeyPrompt.MismatchResolution;
import de.kortty.core.SshHostKeyTrustManager.ReplacePolicy;
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
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * Pins which connections may offer to replace a changed host key, end to end against real SSHD
 * servers: {@link SFTPSession} and {@link SshTtyConnector} default to
 * {@link ReplacePolicy#NEVER}, and only an explicit {@link ReplacePolicy#INTERACTIVE} reaches both
 * the target verifier and the jump server's verifier through {@link JumpHostSupport}.
 */
class HostKeyReplacePolicyWiringIntegrationTest {

    private SshServer bastion;
    private SshServer target;

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
    }

    @Test
    void sftpSessionReplacesTargetAndBastionKeysOnlyWhenInteractive() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-replace-wiring-sftp-");
        bastion = startServer(tmp.resolve("bastion.ser"), false);
        target = startServer(tmp.resolve("target.ser"), true);
        char[] master = "it-master".toCharArray();
        ServerConnection connection = connectionViaJump(master);
        ScriptedPrompt prompt = new ScriptedPrompt(MismatchResolution.REPLACE);
        SshHostKeyTrustManager trust =
            new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), prompt, () -> false);
        String oldBastion = pin(trust, bastion.getPort());
        String oldTarget = pin(trust, target.getPort());

        // Default: restored editors and background transfers never offer the replacement, even
        // when the prompt would say REPLACE. The bastion is checked first and blocks the connect.
        SFTPSession unattended = new SFTPSession(connection, "TARGET-secret", trust);
        unattended.setSSHKeyManager(null, master);
        try {
            expectThrows(Exception.class, unattended::connect);
        } finally {
            unattended.close();
        }
        assertThat(prompt.replacementAllowed).containsExactly(false);
        assertThat(pinnedFingerprints(trust))
            .containsExactly(bastion.getPort(), oldBastion, target.getPort(), oldTarget);

        // The SFTP manager tab: the jump hop and the target both get the review.
        SFTPSession interactive = new SFTPSession(connection, "TARGET-secret", trust);
        interactive.setSSHKeyManager(null, master);
        interactive.setHostKeyReplacePolicy(ReplacePolicy.INTERACTIVE);
        try {
            interactive.connect();
            assertThat(interactive.isConnected()).isTrue();
            assertThat(interactive.listFiles(".")).isNotNull();
        } finally {
            interactive.close();
        }
        assertThat(prompt.replacementAllowed).containsExactly(false, true, true).inOrder();
        assertThat(prompt.ports).containsExactly(
            bastion.getPort(), bastion.getPort(), target.getPort()).inOrder();
        Map<Integer, String> pins = pinnedFingerprints(trust);
        assertThat(pins.get(bastion.getPort())).isIn(serverFingerprints(bastion));
        assertThat(pins.get(target.getPort())).isIn(serverFingerprints(target));
    }

    @Test
    void terminalConnectorDefaultsToNeverAndPassesInteractiveToTheTarget() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-replace-wiring-tty-");
        target = startServer(tmp.resolve("target.ser"), false);
        ScriptedPrompt prompt = new ScriptedPrompt(MismatchResolution.KEEP_BLOCKED);
        SshHostKeyTrustManager trust =
            new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), prompt, () -> false);
        String oldTarget = pin(trust, target.getPort());
        ServerConnection connection = new ServerConnection("t", "127.0.0.1", target.getPort(), "u");
        connection.setConnectionTimeoutSeconds(10);

        SshTtyConnector unattended = new SshTtyConnector(connection, "pw", trust);
        expectThrows(SshTtyConnector.HostKeyVerificationException.class, unattended::connect);

        SshTtyConnector interactive = new SshTtyConnector(connection, "pw", trust);
        interactive.setHostKeyReplacePolicy(ReplacePolicy.INTERACTIVE);
        expectThrows(SshTtyConnector.HostKeyVerificationException.class, interactive::connect);

        assertThat(prompt.replacementAllowed).containsExactly(false, true).inOrder();
        assertThat(pinnedFingerprints(trust)).containsExactly(target.getPort(), oldTarget);
    }

    @Test
    void terminalConnectorPassesItsPolicyToTheJumpServer() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-replace-wiring-tty-jump-");
        bastion = startServer(tmp.resolve("bastion.ser"), false);
        target = startServer(tmp.resolve("target.ser"), false);
        char[] master = "it-master".toCharArray();
        ServerConnection connection = connectionViaJump(master);
        ScriptedPrompt prompt = new ScriptedPrompt(MismatchResolution.KEEP_BLOCKED);
        SshHostKeyTrustManager trust =
            new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), prompt, () -> false);
        String oldBastion = pin(trust, bastion.getPort());

        // A refused bastion key is final like a refused target key, so the tab does not retry
        // and show the same alert again.
        SshTtyConnector unattended = new SshTtyConnector(connection, "TARGET-secret", trust);
        unattended.setSSHKeyManager(null, master);
        expectThrows(SshTtyConnector.HostKeyVerificationException.class, unattended::connect);

        SshTtyConnector interactive = new SshTtyConnector(connection, "TARGET-secret", trust);
        interactive.setSSHKeyManager(null, master);
        interactive.setHostKeyReplacePolicy(ReplacePolicy.INTERACTIVE);
        expectThrows(SshTtyConnector.HostKeyVerificationException.class, interactive::connect);

        // Only the bastion was reached; its alert offered the review only for the interactive tab.
        assertThat(prompt.ports).containsExactly(bastion.getPort(), bastion.getPort());
        assertThat(prompt.replacementAllowed).containsExactly(false, true).inOrder();
        assertThat(pinnedFingerprints(trust)).containsExactly(bastion.getPort(), oldBastion);
    }

    private ServerConnection connectionViaJump(char[] master) throws Exception {
        ServerConnection connection = new ServerConnection("t", "127.0.0.1", target.getPort(), "targetuser");
        connection.setConnectionTimeoutSeconds(10);
        JumpServer jump = new JumpServer();
        jump.setEnabled(true);
        jump.setHost("127.0.0.1");
        jump.setPort(bastion.getPort());
        jump.setUsername("jumpuser");
        jump.setEncryptedPassword(
            new de.kortty.security.EncryptionService().encryptPassword("JUMP-secret", master));
        connection.setJumpServer(jump);
        return connection;
    }

    /** Pins a random key for {@code 127.0.0.1:port}, so the server's real key is a changed key. */
    private static String pin(SshHostKeyTrustManager trust, int port) throws Exception {
        PublicKey previous = newKey();
        assertThat(trust.verify("127.0.0.1", port, previous)).isTrue();
        return SshHostKeyTrustManager.fingerprintSha256(previous);
    }

    private static Map<Integer, String> pinnedFingerprints(SshHostKeyTrustManager trust) throws IOException {
        return trust.listTrustedKeys().stream().collect(Collectors.toMap(
            SshHostKeyTrustManager.TrustedHostKey::port,
            SshHostKeyTrustManager.TrustedHostKey::fingerprintSha256));
    }

    private static SshServer startServer(Path hostKey, boolean withSftp) throws IOException {
        SshServer server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(hostKey));
        server.setPasswordAuthenticator((username, password, session) -> true);
        server.setForwardingFilter(AcceptAllForwardingFilter.INSTANCE);
        if (withSftp) {
            server.setSubsystemFactories(Collections.singletonList(new SftpSubsystemFactory.Builder().build()));
        }
        server.start();
        return server;
    }

    private static List<String> serverFingerprints(SshServer server) throws Exception {
        List<String> fingerprints = new ArrayList<>();
        for (KeyPair pair : server.getKeyPairProvider().loadKeys(null)) {
            fingerprints.add(SshHostKeyTrustManager.fingerprintSha256(pair.getPublic()));
        }
        assertThat(fingerprints).isNotEmpty();
        return fingerprints;
    }

    private static PublicKey newKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair().getPublic();
    }

    /** Accepts first use and answers every changed-key prompt with a fixed resolution. */
    private static final class ScriptedPrompt implements SshHostKeyTrustManager.HostKeyPrompt {
        private final MismatchResolution resolution;
        private final List<Boolean> replacementAllowed = new CopyOnWriteArrayList<>();
        private final List<Integer> ports = new CopyOnWriteArrayList<>();

        ScriptedPrompt(MismatchResolution resolution) {
            this.resolution = resolution;
        }

        @Override
        public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
            return true;
        }

        @Override
        public void warnMismatch(SshHostKeyTrustManager.HostKeyMismatch mismatch) {
            throw new AssertionError("resolveMismatch must be used for a changed key");
        }

        @Override
        public MismatchResolution resolveMismatch(SshHostKeyTrustManager.HostKeyMismatch mismatch, boolean allowed) {
            ports.add(mismatch.port());
            replacementAllowed.add(allowed);
            return resolution;
        }

        @Override
        public void warnVerificationFailure(SshHostKeyTrustManager.HostKeyVerificationFailure failure) {
        }
    }
}
