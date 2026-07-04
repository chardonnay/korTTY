package de.kortty.telemetry;

import de.kortty.model.AiProfile;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Shared prop builders for instrumentation call sites. Anti-PII contract:
 * never reads profile names, API URLs, keys, or executable paths.
 */
public final class TelemetryProps {

    private static final int MAX_MODEL_LENGTH = 64;

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
     * Normalizes a model name to an allowlisted token, avoiding free-text PII leakage.
     * Returns a known model family prefix or "auto" as a fallback.
     */
    private static String normalizeModelName(String model) {
        if (model == null || model.isBlank()) {
            return "auto";
        }
        String normalized = model.trim().toLowerCase(Locale.ROOT);
        // Match known model family prefixes
        if (normalized.startsWith("gpt-")) {
            return "gpt";
        }
        if (normalized.startsWith("claude-")) {
            return "claude";
        }
        if (normalized.startsWith("gemini-")) {
            return "gemini";
        }
        if (normalized.startsWith("llama-") || normalized.startsWith("llama2-") || normalized.startsWith("llama3-")) {
            return "llama";
        }
        if (normalized.startsWith("mistral-")) {
            return "mistral";
        }
        if (normalized.startsWith("codellama-")) {
            return "codellama";
        }
        if (normalized.startsWith("deepseek-")) {
            return "deepseek";
        }
        if (normalized.startsWith("qwen-")) {
            return "qwen";
        }
        if (normalized.startsWith("phi-")) {
            return "phi";
        }
        // Fallback for unknown or custom models
        return "auto";
    }

    private TelemetryProps() {
    }
}
