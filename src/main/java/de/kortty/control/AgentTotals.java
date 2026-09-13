package de.kortty.control;

/**
 * Aggregate coding-agent counts. {@code IDLE} and {@code UNKNOWN} both land in {@code idle},
 * matching {@code de.kortty.codingagent.AgentSummary}.
 *
 * <p>Pure, any thread.
 *
 * @param blocked agents waiting for the user
 * @param working agents busy
 * @param done agents that finished and have not been seen
 * @param idle agents idle or in an unknown state
 * @param total all registered agents
 */
public record AgentTotals(int blocked, int working, int done, int idle, int total) {
}
