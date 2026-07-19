package de.kortty.ai.runtimeupdate;

public record LlamaRuntimeActivationResult(
    Status status,
    LlamaRuntimeInstallation installation,
    LlamaRuntimeInstallation previousInstallation
) {
    public enum Status {
        ACTIVATED,
        ALREADY_ACTIVE,
        STAGED_UNTIL_IDLE,
        ROLLED_BACK
    }
}
