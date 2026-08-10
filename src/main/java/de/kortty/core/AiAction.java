package de.kortty.core;

import de.kortty.model.AiWorkload;

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
    GENERATE_SNIPPET_MERMAID,
    GENERATE_ASCII_ART;

    /** Deterministic role routing; no model call is needed to classify the action. */
    public AiWorkload workload() {
        return switch (this) {
            case SUMMARIZE, SOLVE_PROBLEM, ASK, GENERATE_CHAT_TITLE,
                 CORRECT_SNIPPET_DESCRIPTION, CORRECT_SNIPPET_SELECTION_TEXT,
                 TRANSLATE_SNIPPET_SELECTION_TEXT, DESCRIBE_SNIPPET_SELECTION,
                 DESCRIBE_SNIPPET_FULL, GENERATE_ASCII_ART -> AiWorkload.TEXT;
            default -> AiWorkload.CODING;
        };
    }

    /** True for actions that should prefer the dedicated security-review profile. */
    public boolean isSecurityAction() {
        return this == SECURITY_REVIEW_SNIPPET_CODE || this == APPLY_SNIPPET_SECURITY_FIXES;
    }

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
                 GENERATE_SNIPPET_MERMAID -> true;
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

    /**
     * Whether user-defined AI skills may be attached to this action's prompt. The selection
     * text-transform actions — spelling correction and translation — are mechanical natural-language
     * edits that return a strict JSON list of segment replacements. Mermaid generation likewise must not
     * inherit general coding-style instructions. It receives a separate mandatory, compact action skill
     * through {@link AiActionSkillPromptSupport}; configurable user and language skills add prompt bulk without
     * improving this fixed diagram task. Those configurable skills are therefore never included for these
     * actions, regardless of the request's {@code includeAiSkills} flag.
     */
    public boolean allowsAiSkills() {
        return switch (this) {
            case CORRECT_SNIPPET_SELECTION_TEXT, TRANSLATE_SNIPPET_SELECTION_TEXT,
                 GENERATE_SNIPPET_MERMAID -> false;
            default -> true;
        };
    }

    /**
     * Whether the hybrid (remote LLM) skill-relevance classifier may run before this action's main
     * request. The staged full-code-analysis apply actions send one mechanical full-file rewrite per
     * stage over near-identical context, so a per-stage classification round-trip adds latency
     * without changing the outcome; they always use the local relevance selection instead.
     * Explicitly pinned skills are unaffected — pinning bypasses auto-detection entirely.
     */
    public boolean allowsHybridSkillClassification() {
        return switch (this) {
            case APPLY_SNIPPET_IMPROVEMENTS, APPLY_SNIPPET_SECURITY_FIXES -> false;
            default -> true;
        };
    }
}
