package de.kortty.core;

import org.apache.sshd.common.config.keys.KeyUtils;
import org.testng.annotations.Test;

import java.io.IOException;
import java.security.KeyPair;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;
import static org.testng.Assert.expectThrows;

/**
 * Pins that {@code TEMPORARY:} keys parse in memory in every format the former temp-file path
 * accepted, and that a failure never echoes key text.
 */
class TemporarySshKeyMaterialTest {

    @Test
    void recognisesOnlyTheExactPrefix() {
        assertThat(TemporarySshKeyMaterial.isTemporaryKeyPath("TEMPORARY:-----BEGIN")).isTrue();
        assertThat(TemporarySshKeyMaterial.isTemporaryKeyPath("TEMPORARY:")).isTrue();
        assertThat(TemporarySshKeyMaterial.isTemporaryKeyPath(null)).isFalse();
        assertThat(TemporarySshKeyMaterial.isTemporaryKeyPath("")).isFalse();
        assertThat(TemporarySshKeyMaterial.isTemporaryKeyPath("/home/me/.ssh/id_ed25519")).isFalse();
        assertThat(TemporarySshKeyMaterial.isTemporaryKeyPath("temporary:-----BEGIN")).isFalse();
        assertThat(TemporarySshKeyMaterial.isTemporaryKeyPath(" TEMPORARY:x")).isFalse();
    }

    @Test
    void loadsAnRsaPkcs8Pem() throws Exception {
        KeyPair keyPair = TemporaryKeyTestFixtures.rsaKeyPair();

        List<KeyPair> loaded = TemporarySshKeyMaterial.load(
            null, TemporarySshKeyMaterial.PREFIX + TemporaryKeyTestFixtures.pkcs8Pem(keyPair));

        assertThat(loaded).hasSize(1);
        assertThat(KeyUtils.compareKeys(loaded.get(0).getPublic(), keyPair.getPublic())).isTrue();
        assertThat(loaded.get(0).getPrivate()).isNotNull();
    }

    @Test
    void loadsAnRsaTraditionalPem() throws Exception {
        KeyPair keyPair = TemporaryKeyTestFixtures.rsaKeyPair();
        String pem = TemporaryKeyTestFixtures.traditionalPem(keyPair);
        assertThat(pem).contains("BEGIN RSA PRIVATE KEY");

        List<KeyPair> loaded = TemporarySshKeyMaterial.load(null, TemporarySshKeyMaterial.PREFIX + pem);

        assertThat(KeyUtils.compareKeys(loaded.get(0).getPublic(), keyPair.getPublic())).isTrue();
    }

    @Test
    void loadsAnOpenSshEd25519KeyWithoutItsTrailingNewline() throws Exception {
        KeyPair keyPair = TemporaryKeyTestFixtures.ed25519KeyPair();
        String openSsh = TemporaryKeyTestFixtures.openSshPrivateKey(keyPair).stripTrailing();
        assertThat(openSsh).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----");
        assertThat(openSsh).doesNotContain("\n-----END OPENSSH PRIVATE KEY-----\n");

        List<KeyPair> loaded = TemporarySshKeyMaterial.load(null, TemporarySshKeyMaterial.PREFIX + openSsh);

        assertThat(loaded).hasSize(1);
        assertThat(KeyUtils.compareKeys(loaded.get(0).getPublic(), keyPair.getPublic())).isTrue();
    }

    @Test
    void loadsAKeyPastedWithWindowsLineEndings() throws Exception {
        KeyPair keyPair = TemporaryKeyTestFixtures.ed25519KeyPair();
        String crlf = TemporaryKeyTestFixtures.openSshPrivateKey(keyPair).replace("\n", "\r\n");

        List<KeyPair> loaded = TemporarySshKeyMaterial.load(null, TemporarySshKeyMaterial.PREFIX + crlf);

        assertThat(KeyUtils.compareKeys(loaded.get(0).getPublic(), keyPair.getPublic())).isTrue();
    }

    @Test
    void returnsAnUnmodifiableList() throws Exception {
        List<KeyPair> loaded = TemporarySshKeyMaterial.load(null,
            TemporarySshKeyMaterial.PREFIX
                + TemporaryKeyTestFixtures.openSshPrivateKey(TemporaryKeyTestFixtures.ed25519KeyPair()));

        assertThrows(UnsupportedOperationException.class, loaded::clear);
    }

    @Test
    void garbageFailsWithoutEchoingTheInput() {
        String garbage = "this-is-not-a-key-but-a-secret-7f3a91c2";

        IOException failure = expectThrows(IOException.class,
            () -> TemporarySshKeyMaterial.load(null, TemporarySshKeyMaterial.PREFIX + garbage));

        assertThat(failure).hasMessageThat().contains("temporary SSH key");
        assertThat(failure).hasMessageThat().doesNotContain(garbage);
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void aCorruptArmouredKeyFailsWithoutEchoingTheKeyBody() throws Exception {
        String body = "b3BlbnNzaC1rZXktdjEAAAAABG5vbmUAAAAEbm9uZQAAAAAAAAABAAAAMwAAAAtzc2gtZW"
            + "QyNTUxOQAAACDcorruptedcorruptedcorrupted";
        String key = "-----BEGIN OPENSSH PRIVATE KEY-----\n" + body + "\n-----END OPENSSH PRIVATE KEY-----\n";

        IOException failure = expectThrows(IOException.class,
            () -> TemporarySshKeyMaterial.load(null, TemporarySshKeyMaterial.PREFIX + key));

        assertThat(failure).hasMessageThat().doesNotContain(body);
        assertThat(failure).hasMessageThat().doesNotContain(body.substring(0, 20));
        assertThat(failure.getCause()).isNull();
    }

    @Test
    void anEmptyTemporaryKeyFails() {
        IOException failure = expectThrows(IOException.class,
            () -> TemporarySshKeyMaterial.load(null, TemporarySshKeyMaterial.PREFIX));

        assertThat(failure).hasMessageThat().contains("no key pairs found");
    }

    @Test
    void rejectsAPathThatIsNotATemporaryKey() {
        assertThrows(IllegalArgumentException.class,
            () -> TemporarySshKeyMaterial.load(null, "/home/me/.ssh/id_ed25519"));
        assertThrows(IllegalArgumentException.class, () -> TemporarySshKeyMaterial.load(null, null));
    }
}
