package de.kortty.core.swarm;

import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;

import java.util.List;
import java.util.Locale;

/**
 * Shared public DTOs for the AI swarm (running the terminal agent across many servers and
 * aggregating the results). Mirrors the style of {@code TerminalAgentModels}.
 */
public final class SwarmModels {

    private SwarmModels() {
    }

    /** Lifecycle state of a single per-server swarm agent. */
    public enum SwarmAgentState {
        QUEUED,
        CONNECTING,
        PROBING,
        RUNNING,
        AWAITING_APPROVAL,
        DONE,
        FAILED,
        CANCELLED,
        SKIPPED
    }

    /** Lifecycle phase of the whole swarm run. */
    public enum SwarmPhase {
        PREPARING,
        CONNECTING,
        RUNNING_AGENTS,
        AGGREGATING,
        DONE,
        FAILED,
        CANCELLED
    }

    /** Where the swarm draws its targets from. */
    public enum SwarmSource {
        OPEN_TERMINALS,
        CONNECTION_SELECTION
    }

    /** How mutating-command approvals are handled while the swarm runs. */
    public enum BatchApprovalPolicy {
        /** One approval dialog applies to every server in the swarm. */
        ONE_APPROVAL_FOR_ALL,
        /** Each server prompts for its own approval. */
        PER_SERVER,
        /** No mutating commands allowed; approvals never occur. */
        READ_ONLY
    }

    /**
     * Canonical identity used to deduplicate targets. NOTE: do not use {@code ServerConnection.equals}
     * for this — it compares only by id (a single saved connection can back many open tabs/splits).
     */
    public record SwarmTargetKey(String host, int port, String username, ConnectionProtocol protocol) {

        public static SwarmTargetKey of(ServerConnection connection) {
            if (connection == null) {
                return new SwarmTargetKey("", 0, "", null);
            }
            String host = connection.getHost() != null
                ? connection.getHost().trim().toLowerCase(Locale.ROOT)
                : "";
            String user = connection.getUsername() != null ? connection.getUsername().trim() : "";
            return new SwarmTargetKey(host, connection.getPort(), user, connection.getProtocol());
        }
    }

    /** Accumulated token usage across one agent or the whole swarm. */
    public record TokenTotals(long prompt, long completion, long total) {

        public TokenTotals {
            prompt = Math.max(0L, prompt);
            completion = Math.max(0L, completion);
            total = Math.max(total, prompt + completion);
        }

        public static TokenTotals zero() {
            return new TokenTotals(0L, 0L, 0L);
        }

        public TokenTotals plus(TokenTotals other) {
            if (other == null) {
                return this;
            }
            return new TokenTotals(
                prompt + other.prompt,
                completion + other.completion,
                total + other.total);
        }
    }

    /** Live status of one per-server agent, surfaced to the dashboard. */
    public record SwarmAgentStatus(
        String agentId,
        String displayName,
        SwarmTargetKey key,
        SwarmAgentState state,
        String currentActivity,
        long elapsedSeconds,
        TokenTotals tokens,
        String finalAnswer,
        String transcriptSummary,
        String errorMessage) {
    }

    /** Roll-up state of the whole swarm. */
    public record SwarmRunState(
        SwarmPhase phase,
        int total,
        int queued,
        int running,
        int done,
        int failed,
        int cancelled,
        long elapsedSeconds,
        String message) {
    }

    /** Parameters of one swarm request. */
    public record SwarmRequest(
        String query,
        String profileId,
        SwarmSource source,
        boolean includeLocalShell,
        boolean readOnly,
        int maxParallelism,
        BatchApprovalPolicy approvalPolicy) {
    }

    /** Input to the final aggregation step. */
    public record SwarmAggregationRequest(
        String userQuery,
        List<SwarmAgentStatus> perAgentResults) {
    }

    /** Output of the final aggregation step. {@code error} is non-null when the local fallback ran. */
    public record SwarmAggregationResult(
        String markdown,
        TokenTotals aggregationTokens,
        String error) {
    }
}
