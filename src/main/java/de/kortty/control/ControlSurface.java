package de.kortty.control;

import de.kortty.codingagent.PaneRef;
import java.util.List;
import java.util.Optional;

/**
 * Everything Stage 3 needs from the windows, as a narrow port implemented by
 * {@code de.kortty.ui.ControlApiUiBridge} — the same shape {@code de.kortty.codingagent} uses with
 * {@code FocusOracle}/{@code PaneLocator}/{@code PaneAccess}, so the verbs stay unit-testable without
 * a toolkit.
 *
 * <p><strong>JavaFX application thread only</strong>, except the methods whose javadoc says ANY
 * THREAD. Callers reach it through {@link UiCalls#await(UiDispatcher, long,
 * java.util.function.Supplier)}; the implementation asserts the thread on entry.
 *
 * <p>Nothing is cached between calls: a tab can move between windows and a pane can close at any
 * time, so every id is re-resolved to a live object per call.
 */
public interface ControlSurface {

    /** Every open window, in current display order. */
    List<WindowInfo> listWindows();

    /**
     * The tabs of one window, or of all windows when {@code windowIdOrNull} is null.
     *
     * @throws ControlApiException {@link ControlErrorCode#WINDOW_NOT_FOUND}
     */
    List<TabInfo> listTabs(String windowIdOrNull) throws ControlApiException;

    /**
     * The panes of one tab, of one window, or of everything when both arguments are null.
     *
     * @throws ControlApiException {@link ControlErrorCode#WINDOW_NOT_FOUND},
     *     {@link ControlErrorCode#TAB_NOT_FOUND}
     */
    List<PaneInfo> listPanes(String windowIdOrNull, String tabIdOrNull) throws ControlApiException;

    /** The pane the user is looking at, or empty when no window has focus. */
    Optional<PaneInfo> focusedPane();

    /**
     * Resolves a selector to exactly one live pane.
     *
     * @throws ControlApiException {@link ControlErrorCode#PANE_NOT_FOUND},
     *     {@link ControlErrorCode#TAB_NOT_FOUND}, {@link ControlErrorCode#WINDOW_NOT_FOUND},
     *     {@link ControlErrorCode#AMBIGUOUS_PANE} when the id matches more than one live widget
     */
    PaneInfo resolve(PaneAddress address) throws ControlApiException;

    /**
     * The pane whose local shell is the first match in {@code pidsNearestFirst}. This is how a script
     * running inside a pane identifies its own pane.
     */
    Optional<PaneInfo> paneForShellPids(List<Long> pidsNearestFirst);

    /**
     * A read handle for one pane. The handle itself is resolved here; the reads happen later and are
     * ANY THREAD.
     */
    Optional<PaneReader> readerFor(String paneId);

    /**
     * Writes raw bytes to the pane's pty, with the same visibility and ordering guarantees as a user
     * keystroke because it happens on this thread.
     *
     * @return how many bytes were written
     * @throws ControlApiException {@link ControlErrorCode#PANE_NOT_FOUND},
     *     {@link ControlErrorCode#NOT_CONNECTED}, {@link ControlErrorCode#WRITE_FAILED}
     */
    int write(String paneId, byte[] bytes) throws ControlApiException;

    /** Whether the pane has enabled DECSET 2004. */
    boolean isBracketedPasteEnabled(String paneId);

    /** Whether korTTY's own AI shortcut filter would swallow {@code firstLine}. */
    boolean wouldHostShortcutIntercept(String firstLine);

    /** The name of that shortcut command, for {@code data.shortcut}. */
    String hostShortcutCommandName();

    /**
     * Requests focus for a pane. True means the request was <strong>dispatched</strong>; it is never
     * re-read from {@code getFocusedWidget()}, which a headless or unfocused window may never update.
     *
     * @throws ControlApiException {@link ControlErrorCode#PANE_NOT_FOUND}
     */
    boolean focusPane(String paneId) throws ControlApiException;

    /**
     * Raises the window, selects the tab and lands on its current pane.
     *
     * @throws ControlApiException {@link ControlErrorCode#TAB_NOT_FOUND}
     */
    boolean focusTab(String tabId) throws ControlApiException;

    /** The Stage-1/2 handle for the {@code agent.*} verbs; empty when the pane is not open. */
    Optional<PaneRef> paneRefOf(String paneId);

    /**
     * ANY THREAD: the connector state {@code agent.start}'s readiness poll asks for.
     *
     * <p>An implementation whose state is UI-confined marshals — briefly, and bounded by
     * {@link ControlApiProtocol#UI_TIMEOUT_MILLIS} — rather than reading across threads; it answers
     * {@code false} when it cannot reach the UI, because the caller is a poll loop with its own
     * deadline and has nowhere to put an exception.
     */
    boolean isPaneConnected(String paneId);

    /**
     * ANY THREAD, and it MUST NOT be called on the JavaFX application thread: it builds and connects a
     * LOCAL_SHELL connector for a same-server split of {@code paneId}'s tab. Never opens a dialog and
     * never enters {@code TerminalView.doCreateSameServerConnection}, whose modal
     * {@code connectingStage} would run a nested FX event loop.
     *
     * <p>The return type is deliberately {@link Object}: this package must not depend on
     * {@code com.sithtermfx.core.TtyConnector}. The bridge downcasts it in
     * {@link #attachSplitPane(String, String, Object, boolean)}; callers only pass it back.
     *
     * @throws ControlApiException {@link ControlErrorCode#UNSUPPORTED} for a non-LOCAL_SHELL pane,
     *     {@link ControlErrorCode#SPLIT_FAILED} when the shell could not be started
     */
    Object prepareLocalShellSplitConnector(String paneId) throws ControlApiException;

    /**
     * JavaFX thread. Attaches the already-connected connector as a new pane next to {@code paneId}.
     *
     * @return the new pane
     * @throws ControlApiException {@link ControlErrorCode#PANE_NOT_FOUND},
     *     {@link ControlErrorCode#SPLIT_FAILED} when the split aborted
     */
    PaneInfo attachSplitPane(String paneId, String orientation, Object preparedConnector, boolean focus)
        throws ControlApiException;

    /**
     * JavaFX thread. Closes one split pane.
     *
     * @throws ControlApiException {@link ControlErrorCode#PANE_NOT_FOUND},
     *     {@link ControlErrorCode#LAST_PANE} for a tab's last pane, which would leave an empty
     *     terminal area in a still-open tab
     */
    void closePane(String paneId) throws ControlApiException;
}
