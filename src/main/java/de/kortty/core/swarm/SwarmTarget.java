package de.kortty.core.swarm;

import de.kortty.core.agent.AgentCommandRunner;
import de.kortty.model.ServerConnection;
import de.kortty.ui.TerminalTab;

/**
 * One resolved swarm participant: a unique server with a connected command runner. The
 * {@code terminalTab} is nullable — targets resolved from open terminals carry their tab, while
 * targets opened on demand for a connection group may have none (the runner alone drives them).
 */
public record SwarmTarget(
    String agentId,
    ServerConnection connection,
    AgentCommandRunner runner,
    TerminalTab terminalTab,
    String sessionId,
    String displayName) {
}
