package de.kortty.core;

/**
 * Supported AI actions for terminal selections.
 */
public enum AiAction {
    SUMMARIZE,
    SOLVE_PROBLEM,
    ASK,
    GENERATE_CHAT_TITLE,
    GENERATE_SNIPPET_METADATA,
    CORRECT_SNIPPET_DESCRIPTION,
    CORRECT_SNIPPET_SELECTION_TEXT,
    TRANSLATE_SNIPPET_SELECTION_TEXT,
    DESCRIBE_SNIPPET_SELECTION,
    DESCRIBE_SNIPPET_FULL,
    GENERATE_SNIPPET_ALTERNATIVES,
    COMPLETE_SNIPPET_CODE,
    REVIEW_SNIPPET_CODE,
    ANALYZE_SNIPPET_CODE,
    APPLY_SNIPPET_IMPROVEMENTS,
    IMPROVE_SNIPPET_CODE,
    ASSIST_SNIPPET_CODE,
    SECURITY_REVIEW_SNIPPET_CODE,
    APPLY_SNIPPET_SECURITY_FIXES,
    GENERATE_SNIPPET_ONE_LINER,
    GENERATE_SNIPPET_PLANTUML;

    /**
     * Whether this action requires a strict machine-parsed reply — a single JSON object of a fixed
     * shape whose contents are parsed and often replace the user's snippet. For these actions the
     * output format is a hard contract, so user-defined AI skills must not be allowed to change it;
     * the prompt is hardened accordingly (see {@link AiSkillPromptSupport} and {@link AiPromptBuilder}).
     * The chat and plain-text/description actions return free-form prose and are intentionally excluded.
     */
    public boolean requiresStrictJsonReply() {
        return switch (this) {
            case GENERATE_SNIPPET_METADATA, CORRECT_SNIPPET_SELECTION_TEXT, TRANSLATE_SNIPPET_SELECTION_TEXT,
                 GENERATE_SNIPPET_ALTERNATIVES, COMPLETE_SNIPPET_CODE, REVIEW_SNIPPET_CODE,
                 ANALYZE_SNIPPET_CODE, APPLY_SNIPPET_IMPROVEMENTS, IMPROVE_SNIPPET_CODE, ASSIST_SNIPPET_CODE,
                 SECURITY_REVIEW_SNIPPET_CODE, APPLY_SNIPPET_SECURITY_FIXES, GENERATE_SNIPPET_ONE_LINER,
                 GENERATE_SNIPPET_PLANTUML -> true;
            default -> false;
        };
    }

    /**
     * Whether this action's JSON reply carries a CODE payload that is inserted into or replaces the
     * user's snippet (a {@code replacement}, {@code insertText}, {@code code}, or {@code command} field).
     * A degenerate reply here (a bare placeholder such as {@code "$code"}) would corrupt or wipe the
     * user's code, so these actions additionally get the "every code field must be real source" anchor.
     * The findings/metadata/text/diagram strict-JSON actions carry no such code field and are excluded.
     */
    public boolean producesCodePayload() {
        return switch (this) {
            case APPLY_SNIPPET_IMPROVEMENTS, APPLY_SNIPPET_SECURITY_FIXES, ASSIST_SNIPPET_CODE,
                 IMPROVE_SNIPPET_CODE, COMPLETE_SNIPPET_CODE, GENERATE_SNIPPET_ALTERNATIVES,
                 GENERATE_SNIPPET_ONE_LINER -> true;
            default -> false;
        };
    }
}
