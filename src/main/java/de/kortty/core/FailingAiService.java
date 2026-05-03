package de.kortty.core;

/**
 * AI service placeholder that reports a concrete configuration failure when used.
 */
public class FailingAiService implements AiPromptService {

    private final String message;

    public FailingAiService(String message) {
        this.message = message != null && !message.isBlank() ? message : "AI service is not configured.";
    }

    public String message() {
        return message;
    }

    @Override
    public AiExecutionResult execute(AiRequest request) {
        throw new IllegalStateException(message);
    }

    @Override
    public AiExecutionResult executePrompt(String systemPrompt, String userPrompt) {
        throw new IllegalStateException(message);
    }

    @Override
    public AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) {
        throw new IllegalStateException(message);
    }

    @Override
    public boolean testConnection() {
        return false;
    }
}
