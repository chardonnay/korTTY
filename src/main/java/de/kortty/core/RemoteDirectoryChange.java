package de.kortty.core;

import org.jetbrains.annotations.Nullable;

import java.util.Objects;

/**
 * A change of the working directory a terminal connector tracks for its shell session.
 *
 * <p>The tracked directory is a hint, never an authority. OSC 7 and the agent hook arrive in the
 * remote output, which anything on the server can forge (a {@code cat} of a crafted file is
 * enough), and a typed {@code cd} is recorded when the line is submitted, before the shell runs it,
 * and also inside editors, containers or a nested login. Consumers may use a change to decide what
 * to <em>list</em>; anything that <em>writes</em> must still target an explicit path the user sees.
 *
 * @param path the new directory
 * @param source where the change came from
 * @param osc7Host the host named in the OSC 7 URI, lower-case; {@code null} for every other source
 *     and empty when the URI named none
 */
public record RemoteDirectoryChange(String path, Source source, @Nullable String osc7Host) {

    public RemoteDirectoryChange {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(source, "source");
    }

    /** Where a tracked directory change came from. */
    public enum Source {
        /** An OSC 7 {@code file://host/path} report from the shell's prompt hook. */
        OSC7,
        /** The korTTY agent hook, which only the shell startup command prints. */
        AGENT_HOOK,
        /** A {@code cd}, {@code pushd} or {@code popd} line the user submitted. */
        TYPED_CD,
        /** A hint from outside the output stream, such as the home or cwd an agent probe resolved. */
        HOME_HINT
    }

    /**
     * Whether the change is low-confidence: a typed {@code cd} is recorded before the shell runs it
     * and may never take effect, so followers wait for a native prompt before acting on it.
     */
    public boolean lowConfidence() {
        return source == Source.TYPED_CD;
    }

    /**
     * Receives directory changes. It is called on the thread that observed the change (the output
     * reader for OSC 7 and the agent hook, the input path for a typed {@code cd}), only for an actual
     * change, after the connector released its directory lock. Implementations must not block and
     * must not touch the JavaFX scene graph inline: hand off, for example with
     * {@code Platform.runLater}.
     */
    @FunctionalInterface
    public interface Listener {
        void onRemoteDirectoryChanged(RemoteDirectoryChange change);
    }

    /** A registration handle; closing it removes the listener. Closing twice is harmless. */
    @FunctionalInterface
    public interface Subscription extends AutoCloseable {
        /** A subscription that holds nothing, for connectors that do not track a directory. */
        Subscription NONE = () -> { };

        @Override
        void close();
    }
}
