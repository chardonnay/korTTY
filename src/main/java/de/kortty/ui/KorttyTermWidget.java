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
import de.kortty.core.TerminalLinkDetector;
import de.kortty.ui.TerminalLinkClickPolicy.Hit;
import de.kortty.ui.TerminalLinkClickPolicy.HitKind;
import javafx.application.Platform;
import javafx.geometry.Dimension2D;
import javafx.scene.layout.Pane;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

/**
 * korTTY's terminal widget: a {@link SithTermFxWidget} whose panel routes every copy/paste path
 * (shortcuts, context menu, middle-click/primary selection) through the policy-aware clipboard
 * handler, so the enterprise policy's internal-clipboard mode covers the terminal completely.
 *
 * <p>It also exposes the context-menu commands as {@link TerminalPaneActions}, calling SithTermFX's
 * public API directly. The panel is a subclass, {@link KorttyTerminalPanel}, so a declared-method
 * lookup on its runtime class misses every SithTermFX method that it does not override itself.
 *
 * <p>OSC 8 links go through {@link KorttyOsc8LinkInfoProvider}, which keeps only web and mail links,
 * and open only on a Cmd/Ctrl+click through {@link TerminalLinkClickPolicy}. The same click opens a
 * web or e-mail address printed as plain text, which {@link TerminalLinkResolver} finds on demand
 * for the kinds set with {@link #setPlainTextLinkKinds}; none until then. Resting the mouse on a link
 * shows its target ({@link TerminalLinkHoverController}), and an OSC 8 link whose text names another
 * host than it opens asks first ({@link TerminalLinkMismatchDialog}).
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
     * Sets which kinds of links a Cmd/Ctrl+click finds in plain text, such as
     * {@link TerminalLinkResolver#WEB_LINK_KINDS}. The supplier is asked on every click, so a change
     * of the setting behind it applies at once; an empty set leaves only OSC 8 links. Call it on the
     * JavaFX thread.
     */
    public void setPlainTextLinkKinds(@NotNull Supplier<Set<TerminalLinkDetector.Kind>> kinds) {
        ((KorttyTerminalPanel) getTerminalPanel()).setPlainTextLinkKinds(kinds);
    }

    /**
     * Sets where the underline of a hovered link is drawn: the pane's {@code LINKS} overlay layer,
     * which {@code TerminalSplitPane} creates on first use. The supplier may return {@code null}
     * while there is no layer; the hover then shows the cursor and the tooltip only.
     */
    public void setLinkOverlay(@NotNull Supplier<Pane> overlay) {
        ((KorttyTerminalPanel) getTerminalPanel()).setLinkOverlay(overlay);
    }

    /**
     * The widget's terminal panel, korTTY's subclass of SithTermFX's {@link TerminalPanel}. korTTY's
     * overrides of the panel's methods belong here. It is an inner class because
     * {@code clearBuffer(boolean)} needs the widget's terminal.
     */
    public final class KorttyTerminalPanel extends TerminalPanel {

        /** Where a Cmd/Ctrl+click sends a link. */
        private TerminalLinkOpener linkOpener = TerminalLinkOpener.system();

        /** The kinds of links found in plain text; none until the terminal view sets them. */
        private Supplier<Set<TerminalLinkDetector.Kind>> plainTextLinkKinds = Set::of;

        /** The pane's overlay layer for the hover underline; none until the split pane sets it. */
        private Supplier<Pane> linkOverlay = () -> null;

        /** Asks before an OSC 8 link whose text names another host opens. */
        private TerminalLinkMismatchDialog.Confirmation mismatchConfirmation = TerminalLinkMismatchDialog::confirm;

        private final TerminalLinkHoverController linkHover;

        KorttyTerminalPanel(@NotNull SettingsProvider settingsProvider, @NotNull TerminalTextBuffer terminalTextBuffer,
                @NotNull StyleState styleState) {
            super(settingsProvider, terminalTextBuffer, styleState);
            TerminalLinkResolver links = new TerminalLinkResolver(() -> plainTextLinkKinds.get());
            // Links open only on a single, still Cmd/Ctrl+click; SithTermFX's plain-click navigation
            // is a no-op in KorttyLinkInfo. A canvas filter, so it runs before SithTermFX's handler.
            TerminalLinkClickPolicy.install(this, links, this::openLink);
            // Hover: underline, cursor and target tooltip. Added after SithTermFX's own mouse handlers,
            // so the cursor it sets wins over SithTermFX's.
            linkHover = TerminalLinkHoverController.install(this,
                (buffer, cell) -> TerminalLinkResolver.linkAt(buffer, cell, plainTextLinkKinds.get()),
                () -> linkOverlay.get());
        }

        void setPlainTextLinkKinds(@NotNull Supplier<Set<TerminalLinkDetector.Kind>> kinds) {
            plainTextLinkKinds = Objects.requireNonNull(kinds, "kinds");
        }

        void setLinkOverlay(@NotNull Supplier<Pane> overlay) {
            linkOverlay = Objects.requireNonNull(overlay, "overlay");
        }

        /**
         * Replaces the system browser as the target of Cmd/Ctrl+clicked links, for
         * {@code terminalLinksSmoke}. Call it on the JavaFX thread.
         */
        void setLinkOpener(@NotNull TerminalLinkOpener opener) {
            linkOpener = Objects.requireNonNull(opener, "opener");
        }

        /**
         * Replaces the host-mismatch dialog with a stand-in, for {@code terminalLinksSmoke}. Call it
         * on the JavaFX thread.
         */
        void setMismatchConfirmation(@NotNull TerminalLinkMismatchDialog.Confirmation confirmation) {
            mismatchConfirmation = Objects.requireNonNull(confirmation, "confirmation");
        }

        /** The hover handling of this panel, for {@code terminalLinksSmoke}. */
        @NotNull TerminalLinkHoverController linkHover() {
            return linkHover;
        }

        /**
         * Opens a Cmd/Ctrl+clicked link. An OSC 8 link whose text names another host than its target
         * opens only after the user confirms; the question is asked once the click is handled.
         */
        private void openLink(@NotNull Hit hit) {
            URI target = hit.target();
            if (target == null) {
                return;
            }
            Optional<String> shownHost = hit.kind() == HitKind.OSC8
                ? TerminalLinkOpener.visibleHostMismatch(hit.text(), target)
                : Optional.empty();
            if (shownHost.isEmpty()) {
                linkOpener.open(target);
                return;
            }
            Platform.runLater(() -> {
                if (mismatchConfirmation.confirm(getCanvas(), shownHost.get(), target)) {
                    linkOpener.open(target);
                }
            });
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
