package de.kortty.core;

import de.kortty.model.AiPromptPreset;

/**
 * Immutable request passed to an AI service.
 */
public record AiRequest(
    AiAction action,
    String selectedText,
    String connectionDisplayName,
    String responseLanguageCode,
    String userPrompt,
    String conversationContext,
    boolean includeAiSkills,
    AiPromptPreset promptPreset,
    String retrievedContext,
    CodeTextLanguage codeTextLanguage) {

    public AiRequest {
        promptPreset = promptPreset != null ? promptPreset : AiPromptPreset.GENERIC;
    }

    /**
     * The same request with an explicit contract for prose inside returned code. Kept apart from
     * {@link #responseLanguageCode()}, which governs the report and summary text a user reads in
     * korTTY's own interface — those legitimately follow the interface language even when the
     * script's comments must not.
     */
    public AiRequest withCodeTextLanguage(CodeTextLanguage codeTextLanguage) {
        return new AiRequest(action, selectedText, connectionDisplayName, responseLanguageCode,
            userPrompt, conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage);
    }

    public AiRequest(
        AiAction action,
        String selectedText,
        String connectionDisplayName,
        String responseLanguageCode,
        String userPrompt,
        String conversationContext,
        boolean includeAiSkills,
        AiPromptPreset promptPreset) {

        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt,
            conversationContext, includeAiSkills, promptPreset, null, null);
    }

    public AiRequest(
        AiAction action,
        String selectedText,
        String connectionDisplayName,
        String responseLanguageCode,
        String userPrompt,
        String conversationContext,
        boolean includeAiSkills) {

        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt,
            conversationContext, includeAiSkills, AiPromptPreset.GENERIC, null, null);
    }

    public AiRequest(
        AiAction action,
        String selectedText,
        String connectionDisplayName,
        String responseLanguageCode,
        String userPrompt,
        String conversationContext) {

        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt,
            conversationContext, true, AiPromptPreset.GENERIC, null, null);
    }

    public AiRequest(AiAction action, String selectedText, String connectionDisplayName, String responseLanguageCode) {
        this(action, selectedText, connectionDisplayName, responseLanguageCode, null, null);
    }

    public AiRequest(AiAction action, String selectedText, String connectionDisplayName, String responseLanguageCode, String userPrompt) {
        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt, null);
    }
}
