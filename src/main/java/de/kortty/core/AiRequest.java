package de.kortty.core;

import de.kortty.model.AiPromptPreset;
import de.kortty.model.SnippetDiagramType;

/**
 * Immutable request passed to an AI service.
 *
 * <p>{@code diagramType} is only meaningful for {@link AiAction#GENERATE_SNIPPET_MERMAID}; a
 * {@code null} value means the default logical-structure flowchart. {@code asciiArtOptions} is only
 * meaningful for {@link AiAction#GENERATE_ASCII_ART}; {@code null} means the default SVG contract on
 * the default picture size. {@code fileAttachment} is an optional text file sent along with the
 * selected terminal text (e.g. the file whose name was selected in the terminal); {@code null} means
 * no attachment.</p>
 *
 * <p>Wrappers that derive a request from another one must use the {@code with…} methods rather than
 * a shorter constructor, so that action-specific components such as {@code diagramType},
 * {@code asciiArtOptions} and the {@code streamListener} survive the copy.</p>
 *
 * <p>{@code streamListener} receives snapshots of a streamed answer; {@code null} (the default of
 * every shorter constructor) means nobody watches the stream.</p>
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
    AsciiArtRequestOptions asciiArtOptions,
    AiFileAttachment fileAttachment,
    AiStreamListener streamListener) {

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
            codeTextLanguage, diagramType, asciiArtOptions, fileAttachment, streamListener);
    }

    /** The same request for one diagram family; only {@code GENERATE_SNIPPET_MERMAID} uses it. */
    public AiRequest withDiagramType(SnippetDiagramType diagramType) {
        return new AiRequest(action, selectedText, connectionDisplayName, responseLanguageCode,
            userPrompt, conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, asciiArtOptions, fileAttachment, streamListener);
    }

    /** The same request with the ASCII-art options; only {@code GENERATE_ASCII_ART} uses them. */
    public AiRequest withAsciiArtOptions(AsciiArtRequestOptions asciiArtOptions) {
        return new AiRequest(action, selectedText, connectionDisplayName, responseLanguageCode,
            userPrompt, conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, asciiArtOptions, fileAttachment, streamListener);
    }

    /** The same request with the model-specific prompt preset resolved by the profile. */
    public AiRequest withPromptPreset(AiPromptPreset promptPreset) {
        return new AiRequest(action, selectedText, connectionDisplayName, responseLanguageCode,
            userPrompt, conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, asciiArtOptions, fileAttachment, streamListener);
    }

    /** The same request with knowledge-store context retrieved for it. */
    public AiRequest withRetrievedContext(String retrievedContext) {
        return new AiRequest(action, selectedText, connectionDisplayName, responseLanguageCode,
            userPrompt, conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, asciiArtOptions, fileAttachment, streamListener);
    }

    /** The same request with a text file attached ({@code null} removes the attachment). */
    public AiRequest withFileAttachment(AiFileAttachment fileAttachment) {
        return new AiRequest(action, selectedText, connectionDisplayName, responseLanguageCode,
            userPrompt, conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, asciiArtOptions, fileAttachment, streamListener);
    }

    /**
     * The same request with a listener for the streamed answer ({@code null} removes it). Only the
     * chat panel sets one; see {@link AiStreamListener} for the threading and snapshot contract.
     */
    public AiRequest withStreamListener(AiStreamListener streamListener) {
        return new AiRequest(action, selectedText, connectionDisplayName, responseLanguageCode,
            userPrompt, conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, asciiArtOptions, fileAttachment, streamListener);
    }

    /** True when a non-empty text file is attached to this request. */
    public boolean hasFileAttachment() {
        return fileAttachment != null && !fileAttachment.content().isBlank();
    }

    /** Compatibility constructor: a request without a stream listener. */
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
        SnippetDiagramType diagramType,
        AsciiArtRequestOptions asciiArtOptions,
        AiFileAttachment fileAttachment) {

        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt,
            conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, asciiArtOptions, fileAttachment, null);
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
        SnippetDiagramType diagramType,
        AsciiArtRequestOptions asciiArtOptions) {

        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt,
            conversationContext, includeAiSkills, promptPreset, retrievedContext,
            codeTextLanguage, diagramType, asciiArtOptions, null);
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
