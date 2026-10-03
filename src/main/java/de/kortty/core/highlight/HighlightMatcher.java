package de.kortty.core.highlight;

import de.kortty.core.DeadlineCharSequence;
import de.kortty.model.HighlightRule;

import java.util.Arrays;
import java.util.BitSet;
import java.util.regex.Matcher;

/**
 * Decides, for one logical line, which rule owns each character. Pure: no terminal, no logging, no
 * state between calls, so the rule editor's preview and the terminal engine get the same answer.
 *
 * <p>Semantics, the same as the session journal's auto-marker rules:
 * <ul>
 *   <li>Rules apply in list order and the first rule that claims a character owns it; a later rule
 *       only colors what is still unclaimed.</li>
 *   <li>{@link HighlightRule.Scope#MATCH} claims each hit {@code [start, end)};
 *       {@link HighlightRule.Scope#LINE} claims the whole line once anything hits. A zero-length
 *       hit claims nothing and does not count.</li>
 * </ul>
 *
 * <p>Bounded, because the patterns are the user's and the text is whatever a server prints:
 * <ul>
 *   <li>The input is cut to {@value LogicalLineProjection#MAX_CHARS} characters.</li>
 *   <li>Every rule matches through a {@link DeadlineCharSequence} with its own budget
 *       ({@link #RULE_BUDGET_NANOS} by default). A rule that runs out is reported in
 *       {@link Result#overrunRules()} and claims nothing — half a rule's hits would be a lie.</li>
 *   <li>The caller's pass deadline caps every rule's budget. When it runs out the result is
 *       {@linkplain Result#complete() incomplete}: later rules were never asked, so the caller must
 *       not apply it and should retry the line in a later pass.</li>
 * </ul>
 */
public final class HighlightMatcher {

    /** The owner of a character no rule claimed. */
    public static final int NO_OWNER = -1;

    /** Default budget of one rule on one logical line. */
    public static final long RULE_BUDGET_NANOS = 2_000_000L;

    /**
     * What a logical line came out as.
     *
     * @param owners for each character of the matched text, the index of the owning rule in the
     *               compiled set, or {@link #NO_OWNER}
     * @param overrunRules indexes of the rules that ran out of their own budget, ascending
     * @param complete false when the pass deadline cut the evaluation short
     */
    public record Result(int[] owners, int[] overrunRules, boolean complete) {

        public int owner(int index) {
            return owners[index];
        }

        /** True when at least one character is owned by a rule. */
        public boolean hasMatches() {
            for (int owner : owners) {
                if (owner != NO_OWNER) {
                    return true;
                }
            }
            return false;
        }
    }

    private HighlightMatcher() {
    }

    /** Matches with the default per-rule budget and no rule switched off. */
    public static Result match(CompiledHighlightSet set, CharSequence text, long passDeadlineNanos) {
        return match(set, text, passDeadlineNanos, RULE_BUDGET_NANOS, null);
    }

    /**
     * @param set the rules, in priority order
     * @param text the projected logical line ({@link LogicalLineProjection#text()})
     * @param passDeadlineNanos the {@link System#nanoTime()} value at which the whole pass must stop
     * @param ruleBudgetNanos how long one rule may take on this line
     * @param skippedRules rule indexes not to evaluate (e.g. switched off after repeated overruns),
     *                     or {@code null}
     */
    public static Result match(CompiledHighlightSet set, CharSequence text, long passDeadlineNanos,
                               long ruleBudgetNanos, BitSet skippedRules) {
        int length = text != null ? text.length() : 0;
        int[] owners = new int[length];
        Arrays.fill(owners, NO_OWNER);
        if (set == null || set.isEmpty() || length == 0) {
            return new Result(owners, new int[0], true);
        }
        CharSequence input = length > LogicalLineProjection.MAX_CHARS
            ? text.subSequence(0, LogicalLineProjection.MAX_CHARS) : text;
        BitSet overruns = new BitSet();
        int[] hits = new int[16];
        boolean complete = true;
        for (CompiledHighlightSet.Rule rule : set.rules()) {
            if (skippedRules != null && skippedRules.get(rule.index())) {
                continue;
            }
            long now = System.nanoTime();
            if (now - passDeadlineNanos >= 0L) {
                complete = false;
                break;
            }
            long ruleDeadline = now + Math.max(1L, ruleBudgetNanos);
            boolean passBound = ruleDeadline - passDeadlineNanos >= 0L;
            if (passBound) {
                ruleDeadline = passDeadlineNanos;
            }
            int hitCount;
            try {
                Matcher matcher = rule.pattern().matcher(new DeadlineCharSequence(input, ruleDeadline));
                hitCount = 0;
                while (matcher.find()) {
                    int start = matcher.start();
                    int end = matcher.end();
                    if (end <= start) {
                        continue; // zero-length: find() itself moves on by one
                    }
                    if (hitCount * 2 + 2 > hits.length) {
                        hits = Arrays.copyOf(hits, hits.length * 2);
                    }
                    hits[hitCount * 2] = start;
                    hits[hitCount * 2 + 1] = end;
                    hitCount++;
                    if (rule.scope() == HighlightRule.Scope.LINE) {
                        break;
                    }
                }
            } catch (DeadlineCharSequence.DeadlineExceeded | StackOverflowError e) {
                if (passBound && e instanceof DeadlineCharSequence.DeadlineExceeded) {
                    complete = false;
                    break;
                }
                overruns.set(rule.index());
                continue;
            }
            if (hitCount == 0) {
                continue;
            }
            if (rule.scope() == HighlightRule.Scope.LINE) {
                claim(owners, 0, length, rule.index());
            } else {
                for (int hit = 0; hit < hitCount; hit++) {
                    claim(owners, hits[hit * 2], hits[hit * 2 + 1], rule.index());
                }
            }
        }
        return new Result(owners, overruns.stream().toArray(), complete);
    }

    private static void claim(int[] owners, int start, int end, int owner) {
        for (int i = start; i < end; i++) {
            if (owners[i] == NO_OWNER) {
                owners[i] = owner;
            }
        }
    }
}
