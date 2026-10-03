package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;

import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.core.TextStyle;
import de.kortty.model.HighlightRule;
import de.kortty.model.HighlightRuleSet;
import java.util.EnumSet;
import java.util.List;
import org.testng.annotations.Test;

class HighlightTextStyleTest {

    private static final TerminalColor RED = TerminalColor.index(1);

    private static final TerminalColor GREEN = TerminalColor.index(2);

    private static CompiledHighlightSet.Rule rule(String foreground, String background, boolean bold) {
        HighlightRule rule = new HighlightRule("x", false);
        rule.setForeground(foreground);
        rule.setBackground(background);
        rule.setBold(bold);
        if (foreground == null && background == null && !bold) {
            rule.setUnderline(true);
        }
        return CompiledHighlightSet.compile(new HighlightRuleSet("s", "S", List.of(rule))).rule(0);
    }

    @Test
    void aColorOnlyReplacesWhatTheRuleSets() {
        TextStyle base = new TextStyle(GREEN, TerminalColor.index(7), EnumSet.of(TextStyle.Option.ITALIC));

        HighlightTextStyle derived = HighlightTextStyle.derive(base, rule("ansi:1", null, true), 3);

        assertThat(derived.getForeground()).isEqualTo(RED);
        assertThat(derived.getBackground()).isEqualTo(TerminalColor.index(7));
        assertThat(derived.hasOption(TextStyle.Option.BOLD)).isTrue();
        assertThat(derived.hasOption(TextStyle.Option.ITALIC)).isTrue();
        assertThat(derived.base()).isSameInstanceAs(base);
        assertThat(derived.isFor(0, 3)).isTrue();
        assertThat(derived.isFor(0, 4)).isFalse();
    }

    @Test
    void concealedDimAndBlinkingTextKeepTheirOptions() {
        TextStyle base = new TextStyle(null, null, EnumSet.of(TextStyle.Option.HIDDEN, TextStyle.Option.DIM,
            TextStyle.Option.SLOW_BLINK));

        HighlightTextStyle derived = HighlightTextStyle.derive(base, rule(null, "ansi:1", false), 1);

        assertThat(derived.hasOption(TextStyle.Option.HIDDEN)).isTrue();
        assertThat(derived.hasOption(TextStyle.Option.DIM)).isTrue();
        assertThat(derived.hasOption(TextStyle.Option.SLOW_BLINK)).isTrue();
    }

    @Test
    void inverseIsClearedOnlyWhenTheRuleSetsAColor() {
        TextStyle inverse = new TextStyle(null, null, EnumSet.of(TextStyle.Option.INVERSE));

        assertThat(HighlightTextStyle.derive(inverse, rule("ansi:1", null, false), 1)
            .hasOption(TextStyle.Option.INVERSE)).isFalse();
        assertThat(HighlightTextStyle.derive(inverse, rule(null, null, true), 1)
            .hasOption(TextStyle.Option.INVERSE)).isTrue();
    }

    @Test
    void derivingFromAHighlightUsesTheProgramsStyle() {
        TextStyle base = new TextStyle(GREEN, null);
        HighlightTextStyle once = HighlightTextStyle.derive(base, rule("ansi:1", null, true), 1);

        HighlightTextStyle twice = HighlightTextStyle.derive(once, rule(null, null, false), 2);

        assertThat(twice.base()).isSameInstanceAs(base);
        assertThat(twice.getForeground()).isEqualTo(GREEN);
        assertThat(HighlightTextStyle.baseOf(twice)).isSameInstanceAs(base);
        assertThat(HighlightTextStyle.baseOf(base)).isSameInstanceAs(base);
    }

    @Test
    void equalityIncludesBaseRuleAndGeneration() {
        TextStyle base = new TextStyle(GREEN, null);
        CompiledHighlightSet.Rule rule = rule("ansi:1", null, true);

        assertThat(HighlightTextStyle.derive(base, rule, 1)).isEqualTo(HighlightTextStyle.derive(base, rule, 1));
        assertThat(HighlightTextStyle.derive(base, rule, 1)).isNotEqualTo(HighlightTextStyle.derive(base, rule, 2));
        assertThat(HighlightTextStyle.derive(base, rule, 1))
            .isNotEqualTo(HighlightTextStyle.derive(new TextStyle(RED, null), rule, 1));
        assertThat(HighlightTextStyle.derive(base, rule, 1)).isNotEqualTo(new TextStyle(RED, null,
            EnumSet.of(TextStyle.Option.BOLD)));
    }
}
