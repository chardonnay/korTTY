package de.kortty.control;

import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.desktop.DesktopNotifier;
import java.util.Objects;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;

/**
 * Builds the whole {@link MethodRegistry}: the one place a control-API verb is declared.
 *
 * <p>Every verb is implemented against the {@link ControlSurface} and {@link UiDispatcher} ports plus
 * the Stage-1/2 {@code CodingAgentRegistry} and {@code CodingAgentActions}, so the table can be built
 * and exercised without a JavaFX toolkit.
 *
 * <p>Any thread, never the JavaFX application thread. The returned registry is immutable and its
 * handlers are safe to dispatch concurrently from several connections.
 */
public final class ControlVerbs {

    /** How long an idle timer thread lives before it is reclaimed. */
    private static final long TIMER_KEEP_ALIVE_SECONDS = 1L;

    private ControlVerbs() {
    }

    /**
     * Declares every verb this version implements and reserves the three it does not.
     *
     * @param surface the window port
     * @param ui the JavaFX marshaller
     * @param registry the Stage-2 coding-agent registry
     * @param agentActions a <strong>second</strong> {@code CodingAgentActions} over the same registry
     *     and the same UI bridge, with a control-flavoured audit sink, so the Coding Agents panel's
     *     own actions are never mislabelled as API input
     * @param events the event fan-out behind {@code events.subscribe}
     * @param audit the audit sink; {@link ControlAuditSink#LOGGING} when null
     * @param notifier the desktop notifier, or null when the platform has none
     * @param clockMillis the wall clock
     * @param appVersion the korTTY version published by {@code ping} and {@code api.schema}
     * @param instanceId the id minted at server start, which every mutating verb can be pinned to
     */
    public static MethodRegistry build(ControlSurface surface, UiDispatcher ui,
                                       CodingAgentRegistry registry, CodingAgentActions agentActions,
                                       ControlEventBus events, ControlAuditSink audit,
                                       DesktopNotifier notifier, LongSupplier clockMillis,
                                       String appVersion, String instanceId) {
        Objects.requireNonNull(surface, "surface");
        Objects.requireNonNull(ui, "ui");
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(agentActions, "agentActions");
        Objects.requireNonNull(clockMillis, "clockMillis");
        ControlAuditSink sink = audit == null ? ControlAuditSink.LOGGING : audit;

        ScheduledExecutorService timer = timer();
        ControlPaneWriter writer = new ControlPaneWriter(surface, ui, sink);
        ControlSplitService splits = new ControlSplitService(surface, ui, sink);
        ControlAgentGateway agents = new ControlAgentGateway(surface, ui, registry, agentActions);
        PaneOutputWaiter outputWaiter = new PaneOutputWaiter(surface, ui, timer);
        AgentStateWaiter stateWaiter = new AgentStateWaiter(surface, ui, registry, timer);
        AgentStartService starter =
            new AgentStartService(splits, writer, agents, stateWaiter, surface);

        AtomicReference<MethodRegistry> self = new AtomicReference<>();
        MethodRegistry.Builder builder = MethodRegistry.builder();
        BaseVerbs.register(builder, self, clockMillis, clockMillis.getAsLong(), appVersion, instanceId);
        EventVerbs.register(builder, events);
        LayoutVerbs.register(builder, surface, ui, splits, instanceId);
        PaneIoVerbs.register(builder, surface, ui, writer, outputWaiter, agents, instanceId);
        AgentVerbs.register(builder, surface, ui, agents, stateWaiter, starter, instanceId);
        NotificationVerbs.register(builder, notifier, clockMillis);

        MethodRegistry table = builder.build();
        self.set(table);
        return table;
    }

    /**
     * The scheduler the two wait machines share.
     *
     * <p>Its threads are daemon, named like every other control thread, and time out when idle, so a
     * table that is built but never used — a unit test, a server that is switched off again — leaves
     * nothing running behind it.
     */
    private static ScheduledExecutorService timer() {
        AtomicLong counter = new AtomicLong();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "kortty-control-timer-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, factory);
        executor.setKeepAliveTime(TIMER_KEEP_ALIVE_SECONDS, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }
}
