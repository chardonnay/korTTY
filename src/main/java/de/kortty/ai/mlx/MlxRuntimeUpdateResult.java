package de.kortty.ai.mlx;

/**
 * Outcome of one MLX runtime update evaluation. MLX installation is synchronous with a bounded
 * sanity launch, so there is no pending-first-launch or staged-until-idle state: a busy manager
 * during an automatic install is surfaced as {@link Status#CURRENT} (deferred) rather than a staged
 * result.
 */
public record MlxRuntimeUpdateResult(
    Status status,
    MlxRuntimePackageDescriptor availablePackage,
    String revokedRuntimeId
) {
    public MlxRuntimeUpdateResult(Status status, MlxRuntimePackageDescriptor availablePackage) {
        this(status, availablePackage, null);
    }

    public enum Status {
        DISABLED,
        CURRENT,
        UPDATE_AVAILABLE,
        REVOKED,
        ACTIVATED
    }
}
