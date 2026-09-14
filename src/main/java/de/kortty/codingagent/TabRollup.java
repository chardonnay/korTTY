package de.kortty.codingagent;

/**
 * Per-tab rollup of the agents hosted by a tab's panes. Recomputed on registry events and cached,
 * so the dashboard tick can query it allocation-free. {@code idle} counts IDLE and UNKNOWN agents.
 */
public record TabRollup(String tabId, int blocked, int working, int done, int idle, int agentCount,
                        CodingAgentState mostUrgent) {

    /** The rollup of a tab without agents. */
    public static final TabRollup EMPTY = new TabRollup("", 0, 0, 0, 0, 0, CodingAgentState.UNKNOWN);

    public boolean hasAgents() {
        return agentCount > 0;
    }
}
