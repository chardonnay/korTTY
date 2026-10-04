package de.kortty.core.sftp;

import org.apache.sshd.sftp.client.SftpClient;

import java.io.IOException;

/**
 * Where transfer workers get their SFTP channels from.
 *
 * <p>The {@linkplain #primaryClient() primary client} is the multiplexed channel the tab also lists
 * folders with; it belongs to the source and a worker must never close it (cancelling a transfer on
 * it is cooperative, per buffer). {@link #openChannel()} opens an additional SFTP channel on the same
 * SSH session for a parallel worker; the caller owns that channel and must close it.
 */
public interface SftpChannelSource {

    /**
     * The shared SFTP channel of this source. Never closed by a caller.
     *
     * @throws IllegalStateException when the source is not connected
     */
    SftpClient primaryClient();

    /**
     * Opens a further SFTP channel on the same SSH session. The caller closes the result; a server
     * that refuses more channels makes this throw.
     */
    SftpClient openChannel() throws IOException;

    /** Whether the session and the primary channel are both still open. */
    boolean isOpen();

    /**
     * Whether the source owns its SSH session (a standalone SFTP login) rather than borrowing one
     * from a terminal pane, whose session it must never close.
     */
    boolean ownsSession();

    /** A short label for logs, such as the connection's display name; never a secret. */
    String describe();
}
