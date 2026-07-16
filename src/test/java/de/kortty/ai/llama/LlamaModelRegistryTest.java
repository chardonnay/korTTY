package de.kortty.ai.llama;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class LlamaModelRegistryTest {

    @Test
    void persistsValidatedModelsAndReloadsThem() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-registry-");
        Path registryFile = directory.resolve("models.xml");
        LlamaModelRegistry registry = new LlamaModelRegistry(registryFile);
        LlamaModel model = new LlamaModel(
            "qwen-local",
            "Qwen Local",
            directory.resolve("qwen.gguf"),
            directory.resolve("llama-server"),
            LlamaBackend.METAL,
            8192,
            8,
            48,
            900);

        registry.register(model);
        LlamaModelRegistry reloaded = new LlamaModelRegistry(registryFile);

        assertThat(reloaded.list()).containsExactly(model);
        assertThat(reloaded.find(" qwen-local ")).hasValue(model);
        assertThat(Files.readString(registryFile)).contains("<llamaModels>");
        assertThat(Files.readString(registryFile)).contains("<schemaVersion>1</schemaVersion>");
    }

    @Test
    void replacesByIdAndRemoveDoesNotDeleteModelFile() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-registry-");
        Path gguf = Files.writeString(directory.resolve("model.gguf"), "gguf");
        Path executable = Files.writeString(directory.resolve("llama-server"), "binary");
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(directory);
        registry.register(new LlamaModel("model", "First", gguf, executable));
        registry.register(new LlamaModel("model", "Replacement", gguf, executable));

        assertThat(registry.list()).hasSize(1);
        assertThat(registry.find("model").orElseThrow().getDisplayName()).isEqualTo("Replacement");
        assertThat(registry.remove("model")).isPresent();
        assertThat(Files.exists(gguf)).isTrue();
        assertThat(new LlamaModelRegistry(directory.resolve("models.xml")).list()).isEmpty();
    }

    @Test
    void rejectsMalformedOrUnsafeRegistryData() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-registry-");
        Path registryFile = Files.writeString(
            directory.resolve("models.xml"),
            "<?xml version=\"1.0\"?><!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><llamaModels><model><id>&e;</id></model></llamaModels>");

        expectThrows(LlamaRegistryException.class, () -> new LlamaModelRegistry(registryFile));
        expectThrows(
            IllegalArgumentException.class,
            () -> new LlamaModel("unsafe id", "Unsafe", directory.resolve("x.gguf"), directory.resolve("server")));
    }

    @Test
    void acceptsNeverOrOneTo1440IdleMinutesAndRejectsValuesOutsideThatRange() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-timeout-");
        Path modelPath = directory.resolve("model.gguf");
        Path executable = directory.resolve("llama-server");

        assertThat(modelWithIdle("never", modelPath, executable, 0).getIdleTimeoutMinutes()).isEqualTo(0);
        assertThat(modelWithIdle("minimum", modelPath, executable, 1).getIdleTimeoutMinutes()).isEqualTo(1);
        assertThat(modelWithIdle("maximum", modelPath, executable, 1440).getIdleTimeoutMinutes()).isEqualTo(1440);
        expectThrows(IllegalArgumentException.class, () -> modelWithIdle("negative", modelPath, executable, -1));
        expectThrows(IllegalArgumentException.class, () -> modelWithIdle("too-high", modelPath, executable, 1441));
    }

    @Test
    void atomicallyRebindsEveryModelToTheActiveRuntime() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-registry-rebind-");
        Path previous = directory.resolve("old-llama-server");
        Path active = directory.resolve("active-llama-server");
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(directory);
        registry.register(new LlamaModel("text", "Text", directory.resolve("text.gguf"), previous));
        registry.register(new LlamaModel("coding", "Coding", directory.resolve("coding.gguf"), previous));

        registry.replaceServerExecutableForAll(active);

        LlamaModelRegistry reloaded = LlamaModelRegistry.inDirectory(directory);
        assertThat(reloaded.list()).hasSize(2);
        assertThat(reloaded.list().stream().map(LlamaModel::getServerExecutable).distinct().toList())
            .containsExactly(active.toAbsolutePath().normalize());
        assertThat(reloaded.find("text").orElseThrow().getDisplayName()).isEqualTo("Text");
        assertThat(reloaded.find("coding").orElseThrow().getDisplayName()).isEqualTo("Coding");
    }

    @Test
    void staleRegistryInstancesMergeMutationsAndRebindTheLatestCatalog() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-registry-shared-lock-");
        Path previous = directory.resolve("old-llama-server");
        Path active = directory.resolve("active-llama-server");
        LlamaModelRegistry runtimeRegistry = LlamaModelRegistry.inDirectory(directory);
        LlamaModelRegistry uiRegistry = LlamaModelRegistry.inDirectory(directory);

        runtimeRegistry.register(new LlamaModel(
            "text", "Text", directory.resolve("text.gguf"), previous));
        uiRegistry.register(new LlamaModel(
            "coding", "Coding", directory.resolve("coding.gguf"), previous));
        runtimeRegistry.replaceServerExecutableForAll(active);

        LlamaModelRegistry reloaded = LlamaModelRegistry.inDirectory(directory);
        assertThat(reloaded.list().stream().map(LlamaModel::getId).toList())
            .containsExactly("text", "coding");
        assertThat(reloaded.list().stream().map(LlamaModel::getServerExecutable).distinct().toList())
            .containsExactly(active.toAbsolutePath().normalize());
    }

    @Test
    void rollbackBindingsPreservesModelsAddedAfterRuntimeRebind() throws Exception {
        Path directory = Files.createTempDirectory("kortty-llama-registry-binding-rollback-");
        Path previous = directory.resolve("old-llama-server");
        Path active = directory.resolve("active-llama-server");
        LlamaModelRegistry runtimeRegistry = LlamaModelRegistry.inDirectory(directory);
        LlamaModelRegistry uiRegistry = LlamaModelRegistry.inDirectory(directory);
        runtimeRegistry.register(new LlamaModel(
            "text", "Text", directory.resolve("text.gguf"), previous));

        java.util.Map<String, Path> bindings = runtimeRegistry.replaceServerExecutableForAll(active);
        uiRegistry.register(new LlamaModel(
            "coding", "Coding", directory.resolve("coding.gguf"), previous));
        runtimeRegistry.restoreServerExecutables(bindings, active);

        LlamaModelRegistry reloaded = LlamaModelRegistry.inDirectory(directory);
        assertThat(reloaded.find("text").orElseThrow().getServerExecutable())
            .isEqualTo(previous.toAbsolutePath().normalize());
        assertThat(reloaded.find("coding").orElseThrow().getServerExecutable())
            .isEqualTo(previous.toAbsolutePath().normalize());
    }

    private static LlamaModel modelWithIdle(String id, Path modelPath, Path executable, int minutes) {
        return new LlamaModel(
            id,
            id,
            modelPath,
            executable,
            LlamaBackend.AUTO,
            LlamaModel.DEFAULT_CONTEXT_SIZE,
            LlamaModel.AUTO_THREADS,
            LlamaModel.AUTO_GPU_LAYERS,
            minutes);
    }
}
