package de.kortty.ai.mlx;

import org.testng.annotations.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static com.google.common.truth.Truth.assertThat;
import static org.testng.Assert.expectThrows;

class MlxModelRegistryTest {

    @Test
    void persistsValidatedModelsAndReloadsThem() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-registry-");
        Path registryFile = directory.resolve("mlx-models.json");
        MlxModelRegistry registry = new MlxModelRegistry(registryFile);
        MlxModel model = new MlxModel(
            "qwen-mlx",
            "Qwen MLX",
            directory.resolve("qwen-mlx"),
            8192,
            900,
            "4bit");

        registry.register(model);
        MlxModelRegistry reloaded = new MlxModelRegistry(registryFile);

        assertThat(reloaded.list()).containsExactly(model);
        assertThat(reloaded.find(" qwen-mlx ")).hasValue(model);
        assertThat(reloaded.find("qwen-mlx").orElseThrow().getQuantizationLabel()).isEqualTo("4bit");
        assertThat(Files.readString(registryFile)).contains("\"schemaVersion\": 1");
        assertThat(Files.readString(registryFile)).contains("\"id\": \"qwen-mlx\"");
    }

    @Test
    void replacesByIdAndRemoveDoesNotDeleteModelDirectory() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-registry-");
        Path modelDirectory = Files.createDirectories(directory.resolve("model"));
        MlxModelRegistry registry = MlxModelRegistry.inDirectory(directory);
        registry.register(new MlxModel("model", "First", modelDirectory));
        registry.register(new MlxModel("model", "Replacement", modelDirectory));

        assertThat(registry.list()).hasSize(1);
        assertThat(registry.find("model").orElseThrow().getDisplayName()).isEqualTo("Replacement");
        assertThat(registry.remove("model")).isPresent();
        assertThat(Files.isDirectory(modelDirectory)).isTrue();
        assertThat(new MlxModelRegistry(directory.resolve("mlx-models.json")).list()).isEmpty();
    }

    @Test
    void mergesMutationsFromSeparateRegistryInstances() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-registry-shared-lock-");
        MlxModelRegistry first = MlxModelRegistry.inDirectory(directory);
        MlxModelRegistry second = MlxModelRegistry.inDirectory(directory);

        first.register(new MlxModel("text", "Text", directory.resolve("text-model")));
        second.register(new MlxModel("coding", "Coding", directory.resolve("coding-model")));
        second.remove("text");
        first.reload();

        assertThat(first.list().stream().map(MlxModel::getId).toList()).containsExactly("coding");
        assertThat(MlxModelRegistry.inDirectory(directory).find("coding")).isPresent();
    }

    @Test
    void rejectsMalformedOrUnsafeRegistryData() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-registry-");
        Path registryFile = directory.resolve("mlx-models.json");

        Files.writeString(registryFile, "{not valid json");
        expectThrows(MlxRegistryException.class, () -> new MlxModelRegistry(registryFile));

        Files.writeString(registryFile, "{\"schemaVersion\": 1, \"models\": [{\"id\": 42}]}");
        expectThrows(MlxRegistryException.class, () -> new MlxModelRegistry(registryFile));

        Files.writeString(registryFile, "{\"schemaVersion\": 999, \"models\": []}");
        MlxRegistryException tooNew = expectThrows(
            MlxRegistryException.class,
            () -> new MlxModelRegistry(registryFile));
        assertThat(tooNew).hasMessageThat().contains("newer");

        expectThrows(
            IllegalArgumentException.class,
            () -> new MlxModel("unsafe id", "Unsafe", directory.resolve("model")));
        expectThrows(
            IllegalArgumentException.class,
            () -> new MlxModel("no-directory", "No Directory", null));
    }

    @Test
    void rejectsDuplicateModelIdsInThePersistedRegistry() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-registry-");
        Path registryFile = directory.resolve("mlx-models.json");
        Files.writeString(registryFile, """
            {
              "schemaVersion": 1,
              "models": [
                {"id": "dup", "modelDirectory": "%s"},
                {"id": "dup", "modelDirectory": "%s"}
              ]
            }
            """.formatted(directory.resolve("first"), directory.resolve("second")));

        MlxRegistryException duplicate = expectThrows(
            MlxRegistryException.class,
            () -> new MlxModelRegistry(registryFile));
        assertThat(duplicate).hasMessageThat().contains("Duplicate");
    }

    @Test
    void acceptsNeverOrOneTo1440IdleMinutesAndRejectsValuesOutsideThatRange() throws Exception {
        Path directory = Files.createTempDirectory("kortty-mlx-timeout-");
        Path modelDirectory = directory.resolve("model");

        assertThat(modelWithIdle("never", modelDirectory, 0).getIdleTimeoutMinutes()).isEqualTo(0);
        assertThat(modelWithIdle("minimum", modelDirectory, 1).getIdleTimeoutMinutes()).isEqualTo(1);
        assertThat(modelWithIdle("maximum", modelDirectory, 1440).getIdleTimeoutMinutes()).isEqualTo(1440);
        expectThrows(IllegalArgumentException.class, () -> modelWithIdle("negative", modelDirectory, -1));
        expectThrows(IllegalArgumentException.class, () -> modelWithIdle("too-high", modelDirectory, 1441));
    }

    private static MlxModel modelWithIdle(String id, Path modelDirectory, int minutes) {
        return new MlxModel(id, id, modelDirectory, MlxModel.MODEL_DEFAULT_CONTEXT_SIZE, minutes, null);
    }
}
