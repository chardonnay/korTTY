package de.kortty.ai.mlx;

import de.kortty.ai.mlx.MlxRuntimeLocator.MlxRuntimeInstallation;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Product-level MLX runtime provisioning driven by the shared {@link LlamaRuntimeUpdatePolicy}
 * (OFF/NOTIFY/AUTOMATIC_STABLE), so a single update policy governs both embedded runtimes.
 *
 * <p>Mirrors {@code LlamaRuntimeProvisioner}: signed-index selection, immediate enforcement of a
 * verified withdrawal of the active package, and idle-gated automatic installation. MLX install is
 * synchronous (extract + bounded sanity launch), so there is no pending-first-launch or staged
 * scheduling — a busy manager during an automatic install defers to {@code CURRENT}.
 */
public final class MlxRuntimeProvisioner {

    private final MlxRuntimePackageInstaller installer;
    private final MlxRuntimePackageInstaller.IndexProvider indexProvider;
    private final Supplier<MlxRuntimeManager> manager;
    private final BooleanSupplier runtimeIsIdle;
    private final BooleanSupplier platformSupported;

    public static MlxRuntimeProvisioner createDefault() {
        return new MlxRuntimeProvisioner(
            MlxRuntimePackageInstaller.createDefault(),
            defaultIndexProvider(),
            MlxRuntimeManager::getDefault,
            MlxRuntimeManager::isDefaultIdle,
            MlxPlatform::isSupported);
    }

    MlxRuntimeProvisioner(
        MlxRuntimePackageInstaller installer,
        MlxRuntimePackageInstaller.IndexProvider indexProvider,
        Supplier<MlxRuntimeManager> manager,
        BooleanSupplier runtimeIsIdle,
        BooleanSupplier platformSupported
    ) {
        this.installer = Objects.requireNonNull(installer, "installer");
        this.indexProvider = Objects.requireNonNull(indexProvider, "indexProvider");
        this.manager = Objects.requireNonNull(manager, "manager");
        this.runtimeIsIdle = Objects.requireNonNull(runtimeIsIdle, "runtimeIsIdle");
        this.platformSupported = Objects.requireNonNull(platformSupported, "platformSupported");
    }

    public Optional<MlxRuntimeInstallation> activeInstallation() throws IOException {
        return installer.active();
    }

    public Optional<String> blockedActiveRuntimeId() throws IOException {
        return installer.blockedActiveRuntimeId();
    }

