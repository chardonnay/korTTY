package de.kortty.core.sftp;

import de.kortty.ui.I18n;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One SFTP channel opened on a terminal's SSH session for a single job, such as a terminal
 * drag-and-drop upload or a snippet copy to the terminal directory. Use it in try-with-resources:
 * {@link #close()} closes the SFTP channel and nothing else. The session, its client and any jump
 * tunnel belong to the terminal and stay open.
 *
 * <p>The session is passed in at use time and never cached by the caller beyond the job: a lease
 * on a session that is already gone (the tab closed, a reconnect built a new one) fails up front
 * with a readable {@link IOException} instead of a {@link NullPointerException} deep in MINA.
 */
public final class TerminalSftpLease implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(TerminalSftpLease.class);

    private final SftpClient client;
    private final AtomicBoolean closed = new AtomicBoolean();

    private TerminalSftpLease(SftpClient client) {
        this.client = client;
    }

    /**
     * Opens an SFTP channel on {@code session}.
     *
     * @throws IOException when the session is null, closed or closing (message from
     *     {@code terminal.sftp.sessionUnavailable}), or when the server refuses the SFTP channel
     */
    public static TerminalSftpLease open(@Nullable ClientSession session) throws IOException {
        if (session == null || !session.isOpen() || session.isClosing()) {
            throw new IOException(I18n.get("terminal.sftp.sessionUnavailable"));
        }
        return new TerminalSftpLease(SftpClientFactory.instance().createSftpClient(session));
    }

    /** The leased SFTP client; valid until {@link #close()}. */
    public SftpClient client() {
        return client;
    }

    /** Closes the SFTP channel once; never closes the terminal's session. */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        try {
            client.close();
        } catch (IOException | RuntimeException e) {
            logger.debug("Closing a terminal SFTP channel failed: {}", e.getMessage());
        }
    }
}
