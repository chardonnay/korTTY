package de.kortty.codingagent;

import java.util.Collection;

/**
 * Global counts over every registered agent. IDLE and UNKNOWN agents are counted in {@code idle}
 * and {@code total} only; they are never shown as chips.
 */
public record AgentSummary(int blocked, int working, int done, int idle, int total) {

    public static final AgentSummary EMPTY = new AgentSummary(0, 0, 0, 0, 0);

    public boolean isEmpty() {
        return total == 0;
    }

    /** Component-wise sum. */
    public AgentSummary plus(AgentSummary other) {
        if (other == null || other.isEmpty()) {
            return this;
        }
        if (isEmpty()) {
            return other;
        }
        return new AgentSummary(blocked + other.blocked, working + other.working, done + other.done,
            idle + other.idle, total + other.total);
    }

    /** Counts {@code entries} by effective state. */
    public static AgentSummary of(Collection<CodingAgentEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            return EMPTY;
        }
        int blocked = 0;
        int working = 0;
        int done = 0;
        int idle = 0;
        for (CodingAgentEntry entry : entries) {
            switch (entry.state()) {
                case BLOCKED -> blocked++;
                case WORKING -> working++;
                case DONE -> done++;
                default -> idle++;
            }
        }
        return new AgentSummary(blocked, working, done, idle, entries.size());
    }
}
