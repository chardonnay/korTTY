package de.kortty.core;

/**
 * Text after a masking pass, plus how many secrets that pass replaced. The count is what the AI
 * confirmation dialog and the status bar report ("3 secrets masked"); it never carries the masked
 * values themselves.
 *
 * @param text  the masked text; {@code null} only when the input was {@code null}
 * @param count number of replaced matches, never negative
 */
public record RedactionResult(String text, int count) {

    public RedactionResult {
        count = Math.max(0, count);
    }

    /** The input passed through untouched. */
    public static RedactionResult unchanged(String text) {
        return new RedactionResult(text, 0);
    }

    /** True when at least one secret was masked. */
    public boolean masked() {
        return count > 0;
    }

    /** Chains a further pass: its text replaces this one, the counts add up. */
    public RedactionResult then(RedactionResult next) {
        return next == null ? this : new RedactionResult(next.text(), count + next.count());
    }
}
