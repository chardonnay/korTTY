package de.kortty.ui;

import com.sithtermfx.core.TtyConnector;
import com.sithtermfx.core.model.TerminalTextBuffer;
import com.sithtermfx.ui.SithTermFxWidget;
import de.kortty.codingagent.CodingAgentEntry;
import de.kortty.codingagent.CodingAgentRegistry;
import de.kortty.codingagent.CodingAgentState;
import de.kortty.codingagent.PaneRef;
import de.kortty.codingagent.TabRollup;
import de.kortty.codingagent.TerminalScreenCapture;
import de.kortty.control.AgentInfo;
import de.kortty.control.AgentRollupInfo;
import de.kortty.control.ControlApiException;
import de.kortty.control.ControlErrorCode;
import de.kortty.control.ControlIds;
import de.kortty.control.ControlSurface;
import de.kortty.control.PaneAddress;
import de.kortty.control.PaneInfo;
import de.kortty.control.PaneReader;
import de.kortty.control.TabInfo;
import de.kortty.control.UiDispatcher;
import de.kortty.control.WindowInfo;
import de.kortty.model.ConnectionProtocol;
import de.kortty.model.ServerConnection;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javafx.application.Platform;
import javafx.geometry.Orientation;
import javafx.stage.Stage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The control API's view of the running application: the single implementation of
 * {@link ControlSurface} and {@link UiDispatcher} over the real windows, tabs and panes.
 *
 * <p><strong>JavaFX application thread only</strong>, except where a method says ANY THREAD. Every
 * {@code ControlSurface} method therefore <em>asserts</em> the thread on entry and throws
 * {@link IllegalStateException} rather than answering. That is not paranoia: {@code CodingAgentRegistry}
 * is backed by plain unsynchronised maps whose readers are as thread-confined as its mutators, and
 * {@link MainWindow#getOpenWindows()} hands out the live list, so an off-thread call would not fail —
 * it would quietly return a wrong or half-built answer, which is the one outcome a control API must
 * never produce.
 *
 * <p>This is also the <em>only</em> place {@code javafx.application.Platform} is reachable from the
 * control API: {@code de.kortty.control} owns no JavaFX dependency and marshals everything through
 * {@link #submit(Supplier)}.
 *
 * <p>Nothing is cached between calls, exactly as {@link CodingAgentUiBridge} does it: a tab can move
 * between windows and a pane can close at any time, so every id is re-resolved to a live object per
 * call.
 */
public final class ControlApiUiBridge implements ControlSurface, UiDispatcher {

    private static final Logger logger = LoggerFactory.getLogger(ControlApiUiBridge.class);

    /** The protocol reported for a tab whose connection is not known. */
    private static final String PROTOCOL_UNKNOWN = "UNKNOWN";

    /** The shell pid reported when the pane has none. */
    private static final long UNKNOWN_PID = -1L;

    /** The wire spelling of a vertical split. */
    private static final String ORIENTATION_VERTICAL = "vertical";

    private final Supplier<List<MainWindow>> windows;

    private final CodingAgentRegistry registry;

    private final CodingAgentUiBridge agentBridge;

    private final LongSupplier clockMillis;

    /**
     * @param windows the live open-window list, never cached and always snapshotted on the FX thread
     * @param registry the Stage-2 coding-agent registry, read only from the FX thread
     * @param agentBridge the Stage-2 bridge, reused for the focus sequence a tab focus needs
     * @param clockMillis the wall clock used for the agent projections' time-in-state
     */
    public ControlApiUiBridge(Supplier<List<MainWindow>> windows, CodingAgentRegistry registry,
                              CodingAgentUiBridge agentBridge, LongSupplier clockMillis) {
        this.windows = Objects.requireNonNull(windows, "windows");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.agentBridge = agentBridge;
        this.clockMillis = Objects.requireNonNull(clockMillis, "clockMillis");
    }

    // ---- UiDispatcher -------------------------------------------------------------------------

    /**
     * {@inheritDoc}
     *
     * <p>ANY THREAD. The inline fast path matches {@link TerminalView#focusWidget(SithTermFxWidget)}:
     * a caller that is already on the FX thread runs now instead of queueing behind the pulse it is
     * itself inside.
     */
    @Override
    public <T> CompletableFuture<T> submit(Supplier<T> task) {
        Objects.requireNonNull(task, "task");
        if (Platform.isFxApplicationThread()) {
            try {
                return CompletableFuture.completedFuture(task.get());
            } catch (RuntimeException e) {
                return CompletableFuture.failedFuture(e);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        // An absent toolkit throws IllegalStateException here; UiCalls turns that into ui_unavailable.
        Platform.runLater(() -> {
            try {
                future.complete(task.get());
            } catch (RuntimeException e) {
                future.completeExceptionally(e);
            }
        });
        return future;
    }

    /** ANY THREAD. */
    @Override
    public boolean isUiThread() {
        return Platform.isFxApplicationThread();
    }

    // ---- enumeration --------------------------------------------------------------------------

    @Override
    public List<WindowInfo> listWindows() {
        requireUiThread("listWindows");
        List<MainWindow> open = snapshotWindows();
        List<WindowInfo> result = new ArrayList<>(open.size());
        for (int index = 0; index < open.size(); index++) {
            MainWindow window = open.get(index);
            result.add(new WindowInfo(window.getWindowId(), index, windowTitleOf(window),
                window.isForegroundWindow(), window.getOpenTerminalTabs().size()));
        }
        return List.copyOf(result);
    }

    @Override
    public List<TabInfo> listTabs(String windowIdOrNull) throws ControlApiException {
        requireUiThread("listTabs");
        List<TabInfo> result = new ArrayList<>();
        for (MainWindow window : windowsFor(windowIdOrNull)) {
            TerminalTab active = window.getActiveTerminalTab();
            for (TerminalTab tab : window.getOpenTerminalTabs()) {
                TabInfo info = tabInfo(window, tab, tab == active);
                if (info != null) {
                    result.add(info);
                }
            }
        }
        return List.copyOf(result);
    }

    @Override
    public List<PaneInfo> listPanes(String windowIdOrNull, String tabIdOrNull) throws ControlApiException {
        requireUiThread("listPanes");
        String terminalViewId = tabIdOrNull == null ? null : ControlIds.terminalViewId(tabIdOrNull);
        List<PaneInfo> result = new ArrayList<>();
        boolean tabSeen = false;
        for (MainWindow window : windowsFor(windowIdOrNull)) {
            for (TerminalTab tab : window.getOpenTerminalTabs()) {
                TerminalView view = tab.getTerminalView();
                if (view == null || (terminalViewId != null
                        && !terminalViewId.equals(view.getTerminalViewId()))) {
                    continue;
                }
                tabSeen = true;
                result.addAll(panesOf(window, view));
            }
        }
        if (terminalViewId != null && !tabSeen) {
            throw new ControlApiException(ControlErrorCode.TAB_NOT_FOUND,
                "No tab " + tabIdOrNull + " is open", Map.of("tab", tabIdOrNull));
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<PaneInfo> focusedPane() {
        requireUiThread("focusedPane");
        for (MainWindow window : snapshotWindows()) {
            if (!window.isForegroundWindow()) {
                continue;
            }
            Optional<PaneInfo> pane = focusedPaneOf(window);
            if (pane.isPresent()) {
                return pane;
            }
        }
        // No window has focus (a headless run, or the user is in another application): fall back to
        // the most recently opened one, which is what every other korTTY "current window" does.
        return MainWindow.getFocusedWindow().flatMap(this::focusedPaneOf);
    }

    @Override
    public PaneInfo resolve(PaneAddress address) throws ControlApiException {
        requireUiThread("resolve");
        Objects.requireNonNull(address, "address");
        return switch (address.kind()) {
            case FOCUSED -> focusedPane().orElseThrow(() -> new ControlApiException(
                ControlErrorCode.PANE_NOT_FOUND, "No pane has focus", Map.of("selector", "@focused")));
            case PANE -> resolveBarePane(address.paneId());
            case TAB -> resolveTabPane(address.tabId());
            case QUALIFIED -> resolveQualified(address);
        };
    }

    @Override
    public Optional<PaneInfo> paneForShellPids(List<Long> pidsNearestFirst) {
        requireUiThread("paneForShellPids");
        if (pidsNearestFirst == null || pidsNearestFirst.isEmpty()) {
            return Optional.empty();
        }
        for (Long pid : pidsNearestFirst) {
            if (pid == null) {
                continue;
            }
            for (MainWindow window : snapshotWindows()) {
                for (TerminalTab tab : window.getOpenTerminalTabs()) {
                    TerminalView view = tab.getTerminalView();
                    if (view == null) {
                        continue;
                    }
                    for (SithTermFxWidget widget : view.getOrderedWidgets()) {
                        OptionalLong shellPid = view.shellPidOf(widget);
                        if (shellPid.isPresent() && shellPid.getAsLong() == pid) {
                            return Optional.of(paneInfo(window, view, widget));
                        }
                    }
                }
            }
        }
        return Optional.empty();
    }

    // ---- reading ------------------------------------------------------------------------------

    @Override
    public Optional<PaneReader> readerFor(String paneId) {
        requireUiThread("readerFor");
        return locate(paneId).map(located ->
            new ControlApiPaneReader(paneId, located.widget(), located.view()));
    }

    // ---- writing ------------------------------------------------------------------------------

    @Override
    public int write(String paneId, byte[] bytes) throws ControlApiException {
        requireUiThread("write");
        if (bytes == null || bytes.length == 0) {
            return 0;
        }
        Located located = requireLocated(paneId);
        TtyConnector connector = located.widget().getTtyConnector();
        if (connector == null || !connector.isConnected()) {
            throw new ControlApiException(ControlErrorCode.NOT_CONNECTED,
                "The pane is not connected: " + paneId, Map.of("pane", paneId));
        }
        try {
            // On the FX thread on purpose: this is the same path, interceptors included, that a user
            // keystroke takes, so API input inherits its ordering and visibility guarantees exactly.
            connector.write(bytes);
            return bytes.length;
        } catch (IOException | RuntimeException e) {
            throw new ControlApiException(ControlErrorCode.WRITE_FAILED,
                "The pane refused the write: " + paneId, Map.of("pane", paneId, "detail",
                    e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    @Override
    public boolean isBracketedPasteEnabled(String paneId) {
        requireUiThread("isBracketedPasteEnabled");
        return locate(paneId)
            .map(located -> located.view().isBracketedPasteEnabled(located.widget()))
            .orElse(false);
    }

    @Override
    public boolean wouldHostShortcutIntercept(String firstLine) {
        requireUiThread("wouldHostShortcutIntercept");
        return agentBridge != null && agentBridge.wouldHostShortcutIntercept(firstLine);
    }

    @Override
    public String hostShortcutCommandName() {
        requireUiThread("hostShortcutCommandName");
        return agentBridge == null ? "" : agentBridge.hostShortcutCommandName();
    }

    // ---- focus --------------------------------------------------------------------------------

    @Override
    public boolean focusPane(String paneId) throws ControlApiException {
        requireUiThread("focusPane");
        Located located = requireLocated(paneId);
        raise(located.window());
        located.view().focusWidget(located.widget());
        // Reported from the request having been dispatched: TerminalView.getFocusedWidget() prefers
        // lastFocusedWidget, which the canvas focus observer writes and which a headless or unfocused
        // window may never update, so re-reading it would turn a correct focus into a false failure.
        return true;
    }

    @Override
    public boolean focusTab(String tabId) throws ControlApiException {
        requireUiThread("focusTab");
        String terminalViewId = ControlIds.terminalViewId(tabId);
        for (MainWindow window : snapshotWindows()) {
            TerminalTab tab = window.findTerminalTabByViewId(terminalViewId).orElse(null);
            if (tab == null) {
                continue;
            }
            raise(window);
            if (tab.getTabPane() != null) {
                tab.getTabPane().getSelectionModel().select(tab);
            }
            TerminalView view = tab.getTerminalView();
            if (view != null) {
                SithTermFxWidget current = view.getFocusedWidget();
                if (current != null) {
                    // The tab-selection listener focuses the tab's current widget one pulse later;
                    // land after it so the pane the caller asked for wins, as CodingAgentUiBridge does.
                    Platform.runLater(() -> Platform.runLater(() -> view.focusWidget(current)));
                }
            }
            return true;
        }
        throw new ControlApiException(ControlErrorCode.TAB_NOT_FOUND,
            "No tab " + tabId + " is open", Map.of("tab", tabId));
    }

    @Override
    public Optional<PaneRef> paneRefOf(String paneId) {
        requireUiThread("paneRefOf");
        return locate(paneId).flatMap(located -> located.view().paneRefOf(located.widget()));
    }

    // ---- split and close ----------------------------------------------------------------------

    /** ANY THREAD: a non-blocking connector state read, used by {@code agent.start}'s readiness poll. */
    @Override
    public boolean isPaneConnected(String paneId) {
        Located located = locate(paneId).orElse(null);
        if (located == null) {
            return false;
        }
        TtyConnector connector = located.widget().getTtyConnector();
        return connector != null && connector.isConnected();
    }

    /**
     * {@inheritDoc}
     *
     * <p>ANY THREAD except the JavaFX application thread, which is asserted: this spawns a pty and
     * the whole point of the split path is that the UI thread never waits for it.
     */
    @Override
    public Object prepareLocalShellSplitConnector(String paneId) throws ControlApiException {
        if (Platform.isFxApplicationThread()) {
            throw new IllegalStateException("ControlApiUiBridge.prepareLocalShellSplitConnector must "
                + "not run on the JavaFX application thread: it spawns a shell process");
        }
        // locate() walks the open windows, which is FX-confined, so resolve inside one hop first.
        Located located = awaitLocated(paneId);
        try {
            return located.view().createLocalShellSplitConnector();
        } catch (IllegalStateException e) {
            throw new ControlApiException(ControlErrorCode.UNSUPPORTED,
                "Only a local-shell pane can be split by the control API",
                Map.of("pane", paneId, "reason", "split is local-shell only"));
        } catch (IOException | RuntimeException e) {
            throw new ControlApiException(ControlErrorCode.SPLIT_FAILED,
                "The new local shell could not be started for " + paneId,
                Map.of("pane", paneId, "detail", e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    @Override
    public PaneInfo attachSplitPane(String paneId, String orientation, Object preparedConnector,
                                    boolean focus) throws ControlApiException {
        requireUiThread("attachSplitPane");
        Located located = requireLocated(paneId);
        if (!(preparedConnector instanceof TtyConnector connector)) {
            throw new ControlApiException(ControlErrorCode.SPLIT_FAILED,
                "The prepared connector is not usable for " + paneId, Map.of("pane", paneId));
        }
        Orientation direction = ORIENTATION_VERTICAL.equalsIgnoreCase(orientation)
            ? Orientation.VERTICAL : Orientation.HORIZONTAL;
        SithTermFxWidget created = located.view()
            .attachSplitPane(located.widget(), direction, connector).orElse(null);
        if (created == null) {
            throw new ControlApiException(ControlErrorCode.SPLIT_FAILED,
                "The split aborted: the new pane never attached", Map.of("pane", paneId));
        }
        if (focus) {
            located.view().focusWidget(created);
        }
        return paneInfo(located.window(), located.view(), created);
    }

    @Override
    public void closePane(String paneId) throws ControlApiException {
        requireUiThread("closePane");
        Located located = requireLocated(paneId);
        if (!located.view().closePane(located.widget())) {
            throw new ControlApiException(ControlErrorCode.LAST_PANE,
                "A tab's last pane cannot be closed by the control API: " + paneId,
                Map.of("pane", paneId, "hint", "close the tab yourself"));
        }
    }

    // ---- resolution helpers -------------------------------------------------------------------

    /** One pane resolved to the live objects that answer every question about it. */
    private record Located(MainWindow window, TerminalView view, SithTermFxWidget widget) {
    }

    private PaneInfo resolveBarePane(String paneId) throws ControlApiException {
        List<Located> matches = locateAll(paneId);
        if (matches.isEmpty()) {
            throw paneNotFound(paneId);
        }
        if (matches.size() > 1) {
            // Pane ids derive from identityHashCode and are theoretically collidable; answering
            // ambiguous_pane is the only safe response, because picking one types into the wrong pane.
            throw new ControlApiException(ControlErrorCode.AMBIGUOUS_PANE,
                "More than one live pane carries the id " + paneId,
                Map.of("pane", paneId, "matches", matches.size()));
        }
        Located located = matches.get(0);
        return paneInfo(located.window(), located.view(), located.widget());
    }

    private PaneInfo resolveTabPane(String tabId) throws ControlApiException {
        String terminalViewId = ControlIds.terminalViewId(tabId);
        for (MainWindow window : snapshotWindows()) {
            TerminalTab tab = window.findTerminalTabByViewId(terminalViewId).orElse(null);
            TerminalView view = tab == null ? null : tab.getTerminalView();
            if (view == null) {
                continue;
            }
            SithTermFxWidget widget = view.getFocusedWidget();
            if (widget == null) {
                throw new ControlApiException(ControlErrorCode.PANE_NOT_FOUND,
                    "Tab " + tabId + " has no pane", Map.of("tab", tabId));
            }
            return paneInfo(window, view, widget);
        }
        throw new ControlApiException(ControlErrorCode.TAB_NOT_FOUND,
            "No tab " + tabId + " is open", Map.of("tab", tabId));
    }

    private PaneInfo resolveQualified(PaneAddress address) throws ControlApiException {
        MainWindow window = MainWindow.findWindowById(address.windowId())
            .orElseThrow(() -> new ControlApiException(ControlErrorCode.WINDOW_NOT_FOUND,
                "No window " + address.windowId() + " is open", Map.of("window", address.windowId())));
        TerminalTab tab = window.findTerminalTabByViewId(ControlIds.terminalViewId(address.tabId()))
            .orElseThrow(() -> new ControlApiException(ControlErrorCode.TAB_NOT_FOUND,
                "No tab " + address.tabId() + " is open in " + address.windowId(),
                Map.of("tab", address.tabId(), "window", address.windowId())));
        TerminalView view = tab.getTerminalView();
        SithTermFxWidget widget = view == null ? null
            : view.widgetForPaneId(ControlIds.widgetPaneIdFromPaneId(address.paneId())).orElse(null);
        if (widget == null) {
            throw paneNotFound(address.paneId());
        }
        return paneInfo(window, view, widget);
    }

    /** Every live pane carrying {@code paneId}; more than one means the ids collided. */
    private List<Located> locateAll(String paneId) {
        if (paneId == null || paneId.isBlank()) {
            return List.of();
        }
        String widgetPaneId;
        try {
            widgetPaneId = ControlIds.widgetPaneIdFromPaneId(paneId);
        } catch (IllegalArgumentException e) {
            return List.of();
        }
        List<Located> matches = new ArrayList<>(1);
        for (MainWindow window : snapshotWindows()) {
            for (TerminalTab tab : window.getOpenTerminalTabs()) {
                TerminalView view = tab.getTerminalView();
                if (view == null) {
                    continue;
                }
                view.widgetForPaneId(widgetPaneId)
                    .ifPresent(widget -> matches.add(new Located(window, view, widget)));
            }
        }
        return matches;
    }

    private Optional<Located> locate(String paneId) {
        List<Located> matches = locateAll(paneId);
        return matches.size() == 1 ? Optional.of(matches.get(0)) : Optional.empty();
    }

    private Located requireLocated(String paneId) throws ControlApiException {
        return locate(paneId).orElseThrow(() -> paneNotFound(paneId));
    }

    /** Resolves a pane from off the FX thread, for the split's off-thread connector preparation. */
    private Located awaitLocated(String paneId) throws ControlApiException {
        Located located;
        try {
            located = submit(() -> locate(paneId).orElse(null)).join();
        } catch (RuntimeException e) {
            throw new ControlApiException(ControlErrorCode.UI_UNAVAILABLE,
                "The window could not be reached for " + paneId, Map.of("pane", paneId));
        }
        if (located == null) {
            throw paneNotFound(paneId);
        }
        return located;
    }

    private static ControlApiException paneNotFound(String paneId) {
        return new ControlApiException(ControlErrorCode.PANE_NOT_FOUND,
            "No pane " + paneId + " is open", Map.of("pane", paneId));
    }

    private List<MainWindow> windowsFor(String windowIdOrNull) throws ControlApiException {
        if (windowIdOrNull == null) {
            return snapshotWindows();
        }
        return List.of(MainWindow.findWindowById(windowIdOrNull)
            .orElseThrow(() -> new ControlApiException(ControlErrorCode.WINDOW_NOT_FOUND,
                "No window " + windowIdOrNull + " is open", Map.of("window", windowIdOrNull))));
    }

    // ---- projection ---------------------------------------------------------------------------

    private TabInfo tabInfo(MainWindow window, TerminalTab tab, boolean active) {
        TerminalView view = tab.getTerminalView();
        if (view == null) {
            return null;
        }
        String tabId = ControlIds.tabId(view.getTerminalViewId());
        ServerConnection connection = view.getConnection();
        TtyConnector connector = view.getFocusedWidget() == null ? null
            : view.getFocusedWidget().getTtyConnector();
        return new TabInfo(tabId, window.getWindowId(), CodingAgentUiBridge.tabTitleOf(tab),
            protocolOf(connection), connection == null ? null : connection.getHost(), active,
            connector != null && connector.isConnected(), view.getOrderedWidgets().size(),
            rollup(view.getTerminalViewId()));
    }

    private List<PaneInfo> panesOf(MainWindow window, TerminalView view) {
        List<SithTermFxWidget> widgets = view.getOrderedWidgets();
        List<PaneInfo> result = new ArrayList<>(widgets.size());
        for (SithTermFxWidget widget : widgets) {
            result.add(paneInfo(window, view, widget));
        }
        return result;
    }

    private PaneInfo paneInfo(MainWindow window, TerminalView view, SithTermFxWidget widget) {
        String paneId = ControlIds.paneIdFromWidgetPaneId(TerminalScreenCapture.paneIdOf(widget));
        String tabId = ControlIds.tabId(view.getTerminalViewId());
        ServerConnection connection = view.getConnection();
        String protocol = protocolOf(connection);
        TtyConnector connector = widget.getTtyConnector();
        OptionalLong shellPid = view.shellPidOf(widget);
        Geometry geometry = geometryOf(widget);
        return new PaneInfo(paneId, tabId, window.getWindowId(),
            Math.max(0, view.getOrderedWidgets().indexOf(widget)),
            widget == view.getFocusedWidget(), protocol,
            connector != null && connector.isConnected(),
            connection != null && connection.getProtocol() == ConnectionProtocol.LOCAL_SHELL,
            view.workingDirectoryOf(widget), shellPid.orElse(UNKNOWN_PID),
            geometry.columns(), geometry.rows(), geometry.alternateScreen(),
            view.isBracketedPasteEnabled(widget), agentInfo(view, widget, paneId, tabId,
                window.getWindowId()));
    }

    /** The pane's cell geometry; three field reads under the buffer's own lock. */
    private record Geometry(int columns, int rows, boolean alternateScreen) {
    }

    private static Geometry geometryOf(SithTermFxWidget widget) {
        TerminalTextBuffer buffer = widget.getTerminalTextBuffer();
        if (buffer == null) {
            return new Geometry(0, 0, false);
        }
        buffer.lock();
        try {
            return new Geometry(buffer.getWidth(), buffer.getHeight(), buffer.isUsingAlternateBuffer());
        } finally {
            buffer.unlock();
        }
    }

    private AgentInfo agentInfo(TerminalView view, SithTermFxWidget widget, String paneId, String tabId,
                                String windowId) {
        PaneRef ref = view.paneRefOf(widget).orElse(null);
        CodingAgentEntry entry = ref == null ? null : registry.entry(ref).orElse(null);
        return entry == null
            ? AgentInfo.undetected(paneId, tabId, windowId)
            : AgentInfo.of(entry, paneId, tabId, windowId, clockMillis.getAsLong());
    }

    private AgentRollupInfo rollup(String terminalViewId) {
        TabRollup rollup = registry.rollupFor(terminalViewId);
        if (rollup == null || !rollup.hasAgents()) {
            return new AgentRollupInfo(0, 0, 0, 0, 0, null);
        }
        return new AgentRollupInfo(rollup.blocked(), rollup.working(), rollup.done(), rollup.idle(),
            rollup.agentCount(), wire(rollup.mostUrgent()));
    }

    private static String wire(CodingAgentState state) {
        return state == null ? null : state.name().toLowerCase(Locale.ROOT);
    }

    private static String protocolOf(ServerConnection connection) {
        if (connection == null || connection.getProtocol() == null) {
            return PROTOCOL_UNKNOWN;
        }
        return connection.getProtocol().name();
    }

    private static String windowTitleOf(MainWindow window) {
        Stage stage = window.getStage();
        String title = stage == null ? null : stage.getTitle();
        return title == null ? "" : title;
    }

    private Optional<PaneInfo> focusedPaneOf(MainWindow window) {
        TerminalTab active = window.getActiveTerminalTab();
        TerminalView view = active == null ? null : active.getTerminalView();
        SithTermFxWidget widget = view == null ? null : view.getFocusedWidget();
        return widget == null ? Optional.empty() : Optional.of(paneInfo(window, view, widget));
    }

    /** The stage sequence {@link CodingAgentUiBridge#focus(PaneRef)} uses, so both land identically. */
    private static void raise(MainWindow window) {
        Stage stage = window.getStage();
        if (stage == null) {
            return;
        }
        if (!stage.isShowing()) {
            stage.show();
        }
        if (stage.isIconified()) {
            stage.setIconified(false);
        }
        stage.toFront();
        stage.requestFocus();
    }

    /** The live static list, copied here so a window opening or closing cannot corrupt an answer. */
    private List<MainWindow> snapshotWindows() {
        List<MainWindow> open = windows.get();
        return open == null ? List.of() : List.copyOf(open);
    }

    /**
     * The thread assertion every {@code ControlSurface} method opens with.
     *
     * <p>A refusal, not a marshal: marshalling here would hide the caller's bug and could deadlock a
     * caller that is itself holding the FX thread, while answering off-thread would race
     * {@code CodingAgentRegistry}'s unsynchronised maps and the live open-window list.
     */
    private static void requireUiThread(String method) {
        if (!Platform.isFxApplicationThread()) {
            Map<String, Object> where = new LinkedHashMap<>();
            where.put("method", method);
            where.put("thread", Thread.currentThread().getName());
            logger.debug("control-api surface called off the FX thread: {}", where);
            throw new IllegalStateException("ControlApiUiBridge." + method
                + " must run on the JavaFX application thread, not " + Thread.currentThread().getName());
        }
    }
}
