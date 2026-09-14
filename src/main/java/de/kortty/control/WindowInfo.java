package de.kortty.control;

/**
 * One open window.
 *
 * <p>{@code windowId} is {@code "w"} plus a monotonically increasing counter minted in the
 * {@code MainWindow} constructor: it is <strong>not</strong> the list position, so closing an earlier
 * window does not renumber the rest. {@code index} is the current position and is for display only.
 *
 * <p>Pure, any thread.
 *
 * @param windowId the stable id
 * @param index the current position among the open windows; shifts when a window closes
 * @param title the window title
 * @param focused whether this window currently has focus
 * @param tabCount how many tabs it holds
 */
public record WindowInfo(String windowId, int index, String title, boolean focused, int tabCount) {
}
