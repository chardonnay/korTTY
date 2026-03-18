package de.kortty.persistence;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import de.kortty.security.EncryptionService;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
            assertFalse(persistedXml.contains(tempKeyContent));
            assertFalse(persistedXml.contains("TEMPORARY:" + tempKeyContent));
            assertTrue(persistedXml.contains("enc:"));

            List<ServerConnection> reloaded = repository.loadConnections(key);
            assertEquals(1, reloaded.size());
            ServerConnection reloadedConnection = reloaded.get(0);
            assertEquals(tempKeyContent, reloadedConnection.getTemporaryKeyContent());
            assertEquals("TEMPORARY:" + tempKeyContent, reloadedConnection.getPrivateKeyPath());
            assertEquals(60L, reloadedConnection.getTemporaryKeyExpirationMinutes());
            assertTrue(reloadedConnection.isTemporaryKeyPermanent());
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

            assertThrows(IllegalStateException.class, () -> repository.saveConnections(List.of(connection), null));
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
