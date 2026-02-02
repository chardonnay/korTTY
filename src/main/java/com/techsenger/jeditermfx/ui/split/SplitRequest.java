package com.techsenger.jeditermfx.ui.split;

import com.techsenger.jeditermfx.ui.JediTermFxWidget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Request context when creating a new terminal split.
 * The application uses this to decide whether to reuse an existing connection or create a new one.
 */
public class SplitRequest {

    public enum SplitMode {
        /** New shell on same server (reuse existing SSH connection, new channel/PTY) */
        SAME_SERVER_NEW_SHELL,
        /** New connection (e.g. different server via QuickConnect dialog) */
        NEW_CONNECTION
    }

    private final SplitMode splitMode;
    private final @Nullable JediTermFxWidget parentWidget;

    public SplitRequest(@NotNull SplitMode splitMode, @Nullable JediTermFxWidget parentWidget) {
        this.splitMode = splitMode;
        this.parentWidget = parentWidget;
    }

    public @NotNull SplitMode getSplitMode() {
        return splitMode;
    }

    /**
     * The widget that was focused when the split was requested.
     * Can be used to reuse its TtyConnector (e.g. open new channel on same SSH).
     */
    public @Nullable JediTermFxWidget getParentWidget() {
        return parentWidget;
    }
}
