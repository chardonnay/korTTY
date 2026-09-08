package de.kortty.core;

import de.kortty.model.AiPromptPreset;
import de.kortty.model.SnippetDiagramType;

/**
 * Immutable request passed to an AI service.
 *
 * <p>{@code diagramType} is only meaningful for {@link AiAction#GENERATE_SNIPPET_MERMAID}; a
 * {@code null} value means the default logical-structure flowchart. {@code asciiArtOptions} is only
 * meaningful for {@link AiAction#GENERATE_ASCII_ART}; {@code null} means the default SVG contract on
 * the default picture size.</p>
 *
 * <p>Wrappers that derive a request from another one must use the {@code with…} methods rather than
 * a shorter constructor, so that action-specific components such as {@code diagramType} and
 * {@code asciiArtOptions} survive the copy.</p>
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
    SnippetDiagramType diagramType,
    AsciiArtRequestOptions asciiArtOptions) {

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
            codeTextLanguage, diagramType, asciiArtOptions);
    }

    /** The same request for one diagram family; only {@code GENERATE_SNIPPET_MERMAID} uses it. */
    public AiRequest withDiagramType(SnippetDiagramType diagramType) {
        return new AiRequest(action, selectedText, connectionDisplayName, responseLanguageCode,
            userPrompt, conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, asciiArtOptions);
    }

    /** The same request with the ASCII-art options; only {@code GENERATE_ASCII_ART} uses them. */
    public AiRequest withAsciiArtOptions(AsciiArtRequestOptions asciiArtOptions) {
        return new AiRequest(action, selectedText, connectionDisplayName, responseLanguageCode,
            userPrompt, conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, asciiArtOptions);
    }

    /** The same request with the model-specific prompt preset resolved by the profile. */
    public AiRequest withPromptPreset(AiPromptPreset promptPreset) {
        return new AiRequest(action, selectedText, connectionDisplayName, responseLanguageCode,
            userPrompt, conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, asciiArtOptions);
    }

    /** The same request with knowledge-store context retrieved for it. */
    public AiRequest withRetrievedContext(String retrievedContext) {
        return new AiRequest(action, selectedText, connectionDisplayName, responseLanguageCode,
            userPrompt, conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, asciiArtOptions);
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
        CodeTextLanguage codeTextLanguage,
        SnippetDiagramType diagramType) {

        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt,
            conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, null);
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
            codeTextLanguage, null, null);
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
            conversationContext, includeAiSkills, promptPreset, retrievedContext, null, null, null);
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
            conversationContext, includeAiSkills, promptPreset, null, null, null, null);
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
            conversationContext, includeAiSkills, AiPromptPreset.GENERIC, null, null, null, null);
    }

    public AiRequest(
        AiAction action,
        String selectedText,
        String connectionDisplayName,
        String responseLanguageCode,
        String userPrompt,
        String conversationContext) {

        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt,
            conversationContext, true, AiPromptPreset.GENERIC, null, null, null, null);
    }

    public AiRequest(AiAction action, String selectedText, String connectionDisplayName, String responseLanguageCode) {
        this(action, selectedText, connectionDisplayName, responseLanguageCode, null, null);
    }

    public AiRequest(AiAction action, String selectedText, String connectionDisplayName, String responseLanguageCode, String userPrompt) {
        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt, null);
    }
}
