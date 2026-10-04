package de.kortty.ui;

import de.kortty.core.SessionRestoreDecision;
import de.kortty.core.SessionRestoreDecision.Action;
import de.kortty.core.SessionRestoreDecision.Decision;
import de.kortty.core.SessionRestoreDecision.Facts;
import org.testng.annotations.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

/**
 * The startup offer and the automatic restore, without a toolkit: the offer shows a non-modal bar
 * and opens nothing; the automatic restore waits until no modal dialog is open (the consent prompt
 * first of all), offers instead when a dialog stays open, and does nothing once the user restored
 * the session another way. Either way the previous session stands in for this run's snapshot.
 */
class SessionRestoreCoordinatorTest {

    private static final Facts FACTS = new Facts(true, 2, 5, false);

    @Test
    void nothingHappensWhenTheDecisionIsNone() {
        FakeHost host = new FakeHost();

        coordinator(host, new Decision(Action.NONE, false)).start();

        assertThat(host.calls).isEmpty();
    }

    @Test
    void anOfferShowsTheBarWithTheCountsAndOpensNothing() {
        FakeHost host = new FakeHost();

        coordinator(host, new Decision(Action.OFFER, false)).start();

        assertThat(host.calls).containsExactly("keep", "offer:session.restore.offer.prompt[2, 5]").inOrder();
    }

    @Test
    void anOfferAfterACrashSaysWhyItAsks() {
        FakeHost host = new FakeHost();

        coordinator(host, new Decision(Action.OFFER, true)).start();

        assertThat(host.calls).containsExactly("keep", "offer:session.restore.offer.afterCrash[2, 5]").inOrder();
    }

    @Test
    void theAutomaticRestoreRunsAtOnceWhenNoDialogIsOpen() {
        FakeHost host = new FakeHost();

        coordinator(host, new Decision(Action.AUTO, false)).start();

        assertThat(host.calls).containsExactly("keep", "restore").inOrder();
        assertThat(host.scheduled).isEmpty();
    }

    @Test
    void theAutomaticRestoreWaitsUntilTheConsentPromptIsAnswered() {
        FakeHost host = new FakeHost();
        host.modal = true;

        coordinator(host, new Decision(Action.AUTO, false)).start();
        assertThat(host.calls).containsExactly("keep");
        assertThat(host.scheduled).hasSize(1);
        assertThat(host.delays).containsExactly(SessionRestoreCoordinator.MODAL_POLL_MILLIS);

        host.runNext();
        host.runNext();
        assertWithMessage("still waiting while the dialog is open").that(host.calls).containsExactly("keep");

        host.modal = false;
        host.runNext();
        assertThat(host.calls).containsExactly("keep", "restore").inOrder();
        assertThat(host.scheduled).isEmpty();
    }

    @Test
    void aDialogThatStaysOpenTurnsTheRestoreIntoAnOffer() {
        FakeHost host = new FakeHost();
        host.modal = true;

        coordinator(host, new Decision(Action.AUTO, false)).start();
        int polls = 0;
        while (!host.scheduled.isEmpty()) {
            host.runNext();
            polls++;
        }

        assertThat(polls).isEqualTo((int) (SessionRestoreCoordinator.MODAL_WAIT_MILLIS / SessionRestoreCoordinator.MODAL_POLL_MILLIS));
        assertThat(host.calls).containsExactly("keep", "offer:session.restore.offer.prompt[2, 5]").inOrder();
    }

    @Test
    void aSessionTheUserRestoredMeanwhileIsNotRestoredAgain() {
        FakeHost host = new FakeHost();
        host.modal = true;

        coordinator(host, new Decision(Action.AUTO, false)).start();
        // File › Restore Previous Session while the consent prompt was still open.
        host.restorable = false;
        host.modal = false;
        host.runNext();

        assertThat(host.calls).containsExactly("keep");
        assertThat(host.scheduled).isEmpty();
    }

    @Test
    void everyOfferTextNamesTheWindowsAndTheTabs() {
        SessionRestoreCoordinator coordinator = coordinator(new FakeHost(), new Decision(Action.OFFER, false));

        assertThat(coordinator.offerText(false)).isEqualTo("session.restore.offer.prompt[2, 5]");
        assertThat(coordinator.offerText(true)).isEqualTo("session.restore.offer.afterCrash[2, 5]");
        assertThat(SessionRestoreCoordinator.KEYS).containsExactly(SessionRestoreCoordinator.PROMPT_KEY,
            SessionRestoreCoordinator.AFTER_CRASH_KEY, SessionRestoreCoordinator.RESTORE_KEY,
            SessionRestoreCoordinator.TOOLTIP_KEY);
    }

    private static SessionRestoreCoordinator coordinator(FakeHost host, SessionRestoreDecision.Decision decision) {
        return new SessionRestoreCoordinator(host, decision, FACTS,
            (key, args) -> key + java.util.Arrays.toString(args));
    }

    private static final class FakeHost implements SessionRestoreCoordinator.Host {
        final List<String> calls = new ArrayList<>();
        final Deque<Runnable> scheduled = new ArrayDeque<>();
        final List<Long> delays = new ArrayList<>();
        boolean modal;
        boolean restorable = true;

        @Override
        public boolean modalShowing() {
            return modal;
        }

        @Override
        public boolean previousRestorable() {
            return restorable;
        }

        @Override
        public void keepPreviousSession() {
            calls.add("keep");
        }

        @Override
        public void offer(String text) {
            calls.add("offer:" + text);
        }

        @Override
        public void restore() {
            calls.add("restore");
        }

        @Override
        public void schedule(long delayMillis, Runnable task) {
            delays.add(delayMillis);
            scheduled.add(task);
        }

        void runNext() {
            scheduled.removeFirst().run();
        }
    }
}
