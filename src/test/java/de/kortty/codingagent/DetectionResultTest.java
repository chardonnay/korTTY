package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import org.testng.annotations.Test;

class DetectionResultTest {

    @Test
    void noneMeansNoAgent() {
        assertThat(DetectionResult.NONE.agentDetected()).isFalse();
        assertThat(DetectionResult.NONE.ruleMatched()).isFalse();
        assertThat(DetectionResult.NONE.fallbackApplied()).isFalse();
        assertThat(DetectionResult.NONE.explain()).isEqualTo("No coding agent detected");
    }

    @Test
    void ofSubstitutesUnknownForNulls() {
        DetectionResult result = DetectionResult.of(null, null, null, null);
        assertThat(result.kind()).isEqualTo(CodingAgentKind.UNKNOWN);
        assertThat(result.state()).isEqualTo(CodingAgentState.UNKNOWN);
        assertThat(result.agentDetected()).isFalse();
    }

    @Test
    void ruleMatchIsExplainedWithRuleIdAndEvidence() {
        DetectionResult result = DetectionResult.of(
            CodingAgentKind.CLAUDE_CODE, CodingAgentState.BLOCKED, "permission-prompt", "  Do you want to proceed?  ");

        assertThat(result.agentDetected()).isTrue();
        assertThat(result.ruleMatched()).isTrue();
        assertThat(result.fallbackApplied()).isFalse();
        assertThat(result.explain())
            .isEqualTo("Claude Code is BLOCKED (rule permission-prompt: \"Do you want to proceed?\")");
    }

    @Test
    void fallbackIsExplainedWithoutRuleId() {
        DetectionResult result = DetectionResult.of(CodingAgentKind.CODEX, CodingAgentState.IDLE, null, null);

        assertThat(result.ruleMatched()).isFalse();
        assertThat(result.fallbackApplied()).isTrue();
        assertThat(result.explain()).isEqualTo("Codex is IDLE (no rule matched, fallback)");
    }

    @Test
    void recordsWithEqualComponentsAreEqual() {
        DetectionResult a = DetectionResult.of(CodingAgentKind.GEMINI_CLI, CodingAgentState.WORKING, "w", "e");
        DetectionResult b = DetectionResult.of(CodingAgentKind.GEMINI_CLI, CodingAgentState.WORKING, "w", "e");
        assertThat(a).isEqualTo(b);
        assertThat(a).isNotEqualTo(DetectionResult.NONE);
    }
}
