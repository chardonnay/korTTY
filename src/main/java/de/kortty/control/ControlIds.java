package de.kortty.control;

/**
 * Pure id minting and prefix handling: the only place a control-API prefix is added or stripped.
 *
 * <p>These methods translate between korTTY's internal widget/view identifiers and the wire ids, so
 * a malformed argument is a programming error in the bridge, not untrusted input — it throws
 * {@link IllegalArgumentException} rather than producing a {@link ControlApiException}. Untrusted
 * selector text goes through {@link PaneAddress#parse(String)} instead.
 *
 * <p>Pure, any thread.
 */
public final class ControlIds {

    /** The window id prefix. */
    public static final String WINDOW_PREFIX = "w";

    /** The tab id prefix. */
    public static final String TAB_PREFIX = "t";

    /** The pane id prefix. */
    public static final String PANE_PREFIX = "p";

    /** The prefix {@code TerminalScreenCapture.paneIdOf(widget)} produces. */
    private static final String WIDGET_PANE_PREFIX = "terminal-";

    private ControlIds() {
    }

    /** {@code "terminal-1a2b"} to {@code "p1a2b"}. */
    public static String paneIdFromWidgetPaneId(String widgetPaneId) {
        require(widgetPaneId, "widgetPaneId");
        if (!widgetPaneId.startsWith(WIDGET_PANE_PREFIX)
                || widgetPaneId.length() == WIDGET_PANE_PREFIX.length()) {
            throw new IllegalArgumentException(
                "Not a widget pane id (expected a '" + WIDGET_PANE_PREFIX + "' prefix): " + widgetPaneId);
        }
        return PANE_PREFIX + widgetPaneId.substring(WIDGET_PANE_PREFIX.length());
    }

    /** {@code "p1a2b"} to {@code "terminal-1a2b"}; used when building a {@code PaneRef}. */
    public static String widgetPaneIdFromPaneId(String paneId) {
        return WIDGET_PANE_PREFIX + strip(paneId, PANE_PREFIX, "paneId");
    }

    /** {@code TerminalView.getTerminalViewId()} to a wire tab id. */
    public static String tabId(String terminalViewId) {
        require(terminalViewId, "terminalViewId");
        return TAB_PREFIX + terminalViewId;
    }

    /** A wire tab id back to {@code TerminalView.getTerminalViewId()}. */
    public static String terminalViewId(String tabId) {
        return strip(tabId, TAB_PREFIX, "tabId");
    }

    /** The window counter minted in the {@code MainWindow} constructor to a wire window id. */
    public static String windowId(long counter) {
        return WINDOW_PREFIX + counter;
    }

    private static String strip(String value, String prefix, String argumentName) {
        require(value, argumentName);
        if (!value.startsWith(prefix) || value.length() == prefix.length()) {
            throw new IllegalArgumentException(
                "Not a '" + prefix + "' id: " + value);
        }
        return value.substring(prefix.length());
    }

    private static void require(String value, String argumentName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(argumentName + " must not be blank");
        }
    }
}
