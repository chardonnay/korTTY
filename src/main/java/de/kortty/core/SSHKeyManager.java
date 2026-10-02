package de.kortty.core;

import de.kortty.model.SSHKey;
import de.kortty.security.EncryptionService;
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
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Manages stored SSH keys for server connections.
 */
public class SSHKeyManager {
    
    private static final Logger logger = LoggerFactory.getLogger(SSHKeyManager.class);
    public static final String SSH_KEYS_FILE = "ssh-keys.xml";
    private static final String SSH_KEYS_DIR = "ssh-keys";  // Subdirectory in .kortty for copied keys
    
    /** Shared, thread-safe JAXBContext; building one per load and save is the expensive part. */
    private static final JAXBContext JAXB_CONTEXT;
    static {
        try {
            JAXB_CONTEXT = JAXBContext.newInstance(SSHKeysWrapper.class, SSHKey.class);
        } catch (JAXBException e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    
    private final Path configDir;
    private final List<SSHKey> keys = new ArrayList<>();
    private final StoreFileGuard guard;
    
    public SSHKeyManager(Path configDir) {
        this.configDir = configDir;
        this.guard = new StoreFileGuard(configDir.resolve(SSH_KEYS_FILE));
    }
    
    /**
     * Loads SSH keys from configuration file. A corrupt file is moved aside (see
     * {@link #getLoadFailureBackup()}) and the keys in memory are kept. When the file cannot be
     * read or moved aside this throws, and {@link #save()} refuses to write over it.
     */
    public void load() throws Exception {
        Path file = guard.file();
        guard.beginLoad();
        if (!Files.exists(file)) {
            logger.info("No SSH keys file found, starting with empty list");
            return;
        }
        
        try {
            Optional<SSHKeysWrapper> loaded = guard.read(content ->
                (SSHKeysWrapper) JAXB_CONTEXT.createUnmarshaller().unmarshal(new ByteArrayInputStream(content)));
            if (loaded.isEmpty()) {
                logger.warn("Kept {} SSH keys in memory; the unreadable SSH keys file was moved aside", keys.size());
                return;
            }
            SSHKeysWrapper wrapper = loaded.get();
            
            keys.clear();
            if (wrapper.getKeys() != null) {
                keys.addAll(wrapper.getKeys());
            }
            
            logger.info("Loaded {} SSH keys from {}", keys.size(), file);
        } catch (Exception e) {
            logger.error("Failed to load SSH keys from " + file, e);
            throw e;
        }
    }
    
    /**
     * Saves SSH keys to configuration file: atomically, flushed to the disk, owner-only.
     *
     * @throws IllegalStateException when the last load could not read the file and left it in place
     */
    public void save() throws Exception {
        Path file = guard.file();
        
        try {
            guard.ensureWritable();
            SSHKeysWrapper wrapper = new SSHKeysWrapper();
            wrapper.setKeys(new ArrayList<>(keys));
            
            Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            StringWriter xml = new StringWriter();
            marshaller.marshal(wrapper, xml);
            
            Files.createDirectories(configDir);
            AtomicFileWriter.writeStoreAtomically(file, xml.toString(), AtomicFileWriter.FileMode.OWNER_ONLY);
            
            logger.info("Saved {} SSH keys to {}", keys.size(), file);
        } catch (Exception e) {
            logger.error("Failed to save SSH keys to " + file, e);
            throw e;
        }
    }

    /** Where the last load moved an unreadable {@code ssh-keys.xml}, if it did. */
    public Optional<Path> getLoadFailureBackup() {
        return guard.getLoadFailureBackup();
    }

    /** Whether saving is refused because {@code ssh-keys.xml} could not be read. */
    public boolean isSaveBlocked() {
        return guard.isSaveBlocked();
    }
    
    /**
     * Adds a new SSH key
     */
    public void addKey(SSHKey key) {
        keys.add(key);
        logger.info("Added SSH key: {}", key.getName());
    }
    
    /**
     * Removes an SSH key
     */
    public void removeKey(SSHKey key) {
        keys.remove(key);
        logger.info("Removed SSH key: {}", key.getName());
    }
    
    /**
     * Updates an existing SSH key
     */
    public void updateKey(SSHKey key) {
        int index = keys.indexOf(key);
        if (index >= 0) {
            keys.set(index, key);
            logger.info("Updated SSH key: {}", key.getName());
        }
    }
    
    /**
     * Gets all SSH keys
     */
    public List<SSHKey> getAllKeys() {
        return new ArrayList<>(keys);
    }
    
    /**
     * Finds an SSH key by ID
     */
    public Optional<SSHKey> findKeyById(String id) {
        return keys.stream()
                .filter(k -> k.getId().equals(id))
                .findFirst();
    }
    
    /**
     * Copies a key file to the .kortty/ssh-keys directory
     */
    public Path copyKeyToUserDir(SSHKey key) throws Exception {
        Path sourcePath = Path.of(key.getKeyPath());
        if (!Files.exists(sourcePath)) {
            throw new Exception("Key file does not exist: " + key.getKeyPath());
        }
        
        Path keysDir = configDir.resolve(SSH_KEYS_DIR);
        Files.createDirectories(keysDir);
        // The directory holds private keys: owner-only, like ~/.ssh (OpenSSH also refuses a
        // private key that others can read).
        AtomicFileWriter.restrictToOwner(keysDir);
        
        String fileName = sourcePath.getFileName().toString();
        Path targetPath = keysDir.resolve(key.getId() + "_" + fileName);
        
        Files.copy(sourcePath, targetPath, StandardCopyOption.REPLACE_EXISTING);
        AtomicFileWriter.restrictToOwner(targetPath);
        
        // Also copy .pub file if it exists
        Path pubSourcePath = sourcePath.resolveSibling(fileName + ".pub");
        if (Files.exists(pubSourcePath)) {
            Path pubTargetPath = keysDir.resolve(key.getId() + "_" + fileName + ".pub");
            Files.copy(pubSourcePath, pubTargetPath, StandardCopyOption.REPLACE_EXISTING);
        }
        
        key.setCopiedToUserDir(true);
        key.setUserDirPath(targetPath.toString());
        
        logger.info("Copied SSH key {} to user directory: {}", key.getName(), targetPath);
        return targetPath;
    }
    
    /**
     * Decrypts and returns the passphrase for a key
     */
    public String getPassphrase(SSHKey key, char[] masterPassword) throws Exception {
        if (key.getEncryptedPassphrase() == null) {
            return null;
        }
        
        EncryptionService encryptionService = new EncryptionService();
        return encryptionService.decryptPassword(key.getEncryptedPassphrase(), masterPassword);
    }
    
    /**
     * Encrypts and stores a passphrase for a key
     */
    public void setPassphrase(SSHKey key, String passphrase, char[] masterPassword) throws Exception {
        EncryptionService encryptionService = new EncryptionService();
        String encrypted = encryptionService.encryptPassword(passphrase, masterPassword);
        key.setEncryptedPassphrase(encrypted);
    }
    
    /**
     * Gets the path to use for a key (user dir if copied, otherwise original path)
     */
    public String getEffectiveKeyPath(SSHKey key) {
        if (key.isCopiedToUserDir() && key.getUserDirPath() != null) {
            return key.getUserDirPath();
        }
        return key.getKeyPath();
    }
    
    /**
     * JAXB wrapper for SSH keys list
     */
    @XmlRootElement(name = "sshKeys")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class SSHKeysWrapper {
        @XmlElement(name = "sshKey")
        private List<SSHKey> keys;
        
        public List<SSHKey> getKeys() {
            return keys;
        }
        
        public void setKeys(List<SSHKey> keys) {
            this.keys = keys;
        }
    }
}
