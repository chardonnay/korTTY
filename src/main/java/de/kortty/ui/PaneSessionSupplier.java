package de.kortty.ui;

import de.kortty.core.sftp.BorrowedSessionSupplier;
import de.kortty.model.ServerConnection;
import org.apache.sshd.client.session.ClientSession;
import org.jetbrains.annotations.Nullable;

import java.lang.ref.WeakReference;
import java.util.Locale;
import java.util.Objects;

/**
 * The {@link BorrowedSessionSupplier} of one terminal pane: the SSH session an SFTP tab or the
 * remote sidebar borrows from it.
 *
 * <p>It holds the terminal view and the pane only weakly, so an SFTP tab left open never keeps a
 * closed terminal alive, and it remembers the pane's identity (connection id, user name and host
 * of its {@link PaneOrigin}) as it was when SFTP attached. {@link #get()} resolves the pane's
 * current connector every time (a reconnect builds a new one with a new session) and returns its
 * session only while the identity still matches. A pane that was closed, is disconnected, or now
 * runs another user or host gives {@code null}, so the borrower never lists or writes as someone
 * else.
 *
 * <p>Generic in the view and pane types so it carries no JavaFX dependency; the resolver must not
 * touch the scene graph, because {@link #get()} runs on worker threads.
 *
 * @param <V> the terminal view type
 * @param <P> the pane type
 */
final class PaneSessionSupplier<V, P> implements BorrowedSessionSupplier {

    /**
     * Who a pane's session logs in as. The host is compared without case, the user name exactly.
     *
     * @param connectionId the saved connection's id, or null for an unsaved one
     * @param username the login user
     * @param host the target host as configured
     */
    record Identity(@Nullable String connectionId, @Nullable String username, @Nullable String host) {

        static Identity of(ServerConnection connection) {
            Objects.requireNonNull(connection, "connection");
            return new Identity(connection.getId(), connection.getUsername(), connection.getHost());
        }

        boolean matches(@Nullable Identity other) {
            return other != null
                && Objects.equals(connectionId, other.connectionId)
                && Objects.equals(username, other.username)
                && Objects.equals(normalizedHost(host), normalizedHost(other.host));
        }

        private static @Nullable String normalizedHost(@Nullable String host) {
            return host == null ? null : host.trim().toLowerCase(Locale.ROOT);
        }

        /** Names no user: identities reach log lines. */
        @Override
        public String toString() {
            return "Identity[" + connectionId + ", " + host + "]";
        }
    }

    /**
     * What a pane runs right now.
     *
     * @param identity who the pane's origin logs in as
     * @param sessionConnection the connection the pane's connector actually runs, or null when unknown
     * @param session the connector's SSH session, or null (closed, local, Mosh, disconnected)
     */
    record PaneSession(Identity identity, @Nullable Identity sessionConnection, @Nullable ClientSession session) {
        PaneSession {
            Objects.requireNonNull(identity, "identity");
        }
    }

    /** Looks up what {@code pane} of {@code view} runs now, or null when the pane is gone. */
    @FunctionalInterface
    interface Resolver<V, P> {
        @Nullable PaneSession resolve(V view, P pane);
    }

    private final WeakReference<V> view;
    private final WeakReference<P> pane;
    private final Identity attachedIdentity;
    private final Resolver<V, P> resolver;

    /**
     * @param resolver must not capture {@code view} or {@code pane} strongly (use a static method
     *     reference), or the weak references are pointless
     */
    PaneSessionSupplier(V view, P pane, Identity attachedIdentity, Resolver<V, P> resolver) {
        this.view = new WeakReference<>(Objects.requireNonNull(view, "view"));
        this.pane = new WeakReference<>(Objects.requireNonNull(pane, "pane"));
        this.attachedIdentity = Objects.requireNonNull(attachedIdentity, "attachedIdentity");
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    /** The identity recorded when SFTP attached. */
    Identity attachedIdentity() {
        return attachedIdentity;
    }

    @Override
    public @Nullable ClientSession get() {
        V currentView = view.get();
        P currentPane = pane.get();
        if (currentView == null || currentPane == null) {
            return null;
        }
        PaneSession now;
        try {
            now = resolver.resolve(currentView, currentPane);
        } catch (RuntimeException e) {
            return null;
        }
        if (now == null || !attachedIdentity.matches(now.identity())) {
            return null;
        }
        if (now.sessionConnection() != null
                && !sameUserAndHost(attachedIdentity, now.sessionConnection())) {
            return null;
        }
        ClientSession session = now.session();
        return session != null && session.isOpen() && !session.isClosing() ? session : null;
    }

    /** The connector's own connection may be an unsaved copy, so only user and host must agree. */
    private static boolean sameUserAndHost(Identity attached, Identity running) {
        return attached.matches(new Identity(attached.connectionId(), running.username(), running.host()));
    }
}
