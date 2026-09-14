package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Desktop notifications for settled coding-agent transitions. {@link #notify} may be called from
 * any thread (the bridge calls it on the JavaFX thread): it truncates the body, checks support and
 * hands the work to the {@code kortty-desktop-notifier} daemon executor — nothing is joined by the
 * caller. An unsupported backend is logged once at debug level; a backend that fails twice in a
 * row is logged once and then disabled for the session.
 */
public final class DesktopNotifier implements AutoCloseable {

    /** Maximum body length handed to the backends; longer bodies end with an ellipsis. */
    public static final int MAX_BODY_CHARS = 200;

    static final String EXECUTOR_THREAD_NAME = "kortty-desktop-notifier";

    private static final Logger logger = LoggerFactory.getLogger(DesktopNotifier.class);
    private static final int FAILURES_BEFORE_DISABLING = 2;

    private final DesktopNotifierBackend backend;
    private final ExecutorService executor;
    private final AtomicBoolean unsupportedLogged = new AtomicBoolean();
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile boolean disabled;
    private volatile boolean closed;

    /** Creates the notifier for the running platform with its own daemon executor. */
    public static DesktopNotifier createDefault(PlatformProbe probe) {
        return new DesktopNotifier(DesktopNotifierBackends.createDefault(probe), defaultExecutor());
    }

    /**
     * Creates a notifier over {@code backend}; {@code executor} is owned and shut down by
     * {@link #close()}.
     */
    public DesktopNotifier(DesktopNotifierBackend backend, ExecutorService executor) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** The {@code kortty-desktop-notifier} daemon single-thread executor. */
    public static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, EXECUTOR_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    /** True while the backend can deliver notifications and has not been disabled by failures. */
    public boolean isSupported() {
        if (closed || disabled) {
            return false;
        }
        try {
            return backend.isSupported();
        } catch (Throwable t) {
            logger.debug("Desktop notifier support probe failed", t);
            return false;
        }
    }

    /** Shows a notification asynchronously; {@code body} is truncated to {@link #MAX_BODY_CHARS}. */
    public void notify(String title, String body) {
        if (closed) {
            return;
        }
        String safeTitle = title == null ? "" : title;
        String safeBody = NotificationCommands.truncate(body, MAX_BODY_CHARS);
        if (!isSupported()) {
            if (unsupportedLogged.compareAndSet(false, true)) {
                logger.debug("desktop notification suppressed (unsupported platform): {}", safeTitle);
            }
            return;
        }
        try {
            executor.execute(() -> deliver(safeTitle, safeBody));
        } catch (RejectedExecutionException e) {
            logger.debug("desktop notification dropped: notifier executor is shut down");
        }
    }

    /** Releases the backend best-effort and shuts the executor down without waiting. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        try {
            backend.close();
        } catch (Throwable t) {
            logger.debug("Could not close the desktop notifier backend", t);
        }
        executor.shutdownNow();
    }

    private void deliver(String title, String body) {
        if (closed || disabled) {
            return;
        }
        try {
            backend.notify(title, body);
            consecutiveFailures.set(0);
        } catch (Throwable t) {
            int failures = consecutiveFailures.incrementAndGet();
            if (failureLogged.compareAndSet(false, true)) {
                logger.warn("Desktop notification failed", t);
            } else {
                logger.debug("Desktop notification failed ({} in a row)", failures, t);
            }
            if (failures >= FAILURES_BEFORE_DISABLING) {
                disabled = true;
                logger.debug("Desktop notifications disabled for this session after {} failures", failures);
            }
        }
    }
}
