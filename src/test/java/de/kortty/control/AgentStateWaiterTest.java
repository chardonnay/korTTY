package de.kortty.control;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

import de.kortty.codingagent.AgentProcess;
import de.kortty.codingagent.CodingAgentEvent;
import de.kortty.codingagent.CodingAgentKind;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.DetectionResult;
import de.kortty.codingagent.FakeFocusOracle;
import de.kortty.codingagent.PaneRef;
import java.lang.reflect.Field;
import java.time.Instant;
import java.util.Collection;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

class AgentStateWaiterTest {

    private static final String PANE = "p1a2b";

    private final AtomicLong clock = new AtomicLong(100_000L);

    private FakeControlSurface surface;

    private CodingAgentRegistry agents;

    private ScheduledExecutorService timer;

    private OneBlockDispatcher ui;

    private AgentStateWaiter waiter;

    /**
     * Runs the task inline and then, still inside the same {@code submit} call, applies whatever the
     * test scheduled. A listener registered in the same block therefore sees that transition; an
     * implementation that checked the entry in one hop and subscribed in another would lose it.
     */
    private static final class OneBlockDispatcher implements UiDispatcher {

        private final AtomicInteger submits = new AtomicInteger();

        private Runnable insideBlock;

        @Override
        public <T> CompletableFuture<T> submit(Supplier<T> task) {
            submits.incrementAndGet();
            try {
                T value = task.get();
                Runnable pending = insideBlock;
                insideBlock = null;
                if (pending != null) {
                    pending.run();
                }
                return CompletableFuture.completedFuture(value);
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }

        @Override
        public boolean isUiThread() {
            return true;
        }
    }

    @BeforeMethod
    void setUp() {
        surface = new FakeControlSurface();
        surface.addPane(FakeControlSurface.pane(PANE, "t1", "w1", 0, true, true, 4711L));
        agents = CodingAgentRegistry.forTests(new FakeFocusOracle(), clock::get);
        timer = new ScheduledThreadPoolExecutor(1);
        ui = new OneBlockDispatcher();
        waiter = new AgentStateWaiter(surface, ui, agents, timer);
    }

    @AfterMethod
    void tearDown() {
        timer.shutdownNow();
        agents.clear();
    }

    private static PaneRef ref() {
        return new PaneRef(ControlIds.terminalViewId("t1"), ControlIds.widgetPaneIdFromPaneId(PANE));
    }

    private void register(CodingAgentState state) {
        agents.onEvent(new CodingAgentEvent(ref(), DetectionResult.NONE,
            DetectionResult.of(CodingAgentKind.CLAUDE_CODE, state, "claude.rule", "evidence-" + state),
            new AgentProcess(4711L, CodingAgentKind.CLAUDE_CODE, "claude", null),
            CodingAgentEvent.Reason.DETECTED, Instant.ofEpochMilli(clock.get())));
    }

    /** How many listeners the registry currently holds, so a leaked handle is visible. */
    private int listenerCount() throws Exception {
        Field field = CodingAgentRegistry.class.getDeclaredField("listeners");
        field.setAccessible(true);
        return ((Collection<?>) field.get(agents)).size();
    }

    @Test(timeOut = 30_000)
    void anAlreadySatisfiedConditionReturnsImmediately() throws Exception {
        register(CodingAgentState.DONE);

        AgentWaitResult result = waiter.await(PANE, "done", 5_000L);

        assertThat(result.state()).isEqualTo("done");
        assertThat(result.previousState()).isNull();
        assertThat(result.waitedMillis()).isEqualTo(0L);
        assertThat(result.agent().kind()).isEqualTo("claude-code");
    }

    @Test(timeOut = 30_000)
    void aTransitionDeliveredInsideTheSameUiBlockIsNotLost() throws Exception {
        register(CodingAgentState.WORKING);
        ui.submits.set(0);
        ui.insideBlock = () -> register(CodingAgentState.DONE);

        AgentWaitResult result = waiter.await(PANE, "done", 5_000L);

        assertThat(result.state()).isEqualTo("done");
        assertThat(result.previousState()).isEqualTo("working");
        // The entry check and the subscribe happened in ONE block; the second submit is the
        // listener release in the finally.
        assertThat(ui.submits.get()).isEqualTo(2);
    }

    @Test(timeOut = 30_000)
    void aLaterTransitionIsDeliveredAndTheListenerIsClosedAfterwards() throws Exception {
        register(CodingAgentState.WORKING);
        int before = listenerCount();
        timer.schedule(() -> register(CodingAgentState.DONE), 100L, TimeUnit.MILLISECONDS);

        AgentWaitResult result = waiter.await(PANE, "done", 10_000L);

        assertThat(result.state()).isEqualTo("done");
        assertThat(result.waitedMillis()).isAtLeast(50L);
        assertThat(listenerCount()).isEqualTo(before);
    }

    @Test(timeOut = 30_000)
    void aTimeoutCarriesTheWaitAndTheLastStateAndStillClosesTheListener() throws Exception {
        register(CodingAgentState.WORKING);
        int before = listenerCount();

        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> waiter.await(PANE, "done", 200L));

        assertThat(failure.code()).isEqualTo(ControlErrorCode.TIMEOUT);
        assertThat(failure.data()).containsKey("waited_millis");
        assertThat(failure.data()).containsEntry("state", "working");
        assertThat(listenerCount()).isEqualTo(before);
    }

    @Test(timeOut = 30_000)
    void aCancelledWaitAlsoClosesTheListener() throws Exception {
        register(CodingAgentState.WORKING);
        int before = listenerCount();
        Thread.currentThread().interrupt();

        ControlApiException failure =
            expectThrows(ControlApiException.class, () -> waiter.await(PANE, "done", 10_000L));

        assertThat(Thread.interrupted()).isTrue();
        assertThat(failure.code()).isEqualTo(ControlErrorCode.TIMEOUT);
        assertThat(listenerCount()).isEqualTo(before);
    }

    @Test(timeOut = 30_000)
    void anyMeansAnyEffectiveStateChange() throws Exception {
        register(CodingAgentState.WORKING);
        timer.schedule(() -> register(CodingAgentState.IDLE), 100L, TimeUnit.MILLISECONDS);

        AgentWaitResult result = waiter.await(PANE, "any", 10_000L);

        // The registry synthesises DONE for a WORKING agent that goes idle unseen, and the wait
        // reports that effective state rather than the raw detection.
        assertThat(result.state()).isEqualTo("done");
        assertThat(result.previousState()).isEqualTo("working");
    }

    @Test(timeOut = 30_000)
    void anUnknownTargetIsInvalidParamsAndAPaneWithoutAnAgentIsAgentNotFound() {
        assertThat(expectThrows(ControlApiException.class,
            () -> waiter.await(PANE, "sleepy", 1_000L)).code())
            .isEqualTo(ControlErrorCode.INVALID_PARAMS);
        assertThat(expectThrows(ControlApiException.class,
            () -> waiter.await(PANE, "done", 1_000L)).code())
            .isEqualTo(ControlErrorCode.AGENT_NOT_FOUND);
    }
}
