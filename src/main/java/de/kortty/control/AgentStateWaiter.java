package de.kortty.control;

import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.RegistryChange;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * {@code agent.wait}: event-driven, never polled.
 *
 * <p>The current entry is read <strong>and</strong> the registry listener registered inside
 * <strong>one</strong> {@link UiDispatcher} block, so an already-satisfied condition returns
 * immediately and a transition delivered between the check and the subscribe cannot be lost. The
 * listener handle is closed in a {@code finally} — on success, on timeout and on cancellation alike.
 *
 * <p>Any thread, never the JavaFX application thread — it blocks until the deadline.
 */
public final class AgentStateWaiter {

    private static final Logger LOG = LoggerFactory.getLogger(AgentStateWaiter.class);

    /** The {@code until} value that accepts any effective-state change. */
    private static final String UNTIL_ANY = "any";

    /** The accepted {@code until} values, published in {@code error.data.known}. */
    private static final List<String> UNTIL_VALUES =
        List.of("blocked", "done", "idle", "working", UNTIL_ANY);

    private final ControlSurface surface;

    private final UiDispatcher ui;

    private final CodingAgentRegistry registry;

    private final ScheduledExecutorService timer;

    /**
     * @param surface the window port
     * @param ui the JavaFX marshaller
     * @param registry the Stage-2 registry
     * @param timer the shared timer executor; kept so a wait never borrows the caller's scheduler
     */
    public AgentStateWaiter(ControlSurface surface, UiDispatcher ui, CodingAgentRegistry registry,
                            ScheduledExecutorService timer) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.timer = Objects.requireNonNull(timer, "timer");
    }

    /**
     * Waits until the pane's agent reaches a state.
     *
     * @param paneId the resolved pane id
     * @param until {@code blocked}, {@code done}, {@code idle}, {@code working} or {@code any}
     * @param timeoutMillis the wait budget, clamped to {@link ControlApiProtocol#WAIT_HARD_CAP_MILLIS}
     * @throws ControlApiException {@link ControlErrorCode#INVALID_PARAMS},
     *     {@link ControlErrorCode#PANE_NOT_FOUND}, {@link ControlErrorCode#AGENT_NOT_FOUND},
     *     {@link ControlErrorCode#TIMEOUT} with {@code data.waited_millis} and {@code data.state}
     */
    public AgentWaitResult await(String paneId, String until, long timeoutMillis)
            throws ControlApiException {
        String target = normalise(until);
        long budget = Math.max(1L, Math.min(timeoutMillis, ControlApiProtocol.WAIT_HARD_CAP_MILLIS));
        long startNanos = System.nanoTime();
        CompletableFuture<AgentWaitResult> future = new CompletableFuture<>();
        AtomicReference<AutoCloseable> handle = new AtomicReference<>();
        AtomicReference<String> lastState = new AtomicReference<>();

        // One block: read the entry and subscribe, so no transition can slip between the two.
        UiCalls.await(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS, () -> {
            try {
                PaneInfo pane = surface.resolve(PaneAddress.ofPaneId(require(paneId)));
                PaneRef ref = surface.paneRefOf(pane.paneId()).orElseThrow(() -> new ControlApiException(
                    ControlErrorCode.PANE_NOT_FOUND, "The pane is no longer open: " + pane.paneId(),
                    Map.of("pane", pane.paneId())));
                CodingAgentEntry entry = registry.entry(ref).orElseThrow(() -> new ControlApiException(
                    ControlErrorCode.AGENT_NOT_FOUND,
                    "No coding agent is registered for " + pane.paneId(),
                    Map.of("pane", pane.paneId())));
                String current = wire(entry.state());
                lastState.set(current);
                if (satisfiedAlready(target, current)) {
                    future.complete(new AgentWaitResult(current, null, 0L,
                        AgentInfo.of(entry, pane.paneId(), pane.tabId(), pane.windowId(),
                            System.currentTimeMillis())));
                    return Boolean.TRUE;
                }
                handle.set(registry.addListener(change -> onChange(change, ref, pane, target, lastState,
                    startNanos, future)));
                return Boolean.TRUE;
            } catch (ControlApiException e) {
                throw new CompletionException(e);
            }
        });

        // The shared timer owns the deadline, so the waiting thread parks on one future and nothing
        // else: no poll loop, and the wait ends even if the registry never fires again.
        ScheduledFuture<?> deadline = timer.schedule(
            () -> future.completeExceptionally(new DeadlineReached()), budget, TimeUnit.MILLISECONDS);
        try {
            return future.get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw timedOut(paneId, lastState.get(), elapsedMillis(startNanos));
        } catch (ExecutionException e) {
            if (e.getCause() instanceof DeadlineReached) {
                throw timedOut(paneId, lastState.get(), elapsedMillis(startNanos));
            }
            throw new ControlApiException(ControlErrorCode.INTERNAL_ERROR,
                "The wait failed; see the korTTY log", e.getCause());
        } finally {
            deadline.cancel(false);
            release(handle.get());
        }
    }

    /** The signal the timer uses to end a wait; a control-flow marker, not a diagnosable failure. */
    private static final class DeadlineReached extends RuntimeException {

        private static final long serialVersionUID = 1L;

        DeadlineReached() {
            super("The agent wait deadline passed", null, false, false);
        }
    }

    private void onChange(RegistryChange change, PaneRef ref, PaneInfo pane, String target,
                          AtomicReference<String> lastState,
                          long startNanos, CompletableFuture<AgentWaitResult> future) {
        if (change == null || !ref.equals(change.pane())) {
            return;
        }
        CodingAgentEntry current = change.current();
        if (current == null || change.kind() == RegistryChange.Kind.EVIDENCE_CHANGED) {
            return;
        }
        String state = wire(current.state());
        String previous = change.previous() == null ? null : wire(change.previous().state());
        lastState.set(state);
        if (!satisfiedByChange(target, state, previous)) {
            return;
        }
        future.complete(new AgentWaitResult(state, previous, elapsedMillis(startNanos),
            AgentInfo.of(current, pane.paneId(), pane.tabId(), pane.windowId(),
                System.currentTimeMillis())));
    }

    /**
     * Closes the listener handle through the dispatcher, because every registry touch belongs on the
     * UI thread. A failure here must never replace the wait's own outcome, so it is only logged.
     */
    private void release(AutoCloseable handle) {
        if (handle == null) {
            return;
        }
        try {
            UiCalls.await(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS, () -> {
                try {
                    handle.close();
                } catch (Exception e) {
                    LOG.debug("control-api agent.wait could not unregister its listener: {}", e.toString());
                }
                return Boolean.TRUE;
            });
        } catch (ControlApiException e) {
            LOG.debug("control-api agent.wait could not reach the UI to unregister: {}", e.toString());
        }
    }

    /**
     * Whether the entry as it stands already answers the wait. {@code any} never does: it asks for the
     * <em>next</em> change, so returning the current state immediately would make it useless.
     */
    private static boolean satisfiedAlready(String target, String state) {
        return !UNTIL_ANY.equals(target) && target.equals(state);
    }

    /** Whether one delivered transition answers the wait. */
    private static boolean satisfiedByChange(String target, String state, String previous) {
        if (UNTIL_ANY.equals(target)) {
            return previous == null || !previous.equals(state);
        }
        return target.equals(state);
    }

    private static String wire(CodingAgentState state) {
        return state == null ? "unknown" : state.name().toLowerCase(Locale.ROOT);
    }

    private static String require(String paneId) throws ControlApiException {
        if (paneId == null || paneId.isBlank()) {
            throw new ControlApiException(ControlErrorCode.PANE_NOT_FOUND, "No pane given",
                Map.of("param", "pane"));
        }
        return paneId;
    }

    private static String normalise(String until) throws ControlApiException {
        String value = until == null || until.isBlank() ? UNTIL_ANY : until.strip().toLowerCase(Locale.ROOT);
        if (!UNTIL_VALUES.contains(value)) {
            throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "Unknown wait target: " + until,
                Map.of("param", "until", "known", UNTIL_VALUES));
        }
        return value;
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static ControlApiException timedOut(String paneId, String state, long waitedMillis) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pane", paneId);
        data.put("waited_millis", waitedMillis);
        data.put("state", state);
        return new ControlApiException(ControlErrorCode.TIMEOUT,
            "The agent did not reach the requested state within " + waitedMillis + " ms", data);
    }
}
