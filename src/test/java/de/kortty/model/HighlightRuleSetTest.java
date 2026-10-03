package de.kortty.model;

import static com.google.common.truth.Truth.assertThat;

import java.util.ArrayList;
import java.util.List;
import org.testng.annotations.Test;

class HighlightRuleSetTest {

    private static HighlightRule rule(String pattern) {
        HighlightRule rule = new HighlightRule(pattern, false);
        rule.setForeground("ansi:3");
        rule.setUnderline(true);
        return rule;
    }

    @Test
    void aNewRuleHasAnIdAndTheDocumentedDefaults() {
        HighlightRule rule = new HighlightRule();
        assertThat(rule.getId()).isNotEmpty();
        assertThat(rule.isEnabled()).isTrue();
        assertThat(rule.isRegex()).isFalse();
        assertThat(rule.isIgnoreCase()).isTrue();
        assertThat(rule.isWholeWord()).isFalse();
        assertThat(rule.getScope()).isEqualTo(HighlightRule.Scope.MATCH);
        assertThat(rule.getForeground()).isNull();
        assertThat(rule.getBackground()).isNull();
        assertThat(rule.hasVisualEffect()).isFalse();

        rule.setScope(null);
        assertThat(rule.getScope()).isEqualTo(HighlightRule.Scope.MATCH);
        rule.setId(" ");
        assertThat(rule.getId()).isNotEmpty();
    }

    @Test
    void thePatternIsKeptAsTypedAndBlankColorsMeanKeep() {
        HighlightRule rule = new HighlightRule("  up  ", false);
        assertThat(rule.getPattern()).isEqualTo("  up  ");
        rule.setForeground(" #AABBCC ");
        rule.setBackground("");
        assertThat(rule.getForeground()).isEqualTo("#AABBCC");
        assertThat(rule.getBackground()).isNull();
        assertThat(rule.hasVisualEffect()).isTrue();
    }

    @Test
    void theCopyConstructorIsDeepAndKeepsIds() {
        HighlightRule original = rule("error");
        original.setScope(HighlightRule.Scope.LINE);
        original.setWholeWord(true);
        original.setBold(true);
        original.setItalic(true);
        original.setBackground("#101010");
        List<HighlightRule> rules = new ArrayList<>();
        rules.add(original);
        rules.add(null);
        HighlightRuleSet set = new HighlightRuleSet("ops", "Ops", rules);

        HighlightRuleSet copy = new HighlightRuleSet(set);
        assertThat(copy.getId()).isEqualTo("ops");
        assertThat(copy.getName()).isEqualTo("Ops");
        assertThat(copy.getRules()).hasSize(1);
        HighlightRule copied = copy.getRules().get(0);
        assertThat(copied).isNotSameInstanceAs(original);
        assertThat(copied.getId()).isEqualTo(original.getId());
        assertThat(copied.getPattern()).isEqualTo("error");
        assertThat(copied.getScope()).isEqualTo(HighlightRule.Scope.LINE);
        assertThat(copied.isWholeWord()).isTrue();
        assertThat(copied.isBold()).isTrue();
        assertThat(copied.isItalic()).isTrue();
        assertThat(copied.isUnderline()).isTrue();
        assertThat(copied.getForeground()).isEqualTo("ansi:3");
        assertThat(copied.getBackground()).isEqualTo("#101010");

        copied.setPattern("changed");
        assertThat(original.getPattern()).isEqualTo("error");
    }

    @Test
    void duplicateGetsAFreshSetIdAndFreshRuleIds() {
        HighlightRuleSet set = new HighlightRuleSet("builtin.errors", "Errors", List.of(rule("a"), rule("b")));
        HighlightRuleSet duplicate = set.duplicate("My errors");

        assertThat(duplicate.getId()).isNotEqualTo("builtin.errors");
        assertThat(duplicate.getName()).isEqualTo("My errors");
        assertThat(duplicate.getRules()).hasSize(2);
        for (int i = 0; i < 2; i++) {
            assertThat(duplicate.getRules().get(i).getId()).isNotEqualTo(set.getRules().get(i).getId());
            assertThat(duplicate.getRules().get(i).getPattern()).isEqualTo(set.getRules().get(i).getPattern());
        }
        assertThat(duplicate.getRules().get(0).getId()).isNotEqualTo(duplicate.getRules().get(1).getId());
    }

    @Test
    void theRuleListIsNeverNull() {
        HighlightRuleSet set = new HighlightRuleSet("x", "X", null);
        assertThat(set.getRules()).isEmpty();
        set.setRules(null);
        assertThat(set.getRules()).isEmpty();
        assertThat(new HighlightRuleSet(null).getRules()).isEmpty();
    }
}
