package de.kortty.core;

import de.kortty.model.GPGKey;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.JAXBException;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElement;
import jakarta.xml.bind.annotation.XmlRootElement;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.StringWriter;
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
    public static final String GPG_KEYS_FILE = "gpg-keys.xml";
    
    /** Shared, thread-safe JAXBContext; building one per load and save is the expensive part. */
    private static final JAXBContext JAXB_CONTEXT;
    static {
        try {
            JAXB_CONTEXT = JAXBContext.newInstance(GPGKeysWrapper.class, GPGKey.class);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    
    private final Path configDir;
    private final List<GPGKey> keys = new ArrayList<>();
    private final StoreFileGuard guard;
    
    public GPGKeyManager(Path configDir) {
        this.configDir = configDir;
        this.guard = new StoreFileGuard(configDir.resolve(GPG_KEYS_FILE));
    }
    
    /**
     * Loads GPG keys from configuration file. A corrupt file is moved aside (see
     * {@link #getLoadFailureBackup()}) and the keys in memory are kept. When the file cannot be
     * read or moved aside this throws, and {@link #save()} refuses to write over it.
     */
    public void load() throws Exception {
        Path file = guard.file();
        guard.beginLoad();
        if (guard.isMissing()) {
            logger.info("No GPG keys file found, starting with empty list");
            return;
        }
        
        try {
            Optional<GPGKeysWrapper> loaded = guard.read(content ->
                (GPGKeysWrapper) JAXB_CONTEXT.createUnmarshaller().unmarshal(new ByteArrayInputStream(content)));
            if (loaded.isEmpty()) {
                logger.warn("Kept {} GPG keys in memory; the unreadable GPG keys file was moved aside", keys.size());
                return;
            }
            GPGKeysWrapper wrapper = loaded.get();
            
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
     * Saves GPG keys to configuration file atomically. The file only holds public-key metadata,
     * so it keeps its permissions.
     *
     * @throws IllegalStateException when the last load could not read the file and left it in place
     */
    public void save() throws Exception {
        Path file = guard.file();
        
        try {
            guard.ensureWritable();
            GPGKeysWrapper wrapper = new GPGKeysWrapper();
            wrapper.setKeys(new ArrayList<>(keys));
            
            Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            StringWriter xml = new StringWriter();
            marshaller.marshal(wrapper, xml);
            
            Files.createDirectories(configDir);
            AtomicFileWriter.writeStoreAtomically(file, xml.toString(), AtomicFileWriter.FileMode.PRESERVE);
            
            logger.info("Saved {} GPG keys to {}", keys.size(), file);
        } catch (Exception e) {
            logger.error("Failed to save GPG keys to " + file, e);
            throw e;
        }
    }

    /** Where the last load moved an unreadable {@code gpg-keys.xml}, if it did. */
    public Optional<Path> getLoadFailureBackup() {
        return guard.getLoadFailureBackup();
    }

    /** Whether saving is refused because {@code gpg-keys.xml} could not be read. */
    public boolean isSaveBlocked() {
        return guard.isSaveBlocked();
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
