package de.kortty.ai.runtimeupdate;

import de.kortty.KorTTYApplication;
import de.kortty.ai.llama.LlamaBackend;
import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.llama.LlamaRuntimeManager;
import de.kortty.ai.llama.LlamaRuntimeTrustGuard;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Product-level runtime provisioning: signed release lookup, verified installation, health check,
 * idle-only activation and atomic model-registry rebinding.
 */
public final class LlamaRuntimeProvisioner {

    private final LlamaRuntimeReleaseConfiguration releaseConfiguration;
    private final LlamaRuntimePackageInstaller installer;
    private final LlamaModelRegistry registry;
    private final Supplier<String> currentVersion;
    private final BooleanSupplier runtimeIsIdle;
    private final Runnable shutdownRuntimeManager;
    private final Runnable initializeRuntimeManager;
    private final ActivationGate activationGate;
    private final LlamaRuntimePackageInstaller.RuntimeHealthCheck healthCheck;
    private final UpdateOperation updateOperation;

    public static LlamaRuntimeProvisioner createDefault() {
        Path llamaDirectory = KorTTYApplication.getConfigDirectory().resolve("llm");
        return new LlamaRuntimeProvisioner(
            LlamaRuntimeReleaseConfiguration.loadDefault(),
            new LlamaRuntimePackageInstaller(llamaDirectory.resolve("runtime")),
            LlamaModelRegistry.inDirectory(llamaDirectory),
            KorTTYApplication::getAppVersion,
            LlamaRuntimeManager::isDefaultIdle,
            LlamaRuntimeManager::shutdownDefault,
            LlamaRuntimeManager::getDefault,
            () -> {
                LlamaRuntimeManager.ActivationGuard guard =
                    LlamaRuntimeManager.blockDefaultLeasesForActivation();
                return guard::close;
            },
            new LlamaRuntimeVersionHealthCheck(),
            null);
    }

    LlamaRuntimeProvisioner(
        LlamaRuntimeReleaseConfiguration releaseConfiguration,
        LlamaRuntimePackageInstaller installer,
        LlamaModelRegistry registry,
        Supplier<String> currentVersion,
        BooleanSupplier runtimeIsIdle,
        Runnable shutdownRuntimeManager,
        Runnable initializeRuntimeManager,
        LlamaRuntimePackageInstaller.RuntimeHealthCheck healthCheck
    ) {
        this(releaseConfiguration, installer, registry, currentVersion, runtimeIsIdle,
            shutdownRuntimeManager, initializeRuntimeManager, () -> () -> { }, healthCheck, null);
    }

    LlamaRuntimeProvisioner(
        LlamaRuntimeReleaseConfiguration releaseConfiguration,
        LlamaRuntimePackageInstaller installer,
        LlamaModelRegistry registry,
        Supplier<String> currentVersion,
        BooleanSupplier runtimeIsIdle,
        Runnable shutdownRuntimeManager,
        Runnable initializeRuntimeManager,
        LlamaRuntimePackageInstaller.RuntimeHealthCheck healthCheck,
        UpdateOperation updateOperation
    ) {
        this(releaseConfiguration, installer, registry, currentVersion, runtimeIsIdle,
            shutdownRuntimeManager, initializeRuntimeManager, () -> () -> { }, healthCheck,
            updateOperation);
    }

    LlamaRuntimeProvisioner(
        LlamaRuntimeReleaseConfiguration releaseConfiguration,
        LlamaRuntimePackageInstaller installer,
        LlamaModelRegistry registry,
        Supplier<String> currentVersion,
        BooleanSupplier runtimeIsIdle,
        Runnable shutdownRuntimeManager,
        Runnable initializeRuntimeManager,
        ActivationGate activationGate,
        LlamaRuntimePackageInstaller.RuntimeHealthCheck healthCheck,
        UpdateOperation updateOperation
    ) {
        this.releaseConfiguration = Objects.requireNonNull(releaseConfiguration, "releaseConfiguration");
        this.installer = Objects.requireNonNull(installer, "installer");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.runtimeIsIdle = Objects.requireNonNull(runtimeIsIdle, "runtimeIsIdle");
        this.shutdownRuntimeManager = Objects.requireNonNull(shutdownRuntimeManager, "shutdownRuntimeManager");
        this.initializeRuntimeManager = Objects.requireNonNull(initializeRuntimeManager, "initializeRuntimeManager");
        this.activationGate = Objects.requireNonNull(activationGate, "activationGate");
        this.healthCheck = Objects.requireNonNull(healthCheck, "healthCheck");
        this.updateOperation = updateOperation;
    }

