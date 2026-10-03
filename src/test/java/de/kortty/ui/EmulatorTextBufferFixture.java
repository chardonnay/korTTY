package de.kortty.ui;

import com.sithtermfx.core.HyperlinkStyle;
import com.sithtermfx.core.TerminalDisplay;
import com.sithtermfx.core.TextStyle;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.core.model.hyperlinks.LinkInfoProvider;
import com.sithtermfx.core.model.hyperlinks.TextProcessing;

import java.lang.reflect.Array;
import java.lang.reflect.Proxy;

/**
 * A text buffer driven by a real SithTermFX emulator, without a JavaFX toolkit, as korTTY's panes
 * use it: OSC 8 links are resolved by a {@link LinkInfoProvider} and no hyperlink filter is
 * registered.
 */
final class EmulatorTextBufferFixture {

    final TerminalTextBuffer buffer;
    final SithTerminal terminal;

    EmulatorTextBufferFixture(int width, int height, int historyLines, LinkInfoProvider provider) {
        StyleState styleState = new StyleState();
        TextProcessing processing = new TextProcessing(new TextStyle(),
            HyperlinkStyle.HighlightMode.HOVER_WITH_CUSTOM_COLOR);
        processing.setLinkInfoProvider(provider);
        buffer = new TerminalTextBuffer(width, height, styleState, historyLines, processing);
        processing.setTerminalTextBuffer(buffer);
        terminal = new SithTerminal(noDisplay(), buffer, styleState);
    }

    /** Writes text the way the emulator does, CR and LF included. */
    void write(String text) {
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\r' || c == '\n') {
                if (i > start) {
                    terminal.writeString(text.substring(start, i));
                }
                if (c == '\r') {
                    terminal.carriageReturn();
                } else {
                    terminal.newLine();
                }
                start = i + 1;
            }
        }
        if (start < text.length()) {
            terminal.writeString(text.substring(start));
        }
    }

    /**
     * Writes text without line breaks that wraps over several rows. The emulator hands the buffer at
     * most the rest of one row per write and wraps before the next one, so the text goes in that way.
     */
    void writeWrapping(String text) {
        int width = buffer.getWidth();
        int start = 0;
        while (start < text.length()) {
            int column = terminal.getCursorX() - 1;
            int room = column >= width ? width : width - column;
            int end = Math.min(text.length(), start + room);
            terminal.writeString(text.substring(start, end));
            start = end;
        }
    }

    /** {@code ESC ] 8 ; ; target ST text ESC ] 8 ; ; ST}. */
    void link(String target, String text) {
        terminal.setLinkUriStarted(target);
        write(text);
        terminal.setLinkUriFinished();
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
}
