package de.kortty.ai.llama;

import java.nio.file.Path;
import org.testng.annotations.Test;

import static com.google.common.truth.Truth.assertThat;

class LlamaRuntimeBackendMismatchTest {

    @Test
    void namesAGpuModelThatRunsOnTheCpuPackage() {
        // The reported case: a VULKAN model on a Windows machine whose AUTO runtime is the CPU build.
        assertThat(LlamaRuntimeManager.backendMismatch(model(LlamaBackend.VULKAN), LlamaBackend.CPU))
            .hasValue("Local model qwen is configured for VULKAN, but the active llama.cpp runtime package "
                + "is the CPU build; the model runs without VULKAN acceleration. Install the VULKAN "
                + "runtime in AI Manager > Local Models.");
    }

    @Test
    void staysQuietWhenThePackageFitsOrIsExternal() {
        assertThat(LlamaRuntimeManager.backendMismatch(model(LlamaBackend.VULKAN), LlamaBackend.VULKAN)).isEmpty();
        assertThat(LlamaRuntimeManager.backendMismatch(model(LlamaBackend.CPU), LlamaBackend.VULKAN)).isEmpty();
        assertThat(LlamaRuntimeManager.backendMismatch(model(LlamaBackend.AUTO), LlamaBackend.CPU)).isEmpty();
        // A user-managed llama-server has no package descriptor to compare with.
        assertThat(LlamaRuntimeManager.backendMismatch(model(LlamaBackend.VULKAN), null)).isEmpty();
    }

    private static LlamaModel model(LlamaBackend backend) {
        return new LlamaModel("qwen", "Qwen", Path.of("qwen.gguf"), Path.of("llama-server"),
            backend, LlamaModelPurpose.CHAT, 4096, 0, -1, 15);
    }
}
