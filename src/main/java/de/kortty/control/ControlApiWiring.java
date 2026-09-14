package de.kortty.control;

import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.PaneAccess;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.codingagent.desktop.PlatformProbe;
import de.kortty.ui.I18n;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Assembles a ready-to-run control API out of its parts: the event bus, the verb table and the
 * server.
 *
 * <p>It exists so the application wires the API in one call and never has to know the construction
 * order — the instance id must be minted once and shared by the verb table and the server, and the
 * event bus must exist before the verbs that publish into it.
 *
 * <p>Any thread, never the JavaFX application thread. Nothing here starts a listener:
 * {@link ControlApiServer#applyEnabledState()} does that, and only when the gate says yes.
 *
 * <p>This is the one class of the package that reaches for {@code de.kortty.ui.I18n}: it is the
 * assembly point, and the takeover notification it installs is user-facing text. It stays free of
 * JavaFX like the rest of the package — {@code I18n} resolves a resource bundle and touches no
 * toolkit.
 */
public final class ControlApiWiring {

    /** How long an idle event-drain thread lives before it is reclaimed. */
    private static final long TIMER_KEEP_ALIVE_SECONDS = 1L;

    /**
     * The verbs that change a pane; the first of them in a run raises the takeover notification.
     *
     * <p>The {@code agent.*} writers belong here as much as the {@code pane.*} ones: they type
     * arbitrary bytes into a pane, and {@code agent.prompt} plus {@code agent.send_keys} are exactly
     * how a caller drives someone else's coding agent.
     */
    private static final Set<String> WRITING_VERBS =
        Set.of("pane.send_text", "pane.run", "pane.send_keys", "pane.split", "pane.close",
            "agent.send_text", "agent.send_keys", "agent.prompt");

    /** The wire verb each {@code CodingAgentActions} verb is audited under. */
    private static final Map<String, String> AGENT_VERBS = Map.of(
        "sendText", "agent.send_text",
        "sendKeys", "agent.send_keys",
        "prompt", "agent.prompt",
        "explain", "agent.explain",
        "rename", "agent.rename");

    /**
     * The bus each assembled server publishes through.
     *
     * <p>Weak-keyed on purpose: the application holds the server, and a discarded server must not keep
     * its bus — or, through the bus, its subscribers' sessions — alive.
     */
    private static final Map<ControlApiServer, ControlEventBus> BUSES =
        Collections.synchronizedMap(new WeakHashMap<>());

    private ControlApiWiring() {
    }

    /**
     * Builds the server, its verb table and its event bus.
     *
     * @param configDir the korTTY configuration directory; the server owns {@code configDir/control}
     * @param probe the platform facts that choose between the unix socket and the loopback transport
     * @param surface the window port, normally {@code de.kortty.ui.ControlApiUiBridge}
     * @param ui the JavaFX marshaller, normally the same object
     * @param registry the Stage-2 coding-agent registry
     * @param panes the Stage-2 pane access, normally the same coding-agent UI bridge the panel uses;
     *     a <strong>second</strong> {@code CodingAgentActions} is built over it here so every
     *     {@code agent.*} action is audited — and announced — as API input rather than as something
     *     the user did in the Coding Agents panel
     * @param notifier the desktop notifier, or null when the platform has none
     * @param gate {@link ControlApiGate#verdict}, re-evaluated at start, at accept and at dispatch
     * @param appVersion the korTTY version published by {@code ping}, {@code auth} and the endpoint file
     * @return a server that is built but not listening
     */
    public static ControlApiServer create(Path configDir, PlatformProbe probe, ControlSurface surface,
                                          UiDispatcher ui, CodingAgentRegistry registry,
                                          PaneAccess panes, DesktopNotifier notifier,
                                          Supplier<ControlApiGate.Verdict> gate, String appVersion) {
        String instanceId = UUID.randomUUID().toString();
        ControlEventBus events = new ControlEventBus(eventTimer(), System::currentTimeMillis);
        ControlAuditSink sink = auditSink(notifier);
        CodingAgentActions controlActions = new CodingAgentActions(registry, panes, agentAuditSink(sink));
        MethodRegistry methods = ControlVerbs.build(surface, ui, registry, controlActions, events,
            sink, notifier, System::currentTimeMillis, appVersion, instanceId);
        ControlApiServer server = new ControlApiServer(configDir, probe, methods, gate,
            System::currentTimeMillis, appVersion, instanceId, events);
        BUSES.put(server, events);
        return server;
    }

    /**
     * Bridges the Stage-2 audit sink onto the control sink, so an {@code agent.*} action is recorded
     * under its wire verb and reaches the takeover notification like any other API write.
     *
     * <p>Without it the agent verbs would have their own sink and their own vocabulary, and
     * {@code agent.prompt} — arbitrary bytes typed into someone else's coding agent — would complete
     * with no notification at all.
     *
     * <p>Never throws: {@code CodingAgentActions.explain()} and {@code rename()} call their sink
     * directly, so an exception here would turn a successful action into a JSON-RPC error.
     */
    static CodingAgentActions.AuditSink agentAuditSink(ControlAuditSink sink) {
        return (verb, pane, detail) -> {
            try {
                sink.record(AGENT_VERBS.getOrDefault(verb, "agent." + verb), paneId(pane), detail);
            } catch (RuntimeException e) {
                // An audit line is never worth failing the action it describes.
            }
        };
    }

    /** The wire pane id of a Stage-2 pane handle, so one log line reads like every other one. */
    private static String paneId(PaneRef pane) {
        return pane == null ? null : ControlIds.paneIdFromWidgetPaneId(pane.paneId());
    }

    /**
     * The event bus of a server {@link #create} built, so the application can register it with the
     * coding-agent registry on the JavaFX thread.
     *
     * @return the bus, or null for a server this class did not build
     */
    public static ControlEventBus eventBus(ControlApiServer server) {
        return server == null ? null : BUSES.get(server);
    }

    /**
     * The audit sink the pane verbs use: the standard log line, plus one desktop notification on the
     * <strong>first</strong> pane-changing verb of a server run.
     *
     * <p>One notification, not one per write: a script that types a hundred lines must announce itself
     * without becoming the notification spam that trains users to dismiss it unread. The point is that
     * a takeover is never silent, not that every keystroke is.
     *
     * <p>Never throws. {@code CodingAgentActions.explain()} and {@code rename()} call their sink
     * directly, so an exception here would turn a successful action into a JSON-RPC error.
     *
     * <p>Package-private rather than private so the notification rule — which verbs announce, and
     * that they announce once — is provable without a toolkit and without a socket.
     */
    static ControlAuditSink auditSink(DesktopNotifier notifier) {
        AtomicBoolean announced = new AtomicBoolean();
        return (verb, paneId, detail) -> {
            ControlAuditSink.LOGGING.record(verb, paneId, detail);
            if (notifier == null || !WRITING_VERBS.contains(verb)
                    || !announced.compareAndSet(false, true)) {
                return;
            }
            try {
                notifier.notify(I18n.get("controlApi.notify.takeover.title"),
                    I18n.get("controlApi.notify.takeover.body"));
            } catch (RuntimeException e) {
                // An audit sink must never throw; a missing notifier backend is not worth an error.
            }
        };
    }

    /**
     * The scheduler that drains the per-subscription event queues.
     *
     * <p>Its thread is daemon, named like every other control thread, and times out when idle, so a
     * server that is built but never switched on — the default, since the API ships off — leaves
     * nothing running behind it.
     */
    private static ScheduledExecutorService eventTimer() {
        AtomicLong counter = new AtomicLong();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "kortty-control-events-" + counter.incrementAndGet());
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
