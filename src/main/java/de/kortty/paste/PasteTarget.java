package de.kortty.paste;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

/**
 * A terminal pane that {@link PasteGuard} pastes into.
 *
 * <p>A pane has exactly one stable {@link #key()}. Two target objects for the same pane must return
 * the same key object, because the guard keeps at most one pending confirmation per key.
 */
public interface PasteTarget {

    /** The pane's identity, compared by reference; the same object for as long as the pane lives. */
    Object key();

    /**
     * Whether the program in the pane has bracketed paste (DECSET 2004) enabled right now. Read again
     * when a confirmed paste is sent, since the program may have changed it in the meantime.
     */
    boolean bracketedPasteMode();

    /** Whether the pane has a live session that can receive text. */
    boolean canReceive();

    /**
     * The pane's current session, compared by reference. A reconnect or another rebind replaces it,
     * so a paste confirmed for the old session is dropped instead of reaching the new one.
     */
    Object session();

    /**
     * Writes the finished payload to the pane as user input, in order with the user's keystrokes.
     *
     * @param payload the text from {@link PasteSanitizer#encode(String, boolean, Charset)}
     */
    void send(String payload);

    /** The pane as the user knows it, usually the connection's name; never null. */
    String label();

    /** The encoding the pane's connector sends text in; it decides the 8-bit marker form. */
    default Charset charset() {
        return StandardCharsets.UTF_8;
    }

    /** Whether broadcast mode is on in the pane's tab. */
    default boolean broadcastActive() {
        return false;
    }

    /**
     * Whether the pane takes part in multi-exec, so what it receives as user input is mirrored to the
     * other panes of the group.
     */
    default boolean multiExecActive() {
        return false;
    }
}
