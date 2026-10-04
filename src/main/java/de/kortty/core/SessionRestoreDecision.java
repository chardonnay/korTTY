package de.kortty.core;

import de.kortty.model.SessionRestoreMode;
import de.kortty.model.SessionSnapshot;
import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * What korTTY does at startup with the session before this start, by the Session Restore setting
 * ({@link SessionRestoreMode}) and what {@link SessionSnapshotStore#startUp} found:
 *
 * <ul>
 *   <li><b>Nothing to offer.</b> Only the session the last run left, and only when it had a tab and
 *       this korTTY made it the previous session, is offered: a second korTTY started while the
 *       first one runs (it holds no lock, so it rotates nothing) never reopens the first one's tabs,
 *       and a start that found an empty snapshot offers nothing, so a session the user dismissed
 *       does not come back at the next start. (An offer left unanswered in a run that opened nothing
 *       comes back: the snapshot kept it, see {@code SessionAutosaveCoordinator#carryForward}.)</li>
 *   <li><b>{@code off}</b> does nothing, <b>{@code ask}</b> offers the session in a bar,
 *       <b>{@code auto}</b> reopens it.</li>
 *   <li><b>Crash-loop guard.</b> When the last run reopened its previous session and ended without
 *       quitting normally before it had run for a minute, {@code auto} offers instead: if restoring
 *       that session is what made korTTY end, it does not do so again at every start.</li>
 * </ul>
 *
 * <p>Pure: no file, no toolkit.
 */
public final class SessionRestoreDecision {

    private SessionRestoreDecision() {
    }

    /** What to do at startup. */
    public enum Action {
        /** Nothing; File › Restore Previous Session stays available as always. */
        NONE,
        /** Offer the previous session in a bar with Restore and Dismiss. */
        OFFER,
        /** Reopen the previous session, once no dialog is open. */
        AUTO
    }

    /**
     * The decision.
     *
     * @param action     what to do
     * @param afterCrash whether {@code auto} became {@link Action#OFFER} because korTTY ended
     *                   unexpectedly right after the last restore; the bar then says why it asks
     */
    public record Decision(Action action, boolean afterCrash) {
        public Decision {
            Objects.requireNonNull(action, "action");
        }

        static final Decision NONE = new Decision(Action.NONE, false);
    }

    /**
     * What the decision looks at.
     *
     * @param offerable  whether this start made the last run's session the previous one (see
     *                   {@link SessionSnapshotStore.StartupState#rotated()})
     * @param windows    the windows of that session with a tab to reopen
     * @param tabs       the tabs of that session, over all its windows
     * @param afterCrash whether the last run ended unexpectedly right after a restore (see
     *                   {@link #endedRightAfterRestore})
     */
    public record Facts(boolean offerable, int windows, int tabs, boolean afterCrash) {

        /** The facts {@link SessionSnapshotStore#startUp} found; none for {@code null}. */
        public static Facts of(@Nullable SessionSnapshotStore.StartupState startup) {
            if (startup == null || startup.last() == null) {
                return new Facts(false, 0, 0, false);
            }
            SessionSnapshot last = startup.last();
            return new Facts(startup.rotated(), SessionSnapshotStore.restorableWindows(last),
                SessionSnapshotStore.restorableTabs(last), endedRightAfterRestore(last));
        }
    }

    /**
     * Whether the run that wrote {@code last} reopened its previous session and then ended without
     * quitting normally before it had run for a minute: a crash, a forced end or a power cut right
     * after the restore.
     */
    public static boolean endedRightAfterRestore(@Nullable SessionSnapshot last) {
        return last != null && last.isSessionRestored() && !last.isStable() && !last.isCleanExit();
    }

    /** What to do at startup in {@code mode} ({@code null} means {@link SessionRestoreMode#DEFAULT}). */
    public static Decision decide(@Nullable SessionRestoreMode mode, Facts facts) {
        Objects.requireNonNull(facts, "facts");
        if (!facts.offerable() || facts.tabs() <= 0) {
            return Decision.NONE;
        }
        return switch (mode != null ? mode : SessionRestoreMode.DEFAULT) {
            case OFF -> Decision.NONE;
            case ASK -> new Decision(Action.OFFER, false);
            case AUTO -> facts.afterCrash()
                ? new Decision(Action.OFFER, true)
                : new Decision(Action.AUTO, false);
        };
    }
}
