package de.kortty.ui;

import com.sithtermfx.core.model.SithTerminal;
import com.sithtermfx.core.model.StyleState;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.ui.SithTermFxWidget;
import com.sithtermfx.ui.TerminalCopyPasteHandler;
import com.sithtermfx.ui.TerminalPanel;
import com.sithtermfx.ui.settings.SettingsProvider;
import de.kortty.core.PolicyAwareCopyPasteHandler;
import org.jetbrains.annotations.NotNull;

/**
 * korTTY's terminal widget: a {@link SithTermFxWidget} whose panel routes every copy/paste path
 * (shortcuts, context menu, middle-click/primary selection) through the policy-aware clipboard
 * handler, so the enterprise policy's internal-clipboard mode covers the terminal completely.
 *
 * <p>It also exposes the context-menu commands as {@link TerminalPaneActions}, calling SithTermFX's
 * public API directly. The panel is an anonymous subclass, so a reflective lookup of these methods
 * on the runtime class finds nothing.
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
        return new TerminalPanel(settingsProvider, terminalTextBuffer, styleState) {
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
        };
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
}
