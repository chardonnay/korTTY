package de.kortty.core;

import de.kortty.core.OpenSshKnownHostsParser.KnownHostsEntry;
import de.kortty.core.OpenSshKnownHostsParser.KnownHostsFile;
import de.kortty.core.SshHostKeyTrustManager.KnownHostsConflict;
import de.kortty.core.SshHostKeyTrustManager.KnownHostsImportResult;
import de.kortty.core.SshHostKeyTrustManager.TrustedHostKey;
import org.apache.sshd.common.config.keys.PublicKeyEntry;
import org.apache.sshd.common.config.keys.PublicKeyEntryResolver;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.PublicKey;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class SshHostKeyTrustManagerKnownHostsImportTest {

    @Test
    void importAddsNewHostsAndPinsTheKeyTheClientNegotiatesFirst() throws Exception {
        Path store = newStorePath();
        CountingPrompt prompt = new CountingPrompt();
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, prompt, () -> false);
        KnownHostsFile file = OpenSshKnownHostsParser.parse(OpenSshKnownHostsParserTest.fixture());

        KnownHostsImportResult result = manager.importKnownHosts(file, false);

        assertThat(result.dryRun()).isFalse();
        assertThat(hostPorts(result.added())).containsExactly(
            "192.0.2.10:22", "2001:db8::1:2200", "alt.example.com:2222", "multi.example.com:22",
            "plain.example.com:22", "web.example.com:22").inOrder();
        assertThat(result.alreadyTrusted()).isEqualTo(0);
        assertThat(result.conflicts()).isEmpty();
        // multi.example.com lists rsa, ed25519 and ecdsa: only the ecdsa key is pinned
        assertThat(result.additionalKeys()).isEqualTo(2);
        // port22.example.com and both duplicate.example.com lines use the key the file revokes
        assertThat(result.revokedSkipped()).isEqualTo(3);
        assertThat(result.trustedButRevoked()).isEmpty();

        assertThat(manager.listTrustedKeys()).hasSize(6);
        PublicKey multiEcdsa = keyOf(file, "multi.example.com", "ecdsa-sha2-nistp256");
        PublicKey multiEd25519 = keyOf(file, "multi.example.com", "ssh-ed25519");
        assertThat(manager.verify("MULTI.example.com", 22, multiEcdsa)).isTrue();
        assertThat(manager.verify("alt.example.com", 2222, keyOf(file, "alt.example.com", "ssh-rsa"))).isTrue();
        assertThat(prompt.firstUse.get()).isEqualTo(0);
        assertThat(manager.verify("multi.example.com", 22, multiEd25519)).isFalse();
        assertThat(prompt.mismatches.get()).isEqualTo(1);
        // the port is part of the endpoint: alt.example.com:22 is still unknown and asks as first use
        assertThat(manager.verify("alt.example.com", 22, keyOf(file, "alt.example.com", "ssh-rsa"))).isTrue();
        assertThat(prompt.firstUse.get()).isEqualTo(1);
    }

    @Test
    void dryRunReportsTheSameOutcomeWithoutWriting() throws Exception {
        Path store = newStorePath();
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, new CountingPrompt(), () -> false);
        KnownHostsFile file = OpenSshKnownHostsParser.parse(OpenSshKnownHostsParserTest.fixture());

        KnownHostsImportResult preview = manager.importKnownHosts(file, true);

        assertThat(preview.dryRun()).isTrue();
        assertThat(preview.added()).hasSize(6);
        assertThat(Files.exists(store)).isFalse();
        assertThat(hostPorts(manager.importKnownHosts(file, false).added()))
            .containsExactlyElementsIn(hostPorts(preview.added())).inOrder();
    }

    @Test
    void reimportCountsEverythingAsAlreadyTrustedAndLeavesTheStoreUntouched() throws Exception {
        Path store = newStorePath();
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, new CountingPrompt(), () -> false);
        KnownHostsFile file = OpenSshKnownHostsParser.parse(OpenSshKnownHostsParserTest.fixture());
        manager.importKnownHosts(file, false);
        byte[] before = Files.readAllBytes(store);

        KnownHostsImportResult again = manager.importKnownHosts(file, false);

        assertThat(again.added()).isEmpty();
        assertThat(again.alreadyTrusted()).isEqualTo(6);
        assertThat(again.conflicts()).isEmpty();
        assertThat(Files.readAllBytes(store)).isEqualTo(before);
    }

    @Test
    void neverOverwritesADifferentTrustedKeyAndReportsTheConflict() throws Exception {
        Path store = newStorePath();
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, new CountingPrompt(), () -> false);
        KnownHostsFile file = OpenSshKnownHostsParser.parse(OpenSshKnownHostsParserTest.fixture());
        PublicKey other = newRsaKey();
        assertThat(manager.verify("plain.example.com", 22, other)).isTrue();
        // a pin on one of multi's non-preferred keys is a match, not a conflict
        PublicKey multiEd25519 = keyOf(file, "multi.example.com", "ssh-ed25519");
        assertThat(manager.verify("multi.example.com", 22, multiEd25519)).isTrue();

        KnownHostsImportResult result = manager.importKnownHosts(file, false);

        assertThat(result.alreadyTrusted()).isEqualTo(1);
        assertThat(result.conflicts()).hasSize(1);
        KnownHostsConflict conflict = result.conflicts().getFirst();
        assertThat(conflict.host()).isEqualTo("plain.example.com");
        assertThat(conflict.port()).isEqualTo(22);
        assertThat(conflict.trustedAlgorithm()).isEqualTo("ssh-rsa");
        assertThat(conflict.trustedFingerprintSha256()).isEqualTo(SshHostKeyTrustManager.fingerprintSha256(other));
        assertThat(conflict.fileAlgorithm()).isEqualTo("ssh-ed25519");
        assertThat(conflict.lineNumber()).isEqualTo(4);
        assertThat(hostPorts(result.added())).doesNotContain("plain.example.com:22");
        assertThat(hostPorts(result.added())).doesNotContain("multi.example.com:22");
        assertThat(manager.verify("plain.example.com", 22, other)).isTrue();
        assertThat(manager.verify("multi.example.com", 22, multiEd25519)).isTrue();
    }

    @Test
    void policyLockOnPinChangesStillAllowsAddingButNeverReplaces() throws Exception {
        Path store = newStorePath();
        SshHostKeyTrustManager unlocked = new SshHostKeyTrustManager(store, new CountingPrompt(), () -> false);
        PublicKey other = newRsaKey();
        assertThat(unlocked.verify("web.example.com", 22, other)).isTrue();
        SshHostKeyTrustManager locked = new SshHostKeyTrustManager(store, new CountingPrompt(), () -> true);
        KnownHostsFile file = OpenSshKnownHostsParser.parse(OpenSshKnownHostsParserTest.fixture());

        KnownHostsImportResult result = locked.importKnownHosts(file, false);

        assertThat(result.added()).hasSize(5);
        assertThat(result.conflicts()).hasSize(1);
        assertThat(locked.verify("web.example.com", 22, other)).isTrue();
    }

    @Test
    void reportsTrustedKeysTheFileRevokes() throws Exception {
        Path store = newStorePath();
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, new CountingPrompt(), () -> false);
        KnownHostsFile file = OpenSshKnownHostsParser.parse(OpenSshKnownHostsParserTest.fixture());
        PublicKey revoked = keyOf(file, "port22.example.com", "ssh-ed25519");
        assertThat(manager.verify("old.example.com", 2200, revoked)).isTrue();

        KnownHostsImportResult result = manager.importKnownHosts(file, false);

        assertThat(hostPorts(result.trustedButRevoked())).containsExactly("old.example.com:2200");
        // the pin is reported, not removed
        assertThat(manager.verify("old.example.com", 2200, revoked)).isTrue();
    }

    @Test
    void duplicateLinesForOneHostAddOneKey() throws Exception {
        Path store = newStorePath();
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, new CountingPrompt(), () -> false);
        String keyPart = OpenSshKnownHostsParserTest.fixture().lines()
            .filter(line -> line.startsWith("plain.example.com ")).findFirst().orElseThrow().split(" ", 2)[1];
        KnownHostsFile file = OpenSshKnownHostsParser.parse(
            "Twice.Example.com " + keyPart + "\ntwice.example.com. " + keyPart + "\n[twice.example.com]:22 " + keyPart);

        KnownHostsImportResult result = manager.importKnownHosts(file, false);

        assertThat(hostPorts(result.added())).containsExactly("twice.example.com:22");
        assertThat(result.additionalKeys()).isEqualTo(0);
    }

    @Test
    void malformedStoreFailsTheImportWithoutTouchingIt() throws Exception {
        Path store = newStorePath();
        Files.createDirectories(store.getParent());
        Files.writeString(store, "format=1\nentry.count=nope\n");
        SshHostKeyTrustManager manager = new SshHostKeyTrustManager(store, new CountingPrompt(), () -> false);
        KnownHostsFile file = OpenSshKnownHostsParser.parse(OpenSshKnownHostsParserTest.fixture());

        expectThrows(IOException.class, () -> manager.importKnownHosts(file, false));
        expectThrows(IOException.class, () -> manager.importKnownHosts(file, true));
        assertThat(Files.readString(store)).isEqualTo("format=1\nentry.count=nope\n");
    }

    private static PublicKey keyOf(KnownHostsFile file, String host, String keyType) throws Exception {
        KnownHostsEntry entry = file.entries().stream()
            .filter(candidate -> candidate.host().equals(host) && candidate.keyType().equals(keyType))
            .findFirst()
            .orElseThrow();
        return PublicKeyEntry.parsePublicKeyEntry(entry.publicKeyLine())
            .resolvePublicKey(null, Map.of(), PublicKeyEntryResolver.FAILING);
    }

    private static List<String> hostPorts(List<TrustedHostKey> keys) {
        return keys.stream().map(key -> key.host() + ":" + key.port()).toList();
    }

    private static Path newStorePath() throws Exception {
        return Files.createTempDirectory("kortty-known-hosts-import-")
            .resolve(SshHostKeyTrustManager.STORE_FILE_NAME);
    }

    private static PublicKey newRsaKey() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        return generator.generateKeyPair().getPublic();
    }

    private static final class CountingPrompt implements SshHostKeyTrustManager.HostKeyPrompt {
        private final AtomicInteger firstUse = new AtomicInteger();
        private final AtomicInteger mismatches = new AtomicInteger();

        @Override
        public boolean confirmFirstUse(SshHostKeyTrustManager.HostKeyDetails details) {
            firstUse.incrementAndGet();
            return true;
        }

        @Override
        public void warnMismatch(SshHostKeyTrustManager.HostKeyMismatch mismatch) {
            mismatches.incrementAndGet();
        }

        @Override
        public void warnVerificationFailure(SshHostKeyTrustManager.HostKeyVerificationFailure failure) {
        }
    }
}
