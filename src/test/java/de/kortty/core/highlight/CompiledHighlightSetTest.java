package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.sithtermfx.core.TerminalColor;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.LoggerFactory;
import org.testng.annotations.Test;

class CompiledHighlightSetTest {

    private static HighlightRule rule(String pattern, boolean regex) {
        HighlightRule rule = new HighlightRule(pattern, regex);
        rule.setUnderline(true);
        return rule;
    }

    @Test
    void parsesHexAndThemeColorsAndTreatsBlankAsKeep() {
        TerminalColor hex = CompiledHighlightSet.parseColor("#1a2B3c");
        assertThat(hex.isIndexed()).isFalse();
        assertThat(hex).isEqualTo(TerminalColor.rgb(0x1a, 0x2b, 0x3c));

        TerminalColor theme = CompiledHighlightSet.parseColor(" ANSI:12 ");
        assertThat(theme.isIndexed()).isTrue();
        assertThat(theme.getColorIndex()).isEqualTo(12);

        assertThat(CompiledHighlightSet.parseColor(null)).isNull();
        assertThat(CompiledHighlightSet.parseColor("  ")).isNull();
        expectThrows(IllegalArgumentException.class, () -> CompiledHighlightSet.parseColor("ansi:16"));
        expectThrows(IllegalArgumentException.class, () -> CompiledHighlightSet.parseColor("#12"));
        expectThrows(IllegalArgumentException.class, () -> CompiledHighlightSet.parseColor("blue"));
    }

    @Test
    void compilesUsableRulesInOrderWithTheirEffects() {
        HighlightRule first = rule("disk", false);
        first.setForeground("ansi:1");
        first.setBackground("#000000");
        first.setBold(true);
        first.setItalic(true);
        first.setUnderline(false);
        HighlightRule second = rule("\\d+", true);
        second.setScope(HighlightRule.Scope.LINE);
        HighlightRuleSet set = new HighlightRuleSet("mine", "Mine", List.of(first, second));

        CompiledHighlightSet compiled = CompiledHighlightSet.compile(set);
        assertThat(compiled.setId()).isEqualTo("mine");
        assertThat(compiled.size()).isEqualTo(2);
        CompiledHighlightSet.Rule one = compiled.rule(0);
        assertThat(one.index()).isEqualTo(0);
        assertThat(one.ruleId()).isEqualTo(first.getId());
        assertThat(one.foreground()).isEqualTo(TerminalColor.index(1));
        assertThat(one.background()).isEqualTo(TerminalColor.rgb(0, 0, 0));
        assertThat(one.bold()).isTrue();
        assertThat(one.italic()).isTrue();
        assertThat(one.underline()).isFalse();
        assertThat(one.scope()).isEqualTo(HighlightRule.Scope.MATCH);
        CompiledHighlightSet.Rule two = compiled.rule(1);
        assertThat(two.foreground()).isNull();
        assertThat(two.background()).isNull();
        assertThat(two.underline()).isTrue();
        assertThat(two.scope()).isEqualTo(HighlightRule.Scope.LINE);
    }

    @Test
    void dropsDisabledAndInvalidRulesAndRenumbersTheRest() {
        HighlightRule disabled = rule("off", false);
        disabled.setEnabled(false);
        HighlightRule broken = rule("(", true);
        HighlightRule kept = rule("kept", false);
        List<HighlightRule> rules = new ArrayList<>();
        rules.add(disabled);
        rules.add(null);
        rules.add(broken);
        rules.add(kept);
        HighlightRuleSet set = new HighlightRuleSet("mine", "Mine", rules);

        CompiledHighlightSet compiled = CompiledHighlightSet.compile(set);
        assertThat(compiled.size()).isEqualTo(1);
        assertThat(compiled.rule(0).index()).isEqualTo(0);
        assertThat(compiled.rule(0).ruleId()).isEqualTo(kept.getId());
    }

    @Test
    void compilesAtMostSixtyFourRules() {
        List<HighlightRule> rules = new ArrayList<>();
        for (int i = 0; i < 70; i++) {
            rules.add(rule("r" + i, false));
        }
        CompiledHighlightSet compiled = CompiledHighlightSet.compile(new HighlightRuleSet("big", "Big", rules));
        assertThat(compiled.size()).isEqualTo(HighlightRuleValidator.MAX_RULES_PER_SET);
        assertThat(compiled.rule(63).ruleId()).isEqualTo(rules.get(63).getId());
    }

    @Test
    void noSetCompilesToNone() {
        assertThat(CompiledHighlightSet.compile(null)).isSameInstanceAs(CompiledHighlightSet.NONE);
        assertThat(CompiledHighlightSet.NONE.isEmpty()).isTrue();
        assertThat(CompiledHighlightSet.NONE.setId()).isNull();
        assertThat(CompiledHighlightSet.compile(new HighlightRuleSet("empty", "Empty", List.of())).isEmpty())
            .isTrue();
    }

    @Test
    void theCompiledRuleListIsImmutable() {
        CompiledHighlightSet compiled =
            CompiledHighlightSet.compile(new HighlightRuleSet("mine", "Mine", List.of(rule("x", false))));
        expectThrows(UnsupportedOperationException.class, () -> compiled.rules().clear());
    }

    /** Patterns can quote what a user watches for in their own output; the log gets ids only. */
    @Test
    void aDroppedRuleIsLoggedByIdNeverByPattern() {
        Logger logger = (Logger) LoggerFactory.getLogger(CompiledHighlightSet.class);
        Level previous = logger.getLevel();
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        logger.setLevel(Level.WARN);
        try {
            HighlightRule secret = rule("(customer-4711-secret", true);
            CompiledHighlightSet.compile(new HighlightRuleSet("set-1", "Set", List.of(secret)));

            assertThat(appender.list).hasSize(1);
            String message = appender.list.get(0).getFormattedMessage();
            assertThat(message).contains(secret.getId());
            assertThat(message).contains("set-1");
            assertThat(message).doesNotContain("customer-4711-secret");
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }
    }
}
