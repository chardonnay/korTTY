package de.kortty.control;

/**
 * Per-tab agent counts, mirroring {@code de.kortty.codingagent.TabRollup}.
 *
 * <p>Pure, any thread.
 *
 * @param blocked agents waiting for the user
 * @param working agents busy
 * @param done agents that finished and have not been seen
 * @param idle agents idle or in an unknown state
 * @param total all registered agents in the tab
 * @param mostUrgent the wire state of the most urgent agent, or null when the tab has none
 */
public record AgentRollupInfo(int blocked, int working, int done, int idle, int total,
                              String mostUrgent) {
}
