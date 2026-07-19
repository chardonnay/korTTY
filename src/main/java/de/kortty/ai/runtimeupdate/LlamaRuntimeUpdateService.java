package de.kortty.ai.runtimeupdate;

import de.kortty.ai.llama.LlamaBackend;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.io.IOException;
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
        if (revokedRuntimeId == null) {
            revokedRuntimeId = installer.blockedActiveRuntimeId().orElse(null);
        }
        Optional<LlamaRuntimePackageDescriptor> selected = selector.select(
            index,
            LlamaRuntimePlatform.current(),
            LlamaRuntimePackageDescriptor.currentArchitecture(),
            backend != null ? backend : LlamaBackend.AUTO,
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
            boolean pendingFirstLaunch = installer.pendingActivation()
                .map(value -> value.installation().descriptor().installationId())
                .filter(candidate.installationId()::equals)
                .isPresent();
            return new LlamaRuntimeUpdateResult(
                pendingFirstLaunch
                    ? LlamaRuntimeUpdateResult.Status.PENDING_FIRST_LAUNCH
                    : LlamaRuntimeUpdateResult.Status.CURRENT,
                pendingFirstLaunch ? candidate : null,
                null,
                revokedRuntimeId);
        }
        if (active.isPresent()
            && !index.isRevoked(active.get().descriptor())
            && !selector.isNewer(candidate, active.get().descriptor())) {
            return new LlamaRuntimeUpdateResult(
                LlamaRuntimeUpdateResult.Status.CURRENT, null, null);
        }
        if (effectivePolicy == LlamaRuntimeUpdatePolicy.NOTIFY) {
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
