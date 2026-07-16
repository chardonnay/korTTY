package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.io.IOException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Application-wide asynchronous coordinator for startup checks and explicit provisioning. */
public final class LlamaRuntimeUpdateCoordinator implements AutoCloseable {

    private static final Logger logger = LoggerFactory.getLogger(LlamaRuntimeUpdateCoordinator.class);
    private static final Object DEFAULT_LOCK = new Object();
    private static volatile LlamaRuntimeUpdateCoordinator defaultInstance;
    private static final long IDLE_RETRY_SECONDS = 15L;
    private static final long PENDING_FIRST_LAUNCH_POLL_SECONDS = 2L;

    private final LlamaRuntimeProvisioner provisioner;
    private final ScheduledExecutorService executor;
    private final CopyOnWriteArrayList<Consumer<Status>> listeners = new CopyOnWriteArrayList<>();
    private final AtomicReference<Status> status = new AtomicReference<>(
        new Status(State.NOT_STARTED, null, null, null));
    private final AtomicBoolean closed = new AtomicBoolean();

    public static LlamaRuntimeUpdateCoordinator getDefault() {
        LlamaRuntimeUpdateCoordinator current = defaultInstance;
        if (current != null && !current.closed.get()) {
            return current;
        }
        synchronized (DEFAULT_LOCK) {
            current = defaultInstance;
            if (current == null || current.closed.get()) {
                current = new LlamaRuntimeUpdateCoordinator(LlamaRuntimeProvisioner.createDefault());
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

    public LlamaRuntimeUpdateCoordinator(LlamaRuntimeProvisioner provisioner) {
        this(provisioner, newExecutor());
    }

    LlamaRuntimeUpdateCoordinator(
        LlamaRuntimeProvisioner provisioner,
        ScheduledExecutorService executor
    ) {
        this.provisioner = Objects.requireNonNull(provisioner, "provisioner");
        this.executor = Objects.requireNonNull(executor, "executor");
    }

    /** Runs the configured startup policy. OFF is completed locally without network access. */
    public CompletableFuture<Status> start(LlamaRuntimeUpdatePolicy policy, LlamaBackend backend) {
        return submit(policy, backend, false);
    }

    /** Explicit user request; NOTIFY/OFF do not suppress this stable installation. */
    public CompletableFuture<Status> installStable(LlamaBackend backend) {
        return submit(LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE, backend, true);
    }

    public Optional<LlamaRuntimeInstallation> activeInstallation() {
        try {
            return provisioner.activeInstallation();
        } catch (IOException e) {
            logger.warn("Could not read the active llama.cpp runtime installation", e);
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

    private CompletableFuture<Status> submit(
        LlamaRuntimeUpdatePolicy policy,
        LlamaBackend backend,
        boolean explicitInstall
    ) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("Runtime update coordinator is closed."));
        }
        LlamaRuntimeUpdatePolicy effective = policy != null ? policy : LlamaRuntimeUpdatePolicy.NOTIFY;
        LlamaBackend effectiveBackend = backend != null ? backend : LlamaBackend.AUTO;
        publish(new Status(
            explicitInstall || effective == LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE
                ? State.INSTALLING : State.CHECKING,
            null, null, activeInstallation().orElse(null)));
        return CompletableFuture.supplyAsync(() -> run(effective, effectiveBackend), executor);
    }

    private Status run(LlamaRuntimeUpdatePolicy policy, LlamaBackend backend) {
        try {
            LlamaRuntimeUpdateResult result = provisioner.checkAndMaybeApply(policy, backend);
            Optional<LlamaRuntimeInstallation> active = provisioner.activeInstallation();
            Status next = switch (result.status()) {
                case DISABLED -> new Status(State.DISABLED, null, null, active.orElse(null));
                case CURRENT -> new Status(
                    active.isPresent() ? State.READY : State.CURRENT,
                    null, null, active.orElse(null));
                case UPDATE_AVAILABLE -> new Status(
                    State.UPDATE_AVAILABLE, null, result.availablePackage(), active.orElse(null));
                case REVOKED -> new Status(
                    State.REVOKED, null, result.availablePackage(), active.orElse(null),
                    result.revokedRuntimeId());
                case ACTIVATED -> new Status(
                    State.READY, null, result.availablePackage(), active.orElse(null));
                case PENDING_FIRST_LAUNCH -> new Status(
                    State.PENDING_FIRST_LAUNCH, null, result.availablePackage(), active.orElse(null));
                case STAGED_UNTIL_IDLE -> new Status(
                    State.STAGED_UNTIL_IDLE, null, result.availablePackage(), active.orElse(null));
                case ROLLED_BACK -> {
                    if (result.revokedRuntimeId() != null && active.isEmpty()) {
                        yield new Status(
                            State.REVOKED,
                            "The verified replacement failed its health check; local AI remains blocked.",
                            null,
                            null,
                            result.revokedRuntimeId());
                    }
                    yield new Status(
                        State.ROLLED_BACK,
                        active.isPresent()
                            ? "The runtime failed its health check; a non-revoked previous version remains active."
                            : "The replacement failed its health check; no local runtime remains active.",
                        result.availablePackage(), active.orElse(null));
                }
            };
            publish(next);
            if (next.state() == State.STAGED_UNTIL_IDLE && !closed.get()) {
                executor.schedule(
                    () -> run(LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE, backend),
                    IDLE_RETRY_SECONDS,
                    TimeUnit.SECONDS);
            }
            if (next.state() == State.PENDING_FIRST_LAUNCH && !closed.get()) {
                schedulePendingFirstLaunchPoll();
            }
            return next;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.warn("llama.cpp runtime update or installation was interrupted", e);
            Status failed = failureStatus("Runtime update was interrupted.");
            publish(failed);
            return failed;
        } catch (Exception e) {
            logger.error("llama.cpp runtime update or installation failed", e);
            Status failed = failureStatus(message(e));
            publish(failed);
            return failed;
        }
    }

    private void schedulePendingFirstLaunchPoll() {
        executor.schedule(this::pollPendingFirstLaunch,
            PENDING_FIRST_LAUNCH_POLL_SECONDS, TimeUnit.SECONDS);
    }

    private void pollPendingFirstLaunch() {
        if (closed.get() || status.get().state() != State.PENDING_FIRST_LAUNCH) {
            return;
        }
        try {
            if (provisioner.pendingActivation().isPresent()) {
                schedulePendingFirstLaunchPoll();
                return;
            }
            Status pendingStatus = status.get();
            Optional<LlamaRuntimeInstallation> active = provisioner.activeInstallation();
            boolean promoted = active.isPresent() && pendingStatus.availablePackage() != null
                && active.get().descriptor().installationId()
                    .equals(pendingStatus.availablePackage().installationId());
            publish(promoted
                ? new Status(State.READY, null, pendingStatus.availablePackage(), active.get())
                : new Status(
                    State.ROLLED_BACK,
                    active.isPresent()
                        ? "The pending runtime failed its first real model/API start; a previous runtime was restored."
                        : "The pending runtime failed its first real model/API start; no previous runtime was available.",
                    pendingStatus.availablePackage(),
                    active.orElse(null)));
        } catch (Exception e) {
            logger.error("Polling pending llama.cpp runtime activation failed", e);
            publish(failureStatus(message(e)));
        }
    }

    private Status failureStatus(String detail) {
        try {
            Optional<String> revokedRuntime = provisioner.blockedActiveRuntimeId();
            if (revokedRuntime.isPresent()) {
                return new Status(State.REVOKED, detail, null, null, revokedRuntime.get());
            }
        } catch (IOException blockedStateFailure) {
            logger.warn("Could not read the quarantined llama.cpp runtime state", blockedStateFailure);
            detail = detail + " (Could not read the quarantined runtime state: "
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

    private static ScheduledExecutorService newExecutor() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "kortty-llama-runtime-update");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(factory);
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
        PENDING_FIRST_LAUNCH,
        STAGED_UNTIL_IDLE,
        ROLLED_BACK,
        FAILED,
        CLOSED
    }

    public record Status(
        State state,
        String detail,
        LlamaRuntimePackageDescriptor availablePackage,
        LlamaRuntimeInstallation activeInstallation,
        String revokedRuntimeId
    ) {
        public Status(
            State state,
            String detail,
            LlamaRuntimePackageDescriptor availablePackage,
            LlamaRuntimeInstallation activeInstallation
        ) {
            this(state, detail, availablePackage, activeInstallation, null);
        }

        public Status {
            Objects.requireNonNull(state, "state");
        }
    }
}
