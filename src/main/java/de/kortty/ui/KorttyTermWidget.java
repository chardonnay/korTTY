package de.kortty.ui;

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
 */
public class KorttyTermWidget extends SithTermFxWidget {

    public KorttyTermWidget(int columns, int lines, SettingsProvider settingsProvider) {
        super(columns, lines, settingsProvider);
    }

    @Override
    protected TerminalPanel createTerminalPanel(@NotNull SettingsProvider settingsProvider,
            @NotNull StyleState styleState, @NotNull TerminalTextBuffer terminalTextBuffer) {
        return new TerminalPanel(settingsProvider, terminalTextBuffer, styleState) {
            @Override
            protected TerminalCopyPasteHandler createCopyPasteHandler() {
                return new PolicyAwareCopyPasteHandler();
            }
        };
    }
}
