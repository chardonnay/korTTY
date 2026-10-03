package de.kortty.core;

import de.kortty.core.SshHostKeyTrustManager.HostKeyPrompt.MismatchResolution;
import de.kortty.core.SshHostKeyTrustManager.ReplacePolicy;
import de.kortty.model.ServerConnection;
import de.kortty.policy.PolicyRestrictionException;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

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

    // ---- Review and replace a changed key -------------------------------------------------------

    @Test
    void confirmedReplacementPinsNewKeyAndAcceptsHandshake() throws Exception {
        Path store = newStorePath();
        PublicKey trusted = newKey();
        PublicKey changed = newKey();
        ResolvingPrompt prompt = new ResolvingPrompt(MismatchResolution.REPLACE);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt, () -> false);
        assertThat(manager.verify("host.internal", 2222, trusted)).isTrue();

        assertThat(manager.verify("host.internal", 2222, changed, HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE))
            .isTrue();

        assertThat(prompt.replacementAllowed).containsExactly(true);
        assertThat(storedFingerprint(store, 0)).isEqualTo(SshHostKeyTrustManager.fingerprintSha256(changed));
        assertThat(manager.listTrustedKeys()).hasSize(1);
        // The old key is now the stranger: it mismatches like any other changed key.
        ResolvingPrompt laterPrompt = new ResolvingPrompt(MismatchResolution.KEEP_BLOCKED);
        assertThat(new SshHostKeyTrustManager(store, laterPrompt, () -> false)
            .verify("host.internal", 2222, trusted, HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE)).isFalse();
        assertThat(laterPrompt.mismatches).hasSize(1);
    }

    @Test
    void declinedReplacementKeepsPinAndRejects() throws Exception {
        Path store = newStorePath();
        PublicKey trusted = newKey();
        ResolvingPrompt prompt = new ResolvingPrompt(MismatchResolution.KEEP_BLOCKED);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt, () -> false);
        assertThat(manager.verify("host.internal", 22, trusted)).isTrue();
        byte[] before = Files.readAllBytes(store);

        assertThat(manager.verify("host.internal", 22, newKey(), HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE))
            .isFalse();

        assertThat(prompt.replacementAllowed).containsExactly(true);
        assertThat(Files.readAllBytes(store)).isEqualTo(before);
    }

    @Test
    void replacementRefusedWhenPolicyLocksPins() throws Exception {
        Path store = newStorePath();
        PublicKey trusted = newKey();
        ResolvingPrompt prompt = new ResolvingPrompt(MismatchResolution.REPLACE);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt, () -> true);
        // First-use pinning is not affected by the lock.
        assertThat(manager.verify("host.internal", 22, trusted)).isTrue();
        byte[] before = Files.readAllBytes(store);

        assertThat(manager.verify("host.internal", 22, newKey(), HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE))
            .isFalse();

        assertThat(prompt.replacementAllowed).containsExactly(false);
        assertThat(Files.readAllBytes(store)).isEqualTo(before);
        assertThat(manager.isPinManagementLocked()).isTrue();
    }

    @Test
    void neverVerifierNeverOffersReplacement() throws Exception {
        Path store = newStorePath();
        PublicKey trusted = newKey();
        ResolvingPrompt prompt = new ResolvingPrompt(MismatchResolution.REPLACE);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt, () -> false);
        ServerConnection connection = new ServerConnection("Scheduled", "batch.example", 22, "root");
        assertThat(manager.verifierFor(connection).verifyServerKey(null, null, trusted)).isTrue();
        byte[] before = Files.readAllBytes(store);

        // Every overload that does not say INTERACTIVE is unattended: the dialog may only warn.
        List<SshHostKeyTrustManager.ConnectionVerifier> unattended = List.of(
            manager.verifierFor(connection),
            manager.verifierFor(connection, HostKeyCheckMode.STRICT),
            manager.verifierFor(connection, HostKeyCheckMode.ACCEPT_NEW),
            manager.verifierFor(connection, HostKeyCheckMode.STRICT, ReplacePolicy.NEVER),
            manager.verifierFor(connection, HostKeyCheckMode.STRICT, null));
        for (SshHostKeyTrustManager.ConnectionVerifier verifier : unattended) {
            assertThat(verifier.verifyServerKey(null, null, newKey())).isFalse();
            assertThat(verifier.wasRejected()).isTrue();
        }

        assertThat(prompt.replacementAllowed).hasSize(unattended.size());
        assertThat(prompt.replacementAllowed).doesNotContain(true);
        assertThat(Files.readAllBytes(store)).isEqualTo(before);
    }

    @Test
    void interactiveVerifierOffersReplacementAndAcceptsConfirmedKey() throws Exception {
        Path store = newStorePath();
        ResolvingPrompt prompt = new ResolvingPrompt(MismatchResolution.REPLACE);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt, () -> false);
        ServerConnection connection = new ServerConnection("Terminal", "term.example", 22, "root");
        assertThat(manager.verifierFor(connection).verifyServerKey(null, null, newKey())).isTrue();
        PublicKey changed = newKey();

        SshHostKeyTrustManager.ConnectionVerifier verifier =
            manager.verifierFor(connection, HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE);

        assertThat(verifier.verifyServerKey(null, null, changed)).isTrue();
        assertThat(verifier.wasRejected()).isFalse();
        assertThat(prompt.replacementAllowed).containsExactly(true);
        assertThat(storedFingerprint(store, 0)).isEqualTo(SshHostKeyTrustManager.fingerprintSha256(changed));
    }

    @Test
    void replacementNotOfferedOnConcurrentFirstUseRecheck() throws Exception {
        Path store = newStorePath();
        PublicKey acceptedKey = newKey();
        PublicKey conflictingKey = newKey();
        CountDownLatch promptEntered = new CountDownLatch(1);
        CountDownLatch releasePrompt = new CountDownLatch(1);
        ResolvingPrompt prompt = new ResolvingPrompt(MismatchResolution.REPLACE);
        prompt.blockFirstUse(promptEntered, releasePrompt);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt, () -> false);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> accepted = executor.submit(() -> manager.verify(
                "shared.example", 22, acceptedKey, HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE));
            assertThat(promptEntered.await(5, TimeUnit.SECONDS)).isTrue();
            AtomicReference<Thread> conflictingThread = new AtomicReference<>();
            Future<Boolean> conflicting = executor.submit(() -> {
                conflictingThread.set(Thread.currentThread());
                return manager.verify(
                    "shared.example", 22, conflictingKey, HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE);
            });
            // Release only once the second handshake waits on the shared first-use decision; if it
            // read the store after the pin was written, it would take the ordinary mismatch path.
            awaitWaiting(conflictingThread);
            releasePrompt.countDown();

            assertThat(accepted.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(conflicting.get(5, TimeUnit.SECONDS)).isFalse();
        } finally {
            releasePrompt.countDown();
            executor.shutdownNow();
        }

        assertThat(prompt.confirmations.get()).isEqualTo(1);
        assertThat(prompt.replacementAllowed).containsExactly(false);
        assertThat(storedFingerprint(store, 0)).isEqualTo(SshHostKeyTrustManager.fingerprintSha256(acceptedKey));
    }

    @Test
    void replaceIsCompareAndSwap() throws Exception {
        Path store = newStorePath();
        PublicKey keyA = newKey();
        PublicKey keyC = newKey();
        PublicKey keyD = newKey();
        ResolvingPrompt firstPrompt = new ResolvingPrompt(MismatchResolution.REPLACE);
        SshHostKeyTrustManager firstManager = new SshHostKeyTrustManager(store, firstPrompt, () -> false);
        assertThat(firstManager.verify("cas.example", 22, keyA)).isTrue();
        SshHostKeyTrustManager secondManager =
            new SshHostKeyTrustManager(store, new ResolvingPrompt(MismatchResolution.REPLACE), () -> false);
        AtomicBoolean secondReplaced = new AtomicBoolean();
        // While the first window's confirmation is open, another window replaces A with D.
        firstPrompt.duringResolve(() -> secondReplaced.set(secondManager.verify(
            "cas.example", 22, keyD, HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE)));

        assertThat(firstManager.verify("cas.example", 22, keyC, HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE))
            .isFalse();

        assertThat(secondReplaced.get()).isTrue();
        assertThat(storedFingerprint(store, 0)).isEqualTo(SshHostKeyTrustManager.fingerprintSha256(keyD));
        assertThat(firstPrompt.replacementConflicts).hasSize(1);
        assertThat(firstPrompt.replacementConflicts.getFirst().expectedFingerprintSha256())
            .isEqualTo(SshHostKeyTrustManager.fingerprintSha256(keyA));
    }

    @Test
    void parallelConfirmationOfTheSameKeyIsIdempotent() throws Exception {
        Path store = newStorePath();
        PublicKey keyA = newKey();
        PublicKey keyB = newKey();
        ResolvingPrompt terminalPrompt = new ResolvingPrompt(MismatchResolution.REPLACE);
        SshHostKeyTrustManager terminal = new SshHostKeyTrustManager(store, terminalPrompt, () -> false);
        assertThat(terminal.verify("dup.example", 22, keyA)).isTrue();
        SshHostKeyTrustManager sftp =
            new SshHostKeyTrustManager(store, new ResolvingPrompt(MismatchResolution.REPLACE), () -> false);
        AtomicBoolean sftpReplaced = new AtomicBoolean();
        // Terminal and SFTP both met the rebuilt server; the user confirms B in the SFTP alert first.
        terminalPrompt.duringResolve(() -> sftpReplaced.set(sftp.verify(
            "dup.example", 22, keyB, HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE)));

        assertThat(terminal.verify("dup.example", 22, keyB, HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE))
            .isTrue();

        assertThat(sftpReplaced.get()).isTrue();
        assertThat(terminalPrompt.replacementConflicts).isEmpty();
        assertThat(storedFingerprint(store, 0)).isEqualTo(SshHostKeyTrustManager.fingerprintSha256(keyB));

        // Once B is pinned, it is a plain match: nothing is asked any more.
        ResolvingPrompt laterPrompt = new ResolvingPrompt(MismatchResolution.KEEP_BLOCKED);
        assertThat(new SshHostKeyTrustManager(store, laterPrompt, () -> false)
            .verify("dup.example", 22, keyB, HostKeyCheckMode.STRICT, ReplacePolicy.INTERACTIVE)).isTrue();
        assertThat(laterPrompt.replacementAllowed).isEmpty();
    }

    // ---- List and remove ------------------------------------------------------------------------

    @Test
    void listTrustedKeysReturnsSortedPins() throws Exception {
        Path store = newStorePath();
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, new RecordingPrompt(true), () -> false);
        assertThat(manager.listTrustedKeys()).isEmpty();
        PublicKey zeta = newKey();
        PublicKey alphaHigh = newKey();
        PublicKey alpha = newKey();
        assertThat(manager.verify("Zeta.example", 22, zeta)).isTrue();
        assertThat(manager.verify("alpha.example", 2222, alphaHigh)).isTrue();
        assertThat(manager.verify("alpha.example", 22, alpha)).isTrue();

        List<SshHostKeyTrustManager.TrustedHostKey> keys = manager.listTrustedKeys();

        assertThat(keys.stream().map(key -> key.host() + ":" + key.port()).toList())
            .containsExactly("alpha.example:22", "alpha.example:2222", "zeta.example:22").inOrder();
        SshHostKeyTrustManager.TrustedHostKey first = keys.getFirst();
        assertThat(first.algorithm()).isEqualTo("ssh-rsa");
        assertThat(first.fingerprintSha256()).isEqualTo(SshHostKeyTrustManager.fingerprintSha256(alpha));
        assertThat(java.time.Instant.parse(first.trustedAt())).isNotNull();
    }

    @Test
    void listTrustedKeysFailsClosedOnMalformedStore() throws Exception {
        Path store = newStorePath();
        Files.writeString(store, "format=999\nentry.count=0\n");
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, new RecordingPrompt(true), () -> false);

        expectThrows(java.io.IOException.class, manager::listTrustedKeys);
    }

    @Test
    void removePinRequiresMatchingFingerprint() throws Exception {
        Path store = newStorePath();
        PublicKey key = newKey();
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, new RecordingPrompt(true), () -> false);
        assertThat(manager.verify("host.internal", 22, key)).isTrue();
        assertThat(manager.verify("other.internal", 22, newKey())).isTrue();
        byte[] before = Files.readAllBytes(store);

        assertThat(manager.removePin("host.internal", 22, SshHostKeyTrustManager.fingerprintSha256(newKey())))
            .isFalse();
        assertThat(manager.removePin("unknown.internal", 22, SshHostKeyTrustManager.fingerprintSha256(key)))
            .isFalse();
        assertThat(manager.removePin("host.internal", 2222, SshHostKeyTrustManager.fingerprintSha256(key)))
            .isFalse();
        assertThat(Files.readAllBytes(store)).isEqualTo(before);

        // Host spelling is normalized exactly like a connection's.
        assertThat(manager.removePin("HOST.internal.", 22, SshHostKeyTrustManager.fingerprintSha256(key))).isTrue();
        assertThat(manager.listTrustedKeys().stream().map(SshHostKeyTrustManager.TrustedHostKey::host).toList())
            .containsExactly("other.internal");
    }

    @Test
    void removingLastPinDeletesStoreAndFirstUsePromptsAgain() throws Exception {
        Path store = newStorePath();
        PublicKey key = newKey();
        RecordingPrompt prompt = new RecordingPrompt(true);
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt, () -> false);
        assertThat(manager.verify("host.internal", 22, key)).isTrue();

        assertThat(manager.removePin("host.internal", 22, SshHostKeyTrustManager.fingerprintSha256(key))).isTrue();

        // entry.count=0 would be a malformed store; the empty store is a missing file.
        assertThat(Files.exists(store)).isFalse();
        assertThat(manager.listTrustedKeys()).isEmpty();
        assertThat(manager.verify("host.internal", 22, key)).isTrue();
        assertThat(prompt.confirmations.get()).isEqualTo(2);
        assertThat(prompt.failures).isEmpty();
    }

    @Test
    void removePinThrowsPolicyRestrictionWhenLocked() throws Exception {
        Path store = newStorePath();
        PublicKey key = newKey();
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, new RecordingPrompt(true), () -> true);
        assertThat(manager.verify("host.internal", 22, key)).isTrue();
        byte[] before = Files.readAllBytes(store);

        expectThrows(PolicyRestrictionException.class,
            () -> manager.removePin("host.internal", 22, SshHostKeyTrustManager.fingerprintSha256(key)));

        assertThat(Files.readAllBytes(store)).isEqualTo(before);
    }

    @Test
    void lockSupplierFailureCountsAsLocked() throws Exception {
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(newStorePath(), new RecordingPrompt(true), () -> {
            throw new IllegalStateException("policy not readable");
        });

        assertThat(manager.isPinManagementLocked()).isTrue();
    }

    @Test
    void removePinPreservesOtherPinsAcrossManagers() throws Exception {
        Path store = newStorePath();
        PublicKey keyA = newKey();
        PublicKey keyB = newKey();
        SshHostKeyTrustManager firstManager =
            new SshHostKeyTrustManager(store, new RecordingPrompt(true), () -> false);
        assertThat(firstManager.verify("a.example", 22, keyA)).isTrue();
        assertThat(firstManager.verify("b.example", 22, keyB)).isTrue();

        SshHostKeyTrustManager secondManager =
            new SshHostKeyTrustManager(store, new RecordingPrompt(true), () -> false);
        assertThat(secondManager.removePin("a.example", 22, SshHostKeyTrustManager.fingerprintSha256(keyA))).isTrue();

        assertThat(firstManager.listTrustedKeys().stream().map(SshHostKeyTrustManager.TrustedHostKey::host).toList())
            .containsExactly("b.example");
        RecordingPrompt mustNotPrompt = new RecordingPrompt(false);
        assertThat(new SshHostKeyTrustManager(store, mustNotPrompt, () -> false).verify("b.example", 22, keyB))
            .isTrue();
        assertThat(mustNotPrompt.confirmations.get()).isEqualTo(0);
    }

    private static String storedFingerprint(Path store, int entry) throws Exception {
        Properties properties = new Properties();
        try (java.io.Reader reader = Files.newBufferedReader(store)) {
            properties.load(reader);
        }
        return properties.getProperty("entry." + entry + ".fingerprintSha256");
    }

    private static void awaitWaiting(AtomicReference<Thread> thread) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            Thread current = thread.get();
            if (current != null) {
                Thread.State state = current.getState();
                if (state == Thread.State.WAITING || state == Thread.State.TIMED_WAITING) {
                    return;
                }
            }
            Thread.sleep(5);
        }
        throw new AssertionError("The second handshake never waited on the shared first-use decision");
    }

    private static Path newStorePath() throws Exception {
        return Files.createTempDirectory("kortty-host-key-test-")
            .resolve(SshHostKeyTrustManager.STORE_FILE_NAME);
    }

    private static PublicKey newKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        // 2048-bit keeps the fixture above CodeQL's minimum recommended size; the trust
        // manager only treats this PublicKey as an opaque fingerprint blob, so key strength
        // does not affect the store/reload/mismatch behaviour under test.
        generator.initialize(2048);
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

    /**
     * Records whether a replacement was offered and answers the changed-key prompt with a fixed
     * resolution; optionally blocks in the first-use prompt or runs a hook while "the dialog is open".
     */
    private static final class ResolvingPrompt extends RecordingPrompt {
        private final MismatchResolution resolution;
        private final List<Boolean> replacementAllowed = java.util.Collections.synchronizedList(new ArrayList<>());
        private final List<SshHostKeyTrustManager.HostKeyMismatch> replacementConflicts =
            java.util.Collections.synchronizedList(new ArrayList<>());
        private volatile Runnable duringResolve;
        private volatile CountDownLatch firstUseEntered;
        private volatile CountDownLatch firstUseRelease;

        private ResolvingPrompt(MismatchResolution resolution) {
            super(true);
            this.resolution = resolution;
        }

        private void duringResolve(Runnable hook) {
            this.duringResolve = hook;
        }

        private void blockFirstUse(CountDownLatch entered, CountDownLatch release) {
            this.firstUseEntered = entered;
            this.firstUseRelease = release;
        }

        @Override
        public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
            boolean result = super.confirmFirstUse(details);
            CountDownLatch entered = firstUseEntered;
            CountDownLatch release = firstUseRelease;
            if (entered == null || release == null) {
                return result;
            }
            entered.countDown();
            try {
                return release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        @Override
        public MismatchResolution resolveMismatch(
                SshHostKeyTrustManager.HostKeyMismatch mismatch, boolean allowed) {
            mismatches.add(mismatch);
            replacementAllowed.add(allowed);
            Runnable hook = duringResolve;
            if (hook != null) {
                duringResolve = null;
                hook.run();
            }
            return resolution;
        }

        @Override
        public void warnReplacementConflict(SshHostKeyTrustManager.HostKeyMismatch mismatch) {
            replacementConflicts.add(mismatch);
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
