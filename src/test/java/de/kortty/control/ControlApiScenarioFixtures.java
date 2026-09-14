package de.kortty.control;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.sithtermfx.core.TtyConnector;
import de.kortty.codingagent.CodingAgentActions;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.PaneAccess;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.RecordingTtyConnector;
import de.kortty.codingagent.desktop.DesktopNotifier;
import de.kortty.codingagent.desktop.DesktopNotifierBackend;
import de.kortty.codingagent.desktop.PlatformProbe;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.testng.SkipException;

/**
 * The shared scenery of the Stage-3 control-API contract suite: three scripted window layouts and one
 * factory that stands a <strong>real</strong> {@link ControlApiServer} on a <strong>real</strong>
 * socket in front of them.
 *
 * <p>This is the seam that lets {@code de.kortty.cli}'s tests drive the genuine server: the surfaces
 * come back as {@link ControlSurface}, so a test outside this package never has to see
 * {@code FakeControlSurface}, and {@link #startServer} assembles exactly the objects korTTY's own
 * bridge will assemble in P6 — the real {@link ControlVerbs} table, the real
 * {@link ControlApiServer}, the real {@link ControlEventBus} wired to the registry — with only the
 * two ports that need a toolkit replaced: {@link ControlSurface} by a scripted double and
 * {@link UiDispatcher} by {@link UiDispatcher#DIRECT}, which runs every hop inline so that
 * <strong>no JavaFX toolkit is ever started</strong>.
 *
 * <p>Why that matters: P2 (transport), P3 (verbs) and P4 (the CLI) were each written against a frozen
 * paper spec and each tested in isolation. A disagreement between them — a result field that is named
 * differently on the two sides, an error code the client maps to another exit, a frame the server can
 * emit but its own codec rejects — is invisible to every one of those suites and visible only here,
 * where all three run against one socket.
 *
 * <p>Any thread; every method is a pure factory apart from the filesystem and socket work
 * {@link #startServer} performs. The scheduler and the notifier executor are process-wide daemons,
 * deliberately named outside the {@code kortty-control-} space so
 * {@code ControlApiThreadHygieneTest} can keep asserting that no control thread survives a
 * {@code close()}.
 */
public final class ControlApiScenarioFixtures {

    /** The longest socket path these tests attempt; below the measured Linux and macOS limits. */
    private static final int MAX_SOCKET_PATH_CHARS = 100;

    /** The version every fixture server reports; read back from the endpoint rather than imported. */
    private static final String APP_VERSION = "3.4.1";

    /** Test-owned threads, named so the thread-hygiene test never mistakes a fixture for a leak. */
    private static final ThreadFactory FIXTURE_THREADS = runnable -> {
        Thread thread = new Thread(runnable, "kt-fixture-worker");
        thread.setDaemon(true);
        return thread;
    };

    /** The scheduler the event bus drains its per-subscription queues on. */
    private static final ScheduledExecutorService TIMER = timer();

    /** Never used: the backend below reports no support, so {@code notify} returns before submitting. */
    private static final ExecutorService NOTIFIER_EXECUTOR =
        Executors.newSingleThreadExecutor(FIXTURE_THREADS);

    /**
     * A notifier that answers {@code supported:false}.
     *
     * <p>It exists so {@code notification.show} reaches its rate limiter — the only path in the whole
     * API that can produce {@code busy} — instead of failing early with {@code unsupported}, and so
     * that no test can raise a real desktop notification on a developer's machine.
     */
    private static final DesktopNotifier NOTIFIER = new DesktopNotifier(new DesktopNotifierBackend() {

        @Override
        public boolean isSupported() {
            return false;
        }

        @Override
        public void notify(String title, String body) {
            throw new AssertionError("A fixture must never raise a real desktop notification");
        }
    }, NOTIFIER_EXECUTOR);

    private ControlApiScenarioFixtures() {
    }

