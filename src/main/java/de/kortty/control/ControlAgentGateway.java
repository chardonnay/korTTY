package de.kortty.control;

import de.kortty.codingagent.AgentSummary;
import de.kortty.codingagent.CodingAgentActionException;
import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.KeyChord;
import de.kortty.codingagent.KeyChordEncoder;
import de.kortty.codingagent.PaneRef;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionException;

/**
 * The {@code agent.*} verbs, running against a <strong>second</strong> {@code CodingAgentActions}
 * instance: the same registry and the same UI bridge, but a control-flavoured audit sink, so API
 * input is distinguishable in the log and the Coding Agents panel's own actions are never mislabelled.
 *
 * <p>Any thread, never the JavaFX application thread. {@code CodingAgentRegistry} mutators
 * <em>silently drop</em> off-thread calls and its readers are unsynchronised maps, so every registry
 * touch — read or write — goes through the {@link UiDispatcher}, and each verb runs as a
 * <strong>whole</strong> inside one hop because it is a registry read plus a connector write and must
 * be atomic with respect to the UI.
 *
 * <p>{@code AgentProcess.isAlive()} is never called: it hits the OS and would stutter the toolkit
 * while a large {@code agent.list} is serialised.
 */
public final class ControlAgentGateway {

    private final ControlSurface surface;

    private final UiDispatcher ui;

    private final CodingAgentRegistry registry;

    private final CodingAgentActions actions;

    /**
     * @param surface the window port
     * @param ui the JavaFX marshaller
     * @param registry the Stage-2 registry
     * @param actions the control-flavoured {@code CodingAgentActions} instance
     */
    public ControlAgentGateway(ControlSurface surface, UiDispatcher ui, CodingAgentRegistry registry,
                               CodingAgentActions actions) {
        this.surface = Objects.requireNonNull(surface, "surface");
        this.ui = Objects.requireNonNull(ui, "ui");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.actions = Objects.requireNonNull(actions, "actions");
    }

    /**
     * The agent registered for a pane.
     *
     * @throws ControlApiException {@link ControlErrorCode#PANE_NOT_FOUND},
     *     {@link ControlErrorCode#AGENT_NOT_FOUND}
     */
    public AgentInfo get(String paneId) throws ControlApiException {
        return inUi(() -> {
            Resolved resolved = resolve(paneId);
            return AgentInfo.of(resolved.entry(), resolved.pane().paneId(), resolved.pane().tabId(),
                resolved.pane().windowId(), System.currentTimeMillis());
        });
    }

    /**
     * Every registered agent in the panel's urgency order — BLOCKED first, then longest in state.
     *
     * @param stateFilter a wire state, or null for every state
     * @param kindFilter a {@code CodingAgentKind} id, or null for every kind
     * @param tabId a control tab id, or null
     * @param windowId a window id, or null
     */
    public List<AgentInfo> list(String stateFilter, String kindFilter, String tabId, String windowId)
            throws ControlApiException {
        String state = lower(stateFilter);
        String kind = lower(kindFilter);
        return inUi(() -> {
            Map<String, PaneInfo> panes = paneIndex();
            long now = System.currentTimeMillis();
            List<AgentInfo> result = new ArrayList<>();
            for (CodingAgentEntry entry : registry.entries()) {
                PaneRef ref = entry.pane();
                String pane = ControlIds.paneIdFromWidgetPaneId(ref.paneId());
                PaneInfo info = panes.get(pane);
                String tab = info != null ? info.tabId() : ControlIds.tabId(ref.tabId());
                String window = info != null ? info.windowId() : null;
                AgentInfo agent = AgentInfo.of(entry, pane, tab, window, now);
                if (state != null && !state.equals(agent.state())) {
                    continue;
                }
                if (kind != null && !kind.equals(agent.kind())) {
                    continue;
                }
                if (tabId != null && !tabId.equals(tab)) {
                    continue;
                }
                if (windowId != null && !windowId.equals(window)) {
                    continue;
                }
                result.add(agent);
            }
            return List.copyOf(result);
        });
    }

    /** The global rollup; IDLE and UNKNOWN both land in {@code idle}, as {@code AgentSummary} does. */
    public AgentTotals totals() throws ControlApiException {
        return inUi(() -> {
            AgentSummary summary = registry.summary();
            return new AgentTotals(summary.blocked(), summary.working(), summary.done(),
                summary.idle(), summary.total());
        });
    }

    /**
     * The multi-line human explanation of the current detection.
     *
     * @throws ControlApiException {@link ControlErrorCode#PANE_NOT_FOUND},
     *     {@link ControlErrorCode#AGENT_NOT_FOUND}
     */
    public String explain(String paneId) throws ControlApiException {
        return inUi(() -> {
            Resolved resolved = resolve(paneId);
            return translate(() -> actions.explain(resolved.ref()));
        });
    }

    /**
     * Submits a prompt to the agent.
     *
     * @throws ControlApiException {@link ControlErrorCode#AGENT_NOT_FOUND},
     *     {@link ControlErrorCode#AGENT_BLOCKED}, {@link ControlErrorCode#EMPTY_INPUT},
     *     {@link ControlErrorCode#NOT_CONNECTED}, {@link ControlErrorCode#WRITE_FAILED},
     *     {@link ControlErrorCode#HOST_SHORTCUT_CONFLICT}
     */
    public WriteResult prompt(String paneId, String text) throws ControlApiException {
        return inUi(() -> {
            Resolved resolved = resolve(paneId);
            boolean bracketed = KeyChordEncoder.isMultiLine(text)
                && surface.isBracketedPasteEnabled(resolved.pane().paneId());
            translate(() -> {
                actions.prompt(resolved.ref(), text);
                return Boolean.TRUE;
            });
            int bytes = KeyChordEncoder.promptPayload(text, bracketed)
                .getBytes(StandardCharsets.UTF_8).length;
            return new WriteResult(resolved.pane().paneId(), bytes, bracketed, true, List.of());
        });
    }

