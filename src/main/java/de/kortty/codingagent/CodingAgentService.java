package de.kortty.codingagent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application-scoped owner of coding-agent detection: one daemon scheduler thread shared by every
 * {@link CodingAgentMonitor}, the screen classifier, the monitor map keyed by {@link PaneRef} and the
 * listener fan-out. Monitors are created dormant by {@link #attach} (TerminalView binds them once the
 * pane's local shell connector is known) and removed by {@link #detach}. Events reach listeners on
 * the injected UI executor (Platform::runLater in the app, Runnable::run in tests); a listener that
 * throws is logged and does not prevent delivery to the remaining listeners. {@link #stop()} closes
 * every monitor and shuts the scheduler down; it is registered as an application shutdown step.
 */
public final class CodingAgentService implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(CodingAgentService.class);

    /** Name of the daemon thread created by {@link #defaultScheduler()}. */
    public static final String THREAD_NAME = "kortty-coding-agent-detector";

    private final ScreenClassifier classifier;
    private final BooleanSupplier detectionEnabled;
    private final Executor uiExecutor;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<PaneRef, CodingAgentMonitor> monitors = new ConcurrentHashMap<>();
    private final CopyOnWriteArrayList<Consumer<CodingAgentEvent>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** A single-thread scheduled executor whose daemon thread is named {@link #THREAD_NAME}. */
    public static ScheduledExecutorService defaultScheduler() {
        return Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * @param classifier screen classification shared by every monitor
     * @param detectionEnabled re-read on every evaluation (the global setting)
     * @param uiExecutor delivery executor for events
     * @param scheduler the scheduler this service owns and shuts down in {@link #stop()}
     */
    public CodingAgentService(ScreenClassifier classifier, BooleanSupplier detectionEnabled, Executor uiExecutor,
                              ScheduledExecutorService scheduler) {
        this.classifier = Objects.requireNonNull(classifier, "classifier");
        this.detectionEnabled = Objects.requireNonNull(detectionEnabled, "detectionEnabled");
        this.uiExecutor = Objects.requireNonNull(uiExecutor, "uiExecutor");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
    }

    /** The classifier shared by every monitor. */
    public ScreenClassifier classifier() {
        return classifier;
    }

    /**
     * Returns the monitor for {@code pane}, creating a dormant one on first call. After {@link #stop()}
     * a closed monitor is returned so callers need no special casing during shutdown.
     */
    public CodingAgentMonitor attach(PaneRef pane, Supplier<ScreenSnapshot> screenSource) {
        Objects.requireNonNull(pane, "pane");
        Objects.requireNonNull(screenSource, "screenSource");
        if (closed.get()) {
            logger.debug("Coding agent service is stopped; returning a closed monitor for {}", pane);
            CodingAgentMonitor monitor = newMonitor(pane, screenSource);
            monitor.close();
            return monitor;
        }
        CodingAgentMonitor monitor = monitors.computeIfAbsent(pane, key -> newMonitor(key, screenSource));
        if (closed.get() && monitors.remove(pane, monitor)) {
            // stop() raced with this attach: do not leave a live monitor behind.
            monitor.close();
        }
        return monitor;
    }

    /** Removes and closes {@code monitor}; the close publishes a {@code PANE_DETACHED} removal if detected. */
    public void detach(CodingAgentMonitor monitor) {
        if (monitor == null) {
            return;
        }
        monitors.remove(monitor.pane(), monitor);
        monitor.close();
    }

    /** Every attached monitor, dormant or bound, in no particular order. */
    public List<CodingAgentMonitor> monitors() {
        return List.copyOf(monitors.values());
    }

    /** The monitor attached for {@code pane}, if any. */
    public Optional<CodingAgentMonitor> monitorFor(PaneRef pane) {
        if (pane == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(monitors.get(pane));
    }

    /** The current result of every pane with a detected agent (panes without one are absent). */
    public Map<PaneRef, DetectionResult> snapshot() {
        Map<PaneRef, DetectionResult> result = new HashMap<>();
        for (CodingAgentMonitor monitor : monitors.values()) {
            DetectionResult current = monitor.current();
            if (current.agentDetected()) {
                result.put(monitor.pane(), current);
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /** Registers a listener; closing the returned handle unregisters it. */
    public AutoCloseable addListener(Consumer<CodingAgentEvent> listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Evaluates every bound monitor synchronously (used after the detection setting changed). */
    public void evaluateAll() {
        if (closed.get()) {
            return;
        }
        for (CodingAgentMonitor monitor : monitors.values()) {
            if (monitor.isBound()) {
                monitor.evaluateNow();
            }
        }
    }

    /** True once {@link #stop()} has run. */
    public boolean isClosed() {
        return closed.get();
    }

    /** Closes every monitor (publishing their removals), clears the map and shuts the scheduler down. */
    public void stop() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        List<CodingAgentMonitor> open = new ArrayList<>(monitors.values());
        monitors.clear();
        for (CodingAgentMonitor monitor : open) {
            try {
                monitor.close();
            } catch (RuntimeException e) {
                logger.warn("Closing coding agent monitor for {} failed: {}", monitor.pane(), e.toString());
            }
        }
        scheduler.shutdownNow();
        logger.debug("Coding agent service stopped ({} monitors closed)", open.size());
    }

    @Override
    public void close() {
        stop();
    }

    private CodingAgentMonitor newMonitor(PaneRef pane, Supplier<ScreenSnapshot> screenSource) {
        return new CodingAgentMonitor(pane, screenSource, classifier, detectionEnabled, scheduler, uiExecutor,
            this::publish);
    }

    private void publish(CodingAgentEvent event) {
        for (Consumer<CodingAgentEvent> listener : listeners) {
            try {
                listener.accept(event);
            } catch (RuntimeException e) {
                logger.warn("Coding agent listener failed for {}: {}", event.pane(), e.toString());
            }
        }
    }
}
