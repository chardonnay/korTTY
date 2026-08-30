package de.kortty.ui;

import de.kortty.core.CodeTextLanguage;

import java.util.Set;

/**
 * Mutable, editor-scoped runtime options shared between the snippet editor and its {@link
 * SnippetAiAssistFactory}-built providers. Currently carries the AI skills selected for AI-code
 * runs: the editor updates {@link #setForcedSkillIds(Set)} from its skill picker, and the factory reads
 * {@link #forcedSkillIds()} at request time as the freshly-built AI service's explicit skill allowlist —
 * so one selection applies to every AI-code function without threading a field through each request.
 *
 * <p>It carries the code-text language contract the same way: the editor resolves it per action from
 * the snippet's own prose and the user's choice, and the factory hands it to every service it builds.</p>
 */
final class SnippetAiRuntimeOptions {

    private volatile Set<String> forcedSkillIds = Set.of();
    private volatile CodeTextLanguage codeTextLanguage;

    /** Explicit skill ids for every AI-code prompt, regardless of target; never {@code null}. */
    Set<String> forcedSkillIds() {
        return forcedSkillIds;
    }

    void setForcedSkillIds(Set<String> ids) {
        this.forcedSkillIds = ids != null ? Set.copyOf(ids) : Set.of();
    }

    /**
     * Which language prose inside returned code must use, and whether existing prose may be
     * converted into it. {@code null} leaves the prompt's previous behaviour untouched.
     */
    CodeTextLanguage codeTextLanguage() {
        return codeTextLanguage;
    }

    void setCodeTextLanguage(CodeTextLanguage codeTextLanguage) {
        this.codeTextLanguage = codeTextLanguage;
    }
}
