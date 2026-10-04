package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

class HighlightRuleValidatorTest {

    private static HighlightRule literal(String pattern) {
        HighlightRule rule = new HighlightRule(pattern, false);
        rule.setBold(true);
        return rule;
    }

    private static HighlightRule regex(String pattern) {
        HighlightRule rule = new HighlightRule(pattern, true);
        rule.setBold(true);
        return rule;
    }

    @Test
    void acceptsAPlainLiteralRuleAndAPlainRegexRule() {
        assertThat(HighlightRuleValidator.validateRule(literal("disk full"))).isEmpty();
        assertThat(HighlightRuleValidator.validateRule(regex("err(or)?\\b"))).isEmpty();
        assertThat(HighlightRuleValidator.isValid(regex("\\d+ ms"))).isTrue();
    }

    @Test
    void rejectsAMissingOrBlankPattern() {
        assertThat(HighlightRuleValidator.validateRule(literal(null)))
            .containsExactly(HighlightRuleValidator.KEY_PATTERN_REQUIRED);
        assertThat(HighlightRuleValidator.validateRule(literal("")))
            .containsExactly(HighlightRuleValidator.KEY_PATTERN_REQUIRED);
        assertThat(HighlightRuleValidator.validateRule(regex("   ")))
            .containsExactly(HighlightRuleValidator.KEY_PATTERN_REQUIRED);
        assertThat(HighlightRuleValidator.validateRule(null))
            .containsExactly(HighlightRuleValidator.KEY_PATTERN_REQUIRED);
    }

    @Test
    void capsThePatternAtTheControlApiLimit() {
        String atLimit = "x".repeat(HighlightRuleValidator.MAX_PATTERN_CHARS);
        assertThat(HighlightRuleValidator.validateRule(literal(atLimit))).isEmpty();
        assertThat(HighlightRuleValidator.validateRule(literal(atLimit + "x")))
            .containsExactly(HighlightRuleValidator.KEY_PATTERN_TOO_LONG);
        assertThat(HighlightRuleValidator.MAX_PATTERN_CHARS).isEqualTo(512);
    }

    @Test
    void rejectsARegexThatDoesNotCompileButQuotesTheSameTextAsALiteral() {
        assertThat(HighlightRuleValidator.validateRule(regex("(unclosed")))
            .containsExactly(HighlightRuleValidator.KEY_PATTERN_INVALID);
        assertThat(HighlightRuleValidator.validateRule(regex("[a-")))
            .containsExactly(HighlightRuleValidator.KEY_PATTERN_INVALID);
        assertThat(HighlightRuleValidator.validateRule(literal("(unclosed"))).isEmpty();
    }

    @Test
    void anUnbalancedRegexCannotPairUpWithTheWholeWordWrapper() {
        // Wrapped as (?<!w)(?:a)|(?:b)(?!w) this would compile and quietly bend whole-word.
        HighlightRule rule = regex("a)|(?:b");
        rule.setWholeWord(true);
        assertThat(HighlightRuleValidator.validateRule(rule))
            .containsExactly(HighlightRuleValidator.KEY_PATTERN_INVALID);
    }

    @Test
    void rejectsPatternsThatMatchTheEmptyString() {
        for (String pattern : List.of("a*", ".*?", "|error", "error|", "^", "$", "(x)?")) {
            assertWithMessage(pattern)
                .that(HighlightRuleValidator.validateRule(regex(pattern)))
                .containsExactly(HighlightRuleValidator.KEY_PATTERN_MATCHES_EMPTY);
        }
        HighlightRule wrapped = regex("a*");
        wrapped.setWholeWord(true);
        assertThat(HighlightRuleValidator.validateRule(wrapped))
            .containsExactly(HighlightRuleValidator.KEY_PATTERN_MATCHES_EMPTY);
    }

