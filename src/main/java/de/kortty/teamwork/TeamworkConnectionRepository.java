package de.kortty.teamwork;

import de.kortty.model.ServerConnection;
import de.kortty.model.TeamworkSourceConfig;

import java.util.List;

/**
 * Loads connection list from a teamwork source (Git or shared file).
 * Implementations may cache locally; the sync service coordinates caching.
 */
public interface TeamworkConnectionRepository {

    /**
     * Load connections from the given source.
     *
     * @param source source configuration (type, location, etc.)
     * @return load result with connections and version token, or null on error
     */
    TeamworkLoadResult loadConnections(TeamworkSourceConfig source);

    /**
     * Push updated connections to the source (for write-capable sources).
     * Not all implementations support this (e.g. read-only shared file).
     *
     * @param source source configuration
     * @param connections connections to save
     * @return new version token after save, or null on failure
     */
    default String saveConnections(TeamworkSourceConfig source, List<ServerConnection> connections) {
        return null;
    }
}
