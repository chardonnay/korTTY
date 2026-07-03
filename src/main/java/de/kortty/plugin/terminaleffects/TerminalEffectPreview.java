package de.kortty.plugin.terminaleffects;

import javafx.scene.Node;
import org.jetbrains.annotations.NotNull;

/**
 * Small animated preview of a terminal effect, shown in the plugin manager.
 *
 * <p>One instance backs one displayed preview. Callers request the node once via {@link #node()},
 * call {@link #start()} after attaching it to a scene and {@link #stop()} before discarding it.
 * Implementations must not construct JavaFX objects before {@link #node()} is invoked so that
 * plugin metadata (including {@link TerminalEffectPlugin#createPreview()}) stays usable without
 * a running JavaFX toolkit.</p>
 */
public interface TerminalEffectPreview {

    /**
     * Lazily creates and returns the preview node. Must be called on the JavaFX thread.
     */
    @NotNull Node node();

    /**
     * Starts the preview animation. No-op if the node was never created.
     */
    default void start() {
    }

    /**
     * Stops the preview animation and releases animation resources.
     */
    default void stop() {
    }
}
