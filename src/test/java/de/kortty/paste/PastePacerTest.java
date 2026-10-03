package de.kortty.paste;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.assertThrows;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.testng.annotations.Test;

class PastePacerTest {

    private static final String START = PasteSanitizer.START_MARKER;

    private static final String END = PasteSanitizer.END_MARKER;

    /** Keeps every scheduled task until the test runs it, like a timer whose pause is always over. */
    private static final class ManualScheduler implements PastePacer.Scheduler {
        final Deque<Task> tasks = new ArrayDeque<>();
        final List<Long> delays = new ArrayList<>();
        RuntimeException failure;

        @Override
        public PastePacer.Cancellable schedule(Runnable task, long delayMs) {
            if (failure != null) {
                throw failure;
            }
            delays.add(delayMs);
            Task scheduled = new Task(task);
            tasks.add(scheduled);
            return () -> scheduled.cancelled = true;
        }

        /** Runs the oldest task, as its timer would; a cancelled one is dropped without running. */
        boolean runNext() {
            Task task = tasks.poll();
            if (task == null) {
                return false;
            }
            if (!task.cancelled) {
                task.runnable.run();
            }
            return true;
        }

        void runAll() {
            while (runNext()) {
                // keep going
            }
        }

        boolean hasLiveTask() {
            return tasks.stream().anyMatch(task -> !task.cancelled);
        }

        static final class Task {
            final Runnable runnable;
            boolean cancelled;

            Task(Runnable runnable) {
                this.runnable = runnable;
            }
        }
    }

    /** Remembers what the pacer reports, as "progress 1/3" and "ended COMPLETED 3/3". */
    private static final class RecordingListener implements PastePacer.Listener {
        final List<String> events = new ArrayList<>();

        @Override
        public void progressed(Object key, int sent, int total) {
            events.add("progress " + sent + "/" + total);
        }

        @Override
        public void ended(Object key, PastePacer.Outcome outcome, int sent, int total) {
            events.add("ended " + outcome + " " + sent + "/" + total);
        }
    }

    private static final class FakeTarget implements PasteTarget {
        final Object key = new Object();
        final List<String> sent = new ArrayList<>();
        boolean canReceive = true;
        Object session = new Object();
        RuntimeException sendFailure;

        @Override
        public Object key() {
            return key;
        }

        @Override
        public boolean bracketedPasteMode() {
            return false;
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
            return "switch-01";
        }
    }

    @Test
    void linesAreSplitAfterEveryCarriageReturn() {
        assertThat(PastePacer.lines("one\rtwo\rthree")).containsExactly("one\r", "two\r", "three").inOrder();
        assertThat(PastePacer.lines("one\rtwo\r")).containsExactly("one\r", "two\r").inOrder();
        assertThat(PastePacer.lines("\r\rx")).containsExactly("\r", "\r", "x").inOrder();
        assertThat(PastePacer.lines("single")).containsExactly("single");
        assertThat(PastePacer.lines("")).isEmpty();
        assertThat(PastePacer.lines(null)).isEmpty();
    }

    @Test
    void aBracketedPasteKeepsItsStartMarkerOnTheFirstLineAndItsEndMarkerOnTheLast() {
        assertThat(PastePacer.lines(START + "one\rtwo" + END))
            .containsExactly(START + "one\r", "two" + END).inOrder();
        // A trailing line break would leave the end marker alone; it travels with the last line.
        assertThat(PastePacer.lines(START + "one\rtwo\r" + END))
            .containsExactly(START + "one\r", "two\r" + END).inOrder();
        assertThat(PastePacer.lines(START + "\r" + END)).containsExactly(START + "\r" + END);
    }

    @Test
    void theLinesJoinedGiveThePayloadBack() {
        for (String payload : List.of("a\rb\rc", "a\r", START + "a\rb\r" + END, "\r", "x\r\ry")) {
            assertThat(String.join("", PastePacer.lines(payload))).isEqualTo(payload);
        }
    }

