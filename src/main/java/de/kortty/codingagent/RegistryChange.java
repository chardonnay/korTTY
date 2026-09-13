package de.kortty.codingagent;

/**
 * One change published by {@link CodingAgentRegistry}. {@code previous} is null for ADDED,
 * {@code current} is null for REMOVED.
 */
public record RegistryChange(Kind kind, PaneRef pane, CodingAgentEntry previous, CodingAgentEntry current,
                             long atMillis) {

    /** What happened to the entry. */
    public enum Kind { ADDED, STATE_CHANGED, SEEN, ALIAS_CHANGED, REMOVED }

    /** True when the effective state of the entry just became {@code state}. */
    public boolean entered(CodingAgentState state) {
        return current != null && current.state() == state && (previous == null || previous.state() != state);
    }
}
