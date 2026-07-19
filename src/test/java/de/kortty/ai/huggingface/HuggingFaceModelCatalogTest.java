package de.kortty.ai.huggingface;

import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class HuggingFaceModelCatalogTest {

    private static final long GIB = 1024L * 1024L * 1024L;

    @Test
    void choosesDocumentedMemoryTiersAndEmbeddingDefault() {
        // The first candidate per role is the preferred default for the detected RAM tier.
        assertThat(firstFor(8 * GIB, HuggingFaceModelCatalog.Role.TEXT).modelId())
            .contains("Qwen3-1.7B");
        assertThat(firstFor(16 * GIB, HuggingFaceModelCatalog.Role.TEXT).modelId())
            .contains("Qwen3-4B");
        assertThat(firstFor(24 * GIB, HuggingFaceModelCatalog.Role.TEXT).modelId())
            .contains("Qwen3-8B");
        assertThat(firstFor(16 * GIB, HuggingFaceModelCatalog.Role.CODING).modelId())
            .contains("Coder-7B");
        // The small Qwen3 embedding model stays the default on every tier.
        assertThat(firstFor(8 * GIB, HuggingFaceModelCatalog.Role.EMBEDDING).modelId())
            .contains("Qwen3-Embedding-0.6B");
        assertThat(firstFor(24 * GIB, HuggingFaceModelCatalog.Role.EMBEDDING).modelId())
            .contains("Qwen3-Embedding-0.6B");
    }

    @Test
    void offersEmbeddingAlternativesScaledToMemory() {
        // Low-RAM machines still get the RAM-independent alternatives (bge-m3, nomic).
        assertThat(embeddingIdsFor(8 * GIB))
            .containsExactly("qwen3-embedding-0.6b-q8", "bge-m3-q8", "nomic-embed-text-1.5-q8")
            .inOrder();
        // Larger tiers unlock the bigger Qwen3 embedding models, ordered by preference.
        assertThat(embeddingIdsFor(16 * GIB))
            .containsExactly(
                "qwen3-embedding-0.6b-q8", "qwen3-embedding-4b-q4",
                "bge-m3-q8", "nomic-embed-text-1.5-q8")
            .inOrder();
        assertThat(embeddingIdsFor(24 * GIB))
            .containsExactly(
                "qwen3-embedding-0.6b-q8", "qwen3-embedding-4b-q4", "qwen3-embedding-8b-q4",
                "bge-m3-q8", "nomic-embed-text-1.5-q8")
            .inOrder();
    }

    private static HuggingFaceModelCatalog.Recommendation firstFor(
        long memoryBytes,
        HuggingFaceModelCatalog.Role role
    ) {
        return HuggingFaceModelCatalog.candidatesForMemory(memoryBytes).stream()
            .filter(value -> value.roles().contains(role))
            .max(java.util.Comparator.comparingInt(
                HuggingFaceModelCatalog.Recommendation::preference))
            .orElseThrow();
    }

    private static java.util.List<String> embeddingIdsFor(long memoryBytes) {
        return HuggingFaceModelCatalog.candidatesForMemory(memoryBytes).stream()
            .filter(value -> value.roles().contains(HuggingFaceModelCatalog.Role.EMBEDDING))
            .map(HuggingFaceModelCatalog.Recommendation::id)
            .toList();
    }

    @Test
    void hardwareEstimateIncludesApplicationAndKvHeadroom() {
        HuggingFaceHardwareEstimate estimate = HuggingFaceHardwareEstimator.estimate(4 * GIB, 8 * GIB);

        assertThat(estimate.estimatedWorkingSetBytes()).isGreaterThan(estimate.modelBytes());
        assertThat(estimate.suitability()).isNotEqualTo(HuggingFaceHardwareEstimate.Suitability.UNKNOWN);
    }
}