    @Test
    void rejectsBadColorsAndAcceptsHexAndThemeColors() {
        for (String bad : List.of("#12", "ansi:16", "ansi:-1", "ansi:", "red", "#GGGGGG", "#1234567", "ansi:1a")) {
            HighlightRule rule = literal("x");
            rule.setForeground(bad);
            assertWithMessage(bad).that(HighlightRuleValidator.validateRule(rule))
                .containsExactly(HighlightRuleValidator.KEY_FOREGROUND_INVALID);
            HighlightRule background = literal("x");
            background.setBackground(bad);
            assertWithMessage(bad).that(HighlightRuleValidator.validateRule(background))
                .containsExactly(HighlightRuleValidator.KEY_BACKGROUND_INVALID);
        }
        for (String good : List.of("#A1b2C3", "#000000", "ansi:0", "ansi:15", "ANSI:7", " ansi:3 ")) {
            HighlightRule rule = literal("x");
            rule.setForeground(good);
            rule.setBackground(good);
            assertWithMessage(good).that(HighlightRuleValidator.validateRule(rule)).isEmpty();
        }
    }

    @Test
    void aBlankColorMeansKeepAndIsNotAnEffect() {
        HighlightRule rule = new HighlightRule("x", false);
        rule.setForeground("  ");
        assertThat(rule.getForeground()).isNull();
        assertThat(HighlightRuleValidator.validateRule(rule))
            .containsExactly(HighlightRuleValidator.KEY_NO_EFFECT);
    }

    @Test
    void requiresAtLeastOneVisualEffect() {
        HighlightRule rule = new HighlightRule("x", false);
        assertThat(HighlightRuleValidator.validateRule(rule))
            .containsExactly(HighlightRuleValidator.KEY_NO_EFFECT);
        for (int effect = 0; effect < 5; effect++) {
            HighlightRule withEffect = new HighlightRule("x", false);
            switch (effect) {
                case 0 -> withEffect.setForeground("ansi:1");
                case 1 -> withEffect.setBackground("#202020");
                case 2 -> withEffect.setBold(true);
                case 3 -> withEffect.setItalic(true);
                default -> withEffect.setUnderline(true);
            }
            assertWithMessage("effect " + effect).that(HighlightRuleValidator.validateRule(withEffect)).isEmpty();
        }
    }

    @Test
    void aNotificationIsAnEffectOfItsOwn() {
        HighlightRule trigger = new HighlightRule("No space left", false);
        trigger.setAction(HighlightRule.Action.NOTIFY);
        assertWithMessage("a trigger may leave the text as it is").that(HighlightRuleValidator.validateRule(trigger))
            .isEmpty();
        trigger.setAction(HighlightRule.Action.NONE);
        assertThat(HighlightRuleValidator.validateRule(trigger)).containsExactly(HighlightRuleValidator.KEY_NO_EFFECT);
        trigger.setAction(null);
        assertThat(trigger.getAction()).isEqualTo(HighlightRule.Action.NONE);
    }

    @Test
    void reportsEveryIndependentProblemOfARule() {
        HighlightRule rule = new HighlightRule("(", true);
        rule.setForeground("#12");
        assertThat(HighlightRuleValidator.validateRule(rule))
            .containsExactly(HighlightRuleValidator.KEY_PATTERN_INVALID,
                HighlightRuleValidator.KEY_FOREGROUND_INVALID)
            .inOrder();
    }

    @Test
    void aSwitchedOffRuleIsStillJudgedWhileItIsEdited() {
        HighlightRule rule = regex("(");
        rule.setEnabled(false);
        assertThat(HighlightRuleValidator.validateRule(rule))
            .containsExactly(HighlightRuleValidator.KEY_PATTERN_INVALID);
    }

    @Test
    void aSetNeedsANameAndAtMostSixtyFourRules() {
        HighlightRuleSet set = new HighlightRuleSet("mine", " ", List.of(literal("x")));
        assertThat(HighlightRuleValidator.validateSet(set))
            .containsExactly(HighlightRuleValidator.KEY_NAME_REQUIRED);

        List<HighlightRule> rules = new ArrayList<>();
        for (int i = 0; i < HighlightRuleValidator.MAX_RULES_PER_SET; i++) {
            rules.add(literal("x" + i));
        }
        HighlightRuleSet full = new HighlightRuleSet("mine", "Mine", rules);
        assertThat(HighlightRuleValidator.validateSet(full)).isEmpty();
        full.getRules().add(literal("one too many"));
        assertThat(HighlightRuleValidator.validateSet(full))
            .containsExactly(HighlightRuleValidator.KEY_TOO_MANY_RULES);
        assertThat(HighlightRuleValidator.MAX_RULES_PER_SET).isEqualTo(64);
    }

