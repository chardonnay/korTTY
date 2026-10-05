package de.kortty.policy;

/**
 * Settings the policy can take over. UI code asks {@link EffectivePolicy#isManaged(ManagedSetting)}
 * to decide whether a control must be locked with a "managed by your organization" hint.
 */
public enum ManagedSetting {
    AI_FEATURES,
    AGENT_EXECUTION,
    AGENT_CONFIRM_MUTATING,
    TEAMWORK,
    PLUGINS,
    UPDATES,
    TELEMETRY,
    TERMINAL_RECORDING,
    PORT_FORWARDING,
    MASTER_PASSWORD,
    HOST_KEY_CHECK,
    SCRIPT_HEADERS,
    AI_PROFILES,
    AI_INTERNET,
    AI_RUNTIME,
    LOAD_INTO_SNIPPET_EDITOR,
    SERVER_ACCESS,
    /**
     * The clipboard group: {@code clipboard-mode} and {@code allow-osc52-clipboard-write}. The OSC 52
     * switch in Settings → Terminal is locked only while the policy forbids it
     * ({@link EffectivePolicy#osc52ClipboardWriteAllowed()}).
     */
    CLIPBOARD,
    LOGGING,
    SESSION_JOURNAL,
    CONTROL_API,
    SNIPPET_ANALYSIS_CONTENT,
    /** The "Run highlight trigger actions" switch in Settings → Terminal ({@link PolicyFeature#TERMINAL_TRIGGERS}). */
    TERMINAL_TRIGGERS,
    /** The paste warning in Settings → Terminal → Paste protection ({@code [rule.terminal] paste-warning}). */
    PASTE_WARNING,
    /** Multi-exec and broadcast mode ({@link PolicyFeature#MULTI_EXEC}); no Settings control of its own. */
    MULTI_EXEC,
    /** The startup session restore in Settings → Window ({@code [rule.terminal] session-restore}). */
    SESSION_RESTORE,
    /**
     * "Also restore the output of each terminal pane" and its lines per pane in Settings → Window
     * ({@code [rule.terminal] session-restore-output}).
     */
    SESSION_RESTORE_OUTPUT,
    /** File transfer as a whole ({@link PolicyFeature#FILE_TRANSFER}); no Settings control of its own. */
    FILE_TRANSFER,
    /**
     * The parallel-transfers spinner and the conflict-default choice in Settings → SFTP Manager
     * ({@code [rule.sftp] max-parallel-transfers} and {@code conflict-default}).
     */
    SFTP_TRANSFERS,
    /** Editing server files as root ({@link PolicyFeature#SFTP_SUDO_EDIT}); no Settings control of its own. */
    SFTP_SUDO_EDIT,
    /** JobScheduler webhook notifications ({@link PolicyFeature#JOB_WEBHOOKS}); no Settings control of its own. */
    JOB_WEBHOOKS,
    /**
     * The hosts JobScheduler webhooks may be sent to ({@code [rule.job-scheduler] webhook-host-allowlist},
     * {@link EffectivePolicy#webhookHostAllowlist()}); an empty list allows any host.
     */
    WEBHOOK_HOST_ALLOWLIST,
    /**
     * The two MCP server switches ({@link PolicyFeature#MCP_SERVER}): denied, both are forced off and
     * locked.
     */
    MCP_SERVER,
    CONNECTION_ISOLATION,
    INCOGNITO_SESSIONS
}
