package de.kortty.ui;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.util.OptionalDouble;
import org.testng.annotations.Test;

/**
 * The scroll-bar value for a prompt jump, checked against SithTermFX's own mapping from value to
 * scroll origin ({@code TerminalPanel.resolveSwingScrollBarValue}, reproduced in
 * {@link TerminalScrollSupport#originForScrollValue}): whatever line is asked for, SithTermFX then
 * shows exactly that line at the top, or the nearest one it can.
 */
class TerminalScrollSupportTest {

    @Test
    void everyLineLandsAtTheTopOrAsCloseAsTheScrollbackAllows() {
        for (int history : new int[] {0, 1, 500, 10_000}) {
            for (int rows : new int[] {5, 40}) {
                // How SithTermFX sets up its bar: min = -history, max = rows, visible = rows.
                double min = -history;
                double max = rows;
                double visible = rows;
                for (long line = 0; line < history + rows; line += history > 1_000 ? 37 : 1) {
                    int origin = TerminalScrollSupport.originForLine(line, history);
                    int expectedOrigin = (int) Math.max(-history, Math.min(0, line - history));
                    assertThat(origin).isEqualTo(expectedOrigin);
                    OptionalDouble value = TerminalScrollSupport.scrollValueForOrigin(origin, min, max, visible);
                    if (history == 0) {
                        assertWithMessage("without scrollback the bar cannot move").that(value.isPresent()).isFalse();
                        continue;
                    }
                    assertThat(value.isPresent()).isTrue();
                    assertThat(value.getAsDouble()).isAtLeast(min);
                    assertThat(value.getAsDouble()).isAtMost(max);
                    int shown = TerminalScrollSupport.originForScrollValue(value.getAsDouble(), min, max, visible);
                    assertWithMessage("history " + history + ", rows " + rows + ", line " + line)
                        .that(shown).isEqualTo(expectedOrigin);
                    if (line >= history) {
                        assertWithMessage("a line on the last screen shows with the view at the bottom")
                            .that(value.getAsDouble()).isEqualTo(max);
                    }
                }
            }
        }
    }

    @Test
    void theFirstScrollbackLineIsTheTopOfTheBar() {
        OptionalDouble value = TerminalScrollSupport.scrollValueForOrigin(-500, -500, 40, 40);
        assertThat(value.getAsDouble()).isEqualTo(-500.0);
    }

    @Test
    void originsOutsideTheScrollbackAreClamped() {
        assertThat(TerminalScrollSupport.originForLine(-20, 100)).isEqualTo(-100);
        assertThat(TerminalScrollSupport.originForLine(10_000, 100)).isEqualTo(0);
        assertThat(TerminalScrollSupport.originForLine(5, -3)).isEqualTo(0);
        OptionalDouble below = TerminalScrollSupport.scrollValueForOrigin(-900, -100, 40, 40);
        assertThat(TerminalScrollSupport.originForScrollValue(below.getAsDouble(), -100, 40, 40)).isEqualTo(-100);
        OptionalDouble above = TerminalScrollSupport.scrollValueForOrigin(25, -100, 40, 40);
        assertThat(above.getAsDouble()).isEqualTo(40.0);
    }

    @Test
    void aBarWithoutRangeCannotScroll() {
        assertThat(TerminalScrollSupport.scrollValueForOrigin(0, 0, 0, 0).isPresent()).isFalse();
        assertThat(TerminalScrollSupport.scrollValueForOrigin(0, 10, 10, 0).isPresent()).isFalse();
        assertThat(TerminalScrollSupport.originForScrollValue(3, 10, 10, 0)).isEqualTo(0);
    }
}
