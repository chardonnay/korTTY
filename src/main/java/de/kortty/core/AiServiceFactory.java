package de.kortty.core;

import de.kortty.ai.llama.EmbeddedLlamaAiService;
import de.kortty.ai.llama.LlamaModel;
import de.kortty.ai.llama.LlamaModelPurpose;
import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.mlx.EmbeddedMlxAiService;
import de.kortty.ai.mlx.MlxModel;
import de.kortty.ai.mlx.MlxModelRegistry;
import de.kortty.ai.mlx.MlxPlatform;
import de.kortty.KorTTYApplication;
import de.kortty.model.AiInternetAccessMode;
import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiProfile;
import de.kortty.model.AiPromptPreset;
import de.kortty.model.AiReasoningEffort;
import de.kortty.rag.RagRuntimeService;

import java.net.URI;
import java.util.Locale;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Builds the correct AI service implementation for a configured profile.
 */
public final class AiServiceFactory {

    private static final Logger LOG = LoggerFactory.getLogger(AiServiceFactory.class);

    private static final String OPENAI_CHAT_COMPLETIONS_PATH = "/chat/completions";
    private static final String MISSING_MODEL_MESSAGE = "AI model must be configured.";
    private static final String CLOUD_MODEL_REQUIRED_MESSAGE =
        "Select a specific AI model for this provider (for example gpt-4o). "
            + "Automatic model detection is only available for a local LM Studio server.";

    private AiServiceFactory() {
    }

    public static boolean canAutoResolveLocalModel(String apiUrl) {
        String trimmedApiUrl = trimToNull(apiUrl);
        if (trimmedApiUrl == null) {
            return false;
        }
        String normalizedApiUrl = normalizeOpenAiCompatibleChatCompletionsUrl(trimmedApiUrl);
        return LocalLmModelResolver.canResolve(normalizedApiUrl);
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

        return create(profile, apiKey, internetConfig, skillPromptSupport, null);
    }

    static AiService createForReasoningProbe(
        AiProfile profile,
        String apiKey,
        AiInternetAccessConfiguration internetConfig,
        AiSkillPromptSupport skillPromptSupport,
        AiReasoningEffort reasoningEffort) {

        return create(profile, apiKey, internetConfig, skillPromptSupport, reasoningEffort);
    }

