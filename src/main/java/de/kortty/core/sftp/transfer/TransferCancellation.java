package de.kortty.core.sftp.transfer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Cancels one transfer from any thread.
 *
 * <p>The copier checks the flag after every buffer. A worker that copies over a channel it opened
 * itself registers that channel with {@link #attachOwnedChannel(Closeable)}; cancelling then also
 * closes it, which unblocks a read or write waiting on the server. The shared primary channel is
 * never attached, so cancelling a transfer on it stays cooperative.
 */
public final class TransferCancellation {

    private static final Logger logger = LoggerFactory.getLogger(TransferCancellation.class);

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicReference<Closeable> ownedChannel = new AtomicReference<>();

    /** A fresh, not yet cancelled token. */
    public static TransferCancellation create() {
        return new TransferCancellation();
    }

    /** Marks the transfer cancelled and closes an attached owned channel. Idempotent. */
    public void cancel() {
        if (cancelled.compareAndSet(false, true)) {
            closeQuietly(ownedChannel.getAndSet(null));
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /** Throws {@link TransferCancelledException} once {@link #cancel()} was called. */
    public void throwIfCancelled() throws TransferCancelledException {
        if (cancelled.get()) {
            throw new TransferCancelledException();
        }
    }

    /**
     * Registers a channel the worker owns, to be closed on cancel. When the token is already
     * cancelled the channel is closed at once.
     */
    public void attachOwnedChannel(Closeable channel) {
        if (channel == null) {
            return;
        }
        ownedChannel.set(channel);
        if (cancelled.get()) {
            closeQuietly(ownedChannel.getAndSet(null));
        }
    }

    /** Forgets {@code channel} again (when the worker closes or returns it itself). */
    public void detachOwnedChannel(Closeable channel) {
        if (channel != null) {
            ownedChannel.compareAndSet(channel, null);
        }
    }

    private static void closeQuietly(Closeable channel) {
        if (channel == null) {
            return;
        }
        try {
            channel.close();
        } catch (IOException | RuntimeException e) {
            logger.debug("Closing the cancelled transfer's channel failed: {}", e.toString());
        }
    }
}
