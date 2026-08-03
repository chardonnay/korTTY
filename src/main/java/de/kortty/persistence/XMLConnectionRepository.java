package de.kortty.persistence;

import de.kortty.model.ServerConnection;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.WindowGeometry;
import de.kortty.model.SSHTunnel;
import de.kortty.model.JumpServer;
import de.kortty.model.AuthMethod;
import de.kortty.model.TunnelType;
import de.kortty.model.ConnectionSource;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Repository for storing and loading SSH connections in XML format.
 */
public class XMLConnectionRepository {
    
    private static final Logger logger = LoggerFactory.getLogger(XMLConnectionRepository.class);
    private static final String CONNECTIONS_FILE = "connections.xml";
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
     * Saves connections to XML file using the provided key for at-rest encryption.
     */
    public void saveConnections(List<ServerConnection> connections, SecretKey key) throws Exception {
        ConnectionsWrapper wrapper = new ConnectionsWrapper();
        wrapper.setConnections(prepareForPersistence(connections, key));
        
        Marshaller marshaller = JAXB_CONTEXT.createMarshaller();
        marshaller.setProperty(Marshaller.JAXB_FORMATTED_OUTPUT, true);
        
        Path file = configDir.resolve(CONNECTIONS_FILE);
        try (OutputStream out = Files.newOutputStream(file)) {
            marshaller.marshal(wrapper, out);
        }
        
        logger.info("Saved {} connections to {}", connections.size(), file);
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
        Path file = configDir.resolve(CONNECTIONS_FILE);
        
        if (!Files.exists(file)) {
            logger.info("No connections file found, returning empty list");
            return new ArrayList<>();
        }
        
        Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
        
        ConnectionsWrapper wrapper;
        try (InputStream in = Files.newInputStream(file)) {
            wrapper = (ConnectionsWrapper) unmarshaller.unmarshal(in);
        }
        
        List<ServerConnection> connections = restoreAfterLoad(wrapper.getConnections(), key);
        logger.info("Loaded {} connections from {}", connections.size(), file);
        
        return connections != null ? connections : new ArrayList<>();
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
        wrapper.setConnections(prepareForPersistence(connections, key));
        
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
        
        List<ServerConnection> connections = restoreAfterLoad(wrapper.getConnections(), key);
        logger.info("Imported {} connections from {}", connections.size(), sourceFile);
        
        return connections != null ? connections : new ArrayList<>();
    }

    public static void writeConnections(List<ServerConnection> connections, OutputStream out, SecretKey key) throws Exception {
        ConnectionsWrapper wrapper = new ConnectionsWrapper();
        wrapper.setConnections(prepareForPersistence(connections, key));

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
        Unmarshaller unmarshaller = JAXB_CONTEXT.createUnmarshaller();
        ConnectionsWrapper wrapper = (ConnectionsWrapper) unmarshaller.unmarshal(in);
        return restoreAfterLoad(wrapper.getConnections(), key);
    }

    public static List<ServerConnection> readConnections(Path file, SecretKey key) throws Exception {
        try (InputStream in = Files.newInputStream(file)) {
            return readConnections(in, key);
        }
    }

    private static List<ServerConnection> prepareForPersistence(List<ServerConnection> connections, SecretKey key) throws Exception {
        List<ServerConnection> prepared = new ArrayList<>();
        if (connections == null) {
            return prepared;
        }

        EncryptionService encryptionService = new EncryptionService();
        for (ServerConnection connection : connections) {
            if (connection == null) {
                continue;
            }
            prepared.add(prepareForPersistence(connection, key, encryptionService));
        }
        return prepared;
    }

    private static ServerConnection prepareForPersistence(ServerConnection connection, SecretKey key,
                                                          EncryptionService encryptionService) throws Exception {
        ServerConnection copy = ServerConnection.copyForAuth(connection);
        String tempKeyContent = extractTemporaryKeyContent(copy);

        if (tempKeyContent != null && !tempKeyContent.isBlank()) {
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

    private static List<ServerConnection> restoreAfterLoad(List<ServerConnection> connections, SecretKey key) throws Exception {
        List<ServerConnection> restored = connections != null ? connections : new ArrayList<>();
        EncryptionService encryptionService = new EncryptionService();
        for (ServerConnection connection : restored) {
            if (connection == null) {
                continue;
            }
            restoreAfterLoad(connection, key, encryptionService);
        }
        return restored;
    }

    private static void restoreAfterLoad(ServerConnection connection, SecretKey key,
                                         EncryptionService encryptionService) throws Exception {
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
