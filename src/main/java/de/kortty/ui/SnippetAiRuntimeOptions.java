package de.kortty.ui;

import java.util.Set;

/**
 * Mutable, editor-scoped runtime options shared between the snippet editor and its {@link
 * SnippetAiAssistFactory}-built providers. Currently carries the AI skills selected for AI-code
 * runs: the editor updates {@link #setForcedSkillIds(Set)} from its skill picker, and the factory reads
 * {@link #forcedSkillIds()} at request time as the freshly-built AI service's explicit skill allowlist —
 * so one selection applies to every AI-code function without threading a field through each request.
 */
final class SnippetAiRuntimeOptions {

    private volatile Set<String> forcedSkillIds = Set.of();

    /** Explicit skill ids for every AI-code prompt, regardless of target; never {@code null}. */
    Set<String> forcedSkillIds() {
        return forcedSkillIds;
    }

    void setForcedSkillIds(Set<String> ids) {
        this.forcedSkillIds = ids != null ? Set.copyOf(ids) : Set.of();
    }
}
