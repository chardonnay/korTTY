package de.kortty.core;

import de.kortty.model.AuthMethod;
import de.kortty.model.ServerConnection;
import de.kortty.persistence.XMLConnectionRepository;
import de.kortty.security.EncryptionService;
import org.testng.annotations.Test;

import javax.crypto.SecretKey;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;

/**
 * A start with "Require master password on startup" turned off loads connections.xml without the
 * vault key, so encrypted temporary SSH keys are cleared in memory. Unlocking later sets the key,
 * and every save after that used to write the cleared state — the stored temporary keys were gone
 * for good. These tests pin that a locked load, any number of saves, an unlock and a save keep them.
 */
class ConfigurationManagerLockedLoadTest {

    private static final String KEY_MATERIAL = """
        kortty-test-temporary-key-material
        line-two-of-fake-fixture
        """;

    @Test
    void lockedLoadThenUnlockThenSaveKeepsTheTemporaryKey() throws Exception {
        Path dir = Files.createTempDirectory("kortty-locked-load");
        try {
            SecretKey key = deriveTestKey();
            String id = seedTemporaryKeyConnection(dir, key);

            ConfigurationManager locked = new ConfigurationManager(dir);
            locked.load(null);
            ServerConnection loaded = locked.getConnectionById(id);
            assertThat(loaded.getTemporaryKeyContent()).isNull();
            assertThat(locked.isLoadedWithoutKey()).isTrue();

            assertThat(locked.onVaultUnlocked(key)).isEqualTo(1);
            assertThat(locked.isLoadedWithoutKey()).isFalse();
            assertThat(loaded.getTemporaryKeyContent()).isEqualTo(KEY_MATERIAL);
            assertThat(loaded.getPrivateKeyPath()).isEqualTo("TEMPORARY:" + KEY_MATERIAL);
            assertThat(loaded.getTemporaryKeyExpirationMinutes()).isEqualTo(45L);
            assertThat(loaded.isTemporaryKeyPermanent()).isTrue();

            locked.save(key);

            ServerConnection reloaded = reload(dir, key, id);
            assertThat(reloaded.getTemporaryKeyContent()).isEqualTo(KEY_MATERIAL);
            assertThat(reloaded.getTemporaryKeyExpirationMinutes()).isEqualTo(45L);
            assertThat(reloaded.isTemporaryKeyPermanent()).isTrue();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void savesWhileLockedKeepTheEncryptedKeyOnDisk() throws Exception {
        Path dir = Files.createTempDirectory("kortty-locked-save");
        try {
            SecretKey key = deriveTestKey();
            String id = seedTemporaryKeyConnection(dir, key);

            ConfigurationManager locked = new ConfigurationManager(dir);
            locked.load(null);
            // An unrelated edit saved while the vault is still locked (connection manager, tab rename, ...).
            locked.getConnectionById(id).setName("Renamed while locked");
            locked.save(null);

            String xml = Files.readString(dir.resolve("connections.xml"));
            assertThat(xml).contains("enc:");
            assertThat(xml).doesNotContain(KEY_MATERIAL.strip());

            locked.onVaultUnlocked(key);
            locked.save(key);

            ServerConnection reloaded = reload(dir, key, id);
            assertThat(reloaded.getName()).isEqualTo("Renamed while locked");
            assertThat(reloaded.getTemporaryKeyContent()).isEqualTo(KEY_MATERIAL);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void unlockKeepsInMemoryEditsOfTheSameConnection() throws Exception {
        Path dir = Files.createTempDirectory("kortty-locked-edit");
        try {
            SecretKey key = deriveTestKey();
            String id = seedTemporaryKeyConnection(dir, key);

            ConfigurationManager locked = new ConfigurationManager(dir);
            locked.load(null);
            ServerConnection connection = locked.getConnectionById(id);
            connection.setHost("edited.example.com");
            connection.setPort(2222);

            locked.onVaultUnlocked(key);

            assertThat(connection.getHost()).isEqualTo("edited.example.com");
            assertThat(connection.getPort()).isEqualTo(2222);
            assertThat(connection.getTemporaryKeyContent()).isEqualTo(KEY_MATERIAL);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void authenticationChangedWhileLockedWinsOverTheStoredKey() throws Exception {
        Path dir = Files.createTempDirectory("kortty-locked-reauth");
        try {
            SecretKey key = deriveTestKey();
            String id = seedTemporaryKeyConnection(dir, key);

            ConfigurationManager locked = new ConfigurationManager(dir);
            locked.load(null);
            ServerConnection connection = locked.getConnectionById(id);
            connection.setAuthMethod(AuthMethod.PASSWORD);

            assertThat(locked.onVaultUnlocked(key)).isEqualTo(0);
            assertThat(connection.getTemporaryKeyContent()).isNull();

            locked.save(key);
            ServerConnection reloaded = reload(dir, key, id);
            assertThat(reloaded.getAuthMethod()).isEqualTo(AuthMethod.PASSWORD);
            assertThat(reloaded.getTemporaryKeyContent()).isNull();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void saveWithTheKeyRestoresEvenWithoutAnUnlockNotice() throws Exception {
        Path dir = Files.createTempDirectory("kortty-locked-safety-net");
        try {
            SecretKey key = deriveTestKey();
            String id = seedTemporaryKeyConnection(dir, key);

            ConfigurationManager locked = new ConfigurationManager(dir);
            locked.load(null);
            // Unlocked by a path that did not call onVaultUnlocked: the save must not drop the key.
            locked.save(key);

            assertThat(locked.getConnectionById(id).getTemporaryKeyContent()).isEqualTo(KEY_MATERIAL);
            assertThat(reload(dir, key, id).getTemporaryKeyContent()).isEqualTo(KEY_MATERIAL);
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void aTemporaryKeyCountsAsLockedUntilTheUnlockOrANewAuthentication() throws Exception {
        Path dir = Files.createTempDirectory("kortty-locked-query");
        try {
            SecretKey key = deriveTestKey();
            String id = seedTemporaryKeyConnection(dir, key);

            ConfigurationManager unlocked = new ConfigurationManager(dir);
            unlocked.load(key);
            assertThat(unlocked.hasLockedTemporaryKey(id)).isFalse();

            ConfigurationManager locked = new ConfigurationManager(dir);
            locked.load(null);
            assertThat(locked.hasLockedTemporaryKey(id)).isTrue();
            assertThat(locked.hasLockedTemporaryKey(null)).isFalse();
            assertThat(locked.hasLockedTemporaryKey("no-such-connection")).isFalse();
            locked.onVaultUnlocked(key);
            assertThat(locked.hasLockedTemporaryKey(id)).isFalse();

            ConfigurationManager reauthenticated = new ConfigurationManager(dir);
            reauthenticated.load(null);
            reauthenticated.getConnectionById(id).setAuthMethod(AuthMethod.PASSWORD);
            assertThat(reauthenticated.hasLockedTemporaryKey(id)).isFalse();
        } finally {
            deleteRecursively(dir);
        }
    }

    @Test
    void unlockedLoadHasNothingToRestore() throws Exception {
        Path dir = Files.createTempDirectory("kortty-unlocked-load");
        try {
            SecretKey key = deriveTestKey();
            String id = seedTemporaryKeyConnection(dir, key);

            ConfigurationManager unlocked = new ConfigurationManager(dir);
            unlocked.load(key);

            assertThat(unlocked.isLoadedWithoutKey()).isFalse();
            assertThat(unlocked.getConnectionById(id).getTemporaryKeyContent()).isEqualTo(KEY_MATERIAL);
            assertThat(unlocked.onVaultUnlocked(key)).isEqualTo(0);
        } finally {
            deleteRecursively(dir);
        }
    }

    private static String seedTemporaryKeyConnection(Path dir, SecretKey key) throws Exception {
        ServerConnection connection = new ServerConnection();
        connection.setName("Temporary key connection");
        connection.setHost("example.com");
        connection.setUsername("root");
        connection.setAuthMethod(AuthMethod.PUBLIC_KEY);
        connection.setTemporaryKeyContent(KEY_MATERIAL);
        connection.setTemporaryKeyExpirationMinutes(45L);
        connection.setTemporaryKeyPermanent(true);
        connection.setPrivateKeyPath("TEMPORARY:" + KEY_MATERIAL);
        new XMLConnectionRepository(dir).saveConnections(List.of(connection), key);
        return connection.getId();
    }

    private static ServerConnection reload(Path dir, SecretKey key, String id) throws Exception {
        List<ServerConnection> connections = new XMLConnectionRepository(dir).loadConnections(key);
        return connections.stream().filter(c -> id.equals(c.getId())).findFirst().orElseThrow();
    }

    private static SecretKey deriveTestKey() throws Exception {
        EncryptionService encryptionService = new EncryptionService();
        return encryptionService.deriveKey("test-master-password".toCharArray(), encryptionService.generateSalt());
    }

    private static void deleteRecursively(Path dir) throws Exception {
        if (!Files.exists(dir)) {
            return;
        }
        try (var paths = Files.walk(dir)) {
            paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> p.toFile().delete());
        }
    }
}
