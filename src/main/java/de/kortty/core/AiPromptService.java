package de.kortty.core;

/**
 * AI service capable of executing direct system/user prompt pairs.
 */
public interface AiPromptService extends AiService {

    AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception;

    AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws Exception;

    default AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt) throws Exception {
        return executePrompt(systemPrompt, userPrompt);
    }
}
