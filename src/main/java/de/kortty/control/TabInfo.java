package de.kortty.control;

/**
 * One terminal tab. {@code tabId} is {@code "t"} plus {@code TerminalView.getTerminalViewId()}, a
 * per-view-instance UUID; it does not survive a restart.
 *
 * <p>Pure, any thread.
 *
 * @param tabId the stable id for this tab instance
 * @param windowId the window currently holding the tab
 * @param title the tab title
 * @param protocol the connection protocol, e.g. {@code LOCAL_SHELL} or {@code SSH}
 * @param host the remote host, or null for a local shell
 * @param active whether this is the selected tab of its window
 * @param connected whether the tab's connection is up
 * @param paneCount how many panes the tab holds
 * @param agents the per-tab coding-agent rollup
 */
public record TabInfo(String tabId, String windowId, String title, String protocol, String host,
                      boolean active, boolean connected, int paneCount, AgentRollupInfo agents) {
}
