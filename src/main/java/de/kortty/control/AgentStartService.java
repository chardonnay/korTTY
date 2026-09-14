package de.kortty.control;

import de.kortty.codingagent.CodingAgentKind;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * {@code agent.start}: split-or-use a pane, launch the agent, wait until it is registered and
 * optionally prompt it.
 *
 * <p>A declared <strong>composition</strong> of the verbs that already exist, not a fourth write
 * path: {@code pane.split} (or the caller's pane), a readiness wait on the connector, {@code pane.run}
 * with the launch command, a wait for the Stage-1 double-evidence registration, then the optional
 * prompt and state wait. Every failure therefore carries the composed step's own error code, plus
 * {@code data.stage} naming which step gave up.
 *
 * <p>Any thread, never the JavaFX application thread — it blocks on the composed waits. The only
 * surface member it touches directly is {@link ControlSurface#isPaneConnected(String)}, which is
 * documented ANY THREAD; every other UI touch happens inside the collaborators it composes.
 */
public final class AgentStartService {

    /** How often the connector is asked whether the pane came up. */
    private static final long CONNECT_POLL_MILLIS = 50L;

    /** How often the registry is asked whether the agent was detected. */
    private static final long DETECT_POLL_MILLIS = 150L;

    private final ControlSplitService splits;

    private final ControlPaneWriter writer;

    private final ControlAgentGateway agents;

    private final AgentStateWaiter waiter;

    private final ControlSurface surface;

    /**
     * @param splits the split service, used only by the {@code split_from} form
     * @param writer the pane write path, used to type the launch command
     * @param agents the agent gateway, used for readiness and for the optional prompt
     * @param waiter the state waiter, used for the optional {@code wait}
     * @param surface the window port; only its ANY-THREAD members are called here
     */
    public AgentStartService(ControlSplitService splits, ControlPaneWriter writer,
                             ControlAgentGateway agents, AgentStateWaiter waiter,
                             ControlSurface surface) {
        this.splits = Objects.requireNonNull(splits, "splits");
        this.writer = Objects.requireNonNull(writer, "writer");
        this.agents = Objects.requireNonNull(agents, "agents");
        this.waiter = Objects.requireNonNull(waiter, "waiter");
        this.surface = Objects.requireNonNull(surface, "surface");
    }

    /**
     * One {@code agent.start} call.
     *
     * @param paneId the pane to launch in, or null
     * @param splitFromPaneId the pane to split, or null; exactly one of the two is required
     * @param orientation the split orientation when splitting
     * @param kind the {@code CodingAgentKind} id to launch and to wait for
     * @param command the launch command, or null for the kind's own executable
     * @param prompt the prompt to send once the agent is registered, or null
     * @param readyTimeoutMillis the budget for the connect and the detect stages
     * @param waitAfterPrompt whether to wait for a state after prompting; {@code wait} itself is not
     *     a legal record component name, because it would clash with {@code Object.wait()}
     * @param until the state to wait for
     * @param waitTimeoutMillis the budget for that wait
     */
    public record Request(String paneId, String splitFromPaneId, String orientation, String kind,
                          List<String> command, String prompt, long readyTimeoutMillis,
                          boolean waitAfterPrompt, String until, long waitTimeoutMillis) {

        public Request {
            command = command == null ? List.of() : List.copyOf(command);
        }
    }

    /**
     * What the composition produced.
     *
     * @param pane the pane the split created, or null for the {@code pane} form — the caller already
     *     knows that pane and re-resolves it for the reply, which also keeps this service free of a
     *     UI hop it has no dispatcher for
     * @param agent the registered agent
     * @param command the launch command that was typed
     */
    public record Result(PaneInfo pane, AgentInfo agent, List<String> command) {

        public Result {
            command = command == null ? List.of() : List.copyOf(command);
        }
    }

    /**
     * Runs the composition.
     *
     * @throws ControlApiException whatever the composed steps raise, plus
     *     {@link ControlErrorCode#INVALID_PARAMS} when neither or both of {@code pane} and
     *     {@code split_from} are given or the kind is unknown, and
     *     {@link ControlErrorCode#TIMEOUT} with {@code data.stage} {@code connect} or {@code detect}
     */
    public Result start(Request request) throws ControlApiException {
        Objects.requireNonNull(request, "request");
        boolean hasPane = notBlank(request.paneId());
        boolean hasSplit = notBlank(request.splitFromPaneId());
        if (hasPane == hasSplit) {
            throw new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "Exactly one of 'pane' and 'split_from' is required",
                Map.of("param", "pane", "hint", "pass pane to use an existing pane, split_from to make one"));
        }
        CodingAgentKind kind = CodingAgentKind.forId(lower(request.kind()))
            .filter(value -> value != CodingAgentKind.UNKNOWN)
            .orElseThrow(() -> new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "Unknown coding-agent kind: " + request.kind(),
                Map.of("param", "kind", "known", knownKinds())));

        PaneInfo created = null;
        String paneId;
        if (hasSplit) {
            created = splits.split(request.splitFromPaneId(), request.orientation(), false);
            paneId = created.paneId();
        } else {
            paneId = request.paneId().strip();
        }

        long readyBudget = Math.max(1L,
            Math.min(request.readyTimeoutMillis(), ControlApiProtocol.WAIT_HARD_CAP_MILLIS));
        awaitConnected(paneId, readyBudget);

        List<String> command = launchCommand(request.command(), kind);
        writer.run(paneId, String.join(" ", command));

        AgentInfo agent = awaitRegistered(paneId, kind, readyBudget);
        if (notBlank(request.prompt())) {
            agents.prompt(paneId, request.prompt());
            if (request.waitAfterPrompt()) {
                waiter.await(paneId, request.until(), request.waitTimeoutMillis());
            }
            agent = agents.get(paneId);
        }
        return new Result(created, agent, command);
    }

    /** Step (b): the pane must report connected before anything is typed into it. */
    private void awaitConnected(String paneId, long budgetMillis) throws ControlApiException {
        long start = System.nanoTime();
        while (true) {
            if (surface.isPaneConnected(paneId)) {
                return;
            }
            if (elapsedMillis(start) >= budgetMillis) {
                throw stage(paneId, "connect", elapsedMillis(start),
                    "The pane did not connect within " + budgetMillis + " ms");
            }
            sleep(CONNECT_POLL_MILLIS);
        }
    }

    /**
     * Step (d): the agent counts as started once the registry holds an entry of the requested kind —
     * the Stage-1 double-evidence signal, never a re-run of the detector.
     */
    private AgentInfo awaitRegistered(String paneId, CodingAgentKind kind, long budgetMillis)
            throws ControlApiException {
        long start = System.nanoTime();
        while (true) {
            Optional<AgentInfo> agent = currentAgent(paneId);
            if (agent.isPresent() && kind.id().equals(agent.get().kind())) {
                return agent.get();
            }
            if (elapsedMillis(start) >= budgetMillis) {
                throw stage(paneId, "detect", elapsedMillis(start),
                    "No " + kind.displayName() + " was detected in the pane within " + budgetMillis + " ms");
            }
            sleep(DETECT_POLL_MILLIS);
        }
    }

    private Optional<AgentInfo> currentAgent(String paneId) throws ControlApiException {
        try {
            return Optional.of(agents.get(paneId));
        } catch (ControlApiException e) {
            if (e.code() == ControlErrorCode.AGENT_NOT_FOUND) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /** The kind's own executable, chosen deterministically because {@code executableNames} is a set. */
    private static List<String> launchCommand(List<String> requested, CodingAgentKind kind)
            throws ControlApiException {
        if (requested != null && !requested.isEmpty()) {
            return List.copyOf(requested);
        }
        return kind.executableNames().stream()
            .sorted()
            .findFirst()
            .map(List::of)
            .orElseThrow(() -> new ControlApiException(ControlErrorCode.INVALID_PARAMS,
                "No launch command is known for " + kind.displayName(),
                Map.of("param", "command", "kind", String.valueOf(kind.id()))));
    }

    private static List<String> knownKinds() {
        List<String> kinds = new ArrayList<>();
        for (CodingAgentKind kind : CodingAgentKind.values()) {
            if (kind.id() != null) {
                kinds.add(kind.id());
            }
        }
        return List.copyOf(kinds);
    }

    private static ControlApiException stage(String paneId, String stage, long waitedMillis,
                                             String message) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("pane", paneId);
        data.put("stage", stage);
        data.put("waited_millis", waitedMillis);
        return new ControlApiException(ControlErrorCode.TIMEOUT, message, data);
    }

    private static void sleep(long millis) throws ControlApiException {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ControlApiException(ControlErrorCode.TIMEOUT,
                "The start was interrupted", Map.of("stage", "interrupted"));
        }
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static String lower(String value) {
        return value == null ? null : value.strip().toLowerCase(Locale.ROOT);
    }
}
