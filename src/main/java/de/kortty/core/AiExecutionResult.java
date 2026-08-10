package de.kortty.core;

/**
 * Result of one AI request including optional token usage data and the model's optional
 * reasoning / chain-of-thought text.
 *
 * <p>{@code reasoning} holds the model's extended thinking when the provider exposes it
 * (Anthropic {@code thinking} blocks, OpenAI-compatible {@code reasoning_content} /
 * {@code reasoning}, LM Studio {@code reasoning} output items, or {@code <think>} blocks from
 * a local CLI). It is display-only and never part of {@link #content()}. It is {@code null}
 * when the model produced no reasoning.
 *
 * <p>{@code outputTruncated} marks any answer that is known to be incomplete, and is what the
 * fail-closed callers act on. {@code streamInterrupted} narrows the reason: the connection was cut
 * mid-answer rather than the model stopping at its output-token limit. Both are incomplete, but
 * only an interruption is transient and worth retrying — a token limit recurs deterministically.
 * An interrupted result therefore always sets {@code outputTruncated} as well, so a caller that
 * only asks "is this complete?" keeps its existing behaviour.
 */
public record AiExecutionResult(
    String content,
    AiTokenUsage usage,
    String reasoning,
    boolean outputTruncated,
    boolean streamInterrupted) {

    /**
     * Convenience constructor for a result whose incompleteness, if any, is not an interruption.
     */
    public AiExecutionResult(String content, AiTokenUsage usage, String reasoning, boolean outputTruncated) {
        this(content, usage, reasoning, outputTruncated, false);
    }

    /**
     * Convenience constructor for a complete result with optional reasoning text.
     */
    public AiExecutionResult(String content, AiTokenUsage usage, String reasoning) {
        this(content, usage, reasoning, false, false);
    }

    /**
     * Convenience constructor for the common case of a result without separate reasoning text.
     */
    public AiExecutionResult(String content, AiTokenUsage usage) {
        this(content, usage, null, false, false);
    }
}
