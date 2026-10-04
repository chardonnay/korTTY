package de.kortty.ui;

import de.kortty.core.SessionRestoreDecision;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Objects;

/**
 * Carries out at startup what {@link SessionRestoreDecision} decided about the previous session:
 *
 * <ul>
 *   <li><b>Offer.</b> A bar in the window offers the previous session with <b>Restore</b> and
 *       <b>Dismiss</b>. It never blocks the window, and nothing opens until the user chooses
 *       Restore.</li>
 *   <li><b>Automatic.</b> The previous session opens by itself, but only once no modal dialog is
 *       open, so its connections never ask for host keys, passwords or access reasons on top of a
 *       dialog the user has to answer first, such as the consent prompt. It looks again every
 *       {@value #MODAL_POLL_MILLIS} ms; after {@value #MODAL_WAIT_MILLIS} ms with a dialog still
 *       open it offers the session instead.</li>
 *   <li>Either way the previous session stands in for this run's snapshot while the user has not
 *       answered, and nothing happens once the user restored it another way.</li>
 * </ul>
 *
 * <p>FX thread; toolkit-free itself: the window and the timer come in through {@link Host}.
 */
final class SessionRestoreCoordinator {

    private static final Logger logger = LoggerFactory.getLogger(SessionRestoreCoordinator.class);

    /** How often the automatic restore looks again whether a modal dialog is still open. */
    static final long MODAL_POLL_MILLIS = 500;
    /** How long the automatic restore waits for the dialogs to close before it offers instead. */
    static final long MODAL_WAIT_MILLIS = 60_000;

    static final String PROMPT_KEY = "session.restore.offer.prompt";
    static final String AFTER_CRASH_KEY = "session.restore.offer.afterCrash";
    static final String RESTORE_KEY = "session.restore.offer.restore";
    static final String TOOLTIP_KEY = "session.restore.offer.tooltip";
    /** The texts of the offer bar; Dismiss is the restore bar's ({@link RestoreAttention#DISMISS_KEY}). */
    static final List<String> KEYS = List.of(PROMPT_KEY, AFTER_CRASH_KEY, RESTORE_KEY, TOOLTIP_KEY);

    /** What the coordinator needs from korTTY. */
    interface Host {
        /** Whether a modal dialog is showing in any window. */
        boolean modalShowing();

        /** Whether the previous session can still be restored in this run; the user may have done so meanwhile. */
        boolean previousRestorable();

        /** Lets the previous session stand in for this run's snapshot until the user answers. */
        void keepPreviousSession();

        /** Shows the offer bar with this text. */
        void offer(String text);

        /** Restores the previous session now. */
        void restore();

        /** Runs {@code task} on the JavaFX thread after {@code delayMillis} ms. */
        void schedule(long delayMillis, Runnable task);
    }

    private final Host host;
    private final SessionRestoreDecision.Decision decision;
    private final SessionRestoreDecision.Facts facts;
    private final TabRestoreTriage.Texts texts;

    SessionRestoreCoordinator(Host host, SessionRestoreDecision.Decision decision, SessionRestoreDecision.Facts facts,
                              TabRestoreTriage.Texts texts) {
        this.host = Objects.requireNonNull(host, "host");
        this.decision = Objects.requireNonNull(decision, "decision");
        this.facts = Objects.requireNonNull(facts, "facts");
        this.texts = Objects.requireNonNull(texts, "texts");
    }

    /** Carries out the decision. Call once. */
    void start() {
        switch (decision.action()) {
            case NONE -> {
                // File › Restore Previous Session stays available as always.
            }
            case OFFER -> {
                host.keepPreviousSession();
                host.offer(offerText(decision.afterCrash()));
            }
            case AUTO -> {
                host.keepPreviousSession();
                restoreOnceNoModal(0);
            }
        }
    }

    private void restoreOnceNoModal(long waited) {
        if (!host.previousRestorable()) {
            // The user restored it another way meanwhile.
            return;
        }
        if (!host.modalShowing()) {
            host.restore();
            return;
        }
        if (waited >= MODAL_WAIT_MILLIS) {
            logger.info("A dialog stayed open for {} s; offering the previous session instead of restoring it",
                MODAL_WAIT_MILLIS / 1000);
            host.offer(offerText(false));
            return;
        }
        host.schedule(MODAL_POLL_MILLIS, () -> restoreOnceNoModal(waited + MODAL_POLL_MILLIS));
    }

    /** The text of the offer bar, with the number of windows and tabs the restore would open. */
    String offerText(boolean afterCrash) {
        return texts.get(afterCrash ? AFTER_CRASH_KEY : PROMPT_KEY, facts.windows(), facts.tabs());
    }
}
