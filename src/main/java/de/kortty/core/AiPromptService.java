package de.kortty.core;

import java.util.List;

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

    /**
     * True when this transport can attach {@link AiImageInput} images to a prompt. Callers must
     * check this before using a vision method — the defaults below throw.
     */
    default boolean supportsVision() {
        return false;
    }

    /** Executes a strict-JSON prompt with images attached to the user message. */
    default AiExecutionResult executeVisionJsonPrompt(
        String systemPrompt,
        String userPrompt,
        List<AiImageInput> images) throws Exception {

        throw new UnsupportedOperationException("This AI transport does not support image input");
    }

    /** Executes a vision JSON prompt in the supplied role/autonomous scope. */
    default AiExecutionResult executeVisionJsonPrompt(
        String systemPrompt,
        String userPrompt,
        List<AiImageInput> images,
        AiPromptExecutionScope scope) throws Exception {

        return executeVisionJsonPrompt(systemPrompt, userPrompt, images);
    }

    /** Vision variant for endpoints that reject {@code response_format}. */
    default AiExecutionResult executeVisionJsonPromptWithoutResponseFormat(
        String systemPrompt,
        String userPrompt,
        List<AiImageInput> images) throws Exception {

        throw new UnsupportedOperationException("This AI transport does not support image input");
    }

    /** Vision no-response-format variant in the supplied role/autonomous scope. */
    default AiExecutionResult executeVisionJsonPromptWithoutResponseFormat(
        String systemPrompt,
        String userPrompt,
        List<AiImageInput> images,
        AiPromptExecutionScope scope) throws Exception {

        return executeVisionJsonPromptWithoutResponseFormat(systemPrompt, userPrompt, images);
    }
}
