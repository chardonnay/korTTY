package de.kortty.control;

import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.DetectionResult;
import java.util.Locale;
import java.util.Objects;

/**
 * Projection of a Stage-2 {@code CodingAgentEntry} onto the wire.
 *
 * <p>{@link #of} never calls {@code AgentProcess.isAlive()}: that hits the OS, and serialising a list
 * of agents must not turn into one {@code ProcessHandle} lookup per entry on a request path.
 *
 * <p>Pure, any thread — but the {@code CodingAgentEntry} handed to {@link #of} must have been read on
 * the JavaFX application thread, because the registry's readers are plain unsynchronised maps.
 *
 * @param paneId the pane hosting the agent
 * @param tabId the tab holding that pane
 * @param windowId the window holding that tab
 * @param detected whether the registry holds an agent for the pane
 * @param kind the agent kind id ({@code claude-code}, {@code codex}, {@code gemini-cli}), or null
 * @param state the wire state ({@code blocked}, {@code working}, {@code done}, {@code idle},
 *     {@code unknown})
 * @param displayName the alias when set, otherwise the kind's display name
 * @param alias the user-assigned alias, or null
 * @param matchedRuleId the rule that produced the state, or null when a fallback applied
 * @param evidence the evidence line of the last detection, or null
 * @param stateSinceMillis registry clock at the last effective-state change
 * @param secondsInState whole seconds spent in the effective state
 * @param doneUntilSeen whether the DONE state was synthesised by the registry
 * @param pid the agent process pid, or -1 when unknown
 * @param command the agent process command line, or null
 */
public record AgentInfo(String paneId, String tabId, String windowId, boolean detected, String kind,
                        String state, String displayName, String alias, String matchedRuleId,
                        String evidence, long stateSinceMillis, long secondsInState,
                        boolean doneUntilSeen, long pid, String command) {

    /** The wire state of an agent the registry does not know about. */
    private static final String STATE_UNKNOWN = "unknown";

    /** The pid reported when the agent process is not known. */
    private static final long UNKNOWN_PID = -1L;

    /** Projects a live registry entry. */
    public static AgentInfo of(CodingAgentEntry entry, String paneId, String tabId, String windowId,
                               long nowMillis) {
        Objects.requireNonNull(entry, "entry");
        DetectionResult detection = entry.detection();
        return new AgentInfo(
            paneId,
            tabId,
            windowId,
            true,
            entry.kind() == null ? null : entry.kind().id(),
            entry.state() == null ? STATE_UNKNOWN : entry.state().name().toLowerCase(Locale.ROOT),
            entry.displayName(),
            entry.alias(),
            detection == null ? null : detection.matchedRuleId(),
            entry.lastLine(),
            entry.stateSinceMillis(),
            entry.secondsInState(nowMillis),
            entry.doneUntilSeen(),
            entry.process() == null ? UNKNOWN_PID : entry.process().pid(),
            entry.process() == null ? null : entry.process().command());
    }

    /** The projection of a pane with no registered coding agent. */
    public static AgentInfo undetected(String paneId, String tabId, String windowId) {
        return new AgentInfo(paneId, tabId, windowId, false, null, STATE_UNKNOWN, null, null, null,
            null, 0L, 0L, false, UNKNOWN_PID, null);
    }
}
