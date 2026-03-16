package de.kortty.core;

/**
 * Result of one AI request including optional token usage data.
 */
public record AiExecutionResult(String content, AiTokenUsage usage) {
}
