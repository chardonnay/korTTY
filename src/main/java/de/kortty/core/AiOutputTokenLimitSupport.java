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
    static final int FULL_REPLACEMENT_MAX_COMPLETION_TOKENS = 65_536;
    /**
     * Head-room for everything a model emits before the replacement itself, and therefore also the
     * floor of the full-replacement budget. Sized for models that bill hidden thinking as
     * completion tokens: MiniMax-M3 spent 36 449 of a ~36 500-token budget on a 13 KB script and
     * was cut off mid-replacement, which the fail-closed guard then had to reject. Raising this
     * only permits a longer answer — it never obliges a model to produce one.
     */
    private static final int FULL_REPLACEMENT_REASONING_RESERVE_TOKENS = 49_152;

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

    /**
     * Budget for one full-replacement answer: the reasoning reserve plus room for the rewritten
     * source, capped. A separate lower bound would be unreachable — a source is never shorter than
     * nothing, so the reserve is already the floor.
     */
    private static int fullReplacementLimit(String source) {
        long sourceCharacters = source != null ? source.length() : 0L;
        long requested = FULL_REPLACEMENT_REASONING_RESERVE_TOKENS + sourceCharacters;
        return (int) Math.min(FULL_REPLACEMENT_MAX_COMPLETION_TOKENS, requested);
    }
}
