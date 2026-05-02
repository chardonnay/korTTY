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

    private Path createEncryptedPkcs8Pem(String passphrase) throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048);
        java.security.KeyPair keyPair = generator.generateKeyPair();

        Path pemFile = Files.createTempFile("kortty-encrypted-key-", ".pem");
        try (JcaPEMWriter writer = new JcaPEMWriter(Files.newBufferedWriter(pemFile, StandardCharsets.US_ASCII))) {
            JceOpenSSLPKCS8EncryptorBuilder encryptorBuilder =
                new JceOpenSSLPKCS8EncryptorBuilder(PKCS8Generator.AES_256_CBC);
            encryptorBuilder.setRandom(new SecureRandom());
            encryptorBuilder.setPasssword(passphrase.toCharArray());
            writer.writeObject(new JcaPKCS8Generator(keyPair.getPrivate(), encryptorBuilder.build()));
        } catch (IOException e) {
            Files.deleteIfExists(pemFile);
            throw e;
        }
        return pemFile;
    }
}