    public Optional<LlamaRuntimeInstallation> activeInstallation() throws IOException {
        return installer.active();
    }

    public Optional<String> blockedActiveRuntimeId() throws IOException {
        return installer.blockedActiveRuntimeId();
    }

    public Optional<LlamaRuntimePackageInstaller.PendingActivation> pendingActivation() throws IOException {
        return installer.pendingActivation();
    }

    /** OFF never creates an index client and therefore performs no network I/O. */
    public synchronized LlamaRuntimeUpdateResult checkAndMaybeApply(
        LlamaRuntimeUpdatePolicy policy,
        LlamaBackend backend
    ) throws IOException, InterruptedException {
        LlamaRuntimeUpdatePolicy effective = policy != null ? policy : LlamaRuntimeUpdatePolicy.NOTIFY;
        if (effective == LlamaRuntimeUpdatePolicy.OFF) {
            reconcileActiveRuntimeIfIdle();
            Optional<String> blockedRuntime = installer.blockedActiveRuntimeId();
            if (blockedRuntime.isPresent()) {
                return new LlamaRuntimeUpdateResult(
                    LlamaRuntimeUpdateResult.Status.REVOKED,
                    null,
                    null,
                    blockedRuntime.get());
            }
            Optional<LlamaRuntimePackageInstaller.PendingActivation> pending = installer.pendingActivation();
            if (pending.isPresent()) {
                return new LlamaRuntimeUpdateResult(
                    LlamaRuntimeUpdateResult.Status.PENDING_FIRST_LAUNCH,
                    pending.get().installation().descriptor(),
                    null);
            }
            return new LlamaRuntimeUpdateResult(LlamaRuntimeUpdateResult.Status.DISABLED, null, null);
        }
        LlamaBackend effectiveBackend = effectiveBackend(
            backend != null ? backend : LlamaBackend.AUTO,
            installer.active());
        LlamaRuntimeUpdateResult result = updateOperation != null
            ? updateOperation.check(effective, effectiveBackend, runtimeIsIdle, healthCheck)
            : updateService().checkAndMaybeApply(effective, effectiveBackend, runtimeIsIdle, healthCheck);
        if ((result.status() == LlamaRuntimeUpdateResult.Status.ACTIVATED
                || result.status() == LlamaRuntimeUpdateResult.Status.PENDING_FIRST_LAUNCH)
            && result.activation() != null) {
            activateForRegisteredModels(result.activation().installation());
        } else if (result.status() == LlamaRuntimeUpdateResult.Status.CURRENT
            || result.status() == LlamaRuntimeUpdateResult.Status.PENDING_FIRST_LAUNCH) {
            reconcileActiveRuntimeIfIdle();
        }
        return result;
    }

    /** Explicit user action: installs/activates the latest compatible stable runtime. */
    public synchronized LlamaRuntimeInstallation installStable(LlamaBackend backend)
        throws IOException, InterruptedException {
        LlamaRuntimeUpdateResult result = checkAndMaybeApply(
            LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE,
            backend != null ? backend : LlamaBackend.AUTO);
        if (result.status() == LlamaRuntimeUpdateResult.Status.STAGED_UNTIL_IDLE) {
            throw new LlamaRuntimeBusyException("The runtime is staged and will activate after all local AI requests finish.");
        }
        if (result.status() == LlamaRuntimeUpdateResult.Status.ROLLED_BACK) {
            throw new IOException("The new llama.cpp runtime failed its health check and was rolled back.");
        }
        return installer.active().orElseThrow(() -> new IOException(
            "The signed stable index contains no compatible llama.cpp runtime for this platform and backend."));
    }

    public synchronized Optional<LlamaRuntimeInstallation> reconcileActiveRuntimeIfIdle() throws IOException {
        Optional<LlamaRuntimeInstallation> active = installer.active();
        if (active.isEmpty() || !runtimeIsIdle.getAsBoolean()) {
            return active;
        }
        // The AI Manager owns a separate registry instance. Reload before every read-modify-write
        // so a runtime update cannot erase models installed after this provisioner was created.
        registry.reload();
        Path executable = active.get().executable().toAbsolutePath().normalize();
        boolean needsRebind = registry.list().stream()
            .anyMatch(model -> !model.getServerExecutable().toAbsolutePath().normalize().equals(executable));
        if (needsRebind) {
            activateForRegisteredModels(active.get());
        }
        return active;
    }

