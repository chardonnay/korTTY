package de.kortty.ui;

/**
 * Which of the two MCP switches on <em>Settings › Terminal › Control API</em> can be changed, and the
 * hint shown under them.
 *
 * <p>Pure, any thread. The policy only locks the switches when it denies the MCP server (or the
 * Control API); an {@code allow} leaves both with the user, because {@code PolicyClamp} never forces
 * them on. The switches keep their values while they are greyed out: the MCP gate is closed anyway
 * whenever the Control API is off.
 */
public final class McpSettingsSupport {

    /** Hint while the Control API checkbox is unticked. */
    public static final String HINT_NEEDS_CONTROL_API = "settings.controlApi.mcpServer.needsControlApi";

    /** Hint while the enterprise policy denies {@code mcp-server} or {@code control-api}. */
    public static final String HINT_BLOCKED_BY_POLICY = "settings.controlApi.mcpServer.blockedByPolicy";

    /**
     * What the two checkboxes allow right now.
     *
     * @param serverEditable whether "MCP server" can be ticked or unticked
     * @param writeToolsEditable whether "Allow write tools" can be ticked or unticked
     * @param forceOff whether both boxes must be shown unticked (the policy forces them off)
     * @param hintKey the i18n key of the explanation, or null when nothing is disabled
     */
    public record State(boolean serverEditable, boolean writeToolsEditable, boolean forceOff, String hintKey) {
    }

    private McpSettingsSupport() {
    }

    /**
     * @param controlApiTicked the Control API checkbox as it is shown now (not as saved)
     * @param policyAllowsMcp {@code EffectivePolicy.mcpServerAllowed()}
     * @param serverTicked the "MCP server" checkbox as it is shown now
     */
    public static State state(boolean controlApiTicked, boolean policyAllowsMcp, boolean serverTicked) {
        if (!policyAllowsMcp) {
            return new State(false, false, true, HINT_BLOCKED_BY_POLICY);
        }
        if (!controlApiTicked) {
            return new State(false, false, false, HINT_NEEDS_CONTROL_API);
        }
        return new State(true, serverTicked, false, null);
    }
}
