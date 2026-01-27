package de.kortty.core;

import de.kortty.model.TemporarySSHKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Manages temporary SSH keys with expiration times.
 * Keys are automatically removed when they expire.
 */
public class TemporarySSHKeyManager {
    
    private static final Logger logger = LoggerFactory.getLogger(TemporarySSHKeyManager.class);
    private static TemporarySSHKeyManager instance;
    
    private final Map<String, TemporarySSHKey> temporaryKeys = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);
    
    private TemporarySSHKeyManager() {
        // Start cleanup task that runs every minute
        scheduler.scheduleAtFixedRate(this::cleanupExpiredKeys, 1, 1, TimeUnit.MINUTES);
    }
    
    public static synchronized TemporarySSHKeyManager getInstance() {
        if (instance == null) {
            instance = new TemporarySSHKeyManager();
        }
        return instance;
    }
    
    /**
     * Stores a temporary SSH key.
     * @param keyContent The SSH key content
     * @param expirationMinutes Expiration time in minutes
     * @return The stored TemporarySSHKey
     */
    public TemporarySSHKey storeTemporaryKey(String keyContent, long expirationMinutes) {
        TemporarySSHKey tempKey = new TemporarySSHKey(keyContent, expirationMinutes);
        temporaryKeys.put(keyContent, tempKey);
        logger.info("Stored temporary SSH key, expires in {} minutes", expirationMinutes);
        return tempKey;
    }
    
    /**
     * Gets a temporary SSH key if it's still valid.
     * @param keyContent The SSH key content
     * @return The TemporarySSHKey if valid, null otherwise
     */
    public TemporarySSHKey getTemporaryKey(String keyContent) {
        TemporarySSHKey key = temporaryKeys.get(keyContent);
        if (key != null && key.isValid()) {
            return key;
        }
        if (key != null && !key.isValid()) {
            temporaryKeys.remove(keyContent);
        }
        return null;
    }
    
    /**
     * Gets the current temporary key if any is stored and valid.
     * @return The first valid temporary key, or null
     */
    public TemporarySSHKey getCurrentTemporaryKey() {
        return temporaryKeys.values().stream()
            .filter(TemporarySSHKey::isValid)
            .findFirst()
            .orElse(null);
    }
    
    /**
     * Removes a temporary SSH key.
     */
    public void removeTemporaryKey(String keyContent) {
        temporaryKeys.remove(keyContent);
        logger.info("Removed temporary SSH key");
    }
    
    /**
     * Removes all expired keys.
     */
    private void cleanupExpiredKeys() {
        temporaryKeys.entrySet().removeIf(entry -> {
            if (!entry.getValue().isValid()) {
                logger.debug("Removed expired temporary SSH key");
                return true;
            }
            return false;
        });
    }
    
    /**
     * Shuts down the scheduler.
     */
    public void shutdown() {
        scheduler.shutdown();
    }
}
