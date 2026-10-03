package de.kortty.core.highlight;

import static com.google.common.truth.Truth.assertThat;

import com.sithtermfx.core.StyledTextConsumer;
import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.model.CharBuffer;
import com.sithtermfx.core.model.TerminalLine;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import org.jetbrains.annotations.NotNull;
import org.testng.annotations.Test;

/**
 * Pins why keyword highlighting bakes its styles into the cells instead of using SithTermFX's own
 * {@link TerminalLine#addCustomHighlighting} overlay. Each test describes a limit of the overlay in
 * SithTermFX 1.2.2; if a SithTermFX upgrade makes one of them fail, the overlay has changed and the
 * choice is worth re-evaluating — the failure is the reminder, not a bug in korTTY.
 */
class TerminalLineCustomHighlightingCharacterizationTest {

    private static final TerminalColor RED = TerminalColor.index(1);

    private static final TerminalColor BLUE = TerminalColor.index(4);

    /** One run the renderer would draw. */
    private record Run(int x, TextStyle style, String text) {
    }

    private static List<Run> render(TerminalLine line) {
        List<Run> runs = new ArrayList<>();
        line.process(0, new StyledTextConsumer() {
            @Override
            public void consume(int x, int y, @NotNull TextStyle style, @NotNull CharBuffer characters, int startRow) {
                runs.add(new Run(x, style, characters.toString()));
            }

            @Override
            public void consumeNul(int x, int y, int nulIndex, @NotNull TextStyle style, @NotNull CharBuffer characters,
                                   int startRow) {
            }

            @Override
            public void consumeQueue(int x, int y, int nulIndex, int startRow) {
            }
        }, 0);
        return runs;
    }

    private static TextStyle styleOf(List<Run> runs, String text) {
        for (Run run : runs) {
            if (run.text().equals(text)) {
                return run.style();
            }
        }
        throw new AssertionError("no run " + text + " in " + runs);
    }

    private static TerminalLine line(String text) {
        return new TerminalLine(new TerminalLine.TextEntry(TextStyle.EMPTY, new CharBuffer(text)));
    }

    @Test
    void onlyTheFirstIntervalOfALineIsRendered() {
        TerminalLine line = line("aaa bbb");
        line.addCustomHighlighting(0, 3, new TextStyle(RED, null));
        line.addCustomHighlighting(4, 3, new TextStyle(BLUE, null));

        List<Run> runs = render(line);

        assertThat(styleOf(runs, "aaa").getForeground()).isEqualTo(RED);
        assertThat(styleOf(runs, " bbb").getForeground()).isNull();
    }

    @Test
    void mergingTheOverlayDropsBoldAndUnderline() {
        TerminalLine line = line("aaa bbb");
        line.addCustomHighlighting(0, 3,
            new TextStyle(RED, null, EnumSet.of(TextStyle.Option.BOLD, TextStyle.Option.UNDERLINED)));

        TextStyle drawn = styleOf(render(line), "aaa");

        assertThat(drawn.getForeground()).isEqualTo(RED);
        assertThat(drawn.hasOption(TextStyle.Option.BOLD)).isFalse();
        assertThat(drawn.hasOption(TextStyle.Option.UNDERLINED)).isFalse();
    }

    @Test
    void aCopyOfTheLineLosesTheOverlay() {
        TerminalLine line = line("aaa bbb");
        line.addCustomHighlighting(0, 3, new TextStyle(RED, null));

        TerminalLine copy = line.copy();

        assertThat(styleOf(render(line), "aaa").getForeground()).isEqualTo(RED);
        assertThat(render(copy)).hasSize(1);
        assertThat(render(copy).get(0).style().getForeground()).isNull();
    }

    @Test
    void aWidthReflowLosesTheOverlay() {
        HeadlessTerminalSession session = new HeadlessTerminalSession(20, 5);
        session.print("aaa bbb");
        session.line(0).addCustomHighlighting(0, 3, new TextStyle(RED, null));
        assertThat(styleOf(render(session.line(0)), "aaa").getForeground()).isEqualTo(RED);

        session.resize(10, 5);

        TerminalLine reflowed = session.line(0);
        assertThat(reflowed.getText()).isEqualTo("aaa bbb");
        for (Run run : render(reflowed)) {
            assertThat(run.style().getForeground()).isNull();
        }
    }
}
