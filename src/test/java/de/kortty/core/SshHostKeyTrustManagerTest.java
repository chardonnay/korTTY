package de.kortty.core;

import de.kortty.model.ServerConnection;
import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;

class SshHostKeyTrustManagerTest {

    @Test
    void firstUseRequiresConfirmationAndPersistsCompletePin() throws Exception {
        Path store = newStorePath();
        RecordingPrompt prompt = new RecordingPrompt(true);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt);
        PublicKey key = newKey();

        assertThat(manager.verify("Example.COM.", 22, key)).isTrue();
        assertThat(prompt.confirmations.get()).isEqualTo(1);
        assertThat(prompt.mismatches).isEmpty();

        String persisted = Files.readString(store);
        assertThat(persisted).contains("entry.0.host=example.com");
        assertThat(persisted).contains("entry.0.port=22");
        assertThat(persisted).contains("fingerprintSha256");
        assertThat(persisted).contains("publicKeyLine");
        Properties properties = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(store)) {
            properties.load(reader);
        }
        assertThat(properties.getProperty("entry.0.fingerprintSha256"))
            .isEqualTo(SshHostKeyTrustManager.fingerprintSha256(key));
    }

    @Test
    void persistedMatchingKeyIsSilentlyAcceptedByNewManagerAndSftpEndpoint() throws Exception {
        Path store = newStorePath();
        PublicKey key = newKey();
        RecordingPrompt firstPrompt = new RecordingPrompt(true);
        assertThat(new SshHostKeyTrustManager(store, firstPrompt).verify("host.internal", 22, key)).isTrue();

        RecordingPrompt laterPrompt = new RecordingPrompt(false);
        SshHostKeyTrustManager reloaded = new SshHostKeyTrustManager(store, laterPrompt);

        assertThat(reloaded.verify("HOST.INTERNAL", 22, key)).isTrue();
        assertThat(laterPrompt.confirmations.get()).isEqualTo(0);
        assertThat(laterPrompt.mismatches).isEmpty();
    }

    @Test
    void changedKeyIsRejectedAndWarnedWithoutReplacingPin() throws Exception {
        Path store = newStorePath();
        PublicKey trusted = newKey();
        PublicKey changed = newKey();
        RecordingPrompt initialPrompt = new RecordingPrompt(true);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, initialPrompt);
        assertThat(manager.verify("host.internal", 2222, trusted)).isTrue();
        String before = Files.readString(store);

        assertThat(manager.verify("host.internal", 2222, changed)).isFalse();

        assertThat(initialPrompt.confirmations.get()).isEqualTo(1);
        assertThat(initialPrompt.mismatches).hasSize(1);
        SshHostKeyTrustManager.HostKeyMismatch mismatch = initialPrompt.mismatches.getFirst();
        assertThat(mismatch.expectedFingerprintSha256())
            .isEqualTo(SshHostKeyTrustManager.fingerprintSha256(trusted));
        assertThat(mismatch.offeredFingerprintSha256())
            .isEqualTo(SshHostKeyTrustManager.fingerprintSha256(changed));
        assertThat(Files.readString(store)).isEqualTo(before);
    }

    @Test
    void trustIsScopedByPortAndNormalizesHostSpelling() throws Exception {
        Path store = newStorePath();
        RecordingPrompt prompt = new RecordingPrompt(true);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt);
        PublicKey key = newKey();

        assertThat(manager.verify("[2001:DB8::1]", 22, key)).isTrue();
        assertThat(manager.verify("2001:db8::1", 22, key)).isTrue();
        assertThat(manager.verify("2001:db8::1", 2200, key)).isTrue();

        assertThat(prompt.confirmations.get()).isEqualTo(2);
    }

    @Test
    void rejectedFirstUseDoesNotCreateTrustStore() throws Exception {
        Path store = newStorePath();
        RecordingPrompt prompt = new RecordingPrompt(false);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt);

        assertThat(manager.verify("untrusted.example", 22, newKey())).isFalse();

        assertThat(prompt.confirmations.get()).isEqualTo(1);
        assertThat(Files.exists(store)).isFalse();
    }

    @Test
    void connectionVerifierMarksHostKeyRejectionAsNonRetriable() throws Exception {
        Path store = newStorePath();
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, new RecordingPrompt(false));
        ServerConnection connection = new ServerConnection("Rejected", "reject.example", 22, "root");
        SshHostKeyTrustManager.ConnectionVerifier verifier = manager.verifierFor(connection);

        assertThat(verifier.verifyServerKey(null, null, newKey())).isFalse();
        assertThat(verifier.wasRejected()).isTrue();
    }

    @Test
    void simultaneousTerminalAndSftpVerificationUsesOneConfirmation() throws Exception {
        Path store = newStorePath();
        PublicKey key = newKey();
        CountDownLatch promptEntered = new CountDownLatch(1);
        CountDownLatch releasePrompt = new CountDownLatch(1);
        BlockingPrompt prompt = new BlockingPrompt(promptEntered, releasePrompt);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> terminal = executor.submit(() -> manager.verify("shared.example", 22, key));
            assertThat(promptEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> sftp = executor.submit(() -> manager.verify("shared.example", 22, key));
            releasePrompt.countDown();

            assertThat(terminal.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(sftp.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(prompt.confirmations.get()).isEqualTo(1);
        } finally {
            releasePrompt.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void simultaneousDifferentKeyCannotRideOnAcceptedDecision() throws Exception {
        Path store = newStorePath();
        PublicKey acceptedKey = newKey();
        PublicKey conflictingKey = newKey();
        CountDownLatch promptEntered = new CountDownLatch(1);
        CountDownLatch releasePrompt = new CountDownLatch(1);
        BlockingPrompt prompt = new BlockingPrompt(promptEntered, releasePrompt);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> accepted = executor.submit(() -> manager.verify("shared.example", 22, acceptedKey));
            assertThat(promptEntered.await(5, TimeUnit.SECONDS)).isTrue();
            Future<Boolean> conflicting = executor.submit(() -> manager.verify("shared.example", 22, conflictingKey));
            releasePrompt.countDown();

            assertThat(accepted.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(conflicting.get(5, TimeUnit.SECONDS)).isFalse();
            assertThat(prompt.confirmations.get()).isEqualTo(1);
            assertThat(prompt.mismatches).hasSize(1);
        } finally {
            releasePrompt.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void independentManagersMergePinsWithoutLostUpdates() throws Exception {
        Path store = newStorePath();
        CountDownLatch bothPrompting = new CountDownLatch(2);
        SshHostKeyTrustManager.HostKeyPrompt prompt = new SshHostKeyTrustManager.HostKeyPrompt() {
            @Override
            public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
                bothPrompting.countDown();
                try {
                    return bothPrompting.await(5, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }

            @Override
            public void warnMismatch(SshHostKeyTrustManager.HostKeyMismatch mismatch) {
            }

            @Override
            public void warnVerificationFailure(SshHostKeyTrustManager.HostKeyVerificationFailure failure) {
            }
        };
        SshHostKeyTrustManager firstManager = new SshHostKeyTrustManager(store, prompt);
        SshHostKeyTrustManager secondManager = new SshHostKeyTrustManager(store, prompt);
        PublicKey firstKey = newKey();
        PublicKey secondKey = newKey();
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> firstManager.verify("first.example", 22, firstKey));
            Future<Boolean> second = executor.submit(() -> secondManager.verify("second.example", 22, secondKey));

            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(second.get(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            executor.shutdownNow();
        }

        RecordingPrompt mustNotPrompt = new RecordingPrompt(false);
        SshHostKeyTrustManager reloaded = new SshHostKeyTrustManager(store, mustNotPrompt);
        assertThat(reloaded.verify("first.example", 22, firstKey)).isTrue();
        assertThat(reloaded.verify("second.example", 22, secondKey)).isTrue();
        assertThat(mustNotPrompt.confirmations.get()).isEqualTo(0);
    }

    @Test
    void malformedTrustStoreFailsClosedAndIsNotOverwritten() throws Exception {
        Path store = newStorePath();
        Files.writeString(store, "format=999\nentry.count=0\n");
        RecordingPrompt prompt = new RecordingPrompt(true);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt);

        assertThat(manager.verify("host.internal", 22, newKey())).isFalse();

        assertThat(prompt.confirmations.get()).isEqualTo(0);
        assertThat(prompt.failures).hasSize(1);
        assertThat(Files.readString(store)).isEqualTo("format=999\nentry.count=0\n");
    }

    @Test
    void repairedTrustStoreCanBeUsedWithoutRestartingManager() throws Exception {
        Path store = newStorePath();
        Files.writeString(store, "format=999\nentry.count=0\n");
        RecordingPrompt prompt = new RecordingPrompt(true);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt);
        PublicKey key = newKey();
        assertThat(manager.verify("host.internal", 22, key)).isFalse();

        Files.delete(store);

        assertThat(manager.verify("host.internal", 22, key)).isTrue();
        assertThat(prompt.confirmations.get()).isEqualTo(1);
    }

    @Test
    void inconsistentPersistedKeyMaterialFailsClosed() throws Exception {
        Path store = newStorePath();
        PublicKey key = newKey();
        assertThat(new SshHostKeyTrustManager(store, new RecordingPrompt(true))
            .verify("host.internal", 22, key)).isTrue();

        Properties properties = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(store)) {
            properties.load(reader);
        }
        properties.setProperty("entry.0.algorithm", "ssh-ed25519");
        try (java.io.Writer writer = Files.newBufferedWriter(store)) {
            properties.store(writer, "corrupted test fixture");
        }

        RecordingPrompt prompt = new RecordingPrompt(true);
        assertThat(new SshHostKeyTrustManager(store, prompt).verify("host.internal", 22, key)).isFalse();
        assertThat(prompt.confirmations.get()).isEqualTo(0);
        assertThat(prompt.failures).hasSize(1);
    }

    private static Path newStorePath() throws Exception {
        return Files.createTempDirectory("kortty-host-key-test-")
            .resolve(SshHostKeyTrustManager.STORE_FILE_NAME);
    }

    private static PublicKey newKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(1024);
        return generator.generateKeyPair().getPublic();
    }

    private static class RecordingPrompt implements SshHostKeyTrustManager.HostKeyPrompt {
        private final boolean confirmationResult;
        protected final AtomicInteger confirmations = new AtomicInteger();
        protected final List<SshHostKeyTrustManager.HostKeyMismatch> mismatches =
            java.util.Collections.synchronizedList(new ArrayList<>());
        protected final List<SshHostKeyTrustManager.HostKeyVerificationFailure> failures =
            java.util.Collections.synchronizedList(new ArrayList<>());

        private RecordingPrompt(boolean confirmationResult) {
            this.confirmationResult = confirmationResult;
        }

        @Override
        public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
            confirmations.incrementAndGet();
            return confirmationResult;
        }

        @Override
        public void warnMismatch(SshHostKeyTrustManager.HostKeyMismatch mismatch) {
            mismatches.add(mismatch);
        }

        @Override
        public void warnVerificationFailure(SshHostKeyTrustManager.HostKeyVerificationFailure failure) {
            failures.add(failure);
        }
    }

    private static final class BlockingPrompt extends RecordingPrompt {
        private final CountDownLatch entered;
        private final CountDownLatch release;

        private BlockingPrompt(CountDownLatch entered, CountDownLatch release) {
            super(true);
            this.entered = entered;
            this.release = release;
        }

        @Override
        public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
            confirmations.incrementAndGet();
            entered.countDown();
            try {
                return release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
    }
}
