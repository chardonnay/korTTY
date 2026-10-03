package de.kortty.ui;

import de.kortty.ui.actions.TerminalPaletteActions;
import javafx.geometry.Orientation;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A terminal tab as the command palette's terminal and tab commands see it
 * ({@link TerminalPaletteActions}): the pane commands go to the tab's {@link TerminalView}, which
 * acts on the focused pane, and Duplicate and Reconnect do what the tab's right-click menu does.
 */
final class TerminalPaletteTarget implements TerminalPaletteActions.Target {

    private final TerminalTab tab;
    private final Consumer<TerminalTab> duplicate;

    /**
     * @param duplicate opens a copy of the tab next to it, as <b>Duplicate</b> in its right-click
     *                  menu does
     */
    TerminalPaletteTarget(TerminalTab tab, Consumer<TerminalTab> duplicate) {
        this.tab = Objects.requireNonNull(tab, "tab");
        this.duplicate = Objects.requireNonNull(duplicate, "duplicate");
    }

    @Override
    public void clearBuffer() {
        tab.getTerminalView().clearFocusedBuffer();
    }

    @Override
    public void splitRight() {
        tab.getTerminalView().splitFocused(Orientation.HORIZONTAL);
    }

    @Override
    public void splitDown() {
        tab.getTerminalView().splitFocused(Orientation.VERTICAL);
    }

    @Override
    public int paneCount() {
        return tab.getTerminalView().getTerminalPaneCount();
    }

    @Override
    public boolean isBroadcasting() {
        return tab.getTerminalView().isBroadcastMode();
    }

    @Override
    public void toggleBroadcast() {
        tab.getTerminalView().toggleBroadcast();
    }

    @Override
    public void duplicate() {
        duplicate.accept(tab);
    }

    @Override
    public void reconnect() {
        tab.triggerReconnect();
    }
}
