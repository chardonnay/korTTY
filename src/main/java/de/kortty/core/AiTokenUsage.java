package de.kortty.core;

/**
 * Token usage reported or estimated for one AI request.
 */
public record AiTokenUsage(long promptTokens, long completionTokens, long totalTokens, long cachedPromptTokens) {

    public AiTokenUsage(long promptTokens, long completionTokens, long totalTokens) {
        this(promptTokens, completionTokens, totalTokens, 0L);
    }

    /** {@code cachedPromptTokens}: the part of the prompt the endpoint served from its prefix cache, when it reports one. */
    public AiTokenUsage {
        promptTokens = Math.max(0L, promptTokens);
        completionTokens = Math.max(0L, completionTokens);
        totalTokens = Math.max(totalTokens, promptTokens + completionTokens);
        cachedPromptTokens = Math.max(0L, Math.min(cachedPromptTokens, promptTokens));
    }
}
