package de.kortty.core;

import com.sithtermfx.ui.DefaultTerminalCopyPasteHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Terminal copy-paste handler honoring the enterprise policy's clipboard mode. Every terminal
 * copy/paste path funnels through this handler — keyboard shortcuts, the context menu, X11
 * middle-click and the primary-selection emulation — so in internal mode nothing can enter or
 * leave through the OS clipboard, while copy/paste between korTTY terminals and the rest of the
 * app keeps working via the shared internal buffer.
 */
public final class PolicyAwareCopyPasteHandler extends DefaultTerminalCopyPasteHandler {

    @Override
    public void setContents(@NotNull String text, boolean useSystemSelectionClipboardIfAvailable) {
        if (KorttyClipboard.isInternalMode()) {
            KorttyClipboard.setInternalBuffer(text);
            return;
        }
        super.setContents(text, useSystemSelectionClipboardIfAvailable);
    }

    @Nullable
    @Override
    public String getContents(boolean useSystemSelectionClipboardIfAvailable) {
        if (KorttyClipboard.isInternalMode()) {
            // The X11 primary selection is an OS clipboard too — internal mode ignores it.
            return KorttyClipboard.internalBuffer();
        }
        return super.getContents(useSystemSelectionClipboardIfAvailable);
    }
}
