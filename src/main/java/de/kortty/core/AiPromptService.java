package de.kortty.core;

/**
 * AI service capable of executing direct system/user prompt pairs.
 */
public interface AiPromptService extends AiService {

    /**
     * Executes an autonomous prompt. Agent, planning, swarm, and job code intentionally uses this
     * two-argument form; knowledge stores therefore remain opt-in for those workflows.
     */
    AiExecutionResult executePrompt(String systemPrompt, String userPrompt) throws Exception;

    /** Executes an ordinary role-scoped prompt, or an explicitly autonomous one. */
    default AiExecutionResult executePrompt(
        String systemPrompt,
        String userPrompt,
        AiPromptExecutionScope scope) throws Exception {

        return executePrompt(systemPrompt, userPrompt);
    }

    AiExecutionResult executeJsonPrompt(String systemPrompt, String userPrompt) throws Exception;

    /** Executes a strict-JSON prompt in the supplied role/autonomous scope. */
    default AiExecutionResult executeJsonPrompt(
        String systemPrompt,
        String userPrompt,
        AiPromptExecutionScope scope) throws Exception {

        return executeJsonPrompt(systemPrompt, userPrompt);
    }

    default AiExecutionResult executeJsonPromptWithoutResponseFormat(String systemPrompt, String userPrompt) throws Exception {
        return executePrompt(systemPrompt, userPrompt);
    }

    /** Executes a JSON prompt without response-format transport support in the supplied scope. */
    default AiExecutionResult executeJsonPromptWithoutResponseFormat(
        String systemPrompt,
        String userPrompt,
        AiPromptExecutionScope scope) throws Exception {

        return executePrompt(systemPrompt, userPrompt, scope);
    }
}
