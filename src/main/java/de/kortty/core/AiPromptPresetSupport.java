package de.kortty.core;

import de.kortty.ai.catalog.AiCatalogService;
import de.kortty.model.AiPromptPreset;

/** Resolves model-family presets and appends conservative compatibility guidance. */
public final class AiPromptPresetSupport {

    private AiPromptPresetSupport() {
    }

    public static AiPromptPreset resolve(AiPromptPreset configured, String modelName) {
        AiPromptPreset preset = configured != null ? configured : AiPromptPreset.AUTO;
        if (preset != AiPromptPreset.AUTO) {
            return preset;
        }
        return AiCatalogService.getDefault().catalog().promptPresetFor(modelName)
            .orElse(AiPromptPreset.GENERIC);
    }

    public static String append(String systemPrompt, AiPromptPreset preset) {
        String base = systemPrompt != null ? systemPrompt.trim() : "";
        String guidance = guidance(preset);
        return guidance.isBlank() ? base : base + "\n\n<kortty_model_preset>\n" + guidance
            + "\n</kortty_model_preset>";
    }

    static String guidance(AiPromptPreset preset) {
        AiPromptPreset effective = preset != null ? preset : AiPromptPreset.GENERIC;
        return switch (effective) {
            case AUTO, GENERIC -> "";
            case LLAMA -> "Use the model's native chat template. Follow the latest instruction and exact output schema; keep the final answer direct and omit internal reasoning.";
            case QWEN -> "Produce the final answer directly. Do not emit <think> or analysis sections. Preserve exact JSON/code contracts even when reasoning mode is supported.";
            case MISTRAL -> "Treat system constraints as authoritative, keep the response concise, and emit exactly the requested JSON, code, or prose format without wrapper text.";
            case GEMMA -> "Prioritize the explicit task and output schema. Do not repeat the prompt, add conversational preambles, or expose chain-of-thought.";
            case DEEPSEEK -> "Return only the final result and suppress reasoning traces or <think> blocks. Never let reasoning text break strict JSON or code-only responses.";
            case PHI -> "Use short, unambiguous steps internally and return only the required final format. Avoid extra headings or explanations for strict outputs.";
            case GPT_OSS -> "Follow the exact final-answer channel contract represented by this prompt. Do not surface hidden analysis and do not add text outside strict structured outputs.";
        };
    }
}
