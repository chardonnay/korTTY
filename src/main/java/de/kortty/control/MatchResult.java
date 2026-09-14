package de.kortty.control;

/**
 * The result of {@code pane.wait_output}.
 *
 * <p>Pure, any thread.
 *
 * @param paneId the pane that was watched
 * @param matched whether the pattern was found before the deadline
 * @param match the matched text, or null
 * @param line the whole line the match sits on, or null
 * @param lineIndex the index of that line within {@link #screen()}, or -1
 * @param waitedMillis how long the wait actually took
 * @param screen the text the last poll saw
 */
public record MatchResult(String paneId, boolean matched, String match, String line, int lineIndex,
                          long waitedMillis, PaneText screen) {
}
