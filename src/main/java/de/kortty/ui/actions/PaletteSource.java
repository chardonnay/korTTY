package de.kortty.ui.actions;

import java.util.List;

/**
 * Supplies the command palette with the rows of one {@link PaletteEntry.Kind kind}. The palette asks
 * once each time it opens, so the rows show the state of that moment, and it never asks while the
 * user types. Called on the FX thread.
 */
public interface PaletteSource {

    /** The kind of every entry this source returns. */
    PaletteEntry.Kind kind();

    /** The rows right now, in the order they are listed when nothing is typed. */
    List<PaletteEntry> entries();
}
