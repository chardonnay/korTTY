package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.codingagent.ScreenSnapshot;
import de.kortty.codingagent.TerminalScreenCapture;
import de.kortty.control.PaneReader;
import de.kortty.control.PaneText;
import de.kortty.control.ReadMode;
import java.util.List;
import java.util.Objects;

/**
 * The control API's read handle for one pane: resolved in a single JavaFX hop, used afterwards on a
 * connection or timer thread.
 *
 * <p><strong>Any thread, and deliberately never the JavaFX application thread for {@link #read}.</strong>
 * Both modes take the SithTermFX text-buffer lock for microseconds and touch no scene node, which is
 * what keeps a ten-thousand-line scrollback read — and {@code pane.wait_output}'s poll loop — entirely
 * off the UI thread. Nothing here registers a listener, so a connection that dies mid-wait leaves
 * nothing behind to unregister.
 *
 * <p>The handle keeps a strong reference to the widget on purpose: a pane closed between resolution
 * and read must answer an empty text rather than a {@code NullPointerException}, and that requires the
 * widget to still be reachable.
 */
public final class ControlApiPaneReader implements PaneReader {

    private final String paneId;

    private final SithTermFxWidget widget;

    private final TerminalView view;

    /**
     * @param paneId the wire pane id this handle reads
     * @param widget the pane's widget, captured on the JavaFX thread
     * @param view the tab holding it, used for the locked scrollback read
     */
    ControlApiPaneReader(String paneId, SithTermFxWidget widget, TerminalView view) {
        this.paneId = Objects.requireNonNull(paneId, "paneId");
        this.widget = Objects.requireNonNull(widget, "widget");
        this.view = Objects.requireNonNull(view, "view");
    }

    @Override
    public String paneId() {
        return paneId;
    }

    /**
     * Whether the pane still has a live session.
     *
     * <p>Answered from the widget's own connector rather than by walking the split tree: that tree is
     * rebuilt on the JavaFX thread on every split and close, and a poll from the timer thread must not
     * race it. Closing a pane closes its connector with it, so a pane that has gone away reports false
     * here and {@link #read} then yields an empty text.
     */
    @Override
    public boolean isOpen() {
        if (widget.getTerminalTextBuffer() == null) {
            return false;
        }
        TtyConnector connector = widget.getTtyConnector();
        return connector != null && connector.isConnected();
    }

    /**
     * Captures the pane's text.
     *
     * <p>{@link ReadMode#DETECTION} never reaches a reader — {@code pane.read} answers it from the
     * monitor's already-published snapshot without resolving a handle — so it is treated as
     * {@link ReadMode#VISIBLE} rather than given a shape of its own.
     */
    @Override
    public PaneText read(ReadMode mode, int maxLines) {
        if (mode == ReadMode.RECENT) {
            return readRecent(maxLines);
        }
        return PaneText.visible(paneId, TerminalScreenCapture.capture(widget));
    }

    /**
     * History buffer plus screen under one lock, then the geometry.
     *
     * <p>Two lock acquisitions rather than one: the scrollback read is the expensive half and the
     * geometry read is three field reads, so holding one lock across both would only widen the window
     * in which the emulator thread is blocked for no gain.
     */
    private PaneText readRecent(int maxLines) {
        List<String> lines = view.readPaneScrollback(widget, maxLines);
        ScreenSnapshot geometry = TerminalScreenCapture.capture(widget);
        return PaneText.recent(paneId, lines, geometry.columns(), geometry.rows(), geometry.oscTitle(),
            false);
    }
}
