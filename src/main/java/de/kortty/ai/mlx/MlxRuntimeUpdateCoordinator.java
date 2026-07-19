package de.kortty.ai.mlx;

import de.kortty.ai.mlx.MlxRuntimeLocator.MlxRuntimeInstallation;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Application-wide asynchronous coordinator for MLX runtime startup checks and explicit
 * provisioning, driven by the shared {@link LlamaRuntimeUpdatePolicy}.
 *
 * <p>Mirrors {@code LlamaRuntimeUpdateCoordinator} but simpler: MLX install is synchronous with a
 * bounded sanity launch, so there is no pending-first-launch polling or staged-until-idle retry
 * scheduling. MLX has a single backend, so {@link #start(LlamaRuntimeUpdatePolicy)} takes no backend
 * parameter.
 */
public final class MlxRuntimeUpdateCoordinator implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(MlxRuntimeUpdateCoordinator.class);
    private static final Object DEFAULT_LOCK = new Object();
    private static volatile MlxRuntimeUpdateCoordinator defaultInstance;

    private final MlxRuntimeProvisioner provisioner;
    private final ExecutorService executor;
    private final CopyOnWriteArrayList<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<Status> status = new AtomicReference<>(
        new Status(State.NOT_STARTED, null, null, null));
    private final AtomicBoolean closed = new AtomicBoolean();

    public static MlxRuntimeUpdateCoordinator getDefault() {
        MlxRuntimeUpdateCoordinator current = defaultInstance;
        if (current != null && !current.closed.get()) {
            return current;
        }
        synchronized (DEFAULT_LOCK) {
            current = defaultInstance;
            if (current == null || current.closed.get()) {
                current = new MlxRuntimeUpdateCoordinator(MlxRuntimeProvisioner.createDefault());
                defaultInstance = current;
            }
            return current;
        }
    }

    public static void shutdownDefault() {
        synchronized (DEFAULT_LOCK) {
            if (defaultInstance != null) {
                defaultInstance.close();
                defaultInstance = null;
            }
        }
    }

    public MlxRuntimeUpdateCoordinator(MlxRuntimeProvisioner provisioner) {
        this(provisioner, newExecutor());
    }

    MlxRuntimeUpdateCoordinator(MlxRuntimeProvisioner provisioner, ExecutorService executor) {
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Runs the configured startup policy. OFF is completed locally without network access. */
    public CompletableFuture<Status> start(LlamaRuntimeUpdatePolicy policy) {
        return submit(policy, false);
    }

    /** Explicit user request; NOTIFY/OFF do not suppress this stable installation. */
    public CompletableFuture<Status> installStable() {
        return submit(LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE, true);
    }

    public Optional<MlxRuntimeInstallation> activeInstallation() {
        try {
            return provisioner.activeInstallation();
        } catch (IOException e) {
            logger.warn("Could not read the active MLX runtime installation", e);
            publish(new Status(State.FAILED, message(e), null, null));
            return Optional.empty();
        }
    }

    public Status status() {
        return status.get();
    }

    public AutoCloseable addListener(Consumer<Status> listener) {
        Consumer<Status> required = Objects.requireNonNull(listener, "listener");
        listeners.add(required);
        required.accept(status.get());
        return () -> listeners.remove(required);
    }

    private CompletableFuture<Status> submit(LlamaRuntimeUpdatePolicy policy, boolean explicitInstall) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(
                new IllegalStateException("MLX runtime update coordinator is closed."));
        }
        LlamaRuntimeUpdatePolicy effective = policy != null ? policy : LlamaRuntimeUpdatePolicy.NOTIFY;
        publish(new Status(
            explicitInstall || effective == LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE
                ? State.INSTALLING : State.CHECKING,
            null, null, activeInstallation().orElse(null)));
        return CompletableFuture.supplyAsync(() -> run(effective), executor);
    }

    private Status run(LlamaRuntimeUpdatePolicy policy) {
        try {
            MlxRuntimeUpdateResult result = provisioner.checkAndMaybeApply(policy);
            Optional<MlxRuntimeInstallation> active = provisioner.activeInstallation();
            Status next = switch (result.status()) {
                case DISABLED -> new Status(State.DISABLED, null, null, active.orElse(null));
                case CURRENT -> new Status(
                    active.isPresent() ? State.READY : State.CURRENT,
                    null, result.availablePackage(), active.orElse(null));
                case UPDATE_AVAILABLE -> new Status(
                    State.UPDATE_AVAILABLE, null, result.availablePackage(), active.orElse(null));
                case REVOKED -> new Status(
                    State.REVOKED, null, result.availablePackage(), active.orElse(null),
                    result.revokedRuntimeId());
                case ACTIVATED -> new Status(
                    State.READY, null, result.availablePackage(), active.orElse(null));
            };
            publish(next);
            return next;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("MLX runtime update or installation was interrupted", e);
            Status failed = failureStatus("MLX runtime update was interrupted.");
            publish(failed);
            return failed;
        } catch (Exception e) {
            logger.error("MLX runtime update or installation failed", e);
            Status failed = failureStatus(message(e));
            publish(failed);
            return failed;
        }
    }

    private Status failureStatus(String detail) {
        try {
            Optional<String> revokedRuntime = provisioner.blockedActiveRuntimeId();
            if (revokedRuntime.isPresent()) {
                return new Status(State.REVOKED, detail, null, null, revokedRuntime.get());
            }
        } catch (IOException blockedStateFailure) {
            logger.warn("Could not read the blocked MLX runtime state", blockedStateFailure);
            detail = detail + " (Could not read the blocked runtime state: "
                + message(blockedStateFailure) + ")";
        }
        return new Status(State.FAILED, detail, null, activeInstallation().orElse(null));
    }

    private void publish(Status next) {
        status.set(next);
        for (Consumer<Status> listener : listeners) {
            try {
                listener.accept(next);
            } catch (RuntimeException ignored) {
                // A stale UI listener must not stop provisioning or other status listeners.
            }
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        executor.shutdownNow();
        listeners.clear();
        status.set(new Status(State.CLOSED, null, null, null));
    }

    private static ExecutorService newExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "kortty-mlx-runtime-update");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadExecutor(factory);
    }

    private static String message(Throwable error) {
        String value = error.getMessage();
        return value != null && !value.isBlank() ? value : error.getClass().getSimpleName();
    }

    public enum State {
        NOT_STARTED,
        DISABLED,
        CHECKING,
        CURRENT,
        UPDATE_AVAILABLE,
        REVOKED,
        INSTALLING,
        READY,
        FAILED,
        CLOSED
    }

    public record Status(
        State state,
        String detail,
        MlxRuntimePackageDescriptor availablePackage,
        MlxRuntimeInstallation activeInstallation,
        String revokedRuntimeId
    ) {
        public Status(
            State state,
            String detail,
            MlxRuntimePackageDescriptor availablePackage,
            MlxRuntimeInstallation activeInstallation
        ) {
            this(state, detail, availablePackage, activeInstallation, null);
        }

        public Status {
            Objects.requireNonNull(state, "state");
        }
    }
}
