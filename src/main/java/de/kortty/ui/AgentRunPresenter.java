package de.kortty.ui;

import de.kortty.agentdesign.MetroStationModel;
import de.kortty.core.TerminalAgentService;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Region;

import java.util.List;
import java.util.function.Supplier;

/**
 * A KI-Agent design that shows the run {@link AgentDesignHost} follows beside the terminal: a status
 * display (STATUS role) or a history panel (HISTORY role), placed in one of the pane's slots around the
 * terminal or floating over it. FX thread.
 */
interface AgentRunPresenter {

    /** Where the design sits. */
    enum Slot {
        TOP,
        BOTTOM,
        RIGHT,
        /** Floating over the terminal in the pane's control layer; the design places itself there. */
        OVERLAY
    }

    /** What the design's buttons do; the host decides for which run. */
    interface Actions {
        void togglePause();

        void cancel();

        void hide();

        /** Answers the open approval of the followed run. */
        default void answerApproval(TerminalAgentService.ApprovalDecision decision) {
        }

        /** Follows {@code runId} instead of the run the host would pick. */
        default void selectRun(String runId) {
        }

        /**
         * Starts a new run in this pane with {@code prompt}, as {@code agent <prompt>} typed at the
         * shell would. Returns whether it started.
         */
        default boolean startRun(String prompt) {
            return false;
        }

        /** Whether {@link #startRun} can start a run in this pane. */
        default boolean canStartRuns() {
            return false;
        }
    }

    /** One run of the pane, for designs that list the runs (tabs). */
    record RunTab(String runId, String prompt, boolean running, boolean awaitingInput) {
    }

    /**
     * The run the design follows.
     *
     * @param prompt        the user's question
     * @param stations      the run's steps as metro stations
     * @param trace         the run's steps as listed in the terminal block
     * @param meta          tokens and elapsed time, ready to show
     * @param running       the run has not ended
     * @param paused        the run is paused
     * @param awaitingInput an approval or password request is open
     * @param activeRuns    how many runs of the pane with this design are unfinished
     * @param runs          every run of the pane the host keeps, oldest first
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
        int activeRuns,
        List<RunTab> runs) {

        public View {
            runs = runs != null ? List.copyOf(runs) : List.of();
        }

        public View(String runId, String prompt, MetroStationModel stations, AgentInlineBlockLayout.BlockState trace,
                    String meta, boolean running, boolean paused, boolean awaitingInput, int activeRuns) {
            this(runId, prompt, stations, trace, meta, running, paused, awaitingInput, activeRuns, List.of());
        }

        /** Whether the open request is an approval (not a password). */
        boolean approvalOpen() {
            return trace != null && trace.pendingApproval != null;
        }
    }

    Slot slot();

    Region node();

    /** Shows the followed run and makes the design visible. */
    void show(View view);

    /** Fades the design out after {@code seconds}; 0 keeps it. */
    void scheduleAutoHide(int seconds);

    void hideNow();

    boolean isShowing();

    void applyPalette(AgentDesignPalette palette);

    void setReducedAnimations(boolean reducedAnimations);

    /** Where step lists drawn over the terminal go (a drawer, a lens); may supply {@code null}. */
    default void setOverlay(Supplier<Pane> overlay) {
    }

    /** Where a floating design and its clickable parts go; may supply {@code null}. */
    default void setControlLayer(Supplier<Pane> controls) {
    }

    /**
     * The node the terminal draws on; a floating design keeps to its area, clear of the rows below the
     * terminal. May supply {@code null}.
     */
    default void setTerminalCanvas(Supplier<javafx.scene.Node> canvas) {
    }

    /** Whether the design has its own buttons for an open approval, so the host's approval row can stay hidden. */
    default boolean answersApprovals() {
        return false;
    }

    /** Whether the design stays after the run ended instead of fading out with the status displays. */
    default boolean staysAfterRun() {
        return false;
    }
}
