package de.kortty.core;

import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.WindowGeometry;
import de.kortty.persistence.XMLConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages application configuration including connections and global settings.
 */
public class ConfigurationManager {
    
    private static final Logger logger = LoggerFactory.getLogger(ConfigurationManager.class);
    
    private final Path configDir;
    private final XMLConnectionRepository connectionRepository;
    private final StoreFileGuard connectionsGuard;
    
    private ConnectionSettings globalSettings;
    private WindowGeometry defaultWindowGeometry;
    private List<ServerConnection> connections;
    private String lastOpenedProject;
    
    public ConfigurationManager(Path configDir) {
        this.configDir = configDir;
        this.connectionRepository = new XMLConnectionRepository(configDir);
        this.connectionsGuard = new StoreFileGuard(connectionRepository.connectionsFile());
        this.globalSettings = new ConnectionSettings();
        this.defaultWindowGeometry = new WindowGeometry(100, 100, 900, 600);
        this.connections = new ArrayList<>();
    }
    
    /**
     * Loads the connections from {@code connections.xml}. Never throws: a corrupt file is moved
     * aside as {@code connections.xml.corrupt-<timestamp>} (see {@link #getLoadFailureBackup()}),
     * a file that cannot be read stays in place and blocks saving (see {@link #isSaveBlocked()}).
     * In both cases the connections in memory are kept as they are — empty at startup, the
     * previous list after a failed reload — so a broken file never turns into an empty list on
     * disk.
     */
    public void load(SecretKey key) {
        Path file = connectionsGuard.file();
        connectionsGuard.beginLoad();
        if (!Files.exists(file)) {
            logger.info("No connections file found, starting with empty list");
            connections = new ArrayList<>();
            return;
        }
        try {
            Optional<List<ServerConnection>> loaded = connectionsGuard.read(
                content -> XMLConnectionRepository.readConnections(new ByteArrayInputStream(content), key));
            if (loaded.isPresent()) {
                connections = new ArrayList<>(loaded.get());
                logger.info("Loaded {} connections", connections.size());
            } else {
                logger.warn("Kept {} connections in memory; the unreadable connections file was moved aside",
                    connections.size());
            }
        } catch (Exception e) {
            logger.error("Failed to load connections; korTTY will not save over {} in this session", file, e);
        }
    }
    
    /**
     * Saves configuration to disk. Logs instead of throwing; use {@link #saveOrThrow} where the
     * caller reports a failed save to the user.
     */
    public void save(SecretKey key) {
        try {
            saveOrThrow(key);
        } catch (Exception e) {
            logger.error("Failed to save connections", e);
        }
    }

    /**
     * Saves the connections and reports failure.
     *
     * @throws IllegalStateException when the last load could not read the file and left it in place
     */
    public void saveOrThrow(SecretKey key) throws Exception {
        connectionsGuard.ensureWritable();
        connectionRepository.saveConnections(connections, key);
        logger.info("Saved {} connections", connections.size());
    }

    /** Where the last load moved an unreadable {@code connections.xml}, if it did. */
    public Optional<Path> getLoadFailureBackup() {
        return connectionsGuard.getLoadFailureBackup();
    }

    /** Whether saving is refused because {@code connections.xml} could not be read. */
    public boolean isSaveBlocked() {
        return connectionsGuard.isSaveBlocked();
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
                .filter(c -> c.getId().equals(id))
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
