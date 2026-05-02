package de.kortty.core;

import de.kortty.model.Snippet;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import static com.google.common.truth.Truth.assertThat;


class SnippetManagerTest {

    Path tempDir;

    @BeforeMethod
    void createTempDir() throws IOException {
        tempDir = Files.createTempDirectory("kortty-snippet-manager-test");
    }

    @AfterMethod
    void deleteTempDir() throws IOException {
        if (tempDir == null || !Files.exists(tempDir)) {
            return;
        }
        try (var paths = Files.walk(tempDir)) {
            paths.sorted(Comparator.reverseOrder())
                .forEach(path -> {
                    try {
                        Files.deleteIfExists(path);
                    } catch (IOException e) {
                        throw new IllegalStateException("Failed to delete temp path " + path, e);
                    }
                });
        }
    }

    @Test
    void saveLoadAndSearchPreserveSnippetDescription() throws Exception {
        SnippetManager manager = new SnippetManager(tempDir);
        Snippet snippet = new Snippet("cleanup.sh", "echo ok", "bash");
        snippet.setDescription("Bereinigt alte Logdateien");
        manager.addSnippet(snippet);
        manager.save();

        SnippetManager reloaded = new SnippetManager(tempDir);
        reloaded.load();

        Snippet loaded = reloaded.getAllSnippets().getFirst();
        assertThat(loaded.getDescription()).isEqualTo("Bereinigt alte Logdateien");
        assertThat(reloaded.search("Logdateien")).isEqualTo(List.of(loaded));
    }
}
