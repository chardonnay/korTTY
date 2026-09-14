package de.kortty.ai.mlx;

import de.kortty.ai.mlx.MlxRuntimeLocator.MlxRuntimeInstallation;
import de.kortty.model.LlamaRuntimeUpdatePolicy;
import java.io.IOException;
import java.util.List;
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
        List<MlxRuntimePackageDescriptor> selectable = installer.selectableVersions(index);
        Optional<MlxRuntimeInstallation> active = installer.active();

        // A pinned version keeps an installed runtime where it is. A pin the index no longer offers
        // (revoked, or gone) is dropped so it cannot keep the user off a safe runtime.
        Optional<String> pinned = installer.pinnedRuntimeId();
        if (pinned.isPresent()) {
            Optional<MlxRuntimePackageDescriptor> pinnedPackage = selectable.stream()
                .filter(descriptor -> descriptor.runtimeId().equals(pinned.get()))
                .findFirst();
            if (pinnedPackage.isEmpty()) {
                installer.unpinRuntime();
            } else if (active.isPresent()) {
                return new MlxRuntimeUpdateResult(
                    revokedRuntimeId != null
                        ? MlxRuntimeUpdateResult.Status.REVOKED
                        : MlxRuntimeUpdateResult.Status.CURRENT,
                    null,
                    revokedRuntimeId);
            } else {
                return offerOrInstall(effective, pinnedPackage.get(), revokedRuntimeId);
            }
        }

        Optional<MlxRuntimePackageDescriptor> selected = selectable.stream().findFirst();
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
        return offerOrInstall(effective, candidate, revokedRuntimeId);
    }

    private MlxRuntimeUpdateResult offerOrInstall(
        LlamaRuntimeUpdatePolicy policy,
        MlxRuntimePackageDescriptor candidate,
        String revokedRuntimeId
    ) throws IOException, InterruptedException {
        if (policy == LlamaRuntimeUpdatePolicy.NOTIFY) {
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
            installer.installFromIndex(manager.get(), candidate.runtimeId());
        } catch (MlxRuntimePackageInstaller.MlxRuntimeBusyException busy) {
            // A local request started between the idle check and the switch; defer without failing.
            return new MlxRuntimeUpdateResult(MlxRuntimeUpdateResult.Status.CURRENT, candidate, revokedRuntimeId);
        }
        return new MlxRuntimeUpdateResult(MlxRuntimeUpdateResult.Status.ACTIVATED, candidate, revokedRuntimeId);
    }

    /**
     * Explicit user action: forces an idle-gated install of the newest compatible stable package.
     * Asking for the newest runtime ends a pinned version.
     */
    public synchronized MlxRuntimeUpdateResult installStable() throws IOException, InterruptedException {
        installer.unpinRuntime();
        return checkAndMaybeApply(LlamaRuntimeUpdatePolicy.AUTOMATIC_STABLE);
    }

    /**
     * Explicit user action: switches to one specific MLX runtime version from the signed index — the
     * way back to an older version when a newer one causes problems. Choosing the newest version
     * follows stable updates again; any other version is pinned.
     */
    public synchronized MlxRuntimeUpdateResult installVersion(String runtimeId)
        throws IOException, InterruptedException {
        Objects.requireNonNull(runtimeId, "runtimeId");
        if (!platformSupported.getAsBoolean()) {
            throw new IOException("The embedded MLX runtime is available only on Apple-Silicon macOS.");
        }
        MlxRuntimeIndex index = indexProvider.fetch();
        installer.applyRevocations(index);
        List<MlxRuntimePackageDescriptor> selectable = installer.selectableVersions(index);
        MlxRuntimePackageDescriptor candidate = selectable.stream()
            .filter(descriptor -> descriptor.runtimeId().equals(runtimeId))
            .findFirst()
            .orElseThrow(() -> new IOException(
                "The MLX runtime " + runtimeId + " is not offered for this Mac, or it was revoked."));
        boolean alreadyActive = installer.active()
            .map(active -> active.id().equals(candidate.installationId()))
            .orElse(false);
        if (!alreadyActive) {
            if (!runtimeIsIdle.getAsBoolean()) {
                throw new MlxRuntimePackageInstaller.MlxRuntimeBusyException(
                    "The MLX runtime cannot be switched while a local AI request is running.");
            }
            installer.installFromIndex(manager.get(), runtimeId);
        }
        if (selectable.get(0).runtimeId().equals(runtimeId)) {
            installer.unpinRuntime();
        } else {
            installer.pinRuntime(runtimeId);
        }
        return new MlxRuntimeUpdateResult(
            alreadyActive ? MlxRuntimeUpdateResult.Status.CURRENT : MlxRuntimeUpdateResult.Status.ACTIVATED,
            candidate,
            installer.blockedActiveRuntimeId().orElse(null));
    }

    /** MLX runtime versions this Mac may switch to, newest first. */
    public List<MlxRuntimePackageDescriptor> availableVersions() throws IOException, InterruptedException {
        if (!platformSupported.getAsBoolean()) {
            return List.of();
        }
        MlxRuntimeIndex index = indexProvider.fetch();
        installer.applyRevocations(index);
        return installer.selectableVersions(index);
    }

    public Optional<String> pinnedRuntimeId() throws IOException {
        return installer.pinnedRuntimeId();
    }

    /** Ends a pinned version; the next update check follows the stable channel again. */
    public synchronized void unpin() throws IOException {
        installer.unpinRuntime();
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
        return () -> MlxRuntimeReleaseConfiguration.loadDefault().fetchStableIndex();
    }
}
