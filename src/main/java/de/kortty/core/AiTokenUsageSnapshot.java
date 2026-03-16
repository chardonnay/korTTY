package de.kortty.core;

import java.time.LocalDate;

/**
 * Computed token-usage status for one AI profile.
 */
public record AiTokenUsageSnapshot(
    long usedPromptTokens,
    long usedCompletionTokens,
    long usedTotalTokens,
    long maxTokens,
    long remainingTokens,
    LocalDate cycleStartDate,
    LocalDate nextResetDate,
    AiTokenWarningLevel warningLevel,
    boolean unlimited) {
}
