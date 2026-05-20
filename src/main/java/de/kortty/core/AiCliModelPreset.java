package de.kortty.core;

import de.kortty.model.AiReasoningEffort;

import java.util.List;

/**
 * Optional model metadata for providers with verified model/reasoning pairs.
 */
public record AiCliModelPreset(String modelName, List<AiReasoningEffort> reasoningEfforts) {

    public AiCliModelPreset {
        modelName = modelName != null ? modelName.trim() : "";
        reasoningEfforts = reasoningEfforts != null && !reasoningEfforts.isEmpty()
            ? List.copyOf(reasoningEfforts)
            : List.of(AiReasoningEffort.DISABLED);
    }
}
