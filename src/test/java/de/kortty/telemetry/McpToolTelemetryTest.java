package de.kortty.telemetry;

import de.kortty.cli.mcp.McpToolCatalog;
import org.testng.annotations.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * {@code mcp_tool_called} carries a tool from a fixed list and an outcome, nothing else: never a pane
 * id, an argument, the client name or a method an MCP client made up.
 */
class McpToolTelemetryTest {

    private static final Set<String> OUTCOMES = Set.of("ok", "refused", "denied", "timeout", "failed");

    @Test
    void propsAreTheToolAndTheOutcomeOnly() {
        for (String method : McpToolTelemetry.TOOLS.keySet()) {
            for (McpToolTelemetry.Outcome outcome : McpToolTelemetry.Outcome.values()) {
                Map<String, Object> props = McpToolTelemetry.props(method, outcome);
                assertThat(props.keySet()).containsExactly("tool", "outcome").inOrder();
                assertThat(props.get("tool")).isEqualTo(McpToolTelemetry.TOOLS.get(method));
                assertThat(OUTCOMES).contains(props.get("outcome"));
                assertOnlyLowercaseIds(props);
            }
        }
        assertThat(McpToolTelemetry.props("pane.run", McpToolTelemetry.Outcome.DENIED))
            .containsExactly("tool", "pane_run", "outcome", "denied").inOrder();
        assertThat(McpToolTelemetry.props("pane.read", null).get("outcome")).isEqualTo("failed");
    }

    @Test
    void methodsOutsideTheToolListAreReportedAsOther() {
        for (String method : new String[] {"agent.prompt", "events.subscribe", "ping", "api.schema",
                "pane.read; rm -rf ~", "My Secret Method", "", null}) {
            assertThat(McpToolTelemetry.toolId(method)).isEqualTo("other");
        }
    }

    @Test
    void toolListMatchesTheKorttyCliMcpCatalogExactly() {
        Map<String, String> catalog = new HashMap<>();
        for (McpToolCatalog.McpTool tool : McpToolCatalog.all()) {
            catalog.put(tool.method(), tool.name());
        }
        assertWithMessage("McpToolTelemetry.TOOLS must mirror McpToolCatalog; a new tool needs a telemetry id")
            .that(McpToolTelemetry.TOOLS).containsExactlyEntriesIn(catalog);
    }

    @Test
    void eventNameIsStable() {
        assertThat(TelemetryEvents.MCP_TOOL_CALLED).isEqualTo("mcp_tool_called");
    }

    private static void assertOnlyLowercaseIds(Map<String, Object> props) {
        Pattern enumId = Pattern.compile("[a-z][a-z_]*");
        for (Map.Entry<String, Object> prop : props.entrySet()) {
            assertWithMessage(prop.getKey()).that(prop.getValue()).isInstanceOf(String.class);
            assertWithMessage(prop.getKey()).that(enumId.matcher((String) prop.getValue()).matches()).isTrue();
        }
    }
}
