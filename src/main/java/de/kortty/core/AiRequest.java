package de.kortty.core;

/**
 * Immutable request passed to an AI service.
 */
public record AiRequest(
    AiAction action,
    String selectedText,
    String connectionDisplayName,
    String responseLanguageCode,
    String userPrompt,
    String conversationContext) {

    public AiRequest(AiAction action, String selectedText, String connectionDisplayName, String responseLanguageCode) {
        this(action, selectedText, connectionDisplayName, responseLanguageCode, null, null);
    }

    public AiRequest(AiAction action, String selectedText, String connectionDisplayName, String responseLanguageCode, String userPrompt) {
        this(action, selectedText, connectionDisplayName, responseLanguageCode, userPrompt, null);
    }
}
