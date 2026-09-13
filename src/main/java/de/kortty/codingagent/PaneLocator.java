package de.kortty.codingagent;

import java.util.Optional;

/** Resolves where a pane currently lives (window, tab, pane index, cwd); never cached as a key. */
public interface PaneLocator {

    /** The current location of {@code pane}, empty when the pane is no longer open. */
    Optional<PaneLocation> locate(PaneRef pane);
}
