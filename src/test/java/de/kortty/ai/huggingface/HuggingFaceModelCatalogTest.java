package de.kortty.ai.huggingface;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class HuggingFaceModelCatalogTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void choosesDocumentedMemoryTiersAndEmbeddingDefault() {
        assertThat(HuggingFaceModelCatalog.defaultsForMemory(8 * GIB).get(0).modelId())
            .contains("Qwen3-1.7B");
        assertThat(HuggingFaceModelCatalog.defaultsForMemory(16 * GIB).get(0).modelId())
            .contains("Qwen3-4B");
        assertThat(HuggingFaceModelCatalog.defaultsForMemory(24 * GIB).get(0).modelId())
            .contains("Qwen3-8B");
        assertThat(HuggingFaceModelCatalog.defaultsForMemory(16 * GIB).get(1).modelId())
            .contains("Coder-7B");
        assertThat(HuggingFaceModelCatalog.defaultsForMemory(8 * GIB)).hasSize(2);
        assertThat(HuggingFaceModelCatalog.defaultsForMemory(8 * GIB).get(1).roles())
            .containsExactly(HuggingFaceModelCatalog.Role.EMBEDDING);
    }

    @Test
    void hardwareEstimateIncludesApplicationAndKvHeadroom() {
        HuggingFaceHardwareEstimate estimate = HuggingFaceHardwareEstimator.estimate(4 * GIB, 8 * GIB);

        assertThat(estimate.estimatedWorkingSetBytes()).isGreaterThan(estimate.modelBytes());
        assertThat(estimate.suitability()).isNotEqualTo(HuggingFaceHardwareEstimate.Suitability.UNKNOWN);
    }
}
