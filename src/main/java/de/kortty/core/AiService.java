package de.kortty.core;

/**
 * Generic AI service used for terminal selection analysis.
 */
public interface AiService {

    /**
     * Executes the given AI request and returns the generated response text.
     */
    AiExecutionResult execute(AiRequest request) throws Exception;

    /**
     * Tests whether the current AI configuration is valid.
     */
    boolean testConnection();

    /**
     * @return a short, secret-free reason why the last {@link #testConnection()} failed (for
     *     example the provider's own error message), or {@code null} when none is known
     */
    default String lastTestFailure() {
        return null;
    }
}
