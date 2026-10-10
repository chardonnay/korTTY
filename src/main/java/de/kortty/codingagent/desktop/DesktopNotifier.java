package de.kortty.codingagent.desktop;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

/**
 * Desktop notifications for settled coding-agent transitions. {@link #notify} may be called from
 * any thread (the bridge calls it on the JavaFX thread): it truncates the body, checks support and
 * hands the work to the {@code kortty-desktop-notifier} daemon executor — nothing is joined by the
 * caller. An unsupported backend is logged once at debug level; a backend that fails twice in a
 * row is logged once and then disabled for the session.
 *
 * <p>A notification can carry an action for its click ({@link #notify(String, String, Runnable)}).
 * Where the backend reports clicks ({@link DesktopNotifierBackend#supportsActivation()}) the action
 * runs at most once, through the activation dispatcher — the application passes
 * {@code Platform::runLater}, so the action always runs on the JavaFX thread — and never after
 * {@link #close()}.
 *
 * <p>The enterprise policy can forbid desktop notifications altogether
 * ({@code desktop-notifications = "deny"}): the notifier asks its policy gate on every notification
 * and on every support probe, so nothing that goes through it — whoever asks — is shown while the
 * gate says no, and the decision follows a policy reload without a restart.
 */
public final class DesktopNotifier implements AutoCloseable {

    /** Maximum body length handed to the backends; longer bodies end with an ellipsis. */
    public static final int MAX_BODY_CHARS = 200;

    static final String EXECUTOR_THREAD_NAME = "kortty-desktop-notifier";

    private static final Logger logger = LoggerFactory.getLogger(DesktopNotifier.class);
    private static final int FAILURES_BEFORE_DISABLING = 2;

    private final DesktopNotifierBackend backend;
    private final ExecutorService executor;
    private final Executor activationDispatcher;
    private final BooleanSupplier policyAllows;
    private final AtomicBoolean policyLogged = new AtomicBoolean();
    private final AtomicBoolean unsupportedLogged = new AtomicBoolean();
    private final AtomicBoolean failureLogged = new AtomicBoolean();
    private final AtomicInteger consecutiveFailures = new AtomicInteger();
    private volatile boolean disabled;
    private volatile boolean closed;

    /** Creates the notifier for the running platform with its own daemon executor. */
    public static DesktopNotifier createDefault(PlatformProbe probe) {
        return createDefault(probe, Runnable::run);
    }

    /**
     * Creates the notifier for the running platform; a click on a notification runs its action
     * through {@code activationDispatcher} ({@code Platform::runLater} in the application).
     */
    public static DesktopNotifier createDefault(PlatformProbe probe, Executor activationDispatcher) {
        return createDefault(probe, activationDispatcher, () -> true);
    }

    /**
     * Creates the notifier for the running platform, whose notifications are shown only while
     * {@code policyAllows} says so (the enterprise policy's {@code desktop-notifications}).
     */
    public static DesktopNotifier createDefault(PlatformProbe probe, Executor activationDispatcher,
                                                BooleanSupplier policyAllows) {
        return new DesktopNotifier(DesktopNotifierBackends.createDefault(probe), defaultExecutor(),
            activationDispatcher, policyAllows);
    }

    /**
     * Creates a notifier over {@code backend}; {@code executor} is owned and shut down by
     * {@link #close()}. Click actions run on the thread that reports the click.
     */
    public DesktopNotifier(DesktopNotifierBackend backend, ExecutorService executor) {
        this(backend, executor, Runnable::run);
    }

    /**
     * Creates a notifier over {@code backend} whose click actions run through
     * {@code activationDispatcher}; {@code executor} is owned and shut down by {@link #close()}.
     */
    public DesktopNotifier(DesktopNotifierBackend backend, ExecutorService executor, Executor activationDispatcher) {
        this(backend, executor, activationDispatcher, () -> true);
    }

