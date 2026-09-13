package de.kortty.codingagent;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Per-pane coding-agent detector. A monitor is created dormant by {@link CodingAgentService#attach}
 * and does nothing until {@link #bind} hands it a process source and a connection probe for the
 * pane's local shell. While bound it evaluates
 * <ul>
 *   <li>coalesced {@link #COALESCE_MILLIS} after the first {@link #markDirty()} of a burst
 *       (the terminal model listener calls markDirty on every buffer mutation),</li>
 *   <li>every {@link #HEARTBEAT_MILLIS} on a heartbeat that notices process exit without screen
 *       output, disconnection and the detection setting being switched off, and</li>
 *   <li>on demand via {@link #evaluateNow()} from any thread.</li>
 * </ul>
 * Every evaluation runs on the shared scheduler thread (or the caller's thread for evaluateNow) and
 * is serialised by the monitor's lock. An agent is reported only with double evidence: a live agent
 * process below the shell <em>and</em> a first rule match for that process; afterwards a screen that
 * matches no rule yields the rule set's fallback state for as long as the process lives. The process
 * source is re-invoked at most every {@link #PROCESS_RESCAN_MILLIS} while no live process is cached.
 * Changes of the {@link DetectionResult} are published exactly once each through the injected UI
 * executor; if that executor rejects with an {@link IllegalStateException} (no JavaFX toolkit) the
 * event is delivered inline.
 */
public final class CodingAgentMonitor implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(CodingAgentMonitor.class);

    /** Delay between the first markDirty of a burst and the evaluation that absorbs the burst. */
    public static final long COALESCE_MILLIS = 200L;
    /** Interval of the heartbeat evaluation armed by {@link #bind}. */
    public static final long HEARTBEAT_MILLIS = 2_000L;
    /** Minimum interval between two invocations of the process source while no live process is cached. */
    public static final long PROCESS_RESCAN_MILLIS = 1_500L;

    private static final long PROCESS_RESCAN_NANOS = TimeUnit.MILLISECONDS.toNanos(PROCESS_RESCAN_MILLIS);
    private static final long NO_PID = -1L;

    private record Binding(Supplier<Optional<AgentProcess>> processSource, BooleanSupplier connected) {}

    private final PaneRef pane;
    private final Supplier<ScreenSnapshot> screenSource;
    private final ScreenClassifier classifier;
    private final BooleanSupplier detectionEnabled;
    private final ScheduledExecutorService scheduler;
    private final Executor uiExecutor;
    private final Consumer<CodingAgentEvent> onChange;

    private final AtomicBoolean dirty = new AtomicBoolean(false);
    private final AtomicBoolean scheduled = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicReference<Binding> binding = new AtomicReference<>();
    private final AtomicReference<DetectionResult> current = new AtomicReference<>(DetectionResult.NONE);
    private final AtomicReference<AgentProcess> currentProcess = new AtomicReference<>();
    private final AtomicReference<ScheduledFuture<?>> heartbeat = new AtomicReference<>();

    /** Pid of the process that produced the first rule match; guarded by the monitor lock. */
    private long confirmedPid = NO_PID;
    /** System.nanoTime() of the last process-source invocation (0 = never); guarded by the monitor lock. */
    private long lastRescanNanos;

    /**
     * @param pane identity of the monitored pane
     * @param screenSource captures the live screen; invoked on the scheduler thread only while bound
     * @param classifier screen classification (the rule-driven detector in the app, a lambda in tests)
     * @param detectionEnabled re-read on every evaluation (the global setting)
     * @param scheduler the service's single daemon scheduler thread
     * @param uiExecutor delivery executor for events (Platform::runLater in the app, Runnable::run in tests)
     * @param onChange receives every published event
     */
    public CodingAgentMonitor(PaneRef pane, Supplier<ScreenSnapshot> screenSource, ScreenClassifier classifier,
                              BooleanSupplier detectionEnabled, ScheduledExecutorService scheduler,
                              Executor uiExecutor, Consumer<CodingAgentEvent> onChange) {
        this.pane = Objects.requireNonNull(pane, "pane");
        this.screenSource = Objects.requireNonNull(screenSource, "screenSource");
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.detectionEnabled = Objects.requireNonNull(detectionEnabled, "detectionEnabled");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor");
        this.onChange = Objects.requireNonNull(onChange, "onChange");
    }

    /** The monitored pane. */
    public PaneRef pane() {
        return pane;
    }

    /**
     * Binds the monitor to a local shell: arms the heartbeat and schedules an immediate evaluation.
     * Re-binding while an agent is detected first publishes a {@code CONNECTOR_REBOUND} removal.
     *
     * @param processSource yields the live agent process below the shell, if any
     * @param connected whether the shell connector is still connected (polled on every evaluation)
     */
    public void bind(Supplier<Optional<AgentProcess>> processSource, BooleanSupplier connected) {
        Objects.requireNonNull(processSource, "processSource");
        Objects.requireNonNull(connected, "connected");
        if (closed.get()) {
            logger.debug("Ignoring bind of closed coding agent monitor for {}", pane);
            return;
        }
        synchronized (this) {
            Binding previous = binding.getAndSet(new Binding(processSource, connected));
            if (previous != null) {
                dropTo(CodingAgentEvent.Reason.CONNECTOR_REBOUND);
            }
            lastRescanNanos = 0L;
        }
        armHeartbeat();
        try {
            scheduler.execute(this::evaluate);
        } catch (RejectedExecutionException e) {
            logger.debug("Coding agent scheduler rejected the initial evaluation for {}", pane);
        }
    }

    /** Drops the binding: cancels the heartbeat and publishes a {@code CONNECTOR_REBOUND} removal if detected. */
    public void unbind() {
        cancelHeartbeat();
        synchronized (this) {
            Binding previous = binding.getAndSet(null);
            if (previous != null) {
                dropTo(CodingAgentEvent.Reason.CONNECTOR_REBOUND);
            }
        }
    }

    /** True between {@link #bind} and {@link #unbind}/{@link #close}. */
    public boolean isBound() {
        return binding.get() != null;
    }

    /**
     * Signals that the screen changed. Cheap and non-blocking (called on the terminal emulator thread
     * for every buffer mutation): a no-op while dormant or closed, otherwise schedules at most one
     * coalesced evaluation {@link #COALESCE_MILLIS} later.
     */
    public void markDirty() {
        if (closed.get() || binding.get() == null) {
            return;
        }
        dirty.set(true);
        if (scheduled.compareAndSet(false, true)) {
            try {
                scheduler.schedule(this::evaluateScheduled, COALESCE_MILLIS, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException e) {
                scheduled.set(false);
            }
        }
    }

    /** The last published result; {@link DetectionResult#NONE} while no agent is confirmed. */
    public DetectionResult current() {
        return current.get();
    }

    /**
     * The cached live agent process below the shell. Present as soon as the process source found one,
     * i.e. possibly before the first rule match confirms the detection.
     */
    public Optional<AgentProcess> currentProcess() {
        return Optional.ofNullable(currentProcess.get());
    }

    /** Evaluates synchronously on the calling thread; a no-op while dormant, disconnected or closed. */
    public void evaluateNow() {
        if (closed.get()) {
            return;
        }
        evaluate();
    }

    /** True once {@link #close} has run. */
    public boolean isClosed() {
        return closed.get();
    }

    /**
     * Cancels the heartbeat, publishes a {@code PANE_DETACHED} removal if an agent was detected and
     * ignores every later call. Idempotent; does no blocking work beyond the (few-millisecond)
     * evaluation lock.
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        cancelHeartbeat();
        synchronized (this) {
            binding.set(null);
            dropTo(CodingAgentEvent.Reason.PANE_DETACHED);
        }
    }

    private void evaluateScheduled() {
        scheduled.set(false);
        if (dirty.getAndSet(false)) {
            evaluate();
        }
    }

    private void heartbeat() {
        if (closed.get() || binding.get() == null) {
            return;
        }
        evaluate();
    }

    private synchronized void evaluate() {
        try {
            doEvaluate();
        } catch (RuntimeException e) {
            logger.debug("Coding agent evaluation failed for {}: {}", pane, e.toString());
        }
    }

    private void doEvaluate() {
        if (closed.get()) {
            return;
        }
        if (!detectionEnabled.getAsBoolean()) {
            dropTo(CodingAgentEvent.Reason.DETECTION_DISABLED);
            return;
        }
        Binding bound = binding.get();
        if (bound == null) {
            dropTo(CodingAgentEvent.Reason.CONNECTOR_REBOUND);
            return;
        }
        if (!bound.connected().getAsBoolean()) {
            dropTo(CodingAgentEvent.Reason.DISCONNECTED);
            return;
        }
        AgentProcess process = resolveProcess(bound);
        if (process == null) {
            dropTo(CodingAgentEvent.Reason.PROCESS_EXITED);
            return;
        }
        if (confirmedPid != NO_PID && confirmedPid != process.pid()) {
            // The confirmed process is gone and a different, not yet confirmed one took its place.
            publishRemoval(CodingAgentEvent.Reason.PROCESS_EXITED, null);
            confirmedPid = NO_PID;
        }
        ScreenSnapshot snapshot = screenSource.get();
        DetectionResult result = classifier.classify(process.kind(), snapshot == null ? ScreenSnapshot.EMPTY : snapshot);
        if (result == null || !result.agentDetected()) {
            // The classifier cannot classify this kind at all (no rule set): nothing to report.
            dropTo(CodingAgentEvent.Reason.DETECTION_DISABLED);
            return;
        }
        if (result.ruleMatched()) {
            confirmedPid = process.pid();
        }
        if (confirmedPid != process.pid()) {
            // Double evidence pending: process present, but no rule has matched its screen yet.
            return;
        }
        DetectionResult previous = current.get();
        if (previous.equals(result)) {
            return;
        }
        CodingAgentEvent.Reason reason = previous.agentDetected()
            ? CodingAgentEvent.Reason.STATE_CHANGED
            : CodingAgentEvent.Reason.DETECTED;
        current.set(result);
        publish(new CodingAgentEvent(pane, previous, result, process, reason, Instant.now()));
    }

    /** Returns the cached process while it lives, otherwise re-scans at most every PROCESS_RESCAN_MILLIS. */
    private AgentProcess resolveProcess(Binding bound) {
        AgentProcess cached = currentProcess.get();
        if (cached != null && cached.isAlive()) {
            return cached;
        }
        currentProcess.set(null);
        long now = System.nanoTime();
        if (lastRescanNanos != 0L && now - lastRescanNanos < PROCESS_RESCAN_NANOS) {
            return null;
        }
        lastRescanNanos = now;
        AgentProcess found = bound.processSource().get().orElse(null);
        currentProcess.set(found);
        return found;
    }

    /** Resets the detection (publishing a removal if an agent was detected) and clears the process cache. */
    private void dropTo(CodingAgentEvent.Reason reason) {
        AgentProcess process = currentProcess.getAndSet(null);
        confirmedPid = NO_PID;
        publishRemoval(reason, reason == CodingAgentEvent.Reason.PROCESS_EXITED ? null : process);
    }

    private void publishRemoval(CodingAgentEvent.Reason reason, AgentProcess process) {
        DetectionResult previous = current.get();
        if (!previous.agentDetected()) {
            return;
        }
        current.set(DetectionResult.NONE);
        publish(new CodingAgentEvent(pane, previous, DetectionResult.NONE, process, reason, Instant.now()));
    }

    private void publish(CodingAgentEvent event) {
        Runnable delivery = () -> {
            try {
                onChange.accept(event);
            } catch (RuntimeException e) {
                logger.warn("Coding agent event handler failed for {}: {}", pane, e.toString());
            }
        };
        try {
            uiExecutor.execute(delivery);
        } catch (IllegalStateException toolkitNotRunning) {
            delivery.run();
        }
    }

    private void armHeartbeat() {
        ScheduledFuture<?> next;
        try {
            next = scheduler.scheduleWithFixedDelay(this::heartbeat, HEARTBEAT_MILLIS, HEARTBEAT_MILLIS,
                TimeUnit.MILLISECONDS);
        } catch (RejectedExecutionException e) {
            logger.debug("Coding agent scheduler rejected the heartbeat for {}", pane);
            return;
        }
        ScheduledFuture<?> previous = heartbeat.getAndSet(next);
        if (previous != null) {
            previous.cancel(false);
        }
        if (closed.get() || binding.get() == null) {
            cancelHeartbeat();
        }
    }

    private void cancelHeartbeat() {
        ScheduledFuture<?> running = heartbeat.getAndSet(null);
        if (running != null) {
            running.cancel(false);
        }
    }
}
