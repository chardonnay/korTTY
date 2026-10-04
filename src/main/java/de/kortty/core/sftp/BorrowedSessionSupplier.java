package de.kortty.core.sftp;

import org.apache.sshd.client.session.ClientSession;
import org.jetbrains.annotations.Nullable;

/**
 * Resolves the SSH session an SFTP session borrows from a terminal pane, at the moment it is
 * needed. Callers never cache the result: a reconnect gives the pane a new session, and a pane that
 * was closed or now runs another user or host gives {@code null}.
 *
 * <p>Implementations must be cheap and must not block; they are called from worker threads.
 */
@FunctionalInterface
public interface BorrowedSessionSupplier {

    /**
     * The pane's current open SSH session, or {@code null} when the pane is gone, disconnected, or
     * no longer runs the user and host it ran when the SFTP session attached.
     */
    @Nullable ClientSession get();
}
