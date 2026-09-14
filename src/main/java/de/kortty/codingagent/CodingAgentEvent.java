package de.kortty.codingagent;

import java.time.Instant;

/**
 * Published only when the DetectionResult of a pane changes. previous/current are never null
 * (DetectionResult.NONE means "no agent"); process is the agent process at the time of the event
 * (null for removals whose process is gone).
 */
public record CodingAgentEvent(PaneRef pane, DetectionResult previous, DetectionResult current,
                               AgentProcess process, Reason reason, Instant at) {

    public enum Reason {
        DETECTED, STATE_CHANGED, PROCESS_EXITED, DISCONNECTED, PANE_DETACHED, DETECTION_DISABLED, CONNECTOR_REBOUND
    }

    public boolean isDetection() {
        return !previous.agentDetected() && current.agentDetected();
    }

    public boolean isRemoval() {
        return previous.agentDetected() && !current.agentDetected();
    }

    public boolean isStateChange() {
        return previous.agentDetected() && current.agentDetected();
    }
}
