package de.kortty.core;

/**
 * Verified CLI argument template shown for a local AI CLI provider.
 */
public record AiCliArgumentPreset(String displayName, String argumentsTemplate) {

    public AiCliArgumentPreset {
        displayName = displayName != null ? displayName.trim() : "";
        argumentsTemplate = argumentsTemplate != null ? argumentsTemplate.trim() : "";
    }
}
