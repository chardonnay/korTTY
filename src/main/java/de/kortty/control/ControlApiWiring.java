package de.kortty.control;

import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentRegistry;
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
import java.util.function.BooleanSupplier;

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

    /** The verbs that change a pane; the first of them in a run raises the takeover notification. */
    private static final Set<String> WRITING_VERBS =
        Set.of("pane.send_text", "pane.run", "pane.send_keys", "pane.split", "pane.close");

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
     * @param controlActions a <strong>second</strong> {@code CodingAgentActions} over the same registry
     *     and the same UI bridge but with a control-flavoured audit sink, so the Coding Agents panel's
     *     own actions are never mislabelled as API input
     * @param notifier the desktop notifier, or null when the platform has none
     * @param gate {@link ControlApiGate#shouldRun}, re-evaluated at start, at accept and at dispatch
     * @param appVersion the korTTY version published by {@code ping}, {@code auth} and the endpoint file
     * @return a server that is built but not listening
     */
    public static ControlApiServer create(Path configDir, PlatformProbe probe, ControlSurface surface,
                                          UiDispatcher ui, CodingAgentRegistry registry,
                                          CodingAgentActions controlActions, DesktopNotifier notifier,
                                          BooleanSupplier gate, String appVersion) {
        String instanceId = UUID.randomUUID().toString();
        ControlEventBus events = new ControlEventBus(eventTimer(), System::currentTimeMillis);
        MethodRegistry methods = ControlVerbs.build(surface, ui, registry, controlActions, events,
            auditSink(notifier), notifier, System::currentTimeMillis, appVersion, instanceId);
        ControlApiServer server = new ControlApiServer(configDir, probe, methods, gate,
            System::currentTimeMillis, appVersion, instanceId);
        BUSES.put(server, events);
        return server;
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
     */
    private static ControlAuditSink auditSink(DesktopNotifier notifier) {
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
