package de.kortty.ai.llama;

import java.nio.file.Path;
import java.util.List;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class LlamaRuntimeBackendCompatibilityTest {

    @Test
    void requiresMatchingGpuRuntimeButAllowsAutoAndCpuModelsOnGpuRuntime() {
        LlamaModel vulkan = model("vulkan", LlamaBackend.VULKAN);
        LlamaModel cpu = model("cpu", LlamaBackend.CPU);
        LlamaModel automatic = model("auto", LlamaBackend.AUTO);

        assertThat(LlamaRuntimeBackendCompatibility.evaluate(LlamaBackend.CPU, List.of(vulkan)).status())
            .isEqualTo(LlamaRuntimeBackendCompatibility.Status.REQUIRES_DIFFERENT_RUNTIME);
        assertThat(LlamaRuntimeBackendCompatibility.evaluate(LlamaBackend.CPU, List.of(vulkan)).requiredBackend())
            .isEqualTo(LlamaBackend.VULKAN);
        assertThat(LlamaRuntimeBackendCompatibility.evaluate(
            LlamaBackend.VULKAN, List.of(vulkan, cpu, automatic)).status())
            .isEqualTo(LlamaRuntimeBackendCompatibility.Status.COMPATIBLE);
    }

    @Test
    void rejectsOneSelectionWithConflictingGpuBackends() {
        assertThat(LlamaRuntimeBackendCompatibility.evaluate(
            LlamaBackend.CPU,
            List.of(model("metal", LlamaBackend.METAL), model("vulkan", LlamaBackend.VULKAN))).status())
            .isEqualTo(LlamaRuntimeBackendCompatibility.Status.CONFLICTING_MODEL_BACKENDS);
    }

    private static LlamaModel model(String id, LlamaBackend backend) {
        return new LlamaModel(
            id, id, Path.of(id + ".gguf"), Path.of("llama-server"), backend,
            LlamaModel.DEFAULT_CONTEXT_SIZE, LlamaModel.AUTO_THREADS,
            LlamaModel.AUTO_GPU_LAYERS, LlamaModel.DEFAULT_IDLE_TIMEOUT_MINUTES);
    }
}
