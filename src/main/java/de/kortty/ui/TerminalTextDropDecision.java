package de.kortty.ui;

import java.util.Objects;

/**
 * What a drag over a terminal tab does: copy dropped files to the server, paste dropped text, refuse
 * it, or leave the drag to someone else. Pure, so the rules hold without a JavaFX toolkit;
 * {@link TerminalView} feeds it the dragboard, the gesture source and the settings, and resolves the
 * pane under the pointer for a text drop itself.
 *
 * <ul>
 *   <li>A split-pane move (Shift+Alt drag of a pane) belongs to the split pane.</li>
 *   <li>With Settings → Terminal → drag and drop off, nothing is taken, files or text.</li>
 *   <li>Files win: a drag from a file manager that also carries the path as text copies the files
 *       and never pastes the path.</li>
 *   <li>In the enterprise policy's internal clipboard mode, text from another application is
 *       refused, as a paste of the OS clipboard is; text dragged inside korTTY is still pasted.</li>
 *   <li>Text is taken only when its source offers a copy. korTTY's own moves (a connection from the
 *       connection tree carries its id as text) are not pastes, and accepting a move would make the
 *       source application delete the text it dragged.</li>
 * </ul>
 */
final class TerminalTextDropDecision {

    /** What to do with the drag. */
    enum Action {

        /** Not a drop onto the terminal: leave the event to the split pane, the window or nobody. */
        IGNORE,

        /** Files: copied to the server over SFTP, as before text drops existed. */
        COPY_FILES,

        /** Text: pasted into the pane under the pointer through paste protection. */
        PASTE_TEXT,

        /** Text the clipboard policy keeps out: taken by nobody, so nothing is pasted. */
        REFUSE
    }

    /**
     * What a drag carries.
     *
     * @param splitPaneMove the split pane's own pane-move format is on the dragboard
     * @param hasFiles the dragboard has files
     * @param hasText the dragboard has a string
     * @param offersCopy the drag source allows a copy
     * @param fromKortty the drag started in korTTY (a JavaFX gesture source); a drag from another
     *     application has none
     */
    record Drag(boolean splitPaneMove, boolean hasFiles, boolean hasText, boolean offersCopy,
            boolean fromKortty) {
    }

    private TerminalTextDropDecision() {
    }

    /**
     * Decides what a drag over a terminal does.
     *
     * @param drag what the drag carries
     * @param dragDropEnabled Settings → Terminal → Allow drag and drop into the terminal
     * @param internalClipboardMode the enterprise policy keeps the OS clipboard out of korTTY
     */
    static Action decide(Drag drag, boolean dragDropEnabled, boolean internalClipboardMode) {
        Objects.requireNonNull(drag, "drag");
        if (drag.splitPaneMove() || !dragDropEnabled) {
            return Action.IGNORE;
        }
        if (drag.hasFiles()) {
            return Action.COPY_FILES;
        }
        if (!drag.hasText()) {
            return Action.IGNORE;
        }
        if (internalClipboardMode && !drag.fromKortty()) {
            return Action.REFUSE;
        }
        return drag.offersCopy() ? Action.PASTE_TEXT : Action.IGNORE;
    }
}
