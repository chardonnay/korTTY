package de.kortty.ai.llama;

import de.kortty.core.AiExecutionResult;
import de.kortty.core.AiPromptExecutionScope;
import de.kortty.core.AiPromptService;
import de.kortty.core.AiRequest;
import de.kortty.core.AiSkillPromptSupport;
import de.kortty.core.AiSkillUsageTracker;
import de.kortty.core.OpenAiCompatibleAiService;
import de.kortty.core.TavilyWebSearchTool;
import de.kortty.model.AiModelSelectionMode;
import de.kortty.model.AiReasoningEffort;

import java.util.List;
import java.util.function.Supplier;

/**
 * AI service that leases a model-specific llama-server and delegates to korTTY's existing
 * OpenAI-compatible transport.
 */
public final class EmbeddedLlamaAiService implements AiPromptService, AiSkillUsageTracker {

    private final String modelId;
    private final AiReasoningEffort reasoningEffort;
    private final TavilyWebSearchTool webSearchTool;
    private final AiSkillPromptSupport skillPromptSupport;
    private final Supplier<LlamaRuntimeManager> runtimeManagerSupplier;

    /** Creates a service using the lazily initialized application-wide runtime manager. */
    public EmbeddedLlamaAiService(
        String modelId,
        AiReasoningEffort reasoningEffort,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport) {

        this(
            modelId,
            reasoningEffort,
            webSearchTool,
            skillPromptSupport,
            LlamaRuntimeManager::getDefault);
    }

    /** Constructor for explicit lifecycle ownership and integration tests. */
    public EmbeddedLlamaAiService(
        String modelId,
        AiReasoningEffort reasoningEffort,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport,
        LlamaRuntimeManager runtimeManager) {

        this(modelId, reasoningEffort, webSearchTool, skillPromptSupport, () -> runtimeManager);
    }

    private EmbeddedLlamaAiService(
        String modelId,
        AiReasoningEffort reasoningEffort,
        TavilyWebSearchTool webSearchTool,
        AiSkillPromptSupport skillPromptSupport,
        Supplier<LlamaRuntimeManager> runtimeManagerSupplier) {

        if (modelId == null || modelId.isBlank()) {
            throw new IllegalArgumentException("Embedded llama.cpp model id must be configured.");
        }
        if (runtimeManagerSupplier == null) {
            throw new IllegalArgumentException("llama.cpp runtime manager must be configured.");
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
        return withDelegate(delegate -> delegate.execute(request));
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

        return withDelegate(delegate -> delegate.executePrompt(systemPrompt, userPrompt, scope));
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

        return withDelegate(delegate -> delegate.executeJsonPrompt(systemPrompt, userPrompt, scope));
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

        return withDelegate(delegate ->
            delegate.executeJsonPromptWithoutResponseFormat(systemPrompt, userPrompt, scope));
    }

    @Override
    public boolean testConnection() {
        try {
            return withDelegate(OpenAiCompatibleAiService::testConnection);
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public List<AiSkillPromptSupport.SkillUsage> drainSkillUsages() {
        return skillPromptSupport.drainSkillUsages();
    }

    private <T> T withDelegate(DelegateCall<T> call) throws Exception {
        LlamaRuntimeManager manager = runtimeManagerSupplier.get();
        if (manager == null) {
            throw new LlamaRuntimeException("llama.cpp runtime manager is not available.");
        }
        try (LlamaRuntimeManager.RuntimeLease lease = manager.acquire(modelId)) {
            if (lease.purpose() != LlamaModelPurpose.CHAT) {
                throw new LlamaRuntimeException(
                    "Local model " + modelId + " is configured for embeddings, not chat generation.");
            }
            OpenAiCompatibleAiService delegate = new OpenAiCompatibleAiService(
                lease.endpoint().toString(),
                lease.modelAlias(),
                AiModelSelectionMode.MANUAL,
                lease.apiKey(),
                reasoningEffort,
                webSearchTool,
                skillPromptSupport);
            return call.invoke(delegate);
        }
    }

    @FunctionalInterface
    private interface DelegateCall<T> {
        T invoke(OpenAiCompatibleAiService delegate) throws Exception;
    }
}
