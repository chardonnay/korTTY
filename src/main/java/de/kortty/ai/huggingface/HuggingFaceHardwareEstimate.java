package de.kortty.ai.huggingface;

/** Conservative local-memory estimate for loading and using a GGUF file. */
public record HuggingFaceHardwareEstimate(
    Suitability suitability,
    long modelBytes,
    long estimatedWorkingSetBytes,
    long availableMemoryBytes
) {
    public enum Suitability {
        COMFORTABLE,
        POSSIBLE,
        TOO_LARGE,
        UNKNOWN
    }
}
