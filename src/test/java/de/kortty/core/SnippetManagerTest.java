package de.kortty.core;

import de.kortty.model.Snippet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class SnippetManagerTest {

    @TempDir
    Path tempDir;

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
        assertEquals("Bereinigt alte Logdateien", loaded.getDescription());
        assertEquals(List.of(loaded), reloaded.search("Logdateien"));
    }
}
