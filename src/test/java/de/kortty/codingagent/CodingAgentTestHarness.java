package de.kortty.codingagent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Builds a real {@link CodingAgentService} (synchronous {@code Runnable::run} UI executor) whose
 * monitors are bound to a fake process source and a scripted screen, feeding a
 * {@link CodingAgentRegistry#forTests} registry through genuine {@link CodingAgentEvent}s. The
 * screen of a pane encodes the state on its first line and the evidence on the following lines,
 * which the harness classifier turns into a rule-matched {@link DetectionResult}. Public because
 * the UI smokes in {@code de.kortty.ui} use it too.
 */
public final class CodingAgentTestHarness implements AutoCloseable {

    private static final String RULE_PREFIX = "harness-";

    private final ScheduledExecutorService scheduler;
    private final CodingAgentService service;
    private final FakeFocusOracle focus = new FakeFocusOracle();
    private final AtomicLong clock = new AtomicLong(1_700_000_000_000L);
    private final CodingAgentRegistry registry;
    private final AutoCloseable registration;
    private final AutoCloseable changeRecording;
    private final Map<PaneRef, AtomicReference<ScreenSnapshot>> screens = new HashMap<>();
    private final List<RegistryChange> changes = new CopyOnWriteArrayList<>();

    public CodingAgentTestHarness() {
        scheduler = CodingAgentService.defaultScheduler();
        service = new CodingAgentService(CodingAgentTestHarness::classify, () -> true, Runnable::run, scheduler);
        registry = CodingAgentRegistry.forTests(focus, clock::get);
        registration = service.addListener(registry::onEvent);
        changeRecording = registry.addListener(changes::add);
    }

    public CodingAgentService service() {
        return service;
    }

    public CodingAgentRegistry registry() {
        return registry;
    }

    public FakeFocusOracle focus() {
        return focus;
    }

    /** The fake registry clock. */
    public long nowMillis() {
        return clock.get();
    }

    /** Advances the fake registry clock. */
    public void advance(long millis) {
        clock.addAndGet(millis);
    }

    /** Every registry change published so far, in order. */
    public List<RegistryChange> changes() {
        return new ArrayList<>(changes);
    }

    public void clearChanges() {
        changes.clear();
    }

    /**
     * Attaches and binds a monitor for the pane and evaluates it synchronously, so the registry holds
     * the agent when this returns.
     */
    public PaneRef addAgent(String tabId, String paneId, CodingAgentKind kind, CodingAgentState state, String evidence) {
        PaneRef pane = new PaneRef(tabId, paneId);
        AtomicReference<ScreenSnapshot> screen = screens.computeIfAbsent(pane, key -> new AtomicReference<>());
        screen.set(screenFor(state, evidence));
        CodingAgentMonitor monitor = service.attach(pane, screen::get);
        AgentProcess process = new AgentProcess(ProcessHandle.current().pid(), kind,
            kind.executableNames().stream().findFirst().orElse("agent"), null);
        monitor.bind(() -> Optional.of(process), () -> true);
        monitor.evaluateNow();
        return pane;
    }

    /** Changes the screen of a registered pane and evaluates it synchronously. */
    public void setState(PaneRef pane, CodingAgentState state, String evidence) {
        AtomicReference<ScreenSnapshot> screen = screens.get(Objects.requireNonNull(pane, "pane"));
        if (screen == null) {
            throw new IllegalArgumentException("Unknown pane " + pane + "; call addAgent first");
        }
        screen.set(screenFor(state, evidence));
        service.monitorFor(pane).ifPresent(CodingAgentMonitor::evaluateNow);
    }

    /** Detaches the pane's monitor, publishing a PANE_DETACHED removal. */
    public void remove(PaneRef pane) {
        service.monitorFor(pane).ifPresent(service::detach);
        screens.remove(pane);
    }

    @Override
    public void close() {
        try {
            registration.close();
        } catch (Exception ignored) {
            // the handle only removes a listener
        }
        service.stop();
        scheduler.shutdownNow();
        registry.clear();
        try {
            changeRecording.close();
        } catch (Exception ignored) {
            // the handle only removes a listener
        }
    }

    private static ScreenSnapshot screenFor(CodingAgentState state, String evidence) {
        String text = state.name() + "\n" + (evidence == null ? "" : evidence);
        return ScreenSnapshot.ofText(text, null, false);
    }

    private static DetectionResult classify(CodingAgentKind kind, ScreenSnapshot snapshot) {
        if (snapshot == null || snapshot.lines().isEmpty()) {
            return DetectionResult.NONE;
        }
        CodingAgentState state;
        try {
            state = CodingAgentState.valueOf(snapshot.lines().get(0).strip());
        } catch (IllegalArgumentException e) {
            return DetectionResult.NONE;
        }
        String evidence = snapshot.lines().size() > 1
            ? String.join("\n", snapshot.lines().subList(1, snapshot.lines().size()))
            : null;
        if (evidence != null && evidence.isBlank()) {
            evidence = null;
        }
        return DetectionResult.of(kind, state, RULE_PREFIX + state.name().toLowerCase(Locale.ROOT), evidence);
    }
}
