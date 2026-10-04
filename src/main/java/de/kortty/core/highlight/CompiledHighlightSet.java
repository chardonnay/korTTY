package de.kortty.core.highlight;

import com.sithtermfx.core.TerminalColor;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * A {@link HighlightRuleSet} turned into what the matcher and the terminal engine need: compiled
 * patterns, parsed colors and effect flags, in rule order.
 *
 * <p>Compiling never fails as a whole. A rule that is disabled or does not pass
 * {@link HighlightRuleValidator} is dropped, and the drop is logged with the rule's id only — never its
 * pattern, which can quote what the user is watching for in their own output. Rules beyond
 * {@link HighlightRuleValidator#MAX_RULES_PER_SET} are dropped the same way, so a hand-edited
 * settings file cannot make every pass evaluate thousands of patterns.
 *
 * <p>Immutable and thread-safe once built: one instance is shared by every pane showing the set.
 */
public final class CompiledHighlightSet {

    private static final Logger logger = LoggerFactory.getLogger(CompiledHighlightSet.class);

    /** Characters that make a hit part of a longer word for {@link HighlightRule#isWholeWord()}. */
    private static final String WORD_CHARS = "[\\p{L}\\p{N}_]";

    /** No set: nothing is highlighted. */
    public static final CompiledHighlightSet NONE = new CompiledHighlightSet(null, List.of());

    /**
     * One usable rule.
     *
     * @param index position in {@link #rules()}, which is what the matcher reports as a char's owner
     * @param ruleId the rule's stable id, the only thing about a rule that may be logged
     * @param foreground the color to paint the text in, or {@code null} to keep the program's
     * @param background the color to paint behind the text, or {@code null} to keep the program's
     * @param action what the rule does when its pattern appears in new output ({@link HighlightRule#getAction()})
     * @param notifyWithText whether a notification of the rule carries the matched text
     * @param label what a notification calls the rule: its name, or its pattern when it has none. The
     *              user's own text, but it may quote what they watch for, so it is never logged
     */
    public record Rule(int index, String ruleId, Pattern pattern, HighlightRule.Scope scope,
                       TerminalColor foreground, TerminalColor background,
                       boolean bold, boolean italic, boolean underline,
                       HighlightRule.Action action, boolean notifyWithText, String label) {

        /** A rule without an action, as every rule was before triggers existed. */
        public Rule(int index, String ruleId, Pattern pattern, HighlightRule.Scope scope,
                    TerminalColor foreground, TerminalColor background,
                    boolean bold, boolean italic, boolean underline) {
            this(index, ruleId, pattern, scope, foreground, background, bold, italic, underline,
                HighlightRule.Action.NONE, false, "");
        }

        public Rule {
            action = action != null ? action : HighlightRule.Action.NONE;
            label = label != null ? label : "";
        }

        /**
         * True when the rule changes how its match looks. A rule that only acts (a notification) claims no
         * characters, so it neither restyles them nor takes them away from a rule further down.
         */
        public boolean visual() {
            return foreground != null || background != null || bold || italic || underline;
        }

        /** True when the rule acts when its pattern appears ({@link HighlightRule.Action#NONE} does not). */
        public boolean trigger() {
            return action != HighlightRule.Action.NONE;
        }
    }

    private final String setId;

    private final List<Rule> rules;

    private final boolean hasTriggers;

    private CompiledHighlightSet(String setId, List<Rule> rules) {
        this.setId = setId;
        this.rules = List.copyOf(rules);
        this.hasTriggers = this.rules.stream().anyMatch(Rule::trigger);
    }

    /** Compiles the usable rules of {@code set} in order; {@code null} gives {@link #NONE}. */
    public static CompiledHighlightSet compile(HighlightRuleSet set) {
        if (set == null) {
            return NONE;
        }
        List<Rule> compiled = new ArrayList<>();
        for (HighlightRule rule : set.getRules()) {
            if (rule == null || !rule.isEnabled()) {
                continue;
            }
            if (compiled.size() >= HighlightRuleValidator.MAX_RULES_PER_SET) {
                logger.warn("Highlight rule set {} has more than {} rules; rule {} and the rest are ignored",
                    set.getId(), HighlightRuleValidator.MAX_RULES_PER_SET, rule.getId());
                break;
            }
            List<String> problems = HighlightRuleValidator.validateRule(rule);
            if (!problems.isEmpty()) {
                logger.warn("Highlight rule {} in set {} is ignored: {}", rule.getId(), set.getId(), problems.get(0));
                continue;
            }
            compiled.add(new Rule(compiled.size(), rule.getId(), patternFor(rule), rule.getScope(),
                parseColor(rule.getForeground()), parseColor(rule.getBackground()),
                rule.isBold(), rule.isItalic(), rule.isUnderline(),
                rule.getAction(), rule.isNotifyWithText(), labelOf(rule)));
        }
        return new CompiledHighlightSet(set.getId(), compiled);
    }

    /** What a notification calls {@code rule}: its name, else its pattern as typed. */
    static String labelOf(HighlightRule rule) {
        String name = rule.getName();
        if (name != null) {
            return name;
        }
        String pattern = rule.getPattern();
        return pattern != null ? pattern.strip() : "";
    }

    /**
     * The pattern a rule matches with: a literal is quoted, ignore-case is Unicode-aware, and
     * whole-word wraps the body in look-arounds that refuse a letter, digit or underscore on either
     * side ({@code error} then hits {@code ERROR:} but not {@code terror}). The look-arounds wrap the
     * whole body, so the regex engine itself backtracks to an alternative that satisfies them. A
     * user regex whose trailing {@code (?x)} comment would swallow the wrapper's closing parenthesis
     * does not compile, so it is rejected by the validator rather than silently matching wrongly.
     *
     * @throws IllegalArgumentException when the rule's regular expression does not compile
     */
    public static Pattern patternFor(HighlightRule rule) {
        String raw = rule.getPattern() != null ? rule.getPattern() : "";
        String body = rule.isRegex() ? raw : Pattern.quote(raw);
        if (rule.isWholeWord()) {
            body = "(?<!" + WORD_CHARS + ")(?:" + body + ")(?!" + WORD_CHARS + ")";
        }
        int flags = rule.isIgnoreCase() ? Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE : 0;
        return Pattern.compile(body, flags);
    }

    /**
     * Parses a stored color: {@code #RRGGBB} is a fixed color, {@code ansi:0} to {@code ansi:15} a
     * theme color that follows the pane's palette, blank or {@code null} means "keep".
     *
     * @throws IllegalArgumentException for anything else
     */
    public static TerminalColor parseColor(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String value = spec.trim();
        if (value.length() == 7 && value.charAt(0) == '#') {
            int rgb = 0;
            for (int i = 1; i < 7; i++) {
                int digit = Character.digit(value.charAt(i), 16);
                if (digit < 0) {
                    throw new IllegalArgumentException("Not a #RRGGBB color");
                }
                rgb = (rgb << 4) | digit;
            }
            return TerminalColor.rgb((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        }
        String lower = value.toLowerCase(Locale.ROOT);
        if (lower.startsWith("ansi:")) {
            String number = lower.substring("ansi:".length());
            if (!number.isEmpty() && number.length() <= 2 && number.chars().allMatch(Character::isDigit)) {
                int index = Integer.parseInt(number);
                if (index <= 15) {
                    return TerminalColor.index(index);
                }
            }
        }
        throw new IllegalArgumentException("Not a highlight color");
    }

    /** True for {@code null}/blank (keep) and for every color {@link #parseColor(String)} accepts. */
    public static boolean isValidColor(String spec) {
        try {
            parseColor(spec);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    /** Id of the source set, or {@code null} for {@link #NONE}. */
    public String setId() {
        return setId;
    }

    /** The usable rules in priority order. */
    public List<Rule> rules() {
        return rules;
    }

    public Rule rule(int index) {
        return rules.get(index);
    }

    public int size() {
        return rules.size();
    }

    /** True when nothing can ever be highlighted: {@link #NONE}, or a set without a usable rule. */
    public boolean isEmpty() {
        return rules.isEmpty();
    }

    /** True when at least one usable rule is a trigger ({@link Rule#trigger()}). */
    public boolean hasTriggers() {
        return hasTriggers;
    }
}
