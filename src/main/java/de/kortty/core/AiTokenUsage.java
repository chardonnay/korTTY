package de.kortty.core;

/**
 * Token usage reported or estimated for one AI request.
 */
public record AiTokenUsage(long promptTokens, long completionTokens, long totalTokens) {

    public AiTokenUsage {
        promptTokens = Math.max(0L, promptTokens);
        completionTokens = Math.max(0L, completionTokens);
        totalTokens = Math.max(totalTokens, promptTokens + completionTokens);
    }
}