    @Test
    void theDelayIsLimitedTo0Through1000Milliseconds() {
        assertThat(PastePacer.clampLineDelayMs(-5)).isEqualTo(0);
        assertThat(PastePacer.clampLineDelayMs(0)).isEqualTo(0);
        assertThat(PastePacer.clampLineDelayMs(250)).isEqualTo(250);
        assertThat(PastePacer.clampLineDelayMs(1000)).isEqualTo(1000);
        assertThat(PastePacer.clampLineDelayMs(Integer.MAX_VALUE)).isEqualTo(PastePacer.MAX_LINE_DELAY_MS);
        assertThat(PastePacer.MAX_LINE_DELAY_MS).isEqualTo(1000);
    }

    @Test
    void withoutADelayThePasteIsSentAtOnceInOnePiece() {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingListener listener = new RecordingListener();
        PastePacer pacer = new PastePacer(scheduler, listener);
        FakeTarget target = new FakeTarget();

        assertThat(pacer.send(target, "one\rtwo\r", 0)).isTrue();

        assertThat(target.sent).containsExactly("one\rtwo\r");
        assertThat(pacer.isPacing(target.key())).isFalse();
        assertThat(scheduler.tasks).isEmpty();
        assertThat(listener.events).isEmpty();
    }

    @Test
    void aSingleLineIsSentAtOnceEvenWithADelay() {
        ManualScheduler scheduler = new ManualScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        FakeTarget target = new FakeTarget();

        pacer.send(target, START + "only line\r" + END, 200);

        assertThat(target.sent).containsExactly(START + "only line\r" + END);
        assertThat(pacer.isPacing(target.key())).isFalse();
        assertThat(scheduler.tasks).isEmpty();
    }

    @Test
    void theLinesGoOutInOrderOnePauseApart() {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingListener listener = new RecordingListener();
        PastePacer pacer = new PastePacer(scheduler, listener);
        FakeTarget target = new FakeTarget();

        pacer.send(target, "conf t\rinterface ge-0/0/1\rdescription uplink\r", 150);

        // The first line at once, the next one only when its pause is over.
        assertThat(target.sent).containsExactly("conf t\r");
        assertThat(pacer.isPacing(target.key())).isTrue();
        assertThat(scheduler.delays).containsExactly(150L);

        scheduler.runNext();
        assertThat(target.sent).containsExactly("conf t\r", "interface ge-0/0/1\r").inOrder();
        scheduler.runNext();
        assertThat(target.sent)
            .containsExactly("conf t\r", "interface ge-0/0/1\r", "description uplink\r").inOrder();

        assertThat(scheduler.delays).containsExactly(150L, 150L);
        assertThat(scheduler.tasks).isEmpty();
        assertThat(listener.events)
            .containsExactly("progress 1/3", "progress 2/3", "progress 3/3", "ended COMPLETED 3/3").inOrder();
    }

    @Test
    void theHoldEndsWhenTheLastLineIsOut() {
        ManualScheduler scheduler = new ManualScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        FakeTarget target = new FakeTarget();

        pacer.send(target, "a\rb", 100);
        assertThat(pacer.isPacing(target.key())).isTrue();
        assertThat(pacer.isPacingAny()).isTrue();

        scheduler.runAll();

        assertThat(pacer.isPacing(target.key())).isFalse();
        assertThat(pacer.isPacingAny()).isFalse();
        // The pane takes the next paste again.
        assertThat(pacer.send(target, "c\rd", 100)).isTrue();
        assertThat(pacer.isPacing(target.key())).isTrue();
    }

    @Test
    void aBracketedPasteArrivesAsOneBlockWithItsMarkersOnTheFirstAndLastLine() {
        ManualScheduler scheduler = new ManualScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        FakeTarget target = new FakeTarget();

        pacer.send(target, START + "one\rtwo\rthree" + END, 50);
        scheduler.runAll();

        assertThat(target.sent).containsExactly(START + "one\r", "two\r", "three" + END).inOrder();
        assertThat(String.join("", target.sent)).isEqualTo(START + "one\rtwo\rthree" + END);
    }

    @Test
    void theDelayIsClampedToTheMaximum() {
        ManualScheduler scheduler = new ManualScheduler();
        PastePacer pacer = new PastePacer(scheduler);

        pacer.send(new FakeTarget(), "a\rb", 60_000);

        assertThat(scheduler.delays).containsExactly((long) PastePacer.MAX_LINE_DELAY_MS);
    }

