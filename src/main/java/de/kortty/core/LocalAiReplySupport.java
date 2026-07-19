package de.kortty.core;

import java.io.IOException;

/**
 * Shared post-processing for replies from korTTY's embedded local AI sidecars (llama.cpp, mlx-lm),
 * which deliver a reasoning model's chain-of-thought inline in the content.
 */
public final class LocalAiReplySupport {

    private LocalAiReplySupport() {
    }

    /**
     * Restores the transport contract the rest of the app relies on: thoughts in
     * {@link AiExecutionResult#reasoning()}, content clean. A reply that contains only reasoning
     * and no answer text raises {@link ReasoningOnlyReplyException} so callers can retry once —
     * the failure is stochastic at temperature &gt; 0.
     */
    public static AiExecutionResult separateInlineReasoning(AiExecutionResult result) throws IOException {
        if (result == null) {
            return null;
        }
        AiResponseSanitizer.InlineReasoning split = AiResponseSanitizer.extractInlineReasoning(result.content());
        if (split.reasoning() == null) {
            return result;
        }
        if (split.reasoningOnly()) {
            throw new ReasoningOnlyReplyException(
                "The local AI model spent its whole reply on reasoning and produced no answer. "
                    + "Retry, shorten the input, or raise the model's context size.");
        }
        String reasoning = result.reasoning() != null && !result.reasoning().isBlank()
            ? result.reasoning() + "\n\n" + split.reasoning()
            : split.reasoning();
        return new AiExecutionResult(split.content(), result.usage(), reasoning.isBlank() ? null : reasoning);
    }

    /** Thrown when a local reply contained only chain-of-thought and no answer text. */
    public static final class ReasoningOnlyReplyException extends IOException {
        public ReasoningOnlyReplyException(String message) {
            super(message);
        }
    }
}
