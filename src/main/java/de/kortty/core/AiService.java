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
}