    /**
     * Two windows, three tabs, four panes — the layout every enumeration and addressing assertion
     * needs.
     *
     * <p>{@code w1} holds {@code t9f3a} (panes {@code p1a2b} and {@code p2c3d}) and {@code t7b8c}
     * (pane {@code p3e4f}); {@code w2} holds {@code t5d6e} (the SSH pane {@code p4a5b}). The focused
     * pane is {@code p1a2b}, so {@code @focused} and {@code pane.current} have a definite answer, and
     * every local-shell pane carries a reader with scripted {@code visible} and {@code recent} text.
     */
    public static ControlSurface twoWindowsThreeTabs() {
        FakeControlSurface surface = new FakeControlSurface();
        surface.addWindow(new WindowInfo("w1", 0, "korTTY", true, 2));
        surface.addWindow(new WindowInfo("w2", 1, "korTTY", false, 1));
        surface.addTab(tab("t9f3a", "w1", "LOCAL_SHELL", true, 2));
        surface.addTab(tab("t7b8c", "w1", "LOCAL_SHELL", false, 1));
        surface.addTab(tab("t5d6e", "w2", "SSH", true, 1));
        surface.addPane(FakeControlSurface.pane("p1a2b", "t9f3a", "w1", 0, true, true, 4711L));
        surface.addPane(FakeControlSurface.pane("p2c3d", "t9f3a", "w1", 1, true, true, 4712L));
        surface.addPane(FakeControlSurface.pane("p3e4f", "t7b8c", "w1", 0, true, true, 4713L));
        surface.addPane(FakeControlSurface.pane("p4a5b", "t5d6e", "w2", 0, false, true, -1L));
        surface.setFocusedPaneId("p1a2b");
        reader(surface, "p1a2b", "user@host:~$ ");
        reader(surface, "p2c3d", "second pane");
        reader(surface, "p3e4f", "third pane");
        return surface;
    }

    /**
     * One window, one tab, one connected local-shell pane {@code p1a2b} whose shell pid is
     * {@code 4711} and whose {@code PaneInfo} already advertises a Claude Code agent.
     *
     * <p>The surface side only claims the pane; a registry entry — what every {@code agent.*} verb
     * actually requires — is the caller's to add, because the registry is injected separately and is
     * the single source of truth for {@code detected}.
     */
    public static ControlSurface oneLocalShellPaneWithClaudeCode() {
        FakeControlSurface surface = new FakeControlSurface();
        surface.addWindow(new WindowInfo("w1", 0, "korTTY", true, 1));
        surface.addTab(tab("t9f3a", "w1", "LOCAL_SHELL", true, 1));
        surface.addPane(FakeControlSurface.pane("p1a2b", "t9f3a", "w1", 0, true, true, 4711L));
        surface.setFocusedPaneId("p1a2b");
        reader(surface, "p1a2b", "> summarise de.kortty.control");
        return surface;
    }

    /**
     * One window, one tab, one connected SSH pane {@code p4a5b}.
     *
     * <p>This is the scenery for the documented refusal: {@code pane.split} is local-shell only and
     * must answer {@code unsupported} with {@code data.reason} and {@code data.protocol}, never
     * {@code split_failed} and never a silent success.
     */
    public static ControlSurface oneSshPane() {
        FakeControlSurface surface = new FakeControlSurface();
        surface.addWindow(new WindowInfo("w1", 0, "korTTY", true, 1));
        surface.addTab(tab("t5d6e", "w1", "SSH", true, 1));
        surface.addPane(FakeControlSurface.pane("p4a5b", "t5d6e", "w1", 0, false, true, -1L));
        surface.setFocusedPaneId("p4a5b");
        reader(surface, "p4a5b", "remote$ ");
        return surface;
    }

