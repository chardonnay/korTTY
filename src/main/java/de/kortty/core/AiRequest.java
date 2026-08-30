package de.kortty.core;

import de.kortty.model.AiPromptPreset;
import de.kortty.model.SnippetDiagramType;

/**
 * Immutable request passed to an AI service.
 *
 * <p>{@code diagramType} is only meaningful for {@link AiAction#GENERATE_SNIPPET_MERMAID}; a
 * {@code null} value means the default logical-structure flowchart.</p>
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
    CodeTextLanguage codeTextLanguage,
    SnippetDiagramType diagramType) {

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
            codeTextLanguage, diagramType);
    }

    /** The same request for one diagram family; only {@code GENERATE_SNIPPET_MERMAID} uses it. */
    public AiRequest withDiagramType(SnippetDiagramType diagramType) {
        return new AiRequest(action, selectedText, connectionDisplayName, responseLanguageCode,
            userPrompt, conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType);
    }

    public AiRequest(
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

        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt,
            conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, null);
    }

    public AiRequest(
        AiAction action,
        String selectedText,
        String connectionDisplayName,
        String responseLanguageCode,
        String userPrompt,
        String conversationContext,
        boolean includeAiSkills,
        AiPromptPreset promptPreset,
        String retrievedContext) {

        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt,
            conversationContext, includeAiSkills, promptPreset, retrievedContext, null, null);
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
            conversationContext, includeAiSkills, promptPreset, null, null, null);
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
            conversationContext, includeAiSkills, AiPromptPreset.GENERIC, null, null, null);
    }

    public AiRequest(
        AiAction action,
        String selectedText,
        String connectionDisplayName,
        String responseLanguageCode,
        String userPrompt,
        String conversationContext) {

        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt,
            conversationContext, true, AiPromptPreset.GENERIC, null, null, null);
    }

    public AiRequest(AiAction action, String selectedText, String connectionDisplayName, String responseLanguageCode) {
        this(action, selectedText, connectionDisplayName, responseLanguageCode, null, null);
    }

    public AiRequest(AiAction action, String selectedText, String connectionDisplayName, String responseLanguageCode, String userPrompt) {
        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt, null);
    }
}
