package de.kortty.paste;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import org.testng.annotations.Test;

class PasteGuardTest {

    private static final String ESC = "\u001b";

    private static final PasteRules ALWAYS_MULTI_LINE = (text, bracketed) -> EnumSet.of(PasteReason.MULTI_LINE);

    /** A pane; several instances may share one key, like two adapters for the same widget. */
    private static final class FakeTarget implements PasteTarget {
        final Object key;
        final List<String> sent = new ArrayList<>();
        boolean bracketed;
        boolean canReceive = true;
        Object session = new Object();
        Charset charset = StandardCharsets.UTF_8;
        boolean broadcast;
        RuntimeException sendFailure;

        FakeTarget() {
            this(new Object());
        }

        FakeTarget(Object key) {
            this.key = key;
        }

        @Override
        public Object key() {
            return key;
        }

        @Override
        public boolean bracketedPasteMode() {
            return bracketed;
        }

        @Override
        public boolean canReceive() {
            return canReceive;
        }

        @Override
        public Object session() {
            return session;
        }

        @Override
        public void send(String payload) {
            if (sendFailure != null) {
                throw sendFailure;
            }
            sent.add(payload);
        }

        @Override
        public String label() {
            return "web-01";
        }

        @Override
        public Charset charset() {
            return charset;
        }

        @Override
        public boolean broadcastActive() {
            return broadcast;
        }
    }

    /** Keeps every request and its answer callback, so a test answers when it wants to. */
    private static final class RecordingConfirmer implements PasteConfirmer {
        final List<PasteConfirmationRequest> requests = new ArrayList<>();
        final List<Consumer<Boolean>> answers = new ArrayList<>();

        @Override
        public void confirm(PasteConfirmationRequest request, Consumer<Boolean> answer) {
            requests.add(request);
            answers.add(answer);
        }

        void answer(int index, boolean accepted) {
            answers.get(index).accept(accepted);
        }
    }

