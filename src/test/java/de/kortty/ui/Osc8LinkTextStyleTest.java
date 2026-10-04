package de.kortty.ui;

import com.sithtermfx.core.ArrayTerminalDataStream;
import com.sithtermfx.core.HyperlinkStyle;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.emulator.SithEmulator;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.core.model.hyperlinks.TextProcessing;
import de.kortty.core.TerminalRecordingStyleRun;
import de.kortty.core.TerminalScreenRenderer;
import org.testng.annotations.Test;

import java.io.IOException;
import java.net.URI;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * What SithTermFX 1.2.3 changed on purpose for OSC 8 links, read through the real emulator with
 * korTTY's link provider and checked where korTTY sees it: in the cell styles and in the style runs
 * recordings store ({@link TerminalScreenRenderer#styleRuns}). SGR inside a link applies to the
 * link text, SGR 0 resets the text but keeps the link open, bold and inverse survive on links, and
 * closing a link keeps the current SGR state, as in xterm. Up to 1.2.2 SGR inside a link was
 * ignored, and SGR 0 ended it.
 */
public class Osc8LinkTextStyleTest {

    private static final String ESC = "\u001B";
    private static final String ST = ESC + "\\";
    private static final String TARGET = "https://example.com/docs";

    @Test
    public void sgrInsideALinkStylesItsTextAndSgrZeroKeepsTheLink() throws IOException {
        Screen screen = new Screen();
        screen.interpret(ESC + "[1;31m" + ESC + "]8;;" + TARGET + ST + "bold" + ESC + "[0m" + "plain"
            + ESC + "[7m" + "inv" + ESC + "]8;;" + ST + "after");

        // Every char of the link text is still in the one link, SGR 0 included.
        for (int column = 0; column < "boldplaininv".length(); column++) {
            TextStyle style = screen.buffer.getStyleAt(column, 0);
            assertWithMessage("column %s", column).that(style).isInstanceOf(HyperlinkStyle.class);
            assertWithMessage("column %s", column).that(((KorttyLinkInfo) ((HyperlinkStyle) style).getLinkInfo()).target())
                .isEqualTo(URI.create(TARGET));
        }
        assertThat(screen.buffer.getStyleAt("boldplaininv".length(), 0)).isNotInstanceOf(HyperlinkStyle.class);

        TerminalRecordingStyleRun bold = screen.run("bold");
        assertThat(bold.options()).containsExactly("BOLD");
        assertThat(bold.foreground()).isNotNull();
        TerminalRecordingStyleRun plain = screen.run("plain");
        assertThat(plain.options()).isEmpty();
        assertThat(plain.foreground()).isNull();
        assertThat(screen.run("inv").options()).containsExactly("INVERSE");
        // Closing the link keeps the SGR state that was current inside it.
        assertThat(screen.run("after").options()).containsExactly("INVERSE");
    }

    @Test
    public void aLinkStartedInsideAnotherReplacesIt() throws IOException {
        Screen screen = new Screen();
        screen.interpret(ESC + "]8;;https://example.com/outer" + ST + "out" + ESC + "]8;;" + TARGET + ST + "in"
            + ESC + "]8;;" + ST);

        HyperlinkStyle inner = (HyperlinkStyle) screen.buffer.getStyleAt(3, 0);
        assertThat(((KorttyLinkInfo) inner.getLinkInfo()).target()).isEqualTo(URI.create(TARGET));
        assertThat(inner.getPrevTextStyle()).isNotInstanceOf(HyperlinkStyle.class);
        HyperlinkStyle outer = (HyperlinkStyle) screen.buffer.getStyleAt(0, 0);
        assertThat(((KorttyLinkInfo) outer.getLinkInfo()).target()).isEqualTo(URI.create("https://example.com/outer"));
    }

    /** A 40x3 screen read by the real emulator, with korTTY's OSC 8 provider and no link filter. */
    private static final class Screen {
        final TerminalTextBuffer buffer;
        final SithTerminal terminal;

        Screen() {
            StyleState styleState = new StyleState();
            TextProcessing processing = new TextProcessing(new TextStyle(), HyperlinkStyle.HighlightMode.HOVER_WITH_BOTH_COLORS);
            processing.setLinkInfoProvider(new KorttyOsc8LinkInfoProvider());
            buffer = new TerminalTextBuffer(40, 3, styleState, 10, processing);
            processing.setTerminalTextBuffer(buffer);
            terminal = new SithTerminal(new OscEmulatorHarness.CountingDisplay(), buffer, styleState);
        }

        void interpret(String output) throws IOException {
            SithEmulator emulator = new SithEmulator(new ArrayTerminalDataStream(output.toCharArray()), terminal);
            while (emulator.hasNext()) {
                emulator.next();
            }
        }

        TerminalRecordingStyleRun run(String text) {
            List<TerminalRecordingStyleRun> runs = TerminalScreenRenderer.styleRuns(buffer, TerminalScreenRenderer.Palette.DEFAULT);
            return runs.stream()
                .filter(candidate -> candidate.text().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no run '" + text + "' in " + runs));
        }
    }
}
