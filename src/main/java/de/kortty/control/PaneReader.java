package de.kortty.control;

/**
 * An off-thread-safe handle to one pane's text buffer.
 *
 * <p>The handle is resolved in a single JavaFX hop and is then used on the connection or timer
 * thread: {@link #read} takes the SithTermFX buffer lock for microseconds per call, which is what
 * keeps a 10 000-line scrollback read entirely off the UI thread and what lets
 * {@code pane.wait_output} poll without registering a listener that could leak when a connection dies
 * mid-wait.
 *
 * <p>ANY THREAD, except the JavaFX application thread for {@link #read}.
 */
public interface PaneReader {

    /** The pane this handle reads. */
    String paneId();

    /** Whether the widget is still open; a closed widget yields an empty read, never an exception. */
    boolean isOpen();

    /**
     * Captures the pane's text.
     *
     * @param mode which buffer to read
     * @param maxLines the largest number of rows to return; only meaningful for
     *     {@link ReadMode#RECENT}
     */
    PaneText read(ReadMode mode, int maxLines);
}
