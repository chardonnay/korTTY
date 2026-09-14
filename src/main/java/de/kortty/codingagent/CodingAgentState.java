package de.kortty.codingagent;

/**
 * Detected state of a coding agent. Declared in rollup order: earlier = more urgent
 * (Stage 2 rollup BLOCKED &gt; DONE &gt; WORKING &gt; IDLE &gt; UNKNOWN). DONE is reserved for rules that
 * recognise an explicit completion screen and for Stage 2's done-until-seen transition.
 */
public enum CodingAgentState {
    BLOCKED, DONE, WORKING, IDLE, UNKNOWN;

    public static CodingAgentState mostUrgent(CodingAgentState a, CodingAgentState b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        return a.ordinal() <= b.ordinal() ? a : b;
    }
}
