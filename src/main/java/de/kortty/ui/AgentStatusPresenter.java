package de.kortty.ui;

import de.kortty.agentdesign.MetroStationModel;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import java.util.function.Supplier;

/**
 * A KI-Agent design of the STATUS role: a compact display of the run {@link AgentDesignHost} follows,
 * placed in one of the pane's slots around the terminal. FX thread.
 */
interface AgentStatusPresenter {

    /** Where the display sits around the terminal. */
    enum Slot {
        TOP,
        BOTTOM,
        RIGHT
    }

    /** What the display's buttons do; the host decides for which run. */
    interface Actions {
        void togglePause();

        void cancel();

        void hide();
    }

    /**
     * The run the display follows.
     *
     * @param prompt        the user's question
     * @param stations      the run's steps as metro stations
     * @param trace         the run's steps as listed in the terminal block
     * @param meta          tokens and elapsed time, ready to show
     * @param running       the run has not ended
     * @param paused        the run is paused
     * @param awaitingInput an approval or password request is open
     * @param activeRuns    how many runs of the pane with this display are unfinished
     */
    record View(
        String runId,
        String prompt,
        MetroStationModel stations,
        AgentInlineBlockLayout.BlockState trace,
        String meta,
        boolean running,
        boolean paused,
        boolean awaitingInput,
        int activeRuns) {
    }

    Slot slot();

    Region node();

    /** Shows the followed run and makes the display visible. */
    void show(View view);

    /** Fades the display out after {@code seconds}; 0 keeps it. */
    void scheduleAutoHide(int seconds);

    void hideNow();

    boolean isShowing();

    void applyPalette(AgentDesignPalette palette);

    void setReducedAnimations(boolean reducedAnimations);

    /** Where step lists drawn over the terminal go (a drawer, a lens); may supply {@code null}. */
    default void setOverlay(Supplier<Pane> overlay) {
    }
}
