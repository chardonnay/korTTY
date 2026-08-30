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
    SnippetDiagramType diagramType) {

    public AiRequest {
        promptPreset = promptPreset != null ? promptPreset : AiPromptPreset.GENERIC;
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
            conversationContext, includeAiSkills, promptPreset, retrievedContext, null);
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
