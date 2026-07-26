package de.kortty.core;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Wraps an {@link AiPromptService} to accumulate real token usage across calls, for benchmark
 * tools that need actual prompt/completion token counts rather than a character-count proxy.
 *
 * <p>Only the entry points {@link LocalAiTranslationService} and {@link GuideTranslationGenerator}
 * actually use ({@link #execute}, {@link #testConnection}, the two-argument
 * {@code executePrompt}/{@code executeJsonPrompt}) are overridden; the scoped three-argument
 * variants fall through to those via the interface's default methods.
 */
final class TokenMeteringAiPromptService implements AiPromptService {

    private final AiPromptService delegate;
    private final AtomicLong promptTokens = new AtomicLong();
    private final AtomicLong completionTokens = new AtomicLong();
    private final AtomicLong totalTokens = new AtomicLong();
    private final AtomicLong callsWithUsage = new AtomicLong();
    private final AtomicLong callsWithoutUsage = new AtomicLong();

    TokenMeteringAiPromptService(AiPromptService delegate) {
        this.delegate = delegate;
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        return record(delegate.execute(request));
    }

    @Override
    public boolean testConnection() {
        return delegate.testConnection();
    }

    @Override
    public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception {
        return record(delegate.executePrompt(systemPrompt, userPrompt));
    }

    @Override
    public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws Exception {
        return record(delegate.executeJsonPrompt(systemPrompt, userPrompt));
    }

    private AiExecutionResult record(AiExecutionResult result) {
        AiTokenUsage usage = result != null ? result.usage() : null;
        if (usage == null || usage.totalTokens() <= 0) {
            callsWithoutUsage.incrementAndGet();
        } else {
            callsWithUsage.incrementAndGet();
            promptTokens.addAndGet(usage.promptTokens());
            completionTokens.addAndGet(usage.completionTokens());
            totalTokens.addAndGet(usage.totalTokens());
        }
        return result;
    }

    long promptTokens() {
        return promptTokens.get();
    }

    long completionTokens() {
        return completionTokens.get();
    }

    long totalTokens() {
        return totalTokens.get();
    }

    /** True if at least one call reported no usable token usage (model/backend does not report it). */
    boolean anyCallMissingUsage() {
        return callsWithoutUsage.get() > 0;
    }

    long callsWithUsage() {
        return callsWithUsage.get();
    }
}
