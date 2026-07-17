package de.kortty.ai.mlx;

import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiPromptExecutionScope;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiRequest;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.core.AiSkillUsageTracker;
import de.kortty.core.LocalAiReplySupport;
import de.kortty.core.OpenAiCompatibleAiService;
import de.kortty.core.TavilyWebSearchTool;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiReasoningEffort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.List;
import java.util.function.Supplier;

/**
 * AI service that leases a model-specific mlx-lm sidecar (Apple Silicon) and delegates to
 * korTTY's existing OpenAI-compatible transport.
 */
public final class EmbeddedMlxAiService implements AiPromptService, AiSkillUsageTracker {

    private static final Logger logger = LoggerFactory.getLogger(EmbeddedMlxAiService.class);

    private final String modelId;
    private final AiReasoningEffort reasoningEffort;
    private final TavilyWebSearchTool webSearchTool;
    private final AiSkillPromptSupport skillPromptSupport;
    private final Supplier<MlxRuntimeManager> runtimeManagerSupplier;

    /** Creates a service using the lazily initialized application-wide runtime manager. */
    public EmbeddedMlxAiService(
        String modelId,
        AiReasoningEffort reasoningEffort,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport) {

        this(
            modelId,
            reasoningEffort,
            webSearchTool,
            skillPromptSupport,
            MlxRuntimeManager::getDefault);
    }

    /** Constructor for explicit lifecycle ownership and integration tests. */
    public EmbeddedMlxAiService(
        String modelId,
        AiReasoningEffort reasoningEffort,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport,
        MlxRuntimeManager runtimeManager) {

        this(modelId, reasoningEffort, webSearchTool, skillPromptSupport, () -> runtimeManager);
    }

    private EmbeddedMlxAiService(
        String modelId,
        AiReasoningEffort reasoningEffort,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport,
        Supplier<MlxRuntimeManager> runtimeManagerSupplier) {

        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Embedded MLX model id must be configured.");
        }
        if (runtimeManagerSupplier == null) {
            throw new IllegalArgumentException("MLX runtime manager must be configured.");
        }
        this.modelId = modelId.trim();
        this.reasoningEffort = reasoningEffort != null ? reasoningEffort : AiReasoningEffort.DISABLED;
        this.webSearchTool = webSearchTool;
        this.skillPromptSupport = skillPromptSupport != null ? skillPromptSupport : AiSkillPromptSupport.disabled();
        this.runtimeManagerSupplier = runtimeManagerSupplier;
    }

    public String getModelId() {
        return modelId;
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        return withDelegate(delegate -> separateInlineReasoning(delegate.execute(request)));
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

        return withDelegate(delegate ->
            separateInlineReasoning(delegate.executePrompt(systemPrompt, userPrompt, scope)));
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

        // The pinned mlx-lm ignores an OpenAI "response_format" field entirely, so JSON replies
        // rely on the prompt contract alone. Route through the without-response-format variant to
        // make that explicit instead of sending a silently dropped constraint.
        return withDelegate(delegate -> separateInlineReasoning(
            delegate.executeJsonPromptWithoutResponseFormat(systemPrompt, userPrompt, scope)));
    }

    @Override
    public AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt) throws Exception {
        return executeJsonPromptWithoutResponseFormat(
            systemPrompt, userPrompt, AiPromptExecutionScope.AUTONOMOUS);
    }

    @Override
    public AiExecutionResult executeJsonPromptWithoutResponseFormat(
        String systemPrompt,
        String userPrompt,
        AiPromptExecutionScope scope) throws Exception {

        return withDelegate(delegate -> separateInlineReasoning(
            delegate.executeJsonPromptWithoutResponseFormat(systemPrompt, userPrompt, scope)));
    }

    @Override
    public boolean testConnection() {
        try {
            // mlx-lm loads the model lazily on the first /v1 request, so the delegate's fixed
            // 30-second connection-test timeout would fail every cold sidecar of a larger model.
            // A real, untimed prompt warms the model and proves the whole authenticated route.
            return withDelegate(delegate -> {
                AiExecutionResult result = delegate.executePrompt(
                    "You are a connection test.", "Reply with the single word OK.");
                return result != null && result.content() != null && !result.content().isBlank();
            });
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<AiSkillPromptSupport.SkillUsage> drainSkillUsages() {
        return skillPromptSupport.drainSkillUsages();
    }

    private <T> T withDelegate(DelegateCall<T> call) throws Exception {
        MlxRuntimeManager manager = runtimeManagerSupplier.get();
        if (manager == null) {
            throw new MlxRuntimeException("MLX runtime manager is not available.");
        }
        try {
            return callWithLease(manager, call);
        } catch (Exception e) {
            // A retried local call is not billed, and both retried failure shapes are stochastic
            // at temperature > 0: a sidecar 5xx and a reasoning model spending its whole reply
            // inside <think>. Client errors (4xx) are deterministic and not retried; the retry
            // re-runs the whole call, including any web-search tool rounds.
            if (!isRetryableLocalFailure(e)) {
                throw e;
            }
            logger.warn("Local mlx-lm request failed ({}); retrying once.", e.getMessage());
            return callWithLease(manager, call);
        }
    }

    private <T> T callWithLease(MlxRuntimeManager manager, DelegateCall<T> call) throws Exception {
        try (MlxRuntimeManager.RuntimeLease lease = manager.acquire(modelId)) {
            // The request body must NOT name a model: mlx-lm loads the request's "model" value as
            // a path or Hugging Face id, and the sidecar runs offline with the one directory it
            // was started with. DEFAULT selection keeps the field out of the body entirely, which
            // selects the sidecar's loaded model.
            OpenAiCompatibleAiService delegate = new OpenAiCompatibleAiService(
                lease.endpoint().toString(),
                "",
                AiModelSelectionMode.DEFAULT,
                lease.apiKey(),
                reasoningEffort,
                webSearchTool,
                skillPromptSupport);
            return call.invoke(delegate);
        }
    }

    private static boolean isRetryableLocalFailure(Exception e) {
        if (e instanceof OpenAiCompatibleAiService.AiApiException apiError) {
            return apiError.statusCode() >= 500;
        }
        // A refused connection means the sidecar exited (idle self-exit racing a new request)
        // before any work started; re-acquiring the lease relaunches it with a fresh key/port.
        // Empty replies are a stochastic small-model failure worth exactly one more attempt.
        return e instanceof LocalAiReplySupport.ReasoningOnlyReplyException
            || e instanceof OpenAiCompatibleAiService.EmptyResponseException
            || e instanceof java.net.ConnectException;
    }

    /**
     * mlx-lm applies the model's chat template but never extracts reasoning, so a reasoning
     * model's chain-of-thought arrives inline in the content; the shared support restores the
     * transport contract (thoughts in {@link AiExecutionResult#reasoning()}).
     */
    private static AiExecutionResult separateInlineReasoning(AiExecutionResult result) throws IOException {
        return LocalAiReplySupport.separateInlineReasoning(result);
    }

    @FunctionalInterface
    private interface DelegateCall<T> {
        T invoke(OpenAiCompatibleAiService delegate) throws Exception;
    }
}
