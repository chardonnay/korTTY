package de.kortty.core;

import de.kortty.core.SshHostKeyTrustManager.HostKeyPrompt.MismatchResolution;
import de.kortty.core.SshHostKeyTrustManager.ReplacePolicy;
import de.kortty.model.ServerConnection;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

/**
 * A server that was rebuilt with a new host key, end to end against a real SSHD server: a
 * confirmed replacement pins the server's real key and lets the very same handshake authenticate;
 * a declined one keeps the old pin and fails the connect as a host-key rejection.
 */
class HostKeyReplaceIntegrationTest {

    private SshServer server;

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (server != null) {
            server.stop(true);
        }
    }

    @Test
    void confirmedReplacementPinsTheServersRealKeyAndAuthenticates() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-hostkey-replace-");
        startServer(tmp);
        ScriptedPrompt prompt = new ScriptedPrompt(MismatchResolution.REPLACE);
        SshHostKeyTrustManager trust =
            new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), prompt, () -> false);
        PublicKey previousKey = newKey();
        assertThat(trust.verify("127.0.0.1", server.getPort(), previousKey)).isTrue();

        SshHostKeyTrustManager.ConnectionVerifier verifier = trust.verifierFor(
            target(), HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE);
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(verifier);
        client.start();
        try (ClientSession session = client.connect("u", "127.0.0.1", server.getPort())
                .verify(Duration.ofSeconds(10)).getSession()) {
            session.addPasswordIdentity("pw");
            session.auth().verify(Duration.ofSeconds(10));
            assertThat(session.isAuthenticated()).isTrue();
        } finally {
            client.stop();
        }

        assertThat(verifier.wasRejected()).isFalse();
        assertThat(prompt.replacementAllowed).containsExactly(true);
        List<SshHostKeyTrustManager.TrustedHostKey> pins = trust.listTrustedKeys();
        assertThat(pins).hasSize(1);
        assertThat(pins.getFirst().fingerprintSha256()).isIn(serverFingerprints());
        assertThat(pins.getFirst().fingerprintSha256())
            .isEqualTo(prompt.mismatches.getFirst().offeredFingerprintSha256());
        assertThat(prompt.mismatches.getFirst().expectedFingerprintSha256())
            .isEqualTo(SshHostKeyTrustManager.fingerprintSha256(previousKey));
    }

    @Test
    void declinedReplacementKeepsTheOldPinAndRejectsTheConnect() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-hostkey-keep-");
        startServer(tmp);
        ScriptedPrompt prompt = new ScriptedPrompt(MismatchResolution.KEEP_BLOCKED);
        SshHostKeyTrustManager trust =
            new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), prompt, () -> false);
        PublicKey previousKey = newKey();
        assertThat(trust.verify("127.0.0.1", server.getPort(), previousKey)).isTrue();

        SshHostKeyTrustManager.ConnectionVerifier verifier = trust.verifierFor(
            target(), HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE);
        SshClient client = SshClient.setUpDefaultClient();
        client.setServerKeyVerifier(verifier);
        client.start();
        try {
            expectThrows(Exception.class, () -> {
                try (ClientSession session = client.connect("u", "127.0.0.1", server.getPort())
                        .verify(Duration.ofSeconds(10)).getSession()) {
                    session.addPasswordIdentity("pw");
                    session.auth().verify(Duration.ofSeconds(10));
                }
            });
        } finally {
            client.stop();
        }

        assertThat(verifier.wasRejected()).isTrue();
        assertThat(prompt.replacementAllowed).containsExactly(true);
        assertThat(trust.listTrustedKeys().getFirst().fingerprintSha256())
            .isEqualTo(SshHostKeyTrustManager.fingerprintSha256(previousKey));
    }

    private void startServer(Path tmp) throws IOException {
        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("host.ser")));
        server.setPasswordAuthenticator((user, password, session) -> true);
        server.start();
    }

    private ServerConnection target() {
        return new ServerConnection("t", "127.0.0.1", server.getPort(), "u");
    }

    private List<String> serverFingerprints() throws Exception {
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

    /** Accepts first use and answers the changed-key prompt with a fixed resolution. */
    private static final class ScriptedPrompt implements SshHostKeyTrustManager.HostKeyPrompt {
        private final MismatchResolution resolution;
        private final List<Boolean> replacementAllowed = new CopyOnWriteArrayList<>();
        private final List<SshHostKeyTrustManager.HostKeyMismatch> mismatches = new CopyOnWriteArrayList<>();

        ScriptedPrompt(MismatchResolution resolution) {
            this.resolution = resolution;
        }

        @Override
        public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
            return true;
        }

        @Override
        public void warnMismatch(SshHostKeyTrustManager.HostKeyMismatch mismatch) {
            mismatches.add(mismatch);
        }

        @Override
        public MismatchResolution resolveMismatch(SshHostKeyTrustManager.HostKeyMismatch mismatch, boolean allowed) {
            mismatches.add(mismatch);
            replacementAllowed.add(allowed);
            return resolution;
        }

        @Override
        public void warnVerificationFailure(SshHostKeyTrustManager.HostKeyVerificationFailure failure) {
        }
    }
}
