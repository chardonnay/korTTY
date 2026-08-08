package de.kortty.core;

/**
 * Result of one AI request including optional token usage data and the model's optional
 * reasoning / chain-of-thought text.
 *
 * <p>{@code reasoning} holds the model's extended thinking when the provider exposes it
 * (Anthropic {@code thinking} blocks, OpenAI-compatible {@code reasoning_content} /
 * {@code reasoning}, LM Studio {@code reasoning} output items, or {@code <think>} blocks from
 * a local CLI). It is display-only and never part of {@link #content()}. It is {@code null}
 * when the model produced no reasoning. {@code outputTruncated} is true when the provider reports
 * that generation stopped at the configured output-token limit.
 */
public record AiExecutionResult(
    String content,
    AiTokenUsage usage,
    String reasoning,
    boolean outputTruncated) {

    /**
     * Convenience constructor for a complete result with optional reasoning text.
     */
    public AiExecutionResult(String content, AiTokenUsage usage, String reasoning) {
        this(content, usage, reasoning, false);
    }

    /**
     * Convenience constructor for the common case of a result without separate reasoning text.
     */
    public AiExecutionResult(String content, AiTokenUsage usage) {
        this(content, usage, null, false);
    }
}
