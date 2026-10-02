package de.kortty.persistence;

import de.kortty.model.ServerConnection;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.WindowGeometry;
import de.kortty.model.SSHTunnel;
import de.kortty.model.JumpServer;
import de.kortty.model.AuthMethod;
import de.kortty.model.TunnelType;
import de.kortty.model.ConnectionSource;
import de.kortty.core.AtomicFileWriter;
import de.kortty.security.EncryptionService;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.SecretKey;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Repository for storing and loading SSH connections in XML format.
 */
public class XMLConnectionRepository {
    
    private static final Logger logger = LoggerFactory.getLogger(XMLConnectionRepository.class);
    public static final String CONNECTIONS_FILE = "connections.xml";
    private static final String ENCRYPTED_TEMP_KEY_PREFIX = "enc:";
    private static final String TEMPORARY_KEY_PATH_PREFIX = "TEMPORARY:";
    private static final String TEMPORARY_KEY_PATH_MARKER = "TEMPORARY:__ENCRYPTED__";

    /** Shared JAXBContext – thread-safe and expensive to create, so we do it once. */
    private static final JAXBContext JAXB_CONTEXT;
    static {
        try {
            JAXB_CONTEXT = JAXBContext.newInstance(
                ConnectionsWrapper.class, ServerConnection.class, ConnectionSettings.class,
                WindowGeometry.class, SSHTunnel.class, JumpServer.class, AuthMethod.class,
                TunnelType.class, ConnectionSource.class, de.kortty.model.TerminalLogConfig.class,
                de.kortty.model.TerminalLogConfig.LogFormat.class,
                de.kortty.model.SessionJournalConfig.class);
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }
    
    private final Path configDir;
    
    /**
     * A temporary SSH key exactly as {@code connections.xml} stores it: the key material still
     * encrypted with the master-password key ({@code enc:} prefix included), plus its lifetime.
     */
    public record StoredTemporaryKey(String encryptedContent, Long expirationMinutes, boolean permanent) {
        public StoredTemporaryKey {
            Objects.requireNonNull(encryptedContent, "encryptedContent");
        }
    }

    public XMLConnectionRepository(Path configDir) {
        this.configDir = configDir;
    }
    
    /**
     * Saves connections to XML file.
     */
    public void saveConnections(List<ServerConnection> connections) throws Exception {
        saveConnections(connections, null);
    }

    /**
     * Saves connections to XML file using the provided key for at-rest encryption. The file is
     * replaced atomically (a crash mid-save keeps the previous file) and written owner-only: it
     * names every host and user even though the secrets in it are encrypted.
     */
    public void saveConnections(List<ServerConnection> connections, SecretKey key) throws Exception {
        saveConnections(connections, key, Map.of());
    }

    /**
     * Saves connections, writing {@code preserved} back verbatim for each connection id that has no
     * temporary key in memory. A load while the vault was locked had to clear those keys in memory;
     * this keeps the encrypted copies on disk until the vault is unlocked and they can be restored.
     */
    public void saveConnections(List<ServerConnection> connections, SecretKey key,
                                Map<String, StoredTemporaryKey> preserved) throws Exception {
        ConnectionsWrapper wrapper = new ConnectionsWrapper();
        wrapper.setConnections(prepareForPersistence(connections, key,
            preserved != null ? preserved : Map.of()));
        
        Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        StringWriter xml = new StringWriter(16 * 1024);
        marshaller.marshal(wrapper, xml);
        
        Path file = connectionsFile();
        AtomicFileWriter.writeStoreAtomically(file, xml.toString(), AtomicFileWriter.FileMode.OWNER_ONLY);
        
        logger.info("Saved {} connections to {}", connections.size(), file);
    }

    /** The {@code connections.xml} this repository reads and writes. */
    public Path connectionsFile() {
        return configDir.resolve(CONNECTIONS_FILE);
    }
    
    /**
     * Loads connections from XML file.
     */
    public List<ServerConnection> loadConnections() throws Exception {
        return loadConnections(null);
    }

    /**
     * Loads connections from XML file using the provided key for at-rest decryption.
     */
    public List<ServerConnection> loadConnections(SecretKey key) throws Exception {
        return loadConnections(key, null);
    }

    /**
     * Loads connections like {@link #loadConnections(SecretKey)} and adds to {@code lockedKeyIds}
     * the id of every connection whose encrypted temporary SSH key could not be decrypted because
     * {@code key} is {@code null} (vault locked). Those keys are cleared in memory only.
     */
    public List<ServerConnection> loadConnections(SecretKey key, Collection<String> lockedKeyIds) throws Exception {
        Path file = connectionsFile();
        
        if (!Files.exists(file)) {
            logger.info("No connections file found, returning empty list");
            return new ArrayList<>();
        }
        
        List<ServerConnection> connections = restoreAfterLoad(readWrapper(file).getConnections(), key, lockedKeyIds);
        logger.info("Loaded {} connections from {}", connections.size(), file);
        
        return connections != null ? connections : new ArrayList<>();
    }
    
    /**
     * Reads the temporary SSH keys from {@code connections.xml} without decrypting them, by
     * connection id. Only encrypted keys are returned; empty when the file does not exist.
     */
    public Map<String, StoredTemporaryKey> readStoredTemporaryKeys() throws Exception {
        Map<String, StoredTemporaryKey> stored = new LinkedHashMap<>();
        Path file = connectionsFile();
        if (!Files.exists(file)) {
            return stored;
        }
        List<ServerConnection> persisted = readWrapper(file).getConnections();
        if (persisted == null) {
            return stored;
        }
        for (ServerConnection connection : persisted) {
            if (connection == null || connection.getId() == null) {
                continue;
            }
            String content = connection.getTemporaryKeyContent();
            if (content != null && content.startsWith(ENCRYPTED_TEMP_KEY_PREFIX)) {
                stored.put(connection.getId(), new StoredTemporaryKey(
                    content, connection.getTemporaryKeyExpirationMinutes(), connection.isTemporaryKeyPermanent()));
            }
        }
        return stored;
    }

    /**
     * Decrypts {@code stored} with {@code key} and puts it on {@code target} the way a load with an
     * unlocked vault would have: key material, lifetime and the {@code TEMPORARY:} key path.
     *
     * @throws Exception when {@code key} cannot decrypt the stored key; {@code target} is unchanged
     */
    public static void restoreTemporaryKey(ServerConnection target, StoredTemporaryKey stored, SecretKey key)
            throws Exception {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(stored, "stored");
        Objects.requireNonNull(key, "key");
        String encrypted = stored.encryptedContent();
        if (!encrypted.startsWith(ENCRYPTED_TEMP_KEY_PREFIX)) {
            throw new IllegalArgumentException("The stored temporary key is not encrypted");
        }
        String decrypted = new EncryptionService().decrypt(encrypted.substring(ENCRYPTED_TEMP_KEY_PREFIX.length()), key);
        target.setTemporaryKeyContent(decrypted);
        target.setTemporaryKeyExpirationMinutes(stored.expirationMinutes());
        target.setTemporaryKeyPermanent(stored.permanent());
        target.setPrivateKeyPath(TEMPORARY_KEY_PATH_PREFIX + decrypted);
    }

    private static ConnectionsWrapper readWrapper(Path file) throws Exception {
        Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
        try (InputStream in = Files.newInputStream(file)) {
            return (ConnectionsWrapper) unmarshaller.unmarshal(in);
        }
    }

    /**
     * Exports connections to a specified file.
     */
    public void exportConnections(List<ServerConnection> connections, Path targetFile) throws Exception {
        exportConnections(connections, targetFile, null);
    }

    /**
     * Exports connections to a specified file using the provided key for at-rest encryption.
     */
    public void exportConnections(List<ServerConnection> connections, Path targetFile, SecretKey key) throws Exception {
        ConnectionsWrapper wrapper = new ConnectionsWrapper();
        wrapper.setConnections(prepareForPersistence(connections, key, Map.of()));
        
        Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        
        try (OutputStream out = Files.newOutputStream(targetFile)) {
            marshaller.marshal(wrapper, out);
        }
        
        logger.info("Exported {} connections to {}", connections.size(), targetFile);
    }
    
    /**
     * Imports connections from an XML file.
     */
    public List<ServerConnection> importConnections(Path sourceFile) throws Exception {
        return importConnections(sourceFile, null);
    }

    /**
     * Imports connections from an XML file using the provided key for at-rest decryption.
     */
    public List<ServerConnection> importConnections(Path sourceFile, SecretKey key) throws Exception {
        Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
        
        ConnectionsWrapper wrapper;
        try (InputStream in = Files.newInputStream(sourceFile)) {
            wrapper = (ConnectionsWrapper) unmarshaller.unmarshal(in);
        }
        
        List<ServerConnection> connections = restoreAfterLoad(wrapper.getConnections(), key, null);
        logger.info("Imported {} connections from {}", connections.size(), sourceFile);
        
        return connections != null ? connections : new ArrayList<>();
    }

    public static void writeConnections(List<ServerConnection> connections, OutputStream out, SecretKey key) throws Exception {
        ConnectionsWrapper wrapper = new ConnectionsWrapper();
        wrapper.setConnections(prepareForPersistence(connections, key, Map.of()));

        Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        marshaller.marshal(wrapper, out);
    }

    public static void writeConnections(List<ServerConnection> connections, Path file, SecretKey key) throws Exception {
        try (OutputStream out = Files.newOutputStream(file)) {
            writeConnections(connections, out, key);
        }
    }

    public static List<ServerConnection> readConnections(InputStream in, SecretKey key) throws Exception {
        return readConnections(in, key, null);
    }

    /**
     * Reads connections like {@link #readConnections(InputStream, SecretKey)} and adds to
     * {@code lockedKeyIds} the id of every connection whose encrypted temporary SSH key could not be
     * decrypted because {@code key} is {@code null} (vault locked). Those keys are cleared in memory only.
     */
    public static List<ServerConnection> readConnections(InputStream in, SecretKey key,
                                                         Collection<String> lockedKeyIds) throws Exception {
        Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
        ConnectionsWrapper wrapper = (ConnectionsWrapper) unmarshaller.unmarshal(in);
        return restoreAfterLoad(wrapper.getConnections(), key, lockedKeyIds);
    }

    public static List<ServerConnection> readConnections(Path file, SecretKey key) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return readConnections(in, key);
        }
    }

    private static List<ServerConnection> prepareForPersistence(List<ServerConnection> connections, SecretKey key,
                                                                Map<String, StoredTemporaryKey> preserved) throws Exception {
        List<ServerConnection> prepared = new ArrayList<>();
        if (connections == null) {
            return prepared;
        }

        EncryptionService encryptionService = new EncryptionService();
        for (ServerConnection connection : connections) {
            if (connection == null) {
                continue;
            }
            prepared.add(prepareForPersistence(connection, key, encryptionService,
                connection.getId() != null ? preserved.get(connection.getId()) : null));
        }
        return prepared;
    }

    private static ServerConnection prepareForPersistence(ServerConnection connection, SecretKey key,
                                                          EncryptionService encryptionService,
                                                          StoredTemporaryKey preserved) throws Exception {
        ServerConnection copy = ServerConnection.copyForAuth(connection);
        String tempKeyContent = extractTemporaryKeyContent(copy);

        if ((tempKeyContent == null || tempKeyContent.isBlank()) && preserved != null) {
            // Cleared in memory by a locked load: keep the encrypted original on disk.
            copy.setTemporaryKeyContent(preserved.encryptedContent());
            copy.setTemporaryKeyExpirationMinutes(preserved.expirationMinutes());
            copy.setTemporaryKeyPermanent(preserved.permanent());
            copy.setPrivateKeyPath(TEMPORARY_KEY_PATH_MARKER);
        } else if (tempKeyContent != null && !tempKeyContent.isBlank()) {
            if (key == null) {
                throw new IllegalStateException("A master password must be unlocked before persisting temporary SSH keys.");
            }
            copy.setTemporaryKeyContent(ENCRYPTED_TEMP_KEY_PREFIX + encryptionService.encrypt(tempKeyContent, key));
            copy.setPrivateKeyPath(TEMPORARY_KEY_PATH_MARKER);
        } else if (isTemporaryKeyPath(copy.getPrivateKeyPath())) {
            copy.setPrivateKeyPath(TEMPORARY_KEY_PATH_MARKER);
        }

        return copy;
    }

    private static List<ServerConnection> restoreAfterLoad(List<ServerConnection> connections, SecretKey key,
                                                           Collection<String> lockedKeyIds) throws Exception {
        List<ServerConnection> restored = connections != null ? connections : new ArrayList<>();
        EncryptionService encryptionService = new EncryptionService();
        for (ServerConnection connection : restored) {
            if (connection == null) {
                continue;
            }
            restoreAfterLoad(connection, key, encryptionService, lockedKeyIds);
        }
        return restored;
    }

    private static void restoreAfterLoad(ServerConnection connection, SecretKey key,
                                         EncryptionService encryptionService,
                                         Collection<String> lockedKeyIds) throws Exception {
        String persisted = connection.getTemporaryKeyContent();
        if (persisted == null || persisted.isBlank()) {
            if (TEMPORARY_KEY_PATH_MARKER.equals(connection.getPrivateKeyPath())) {
                connection.setPrivateKeyPath(null);
            }
            return;
        }

        String decrypted = persisted;
        if (persisted.startsWith(ENCRYPTED_TEMP_KEY_PREFIX)) {
            if (key == null) {
                logger.warn("Temporary SSH key for connection '{}' could not be decrypted because the master password is locked",
                    connection.getDisplayName());
                clearTemporaryKeyState(connection);
                if (lockedKeyIds != null && connection.getId() != null) {
                    lockedKeyIds.add(connection.getId());
                }
                return;
            }
            try {
                decrypted = encryptionService.decrypt(persisted.substring(ENCRYPTED_TEMP_KEY_PREFIX.length()), key);
            } catch (Exception e) {
                logger.warn("Temporary SSH key for connection '{}' could not be decrypted and was cleared",
                    connection.getDisplayName(), e);
                clearTemporaryKeyState(connection);
                return;
            }
            connection.setTemporaryKeyContent(decrypted);
        }

        if (TEMPORARY_KEY_PATH_MARKER.equals(connection.getPrivateKeyPath()) || connection.getPrivateKeyPath() == null) {
            connection.setPrivateKeyPath(TEMPORARY_KEY_PATH_PREFIX + decrypted);
        }
    }

    private static String extractTemporaryKeyContent(ServerConnection connection) {
        String tempKeyContent = connection.getTemporaryKeyContent();
        if (tempKeyContent != null && !tempKeyContent.isBlank()) {
            return tempKeyContent;
        }

        String privateKeyPath = connection.getPrivateKeyPath();
        if (privateKeyPath != null && privateKeyPath.startsWith(TEMPORARY_KEY_PATH_PREFIX)) {
            String extracted = privateKeyPath.substring(TEMPORARY_KEY_PATH_PREFIX.length());
            return extracted.isBlank() ? null : extracted;
        }
        return null;
    }

    private static boolean isTemporaryKeyPath(String privateKeyPath) {
        return privateKeyPath != null && privateKeyPath.startsWith(TEMPORARY_KEY_PATH_PREFIX);
    }

    private static void clearTemporaryKeyState(ServerConnection connection) {
        connection.setTemporaryKeyContent(null);
        connection.setTemporaryKeyExpirationMinutes(null);
        connection.setTemporaryKeyPermanent(false);
        if (isTemporaryKeyPath(connection.getPrivateKeyPath())) {
            connection.setPrivateKeyPath(null);
        }
    }
    
    /**
     * Wrapper class for JAXB serialization.
     */
    @XmlRootElement(name = "connections")
    @XmlAccessorType(XmlAccessType.FIELD)
    public static class ConnectionsWrapper {
        
        @XmlElement(name = "connection")
        private List<ServerConnection> connections = new ArrayList<>();
        
        public List<ServerConnection> getConnections() {
            return connections;
        }
        
        public void setConnections(List<ServerConnection> connections) {
            this.connections = connections;
        }
    }
}
