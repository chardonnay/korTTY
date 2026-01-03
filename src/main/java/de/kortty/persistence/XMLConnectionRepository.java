package de.kortty.persistence;

import de.kortty.model.ServerConnection;
import de.kortty.model.SSHTunnel;
import de.kortty.model.JumpServer;
import de.kortty.model.AuthMethod;
import de.kortty.model.TunnelType;
import jakarta.xml.bind.JAXBContext;
import jakarta.xml.bind.Marshaller;
import jakarta.xml.bind.Unmarshaller;
import jakarta.xml.bind.annotation.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    
    private final Path configDir;
    
    public XMLConnectionRepository(Path configDir) {
        this.configDir = configDir;
    }
    
    /**
     * Saves connections to XML file.
     */
    public void saveConnections(List<ServerConnection> connections) throws Exception {
        ConnectionsWrapper wrapper = new ConnectionsWrapper();
        wrapper.setConnections(connections);
        
        JAXBContext context = JAXBContext.newInstance(ConnectionsWrapper.class, ServerConnection.class, SSHTunnel.class, JumpServer.class, AuthMethod.class, TunnelType.class, de.kortty.model.TerminalLogConfig.class, de.kortty.model.TerminalLogConfig.LogFormat.class);
        Marshaller marshaller = context.createMarshaller();
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
        Path file = configDir.resolve(CONNECTIONS_FILE);
        
        if (!Files.exists(file)) {
            logger.info("No connections file found, returning empty list");
            return new ArrayList<>();
        }
        
        JAXBContext context = JAXBContext.newInstance(ConnectionsWrapper.class, ServerConnection.class, SSHTunnel.class, JumpServer.class, AuthMethod.class, TunnelType.class, de.kortty.model.TerminalLogConfig.class, de.kortty.model.TerminalLogConfig.LogFormat.class);
        Unmarshaller unmarshaller = context.createUnmarshaller();
        
        ConnectionsWrapper wrapper;
        try (InputStream in = Files.newInputStream(file)) {
            wrapper = (ConnectionsWrapper) unmarshaller.unmarshal(in);
        }
        
        List<ServerConnection> connections = wrapper.getConnections();
        logger.info("Loaded {} connections from {}", connections.size(), file);
        
        return connections != null ? connections : new ArrayList<>();
    }
    
    /**
     * Exports connections to a specified file.
     */
    public void exportConnections(List<ServerConnection> connections, Path targetFile) throws Exception {
        ConnectionsWrapper wrapper = new ConnectionsWrapper();
        wrapper.setConnections(connections);
        
        JAXBContext context = JAXBContext.newInstance(ConnectionsWrapper.class, ServerConnection.class, SSHTunnel.class, JumpServer.class, AuthMethod.class, TunnelType.class, de.kortty.model.TerminalLogConfig.class, de.kortty.model.TerminalLogConfig.LogFormat.class);
        Marshaller marshaller = context.createMarshaller();
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
        JAXBContext context = JAXBContext.newInstance(ConnectionsWrapper.class, ServerConnection.class, SSHTunnel.class, JumpServer.class, AuthMethod.class, TunnelType.class, de.kortty.model.TerminalLogConfig.class, de.kortty.model.TerminalLogConfig.LogFormat.class);
        Unmarshaller unmarshaller = context.createUnmarshaller();
        
        ConnectionsWrapper wrapper;
        try (InputStream in = Files.newInputStream(sourceFile)) {
            wrapper = (ConnectionsWrapper) unmarshaller.unmarshal(in);
        }
        
        List<ServerConnection> connections = wrapper.getConnections();
        logger.info("Imported {} connections from {}", connections.size(), sourceFile);
        
        return connections != null ? connections : new ArrayList<>();
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
