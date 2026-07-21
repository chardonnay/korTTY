package de.kortty.core;

import org.apache.sshd.client.SshClient;
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
import java.util.concurrent.atomic.AtomicInteger;

import de.kortty.model.ServerConnection;

import static com.google.common.truth.Truth.assertThat;

/**
 * Verifies the accept-new mode two ways: against the trust manager directly for the deterministic
 * "accept unknown, block changed" guarantee, and against a real SSHD server for the "no prompt on
 * first use" behaviour.
 */
class HostKeyAcceptNewIntegrationTest {

    private SshServer server;

    @AfterMethod(alwaysRun = true)
    void tearDown() throws IOException {
        if (server != null) {
            server.stop(true);
        }
    }

    @Test
    void acceptNewPinsAnUnknownKeyWithoutPromptButRefusesAChangedKey() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-acceptnew-");
        AtomicInteger prompts = new AtomicInteger();
        SshHostKeyTrustManager trust =
            new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), new CountingPrompt(prompts));

        PublicKey keyA = newKey();
        PublicKey keyB = newKey();

        // Unknown endpoint, accept-new: trusted and pinned with no prompt.
        assertThat(trust.verify("host.example", 22, keyA, HostKeyCheckMode.ACCEPT_NEW)).isTrue();
        assertThat(prompts.get()).isEqualTo(0);

        // Same key again: still trusted, still no prompt (it matches the pin).
        assertThat(trust.verify("host.example", 22, keyA, HostKeyCheckMode.ACCEPT_NEW)).isTrue();
        assertThat(prompts.get()).isEqualTo(0);

        // A DIFFERENT key on the already-pinned endpoint is refused even in accept-new — the whole
        // point of the "block changed" guarantee: relaxed first-use does not mean blind trust of a
        // key change on a host you have connected to before.
        assertThat(trust.verify("host.example", 22, keyB, HostKeyCheckMode.ACCEPT_NEW)).isFalse();

        // And strict mode refuses the changed key too.
        assertThat(trust.verify("host.example", 22, keyB, HostKeyCheckMode.STRICT)).isFalse();
    }

    @Test
    void strictModeStillPromptsOnAnUnknownKey() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-strict-");
        AtomicInteger prompts = new AtomicInteger();
        SshHostKeyTrustManager trust =
            new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), new CountingPrompt(prompts));

        assertThat(trust.verify("strict.example", 22, newKey(), HostKeyCheckMode.STRICT)).isTrue();
        assertThat(prompts.get()).isEqualTo(1);
    }

    @Test
    void acceptNewConnectsToARealServerWithoutAPrompt() throws Exception {
        Path tmp = Files.createTempDirectory("kortty-acceptnew-live-");
        AtomicInteger prompts = new AtomicInteger();
        SshHostKeyTrustManager trust =
            new SshHostKeyTrustManager(tmp.resolve("hostkeys.properties"), new CountingPrompt(prompts));

        server = SshServer.setUpDefaultServer();
        server.setHost("127.0.0.1");
        server.setPort(0);
        server.setKeyPairProvider(new SimpleGeneratorHostKeyProvider(tmp.resolve("host.ser")));
        server.setPasswordAuthenticator((u, p, s) -> true);
        server.start();

        SshClient client = SshClient.setUpDefaultClient();
        ServerConnection target = new ServerConnection("t", "127.0.0.1", server.getPort(), "u");
        client.setServerKeyVerifier(trust.verifierFor(target, HostKeyCheckMode.ACCEPT_NEW));
        client.start();
        try {
            var session = client.connect("u", "127.0.0.1", server.getPort())
                .verify(Duration.ofSeconds(10)).getSession();
            session.addPasswordIdentity("pw");
            session.auth().verify(Duration.ofSeconds(10));
            assertThat(session.isAuthenticated()).isTrue();
            session.close(false);
        } finally {
            client.stop();
        }
        assertThat(prompts.get()).isEqualTo(0);
    }

    private static PublicKey newKey() throws Exception {
        KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
        gen.initialize(2048);
        KeyPair pair = gen.generateKeyPair();
        return pair.getPublic();
    }

    private static final class CountingPrompt implements SshHostKeyTrustManager.HostKeyPrompt {
        private final AtomicInteger prompts;

        CountingPrompt(AtomicInteger prompts) {
            this.prompts = prompts;
        }

        @Override
        public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
            prompts.incrementAndGet();
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
