package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.io.IOException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/** Coordinates signed-index selection with OFF/NOTIFY/AUTOMATIC_STABLE policy semantics. */
public final class LlamaRuntimeUpdateService {

    @FunctionalInterface
    interface IndexProvider {
        LlamaRuntimeIndex fetch() throws IOException, InterruptedException;
    }

    @FunctionalInterface
    interface RevokedRuntimeHandler {
        void block(LlamaRuntimeIndex index, LlamaRuntimeInstallation installation) throws IOException;
    }

    private final IndexProvider indexProvider;
    private final LlamaRuntimeSelector selector;
    private final LlamaRuntimePackageInstaller installer;
    private final Supplier<String> currentVersion;
    private final int supportedApiContractVersion;
    private final RevokedRuntimeHandler revokedRuntimeHandler;

    public LlamaRuntimeUpdateService(
        LlamaRuntimeIndexClient indexClient,
        LlamaRuntimePackageInstaller installer,
        Supplier<String> currentVersion,
        int supportedApiContractVersion
    ) {
        this(indexClient, new LlamaRuntimeSelector(), installer, currentVersion,
            supportedApiContractVersion, (index, installation) -> installer.applyRevocations(index));
    }

    public LlamaRuntimeUpdateService(
        LlamaRuntimeIndexClient indexClient,
        LlamaRuntimeSelector selector,
        LlamaRuntimePackageInstaller installer,
        Supplier<String> currentVersion,
        int supportedApiContractVersion
    ) {
        this(indexClient, selector, installer, currentVersion, supportedApiContractVersion,
            (index, installation) -> installer.applyRevocations(index));
    }

    LlamaRuntimeUpdateService(
        LlamaRuntimeIndexClient indexClient,
        LlamaRuntimeSelector selector,
        LlamaRuntimePackageInstaller installer,
        Supplier<String> currentVersion,
        int supportedApiContractVersion,
        RevokedRuntimeHandler revokedRuntimeHandler
    ) {
        this(Objects.requireNonNull(indexClient, "indexClient")::fetch, selector, installer,
            currentVersion, supportedApiContractVersion, revokedRuntimeHandler);
    }

    LlamaRuntimeUpdateService(
        IndexProvider indexProvider,
        LlamaRuntimeSelector selector,
        LlamaRuntimePackageInstaller installer,
        Supplier<String> currentVersion,
        int supportedApiContractVersion,
        RevokedRuntimeHandler revokedRuntimeHandler
    ) {
        this.indexProvider = Objects.requireNonNull(indexProvider, "indexProvider");
        this.selector = Objects.requireNonNull(selector, "selector");
        this.installer = Objects.requireNonNull(installer, "installer");
        this.currentVersion = Objects.requireNonNull(currentVersion, "currentVersion");
        this.revokedRuntimeHandler = Objects.requireNonNull(revokedRuntimeHandler, "revokedRuntimeHandler");
        if (supportedApiContractVersion < 1) {
            throw new IllegalArgumentException("Supported runtime API contract must be positive.");
        }
        this.supportedApiContractVersion = supportedApiContractVersion;
    }

