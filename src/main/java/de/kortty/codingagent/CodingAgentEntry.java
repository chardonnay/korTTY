package de.kortty.codingagent;

/**
 * Snapshot of one detected coding agent as the UI sees it. Immutable; the registry creates a new
 * instance per change.
 *
 * @param pane the pane hosting the agent
 * @param kind the agent kind
 * @param state the EFFECTIVE state including the registry's synthetic done-until-seen DONE
 *              ({@code detection.state()} is the raw Stage-1 state)
 * @param detection the last monitor result
 * @param process the agent process at the time of the last event; may be null
 * @param stateSinceMillis registry clock at the last effective-state change
 * @param detectedAtMillis registry clock at the detection
 * @param alias user-assigned alias; null when none
 * @param doneUntilSeen true while {@code state == DONE} was synthesised by the registry
 */
public record CodingAgentEntry(
        PaneRef pane,
        CodingAgentKind kind,
        CodingAgentState state,
        DetectionResult detection,
        AgentProcess process,
        long stateSinceMillis,
        long detectedAtMillis,
        String alias,
        boolean doneUntilSeen) {

    /** The alias when set, otherwise the kind's display name ("Claude Code"). */
    public String displayName() {
        return alias != null && !alias.isBlank() ? alias : kind.displayName();
    }

    /** The alias when set, otherwise the kind's short name ("claude"). */
    public String shortName() {
        return alias != null && !alias.isBlank() ? alias : CodingAgentGlyphs.shortName(kind);
    }

    /** Whole seconds spent in the effective state, never negative. */
    public long secondsInState(long nowMillis) {
        return Math.max(0L, (nowMillis - stateSinceMillis) / 1000L);
    }

    /** The evidence line of the last detection, or null. */
    public String lastLine() {
        return detection != null ? detection.evidence() : null;
    }
}
