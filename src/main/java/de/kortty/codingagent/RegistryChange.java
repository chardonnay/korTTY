package de.kortty.codingagent;

/**
 * One change published by {@link CodingAgentRegistry}. {@code previous} is null for ADDED,
 * {@code current} is null for REMOVED.
 */
public record RegistryChange(Kind kind, PaneRef pane, CodingAgentEntry previous, CodingAgentEntry current,
                             long atMillis) {

    /**
     * What happened to the entry. {@code EVIDENCE_CHANGED} is the cheap sibling of
     * {@code STATE_CHANGED}: a new detection for a pane whose effective state, agent kind, alias,
     * time in state, done-until-seen flag and process are all unchanged — only the evidence line (and
     * possibly the matched rule) differs. A WORKING agent's animated status line produces one of
     * these every coalescing window, so consumers must answer it with an in-place update instead of
     * a full rebuild.
     */
    public enum Kind { ADDED, STATE_CHANGED, EVIDENCE_CHANGED, SEEN, ALIAS_CHANGED, REMOVED }

    /** True when the effective state of the entry just became {@code state}. */
    public boolean entered(CodingAgentState state) {
        return current != null && current.state() == state && (previous == null || previous.state() != state);
    }
}
