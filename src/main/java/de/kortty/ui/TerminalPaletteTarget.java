package de.kortty.ui;

import de.kortty.ui.actions.TerminalPaletteActions;

import java.util.Objects;
import java.util.function.Consumer;

/**
 * A terminal tab as the command palette's terminal and tab commands see it
 * ({@link TerminalPaletteActions}): Clear Buffer goes to the tab's {@link TerminalView}, which acts
 * on the focused pane, and Duplicate, Reconnect and the two watch switches (Monitor for Activity,
 * Monitor for Silence) do what the tab's right-click menu does. The splits and broadcast mode come
 * from <i>View → Panes</i>, which the palette harvests.
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
    public void duplicate() {
        duplicate.accept(tab);
    }

    @Override
    public void reconnect() {
        tab.triggerReconnect();
    }

    @Override
    public boolean isMonitoringActivity() {
        return tab.isMonitoringActivity();
    }

    @Override
    public void toggleMonitoringActivity() {
        tab.setMonitoringActivity(!tab.isMonitoringActivity());
    }

    @Override
    public boolean isMonitoringSilence() {
        return tab.isMonitoringSilence();
    }

    @Override
    public void toggleMonitoringSilence() {
        tab.setMonitoringSilence(!tab.isMonitoringSilence());
    }
}
