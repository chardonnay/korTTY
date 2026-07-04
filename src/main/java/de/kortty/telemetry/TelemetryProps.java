package de.kortty.telemetry;

import de.kortty.model.AiProfile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Shared prop builders for instrumentation call sites. Anti-PII contract:
 * never reads profile names, API URLs, keys, or executable paths.
 */
public final class TelemetryProps {

    /**
     * Known model-family keywords, matched by substring against the lowercased model id
     * (not just a prefix — vendor-qualified ids like {@code "openai/gpt-oss-20b"} or
     * {@code "meta-llama/Llama-3.1-70b"} are common). Order matters: first match wins.
     * Keep in sync with the providers korTTY actually suggests/supports
     * ({@code AiCloudModelCatalog}, {@code AiCliProviderRegistry}).
     */
    private static final List<Map.Entry<String, List<String>>> MODEL_FAMILIES = List.of(
        Map.entry("openai", List.of("gpt", "chatgpt", "o1-", "o1preview", "o3-", "o4-")),
        Map.entry("anthropic", List.of("claude")),
        Map.entry("google", List.of("gemini")),
        Map.entry("minimax", List.of("minimax", "mmx")),
        Map.entry("meta", List.of("llama")),
        Map.entry("mistral", List.of("mistral", "mixtral")),
        Map.entry("deepseek", List.of("deepseek")),
        Map.entry("qwen", List.of("qwen")),
        Map.entry("phi", List.of("phi-", "phi2", "phi3", "phi4")),
        Map.entry("cohere", List.of("command", "cohere")),
        Map.entry("xai", List.of("grok")));

    /** {@code mode}, {@code cli_provider}, {@code model} for an AI profile. */
    public static Map<String, Object> aiProfileProps(AiProfile profile) {
        Map<String, Object> props = new LinkedHashMap<>();
        if (profile == null) {
            return props;
        }
        props.put("mode", profile.getConnectionMode().name().toLowerCase(Locale.ROOT));
        String cliProvider = profile.getCliProviderId();
        props.put("cli_provider", cliProvider != null && !cliProvider.isBlank() ? cliProvider.trim() : "none");
        props.put("model", normalizeModelName(profile.getModel()));
        return props;
    }

    /**
     * Buckets a model id into a known family, never passing through free text — a
     * custom/self-hosted endpoint's model name could otherwise leak an internal or
     * identifying naming convention. {@code "auto"} means no model was configured;
     * {@code "other"} means a model was configured but isn't in a known family.
     */
    static String normalizeModelName(String model) {
        if (model == null || model.isBlank()) {
            return "auto";
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        for (Map.Entry<String, List<String>> family : MODEL_FAMILIES) {
            for (String keyword : family.getValue()) {
                if (normalized.contains(keyword)) {
                    return family.getKey();
                }
            }
        }
        return "other";
    }

    private TelemetryProps() {
    }
}