    @Test
    void cancelStopsTheRemainingLines() {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingListener listener = new RecordingListener();
        PastePacer pacer = new PastePacer(scheduler, listener);
        FakeTarget target = new FakeTarget();
        pacer.send(target, "one\rtwo\rthree\r", 100);
        scheduler.runNext();

        assertThat(pacer.cancel(target.key())).isTrue();

        assertThat(scheduler.hasLiveTask()).isFalse();
        scheduler.runAll();
        assertThat(target.sent).containsExactly("one\r", "two\r").inOrder();
        assertThat(pacer.isPacing(target.key())).isFalse();
        assertThat(listener.events).containsExactly("progress 1/3", "progress 2/3", "ended CANCELLED 2/3").inOrder();
        assertThat(pacer.cancel(target.key())).isFalse();
    }

    @Test
    void aTaskThatRunsAfterTheCancelSendsNothing() {
        // A timer that does not honour the cancel: the run itself must notice it was stopped.
        List<Runnable> tasks = new ArrayList<>();
        PastePacer pacer = new PastePacer((task, delayMs) -> {
            tasks.add(task);
            return () -> { };
        });
        FakeTarget target = new FakeTarget();
        pacer.send(target, "one\rtwo\r", 100);
        pacer.cancel(target.key());

        tasks.forEach(Runnable::run);

        assertThat(target.sent).containsExactly("one\r");
    }

    @Test
    void aCancelledBracketedPasteIsClosedSoTheProgramLeavesPasteMode() {
        ManualScheduler scheduler = new ManualScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        FakeTarget target = new FakeTarget();
        pacer.send(target, START + "one\rtwo\rthree" + END, 100);

        pacer.cancel(target.key());

        assertThat(target.sent).containsExactly(START + "one\r", END).inOrder();
    }

    @Test
    void aCancelledUnbracketedPasteGetsNoMarker() {
        ManualScheduler scheduler = new ManualScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        FakeTarget target = new FakeTarget();
        pacer.send(target, "one\rtwo", 100);

        pacer.cancel(target.key());

        assertThat(target.sent).containsExactly("one\r");
    }

    @Test
    void aReplacedSessionGetsNoMoreLinesAndNoEndMarker() {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingListener listener = new RecordingListener();
        PastePacer pacer = new PastePacer(scheduler, listener);
        FakeTarget target = new FakeTarget();
        pacer.send(target, START + "one\rtwo\rthree" + END, 100);

        // A reconnect binds a new session to the pane while the paste waits for its next line.
        target.session = new Object();
        scheduler.runAll();

        assertThat(target.sent).containsExactly(START + "one\r");
        assertThat(pacer.isPacing(target.key())).isFalse();
        assertThat(listener.events).containsExactly("progress 1/3", "ended INTERRUPTED 1/3").inOrder();
    }

    @Test
    void aRebindCancelsBeforeTheNewSessionIsBound() {
        // TerminalView cancels in decorateTerminalConnector, while the old session is still bound.
        ManualScheduler scheduler = new ManualScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        FakeTarget target = new FakeTarget();
        pacer.send(target, START + "one\rtwo" + END, 100);

        pacer.cancel(target.key());
        target.session = new Object();
        scheduler.runAll();

        assertThat(target.sent).containsExactly(START + "one\r", END).inOrder();
        assertThat(pacer.isPacing(target.key())).isFalse();
    }

    @Test
    void aPaneThatLostItsConnectionGetsNoMoreLines() {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingListener listener = new RecordingListener();
        PastePacer pacer = new PastePacer(scheduler, listener);
        FakeTarget target = new FakeTarget();
        pacer.send(target, START + "one\rtwo" + END, 100);

        target.canReceive = false;
        scheduler.runAll();

        assertThat(target.sent).containsExactly(START + "one\r");
        assertThat(listener.events).containsExactly("progress 1/2", "ended INTERRUPTED 1/2").inOrder();
    }

    @Test
    void aSecondPasteWhileThePaneIsPacingIsRefused() {
        ManualScheduler scheduler = new ManualScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        FakeTarget target = new FakeTarget();
        pacer.send(target, "one\rtwo", 100);

        assertThat(pacer.send(target, "other\rpaste", 100)).isFalse();
        assertThat(pacer.send(target, "even-a-single-line", 0)).isFalse();
        scheduler.runAll();

        assertThat(target.sent).containsExactly("one\r", "two").inOrder();
    }

