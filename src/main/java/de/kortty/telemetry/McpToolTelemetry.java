package de.kortty.telemetry;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The props of {@code mcp_tool_called}: which {@code kortty-cli mcp} tool an MCP client called and how
 * the call ended.
 *
 * <p>The tool comes from a fixed list keyed by the wire method, so a method an MCP client is refused
 * (or a name a hand-written client made up) is reported as {@code other}, never by its name. No
 * argument, pane id, client name or text is ever part of the props.
 *
 * <p>Pure, any thread.
 */
public final class McpToolTelemetry {

    /** How an MCP tool call ended. */
    public enum Outcome {
        /** korTTY answered with a result. */
        OK,
        /** korTTY refused it: MCP server off, policy, allowlist, write tools off, or a pane guard. */
        REFUSED,
        /** The user denied the write, or korTTY could not ask. */
        DENIED,
        /** The consent prompt or a wait ran out of time. */
        TIMEOUT,
        /** Any other error, such as an unknown pane or invalid arguments. */
        FAILED;

        /** The wire id sent as the {@code outcome} prop. */
        public String id() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** The tool id for every method not in {@link #TOOLS}. */
    static final String OTHER_TOOL = "other";

    /** Wire method to MCP tool name, mirroring {@code McpToolCatalog} ({@code McpToolCatalogTest} pins it). */
    static final Map<String, String> TOOLS = Map.of(
        "pane.list", "pane_list",
        "pane.read", "pane_read",
        "pane.wait_output", "pane_wait_output",
        "tab.list", "tab_list",
        "agent.list", "agent_list",
        "pane.send_text", "pane_send_text",
        "pane.run", "pane_run",
        "pane.send_keys", "pane_send_keys");

    private McpToolTelemetry() {
    }

    /** The tool id reported for {@code method}: a {@code kortty-cli mcp} tool name, or {@code other}. */
    public static String toolId(String method) {
        return method == null ? OTHER_TOOL : TOOLS.getOrDefault(method, OTHER_TOOL);
    }

    /** {@code tool} and {@code outcome}, in that order; a null outcome is reported as {@code failed}. */
    public static Map<String, Object> props(String method, Outcome outcome) {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("tool", toolId(method));
        props.put("outcome", (outcome == null ? Outcome.FAILED : outcome).id());
        return props;
    }
}
