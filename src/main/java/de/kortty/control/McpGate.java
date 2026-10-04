package de.kortty.control;

import de.kortty.model.GlobalSettings;
import de.kortty.policy.EffectivePolicy;

/**
 * The gate an MCP client ({@code client_kind = "mcp"}) passes on top of {@link ControlApiGate}.
 *
 * <p>Pure, any thread. Evaluated when an MCP client authenticates and again before every request it
 * sends, so switching the MCP server off — or a policy reload that denies it — refuses the very next
 * call on a connection that is already open.
 *
 * <p>Three legs, every one fail-closed: enterprise policy ({@code mcp-server}, which also needs
 * {@code control-api}), the user's default-off "MCP server" switch, and the separate default-off
 * "allow write tools" switch, which only widens an open gate from read-only to read-write.
 */
public final class McpGate {

    private McpGate() {
    }

    /** What the gate says right now, and which refusal that is on the wire. */
    public enum Verdict {

        /** MCP clients are served the read-only allowlist. */
        READ_ONLY(null, "The korTTY MCP server is available, read-only"),

        /** MCP clients are served the read-only allowlist plus the pane write verbs. */
        READ_WRITE(null, "The korTTY MCP server is available, with write tools"),

        /** The user has not switched on the (default-off) MCP server setting. */
        DISABLED_BY_SETTING(ControlErrorCode.MCP_SERVER_DISABLED,
            "The korTTY MCP server is switched off"),

        /** Enterprise policy denies {@code mcp-server} (or {@code control-api}). */
        BLOCKED_BY_POLICY(ControlErrorCode.BLOCKED_BY_POLICY,
            "The korTTY MCP server is denied by enterprise policy"),

        /** A leg is not resolved yet, i.e. korTTY is still starting up. */
        NOT_READY(ControlErrorCode.NOT_READY, "The korTTY MCP server is not ready yet");

        private final ControlErrorCode errorCode;

        private final String message;

        Verdict(ControlErrorCode errorCode, String message) {
            this.errorCode = errorCode;
            this.message = message;
        }

        /** Whether MCP clients may be served at all. */
        public boolean isOpen() {
            return this == READ_ONLY || this == READ_WRITE;
        }

        /** Whether MCP clients may also use the pane write verbs. */
        public boolean writesAllowed() {
            return this == READ_WRITE;
        }

        /** The refusal this verdict raises; null for the two open arms. */
        public ControlErrorCode errorCode() {
            return errorCode;
        }

        /** The English wire message, naming the cause but no configuration detail. */
        public String message() {
            return message;
        }
    }

    /**
     * The verdict for the live settings and the resolved policy; a null argument is a leg that is not
     * resolved yet and answers {@link Verdict#NOT_READY}.
     */
    public static Verdict verdict(GlobalSettings settings, EffectivePolicy policy) {
        return evaluate(policy == null ? null : policy.mcpServerAllowed(),
            settings == null ? null : settings.isMcpServerEnabled(),
            settings == null ? null : settings.isMcpWriteToolsEnabled());
    }

    /**
     * The truth table: a known policy denial wins (the clamp forces both switches off whenever the
     * policy denies, so the switch being off is a consequence, not a separate choice); then the user's
     * switch; then an unknown leg; and only then the write switch, which can never open a closed gate.
     *
     * @param policyAllowed the policy leg; null when it could not be determined
     * @param serverEnabled the "MCP server" switch; null when it could not be determined
     * @param writesEnabled the "allow write tools" switch; null counts as off
     */
    static Verdict evaluate(Boolean policyAllowed, Boolean serverEnabled, Boolean writesEnabled) {
        if (Boolean.FALSE.equals(policyAllowed)) {
            return Verdict.BLOCKED_BY_POLICY;
        }
        if (Boolean.FALSE.equals(serverEnabled)) {
            return Verdict.DISABLED_BY_SETTING;
        }
        if (policyAllowed == null || serverEnabled == null) {
            return Verdict.NOT_READY;
        }
        return Boolean.TRUE.equals(writesEnabled) ? Verdict.READ_WRITE : Verdict.READ_ONLY;
    }
}
