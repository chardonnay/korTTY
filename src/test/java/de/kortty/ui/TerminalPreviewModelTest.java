package de.kortty.ui;

import de.kortty.core.TerminalPaletteSupport;
import de.kortty.model.Theme;
import org.testng.annotations.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static com.google.common.truth.Truth.assertThat;

/** The sample session behind the Themes tab's mock terminal, without the JavaFX toolkit. */
public class TerminalPreviewModelTest {

    private static final TerminalPreviewModel.Palette BUILT_IN = TerminalPaletteSupport::builtInHex;

    @Test
    void sampleUsesAllSixteenPaletteColours() {
        Set<String> seen = new HashSet<>();
        for (TerminalPreviewModel.Line line : TerminalPreviewModel.sampleSession(new Theme(), BUILT_IN)) {
            for (TerminalPreviewModel.Run run : line.runs()) {
                if (run.hex() != null) {
                    seen.add(run.hex().toUpperCase());
                }
            }
        }
        for (int i = 0; i < 8; i++) {
            assertThat(seen).contains(TerminalPaletteSupport.builtInHex(i, false).toUpperCase());
            assertThat(seen).contains(TerminalPaletteSupport.builtInHex(i, true).toUpperCase());
        }
    }

    @Test
    void promptLineEndsWithTheCursorAndOnlyThatLine() {
        List<TerminalPreviewModel.Line> lines = TerminalPreviewModel.sampleSession(new Theme(), BUILT_IN);
        assertThat(lines.getLast().cursorAfter()).isTrue();
        assertThat(lines.stream().filter(TerminalPreviewModel.Line::cursorAfter).count()).isEqualTo(1);
        String prompt = lines.getLast().runs().stream().map(TerminalPreviewModel.Run::text).reduce("", String::concat);
        assertThat(prompt).endsWith("$ ");
    }

    @Test
    void themeNameBecomesTheHostName() {
        Theme theme = new Theme();
        theme.setName("Solarized Dark!");
        String first = TerminalPreviewModel.sampleSession(theme, BUILT_IN).getFirst().runs().getFirst().text();
        assertThat(first).isEqualTo("user@solarized-dark");
        theme.setName("###");
        assertThat(TerminalPreviewModel.sampleSession(theme, BUILT_IN).getFirst().runs().getFirst().text())
            .isEqualTo("user@theme");
    }

    @Test
    void cursorShapeFollowsTheThemeEditorIds() {
        assertThat(TerminalPreviewModel.cursorShape("BLINK_BLOCK")).isEqualTo(TerminalPreviewModel.CursorShape.BLOCK);
        assertThat(TerminalPreviewModel.cursorShape("STEADY_BLOCK")).isEqualTo(TerminalPreviewModel.CursorShape.BLOCK);
        assertThat(TerminalPreviewModel.cursorShape("BLINK_UNDERLINE")).isEqualTo(TerminalPreviewModel.CursorShape.UNDERLINE);
        assertThat(TerminalPreviewModel.cursorShape("STEADY_UNDERLINE")).isEqualTo(TerminalPreviewModel.CursorShape.UNDERLINE);
        assertThat(TerminalPreviewModel.cursorShape("BLINK_VERTICAL_BAR")).isEqualTo(TerminalPreviewModel.CursorShape.BAR);
        assertThat(TerminalPreviewModel.cursorShape("STEADY_VERTICAL_BAR")).isEqualTo(TerminalPreviewModel.CursorShape.BAR);
        assertThat(TerminalPreviewModel.cursorShape(null)).isEqualTo(TerminalPreviewModel.CursorShape.BLOCK);
    }

    @Test
    void badHexFallsBackToTheBuiltInColour() {
        assertThat(TerminalPreviewModel.isColor("#FF8800")).isTrue();
        assertThat(TerminalPreviewModel.isColor("#f80")).isTrue();
        assertThat(TerminalPreviewModel.isColor("#FF880080")).isTrue();
        assertThat(TerminalPreviewModel.isColor("red")).isFalse();
        assertThat(TerminalPreviewModel.isColor("#GG0000")).isFalse();
        assertThat(TerminalPreviewModel.isColor(null)).isFalse();
        assertThat(TerminalPreviewModel.safeHex("not-a-colour", 1, false)).isEqualTo(TerminalPaletteSupport.builtInHex(1, false));
        assertThat(TerminalPreviewModel.safeHex("#123456", 1, false)).isEqualTo("#123456");
    }
}
