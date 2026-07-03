package de.kortty.ui;

import de.kortty.model.ServerConnection;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pure helpers for materializing "headless" swarm targets — selected connections without an open
 * terminal that are run over a lazily connected SSH session instead of requiring the user to open
 * terminal tabs first.
 */
final class SwarmHeadlessTargetSupport {

    private SwarmHeadlessTargetSupport() {
    }

    /** Connections that can run headless: non-null, SSH-reachable (no local shell), with an id. */
    static List<ServerConnection> schedulableConnections(List<ServerConnection> missing) {
        List<ServerConnection> schedulable = new ArrayList<>();
        if (missing == null) {
            return schedulable;
        }
        for (ServerConnection connection : missing) {
            if (connection != null && !connection.isLocalShell() && connection.getId() != null) {
                schedulable.add(connection);
            }
        }
        return schedulable;
    }

    /**
     * Stable per-connection agent id across runs (restarts and row rebuilds rely on stable ids);
     * generated once and cached in the supplied map.
     */
    static String stableAgentId(Map<String, String> cache, String connectionId) {
        return cache.computeIfAbsent(connectionId, id -> "swarm-headless-" + UUID.randomUUID());
    }
}
