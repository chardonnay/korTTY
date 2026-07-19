package de.kortty.ai.runtimeupdate;

public record LlamaRuntimeUpdateResult(
    Status status,
    LlamaRuntimePackageDescriptor availablePackage,
    LlamaRuntimeActivationResult activation,
    String revokedRuntimeId
) {
    public LlamaRuntimeUpdateResult(
        Status status,
        LlamaRuntimePackageDescriptor availablePackage,
        LlamaRuntimeActivationResult activation
    ) {
        this(status, availablePackage, activation, null);
    }

    public enum Status {
        DISABLED,
        CURRENT,
        UPDATE_AVAILABLE,
        REVOKED,
        ACTIVATED,
        PENDING_FIRST_LAUNCH,
        STAGED_UNTIL_IDLE,
        ROLLED_BACK
    }
}
