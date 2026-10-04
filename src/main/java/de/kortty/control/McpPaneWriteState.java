package de.kortty.control;

import java.util.Optional;

/**
 * What korTTY knows about a pane right before it would type into it for an MCP client: the guards
 * the terminal itself applies before it types on its own, plus a label for the consent prompt.
 *
 * <p>Pure, any thread. Read on the JavaFX thread by {@link ControlSurface#mcpWriteStateOf(String)},
 * once before the consent prompt and once more inside the write hop, so a pane that started a
 * full-screen program or a paste while the user was reading the prompt is still refused.
 *
 * @param paneLabel how the consent prompt names the pane: tab title, host and pane id
 * @param known false for a surface that cannot tell; such a pane is always refused (fail-closed)
 * @param pastePacing the pane is still sending a paste line by line
 * @param alternateScreen a full-screen program such as {@code vim} or {@code less} has the pane
 * @param foreignSession the pane is suspected to run as another user or host ({@code su}, a nested
 *     {@code ssh})
 * @param broadcast the tab's broadcast mode is on
 * @param multiExec the pane takes part in multi-exec
 * @param codingAgent a coding agent is detected in the pane, or a korTTY agent run drives it
 */
public record McpPaneWriteState(String paneLabel, boolean known, boolean pastePacing,
                                boolean alternateScreen, boolean foreignSession, boolean broadcast,
                                boolean multiExec, boolean codingAgent) {

    /** Data reason: the surface cannot tell what the pane is doing. */
    public static final String REASON_UNKNOWN = "pane_state_unknown";

    /** Data reason: the pane is still pacing a paste. */
    public static final String REASON_PASTE_PACING = "paste_pacing";

    /** Data reason: a full-screen program has the pane. */
    public static final String REASON_ALTERNATE_SCREEN = "alternate_screen";

    /** Data reason: the pane is suspected to run as another identity. */
    public static final String REASON_FOREIGN_SESSION = "foreign_session";

    /** Data reason: the tab mirrors typed input to its other panes. */
    public static final String REASON_BROADCAST = "broadcast";

    /** Data reason: the pane mirrors typed input to the other multi-exec members. */
    public static final String REASON_MULTI_EXEC = "multi_exec";

    /** Data reason: a coding agent or a korTTY agent run owns the pane's input. */
    public static final String REASON_CODING_AGENT = "coding_agent";

    /** The state of a pane the surface cannot describe; it is always refused. */
    public static McpPaneWriteState unknown(String paneId) {
        return new McpPaneWriteState(paneId, false, false, false, false, false, false, false);
    }

    /** A pane with none of the guards raised. */
    public static McpPaneWriteState clear(String paneLabel) {
        return new McpPaneWriteState(paneLabel, true, false, false, false, false, false, false);
    }

    /**
     * Why korTTY refuses to type into the pane for an MCP client, before anybody is asked; empty
     * when nothing stands in the way. The first raised guard wins, in a fixed order.
     */
    public Optional<String> refusal() {
        if (!known) {
            return Optional.of(REASON_UNKNOWN);
        }
        if (pastePacing) {
            return Optional.of(REASON_PASTE_PACING);
        }
        if (alternateScreen) {
            return Optional.of(REASON_ALTERNATE_SCREEN);
        }
        if (foreignSession) {
            return Optional.of(REASON_FOREIGN_SESSION);
        }
        if (broadcast) {
            return Optional.of(REASON_BROADCAST);
        }
        if (multiExec) {
            return Optional.of(REASON_MULTI_EXEC);
        }
        if (codingAgent) {
            return Optional.of(REASON_CODING_AGENT);
        }
        return Optional.empty();
    }
}