    @Test
    void anotherPaneCanPaceAtTheSameTime() {
        ManualScheduler scheduler = new ManualScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        FakeTarget first = new FakeTarget();
        FakeTarget second = new FakeTarget();

        pacer.send(first, "a\rb", 100);
        pacer.send(second, "c\rd", 100);
        assertThat(pacer.isPacing(first.key())).isTrue();
        assertThat(pacer.isPacing(second.key())).isTrue();

        pacer.cancel(first.key());
        scheduler.runAll();

        assertThat(first.sent).containsExactly("a\r");
        assertThat(second.sent).containsExactly("c\r", "d").inOrder();
    }

    @Test
    void cancelAllStopsEveryPane() {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingListener listener = new RecordingListener();
        PastePacer pacer = new PastePacer(scheduler, listener);
        FakeTarget first = new FakeTarget();
        FakeTarget second = new FakeTarget();
        pacer.send(first, "a\rb", 100);
        pacer.send(second, "c\rd", 100);

        pacer.cancelAll();
        scheduler.runAll();

        assertThat(pacer.isPacingAny()).isFalse();
        assertThat(first.sent).containsExactly("a\r");
        assertThat(second.sent).containsExactly("c\r");
        assertThat(listener.events).containsAtLeast("ended CANCELLED 1/2", "ended CANCELLED 1/2");
    }

    @Test
    void aLineThatCannotBeSentEndsThePasteQuietly() {
        ManualScheduler scheduler = new ManualScheduler();
        RecordingListener listener = new RecordingListener();
        PastePacer pacer = new PastePacer(scheduler, listener);
        FakeTarget target = new FakeTarget();
        pacer.send(target, "one\rtwo\rthree", 100);

        target.sendFailure = new IllegalStateException("stream closed");
        scheduler.runAll();

        assertThat(target.sent).containsExactly("one\r");
        assertThat(pacer.isPacing(target.key())).isFalse();
        assertThat(listener.events).containsExactly("progress 1/3", "ended INTERRUPTED 1/3").inOrder();
    }

    @Test
    void sendingAtOnceReportsAFailureToTheCaller() {
        PastePacer pacer = new PastePacer(new ManualScheduler());
        FakeTarget target = new FakeTarget();
        target.sendFailure = new IllegalStateException("stream closed");

        assertThrows(IllegalStateException.class, () -> pacer.send(target, "one\rtwo", 0));
        assertThat(pacer.isPacing(target.key())).isFalse();
    }

    @Test
    void aSchedulerThatFailsEndsThePasteAndClosesTheBracket() {
        ManualScheduler scheduler = new ManualScheduler();
        scheduler.failure = new IllegalStateException("timer shut down");
        RecordingListener listener = new RecordingListener();
        PastePacer pacer = new PastePacer(scheduler, listener);
        FakeTarget target = new FakeTarget();

        pacer.send(target, START + "one\rtwo" + END, 100);

        assertThat(target.sent).containsExactly(START + "one\r", END).inOrder();
        assertThat(pacer.isPacing(target.key())).isFalse();
        assertThat(listener.events).containsExactly("progress 1/2", "ended INTERRUPTED 1/2").inOrder();
    }

    @Test
    void aListenerThatFailsDoesNotStopThePaste() {
        ManualScheduler scheduler = new ManualScheduler();
        PastePacer pacer = new PastePacer(scheduler, new PastePacer.Listener() {
            @Override
            public void progressed(Object key, int sent, int total) {
                throw new IllegalStateException("indicator gone");
            }

            @Override
            public void ended(Object key, PastePacer.Outcome outcome, int sent, int total) {
                throw new IllegalStateException("indicator gone");
            }
        });
        FakeTarget target = new FakeTarget();

        pacer.send(target, "a\rb\rc", 10);
        scheduler.runAll();

        assertThat(target.sent).containsExactly("a\r", "b\r", "c").inOrder();
        assertThat(pacer.isPacing(target.key())).isFalse();
    }

