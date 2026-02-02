package com.techsenger.jeditermfx.ui.split;

import com.techsenger.jeditermfx.core.TtyConnector;
import org.jetbrains.annotations.Nullable;

/**
 * Factory for creating TtyConnectors when the user splits a terminal.
 * The application implements this to provide new sessions - either reusing
 * an existing connection (same server, new shell) or creating a new connection.
 */
@FunctionalInterface
public interface SplitConnectorFactory {

    /**
     * Creates a TtyConnector for a new split.
     *
     * @param request Contains split mode (same server vs new connection) and parent widget
     * @return The new TtyConnector, or null if the user cancelled (e.g. closed dialog)
     */
    @Nullable
    TtyConnector createConnectorForSplit(@Nullable SplitRequest request);
}