    /**
     * Starts a real control-API server over {@code surface} and {@code registry} and returns it
     * already listening.
     *
     * <p>The transport is whatever this platform would really choose — {@code AF_UNIX} on POSIX,
     * {@code 127.0.0.1} on Windows — so the UDS leg is exercised wherever it exists and the loopback
     * leg wherever it does not. The gate is a constant {@link ControlApiGate.Verdict#OPEN}:
     * {@link ControlApiGate} reads the settings and policy flags reflectively and refuses until P6
     * supplies them, which would otherwise make every test here silently bind nothing.
     *
     * <p>The caller owns the returned server and must {@code close()} it in an {@code @AfterMethod};
     * a leaked listener keeps a socket file that the next test's {@code deleteTree} cannot remove.
     *
     * @param tempConfigDir the temporary stand-in for {@code ~/.kortty}; the server owns
     *     {@code tempConfigDir/control} and writes {@code endpoint.json} there last
     * @param surface one of the scenarios above, or any other {@link ControlSurface}
     * @param registry the Stage-2 registry, normally {@code CodingAgentRegistry.forTests(...)}, which
     *     needs no JavaFX toolkit
     * @throws SkipException when the socket path would exceed the platform limit
     */
    public static ControlApiServer startServer(Path tempConfigDir, ControlSurface surface,
                                               CodingAgentRegistry registry) {
        skipIfSocketPathTooLong(tempConfigDir.resolve(ControlDirectory.DIRECTORY_NAME));
        ControlEventBus events = new ControlEventBus(TIMER, System::currentTimeMillis);
        CodingAgentActions actions = new CodingAgentActions(registry, new AlwaysConnectedPanes(),
            (verb, pane, detail) -> { });
        String instanceId = UUID.randomUUID().toString();
        MethodRegistry methods = ControlVerbs.build(surface, UiDispatcher.DIRECT, registry, actions,
            events, (verb, pane, detail) -> { }, NOTIFIER, System::currentTimeMillis, APP_VERSION,
            instanceId);
        registry.addListener(events.registryListener(surface));
        ControlApiServer server = new ControlApiServer(tempConfigDir, nativeProbe(), methods,
            () -> ControlApiGate.Verdict.OPEN, System::currentTimeMillis, APP_VERSION, instanceId);
        server.applyEnabledState();
        return server;
    }

    /**
     * Skips — never fails — when a unix-domain socket under {@code controlDir} would be longer than
     * the kernel accepts.
     *
     * <p>The limit is a measured platform fact, not a preference: {@code sun_path} holds 108 bytes on
     * Linux and 104 on macOS, where the temp directory alone is {@code /var/folders/xy/<32 chars>/T/}.
     * A CI checkout deep enough to exceed it must lose these tests, not report them as broken. On
     * Windows there is no socket path at all and the loopback leg runs unconditionally.
     */
    public static void skipIfSocketPathTooLong(Path controlDir) {
        if (isWindows()) {
            return;
        }
        String path = controlDir.resolve(ControlApiTransports.SOCKET_FILE_NAME)
            .toAbsolutePath().toString();
        if (path.length() > MAX_SOCKET_PATH_CHARS) {
            throw new SkipException("the socket path is " + path.length() + " characters, over the "
                + MAX_SOCKET_PATH_CHARS + "-character test limit");
        }
    }

    // --- shared helpers the suite's @BeforeMethod / @AfterMethod blocks need -------------------

    /** A short temp root, so the socket path below it stays inside the platform limit. */
    static Path newTempRoot() throws IOException {
        return Files.createTempDirectory("kt");
    }

    /** Whether this JVM runs on Windows, where the unix-domain leg does not exist. */
    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    /** The probe this platform would really produce, so each leg binds what it can. */
    static PlatformProbe nativeProbe() {
        return isWindows()
            ? new PlatformProbe("Windows 11", null, false, true)
            : new PlatformProbe("Linux", null, false, true);
    }

