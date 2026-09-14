package de.kortty.codingagent;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BooleanSupplier;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application-scoped owner of the effective coding-agent state the UI shows: one
 * {@link CodingAgentEntry} per pane with a detected agent, user aliases, cached per-tab
 * {@link TabRollup}s and the global {@link AgentSummary}, fed by the Stage-1
 * {@link CodingAgentEvent} stream and consumed through {@link Listener}s.
 *
 * <p>The registry is single-threaded by contract: every mutator checks the injected
 * {@code onUiThread} guard and drops the call with a debug log otherwise (the only such path is the
 * inline delivery of {@code CodingAgentMonitor.publish} when the toolkit is already gone at
 * shutdown). The application passes {@code Platform::isFxApplicationThread}; tests use
 * {@link #forTests} whose guard is always true. Listeners are invoked synchronously on the calling
 * thread; a throwing listener is logged and does not block the others.
 *
 * <p>Done-until-seen: a raw WORKING → IDLE/UNKNOWN transition of a pane the user is not looking at
 * ({@link FocusOracle#isSeen}) becomes the effective state DONE with {@code doneUntilSeen == true}
 * and stays DONE until {@link #markSeen}/{@link #reconcileSeen} turn it into IDLE. A rule-detected
 * DONE stays DONE until seen as well; a raw BLOCKED → IDLE never synthesises DONE; raw BLOCKED and
 * WORKING always replace the effective state.
 */
public final class CodingAgentRegistry {

    private static final Logger logger = LoggerFactory.getLogger(CodingAgentRegistry.class);

    /** Receives every change synchronously on the registry's thread. */
    public interface Listener {
        void onRegistryChanged(RegistryChange change);
    }

    private static final Comparator<CodingAgentEntry> URGENCY_ORDER = Comparator
        .comparingInt((CodingAgentEntry entry) -> entry.state().ordinal())
        .thenComparingLong(CodingAgentEntry::stateSinceMillis)
        .thenComparing(entry -> entry.pane().tabId(), Comparator.nullsLast(Comparator.naturalOrder()))
        .thenComparing(entry -> entry.pane().paneId(), Comparator.nullsLast(Comparator.naturalOrder()));

    private final LongSupplier clockMillis;
    private final BooleanSupplier onUiThread;
    private final LinkedHashMap<PaneRef, CodingAgentEntry> entries = new LinkedHashMap<>();
    private final HashMap<PaneRef, String> aliases = new HashMap<>();
    private final HashMap<String, TabRollup> rollups = new HashMap<>();
    private final CopyOnWriteArrayList<Listener> listeners = new CopyOnWriteArrayList<>();
    private FocusOracle focus;
    private AgentSummary summary = AgentSummary.EMPTY;
    private List<CodingAgentEntry> sortedEntries = List.of();
    private boolean sortedEntriesValid = true;

    /**
     * @param focus answers whether a pane is currently seen (may be replaced via {@link #setFocusOracle})
     * @param clockMillis the registry clock (System::currentTimeMillis in the app, a fake in tests)
     * @param onUiThread the thread guard every mutator checks
     */
    public CodingAgentRegistry(FocusOracle focus, LongSupplier clockMillis, BooleanSupplier onUiThread) {
        this.focus = focus == null ? FocusOracle.NEVER : focus;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
        this.onUiThread = Objects.requireNonNull(onUiThread, "onUiThread");
    }

    /** A registry whose thread guard always passes, driven synchronously from the test thread. */
    public static CodingAgentRegistry forTests(FocusOracle focus, LongSupplier clockMillis) {
        return new CodingAgentRegistry(focus, clockMillis, () -> true);
    }

    /** Replaces the focus oracle (the bridge installs the real one after the windows exist). */
    public void setFocusOracle(FocusOracle focus) {
        this.focus = focus == null ? FocusOracle.NEVER : focus;
    }

    /** Adds an entry per detected pane of a service snapshot (panes already registered are skipped). */
    public void seed(Map<PaneRef, DetectionResult> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return;
        }
        if (!guard("seed")) {
            return;
        }
        for (Map.Entry<PaneRef, DetectionResult> item : snapshot.entrySet()) {
            PaneRef pane = item.getKey();
            DetectionResult detection = item.getValue();
            if (pane == null || detection == null || !detection.agentDetected() || entries.containsKey(pane)) {
                continue;
            }
            add(pane, detection, null);
        }
    }

    /** Applies one Stage-1 event: detections add, state changes update, removals delete the entry. */
    public void onEvent(CodingAgentEvent event) {
        if (event == null) {
            return;
        }
        if (!guard("event " + event.reason() + " for " + event.pane())) {
            return;
        }
        PaneRef pane = event.pane();
        if (event.isRemoval()) {
            remove(pane, event.reason());
            return;
        }
        if (!event.current().agentDetected()) {
            return;
        }
        CodingAgentEntry existing = entries.get(pane);
        if (existing == null) {
            add(pane, event.current(), event.process());
        } else {
            update(existing, event.current(), event.process());
        }
    }

    /**
     * The user looked at the pane: an effective DONE becomes IDLE and a {@code SEEN} change is
     * published.
     *
     * @return true when the entry was DONE and has been flipped
     */
    public boolean markSeen(PaneRef pane) {
        if (pane == null || !guard("markSeen " + pane)) {
            return false;
        }
        CodingAgentEntry existing = entries.get(pane);
        if (existing == null || existing.state() != CodingAgentState.DONE) {
            return false;
        }
        long now = clockMillis.getAsLong();
        CodingAgentEntry updated = new CodingAgentEntry(existing.pane(), existing.kind(), CodingAgentState.IDLE,
            existing.detection(), existing.process(), now, existing.detectedAtMillis(), existing.alias(), false);
        put(updated);
        publish(new RegistryChange(RegistryChange.Kind.SEEN, pane, existing, updated, now));
        return true;
    }

    /** Flips every DONE entry whose pane the focus oracle reports as seen. */
    public void reconcileSeen() {
        if (!guard("reconcileSeen")) {
            return;
        }
        List<PaneRef> seenDone = new ArrayList<>();
        for (CodingAgentEntry entry : entries.values()) {
            if (entry.state() == CodingAgentState.DONE && focus.isSeen(entry.pane())) {
                seenDone.add(entry.pane());
            }
        }
        for (PaneRef pane : seenDone) {
            markSeen(pane);
        }
    }

    /**
     * Sets or clears (blank) the alias of a pane. The alias survives a CONNECTOR_REBOUND removal and
     * is cleared by PANE_DETACHED.
     */
    public void setAlias(PaneRef pane, String alias) {
        if (pane == null || !guard("setAlias " + pane)) {
            return;
        }
        String normalised = alias == null || alias.isBlank() ? null : alias.strip();
        String previousAlias = normalised == null ? aliases.remove(pane) : aliases.put(pane, normalised);
        CodingAgentEntry existing = entries.get(pane);
        if (existing == null || Objects.equals(previousAlias, normalised)) {
            return;
        }
        CodingAgentEntry updated = new CodingAgentEntry(existing.pane(), existing.kind(), existing.state(),
            existing.detection(), existing.process(), existing.stateSinceMillis(), existing.detectedAtMillis(),
            normalised, existing.doneUntilSeen());
        put(updated);
        publish(new RegistryChange(RegistryChange.Kind.ALIAS_CHANGED, pane, existing, updated,
            clockMillis.getAsLong()));
    }

    /** The entry of {@code pane}, if an agent is registered for it. */
    public Optional<CodingAgentEntry> entry(PaneRef pane) {
        if (pane == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(entries.get(pane));
    }

    /**
     * Every entry, unmodifiable, ordered by urgency (BLOCKED, DONE, WORKING, IDLE, UNKNOWN), then
     * longest in state first, then tab id and pane id.
     */
    public List<CodingAgentEntry> entries() {
        if (!sortedEntriesValid) {
            List<CodingAgentEntry> sorted = new ArrayList<>(entries.values());
            sorted.sort(URGENCY_ORDER);
            sortedEntries = Collections.unmodifiableList(sorted);
            sortedEntriesValid = true;
        }
        return sortedEntries;
    }

    /** The entries of one tab in {@link #entries()} order. */
    public List<CodingAgentEntry> entriesForTab(String tabId) {
        if (tabId == null) {
            return List.of();
        }
        List<CodingAgentEntry> result = new ArrayList<>();
        for (CodingAgentEntry entry : entries()) {
            if (tabId.equals(entry.pane().tabId())) {
                result.add(entry);
            }
        }
        return Collections.unmodifiableList(result);
    }

    /** The cached rollup of a tab; {@link TabRollup#EMPTY} when it hosts no agent. */
    public TabRollup rollupFor(String tabId) {
        if (tabId == null) {
            return TabRollup.EMPTY;
        }
        TabRollup rollup = rollups.get(tabId);
        return rollup == null ? TabRollup.EMPTY : rollup;
    }

    /** The cached global summary. */
    public AgentSummary summary() {
        return summary;
    }

    /** The BLOCKED entries in {@link #entries()} order (longest waiting first). */
    public List<CodingAgentEntry> blockedEntries() {
        List<CodingAgentEntry> result = new ArrayList<>();
        for (CodingAgentEntry entry : entries()) {
            if (entry.state() != CodingAgentState.BLOCKED) {
                break;
            }
            result.add(entry);
        }
        return Collections.unmodifiableList(result);
    }

    /** Registers a listener; closing the returned handle unregisters it. */
    public AutoCloseable addListener(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Removes every entry (publishing REMOVED for each), alias and rollup. */
    public void clear() {
        if (!guard("clear")) {
            return;
        }
        List<PaneRef> panes = new ArrayList<>(entries.keySet());
        for (PaneRef pane : panes) {
            remove(pane, CodingAgentEvent.Reason.PANE_DETACHED);
        }
        aliases.clear();
        rollups.clear();
        summary = AgentSummary.EMPTY;
        invalidate();
    }

    /** The registry clock, for callers that format time-in-state consistently with the entries. */
    long clockMillis() {
        return clockMillis.getAsLong();
    }

    private boolean guard(String operation) {
        if (onUiThread.getAsBoolean()) {
            return true;
        }
        logger.debug("Coding agent registry ignored {} off the UI thread ({})", operation,
            Thread.currentThread().getName());
        return false;
    }

    private void add(PaneRef pane, DetectionResult detection, AgentProcess process) {
        long now = clockMillis.getAsLong();
        CodingAgentEntry entry = new CodingAgentEntry(pane, detection.kind(), detection.state(), detection,
            process, now, now, aliases.get(pane), false);
        put(entry);
        publish(new RegistryChange(RegistryChange.Kind.ADDED, pane, null, entry, now));
    }

    private void update(CodingAgentEntry existing, DetectionResult detection, AgentProcess process) {
        long now = clockMillis.getAsLong();
        CodingAgentState raw = detection.state();
        CodingAgentState effective;
        boolean synthetic;
        switch (raw) {
            case BLOCKED, WORKING -> {
                effective = raw;
                synthetic = false;
            }
            case DONE -> {
                effective = CodingAgentState.DONE;
                synthetic = false;
            }
            default -> {
                if (existing.state() == CodingAgentState.DONE) {
                    effective = CodingAgentState.DONE;
                    synthetic = existing.doneUntilSeen();
                } else if (existing.state() == CodingAgentState.WORKING && !focus.isSeen(existing.pane())) {
                    effective = CodingAgentState.DONE;
                    synthetic = true;
                } else {
                    effective = raw;
                    synthetic = false;
                }
            }
        }
        boolean stateChanged = effective != existing.state();
        long since = stateChanged ? now : existing.stateSinceMillis();
        CodingAgentEntry updated = new CodingAgentEntry(existing.pane(), detection.kind(), effective, detection,
            process != null ? process : existing.process(), since, existing.detectedAtMillis(), existing.alias(),
            synthetic);
        put(updated);
        publish(new RegistryChange(changeKindOf(existing, updated), existing.pane(), existing, updated, now));
    }

    /**
     * STATE_CHANGED when anything the UI renders as structure changed, EVIDENCE_CHANGED when the new
     * detection differs from the previous one only in its evidence line (or matched rule). A WORKING
     * agent's animated status line ("✻ Thinking… (12s · esc to interrupt)") is the rule's evidence, so
     * without this distinction every spinner frame would look like a state change to the panel, the
     * dashboard and the strip.
     */
    private static RegistryChange.Kind changeKindOf(CodingAgentEntry previous, CodingAgentEntry current) {
        boolean structural = previous.state() != current.state()
            || previous.kind() != current.kind()
            || previous.stateSinceMillis() != current.stateSinceMillis()
            || previous.doneUntilSeen() != current.doneUntilSeen()
            || !Objects.equals(previous.alias(), current.alias())
            || !Objects.equals(previous.process(), current.process());
        return structural ? RegistryChange.Kind.STATE_CHANGED : RegistryChange.Kind.EVIDENCE_CHANGED;
    }

    private void remove(PaneRef pane, CodingAgentEvent.Reason reason) {
        if (reason == CodingAgentEvent.Reason.PANE_DETACHED) {
            aliases.remove(pane);
        }
        CodingAgentEntry existing = entries.remove(pane);
        if (existing == null) {
            return;
        }
        recompute(pane.tabId());
        publish(new RegistryChange(RegistryChange.Kind.REMOVED, pane, existing, null, clockMillis.getAsLong()));
    }

    private void put(CodingAgentEntry entry) {
        entries.put(entry.pane(), entry);
        recompute(entry.pane().tabId());
    }

    private void recompute(String tabId) {
        invalidate();
        summary = AgentSummary.of(entries.values());
        int blocked = 0;
        int working = 0;
        int done = 0;
        int idle = 0;
        int count = 0;
        CodingAgentState urgent = null;
        for (CodingAgentEntry entry : entries.values()) {
            if (!Objects.equals(tabId, entry.pane().tabId())) {
                continue;
            }
            count++;
            urgent = CodingAgentState.mostUrgent(urgent, entry.state());
            switch (entry.state()) {
                case BLOCKED -> blocked++;
                case WORKING -> working++;
                case DONE -> done++;
                default -> idle++;
            }
        }
        if (count == 0) {
            rollups.remove(tabId);
        } else {
            rollups.put(tabId, new TabRollup(tabId, blocked, working, done, idle, count,
                urgent == null ? CodingAgentState.UNKNOWN : urgent));
        }
    }

    private void invalidate() {
        sortedEntriesValid = false;
    }

    private void publish(RegistryChange change) {
        for (Listener listener : listeners) {
            try {
                listener.onRegistryChanged(change);
            } catch (RuntimeException e) {
                logger.warn("Coding agent registry listener failed for {}: {}", change.pane(), e.toString());
            }
        }
    }
}
