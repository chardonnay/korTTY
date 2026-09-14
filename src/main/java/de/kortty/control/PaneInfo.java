package de.kortty.control;

/**
 * One terminal pane. {@code paneId} is {@code "p"} plus {@code TerminalScreenCapture.paneIdOf(widget)}
 * with its {@code terminal-} prefix stripped; see {@link ControlIds}.
 *
 * <p>Pure, any thread.
 *
 * @param paneId the stable id for this widget's life
 * @param tabId the tab holding the pane
 * @param windowId the window holding that tab
 * @param index the current position among the tab's panes; for display only
 * @param focused whether this is the tab's focused pane
 * @param protocol the tab's connection protocol
 * @param connected whether the pane's connector is connected
 * @param localShell whether the pane is a local shell, the only kind that can be split
 * @param workingDirectory the connector-reported working directory, or null
 * @param shellPid the local shell pid, or -1 when unknown (remote panes, Flatpak, disconnected)
 * @param columns the pane width in cells
 * @param rows the pane height in cells
 * @param alternateScreen whether the alternate screen buffer is active
 * @param bracketedPaste whether the pane has enabled DECSET 2004
 * @param agent the coding agent in this pane; never null, see {@link AgentInfo#undetected}
 */
public record PaneInfo(String paneId, String tabId, String windowId, int index, boolean focused,
                       String protocol, boolean connected, boolean localShell,
                       String workingDirectory, long shellPid, int columns, int rows,
                       boolean alternateScreen, boolean bracketedPaste, AgentInfo agent) {
}
