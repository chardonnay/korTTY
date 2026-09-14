package de.kortty.control;

import de.kortty.codingagent.ScreenSnapshot;
import java.util.List;
import java.util.Objects;

/**
 * What {@code pane.read} returns for the text modes.
 *
 * <p>Pure, any thread. Building one from a {@link ScreenSnapshot} is safe off the JavaFX thread,
 * which is the whole point of resolving a {@link PaneReader} in one hop and reading later.
 *
 * @param paneId the pane that was read
 * @param mode the wire read mode, see {@link ReadMode#wire()}
 * @param lines the rows, right-trimmed, oldest first
 * @param columns the pane width in cells
 * @param rows the pane height in cells
 * @param alternateScreen whether the alternate screen buffer was active; always false for
 *     {@link #recent}, which spans the history buffer
 * @param oscTitle the last OSC 0/2 title, or null
 * @param truncated whether leading lines were dropped to fit
 *     {@link ControlApiProtocol#MAX_RESULT_BYTES}
 */
public record PaneText(String paneId, String mode, List<String> lines, int columns, int rows,
                       boolean alternateScreen, String oscTitle, boolean truncated) {

    public PaneText {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }

    /** The live screen, including the alternate buffer. */
    public static PaneText visible(String paneId, ScreenSnapshot snapshot) {
        ScreenSnapshot source = snapshot == null ? ScreenSnapshot.EMPTY : snapshot;
        return new PaneText(paneId, ReadMode.VISIBLE.wire(), source.lines(), source.columns(),
            source.rows(), source.alternateScreen(), source.oscTitle(), false);
    }

    /** The history buffer plus the screen, read under one buffer lock. */
    public static PaneText recent(String paneId, List<String> lines, int columns, int rows,
                                  String oscTitle, boolean truncated) {
        return new PaneText(paneId, ReadMode.RECENT.wire(), Objects.requireNonNullElse(lines, List.of()),
            columns, rows, false, oscTitle, truncated);
    }
}
