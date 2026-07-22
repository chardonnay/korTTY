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
    MASTER_PASSWORD,
    HOST_KEY_CHECK,
    SCRIPT_HEADERS,
    AI_PROFILES,
    AI_RUNTIME,
    LOAD_INTO_SNIPPET_EDITOR,
    SERVER_ACCESS
}