    /**
     * Evaluates the update policy. OFF and an unsupported platform never create an index client and
     * therefore perform no network I/O; a verified withdrawal of the active package is enforced
     * before any candidate is offered, exactly like the llama.cpp provisioner.
     */
    public synchronized MlxRuntimeUpdateResult checkAndMaybeApply(LlamaRuntimeUpdatePolicy policy)
        throws IOException, InterruptedException {
        LlamaRuntimeUpdatePolicy effective = policy != null ? policy : LlamaRuntimeUpdatePolicy.NOTIFY;
        if (!platformSupported.getAsBoolean()) {
            return new MlxRuntimeUpdateResult(MlxRuntimeUpdateResult.Status.DISABLED, null);
        }
        if (effective == LlamaRuntimeUpdatePolicy.OFF) {
            Optional<String> blocked = installer.blockedActiveRuntimeId();
            return blocked
                .map(id -> new MlxRuntimeUpdateResult(MlxRuntimeUpdateResult.Status.REVOKED, null, id))
                .orElseGet(() -> new MlxRuntimeUpdateResult(MlxRuntimeUpdateResult.Status.DISABLED, null));
        }

        MlxRuntimeIndex index = indexProvider.fetch();
        Optional<MlxRuntimeInstallation> activeBefore = installer.active();
        Optional<MlxRuntimePackageDescriptor> activeEntry = activeBefore
            .flatMap(active -> index.packages().stream()
                .filter(descriptor -> descriptor.installationId().equals(active.id()))
                .findFirst());
        boolean activeRevoked = activeBefore.isPresent()
            && (index.revokedRuntimeIds().contains(activeBefore.get().id())
                || activeEntry.map(index::isRevoked).orElse(false));
        if (activeRevoked) {
            String revokedRuntimeId = activeEntry
                .map(MlxRuntimePackageDescriptor::runtimeId)
                .orElseGet(() -> activeBefore.get().id());
            installer.blockRevokedActive(manager.get(), index, activeBefore.get());
            return new MlxRuntimeUpdateResult(MlxRuntimeUpdateResult.Status.REVOKED, null, revokedRuntimeId);
        }

        // Withdrawals also block later reinstallation of any package the index has revoked.
        installer.applyRevocations(index);
        String revokedRuntimeId = installer.blockedActiveRuntimeId().orElse(null);

        Optional<MlxRuntimePackageDescriptor> selected = selectNewest(index);
        Optional<MlxRuntimeInstallation> active = installer.active();
        if (selected.isEmpty()) {
            return new MlxRuntimeUpdateResult(
                revokedRuntimeId != null
                    ? MlxRuntimeUpdateResult.Status.REVOKED
                    : MlxRuntimeUpdateResult.Status.CURRENT,
                null,
                revokedRuntimeId);
        }
        MlxRuntimePackageDescriptor candidate = selected.get();
        if (active.isPresent() && active.get().id().equals(candidate.installationId())) {
            return new MlxRuntimeUpdateResult(
                revokedRuntimeId != null
                    ? MlxRuntimeUpdateResult.Status.REVOKED
                    : MlxRuntimeUpdateResult.Status.CURRENT,
                null,
                revokedRuntimeId);
        }
        if (active.isPresent() && !isNewerThanActive(candidate, active.get(), index)) {
            return new MlxRuntimeUpdateResult(MlxRuntimeUpdateResult.Status.CURRENT, null, revokedRuntimeId);
        }
        if (effective == LlamaRuntimeUpdatePolicy.NOTIFY) {
            return new MlxRuntimeUpdateResult(
                revokedRuntimeId != null
                    ? MlxRuntimeUpdateResult.Status.REVOKED
                    : MlxRuntimeUpdateResult.Status.UPDATE_AVAILABLE,
                candidate,
                revokedRuntimeId);
        }
        // AUTOMATIC_STABLE: install synchronously, but never interrupt an in-flight local request.
        if (!runtimeIsIdle.getAsBoolean()) {
            return new MlxRuntimeUpdateResult(MlxRuntimeUpdateResult.Status.CURRENT, candidate, revokedRuntimeId);
        }
        try {
            installer.installFromIndex(manager.get());
        } catch (MlxRuntimePackageInstaller.MlxRuntimeBusyException busy) {
            // A local request started between the idle check and the switch; defer without failing.
            return new MlxRuntimeUpdateResult(MlxRuntimeUpdateResult.Status.CURRENT, candidate, revokedRuntimeId);
        }
        return new MlxRuntimeUpdateResult(MlxRuntimeUpdateResult.Status.ACTIVATED, candidate, revokedRuntimeId);
    }

    /** Explicit user action: forces an idle-gated install of the newest compatible stable package. */
    public synchronized MlxRuntimeUpdateResult installStable() throws IOException, InterruptedException {
        return checkAndMaybeApply(LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE);
    }

    private Optional<MlxRuntimePackageDescriptor> selectNewest(MlxRuntimeIndex index) {
        // Identical selection to MlxRuntimePackageInstaller.installFromIndex so what is offered here
        // is exactly what the install path would activate.
        return index.packages().stream()
            .filter(MlxRuntimePackageDescriptor::matchesCurrentPlatform)
            .filter(descriptor -> !index.isRevoked(descriptor))
            .max(Comparator.comparingLong(MlxRuntimePackageInstaller::versionSortKey)
                .thenComparing(MlxRuntimePackageDescriptor::runtimeId));
    }

    private static boolean isNewerThanActive(
        MlxRuntimePackageDescriptor candidate,
        MlxRuntimeInstallation active,
        MlxRuntimeIndex index
    ) {
        // The active installation carries no on-disk version, so it is resolved through the signed
        // index by installation id. An active build the index no longer lists is treated as older.
        Optional<MlxRuntimePackageDescriptor> activeDescriptor = index.packages().stream()
            .filter(descriptor -> descriptor.installationId().equals(active.id()))
            .findFirst();
        return activeDescriptor.isEmpty()
            || MlxRuntimePackageInstaller.versionSortKey(candidate)
                > MlxRuntimePackageInstaller.versionSortKey(activeDescriptor.get());
    }

    private static MlxRuntimePackageInstaller.IndexProvider defaultIndexProvider() {
        return () -> {
            MlxRuntimeReleaseConfiguration configuration = MlxRuntimeReleaseConfiguration.loadDefault();
            return new MlxRuntimeIndexClient(
                configuration.stableIndexUri(),
                configuration.stableSignatureUri(),
                configuration.requireTrustedPublicKey()).fetch();
        };
    }
}
