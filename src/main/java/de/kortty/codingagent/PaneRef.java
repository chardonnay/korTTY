package de.kortty.codingagent;

/**
 * Stable identity of a terminal pane for the lifetime of its widget.
 * tabId = TerminalView.getTerminalViewId() (UUID per tab lifetime),
 * paneId = TerminalScreenCapture.paneIdOf(widget) = "terminal-&lt;identityHashCode hex&gt;" (recording precedent).
 * Stage 3 maps this to its w&lt;n&gt;:t&lt;sessionId&gt;:p&lt;n&gt; addressing without changing equality.
 */
public record PaneRef(String tabId, String paneId) {
}
