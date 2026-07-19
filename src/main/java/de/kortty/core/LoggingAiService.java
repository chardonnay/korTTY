package de.kortty.core;

import de.kortty.model.AiConnectionMode;
import de.kortty.model.AiProfile;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Outermost AI service decorator that records the lifecycle of every AI request in the log.
 *
 * <p>It emits one concise INFO line when a request is submitted and a second when it completes or
 * fails — across all execution paths: the chat {@link #execute(AiRequest)} path and the autonomous
 * {@code executePrompt} / {@code executeJsonPrompt} paths used by the agent, planning, workflow,
 * guide, and job-scheduler flows. Only request/response <em>metadata</em> is logged — the action,
 * provider, model, approximate input size, duration, token usage, and whether the model returned
 * reasoning — and never the prompt or response text, so ordinary INFO logging can never leak
 * conversation content.
 *
 * <p>It implements {@link AiPromptService} and {@link AiSkillUsageTracker} so it stays a fully
 * transparent decorator: the agent, planning, workflow, guide, profile-wizard, and job-scheduler
 * paths all check {@code instanceof AiPromptService} on the factory result, and skill-usage
 * tracking checks {@code instanceof AiSkillUsageTracker}. A decorator that implemented only
 * {@link AiService} would make every one of those checks fail and, for the terminal agent, surface
 * as a spurious "no AI profile configured" prompt.
 *
 * <p>{@link #testConnection()} is delegated silently: a connection test is configuration
 * validation, not a submitted request, and logging it would only add noise.
 */
final class LoggingAiService implements AiPromptService, AiSkillUsageTracker {

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
        return logged(action, approximateInputChars(request), () -> delegate.execute(request));
    }

    @Override
    public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception {
        return executePrompt(systemPrompt, userPrompt, AiPromptExecutionScope.AUTONOMOUS);
    }

    @Override
    public AiExecutionResult executePrompt(
        String systemPrompt,
        String userPrompt,
        AiPromptExecutionScope scope) throws Exception {

        return logged("prompt", promptChars(systemPrompt, userPrompt),
            () -> promptDelegate().executePrompt(systemPrompt, userPrompt, scope));
    }

    @Override
    public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws Exception {
        return executeJsonPrompt(systemPrompt, userPrompt, AiPromptExecutionScope.AUTONOMOUS);
    }

    @Override
    public AiExecutionResult executeJsonPrompt(
        String systemPrompt,
        String userPrompt,
        AiPromptExecutionScope scope) throws Exception {

        return logged("json-prompt", promptChars(systemPrompt, userPrompt),
            () -> promptDelegate().executeJsonPrompt(systemPrompt, userPrompt, scope));
    }

    @Override
    public AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt)
        throws Exception {
        return executeJsonPromptWithoutResponseFormat(systemPrompt, userPrompt, AiPromptExecutionScope.AUTONOMOUS);
    }

    @Override
    public AiExecutionResult executeJsonPromptWithoutResponseFormat(
        String systemPrompt,
        String userPrompt,
        AiPromptExecutionScope scope) throws Exception {

        return logged("json-prompt-nf", promptChars(systemPrompt, userPrompt),
            () -> promptDelegate().executeJsonPromptWithoutResponseFormat(systemPrompt, userPrompt, scope));
    }

    @Override
    public boolean testConnection() {
        return delegate.testConnection();
    }

    @Override
    public List<AiSkillPromptSupport.SkillUsage> drainSkillUsages() {
        return delegate instanceof AiSkillUsageTracker tracker ? tracker.drainSkillUsages() : List.of();
    }

    /** The wrapped service. Lets callers (and tests) see the concrete service through this decorator. */
    AiService delegate() {
        return delegate;
    }

    private AiPromptService promptDelegate() {
        if (delegate instanceof AiPromptService service) {
            return service;
        }
        throw new IllegalStateException("Configured AI service does not support direct prompts.");
    }

    /** A single AI call; wrapped so every execution path logs uniformly. */
    @FunctionalInterface
    private interface Call {
        AiExecutionResult run() throws Exception;
    }

    private AiExecutionResult logged(String action, int inputChars, Call call) throws Exception {
        LOG.info("AI request sent: action={} provider={} model='{}' profile='{}' inputChars={}",
            action, provider, model, profile, inputChars);
        long startNanos = System.nanoTime();
        try {
            AiExecutionResult result = call.run();
            LOG.info("AI request done: action={} model='{}' in {} ms, {}, reasoning={}",
                action, model, elapsedMillis(startNanos), tokenSummary(result), reasoningFlag(result));
            return result;
        } catch (Exception failure) {
            LOG.warn("AI request failed: action={} provider={} model='{}' after {} ms: {}",
                action, provider, model, elapsedMillis(startNanos), failure.toString());
            throw failure;
        }
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

    private static int promptChars(String systemPrompt, String userPrompt) {
        return length(systemPrompt) + length(userPrompt);
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
