package de.kortty.core.highlight;

import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The one set of checks a highlight rule and a rule set have to pass, shared by the rule editor (to
 * explain what is wrong) and by {@link CompiledHighlightSet} (to drop what is wrong). Every problem is
 * an i18n key under {@code highlight.validation.}; {@link #messageArguments(String)} supplies the
 * limit a message quotes, so the UI shows {@code getString(key, messageArguments(key))}.
 *
 * <p>Pure and side-effect free; any thread.
 */
public final class HighlightRuleValidator {

    /** Same cap as the control API's {@code pane.wait_output} pattern (ControlApiProtocol.MAX_REGEX_CHARS). */
    public static final int MAX_PATTERN_CHARS = 512;

    public static final int MAX_RULES_PER_SET = 64;

    public static final int MAX_USER_SETS = 32;

    public static final String KEY_PATTERN_REQUIRED = "highlight.validation.patternRequired";
    public static final String KEY_PATTERN_TOO_LONG = "highlight.validation.patternTooLong";
    public static final String KEY_PATTERN_INVALID = "highlight.validation.patternInvalid";
    public static final String KEY_PATTERN_MATCHES_EMPTY = "highlight.validation.patternMatchesEmpty";
    public static final String KEY_FOREGROUND_INVALID = "highlight.validation.foregroundInvalid";
    public static final String KEY_BACKGROUND_INVALID = "highlight.validation.backgroundInvalid";
    public static final String KEY_NO_EFFECT = "highlight.validation.noEffect";
    public static final String KEY_NAME_REQUIRED = "highlight.validation.nameRequired";
    public static final String KEY_TOO_MANY_RULES = "highlight.validation.tooManyRules";
    public static final String KEY_RESERVED_ID = "highlight.validation.reservedId";
    public static final String KEY_TOO_MANY_SETS = "highlight.validation.tooManySets";
    public static final String KEY_DUPLICATE_ID = "highlight.validation.duplicateId";

    /** Every key this class can return; the i18n coverage test checks each exists in every bundle. */
    public static final List<String> MESSAGE_KEYS = List.of(
        KEY_PATTERN_REQUIRED, KEY_PATTERN_TOO_LONG, KEY_PATTERN_INVALID, KEY_PATTERN_MATCHES_EMPTY,
        KEY_FOREGROUND_INVALID, KEY_BACKGROUND_INVALID, KEY_NO_EFFECT, KEY_NAME_REQUIRED,
        KEY_TOO_MANY_RULES, KEY_RESERVED_ID, KEY_TOO_MANY_SETS, KEY_DUPLICATE_ID);

    private HighlightRuleValidator() {
    }

    /**
     * The problems of one rule, most fundamental first; empty when the rule is usable. A rule's
     * {@code enabled} flag is not judged: a switched-off rule may still be checked while it is edited.
     */
    public static List<String> validateRule(HighlightRule rule) {
        List<String> problems = new ArrayList<>(2);
        if (rule == null) {
            problems.add(KEY_PATTERN_REQUIRED);
            return problems;
        }
        String pattern = rule.getPattern();
        if (pattern == null || pattern.isBlank()) {
            problems.add(KEY_PATTERN_REQUIRED);
        } else if (pattern.length() > MAX_PATTERN_CHARS) {
            problems.add(KEY_PATTERN_TOO_LONG);
        } else {
            String patternProblem = patternProblem(rule);
            if (patternProblem != null) {
                problems.add(patternProblem);
            }
        }
        if (!CompiledHighlightSet.isValidColor(rule.getForeground())) {
            problems.add(KEY_FOREGROUND_INVALID);
        }
        if (!CompiledHighlightSet.isValidColor(rule.getBackground())) {
            problems.add(KEY_BACKGROUND_INVALID);
        }
        if (!rule.hasVisualEffect()) {
            problems.add(KEY_NO_EFFECT);
        }
        return problems;
    }

    /** True when {@link #validateRule(HighlightRule)} finds nothing. */
    public static boolean isValid(HighlightRule rule) {
        return validateRule(rule).isEmpty();
    }

    /**
     * Set-level problems — name and rule count — without the per-rule ones, which the editor shows
     * next to each rule. Holds for built-in and user sets alike; see {@link #validateUserSet}.
     */
    public static List<String> validateSet(HighlightRuleSet set) {
        List<String> problems = new ArrayList<>(2);
        if (set == null) {
            problems.add(KEY_NAME_REQUIRED);
            return problems;
        }
        if (set.getName() == null || set.getName().isBlank()) {
            problems.add(KEY_NAME_REQUIRED);
        }
        if (set.getRules().size() > MAX_RULES_PER_SET) {
            problems.add(KEY_TOO_MANY_RULES);
        }
        return problems;
    }

    /** {@link #validateSet} plus the reserved {@code builtin.} id prefix a user set may not take. */
    public static List<String> validateUserSet(HighlightRuleSet set) {
        List<String> problems = validateSet(set);
        if (set != null && HighlightBuiltinSets.isReservedId(set.getId())) {
            problems.add(KEY_RESERVED_ID);
        }
        return problems;
    }

    /** Problems of the stored list of user sets as a whole: how many there are and unique ids. */
    public static List<String> validateUserSets(List<HighlightRuleSet> sets) {
        List<String> problems = new ArrayList<>(2);
        if (sets == null) {
            return problems;
        }
        if (sets.size() > MAX_USER_SETS) {
            problems.add(KEY_TOO_MANY_SETS);
        }
        Set<String> ids = new HashSet<>();
        for (HighlightRuleSet set : sets) {
            if (set != null && !ids.add(set.getId())) {
                problems.add(KEY_DUPLICATE_ID);
                break;
            }
        }
        return problems;
    }

    /** The values a message quotes ({0}); empty for messages that quote nothing. */
    public static Object[] messageArguments(String key) {
        if (KEY_PATTERN_TOO_LONG.equals(key)) {
            return new Object[] {MAX_PATTERN_CHARS};
        }
        if (KEY_TOO_MANY_RULES.equals(key)) {
            return new Object[] {MAX_RULES_PER_SET};
        }
        if (KEY_TOO_MANY_SETS.equals(key)) {
            return new Object[] {MAX_USER_SETS};
        }
        return new Object[0];
    }

    /**
     * Compiles the rule as the matcher will and checks it cannot match empty text — {@code a*},
     * {@code .*?} or an empty alternative such as {@code |error} would claim every position. A
     * regex has to compile on its own as well as wrapped for whole-word, so an unbalanced
     * parenthesis cannot pair up with the wrapper's and change what whole-word means.
     */
    private static String patternProblem(HighlightRule rule) {
        Pattern pattern;
        try {
            if (rule.isRegex()) {
                Pattern.compile(rule.getPattern());
            }
            pattern = CompiledHighlightSet.patternFor(rule);
        } catch (IllegalArgumentException | StackOverflowError e) {
            return KEY_PATTERN_INVALID;
        }
        try {
            if (pattern.matcher("").find()) {
                return KEY_PATTERN_MATCHES_EMPTY;
            }
        } catch (RuntimeException | StackOverflowError e) {
            return KEY_PATTERN_INVALID;
        }
        return null;
    }
}