    private static AiService create(
        AiProfile profile,
        String apiKey,
        AiInternetAccessConfiguration internetConfig,
        AiSkillPromptSupport skillPromptSupport,
        AiReasoningEffort reasoningEffortOverride) {

        if (profile == null) {
            return null;
        }
        AiSkillPromptSupport effectiveSkillSupport = skillPromptSupport != null
            ? skillPromptSupport
            : AiSkillPromptSupport.disabled();
        if (profile.getConnectionMode() == AiConnectionMode.EMBEDDED_LLAMA_CPP) {
            String embeddedModelId = trimToNull(profile.getEmbeddedModelId());
            if (embeddedModelId == null) {
                throw new IllegalStateException("Select a local GGUF model for the embedded llama.cpp profile.");
            }
            requirePolicyAllowedModel(embeddedModelId);
            LlamaModelRegistry.inDirectory(KorTTYApplication.getConfigDirectory().resolve("llm"))
                .find(embeddedModelId)
                .filter(model -> model.getPurpose() != LlamaModelPurpose.CHAT)
                .ifPresent(model -> {
                    throw new IllegalStateException(
                        "The selected local model is configured for embeddings, not chat generation.");
                });
            AiInternetAccessMode mode = profile.getInternetAccessMode();
            if (mode == null) {
                throw new IllegalStateException("AI internet access mode must be configured.");
            }
            requirePolicyAllowedInternetMode(mode);
            if (mode.usesLmStudioMcp()) {
                throw new IllegalStateException("LM Studio MCP internet modes are not available for embedded llama.cpp profiles.");
            }
            AiInternetAccessConfiguration effectiveConfig = internetConfig != null
                ? internetConfig
                : AiInternetAccessConfiguration.disabled();
            TavilyWebSearchTool webSearchTool = null;
            if (mode.usesKorTTYTool()) {
                String tavilyApiKey = trimToNull(effectiveConfig.tavilyApiKey());
                if (tavilyApiKey == null) {
                    throw new IllegalStateException("Tavily API key must be configured for internet mode " + mode + ".");
                }
                webSearchTool = new TavilyWebSearchTool(tavilyApiKey);
            }
            return decorate(profile, embeddedModelId, effectiveReasoningEffort(profile, reasoningEffortOverride), new EmbeddedLlamaAiService(
                embeddedModelId,
                effectiveReasoningEffort(profile, reasoningEffortOverride),
                webSearchTool,
                effectiveSkillSupport));
        }
        if (profile.getConnectionMode() == AiConnectionMode.EMBEDDED_MLX) {
            if (!MlxPlatform.isSupported()) {
                throw new IllegalStateException("Embedded MLX profiles require an Apple Silicon Mac.");
            }
            String embeddedModelId = trimToNull(profile.getEmbeddedModelId());
            if (embeddedModelId == null) {
                throw new IllegalStateException("Select a local MLX model for the embedded MLX profile.");
            }
            requirePolicyAllowedModel(embeddedModelId);
            AiInternetAccessMode mode = profile.getInternetAccessMode();
            if (mode == null) {
                throw new IllegalStateException("AI internet access mode must be configured.");
            }
            requirePolicyAllowedInternetMode(mode);
            if (mode.usesLmStudioMcp()) {
                throw new IllegalStateException("LM Studio MCP internet modes are not available for embedded MLX profiles.");
            }
            AiInternetAccessConfiguration effectiveConfig = internetConfig != null
                ? internetConfig
                : AiInternetAccessConfiguration.disabled();
            TavilyWebSearchTool webSearchTool = null;
            if (mode.usesKorTTYTool()) {
                String tavilyApiKey = trimToNull(effectiveConfig.tavilyApiKey());
                if (tavilyApiKey == null) {
                    throw new IllegalStateException("Tavily API key must be configured for internet mode " + mode + ".");
                }
                webSearchTool = new TavilyWebSearchTool(tavilyApiKey);
            }
            return decorate(profile, embeddedModelId, effectiveReasoningEffort(profile, reasoningEffortOverride), new EmbeddedMlxAiService(
                embeddedModelId,
                effectiveReasoningEffort(profile, reasoningEffortOverride),
                webSearchTool,
                effectiveSkillSupport));
        }
        if (profile.getConnectionMode() == AiConnectionMode.LOCAL_CLI) {
            String model = trimToNull(profile.getModel());
            AiModelSelectionMode modelSelectionMode = profile.getModelSelectionMode();
            if (trimToNull(profile.getCliProviderId()) == null) {
                throw new IllegalStateException("AI CLI provider must be configured.");
            }
            String argumentsTemplate = trimToNull(profile.getCliArgumentsTemplate());
            if (argumentsTemplate == null) {
                throw new IllegalStateException("AI CLI argument template must be configured.");
            }
            if (AiCliArgumentTemplate.requiresModel(argumentsTemplate)
                && (modelSelectionMode == AiModelSelectionMode.DEFAULT || model == null)) {
                throw new IllegalStateException(MISSING_MODEL_MESSAGE);
            }
            return decorate(profile, model, effectiveReasoningEffort(profile, reasoningEffortOverride),
                new LocalCliAiService(profileWithReasoning(profile, reasoningEffortOverride), effectiveSkillSupport));
        }
        String apiUrl = trimToNull(profile.getApiUrl());
        if (apiUrl == null) {
            return null;
        }
        if (isAnthropicMessagesEndpoint(apiUrl)) {
            String anthropicModel = trimToNull(profile.getModel());
            if (anthropicModel == null) {
                throw new IllegalStateException(MISSING_MODEL_MESSAGE);
            }
            return decorate(profile, anthropicModel, effectiveReasoningEffort(profile, reasoningEffortOverride), new AnthropicAiService(
                apiUrl,
                anthropicModel,
                apiKey,
                effectiveReasoningEffort(profile, reasoningEffortOverride),
                effectiveSkillSupport));
        }
        if (apiUrl.matches("^https?://[^/]+/?$") && !LocalLmModelResolver.isLocalLmStudioBaseUrl(apiUrl)) {
            return null;
        }
        String model = trimToNull(profile.getModel());
        AiModelSelectionMode modelSelectionMode = profile.getModelSelectionMode();
        String serviceModel = modelSelectionMode == AiModelSelectionMode.DEFAULT
            ? ""
            : (model != null ? model : "");
        String normalizedApiKey = apiKey != null ? apiKey.trim() : "";
        AiInternetAccessConfiguration effectiveConfig = internetConfig != null
            ? internetConfig
            : AiInternetAccessConfiguration.disabled();
        AiInternetAccessMode mode = profile.getInternetAccessMode();
        if (mode == null) {
            throw new IllegalStateException("AI internet access mode must be configured.");
        }
        requirePolicyAllowedInternetMode(mode);
        if (mode.usesLmStudioMcp()) {
            if (!apiUrl.matches("(?i).*/api/v1/chat/?$")) {
                throw new IllegalStateException("LM Studio MCP internet modes require the LM Studio native API endpoint /api/v1/chat.");
            }
            if (modelSelectionMode == AiModelSelectionMode.AUTO && !LocalLmModelResolver.canResolve(apiUrl)) {
                throw new IllegalStateException(LocalLmModelResolver.MISSING_MODEL_MESSAGE);
            }
            if (modelSelectionMode == AiModelSelectionMode.MANUAL && model == null) {
                throw new IllegalStateException(MISSING_MODEL_MESSAGE);
            }
            return decorate(profile, model, effectiveReasoningEffort(profile, reasoningEffortOverride), new LmStudioNativeAiService(
                apiUrl,
                serviceModel,
                modelSelectionMode,
                normalizedApiKey,
                effectiveReasoningEffort(profile, reasoningEffortOverride),
                effectiveConfig,
                effectiveSkillSupport));
        }
        TavilyWebSearchTool webSearchTool = null;
        if (mode.usesKorTTYTool()) {
            String tavilyApiKey = trimToNull(effectiveConfig.tavilyApiKey());
            if (tavilyApiKey == null) {
                throw new IllegalStateException("Tavily API key must be configured for internet mode " + mode + ".");
            }
            webSearchTool = new TavilyWebSearchTool(tavilyApiKey);
        }
        if (modelSelectionMode == AiModelSelectionMode.MANUAL && model == null) {
            throw new IllegalStateException(CLOUD_MODEL_REQUIRED_MESSAGE);
        }
        if (modelSelectionMode == AiModelSelectionMode.AUTO) {
            String normalizedApiUrl = normalizeOpenAiCompatibleChatCompletionsUrl(apiUrl);
            if (!LocalLmModelResolver.canResolve(normalizedApiUrl)) {
                throw new IllegalStateException(CLOUD_MODEL_REQUIRED_MESSAGE);
            }
        }
        return decorate(profile, model, effectiveReasoningEffort(profile, reasoningEffortOverride), new OpenAiCompatibleAiService(
            normalizeOpenAiCompatibleChatCompletionsUrl(apiUrl),
            serviceModel,
            modelSelectionMode,
            normalizedApiKey,
            effectiveReasoningEffort(profile, reasoningEffortOverride),
            webSearchTool,
            effectiveSkillSupport));
    }

