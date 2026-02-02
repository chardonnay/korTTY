package de.kortty.persistence.exporter;

import de.kortty.model.ServerConnection;
import de.kortty.persistence.XMLConnectionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * Exports connections to KorTTY native XML format.
 */
public class KorTTYExporter implements ConnectionExporter {
    
    private static final Logger logger = LoggerFactory.getLogger(KorTTYExporter.class);
    
    @Override
    public String getName() {
        return "KorTTY";
    }
    
    @Override
    public String getFileExtension() {
        return "xml";
    }
    
    @Override
    public String getFileDescription() {
        return "KorTTY Verbindungen (*.xml)";
    }
    
    @Override
    public void exportConnections(List<ServerConnection> connections, Path file) throws Exception {
        // Don't use XMLConnectionRepository constructor with file path
        // because it expects a directory and will append /connections.xml
        // Use JAXB directly to write to the specified file
        
        XMLConnectionRepository.ConnectionsWrapper wrapper = new XMLConnectionRepository.ConnectionsWrapper();
        wrapper.setConnections(connections);
        
        jakarta.xml.bind.JAXBContext context = jakarta.xml.bind.JAXBContext.newInstance(
            XMLConnectionRepository.ConnectionsWrapper.class, 
            de.kortty.model.ServerConnection.class,
            de.kortty.model.SSHTunnel.class,
            de.kortty.model.JumpServer.class,
            de.kortty.model.AuthMethod.class,
            de.kortty.model.TunnelType.class,
            de.kortty.model.TerminalLogConfig.class,
            de.kortty.model.TerminalLogConfig.LogFormat.class
        );
        jakarta.xml.bind.Marshaller marshaller = context.createMarshaller();
        marshaller.setProperty(jakarta.xml.bind.Marshaller.JAXB_FORMATTED_OUTPUT, true);
        
        try (java.io.OutputStream out = java.nio.file.Files.newOutputStream(file)) {
            marshaller.marshal(wrapper, out);
        }
        
        logger.info("Exported {} connections to KorTTY format: {}", connections.size(), file);
    }

    
    @Override
    public String toString() {
        return getName();
    }
}
