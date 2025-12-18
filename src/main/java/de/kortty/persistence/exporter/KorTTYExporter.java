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
        XMLConnectionRepository repository = new XMLConnectionRepository(file);
        repository.saveConnections(connections);
        logger.info("Exported {} connections to KorTTY format: {}", connections.size(), file);
    }

    
    @Override
    public String toString() {
        return getName();
    }
}
