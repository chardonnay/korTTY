package de.kortty.persistence.importer;

import de.kortty.model.ServerConnection;

import java.nio.file.Path;
import java.util.List;

/**
 * Interface for importing connections from various formats.
 */
public interface ConnectionImporter {
    
    /**
     * Gets the name of this importer.
     */
    String getName();
    
    /**
     * Gets the file extension(s) this importer supports.
     */
    String[] getSupportedExtensions();
    
    /**
     * Gets a description for the file chooser.
     */
    String getFileDescription();
    
    /**
     * Checks if this importer can handle the given file.
     */
    boolean canImport(Path file);
    
    /**
     * Imports connections from the given file.
     */
    List<ServerConnection> importConnections(Path file) throws Exception;
}
