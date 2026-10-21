package de.kortty.ui;

import com.sithtermfx.core.compatibility.Point;
import javafx.scene.Node;
import javafx.scene.layout.Pane;
import org.jetbrains.annotations.Nullable;

/** The terminal of one pane as KI-Agent designs that draw into or over the terminal see it. FX thread. */
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
    default @Nullable Pane overlayLayer() {
        return null;
    }

    /**
     * A layer over the pane whose own nodes take clicks (a floating card, a pill, a picture-in-picture
     * window), or {@code null} when the pane has none. Its empty rest lets every event through.
     */
    default @Nullable Pane controlLayer() {
        return null;
    }

    /** The node the terminal draws on, whose coordinates {@link #cellGeometry()} uses; may be {@code null}. */
    default @Nullable Node canvas() {
        return null;
    }

    /** The terminal's current cell grid, or {@code null} before it measured its font. */
    default @Nullable TerminalCellGeometry cellGeometry() {
        return null;
    }

    /** The cursor's column ({@code x}) and screen line ({@code y}), both 0-based, or {@code null}. */
    default @Nullable Point cursorCell() {
        return null;
    }

    /** Types {@code text} at the shell's prompt without pressing Enter, as a paste would. */
    default void insertAtPrompt(String text) {
    }

    /**
     * Starts a KI-Agent run in this pane with {@code prompt}, as {@code agent <prompt>} typed at the shell
     * would, with the named profile or the pane's default. Returns whether it started.
     */
    default boolean startRun(String prompt, @Nullable String profileName) {
        return false;
    }

    /** Whether {@link #startRun} can start a run in this pane now. */
    default boolean canStartRuns() {
        return false;
    }
}