    /**
     * Presses keys in the agent's pane. Names that all map to a {@code KeyChord} — the common
     * {@code enter}/{@code esc}/{@code y}/{@code n}/{@code ctrl+c} case — are delegated to
     * {@code CodingAgentActions}; anything else is encoded by {@link ControlKeyTable} and written
     * through the surface, which keeps the full vocabulary available without widening the Stage-2
     * enum.
     *
     * @throws ControlApiException {@link ControlErrorCode#AGENT_NOT_FOUND},
     *     {@link ControlErrorCode#UNKNOWN_KEY}, {@link ControlErrorCode#EMPTY_INPUT},
     *     {@link ControlErrorCode#NOT_CONNECTED}, {@link ControlErrorCode#WRITE_FAILED}
     */
    public WriteResult sendKeys(String paneId, List<String> keyNames) throws ControlApiException {
        List<String> normalised = ControlKeyTable.normalise(keyNames);
        byte[] payload = ControlKeyTable.encodeAll(normalised);
        List<KeyChord> chords = asChords(normalised);
        return inUi(() -> {
            Resolved resolved = resolve(paneId);
            if (chords != null) {
                translate(() -> {
                    actions.sendKeys(resolved.ref(), chords);
                    return Boolean.TRUE;
                });
                return new WriteResult(resolved.pane().paneId(), payload.length, false, false, normalised);
            }
            int written = surface.write(resolved.pane().paneId(), payload);
            return new WriteResult(resolved.pane().paneId(), written, false, false, normalised);
        });
    }

    /**
     * Sets or clears the agent's alias.
     *
     * <p>{@code registry.setAlias} is silently dropped off the UI thread, which is exactly why this
     * runs inside the dispatcher rather than on the caller's thread.
     *
     * @throws ControlApiException {@link ControlErrorCode#AGENT_NOT_FOUND}
     */
    public AgentInfo rename(String paneId, String alias) throws ControlApiException {
        return inUi(() -> {
            Resolved resolved = resolve(paneId);
            translate(() -> {
                actions.rename(resolved.ref(), alias);
                return Boolean.TRUE;
            });
            CodingAgentEntry renamed = registry.entry(resolved.ref()).orElse(resolved.entry());
            return AgentInfo.of(renamed, resolved.pane().paneId(), resolved.pane().tabId(),
                resolved.pane().windowId(), System.currentTimeMillis());
        });
    }

    /** One pane plus its Stage-2 handle and registry entry, all resolved inside the same hop. */
    private record Resolved(PaneInfo pane, PaneRef ref, CodingAgentEntry entry) {
    }

    private Resolved resolve(String paneId) throws ControlApiException {
        if (paneId == null || paneId.isBlank()) {
            throw new ControlApiException(ControlErrorCode.PANE_NOT_FOUND, "No pane given",
                Map.of("param", "pane"));
        }
        PaneInfo pane = surface.resolve(PaneAddress.ofPaneId(paneId));
        PaneRef ref = surface.paneRefOf(pane.paneId()).orElseThrow(() -> new ControlApiException(
            ControlErrorCode.PANE_NOT_FOUND, "The pane is no longer open: " + pane.paneId(),
            Map.of("pane", pane.paneId())));
        CodingAgentEntry entry = registry.entry(ref).orElseThrow(() -> new ControlApiException(
            ControlErrorCode.AGENT_NOT_FOUND, "No coding agent is registered for " + pane.paneId(),
            Map.of("pane", pane.paneId())));
        return new Resolved(pane, ref, entry);
    }

    private Map<String, PaneInfo> paneIndex() throws ControlApiException {
        Map<String, PaneInfo> index = new HashMap<>();
        for (PaneInfo pane : surface.listPanes(null, null)) {
            index.putIfAbsent(pane.paneId(), pane);
        }
        return index;
    }

    /** The chords for these names, or null when at least one has no {@code KeyChord}. */
    private static List<KeyChord> asChords(List<String> names) {
        List<KeyChord> chords = new ArrayList<>(names.size());
        for (String name : names) {
            Optional<KeyChord> chord = ControlKeyTable.asKeyChord(name);
            if (chord.isEmpty()) {
                return null;
            }
            chords.add(chord.get());
        }
        return chords;
    }

    private static String lower(String value) {
        return value == null || value.isBlank() ? null : value.strip().toLowerCase(Locale.ROOT);
    }

    private <T> T inUi(ThrowingSupplier<T> body) throws ControlApiException {
        return UiCalls.await(ui, ControlApiProtocol.UI_TIMEOUT_MILLIS, () -> {
            try {
                return body.get();
            } catch (ControlApiException e) {
                throw new CompletionException(e);
            }
        });
    }

    private static <T> T translate(AgentAction<T> action) throws ControlApiException {
        try {
            return action.run();
        } catch (CodingAgentActionException e) {
            throw ControlApiException.from(e);
        }
    }

    /** A body that may fail with the package's checked exception. */
    @FunctionalInterface
    private interface ThrowingSupplier<T> {
        T get() throws ControlApiException;
    }

    /** A body that may fail with the Stage-2 checked exception. */
    @FunctionalInterface
    private interface AgentAction<T> {
        T run() throws CodingAgentActionException;
    }
}
