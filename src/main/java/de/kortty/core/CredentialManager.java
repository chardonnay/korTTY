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
                StoredCredential.Environment.class,
                StoredCredential.PasswordType.class
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
                StoredCredential.Environment.class,
                StoredCredential.PasswordType.class
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
     * Returns the password for a credential, either by decrypting the stored password
     * or by executing an external command (depending on the credential's PasswordType).
     */
    public String getPassword(StoredCredential credential, char[] masterPassword) throws Exception {
        if (credential.getPasswordType() == StoredCredential.PasswordType.EXTERNAL_COMMAND) {
            return getPasswordFromExternalCommand(credential, masterPassword);
        }
        
        // Default: decrypt stored password
        if (credential.getEncryptedPassword() == null) {
            return null;
        }
        
        EncryptionService encryptionService = new EncryptionService();
        return encryptionService.decryptPassword(credential.getEncryptedPassword(), masterPassword);
    }
    
    /**
     * Retrieves a password by executing an external command (shell script / CLI tool).
     * The command itself is stored encrypted and decrypted before execution.
     * The command's stdout output (trimmed) is used as the password.
     * 
     * @param credential the credential with an encrypted external command
     * @param masterPassword the master password to decrypt the command
     * @return the password returned by the external command
     * @throws Exception if the command fails or times out
     */
    private String getPasswordFromExternalCommand(StoredCredential credential, char[] masterPassword) throws Exception {
        String encryptedCommand = credential.getEncryptedExternalCommand();
        if (encryptedCommand == null || encryptedCommand.isBlank()) {
            throw new Exception("No external command configured for credential: " + credential.getName());
        }
        
        // Decrypt the command
        EncryptionService encryptionService = new EncryptionService();
        String command = encryptionService.decryptPassword(encryptedCommand, masterPassword);
        
        logger.info("Executing external password command for credential: {}", credential.getName());
        return executeExternalCommand(command);
    }
    
    /**
     * Executes a shell command and returns its stdout output as the password.
     * Has a 10-second timeout to prevent hanging.
     * 
     * @param command the shell command to execute
     * @return the trimmed stdout output
     * @throws Exception if the command fails, times out, or returns empty output
     */
    public static String executeExternalCommand(String command) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("/bin/sh", "-c", command);
        pb.redirectErrorStream(false);
        
        Process process = pb.start();
        
        // Read stdout and stderr in parallel to prevent deadlocks
        String stdout;
        String stderr;
        try (var stdoutStream = process.getInputStream();
             var stderrStream = process.getErrorStream()) {
            stdout = new String(stdoutStream.readAllBytes()).trim();
            stderr = new String(stderrStream.readAllBytes()).trim();
        }
        
        boolean finished = process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        if (!finished) {
            process.destroyForcibly();
            throw new Exception("External command timed out after 10 seconds");
        }
        
        int exitCode = process.exitValue();
        if (exitCode != 0) {
            String errorMsg = stderr.isEmpty() ? "exit code " + exitCode : stderr;
            throw new Exception("External command failed: " + errorMsg);
        }
        
        if (stdout.isEmpty()) {
            throw new Exception("External command returned empty output");
        }
        
        // Return first line only (password should be a single line)
        String[] lines = stdout.split("\\R", 2);
        return lines[0];
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
     * Encrypts and stores an external command for a credential
     */
    public void setExternalCommand(StoredCredential credential, String command, char[] masterPassword) throws Exception {
        EncryptionService encryptionService = new EncryptionService();
        String encrypted = encryptionService.encryptPassword(command, masterPassword);
        credential.setEncryptedExternalCommand(encrypted);
    }
    
    /**
     * Decrypts and returns the external command for a credential (for display in edit dialog)
     */
    public String getExternalCommand(StoredCredential credential, char[] masterPassword) throws Exception {
        String encrypted = credential.getEncryptedExternalCommand();
        if (encrypted == null || encrypted.isBlank()) {
            return null;
        }
        EncryptionService encryptionService = new EncryptionService();
        return encryptionService.decryptPassword(encrypted, masterPassword);
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
