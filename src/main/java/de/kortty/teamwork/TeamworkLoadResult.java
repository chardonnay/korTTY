package de.kortty.teamwork;

import de.kortty.model.ServerConnection;

import java.util.List;

/**
 * Result of loading connections from a teamwork source.
 */
public class TeamworkLoadResult {
    private final List<ServerConnection> connections;
    private final String versionToken;  // commit hash, ETag, or file lastModified

    public TeamworkLoadResult(List<ServerConnection> connections, String versionToken) {
        this.connections = connections;
        this.versionToken = versionToken;
    }

    public List<ServerConnection> getConnections() {
        return connections;
    }

    public String getVersionToken() {
        return versionToken;
    }
}
