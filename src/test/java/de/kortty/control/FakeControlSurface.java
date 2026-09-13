package de.kortty.control;

import de.kortty.codingagent.PaneRef;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

/** Test double for {@link ControlSurface}: every answer is scripted and every call is recorded. */
final class FakeControlSurface implements ControlSurface {

    /** The call names the split test asserts on, with the hop marker appended. */
    private final List<String> calls = new ArrayList<>();

    private final List<WindowInfo> windows = new ArrayList<>();

    private final List<TabInfo> tabs = new ArrayList<>();

    private final List<PaneInfo> panes = new ArrayList<>();

    private final Map<String, PaneReader> readers = new LinkedHashMap<>();

    private final Map<String, ByteArrayOutputStream> written = new LinkedHashMap<>();

    private final Map<String, Boolean> bracketedPaste = new LinkedHashMap<>();

    private final List<String> focusedPanes = new ArrayList<>();

    private final List<String> focusedTabs = new ArrayList<>();

    private Predicate<String> hostShortcut = line -> false;

    private String hostShortcutCommandName = "agent";

    private BooleanSupplier inUiHop = () -> false;

    private ControlApiException writeFailure;

    private ControlApiException prepareFailure;

    private ControlApiException closeFailure;

    private Object preparedConnector = new Object();

    private PaneInfo attachResult;

    private boolean paneConnected = true;

    private String focusedPaneId;

    /** A pane with the fields the verbs actually read; everything else is a harmless default. */
    static PaneInfo pane(String paneId, String tabId, String windowId, int index, boolean localShell,
                         boolean connected, long shellPid) {
        return new PaneInfo(paneId, tabId, windowId, index, false,
            localShell ? "LOCAL_SHELL" : "SSH", connected, localShell, "/home/u", shellPid,
            80, 24, false, false, AgentInfo.undetected(paneId, tabId, windowId));
    }

    List<String> calls() {
        return List.copyOf(calls);
    }

    void setInUiHop(BooleanSupplier inUiHop) {
        this.inUiHop = inUiHop;
    }

    void addWindow(WindowInfo window) {
        windows.add(window);
    }

    void addTab(TabInfo tab) {
        tabs.add(tab);
    }

    void addPane(PaneInfo pane) {
        panes.add(pane);
    }

    void setFocusedPaneId(String paneId) {
        this.focusedPaneId = paneId;
    }

    void setReader(String paneId, PaneReader reader) {
        readers.put(paneId, reader);
    }

    void setBracketedPaste(String paneId, boolean enabled) {
        bracketedPaste.put(paneId, enabled);
    }

    void setHostShortcut(Predicate<String> hostShortcut, String commandName) {
        this.hostShortcut = hostShortcut;
        this.hostShortcutCommandName = commandName;
    }

    void failWrite(ControlApiException failure) {
        this.writeFailure = failure;
    }

    void failPrepare(ControlApiException failure) {
        this.prepareFailure = failure;
    }

    void failClose(ControlApiException failure) {
        this.closeFailure = failure;
    }

    void setPreparedConnector(Object connector) {
        this.preparedConnector = connector;
    }

    void setAttachResult(PaneInfo attachResult) {
        this.attachResult = attachResult;
    }

    void setPaneConnected(boolean paneConnected) {
        this.paneConnected = paneConnected;
    }

    byte[] written(String paneId) {
        ByteArrayOutputStream out = written.get(paneId);
        return out == null ? new byte[0] : out.toByteArray();
    }

    List<String> focusedPanes() {
        return List.copyOf(focusedPanes);
    }

    List<String> focusedTabs() {
        return List.copyOf(focusedTabs);
    }

    @Override
    public List<WindowInfo> listWindows() {
        record("listWindows");
        return List.copyOf(windows);
    }

