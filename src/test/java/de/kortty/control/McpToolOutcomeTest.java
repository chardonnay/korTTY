package de.kortty.control;

import de.kortty.telemetry.McpToolTelemetry.Outcome;
import org.testng.annotations.Test;

import java.util.Map;

import static com.google.common.truth.Truth.assertThat;

/** How a refused MCP request is reported in {@code mcp_tool_called}. */
class McpToolOutcomeTest {

    private static Outcome outcome(ControlErrorCode code) {
        return ControlConnection.mcpOutcome(new ControlApiException(code, "x"));
    }

    private static Outcome denied(String reason) {
        return ControlConnection.mcpOutcome(new ControlApiException(ControlErrorCode.MCP_WRITE_DENIED, "x",
            Map.of("pane", "p-1", "reason", reason)));
    }

    @Test
    void gateAllowlistPolicyAndPaneGuardsAreRefusals() {
        for (ControlErrorCode code : new ControlErrorCode[] {ControlErrorCode.MCP_SERVER_DISABLED,
                ControlErrorCode.METHOD_NOT_ALLOWED_FOR_MCP, ControlErrorCode.BLOCKED_BY_POLICY,
                ControlErrorCode.CONTROL_API_DISABLED, ControlErrorCode.MCP_WRITE_REFUSED}) {
            assertThat(outcome(code)).isEqualTo(Outcome.REFUSED);
        }
    }

    @Test
    void consentDenialsAreDeniedAndAnUnansweredPromptIsATimeout() {
        assertThat(denied(McpWriteConsent.REASON_DENIED)).isEqualTo(Outcome.DENIED);
        assertThat(denied(McpWriteConsent.REASON_NO_PROMPT)).isEqualTo(Outcome.DENIED);
        assertThat(denied(McpWriteConsent.REASON_TIMEOUT)).isEqualTo(Outcome.TIMEOUT);
        assertThat(outcome(ControlErrorCode.MCP_WRITE_DENIED)).isEqualTo(Outcome.DENIED);
    }

    @Test
    void aWaitThatRanOutIsATimeoutAndEverythingElseFailed() {
        assertThat(outcome(ControlErrorCode.TIMEOUT)).isEqualTo(Outcome.TIMEOUT);
        assertThat(outcome(ControlErrorCode.PANE_NOT_FOUND)).isEqualTo(Outcome.FAILED);
        assertThat(outcome(ControlErrorCode.INVALID_PARAMS)).isEqualTo(Outcome.FAILED);
        assertThat(ControlConnection.mcpOutcome(null)).isEqualTo(Outcome.FAILED);
    }
}
