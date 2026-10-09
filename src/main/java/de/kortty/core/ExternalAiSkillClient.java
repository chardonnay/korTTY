package de.kortty.core;

import java.util.List;

/**
 * Talks to one external AI-skill provider. Calls block on the network — never call them on the
 * JavaFX application thread.
 */
public interface ExternalAiSkillClient {

    /**
     * Skills matching {@code query}: keywords for a search API, an {@code owner/repo} reference or URL
     * for a repository-backed provider, a SKILL.md URL for a plain HTTP server.
     */
    List<ExternalAiSkillCandidate> search(String query) throws ExternalAiSkillException;

    /** Downloads the SKILL.md a {@link ExternalAiSkillCandidate#reference()} points to. */
    ExternalAiSkillDocument fetch(String reference) throws ExternalAiSkillException;

    /**
     * Checks that the provider answers and accepts the configured credentials.
     *
     * @return a short detail for the success message, e.g. the remaining request quota; may be empty
     */
    String testConnection() throws ExternalAiSkillException;
}