    private LlamaRuntimeUpdateService updateService() throws IOException {
        LlamaRuntimeIndexVerifier verifier = new LlamaRuntimeIndexVerifier(
            releaseConfiguration.requireTrustedPublicKey());
        LlamaRuntimeIndexClient client = new LlamaRuntimeIndexClient(
            releaseConfiguration.stableIndexUri(),
            releaseConfiguration.stableSignatureUri(),
            verifier);
        return new LlamaRuntimeUpdateService(
            client,
            new LlamaRuntimeSelector(),
            installer,
            currentVersion,
            releaseConfiguration.apiContractVersion(),
            this::blockRevokedRuntime);
    }

    static LlamaBackend effectiveBackend(
        LlamaBackend requested,
        Optional<LlamaRuntimeInstallation> active
    ) {
        LlamaBackend normalized = requested != null ? requested : LlamaBackend.AUTO;
        if (normalized != LlamaBackend.AUTO) {
            return normalized;
        }
        return active != null && active.isPresent()
            ? active.get().descriptor().backend()
            : LlamaBackend.AUTO;
    }

    private void activateForRegisteredModels(LlamaRuntimeInstallation installation) throws IOException {
        // The exclusive scope waits for an in-flight generation to drain and blocks every new
        // lease until shutdown, registry rebinding, and manager recreation have all completed.
        try (ActivationScope ignored = activationGate.block()) {
            if (!runtimeIsIdle.getAsBoolean()) {
                rollbackAfterActivationFailure();
                throw new LlamaRuntimeBusyException(
                    "llama.cpp runtime activation was deferred because a local AI request started.");
            }
            shutdownRuntimeManager.run();
            Map<String, Path> previousBindings = Map.of();
            try {
                // The registry captures the latest cross-instance state while holding its JVM and file
                // locks; this closes the race between an AI Manager install and the runtime rebind.
                previousBindings = registry.replaceServerExecutableForAll(installation.executable());
                initializeRuntimeManager.run();
            } catch (RuntimeException activationFailure) {
                try {
                    registry.restoreServerExecutables(previousBindings, installation.executable());
                    rollbackAfterActivationFailure();
                } catch (Exception rollbackFailure) {
                    activationFailure.addSuppressed(rollbackFailure);
                } finally {
                    try {
                        initializeRuntimeManager.run();
                    } catch (RuntimeException restartFailure) {
                        activationFailure.addSuppressed(restartFailure);
                    }
                }
                throw new IOException(
                    "Could not activate the verified llama.cpp runtime; previous state restored.",
                    activationFailure);
            }
        }
    }

    /**
     * Fail closed as soon as the signed index withdraws the active package. The durable denylist
     * and package marker block new leases first; then running sidecars are stopped and stale model
     * bindings are replaced by the non-executable marker until a healthy replacement activates.
     */
    void blockRevokedRuntime(
        LlamaRuntimeIndex index,
        LlamaRuntimeInstallation revokedInstallation
    ) throws IOException {
        IOException failure = null;
        try {
            installer.applyRevocations(index);
        } catch (IOException e) {
            failure = e;
        }
        try {
            shutdownRuntimeManager.run();
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = new IOException("Could not stop the revoked llama.cpp runtime.", e);
            } else {
                failure.addSuppressed(e);
            }
        }
        try {
            registry.reload();
            registry.replaceServerExecutable(
                revokedInstallation.executable(),
                revokedInstallation.directory().resolve(LlamaRuntimeTrustGuard.REVOCATION_MARKER_FILE));
        } catch (RuntimeException e) {
            if (failure == null) {
                failure = new IOException("Could not quarantine local models bound to the revoked runtime.", e);
            } else {
                failure.addSuppressed(e);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void rollbackAfterActivationFailure() throws IOException {
        installer.rollbackAfterFailedLaunch();
    }

    /** Signals a safe, retryable activation delay without weakening signature or health checks. */
    public static final class LlamaRuntimeBusyException extends IOException {
        public LlamaRuntimeBusyException(String message) {
            super(message);
        }
    }

    @FunctionalInterface
    interface UpdateOperation {
        LlamaRuntimeUpdateResult check(
            LlamaRuntimeUpdatePolicy policy,
            LlamaBackend backend,
            BooleanSupplier runtimeIsIdle,
            LlamaRuntimePackageInstaller.RuntimeHealthCheck healthCheck
        ) throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface ActivationGate {
        ActivationScope block();
    }

    @FunctionalInterface
    interface ActivationScope extends AutoCloseable {
        @Override
        void close();
    }
}
