package de.kortty.ui;

import de.kortty.core.ObservableTtyConnector;
import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.core.agent.AgentCommandRunners;
import de.kortty.core.swarm.SwarmModels;
import de.kortty.core.swarm.SwarmTarget;
import de.kortty.model.ServerConnection;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Collects deduplicated {@link SwarmTarget}s from the currently open terminals. Must run on the
 * JavaFX application thread (it reads live terminal state). Each unique server (host:port:user:
 * protocol) yields exactly one target, so many tabs/splits on the same host run the agent once.
 */
public final class SwarmTargetCollector {

    private SwarmTargetCollector() {
    }

    /** Result of collecting open-terminal targets, including counts of what was skipped. */
    public record CollectResult(
        List<SwarmTarget> targets,
        int skippedDuplicates,
        int skippedUnsupported,
        int skippedLocal) {
    }

    /** Result of resolving a connection selection against currently open terminals. */
    public record ResolveResult(
        List<SwarmTarget> openTargets,
        List<ServerConnection> missing) {
    }

    /**
     * Resolves a selection of saved connections into open targets (already-connected terminals) and
     * a list of connections that still need to be opened (handled by the "Connect missing" step).
     */
    public static ResolveResult resolveSelection(
        MainWindow window, List<ServerConnection> selected, boolean includeLocalShell) {
        if (window == null || selected == null) {
            return new ResolveResult(List.of(), List.of());
        }
        Map<SwarmModels.SwarmTargetKey, SwarmTarget> open = new LinkedHashMap<>();
        for (SwarmTarget target : collectOpenTerminals(window, true).targets()) {
            open.put(SwarmModels.SwarmTargetKey.of(target.connection()), target);
        }
        List<SwarmTarget> openTargets = new ArrayList<>();
        List<ServerConnection> missing = new ArrayList<>();
        java.util.Set<SwarmModels.SwarmTargetKey> seen = new java.util.LinkedHashSet<>();
        for (ServerConnection connection : selected) {
            if (connection == null) {
                continue;
            }
            if (connection.isLocalShell() && !includeLocalShell) {
                continue;
            }
            SwarmModels.SwarmTargetKey key = SwarmModels.SwarmTargetKey.of(connection);
            if (!seen.add(key)) {
                continue;
            }
            SwarmTarget openTarget = open.get(key);
            if (openTarget != null) {
                openTargets.add(openTarget);
            } else {
                missing.add(connection);
            }
        }
        return new ResolveResult(openTargets, missing);
    }

    public static CollectResult collectOpenTerminals(MainWindow window, boolean includeLocalShell) {
        if (window == null) {
            return new CollectResult(List.of(), 0, 0, 0);
        }
        Map<SwarmModels.SwarmTargetKey, SwarmTarget> byKey = new LinkedHashMap<>();
        int duplicates = 0;
        int unsupported = 0;
        int local = 0;

        for (TerminalTab tab : window.getOpenTerminalTabs()) {
            TerminalView view = tab.getTerminalView();
            if (view == null) {
                continue;
            }
            for (ObservableTtyConnector connector : view.getAllAgentConnectors()) {
                if (connector == null) {
                    continue;
                }
                ServerConnection connection = connector.getConnection();
                if (connection == null) {
                    continue;
                }
                if (connection.isLocalShell() && !includeLocalShell) {
                    local++;
                    continue;
                }
                SwarmModels.SwarmTargetKey key = SwarmModels.SwarmTargetKey.of(connection);
                if (byKey.containsKey(key)) {
                    duplicates++;
                    continue;
                }
                AgentCommandRunner runner = AgentCommandRunners.forConnector(connector);
                if (runner == null) {
                    unsupported++;
                    continue;
                }
                if (!runner.isConnected()) {
                    // Dead/closing connector: do not reserve the key so a live duplicate can win.
                    continue;
                }
                byKey.put(key, new SwarmTarget(
                    "swarm-" + UUID.randomUUID(),
                    connection,
                    runner,
                    tab,
                    "swarm-" + UUID.randomUUID(),
                    displayName(connection)));
            }
        }
        return new CollectResult(new ArrayList<>(byKey.values()), duplicates, unsupported, local);
    }

    static String displayName(ServerConnection connection) {
        if (connection == null) {
            return "";
        }
        String name = connection.getDisplayName();
        if (name != null && !name.isBlank()) {
            return name;
        }
        String user = connection.getUsername();
        String host = connection.getHost();
        if (host == null || host.isBlank()) {
            return name != null ? name : "";
        }
        return (user != null && !user.isBlank()) ? user + "@" + host : host;
    }
}
