package de.kortty.plugin.terminaleffects;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.ui.SithTermFxWidget;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Runtime instance of a terminal effect for a single terminal tab.
 */
public interface TerminalEffectSession extends AutoCloseable {

    /**
     * Starts UI or listener resources for the effect.
     */
    default void start() {
    }

    /**
     * Gives the effect a chance to decorate a terminal connector before SithTermFX starts it.
     */
    default @NotNull TtyConnector wrapConnector(
            @Nullable SithTermFxWidget widget,
            @NotNull TtyConnector connector) {
        return connector;
    }

    /**
     * Stops resources created by this effect session.
     */
    default void stop() {
    }

    @Override
    default void close() {
        stop();
    }
}
