package de.kortty.isolation;

import java.util.Collection;

/**
 * The isolation a running terminal session actually has, as opposed to the {@link IsolationLevel} that
 * was asked for. It is what the shield on a terminal tab shows, so it is only ever as strong as what was
 * verified: a sandbox that could not be set up, or whose self-test failed, is {@link #DEGRADED}, never
 * {@link #SANDBOXED}.
 */
public enum IsolationState {

    /** No isolation: no shield on the tab. */
    NONE,

    /** A process of its own, without a sandbox: the shield outline. */
    PROCESS,

    /** A sandbox was asked for but is not active; the session runs as {@link #PROCESS}: the shield with a warning sign. */
    DEGRADED,

    /** A process of its own inside a verified operating-system sandbox: the filled shield. */
    SANDBOXED;

    /**
     * The state a tab with several panes shows. A {@link #DEGRADED} pane wins, because a sandbox that
     * silently is not there is what the user must notice; otherwise the weakest pane does, so a shield is
     * never stronger than the least isolated pane behind it. {@link #NONE} for no states at all.
     */
    public static IsolationState aggregate(Collection<IsolationState> states) {
        if (states == null || states.isEmpty()) {
            return NONE;
        }
        IsolationState weakest = SANDBOXED;
        for (IsolationState state : states) {
            IsolationState s = state != null ? state : NONE;
            if (s == DEGRADED) {
                return DEGRADED;
            }
            if (strength(s) < strength(weakest)) {
                weakest = s;
            }
        }
        return weakest;
    }

    private static int strength(IsolationState state) {
        return switch (state) {
            case NONE -> 0;
            case PROCESS, DEGRADED -> 1;
            case SANDBOXED -> 2;
        };
    }
}
