package de.kortty.ui;

import de.kortty.core.swarm.SwarmModels;

import java.util.Collection;

/**
 * Decides which activity indicator the AI-swarm tab header shows while a run is in progress.
 * Priority: an agent waiting for approval always wins (the user must act), then general activity,
 * then an all-paused swarm.
 */
final class SwarmTabActivitySupport {

    enum Indicator {
        NONE,
        ACTIVE,
        WAITING,
        PAUSED
    }

    private SwarmTabActivitySupport() {
    }

    static Indicator dominantIndicator(boolean busy, Collection<SwarmModels.SwarmAgentState> states) {
        if (!busy) {
            return Indicator.NONE;
        }
        boolean anyActive = false;
        boolean anyPaused = false;
        if (states != null) {
            for (SwarmModels.SwarmAgentState state : states) {
                if (state == SwarmModels.SwarmAgentState.AWAITING_APPROVAL) {
                    return Indicator.WAITING;
                }
                if (state == SwarmModels.SwarmAgentState.CONNECTING
                    || state == SwarmModels.SwarmAgentState.PROBING
                    || state == SwarmModels.SwarmAgentState.RUNNING
                    || state == SwarmModels.SwarmAgentState.QUEUED) {
                    anyActive = true;
                } else if (state == SwarmModels.SwarmAgentState.PAUSED) {
                    anyPaused = true;
                }
            }
        }
        if (anyActive) {
            return Indicator.ACTIVE;
        }
        if (anyPaused) {
            return Indicator.PAUSED;
        }
        // busy without active agents = the aggregation step is still working
        return Indicator.ACTIVE;
    }
}