    @Override
    public List<TabInfo> listTabs(String windowIdOrNull) throws ControlApiException {
        record("listTabs");
        if (windowIdOrNull != null && windows.stream().noneMatch(w -> w.windowId().equals(windowIdOrNull))) {
            throw new ControlApiException(ControlErrorCode.WINDOW_NOT_FOUND,
                "No window " + windowIdOrNull, Map.of("window", windowIdOrNull));
        }
        List<TabInfo> result = new ArrayList<>();
        for (TabInfo tab : tabs) {
            if (windowIdOrNull == null || windowIdOrNull.equals(tab.windowId())) {
                result.add(tab);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public List<PaneInfo> listPanes(String windowIdOrNull, String tabIdOrNull) throws ControlApiException {
        record("listPanes");
        if (windowIdOrNull != null && windows.stream().noneMatch(w -> w.windowId().equals(windowIdOrNull))) {
            throw new ControlApiException(ControlErrorCode.WINDOW_NOT_FOUND,
                "No window " + windowIdOrNull, Map.of("window", windowIdOrNull));
        }
        if (tabIdOrNull != null && tabs.stream().noneMatch(t -> t.tabId().equals(tabIdOrNull))) {
            throw new ControlApiException(ControlErrorCode.TAB_NOT_FOUND,
                "No tab " + tabIdOrNull, Map.of("tab", tabIdOrNull));
        }
        List<PaneInfo> result = new ArrayList<>();
        for (PaneInfo pane : panes) {
            if ((windowIdOrNull == null || windowIdOrNull.equals(pane.windowId()))
                    && (tabIdOrNull == null || tabIdOrNull.equals(pane.tabId()))) {
                result.add(pane);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public Optional<PaneInfo> focusedPane() {
        record("focusedPane");
        return panes.stream().filter(pane -> pane.paneId().equals(focusedPaneId)).findFirst();
    }

    @Override
    public PaneInfo resolve(PaneAddress address) throws ControlApiException {
        record("resolve");
        return switch (address.kind()) {
            case FOCUSED -> focusedPane().orElseThrow(() -> new ControlApiException(
                ControlErrorCode.PANE_NOT_FOUND, "No pane has focus", Map.of()));
            case TAB -> panes.stream().filter(pane -> pane.tabId().equals(address.tabId())).findFirst()
                .orElseThrow(() -> new ControlApiException(ControlErrorCode.TAB_NOT_FOUND,
                    "No tab " + address.tabId(), Map.of("tab", address.tabId())));
            case PANE, QUALIFIED -> byPaneId(address.paneId());
        };
    }

    private PaneInfo byPaneId(String paneId) throws ControlApiException {
        List<PaneInfo> matches = new ArrayList<>();
        for (PaneInfo pane : panes) {
            if (pane.paneId().equals(paneId)) {
                matches.add(pane);
            }
        }
        if (matches.isEmpty()) {
            throw new ControlApiException(ControlErrorCode.PANE_NOT_FOUND, "No pane " + paneId,
                Map.of("pane", paneId));
        }
        if (matches.size() > 1) {
            throw new ControlApiException(ControlErrorCode.AMBIGUOUS_PANE,
                "More than one live pane answers to " + paneId,
                Map.of("pane", paneId, "matches", matches.size()));
        }
        return matches.get(0);
    }

    @Override
    public Optional<PaneInfo> paneForShellPids(List<Long> pidsNearestFirst) {
        record("paneForShellPids");
        for (Long pid : pidsNearestFirst) {
            for (PaneInfo pane : panes) {
                if (pane.shellPid() == pid) {
                    return Optional.of(pane);
                }
            }
        }
        return Optional.empty();
    }

    @Override
    public Optional<PaneReader> readerFor(String paneId) {
        record("readerFor");
        return Optional.ofNullable(readers.get(paneId));
    }

    @Override
    public int write(String paneId, byte[] bytes) throws ControlApiException {
        record("write");
        if (writeFailure != null) {
            throw writeFailure;
        }
        written.computeIfAbsent(paneId, key -> new ByteArrayOutputStream()).writeBytes(bytes);
        return bytes.length;
    }

    @Override
    public boolean isBracketedPasteEnabled(String paneId) {
        record("isBracketedPasteEnabled");
        return Boolean.TRUE.equals(bracketedPaste.get(paneId));
    }

    @Override
    public boolean wouldHostShortcutIntercept(String firstLine) {
        record("wouldHostShortcutIntercept");
        return hostShortcut.test(firstLine);
    }

    @Override
    public String hostShortcutCommandName() {
        return hostShortcutCommandName;
    }

    @Override
    public boolean focusPane(String paneId) throws ControlApiException {
        record("focusPane");
        byPaneId(paneId);
        focusedPanes.add(paneId);
        return true;
    }

    @Override
    public boolean focusTab(String tabId) throws ControlApiException {
        record("focusTab");
        if (tabs.stream().noneMatch(tab -> tab.tabId().equals(tabId))) {
            throw new ControlApiException(ControlErrorCode.TAB_NOT_FOUND, "No tab " + tabId,
                Map.of("tab", tabId));
        }
        focusedTabs.add(tabId);
        return true;
    }

    @Override
    public Optional<PaneRef> paneRefOf(String paneId) {
        record("paneRefOf");
        for (PaneInfo pane : panes) {
            if (pane.paneId().equals(paneId)) {
                return Optional.of(new PaneRef(ControlIds.terminalViewId(pane.tabId()),
                    ControlIds.widgetPaneIdFromPaneId(paneId)));
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean isPaneConnected(String paneId) {
        record("isPaneConnected");
        return paneConnected;
    }

    @Override
    public Object prepareLocalShellSplitConnector(String paneId) throws ControlApiException {
        record("prepareLocalShellSplitConnector");
        if (prepareFailure != null) {
            throw prepareFailure;
        }
        return preparedConnector;
    }

    @Override
    public PaneInfo attachSplitPane(String paneId, String orientation, Object preparedConnector,
                                    boolean focus) {
        record("attachSplitPane");
        return attachResult;
    }

    @Override
    public void closePane(String paneId) throws ControlApiException {
        record("closePane");
        if (closeFailure != null) {
            throw closeFailure;
        }
        panes.removeIf(pane -> pane.paneId().equals(paneId));
    }

    private void record(String call) {
        calls.add(call + "(inHop=" + inUiHop.getAsBoolean() + ")");
    }
}
