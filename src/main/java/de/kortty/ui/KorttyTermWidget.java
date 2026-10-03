package de.kortty.ui;

import com.sithtermfx.core.Terminal;
import com.sithtermfx.core.TerminalMode;
import com.sithtermfx.core.TerminalOutputStream;
import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.compatibility.Point;
import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.TerminalCopyPasteHandler;
import com.sithtermfx.ui.TerminalPanel;
import com.sithtermfx.ui.settings.SettingsProvider;
import de.kortty.core.PolicyAwareCopyPasteHandler;
import de.kortty.paste.PasteSource;
import de.kortty.paste.PasteTarget;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import javafx.geometry.Dimension2D;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * korTTY's terminal widget: a {@link SithTermFxWidget} whose panel routes every copy/paste path
 * (shortcuts, context menu, middle-click/primary selection) through the policy-aware clipboard
 * handler, so the enterprise policy's internal-clipboard mode covers the terminal completely.
 *
 * <p>Once a {@link PasteHandler} is installed, every paste leaves SithTermFX's own paste code: the
 * paste action, the context menu, Edit → Paste and a local middle-click hand the text and
 * {@link #pasteTarget()} to the handler, which decides what reaches the pane. The widget also
 * reports whether the program in the pane really has bracketed paste enabled
 * ({@link #isBracketedPasteMode()}), which SithTermFX's own flag gets wrong after a terminal reset.
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

    /**
     * Reads the text of a paste. The panel's own handler is private to SithTermFX; this one behaves
     * the same (it keeps no state), including the enterprise policy's internal-clipboard mode.
     */
    private static final PolicyAwareCopyPasteHandler PASTE_READER = new PolicyAwareCopyPasteHandler();

    /** Receives a pane's pastes instead of SithTermFX's own paste code. */
    @FunctionalInterface
    public interface PasteHandler {

        /**
         * Called on the JavaFX thread for every paste into the pane.
         *
         * @param target the pane, the same object for every paste
         * @param text the clipboard or selection text, unchanged
         * @param source where the text came from
         */
        void paste(@NotNull PasteTarget target, @NotNull String text, @NotNull PasteSource source);
    }

    private final WidgetPasteTarget pasteTarget = new WidgetPasteTarget();

    private @Nullable PasteHandler pasteHandler;

    private Supplier<String> pasteTargetLabel = () -> "";

    private Supplier<Charset> pasteTargetCharset = () -> StandardCharsets.UTF_8;

    private BooleanSupplier pasteTargetBroadcast = () -> false;

    public KorttyTermWidget(int columns, int lines, SettingsProvider settingsProvider) {
        super(columns, lines, settingsProvider);
        // Replace SithTermFX's default OSC 8 provider before the pane is started: it opens file:
        // links with java.awt.Desktop.open, so remote output could launch a local program.
        setLinkInfoProvider(new KorttyOsc8LinkInfoProvider());
        // A filter runs before SithTermFX's own click handler on the canvas, so consuming the click
        // keeps SithTermFX from pasting the selection itself. Mouse reports to the program go out
        // on press and release, which this leaves alone.
        getTerminalPanel().getCanvas().addEventFilter(MouseEvent.MOUSE_CLICKED, this::pasteOnMiddleClick);
    }

    /**
     * Routes the pane's pastes to {@code handler}; null gives them back to SithTermFX. JavaFX thread.
     */
    public void setPasteHandler(@Nullable PasteHandler handler) {
        pasteHandler = handler;
    }

    /**
     * What {@link #pasteTarget()} reports about the pane beyond what the widget knows itself.
     *
     * @param label how the user knows the pane, usually the connection's name
     * @param charset the encoding the pane's connector sends text in
     * @param broadcastActive whether broadcast mode is on in the pane's tab
     */
    public void describePasteTarget(@NotNull Supplier<String> label, @NotNull Supplier<Charset> charset,
            @NotNull BooleanSupplier broadcastActive) {
        pasteTargetLabel = Objects.requireNonNull(label, "label");
        pasteTargetCharset = Objects.requireNonNull(charset, "charset");
        pasteTargetBroadcast = Objects.requireNonNull(broadcastActive, "broadcastActive");
    }

    /** The pane as a paste target: one object for the widget's whole life, keyed by the widget. */
    public @NotNull PasteTarget pasteTarget() {
        return pasteTarget;
    }

    /**
     * Whether the program in the pane has bracketed paste (DECSET 2004) enabled. SithTermFX keeps a
     * flag that a terminal reset ({@code ESC c}, the {@code reset} command) does not clear, so this
     * also asks the emulator, whose mode a reset does clear. See {@link #effectiveBracketedPasteMode}.
     */
    public boolean isBracketedPasteMode() {
        boolean panelFlag = getTerminalPanel() instanceof KorttyTerminalPanel panel && panel.bracketedPasteMode;
        return effectiveBracketedPasteMode(panelFlag, getTerminal());
    }

    /**
     * Forgets the pane's bracketed-paste state, for a new session on this widget: the program that
     * enabled it is gone, and the next one enables it again when it handles bracketed paste.
     */
    public void resetBracketedPasteMode() {
        getTerminalPanel().setBracketedPasteMode(false);
    }

    /**
     * Whether a pane is in bracketed-paste mode: the program switched it on (the flag the emulator
     * reports to the panel) and the emulator still has the mode, which a terminal reset clears
     * without telling the panel.
     *
     * @param panelFlag the last value the emulator reported through {@code setBracketedPasteMode}
     * @param terminal the pane's emulator; a terminal other than SithTermFX's is trusted on the flag
     */
    static boolean effectiveBracketedPasteMode(boolean panelFlag, @Nullable Terminal terminal) {
        return panelFlag
            && (!(terminal instanceof SithTerminal sith) || sith.isModelEnabled(TerminalMode.BracketedPasteMode));
    }

    /**
     * Whether SithTermFX would paste the selection for this click: a middle-click with paste on
     * middle-click enabled that the terminal handles itself instead of reporting it to the program.
     */
    static boolean isLocalMiddleClickPaste(@Nullable MouseButton button, boolean pasteOnMiddleClick,
            boolean localMouseAction) {
        return button == MouseButton.MIDDLE && pasteOnMiddleClick && localMouseAction;
    }

    private void pasteOnMiddleClick(MouseEvent event) {
        PasteHandler handler = pasteHandler;
        if (handler == null || event.isConsumed()) {
            return;
        }
        TerminalPanel panel = getTerminalPanel();
        if (!isLocalMiddleClickPaste(event.getButton(), getSettingsProvider().pasteOnMiddleMouseClick(),
                panel.isLocalMouseAction(event))) {
            return;
        }
        // SithTermFX focuses the pane on every click; the consumed click no longer reaches it.
        panel.getCanvas().requestFocus();
        event.consume();
        paste(handler, PasteSource.SELECTION);
    }

    private void paste(PasteHandler handler, PasteSource source) {
        String text = PASTE_READER.getContents(source == PasteSource.SELECTION);
        if (text != null) {
            handler.paste(pasteTarget, text, source);
        }
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

        /** A copy of SithTermFX's private bracketed-paste flag, which the emulator sets through the panel. */
        private volatile boolean bracketedPasteMode;

        KorttyTerminalPanel(@NotNull SettingsProvider settingsProvider, @NotNull TerminalTextBuffer terminalTextBuffer,
                @NotNull StyleState styleState) {
            super(settingsProvider, terminalTextBuffer, styleState);
        }

        @Override
        protected TerminalCopyPasteHandler createCopyPasteHandler() {
            return new PolicyAwareCopyPasteHandler();
        }

        /**
         * Every paste except the middle-click one ends here: the paste action, the context menu and
         * Edit → Paste. With a {@link PasteHandler} installed the handler gets the text instead of
         * SithTermFX, which would send embedded paste markers on as they are.
         */
        @Override
        public void handlePaste() {
            PasteHandler handler = pasteHandler;
            if (handler == null) {
                super.handlePaste();
                return;
            }
            KorttyTermWidget.this.paste(handler, PasteSource.CLIPBOARD);
        }

        @Override
        public void setBracketedPasteMode(boolean bracketedPasteModeEnabled) {
            super.setBracketedPasteMode(bracketedPasteModeEnabled);
            bracketedPasteMode = bracketedPasteModeEnabled;
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

    /**
     * The widget as a {@link PasteTarget}. It writes through the panel's terminal output stream, the
     * call and the single writer thread SithTermFX's own paste uses, so type-ahead, korTTY's input
     * filters, recordings and the session journal see a paste exactly as before.
     */
    private final class WidgetPasteTarget implements PasteTarget {

        @Override
        public Object key() {
            return KorttyTermWidget.this;
        }

        @Override
        public boolean bracketedPasteMode() {
            return isBracketedPasteMode();
        }

        @Override
        public boolean canReceive() {
            TtyConnector connector = getTtyConnector();
            return getTerminalPanel().getTerminalOutputStream() != null && connector != null && connector.isConnected();
        }

        @Override
        public Object session() {
            return getTtyConnector();
        }

        @Override
        public void send(String payload) {
            TerminalOutputStream output = getTerminalPanel().getTerminalOutputStream();
            if (output != null) {
                output.sendString(payload, true);
            }
        }

        @Override
        public String label() {
            String label = pasteTargetLabel.get();
            return label != null ? label : "";
        }

        @Override
        public Charset charset() {
            Charset charset = pasteTargetCharset.get();
            return charset != null ? charset : StandardCharsets.UTF_8;
        }

        @Override
        public boolean broadcastActive() {
            return pasteTargetBroadcast.getAsBoolean();
        }
    }
}
