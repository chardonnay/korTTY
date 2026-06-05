package de.kortty.core;

import java.util.List;

/**
 * Metadata for a selectable local AI CLI provider.
 */
public record AiCliProviderDescriptor(
    String id,
    String displayName,
    List<String> commandCandidates,
    List<AiCliModelPreset> modelPresets,
    List<AiCliArgumentPreset> argumentPresets,
    boolean supportsRateLimitProbe) {

    public AiCliProviderDescriptor {
        id = id != null ? id.trim() : "";
        displayName = displayName != null ? displayName.trim() : id;
        commandCandidates = commandCandidates != null ? List.copyOf(commandCandidates) : List.of();
        modelPresets = modelPresets != null ? List.copyOf(modelPresets) : List.of();
        argumentPresets = argumentPresets != null ? List.copyOf(argumentPresets) : List.of();
    }
}
