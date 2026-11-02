package de.kortty.memory;

import de.kortty.memory.MemoryStore.WriteReason;
import de.kortty.model.AiTokenizerType;
import de.kortty.policy.UserMemoryMode;
import org.testng.annotations.AfterMethod;
import org.testng.annotations.BeforeMethod;
import org.testng.annotations.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.Comparator;
import java.util.stream.Stream;

import static com.google.common.truth.Truth.assertThat;

class MemoryDocumentCacheTest {

    private Path root;
    private MemoryStore store;
    private MemoryService memory;

    @BeforeMethod
    void createService() throws IOException {
        root = Files.createTempDirectory("kortty-memory-cache-");
        store = new MemoryStore(root.resolve("memory"));
        memory = new MemoryService(store, name -> null);
        memory.setAiMemoryAllowedSupplier(() -> true);
        memory.setUserMemoryModeSupplier(() -> UserMemoryMode.EDITABLE);
    }

    @AfterMethod(alwaysRun = true)
    void deleteFiles() throws IOException {
        if (root == null) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        }
    }

    @Test
    void aFileRestoredWithTheSameSizeAndTimeIsSentWithItsNewContent() throws IOException {
        MemoryScope global = MemoryScope.global();
        store.write(global, MemoryFormat.sha256(""), "Rule: answer briefly\n", WriteReason.USER_SAVE, null);
        assertThat(memory.blockFor(null, AiTokenizerType.ESTIMATE, 1500).text()).contains("briefly");

        Path file = store.fileOf(global);
        FileTime modified = Files.getLastModifiedTime(file);
        Files.writeString(file, "Rule: answer quietly\n");
        Files.setLastModifiedTime(file, modified);

        String block = memory.blockFor(null, AiTokenizerType.ESTIMATE, 1500).text();
        assertThat(block).contains("quietly");
        assertThat(block).doesNotContain("briefly");
    }
}