    /**
     * OFF performs no network I/O. NOTIFY never downloads an update, but a verified security
     * withdrawal is persisted and enforced immediately so a revoked active runtime cannot run.
     *
     * <p>A pinned runtime version keeps an installed runtime where it is: the check still enforces
     * withdrawals, but never switches versions. A pin the index no longer offers — revoked or no
     * longer compatible — is dropped so it cannot keep the user off a safe runtime.
     */
    public LlamaRuntimeUpdateResult checkAndMaybeApply(
        LlamaRuntimeUpdatePolicy policy,
        LlamaBackend backend,
        BooleanSupplier runtimeIsIdle,
        LlamaRuntimePackageInstaller.RuntimeHealthCheck healthCheck
    ) throws IOException, InterruptedException {
        LlamaRuntimeUpdatePolicy effectivePolicy = policy != null ? policy : LlamaRuntimeUpdatePolicy.NOTIFY;
        if (effectivePolicy == LlamaRuntimeUpdatePolicy.OFF) {
            return new LlamaRuntimeUpdateResult(
                LlamaRuntimeUpdateResult.Status.DISABLED, null, null);
        }
        LlamaRuntimeIndex index = indexProvider.fetch();
        String revokedRuntimeId = enforceRevocations(index);
        LlamaBackend requestedBackend = backend != null ? backend : LlamaBackend.AUTO;
        Optional<String> pinned = installer.pinnedRuntimeId();
        if (pinned.isPresent()) {
            Optional<LlamaRuntimePackageDescriptor> pinnedPackage =
                selectVersion(index, requestedBackend, pinned.get());
            if (pinnedPackage.isEmpty()) {
                installer.unpinRuntime();
            } else if (installer.active().isPresent()) {
                return currentOrPending(null, revokedRuntimeId);
            } else {
                return offerOrInstall(effectivePolicy, pinnedPackage.get(), runtimeIsIdle, healthCheck,
                    revokedRuntimeId);
            }
        }
        Optional<LlamaRuntimePackageDescriptor> selected = selector.select(
            index,
            LlamaRuntimePlatform.current(),
            LlamaRuntimePackageDescriptor.currentArchitecture(),
            requestedBackend,
            supportedApiContractVersion,
            currentVersion.get());
        if (selected.isEmpty()) {
            return new LlamaRuntimeUpdateResult(
                revokedRuntimeId != null
                    ? LlamaRuntimeUpdateResult.Status.REVOKED
                    : LlamaRuntimeUpdateResult.Status.CURRENT,
                null, null, revokedRuntimeId);
        }
        LlamaRuntimePackageDescriptor candidate = selected.get();
        Optional<LlamaRuntimeInstallation> active = installer.active();
        if (active.map(value -> value.descriptor().installationId())
            .filter(candidate.installationId()::equals).isPresent()) {
            return currentOrPending(candidate, revokedRuntimeId);
        }
        if (active.isPresent()
            && !index.isRevoked(active.get().descriptor())
            && !selector.isNewer(candidate, active.get().descriptor())) {
            return new LlamaRuntimeUpdateResult(
                LlamaRuntimeUpdateResult.Status.CURRENT, null, null);
        }
        return offerOrInstall(effectivePolicy, candidate, runtimeIsIdle, healthCheck, revokedRuntimeId);
    }

    /**
     * Explicit user action: installs one specific runtime version from the signed index, older or
     * newer than the active one. Choosing the newest version follows stable updates again; any
     * other version is pinned so background checks do not immediately upgrade it again.
     */
    public LlamaRuntimeUpdateResult installVersion(
        String runtimeId,
        LlamaBackend backend,
        BooleanSupplier runtimeIsIdle,
        LlamaRuntimePackageInstaller.RuntimeHealthCheck healthCheck
    ) throws IOException, InterruptedException {
        Objects.requireNonNull(runtimeId, "runtimeId");
        LlamaRuntimeIndex index = indexProvider.fetch();
        String revokedRuntimeId = enforceRevocations(index);
        LlamaBackend requestedBackend = backend != null ? backend : LlamaBackend.AUTO;
        LlamaRuntimePackageDescriptor candidate = selectVersion(index, requestedBackend, runtimeId)
            .orElseThrow(() -> new IOException("The llama.cpp runtime " + runtimeId
                + " is not offered for this platform and korTTY build, or it was revoked."));
        boolean newest = selectableVersions(index, requestedBackend).stream()
            .findFirst()
            .map(descriptor -> descriptor.runtimeId().equals(runtimeId))
            .orElse(false);
        LlamaRuntimeUpdateResult result;
        if (installer.active().map(value -> value.descriptor().installationId())
            .filter(candidate.installationId()::equals).isPresent()) {
            result = currentOrPending(candidate, revokedRuntimeId);
        } else {
            result = offerOrInstall(LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE, candidate, runtimeIsIdle,
                healthCheck, revokedRuntimeId);
        }
        if (result.status() != LlamaRuntimeUpdateResult.Status.ROLLED_BACK) {
            if (newest) {
                installer.unpinRuntime();
            } else {
                installer.pinRuntime(runtimeId);
            }
        }
        return result;
    }

    /** Every runtime version the user may switch to on this platform and build, newest first. */
    public List<LlamaRuntimePackageDescriptor> availableVersions(LlamaBackend backend)
        throws IOException, InterruptedException {
        LlamaRuntimeIndex index = indexProvider.fetch();
        enforceRevocations(index);
        return selectableVersions(index, backend != null ? backend : LlamaBackend.AUTO);
    }

