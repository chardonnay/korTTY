package de.kortty.core;

import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Cooperative, sticky cancellation for AI requests that run on a worker thread.
 *
 * <p>A thread interrupt alone is not a reliable stop signal: the JDK's {@code HttpClient.send}
 * clears the interrupt flag when it throws, and any {@code catch (Exception)} retry in a workflow
 * would then happily send the next request. A {@link Handle} stays cancelled once cancelled, so
 * every layer can ask "was this run stopped?" no matter what happened to the flag.
 *
 * <p>The caller binds a handle to the worker thread for the duration of the run
 * ({@link #runBound}); the provider layer finds it through {@link #current()} without any
 * signature change. Providers register what must be torn down on a stop ({@link #onCancel}): the
 * HTTP response stream is closed and a CLI process tree is killed, so a blocked read or
 * {@code waitFor} ends at once. Without a bound handle everything here is a no-op, which keeps
 * every non-snippet caller's behaviour exactly as before.
 */
public final class AiCancellation {

    private static final Logger logger = LoggerFactory.getLogger(AiCancellation.class);
    private static final ThreadLocal<Handle> CURRENT = new ThreadLocal<>();
    private static final Registration NO_REGISTRATION = () -> { };

    private AiCancellation() {
    }

    /** Removes a cancel hook again; closing it twice is harmless. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable {
        @Override
        void close();
    }

    /** One run's stop switch. Thread-safe; {@link #cancel()} may be called from any thread. */
    public static final class Handle {
        private final Object lock = new Object();
        private final List<Runnable> hooks = new CopyOnWriteArrayList<>();
        private volatile boolean cancelled;
        private Thread boundThread;

        public boolean isCancelled() {
            return cancelled;
        }

        /**
         * Stops the run: marks the handle, runs every registered hook (closing streams, killing
         * processes) and interrupts the bound thread. Only the first call has an effect.
         */
        public void cancel() {
            Thread toInterrupt;
            synchronized (lock) {
                if (cancelled) {
                    return;
                }
                cancelled = true;
                toInterrupt = boundThread;
            }
            for (Runnable hook : hooks) {
                runHook(hook);
            }
            hooks.clear();
            if (toInterrupt != null) {
                toInterrupt.interrupt();
            }
        }

        /** Registers {@code hook} to run on {@link #cancel()}; runs it at once if already cancelled. */
        public Registration onCancel(Runnable hook) {
            Objects.requireNonNull(hook, "hook");
            hooks.add(hook);
            if (cancelled && hooks.remove(hook)) {
                runHook(hook);
                return NO_REGISTRATION;
            }
            return () -> hooks.remove(hook);
        }

        private void bind(Thread thread) {
            synchronized (lock) {
                boundThread = thread;
            }
        }

        private void unbind() {
            synchronized (lock) {
                boundThread = null;
            }
        }

        private static void runHook(Runnable hook) {
            try {
                hook.run();
            } catch (RuntimeException e) {
                logger.debug("AI cancel hook failed", e);
            }
        }
    }

    public static Handle newHandle() {
        return new Handle();
    }

    /** The handle bound to the calling thread, or {@code null}. */
    public static Handle current() {
        return CURRENT.get();
    }

    /** Runs {@code body} on the calling thread with {@code handle} bound to it. */
    public static void runBound(Handle handle, Runnable body) {
        try {
            callBound(handle, () -> {
                body.run();
                return null;
            });
        } catch (RuntimeException | Error e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** Calls {@code body} on the calling thread with {@code handle} bound to it. */
    public static <T> T callBound(Handle handle, Callable<T> body) throws Exception {
        Objects.requireNonNull(body, "body");
        if (handle == null) {
            return body.call();
        }
        Handle previous = CURRENT.get();
        CURRENT.set(handle);
        handle.bind(Thread.currentThread());
        try {
            return body.call();
        } finally {
            handle.unbind();
            if (previous != null) {
                CURRENT.set(previous);
            } else {
                CURRENT.remove();
            }
        }
    }

    /** Whether the run on the calling thread was stopped (false without a bound handle). */
    public static boolean isCancelled() {
        Handle handle = CURRENT.get();
        return handle != null && handle.isCancelled();
    }

    /** Throws {@link AiCancelledException} when the run on the calling thread was stopped. */
    public static void throwIfCancelled() {
        if (isCancelled()) {
            throw new AiCancelledException("AI request was stopped.", null);
        }
    }

    /** Registers a hook on the calling thread's handle; a no-op registration without one. */
    public static Registration onCancel(Runnable hook) {
        Handle handle = CURRENT.get();
        return handle != null ? handle.onCancel(hook) : NO_REGISTRATION;
    }

    /**
     * Whether {@code failure} is (or was caused by) a stop rather than a real error: an
     * {@link AiCancelledException}, a {@link CancellationException}, an {@link InterruptedException}
     * or an interrupted read. A socket timeout is an {@link InterruptedIOException} too, but it is a
     * failure, not a stop.
     */
    public static boolean isCancellation(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 16) {
            if (current instanceof CancellationException || current instanceof InterruptedException) {
                return true;
            }
            if (current instanceof InterruptedIOException && !(current instanceof SocketTimeoutException)) {
                return true;
            }
            Throwable cause = current.getCause();
            if (cause == current) {
                break;
            }
            current = cause;
        }
        return false;
    }
}
