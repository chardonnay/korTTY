package de.kortty.core;

/**
 * Pure helper that reduces a terminal's aggregated AI-agent run counts to a single status with a
 * priority-ordered indicator icon, used by the Dashboard and the terminal tab title.
 *
 * <p>Counts are {@code [awaitingInput, working, paused, done]} (as produced by the activity panels).
 * The most user-actionable state wins: AWAITING &gt; WORKING &gt; PAUSED &gt; DONE &gt; NONE.
 */
public final class AgentDashboardStatus {

    public enum State {
        NONE,
        DONE,
        PAUSED,
        WORKING,
        AWAITING
    }

    private AgentDashboardStatus() {
    }

    /** Reduces {@code [awaitingInput, working, paused, done]} counts to a single status. */
    public static State aggregate(int[] counts) {
        if (counts == null || counts.length < 4) {
            return State.NONE;
        }
        if (counts[0] > 0) {
            return State.AWAITING;
        }
        if (counts[1] > 0) {
            return State.WORKING;
        }
        if (counts[2] > 0) {
            return State.PAUSED;
        }
        if (counts[3] > 0) {
            return State.DONE;
        }
        return State.NONE;
    }

    /** The indicator glyph for a state ("" for NONE so no badge is shown). */
    public static String icon(State state) {
        if (state == null) {
            return "";
        }
        switch (state) {
            case AWAITING:
                return "\u270B"; // ✋ waiting for user input
            case WORKING:
                return "\u26A1"; // ⚡ actively working
            case PAUSED:
                return "\u23F8"; // ⏸ paused
            case DONE:
                return "\u2713"; // ✓ finished (runs still open)
            default:
                return "";
        }
    }

    /** Convenience: the indicator glyph for the aggregate of the given counts. */
    public static String icon(int[] counts) {
        return icon(aggregate(counts));
    }
}
