package de.kortty.ai.runtimeupdate;

import de.kortty.KorTTYApplication;
import de.kortty.ai.llama.LlamaBackend;
import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.llama.LlamaRuntimeManager;
import de.kortty.ai.llama.LlamaRuntimeTrustGuard;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
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
    private final LlamaRuntimeUpdateService.IndexProvider indexProvider;

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
        this(releaseConfiguration, installer, registry, currentVersion, runtimeIsIdle,
            shutdownRuntimeManager, initializeRuntimeManager, activationGate, healthCheck,
            updateOperation, null);
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
        UpdateOperation updateOperation,
        LlamaRuntimeUpdateService.IndexProvider indexProvider
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
        this.indexProvider = indexProvider;
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

    /**
     * Explicit user action: removes the managed runtime completely. The signed revocation denylist
     * and the blocked-active marker survive so a withdrawn package stays blocked after a
     * reinstall. Registered models keep their (now dangling) executable bindings; the next install
     * rebinds them through the regular activation path.
     */
    public synchronized void uninstall() throws IOException {
        // The exclusive scope blocks new leases while the manager is shut down and the packages
        // are removed, exactly like an activation switch.
        try (ActivationScope ignored = activationGate.block()) {
            if (!runtimeIsIdle.getAsBoolean()) {
                throw new LlamaRuntimeBusyException(
                    "The embedded llama.cpp runtime cannot be removed while a local AI request is running.");
            }
            shutdownRuntimeManager.run();
            try {
                installer.uninstallAll();
            } finally {
                initializeRuntimeManager.run();
            }
        }
    }

    /**
     * Explicit user action: installs a locally provided runtime archive, but only when its exact
     * SHA-256 is published by the signed stable index for the current platform, architecture and
     * korTTY build. Installation, health check, idle-only activation, pending-first-launch and
     * registry rebinding are identical to a downloaded update.
     */
    public synchronized LlamaRuntimeUpdateResult installFromLocalPackage(Path archive)
        throws IOException, InterruptedException {
        Objects.requireNonNull(archive, "archive");
        if (!Files.isRegularFile(archive, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The selected llama.cpp runtime archive does not exist.");
        }
        LlamaRuntimeIndex index = fetchVerifiedStableIndex();
        // A verified index always enforces withdrawals first, exactly like the update service.
        Optional<LlamaRuntimeInstallation> activeBefore = installer.active();
        Optional<LlamaRuntimeInstallation> newlyRevokedActive = activeBefore
            .filter(installation -> index.isRevoked(installation.descriptor()));
        if (newlyRevokedActive.isPresent()) {
            blockRevokedRuntime(index, newlyRevokedActive.get());
        } else {
            installer.applyRevocations(index);
        }
        String archiveSha256 = sha256(archive);
        LlamaRuntimePackageDescriptor entry = index.packages().stream()
            .filter(descriptor -> descriptor.sha256().equals(archiveSha256))
            .findFirst()
            .orElseThrow(() -> new IOException(
                "This archive was not published by the signed stable llama.cpp channel and cannot be installed."));
        if (index.isRevoked(entry)) {
            throw new IOException(
                "This llama.cpp runtime package was revoked by the signed stable channel and cannot be installed.");
        }
        LlamaRuntimeSelector selector = new LlamaRuntimeSelector();
        if (!selector.isCompatible(
            index,
            entry,
            LlamaRuntimePlatform.current(),
            LlamaRuntimePackageDescriptor.currentArchitecture(),
            releaseConfiguration.apiContractVersion(),
            currentVersion.get())) {
            throw new IOException(
                "This llama.cpp runtime package is not compatible with this korTTY build, platform or architecture.");
        }
        if (Files.size(archive) != entry.size()) {
            throw new IOException("The archive size does not match its signed package entry.");
        }
        // Same install-and-activate machinery as a download; only the bytes come from the local
        // file, and strictly for the one verified download URI of the matched entry.
        LlamaRuntimePackageInstaller localInstaller = installer.withContentProvider(uri -> {
            if (!entry.downloadUri().equals(uri)) {
                throw new IOException("A local runtime installation must not download remote packages.");
            }
            return Files.newInputStream(archive);
        });
        LlamaRuntimeActivationResult activation = localInstaller.installAndActivate(
            entry, runtimeIsIdle, healthCheck);
        LlamaRuntimeUpdateResult.Status status = switch (activation.status()) {
            case ACTIVATED -> LlamaRuntimeUpdateResult.Status.PENDING_FIRST_LAUNCH;
            case ALREADY_ACTIVE -> installer.pendingActivation().isPresent()
                ? LlamaRuntimeUpdateResult.Status.PENDING_FIRST_LAUNCH
                : LlamaRuntimeUpdateResult.Status.ACTIVATED;
            case STAGED_UNTIL_IDLE -> LlamaRuntimeUpdateResult.Status.STAGED_UNTIL_IDLE;
            case ROLLED_BACK -> LlamaRuntimeUpdateResult.Status.ROLLED_BACK;
        };
        if (status == LlamaRuntimeUpdateResult.Status.ACTIVATED
            || status == LlamaRuntimeUpdateResult.Status.PENDING_FIRST_LAUNCH) {
            activateForRegisteredModels(activation.installation());
        }
        return new LlamaRuntimeUpdateResult(status, entry, activation);
    }

    /** Fetches and Ed25519-verifies the signed stable index using the pinned trust root. */
    LlamaRuntimeIndex fetchVerifiedStableIndex() throws IOException, InterruptedException {
        if (indexProvider != null) {
            return indexProvider.fetch();
        }
        LlamaRuntimeIndexVerifier verifier = new LlamaRuntimeIndexVerifier(
            releaseConfiguration.requireTrustedPublicKey());
        return new LlamaRuntimeIndexClient(
            releaseConfiguration.stableIndexUri(),
            releaseConfiguration.stableSignatureUri(),
            verifier).fetch();
    }

    private static String sha256(Path file) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 is unavailable.", e);
        }
        try (InputStream input = Files.newInputStream(file)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, count);
            }
        }
        return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
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
