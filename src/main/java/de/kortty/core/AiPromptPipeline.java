package de.kortty.core;

/** Enforces Action → Skills → RAG → Model preset ordering for system prompts. */
final class AiPromptPipeline {

    private static final String RETRIEVED = "<retrieved_context>";
    private static final String PRESET = "<kortty_model_preset>";

    private AiPromptPipeline() {
    }

    static String appendAfterSkills(String systemPrompt, AiRequest request) {
        String result = normalize(systemPrompt);
        if (request != null && request.retrievedContext() != null && !request.retrievedContext().isBlank()) {
            result = append(result, request.retrievedContext().trim());
        }
        return AiPromptPresetSupport.append(result, request != null ? request.promptPreset() : null);
    }

    static String insertSkills(String systemPrompt, String skillBlock) {
        String base = normalize(systemPrompt);
        String skills = normalize(skillBlock);
        if (skills.isBlank()) {
            return base;
        }
        int boundary = suffixBoundary(base);
        if (boundary < 0) {
            return append(base, skills);
        }
        String prefix = base.substring(0, boundary).stripTrailing();
        String suffix = base.substring(boundary).stripLeading();
        return append(append(prefix, skills), suffix);
    }

    private static int suffixBoundary(String value) {
        int retrieved = value.indexOf(RETRIEVED);
        int preset = value.indexOf(PRESET);
        if (retrieved < 0) return preset;
        if (preset < 0) return retrieved;
        return Math.min(retrieved, preset);
    }

    private static String append(String first, String second) {
        if (first.isBlank()) return second;
        if (second.isBlank()) return first;
        return first + "\n\n" + second;
    }

    private static String normalize(String value) {
        return value != null ? value.trim() : "";
    }
}