    private String enforceRevocations(LlamaRuntimeIndex index) throws IOException {
        Optional<LlamaRuntimeInstallation> activeBeforeRevocations = installer.active();
        Optional<LlamaRuntimeInstallation> newlyRevokedActive = activeBeforeRevocations
            .filter(installation -> index.isRevoked(installation.descriptor()));
        if (newlyRevokedActive.isPresent()) {
            revokedRuntimeHandler.block(index, newlyRevokedActive.get());
        } else {
            // Withdrawals also remove unsafe rollback candidates and prevent later reinstallation.
            installer.applyRevocations(index);
        }
        String revokedRuntimeId = newlyRevokedActive
            .map(installation -> installation.descriptor().runtimeId())
            .orElse(null);
        return revokedRuntimeId != null ? revokedRuntimeId : installer.blockedActiveRuntimeId().orElse(null);
    }

    private Optional<LlamaRuntimePackageDescriptor> selectVersion(
        LlamaRuntimeIndex index,
        LlamaBackend backend,
        String runtimeId
    ) {
        return selector.selectVersion(
            index,
            LlamaRuntimePlatform.current(),
            LlamaRuntimePackageDescriptor.currentArchitecture(),
            backend,
            supportedApiContractVersion,
            currentVersion.get(),
            runtimeId);
    }

    private List<LlamaRuntimePackageDescriptor> selectableVersions(LlamaRuntimeIndex index, LlamaBackend backend) {
        return selector.selectableVersions(
            index,
            LlamaRuntimePlatform.current(),
            LlamaRuntimePackageDescriptor.currentArchitecture(),
            backend,
            supportedApiContractVersion,
            currentVersion.get());
    }

    /** The active runtime stays; reports a still pending first launch so the coordinator keeps polling. */
    private LlamaRuntimeUpdateResult currentOrPending(
        LlamaRuntimePackageDescriptor expected,
        String revokedRuntimeId
    ) throws IOException {
        Optional<LlamaRuntimePackageDescriptor> pending = installer.pendingActivation()
            .map(value -> value.installation().descriptor())
            .filter(descriptor -> expected == null
                || descriptor.installationId().equals(expected.installationId()));
        return new LlamaRuntimeUpdateResult(
            pending.isPresent()
                ? LlamaRuntimeUpdateResult.Status.PENDING_FIRST_LAUNCH
                : LlamaRuntimeUpdateResult.Status.CURRENT,
            pending.orElse(null),
            null,
            revokedRuntimeId);
    }

    private LlamaRuntimeUpdateResult offerOrInstall(
        LlamaRuntimeUpdatePolicy policy,
        LlamaRuntimePackageDescriptor candidate,
        BooleanSupplier runtimeIsIdle,
        LlamaRuntimePackageInstaller.RuntimeHealthCheck healthCheck,
        String revokedRuntimeId
    ) throws IOException, InterruptedException {
        if (policy == LlamaRuntimeUpdatePolicy.NOTIFY) {
            return new LlamaRuntimeUpdateResult(
                revokedRuntimeId != null
                    ? LlamaRuntimeUpdateResult.Status.REVOKED
                    : LlamaRuntimeUpdateResult.Status.UPDATE_AVAILABLE,
                candidate, null, revokedRuntimeId);
        }
        LlamaRuntimeActivationResult activation = installer.installAndActivate(
            candidate,
            Objects.requireNonNull(runtimeIsIdle, "runtimeIsIdle"),
            Objects.requireNonNull(healthCheck, "healthCheck"));
        LlamaRuntimeUpdateResult.Status status = switch (activation.status()) {
            case ACTIVATED -> LlamaRuntimeUpdateResult.Status.PENDING_FIRST_LAUNCH;
            case ALREADY_ACTIVE -> installer.pendingActivation().isPresent()
                ? LlamaRuntimeUpdateResult.Status.PENDING_FIRST_LAUNCH
                : LlamaRuntimeUpdateResult.Status.ACTIVATED;
            case STAGED_UNTIL_IDLE -> LlamaRuntimeUpdateResult.Status.STAGED_UNTIL_IDLE;
            case ROLLED_BACK -> LlamaRuntimeUpdateResult.Status.ROLLED_BACK;
        };
        return new LlamaRuntimeUpdateResult(status, candidate, activation, revokedRuntimeId);
    }
}
