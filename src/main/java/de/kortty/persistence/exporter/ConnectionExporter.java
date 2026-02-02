package de.kortty.persistence.exporter;

import de.kortty.model.ServerConnection;
import java.nio.file.Path;
import java.util.List;

/**
 * Interface for exporting connections to different formats.
 */
public interface ConnectionExporter {
    
    /**
     * Returns the name of this exporter.
     */
    String getName();
    
    /**
     * Returns the file extension for this format.
     */
    String getFileExtension();
    
    /**
     * Returns the file description for file chooser.
     */
    String getFileDescription();
    
    /**
     * Exports connections to the specified file.
     */
    void exportConnections(List<ServerConnection> connections, Path file) throws Exception;
}
