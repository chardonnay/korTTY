package de.kortty.core;

import org.apache.sshd.common.keyprovider.FileKeyPairProvider;
import org.apache.sshd.common.util.security.SecurityUtils;
import org.bouncycastle.openssl.PKCS8Generator;
import org.bouncycastle.openssl.jcajce.JcaPEMWriter;
import org.bouncycastle.openssl.jcajce.JcaPKCS8Generator;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8EncryptorBuilder;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPairGenerator;
import java.security.SecureRandom;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;


class SshKeyCompatibilityTest {

    @Test
    void fileKeyPairProviderLoadsEncryptedPkcs8PemKeys() throws Exception {
        assertWithMessage("BouncyCastle must be available for encrypted PKCS#8 PEM keys").that(SecurityUtils.isBouncyCastleRegistered()).isTrue();

        Path pemFile = createEncryptedPkcs8Pem("test-passphrase");
        try {
            FileKeyPairProvider keyPairProvider = new FileKeyPairProvider(pemFile);
            keyPairProvider.setPasswordFinder((session, resource, retryIndex) -> "test-passphrase");

            int count = 0;
            for (java.security.KeyPair keyPair : keyPairProvider.loadKeys(null)) {
                assertThat(keyPair).isNotNull();
                assertThat(keyPair.getPrivate()).isNotNull();
                assertThat(keyPair.getPublic()).isNotNull();
                count++;
            }

            assertWithMessage("Exactly one encrypted PEM key pair should be loaded").that(count).isEqualTo(1);
        } finally {
            Files.deleteIfExists(pemFile);
        }
    }

    /**
     * Ed25519 SSH support must come from BouncyCastle, not net.i2p.crypto:eddsa.
     *
     * That library was dropped for CVE-2020-36843 (signature malleability, abandoned upstream with
     * no patched release). Nothing in korTTY imports it directly — it was a pure classpath provider —
     * so removing it breaks ssh-ed25519 host keys and key auth *silently*, with no compile error.
     * MINA also prefers the registrar literally named "EdDSA" (that library's) over every other
     * provider, so putting it back would silently reclaim Ed25519 from BouncyCastle just as quietly.
     * This test fails in both directions.
     */
    @Test
    void ed25519SupportIsProvidedByBouncyCastle() throws Exception {
        assertWithMessage("net.i2p.crypto:eddsa must not be on the classpath (CVE-2020-36843)")
            .that(SecurityUtils.isNetI2pCryptoEdDSARegistered()).isFalse();
        assertWithMessage("Ed25519 SSH support must still be available after dropping net.i2p.crypto:eddsa")
            .that(SecurityUtils.isEDDSACurveSupported()).isTrue();

        String backend = SecurityUtils.getEdDSASupport()
            .orElseThrow(() -> new AssertionError("no EdDSA backend registered"))
            .getClass().getName();
        assertWithMessage("Ed25519 must be backed by BouncyCastle").that(backend).contains("bouncycastle");
    }

    /** An OpenSSH ed25519 key must still round-trip through MINA's key readers via BouncyCastle. */
    @Test
    void opensshEd25519PrivateKeyStillLoads() throws Exception {
        java.security.KeyPair keyPair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();

        Path pemFile = Files.createTempFile("kortty-ed25519-key-", ".pem");
        try {
            try (JcaPEMWriter writer = new JcaPEMWriter(Files.newBufferedWriter(pemFile, StandardCharsets.US_ASCII))) {
                writer.writeObject(new JcaPKCS8Generator(keyPair.getPrivate(), null));
            }

            int count = 0;
            for (java.security.KeyPair loaded : new FileKeyPairProvider(pemFile).loadKeys(null)) {
                assertThat(loaded.getPublic().getAlgorithm()).isAnyOf("Ed25519", "EdDSA");
                count++;
            }
            assertWithMessage("the ed25519 key pair should load").that(count).isEqualTo(1);
        } finally {
            Files.deleteIfExists(pemFile);
        }
    }

    private Path createEncryptedPkcs8Pem(String passphrase) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair keyPair = generator.generateKeyPair();

        Path pemFile = Files.createTempFile("kortty-encrypted-key-", ".pem");
        try (JcaPEMWriter writer = new JcaPEMWriter(Files.newBufferedWriter(pemFile, StandardCharsets.US_ASCII))) {
            JceOpenSSLPKCS8EncryptorBuilder encryptorBuilder =
                new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC);
            encryptorBuilder.setRandom(new SecureRandom());
            encryptorBuilder.setPassword(passphrase.toCharArray());
            writer.writeObject(new JcaPKCS8Generator(keyPair.getPrivate(), encryptorBuilder.build()));
        } catch (IOException e) {
            Files.deleteIfExists(pemFile);
            throw e;
        }
        return pemFile;
    }
}
