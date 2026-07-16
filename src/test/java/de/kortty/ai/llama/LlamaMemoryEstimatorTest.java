package de.kortty.ai.llama;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class LlamaMemoryEstimatorTest {

    @Test
    void combinesSelectedAndLoadedWeightsWithConservativeOverhead() throws Exception {
        Path root = Files.createTempDirectory("kortty-memory-estimator-");
        Path server = Files.writeString(root.resolve("llama-server"), "server");
        LlamaModel selected = model("selected", Files.write(root.resolve("selected.gguf"), new byte[60]), server);
        LlamaModel loaded = model("loaded", Files.write(root.resolve("loaded.gguf"), new byte[40]), server);
        LlamaRuntimeManager.RuntimeStatus loadedStatus = new LlamaRuntimeManager.RuntimeStatus(
            loaded.getId(), LlamaRuntimeState.READY, 0, URI.create("http://127.0.0.1:1234/v1/chat/completions"), null);

        LlamaMemoryEstimator.Estimate estimate = new LlamaMemoryEstimator().estimate(
            List.of(selected), List.of(selected, loaded), Map.of(loaded.getId(), loadedStatus), 160);

        assertThat(estimate.ggufWeightBytes()).isEqualTo(100);
        assertThat(estimate.estimatedRuntimeBytes()).isEqualTo(135);
        assertThat(estimate.runtimeCount()).isEqualTo(2);
        assertThat(estimate.warningRecommended()).isTrue();
    }

    @Test
    void doesNotDoubleCountProfilesSharingOneRuntimeConfiguration() throws Exception {
        Path root = Files.createTempDirectory("kortty-memory-estimator-shared-");
        Path server = Files.writeString(root.resolve("llama-server"), "server");
        Path gguf = Files.write(root.resolve("shared.gguf"), new byte[100]);
        LlamaModel first = model("first", gguf, server);
        LlamaModel alias = model("alias", gguf, server);
        LlamaRuntimeManager.RuntimeStatus loadedStatus = new LlamaRuntimeManager.RuntimeStatus(
            first.getId(), LlamaRuntimeState.BUSY, 1, URI.create("http://127.0.0.1:1234/v1/chat/completions"), null);

        LlamaMemoryEstimator.Estimate estimate = new LlamaMemoryEstimator().estimate(
            List.of(alias), List.of(first, alias), Map.of(first.getId(), loadedStatus), 1024);

        assertThat(estimate.ggufWeightBytes()).isEqualTo(100);
        assertThat(estimate.runtimeCount()).isEqualTo(1);
        assertThat(estimate.warningRecommended()).isFalse();
    }

    private static LlamaModel model(String id, Path gguf, Path server) {
        return new LlamaModel(id, id, gguf, server);
    }
}
