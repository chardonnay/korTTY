package de.kortty.core;

/**
 * Per-action completion-token safety limits for strict, machine-parsed snippet responses.
 *
 * <p>These limits replace any transport fallback for the affected actions. They supply a finite
 * upper bound for Mermaid and full-replacement snippet actions that otherwise let a local
 * OpenAI-compatible server use the model's complete remaining context as its output budget.</p>
 */
public final class AiOutputTokenLimitSupport {

    static final int MERMAID_MAX_COMPLETION_TOKENS = 8_192;
    static final int FULL_REPLACEMENT_MIN_COMPLETION_TOKENS = 32_768;
    static final int FULL_REPLACEMENT_MAX_COMPLETION_TOKENS = 65_536;
    private static final int FULL_REPLACEMENT_REASONING_RESERVE_TOKENS = 24_576;

    private AiOutputTokenLimitSupport() {
    }

    /**
     * Resolves the effective completion cap for one request. An action limit replaces the
     * transport's fallback budget; unrelated actions keep the transport's existing behaviour.
     */
    public static Integer resolve(AiRequest request, Integer configuredDefault) {
        Integer actionLimit = actionLimit(request);
        return actionLimit != null ? actionLimit : configuredDefault;
    }

    static Integer actionLimit(AiRequest request) {
        if (request == null || request.action() == null) {
            return null;
        }
        return switch (request.action()) {
            case GENERATE_SNIPPET_MERMAID -> MERMAID_MAX_COMPLETION_TOKENS;
            case APPLY_SNIPPET_IMPROVEMENTS, APPLY_SNIPPET_SECURITY_FIXES,
                 IMPROVE_SNIPPET_CODE, ASSIST_SNIPPET_CODE ->
                fullReplacementLimit(request.selectedText());
            default -> null;
        };
    }

    private static int fullReplacementLimit(String source) {
        long sourceCharacters = source != null ? source.length() : 0L;
        long requested = FULL_REPLACEMENT_REASONING_RESERVE_TOKENS + sourceCharacters;
        return (int) Math.max(
            FULL_REPLACEMENT_MIN_COMPLETION_TOKENS,
            Math.min(FULL_REPLACEMENT_MAX_COMPLETION_TOKENS, requested));
    }
}
