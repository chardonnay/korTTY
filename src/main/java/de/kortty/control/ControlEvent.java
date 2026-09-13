package de.kortty.control;

import java.util.Set;

/**
 * One push event. {@code kind} is the wire method name, e.g. {@code agent.state_changed}.
 *
 * <p>Pure, any thread — but the instance is built on the JavaFX application thread inside the
 * registry listener, and fan-out must neither throw nor block there.
 *
 * @param kind one of {@link #KINDS}
 * @param atMillis wall-clock millis at publication
 * @param windowId the window the event concerns, or null
 * @param tabId the tab the event concerns, or null
 * @param paneId the pane the event concerns, or null
 * @param agent the agent, or null — {@code agent.removed} carries only the ids
 * @param previousState the wire state held before the change, or null
 * @param reason a short machine-readable reason, or null
 */
public record ControlEvent(String kind, long atMillis, String windowId, String tabId, String paneId,
                           AgentInfo agent, String previousState, String reason) {

    /**
     * The closed set of event kinds. {@code agent.evidence} is opt-in only and
     * {@code events.overflow} is emitted by the bus itself, never by the registry.
     */
    public static final Set<String> KINDS = Set.of(
        "agent.added", "agent.state_changed", "agent.seen", "agent.alias_changed",
        "agent.removed", "agent.evidence", "events.overflow");
}