    /**
     * Removes a temp tree deepest-entry-first; never throws.
     *
     * <p>Closing a channel does not unlink a socket file — that is the server's job — so this runs
     * after {@code server.close()} and not instead of it.
     */
    static void deleteTree(Path root) {
        if (root == null || !Files.exists(root)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // A disposable temp tree: a file another process still owns is not worth failing.
                }
            });
        } catch (IOException ignored) {
            // Nothing further to do in a test teardown.
        }
    }

    /** The {@link PaneRef} the Stage-2 registry knows a control pane id by. */
    static PaneRef paneRef(String tabId, String paneId) {
        return new PaneRef(ControlIds.terminalViewId(tabId), ControlIds.widgetPaneIdFromPaneId(paneId));
    }

    /** A parameter object built from alternating name/value pairs; values may be null. */
    static JsonObject params(Object... pairs) {
        JsonObject params = new JsonObject();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            String name = String.valueOf(pairs[i]);
            Object value = pairs[i + 1];
            if (value == null) {
                params.add(name, JsonNull.INSTANCE);
            } else if (value instanceof Number number) {
                params.addProperty(name, number);
            } else if (value instanceof Boolean flag) {
                params.addProperty(name, flag);
            } else if (value instanceof JsonElement element) {
                params.add(name, element);
            } else {
                params.addProperty(name, String.valueOf(value));
            }
        }
        return params;
    }

    /**
     * One authenticated conversation with a fixture server, over the real socket.
     *
     * <p>It deliberately speaks the protocol by hand rather than through {@code ControlClient}: these
     * tests assert the <em>frames</em> — the echoed id, the {@code jsonrpc} member, an error object
     * where a result was expected — and a client that hides them would hide the drift they exist to
     * catch. {@code de.kortty.cli}'s own round trip is asserted separately, through {@code KorttyCli}.
     */
    static final class Wire implements AutoCloseable {

        private final UdsTestSupport.Client client;

        private long nextId = 1L;

        Wire(EndpointDescriptor endpoint) throws IOException {
            this.client = new UdsTestSupport.Client(endpoint);
        }

        /** The id the most recent {@link #call} used. */
        long lastId() {
            return nextId - 1L;
        }

        /** Sends {@code auth} with the endpoint's token and returns the whole reply frame. */
        JsonObject authenticate(String token) throws IOException {
            return call(ControlConnection.AUTH_METHOD, params("token", token, "client", "contract-test"));
        }

        /** Sends one request and returns the whole reply frame, skipping any event in between. */
        JsonObject call(String method, JsonObject params) throws IOException {
            long id = nextId++;
            client.send(requestLine(id, method, params));
            while (true) {
                JsonObject frame = client.receive();
                if (frame == null) {
                    return null;
                }
                JsonElement frameId = frame.get("id");
                if (frameId != null && frameId.isJsonPrimitive() && frameId.getAsLong() == id) {
                    return frame;
                }
                if (!frame.has("method")) {
                    return frame;
                }
            }
        }

        /** Sends a line verbatim, so a malformed or oversized request can be put on the wire. */
        void sendRaw(String line) throws IOException {
            client.send(line);
        }

        /** The next frame of any kind, or null once the server closed the connection. */
        JsonObject next() throws IOException {
            return client.receive();
        }

        @Override
        public void close() {
            client.close();
        }
    }

    /** One JSON-RPC request line, exactly as the wire specification writes it. */
    static String requestLine(long id, String method, JsonObject params) {
        JsonObject request = new JsonObject();
        request.addProperty("jsonrpc", "2.0");
        request.addProperty("id", id);
        request.addProperty("method", method);
        request.add("params", params == null ? new JsonObject() : params);
        return ControlJson.gson().toJson(request);
    }

    private static TabInfo tab(String tabId, String windowId, String protocol, boolean active,
                               int paneCount) {
        return new TabInfo(tabId, windowId, "shell", protocol, protocol.equals("SSH") ? "host" : null,
            active, true, paneCount, new AgentRollupInfo(0, 0, 0, 0, 0, "unknown"));
    }

    private static void reader(FakeControlSurface surface, String paneId, String lastLine) {
        FakePaneReader reader = new FakePaneReader(paneId);
        reader.setLines(ReadMode.VISIBLE, List.of("$ echo hello", "hello", lastLine));
        reader.setLines(ReadMode.RECENT, List.of("$ make", "ok", lastLine));
        surface.setReader(paneId, reader);
    }

    private static ScheduledExecutorService timer() {
        ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, FIXTURE_THREADS);
        executor.setKeepAliveTime(1L, TimeUnit.SECONDS);
        executor.allowCoreThreadTimeOut(true);
        executor.setRemoveOnCancelPolicy(true);
        return executor;
    }

    /**
     * A {@link PaneAccess} whose panes are always open and always connected.
     *
     * <p>{@code CodingAgentActions} needs one, and the pane a test splits into does not exist until
     * the split has run, so the connectors are minted on demand rather than registered up front.
     */
    private static final class AlwaysConnectedPanes implements PaneAccess {

        private final Map<PaneRef, TtyConnector> connectors = new ConcurrentHashMap<>();

        @Override
        public Optional<TtyConnector> connectorFor(PaneRef pane) {
            return pane == null ? Optional.empty()
                : Optional.of(connectors.computeIfAbsent(pane, key -> new RecordingTtyConnector()));
        }

        @Override
        public boolean isBracketedPasteEnabled(PaneRef pane) {
            return false;
        }

        @Override
        public boolean wouldHostShortcutIntercept(String firstLine) {
            return false;
        }

        @Override
        public String hostShortcutCommandName() {
            return "agent";
        }
    }
}
