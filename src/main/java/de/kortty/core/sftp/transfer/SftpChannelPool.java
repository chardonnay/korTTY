package de.kortty.core.sftp.transfer;

import de.kortty.core.sftp.SftpChannelSource;
import org.apache.sshd.sftp.client.SftpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Lends each transfer worker its own SFTP channel on the source's session.
 *
 * <p>Up to {@code ownedCapacity} channels are opened with {@link SftpChannelSource#openChannel()}.
 * When the server refuses one (OpenSSH {@code MaxSessions}, a proxy that allows only one SFTP
 * subsystem), the capacity shrinks to what is open, a warning is logged once, and the shared primary
 * channel serves as one more lease. With no channel of its own at all, transfers therefore run one at
 * a time on the primary channel, where cancelling is cooperative only.
 *
 * <p>The pool closes only the channels it opened: never the primary client, never the session.
 */
final class SftpChannelPool implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(SftpChannelPool.class);

    /** A channel lent to one worker. */
    record Lease(SftpClient client, boolean owned) {
        Lease {
            Objects.requireNonNull(client, "client");
        }

        boolean usable() {
            return client.isOpen();
        }
    }

    private final SftpChannelSource source;
    private final Set<SftpClient> opened = new HashSet<>();
    private int ownedCapacity;
    private int reserved;
    private boolean primaryLeased;
    private boolean closed;
    private boolean shrinkLogged;

    SftpChannelPool(SftpChannelSource source, int ownedCapacity) {
        this.source = Objects.requireNonNull(source, "source");
        this.ownedCapacity = Math.max(0, ownedCapacity);
    }

    SftpChannelSource source() {
        return source;
    }

    /** How many workers can hold a lease at once: the own channels plus the primary one. */
    synchronized int leaseCapacity() {
        return ownedCapacity + 1;
    }

    /** How many own channels the pool may still open; shrinks when the server refuses one. */
    synchronized int ownedCapacity() {
        return ownedCapacity;
    }

    /**
     * A channel for one worker, or {@code null} when none is left (every lease is taken, the pool is
     * closed or the source is gone). Opening a channel is network I/O: never call this on the FX
     * thread.
     */
    Lease acquire() {
        synchronized (this) {
            if (closed || !source.isOpen()) {
                return null;
            }
            if (opened.size() + reserved >= ownedCapacity) {
                return leasePrimary();
            }
            reserved++;
        }
        SftpClient channel = null;
        Exception failure = null;
        try {
            channel = source.openChannel();
        } catch (IOException | RuntimeException e) {
            failure = e;
        }
        synchronized (this) {
            reserved--;
            if (channel != null) {
                if (closed) {
                    closeQuietly(channel);
                    return null;
                }
                opened.add(channel);
                return new Lease(channel, true);
            }
            if (closed || !source.isOpen()) {
                return null;
            }
            ownedCapacity = opened.size() + reserved;
            if (!shrinkLogged) {
                shrinkLogged = true;
                logger.warn("The server refused another SFTP channel on {}; continuing with {} transfer channel(s): {}",
                    source.describe(), ownedCapacity + 1, failure == null ? "" : failure.toString());
            }
            return leasePrimary();
        }
    }

    private Lease leasePrimary() {
        if (primaryLeased) {
            return null;
        }
        SftpClient primary;
        try {
            primary = source.primaryClient();
        } catch (RuntimeException e) {
            return null;
        }
        if (primary == null || !primary.isOpen()) {
            return null;
        }
        primaryLeased = true;
        return new Lease(primary, false);
    }

    /** Gives {@code lease} back. An own channel is closed (it costs the server a session slot). */
    void release(Lease lease) {
        if (lease == null) {
            return;
        }
        if (!lease.owned()) {
            synchronized (this) {
                primaryLeased = false;
            }
            return;
        }
        synchronized (this) {
            opened.remove(lease.client());
        }
        closeQuietly(lease.client());
    }

    /** Closes the channels this pool opened; the primary channel and the session stay open. */
    @Override
    public void close() {
        List<SftpClient> toClose;
        synchronized (this) {
            closed = true;
            toClose = List.copyOf(opened);
            opened.clear();
        }
        toClose.forEach(SftpChannelPool::closeQuietly);
    }

    private static void closeQuietly(SftpClient channel) {
        try {
            channel.close();
        } catch (IOException | RuntimeException e) {
            logger.debug("Closing a transfer channel failed: {}", e.toString());
        }
    }
}
