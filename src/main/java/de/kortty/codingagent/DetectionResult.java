package de.kortty.codingagent;

/** Pure result of classifying one screen for one agent kind. kind == UNKNOWN means "no coding agent in this pane". */
public record DetectionResult(CodingAgentKind kind, CodingAgentState state, String matchedRuleId, String evidence) {

    public static final DetectionResult NONE =
        new DetectionResult(CodingAgentKind.UNKNOWN, CodingAgentState.UNKNOWN, null, null);

    public static DetectionResult of(CodingAgentKind kind, CodingAgentState state, String matchedRuleId, String evidence) {
        return new DetectionResult(kind == null ? CodingAgentKind.UNKNOWN : kind,
            state == null ? CodingAgentState.UNKNOWN : state, matchedRuleId, evidence);
    }

    public boolean agentDetected() {
        return kind != CodingAgentKind.UNKNOWN;
    }

    /** True when a rule produced this state (as opposed to the rule set's fallbackState). */
    public boolean ruleMatched() {
        return matchedRuleId != null;
    }

    public boolean fallbackApplied() {
        return agentDetected() && matchedRuleId == null;
    }

    /** One-line human explanation, the Stage 2/3 "agent explain" payload. */
    public String explain() {
        if (!agentDetected()) {
            return "No coding agent detected";
        }
        StringBuilder sb = new StringBuilder(kind.displayName()).append(" is ").append(state);
        if (matchedRuleId != null) {
            sb.append(" (rule ").append(matchedRuleId);
        } else {
            sb.append(" (no rule matched, fallback");
        }
        if (evidence != null) {
            sb.append(": \"").append(evidence.strip()).append('"');
        }
        return sb.append(')').toString();
    }
}
