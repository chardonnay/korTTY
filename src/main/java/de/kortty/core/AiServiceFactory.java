package de.kortty.core;

import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiProfile;

/**
 * Builds the correct AI service implementation for a configured profile.
 */
public final class AiServiceFactory {

    private static final String OPENAI_CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String MISSING_MODEL_MESSAGE = "AI model must be configured.";

    private AiServiceFactory() {
    }

    public static AiService create(
        AiProfile profile,
        String apiKey,
        AiInternetAccessConfiguration internetConfig) {

        return create(profile, apiKey, internetConfig, AiSkillPromptSupport.disabled());
    }

    public static AiService create(
        AiProfile profile,
        String apiKey,
        AiInternetAccessConfiguration internetConfig,
        AiSkillPromptSupport skillPromptSupport) {

        if (profile == null) {
            return null;
        }
        String apiUrl = trimToNull(profile.getApiUrl());
        if (apiUrl == null || apiUrl.matches("^https?://[^/]+/?$")) {
            return null;
        }
        String model = trimToNull(profile.getModel());
        String normalizedApiKey = apiKey != null ? apiKey.trim() : "";
        AiInternetAccessConfiguration effectiveConfig = internetConfig != null
            ? internetConfig
            : AiInternetAccessConfiguration.disabled();
        AiSkillPromptSupport effectiveSkillSupport = skillPromptSupport != null
            ? skillPromptSupport
            : AiSkillPromptSupport.disabled();
        AiInternetAccessMode mode = profile.getInternetAccessMode();
        if (mode == null) {
            throw new IllegalStateException("AI internet access mode must be configured.");
        }
        if (mode.usesLmStudioMcp()) {
            if (!apiUrl.matches("(?i).*/api/v1/chat/?$")) {
                throw new IllegalStateException("LM Studio MCP internet modes require the LM Studio native API endpoint /api/v1/chat.");
            }
            return new LmStudioNativeAiService(
                apiUrl,
                model != null ? model : "",
                normalizedApiKey,
                AiReasoningSupport.normalizeForProfile(profile),
                effectiveConfig,
                effectiveSkillSupport);
        }
        TavilyWebSearchTool webSearchTool = null;
        if (mode.usesKorTTYTool()) {
            String tavilyApiKey = trimToNull(effectiveConfig.tavilyApiKey());
            if (tavilyApiKey == null) {
                throw new IllegalStateException("Tavily API key must be configured for internet mode " + mode + ".");
            }
            webSearchTool = new TavilyWebSearchTool(tavilyApiKey);
        }
        if (model == null) {
            throw new IllegalStateException(MISSING_MODEL_MESSAGE);
        }
        return new OpenAiCompatibleAiService(
            normalizeOpenAiCompatibleChatCompletionsUrl(apiUrl),
            model,
            normalizedApiKey,
            AiReasoningSupport.normalizeForProfile(profile),
            webSearchTool,
            effectiveSkillSupport);
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    private static String normalizeOpenAiCompatibleChatCompletionsUrl(String apiUrl) {
        String trimmed = apiUrl.trim();
        String withoutTrailingSlashes = trimmed;
        while (withoutTrailingSlashes.endsWith("/")) {
            withoutTrailingSlashes = withoutTrailingSlashes.substring(0, withoutTrailingSlashes.length() - 1);
        }
        if (withoutTrailingSlashes.matches("(?i).*/v1")) {
            return withoutTrailingSlashes + OPENAI_CHAT_COMPLETIONS_PATH;
        }
        return trimmed;
    }
}
