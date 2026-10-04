package de.kortty.policy;

import java.util.Locale;

/** Features an admin can switch off via {@code [rule.features]} in the policy file. */
public enum PolicyFeature {
    /** Master switch: disables every AI capability at once. */
    AI("ai"),
    AI_AGENT("ai-agent"),
    /** AI chat, saved chats and the terminal-selection AI actions. */
    AI_CHAT("ai-chat"),
    AI_SWARM("ai-swarm"),
    AI_PLANNING("ai-planning"),
    /** Shared-connections sync ("Teamwork") — not an AI feature. */
    TEAMWORK("teamwork"),
    /** Plugins, e.g. terminal effect plugins. */
    PLUGINS("plugins"),
    /** Session journal capture, management and export — not an AI feature; AI summaries additionally require AI. */
    SESSION_JOURNAL("session-journal"),
    /** The local control API and its {@code kortty-cli} client — not an AI feature. */
    CONTROL_API("control-api"),
    /**
     * Highlight-rule triggers: actions a rule takes when its pattern appears in terminal output (a
     * desktop notification, running a snippet). Highlighting itself is not affected.
     */
    TERMINAL_TRIGGERS("terminal-triggers"),
    /**
     * Typing into several terminals at once: multi-exec (panes of several tabs and windows) and a tab's
     * broadcast mode. Denied, no pane can join and broadcast mode cannot be switched on; leaving and
     * Stop Multi-exec always work.
     */
    MULTI_EXEC("multi-exec"),
    /**
     * Copying files between this computer and a server: uploads, downloads, drag and drop and
     * dragging out in the SFTP manager, and the JobScheduler's SFTP upload, download and sync
     * actions and its rsync action. Denied, every one of them is refused; browsing and remote-only
     * operations (rename, delete, permissions, archives, search, remote copy) keep working. Shell commands such as
     * {@code scp} typed into a terminal cannot be blocked.
     */
    FILE_TRANSFER("file-transfer"),
    /**
     * Editing server files as root from the SFTP manager ("Edit as root (sudo)..."). Allowed unless
     * denied; it also needs {@link #FILE_TRANSFER} and {@code load-into-snippet-editor = "allow"}.
     */
    SFTP_SUDO_EDIT("sftp-sudo-edit"),
    /**
     * korTTY as an MCP server ({@code kortty-cli mcp}): an MCP client reaches the control API with
     * {@code client_kind = "mcp"} and gets only the read-only allowlist (plus the pane write verbs when
     * the user also allowed write tools). Denied, every MCP connection is refused and both user
     * switches are forced off. It also needs {@link #CONTROL_API}; a plain control-API client is not
     * affected.
     */
    MCP_SERVER("mcp-server");

    private final String tomlKey;

    PolicyFeature(String tomlKey) {
        this.tomlKey = tomlKey;
    }

    public String tomlKey() {
        return tomlKey;
    }

    /** The feature for a {@code [rule.features]} key; null for unknown keys. */
    public static PolicyFeature fromTomlKey(String key) {
        if (key == null) {
            return null;
        }
        String normalized = key.trim().toLowerCase(Locale.ROOT);
        for (PolicyFeature feature : values()) {
            if (feature.tomlKey.equals(normalized)) {
                return feature;
            }
        }
        return null;
    }
}
