package de.kortty.persistence;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import de.kortty.security.EncryptionService;
import org.testng.annotations.Test;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;


class XMLConnectionRepositoryTest {

    @Test
    void saveAndLoadEncryptsTemporaryKeyMaterial() throws Exception {
        Path dir = Files.createTempDirectory("kortty-xml-repo");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            SecretKey key = deriveTestKey();
            String tempKeyContent = """
                kortty-test-temporary-key-material
                line-two-of-fake-fixture
                line-three-of-fake-fixture
                """;

            ServerConnection connection = new ServerConnection();
            connection.setName("Temporary key connection");
            connection.setHost("example.com");
            connection.setUsername("root");
            connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
            connection.setTemporaryKeyContent(tempKeyContent);
            connection.setTemporaryKeyExpirationMinutes(60L);
            connection.setTemporaryKeyPermanent(true);
            connection.setPrivateKeyPath("TEMPORARY:" + tempKeyContent);

            repository.saveConnections(List.of(connection), key);

            String persistedXml = Files.readString(dir.resolve("connections.xml"));
            assertThat(persistedXml.contains(tempKeyContent)).isFalse();
            assertThat(persistedXml.contains("TEMPORARY:" + tempKeyContent)).isFalse();
            assertThat(persistedXml.contains("enc:")).isTrue();

            List<ServerConnection> reloaded = repository.loadConnections(key);
            assertThat(reloaded.size()).isEqualTo(1);
            ServerConnection reloadedConnection = reloaded.get(0);
            assertThat(reloadedConnection.getTemporaryKeyContent()).isEqualTo(tempKeyContent);
            assertThat(reloadedConnection.getPrivateKeyPath()).isEqualTo("TEMPORARY:" + tempKeyContent);
            assertThat(reloadedConnection.getTemporaryKeyExpirationMinutes()).isEqualTo(60L);
            assertThat(reloadedConnection.isTemporaryKeyPermanent()).isTrue();
        } finally {
            Files.deleteIfExists(dir.resolve("connections.xml"));
            Files.deleteIfExists(dir);
        }
    }

    @Test
    void saveRejectsPlaintextTemporaryKeysWithoutUnlockedMasterPassword() throws Exception {
        Path dir = Files.createTempDirectory("kortty-xml-repo-no-key");
        try {
            XMLConnectionRepository repository = new XMLConnectionRepository(dir);
            ServerConnection connection = new ServerConnection();
            connection.setHost("example.com");
            connection.setUsername("root");
            connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
            connection.setTemporaryKeyContent("temporary-key");
            connection.setPrivateKeyPath("TEMPORARY:temporary-key");

            expectThrows(IllegalStateException.class, () -> repository.saveConnections(List.of(connection), null));
        } finally {
            Files.deleteIfExists(dir.resolve("connections.xml"));
            Files.deleteIfExists(dir);
        }
    }

    private SecretKey deriveTestKey() throws Exception {
        EncryptionService encryptionService = new EncryptionService();
        return encryptionService.deriveKey("test-master-password".toCharArray(), encryptionService.generateSalt());
    }
}
