package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;

import java.net.URI;
import java.util.Locale;
import java.util.Objects;

/**
 * Decides whether a profile may send images ("vision"): explicit override first, then LM Studio
 * discovery, then conservative model-name/host heuristics. Mirrors the shape of
 * {@link AiReasoningSupport}.
 */
public final class AiVisionSupport {

    private AiVisionSupport() {
    }

    public static boolean isVisionCapable(AiProfile profile) {
        if (profile == null || !transportSupportsVision(profile)) {
            return false;
        }
        return switch (profile.getVisionSupport()) {
            case ENABLED -> true;
            case DISABLED -> false;
            case AUTO -> autoVisionCapable(profile);
        };
    }

    /**
     * Mirrors the {@link AiServiceFactory} dispatch: only the OpenAI-compatible and Anthropic HTTP
     * transports implement image input. Everything else — CLI, embedded, the LM Studio native
     * {@code /api/v1/chat} endpoint — is vision-incapable regardless of the override, so an
     * ENABLED profile on such a transport can never reach an {@code UnsupportedOperationException}.
     */
    static boolean transportSupportsVision(AiProfile profile) {
        if (profile.getConnectionMode() != AiConnectionMode.HTTP_API) {
            return false;
        }
        String apiUrl = profile.getApiUrl();
        if (apiUrl == null || apiUrl.isBlank()) {
            return false;
        }
        return profile.getInternetAccessMode() == null || !profile.getInternetAccessMode().usesLmStudioMcp();
    }

    private static boolean autoVisionCapable(AiProfile profile) {
        Boolean discovered = discoveredVisionCapable(profile);
        if (discovered != null) {
            return discovered;
        }
        return modelSuggestsVision(profile.getApiUrl(), profile.getModel());
    }

    /**
     * The discovered flag, or {@code null} when no discovery determined it. Guarded by the
     * reasoning discovery key: vision is recorded in the same discovery run, so the same
     * staleness rules apply (including the embedded-profile special case documented in
     * {@code AiReasoningSupport.discoveredEfforts}).
     */
    static Boolean discoveredVisionCapable(AiProfile profile) {
        if (profile.getDiscoveredVisionCapable() == null) {
            return null;
        }
        AiConnectionMode connectionMode = profile.getConnectionMode();
        if (connectionMode != null && connectionMode.isEmbedded()) {
            String discoveryKey = profile.getReasoningDiscoveryKey();
            return discoveryKey != null && !discoveryKey.isBlank()
                ? profile.getDiscoveredVisionCapable()
                : null;
        }
        if (!Objects.equals(profile.getReasoningDiscoveryKey(), AiReasoningSupport.discoveryKey(profile))) {
            return null;
        }
        return profile.getDiscoveredVisionCapable();
    }

    /**
     * Conservative name/host heuristics for endpoints whose model list carries no modality
     * metadata (OpenAI, Anthropic, and most OpenAI-compatible gateways expose none). False
     * negatives are expected for exotic models — the profile's explicit ENABLED override exists
     * exactly for those.
     */
    static boolean modelSuggestsVision(String apiUrl, String model) {
        if (isAnthropicHost(apiUrl)) {
            // Every Claude model the Messages API still serves accepts image blocks.
            return true;
        }
        String normalized = model != null ? model.trim().toLowerCase(Locale.ROOT) : "";
        if (normalized.isBlank()) {
            return false;
        }
        // Gateway ids ("openai/gpt-4o", "qwen/qwen2.5-vl-7b") prefix a vendor; prefix checks run
        // against the bare model name, substring checks against the full id.
        String bare = normalized.substring(normalized.lastIndexOf('/') + 1);
        if (normalized.contains("vision") || normalized.contains("multimodal")) {
            return true;
        }
        if (normalized.contains("-vl") || normalized.contains("vlm")) {
            return true;
        }
        if (bare.startsWith("claude-")) {
            return !bare.startsWith("claude-1") && !bare.startsWith("claude-2")
                && !bare.startsWith("claude-instant");
        }
        if (bare.startsWith("gpt-5") || bare.startsWith("chatgpt-4o")) {
            return true;
        }
        if (bare.startsWith("gpt-4o") || bare.startsWith("gpt-4.1") || bare.startsWith("gpt-4-turbo")) {
            return true;
        }
        if (bare.startsWith("o3") && !bare.startsWith("o3-mini")) {
            return true;
        }
        if (bare.startsWith("o4")) {
            return true;
        }
        if (bare.startsWith("o1") && !bare.startsWith("o1-mini")) {
            return true;
        }
        if (normalized.contains("gemini-") || normalized.contains("gemma-3") || normalized.contains("gemma3")) {
            return true;
        }
        return normalized.contains("pixtral")
            || normalized.contains("llava")
            || normalized.contains("internvl")
            || normalized.contains("minicpm-v")
            || normalized.contains("molmo")
            || normalized.contains("glm-4v")
            || normalized.contains("llama-4")
            || normalized.contains("llama4");
    }

    private static boolean isAnthropicHost(String apiUrl) {
        if (apiUrl == null || apiUrl.isBlank()) {
            return false;
        }
        try {
            String host = URI.create(apiUrl.trim()).getHost();
            return host != null && host.toLowerCase(Locale.ROOT).endsWith("anthropic.com");
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }
}
