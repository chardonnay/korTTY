package de.kortty.core;

import de.kortty.model.GPGKey;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages GPG keys for encryption/decryption.
 */
public class GPGKeyManager {
    
    private static final Logger logger = LoggerFactory.getLogger(GPGKeyManager.class);
    private static final String GPG_KEYS_FILE = "gpg-keys.xml";
    
    private final Path configDir;
    private final List<GPGKey> keys = new ArrayList<>();
    
    public GPGKeyManager(Path configDir) {
        this.configDir = configDir;
    }
    
    /**
     * Loads GPG keys from configuration file
     */
    public void load() throws Exception {
        Path file = configDir.resolve(GPG_KEYS_FILE);
        if (!Files.exists(file)) {
            logger.info("No GPG keys file found, starting with empty list");
            return;
        }
        
        try {
            JAXBContext context = JAXBContext.newInstance(GPGKeysWrapper.class, GPGKey.class);
            Unmarshaller unmarshaller = context.createUnmarshaller();
            GPGKeysWrapper wrapper = (GPGKeysWrapper) unmarshaller.unmarshal(file.toFile());
            
            keys.clear();
            if (wrapper.getKeys() != null) {
                keys.addAll(wrapper.getKeys());
            }
            
            logger.info("Loaded {} GPG keys from {}", keys.size(), file);
        } catch (Exception e) {
            logger.error("Failed to load GPG keys from " + file, e);
            throw e;
        }
    }
    
    /**
     * Saves GPG keys to configuration file
     */
    public void save() throws Exception {
        Path file = configDir.resolve(GPG_KEYS_FILE);
        
        try {
            GPGKeysWrapper wrapper = new GPGKeysWrapper();
            wrapper.setKeys(new ArrayList<>(keys));
            
            JAXBContext context = JAXBContext.newInstance(GPGKeysWrapper.class, GPGKey.class);
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            
            Files.createDirectories(configDir);
            marshaller.marshal(wrapper, file.toFile());
            
            logger.info("Saved {} GPG keys to {}", keys.size(), file);
        } catch (Exception e) {
            logger.error("Failed to save GPG keys to " + file, e);
            throw e;
        }
    }
    
    /**
     * Adds a new GPG key
     */
    public void addKey(GPGKey key) {
        keys.add(key);
        logger.info("Added GPG key: {}", key.getName());
    }
    
    /**
     * Removes a GPG key
     */
    public void removeKey(GPGKey key) {
        keys.remove(key);
        logger.info("Removed GPG key: {}", key.getName());
    }
    
    /**
     * Updates an existing GPG key
     */
    public void updateKey(GPGKey key) {
        int index = keys.indexOf(key);
        if (index >= 0) {
            keys.set(index, key);
            logger.info("Updated GPG key: {}", key.getName());
        }
    }
    
    /**
     * Gets all GPG keys
     */
    public List<GPGKey> getAllKeys() {
        return new ArrayList<>(keys);
    }
    
    /**
     * Finds a key by ID
     */
    public Optional<GPGKey> findKeyById(String id) {
        return keys.stream()
                .filter(k -> k.getId().equals(id))
                .findFirst();
    }
    
    /**
     * JAXB wrapper for GPG keys list
     */
    @XmlRootElement(name = "gpgKeys")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class GPGKeysWrapper {
        @XmlElement(name = "key")
        private List<GPGKey> keys;
        
        public List<GPGKey> getKeys() {
            return keys;
        }
        
        public void setKeys(List<GPGKey> keys) {
            this.keys = keys;
        }
    }
}
