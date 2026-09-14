package de.kortty.codingagent;

import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.TerminalPanel;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Captures the visible screen of a SithTermFX widget as a {@link ScreenSnapshot}. This is the only
 * class of the package that touches the terminal library; it reads the text buffer under its own
 * lock (held only for the three getters plus the OSC window title read, microseconds). It
 * is safe to call from the detection scheduler thread and never touches JavaFX nodes.
 */
public final class TerminalScreenCapture {

    private static final String PANE_ID_PREFIX = "terminal-";

    private TerminalScreenCapture() {}

    /**
     * Captures the current screen; {@link ScreenSnapshot#EMPTY} when the widget or its buffer is
     * absent. The rows are normalised by {@link ScreenSnapshot#ofLines} (trailing empty element
     * dropped, every row right-trimmed).
     */
    public static ScreenSnapshot capture(SithTermFxWidget widget) {
        if (widget == null) {
            return ScreenSnapshot.EMPTY;
        }
        TerminalTextBuffer textBuffer = widget.getTerminalTextBuffer();
        if (textBuffer == null) {
            return ScreenSnapshot.EMPTY;
        }
        TerminalPanel panel = widget.getTerminalPanel();
        String title;
        String text;
        int columns;
        boolean alternateScreen;
        textBuffer.lock();
        try {
            // The OSC title is a plain field written on the emulator thread; reading it after the
            // lock acquisition gives it the same happens-before edge as the screen it is paired with.
            title = panel != null ? panel.getWindowTitle() : null;
            text = textBuffer.getScreenLines();
            columns = textBuffer.getWidth();
            alternateScreen = textBuffer.isUsingAlternateBuffer();
        } finally {
            textBuffer.unlock();
        }
        List<String> rows = text == null ? List.of() : Arrays.asList(text.split("\n", -1));
        return ScreenSnapshot.ofLines(rows, columns, title, alternateScreen);
    }

    /** A screen source for {@link CodingAgentService#attach} that captures {@code widget} on every call. */
    public static Supplier<ScreenSnapshot> sourceFor(SithTermFxWidget widget) {
        Objects.requireNonNull(widget, "widget");
        return () -> capture(widget);
    }

    /** Stable pane id for the widget's lifetime ({@code "terminal-<identityHashCode hex>"}, the recording precedent). */
    public static String paneIdOf(SithTermFxWidget widget) {
        Objects.requireNonNull(widget, "widget");
        return PANE_ID_PREFIX + Integer.toHexString(System.identityHashCode(widget));
    }
}
