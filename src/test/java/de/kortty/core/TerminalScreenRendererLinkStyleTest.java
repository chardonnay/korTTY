package de.kortty.core;

import com.sithtermfx.core.HyperlinkStyle;
import com.sithtermfx.core.TerminalColor;
import com.sithtermfx.core.TerminalDisplay;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.core.model.hyperlinks.LinkInfo;
import com.sithtermfx.core.model.hyperlinks.TextProcessing;
import org.testng.annotations.Test;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * A cell inside an OSC 8 link holds a {@link HyperlinkStyle}, which has no colours or attributes of
 * its own; the text's style sits inside it. Recordings read the cell styles through
 * {@link TerminalScreenRenderer#styleRuns}, so linked text came out in the default colour and without
 * bold. These cases pin that the renderer reports the style the text was written with, which since
 * SithTermFX 1.2.3 is {@link HyperlinkStyle#getUnderlyingStyle()}.
 */
public class TerminalScreenRendererLinkStyleTest {

    private static final TerminalColor RED = TerminalColor.rgb(0xCC, 0x22, 0x11);
    private static final TerminalColor NAVY = TerminalColor.rgb(0x10, 0x20, 0x30);
    private static final TerminalColor LINK_BLUE = TerminalColor.rgb(0x00, 0x00, 0xFF);
    private static final TerminalColor LINK_WHITE = TerminalColor.rgb(0xFF, 0xFF, 0xFF);

    private static final TextStyle BOLD_RED_ON_NAVY = new TextStyle.Builder()
        .setForeground(RED)
        .setBackground(NAVY)
        .setOption(TextStyle.Option.BOLD, true)
        .build();

    @Test
    public void osc8LinkReportsTheColoursAndBoldOfItsText() {
        Screen screen = new Screen();
        screen.styleState.setCurrent(BOLD_RED_ON_NAVY);

        // ESC ] 8 ; ; https://example.com/notes ST notes.txt ESC ] 8 ; ; ST
        screen.terminal.setLinkUriStarted("https://example.com/notes");
        screen.terminal.writeString("notes.txt");
        screen.terminal.setLinkUriFinished();

        TextStyle cell = screen.buffer.getStyleAt(0, 0);
        assertThat(cell).isInstanceOf(HyperlinkStyle.class);
        assertThat(cell.getForeground()).isNull(); // what the renderer used to report

        TerminalRecordingStyleRun run = screen.run("notes.txt");
        assertThat(run.foreground()).isEqualTo("#CC2211");
        assertThat(run.background()).isEqualTo("#102030");
        assertThat(run.options()).containsExactly("BOLD");
    }

    @Test
    public void osc8LinkWithAnIndexedColourUsesThePalette() {
        Screen screen = new Screen();
        screen.styleState.setCurrent(new TextStyle.Builder()
            .setForeground(TerminalColor.index(4))
            .setOption(TextStyle.Option.BOLD, true)
            .build());

        screen.terminal.setLinkUriStarted("https://example.com/");
        screen.terminal.writeString("docs");
        screen.terminal.setLinkUriFinished();

        TerminalScreenRenderer.Palette boldAsBright = new TerminalScreenRenderer.Palette(
            TerminalScreenRenderer::defaultAnsiColor, true);
        TerminalRecordingStyleRun run = screen.run("docs", boldAsBright);
        assertThat(run.foreground()).isEqualTo("#5C5CFF"); // bright blue: bold counts as bright
        assertThat(run.options()).contains("BOLD");
    }

    @Test
    public void brightColourModeWithoutTheBoldFontDropsTheBoldOptionButKeepsTheBrightColour() {
        Screen screen = new Screen();
        screen.styleState.setCurrent(new TextStyle.Builder()
            .setForeground(TerminalColor.index(1))
            .setOption(TextStyle.Option.BOLD, true)
            .build());
        screen.terminal.writeString("err");

        TerminalScreenRenderer.Palette brightOnly = new TerminalScreenRenderer.Palette(
            TerminalScreenRenderer::defaultAnsiColor, true, false);
        TerminalRecordingStyleRun bright = screen.run("err", brightOnly);
        assertThat(bright.foreground()).isEqualTo("#FF0000");
        assertThat(bright.options()).doesNotContain("BOLD");

        TerminalRecordingStyleRun plain = screen.run("err", TerminalScreenRenderer.Palette.DEFAULT);
        assertThat(plain.foreground()).isEqualTo("#CD0000");
        assertThat(plain.options()).contains("BOLD");
    }

    @Test
    public void linkOpenedInsideAnotherLinkReplacesItAndReportsItsText() {
        Screen screen = new Screen();
        screen.styleState.setCurrent(BOLD_RED_ON_NAVY);

        screen.terminal.setLinkUriStarted("https://example.com/outer");
        screen.terminal.setLinkUriStarted("https://example.com/inner");
        screen.terminal.writeString("inner");

        // SithTermFX 1.2.3: the second link replaces the first instead of nesting inside it.
        assertThat(((HyperlinkStyle) screen.buffer.getStyleAt(0, 0)).getPrevTextStyle())
            .isNotInstanceOf(HyperlinkStyle.class);
        TerminalRecordingStyleRun run = screen.run("inner");
        assertThat(run.foreground()).isEqualTo("#CC2211");
        assertThat(run.options()).containsExactly("BOLD");
    }

    @Test
    public void linkDrawnOverExistingTextReportsTheOriginalStyle() {
        // The shape a vendor link filter in original-colour mode leaves behind.
        HyperlinkStyle link = new HyperlinkStyle(LINK_BLUE, LINK_WHITE, new LinkInfo(() -> { }),
            HyperlinkStyle.HighlightMode.HOVER_WITH_ORIGINAL_COLOR, null, BOLD_RED_ON_NAVY);

        assertThat(TerminalScreenRenderer.drawnStyle(link)).isSameInstanceAs(BOLD_RED_ON_NAVY);

        Screen screen = new Screen();
        screen.styleState.setCurrent(link);
        screen.terminal.writeString("match");
        TerminalRecordingStyleRun run = screen.run("match");
        assertThat(run.foreground()).isEqualTo("#CC2211");
        assertThat(run.background()).isEqualTo("#102030");
    }

    @Test
    public void linkWithoutTextStyleReportsWhatSithTermFxDrawsBeneathIt() {
        // The shape a vendor link filter in custom-colour mode leaves behind: only the link colours,
        // which SithTermFX draws on hover. Not hovered, the text beneath has none of its own.
        HyperlinkStyle link = new HyperlinkStyle(LINK_BLUE, LINK_WHITE, new LinkInfo(() -> { }),
            HyperlinkStyle.HighlightMode.HOVER_WITH_CUSTOM_COLOR, null, null);

        TextStyle drawn = TerminalScreenRenderer.drawnStyle(link);

        assertThat(drawn).isNotInstanceOf(HyperlinkStyle.class);
        assertThat(drawn.getForeground()).isEqualTo(link.getUnderlyingStyle().getForeground());
        assertThat(drawn.getBackground()).isEqualTo(link.getUnderlyingStyle().getBackground());
    }

    @Test
    public void plainTextIsUnchanged() {
        assertThat(TerminalScreenRenderer.drawnStyle(BOLD_RED_ON_NAVY)).isSameInstanceAs(BOLD_RED_ON_NAVY);
        assertThat(TerminalScreenRenderer.drawnStyle(null)).isNull();

        Screen screen = new Screen();
        screen.styleState.setCurrent(BOLD_RED_ON_NAVY);
        screen.terminal.writeString("plain");
        screen.styleState.setCurrent(new TextStyle.Builder()
            .setForeground(NAVY)
            .setOption(TextStyle.Option.UNDERLINED, true)
            .build());
        screen.terminal.writeString(" under");

        TerminalRecordingStyleRun plain = screen.run("plain");
        assertThat(plain.foreground()).isEqualTo("#CC2211");
        assertThat(plain.background()).isEqualTo("#102030");
        assertThat(plain.options()).containsExactly("BOLD");
        TerminalRecordingStyleRun under = screen.run(" under");
        assertThat(under.foreground()).isEqualTo("#102030");
        assertThat(under.background()).isNull();
        assertThat(under.options()).containsExactly("UNDERLINED");
    }

    /** A 40x3 screen whose OSC 8 links are accepted, like a pane with korTTY's link provider. */
    private static final class Screen {
        final StyleState styleState = new StyleState();
        final TerminalTextBuffer buffer;
        final SithTerminal terminal;

        Screen() {
            TextProcessing processing = new TextProcessing(new TextStyle(LINK_BLUE, LINK_WHITE),
                HyperlinkStyle.HighlightMode.HOVER_WITH_CUSTOM_COLOR);
            processing.setLinkInfoProvider(uri -> new LinkInfo(() -> { }));
            buffer = new TerminalTextBuffer(40, 3, styleState, 100, processing);
            processing.setTerminalTextBuffer(buffer);
            terminal = new SithTerminal(noDisplay(), buffer, styleState);
        }

        TerminalRecordingStyleRun run(String text) {
            return run(text, TerminalScreenRenderer.Palette.DEFAULT);
        }

        TerminalRecordingStyleRun run(String text, TerminalScreenRenderer.Palette palette) {
            List<TerminalRecordingStyleRun> runs = TerminalScreenRenderer.styleRuns(buffer, palette);
            return runs.stream()
                .filter(candidate -> candidate.text().equals(text))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no run '" + text + "' in " + runs));
        }
    }

    /** A display that ignores everything; SithTermFX's own test doubles are not published. */
    private static TerminalDisplay noDisplay() {
        return (TerminalDisplay) Proxy.newProxyInstance(TerminalDisplay.class.getClassLoader(),
            new Class<?>[] {TerminalDisplay.class}, (proxy, method, args) -> {
                Class<?> type = method.getReturnType();
                if (type.isPrimitive() && type != void.class) {
                    return Array.get(Array.newInstance(type, 1), 0);
                }
                return type == String.class ? "" : null;
            });
    }

    @Test
    public void everyRunOfAnOsc8LineKeepsItsOwnColour() {
        // A listing that links every name: each name is its own link in its own colour.
        Screen screen = new Screen();
        String[][] names = {{"bin", "#0000EE"}, {"notes.txt", "#E5E5E5"}, {"run.sh", "#00CD00"}};
        int[] indexes = {4, 7, 2};
        for (int i = 0; i < names.length; i++) {
            screen.styleState.setCurrent(new TextStyle(TerminalColor.index(indexes[i]), null));
            screen.terminal.setLinkUriStarted("https://example.com/" + names[i][0]);
            screen.terminal.writeString(names[i][0]);
            screen.terminal.setLinkUriFinished();
            screen.styleState.setCurrent(new TextStyle());
            screen.terminal.writeString(" ");
        }
        for (String[] name : names) {
            assertWithMessage(name[0]).that(screen.run(name[0]).foreground()).isEqualTo(name[1]);
        }
    }
}
