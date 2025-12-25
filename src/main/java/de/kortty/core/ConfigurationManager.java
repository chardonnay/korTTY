package de.kortty.core;

import de.kortty.model.ConnectionSettings;
import de.kortty.model.ServerConnection;
import de.kortty.model.WindowGeometry;
import de.kortty.persistence.XMLConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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
        try {
            connections = connectionRepository.loadConnections();
            logger.info("Loaded {} connections", connections.size());
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
            connectionRepository.saveConnections(connections);
            logger.info("Saved {} connections", connections.size());
        } catch (Exception e) {
            logger.error("Failed to save connections", e);
        }
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