    @Test
    void theBuiltinPrefixIsReservedForUserSetsOnly() {
        HighlightRuleSet spoof = new HighlightRuleSet("builtin.errors", "My errors", List.of(literal("x")));
        assertThat(HighlightRuleValidator.validateUserSet(spoof))
            .containsExactly(HighlightRuleValidator.KEY_RESERVED_ID);
        HighlightRuleSet unknownBuiltin = new HighlightRuleSet("builtin.later", "Later", List.of(literal("x")));
        assertThat(HighlightRuleValidator.validateUserSet(unknownBuiltin))
            .containsExactly(HighlightRuleValidator.KEY_RESERVED_ID);
        assertThat(HighlightRuleValidator.validateSet(spoof)).isEmpty();
        assertThat(HighlightRuleValidator.validateUserSet(
            new HighlightRuleSet("builtin-ish", "Mine", List.of(literal("x"))))).isEmpty();
    }

    @Test
    void theUserSetListIsCappedAndNeedsUniqueIds() {
        List<HighlightRuleSet> sets = new ArrayList<>();
        for (int i = 0; i < HighlightRuleValidator.MAX_USER_SETS; i++) {
            sets.add(new HighlightRuleSet("set-" + i, "Set " + i, List.of(literal("x"))));
        }
        assertThat(HighlightRuleValidator.validateUserSets(sets)).isEmpty();
        sets.add(new HighlightRuleSet("set-0", "Again", List.of(literal("x"))));
        assertThat(HighlightRuleValidator.validateUserSets(sets))
            .containsExactly(HighlightRuleValidator.KEY_TOO_MANY_SETS, HighlightRuleValidator.KEY_DUPLICATE_ID)
            .inOrder();
        assertThat(HighlightRuleValidator.validateUserSets(null)).isEmpty();
        assertThat(HighlightRuleValidator.MAX_USER_SETS).isEqualTo(32);
    }

    @Test
    void messagesThatQuoteALimitGetItAsTheirArgument() {
        assertThat(HighlightRuleValidator.messageArguments(HighlightRuleValidator.KEY_PATTERN_TOO_LONG))
            .asList().containsExactly(512);
        assertThat(HighlightRuleValidator.messageArguments(HighlightRuleValidator.KEY_TOO_MANY_RULES))
            .asList().containsExactly(64);
        assertThat(HighlightRuleValidator.messageArguments(HighlightRuleValidator.KEY_TOO_MANY_SETS))
            .asList().containsExactly(32);
        assertThat(HighlightRuleValidator.messageArguments(HighlightRuleValidator.KEY_NO_EFFECT)).isEmpty();
    }

    @Test
    void everyKeyTheValidatorReturnsIsListedForTheI18nCoverageTest() {
        assertThat(HighlightRuleValidator.MESSAGE_KEYS).containsNoDuplicates();
        for (String key : HighlightRuleValidator.MESSAGE_KEYS) {
            assertThat(key).startsWith("highlight.validation.");
        }
        assertThat(HighlightRuleValidator.MESSAGE_KEYS).containsAtLeast(
            HighlightRuleValidator.KEY_PATTERN_REQUIRED, HighlightRuleValidator.KEY_PATTERN_TOO_LONG,
            HighlightRuleValidator.KEY_PATTERN_INVALID, HighlightRuleValidator.KEY_PATTERN_MATCHES_EMPTY,
            HighlightRuleValidator.KEY_FOREGROUND_INVALID, HighlightRuleValidator.KEY_BACKGROUND_INVALID,
            HighlightRuleValidator.KEY_NO_EFFECT, HighlightRuleValidator.KEY_NAME_REQUIRED,
            HighlightRuleValidator.KEY_TOO_MANY_RULES, HighlightRuleValidator.KEY_RESERVED_ID,
            HighlightRuleValidator.KEY_TOO_MANY_SETS, HighlightRuleValidator.KEY_DUPLICATE_ID);
    }
}