    @Test
    void aListenerMayCancelFromItsProgressCallback() {
        ManualScheduler scheduler = new ManualScheduler();
        AtomicInteger ended = new AtomicInteger();
        PastePacer[] pacer = new PastePacer[1];
        pacer[0] = new PastePacer(scheduler, new PastePacer.Listener() {
            @Override
            public void progressed(Object key, int sent, int total) {
                if (sent == 2) {
                    pacer[0].cancel(key);
                }
            }

            @Override
            public void ended(Object key, PastePacer.Outcome outcome, int sent, int total) {
                ended.incrementAndGet();
            }
        });
        FakeTarget target = new FakeTarget();

        pacer[0].send(target, "a\rb\rc", 10);
        scheduler.runAll();

        assertThat(target.sent).containsExactly("a\r", "b\r").inOrder();
        assertThat(ended.get()).isEqualTo(1);
    }

    @Test
    void emptyPayloadsSendNothing() {
        ManualScheduler scheduler = new ManualScheduler();
        PastePacer pacer = new PastePacer(scheduler);
        FakeTarget target = new FakeTarget();

        assertThat(pacer.send(target, "", 100)).isTrue();
        assertThat(pacer.send(target, null, 100)).isTrue();

        assertThat(target.sent).isEmpty();
        assertThat(pacer.isPacing(target.key())).isFalse();
        assertThat(pacer.isPacing(null)).isFalse();
        assertThat(pacer.cancel(null)).isFalse();
    }

    @Test
    void theTimerSchedulerRunsTheTaskOnTheGivenThreadAfterThePause() throws Exception {
        ScheduledExecutorService timer = new ScheduledThreadPoolExecutor(1);
        try {
            List<Runnable> handedOver = new ArrayList<>();
            CountDownLatch waited = new CountDownLatch(1);
            PastePacer.Scheduler scheduler = PastePacer.Scheduler.timer(timer, task -> {
                synchronized (handedOver) {
                    handedOver.add(task);
                }
                waited.countDown();
            });
            AtomicInteger runs = new AtomicInteger();

            scheduler.schedule(runs::incrementAndGet, 20);

            assertThat(waited.await(5, TimeUnit.SECONDS)).isTrue();
            // Nothing runs on the timer thread itself; the given thread runs the task.
            assertThat(runs.get()).isEqualTo(0);
            synchronized (handedOver) {
                handedOver.forEach(Runnable::run);
            }
            assertThat(runs.get()).isEqualTo(1);
        } finally {
            timer.shutdownNow();
        }
    }

    @Test
    void aTaskCancelledAfterItsPauseButBeforeItRanNeverRuns() throws Exception {
        ScheduledExecutorService timer = new ScheduledThreadPoolExecutor(1);
        try {
            List<Runnable> handedOver = new ArrayList<>();
            CountDownLatch waited = new CountDownLatch(1);
            PastePacer.Scheduler scheduler = PastePacer.Scheduler.timer(timer, task -> {
                synchronized (handedOver) {
                    handedOver.add(task);
                }
                waited.countDown();
            });
            AtomicInteger runs = new AtomicInteger();

            PastePacer.Cancellable handle = scheduler.schedule(runs::incrementAndGet, 0);
            assertThat(waited.await(5, TimeUnit.SECONDS)).isTrue();
            handle.cancel();
            synchronized (handedOver) {
                handedOver.forEach(Runnable::run);
            }

            assertThat(runs.get()).isEqualTo(0);
        } finally {
            timer.shutdownNow();
        }
    }

    @Test
    void aTaskCancelledDuringItsPauseIsNeverHandedOver() throws Exception {
        ScheduledExecutorService timer = new ScheduledThreadPoolExecutor(1);
        try {
            AtomicInteger handedOver = new AtomicInteger();
            PastePacer.Scheduler scheduler = PastePacer.Scheduler.timer(timer, task -> {
                handedOver.incrementAndGet();
                task.run();
            });

            scheduler.schedule(() -> { }, 60_000).cancel();
            timer.shutdown();

            assertThat(timer.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
            assertThat(handedOver.get()).isEqualTo(0);
        } finally {
            timer.shutdownNow();
        }
    }

    @Test
    void theSharedTimerWorksWithADirectThread() throws Exception {
        CountDownLatch ran = new CountDownLatch(1);

        PastePacer.Scheduler.sharedTimer(Runnable::run).schedule(ran::countDown, 1);

        assertThat(ran.await(5, TimeUnit.SECONDS)).isTrue();
    }
}
