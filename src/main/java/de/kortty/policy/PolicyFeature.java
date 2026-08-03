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
    SESSION_JOURNAL("session-journal");

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
