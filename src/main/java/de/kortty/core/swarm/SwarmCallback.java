package de.kortty.core.swarm;

import de.kortty.core.TerminalAgentService;
import de.kortty.model.TerminalAgentModels;

/**
 * Swarm-level observer, the swarm analogue of {@link TerminalAgentService.RunUi}. The orchestrator
 * invokes these callbacks from pool threads; the UI layer is responsible for marshalling them onto
 * the JavaFX application thread.
 */
public interface SwarmCallback {

    /** The whole-swarm roll-up changed. */
    void onSwarmState(SwarmModels.SwarmRunState state);

    /** A single agent's status changed. */
    void onAgentStatus(SwarmModels.SwarmAgentStatus status);

    /** A chunk of one agent's transcript arrived (already tail-capped by the orchestrator). */
    void onAgentTranscript(String agentId, String chunk);

    /** The aggregated answer is ready. */
    void onAggregationResult(SwarmModels.SwarmAggregationResult result);

    /**
     * A mutating command set needs approval. Implementations apply the configured
     * {@link SwarmModels.BatchApprovalPolicy} (e.g. surface one dialog for the whole swarm).
     */
    TerminalAgentService.ApprovalDecision requestBatchApproval(
        TerminalAgentModels.Approval approval, String agentId) throws Exception;

    /** A sudo password is needed for the given agent's server. */
    TerminalAgentModels.PasswordResponse requestPassword(
        TerminalAgentModels.PasswordRequest request, String agentId) throws Exception;

    /** Whether the whole swarm has been cancelled. */
    boolean isCancelled();

    /** Whether one specific agent has been cancelled (others keep running). */
    default boolean isAgentCancelled(String agentId) {
        return false;
    }
}
