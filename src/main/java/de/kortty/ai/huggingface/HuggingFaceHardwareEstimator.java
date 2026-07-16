package de.kortty.ai.huggingface;

/** Produces a simple, explainable estimate; it never claims that a GPU backend is available. */
public final class HuggingFaceHardwareEstimator {

    private static final long ONE_GIB = 1024L * 1024L * 1024L;

    private HuggingFaceHardwareEstimator() {
    }

    public static HuggingFaceHardwareEstimate estimate(long ggufBytes, long availableMemoryBytes) {
        if (ggufBytes <= 0 || availableMemoryBytes <= 0) {
            return new HuggingFaceHardwareEstimate(
                HuggingFaceHardwareEstimate.Suitability.UNKNOWN,
                ggufBytes,
                -1,
                availableMemoryBytes);
        }
        // Model mapping plus KV cache, graph buffers and JVM/application headroom.
        long overhead = Math.max(2 * ONE_GIB, Math.round(ggufBytes * 0.35d));
        long workingSet = saturatingAdd(ggufBytes, overhead);
        HuggingFaceHardwareEstimate.Suitability suitability;
        if (availableMemoryBytes >= saturatingAdd(workingSet, 2 * ONE_GIB)) {
            suitability = HuggingFaceHardwareEstimate.Suitability.COMFORTABLE;
        } else if (availableMemoryBytes >= workingSet) {
            suitability = HuggingFaceHardwareEstimate.Suitability.POSSIBLE;
        } else {
            suitability = HuggingFaceHardwareEstimate.Suitability.TOO_LARGE;
        }
        return new HuggingFaceHardwareEstimate(suitability, ggufBytes, workingSet, availableMemoryBytes);
    }

    private static long saturatingAdd(long first, long second) {
        try {
            return Math.addExact(first, second);
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }
}
