package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;

import de.kortty.core.TerminalPaletteSupport;
import de.kortty.core.highlight.CompiledHighlightSet;
import de.kortty.model.ConnectionSettings;
import de.kortty.model.HighlightRule;
import java.util.List;
import javafx.scene.paint.Color;
import org.testng.annotations.Test;

/**
 * The rule editor's color choices map to and from what a rule stores — blank for the program's color,
 * {@code ansi:N} for a theme color, {@code #RRGGBB} for a custom one — and every value they produce is one
 * the shared validator accepts. No JavaFX toolkit: {@link Color} is a plain value class.
 */
class HighlightColorChoicesTest {

    @Test
    void theChoicesAreKeepTheSixteenThemeColorsThenCustom() {
        List<HighlightColorChoices.Choice> choices = HighlightColorChoices.choices();

        assertThat(choices).hasSize(HighlightColorChoices.THEME_COLORS + 2);
        assertThat(choices.getFirst().kind()).isEqualTo(HighlightColorChoices.Kind.KEEP);
        assertThat(choices.getLast().kind()).isEqualTo(HighlightColorChoices.Kind.CUSTOM);
        for (int index = 0; index < HighlightColorChoices.THEME_COLORS; index++) {
            HighlightColorChoices.Choice choice = choices.get(index + 1);
            assertThat(choice.kind()).isEqualTo(HighlightColorChoices.Kind.THEME);
            assertThat(choice.spec()).isEqualTo("ansi:" + index);
            assertThat(CompiledHighlightSet.isValidColor(choice.spec())).isTrue();
        }
        assertThat(choices.get(2).label()).isEqualTo(I18n.get("highlight.color.red"));
        assertThat(choices.get(10).label()).isEqualTo(I18n.get(HighlightColorChoices.BRIGHT_KEY,
            I18n.get("highlight.color.red")));
        assertThat(choices.get(10).label()).doesNotContain("{0}");
    }

    @Test
    void aStoredColorShowsAsItsChoice() {
        List<HighlightColorChoices.Choice> choices = HighlightColorChoices.choices();

        assertThat(HighlightColorChoices.choiceFor(null, choices).kind()).isEqualTo(HighlightColorChoices.Kind.KEEP);
        assertThat(HighlightColorChoices.choiceFor("  ", choices).kind()).isEqualTo(HighlightColorChoices.Kind.KEEP);
        assertThat(HighlightColorChoices.choiceFor("ansi:9", choices).spec()).isEqualTo("ansi:9");
        assertThat(HighlightColorChoices.choiceFor(" ANSI:3 ", choices).spec()).isEqualTo("ansi:3");
        assertThat(HighlightColorChoices.choiceFor("#FF8800", choices).kind())
            .isEqualTo(HighlightColorChoices.Kind.CUSTOM);
        // Not a theme color the editor offers: shown as custom, and the validator explains the problem.
        assertThat(HighlightColorChoices.choiceFor("ansi:16", choices).kind())
            .isEqualTo(HighlightColorChoices.Kind.CUSTOM);
    }

    @Test
    void aPickedColorIsStoredAsUpperCaseHexTheValidatorAccepts() {
        String spec = HighlightColorChoices.customSpec(Color.rgb(255, 136, 0));

        assertThat(spec).isEqualTo("#FF8800");
        assertThat(CompiledHighlightSet.isValidColor(spec)).isTrue();
        assertThat(HighlightColorChoices.customSpec(null)).isNull();
        assertThat(HighlightColorChoices.kindOf(spec)).isEqualTo(HighlightColorChoices.Kind.CUSTOM);
    }

    @Test
    void themeColorsResolveThroughTheTerminalPalette() {
        ConnectionSettings terminal = new ConnectionSettings();
        assertThat(HighlightColorChoices.hexFor("ansi:1", terminal))
            .isEqualTo(TerminalPaletteSupport.effectiveHex(terminal, 1, false));
        assertThat(HighlightColorChoices.hexFor("ansi:9", terminal))
            .isEqualTo(TerminalPaletteSupport.effectiveHex(terminal, 1, true));

        terminal.setAnsiColor(1, false, "#123456");
        terminal.setAnsiPaletteCustomized(true);
        assertThat(HighlightColorChoices.hexFor("ansi:1", terminal)).isEqualTo("#123456");
        assertThat(HighlightColorChoices.hexFor("#abcdef", terminal)).isEqualTo("#ABCDEF");
        assertThat(HighlightColorChoices.hexFor(null, terminal)).isNull();
        assertThat(HighlightColorChoices.hexFor("#12", terminal)).isNull();
    }

    @Test
    void theLookShowsTheRulesColorsAndEffectsOnTheTerminalColors() {
        ConnectionSettings terminal = new ConnectionSettings();
        terminal.setForegroundColor("#EEEEEE");
        terminal.setBackgroundColor("#101010");

        String plain = HighlightColorChoices.lookStyle(null, terminal);
        assertThat(plain).contains("-fx-text-fill: #EEEEEE;");
        assertThat(plain).contains("-fx-background-color: #101010;");
        assertThat(plain).doesNotContain("bold");

        HighlightRule rule = new HighlightRule("x", false);
        rule.setForeground("#FF0000");
        rule.setBold(true);
        rule.setItalic(true);
        rule.setUnderline(true);
        String look = HighlightColorChoices.lookStyle(rule, terminal);
        assertThat(look).contains("-fx-text-fill: #FF0000;");
        assertThat(look).contains("-fx-background-color: #101010;");
        assertThat(look).contains("-fx-font-weight: bold;");
        assertThat(look).contains("-fx-font-style: italic;");
        assertThat(look).contains("-fx-underline: true;");

        terminal.setBackgroundColor("not a color");
        assertThat(HighlightColorChoices.lookStyle(null, terminal)).contains("-fx-background-color: #1E1E1E;");
    }
}
