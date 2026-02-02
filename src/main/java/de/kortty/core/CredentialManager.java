package de.kortty.core;

import de.kortty.model.StoredCredential;
import de.kortty.security.EncryptionService;
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
import java.util.stream.Collectors;

/**
 * Manages stored credentials for server connections.
 */
public class CredentialManager {
    
    private static final Logger logger = LoggerFactory.getLogger(CredentialManager.class);
    private static final String CREDENTIALS_FILE = "credentials.xml";
    
    private final Path configDir;
    private final List<StoredCredential> credentials = new ArrayList<>();
    
    public CredentialManager(Path configDir) {
        this.configDir = configDir;
    }
    
    /**
     * Loads credentials from configuration file
     */
    public void load() throws Exception {
        Path file = configDir.resolve(CREDENTIALS_FILE);
        if (!Files.exists(file)) {
            logger.info("No credentials file found, starting with empty list");
            return;
        }
        
        try {
            JAXBContext context = JAXBContext.newInstance(
                CredentialsWrapper.class, 
                StoredCredential.class,
                StoredCredential.Environment.class
            );
            Unmarshaller unmarshaller = context.createUnmarshaller();
            CredentialsWrapper wrapper = (CredentialsWrapper) unmarshaller.unmarshal(file.toFile());
            
            credentials.clear();
            if (wrapper.getCredentials() != null) {
                credentials.addAll(wrapper.getCredentials());
            }
            
            logger.info("Loaded {} credentials from {}", credentials.size(), file);
        } catch (Exception e) {
            logger.error("Failed to load credentials from " + file, e);
            throw e;
        }
    }
    
    /**
     * Saves credentials to configuration file
     */
    public void save() throws Exception {
        Path file = configDir.resolve(CREDENTIALS_FILE);
        
        try {
            CredentialsWrapper wrapper = new CredentialsWrapper();
            wrapper.setCredentials(new ArrayList<>(credentials));
            
            JAXBContext context = JAXBContext.newInstance(
                CredentialsWrapper.class, 
                StoredCredential.class,
                StoredCredential.Environment.class
            );
            Marshaller marshaller = context.createMarshaller();
            marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
            
            Files.createDirectories(configDir);
            marshaller.marshal(wrapper, file.toFile());
            
            logger.info("Saved {} credentials to {}", credentials.size(), file);
        } catch (Exception e) {
            logger.error("Failed to save credentials to " + file, e);
            throw e;
        }
    }
    
    /**
     * Adds a new credential
     */
    public void addCredential(StoredCredential credential) {
        credentials.add(credential);
        logger.info("Added credential: {}", credential.getName());
    }
    
    /**
     * Removes a credential
     */
    public void removeCredential(StoredCredential credential) {
        credentials.remove(credential);
        logger.info("Removed credential: {}", credential.getName());
    }
    
    /**
     * Updates an existing credential
     */
    public void updateCredential(StoredCredential credential) {
        int index = credentials.indexOf(credential);
        if (index >= 0) {
            credentials.set(index, credential);
            logger.info("Updated credential: {}", credential.getName());
        }
    }
    
    /**
     * Gets all credentials
     */
    public List<StoredCredential> getAllCredentials() {
        return new ArrayList<>(credentials);
    }
    
    /**
     * Finds credentials matching a server and environment
     */
    public List<StoredCredential> findMatchingCredentials(String hostname, StoredCredential.Environment environment) {
        return credentials.stream()
                .filter(c -> c.getEnvironment() == environment)
                .filter(c -> c.matchesServer(hostname))
                .collect(Collectors.toList());
    }
    
    /**
     * Finds a credential by ID
     */
    public Optional<StoredCredential> findCredentialById(String id) {
        return credentials.stream()
                .filter(c -> c.getId().equals(id))
                .findFirst();
    }
    
    /**
     * Decrypts and returns the password for a credential
     */
    public String getPassword(StoredCredential credential, char[] masterPassword) throws Exception {
        if (credential.getEncryptedPassword() == null) {
            return null;
        }
        
        EncryptionService encryptionService = new EncryptionService();
        return encryptionService.decryptPassword(credential.getEncryptedPassword(), masterPassword);
    }
    
    /**
     * Encrypts and stores a password for a credential
     */
    public void setPassword(StoredCredential credential, String password, char[] masterPassword) throws Exception {
        EncryptionService encryptionService = new EncryptionService();
        String encrypted = encryptionService.encryptPassword(password, masterPassword);
        credential.setEncryptedPassword(encrypted);
    }
    
    /**
     * JAXB wrapper for credentials list
     */
    @XmlRootElement(name = "credentials")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class CredentialsWrapper {
        @XmlElement(name = "credential")
        private List<StoredCredential> credentials;
        
        public List<StoredCredential> getCredentials() {
            return credentials;
        }
        
        public void setCredentials(List<StoredCredential> credentials) {
            this.credentials = credentials;
        }
    }
}
