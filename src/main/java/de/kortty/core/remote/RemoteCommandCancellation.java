package de.kortty.core.remote;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A one-way cancel switch for a remote command. {@link #cancel()} flips the flag once and runs the
 * registered callbacks, which {@link RemoteCommandRunner} uses to close the exec channel at once.
 */
public final class RemoteCommandCancellation {

    private static final Logger logger = LoggerFactory.getLogger(RemoteCommandCancellation.class);

    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final List<Runnable> callbacks = new CopyOnWriteArrayList<>();

    /** Cancels; only the first call runs the callbacks. */
    public void cancel() {
        if (!cancelled.compareAndSet(false, true)) {
            return;
        }
        for (Runnable callback : callbacks) {
            runQuietly(callback);
        }
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    /**
     * Registers a callback that runs on {@link #cancel()}, or right away when already cancelled.
     *
     * @return a handle that removes the callback again
     */
    public AutoCloseable onCancel(Runnable callback) {
        callbacks.add(callback);
        if (cancelled.get() && callbacks.remove(callback)) {
            runQuietly(callback);
        }
        return () -> callbacks.remove(callback);
    }

    private static void runQuietly(Runnable callback) {
        try {
            callback.run();
        } catch (RuntimeException e) {
            logger.debug("Cancel callback failed: {}", e.getClass().getSimpleName());
        }
    }
}
