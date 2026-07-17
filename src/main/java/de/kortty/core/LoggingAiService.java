package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outermost {@link AiService} decorator that records the lifecycle of every AI request in the log.
 *
 * <p>It emits one concise INFO line when a request is submitted and a second when it completes or
 * fails, so {@code kortty.log} shows whether the AI is doing anything, which model handled it, and
 * how long it took. Only request/response <em>metadata</em> is logged — the action, provider,
 * model, approximate input size, duration, token usage, and whether the model returned separate
 * reasoning — and never the prompt or response text, so enabling ordinary INFO logging can never
 * leak conversation content.
 *
 * <p>{@link #testConnection()} is delegated silently: a connection test is configuration
 * validation, not a submitted request, and logging it would only add noise.
 */
final class LoggingAiService implements AiService {

    private static final Logger LOG = LoggerFactory.getLogger(LoggingAiService.class);

    private final AiService delegate;
    private final String provider;
    private final String model;
    private final String profile;

    private LoggingAiService(AiService delegate, String provider, String model, String profile) {
        this.delegate = delegate;
        this.provider = provider;
        this.model = model;
        this.profile = profile;
    }

    /**
     * Wraps {@code delegate} in a logging decorator, deriving stable provider/model/profile labels
     * from the profile. Returns {@code null} when {@code delegate} is {@code null} so callers can
     * pass a possibly-absent service straight through.
     */
    static AiService wrap(AiService delegate, AiProfile profile, String modelName) {
        if (delegate == null) {
            return null;
        }
        AiConnectionMode mode = profile != null ? profile.getConnectionMode() : null;
        String providerLabel = mode != null ? mode.name() : "UNKNOWN";
        String modelLabel = firstNonBlank(
            modelName,
            profile != null ? profile.getEmbeddedModelId() : null,
            profile != null ? profile.getModel() : null,
            "auto");
        String profileLabel = profile != null
            ? firstNonBlank(profile.getName(), profile.getId(), "unnamed")
            : "unnamed";
        return new LoggingAiService(delegate, providerLabel, modelLabel, profileLabel);
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        String action = request != null && request.action() != null ? request.action().name() : "UNKNOWN";
        LOG.info("AI request sent: action={} provider={} model='{}' profile='{}' inputChars={}",
            action, provider, model, profile, approximateInputChars(request));
        long startNanos = System.nanoTime();
        try {
            AiExecutionResult result = delegate.execute(request);
            LOG.info("AI request done: action={} model='{}' in {} ms, {}, reasoning={}",
                action, model, elapsedMillis(startNanos), tokenSummary(result), reasoningFlag(result));
            return result;
        } catch (Exception failure) {
            LOG.warn("AI request failed: action={} provider={} model='{}' after {} ms: {}",
                action, provider, model, elapsedMillis(startNanos), failure.toString());
            throw failure;
        }
    }

    @Override
    public boolean testConnection() {
        return delegate.testConnection();
    }

    /** The wrapped service. Lets callers (and tests) see the concrete service through this decorator. */
    AiService delegate() {
        return delegate;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }

    /** Sum of the request's textual field lengths — a size hint only, never the content itself. */
    private static int approximateInputChars(AiRequest request) {
        if (request == null) {
            return 0;
        }
        return length(request.userPrompt())
            + length(request.selectedText())
            + length(request.conversationContext())
            + length(request.retrievedContext());
    }

    private static int length(String value) {
        return value != null ? value.length() : 0;
    }

    private static String tokenSummary(AiExecutionResult result) {
        AiTokenUsage usage = result != null ? result.usage() : null;
        if (usage == null || usage.totalTokens() <= 0L) {
            return "tokens=n/a";
        }
        return "tokens=" + usage.totalTokens()
            + " (prompt " + usage.promptTokens() + ", completion " + usage.completionTokens() + ")";
    }

    private static String reasoningFlag(AiExecutionResult result) {
        return result != null && result.reasoning() != null && !result.reasoning().isBlank() ? "yes" : "no";
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value.trim();
            }
        }
        return "";
    }
}