    private static AiService decorate(
        AiProfile profile, String modelName, AiReasoningEffort effortInUse, AiService service) {
        // Applied on the raw transport before any wrapper, so the whole request — RAG retrieval and
        // preset optimization included — runs under exactly the timeout the user configured, and
        // under none at all when they configured nothing.
        if (service instanceof AiRequestTimeoutAware timeoutAware) {
            timeoutAware.setRequestTimeout(AiRequestTimeoutSupport.resolve(profile));
        }
        AiPromptPreset configured = profile != null ? profile.getPromptPreset() : AiPromptPreset.AUTO;
        AiPromptPreset resolved = AiPromptPresetSupport.resolve(configured, modelName);
        AiService optimized = resolved == AiPromptPreset.GENERIC
            ? service
            : new AiPromptPresetService(service, resolved);
        AiService composed;
        if (profile == null) {
            composed = optimized;
        } else {
            List<String> automaticStores = automaticallyAssignedRagStoresAllowed(profile)
                ? new RagRuntimeService().configuredStoreIds()
                : List.of();
            List<String> storeIds = ragStoreIdsForProfile(profile, automaticStores);
            composed = storeIds.isEmpty()
                ? optimized
                : new RagAugmentedAiService(optimized, storeIds, modelContextTokens(profile));
        }
        // Outermost wrapper: logs request submit/complete/fail (metadata only) so the whole request
        // lifecycle — including any RAG retrieval and preset optimization above — is timed as one.
        return LoggingAiService.wrap(composed, profile, modelName, effortInUse);
    }

