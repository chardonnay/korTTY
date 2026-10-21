package de.kortty.ui;

import org.jetbrains.annotations.Nullable;

/** The terminal of one pane as KI-Agent designs that draw into the terminal itself see it. FX thread. */
interface AgentTerminalSurface {

    /** The writer for the pane's terminal, or {@code null} while the pane has none. */
    @Nullable AgentTerminalBlockWriter blockWriter();

    /** Whether the terminal's background is dark, which picks the block colours. */
    boolean darkBackground();

    /** Called after korTTY wrote into the terminal itself, so highlight rules skip those lines. */
    void localTextWritten();

    /** Feeds text korTTY wrote into the terminal to the session journal's capture, as remote output is. */
    void journalLocalOutput(String text);

    /**
     * A mouse-transparent layer over the pane for lists a design shows over the terminal (a drawer, a
     * lens), or {@code null} when the pane has none.
     */
    default @Nullable javafx.scene.layout.Pane overlayLayer() {
        return null;
    }
}
