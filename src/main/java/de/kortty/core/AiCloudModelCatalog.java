package de.kortty.core;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Curated, well-known model names for recognised cloud AI providers, keyed by API host.
 *
 * <p>Used to pre-populate the model picker so a user can choose a concrete model without a live
 * {@code /v1/models} call (which needs a valid API key), and to show example models in the setup
 * wizard. The list is intentionally short and may age; any live model list is merged on top of it.
 */
public final class AiCloudModelCatalog {

    private static final Map<String, List<String>> MODELS_BY_HOST = buildCatalog();

    private AiCloudModelCatalog() {
    }

    private static Map<String, List<String>> buildCatalog() {
        Map<String, List<String>> catalog = new LinkedHashMap<>();
        catalog.put("api.openai.com", List.of("gpt-4o-mini", "gpt-4o", "o4-mini"));
        catalog.put("api.anthropic.com", List.of("claude-3-5-sonnet-latest", "claude-3-5-haiku-latest"));
        catalog.put("generativelanguage.googleapis.com", List.of("gemini-2.0-flash", "gemini-1.5-pro"));
        catalog.put("api.mistral.ai", List.of("mistral-large-latest", "mistral-small-latest"));
        catalog.put("api.deepseek.com", List.of("deepseek-chat", "deepseek-reasoner"));
        catalog.put("api.groq.com", List.of("llama-3.3-70b-versatile", "openai/gpt-oss-20b"));
        catalog.put("openrouter.ai", List.of("openai/gpt-4o-mini", "anthropic/claude-3.5-sonnet"));
        catalog.put("api.minimax.io", List.of("MiniMax-Text-01"));
        return catalog;
    }

    /**
     * Returns curated model names for the provider hosting {@code apiUrl}, or an empty list when the
     * host is unknown or the URL cannot be parsed.
     */
    public static List<String> suggestedModelsForUrl(String apiUrl) {
        String host = hostOf(apiUrl);
        if (host == null) {
            return List.of();
        }
        return MODELS_BY_HOST.getOrDefault(host, List.of());
    }

    /** Comma-separated example model names for the provider hosting {@code apiUrl} (may be empty). */
    public static String examplesForUrl(String apiUrl) {
        return String.join(", ", suggestedModelsForUrl(apiUrl));
    }

    private static String hostOf(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return null;
        }
        try {
            String host = URI.create(apiUrl.trim()).getHost();
            return host != null ? host.toLowerCase(Locale.ROOT) : null;
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
