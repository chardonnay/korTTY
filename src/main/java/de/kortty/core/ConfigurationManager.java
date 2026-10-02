package de.kortty.core;

import de.kortty.model.AuthMethod;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.WindowGeometry;
import de.kortty.persistence.XMLConnectionRepository;
import de.kortty.persistence.XMLConnectionRepository.StoredTemporaryKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Manages application configuration including connections and global settings.
 */
public class ConfigurationManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationManager.class);
    
    private final Path configDir;
    private final XMLConnectionRepository connectionRepository;
    
    private ConnectionSettings globalSettings;
    private WindowGeometry defaultWindowGeometry;
    private List<ServerConnection> connections;
    private String lastOpenedProject;

    /**
     * Whether the connections in memory were loaded without the vault key (master password not
     * entered at startup) and the vault has not been unlocked since.
     */
    private boolean loadedWithoutKey;

    /**
     * Connections whose encrypted temporary SSH key the locked load could not decrypt, by id, with
     * the authentication they had then. The key is cleared in memory but still in connections.xml;
     * it is written back on every save and restored once the vault is unlocked.
     */
    private final Map<String, LockedTemporaryKey> lockedTemporaryKeys = new LinkedHashMap<>();

    private record LockedTemporaryKey(AuthMethod authMethod, String sshKeyId) {
    }
    
    public ConfigurationManager(Path configDir) {
        this.configDir = configDir;
        this.connectionRepository = new XMLConnectionRepository(configDir);
        this.globalSettings = new ConnectionSettings();
        this.defaultWindowGeometry = new WindowGeometry(100, 100, 900, 600);
        this.connections = new ArrayList<>();
    }
    
    /**
     * Loads configuration from disk.
     */
    public void load(SecretKey key) {
        lockedTemporaryKeys.clear();
        loadedWithoutKey = key == null;
        try {
            Set<String> undecryptedIds = new LinkedHashSet<>();
            connections = connectionRepository.loadConnections(key, undecryptedIds);
            for (ServerConnection connection : connections) {
                if (connection != null && undecryptedIds.contains(connection.getId())) {
                    lockedTemporaryKeys.put(connection.getId(),
                        new LockedTemporaryKey(connection.getAuthMethod(), connection.getSshKeyId()));
                }
            }
            logger.info("Loaded {} connections", connections.size());
            if (!lockedTemporaryKeys.isEmpty()) {
                logger.info("{} temporary SSH key(s) stay encrypted on disk until the vault is unlocked",
                    lockedTemporaryKeys.size());
            }
        } catch (Exception e) {
            logger.error("Failed to load connections", e);
            connections = new ArrayList<>();
        }
    }
    
    /**
     * Saves configuration to disk.
     */
    public void save(SecretKey key) {
        try {
            if (key != null && loadedWithoutKey) {
                // Unlocked by a path that did not report it: restore before the keys could be lost.
                onVaultUnlocked(key);
            }
            connectionRepository.saveConnections(connections, key, temporaryKeysToPreserve());
            logger.info("Saved {} connections", connections.size());
        } catch (Exception e) {
            logger.error("Failed to save connections", e);
        }
    }

    /**
     * Called once the vault has been unlocked after a load without its key. Re-reads
     * connections.xml, where the temporary SSH keys of that load are still stored encrypted, and
     * puts each one back on its connection — key material, key path and expiry only, so every other
     * in-memory edit stays. A connection whose authentication was changed in the meantime keeps
     * that change and does not get the old key back.
     *
     * @return how many temporary keys were restored
     */
    public int onVaultUnlocked(SecretKey key) {
        Objects.requireNonNull(key, "key");
        loadedWithoutKey = false;
        forgetReconfiguredTemporaryKeys();
        if (lockedTemporaryKeys.isEmpty()) {
            return 0;
        }
        Map<String, StoredTemporaryKey> stored;
        try {
            stored = connectionRepository.readStoredTemporaryKeys();
        } catch (Exception e) {
            logger.warn("Could not re-read connections.xml to restore temporary SSH keys after unlocking", e);
            return 0;
        }
        int restored = 0;
        for (Iterator<Map.Entry<String, LockedTemporaryKey>> it = lockedTemporaryKeys.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<String, LockedTemporaryKey> entry = it.next();
            StoredTemporaryKey storedKey = stored.get(entry.getKey());
            ServerConnection connection = getConnectionById(entry.getKey());
            if (storedKey == null || connection == null) {
                it.remove();
                continue;
            }
            try {
                XMLConnectionRepository.restoreTemporaryKey(connection, storedKey, key);
                it.remove();
                restored++;
            } catch (Exception e) {
                // Stays registered, so later saves keep writing the encrypted copy back.
                logger.warn("Temporary SSH key for connection '{}' could not be decrypted after unlocking; it stays stored",
                    connection.getDisplayName(), e);
            }
        }
        logger.info("Restored {} temporary SSH key(s) after unlocking the vault", restored);
        return restored;
    }

    /** Whether the connections were loaded with the vault locked and it has not been unlocked since. */
    public boolean isLoadedWithoutKey() {
        return loadedWithoutKey;
    }

    /**
     * The encrypted temporary keys a save must write back unchanged: those of connections the
     * locked load cleared and nobody has re-configured since.
     */
    private Map<String, StoredTemporaryKey> temporaryKeysToPreserve() {
        forgetReconfiguredTemporaryKeys();
        if (lockedTemporaryKeys.isEmpty()) {
            return Map.of();
        }
        try {
            Map<String, StoredTemporaryKey> stored = connectionRepository.readStoredTemporaryKeys();
            stored.keySet().retainAll(lockedTemporaryKeys.keySet());
            return stored;
        } catch (Exception e) {
            logger.warn("Could not read the stored temporary SSH keys; this save may drop them", e);
            return Map.of();
        }
    }

    /**
     * Drops the entries whose connection was removed or whose authentication was set up anew, so
     * the user's change wins over the key from disk.
     */
    private void forgetReconfiguredTemporaryKeys() {
        lockedTemporaryKeys.entrySet().removeIf(entry -> {
            ServerConnection connection = getConnectionById(entry.getKey());
            return connection == null || !stillAsLoaded(connection, entry.getValue());
        });
    }

    private static boolean stillAsLoaded(ServerConnection connection, LockedTemporaryKey loaded) {
        return connection.getAuthMethod() == loaded.authMethod()
            && Objects.equals(connection.getSshKeyId(), loaded.sshKeyId())
            && isBlank(connection.getTemporaryKeyContent())
            && isBlank(connection.getPrivateKeyPath());
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
    
    // Connection management
    
    public List<ServerConnection> getConnections() {
        return new ArrayList<>(connections);
    }
    
    public void addConnection(ServerConnection connection) {
        connections.add(connection);
    }
    
    public void updateConnection(ServerConnection connection) {
        for (int i = 0; i < connections.size(); i++) {
            if (connections.get(i).getId().equals(connection.getId())) {
                connections.set(i, connection);
                return;
            }
        }
    }
    
    public void removeConnection(ServerConnection connection) {
        connections.removeIf(c -> c.getId().equals(connection.getId()));
    }
    
    public ServerConnection getConnectionById(String id) {
        return connections.stream()
                .filter(c -> c != null && Objects.equals(c.getId(), id))
                .findFirst()
                .orElse(null);
    }
    
    // Global settings
    
    public ConnectionSettings getGlobalSettings() {
        return globalSettings;
    }
    
    public void setGlobalSettings(ConnectionSettings globalSettings) {
        this.globalSettings = globalSettings;
    }
    
    /**
     * Gets effective settings for a connection (global or connection-specific).
     */
    public ConnectionSettings getEffectiveSettings(ServerConnection connection) {
        if (connection.getSettings() != null && !connection.getSettings().isUseGlobalSettings()) {
            return connection.getSettings();
        }
        return globalSettings;
    }
    
    public WindowGeometry getDefaultWindowGeometry() {
        return defaultWindowGeometry;
    }
    
    public void setDefaultWindowGeometry(WindowGeometry defaultWindowGeometry) {
        this.defaultWindowGeometry = defaultWindowGeometry;
    }
    
    public String getLastOpenedProject() {
        return lastOpenedProject;
    }
    
    public void setLastOpenedProject(String lastOpenedProject) {
        this.lastOpenedProject = lastOpenedProject;
    }
    
    public Path getConfigDir() {
        return configDir;
    }
}
