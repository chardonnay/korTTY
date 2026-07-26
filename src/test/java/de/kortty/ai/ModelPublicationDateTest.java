package de.kortty.ai;

import de.kortty.ai.llama.LlamaModel;
import de.kortty.ai.llama.LlamaModelRegistry;
import de.kortty.ai.mlx.MlxModel;
import de.kortty.ai.mlx.MlxModelRegistry;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;

import static com.google.common.truth.Truth.assertThat;

/**
 * The upstream publication date recorded when a model is installed.
 *
 * <p>It has to be captured at install time and persisted: once the files are on disk nothing about
 * them reveals when the model was released, and a file timestamp would only say when this machine
 * downloaded it.
 */
class ModelPublicationDateTest {

    private static final Instant PUBLISHED = Instant.parse("2025-03-11T08:30:00Z");

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-model-published-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        }
    }

    // ------------------------------------------------------------------ llama

    private LlamaModel llamaModel() throws IOException {
        Path weights = tempDir.resolve("model.gguf");
        Files.writeString(weights, "x", StandardCharsets.UTF_8);
        Path server = tempDir.resolve("server");
        Files.writeString(server, "x", StandardCharsets.UTF_8);
        return new LlamaModel("m1", "Model One", weights, server);
    }

    @Test
    void aLlamaPublicationDateSurvivesRegistryReload() throws IOException {
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(tempDir);
        registry.register(llamaModel().withPublishedAt(PUBLISHED));

        LlamaModelRegistry reloaded = LlamaModelRegistry.inDirectory(tempDir);
        reloaded.reload();
        assertThat(reloaded.list()).hasSize(1);
        assertThat(reloaded.list().getFirst().getPublishedAt()).isEqualTo(PUBLISHED.toString());
    }

    /** Models installed before this was tracked simply have no date, and must still load. */
    @Test
    void aLlamaModelWithoutAPublicationDateStillLoads() throws IOException {
        LlamaModelRegistry registry = LlamaModelRegistry.inDirectory(tempDir);
        registry.register(llamaModel());

        LlamaModelRegistry reloaded = LlamaModelRegistry.inDirectory(tempDir);
        reloaded.reload();
        assertThat(reloaded.list()).hasSize(1);
        assertThat(reloaded.list().getFirst().getPublishedAt()).isNull();
    }

    @Test
    void copyingALlamaModelKeepsItsPublicationDate() throws IOException {
        LlamaModel original = llamaModel().withPublishedAt(PUBLISHED);
        assertThat(new LlamaModel(original).getPublishedAt()).isEqualTo(PUBLISHED.toString());
    }

    // -------------------------------------------------------------------- mlx

    private MlxModel mlxModel() throws IOException {
        Path directory = tempDir.resolve("mlx-model");
        Files.createDirectories(directory);
        return new MlxModel("m2", "Model Two", directory);
    }

    @Test
    void anMlxPublicationDateSurvivesRegistryReload() throws IOException {
        MlxModelRegistry registry = MlxModelRegistry.inDirectory(tempDir);
        registry.register(mlxModel().withPublishedAt(PUBLISHED));

        MlxModelRegistry reloaded = MlxModelRegistry.inDirectory(tempDir);
        reloaded.reload();
        assertThat(reloaded.list()).hasSize(1);
        assertThat(reloaded.list().getFirst().getPublishedAt()).isEqualTo(PUBLISHED.toString());
    }

    @Test
    void anMlxModelWithoutAPublicationDateStillLoads() throws IOException {
        MlxModelRegistry registry = MlxModelRegistry.inDirectory(tempDir);
        registry.register(mlxModel());

        MlxModelRegistry reloaded = MlxModelRegistry.inDirectory(tempDir);
        reloaded.reload();
        assertThat(reloaded.list()).hasSize(1);
        assertThat(reloaded.list().getFirst().getPublishedAt()).isNull();
    }

    /** A corrupt date must not take the whole registry entry down with it. */
    @Test
    void anUnparsableMlxDateIsDroppedRatherThanFailingTheLoad() throws IOException {
        MlxModelRegistry registry = MlxModelRegistry.inDirectory(tempDir);
        registry.register(mlxModel().withPublishedAt(PUBLISHED));

        Path file = tempDir.resolve(MlxModelRegistry.REGISTRY_FILE_NAME);
        Files.writeString(file,
            Files.readString(file, StandardCharsets.UTF_8)
                .replace(PUBLISHED.toString(), "not-a-date"),
            StandardCharsets.UTF_8);

        MlxModelRegistry reloaded = MlxModelRegistry.inDirectory(tempDir);
        reloaded.reload();
        assertThat(reloaded.list()).hasSize(1);
        assertThat(reloaded.list().getFirst().getPublishedAt()).isNull();
    }

    @Test
    void clearingThePublicationDateIsPossible() throws IOException {
        MlxModel dated = mlxModel().withPublishedAt(PUBLISHED);
        assertThat(dated.withPublishedAt(null).getPublishedAt()).isNull();
    }
}
