package de.kortty.core;

import de.kortty.model.AiPromptPreset;

import java.util.List;

/** Decorates every prompt path with one resolved model-family compatibility preset. */
final class AiPromptPresetService implements AiPromptService, AiSkillUsageTracker {

    private final AiService delegate;
    private final AiPromptPreset preset;

    AiPromptPresetService(AiService delegate, AiPromptPreset preset) {
        this.delegate = delegate;
        this.preset = preset != null ? preset : AiPromptPreset.GENERIC;
    }

    @Override
    public AiExecutionResult execute(AiRequest request) throws Exception {
        if (request == null) {
            return delegate.execute(null);
        }
        AiRequest optimized = new AiRequest(
            request.action(), request.selectedText(), request.connectionDisplayName(),
            request.responseLanguageCode(), request.userPrompt(), request.conversationContext(),
            request.includeAiSkills(), preset, request.retrievedContext());
        return delegate.execute(optimized);
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
            AiPromptPresetSupport.append(systemPrompt, preset), userPrompt, scope);
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
            AiPromptPresetSupport.append(systemPrompt, preset), userPrompt, scope);
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
            AiPromptPresetSupport.append(systemPrompt, preset), userPrompt, scope);
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

    AiService delegate() {
        return delegate;
    }

    AiPromptPreset preset() {
        return preset;
    }
}
