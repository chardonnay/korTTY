package de.kortty.ai.runtimeupdate;

/**
 * @param failureDetail why the health check rejected the package, for {@link Status#ROLLED_BACK}
 *                      results whose check could tell; {@code null} otherwise
 */
public record LlamaRuntimeActivationResult(
    Status status,
    LlamaRuntimeInstallation installation,
    LlamaRuntimeInstallation previousInstallation,
    String failureDetail
) {
    public LlamaRuntimeActivationResult(
        Status status,
        LlamaRuntimeInstallation installation,
        LlamaRuntimeInstallation previousInstallation
    ) {
        this(status, installation, previousInstallation, null);
    }

    public enum Status {
        ACTIVATED,
        ALREADY_ACTIVE,
        STAGED_UNTIL_IDLE,
        ROLLED_BACK
    }
}
