package de.kortty.core;

import de.kortty.model.AiPromptPreset;
import de.kortty.rag.CancellationToken;
import de.kortty.rag.RagContextBuilder;
import de.kortty.rag.RagRuntimeService;

import java.util.List;

/** Adds local, bounded RAG context to ordinary AI actions; direct agent prompts remain opt-in. */
final class RagAugmentedAiService implements AiPromptService, AiSkillUsageTracker {

    private static final int MAX_QUERY_CHARS = 16_000;

    private final AiService delegate;
    private final List<String> storeIds;
    private final int modelContextTokens;
    private final ContextRetriever contextRetriever;

    RagAugmentedAiService(AiService delegate, List<String> storeIds, int modelContextTokens) {
        this(delegate, storeIds, modelContextTokens, new RagRuntimeService());
    }

    RagAugmentedAiService(
        AiService delegate,
        List<String> storeIds,
        int modelContextTokens,
        RagRuntimeService ragRuntime) {

        this(delegate, storeIds, modelContextTokens, ragRuntime::retrieve);
    }

    RagAugmentedAiService(
        AiService delegate,
        List<String> storeIds,
        int modelContextTokens,
        ContextRetriever contextRetriever) {

        this.delegate = delegate;
        this.storeIds = storeIds != null ? List.copyOf(storeIds) : List.of();
        this.modelContextTokens = Math.max(1, modelContextTokens);
        this.contextRetriever = contextRetriever;
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        if (request == null || storeIds.isEmpty()) {
            return delegate.execute(request);
        }
        String query = retrievalQuery(request);
        RagContextBuilder.RagContext context = contextRetriever.retrieve(
            storeIds, query, modelContextTokens,
            request.action() != null ? request.action().workload() : null,
            false,
            CancellationToken.NONE);
        AiRequest augmented = new AiRequest(
            request.action(), request.selectedText(), request.connectionDisplayName(),
            request.responseLanguageCode(), request.userPrompt(), request.conversationContext(),
            request.includeAiSkills(), request.promptPreset(), context.text());
        return delegate.execute(augmented);
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

        return promptDelegate().executePrompt(
            scopedSystemPrompt(systemPrompt, userPrompt, scope), userPrompt, scope);
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

        return promptDelegate().executeJsonPrompt(
            scopedSystemPrompt(systemPrompt, userPrompt, scope), userPrompt, scope);
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

        return promptDelegate().executeJsonPromptWithoutResponseFormat(
            scopedSystemPrompt(systemPrompt, userPrompt, scope), userPrompt, scope);
    }

    @Override
    public boolean testConnection() {
        return delegate.testConnection();
    }

    @Override
    public List<AiSkillPromptSupport.SkillUsage> drainSkillUsages() {
        return delegate instanceof AiSkillUsageTracker tracker ? tracker.drainSkillUsages() : List.of();
    }

    private AiPromptService promptDelegate() {
        if (delegate instanceof AiPromptService service) {
            return service;
        }
        throw new IllegalStateException("Configured AI service does not support direct prompts.");
    }

    private static String retrievalQuery(AiRequest request) {
        String query = request.userPrompt() != null && !request.userPrompt().isBlank()
            ? request.userPrompt().trim()
            : request.selectedText() != null ? request.selectedText().trim() : "";
        return query.length() <= MAX_QUERY_CHARS ? query : query.substring(0, MAX_QUERY_CHARS);
    }

    private String scopedSystemPrompt(
        String systemPrompt,
        String userPrompt,
        AiPromptExecutionScope scope) throws Exception {

        AiPromptExecutionScope effectiveScope = AiPromptExecutionScope.normalize(scope);
        RagContextBuilder.RagContext context = contextRetriever.retrieve(
            storeIds,
            userPrompt != null ? userPrompt : "",
            modelContextTokens,
            effectiveScope.workload(),
            effectiveScope.autonomous(),
            CancellationToken.NONE);
        return context.text().isBlank()
            ? systemPrompt
            : (systemPrompt != null ? systemPrompt.trim() : "") + "\n\n" + context.text();
    }

    @FunctionalInterface
    interface ContextRetriever {
        RagContextBuilder.RagContext retrieve(
            List<String> storeIds,
            String query,
            int modelContextTokens,
            de.kortty.model.AiWorkload workload,
            boolean autonomousOnly,
            CancellationToken cancellation) throws Exception;
    }
}
