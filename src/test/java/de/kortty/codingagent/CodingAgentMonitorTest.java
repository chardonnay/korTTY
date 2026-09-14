package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import java.time.Instant;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import org.testng.SkipException;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class CodingAgentMonitorTest {

    private static final PaneRef PANE = new PaneRef("tab-1", "terminal-1a2b");
    private static final DetectionResult BLOCKED = DetectionResult.of(CodingAgentKind.CLAUDE_CODE,
        CodingAgentState.BLOCKED, "permission-prompt", "Do you want to proceed?");
    private static final DetectionResult FALLBACK = DetectionResult.of(CodingAgentKind.CLAUDE_CODE,
        CodingAgentState.IDLE, null, null);
    private static final ScreenSnapshot SCREEN = ScreenSnapshot.ofText("Do you want to proceed?\n> ", null, false);
    private static final long AWAIT_MILLIS = 10_000L;

    private ScheduledExecutorService scheduler;
    private final AtomicInteger classifierCalls = new AtomicInteger();
    private final AtomicReference<DetectionResult> classifierResult = new AtomicReference<>(BLOCKED);
    private final AtomicReference<CountDownLatch> classifierLatch = new AtomicReference<>();
    private final AtomicInteger screenCalls = new AtomicInteger();
    private final AtomicBoolean screenThrows = new AtomicBoolean(false);
    private final AtomicInteger processCalls = new AtomicInteger();
    private final AtomicReference<AgentProcess> process = new AtomicReference<>();
    private final AtomicBoolean connected = new AtomicBoolean(true);
    private final AtomicBoolean enabled = new AtomicBoolean(true);
    private RecordingListener events;
    private CodingAgentMonitor monitor;

    @BeforeMethod
    void setUp() {
        scheduler = CodingAgentService.defaultScheduler();
        classifierCalls.set(0);
        classifierResult.set(BLOCKED);
        classifierLatch.set(null);
        screenCalls.set(0);
        screenThrows.set(false);
        processCalls.set(0);
        process.set(liveProcess());
        connected.set(true);
        enabled.set(true);
        events = new RecordingListener();
        monitor = newMonitor(Runnable::run);
    }

    @AfterMethod
    void tearDown() {
        monitor.close();
        scheduler.shutdownNow();
    }

    private CodingAgentMonitor newMonitor(Executor uiExecutor) {
        return new CodingAgentMonitor(PANE, this::captureScreen, this::classify, enabled::get, scheduler,
            uiExecutor, events);
    }

    private ScreenSnapshot captureScreen() {
        screenCalls.incrementAndGet();
        if (screenThrows.get()) {
            throw new IllegalStateException("buffer gone");
        }
        return SCREEN;
    }

    private DetectionResult classify(CodingAgentKind kind, ScreenSnapshot snapshot) {
        classifierCalls.incrementAndGet();
        CountDownLatch latch = classifierLatch.get();
        if (latch != null) {
            latch.countDown();
        }
        return classifierResult.get();
    }

    private Optional<AgentProcess> processSource() {
        processCalls.incrementAndGet();
        return Optional.ofNullable(process.get());
    }

    private static AgentProcess liveProcess() {
        // The real start instant: AgentProcess.isAlive() rejects a pid whose occupant started at another time.
        return new AgentProcess(ProcessHandle.current().pid(), CodingAgentKind.CLAUDE_CODE, "claude",
            ProcessHandle.current().info().startInstant().orElse(null));
    }

    private void bindAndAwaitDetection() throws InterruptedException {
        monitor.bind(this::processSource, connected::get);
        assertThat(events.await(1, AWAIT_MILLIS)).isTrue();
        assertThat(events.last().reason()).isEqualTo(CodingAgentEvent.Reason.DETECTED);
        assertThat(monitor.current()).isEqualTo(BLOCKED);
    }

    private static void awaitCondition(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AWAIT_MILLIS);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("condition not met within " + AWAIT_MILLIS + " ms");
            }
            Thread.sleep(10);
        }
    }

    @Test(timeOut = 30_000)
    void unboundMonitorIsDormant() throws InterruptedException {
        monitor.markDirty();
        monitor.evaluateNow();

        assertThat(monitor.isBound()).isFalse();
        assertThat(events.await(1, 300)).isFalse();
        assertThat(screenCalls.get()).isEqualTo(0);
        assertThat(classifierCalls.get()).isEqualTo(0);
        assertThat(processCalls.get()).isEqualTo(0);
        assertThat(monitor.current()).isEqualTo(DetectionResult.NONE);
        assertThat(monitor.currentProcess()).isEmpty();
    }

    @Test(timeOut = 30_000)
    void processWithoutRuleMatchIsNotReported() throws InterruptedException {
        classifierResult.set(FALLBACK);
        monitor.bind(this::processSource, connected::get);
        awaitCondition(() -> classifierCalls.get() >= 1);
        monitor.evaluateNow();

        assertThat(monitor.isBound()).isTrue();
        assertThat(events.events()).isEmpty();
        assertThat(monitor.current()).isEqualTo(DetectionResult.NONE);
        assertThat(monitor.currentProcess()).isPresent();
    }

    @Test(timeOut = 30_000)
    void firstRuleMatchPublishesDetectionAndLaterFallbackIsAStateChange() throws InterruptedException {
        bindAndAwaitDetection();
        monitor.evaluateNow();

        assertThat(events.size()).isEqualTo(1);
        CodingAgentEvent detected = events.last();
        assertThat(detected.pane()).isEqualTo(PANE);
        assertThat(detected.previous()).isEqualTo(DetectionResult.NONE);
        assertThat(detected.current()).isEqualTo(BLOCKED);
        assertThat(detected.process()).isNotNull();
        assertThat(detected.process().pid()).isEqualTo(ProcessHandle.current().pid());
        assertThat(detected.isDetection()).isTrue();
        assertThat(detected.at()).isNotNull();

        classifierResult.set(FALLBACK);
        monitor.evaluateNow();

        assertThat(events.size()).isEqualTo(2);
        CodingAgentEvent changed = events.last();
        assertThat(changed.reason()).isEqualTo(CodingAgentEvent.Reason.STATE_CHANGED);
        assertThat(changed.previous()).isEqualTo(BLOCKED);
        assertThat(changed.current().fallbackApplied()).isTrue();
        assertThat(changed.current().state()).isEqualTo(CodingAgentState.IDLE);
        assertThat(changed.isStateChange()).isTrue();
        assertThat(monitor.current()).isEqualTo(FALLBACK);

        monitor.evaluateNow();
        monitor.evaluateNow();
        assertThat(events.size()).isEqualTo(2);
    }

    @Test(timeOut = 30_000)
    void deadProcessPublishesProcessExited() throws Exception {
        if (System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win")) {
            throw new SkipException("needs the `true` executable to obtain a finished pid");
        }
        Process finished = new ProcessBuilder("true").start();
        finished.waitFor();
        awaitCondition(() -> !finished.toHandle().isAlive());
        process.set(new AgentProcess(finished.pid(), CodingAgentKind.CODEX, "codex", Instant.now()));

        bindAndAwaitDetection();
        process.set(null);
        monitor.evaluateNow();

        assertThat(events.size()).isEqualTo(2);
        CodingAgentEvent removal = events.last();
        assertThat(removal.reason()).isEqualTo(CodingAgentEvent.Reason.PROCESS_EXITED);
        assertThat(removal.isRemoval()).isTrue();
        assertThat(removal.previous()).isEqualTo(BLOCKED);
        assertThat(removal.current()).isEqualTo(DetectionResult.NONE);
        assertThat(removal.process()).isNull();
        assertThat(monitor.current()).isEqualTo(DetectionResult.NONE);
        assertThat(monitor.currentProcess()).isEmpty();
    }

    @Test(timeOut = 30_000)
    void reusedPidWithAnotherStartTimeIsTreatedAsExited() throws InterruptedException {
        // The pid is alive (it is this JVM) but the recorded start time belongs to the dead agent, as
        // after the OS handed the agent's pid to an unrelated process.
        Instant realStart = ProcessHandle.current().info().startInstant().orElse(Instant.EPOCH);
        process.set(new AgentProcess(ProcessHandle.current().pid(), CodingAgentKind.CLAUDE_CODE, "claude",
            realStart.minusSeconds(3600)));
        bindAndAwaitDetection();

        // The stale record is not accepted as the live process: the cached detection is dropped
        // (the process source is re-invoked once the rescan cap has elapsed).
        monitor.evaluateNow();

        assertThat(events.size()).isEqualTo(2);
        assertThat(events.last().reason()).isEqualTo(CodingAgentEvent.Reason.PROCESS_EXITED);
        assertThat(monitor.current()).isEqualTo(DetectionResult.NONE);
        assertThat(monitor.currentProcess()).isEmpty();
    }

    @Test(timeOut = 30_000)
    void disconnectCancelsTheHeartbeatAndAConnectedEvaluationReArmsIt() throws InterruptedException {
        bindAndAwaitDetection();
        assertThat(monitor.heartbeatArmed()).isTrue();

        connected.set(false);
        monitor.evaluateNow();
        assertThat(monitor.heartbeatArmed()).isFalse();
        assertThat(monitor.isBound()).isTrue();
        assertThat(events.last().reason()).isEqualTo(CodingAgentEvent.Reason.DISCONNECTED);

        connected.set(true);
        monitor.evaluateNow();
        assertThat(monitor.heartbeatArmed()).isTrue();
        assertThat(events.size()).isEqualTo(2);

        monitor.unbind();
        assertThat(monitor.heartbeatArmed()).isFalse();
    }

    @Test(timeOut = 30_000)
    void stackOverflowInTheClassifierIsSwallowedAndTheHeartbeatSurvives() throws InterruptedException {
        bindAndAwaitDetection();
        classifierLatch.set(null);
        AtomicBoolean overflow = new AtomicBoolean(true);
        CodingAgentMonitor local = new CodingAgentMonitor(new PaneRef("tab-1", "terminal-soe"), this::captureScreen,
            (kind, snapshot) -> {
                if (overflow.get()) {
                    throw new StackOverflowError("pathological regex");
                }
                return BLOCKED;
            }, enabled::get, scheduler, Runnable::run, events);
        try {
            local.bind(this::processSource, connected::get);
            local.evaluateNow();
            assertThat(local.current()).isEqualTo(DetectionResult.NONE);
            assertThat(local.heartbeatArmed()).isTrue();

            overflow.set(false);
            local.evaluateNow();
            assertThat(local.current()).isEqualTo(BLOCKED);
        } finally {
            local.close();
        }
    }

    @Test(timeOut = 30_000)
    void processSourceIsNotReinvokedWhileTheCachedProcessLives() throws InterruptedException {
        bindAndAwaitDetection();
        int calls = processCalls.get();
        assertThat(calls).isEqualTo(1);

        for (int i = 0; i < 5; i++) {
            monitor.evaluateNow();
        }

        assertThat(processCalls.get()).isEqualTo(calls);
        assertThat(monitor.currentProcess()).isPresent();
    }

    @Test(timeOut = 30_000)
    void processRescanIsCappedWhileNoProcessIsFound() throws InterruptedException {
        process.set(null);
        monitor.bind(this::processSource, connected::get);
        awaitCondition(() -> processCalls.get() >= 1);

        for (int i = 0; i < 5; i++) {
            monitor.evaluateNow();
        }

        assertThat(processCalls.get()).isEqualTo(1);
        assertThat(classifierCalls.get()).isEqualTo(0);
        assertThat(events.events()).isEmpty();
    }

    @Test(timeOut = 30_000)
    void detectionDisabledPublishesRemovalOnceAndStopsClassifying() throws InterruptedException {
        bindAndAwaitDetection();
        enabled.set(false);
        monitor.evaluateNow();

        assertThat(events.size()).isEqualTo(2);
        assertThat(events.last().reason()).isEqualTo(CodingAgentEvent.Reason.DETECTION_DISABLED);
        assertThat(events.last().isRemoval()).isTrue();
        assertThat(monitor.current()).isEqualTo(DetectionResult.NONE);

        int calls = classifierCalls.get();
        monitor.evaluateNow();
        monitor.markDirty();
        assertThat(events.await(3, 400)).isFalse();
        assertThat(classifierCalls.get()).isEqualTo(calls);
        assertThat(events.size()).isEqualTo(2);
    }

    @Test(timeOut = 30_000)
    void disconnectedPublishesRemovalAndEvaluateNowBecomesANoOp() throws InterruptedException {
        bindAndAwaitDetection();
        connected.set(false);
        monitor.evaluateNow();

        assertThat(events.size()).isEqualTo(2);
        assertThat(events.last().reason()).isEqualTo(CodingAgentEvent.Reason.DISCONNECTED);
        assertThat(monitor.current()).isEqualTo(DetectionResult.NONE);

        int classifier = classifierCalls.get();
        int screen = screenCalls.get();
        monitor.evaluateNow();
        monitor.evaluateNow();
        assertThat(classifierCalls.get()).isEqualTo(classifier);
        assertThat(screenCalls.get()).isEqualTo(screen);
        assertThat(events.size()).isEqualTo(2);
    }

    @Test(timeOut = 30_000)
    void rebindPublishesRemovalThenFreshDetection() throws InterruptedException {
        bindAndAwaitDetection();
        monitor.bind(this::processSource, connected::get);

        assertThat(events.await(3, AWAIT_MILLIS)).isTrue();
        assertThat(events.events().get(1).reason()).isEqualTo(CodingAgentEvent.Reason.CONNECTOR_REBOUND);
        assertThat(events.events().get(1).isRemoval()).isTrue();
        assertThat(events.events().get(2).reason()).isEqualTo(CodingAgentEvent.Reason.DETECTED);
        assertThat(monitor.current()).isEqualTo(BLOCKED);
        assertThat(processCalls.get()).isEqualTo(2);
    }

    @Test(timeOut = 30_000)
    void unbindPublishesConnectorReboundAndGoesDormant() throws InterruptedException {
        bindAndAwaitDetection();
        monitor.unbind();

        assertThat(events.size()).isEqualTo(2);
        assertThat(events.last().reason()).isEqualTo(CodingAgentEvent.Reason.CONNECTOR_REBOUND);
        assertThat(monitor.isBound()).isFalse();
        assertThat(monitor.current()).isEqualTo(DetectionResult.NONE);

        int calls = classifierCalls.get();
        monitor.markDirty();
        monitor.evaluateNow();
        assertThat(events.await(3, 400)).isFalse();
        assertThat(classifierCalls.get()).isEqualTo(calls);
    }

    @Test(timeOut = 30_000)
    void markDirtyBurstCoalescesIntoOneEvaluation() throws InterruptedException {
        bindAndAwaitDetection();
        CountDownLatch latch = new CountDownLatch(1);
        classifierLatch.set(latch);
        int before = classifierCalls.get();
        long started = System.nanoTime();

        for (int i = 0; i < 1000; i++) {
            monitor.markDirty();
        }

        assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMillis).isAtLeast(CodingAgentMonitor.COALESCE_MILLIS - 20);
        Thread.sleep(400);
        assertThat(classifierCalls.get() - before).isEqualTo(1);
        assertThat(events.size()).isEqualTo(1);
    }

    @Test(timeOut = 30_000)
    void screenSourceExceptionIsSwallowedAndTheNextEvaluationWorks() throws InterruptedException {
        screenThrows.set(true);
        monitor.bind(this::processSource, connected::get);
        awaitCondition(() -> screenCalls.get() >= 1);
        monitor.evaluateNow();

        assertThat(events.events()).isEmpty();
        assertThat(monitor.current()).isEqualTo(DetectionResult.NONE);

        screenThrows.set(false);
        monitor.evaluateNow();

        assertThat(events.size()).isEqualTo(1);
        assertThat(events.last().reason()).isEqualTo(CodingAgentEvent.Reason.DETECTED);
        assertThat(monitor.current()).isEqualTo(BLOCKED);
    }

    @Test(timeOut = 30_000)
    void closePublishesPaneDetachedOnceAndIgnoresLaterCalls() throws InterruptedException {
        bindAndAwaitDetection();
        monitor.close();

        assertThat(monitor.isClosed()).isTrue();
        assertThat(monitor.isBound()).isFalse();
        assertThat(events.size()).isEqualTo(2);
        assertThat(events.last().reason()).isEqualTo(CodingAgentEvent.Reason.PANE_DETACHED);
        assertThat(events.last().isRemoval()).isTrue();
        assertThat(monitor.current()).isEqualTo(DetectionResult.NONE);

        monitor.close();
        assertThat(events.size()).isEqualTo(2);

        int calls = classifierCalls.get();
        monitor.markDirty();
        monitor.evaluateNow();
        monitor.bind(this::processSource, connected::get);
        assertThat(events.await(3, 400)).isFalse();
        assertThat(classifierCalls.get()).isEqualTo(calls);
        assertThat(monitor.isBound()).isFalse();
    }

    @Test(timeOut = 30_000)
    void closeWithoutDetectionPublishesNothing() throws InterruptedException {
        classifierResult.set(FALLBACK);
        monitor.bind(this::processSource, connected::get);
        awaitCondition(() -> classifierCalls.get() >= 1);

        monitor.close();

        assertThat(events.await(1, 300)).isFalse();
        assertThat(monitor.isClosed()).isTrue();
    }

    @Test(timeOut = 30_000)
    void uiExecutorRejectingWithIllegalStateDeliversInline() {
        AtomicInteger executorCalls = new AtomicInteger();
        monitor.close();
        monitor = newMonitor(runnable -> {
            executorCalls.incrementAndGet();
            throw new IllegalStateException("Toolkit not initialized");
        });

        monitor.bind(this::processSource, connected::get);
        monitor.evaluateNow();

        assertThat(executorCalls.get()).isAtLeast(1);
        assertThat(events.size()).isEqualTo(1);
        assertThat(events.last().reason()).isEqualTo(CodingAgentEvent.Reason.DETECTED);
        assertThat(monitor.current()).isEqualTo(BLOCKED);
    }
}