    @Test
    void withoutAReasonThePasteIsSentOnceAndEncoded() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> PasteRules.NONE, confirmer);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "echo a\r\necho b\n", PasteSource.CLIPBOARD);

        assertThat(target.sent).containsExactly("echo a\recho b\r");
        assertThat(confirmer.requests).isEmpty();
    }

    @Test
    void anEmbeddedEndMarkerNeverReachesABracketedPane() {
        PasteGuard guard = new PasteGuard(() -> PasteRules.NONE, new RecordingConfirmer());
        FakeTarget target = new FakeTarget();
        target.bracketed = true;

        guard.paste(target, "echo safe" + ESC + "[201~\nrm -rf ~\n", PasteSource.CLIPBOARD);

        assertThat(target.sent).containsExactly(ESC + "[200~echo safe\rrm -rf ~\r" + ESC + "[201~");
    }

    @Test
    void anUnbracketedPaneGetsTheTextWithoutMarkersAndWithoutWrapping() {
        PasteGuard guard = new PasteGuard(() -> PasteRules.NONE, new RecordingConfirmer());
        FakeTarget target = new FakeTarget();

        guard.paste(target, ESC + "[200~ls" + ESC + "[201~", PasteSource.SELECTION);

        assertThat(target.sent).containsExactly("ls");
    }

    @Test
    void thePanesEncodingDecidesTheEightBitMarker() {
        PasteGuard guard = new PasteGuard(() -> PasteRules.NONE, new RecordingConfirmer());
        FakeTarget target = new FakeTarget();
        target.bracketed = true;
        target.charset = Charset.forName("Windows-1252");

        guard.paste(target, "a›201~b", PasteSource.CLIPBOARD);

        assertThat(target.sent).containsExactly(ESC + "[200~ab" + ESC + "[201~");
    }

    @Test
    void aDeclinedPasteSendsNothing() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> ALWAYS_MULTI_LINE, confirmer);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "a\nb", PasteSource.CLIPBOARD);
        assertThat(target.sent).isEmpty();
        confirmer.answer(0, false);

        assertThat(target.sent).isEmpty();
        assertThat(guard.isPending(target.key())).isFalse();
    }

    @Test
    void anAcceptedPasteIsBracketedAsThePaneIsWhenItIsSent() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> ALWAYS_MULTI_LINE, confirmer);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "a\nb", PasteSource.CLIPBOARD);
        assertThat(confirmer.requests.get(0).bracketed()).isFalse();
        // The shell reached its prompt and enabled bracketed paste while the user was deciding.
        target.bracketed = true;
        confirmer.answer(0, true);

        assertThat(target.sent).containsExactly(ESC + "[200~a\rb" + ESC + "[201~");
        assertThat(guard.isPending(target.key())).isFalse();
    }

    @Test
    void theRequestCarriesWhatTheConfirmationShows() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(
            () -> (text, bracketed) -> EnumSet.of(PasteReason.CONTROL_CHARACTERS, PasteReason.LARGE), confirmer);
        FakeTarget target = new FakeTarget();
        target.bracketed = true;
        target.broadcast = true;

        guard.paste(target, "x\u0003y", PasteSource.SELECTION);

        PasteConfirmationRequest request = confirmer.requests.get(0);
        assertThat(request.label()).isEqualTo("web-01");
        assertThat(request.text()).isEqualTo("x\u0003y");
        assertThat(request.reasons()).containsExactly(PasteReason.CONTROL_CHARACTERS, PasteReason.LARGE);
        assertThat(request.bracketed()).isTrue();
        assertThat(request.source()).isEqualTo(PasteSource.SELECTION);
        assertThat(request.broadcastActive()).isTrue();
    }

    @Test
    void theRequestListsTheReasonsInTheEnumsOrder() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(
            () -> (text, bracketed) -> Set.of(PasteReason.LARGE, PasteReason.MULTI_LINE), confirmer);

        guard.paste(new FakeTarget(), "a\nb", PasteSource.CLIPBOARD);

        assertThat(confirmer.requests.get(0).reasons())
            .containsExactly(PasteReason.MULTI_LINE, PasteReason.LARGE).inOrder();
    }

    @Test
    void theRulesSeeTheTextAsItCameFromTheClipboard() {
        List<String> seen = new ArrayList<>();
        List<Boolean> bracketedSeen = new ArrayList<>();
        PasteGuard guard = new PasteGuard(() -> (text, bracketed) -> {
            seen.add(text);
            bracketedSeen.add(bracketed);
            return Set.of();
        }, new RecordingConfirmer());
        FakeTarget target = new FakeTarget();
        target.bracketed = true;

        guard.paste(target, "a" + ESC + "[201~\r\n", PasteSource.CLIPBOARD);

        assertThat(seen).containsExactly("a" + ESC + "[201~\r\n");
        assertThat(bracketedSeen).containsExactly(true);
    }

    @Test
    void aSecondPasteWhileThePaneAsksIsDroppedEvenThroughAnotherAdapter() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> ALWAYS_MULTI_LINE, confirmer);
        Object pane = new Object();
        FakeTarget first = new FakeTarget(pane);
        FakeTarget second = new FakeTarget(pane);
        second.session = first.session;

        guard.paste(first, "a\nb", PasteSource.CLIPBOARD);
        guard.paste(second, "a\nb", PasteSource.CLIPBOARD);

        assertThat(confirmer.requests).hasSize(1);
        confirmer.answer(0, true);
        assertThat(first.sent).containsExactly("a\rb");
        assertThat(second.sent).isEmpty();

        // Once answered, the pane can ask again.
        guard.paste(second, "c\nd", PasteSource.CLIPBOARD);
        assertThat(confirmer.requests).hasSize(2);
    }

    @Test
    void anotherPaneCanAskWhileOneIsPending() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> ALWAYS_MULTI_LINE, confirmer);

        guard.paste(new FakeTarget(), "a\nb", PasteSource.CLIPBOARD);
        guard.paste(new FakeTarget(), "a\nb", PasteSource.CLIPBOARD);

        assertThat(confirmer.requests).hasSize(2);
    }

    @Test
    void aPasteConfirmedForASessionThatWasReplacedIsDropped() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> ALWAYS_MULTI_LINE, confirmer);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "a\nb", PasteSource.CLIPBOARD);
        target.session = new Object(); // reconnected while the confirmation was open
        confirmer.answer(0, true);

        assertThat(target.sent).isEmpty();
        assertThat(guard.isPending(target.key())).isFalse();
    }

    @Test
    void aPasteConfirmedForAPaneThatLostItsSessionIsDropped() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> ALWAYS_MULTI_LINE, confirmer);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "a\nb", PasteSource.CLIPBOARD);
        target.canReceive = false;
        confirmer.answer(0, true);

        assertThat(target.sent).isEmpty();
    }

    @Test
    void aPaneThatCannotReceiveIsNeitherAskedNorSentTo() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> ALWAYS_MULTI_LINE, confirmer);
        FakeTarget target = new FakeTarget();
        target.canReceive = false;

        guard.paste(target, "a\nb", PasteSource.CLIPBOARD);

        assertThat(confirmer.requests).isEmpty();
        assertThat(target.sent).isEmpty();
    }

    @Test
    void emptyAndNullTextAreIgnored() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> ALWAYS_MULTI_LINE, confirmer);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "", PasteSource.CLIPBOARD);
        guard.paste(target, null, PasteSource.CLIPBOARD);
        guard.paste(null, "a", PasteSource.CLIPBOARD);

        assertThat(confirmer.requests).isEmpty();
        assertThat(target.sent).isEmpty();
    }

    @Test
    void aPasteOfOnlyMarkersSendsNothing() {
        PasteGuard guard = new PasteGuard(() -> PasteRules.NONE, new RecordingConfirmer());
        FakeTarget target = new FakeTarget();
        target.bracketed = true;

        guard.paste(target, ESC + "[200~" + ESC + "[201~", PasteSource.CLIPBOARD);

        assertThat(target.sent).isEmpty();
    }

    @Test
    void onlyTheFirstAnswerCounts() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> ALWAYS_MULTI_LINE, confirmer);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "a\nb", PasteSource.CLIPBOARD);
        confirmer.answer(0, false);
        confirmer.answer(0, true);

        assertThat(target.sent).isEmpty();
    }

    @Test
    void aConfirmerThatFailsLeavesThePaneFreeToAskAgain() {
        PasteGuard guard = new PasteGuard(() -> ALWAYS_MULTI_LINE, (request, answer) -> {
            throw new IllegalStateException("no window");
        });
        FakeTarget target = new FakeTarget();

        guard.paste(target, "a\nb", PasteSource.CLIPBOARD);

        assertThat(target.sent).isEmpty();
        assertThat(guard.isPending(target.key())).isFalse();
    }

    @Test
    void nullRulesAndNullReasonsCountAsNoReason() {
        FakeTarget target = new FakeTarget();
        new PasteGuard(() -> null, new RecordingConfirmer()).paste(target, "a\nb", PasteSource.CLIPBOARD);
        new PasteGuard(() -> (text, bracketed) -> null, new RecordingConfirmer())
            .paste(target, "c\nd", PasteSource.CLIPBOARD);

        assertThat(target.sent).containsExactly("a\rb", "c\rd").inOrder();
    }

    @Test
    void aFailingSendIsSwallowed() {
        PasteGuard guard = new PasteGuard(() -> PasteRules.NONE, new RecordingConfirmer());
        FakeTarget target = new FakeTarget();
        target.sendFailure = new IllegalStateException("executor shut down");

        guard.paste(target, "ls", PasteSource.CLIPBOARD);

        assertThat(target.sent).isEmpty();
    }

    /** Keeps the scheduled lines of a paced paste until the test runs them. */
    private static final class QueuedScheduler implements PastePacer.Scheduler {
        final List<Runnable> tasks = new ArrayList<>();

        @Override
        public PastePacer.Cancellable schedule(Runnable task, long delayMs) {
            tasks.add(task);
            return () -> tasks.remove(task);
        }

        void runAll() {
            while (!tasks.isEmpty()) {
                tasks.remove(0).run();
            }
        }
    }

    @Test
    void withALineDelayAPasteGoesOutLineByLine() {
        QueuedScheduler scheduler = new QueuedScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        PasteGuard guard = new PasteGuard(() -> PasteRules.NONE, new RecordingConfirmer(), pacer, () -> 100);
        FakeTarget target = new FakeTarget();
        target.bracketed = true;

        guard.paste(target, "one\ntwo\nthree", PasteSource.CLIPBOARD);

        assertThat(target.sent).containsExactly(ESC + "[200~one\r");
        assertThat(pacer.isPacing(target.key())).isTrue();
        scheduler.runAll();
        assertThat(target.sent).containsExactly(ESC + "[200~one\r", "two\r", "three" + ESC + "[201~").inOrder();
    }

    @Test
    void aConfirmedPasteIsPacedToo() {
        QueuedScheduler scheduler = new QueuedScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteGuard guard = new PasteGuard(() -> ALWAYS_MULTI_LINE, confirmer, pacer, () -> 100);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "one\ntwo", PasteSource.CLIPBOARD);
        assertThat(target.sent).isEmpty();
        confirmer.answer(0, true);

        assertThat(target.sent).containsExactly("one\r");
        scheduler.runAll();
        assertThat(target.sent).containsExactly("one\r", "two").inOrder();
    }

    @Test
    void aPasteIntoAPaneThatIsStillPacingIsDroppedWithoutAsking() {
        QueuedScheduler scheduler = new QueuedScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        RecordingConfirmer confirmer = new RecordingConfirmer();
        boolean[] ask = {false};
        PasteGuard guard = new PasteGuard(() -> ask[0] ? ALWAYS_MULTI_LINE : PasteRules.NONE, confirmer, pacer,
            () -> 100);
        FakeTarget target = new FakeTarget();
        guard.paste(target, "one\ntwo", PasteSource.CLIPBOARD);

        ask[0] = true;
        guard.paste(target, "three\nfour", PasteSource.CLIPBOARD);
        ask[0] = false;
        guard.paste(target, "five", PasteSource.SELECTION);
        scheduler.runAll();

        assertThat(confirmer.requests).isEmpty();
        assertThat(target.sent).containsExactly("one\r", "two").inOrder();
        // Once the paced paste is out, the pane takes pastes again.
        guard.paste(target, "six", PasteSource.CLIPBOARD);
        assertThat(target.sent).containsExactly("one\r", "two", "six").inOrder();
    }

    @Test
    void withoutALineDelayThePasteGoesOutAtOnce() {
        QueuedScheduler scheduler = new QueuedScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        FakeTarget target = new FakeTarget();

        new PasteGuard(() -> PasteRules.NONE, new RecordingConfirmer(), pacer, () -> 0)
            .paste(target, "one\ntwo", PasteSource.CLIPBOARD);

        assertThat(target.sent).containsExactly("one\rtwo");
        assertThat(scheduler.tasks).isEmpty();
    }

    @Test
    void anUnreadableLineDelayPastesAtOnce() {
        QueuedScheduler scheduler = new QueuedScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        FakeTarget target = new FakeTarget();

        new PasteGuard(() -> PasteRules.NONE, new RecordingConfirmer(), pacer, () -> {
            throw new IllegalStateException("no settings");
        }).paste(target, "one\ntwo", PasteSource.CLIPBOARD);

        assertThat(target.sent).containsExactly("one\rtwo");
        assertThat(pacer.isPacing(target.key())).isFalse();
    }

    @Test
    void theLineDelayIsReadWhenThePasteIsSent() {
        QueuedScheduler scheduler = new QueuedScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        RecordingConfirmer confirmer = new RecordingConfirmer();
        int[] delay = {0};
        PasteGuard guard = new PasteGuard(() -> ALWAYS_MULTI_LINE, confirmer, pacer, () -> delay[0]);
        FakeTarget target = new FakeTarget();

        guard.paste(target, "one\ntwo", PasteSource.CLIPBOARD);
        delay[0] = 250;
        confirmer.answer(0, true);

        assertThat(target.sent).containsExactly("one\r");
        assertThat(pacer.isPacing(target.key())).isTrue();
    }

    @Test
    void eachPaneIsAskedAndPacedByTheRulesOfItsOwnConnection() {
        QueuedScheduler scheduler = new QueuedScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        RecordingConfirmer confirmer = new RecordingConfirmer();
        FakeTarget production = new FakeTarget();
        FakeTarget console = new FakeTarget();
        PasteGuard guard = new PasteGuard(
            target -> target == production ? ALWAYS_MULTI_LINE : PasteRules.NONE, confirmer, pacer,
            target -> target == console ? 100 : 0);

        guard.paste(production, "one\ntwo", PasteSource.CLIPBOARD);
        guard.paste(console, "three\nfour", PasteSource.CLIPBOARD);

        assertThat(confirmer.requests).hasSize(1);
        assertThat(production.sent).isEmpty();
        assertThat(console.sent).containsExactly("three\r");
        scheduler.runAll();
        assertThat(console.sent).containsExactly("three\r", "four").inOrder();

        confirmer.answer(0, true);
        assertWithMessage("the production pane has no line delay of its own, so it pastes at once")
            .that(production.sent).containsExactly("one\rtwo");
    }

    @Test
    void theRequestSaysWhetherThePanesConnectionSetTheWarning() {
        RecordingConfirmer confirmer = new RecordingConfirmer();
        PasteRules fromConnection = new PasteDecision(
            new PasteProtectionSettings(PasteWarningMode.ALWAYS, 0), true);
        FakeTarget connectionPane = new FakeTarget();
        PasteGuard guard = new PasteGuard(
            target -> target == connectionPane ? fromConnection : ALWAYS_MULTI_LINE, confirmer, null, target -> 0);

        guard.paste(connectionPane, "a\nb", PasteSource.CLIPBOARD);
        guard.paste(new FakeTarget(), "c\nd", PasteSource.CLIPBOARD);

        assertThat(confirmer.requests).hasSize(2);
        assertThat(confirmer.requests.get(0).setByConnection()).isTrue();
        assertThat(confirmer.requests.get(1).setByConnection()).isFalse();
    }
}