    /**
     * Combines the profile's explicit stores with automatically role-assigned stores. Automatic
     * stores are accepted only for embedded or provably loopback HTTP inference; callers must pass
     * an empty automatic list for cloud and CLI profiles.
     */
    static List<String> ragStoreIdsForProfile(AiProfile profile, List<String> automaticStoreIds) {
        if (profile == null) {
            return List.of();
        }
        List<String> safeAutomaticStores = automaticallyAssignedRagStoresAllowed(profile)
            && automaticStoreIds != null
            ? automaticStoreIds
            : List.of();
        return java.util.stream.Stream
            .concat(profile.getRagStoreIds().stream(), safeAutomaticStores.stream())
            .filter(value -> value != null && !value.isBlank())
            .map(String::trim)
            .distinct()
            .toList();
    }

    static boolean automaticallyAssignedRagStoresAllowed(AiProfile profile) {
        if (profile == null) {
            return false;
        }
        if (profile.getConnectionMode().isEmbedded()) {
            return true;
        }
        if (profile.getConnectionMode() != AiConnectionMode.HTTP_API) {
            return false;
        }
        String apiUrl = trimToNull(profile.getApiUrl());
        if (apiUrl == null) {
            return false;
        }
        try {
            URI endpoint = URI.create(apiUrl);
            String host = trimToNull(endpoint.getHost());
            if (host == null) {
                return false;
            }
            String normalized = host.toLowerCase(Locale.ROOT);
            if (normalized.startsWith("[") && normalized.endsWith("]")) {
                normalized = normalized.substring(1, normalized.length() - 1);
            }
            if (normalized.endsWith(".")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            }
            if ("localhost".equals(normalized) || "::1".equals(normalized)
                || "0:0:0:0:0:0:0:1".equals(normalized)) {
                return true;
            }
            String[] octets = normalized.split("\\.", -1);
            if (octets.length != 4 || !"127".equals(octets[0])) {
                return false;
            }
            for (String octet : octets) {
                if (octet.isEmpty()) {
                    return false;
                }
                int value = Integer.parseInt(octet);
                if (value < 0 || value > 255) {
                    return false;
                }
            }
            return true;
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static int modelContextTokens(AiProfile profile) {
        try {
            if (profile.getConnectionMode() == AiConnectionMode.EMBEDDED_LLAMA_CPP) {
                return LlamaModelRegistry.inDirectory(KorTTYApplication.getConfigDirectory().resolve("llm"))
                    .find(profile.getEmbeddedModelId())
                    .map(LlamaModel::getContextSize)
                    .filter(value -> value > 0)
                    .orElse(16_000);
            }
            if (profile.getConnectionMode() == AiConnectionMode.EMBEDDED_MLX) {
                return MlxModelRegistry.inDirectory(KorTTYApplication.getConfigDirectory().resolve("llm"))
                    .find(profile.getEmbeddedModelId())
                    .map(MlxModel::getContextSize)
                    .filter(value -> value > 0)
                    .orElse(16_000);
            }
        } catch (RuntimeException ignored) {
            return 16_000;
        }
        return 16_000;
    }

    private static AiProfile profileWithReasoning(AiProfile profile, AiReasoningEffort reasoningEffortOverride) {
        if (reasoningEffortOverride == null) {
            return profile;
        }
        AiProfile copy = new AiProfile(profile);
        copy.setReasoningEffort(reasoningEffortOverride);
        return copy;
    }

    private static AiReasoningEffort effectiveReasoningEffort(
        AiProfile profile,
        AiReasoningEffort reasoningEffortOverride) {

        if (reasoningEffortOverride != null) {
            return reasoningEffortOverride;
        }
        AiReasoningEffort configured = profile != null ? profile.getReasoningEffort() : null;
        AiReasoningEffort effective = AiReasoningSupport.normalizeForProfile(profile);
        if (configured != null && configured != effective) {
            // The stored level is not among the ones this model/provider offers, so it is dropped
            // before the request. Without this line the request simply runs at a different level
            // than the profile shows, with nothing in the log to explain the difference.
            LOG.warn("AI reasoning effort {} is not available for profile '{}' (model '{}', {});"
                    + " using {} instead. Available: {}",
                configured,
                profile != null ? firstNonBlank(profile.getName(), profile.getId(), "unnamed") : "unnamed",
                profile != null ? firstNonBlank(profile.getEmbeddedModelId(), profile.getModel(), "auto") : "auto",
                profile != null && profile.getConnectionMode() != null ? profile.getConnectionMode() : "UNKNOWN",
                effective,
                AiReasoningSupport.availableEfforts(profile));
        }
        return effective;
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }

    /**
     * With {@code allow-user-models = false}, only models provisioned by the enterprise policy
     * (id prefix {@code policy-}) may be loaded — enforced here, at the single backend choke point,
     * so no picker or hand-edited profile can bypass it.
     */
    /**
     * Last line of defence for the {@code [rule.ai-profiles] allow-internet = false} policy.
     *
     * <p>{@link de.kortty.policy.PolicyClamp} already resets stored modes and the UI locks the
     * dropdown, but both operate on settings a determined user can hand-edit between two clamps.
     * Every service creation passes through here, so a forbidden mode cannot reach a transport at
     * all — the profile is refused rather than silently downgraded, because silently answering
     * without the web tool the user selected would misrepresent what the model actually did.</p>
     */
    private static void requirePolicyAllowedInternetMode(AiInternetAccessMode mode) {
        if (mode != null && mode.isEnabled()
            && !de.kortty.policy.PolicyManager.effective().aiInternetAllowed()) {
            throw new IllegalStateException(
                "Internet access for AI profiles is disabled by your organization's policy.");
        }
    }

    private static void requirePolicyAllowedModel(String embeddedModelId) {
        if (!de.kortty.policy.PolicyManager.effective().userModelsAllowed()
            && !embeddedModelId.startsWith(
                de.kortty.policy.PolicyRuntimeProvisioner.POLICY_MODEL_ID_PREFIX)) {
            throw new IllegalStateException(
                "Only local models provided by your organization's policy can be used.");
        }
    }

    private static String trimToNull(String value) {
        return value != null && !value.isBlank() ? value.trim() : null;
    }

    /** True for Anthropic's native Messages API endpoint (handled by {@link AnthropicAiService}). */
    static boolean isAnthropicMessagesEndpoint(String apiUrl) {
        if (apiUrl == null) {
            return false;
        }
        String lower = apiUrl.trim().toLowerCase(java.util.Locale.ROOT);
        try {
            URI uri = URI.create(lower);
            String host = uri.getHost();
            String path = uri.getPath() != null ? uri.getPath() : "";
            boolean anthropicHost = host != null && (host.equals("api.anthropic.com") || host.endsWith(".anthropic.com"));
            return (anthropicHost && path.contains("/v1/messages")) || path.endsWith("/v1/messages");
        } catch (Exception e) {
            return lower.contains("anthropic.com") && lower.contains("/v1/messages");
        }
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
        if (LocalLmModelResolver.isLocalLmStudioBaseUrl(withoutTrailingSlashes)) {
            return withoutTrailingSlashes + "/v1" + OPENAI_CHAT_COMPLETIONS_PATH;
        }
        return trimmed;
    }
}
