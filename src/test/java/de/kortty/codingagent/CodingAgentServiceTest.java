package de.kortty.codingagent;

import static com.google.common.truth.Truth.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class CodingAgentServiceTest {

    private static final PaneRef PANE_A = new PaneRef("tab-1", "terminal-a");
    private static final PaneRef PANE_B = new PaneRef("tab-1", "terminal-b");
    private static final DetectionResult WORKING = DetectionResult.of(CodingAgentKind.CLAUDE_CODE,
        CodingAgentState.WORKING, "working-spinner", "esc to interrupt");
    private static final DetectionResult FALLBACK = DetectionResult.of(CodingAgentKind.CLAUDE_CODE,
        CodingAgentState.IDLE, null, null);
    private static final ScreenSnapshot SCREEN = ScreenSnapshot.ofText("esc to interrupt", null, false);
    private static final long AWAIT_MILLIS = 10_000L;

    private ScheduledExecutorService scheduler;
    private final AtomicReference<DetectionResult> classifierResult = new AtomicReference<>(WORKING);
    private final AtomicInteger classifierCalls = new AtomicInteger();
    private CodingAgentService service;
    private RecordingListener listener;
    private AutoCloseable registration;

    @BeforeMethod
    void setUp() {
        scheduler = CodingAgentService.defaultScheduler();
        classifierResult.set(WORKING);
        classifierCalls.set(0);
        service = new CodingAgentService((kind, snapshot) -> {
            classifierCalls.incrementAndGet();
            return classifierResult.get();
        }, () -> true, Runnable::run, scheduler);
        listener = new RecordingListener();
        registration = service.addListener(listener);
    }

    @AfterMethod
    void tearDown() {
        service.stop();
        scheduler.shutdownNow();
    }

    private static Supplier<ScreenSnapshot> screen(AtomicInteger calls) {
        return () -> {
            calls.incrementAndGet();
            return SCREEN;
        };
    }

    private static Optional<AgentProcess> liveProcess() {
        // The real start instant: AgentProcess.isAlive() rejects a pid whose occupant started at another time.
        return Optional.of(new AgentProcess(ProcessHandle.current().pid(), CodingAgentKind.CLAUDE_CODE, "claude",
            ProcessHandle.current().info().startInstant().orElse(null)));
    }

    private CodingAgentMonitor attachAndDetect(PaneRef pane, AtomicInteger screenCalls, int expectedEvents)
            throws InterruptedException {
        CodingAgentMonitor monitor = service.attach(pane, screen(screenCalls));
        monitor.bind(CodingAgentServiceTest::liveProcess, () -> true);
        assertThat(listener.await(expectedEvents, AWAIT_MILLIS)).isTrue();
        assertThat(monitor.current()).isEqualTo(WORKING);
        return monitor;
    }

    @Test
    void attachReturnsADormantMonitorThatIsListed() {
        CodingAgentMonitor monitor = service.attach(PANE_A, () -> SCREEN);

        assertThat(monitor.pane()).isEqualTo(PANE_A);
        assertThat(monitor.isBound()).isFalse();
        assertThat(monitor.isClosed()).isFalse();
        assertThat(monitor.current()).isEqualTo(DetectionResult.NONE);
        assertThat(service.monitors()).containsExactly(monitor);
        assertThat(service.monitorFor(PANE_A)).hasValue(monitor);
        assertThat(service.monitorFor(PANE_B)).isEmpty();
        assertThat(service.snapshot()).isEmpty();
        assertThat(service.classifier()).isNotNull();
        assertThat(service.isClosed()).isFalse();
    }

    @Test
    void attachingTheSamePaneTwiceReturnsTheExistingMonitor() {
        CodingAgentMonitor first = service.attach(PANE_A, () -> SCREEN);
        CodingAgentMonitor second = service.attach(new PaneRef("tab-1", "terminal-a"), () -> SCREEN);

        assertThat(second).isSameInstanceAs(first);
        assertThat(service.monitors()).hasSize(1);
    }

    @Test(timeOut = 30_000)
    void listenerReceivesEventsAndSnapshotReflectsThem() throws InterruptedException {
        CodingAgentMonitor monitor = attachAndDetect(PANE_A, new AtomicInteger(), 1);
        monitor.evaluateNow();

        assertThat(listener.size()).isEqualTo(1);
        CodingAgentEvent event = listener.last();
        assertThat(event.pane()).isEqualTo(PANE_A);
        assertThat(event.reason()).isEqualTo(CodingAgentEvent.Reason.DETECTED);
        assertThat(event.current()).isEqualTo(WORKING);
        assertThat(service.snapshot()).containsExactly(PANE_A, WORKING);

        classifierResult.set(FALLBACK);
        monitor.evaluateNow();

        assertThat(listener.size()).isEqualTo(2);
        assertThat(listener.last().reason()).isEqualTo(CodingAgentEvent.Reason.STATE_CHANGED);
        assertThat(service.snapshot()).containsExactly(PANE_A, FALLBACK);
    }

    @Test(timeOut = 30_000)
    void detachClosesTheMonitorDeliversPaneDetachedAndClearsTheSnapshot() throws InterruptedException {
        CodingAgentMonitor monitor = attachAndDetect(PANE_A, new AtomicInteger(), 1);

        service.detach(monitor);

        assertThat(monitor.isClosed()).isTrue();
        assertThat(listener.size()).isEqualTo(2);
        assertThat(listener.last().reason()).isEqualTo(CodingAgentEvent.Reason.PANE_DETACHED);
        assertThat(listener.last().isRemoval()).isTrue();
        assertThat(service.monitors()).isEmpty();
        assertThat(service.monitorFor(PANE_A)).isEmpty();
        assertThat(service.snapshot()).isEmpty();

        service.detach(monitor);
        service.detach(null);
        assertThat(listener.size()).isEqualTo(2);
    }

    @Test(timeOut = 30_000)
    void closingTheListenerRegistrationStopsDelivery() throws Exception {
        RecordingListener other = new RecordingListener();
        service.addListener(other);
        registration.close();

        CodingAgentMonitor monitor = service.attach(PANE_A, () -> SCREEN);
        monitor.bind(CodingAgentServiceTest::liveProcess, () -> true);

        assertThat(other.await(1, AWAIT_MILLIS)).isTrue();
        assertThat(listener.events()).isEmpty();
    }

    @Test(timeOut = 30_000)
    void aThrowingListenerDoesNotPreventDeliveryToTheNextOne() throws Exception {
        registration.close();
        AtomicInteger throwingCalls = new AtomicInteger();
        service.addListener(event -> {
            throwingCalls.incrementAndGet();
            throw new IllegalStateException("boom");
        });
        service.addListener(listener);

        CodingAgentMonitor monitor = service.attach(PANE_A, () -> SCREEN);
        monitor.bind(CodingAgentServiceTest::liveProcess, () -> true);

        assertThat(listener.await(1, AWAIT_MILLIS)).isTrue();
        assertThat(throwingCalls.get()).isEqualTo(1);
        assertThat(listener.last().reason()).isEqualTo(CodingAgentEvent.Reason.DETECTED);
    }

    @Test(timeOut = 30_000)
    void evaluateAllEvaluatesEveryBoundMonitorOnly() throws InterruptedException {
        AtomicInteger screenA = new AtomicInteger();
        AtomicInteger screenB = new AtomicInteger();
        AtomicInteger screenDormant = new AtomicInteger();
        attachAndDetect(PANE_A, screenA, 1);
        attachAndDetect(PANE_B, screenB, 2);
        CodingAgentMonitor dormant = service.attach(new PaneRef("tab-2", "terminal-c"), screen(screenDormant));

        classifierResult.set(FALLBACK);
        service.evaluateAll();

        assertThat(listener.size()).isEqualTo(4);
        List<CodingAgentEvent> changes = listener.events().subList(2, 4);
        assertThat(changes.get(0).reason()).isEqualTo(CodingAgentEvent.Reason.STATE_CHANGED);
        assertThat(changes.get(1).reason()).isEqualTo(CodingAgentEvent.Reason.STATE_CHANGED);
        assertThat(changes.stream().map(CodingAgentEvent::pane).toList()).containsExactly(PANE_A, PANE_B);
        assertThat(service.snapshot()).containsExactly(PANE_A, FALLBACK, PANE_B, FALLBACK);
        assertThat(screenDormant.get()).isEqualTo(0);
        assertThat(dormant.current()).isEqualTo(DetectionResult.NONE);
    }

    @Test(timeOut = 30_000)
    void stopClosesAllMonitorsShutsTheSchedulerDownAndAttachAfterwardsReturnsAClosedMonitor()
            throws InterruptedException {
        CodingAgentMonitor monitor = attachAndDetect(PANE_A, new AtomicInteger(), 1);
        service.attach(PANE_B, () -> SCREEN);

        service.stop();

        assertThat(service.isClosed()).isTrue();
        assertThat(scheduler.isShutdown()).isTrue();
        assertThat(monitor.isClosed()).isTrue();
        assertThat(service.monitors()).isEmpty();
        assertThat(service.snapshot()).isEmpty();
        assertThat(listener.size()).isEqualTo(2);
        assertThat(listener.last().reason()).isEqualTo(CodingAgentEvent.Reason.PANE_DETACHED);

        int calls = classifierCalls.get();
        monitor.evaluateNow();
        monitor.markDirty();
        service.evaluateAll();
        assertThat(classifierCalls.get()).isEqualTo(calls);
        assertThat(listener.size()).isEqualTo(2);

        CodingAgentMonitor late = service.attach(new PaneRef("tab-3", "terminal-d"), () -> SCREEN);
        assertThat(late.isClosed()).isTrue();
        assertThat(service.monitors()).isEmpty();
        late.bind(CodingAgentServiceTest::liveProcess, () -> true);
        assertThat(late.isBound()).isFalse();

        service.stop();
        service.close();
        assertThat(listener.size()).isEqualTo(2);
    }

    @Test(timeOut = 30_000)
    void defaultSchedulerRunsOnADaemonThreadNamedAfterTheService() throws Exception {
        Thread worker = scheduler.submit(Thread::currentThread).get();

        assertThat(worker.getName()).isEqualTo(CodingAgentService.THREAD_NAME);
        assertThat(worker.isDaemon()).isTrue();
    }
}
