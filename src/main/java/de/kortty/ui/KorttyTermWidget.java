package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.TerminalCopyPasteHandler;
import com.sithtermfx.ui.TerminalPanel;
import com.sithtermfx.ui.settings.SettingsProvider;
import de.kortty.core.PolicyAwareCopyPasteHandler;
import javafx.geometry.Dimension2D;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * korTTY's terminal widget: a {@link SithTermFxWidget} whose panel routes every copy/paste path
 * (shortcuts, context menu, middle-click/primary selection) through the policy-aware clipboard
 * handler, so the enterprise policy's internal-clipboard mode covers the terminal completely.
 *
 * <p>It also exposes the context-menu commands as {@link TerminalPaneActions}, calling SithTermFX's
 * public API directly. The panel is a subclass, {@link KorttyTerminalPanel}, so a declared-method
 * lookup on its runtime class misses every SithTermFX method that it does not override itself.
 *
 * <p>OSC 8 links go through {@link KorttyOsc8LinkInfoProvider}, which opens only web and mail links.
 */
public class KorttyTermWidget extends SithTermFxWidget implements TerminalPaneActions {

    /** Points per Increase/Decrease step of the context menu's font-size submenu. */
    static final float FONT_SIZE_STEP = 2f;

    public KorttyTermWidget(int columns, int lines, SettingsProvider settingsProvider) {
        super(columns, lines, settingsProvider);
        // Replace SithTermFX's default OSC 8 provider before the pane is started: it opens file:
        // links with java.awt.Desktop.open, so remote output could launch a local program.
        setLinkInfoProvider(new KorttyOsc8LinkInfoProvider());
    }

    @Override
    protected TerminalPanel createTerminalPanel(@NotNull SettingsProvider settingsProvider,
            @NotNull StyleState styleState, @NotNull TerminalTextBuffer terminalTextBuffer) {
        // Runs inside the SithTermFxWidget constructor, before this widget's own fields are set.
        return new KorttyTerminalPanel(settingsProvider, terminalTextBuffer, styleState);
    }

    @Override
    public void copySelection() {
        // Keep the selection, and use the regular clipboard rather than the X11 primary selection.
        getTerminalPanel().handleCopy(false, false);
    }

    @Override
    public void paste() {
        getTerminalPanel().handlePaste();
    }

    @Override
    public void clearBuffer() {
        getTerminalPanel().clearBuffer();
    }

    @Override
    public void showFind() {
        showFindComponent();
    }

    @Override
    public void increaseFontSize() {
        increaseFontSize(FONT_SIZE_STEP);
    }

    @Override
    public void decreaseFontSize() {
        decreaseFontSize(FONT_SIZE_STEP);
    }

    /**
     * The widget's terminal panel, korTTY's subclass of SithTermFX's {@link TerminalPanel}. korTTY's
     * overrides of the panel's methods belong here. It is an inner class because
     * {@code clearBuffer(boolean)} needs the widget's terminal.
     */
    public final class KorttyTerminalPanel extends TerminalPanel {

        KorttyTerminalPanel(@NotNull SettingsProvider settingsProvider, @NotNull TerminalTextBuffer terminalTextBuffer,
                @NotNull StyleState styleState) {
            super(settingsProvider, terminalTextBuffer, styleState);
        }

        @Override
        protected TerminalCopyPasteHandler createCopyPasteHandler() {
            return new PolicyAwareCopyPasteHandler();
        }

        @Override
        protected void clearBuffer(boolean keepLastLine) {
            super.clearBuffer(keepLastLine);
            // When SithTermFX keeps the prompt line it moves the emulator cursor to row 0, one row
            // above the screen (rows count from 1). Until the next output corrects it, every
            // repaint logs two "line out of bounds" errors, and a line feed lands on the kept
            // prompt line instead of below it. Clamp it back onto the screen the way the next
            // write would. This covers the context menu and SithTermFX's own clear shortcut.
            if (KorttyTermWidget.this.getTerminal() instanceof SithTerminal terminal) {
                terminal.scrollY();
            }
        }

        /**
         * The cell under a point in canvas coordinates, mapped the way SithTermFX's own selection
         * and link hit-testing map it. See {@link TerminalCellGeometry#cellAt(double, double)}.
         *
         * @return the column ({@code x}) and buffer line ({@code y}, negative in the history), or
         *         {@code null} when the point is on no cell of the text buffer
         */
        public @Nullable Point cellAt(double x, double y) {
            TerminalCellGeometry geometry = cellGeometry();
            return geometry != null ? geometry.cellAt(x, y) : null;
        }

        /**
         * A snapshot of the panel's current cell grid, including the cell width and the left inset
         * that SithTermFX keeps protected. Call it on the FX thread and use it right away: a
         * scroll, resize or font change makes it stale.
         *
         * @return the geometry, or {@code null} before the panel has measured its font
         */
        public @Nullable TerminalCellGeometry cellGeometry() {
            Dimension2D charSize = myCharSize;
            if (charSize == null) {
                return null;
            }
            TerminalTextBuffer buffer = getTerminalTextBuffer();
            // The laid-out terminal size is private to TerminalPanel. Its public pixel size is that
            // many cells of a whole-pixel cell size (plus the inset), so dividing recovers it exactly.
            int columns = (int) Math.round((getPixelWidth() - getInsetX()) / charSize.getWidth());
            int rows = (int) Math.round(getPixelHeight() / charSize.getHeight());
            return new TerminalCellGeometry(getInsetX(), charSize.getWidth(), charSize.getHeight(), columns, rows,
                getScrollOrigin(), buffer.getWidth(), buffer.getHeight(), buffer.getHistoryLinesCount());
        }
    }
}