    /**
     * Creates a notifier over {@code backend} that shows notifications only while
     * {@code policyAllows} says so; {@code executor} is owned and shut down by {@link #close()}.
     */
    public DesktopNotifier(DesktopNotifierBackend backend, ExecutorService executor, Executor activationDispatcher,
                           BooleanSupplier policyAllows) {
        this.backend = Objects.requireNonNull(backend, "backend");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.activationDispatcher = Objects.requireNonNull(activationDispatcher, "activationDispatcher");
        this.policyAllows = Objects.requireNonNull(policyAllows, "policyAllows");
    }

    /** Whether the enterprise policy lets korTTY show desktop notifications; a failing gate says no. */
    public boolean isAllowedByPolicy() {
        try {
            return policyAllows.getAsBoolean();
        } catch (RuntimeException e) {
            logger.debug("Desktop notification policy gate failed", e);
            return false;
        }
    }

    /** The {@code kortty-desktop-notifier} daemon single-thread executor. */
    public static ExecutorService defaultExecutor() {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, EXECUTOR_THREAD_NAME);
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * True while the backend can deliver notifications, has not been disabled by failures and the
     * enterprise policy allows them.
     */
    public boolean isSupported() {
        if (closed || disabled || !isAllowedByPolicy()) {
            return false;
        }
        try {
            return backend.isSupported();
        } catch (Throwable t) {
            logger.debug("Desktop notifier support probe failed", t);
            return false;
        }
    }

    /**
     * Whether a click on a notification reaches korTTY on this platform, so the action passed to
     * {@link #notify(String, String, Runnable)} can run.
     */
    public boolean supportsActivation() {
        if (!isSupported()) {
            return false;
        }
        try {
            return backend.supportsActivation();
        } catch (Throwable t) {
            logger.debug("Desktop notifier activation probe failed", t);
            return false;
        }
    }

    /** Shows a notification asynchronously; {@code body} is truncated to {@link #MAX_BODY_CHARS}. */
    public void notify(String title, String body) {
        notify(title, body, null);
    }

    /**
     * Shows a notification asynchronously whose click runs {@code onActivate} once, through the
     * activation dispatcher, where the backend reports clicks; {@code body} is truncated to
     * {@link #MAX_BODY_CHARS}.
     *
     * @param onActivate what a click does, for example bringing the pane that asked to the front, or
     *     {@code null}
     */
    public void notify(String title, String body, Runnable onActivate) {
        if (closed) {
            return;
        }
        String safeTitle = title == null ? "" : title;
        String safeBody = NotificationCommands.truncate(body, MAX_BODY_CHARS);
        if (!isAllowedByPolicy()) {
            if (policyLogged.compareAndSet(false, true)) {
                logger.debug("desktop notification suppressed (denied by the enterprise policy): {}", safeTitle);
            }
            return;
        }
        if (!isSupported()) {
            if (unsupportedLogged.compareAndSet(false, true)) {
                logger.debug("desktop notification suppressed (unsupported platform): {}", safeTitle);
            }
            return;
        }
        try {
            Runnable activation = onActivate == null ? null : activation(onActivate);
            executor.execute(() -> deliver(safeTitle, safeBody, activation));
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

    /**
     * Wraps {@code onActivate} for the backend: it runs at most once, never after {@link #close()},
     * and always through the activation dispatcher, whatever thread the backend calls it on; a
     * failure is logged and never reaches the backend.
     */
    Runnable activation(Runnable onActivate) {
        AtomicBoolean used = new AtomicBoolean();
        return () -> {
            if (closed || !used.compareAndSet(false, true)) {
                return;
            }
            try {
                activationDispatcher.execute(() -> {
                    try {
                        onActivate.run();
                    } catch (RuntimeException e) {
                        logger.debug("Desktop notification click action failed", e);
                    }
                });
            } catch (RuntimeException e) {
                logger.debug("Desktop notification click dropped: {}", e.toString());
            }
        };
    }

    private void deliver(String title, String body, Runnable onActivate) {
        if (closed || disabled || !isAllowedByPolicy()) {
            return;
        }
        try {
            if (onActivate != null) {
                backend.notify(title, body, onActivate);
            } else {
                backend.notify(title, body);
            }
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
