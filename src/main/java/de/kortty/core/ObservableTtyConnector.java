package de.kortty.core;

import com.sithtermfx.core.TtyConnector;
import de.kortty.model.ServerConnection;

import java.io.IOException;

/**
 * A {@link TtyConnector} that exposes observation/interception hooks shared by korTTY features
 * such as terminal recording/logging and the AI-agent prompt detection.
 *
 * <p>These hooks used to live exclusively on {@link SshTtyConnector}, which tied recording and the
 * AI input interceptor to SSH connections. Hoisting them here lets any connector (e.g. the local
 * shell connector) participate without depending on the SSH implementation.</p>
 *
 * <p>Note: features that require a live SSH session / second channel (e.g. running side commands
 * over a separate exec channel) remain specific to {@link SshTtyConnector} and are intentionally
 * NOT part of this interface.</p>
 */
public interface ObservableTtyConnector extends TtyConnector {

    /** Listener for data received (decoded output) from the connection. */
    interface DataListener {
        void onData(String data);
    }

    /**
     * Listener for outbound terminal input activity. It deliberately receives only a byte count
     * so features can react to activity without persisting user input text.
     */
    interface InputActivityListener {
        void onInputActivity(int byteCount);
    }

    /** Intercepts outbound terminal input before it is written to the underlying channel/process. */
    @FunctionalInterface
    interface InputInterceptor {
        byte[] intercept(byte[] bytes) throws IOException;
    }

    /** Sets the listener notified when the connection is torn down. */
    void setDisconnectListener(DisconnectListener listener);

    /**
     * Whether the session ended because the transport died (network drop, server gone) rather than
     * through a normal remote exit. The UI keeps the tab open with a reconnect offer in that case
     * instead of closing it. Connectors that cannot tell report {@code false}.
     */
    default boolean wasConnectionLost() {
        return false;
    }

    /** Replaces all data listeners with the given one (clears existing). Null clears them all. */
    void setDataListener(DataListener listener);

    /** Adds a data listener (idempotent). */
    void addDataListener(DataListener listener);

    /** Removes a previously added data listener. */
    void removeDataListener(DataListener listener);

    /** Adds an input-activity listener (idempotent). */
    void addInputActivityListener(InputActivityListener listener);

    /** Removes a previously added input-activity listener. */
    void removeInputActivityListener(InputActivityListener listener);

    /** Sets the interceptor applied to outbound input before it reaches the channel/process. */
    void setInputInterceptor(InputInterceptor inputInterceptor);

    /** The connection this connector serves (used by AI-agent run context, status, etc.). */
    ServerConnection getConnection();

    /**
     * Legacy SSH-oriented name for the tracked working directory, or {@code null} when not tracked.
     * New transport-neutral callers should use {@link #getCurrentWorkingDirectory()}.
     */
    default String getCurrentRemoteDirectory() {
        return null;
    }

    /**
     * Returns the connector's best currently known working directory without performing blocking
     * I/O. This transport-neutral name is preferred by features that work for both SSH and local
     * shells. The legacy remote-directory method remains the compatibility source by default.
     */
    default String getCurrentWorkingDirectory() {
        return getCurrentRemoteDirectory();
    }

    /**
     * Refreshes and returns the connector's current working directory. Implementations may block;
     * callers must invoke this off the JavaFX application thread. Connectors without an active
     * refresh mechanism simply return their non-blocking value.
     */
    default String refreshCurrentWorkingDirectory() {
        return getCurrentWorkingDirectory();
    }

    /** The home directory tracked for this connector, or {@code null} when not tracked. */
    default String getHomeRemoteDirectory() {
        return null;
    }

    /** Hint the connector about the current working directory. No-op for connectors that don't track it. */
    default void updateCurrentRemoteDirectoryHint(String directory) {
    }

    /**
     * Supplies a transport-neutral working-directory hint. The default delegates to the legacy SSH
     * API so existing connector implementations retain their behavior.
     */
    default void updateCurrentWorkingDirectoryHint(String directory) {
        updateCurrentRemoteDirectoryHint(directory);
    }
}
